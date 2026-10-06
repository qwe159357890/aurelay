package com.devindeed.aurelay

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit

/**
 * 中转客户端（手机放音在蜂窝网络下的链路）
 *
 * 蜂窝网络下手机没有公网入站能力（移动实测封入站），无法被动等待电脑连接，
 * 因此改为**主动出站**连到中心服务器，由服务器把 PC 推来的音频字节原样转发过来。
 * 出站长连接一旦建立，入站限制、双卡选卡、运营商 IPv6 策略、CGNAT 全部不再影响链路。
 *
 * **传输方式：WebSocket，复用中心服务器的 15151 端口**（服务器只开放了这一个端口）。
 * 音频本身已经是 Opus 压缩后的字节，服务器不解包、不转码，只是原样转发。
 *
 * 协议：
 * - 首帧（文本）：`{"type":"hello","device_id":"","token":"","role":"R"|"S"}`
 * - 控制帧（文本）：`ready` / `error` / `ping` ↔ `pong`
 * - 音频帧（二进制）：原样透传，服务器转发给同一设备的对端角色
 *
 * 使用方式：调用 `start()` 后，通过 `audioInput()` 拿到一条连续的音频输入流，
 * 交给 AudioRelayService 现有的流解析逻辑（与局域网直连完全同一套代码）。
 *
 * 心跳 30 秒一次文本 ping；断线按 1/2/4/8/16/30 秒退避重连。
 */
class RelayClient {

    /**
     * 连接状态
     */
    enum class State {
        // 未连接
        IDLE,
        // 正在连接或重连
        CONNECTING,
        // 已就绪（收到 ready，可收发数据）
        READY,
        // 出错（握手被拒或网络异常）
        ERROR
    }

    /**
     * 状态与数据回调
     */
    interface Listener {
        /**
         * 状态变化
         *
         * :param state: 新状态
         * :param detail: 说明文本（供界面与日志使用）
         */
        fun onStateChanged(state: State, detail: String)

        /**
         * 收到对端发来的音频数据
         *
         * :param size: 本次收到的字节数
         */
        fun onAudioData(size: Int)
    }

    // 监听器
    var listener: Listener? = null

    // 中转服务器地址（形如 host:15151）
    private var serverText: String = ""

    // 完整的 WebSocket 地址
    private var url: String = ""

    // 设备标识与令牌
    private var deviceId: String = ""
    private var token: String = ""

    // 角色
    private var role: Byte = RelayProtocol.ROLE_RECEIVER

    // 运行控制
    @Volatile private var running = false

    // OkHttp 客户端与当前 WebSocket
    private var httpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null

    // 心跳线程
    private var pingThread: Thread? = null

    // 重连线程（避免并发重连）
    private var retryThread: Thread? = null

    // 管道：把收到的音频数据转成一条 InputStream 供上层解析
    private var pipeOut: PipedOutputStream? = null
    private var pipeIn: PipedInputStream? = null

    // 最近一次收到数据的时间（供界面显示心跳新鲜度）
    @Volatile private var lastAliveAt = 0L

    /**
     * 启动中转连接（已在运行时忽略）
     *
     * :param context: 任意上下文（用于读取中转服务器地址与令牌）
     * :param role: 角色，见 RelayProtocol 的 ROLE_*
     * :param audioPort: 本机音频端口（接收方为 5000，仅用于日志）
     * :return: 无返回值
     */
    @Synchronized
    fun start(context: Context, role: Byte, audioPort: Int) {
        if (running) return
        val app = context.applicationContext
        serverText = AppPrefs.getString(app, AppPrefs.KEY_RELAY_SERVER, AppPrefs.DEFAULT_RELAY_SERVER)
        val scheme = if (serverText.startsWith("ws://") || serverText.startsWith("wss://")) "" else "ws://"
        url = "$scheme$serverText${AppPrefs.RELAY_PATH}"
        deviceId = AddressReporter.getOrCreateDeviceId(app)
        token = AppPrefs.getString(app, AppPrefs.KEY_REPORT_TOKEN, AppPrefs.DEFAULT_REPORT_TOKEN)
        this.role = role
        running = true

        if (pipeIn == null) {
            val out = PipedOutputStream()
            val ins = PipedInputStream(out, 256 * 1024)
            pipeOut = out
            pipeIn = ins
        }

        httpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        pingThread = Thread({ pingLoop() }, "AurelayRelayPing").also { it.start() }
        connectOnce()
        DiagLog.i("中转", "中转客户端启动：$url，角色=${role.toInt().toChar()}，本机端口=$audioPort")
    }

    /**
     * 停止中转连接并释放管道
     *
     * :return: 无返回值
     */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        closeWebSocket()
        try {
            pipeOut?.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        pipeOut = null
        pipeIn = null
        httpClient = null
        DiagLog.i("中转", "中转客户端已停止")
        notifyState(State.IDLE, "已停止")
    }

    /**
     * 取音频输入流（供上层用现有逻辑解析 AURL 流）
     *
     * :return: 音频输入流；未启动时返回 null
     */
    fun audioInput(): InputStream? = pipeIn

    /**
     * 取最近一次收到数据的时间戳（供界面显示心跳新鲜度）
     *
     * :return: 毫秒时间戳；从未收到时返回 0
     */
    fun lastAliveAt(): Long = lastAliveAt

    /**
     * 主动发送音频数据（电脑放音经中转时使用）
     *
     * :param data: 待发送的数据
     * :param length: 有效长度
     * :return: true 表示写入成功
     */
    @Suppress("DEPRECATION")
    fun sendAudio(data: ByteArray, length: Int): Boolean {
        return try {
            val socket = webSocket ?: return false
            val chunk = data.copyOfRange(0, length.coerceAtMost(data.size))
            val packet = okio.ByteString.of(chunk, 0, chunk.size)
            socket.send(packet)
            true
        } catch (e: Exception) {
            DiagLog.e("中转", "发送音频数据失败", e)
            false
        }
    }

    /**
     * 建立一次 WebSocket 连接（失败由回调里的重连逻辑兜住）
     *
     * :return: 无返回值
     */
    private fun connectOnce() {
        val client = httpClient ?: return
        notifyState(State.CONNECTING, "正在连接中转服务器")
        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(socket: WebSocket, response: Response) {
                webSocket = socket
                lastAliveAt = System.currentTimeMillis()
                DiagLog.i("中转", "已连上中转服务器")
                val hello = JSONObject()
                hello.put("type", "hello")
                hello.put("device_id", deviceId)
                hello.put("token", token)
                hello.put("role", if (role == RelayProtocol.ROLE_RECEIVER) "R" else "S")
                socket.send(hello.toString())
            }

            override fun onMessage(socket: WebSocket, bytes: okio.ByteString) {
                lastAliveAt = System.currentTimeMillis()
                try {
                    val data = bytes.toByteArray()
                    pipeOut?.write(data)
                    pipeOut?.flush()
                    listener?.onAudioData(data.size)
                } catch (e: Exception) {
                    DiagLog.e("中转", "写入音频管道失败", e)
                }
            }

            override fun onMessage(socket: WebSocket, text: String) {
                lastAliveAt = System.currentTimeMillis()
                handleControl(text)
            }

            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                DiagLog.e("中转", "中转连接异常：${t.message}", t)
                notifyState(State.ERROR, "连接异常：${t.message ?: "未知"}")
                webSocket = null
                scheduleReconnect()
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                DiagLog.i("中转", "中转连接已关闭：code=$code reason=$reason")
                webSocket = null
                scheduleReconnect()
            }
        })
    }

    /**
     * 处理文本控制消息（ready / error / pong）
     *
     * :param text: 控制消息文本
     * :return: 无返回值
     */
    private fun handleControl(text: String) {
        try {
            val obj = JSONObject(text)
            when (obj.optString("type")) {
                "ready" -> {
                    notifyState(State.READY, "已接入中转服务器")
                    DiagLog.i("中转", "收到 ready，中转链路就绪")
                }
                "error" -> {
                    val code = obj.optInt("code", -1)
                    val message = obj.optString("message", "")
                    notifyState(State.ERROR, "错误 $code：$message")
                    DiagLog.e("中转", "服务器返回错误：code=$code message=$message")
                }
                "pong" -> {
                    // 心跳应答，lastAliveAt 已在入口刷新
                }
                else -> {
                    DiagLog.d("中转", "忽略未知控制消息：$text")
                }
            }
        } catch (e: Exception) {
            DiagLog.w("中转", "解析控制消息失败：$text")
        }
    }

    /**
     * 断线后按退避策略重连（1/2/4/8/16/30 秒）
     *
     * :return: 无返回值
     */
    private fun scheduleReconnect() {
        if (!running) return
        if (retryThread != null && retryThread!!.isAlive) return
        retryThread = Thread({
            var attempt = 0
            while (running && webSocket == null) {
                val delay =
                    RelayProtocol.BACKOFF_MS[attempt.coerceAtMost(RelayProtocol.BACKOFF_MS.size - 1)]
                attempt++
                try {
                    Thread.sleep(delay)
                } catch (e: InterruptedException) {
                    break
                }
                if (!running || webSocket != null) break
                DiagLog.i("中转", "第 $attempt 次重连")
                connectOnce()
                try {
                    Thread.sleep(3000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "AurelayRelayRetry").also { it.start() }
    }

    /**
     * 心跳循环：每 30 秒发一次文本 ping
     *
     * :return: 无返回值
     */
    private fun pingLoop() {
        while (running) {
            try {
                Thread.sleep(RelayProtocol.PING_INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
            if (!running) break
            try {
                webSocket?.send("{\"type\":\"ping\"}")
            } catch (e: Exception) {
                DiagLog.w("中转", "发送心跳失败：${e.message}")
            }
        }
    }

    /**
     * 关闭当前 WebSocket
     *
     * :return: 无返回值
     */
    private fun closeWebSocket() {
        try {
            webSocket?.cancel()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        webSocket = null
    }

    /**
     * 通知状态变化（回调 + 日志）
     *
     * :param state: 新状态
     * :param detail: 说明文本
     * :return: 无返回值
     */
    private fun notifyState(state: State, detail: String) {
        DiagLog.i("中转", "状态：$state · $detail")
        try {
            listener?.onStateChanged(state, detail)
        } catch (e: Exception) {
            DiagLog.e("中转", "通知状态变化失败", e)
        }
    }
}
