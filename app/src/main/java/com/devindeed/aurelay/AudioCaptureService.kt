package com.devindeed.aurelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.util.concurrent.CopyOnWriteArrayList

class AudioCaptureService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null // For local playback
    private var clientSocket: Socket? = null
    private var targetIp: String = ""
    private var targetPort: Int = 5000
    private var audioOutputMode: String = "remote_only" // this_device, remote_only, both_devices
    private var isStreaming = false
    private var discoveryThread: Thread? = null
    private var discoverySocket: DatagramSocket? = null
    private var audioManager: AudioManager? = null
    private var originalMediaVolume: Int = 0

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_TARGET_IP = "EXTRA_TARGET_IP"
        const val EXTRA_TARGET_PORT = "EXTRA_TARGET_PORT"
        const val EXTRA_AUDIO_OUTPUT_MODE = "EXTRA_AUDIO_OUTPUT_MODE"
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "AudioCaptureChannel"
        const val TAG = "AudioCaptureService"
        const val DISCOVERY_PORT = 5002
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startDiscoveryResponder()
    }
    
    private fun startDiscoveryResponder() {
        discoveryThread = Thread {
            try {
                discoverySocket = DatagramSocket(DISCOVERY_PORT)
                val buffer = ByteArray(1024)
                Log.d(TAG, "UDP listener started on port $DISCOVERY_PORT")
                
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        discoverySocket?.receive(packet)
                        val msg = String(packet.data, 0, packet.length).trim()
                        
                        Log.d(TAG, "Received UDP message: $msg from ${packet.address.hostAddress}")
                        
                        if (msg.startsWith("AURYNK_ACCEPT")) {
                            // Connection accepted by receiver
                            try {
                                val bcast = Intent("io.github.aurynk.CLIENT_CONNECTION")
                                bcast.setPackage(packageName)
                                bcast.putExtra("connected", true)
                                bcast.putExtra("client_ip", packet.address.hostAddress ?: "")
                                sendBroadcast(bcast)
                                Log.i(TAG, "Connection ACCEPTED by ${packet.address.hostAddress}")
                            } catch (ex: Exception) {
                                Log.e(TAG, "Failed to broadcast connection accepted: ${ex.message}", ex)
                            }
                        } else if (msg.startsWith("AURYNK_REJECT")) {
                            // Connection rejected by receiver
                            try {
                                val bcast = Intent("io.github.aurynk.CLIENT_CONNECTION")
                                bcast.setPackage(packageName)
                                bcast.putExtra("connected", false)
                                bcast.putExtra("client_ip", "")
                                sendBroadcast(bcast)
                                Log.i(TAG, "Connection REJECTED by ${packet.address.hostAddress}")
                                // Stop the service since connection was rejected
                                stopSelf()
                            } catch (ex: Exception) {
                                Log.e(TAG, "Failed to broadcast connection rejected: ${ex.message}", ex)
                            }
                        } else if (msg.startsWith("AURYNK_DISCONNECT")) {
                            // Receiver disconnected
                            try {
                                val bcast = Intent("io.github.aurynk.CLIENT_CONNECTION")
                                bcast.setPackage(packageName)
                                bcast.putExtra("connected", false)
                                bcast.putExtra("client_ip", "")
                                sendBroadcast(bcast)
                                Log.i(TAG, "Disconnect request from ${packet.address.hostAddress}")
                                stopSelf()
                            } catch (ex: Exception) {
                                Log.e(TAG, "Failed to broadcast disconnect: ${ex.message}", ex)
                            }
                        }
                    } catch (e: Exception) {
                        if (!Thread.currentThread().isInterrupted) {
                            Log.e(TAG, "Error receiving UDP packet: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP listener failed: ${e.message}")
            } finally {
                try { discoverySocket?.close() } catch (e: Exception) {}
            }
        }
        discoveryThread?.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                targetIp = intent.getStringExtra(EXTRA_TARGET_IP) ?: ""
                targetPort = intent.getIntExtra(EXTRA_TARGET_PORT, 5000)
                audioOutputMode = intent.getStringExtra(EXTRA_AUDIO_OUTPUT_MODE) ?: "remote_only"

                if (targetIp.isNotEmpty()) {
                    // 电脑放音只需麦克风，不申请屏幕投射 → 系统不会弹「录制或投射」提示
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceCompat.startForeground(
                            this,
                            NOTIFICATION_ID,
                            createNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, createNotification())
                    }
                    startCapture()
                } else {
                    Log.e(TAG, "缺少目标电脑 IP")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // 启动麦克风采集并推流（电脑放音模式：手机当电脑的无线麦克风）
    private fun startCapture() {
        // ⚠️ 关键点：电脑放音要的是「手机麦克风的声音」，采集源必须是 MIC，
        //    不能用 MediaProjection 的 AudioPlaybackCapture——那录的是系统内部声音。
        //    用内录有两个坏处：
        //      ① 系统必弹「要开始录制或投射内容吗？」，用户看了莫名其妙；
        //      ② 录到的是手机内部播放声，不是人说话声，电脑上听不到人声。
        //    因此这里不再申请屏幕投射，也就不会弹那个提示。
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000)  // 与 Opus 编码规格一致（48kHz）
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            48000,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // 缓冲取「系统最小值」与「20ms 立体声帧」的较大者，保证能装下一整帧
        val bufferSize = maxOf(minBufferSize, 48000 * 2 * 2 * 20 / 1000)

        try {
            // Only start AudioRecord for modes that need streaming
            if (audioOutputMode == "remote_only" || audioOutputMode == "both_devices") {
                audioRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                audioRecord?.startRecording()
                isStreaming = true
            }
                
                // Initialize local audio playback ONLY for both_devices mode
                // In this_device mode, audio already plays naturally on the device
                if (audioOutputMode == "both_devices") {
                    val minBufferSize = AudioTrack.getMinBufferSize(
                        44100,
                        AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    
                    audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(
                            // FLAG_LOW_LATENCY is deprecated; prefer setting performance mode on AudioTrack
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(44100)
                                .build()
                        )
                        .setBufferSizeInBytes(minBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build()
                    
                    audioTrack?.play()
                    Log.d(TAG, "Local audio playback enabled (mode: $audioOutputMode)")
                }

                // Mute device for remote_only mode
                if (audioOutputMode == "remote_only") {
                    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    originalMediaVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    Log.d(TAG, "Muted device volume for remote_only mode (original: $originalMediaVolume)")
                }

                // Connect and stream based on mode
                if (audioOutputMode == "remote_only" || audioOutputMode == "both_devices") {
                    connectAndStream()
                } else {
                    // this_device mode: audio plays naturally, just need to keep service alive
                    Log.d(TAG, "This device mode: audio playing naturally on device")
                    // Service stays alive in foreground, no streaming needed
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "Security Exception starting AudioRecord: ${e.message}")
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting AudioRecord: ${e.message}")
                stopSelf()
            }

        // Check for permission again if needed, though service should have it.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
             Log.e(TAG, "Missing RECORD_AUDIO permission")
             stopSelf()
             return
        }
    }

    // 主动连上目标电脑后按 AURL + Opus 推流（8.8.1）
    private fun connectAndStream() {
        // 采集期间无声播放锚点必须让位，否则会被麦克风采进去形成回声
        SilentPlayer.setScenarioAllowed(this, false)
        DiagLog.install(this)
        serviceScope.launch {
            var encoder: OpusEncoder? = null
            try {
                // First, establish connection
                Log.d(TAG, "Connecting to receiver at $targetIp:$targetPort")
                clientSocket = Socket(targetIp, targetPort)
                clientSocket?.tcpNoDelay = true // Disable Nagle's algorithm for low latency
                Log.d(TAG, "Connected to receiver successfully")

                val outputStream = clientSocket?.getOutputStream()

                if (outputStream == null) {
                    Log.e(TAG, "Output stream is null")
                    stopSelf()
                    return@launch
                }

                // 优先用 Opus 编码（裸 PCM 蜂窝下 691MB/小时，Opus 约 57MB/小时）
                val candidate = OpusEncoder()
                if (candidate.start()) {
                    encoder = candidate
                } else {
                    candidate.stop()
                    Log.w(TAG, "Opus encoder unavailable, fallback to raw PCM")
                    DiagLog.w("编码", "设备无 Opus 编码器，本次回退裸 PCM 推流")
                }
                val codec = if (encoder != null) 1 else 0

                // 发送 8 字节 AURL 包头：魔数 + 版本 + 编码 + 声道 + 采样率代码
                val header = byteArrayOf(
                    0x41, 0x55, 0x52, 0x4C, // "AURL"
                    1, codec.toByte(), 2, 1
                )
                outputStream.write(header)
                outputStream.flush()
                DiagLog.i("协议", "已发送 AURL 包头：编码=${if (codec == 1) "Opus" else "裸PCM"} 声道=2 采样率=48000")

                // 采样与编码缓冲
                val bufferSize = 1024 * 4
                val buffer = ByteArray(bufferSize)
                val accumulator = java.io.ByteArrayOutputStream(OpusEncoder.FRAME_BYTES * 2)
                Log.d(TAG, "Starting audio streaming...")
                var bytesWritten = 0L
                var lastLogTime = System.currentTimeMillis()

                while (isActive && isStreaming && clientSocket?.isConnected == true) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        try {
                            // Also play locally if both_devices mode
                            if (audioOutputMode == "both_devices" && audioTrack != null) {
                                audioTrack?.write(buffer, 0, read)
                            }
                            val enc = encoder
                            if (enc == null) {
                                // 裸 PCM 回退：直接写原始采样
                                outputStream.write(buffer, 0, read)
                                bytesWritten += read
                            } else {
                                accumulator.write(buffer, 0, read)
                                val pending = ByteArray(OpusEncoder.FRAME_BYTES)
                                while (accumulator.size() >= OpusEncoder.FRAME_BYTES) {
                                    val chunk = accumulator.toByteArray()
                                    System.arraycopy(chunk, 0, pending, 0, OpusEncoder.FRAME_BYTES)
                                    accumulator.reset()
                                    if (accumulator.size() > 0) {
                                        // 把多余部分写回（正常情况下 read 会小于帧长，不会触发）
                                        accumulator.write(
                                            chunk,
                                            OpusEncoder.FRAME_BYTES,
                                            chunk.size - OpusEncoder.FRAME_BYTES
                                        )
                                    }
                                    val packet = enc.encode(pending, OpusEncoder.FRAME_BYTES)
                                    if (packet != null && packet.isNotEmpty()) {
                                        writeFrame(outputStream, packet)
                                        bytesWritten += packet.size
                                    }
                                }
                            }

                            // Log progress every 5 seconds
                            val now = System.currentTimeMillis()
                            if (now - lastLogTime > 5000) {
                                Log.d(TAG, "Streamed ${bytesWritten / 1024} KB so far")
                                DiagLog.d("推流", "已推送 ${bytesWritten / 1024} KB")
                                lastLogTime = now
                            }
                        } catch (e: IOException) {
                            Log.e(TAG, "Error writing to receiver: ${e.message}")
                            break
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord read error: $read")
                        break
                    }
                }

                Log.d(TAG, "Audio streaming stopped. Total bytes: $bytesWritten")
                DiagLog.i("推流", "推流结束：共 ${bytesWritten / 1024} KB")

            } catch (e: IOException) {
                Log.e(TAG, "Connection/streaming failed: ${e.message}", e)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error: ${e.message}", e)
                stopSelf()
            } finally {
                encoder?.stop()
                // 采集结束，无声播放锚点恢复工作
                SilentPlayer.setScenarioAllowed(this@AudioCaptureService, true)
            }
        }
    }

    // 写入一帧：4 字节大端长度 + 帧数据
    private fun writeFrame(outputStream: java.io.OutputStream, payload: ByteArray) {
        outputStream.write((payload.size ushr 24) and 0xFF)
        outputStream.write((payload.size ushr 16) and 0xFF)
        outputStream.write((payload.size ushr 8) and 0xFF)
        outputStream.write(payload.size and 0xFF)
        outputStream.write(payload)
    }

    override fun onDestroy() {
        isStreaming = false
        serviceJob.cancel()
        
        // Restore volume if it was muted
        if (audioOutputMode == "remote_only" && audioManager != null) {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, originalMediaVolume, 0)
            Log.d(TAG, "Restored device volume to $originalMediaVolume")
        }
        
        // Stop UDP listener
        try {
            discoveryThread?.interrupt()
            discoverySocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping UDP listener: ${e.message}")
        }
        
        try {
            clientSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null


        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音频采集服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("音频采集")
            .setContentText("正在采集并推送系统音频…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
