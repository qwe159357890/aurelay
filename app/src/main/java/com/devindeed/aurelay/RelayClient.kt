package com.devindeed.aurelay

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
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
 * **传输方式：WebSocket，复用中心服务器的 15151 端口。**
 * 之所以不另开裸 TCP 端口：服务器只开放了 15151，无法新开端口；
 * 而 FastAPI / uvicorn 原生支持 WebSocket，正好可以在同一端口上承载长连接。
 *
 * 帧约定：**文本帧 = 控制消息（hello / ready / error / ping / pong），
 * 二进制帧 = 音频数据（原样透传的 AURL 字节，本身已是 Opus 压缩结果）**。
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

    // 连接参数（来自 AppPrefs）
    private var host: String = ""
    private var port: Int = 15151
    private var url: String = ""
    private var deviceId: String = ""
    private var token: String = ""
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

    // 管道：把收到的音频数据转成一条连续的 InputStream 供上层解析
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
        val server = AppPrefs.getString(app, AppPrefs.KEY_RELAY_SERVER, AppPrefs.DEFAULT_RELAY_SERVER)
        val cleaned = server.removePrefix("ws://").removePrefix("wss://")        val separator = cleaned.lastIndexOf(':')
        host = if (separator > 0) cleaned.substring(0, separator) else cleaned
        port = if (separator > 0) cleaned.substring(separator + 1).toIntOrNull() ?: 15151 else 15151
        // ⚠️ url 字段此前**从未被赋值**，而 connectOnce() 直接拿它建 WebSocket，
        // 于是蜂窝下每次连接都抛
        // 「IllegalArgumentException: Expected URL scheme 'http' or 'https' but no scheme was found for ""」
        // ——中转链路其实一次都没连上过。必须在这里拼出来。
        url = "ws://$host:$port${AppPrefs.RELAY_PATH}"
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
        DiagLog.i("中转", "中转客户端启动：$host:$port，角色=${role.toInt().toChar()}，本机端口=$audioPort")
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

    // 中转客户端是否处于运行态（供服务层判断要不要重启链路）
    fun isRunning(): Boolean = running

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
    fun sendAudio(data: ByteArray, length: Int): Boolean {
        return try {
            val socket = webSocket ?: return false
            socket.send(okio.ByteString.of(*chunkOf(data, length)))
            true
        } catch (e: Exception) {
            DiagLog.e("中转", "发送音频数据失败", e)
            false
        }
    }

    /**
     * 从源数组里截出有效的音频片段
     *
     * :param data: 源数据
     * :param length: 有效长度
     * :return: 截取后的字节数组
     */
    private fun chunkOf(data: ByteArray, length: Int): ByteArray {
        val size = length.coerceAtMost(data.size)
        return data.copyOfRange(0, size)
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

            override fun onMessage(socket: WebSocket, text: String) {
                lastAliveAt = System.currentTimeMillis()
                handleControl(text)
            }

            override fun onMessage(socket: WebSocket, bytes: okio.ByteString) {
                lastAliveAt = System.currentTimeMillis()
                // 必须先判 running：stop() 会把管道置空并关闭，但已排队的数据帧
                // 仍会回调到这里。此前无脑往 pipeOut 写，会刷出成百上千行
                // 「写入音频管道失败 | Read end dead」（实测 09:16-09:17 共 838 行）。
                if (!running) return
                val out = pipeOut ?: return
                try {
                    val data = bytes.toByteArray()
                    out.write(data)
                    out.flush()
                    listener?.onAudioData(data.size)
                } catch (e: IOException) {
                    // 管道已断（对端停止接收）：属于正常收尾，不必逐帧刷错误日志
                    DiagLog.w("中转", "音频管道已断开，忽略残余数据帧")
                    running = false
                } catch (e: Exception) {
                    DiagLog.e("中转", "写入音频管道失败", e)
                }
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
     * 心跳循环：每 30 秒发一次文本 ping
     *
     * :return: 无返回值
     */
    private fun pingLoop() {
        while (running) {
            try {
                TimeUnit.MILLISECONDS.sleep(RelayProtocol.PING_INTERVAL_MS)
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

    /**
     * 断线后按退避策略重连（1/2/4/8/16/30 秒）
     *
     * :return: 无返回值
     */
    private fun scheduleReconnect() {
        if (!running) return
        val existing = retryThread
        if (existing != null && existing.isAlive) return
        retryThread = Thread({
            var attempt = 0
            while (running && webSocket == null) {
                val index = attempt.coerceAtMost(RelayProtocol.BACKOFF_MS.size - 1)
                val delay = RelayProtocol.BACKOFF_MS[index]
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
}
