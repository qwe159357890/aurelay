package com.devindeed.aurelay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process

/**
 * 无声播放锚点（8.3.3 保活第 12 层）
 *
 * 原理：系统把「持有活跃音频输出」的进程视为媒体播放前台进程，优先级高于普通后台进程；
 * 早期 MIUI / EMUI / Flyme 的一键清理会跳过正在播放媒体的进程。
 * 这里循环播放一段幅度仅 ±1 LSB（约 −96 dBFS）的 PCM，人耳听不到，但音频 mixer 保持活跃。
 *
 * ⚠️ 必须遵守的约束（14.1 禁令 #14）：
 * 1. 必须使用**独立 AudioTrack 实例**，严禁复用手机放音的主播放轨；
 * 2. 只调本轨自身音量 `setVolume(0f)`，严禁通过 AudioManager 改系统媒体音量
 *    （那是电脑放音防回授专用的手段，两者不能混）；
 * 3. 不申请 AudioFocus、不创建 MediaSession；
 * 4. 会话结束只 `pause() + flush()`，**绝不调用 `stop()`**（与禁令 #3 同源）。
 *
 * 场景让位：手机放音正在播放、电脑放音正在采集时必须让位，
 * 否则前者会把底噪混进输出、后者会被麦克风采进去形成回声。
 */
object SilentPlayer {

    // 采样率（48kHz）
    private const val SAMPLE_RATE = 48000

    // 单帧采样点数（20ms）
    private const val FRAME_SAMPLES = 960

    // 单帧字节数（16bit 单声道）
    private const val FRAME_BYTES = FRAME_SAMPLES * 2

    // 循环投递间隔（毫秒）
    private const val TICK_MS = 20L

    // 音轨实例（独立，绝不复用主播放轨）
    private var track: AudioTrack? = null

    // 工作线程与其调度器
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    // 主循环任务
    private var tickRunnable: Runnable? = null

    // 是否已进入播放状态
    @Volatile private var playing = false

    // 当前场景是否允许播放（手机放音播放中 / 电脑放音采集中为 false）
    @Volatile private var scenarioAllowed = true

    // 样本抖动计数器（避免全 0 帧被系统判定为空流）
    private var counter = 0L

    /**
     * 按设置开关应用无声播放状态
     *
     * 开关开且场景允许 → 开始播放；否则停止。
     *
     * :param context: 任意上下文
     * :return: 无返回值
     */
    fun applyEnabled(context: Context) {
        val enabled = AppPrefs.getBoolean(context, AppPrefs.KEY_KEEP_ALIVE_SILENT_PLAY, true)
        if (enabled) {
            start(context, "开关打开")
        } else {
            stop("开关关闭")
        }
    }

    /**
     * 设置当前场景是否允许无声播放（业务链路占用音频时调用 false 让位）
     *
     * :param context: 任意上下文
     * :param allowed: true 表示当前场景允许播放
     * :return: 无返回值
     */
    fun setScenarioAllowed(context: Context, allowed: Boolean) {
        scenarioAllowed = allowed
        val enabled = AppPrefs.getBoolean(context, AppPrefs.KEY_KEEP_ALIVE_SILENT_PLAY, true)
        if (!enabled) {
            stop("开关关闭")
            return
        }
        if (allowed) {
            start(context, "场景空闲")
        } else {
            stop("业务占用音频，让位")
        }
    }

    /**
     * 开始无声播放（幂等）
     *
     * :param context: 任意上下文
     * :param reason: 启动原因（写入诊断日志，便于排查）
     * :return: 无返回值
     */
    fun start(context: Context, reason: String) {
        if (playing) return
        if (!scenarioAllowed) {
            DiagLog.i("保活", "无声播放 未开始：业务占用音频（$reason）")
            return
        }
        try {
            if (workerThread == null) {
                val thread = HandlerThread("AurelaySilent", Process.THREAD_PRIORITY_AUDIO)
                thread.start()
                workerThread = thread
                workerHandler = Handler(thread.looper)
            }
            if (track == null) track = buildTrack()
            val built = track ?: return
            built.setVolume(0f)
            if (built.playState != AudioTrack.PLAYSTATE_PLAYING) {
                built.play()
            }
            playing = true
            scheduleTick()
            DiagLog.i("保活", "无声播放 开始（$reason）")
        } catch (e: Exception) {
            DiagLog.e("保活", "无声播放 开始失败", e)
        }
    }

    /**
     * 停止无声播放（只 pause + flush，绝不 stop）
     *
     * :param reason: 停止原因（写入诊断日志）
     * :return: 无返回值
     */
    fun stop(reason: String) {
        if (!playing) {
            // 未播放时也要确保没有残留的循环任务
            cancelTick()
            return
        }
        playing = false
        cancelTick()
        try {
            track?.pause()
            track?.flush()
            DiagLog.i("保活", "无声播放 暂停（$reason）")
        } catch (e: Exception) {
            DiagLog.e("保活", "无声播放 暂停失败", e)
        }
    }

    /**
     * 彻底释放音轨与工作线程（仅在进程结束或用户关闭开关并退出时调用）
     *
     * :return: 无返回值
     */
    fun release() {
        stop("释放")
        try {
            track?.release()
        } catch (e: Exception) {
            DiagLog.e("保活", "无声播放 释放音轨失败", e)
        }
        track = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                workerThread?.quitSafely()
            } else {
                workerThread?.quit()
            }
        } catch (e: Exception) {
            DiagLog.e("保活", "无声播放 退出工作线程失败", e)
        }
        workerThread = null
        workerHandler = null
    }

    /**
     * 构造独立的无声播放音轨
     *
     * :return: 新建的 AudioTrack；构造失败返回 null
     */
    private fun buildTrack(): AudioTrack? {
        val bufferBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_BYTES * 4)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * 投递循环写入任务（每 20ms 写入一帧近静音数据）
     *
     * :return: 无返回值
     */
    private fun scheduleTick() {
        cancelTick()
        val handler = workerHandler ?: return
        val runnable = object : Runnable {
            override fun run() {
                if (!playing) return
                try {
                    val buffer = nextFrame()
                    track?.write(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    DiagLog.e("保活", "无声播放 写入失败", e)
                }
                if (playing) workerHandler?.postDelayed(this, TICK_MS)
            }
        }
        tickRunnable = runnable
        handler.post(runnable)
    }

    /**
     * 取消循环写入任务
     *
     * :return: 无返回值
     */
    private fun cancelTick() {
        tickRunnable?.let { workerHandler?.removeCallbacks(it) }
        tickRunnable = null
    }

    /**
     * 生成下一帧近静音 PCM 数据（幅度 ±1 LSB，小端 16bit）
     *
     * 全 0 帧会被部分 ROM 判定为空流而回收音轨，因此让最低位在 +1 / −1 间抖动。
     *
     * :return: 一帧的字节数据（1920 字节）
     */
    private fun nextFrame(): ByteArray {
        val buffer = ByteArray(FRAME_BYTES)
        var index = 0
        while (index < FRAME_BYTES) {
            val low = if ((counter++ and 1L) == 0L) 1 else 0xFF
            buffer[index] = low.toByte()
            buffer[index + 1] = 0.toByte()
            index += 2
        }
        return buffer
    }
}
