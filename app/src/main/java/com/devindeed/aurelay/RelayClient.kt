package com.devindeed.aurelay

import android.content.Context
import okhttp3.Dns
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
import java.net.InetAddress
import java.net.UnknownHostException
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

/**
 * 中转连接专用的 DNS 解析器：系统解析失败时兜底到服务器公网 IP。
 *
 * 实测蜂窝网络下切网后第一次连接 `039039.xyz` 偶发
 * `Unable to resolve host`，重连又成功，说明是运营商 DNS 抖动而非域名失效。
 * 兜底 IP（AppPrefs.RELAY_FALLBACK_IP）保证蜂窝链路稳定建立。
 */
private val relayDns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            DiagLog.w("中转", "DNS 解析失败：$hostname，改用兜底 IP ${AppPrefs.RELAY_FALLBACK_IP}")
            listOf(InetAddress.getByName(AppPrefs.RELAY_FALLBACK_IP))
        }
    }
}

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
        val cleaned = server.removePrefix("ws://").removePrefix("wss://")
        val separator = cleaned.lastIndexOf(':')
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

        // 不在这里 rebuildPipe：管道统一由 onOpen 回调负责重建。
        // 若在此处先建一条管道，紧接着 connectOnce() 的 onOpen 又 rebuildPipe 会把
        // 这条管道关掉，而 startRelayLink 的消费线程此刻可能正阻塞在这条管道上读，
        // 于是读到 EOF、误判「流包头未收全」而放弃（实测 17:49:34 切蜂窝后
        // 「已连上中转服务器」与「流包头未收全」落在同一毫秒）。
        // 首次连接时在 onOpen 之前 audioInput() 返回 null，消费线程会 sleep 等待，
        // onOpen 建好管道后自然开始消费，干净无竞态。

        httpClient = OkHttpClient.Builder()
            .dns(relayDns)
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
        closePipe()
        httpClient = null
        DiagLog.i("中转", "中转客户端已停止")
        notifyState(State.IDLE, "已停止")
    }

    // 中转客户端是否处于运行态（供服务层判断要不要重启链路）
    fun isRunning(): Boolean = running

    /**
     * 强制重连：主动断开当前 WebSocket 并触发一次全新连接
     *
     * 用于「读到旧会话残留帧」这类需要立刻丢弃当前管道、重建干净链路的场景。
     * 断开后 onClosed 会 closePipe（让上层立即 EOF 退出本次消费）并 scheduleReconnect，
     * 重连成功（onOpen）后 rebuildPipe 重建全新管道，残留数据随旧管道一起被丢弃，
     * 上层重新消费时读到的就是新连接发来的 AURL 包头。
     *
     * :return: 无返回值
     */
    @Synchronized
    fun forceReconnect() {
        if (!running) return
        DiagLog.w("中转", "强制重连：丢弃当前管道与旧会话残留")
        closeWebSocket()
        closePipe()
        // 直接触发重连（closeWebSocket 的 onClosed 回调也会 scheduleReconnect，
        // 但 cancel() 有时不回调 onClosed，这里显式补一次，双保险）
        scheduleReconnect()
    }

    /**
     * 取音频输入流（供上层用现有逻辑解析 AURL 流）
     *
     * :return: 音频输入流；未启动时返回 null
     */
    fun audioInput(): InputStream? = synchronized(this) { pipeIn }

    /**
     * 重建音频管道（新连接建立时调用，保证上层每次拿到干净的新流）
     *
     * PipedInputStream 一旦 read 返回 -1（读到 EOF）就永久 dead，
     * 之后即使 PipedOutputStream 继续写入也无法被读出。因此每次 WebSocket
     * 重连成功都必须重建一对全新管道，否则上层「读到旧流 EOF 退出后」，
     * 新数据无人能读，表现为「中转已连上却完全没声音」。
     *
     * :return: 无返回值
     */
    @Synchronized
    private fun rebuildPipe() {
        try {
            pipeOut?.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        val out = PipedOutputStream()
        val ins = PipedInputStream(out, 256 * 1024)
        pipeOut = out
        pipeIn = ins
    }

    /**
     * 关闭并置空音频管道（断线或停止时调用）
     *
     * 关闭 pipeOut 会让正在读 pipeIn 的上层立即收到 EOF（read 返回 -1），
     * 从而退出本次消费；置空则让上层通过 audioInput() 感知「当前无可用流」，
     * 进入等待，待 onOpen 重建管道后再继续。
     *
     * :return: 无返回值
     */
    @Synchronized
    private fun closePipe() {
        try {
            pipeOut?.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        pipeOut = null
        pipeIn = null
    }

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
                // 每次新连接都重建管道：旧管道可能已被上层读到 EOF（dead），
                // 不重建的话，重连后写入的数据无人能读（表现为「已连上却没声音」）。
                rebuildPipe()
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
                val data = bytes.toByteArray()
                try {
                    val out = synchronized(this@RelayClient) { pipeOut }
                    if (out != null) {
                        // PipedOutputStream.write(byte[], int, int) 返回 void：
                        // 它在内部要么写完请求的字节、要么抛 IOException，
                        // **不存在部分写入**（与 OutputStream 的契约不同，
                        // 但 PipedOutputStream 满足这一条），所以一次写完即可。
                        out.write(data, 0, data.size)
                        out.flush()
                        listener?.onAudioData(data.size)
                    }
                } catch (e: IOException) {
                    // 管道已断（断线重连重建管道、或对端停止接收）：属正常收尾，
                    // 不必逐帧刷错误日志，也不应把整个中转客户端判死。
                    DiagLog.w("中转", "音频管道已断开，忽略残余数据帧")
                } catch (e: Exception) {
                    DiagLog.e("中转", "写入音频管道失败", e)
                }
            }

            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                DiagLog.e("中转", "中转连接异常：${t.message}", t)
                notifyState(State.ERROR, "连接异常：${t.message ?: "未知"}")
                webSocket = null
                // 断线即关闭管道：让上层读管道立即 EOF 从而退出本次消费，
                // 等重连成功（onOpen）重建新管道后，上层会重新消费。
                closePipe()
                scheduleReconnect()
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                DiagLog.i("中转", "中转连接已关闭：code=$code reason=$reason")
                webSocket = null
                closePipe()
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
     * 用优雅关闭（发 WebSocket close 帧）而非 cancel() 硬断：硬断只断开本地 TCP，
     * 服务端可能收不到立即的关闭通知，要等 TCP 空闲超时（最长 90 秒）才感知，
     * 进而导致「R 下线清 S」迟迟不触发、PC 空推流几十秒（实测 19:42 蜂窝→WiFi
     * 切换延迟 20 秒）。优雅 close 会让服务端马上收到 close 帧、立即进 finally
     * 清 S，把切换空窗压到毫秒级。
     *
     * :return: 无返回值
     */
    private fun closeWebSocket() {
        val ws = webSocket
        webSocket = null
        if (ws == null) return
        try {
            // 优雅关闭：发送 close 帧（1000 正常关闭），服务端立即感知
            ws.close(1000, "client closing")
        } catch (e: Exception) {
            // close 失败（连接已死）再硬断兜底
            try {
                ws.cancel()
            } catch (e2: Exception) {
                // 忽略关闭异常
            }
        }
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
