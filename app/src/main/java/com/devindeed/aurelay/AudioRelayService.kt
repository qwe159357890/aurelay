package com.devindeed.aurelay

//noinspection SuspiciousImport
import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.Locale
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory

class AudioRelayService : Service() {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManagerCompat
    private var serverThread: Thread? = null
    @Volatile private var lastClientIp: String = ""
    @Volatile private var lastClientName: String = ""

    @Volatile private var isServerRunning = true
    private var serverSocket: ServerSocket? = null

    // 中转客户端（蜂窝链路）与其接收线程
    private val relayClient = RelayClient()
    private var relayThread: Thread? = null
    private var useTls: Boolean = false // Default to plain TCP for easier testing (change to true for production TLS)
    private var audioTrack: AudioTrack? = null

    // 当前 AudioTrack 采用的采样率与声道数（流协商结果，用于判断是否需要重建）
    private var currentSampleRate: Int = 0
    private var currentOutChannels: Int = 0
    // 当前音轨的缓冲区字节数（创建时确定，日志用）
    private var currentBufferBytes: Int = 0

    // 最近一次设置的音量：新建 AudioTrack 时恢复，避免重建后音量被重置为 1.0
    @Volatile private var currentVolume: Float = 1.0f

    companion object {
        const val ACTION_SET_VOLUME = "com.devindeed.aurelay.SET_VOLUME"
        const val EXTRA_VOLUME = "volume"
        const val ACTION_AUDIO_LEVEL = "com.devindeed.aurelay.AUDIO_LEVEL"
        const val EXTRA_AUDIO_LEVELS = "audio_levels"
        const val ACTION_STOP_SERVICE = "com.devindeed.aurelay.STOP_SERVICE"
        const val ACTION_OPEN_APP = "com.devindeed.aurelay.OPEN_APP"

        // 音频接收端口（地址上报时会一并告知 PC 发送端）
        const val AUDIO_PORT = 5000

        // 链路方式广播（供界面更新链路卡片）
        const val ACTION_LINK_MODE = "com.devindeed.aurelay.LINK_MODE"
        const val EXTRA_LINK_MODE = "link_mode"

        // ==== 自研流协议（在官方「裸 PCM」之上做的向下兼容扩展）====
        // 官方发送端直接推裸 PCM（44.1kHz / 16bit / 立体声），本应用保持兼容；
        // 自研发送端则可先发 8 字节包头启用「分帧 + 可选 Opus 编码」，
        // 蜂窝网络下用 Opus 可把流量压到裸 PCM 的十几分之一。
        //
        // 包头（8 字节）：
        //   [0..3] 魔数 "AURL"
        //   [4]    版本号，当前为 1
        //   [5]    编码：0 = PCM 裸数据，1 = Opus
        //   [6]    声道数：1 或 2
        //   [7]    采样率代码：0 = 44100，1 = 48000
        // 包头之后：每帧 = 4 字节大端长度 + 帧数据
        const val STREAM_MAGIC = "AURL"
        const val STREAM_VERSION = 1
        const val CODEC_PCM = 0
        const val CODEC_OPUS = 1
        const val RATE_44100 = 0
        const val RATE_48000 = 1
        const val HEADER_SIZE = 8

        // 等待流包头 / 读取数据的空闲超时（毫秒），超时则断开该客户端
        private const val IDLE_TIMEOUT_MS = 10_000L

        // 周期诊断日志的输出间隔（毫秒），仅日志开关打开时记录
        private const val REPORT_INTERVAL_MS = 5_000L
    }

    // 单次接收会话的诊断统计（用于回答「数据到底走到哪一步了」）
    private class DiagSession(val branch: String) {
        // 已写入 AudioTrack 的数据块数（PCM 分支即收到的分片数，Opus 分支为解码出的 PCM 数）
        var frames = 0L
        // 已写入 AudioTrack 的字节数
        var bytes = 0L
        // 收到的 Opus 包数（PCM 分支与 frames 相同）
        var packets = 0L
        // 单块最大字节数
        var maxChunk = 0
        // 全程 PCM 峰值绝对值（0 ~ 32768）
        var peak = 0
        // 会话开始时间
        var startedAt = System.currentTimeMillis()
        // 是否已记录首块十六进制预览
        var firstChunkLogged = false
        // 是否已记录首个 Opus 包预览
        var firstPacketLogged = false
        // 上一次周期统计的时间与基线
        var lastAt = System.currentTimeMillis()
        var lastFrames = 0L
        var lastBytes = 0L
    }

    override fun onCreate() {
        super.onCreate()
        // 安装诊断日志器（开关来自设置页，本地文件位于 diag/aurelay-diag.log）
        DiagLog.install(this)
        mediaSession = MediaSessionCompat(this, "AudioRelay")
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()
        startForeground(1, buildNotification())
        // UDP 广播发现已整体删除：电脑端改从中心服务器查询本机地址
        // 启动地址上报（把本机地址同步到用户自己的中心服务器，供电脑端发现）
        AddressReporter.start(this)
        // 全程保活：持有 20 项受控资源（锁、兜底调度、网络回调、位置/传感器/蓝牙/相机等）
        applyKeepAlive()
        Log.i("AudioRelay", "Service onCreate called, foreground started.")
        DiagLog.i("服务", "接收服务已启动：监听端口 $AUDIO_PORT，TLS=$useTls")
        DiagLog.i("环境", DiagLog.environment(this))
    }

    /**
     * 按「全程保活」开关持有或释放受控资源，并同步两个独立锚点开关
     *
     * :return: 无返回值
     */
    private fun applyKeepAlive() {
        val keepAlive = AppPrefs.getBoolean(this, AppPrefs.KEY_KEEP_ALIVE_ALWAYS, true)
        if (keepAlive) {
            ResourceHolder.acquireAll(this)
        } else {
            ResourceHolder.releaseAll(this)
        }
        // 一像素锚点（独立开关）
        if (AppPrefs.getBoolean(this, AppPrefs.KEY_KEEP_ALIVE_ONE_PIXEL, true)) {
            OnePixelOverlay.show(this)
        } else {
            OnePixelOverlay.hide(this)
        }
        // 无声播放锚点（独立开关）
        SilentPlayer.applyEnabled(this)
        // 网络切换时自动切换链路：WiFi 走局域网直连，蜂窝走服务器中转
        NetworkWatcher.addListener { type ->
            DiagLog.i("服务", "检测到网络切换：$type，重新选择链路")
            if (type == NetworkWatcher.NetType.WIFI) {
                stopRelayLink()
                startLanServer()
            } else {
                stopLanServer()
                startRelayLink()
            }
        }
    }

    /**
     * 停止局域网直连（关闭监听端口）
     *
     * :return: 无返回值
     */
    private fun stopLanServer() {
        isServerRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            DiagLog.w("服务", "关闭监听端口失败：${e.message}")
        }
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
    }

    /**
     * 停止服务器中转（断开出站长连接）
     *
     * :return: 无返回值
     */
    private fun stopRelayLink() {
        try {
            relayClient.stop()
        } catch (e: Exception) {
            DiagLog.w("中转", "停止中转客户端失败：${e.message}")
        }
        relayThread?.interrupt()
        relayThread = null
    }

    /**
     * 记录当前链路方式，供服务重启后恢复现场
     *
     * :param mode: lan 表示局域网直连、relay 表示服务器中转
     * :return: 无返回值
     */
    private fun saveLinkMode(mode: String) {
        AppPrefs.setString(this, AppPrefs.KEY_LAST_LINK_MODE, mode)
    }

    /**
     * 按当前网络类型选择链路：WiFi 走局域网直连，蜂窝走服务器中转
     *
     * :return: 无返回值
     */
    private fun startByNetwork() {
        val type = NetworkWatcher.refresh(this)
        when (type) {
            NetworkWatcher.NetType.WIFI -> startLanServer()
            else -> startRelayLink()
        }
    }

    /**
     * 启动局域网直连：监听 5000 端口等待电脑接入
     *
     * :return: 无返回值
     */
    private fun startLanServer() {
        saveLinkMode("lan")
        broadcastLinkMode("lan")
        if (serverThread == null || !serverThread!!.isAlive) {
            isServerRunning = true
            serverThread = Thread({ startAudioServer() }, "AurelayLanServer").also { it.start() }
            DiagLog.i("服务", "已选择局域网直连：监听 $AUDIO_PORT 端口")
        }
    }

    /**
     * 启动服务器中转：主动出站长连接中转服务器并接收转发来的音频
     *
     * :return: 无返回值
     */
    private fun startRelayLink() {
        if (!AppPrefs.getBoolean(this, AppPrefs.KEY_RELAY_ENABLED, true)) {
            DiagLog.w("中转", "允许中转开关已关闭，蜂窝下无法接收")
            return
        }
        saveLinkMode("relay")
        broadcastLinkMode("relay")
        if (relayThread != null && relayThread!!.isAlive) return
        relayThread = Thread({
            try {
                relayClient.start(this@AudioRelayService, RelayProtocol.ROLE_RECEIVER, AUDIO_PORT)
                val input = relayClient.audioInput()
                if (input != null) {
                    handleStream(input, "中转服务器")
                }
            } catch (e: Exception) {
                DiagLog.e("中转", "中转接收异常", e)
            }
        }, "AurelayRelayStream").also { it.start() }
        DiagLog.i("服务", "已选择服务器中转：主动出站连接中转服务器")
    }

    /**
     * 广播当前链路方式，供界面更新链路卡片
     *
     * :param mode: lan 表示局域网直连、relay 表示服务器中转
     * :return: 无返回值
     */
    private fun broadcastLinkMode(mode: String) {
        try {
            val bcast = Intent(ACTION_LINK_MODE)
            bcast.setPackage(packageName)
            bcast.putExtra(EXTRA_LINK_MODE, mode)
            sendBroadcast(bcast)
        } catch (e: Exception) {
            DiagLog.e("服务", "广播链路方式失败", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                Log.d("AudioRelay", "Stopping service from notification action")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_VOLUME -> {
                val volume = intent.getFloatExtra(EXTRA_VOLUME, 0.8f)
                setVolume(volume)
                Log.d("AudioRelay", "Volume set to: $volume")
                return START_STICKY
            }
            else -> {
                isServerRunning = true
                // Check for intent extra to override TLS (for dev/advanced users)
                // Default to false (plain TCP) unless explicitly set to true
                useTls = intent?.getBooleanExtra("useTls", false) ?: false
                // 按当前网络类型自动选择链路：WiFi 局域网直连，蜂窝服务器中转
                startByNetwork()
                Log.i("AudioRelay", "Service onStartCommand: useTls=$useTls, link started.")
                return START_STICKY
            }
        }
    }

    private fun buildNotification(): Notification {
        // Create intent to open the app
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Create intent to disconnect (stop service)
        val disconnectIntent = Intent(this, AudioRelayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Get sender device name (if connected) or show "Ready to receive"
        val displayText = if (lastClientName.isNotEmpty()) {
            "正在接收来自 $lastClientName 的音频"
        } else {
            "等待接收音频"
        }

        val builder =
            NotificationCompat.Builder(this, "audioRelayChannel")
                .setContentTitle("Aurelay 声音中继")
                .setContentText(displayText)
                // 图标统一用 Aurelay 自己的启动图标；其余风格与采集端一致
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openAppPendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "断开连接",
                    disconnectPendingIntent
                )
                .addAction(
                    android.R.drawable.ic_menu_preferences,
                    "打开应用",
                    openAppPendingIntent
                )
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.sessionToken)
                        .setShowActionsInCompactView(0, 1)
                )
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    "audioRelayChannel",
                    "音频中继服务",
                    NotificationManager.IMPORTANCE_LOW
                )
            notificationManager.createNotificationChannel(channel)
        }
    }

    // 建立监听端口并循环接受客户端连接
    private fun startAudioServer() {
        try {
            // --- TLS/Plain socket selection with fallback ---
            if (useTls) {
                try {
                    // --- TLS Setup ---
                    val keystorePassword = "changeit" // Use your actual password
                    val keystoreStream = applicationContext.assets.open("server.p12") // Or use resources/raw
                    val keyStore = KeyStore.getInstance("PKCS12")
                    keyStore.load(keystoreStream, keystorePassword.toCharArray())

                    val kmfAlg = KeyManagerFactory.getDefaultAlgorithm()
                    val kmf = KeyManagerFactory.getInstance(kmfAlg)
                    kmf.init(keyStore, keystorePassword.toCharArray())

                    Log.i("AudioRelay", "Loaded keystore and initialized KeyManagerFactory (alg=$kmfAlg)")

                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(kmf.keyManagers, null, null)
                    val sslServerSocketFactory = sslContext.serverSocketFactory as SSLServerSocketFactory

                    serverSocket = sslServerSocketFactory.createServerSocket(AUDIO_PORT) as SSLServerSocket
                    (serverSocket as SSLServerSocket).needClientAuth = false
                    Log.i("AudioRelay", "TLS enabled. Listening on port ${serverSocket?.localPort} bound to ${serverSocket?.inetAddress}")
                    Log.i("AudioRelay", "ServerSocket implementation: ${serverSocket!!::class.java.name}")
                } catch (tlsEx: Exception) {
                    Log.w("AudioRelay", "TLS setup failed (${tlsEx.message}), falling back to plain TCP: ${tlsEx}")
                    serverSocket = ServerSocket(AUDIO_PORT)
                    Log.i("AudioRelay", "Plain TCP fallback. Listening on port $AUDIO_PORT")
                }
            } else {
                // --- Plain TCP ---
                serverSocket = ServerSocket(AUDIO_PORT)
                Log.i("AudioRelay", "Plain TCP. Listening on port $AUDIO_PORT")
            }
            DiagLog.i("监听", "已在 $AUDIO_PORT 端口开始监听（TLS=$useTls），等待电脑端连接")

            while (isServerRunning) {
                try {
                    // Accept may throw SSLException if a non-TLS client connects to an SSLServerSocket
                    val maybeClient = try {
                        serverSocket?.accept()
                    } catch (sslEx: javax.net.ssl.SSLException) {
                        Log.e("AudioRelay", "SSL exception during accept (possible TLS/plain mismatch): ${sslEx.message}", sslEx)
                        DiagLog.e("监听", "接受连接时发生 TLS 异常（可能是 TLS/明文不匹配）", sslEx)
                        null
                    }

                    maybeClient?.use { client ->
                        handleClient(client)
                    }
                } catch (e: IOException) {
                    if (isServerRunning) {
                        Log.e("AudioRelay", "Error accepting client or reading data: ", e)
                        DiagLog.e("监听", "接受连接或读取数据出错", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRelay", "Server setup or loop error: ", e)
        } finally {
            Log.i("AudioRelay", "Stopping server and releasing AudioTrack.")
            // Proper cleanup is handled in onDestroy
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                Log.e("AudioRelay", "Error closing server socket during cleanup.", e)
            }
        }
    }

    // 处理单个客户端连接：配置 socket 后把流交给统一的流处理逻辑
    private fun handleClient(client: Socket) {
        // Configure socket for robust streaming
        client.soTimeout = 100 // 100ms timeout prevents indefinite blocking
        client.tcpNoDelay = true // Disable Nagle's algorithm for lower latency
        client.receiveBufferSize = 4096 // Smaller buffer for lower latency
        val peer = try { client.inetAddress.hostAddress ?: "" } catch (ex: Exception) { "" }
        Log.i("AudioRelay", "Client connected: $peer")
        DiagLog.i("连接", "电脑端已连接：$peer:${client.port}（本机端口 $AUDIO_PORT）")

        // Update notification with sender name (check runtime permission on Android 13+)
        notifyConnected()

        // Broadcast connection event so UI can update
        try {
            val bcast = Intent("com.devindeed.aurelay.CLIENT_CONNECTION")
            bcast.setPackage(packageName)
            bcast.putExtra("connected", true)
            bcast.putExtra("client_ip", peer)
            sendBroadcast(bcast)
            Log.i("AudioRelay", "Broadcast sent: CLIENT_CONNECTION connected=true ip=$peer")
        } catch (ex: Exception) {
            Log.e("AudioRelay", "Failed to broadcast client connected: ${ex.message}", ex)
        }

        lastClientIp = peer
        try {
            handleStream(client.getInputStream(), peer)
        } finally {
            try { client.close() } catch (ex: Exception) { /* 忽略关闭异常 */ }
        }
    }

    // 统一的流处理：先读流包头判定编码，再进入对应解码链路（局域网直连与服务器中转共用）
    private fun handleStream(rawInput: InputStream, peerLabel: String) {
        var decoder: OpusDecoder? = null
        var session: DiagSession? = null
        DiagLog.i("连接", "开始处理来自 $peerLabel 的音频流")
        // 真实音频开始播放，无声播放锚点让位（避免底噪混入输出）
        SilentPlayer.setScenarioAllowed(this, false)
        try {
            val input = PushbackInputStream(rawInput, HEADER_SIZE)
            val header = readHeader(input)

            var framed = false
            var codec = CODEC_PCM
            var streamChannels = 2
            var streamSampleRate = 44100

            if (header != null) {
                framed = true
                codec = header[5].toInt() and 0xFF
                streamChannels = if ((header[6].toInt() and 0xFF) == 1) 1 else 2
                streamSampleRate =
                    if ((header[7].toInt() and 0xFF) == RATE_48000) 48000 else 44100
                Log.i(
                    "AudioRelay",
                    "检测到自研流包头：codec=$codec channels=$streamChannels sampleRate=$streamSampleRate"
                )
                DiagLog.i(
                    "协议",
                    "收到自研 AURL 包头 [${header.joinToString(" ") { "%02x".format(it) }}]：" +
                            "编码=${if (codec == CODEC_OPUS) "Opus" else "裸PCM"} " +
                            "声道=$streamChannels 采样率=$streamSampleRate"
                )
            } else {
                Log.i("AudioRelay", "未检测到自研流包头，按官方裸 PCM 流处理（44.1kHz / 立体声）")
                DiagLog.i("协议", "未检测到 AURL 包头，按官方裸 PCM 流处理（44100Hz / 立体声）")
            }

            // Opus 解码输出固定为 48kHz，音频轨必须按该采样率创建，否则播放会变速
            val isOpus = framed && codec == CODEC_OPUS
            val playbackRate = if (isOpus) 48000 else streamSampleRate

            // 输出声道数：设备支持立体声就输出立体声，否则降为单声道
            val outChannels = if (supportsStereo(playbackRate)) 2 else 1
            val track = ensureAudioTrack(playbackRate, outChannels)
            if (track == null) {
                Log.e("AudioRelay", "AudioTrack 创建失败，断开本次连接")
                DiagLog.e("音轨", "AudioTrack 创建失败，本次连接无法出声音，主动断开")
                return
            }
            DiagLog.i("音轨", "播放音轨已就绪：${trackSnapshot(track)}；${mediaVolumeInfo()}")

            // Opus 模式需要系统解码器（Android 自带 software opus decoder）
            if (isOpus) {
                decoder = OpusDecoder(streamChannels, playbackRate)
                if (!decoder.start()) {
                    Log.e("AudioRelay", "Opus 解码器初始化失败，断开本次连接")
                    DiagLog.e("解码", "Opus 解码器初始化失败（系统无可用 audio/opus 解码器），断开本次连接")
                    return
                }
            }

            val currentSession = DiagSession(branchName(framed, isOpus))
            session = currentSession
            DiagLog.i(
                "会话",
                "本次接收分支：「${currentSession.branch}」，输出采样率=$playbackRate 输出声道=$outChannels"
            )

            if (framed) {
                if (isOpus) {
                    streamFramedOpus(input, track, decoder, outChannels, streamChannels * 2, currentSession)
                } else {
                    streamFramedPcm(input, track, outChannels, currentSession)
                }
            } else {
                streamRawPcm(input, track, outChannels, currentSession)
            }
        } catch (e: Exception) {
            Log.e("AudioRelay", "客户端会话异常：${e.message}", e)
            DiagLog.e("会话", "客户端会话异常：${e.message}", e)
        } finally {
            try { decoder?.stop() } catch (e: Exception) { /* 忽略 */ }
            // 会话汇总：这是判断「数据到底有没有走完链路」最直接的一行
            val finished = session
            if (finished != null) {
                DiagLog.i("汇总", "接收结束：${sessionSummary(finished)}")
                DiagLog.i("汇总", "结束时的音轨状态：${audioTrackSnapshot()}")
            }
            // 只暂停并清空缓冲、不释放音轨：下一路客户端连接时 ensureAudioTrack()
            // 会重新 play()。若这里写 stop()，音轨会停在 STOPPED 状态，
            // 后续会话复用同一个音轨时就会「连着但没声音」。
            try {
                audioTrack?.pause()
                audioTrack?.flush()
            } catch (e: Exception) {
                // 忽略暂停异常
            }
            Log.i("AudioRelay", "Client disconnected.")
            DiagLog.i("连接", "音频流结束（$peerLabel）")
            // 音频流结束，无声播放锚点恢复工作
            SilentPlayer.setScenarioAllowed(this, true)
            // Clear client info and update notification
            lastClientName = ""
            notifyConnected()
            // Broadcast disconnect event so UI can update
            try {
                val bcast = Intent("com.devindeed.aurelay.CLIENT_CONNECTION")
                bcast.setPackage(packageName)
                bcast.putExtra("connected", false)
                bcast.putExtra("client_ip", "")
                sendBroadcast(bcast)
                Log.i("AudioRelay", "Broadcast sent: CLIENT_CONNECTION connected=false")
                lastClientIp = ""
            } catch (ex: Exception) {
                Log.e("AudioRelay", "Failed to broadcast client disconnected: ${ex.message}", ex)
            }
        }
    }

    // 按 Android 13+ 的通知权限差异刷新前台通知
    private fun notifyConnected() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            @SuppressLint("MissingPermission")
            notificationManager.notify(1, buildNotification())
        } else {
            Log.w("AudioRelay", "Missing POST_NOTIFICATIONS permission; skipping notification update")
        }
    }

    // 读取 8 字节流包头：命中自研魔数返回包头，否则把已读字节退回并按官方裸 PCM 处理
    private fun readHeader(input: PushbackInputStream): ByteArray? {
        val header = ByteArray(HEADER_SIZE)
        var filled = 0
        var idleMillis = 0L
        while (filled < HEADER_SIZE) {
            if (!isServerRunning) return null
            try {
                val n = input.read(header, filled, HEADER_SIZE - filled)
                if (n < 0) {
                    if (filled > 0) input.unread(header, 0, filled)
                    return null
                }
                filled += n
                idleMillis = 0
            } catch (e: java.net.SocketTimeoutException) {
                idleMillis += 100
                if (idleMillis >= IDLE_TIMEOUT_MS) {
                    if (filled > 0) input.unread(header, 0, filled)
                    Log.w("AudioRelay", "等待流包头超时，放弃该连接")
                    return null
                }
            }
        }
        val magic = String(header, 0, 4, Charsets.US_ASCII)
        if (magic == STREAM_MAGIC && (header[4].toInt() and 0xFF) == STREAM_VERSION) {
            return header
        }
        // 非自研协议：退回字节，走官方裸 PCM 分支
        input.unread(header, 0, HEADER_SIZE)
        return null
    }

    // 官方裸 PCM 流：直接写 AudioTrack（与改造前行为一致）
    private fun streamRawPcm(
        input: InputStream,
        track: AudioTrack,
        outChannels: Int,
        session: DiagSession
    ) {
        val buffer = ByteArray(4096)
        var read: Int
        var frameCount = 0
        var consecutiveErrors = 0
        val frameSize = 4 // 官方流固定为立体声：2 字节采样 * 2 声道
        while (isServerRunning && consecutiveErrors < 5) {
            try {
                read = input.read(buffer)
                if (read == -1) {
                    DiagLog.i("接收", "裸 PCM 流结束：对端已关闭连接")
                    break
                }
                consecutiveErrors = 0
                if (read > 0) {
                    recordChunk(session, buffer, read)
                    writeToTrack(track, buffer, read, frameSize, outChannels)
                    if (++frameCount % 5 == 0) {
                        broadcastAudioLevels(calculateAudioLevels(buffer, read))
                    }
                    reportSession(session, track)
                }
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                consecutiveErrors++
                Log.w("AudioRelay", "Read error $consecutiveErrors/5: ${e.message}")
                DiagLog.w("接收", "裸 PCM 读取失败 $consecutiveErrors/5：${e.message}")
            }
        }
    }

    // 自研分帧 PCM 流：每帧 = 4 字节大端长度 + PCM 数据
    private fun streamFramedPcm(
        input: InputStream,
        track: AudioTrack,
        outChannels: Int,
        session: DiagSession
    ) {
        val frameSize = outChannels * 2
        var frameCount = 0
        while (isServerRunning) {
            val payload = readFrame(input) ?: break
            if (payload.isEmpty()) continue
            recordChunk(session, payload, payload.size)
            writeToTrack(track, payload, payload.size, frameSize, outChannels)
            if (++frameCount % 5 == 0) {
                broadcastAudioLevels(calculateAudioLevels(payload, payload.size))
            }
            reportSession(session, track)
        }
    }

    // 自研分帧 Opus 流：每帧为 1 个 Opus 包，解码后写 AudioTrack
    private fun streamFramedOpus(
        input: InputStream,
        track: AudioTrack,
        decoder: OpusDecoder?,
        outChannels: Int,
        inputFrameSize: Int,
        session: DiagSession
    ) {
        if (decoder == null) return
        var frameCount = 0
        while (isServerRunning) {
            val packet = readFrame(input) ?: break
            if (packet.isEmpty()) continue
            session.packets++
            if (!session.firstPacketLogged) {
                session.firstPacketLogged = true
                val preview = packet.take(minOf(packet.size, 16)).joinToString(" ") { "%02x".format(it) }
                DiagLog.i("接收", "首个 Opus 包已到达：长度=${packet.size} 字节，前 ${minOf(packet.size, 16)} 字节=[$preview]")
            }
            decoder.decode(packet, packet.size) { pcm, size ->
                recordChunk(session, pcm, size)
                writeToTrack(track, pcm, size, inputFrameSize, outChannels)
                if (++frameCount % 5 == 0) {
                    broadcastAudioLevels(calculateAudioLevels(pcm, size))
                }
            }
            reportSession(session, track)
        }
    }

    // 读取一个音频帧：4 字节大端长度 + 数据；流结束返回 null
    private fun readFrame(input: InputStream): ByteArray? {
        val lenBuf = ByteArray(4)
        if (!readFully(input, lenBuf, 4)) return null
        val length = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                ((lenBuf[1].toInt() and 0xFF) shl 16) or
                ((lenBuf[2].toInt() and 0xFF) shl 8) or
                (lenBuf[3].toInt() and 0xFF)
        if (length <= 0 || length > 1024 * 1024) {
            Log.w("AudioRelay", "非法帧长度 $length，结束本次流")
            return null
        }
        val payload = ByteArray(length)
        if (!readFully(input, payload, length)) return null
        return payload
    }

    // 尽力读满指定字节数；读超时则继续重试，流结束或长时间无数据返回 false
    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var filled = 0
        var idleMillis = 0L
        while (filled < length) {
            if (!isServerRunning) return false
            try {
                val n = input.read(buffer, filled, length - filled)
                if (n < 0) return false
                filled += n
                idleMillis = 0
            } catch (e: java.net.SocketTimeoutException) {
                idleMillis += 100
                if (idleMillis >= IDLE_TIMEOUT_MS) {
                    Log.w("AudioRelay", "读取数据超时，结束本次流")
                    return false
                }
            }
        }
        return true
    }

    // 把一段 PCM 写入 AudioTrack（必要时做立体声转单声道与帧对齐）
    private fun writeToTrack(
        track: AudioTrack,
        buffer: ByteArray,
        length: Int,
        inputFrameSize: Int,
        outChannels: Int
    ) {
        val alignedBytes = (length / inputFrameSize) * inputFrameSize
        if (alignedBytes <= 0) return
        try {
            val writeMode = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                AudioTrack.WRITE_BLOCKING
            } else {
                AudioTrack.WRITE_NON_BLOCKING
            }
            val result = if (outChannels == 2 && inputFrameSize == 4) {
                track.write(buffer, 0, alignedBytes, writeMode)
            } else if (outChannels == 1 && inputFrameSize == 4) {
                val mono = convertStereoToMono(buffer, alignedBytes)
                track.write(mono, 0, mono.size, writeMode)
            } else {
                // 输入已是单声道，直接写
                track.write(buffer, 0, alignedBytes, writeMode)
            }
            // 负数是 AudioTrack 的错误码（非阻塞模式下缓冲满会返回 0，属正常）
            if (result < 0) {
                DiagLog.w("音轨", "AudioTrack.write 返回错误码 $result（写入长度=$alignedBytes，写入模式=$writeMode）")
            }
        } catch (e: Exception) {
            Log.e("AudioRelay", "AudioTrack write exception: ${e.message}", e)
            DiagLog.e("音轨", "AudioTrack 写入异常：${e.message}", e)
        }
    }

    // 判断当前设备在指定采样率下是否支持立体声输出
    private fun supportsStereo(sampleRate: Int): Boolean {
        val size = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return size != AudioTrack.ERROR_BAD_VALUE && size != AudioTrack.ERROR && size > 0
    }

    // 按流协商出的采样率与声道数创建（或复用）AudioTrack
    @Synchronized
    private fun ensureAudioTrack(sampleRate: Int, outChannels: Int): AudioTrack? {
        val existing = audioTrack
        if (existing != null &&
            currentSampleRate == sampleRate &&
            currentOutChannels == outChannels &&
            existing.state == AudioTrack.STATE_INITIALIZED
        ) {
            // 【关键修复】AudioTrack.stop() 不改变 state（仍是 STATE_INITIALIZED），
            // 所以上一路客户端断开后再连进来，这里会拿到一个「已停止」的音轨：
            // 数据照写但不会被播放 —— 表现就是手机端显示已连接、音量也能调，
            // 却完全没有声音。复用前必须确认它真的在播放，否则先 flush() 再 play()。
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    existing.flush()
                    existing.play()
                    Log.i("AudioRelay", "AudioTrack 已重新 play()：上一次会话残留为停止态")
                    DiagLog.w("音轨", "复用音轨时发现它不在播放态（playState=${playStateName(existing.playState)}），已 flush 并重新 play")
                } catch (e: Exception) {
                    Log.e("AudioRelay", "AudioTrack 恢复播放失败：${e.message}", e)
                    DiagLog.e("音轨", "AudioTrack 恢复播放失败：${e.message}", e)
                }
            } else {
                DiagLog.i("音轨", "复用已存在的音轨（${trackSnapshot(existing)}）")
            }
            return existing
        }
        try {
            existing?.stop()
            existing?.release()
        } catch (e: Exception) {
            // 忽略释放异常
        }
        audioTrack = null

        val channelConfig = if (outChannels == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize <= 0) {
            Log.e("AudioRelay", "AudioTrack.getMinBufferSize 返回 $minBufSize，采样率 $sampleRate 不受支持")
            DiagLog.e("音轨", "该采样率不被支持：getMinBufferSize 返回 $minBufSize（采样率=$sampleRate）")
            return null
        }
        // Use a larger buffer than the minimum to avoid underruns with network jitter
        val bufferSizeBytes = maxOf(minBufSize, minBufSize * 4)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val audioTrackFormat = AudioFormat.Builder()
            .setChannelMask(channelConfig)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .build()
        val builder = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioTrackFormat)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val built = builder.build()
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            Log.e("AudioRelay", "AudioTrack 初始化失败，state=${built.state}")
            DiagLog.e("音轨", "AudioTrack 初始化失败，state=${built.state}")
            built.release()
            return null
        }
        // 恢复用户之前设定过的音量（新建音轨默认 1.0，会把「已静音」状态丢掉）
        try {
            built.setVolume(currentVolume)
        } catch (e: Exception) {
            // 忽略音量恢复异常
        }
        built.play()
        audioTrack = built
        currentSampleRate = sampleRate
        currentOutChannels = outChannels
        currentBufferBytes = bufferSizeBytes
        Log.i("AudioRelay", "AudioTrack 已就绪：sampleRate=$sampleRate channels=$outChannels buffer=$bufferSizeBytes")
        DiagLog.i("音轨", "新建音轨成功：${trackSnapshot(built)}")
        return built
    }

    private fun setVolume(volume: Float) {
        try {
            // Clamp volume between 0.0 and 1.0
            val clampedVolume = volume.coerceIn(0f, 1f)
            currentVolume = clampedVolume
            audioTrack?.setVolume(clampedVolume)
            Log.d("AudioRelay", "AudioTrack volume updated to: $clampedVolume")
        } catch (e: Exception) {
            Log.e("AudioRelay", "Error setting volume: ${e.message}", e)
        }
    }

    // Convert stereo s16le PCM to mono by averaging left and right channels
    private fun convertStereoToMono(stereoBuffer: ByteArray, length: Int): ByteArray {
        val monoSize = length / 2 // Each stereo frame (4 bytes) becomes mono frame (2 bytes)
        val monoBuffer = ByteArray(monoSize)

        var monoIndex = 0
        var i = 0
        while (i + 3 < length) {
            // Read left channel (2 bytes)
            val left = (stereoBuffer[i].toInt() and 0xFF) or ((stereoBuffer[i + 1].toInt() and 0xFF) shl 8)
            // Read right channel (2 bytes)
            val right = (stereoBuffer[i + 2].toInt() and 0xFF) or ((stereoBuffer[i + 3].toInt() and 0xFF) shl 8)

            // Average the channels (treat as signed 16-bit)
            val leftSigned = left.toShort().toInt()
            val rightSigned = right.toShort().toInt()
            val mono = ((leftSigned + rightSigned) / 2).toShort()

            // Write mono sample (2 bytes)
            monoBuffer[monoIndex] = (mono.toInt() and 0xFF).toByte()
            monoBuffer[monoIndex + 1] = ((mono.toInt() shr 8) and 0xFF).toByte()

            i += 4 // Move to next stereo frame
            monoIndex += 2 // Move to next mono frame
        }

        return monoBuffer
    }

    private fun calculateAudioLevels(buffer: ByteArray, size: Int): FloatArray {
        // Calculate 24 frequency bands by sampling the PCM data
        val bands = 24
        val levels = FloatArray(bands)
        val samplesPerBand = size / (bands * 2) // 2 bytes per sample (16-bit PCM)
        if (samplesPerBand <= 0) return levels

        for (i in 0 until bands) {
            var sum = 0f
            val start = i * samplesPerBand * 2
            val end = minOf(start + samplesPerBand * 2, size)

            for (j in start until end step 2) {
                if (j + 1 < size) {
                    // Convert two bytes to 16-bit sample
                    val sample = (buffer[j].toInt() and 0xFF) or ((buffer[j + 1].toInt() and 0xFF) shl 8)
                    val normalized = sample.toShort().toFloat() / 32768f
                    sum += kotlin.math.abs(normalized)
                }
            }

            levels[i] = (sum / samplesPerBand).coerceIn(0f, 1f)
        }

        return levels
    }

    private fun broadcastAudioLevels(levels: FloatArray) {
        try {
            val intent = Intent(ACTION_AUDIO_LEVEL)
            intent.setPackage(packageName)
            intent.putExtra(EXTRA_AUDIO_LEVELS, levels)
            sendBroadcast(intent)
        } catch (e: Exception) {
            // Silently ignore broadcast errors to avoid spam
        }
    }

    // 中文命名本次接收分支（日志里一眼能看出走的哪条链路）
    private fun branchName(framed: Boolean, isOpus: Boolean): String = when {
        !framed -> "官方裸PCM"
        isOpus -> "自研分帧Opus"
        else -> "自研分帧PCM"
    }

    // 统计一块已收到的 PCM 数据（帧数 / 字节数 / 峰值），首块额外记录现场预览
    private fun recordChunk(session: DiagSession, data: ByteArray, size: Int) {
        session.frames++
        session.bytes += size
        if (size > session.maxChunk) session.maxChunk = size
        var localPeak = 0
        var index = 0
        while (index + 1 < size) {
            val sample = ((data[index].toInt() and 0xFF) or (data[index + 1].toInt() shl 8)).toShort().toInt()
            val abs = if (sample < 0) -sample else sample
            if (abs > localPeak) localPeak = abs
            index += 2
        }
        if (localPeak > session.peak) session.peak = localPeak
        if (!session.firstChunkLogged) {
            session.firstChunkLogged = true
            val preview = data.take(minOf(size, 16)).joinToString(" ") { "%02x".format(it) }
            DiagLog.i(
                "接收",
                "首个音频数据到达：分支=${session.branch} 长度=$size 字节，前 ${minOf(size, 16)} 字节=[$preview]，峰值=${peakDb(localPeak)}"
            )
        }
    }

    // 每 5 秒输出一次周期诊断（帧率 / 码率 / 输出电平 / 音轨状态），仅日志开关打开时记录
    private fun reportSession(session: DiagSession, track: AudioTrack) {
        val now = System.currentTimeMillis()
        val elapsed = now - session.lastAt
        if (elapsed < REPORT_INTERVAL_MS) return
        val frames = session.frames - session.lastFrames
        val bytes = session.bytes - session.lastBytes
        val fps = frames * 1000.0 / elapsed
        val kbps = bytes * 8.0 / elapsed
        DiagLog.d(
            "周期",
            "分支=${session.branch} 帧率=${String.format(Locale.US, "%.1f", fps)}/s " +
                    "码率=${String.format(Locale.US, "%.0f", kbps)}kbps 累计帧=${session.frames} " +
                    "累计=${session.bytes}字节 输出峰值=${peakDb(session.peak)}"
        )
        DiagLog.d("周期", "音轨：${trackSnapshot(track)}；${mediaVolumeInfo()}")
        session.lastAt = now
        session.lastFrames = session.frames
        session.lastBytes = session.bytes
    }

    // 汇总一次会话的接收结果（判断链路走到哪一步的核心依据）
    private fun sessionSummary(session: DiagSession): String {
        val seconds = (System.currentTimeMillis() - session.startedAt) / 1000.0
        return "分支=${session.branch} 时长=${String.format(Locale.US, "%.1f", seconds)}s " +
                "收到Opus包=${session.packets} 写入音轨块数=${session.frames} " +
                "写入字节=${session.bytes} 单块最大=${session.maxChunk}字节 " +
                "全程峰值=${peakDb(session.peak)}"
    }

    // 采集当前音轨状态（音轨已释放时给出中文说明）
    private fun audioTrackSnapshot(): String {
        val track = audioTrack ?: return "音轨已为空"
        return trackSnapshot(track)
    }

    // 采集 AudioTrack 的运行时状态（排查「有数据但没声音」的关键依据）
    private fun trackSnapshot(track: AudioTrack): String {
        return try {
            val underruns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) track.underrunCount else -1
            val head = track.playbackHeadPosition
            "state=${stateName(track.state)} playState=${playStateName(track.playState)} " +
                    "采样率=${track.sampleRate} 声道=${track.channelCount} " +
                    "缓冲=${currentBufferBytes}字节 设定音量=$currentVolume 欠载=$underruns 播放头=$head"
        } catch (e: Exception) {
            "读取音轨状态失败：${e.message}"
        }
    }

    // 把 AudioTrack.state 翻译成中文
    private fun stateName(state: Int): String = when (state) {
        AudioTrack.STATE_INITIALIZED -> "已初始化"
        AudioTrack.STATE_NO_STATIC_DATA -> "静态数据未就绪"
        AudioTrack.STATE_UNINITIALIZED -> "未初始化"
        else -> "未知($state)"
    }

    // 把 AudioTrack.playState 翻译成中文（stop() 不会改变 state，只能靠它判断是否真的在播）
    private fun playStateName(playState: Int): String = when (playState) {
        AudioTrack.PLAYSTATE_PLAYING -> "播放中"
        AudioTrack.PLAYSTATE_PAUSED -> "已暂停"
        AudioTrack.PLAYSTATE_STOPPED -> "已停止"
        else -> "未知($playState)"
    }

    // 采集系统媒体音量与输出设备（不少「完全没声音」其实是媒体音量为 0）
    private fun mediaVolumeInfo(): String {
        return try {
            val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val muted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.isStreamMute(AudioManager.STREAM_MUSIC)
            } else {
                false
            }
            val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString("/") { it.type.toString() }
            } else {
                "未知"
            }
            "媒体音量=$current/$max 静音=$muted 输出设备类型=$devices"
        } catch (e: Exception) {
            "媒体音量读取失败：${e.message}"
        }
    }

    // 把 PCM 峰值转成 dBFS 文本（峰值 0 表示采样点全为 0，即静音）
    private fun peakDb(peak: Int): String {
        if (peak <= 0) return "-∞dBFS(全静音)"
        return String.format(Locale.US, "%.1fdBFS", 20 * Math.log10(peak / 32768.0))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("AudioRelay", "onDestroy called, shutting down service.")
        isServerRunning = false
        // 停止地址上报（线程与网络回调）
        try { AddressReporter.stop(this) } catch (e: Exception) { /* 忽略 */ }
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e("AudioRelay", "Error closing server socket on destroy.", e)
        }
        serverThread?.interrupt() // Interrupt the thread
        // 停止出声：服务销毁时必须彻底释放音轨，否则会「点了停止还在响」
        // （会话级只用 pause+flush，见禁令 #3；这里是服务销毁，可以 release）
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w("AudioRelay", "Error releasing AudioTrack on destroy.", e)
        }
        audioTrack = null
        currentSampleRate = 0
        currentOutChannels = 0
        // 停止中转链路
        stopRelayLink()
        // 释放全程保活资源与两个独立锚点
        try {
            ResourceHolder.releaseAll(this)
            OnePixelOverlay.hide(this)
            SilentPlayer.release()
        } catch (e: Exception) {
            DiagLog.w("服务", "释放保活资源失败：${e.message}")
        }
        // Ensure UI knows we're disconnected when service stops
        try {
            val bcast = Intent("com.devindeed.aurelay.CLIENT_CONNECTION")
            bcast.setPackage(packageName)
            bcast.putExtra("connected", false)
            bcast.putExtra("client_ip", "")
            sendBroadcast(bcast)
            Log.i("AudioRelay", "Broadcast sent from onDestroy: CLIENT_CONNECTION connected=false")
            lastClientIp = ""
            lastClientName = ""
        } catch (ex: Exception) {
            Log.e("AudioRelay", "Failed to broadcast disconnection on destroy: ${ex.message}", ex)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
