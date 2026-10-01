package com.devindeed.aurelay

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Opus 解码器（基于 Android 系统自带的 audio/opus 解码器）
 *
 * 外网（蜂窝）模式下，PC 发送端用 Opus 压缩后推送音频，可把流量降到裸 PCM 的
 * 十几分之一；本类负责把收到的 Opus 包解成 16bit PCM，交给 AudioTrack 播放。
 * 系统从 Android 5.0 起自带软件 Opus 解码器（OMX.google.opus.decoder），
 * 因此无需额外打包 native 库。
 */
class OpusDecoder(
    // 声道数（1 或 2）
    private val channelCount: Int,
    // 解码输出采样率（Opus 内部固定 48kHz，这里直接按 48000 配置）
    private val outputSampleRate: Int
) {

    private companion object {
        const val TAG = "AurelayOpus"
        const val MIME_TYPE = "audio/opus"
        // 解码输出 PCM 位深固定为 16bit
        const val BIT_DEPTH = 16
        // csd-0 中的 pre-skip（按 Opus 惯例 3840 个采样点，约 80ms）
        const val PRE_SKIP = 3840
    }

    // MediaCodec 解码器实例
    private var codec: MediaCodec? = null

    // 运行标志
    @Volatile private var running = false

    // 送入解码器的时间戳（微秒），仅用于递增占位
    private var timestampUs: Long = 0

    // 启动解码器：配置 audio/opus 并带上 OpusHead（csd-0）
    fun start(): Boolean {
        return try {
            val instance = MediaCodec.createDecoderByType(MIME_TYPE)
            val format = MediaFormat.createAudioFormat(MIME_TYPE, outputSampleRate, channelCount)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(buildOpusHead()))
            instance.configure(format, null, null, 0)
            instance.start()
            codec = instance
            running = true
            Log.i(TAG, "Opus 解码器已启动：channels=$channelCount sampleRate=$outputSampleRate")
            true
        } catch (e: Exception) {
            Log.e(TAG, "创建 Opus 解码器失败：${e.javaClass.simpleName} ${e.message}")
            running = false
            false
        }
    }

    // 解码一个 Opus 包，解出的 PCM 通过回调交回调用方
    fun decode(packet: ByteArray, size: Int, onPcm: (ByteArray, Int) -> Unit) {
        val instance = codec ?: return
        if (!running || size <= 0 || size > packet.size) return
        try {
            var inIndex = instance.dequeueInputBuffer(10_000)
            if (inIndex < 0) {
                // 输入缓冲暂不可用：先取走已解码数据，再重试一次
                drain(instance, onPcm)
                inIndex = instance.dequeueInputBuffer(10_000)
            }
            if (inIndex >= 0) {
                val inputBuffer = instance.getInputBuffer(inIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(packet, 0, size)
                    instance.queueInputBuffer(inIndex, 0, size, timestampUs, 0)
                    timestampUs += 20_000 // 每个 Opus 包约 20ms
                } else {
                    // 取不到缓冲时送回空包，避免解码器卡死
                    instance.queueInputBuffer(inIndex, 0, 0, timestampUs, 0)
                }
            } else {
                Log.w(TAG, "输入缓冲长时间不可用，丢弃一个 Opus 包")
            }
            drain(instance, onPcm)
        } catch (e: Exception) {
            Log.e(TAG, "Opus 解码异常：${e.javaClass.simpleName} ${e.message}")
        }
    }

    // 取出所有已解码的 PCM 数据
    private fun drain(instance: MediaCodec, onPcm: (ByteArray, Int) -> Unit) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = instance.dequeueOutputBuffer(info, 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = instance.outputFormat
                    Log.i(
                        TAG,
                        "解码输出格式变化：sampleRate=${outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)} " +
                                "channels=${outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}"
                    )
                }
                outIndex >= 0 -> {
                    try {
                        val outputBuffer = instance.getOutputBuffer(outIndex)
                        if (outputBuffer != null && info.size > 0) {
                            val pcm = ByteArray(info.size)
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            outputBuffer.get(pcm)
                            onPcm(pcm, info.size)
                        }
                    } finally {
                        instance.releaseOutputBuffer(outIndex, false)
                    }
                }
                else -> return
            }
        }
    }

    // 停止并释放解码器
    fun stop() {
        running = false
        val instance = codec ?: return
        codec = null
        try {
            instance.stop()
        } catch (e: Exception) {
            // 忽略停止异常
        }
        try {
            instance.release()
        } catch (e: Exception) {
            // 忽略释放异常
        }
        Log.i(TAG, "Opus 解码器已释放")
    }

    // 构造 OpusHead（19 字节，映射族为 0 的单流立体声/单声道头）
    private fun buildOpusHead(): ByteArray {
        val head = ByteArray(19)
        val magic = "OpusHead".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, head, 0, magic.size)
        head[8] = 1 // 版本号
        head[9] = channelCount.toByte() // 声道数
        // pre-skip（小端 16 位）
        head[10] = (PRE_SKIP and 0xFF).toByte()
        head[11] = ((PRE_SKIP shr 8) and 0xFF).toByte()
        // 原始输入采样率（小端 32 位）
        head[12] = (outputSampleRate and 0xFF).toByte()
        head[13] = ((outputSampleRate shr 8) and 0xFF).toByte()
        head[14] = ((outputSampleRate shr 16) and 0xFF).toByte()
        head[15] = ((outputSampleRate shr 24) and 0xFF).toByte()
        // 输出增益（小端 16 位，0 表示不调整）
        head[16] = 0
        head[17] = 0
        head[18] = 0 // 映射族 0
        return head
    }
}
