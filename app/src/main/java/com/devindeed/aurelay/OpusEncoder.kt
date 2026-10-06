package com.devindeed.aurelay

import android.media.MediaCodec
import android.media.MediaFormat

/**
 * Opus 编码器（8.8.1 电脑放音的推流编解码）
 *
 * 电脑放音把手机麦克风/内部声音送到电脑，裸 PCM 在蜂窝下是 691 MB/小时，
 * 用 Opus 压到 128kbps 后约 57 MB/小时（约 11:1），因此采集后必须编码再推。
 *
 * 规格（与 PC 端现推流逐字节一致）：48kHz / 立体声 / 20ms 帧 / 128kbps，
 * 单帧输入 48000 × 2 声道 × 2 字节 × 20ms = 3840 字节。
 *
 * ⚠️ 设备没有可用 Opus 编码器时 `start()` 返回 false，调用方必须**显式提示**并回退裸 PCM，
 * 不允许静默降级（否则 PC 端会按 Opus 解出噪声）。
 */
class OpusEncoder {

    companion object {
        // 目标采样率
        private const val SAMPLE_RATE = 48000

        // 声道数
        // 声道数：固定单声道。麦克风物理上只有一个声道，强行按立体声编码
        // 只会让帧长翻倍、流量翻倍，音质却没有任何提升（左右声道内容相同）
        private const val CHANNELS = 1

        // 目标码率（bps）
        private const val BIT_RATE = 128000

        // 单帧时长（毫秒）
        private const val FRAME_MS = 20

        // 单帧输入字节数：48000 * 2声道 * 2字节 * 20ms / 1000 = 3840
        const val FRAME_BYTES = SAMPLE_RATE * CHANNELS * 2 * FRAME_MS / 1000

        // dequeue 超时（微秒）
        private const val TIMEOUT_US = 20_000L
    }

    // 编码器实例
    private var codec: MediaCodec? = null

    // 已送入的帧数（用于计算呈现时间戳）
    private var presentationIndex = 0L

    /**
     * 启动编码器
     *
     * :return: true 表示启动成功；false 表示设备无可用 Opus 编码器
     */
    fun start(): Boolean {
        return try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                SAMPLE_RATE,
                CHANNELS
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            codec = encoder
            presentationIndex = 0L
            DiagLog.i("编码", "Opus 编码器已启动：48kHz/1ch/20ms/128kbps，单帧 ${FRAME_BYTES} 字节")
            true
        } catch (e: Exception) {
            DiagLog.e("编码", "Opus 编码器启动失败（设备无可用 audio/opus 编码器）", e)
            codec = null
            false
        }
    }

    /**
     * 编码一帧 PCM
     *
     * 输入必须是 48kHz / 立体声 / 16bit 的一帧（3840 字节）；
     * 若采集源不是 48kHz，先用 `resampleTo48k()` 转换。
     *
     * :param pcm: PCM 数据
     * :param length: 有效长度（应等于 FRAME_BYTES）
     * :return: 编码后的 Opus 包；本次无输出时返回 null
     */
    fun encode(pcm: ByteArray, length: Int): ByteArray? {
        val encoder = codec ?: return null
        return try {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val buffer = encoder.getInputBuffer(inputIndex)
                buffer?.clear()
                val size = length.coerceAtMost(FRAME_BYTES)
                buffer?.put(pcm, 0, size)
                val pts = presentationIndex * FRAME_MS * 1000L
                presentationIndex++
                encoder.queueInputBuffer(inputIndex, 0, size, pts, 0)
            }
            drainOutput(encoder)
        } catch (e: Exception) {
            DiagLog.e("编码", "Opus 编码失败", e)
            null
        }
    }

    /**
     * 取出一个已编码的输出包
     *
     * :param encoder: 编码器实例
     * :return: 编码后的 Opus 包；无输出时返回 null
     */
    private fun drainOutput(encoder: MediaCodec): ByteArray? {
        val info = MediaCodec.BufferInfo()
        val outputIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
        if (outputIndex < 0) return null
        return try {
            val buffer = encoder.getOutputBuffer(outputIndex)
            if (buffer == null || info.size <= 0) {
                null
            } else {
                val out = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(out, 0, info.size)
                out
            }
        } finally {
            encoder.releaseOutputBuffer(outputIndex, false)
        }
    }

    /**
     * 把任意采样率的 16bit 立体声 PCM 线性重采样到 48kHz
     *
     * 采集源不是 48kHz 时必须先调用本方法，否则 Opus 编码后播放会变速。
     *
     * :param pcm: 源 PCM 数据（16bit / 立体声）
     * :param length: 有效字节数
     * :param srcRate: 源采样率
     * :return: 重采样后的 48kHz PCM 数据
     */
    fun resampleTo48k(pcm: ByteArray, length: Int, srcRate: Int): ByteArray {
        if (srcRate == SAMPLE_RATE) return pcm.copyOfRange(0, length)
        if (srcRate <= 0) return pcm.copyOfRange(0, length)
        val frameCount = length / 4
        if (frameCount <= 0) return ByteArray(0)
        val ratio = SAMPLE_RATE.toDouble() / srcRate.toDouble()
        val outFrames = (frameCount * ratio).toInt()
        val out = ByteArray(outFrames * 4)
        var index = 0
        while (index < outFrames) {
            val srcPos = index / ratio
            val base = srcPos.toInt().coerceAtMost(frameCount - 1)
            val next = (base + 1).coerceAtMost(frameCount - 1)
            val frac = (srcPos - base).toFloat()
            for (ch in 0 until 2) {
                val cur = readSample(pcm, base * 4 + ch * 2)
                val nxt = readSample(pcm, next * 4 + ch * 2)
                val value = (cur + (nxt - cur) * frac).toInt().coerceIn(-32768, 32767)
                out[index * 4 + ch * 2] = (value and 0xFF).toByte()
                out[index * 4 + ch * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            index++
        }
        return out
    }

    /**
     * 从 PCM 字节数组中读取一个 16bit 小端采样值
     *
     * :param data: 数据源
     * :param offset: 字节下标
     * :return: 采样值（-32768 ~ 32767）
     */
    private fun readSample(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        val low = data[offset].toInt() and 0xFF
        val high = data[offset + 1].toInt()
        return (high shl 8) or low
    }

    /**
     * 停止并释放编码器
     *
     * :return: 无返回值
     */
    fun stop() {
        try {
            codec?.stop()
        } catch (e: Exception) {
            DiagLog.w("编码", "停止 Opus 编码器失败：${e.message}")
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            DiagLog.w("编码", "释放 Opus 编码器失败：${e.message}")
        }
        codec = null
        DiagLog.i("编码", "Opus 编码器已释放")
    }
}
