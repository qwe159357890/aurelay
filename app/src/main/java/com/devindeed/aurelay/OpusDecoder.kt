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
 * 系统从 Android 5.0 起自带软件 Opus 解码器（Android 10 起为
 * `c2.android.opus.decoder`），因此无需额外打包 native 库。
 *
 * 【关键：csd-0 必须用「统一 CSD」格式】
 * AOSP 的 C2SoftOpusDec 把**第一个输入缓冲**当作编解码器初始化数据，并调用
 * `GetOpusHeaderBuffers()` 从这一个缓冲里同时取出三样东西：
 *   1. OpusHead（标识头）
 *   2. codec delay（编解码器延迟，纳秒）
 *   3. seek pre-roll（寻址预滚，纳秒）
 * 它内部靠 `mInputBufferCount` 计数，只有计数到 3 才会真正配置解码器并开始解码。
 *
 * 如果只给 csd-0 = 19 字节的裸 OpusHead（旧式写法），codec delay / pre-roll 都取不到，
 * 计数只加到 1，于是：
 *   - 解码器始终拿不到输出格式，永远不出数据；
 *   - 更糟的是，**紧接着到达的前两个真实 Opus 包会被当成 codec delay / pre-roll 解析**，
 *     把 Opus 包的头 8 字节直接当 int64 纳秒数读出来；
 *   - 该值经 `ns_to_samples()` 换算后可达 1e14 量级（约等于几十天），
 *     写入 `mSamplesToDiscard` 后，**此后每一帧解码结果都被整体丢弃** → 永久静音。
 * 这正是实测日志里「收包几百个、写入音轨 0 块、无任何异常」的原因。
 *
 * 因此本类按 AOSP `OpusHeader.cpp` 的 `WriteOpusHeaders()` 构造「统一 CSD」：
 *   "AOPUSHDR" + u64LE(19) + OpusHead(19B)
 *   "AOPUSDLY" + u64LE(8)  + u64LE(延迟纳秒)
 *   "AOPUSPRL" + u64LE(8)  + u64LE(预滚纳秒)
 * 共 83 字节（= AOPUS_UNIFIED_CSD_MINSIZE），一次把三项都给到，计数直接到 3。
 *
 * 诊断：本类会通过 DiagLog 记录「收包数 / 解码出块数 / 输入丢弃数」——
 * 若长时间只进不出，基本可以断定是解码环节卡住（而不是没收到数据）。
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
        // csd-0 中 OpusHead 的 pre-skip（固定 0：延迟改由 AOPUSDLY 段单独声明）
        const val PRE_SKIP = 0

        // 编解码器延迟 / 寻址预滚（单位：纳秒，写入统一 CSD 的 AOPUSDLY / AOPUSPRL 段）
        // 取 0 表示不做任何开头丢弃，收到的第一帧就能听到声音
        const val CODEC_DELAY_NS = 0L
        const val SEEK_PREROLL_NS = 0L

        // 统一 CSD 的三段标识（与 AOSP OpusHeader.h 逐字节一致，均为 8 字节、无结尾 0）
        const val CSD_MARKER_HEADER = "AOPUSHDR"
        const val CSD_MARKER_CODEC_DELAY = "AOPUSDLY"
        const val CSD_MARKER_SEEK_PREROLL = "AOPUSPRL"

        // 每段 u64 长度字段的字节数
        const val LENGTH_SIZE = 8

        // 单个 OpusHead 的长度（映射族 0 的单流立体声/单声道头固定 19 字节）
        const val OPUS_HEAD_SIZE = 19

        // 取输出缓冲时的超时（微秒）：给 0 会在软件解码器上频繁取不到，取 2ms 更稳
        const val DRAIN_TIMEOUT_US = 2_000L

        // 连续收包多少仍无解码输出时给出告警（20ms/包，约 1 秒）
        const val NO_OUTPUT_WARN_PACKETS = 50L
    }

    // MediaCodec 解码器实例
    private var codec: MediaCodec? = null

    // 运行标志
    @Volatile private var running = false

    // 送入解码器的时间戳（微秒），仅用于递增占位
    private var timestampUs: Long = 0

    // 诊断计数：送入的包数、解出的 PCM 块数、因缓冲不可用丢弃的包数
    private var packetsIn = 0L
    private var framesOut = 0L
    private var droppedPackets = 0L

    // 无输出告警是否已提示过
    private var noOutputWarned = false

    // 是否已记录过「输出格式已确定」
    private var formatLogged = false

    // 启动解码器：配置 audio/opus 并带上统一 CSD（csd-0）
    fun start(): Boolean {
        return try {
            val instance = MediaCodec.createDecoderByType(MIME_TYPE)
            val csd = buildUnifiedCsd()
            val format = MediaFormat.createAudioFormat(MIME_TYPE, outputSampleRate, channelCount)
            // 统一 CSD 必须整体放进 csd-0（framework 会把它作为第一个输入缓冲下发）
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            instance.configure(format, null, null, 0)
            instance.start()
            codec = instance
            running = true
            Log.i(TAG, "Opus 解码器已启动：channels=$channelCount sampleRate=$outputSampleRate")
            DiagLog.i(
                "解码",
                "Opus 解码器已启动：输入声道=$channelCount 输出采样率=$outputSampleRate，" +
                        "解码器=${instance.name} 统一CSD=${csd.size}字节"
            )
            DiagLog.i("解码", "统一 CSD 内容（hex）：${csd.toHex()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "创建 Opus 解码器失败：${e.javaClass.simpleName} ${e.message}")
            DiagLog.e("解码", "创建 Opus 解码器失败：${describe(e)}", e)
            running = false
            false
        }
    }

    // 解码一个 Opus 包，解出的 PCM 通过回调交回调用方
    fun decode(packet: ByteArray, size: Int, onPcm: (ByteArray, Int) -> Unit) {
        val instance = codec ?: return
        if (!running || size <= 0 || size > packet.size) return
        packetsIn++
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
                droppedPackets++
                Log.w(TAG, "输入缓冲长时间不可用，丢弃一个 Opus 包")
                if (droppedPackets % 50 == 1L) {
                    DiagLog.w("解码", "输入缓冲长时间不可用，已丢弃 $droppedPackets 个 Opus 包")
                }
            }
            drain(instance, onPcm)
            if (!noOutputWarned && packetsIn >= NO_OUTPUT_WARN_PACKETS && framesOut == 0L) {
                noOutputWarned = true
                DiagLog.w(
                    "解码",
                    "已送入 $packetsIn 个 Opus 包但没有任何解码输出；" +
                            "若 csd 阶段校验失败，解码器会进入不可恢复错误态（表现为永久静音）"
                )
            }
        } catch (e: MediaCodec.CodecException) {
            Log.e(TAG, "Opus 解码器内部错误：${describe(e)}")
            DiagLog.e("解码", "Opus 解码器内部错误：${describe(e)}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Opus 解码异常：${e.javaClass.simpleName} ${e.message}")
            DiagLog.e("解码", "Opus 解码异常：${describe(e)}", e)
        }
    }

    // 取出所有已解码的 PCM 数据
    private fun drain(instance: MediaCodec, onPcm: (ByteArray, Int) -> Unit) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = try {
                instance.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            } catch (e: MediaCodec.CodecException) {
                DiagLog.e("解码", "取输出缓冲失败：${describe(e)}", e)
                return
            }
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!formatLogged) {
                        formatLogged = true
                        val outFormat = instance.outputFormat
                        val rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val pcmEncoding = outFormat.getInteger(
                            MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT
                        )
                        Log.i(TAG, "解码输出格式变化：sampleRate=$rate channels=$channels")
                        DiagLog.i(
                            "解码",
                            "解码输出格式已确定：采样率=$rate 声道=$channels 位深编码=$pcmEncoding"
                        )
                        if (rate != outputSampleRate) {
                            DiagLog.w(
                                "解码",
                                "解码器实际输出采样率($rate)与音轨采样率($outputSampleRate)不一致，播放会变速"
                            )
                        }
                    }
                }
                outIndex >= 0 -> {
                    try {
                        val outputBuffer = instance.getOutputBuffer(outIndex)
                        if (outputBuffer != null && info.size > 0) {
                            val pcm = ByteArray(info.size)
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            outputBuffer.get(pcm)
                            framesOut++
                            onPcm(pcm, info.size)
                        }
                    } finally {
                        instance.releaseOutputBuffer(outIndex, false)
                    }
                }
                else -> {
                    // 其余负值是 INFO_OUTPUT_BUFFERS_CHANGED 等「非错误」提示，记一条便于排查
                    if (outIndex != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        DiagLog.d("解码", "dequeueOutputBuffer 返回未知负值：$outIndex")
                    }
                    return
                }
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
        DiagLog.i("解码", "Opus 解码器已释放：共送入 $packetsIn 包，解出 $framesOut 块 PCM，丢弃 $droppedPackets 包")
    }

    // 构造 AOSP 要求的「统一 CSD」初始化数据（共 83 字节，整体作为 csd-0）
    private fun buildUnifiedCsd(): ByteArray {
        val head = buildOpusHead()
        val total = (8 + LENGTH_SIZE + head.size) +
                (8 + LENGTH_SIZE + 8) +
                (8 + LENGTH_SIZE + 8)
        val out = ByteArray(total)
        var offset = 0

        // 第 1 段：OpusHead
        writeMarker(out, offset, CSD_MARKER_HEADER)
        offset += 8
        writeLeU64(out, offset, head.size.toLong())
        offset += LENGTH_SIZE
        System.arraycopy(head, 0, out, offset, head.size)
        offset += head.size

        // 第 2 段：codec delay（纳秒）
        writeMarker(out, offset, CSD_MARKER_CODEC_DELAY)
        offset += 8
        writeLeU64(out, offset, 8L)
        offset += LENGTH_SIZE
        writeLeU64(out, offset, CODEC_DELAY_NS)
        offset += 8

        // 第 3 段：seek pre-roll（纳秒）
        writeMarker(out, offset, CSD_MARKER_SEEK_PREROLL)
        offset += 8
        writeLeU64(out, offset, 8L)
        offset += LENGTH_SIZE
        writeLeU64(out, offset, SEEK_PREROLL_NS)

        return out
    }

    // 写入 8 字节标识串（长度固定 8，无需结尾 0）
    private fun writeMarker(out: ByteArray, offset: Int, marker: String) {
        val bytes = marker.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, out, offset, minOf(bytes.size, 8))
    }

    // 以小端 64 位写入整数（AOSP 用 memcpy 按本机字节序读取，Android 均为小端）
    private fun writeLeU64(out: ByteArray, offset: Int, value: Long) {
        var remain = value
        for (i in 0 until 8) {
            out[offset + i] = (remain and 0xFF).toByte()
            remain = remain ushr 8
        }
    }

    // 构造 OpusHead（19 字节，映射族为 0 的单流立体声/单声道头）
    private fun buildOpusHead(): ByteArray {
        val head = ByteArray(OPUS_HEAD_SIZE)
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

    // 把字节数组转成十六进制字符串（诊断用）
    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

    // 把异常翻译成带错误码的可读描述（CodecException 会带上是否可恢复）
    private fun describe(e: Exception): String {
        val base = "${e.javaClass.simpleName} ${e.message}"
        if (e is MediaCodec.CodecException) {
            return "$base（错误码=${e.errorCode} 瞬时=${e.isTransient} 可恢复=${e.isRecoverable}）"
        }
        return base
    }
}
