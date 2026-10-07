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
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
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
    // 服务启动时刻：通知用它显示「已运行 X 分 X 秒」
    private var serviceStartAt: Long = System.currentTimeMillis()
    private lateinit var notificationManager: NotificationManagerCompat
    private var serverThread: Thread? = null
    @Volatile private var lastClientIp: String = ""
    @Volatile private var lastClientName: String = ""

    @Volatile private var isServerRunning = true
    private var serverSocket: ServerSocket? = null

    // 中转客户端（蜂窝链路）与其接收线程
    private val relayClient = RelayClient()
    private var relayThread: Thread? = null

    // 周期链路自检线程：网络回调（NetworkWatcher）在个别机型上可能漏报
    // 「WiFi→蜂窝」切换（实测 19:18 OnePlus LE2120 切蜂窝后类型始终停在 WIFI），
    // 导致中转 R 从不启动。此线程每 10 秒主动 detect 一次真实网络类型，
    // 与当前已保存的链路模式比对，不一致就纠正，作为兜底安全网。
    private var linkSelfCheckThread: Thread? = null

    // 自检线程的运行标志（独立于 isServerRunning，避免「停止会话」误杀自检线程）
    @Volatile private var linkSelfCheckRunning = false
    private var useTls: Boolean = false // Default to plain TCP for easier testing (change to true for production TLS)
    private var audioTrack: AudioTrack? = null

    // readHeader 因「包头没读够就超时」而放弃时置位。
    // 上层据此区分「放弃本次会话」与「这是官方裸 PCM」—— 混为一谈会把
    // 48kHz 的 Opus 按 44.1kHz 裸 PCM 播放，听感即严重失真。
    @Volatile private var headerIncomplete: Boolean = false

    // 当前一路客户端会话的 socket（用于「停止会话」时主动关闭，见 stopAudioSession）
    @Volatile private var currentClientSocket: Socket? = null

    // 音频会话代次：每开始一路新的流处理（handleStream）就 +1，会话自己记下当时的代次。
    // 用于「音轨暂停」的归属判定——旧会话的 finally 若在更新会话已建立后仍去 pause 音轨，
    // 会把新会话刚 play 起来的音轨又停掉，表现为「数据在写、播放头却纹丝不动、全程无声」
    // （实测 16:07:20~16:08:23 切换蜂窝后音轨 playState=已暂停、播放头=0 长达 81 秒）。
    @Volatile private var sessionGeneration: Long = 0

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

        // 常驻前台通知的通知 ID（设计文档 8.3 第 4 项：App 运行全程常驻）。
        // 原先 onCreate 里硬编码写 1，会话停止后要刷新通知就必须能复用到同一个 ID，
        // 否则会再贴出第二条通知，因此提为常量。
        const val NOTIFICATION_ID = 1001

        // 「手机放音」会话通知的 ID：只在真有电脑连进来播放时才出现，
        // 会话结束即撤销；与常驻通知（1001）、电脑放音通知（AudioCaptureService
        // 的 1002）并列，共三条，互不影响。
        const val SESSION_NOTIFICATION_ID = 1003

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
        // 服务启动时刻：通知用它显示「已运行 X 分 X 秒」
        serviceStartAt = System.currentTimeMillis()
        // 记下本次打开 App 的时刻，界面据此显示「已连续运行」时长。
        // 只在服务首次创建时写入：服务存活期间的多次通知刷新不应重置计时。
        // AppPrefs 没有 Long 版接口，这里存十进制字符串。
        AppPrefs.setString(this, AppPrefs.KEY_APP_OPEN_AT, serviceStartAt.toString())
        // 安装诊断日志器（开关来自设置页，本地文件位于 diag/aurelay-diag.log）
        DiagLog.install(this)
        mediaSession = MediaSessionCompat(this, "AudioRelay")
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // 保活资源（ResourceHolder / 一像素锚点 / 无声播放）与地址上报已整体移交
        // KeepAliveService 无条件持有——本服务退化为纯接收，只保留网络切换监听。
        setupNetworkWatcher()
        Log.i("AudioRelay", "Service onCreate called, foreground started.")
        DiagLog.i("服务", "接收服务已启动：监听端口 $AUDIO_PORT，TLS=$useTls")
        DiagLog.i("环境", DiagLog.environment(this))
    }

    /**
     * 注册网络切换监听：WiFi 走局域网直连，蜂窝走服务器中转
     *
     * 保活资源（ResourceHolder / 一像素锚点 / 无声播放）与地址上报已移交
     * KeepAliveService 统一持有，本服务只保留「接收音频」必需的链路切换逻辑。
     *
     * :return: 无返回值
     */
    private fun setupNetworkWatcher() {
        // 网络切换时自动切换链路：WiFi 走局域网直连，蜂窝走服务器中转
        NetworkWatcher.addListener { type ->
            DiagLog.i("服务", "检测到网络切换：$type，重新选择链路")
            // 切链路前先清掉上一链路残留的对端信息：否则「蜂窝→WiFi」切换后，
            // 若新的直连客户端迟迟没接进来，界面会一直卡在旧的
            // 「已连接：中转服务器:5000」，让用户误以为模式没切。
            // 清空后界面立即回到「等待连接」，等新链路真连上再显示新对端。
            clearPeerInfo("网络切换")
            if (type == NetworkWatcher.NetType.WIFI) {
                stopRelayLink()
                startLanServer()
            } else {
                stopLanServer()
                startRelayLink()
            }
        }
        // 周期自检兜底：网络回调可能漏报切换（见 linkSelfCheckThread 字段注释），
        // 这里每 10 秒主动探测一次真实网络类型，与当前链路比对纠正。
        if (linkSelfCheckThread == null || linkSelfCheckThread!!.isAlive.not()) {
            linkSelfCheckRunning = true
            linkSelfCheckThread = Thread({ linkSelfCheckLoop() }, "AurelayLinkSelfCheck").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    // 周期链路自检循环：发现「实际网络类型」与「当前链路」不符就纠正
    private fun linkSelfCheckLoop() {
        while (linkSelfCheckRunning) {
            try {
                Thread.sleep(10_000L)
            } catch (e: InterruptedException) {
                break
            }
            if (!linkSelfCheckRunning) break
            try {
                val actual = NetworkWatcher.detect(this)
                val currentMode = AppPrefs.getString(this, AppPrefs.KEY_LAST_LINK_MODE, "lan")
                val expectLan = actual == NetworkWatcher.NetType.WIFI
                val currentlyLan = currentMode == "lan"
                if (expectLan != currentlyLan) {
                    DiagLog.w(
                        "服务",
                        "链路自检发现偏差：实际网络=$actual 当前链路=${if (currentlyLan) "局域网直连" else "服务器中转"}，" +
                            "自动纠正为${if (expectLan) "局域网直连" else "服务器中转"}"
                    )
                    clearPeerInfo("链路自检纠正")
                    if (expectLan) {
                        stopRelayLink()
                        startLanServer()
                    } else {
                        stopLanServer()
                        startRelayLink()
                    }
                }
            } catch (e: Exception) {
                DiagLog.w("服务", "链路自检失败：${e.message}")
            }
        }
    }

    // 清空对端信息并广播断开（切链路 / 停止会话时调用，让界面及时回到「等待连接」）
    private fun clearPeerInfo(reason: String) {
        lastClientIp = ""
        lastClientName = ""
        try {
            val bcast = Intent("com.devindeed.aurelay.CLIENT_CONNECTION")
            bcast.setPackage(packageName)
            bcast.putExtra("connected", false)
            bcast.putExtra("client_ip", "")
            sendBroadcast(bcast)
            DiagLog.i("连接", "已清空对端信息（$reason）")
        } catch (ex: Exception) {
            DiagLog.w("连接", "广播断开失败（$reason）：${ex.message}")
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
        // 切网络时，除了关监听 socket，还要关掉「已 accept 的当前客户端连接」。
        // 只关监听 socket 的话，已建立的直连流还会继续读（isServerRunning 已被
        // startRelayLink 置回 true，旧直连流的 while 循环不会退），与新中转流并发
        // 写同一个音轨——这就是切网络后音轨状态错乱的并发根源。
        try {
            currentClientSocket?.close()
        } catch (e: Exception) {
            DiagLog.w("服务", "关闭当前客户端连接失败：${e.message}")
        }
        currentClientSocket = null
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
     * 停止当前音频会话（**不销毁服务**，前台通知保持常驻）
     *
     * 与设计文档 8.3 第 4 项一致：用户点「停止」只结束这一次音频会话，
     * 服务与前台通知继续存活以维持保活。旧实现直接 stopSelf()，
     * 通知一消失，国产 ROM 下一次后台清理就把进程收走，
     * 典型后果是「手机停了上报，PC 端随之查不到设备」。
     *
     * :return: 无返回值
     */
    private fun stopAudioSession() {
        DiagLog.i("服务", "收到停止指令：结束音频会话并停止接收，服务与通知保持常驻")
        // 先停接收（关监听 + 停中转出站），再断当前会话。顺序很重要：
        // 只断会话不关监听的话，电脑端的自动重连会在几十毫秒内重新连上，
        // 表现为「点了停止，按钮变开始，但声音一直在响」（实测日志 09:05:40 两次复现）。
        // isServerRunning 一并置 false：所有读流循环都拿它当退出条件。
        stopLanServer()
        stopRelayLink()
        // 再断开当前会话的客户端 socket：让接收循环走到退出分支，
        // 顺便由 handleStream 的 finally 完成音轨 pause+flush。
        // 不先断的话，接收线程会一直阻塞在往已暂停音轨里 write，会话结束不了。
        try {
            currentClientSocket?.close()
        } catch (e: Exception) {
            DiagLog.w("服务", "关闭客户端连接失败：${e.message}")
        }
        currentClientSocket = null
        // 会话级停止按禁令 #3：**只 pause + flush，绝不 release**——
        // release 过的音轨状态是 STATE_UNINITIALIZED，下次复用会在
        // AudioTrack.stop()/flush() 的 precondition 检查上抛 IllegalStateException。
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            DiagLog.w("服务", "暂停音轨失败：${e.message}")
        }
        // 清掉对端信息，让通知与界面回到「等待」
        lastClientIp = ""
        lastClientName = ""
        try {
            val bcast = Intent("com.devindeed.aurelay.CLIENT_CONNECTION")
            bcast.setPackage(packageName)
            bcast.putExtra("connected", false)
            bcast.putExtra("client_ip", "")
            sendBroadcast(bcast)
        } catch (e: Exception) {
            DiagLog.w("服务", "广播断开状态失败：${e.message}")
        }
        // 常驻通知的文案同步回「等待接收音频」
        refreshNotification()
        // 会话通知随之撤销（常驻通知仍在，这是两者的关键区别）
        updateSessionNotification(false)
    }

    /**
     * 张贴或撤销「手机放音」会话通知
     *
     * 与常驻通知（NOTIFICATION_ID）分开：常驻那条 App 运行期间一直在，
     * 本条只在真的有电脑连进来播放时出现，会话结束即撤销。
     *
     * :param connected: True 张贴会话通知，False 撤销
     * :return: 无返回值
     */
    private fun updateSessionNotification(connected: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("AudioRelay", "Missing POST_NOTIFICATIONS permission; skipping session notification")
            return
        }
        @SuppressLint("MissingPermission")
        if (connected) {
            notificationManager.notify(SESSION_NOTIFICATION_ID, buildSessionNotification())
        } else {
            notificationManager.cancel(SESSION_NOTIFICATION_ID)
        }
    }

    /**
     * 构造「手机放音」会话通知（正在接收电脑声音）
     *
     * :return: 通知对象
     */
    private fun buildSessionNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 停止按钮走 ACTION_STOP_SERVICE：现在它只停会话、不销毁服务，
        // 因此点了之后本条通知消失，常驻通知仍留在通知栏。
        val stopIntent = Intent(this, AudioRelayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val peer = if (lastClientName.isNotEmpty()) lastClientName
        else lastClientIp.ifEmpty { "电脑" }

        // 文案同样按「标题=状态 / 正文=来源与动作」两行式，避免长句挤成一片
        return NotificationCompat.Builder(this, "audioRelayChannel")
            .setContentTitle("正在接收电脑声音")
            .setContentText("$peer · 点此管理")
            .setSmallIcon(com.devindeed.aurelay.R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止接收",
                stopPendingIntent
            )
            // 与常驻通知同理：不挂 MediaStyle，否则会被折叠成媒体样式、
            // 既破坏两行文案又可能失去 ongoing 的不可划走语义
            .build()
    }

    /**
     * 用当前状态重建并重新张贴前台通知
     *
     * 会话开始/结束都要调用一次，保证常驻通知上的文案与实际状态一致。
     *
     * :return: 无返回值
     */
    private fun refreshNotification() {
        // 直接复用 notifyConnected：它已经带好了 POST_NOTIFICATIONS 权限检查，
        // 不另写一套，避免两处检查逻辑将来走偏。
        notifyConnected()
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
        // 已在正常监听（如保活把服务又拉起一次）：直接跳过，避免重复绑定端口
        val existing = serverSocket
        if (isServerRunning && existing != null && !existing.isClosed &&
            serverThread != null && serverThread!!.isAlive
        ) {
            return
        }
        isServerRunning = true
        // 「停止」后再点「开始」：旧监听线程可能还没退干净，先等它收尾，
        // 否则新线程绑定 5000 端口会 BindException
        serverThread?.let { old ->
            if (old.isAlive) {
                try { old.join(500) } catch (_: InterruptedException) { }
            }
        }
        serverThread = Thread({ startAudioServer() }, "AurelayLanServer").also { it.start() }
        // 同理：点「开始」后立刻上报，PC 端不用等一个上报周期
        reportNow("start-lan")
        DiagLog.i("服务", "已选择局域网直连：监听 $AUDIO_PORT 端口")
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
        // 中转链路同样是「接收中」，必须把 isServerRunning 置回 true。
        // 切蜂窝时 NetworkWatcher 会先 stopLanServer()（把它置 false），
        // 若这里不复位，中转 handleStream 里的 while(isServerRunning) /
        // if(!isServerRunning) 会立即退出，一个字节都收不到——这正是实测
        // 「12:56 之后手机 0 秒就结束、全程无声音」的直接根因。
        isServerRunning = true
        // 中转流还活着（线程在跑、客户端在运行态）：跳过，避免重复出站
        if (relayThread != null && relayThread!!.isAlive && relayClient.isRunning()) return
        // 「停止」后再点「开始」：旧线程可能还阻塞在中转管道的读上，先等它退场
        relayThread?.let { old ->
            if (old.isAlive) {
                try { old.join(500) } catch (_: InterruptedException) { }
            }
        }
        // 中转客户端若带着上一轮的残骸（running 已false 但 WebSocket 引用还在），
        // 必须先彻底停一次再启动，否则新 connectOnce 会与旧连接抢同一个 OkHttp 管道，
        // 表现为「点开始后手机在线、PC 端却一直连不上」。
        try {
            relayClient.stop()
        } catch (e: Exception) {
            DiagLog.w("中转", "重启前清理旧中转连接失败：${e.message}")
        }
        relayThread = Thread({
            try {
                relayClient.start(this@AudioRelayService, RelayProtocol.ROLE_RECEIVER, AUDIO_PORT)
                // 中转客户端断线会自动重连，每次重连（onOpen）都会重建管道。
                // 这里必须循环消费：handleStream 读完（读到 EOF）后，只要中转
                // 客户端还在运行（可能正处于断线重连中），就继续等新流再消费，
                // 否则「重连成功后没人读管道 → 手机完全没声音」（实测 12:56-12:58）。
                while (relayClient.isRunning()) {
                    val input = relayClient.audioInput()
                    if (input == null) {
                        // 断线重连中，管道尚未重建，稍等再试
                        try {
                            Thread.sleep(100)
                        } catch (e: InterruptedException) {
                            break
                        }
                        continue
                    }
                    handleStream(input, "中转服务器")
                }
            } catch (e: Exception) {
                DiagLog.e("中转", "中转接收异常", e)
            }
        }, "AurelayRelayStream").also { it.start() }
        // 用户点「开始」后要**立刻**把当前链路状态同步到中心服务器，
        // 否则 PC 端要等到下个周期（最长 60 秒）才知道这台手机已就绪。
        reportNow("start-relay")
        DiagLog.i("服务", "已选择服务器中转：主动出站连接中转服务器")
    }

    /**
     * 立即上报一次地址与链路状态
     *
     * :param reason: 触发原因（仅进日志，便于排查是哪条路径触发的）
     * :return: 无返回值
     */
    private fun reportNow(reason: String) {
        try {
            Thread({
                try {
                    AddressReporter.reportOnce(applicationContext)
                    DiagLog.i("上报", "已立即上报（触发点：$reason）")
                } catch (e: Exception) {
                    DiagLog.w("上报", "立即上报失败（$reason）：${e.message}")
                }
            }, "AurelayReportNow").also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            DiagLog.w("上报", "启动立即上报线程失败：${e.message}")
        }
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
                // 设计文档 8.3 第 4 项：通知常驻后，「停止」**只停音频会话**，
                // 不能 stopSelf()——服务一销毁通知就消失，保活前台也就没了，
                // 国产 ROM 下一次清理就把进程收走（表现为「地址上报停了，PC 查不到」）。
                Log.d("AudioRelay", "Stopping audio session, service stays alive for keep-alive")
                stopAudioSession()
                return START_STICKY
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

        // 常驻通知文案：借鉴 MicYou 的「标题=状态 / 正文=极短动作提示」两行式。
        // 原先正文写成「后台常驻运行 · 等待接收音频」这种长句，在通知栏里被
        // 系统的「正在其他应用的上层运行 / 显示内容…」提示挤成一坨，很难读。
        val displayText = if (lastClientName.isNotEmpty()) {
            "正在接收电脑声音 · 点此管理"
        } else {
            "声音中继已开启 · 点此管理"
        }

        val builder =
            NotificationCompat.Builder(this, "audioRelayChannel")
                // 标题只说状态，不塞动作
                .setContentTitle("Aurelay 声音中继")
                .setContentText(displayText)
                .setSmallIcon(com.devindeed.aurelay.R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                // ⚠️ 常驻三件套：ongoing + 不显示时间 + 不重复提醒。
                // 之前这里既setShowWhen(true) 又 setUsesChronometer(true)，
                // 通知右侧的时间列会把「标题/正文」两行式挤歪，视觉上像没改成功；
                // 运行时长已由 App 界面的「已连续运行 X」承担，通知里不必重复。
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openAppPendingIntent)
                // ⚠️ 不要挂 MediaStyle，也不要加 action 按钮。
                // MediaStyle 会把通知折叠成媒体播放器样式，在部分国产 ROM 上
                // 既不遵守 ongoing（能被划走）、又会把两行文案压成一行小字——
                // 用户实测「文案没改成功 + 仍可划走」正是这两条叠加的结果。
                // 停止操作统一在 App 内完成，通知只承担「常驻 + 点击回到 App」。
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 与采集端一致：按 8.3「全程保活」用 IMPORTANCE_HIGH
            //（Android 8+ 通知优先级由渠道决定，LOW 会被系统折叠、服务更易被回收）。
            // 关闭声音与震动，只提升系统重视程度，不打扰用户。
            val channel =
                NotificationChannel(
                    "audioRelayChannel",
                    "音频中继服务",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "手机放音时显示接收状态，用于保持后台运行"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
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

            // The listening socket may be closed in advance by "Stop" (stopAudioSession), use a local reference and check for closure each loop,
            // to avoid the old thread, holding an already-closed socket, spinning idle and spamming logs
            val socket = serverSocket
            while (isServerRunning && socket != null && !socket.isClosed) {
                try {
                    // Accept may throw SSLException if a non-TLS client connects to an SSLServerSocket
                    val maybeClient = try {
                        socket.accept()
                    } catch (sslEx: javax.net.ssl.SSLException) {
                        Log.e("AudioRelay", "SSL exception during accept (possible TLS/plain mismatch): ${sslEx.message}", sslEx)
                        DiagLog.e("监听", "接受连接时发生 TLS 异常（可能是 TLS/明文不匹配）", sslEx)
                        null
                    }

                    maybeClient?.use { client ->
                        handleClient(client)
                    }
                } catch (e: IOException) {
                    // Closed by "Stop" is an expected exit, no error logged; only genuine accept exceptions are reported
                    if (isServerRunning && !socket.isClosed) {
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
        // 记下本次会话的 socket：用户点「停止」时要能主动关掉它，
        // 否则接收线程会一直阻塞在往已暂停音轨里 write，会话结束不了。
        currentClientSocket = client
        val peer = try { client.inetAddress.hostAddress ?: "" } catch (ex: Exception) { "" }
        Log.i("AudioRelay", "Client connected: $peer")
        DiagLog.i("连接", "电脑端已连接：$peer:${client.port}（本机端口 $AUDIO_PORT）")
        try {
            handleStream(client.getInputStream(), peer)
        } finally {
            try { client.close() } catch (ex: Exception) { /* 忽略关闭异常 */ }
            // 会话自然结束时清引用，避免下次「停止」去关一个已被回收的 socket
            if (currentClientSocket === client) {
                currentClientSocket = null
            }
            // 会话结束：撤销会话通知（常驻通知不受影响）
            updateSessionNotification(false)
        }
    }

    /**
     * 标记「音频会话已建立」：刷新对端信息、广播连接状态、张贴会话通知
     *
     * 局域网直连与服务器中转共用。中转链路此前没有这一步，
     * 导致音频已在播放而界面始终显示「等待连接」。
     *
     * :param peerLabel: 对端标识（直连为对端 IP，中转为「中转服务器」）
     * :return: 无返回值
     */
    private fun markSessionEstablished(peerLabel: String) {
        val isRelay = peerLabel == "中转服务器"
        val shownName = if (isRelay) "电脑（经服务器中转）" else peerLabel
        lastClientIp = peerLabel
        lastClientName = shownName
        // Update notification with sender name (check runtime permission on Android 13+)
        notifyConnected()
        // Broadcast connection event so UI can update
        try {
            val bcast = Intent("com.devindeed.aurelay.CLIENT_CONNECTION")
            bcast.setPackage(packageName)
            bcast.putExtra("connected", true)
            bcast.putExtra("client_ip", peerLabel)
            bcast.putExtra("client_name", shownName)
            sendBroadcast(bcast)
            Log.i("AudioRelay", "Broadcast sent: CLIENT_CONNECTION connected=true ip=$peerLabel")
        } catch (ex: Exception) {
            Log.e("AudioRelay", "Failed to broadcast client connected: ${ex.message}", ex)
        }
        // 真有电脑连进来才张贴会话通知（与常驻通知并列，共两条）
        updateSessionNotification(true)
    }

    // 统一的流处理：先读流包头判定编码，再进入对应解码链路（局域网直连与服务器中转共用）
    private fun handleStream(rawInput: InputStream, peerLabel: String) {
        var decoder: OpusDecoder? = null
        var session: DiagSession? = null
        // 领取本次会话代次：finally 里只允许「仍是当前最新会话」的这一路去暂停音轨，
        // 否则切网络快速重建的多路流会互相把对方的音轨 pause 掉。
        val myGeneration = ++sessionGeneration
        DiagLog.i("连接", "开始处理来自 $peerLabel 的音频流（会话代次 #$myGeneration）")
        // 链路已建立：把连接状态同步给界面与通知。
        // 中转链路此前**完全没走这一步**，于是音频明明在播、界面却一直显示
        // 「等待连接」（实测 09:37-09:38）。直连由 handleClient 负责设置，
        // 中转没有对应的 handleClient，就在此统一补上。
        markSessionEstablished(peerLabel)
        // 真实音频开始播放，无声播放锚点让位（避免底噪混入输出）
        SilentPlayer.setScenarioAllowed(this, false)
        try {
            val input = PushbackInputStream(rawInput, HEADER_SIZE)
            // 每次会话开始前清掉上一轮的判定结果
            headerIncomplete = false
            // 中转链路的数据来自服务器转发，不能按官方裸 PCM 处理：
            // PC 端与手机的协议只有AURL 一种，无包头即说明这是旧会话的残留帧。
            val isRelayPeer = peerLabel == "中转服务器"
            val header = readHeader(input, isRelayPeer)
            // readHeader 返回 null 有两种含义，必须区分：
            //   · 包头没读够就超时（headerIncomplete）→ 本次连接直接结束；
            //   · 读满 8 字节但魔数不对 → 是真的官方裸 PCM，继续按PCM 处理。
            // 旧实现把两者都当成裸 PCM，于是「等包头超时」会把 48kHz 的 Opus
            // 按 44.1kHz 裸 PCM 播放 —— 每切换一次停止/开始就多踩一次，
            // 听感是失真越来越重，且电脑静音时噪声底被放大成起伏的嗡嗡声。
            if (header == null && headerIncomplete) {
                DiagLog.w("协议", "流包头未收全，放弃本次会话（不按裸 PCM 处理）")
                return
            }

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
            // 【并发守卫】仅当本会话仍是「最新一代」时才允许暂停：切网络会快速重建
            // 多路流，若旧会话的 finally 无条件 pause，会把新会话刚 play 的音轨停掉，
            // 结果就是「数据持续解码写入、音轨却一直已暂停、播放头=0 无声」。
            if (myGeneration == sessionGeneration) {
                try {
                    audioTrack?.pause()
                    audioTrack?.flush()
                } catch (e: Exception) {
                    // 忽略暂停异常
                }
            }
            Log.i("AudioRelay", "Client disconnected.")
            DiagLog.i("连接", "音频流结束（$peerLabel）")
            // 【并发守卫】旧会话的收尾动作（恢复无声锚点 / 清对端信息 / 广播断开）
            // 同样只在「本会话仍是当前最新一代」时执行。否则切网络快速重建时，
            // 旧会话的 finally 会把新会话刚建立的状态（连接通知、对端名）清掉，
            // 造成界面「已连接/已断开」反复跳、通知状态错乱。
            val isLatest = myGeneration == sessionGeneration
            // 音频流结束，无声播放锚点恢复工作（仅最新会话才允许恢复，避免抢断新会话的占用）
            if (isLatest) {
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
    }

    // 按 Android 13+ 的通知权限差异刷新前台通知
    private fun notifyConnected() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            // 必须重新走 startForeground，不能只用 notify()。
            // notify() 刷出来的是一条**普通通知**：一旦服务被系统降级到后台
            // （点过一次停止、或被其他 App 抢占资源），它就不再受前台服务保护，
            // 用户可以随手划掉——实测就是这样丢掉常驻通知的。
            // startForeground 会把同 ID 的通知重新拉回「前台服务通知」，不可划走。
            //
            // 再叠一层 FLAG_ONGOING_EVENT：部分国产 ROM 不完全理会
            // setOngoing(true)，带上这个 flag 才会稳定变成不可划走。
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        android.app.Notification.FLAG_ONGOING_EVENT
                    )
                }
            } catch (e: Exception) {
                Log.w("AudioRelay", "startForeground 刷新失败，退回 notify：${e.message}")
                @SuppressLint("MissingPermission")
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
            }
        } else {
            Log.w("AudioRelay", "Missing POST_NOTIFICATIONS permission; skipping notification update")
        }
    }

    // 读取 8 字节流包头：命中自研魔数返回包头，读满但魔数不对则退回官方裸 PCM    //
    // 【关键】必须区分两种「失败」：
    //   · 一个字节都没读到（超时 / EOF / 服务停止）→ 不算裸 PCM，直接放弃本次会话；
    //   · 读满了 8 字节但魔数不对→ 确实是官方裸 PCM，正常退回。
    // 混为一谈会让 48kHz 的 Opus 被按 44.1kHz 裸 PCM 播放（听感即严重失真）。
    //
    // @param input: 带 8 字节回退缓冲的输入流
    // @param isRelayPeer: true 表示数据来自服务器中转（PC 端经中转发来）
    // @return: AURL 包头字节数组；放弃会话时返回 null
    private fun readHeader(input: PushbackInputStream, isRelayPeer: Boolean = false): ByteArray? {
        val header = ByteArray(HEADER_SIZE)
        var filled = 0
        var idleMillis = 0L
        while (filled < HEADER_SIZE) {
            if (!isServerRunning) {
                // 服务已停止接收：这不是「官方裸 PCM」，标记放弃，避免上层误判。
                headerIncomplete = true
                return null
            }
            try {
                val n = input.read(header, filled, HEADER_SIZE - filled)
                if (n < 0) {
                    // 对端已关闭 / 流提前结束：一个字节都没读到，或只读到零头。
                    // 这绝不是「官方裸 PCM」——裸 PCM 流一开始就会连续有数据。
                    // 必须标记 headerIncomplete，让上层放弃本次会话而非按裸 PCM
                    // 处理，否则 streamRawPcm 会读到 EOF 立即退出，表现为
                    // 「电脑端已连接 → 0 秒结束 → 全程无声音」。
                    if (filled > 0) input.unread(header, 0, filled)
                    headerIncomplete = true
                    return null
                }
                filled += n
                idleMillis = 0
            } catch (e: java.net.SocketTimeoutException) {
                idleMillis += 100
                if (idleMillis >= IDLE_TIMEOUT_MS) {
                    if (filled > 0) input.unread(header, 0, filled)
                    Log.w("AudioRelay", "等待流包头超时，放弃该连接")
                    // 只读到了零头字节，**不能**退回裸 PCM：
                    // 上层会把 null 一律当官方裸 PCM，于是把 48kHz 的 Opus
                    // 按 44.1kHz 播—— 越反复停止/开始，失真越明显。
                    headerIncomplete = true
                    return null
                }
            }
        }
        val magic = String(header, 0, 4, Charsets.US_ASCII)
        if (magic == STREAM_MAGIC && (header[4].toInt() and 0xFF) == STREAM_VERSION) {
            return header
        }
        // 包头已读满 8 字节，却没有匹配自研魔数。
        //
        // 直连：确实是别的协议（官方裸 PCM），退回裸 PCM 处理。
        //
        // ⚠️ 中转：这里极可能是「旧会话残留帧」或「包头被 WebSocket 帧边界拆散」
        // 抢先到达，绝不能据此 forceReconnect！
        //
        // 实测血泪（18:45 死循环）：服务端「R 重接入/下线清 S」异步 close 旧 S 时，
        // 旧 S 已排队的 Opus 帧仍会被转发给手机，于是手机 readHeader 读满 8 字节
        // 读到的却是 `01 01 02 01 00 00 00 f7`（缺前 4 字节魔数 "AURL" 的后半段），
        // 旧代码据此「判定旧会话残留 → forceReconnect」→ R 重连 → 服务端又清 S →
        // PC 又重连 → 又发包头 → 又被残留帧抢先 → 又误判…… 三方共振死循环，
        // 实测每秒重连 1~2 次、永远凑不齐稳定窗口，完全无声音。
        //
        // 正确做法：**扫描魔数**——逐字节跳过残留，直到定位到 "AURL" 魔数。
        // 残留帧是有限的（旧 S 被 close 后不再产生新帧），跳过它就能接上真正
        // 的包头，链路自然恢复，无需重建连接。
        if (isRelayPeer) {
            return scanRelayMagic(input, header)
        }

        DiagLog.w(
            "协议",
            "未检测到 AURL 包头（读到 ${header.joinToString(" ") { "%02x".format(it) }}），按官方裸 PCM 处理"
        )
        input.unread(header, 0, HEADER_SIZE)
        return null
    }

    // 中转链路上扫描 AURL 魔数：逐字节跳过残留，直到找到合法包头或超时放弃
    private fun scanRelayMagic(input: PushbackInputStream, first: ByteArray): ByteArray? {
        // 把已读满的 8 字节先推回，用统一的「逐字节找魔数」循环从头扫
        input.unread(first, 0, first.size)
        // 4 字节滑动窗口（复用数组，避免每次 new String 的开销）
        val win = ByteArray(4)
        var winLen = 0
        var skipped = 0
        var idleMillis = 0L
        // 最多扫 64KB 残留 + 超时兜底，避免无限循环
        val maxSkip = 64 * 1024
        while (skipped < maxSkip) {
            if (!isServerRunning) {
                headerIncomplete = true
                return null
            }
            val b: Int
            try {
                b = input.read()
            } catch (e: java.net.SocketTimeoutException) {
                idleMillis += 100
                if (idleMillis >= IDLE_TIMEOUT_MS) {
                    headerIncomplete = true
                    return null
                }
                continue
            }
            if (b < 0) {
                headerIncomplete = true
                return null
            }
            idleMillis = 0
            skipped++
            // 维护 4 字节滑动窗口，比对 "AURL"（41 55 52 4c）
            if (winLen < 4) {
                win[winLen] = b.toByte()
                winLen++
            } else {
                // 左移一位，末位补新字节
                win[0] = win[1]
                win[1] = win[2]
                win[2] = win[3]
                win[3] = b.toByte()
            }
            if (winLen == 4 && win[0] == 'A'.code.toByte() && win[1] == 'U'.code.toByte() &&
                win[2] == 'R'.code.toByte() && win[3] == 'L'.code.toByte()
            ) {
                // 找到魔数，读剩余 4 字节包头
                val rest = ByteArray(4)
                var filled = 0
                while (filled < 4) {
                    if (!isServerRunning) {
                        headerIncomplete = true
                        return null
                    }
                    try {
                        val n = input.read(rest, filled, 4 - filled)
                        if (n < 0) {
                            headerIncomplete = true
                            return null
                        }
                        filled += n
                    } catch (e: java.net.SocketTimeoutException) {
                        idleMillis += 100
                        if (idleMillis >= IDLE_TIMEOUT_MS) {
                            headerIncomplete = true
                            return null
                        }
                    }
                }
                val full = ByteArray(HEADER_SIZE)
                System.arraycopy("AURL".toByteArray(Charsets.US_ASCII), 0, full, 0, 4)
                System.arraycopy(rest, 0, full, 4, 4)
                if ((full[4].toInt() and 0xFF) == STREAM_VERSION) {
                    if (skipped > 4) {
                        DiagLog.i("协议", "中转链路跳过 ${skipped - 4} 字节残留后定位到 AURL 包头")
                    }
                    return full
                }
                // 版本号不对：不是我们的包头，把这 4 字节当作残留直接丢弃，
                // 清空窗口继续向后扫（不再 unread，避免超出 8 字节 pushback 容量）。
                winLen = 0
            }
        }
        DiagLog.e("协议", "中转链路扫描 $maxSkip 字节仍未找到 AURL 包头，放弃本次会话")
        headerIncomplete = true
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
        // 停止周期链路自检线程
        linkSelfCheckRunning = false
        linkSelfCheckThread?.interrupt()
        linkSelfCheckThread = null
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
