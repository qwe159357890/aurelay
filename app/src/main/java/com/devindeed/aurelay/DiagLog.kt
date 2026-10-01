package com.devindeed.aurelay

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 诊断日志器（排查「已连接却没有声音」等疑难问题专用）
 *
 * 设计目标：用户只需要在设置页打开一个开关，复现问题，然后把日志上传或分享出来，
 * 我们就能从日志里直接看出问题出在「没收到数据 / 解码失败 / 音轨没播放 / 系统音量为 0」
 * 哪一环，而不必让用户一边连电脑抓 Logcat 一边复现。
 *
 * 三个层级，互不干扰：
 * 1. Logcat：始终输出，开发机接数据线时照旧可看；
 * 2. 内存环形缓冲：**始终记录**关键事件（连接、协议分支、音轨状态、首帧、会话汇总、
 *    异常），因此「先出问题、后打开开关」也能拿到上半段现场；默认保留最近 600 条；
 * 3. 日志文件：**仅在开关打开时**写入 App 外部私有目录下的 diag/aurelay-diag.log，
 *    超限自动轮转（保留 3 份），用于导出与分享。
 *
 * 线程安全：所有写入口都走同一把锁（SimpleDateFormat 本身非线程安全）。
 */
object DiagLog {

    // 日志标签（Logcat 用）
    const val TAG = "AurelayDiag"

    // 设置页「启用诊断日志」开关的持久化键名
    const val KEY_ENABLED = "diag_log_enabled"

    // 内存环形缓冲容量（条）
    private const val RING_CAPACITY = 600

    // 单个日志文件大小上限（字节），超过后轮转为 .1/.2/.3
    private const val MAX_FILE_BYTES = 800_000L

    // 轮转保留的文件份数
    private const val KEEP_FILES = 3

    // 日志目录名（位于 App 外部私有目录，无需存储权限）
    private const val DIR_NAME = "diag"

    // 日志文件名
    private const val FILE_NAME = "aurelay-diag.log"

    // 上传/分享时最多带出多少字符，避免请求体过大
    private const val MAX_DUMP_CHARS = 200_000

    // 内存环形缓冲（始终记录关键事件）
    private val ring = ArrayDeque<String>()

    // 全局锁：保护 ring、时间格式与文件写入
    private val lock = Any()

    // 开关状态（打开后才写文件）
    @Volatile private var enabled = false

    // 当前日志文件
    @Volatile private var file: File? = null

    // 时间格式（只在锁内使用）
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 安装日志器：读取开关状态并准备好日志文件
     *
     * :param context: 任意上下文（内部会取 applicationContext）
     * :return: 无返回值
     */
    fun install(context: Context) {
        val app = context.applicationContext
        enabled = try {
            PreferenceManager.getDefaultSharedPreferences(app).getBoolean(KEY_ENABLED, false)
        } catch (e: Exception) {
            false
        }
        prepareFile(app)
        i("日志", "诊断日志器已安装：开关=${if (enabled) "开" else "关"}，文件=${file?.absolutePath ?: "不可用"}")
    }

    /**
     * 查询开关是否打开
     *
     * :return: true 表示已打开
     */
    fun isEnabled(): Boolean = enabled

    /**
     * 设置开关状态并落盘（立即生效，无需保存设置页）
     *
     * :param context: 任意上下文
     * :param value: true 打开、false 关闭
     * :return: 无返回值
     */
    fun setEnabled(context: Context, value: Boolean) {
        val app = context.applicationContext
        try {
            PreferenceManager.getDefaultSharedPreferences(app)
                .edit().putBoolean(KEY_ENABLED, value).apply()
        } catch (e: Exception) {
            // 落盘失败不影响本次会话内的开关
        }
        prepareFile(app)
        enabled = value
        i("日志", if (value) "诊断日志已开启（开始写入文件）" else "诊断日志已关闭（仅保留内存缓冲）")
        if (value) {
            i("环境", environment(app))
        }
    }

    /**
     * 调试级日志：仅开关打开时记录
     *
     * 用于高频周期统计，避免撑爆始终开启的内存缓冲。
     *
     * :param tag: 日志标签
     * :param message: 日志内容
     * :return: 无返回值
     */
    fun d(tag: String, message: String) {
        if (!enabled) {
            Log.d(TAG, "[$tag] $message")
            return
        }
        append("D", tag, message, true)
    }

    /**
     * 一般级日志：始终记入内存缓冲（关键事件，后开开关也能追溯）
     *
     * :param tag: 日志标签
     * :param message: 日志内容
     * :return: 无返回值
     */
    fun i(tag: String, message: String) {
        append("I", tag, message, false)
    }

    /**
     * 警告级日志：始终记入内存缓冲
     *
     * :param tag: 日志标签
     * :param message: 日志内容
     * :return: 无返回值
     */
    fun w(tag: String, message: String) {
        append("W", tag, message, false)
    }

    /**
     * 错误级日志：始终记入内存缓冲，并附上异常类型与堆栈首部
     *
     * :param tag: 日志标签
     * :param message: 日志内容
     * :param throwable: 可选异常对象
     * :return: 无返回值
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val extra = throwable?.let {
            " | ${it.javaClass.simpleName}: ${it.message} @ " +
                (it.stackTrace.firstOrNull()?.toString() ?: "无堆栈")
        } ?: ""
        append("E", tag, message + extra, false)
    }

    /**
     * 统一写入口：Logcat + 内存缓冲 + 日志文件
     *
     * :param level: 级别字符（D/I/W/E）
     * :param tag: 日志标签
     * :param message: 日志内容
     * :param debugLevel: 是否为调试级（调试级仅在开关打开时进缓冲与文件）
     * :return: 无返回值
     */
    private fun append(level: String, tag: String, message: String, debugLevel: Boolean) {
        when (level) {
            "D" -> Log.d(TAG, "[$tag] $message")
            "W" -> Log.w(TAG, "[$tag] $message")
            "E" -> Log.e(TAG, "[$tag] $message")
            else -> Log.i(TAG, "[$tag] $message")
        }
        if (debugLevel && !enabled) return

        val line = synchronized(lock) {
            val text = "${timeFormat.format(Date())} [$level] [$tag] $message"
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(text)
            text
        }
        if (enabled) writeToFile(line)
    }

    /**
     * 准备日志文件（创建目录并确定文件路径）
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun prepareFile(context: Context) {
        if (file != null) return
        try {
            val dir = File(context.getExternalFilesDir(null), DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            file = File(dir, FILE_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "[日志] 创建日志文件失败：${e.message}")
        }
    }

    /**
     * 追加一行到日志文件，超限则轮转
     *
     * :param line: 已格式化好的日志行
     * :return: 无返回值
     */
    private fun writeToFile(line: String) {
        val target = file ?: return
        try {
            if (target.exists() && target.length() > MAX_FILE_BYTES) rotate(target)
            target.appendText(line + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "[日志] 写入日志文件失败：${e.message}")
        }
    }

    /**
     * 轮转日志文件：aurelay-diag.log → .1 → .2 …，超出份数则丢弃
     *
     * :param target: 当前日志文件
     * :return: 无返回值
     */
    private fun rotate(target: File) {
        try {
            val parent = target.parentFile ?: return
            for (index in KEEP_FILES downTo 1) {
                val old = File(parent, "$FILE_NAME.$index")
                if (!old.exists()) continue
                if (index == KEEP_FILES) {
                    old.delete()
                } else {
                    old.renameTo(File(parent, "$FILE_NAME.${index + 1}"))
                }
            }
            target.renameTo(File(parent, "$FILE_NAME.1"))
        } catch (e: Exception) {
            Log.w(TAG, "[日志] 轮转日志文件失败：${e.message}")
        }
    }

    /**
     * 清空日志（内存缓冲 + 全部日志文件）
     *
     * :param context: 任意上下文
     * :return: 无返回值
     */
    fun clear(context: Context) {
        synchronized(lock) { ring.clear() }
        try {
            val dir = File(context.applicationContext.getExternalFilesDir(null), DIR_NAME)
            dir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "[日志] 清理日志文件失败：${e.message}")
        }
        i("日志", "日志已清空")
    }

    /**
     * 取内存缓冲的全部行
     *
     * :return: 日志行列表（由旧到新）
     */
    fun ringLines(): List<String> {
        synchronized(lock) { return ArrayList(ring) }
    }

    /**
     * 取日志文件的绝对路径（供分享时告知用户文件位置）
     *
     * :return: 日志文件路径；不可用时返回空串
     */
    fun filePath(): String = file?.absolutePath ?: ""

    /**
     * 生成完整日志文本（环境信息 + 文件日志 + 内存缓冲）
     *
     * :param context: 任意上下文
     * :return: 日志全文本（超过上限时保留尾部，即最新内容）
     */
    fun dump(context: Context): String {
        val app = context.applicationContext
        val builder = StringBuilder()
        builder.append("========== Aurelay 手机端诊断日志 ==========\n")
        builder.append(environment(app)).append("\n")
        builder.append("日志开关：").append(if (enabled) "开" else "关").append("\n")
        builder.append("日志文件：").append(filePath().ifEmpty { "不可用" }).append("\n")
        builder.append("生成时间：")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append("\n")

        val fileText = readFiles(app)
        if (fileText.isNotEmpty()) {
            builder.append("\n---------- 文件日志（开关打开后写入）----------\n").append(fileText)
        }
        val buffered = ringLines()
        if (buffered.isNotEmpty()) {
            builder.append("\n---------- 内存缓冲（最近 ").append(buffered.size).append(" 条）----------\n")
            for (line in buffered) builder.append(line).append("\n")
        }
        if (fileText.isEmpty() && buffered.isEmpty()) {
            builder.append("\n（暂无日志）\n")
        }

        val text = builder.toString()
        return if (text.length <= MAX_DUMP_CHARS) text else text.substring(text.length - MAX_DUMP_CHARS)
    }

    /**
     * 读取日志文件内容（含轮转文件，按时间由旧到新拼接）
     *
     * :param context: 应用上下文
     * :return: 文件日志文本；无文件时返回空串
     */
    private fun readFiles(context: Context): String {
        val builder = StringBuilder()
        try {
            val dir = File(context.getExternalFilesDir(null), DIR_NAME)
            for (index in KEEP_FILES downTo 1) {
                val old = File(dir, "$FILE_NAME.$index")
                if (old.exists()) builder.append(old.readText())
            }
            val current = File(dir, FILE_NAME)
            if (current.exists()) builder.append(current.readText())
        } catch (e: Exception) {
            Log.w(TAG, "[日志] 读取日志文件失败：${e.message}")
        }
        return builder.toString()
    }

    /**
     * 取当前安装包的版本名与版本号
     *
     * :param context: 任意上下文
     * :return: 形如 "1.4.4(11)" 的版本字符串；读取失败返回 "未知"
     */
    fun appVersion(context: Context): String {
        return try {
            val app = context.applicationContext
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            "${info.versionName}(${info.versionCode})"
        } catch (e: Exception) {
            "未知"
        }
    }

    /**
     * 采集环境信息（机型、系统、安装包签名 SHA-1、音频状态）
     *
     * 其中签名 SHA-1 用于确认手机上装的是不是我们自己签名的那个包
     * （官方版与改造版 applicationId 相同但签名不同，只看版本号无法区分）。
     *
     * :param context: 应用上下文
     * :return: 多行环境信息文本
     */
    fun environment(context: Context): String {
        val builder = StringBuilder()
        builder.append("App 版本：").append(appVersion(context)).append("\n")
        builder.append("安装包名：").append(context.packageName).append("\n")
        builder.append("签名 SHA1：").append(signatureSha1(context)).append("\n")
        builder.append("机型：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append("（").append(Build.BRAND).append("）\n")
        builder.append("系统：Android ").append(Build.VERSION.RELEASE)
            .append("，SDK ").append(Build.VERSION.SDK_INT)
            .append("，ABI ").append(Build.SUPPORTED_ABIS.joinToString("/")).append("\n")
        builder.append("音频：").append(audioInfo(context))
        return builder.toString()
    }

    /**
     * 采集安装包签名 SHA-1
     *
     * :param context: 应用上下文
     * :return: 大写十六进制 SHA-1；读取失败返回原因摘要
     */
    private fun signatureSha1(context: Context): String {
        return try {
            val pm = context.packageManager
            val bytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures?.firstOrNull()?.toByteArray()
            }
            if (bytes == null) {
                "读取失败（无签名信息）"
            } else {
                MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02X".format(it) }
            }
        } catch (e: Exception) {
            "读取失败：${e.javaClass.simpleName}"
        }
    }

    /**
     * 采集系统音频状态（媒体音量、静音、音频模式、当前输出设备）
     *
     * 「已连接却完全没声音」里相当一部分其实是系统媒体音量为 0，
     * 因此把音量与输出设备一并记下来。
     *
     * :param context: 应用上下文
     * :return: 单行音频状态文本
     */
    private fun audioInfo(context: Context): String {
        return try {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val muted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.isStreamMute(AudioManager.STREAM_MUSIC)
            } else {
                false
            }
            val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .joinToString("/") { outputTypeName(it.type) }
            } else {
                "未知"
            }
            "媒体音量=$current/$max 静音=$muted 模式=${manager.mode} 输出设备=$devices"
        } catch (e: Exception) {
            "读取失败：${e.message}"
        }
    }

    /**
     * 把音频输出设备类型翻译成中文名
     *
     * :param type: AudioDeviceInfo.getType() 返回值
     * :return: 中文设备名
     */
    private fun outputTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机(无麦)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙SCO"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> "其他($type)"
        }
    }
}
