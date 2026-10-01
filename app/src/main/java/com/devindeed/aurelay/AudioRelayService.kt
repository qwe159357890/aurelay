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
import android.media.AudioTrack
import android.os.Build
import android.Manifest
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.security.KeyStore
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
    private var useTls: Boolean = false // Default to plain TCP for easier testing (change to true for production TLS)
    private var audioTrack: AudioTrack? = null

    // 当前 AudioTrack 采用的采样率与声道数（流协商结果，用于判断是否需要重建）
    private var currentSampleRate: Int = 0
    private var currentOutChannels: Int = 0

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

        // Discovery constants — desktop client will broadcast DISCOVERY_REQUEST
        // and the service will reply with DISCOVERY_RESPONSE;<port>;<name>
        const val DISCOVERY_PORT = 5002
        const val DISCOVERY_REQUEST = "AURELAY_DISCOVER"
        const val DISCOVERY_RESPONSE = "AURELAY_RESPONSE"
        const val CONNECT_REQUEST = "AURELAY_CONNECT"
        const val DISCONNECT_REQUEST = "AURELAY_DISCONNECT"

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
    }

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "AudioRelay")
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()
        startForeground(1, buildNotification())
        // Start discovery responder so desktop clients can find this device
        startDiscoveryResponder()
        // 启动地址上报（外网模式：把本机 IPv4/IPv6 同步到用户自己的中心服务器）
        AddressReporter.start(this)
        Log.i("AudioRelay", "Service onCreate called, foreground started.")
    }

    private var discoveryThread: Thread? = null

    private fun startDiscoveryResponder() {
        if (discoveryThread != null && discoveryThread!!.isAlive) return
        discoveryThread = Thread {
            var socket: DatagramSocket? = null
        try {
                socket = DatagramSocket(DISCOVERY_PORT)
                socket.broadcast = true
                val buf = ByteArray(1024)
                while (!Thread.currentThread().isInterrupted) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length).trim()
                        if (msg == DISCOVERY_REQUEST) {
                            // Respond with service info — desktop will use packet source address
                            val deviceName = Build.MODEL.ifEmpty { "Android Device" }.replace(";", "_")
                            val response = "$DISCOVERY_RESPONSE;$AUDIO_PORT;$deviceName"
                            val respData = response.toByteArray()
                            val respPacket = DatagramPacket(respData, respData.size, packet.address, packet.port)
                            socket.send(respPacket)
                            Log.d("AudioRelay", "Discovery request responded to ${packet.address} with name: $deviceName")
                        } else if (msg.startsWith(CONNECT_REQUEST)) {
                            // A sender wants to connect — send confirmation request to UI
                            try {
                                val parts = msg.split(";")
                                val senderName = parts.getOrNull(1) ?: "Android Device"
                                // Store the sender name for notification updates
                                lastClientName = senderName
                                val bcast = Intent(MainActivity.ACTION_CONNECTION_REQUEST)
                                bcast.setPackage(packageName)
                                bcast.putExtra("client_ip", packet.address.hostAddress ?: "")
                                bcast.putExtra("client_name", senderName)
                                sendBroadcast(bcast)
                                Log.i("AudioRelay", "Connect request from $senderName (${packet.address.hostAddress}), sent to UI for confirmation")
                            } catch (ex: Exception) {
                                Log.e("AudioRelay", "Failed to broadcast connect request: ${ex.message}", ex)
                            }
                        } else if (msg.startsWith("AURELAY_ACCEPT")) {
                            // Connection accepted - could be received by sender OR receiver
                            // If receiver gets this, it means the sender acknowledged the acceptance
                            try {
                                val bcast = Intent("com.devindeed.aurelay.CLIENT_CONNECTION")
                                bcast.setPackage(packageName)
                                bcast.putExtra("connected", true)
                                bcast.putExtra("client_ip", packet.address.hostAddress ?: "")
                                sendBroadcast(bcast)
                                Log.i("AudioRelay", "Connection accepted notification from ${packet.address.hostAddress}")
                            } catch (ex: Exception) {
                                Log.e("AudioRelay", "Failed to broadcast connection accepted: ${ex.message}", ex)
                            }
                        } else if (msg.startsWith("AURELAY_REJECT")) {
                            // Connection rejected by receiver
                            try {
                                val bcast = Intent("com.devindeed.aurelay.CLIENT_CONNECTION")
                                bcast.setPackage(packageName)
                                bcast.putExtra("connected", false)
                                bcast.putExtra("client_ip", "")
                                sendBroadcast(bcast)
                                Log.i("AudioRelay", "Connection rejected by ${packet.address.hostAddress}")
                            } catch (ex: Exception) {
                                Log.e("AudioRelay", "Failed to broadcast connection rejected: ${ex.message}", ex)
                            }
                        } else if (msg.startsWith(DISCONNECT_REQUEST)) {
                            try {
                                val bcast = Intent("com.devindeed.aurelay.CLIENT_CONNECTION")
                                bcast.setPackage(packageName)
                                bcast.putExtra("connected", false)
                                bcast.putExtra("client_ip", "")
                                sendBroadcast(bcast)
                                Log.i("AudioRelay", "Disconnect request received from ${packet.address.hostAddress}, broadcasted CLIENT_CONNECTION false")
                            } catch (ex: Exception) {
                                Log.e("AudioRelay", "Failed to broadcast disconnect request: ${ex.message}", ex)
                            }
                        }
                    } catch (e: Exception) {
                        // ignore and continue
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioRelay", "Discovery responder failed: ${e.message}")
            } finally {
                try { socket?.close() } catch (e: Exception) {}
            }
        }
        discoveryThread?.start()
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
                if (serverThread == null || !serverThread!!.isAlive) {
                    serverThread = Thread { startAudioServer() }
                    serverThread?.start()
                    Log.i("AudioRelay", "Service onStartCommand: useTls=$useTls, server thread started.")
                }
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
            "Streaming from $lastClientName"
        } else {
            "Ready to receive audio"
        }

        val builder =
            NotificationCompat.Builder(this, "audioRelayChannel")
                .setContentTitle("Aurelay")
                .setContentText(displayText)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(openAppPendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Disconnect",
                    disconnectPendingIntent
                )
                .addAction(
                    android.R.drawable.ic_menu_preferences,
                    "Open App",
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
                    "Audio Relay Channel",
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

            while (isServerRunning) {
                try {
                    // Accept may throw SSLException if a non-TLS client connects to an SSLServerSocket
                    val maybeClient = try {
                        serverSocket?.accept()
                    } catch (sslEx: javax.net.ssl.SSLException) {
                        Log.e("AudioRelay", "SSL exception during accept (possible TLS/plain mismatch): ${sslEx.message}", sslEx)
                        null
                    }

                    maybeClient?.use { client ->
                        handleClient(client)
                    }
                } catch (e: IOException) {
                    if (isServerRunning) {
                        Log.e("AudioRelay", "Error accepting client or reading data: ", e)
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

    // 处理单个客户端连接：先读流包头判定编码，再进入对应解码链路
    private fun handleClient(client: Socket) {
        // Configure socket for robust streaming
        client.soTimeout = 100 // 100ms timeout prevents indefinite blocking
        client.tcpNoDelay = true // Disable Nagle's algorithm for lower latency
        client.receiveBufferSize = 4096 // Smaller buffer for lower latency
        Log.i("AudioRelay", "Client connected: ${client.inetAddress.hostAddress}")

        // Update notification with sender name (check runtime permission on Android 13+)
        notifyConnected()

        // Broadcast connection event so UI can update
        try {
            val bcast = Intent("com.devindeed.aurelay.CLIENT_CONNECTION")
            bcast.setPackage(packageName)
            bcast.putExtra("connected", true)
            bcast.putExtra("client_ip", client.inetAddress.hostAddress)
            sendBroadcast(bcast)
            Log.i("AudioRelay", "Broadcast sent: CLIENT_CONNECTION connected=true ip=${client.inetAddress.hostAddress}")
        } catch (ex: Exception) {
            Log.e("AudioRelay", "Failed to broadcast client connected: ${ex.message}", ex)
        }

        lastClientIp = try { client.inetAddress.hostAddress ?: "" } catch (ex: Exception) { "" }

        var decoder: OpusDecoder? = null
        try {
            val rawInput = client.getInputStream()
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
            } else {
                Log.i("AudioRelay", "未检测到自研流包头，按官方裸 PCM 流处理（44.1kHz / 立体声）")
            }

            // Opus 解码输出固定为 48kHz，音频轨必须按该采样率创建，否则播放会变速
            val isOpus = framed && codec == CODEC_OPUS
            val playbackRate = if (isOpus) 48000 else streamSampleRate

            // 输出声道数：设备支持立体声就输出立体声，否则降为单声道
            val outChannels = if (supportsStereo(playbackRate)) 2 else 1
            val track = ensureAudioTrack(playbackRate, outChannels)
            if (track == null) {
                Log.e("AudioRelay", "AudioTrack 创建失败，断开本次连接")
                return
            }

            // Opus 模式需要系统解码器（Android 自带 software opus decoder）
            if (isOpus) {
                decoder = OpusDecoder(streamChannels, playbackRate)
                if (!decoder.start()) {
                    Log.e("AudioRelay", "Opus 解码器初始化失败，断开本次连接")
                    return
                }
            }

            if (framed) {
                if (isOpus) {
                    streamFramedOpus(input, track, decoder, outChannels, streamChannels * 2)
                } else {
                    streamFramedPcm(input, track, outChannels)
                }
            } else {
                streamRawPcm(input, track, outChannels)
            }
        } catch (e: Exception) {
            Log.e("AudioRelay", "客户端会话异常：${e.message}", e)
        } finally {
            try { decoder?.stop() } catch (e: Exception) { /* 忽略 */ }
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
    private fun streamRawPcm(input: InputStream, track: AudioTrack, outChannels: Int) {
        val buffer = ByteArray(4096)
        var read: Int
        var frameCount = 0
        var consecutiveErrors = 0
        val frameSize = 4 // 官方流固定为立体声：2 字节采样 * 2 声道
        while (isServerRunning && consecutiveErrors < 5) {
            try {
                read = input.read(buffer)
                if (read == -1) break
                consecutiveErrors = 0
                if (read > 0) {
                    writeToTrack(track, buffer, read, frameSize, outChannels)
                    if (++frameCount % 5 == 0) {
                        broadcastAudioLevels(calculateAudioLevels(buffer, read))
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                consecutiveErrors++
                Log.w("AudioRelay", "Read error $consecutiveErrors/5: ${e.message}")
            }
        }
    }

    // 自研分帧 PCM 流：每帧 = 4 字节大端长度 + PCM 数据
    private fun streamFramedPcm(input: InputStream, track: AudioTrack, outChannels: Int) {
        val frameSize = outChannels * 2
        var frameCount = 0
        while (isServerRunning) {
            val payload = readFrame(input) ?: break
            if (payload.isEmpty()) continue
            writeToTrack(track, payload, payload.size, frameSize, outChannels)
            if (++frameCount % 5 == 0) {
                broadcastAudioLevels(calculateAudioLevels(payload, payload.size))
            }
        }
    }

    // 自研分帧 Opus 流：每帧为 1 个 Opus 包，解码后写 AudioTrack
    private fun streamFramedOpus(
        input: InputStream,
        track: AudioTrack,
        decoder: OpusDecoder?,
        outChannels: Int,
        inputFrameSize: Int
    ) {
        if (decoder == null) return
        var frameCount = 0
        while (isServerRunning) {
            val packet = readFrame(input) ?: break
            if (packet.isEmpty()) continue
            decoder.decode(packet, packet.size) { pcm, size ->
                writeToTrack(track, pcm, size, inputFrameSize, outChannels)
                if (++frameCount % 5 == 0) {
                    broadcastAudioLevels(calculateAudioLevels(pcm, size))
                }
            }
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
            if (outChannels == 2 && inputFrameSize == 4) {
                track.write(buffer, 0, alignedBytes, writeMode)
            } else if (outChannels == 1 && inputFrameSize == 4) {
                val mono = convertStereoToMono(buffer, alignedBytes)
                track.write(mono, 0, mono.size, writeMode)
            } else {
                // 输入已是单声道，直接写
                track.write(buffer, 0, alignedBytes, writeMode)
            }
        } catch (e: Exception) {
            Log.e("AudioRelay", "AudioTrack write exception: ${e.message}", e)
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
                } catch (e: Exception) {
                    Log.e("AudioRelay", "AudioTrack 恢复播放失败：${e.message}", e)
                }
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
        Log.i("AudioRelay", "AudioTrack 已就绪：sampleRate=$sampleRate channels=$outChannels buffer=$bufferSizeBytes")
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
        try {
            discoveryThread?.interrupt()
            discoveryThread = null
        } catch (e: Exception) {
            // ignore
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
