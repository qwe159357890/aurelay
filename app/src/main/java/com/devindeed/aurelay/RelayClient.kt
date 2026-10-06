package com.devindeed.aurelay

import android.content.Context
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Socket

/**
 * 中转客户端（手机放音在蜂窝网络下的链路）
 *
 * 蜂窝网络下手机没有公网入站能力（移动实测封入站），无法被动等待电脑连接，
 * 因此改为**主动出站长连接**到中转服务器，由服务器把 PC 推来的 AURL 字节原样转发过来。
 * 出站长连接一旦建立，入站限制、双卡选卡、运营商 IPv6 策略、CGNAT 全部不再影响链路。
 *
 * 使用方式：调用 `start()` 后，通过 `audioInput()` 拿到一条连续的音频输入流，
 * 交给 AudioRelayService 现有的流解析逻辑（与局域网直连完全同一套代码）。
 *
 * 心跳 30 秒一次 PING；断线按 1/2/4/8/16/30 秒退避重连。
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
        // 已就绪（收到 READY，可收发数据）
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

    // 连接参数
    private var host: String = ""
    private var port: Int = RelayProtocol.DEFAULT_PORT
    private var deviceId: String = ""
    private var token: String = ""
    private var role: Byte = RelayProtocol.ROLE_RECEIVER
    private var localPort: Int = 0

    // 运行控制
    @Volatile private var running = false

    // 当前 socket 与流
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: DataInputStream? = null

    // 读线程与心跳线程
    private var readThread: Thread? = null
    private var pingThread: Thread? = null

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
     * :param audioPort: 本机音频端口（接收方为 5000）
     * :return: 无返回值
     */
    @Synchronized
    fun start(context: Context, role: Byte, audioPort: Int) {
        if (running) return
        val app = context.applicationContext
        val server = AppPrefs.getString(app, AppPrefs.KEY_RELAY_SERVER, AppPrefs.DEFAULT_RELAY_SERVER)
        val parts = server.split(":")
        host = parts.getOrNull(0) ?: AppPrefs.DEFAULT_RELAY_SERVER
        port = parts.getOrNull(1)?.toIntOrNull() ?: RelayProtocol.DEFAULT_PORT
        deviceId = AddressReporter.getOrCreateDeviceId(app)
        token = AppPrefs.getString(app, AppPrefs.KEY_REPORT_TOKEN, AppPrefs.DEFAULT_REPORT_TOKEN)
        this.role = role
        this.localPort = audioPort
        running = true

        if (pipeIn == null) {
            val out = PipedOutputStream()
            val ins = PipedInputStream(out, 256 * 1024)
            pipeOut = out
            pipeIn = ins
        }

        readThread = Thread({ runLoop() }, "AurelayRelayRead").also { it.start() }
        pingThread = Thread({ pingLoop() }, "AurelayRelayPing").also { it.start() }
        DiagLog.i("中转", "中转客户端启动：$host:$port，角色=${role.toInt().toChar()}")
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
        closeSocket()
        try {
            pipeOut?.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        pipeOut = null
        pipeIn = null
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
     * 主动发送音频数据（电脑放音经中转时使用；本次手机放音方向暂不使用）
     *
     * :param data: 待发送的数据
     * :param length: 有效长度
     * :return: true 表示写入成功
     */
    fun sendAudio(data: ByteArray, length: Int): Boolean {
        return try {
            val frame = RelayProtocol.frame(RelayProtocol.TYPE_DATA, data.copyOfRange(0, length))
            val out = output ?: return false
            out.write(frame)
            out.flush()
            true
        } catch (e: Exception) {
            DiagLog.e("中转", "发送音频数据失败", e)
            false
        }
    }

    /**
     * 连接与读取主循环（含退避重连）
     *
     * :return: 无返回值
     */
    private fun runLoop() {
        var attempt = 0
        while (running) {
            notifyState(State.CONNECTING, if (attempt == 0) "正在连接中转服务器" else "第 $attempt 次重连")
            try {
                val sock = Socket()
                sock.connect(java.net.InetSocketAddress(host, port), 8_000)
                sock.tcpNoDelay = true
                socket = sock
                output = sock.getOutputStream()
                input = DataInputStream(sock.getInputStream())
                DiagLog.i("中转", "已连上中转服务器 $host:$port")

                val hello = RelayProtocol.frame(
                    RelayProtocol.TYPE_HELLO,
                    RelayProtocol.helloPayload(role, deviceId, token, localPort)
                )
                output?.write(hello)
                output?.flush()
                lastAliveAt = System.currentTimeMillis()
                attempt = 0
                readFrames()
            } catch (e: Exception) {
                DiagLog.e("中转", "中转连接异常", e)
                notifyState(State.ERROR, "连接异常：${e.message ?: "未知"}")
            } finally {
                closeSocket()
            }
            if (!running) break
            val delay = RelayProtocol.BACKOFF_MS[attempt.coerceAtMost(RelayProtocol.BACKOFF_MS.size - 1)]
            attempt++
            try {
                Thread.sleep(delay)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    /**
     * 逐帧读取服务端消息（阻塞在 socket 读上）
     *
     * :return: 无返回值
     */
    private fun readFrames() {
        val stream = input ?: return
        while (running) {
            val header = ByteArray(RelayProtocol.HEADER_SIZE)
            try {
                stream.readFully(header)
            } catch (e: IOException) {
                throw e
            }
            for (i in 0 until RelayProtocol.MAGIC_SIZE) {
                if (header[i] != RelayProtocol.MAGIC[i]) {
                    DiagLog.e("中转", "收到非法帧头，断开连接")
                    return
                }
            }
            val type = header[RelayProtocol.MAGIC_SIZE]
            val length = RelayProtocol.readInt32(header, RelayProtocol.MAGIC_SIZE + 1)
            if (length < 0 || length > 4 * 1024 * 1024) {
                DiagLog.e("中转", "帧长度异常（$length），断开连接")
                return
            }
            val payload = ByteArray(length)
            if (length > 0) stream.readFully(payload)
            lastAliveAt = System.currentTimeMillis()
            handleFrame(type, payload)
        }
    }

    /**
     * 处理单帧消息
     *
     * :param type: 消息类型
     * :param payload: 载荷
     * :return: 无返回值
     */
    private fun handleFrame(type: Byte, payload: ByteArray) {
        when (type) {
            RelayProtocol.TYPE_READY -> {
                notifyState(State.READY, "已接入中转服务器")
                DiagLog.i("中转", "收到 READY，中转链路就绪")
            }
            RelayProtocol.TYPE_ERROR -> {
                val code = RelayProtocol.errorCode(payload)
                val message = RelayProtocol.errorMessage(payload)
                notifyState(State.ERROR, "错误 $code：$message")
                DiagLog.e("中转", "服务器返回错误：code=$code message=$message")
            }
            RelayProtocol.TYPE_PING -> {
                sendFrame(RelayProtocol.TYPE_PONG)
            }
            RelayProtocol.TYPE_PONG -> {
                // 心跳应答，仅刷新 lastAliveAt（已在 readFrames 中处理）
            }
            RelayProtocol.TYPE_DATA -> {
                try {
                    pipeOut?.write(payload)
                    pipeOut?.flush()
                    listener?.onAudioData(payload.size)
                } catch (e: Exception) {
                    DiagLog.e("中转", "写入音频管道失败", e)
                }
            }
            else -> {
                DiagLog.w("中转", "收到未知消息类型：$type")
            }
        }
    }

    /**
     * 心跳循环：每 30 秒发一次 PING
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
            sendFrame(RelayProtocol.TYPE_PING)
        }
    }

    /**
     * 发送一个无载荷帧
     *
     * :param type: 消息类型
     * :return: 无返回值
     */
    private fun sendFrame(type: Byte) {
        try {
            val out = output ?: return
            out.write(RelayProtocol.frame(type))
            out.flush()
        } catch (e: Exception) {
            DiagLog.w("中转", "发送帧失败（type=$type）：${e.message}")
        }
    }

    /**
     * 关闭当前 socket 与流
     *
     * :return: 无返回值
     */
    private fun closeSocket() {
        try {
            input?.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        try {
            output?.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        try {
            socket?.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        input = null
        output = null
        socket = null
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
