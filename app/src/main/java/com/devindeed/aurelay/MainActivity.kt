package com.devindeed.aurelay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.BorderStroke
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.IntentFilter
import android.content.Intent as AndroidIntent
import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.devindeed.aurelay.iap.PurchaseManager
import com.devindeed.aurelay.ui.SmartAdBanner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast

class MainActivity : ComponentActivity() {
    private var connectionState by mutableStateOf(false)
    private var clientIpState by mutableStateOf("")
    private var audioLevels by mutableStateOf(FloatArray(24) { 0f })

    // 采集服务回传的推流状态：广播接收器是 Activity 字段，访问不到 Composable
    // 里的局部状态，因此先落到这三个 Activity 级字段，再由界面观察同步。
    // seq 每次 +1，保证「同一状态重复到达」也能触发一次效应。
    var captureStateSeq by mutableStateOf(0)
    var captureStateValue by mutableStateOf("")
    var captureStateReason by mutableStateOf("")
    private var pendingConnectionRequest by mutableStateOf<Pair<String, String>?>(null) // IP, Name
    
    companion object {
        const val ACTION_CONNECTION_REQUEST = "com.devindeed.aurelay.CONNECTION_REQUEST"
        const val ACTION_CONNECTION_RESPONSE = "com.devindeed.aurelay.CONNECTION_RESPONSE"
        const val EXTRA_APPROVED = "approved"

        // 待推流的目标电脑 IP：刻意放在 companion object（普通字段，同步读写），
        // 不用 Compose 状态。原因：列表项点击后若权限已授予，权限回调会几乎立即触发，
        // 此时 Compose 状态（clientIp）可能还没重组完成，读到的仍是空值，
        // 导致服务收到空的 EXTRA_TARGET_IP 而拒绝启动——表现为「首次点击没反应、
        // 第二次才成功」。Activity 字段在顶层 Composable 里访问不到，
        // 所以放 companion object，让界面与权限回调都能拿到同一个值。
        var pendingCaptureIp: String = ""

        // 权限测试：一次批量申请的全部危险权限（8.2.3 A 组）
        val RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.MANAGE_OWN_CALLS,
            Manifest.permission.ACCEPT_HANDOVER,
            Manifest.permission.ADD_VOICEMAIL,
            Manifest.permission.USE_SIP,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.UWB_RANGING,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.POST_NOTIFICATIONS
        )

        // 必须「二次单独申请」的权限：与前台权限同批会被系统直接拒绝
        val SECOND_ROUND_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.BODY_SENSORS_BACKGROUND
        )
    }

    // 批量申请全部危险权限（任何一条被拒都只记日志，绝不阻断主流程）
    private fun requestAllPermissions() {
        try {
            DiagLog.install(this)
            ActivityCompat.requestPermissions(this, RUNTIME_PERMISSIONS, 1001)
            DiagLog.i("权限", "已发起批量权限申请：${RUNTIME_PERMISSIONS.size} 项")
        } catch (e: Exception) {
            Log.e("MainActivity", "批量权限申请失败：${e.message}")
        }
    }

    // 权限申请结果：逐条记录授权情况，被拒不阻断；随后发起二次单独申请
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            DiagLog.i("权限", "批量申请结果：已授予 $granted / ${permissions.size}")
            try {
                ActivityCompat.requestPermissions(this, SECOND_ROUND_PERMISSIONS, 1002)
            } catch (e: Exception) {
                Log.e("MainActivity", "二次权限申请失败：${e.message}")
            }
        } else if (requestCode == 1002) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            DiagLog.i("权限", "二次申请结果：已授予 $granted / ${permissions.size}")
        }
    }
    
    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: AndroidIntent?) {
            intent ?: return
            when (intent.action) {
                "com.devindeed.aurelay.CLIENT_CONNECTION" -> {
                    val connected = intent.getBooleanExtra("connected", false)
                    val ip = intent.getStringExtra("client_ip") ?: ""
                    Log.d("MainActivity", "Broadcast received: connected=$connected, ip=$ip")
                    
                    if (!connected) {
                        // 断开连接不再弹「拒绝」提示：该功能（连接需要确认）已删除，
                        // 而服务停止、对端断开也会走同一条广播，弹提示纯属误导
                        Log.d("MainActivity", "连接已断开：ip=$ip")
                    }
                    
                    connectionState = connected
                    clientIpState = if (connected) ip else ""
                }

                // 采集服务如实回报推流状态：只有真的连上电脑才显示「正在广播」，
                // 连不上要明确告诉用户原因（此前是无条件谎报成功）
                AudioCaptureService.ACTION_CAPTURE_STATE -> {
                    val state = intent.getStringExtra(AudioCaptureService.EXTRA_CAPTURE_STATE) ?: ""
                    val reason = intent.getStringExtra(AudioCaptureService.EXTRA_CAPTURE_REASON) ?: ""
                    Log.d("MainActivity", "推流状态回传：$state $reason")
                    // 广播接收器是 Activity 字段，访问不到 Composable 里的局部状态，
                    // 因此写入 Activity 级事件字段，由界面观察并同步（seq 保证重复到达也触发）
                    captureStateValue = state
                    captureStateReason = reason
                    captureStateSeq++
                }
                ACTION_CONNECTION_REQUEST -> {
                    val ip = intent.getStringExtra("client_ip") ?: ""
                    val name = intent.getStringExtra("client_name") ?: "未知设备"
                    Log.d("MainActivity", "Connection request from: $name ($ip)")
                    
                    ctx ?: return
                    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                    val requireConfirm = prefs.getBoolean("require_connection_confirm", true)
                    
                    // Check if device is already paired
                    val pairedDevices = getPairedDevices(ctx)
                    val isPaired = pairedDevices.any { it.ip == ip }
                    
                    if (isPaired) {
                        // Auto-accept paired devices
                        sendConnectionResponse(ip, true)
                        Log.d("MainActivity", "Auto-accepted paired device: $name")
                    } else if (requireConfirm) {
                        // Show confirmation for unpaired devices
                        pendingConnectionRequest = Pair(ip, name)
                    } else {
                        // Auto-accept if confirmation not required
                        sendConnectionResponse(ip, true)
                    }
                }
                AudioRelayService.ACTION_AUDIO_LEVEL -> {
                    val levels = intent.getFloatArrayExtra(AudioRelayService.EXTRA_AUDIO_LEVELS)
                    if (levels != null && levels.size == 24) {
                        audioLevels = levels
                    }
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 安装诊断日志器（越早越好，后续所有日志才有落点）
        DiagLog.install(this)

        // Ensure the notification channel exists
        createNotificationChannel()
        
        // Check if auto-start is enabled in preferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val autoStart = prefs.getBoolean("auto_start_service", false)
        
        if (autoStart) {
            val intent = Intent(this, AudioRelayService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
        
        // Register broadcast receiver with proper flags for all Android versions
        val filter = IntentFilter().apply {
            addAction("com.devindeed.aurelay.CLIENT_CONNECTION")
            addAction(ACTION_CONNECTION_REQUEST)
            addAction(AudioRelayService.ACTION_AUDIO_LEVEL)
            addAction(AudioCaptureService.ACTION_CAPTURE_STATE)
        }
        // Use ContextCompat.registerReceiver with explicit non-exported flag to satisfy Android U+ requirements
        ContextCompat.registerReceiver(this, connectionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Log.d("MainActivity", "Broadcast receiver registered for CLIENT_CONNECTION")

        // 权限测试目标：能申请的全部申请一遍，任何一条被拒都不阻断主流程
        requestAllPermissions()

        setContent {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            // 主题固定浅色、动态取色固定关闭（设置项已删除，不再读取偏好）
            val isDarkTheme = false
            val colorScheme = lightColorScheme()
            
            MaterialTheme(colorScheme = colorScheme) {
                // Sync system bars to match the app's Material color scheme and theme
                SideEffect {
                    try {
                        val controller = WindowInsetsControllerCompat(window, window.decorView)
                        // When in light theme we want dark icons; in dark theme we want light icons
                        controller.isAppearanceLightStatusBars = !isDarkTheme
                        controller.isAppearanceLightNavigationBars = !isDarkTheme
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Failed to set system bar appearance: ${e.message}")
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemBars),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                        // Main app content takes all available space so the banner can sit below it
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            AurelayApp(
                                context = this@MainActivity,
                                isClientConnected = connectionState,
                                clientIp = clientIpState,
                                audioLevels = audioLevels,
                                pendingConnectionRequest = pendingConnectionRequest,
                                onConnectionResponse = { approved ->
                                    pendingConnectionRequest?.let { (ip, _) ->
                                        sendConnectionResponse(ip, approved)
                                        if (!approved) {
                                            Toast.makeText(this@MainActivity, "连接被拒绝", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    pendingConnectionRequest = null
                                },
                                onClientIpSelected = { ip ->
                                    val previous = clientIpState
                                    clientIpState = ip
                                    if (ip.isNotEmpty()) {
                                        sendConnectRequest(ip)
                                    } else {
                                        // clearing selection -> send disconnect to previous if existed
                                        if (previous.isNotEmpty()) {
                                            sendDisconnectRequest(previous)
                                        }
                                    }
                                }
                            )
                        }

                        // Observe dev mock premium state and show SmartAdBanner only if globally enabled
                        val isPremium by PurchaseManager.isPremium.collectAsState(initial = false)
                        if (com.devindeed.aurelay.BuildConfig.ENABLE_ADS) {
                            SmartAdBanner(
                                isPremium = isPremium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .navigationBarsPadding() // keep banner above system nav
                            )
                        }
                    }
                }
                }
            }
        }
    }

    // UDP 广播发现已整体删除：连接请求改为「直接按用户手填的地址连接」，无需再广播
    private fun sendConnectRequest(targetIp: String) {
        Log.d("MainActivity", "开始连接 $targetIp（UDP 广播已移除，直接 TCP 连接）")
        DiagLog.i("连接", "开始连接电脑 $targetIp（手动地址，不经 UDP 发现）")
    }

    // UDP 广播发现已整体删除：断开只影响本地状态，不再广播
    private fun sendDisconnectRequest(targetIp: String) {
        Log.d("MainActivity", "已断开 $targetIp（UDP 广播已移除）")
        DiagLog.i("连接", "已断开电脑 $targetIp")
    }

    // 连接确认：固定「不需要确认」，这里只同步本地 UI 状态（UDP 通知已移除）
    private fun sendConnectionResponse(targetIp: String, approved: Boolean) {
        Log.d("MainActivity", "连接响应：target=$targetIp approved=$approved（UDP 通知已移除）")
        if (approved) {
            runOnUiThread {
                connectionState = true
                clientIpState = targetIp
                Log.d("MainActivity", "已更新本地连接状态：connected with $targetIp")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(connectionReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "audioRelayChannel",
                "音频中继播放",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}

// Helper function to get user-friendly device name
fun getDeviceName(): String {
    return try {
        // Try to get marketing name first (e.g., "Redmi Note 14 5G")
        val marketingName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Build.DEVICE
        } else {
            null
        }
        
        // Fallback: Use MANUFACTURER + MODEL or just MODEL
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: ""
        val model = Build.MODEL ?: "Android 设备"
        
        when {
            // If model already contains manufacturer name, just use model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            // Otherwise combine manufacturer + model
            manufacturer.isNotEmpty() -> "$manufacturer $model"
            else -> model
        }
    } catch (e: Exception) {
        "Android 设备"
    }
}

// Helper function to get device IP address
fun getDeviceIpAddress(context: Context): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    return address.hostAddress ?: "未知"
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "未知"
}

// Paired device data class
data class PairedDevice(
    val name: String,
    val ip: String,
    val port: Int
)

// Helper functions for paired devices
fun getPairedDevices(context: Context): List<PairedDevice> {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val json = prefs.getString("paired_devices", "[]") ?: "[]"
    return try {
        val devices = mutableListOf<PairedDevice>()
        // Simple JSON parsing (manual to avoid dependencies)
        if (json.startsWith("[") && json.endsWith("]")) {
            val content = json.substring(1, json.length - 1)
            if (content.isNotEmpty()) {
                val items = content.split("},{")
                items.forEach { item ->
                    val cleaned = item.replace("{", "").replace("}", "")
                    val parts = cleaned.split(",")
                    var name = ""
                    var ip = ""
                    var port = 5000
                    parts.forEach { part ->
                        val kv = part.split(":")
                        if (kv.size == 2) {
                            val key = kv[0].trim().replace("\"", "")
                            val value = kv[1].trim().replace("\"", "")
                            when (key) {
                                "name" -> name = value
                                "ip" -> ip = value
                                "port" -> port = value.toIntOrNull() ?: 5000
                            }
                        }
                    }
                    if (name.isNotEmpty() && ip.isNotEmpty()) {
                        devices.add(PairedDevice(name, ip, port))
                    }
                }
            }
        }
        devices
    } catch (e: Exception) {
        Log.e("Aurelay", "Failed to parse paired devices: ${e.message}")
        emptyList()
    }
}

fun savePairedDevice(context: Context, device: PairedDevice) {
    val devices = getPairedDevices(context).toMutableList()
    // Remove if already exists
    devices.removeAll { it.ip == device.ip }
    // Add new
    devices.add(device)
    savePairedDevices(context, devices)
}

fun removePairedDevice(context: Context, ip: String) {
    val devices = getPairedDevices(context).toMutableList()
    devices.removeAll { it.ip == ip }
    savePairedDevices(context, devices)
}

fun savePairedDevices(context: Context, devices: List<PairedDevice>) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    // Build JSON manually
    val json = devices.joinToString(
        prefix = "[",
        postfix = "]",
        separator = ","
    ) { device ->
        "{\"name\":\"${device.name}\",\"ip\":\"${device.ip}\",\"port\":${device.port}}"
    }
    prefs.edit().putString("paired_devices", json).apply()
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AurelayApp(
    context: Context,
    isClientConnected: Boolean,
    clientIp: String,
    audioLevels: FloatArray = FloatArray(24) { 0f },
    pendingConnectionRequest: Pair<String, String>? = null,
    onConnectionResponse: (Boolean) -> Unit = {},
    onClientIpSelected: (String) -> Unit
) {
    val deviceIp = remember { getDeviceIpAddress(context) }
    // 本机全部可用地址（含 IPv6），外网直连时可人工核对
    val localAddresses = remember { AddressReporter.collectAddresses() }
    val port = "5000" // Fixed port matching the service
    
    // Get preferences
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val autoStart = prefs.getBoolean(AppPrefs.KEY_AUTO_START, false)
    // 可视化条与音量滑块固定常驻显示（设置项已删除）
    val showVisualizer = true
    val showVolumeSlider = true
    // 电脑放音的音频输出固定为「远端」：手机只当电脑的麦克风，不自播
    val audioOutputMode = "remote_only"

    // --- STATE ---
    var isBroadcastMode by remember { mutableStateOf(false) } // Default: Receiver Mode
    var isServiceRunning by remember { mutableStateOf(autoStart) } // Service state based on preference
    var volume by remember { mutableFloatStateOf(0.8f) }
    var isMuted by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDiagLogDialog by remember { mutableStateOf(false) }
    // 诊断日志：开关状态、日志文本、上传/分享的进行状态与结果提示
    var diagEnabled by remember { mutableStateOf(DiagLog.isEnabled()) }
    var diagText by remember { mutableStateOf("") }
    var diagResult by remember { mutableStateOf("") }
    var diagBusy by remember { mutableStateOf(false) }
    var connectingToIp by remember { mutableStateOf("") } // Track which device we're connecting to

    // 观察采集服务回传的真实推流状态：连上才显示「正在广播」，连不上提示原因。
    // 在此之前是 startForegroundService 之后无条件置 isServiceRunning = true，
    // 导致电脑端根本没开监听时手机也谎报成功——用户完全无法判断真实状态。
    val activity = LocalContext.current as? MainActivity
    LaunchedEffect(activity?.captureStateSeq ?: 0) {
        val state = activity?.captureStateValue ?: return@LaunchedEffect
        when (state) {
            "connected" -> {
                isServiceRunning = true
                connectingToIp = ""
            }
            "failed" -> {
                isServiceRunning = false
                connectingToIp = ""
                val reason = activity?.captureStateReason ?: ""
                Toast.makeText(
                    context,
                    "连不上电脑：${reason.ifEmpty { "电脑端可能没开启接收" }}",
                    Toast.LENGTH_LONG
                ).show()
            }
            "stopped" -> {
                isServiceRunning = false
                connectingToIp = ""
            }
        }
    }

    // Only initialize media projection components when not in preview mode
    val isPreview = LocalInspectionMode.current
    
    
    // Reset selection when service stops unexpectedly
    LaunchedEffect(isClientConnected) {
        if (!isClientConnected && isBroadcastMode && isServiceRunning) {
            // Connection was lost
            isServiceRunning = false
        }
    }
    
    // Clear connecting state when connection is established or failed
    LaunchedEffect(isClientConnected, clientIp, isServiceRunning) {
        if (isServiceRunning || clientIp.isEmpty()) {
            connectingToIp = ""
        }
    }

    // 权限回调是被 remember 住的旧 lambda，直接捕获 clientIp 会拿到「点击前」的旧值
    // （可能为空），服务就会收到空的 TARGET_IP 而启不来。用 rememberUpdatedState
    // 包一层，保证回调触发时读到的是最新值。
    val currentTargetIp by rememberUpdatedState(clientIp)
    val currentOutputMode by rememberUpdatedState(audioOutputMode)

    val recordAudioPermissionLauncher = if (!isPreview) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // 电脑放音只需要麦克风：授权后直接拉起采集服务，不再申请屏幕投射，
                // 系统因此不会再弹「要开始录制或投射内容吗？」
                try {
                    val intent = Intent(context, AudioCaptureService::class.java).apply {
                        action = AudioCaptureService.ACTION_START
                        putExtra(
                            AudioCaptureService.EXTRA_TARGET_IP,
                            MainActivity.pendingCaptureIp.ifEmpty { currentTargetIp }
                        )
                        putExtra(AudioCaptureService.EXTRA_TARGET_PORT, 5000)
                        putExtra(AudioCaptureService.EXTRA_AUDIO_OUTPUT_MODE, currentOutputMode)
                    }
                    ContextCompat.startForegroundService(context, intent)
                    // ⚠️ 这里**不再**无条件置 isServiceRunning = true。
                    // startForegroundService 只是「请求」系统启动服务，真正有没有连上电脑
                    // 要等服务自己回调：连上了会发 CAPTURE_STATE(connected)，
                    // 连不上会发 failed——在此之前 UI 只显示「连接中」，不再谎报「正在广播」。
                    connectingToIp = MainActivity.pendingCaptureIp.ifEmpty { currentTargetIp }
                } catch (ex: Exception) {
                    // 启动失败要留下线索，否则只会看到「点了没反应」或「一闪就崩」
                    DiagLog.e(
                        "采集",
                        "启动采集服务失败：${ex.javaClass.simpleName}：${ex.message}"
                    )
                    Toast.makeText(context, "启动失败：${ex.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "电脑放音需要录音权限。", Toast.LENGTH_LONG).show()
            }
        }
    } else null

    // Dynamic colors based on connection state
    val statusColor by animateColorAsState(
        if (isClientConnected && !isBroadcastMode) MaterialTheme.colorScheme.primary
        else if (isBroadcastMode && isServiceRunning) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.error,
        label = "colorState"
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Aurelay", fontWeight = FontWeight.Bold) },
                actions = {
                    // 右上角直接进设置（「关于」已整体删除）
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {

            // Mode Toggle Switch
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(
                    text = "手机放音",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (!isBroadcastMode) FontWeight.Bold else FontWeight.Normal,
                    color = if (!isBroadcastMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = isBroadcastMode,
                    onCheckedChange = {
                        if (it) { // Trying to switch to Broadcast (Sender) Mode
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                Toast.makeText(context, "音频采集需要 Android 10 及以上", Toast.LENGTH_LONG).show()
                                isBroadcastMode = false // Prevent switch
                            } else {
                                isBroadcastMode = true
                                // Stop Receiver Service if running when switching modes?
                                // Assuming we stop previous mode service to avoid conflicts or confusion
                                if (isServiceRunning) {
                                    val intent = Intent(context, AudioRelayService::class.java)
                                    context.stopService(intent)
                                    isServiceRunning = false
                                }
                            }
                        } else { // Switching back to Receiver Mode
                            isBroadcastMode = false
                             // Stop Sender Service if running
                            if (isServiceRunning) {
                                val intent = Intent(context, AudioCaptureService::class.java)
                                context.stopService(intent)
                                isServiceRunning = false
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Text(
                    text = "电脑放音",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isBroadcastMode) FontWeight.Bold else FontWeight.Normal,
                    color = if (isBroadcastMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 1. HEADER SECTION: Status Indicator
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.15f))
                        .then(
                            if (isClientConnected && !isBroadcastMode) Modifier.clickable {
                                isMuted = !isMuted
                                val muteIntent = Intent(context, AudioRelayService::class.java).apply {
                                    action = AudioRelayService.ACTION_SET_VOLUME
                                    putExtra(AudioRelayService.EXTRA_VOLUME, if (isMuted) 0f else volume)
                                }
                                context.startService(muteIntent)
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isBroadcastMode) Icons.Rounded.PowerSettingsNew
                                     else if (!isClientConnected) Icons.Rounded.LinkOff
                                     else if (isMuted) Icons.Rounded.HeadsetOff 
                                     else Icons.Rounded.Headphones,
                        contentDescription = if (isMuted) "取消静音" else "静音",
                        modifier = Modifier.size(56.dp),
                        tint = statusColor
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))

                val statusText = if (isBroadcastMode) {
                    if (isServiceRunning) "正在广播音频" else "可以开始广播"
                } else {
                    if (isClientConnected) {
                         if (isMuted) "已静音" else "正在接收播放"
                    } else if (isServiceRunning) "等待连接" else "服务未启动"
                }
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isBroadcastMode) {
                         if (isServiceRunning) "正在监听端口 $port" else "点击「开始」以推送音频"
                    } else {
                         if (isClientConnected) "已连接：$clientIp:$port" else "点击「开始」以等待连接"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 2. MIDDLE SECTION: Visualizer OR Connection Info
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(visible = isClientConnected && !isBroadcastMode && showVisualizer) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Real Audio Visualizer
                        RealAudioVisualizer(audioLevels = audioLevels)
                        Spacer(Modifier.height(32.dp))
                        // Volume Slider - conditionally shown
                        if (showVolumeSlider) {
                            Text(
                                "本机音量",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Slider(
                                value = volume,
                                onValueChange = { newVolume ->
                                    volume = newVolume
                                    isMuted = false // Unmute when user adjusts volume
                                    // Send volume change to service
                                    val volumeIntent = Intent(context, AudioRelayService::class.java).apply {
                                        action = AudioRelayService.ACTION_SET_VOLUME
                                        putExtra(AudioRelayService.EXTRA_VOLUME, newVolume)
                                    }
                                    context.startService(volumeIntent)
                                },
                                modifier = Modifier.fillMaxWidth(0.85f)
                            )
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(visible = (!isClientConnected && !isBroadcastMode && isServiceRunning) || isBroadcastMode) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 16.dp)
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (!isBroadcastMode) {
                                    // 手机放音：按网络类型显示「地址上报」或「中转连接」卡
                                    val netType = NetworkWatcher.detect(context)
                                    val isWifiNet = netType == NetworkWatcher.NetType.WIFI
                                    // 每秒刷新一次，让「最近上报」与倒计时真正走动
                                    var tick by remember { mutableStateOf(0) }
                                    LaunchedEffect(Unit) {
                                        while (true) {
                                            tick++
                                            kotlinx.coroutines.delay(1000)
                                        }
                                    }
                                    val lastAt = remember(tick) { prefs.getLong(AddressReporter.KEY_LAST_TIME, 0L) }
                                    val elapsedMs = if (lastAt > 0L) System.currentTimeMillis() - lastAt else -1L
                                    val agoText = if (elapsedMs < 0L) "尚未上报"
                                        else if (elapsedMs < 3000L) "刚刚"
                                        else if (elapsedMs < 60_000L) "${elapsedMs / 1000} 秒前"
                                        else "${elapsedMs / 60_000} 分钟前"
                                    val remaining = if (elapsedMs < 0L) 0L
                                        else (60_000L - (elapsedMs % 60_000L)) / 1000L

                                    Text(
                                        if (isWifiNet) "地址上报" else "中转连接",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Spacer(Modifier.height(16.dp))
                                    HorizontalDivider(
                                        modifier = Modifier.fillMaxWidth(0.3f),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                                    Spacer(Modifier.height(18.dp))

                                    if (isWifiNet) {
                                        // WiFi：把本机地址同步给中心服务器，电脑端查到后直接连
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                "本机地址",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                deviceIp,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                "端口",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                port,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                "最近上报",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                agoText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            "下次上报 ${remaining} 秒后",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                                        )
                                    } else {
                                        // 蜂窝：不开本地服务，主动出站长连接中转服务器
                                        val relayServer = prefs.getString(
                                            AppPrefs.KEY_RELAY_SERVER, AppPrefs.DEFAULT_RELAY_SERVER
                                        ) ?: AppPrefs.DEFAULT_RELAY_SERVER
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                "中转服务器",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                relayServer,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                "心跳",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                if (isClientConnected) "正常 · 刚刚" else "等待连接",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(18.dp))
                                    HorizontalDivider(
                                        modifier = Modifier.fillMaxWidth(0.3f),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                                    Spacer(Modifier.height(14.dp))

                                    Text(
                                        if (isWifiNet)
                                            "电脑端会从中心服务器查到本机地址，直接连接，无需手动填写"
                                        else
                                            "蜂窝无法被公网入站，声音经服务器转发；蜂窝下不上报地址",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )

                                } else {
                                        // 电脑放音：手动维护的电脑列表（UDP 广播发现已整体删除）
                                        val deviceList = remember { mutableStateListOf<DeviceStore.Device>().apply { addAll(DeviceStore.list(context)) } }
                                        var showEditor by remember { mutableStateOf(false) }
                                        var editingIndex by remember { mutableStateOf(-1) }
                                        var editName by remember { mutableStateOf("") }
                                        var editIp by remember { mutableStateOf("") }
                                        var editPort by remember { mutableStateOf("5000") }
                                        var editError by remember { mutableStateOf("") }

                                        fun reloadDevices() {
                                            deviceList.clear()
                                            deviceList.addAll(DeviceStore.list(context))
                                        }

                                        fun openEditor(index: Int) {
                                            editingIndex = index
                                            editError = ""
                                            if (index < 0) {
                                                editName = DeviceStore.DEFAULT_NAME
                                                editIp = ""
                                                editPort = DeviceStore.DEFAULT_PORT.toString()
                                            } else {
                                                val target = deviceList[index]
                                                editName = target.name
                                                editIp = target.ip
                                                editPort = target.port.toString()
                                            }
                                            showEditor = true
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "我的电脑",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            TextButton(onClick = { openEditor(-1) }) {
                                                Text("+ 添加")
                                            }
                                        }

                                        if (deviceList.isEmpty()) {
                                            Column(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text("还没有电脑", style = MaterialTheme.typography.bodyMedium)
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    "点右上「添加」填写电脑的 IPv4 地址和端口",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        } else {
                                            deviceList.forEachIndexed { index, device ->
                                                val isConnectedToThis = clientIp == device.ip && isServiceRunning
                                                val isConnectingToThis = connectingToIp == device.ip
                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(64.dp)
                                                        .padding(vertical = 6.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (isConnectedToThis)
                                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                                                    else
                                                        MaterialTheme.colorScheme.surface,
                                                    tonalElevation = 1.dp,
                                                    border = if (!isConnectedToThis)
                                                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                                    else null
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(horizontal = 12.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.weight(1f),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Headphones,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(24.dp)
                                                            )
                                                            Spacer(Modifier.width(8.dp))
                                                            Column(
                                                                modifier = Modifier.weight(1f)
                                                            ) {
                                                                Text(
                                                                    device.name,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    style = MaterialTheme.typography.bodyLarge,
                                                                    maxLines = 1,
                                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }
                                                        Spacer(Modifier.width(2.dp))
                                                        IconButton(onClick = { openEditor(index) }) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Settings,
                                                                contentDescription = "编辑",
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                        IconButton(onClick = {
                                                            DeviceStore.remove(context, index)
                                                            reloadDevices()
                                                        }) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.LinkOff,
                                                                contentDescription = "删除",
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                        FilledTonalButton(onClick = {
                                                            if (isConnectedToThis) {
                                                                isServiceRunning = false
                                                                val intent = Intent(context, AudioCaptureService::class.java)
                                                                intent.action = AudioCaptureService.ACTION_STOP
                                                                context.startService(intent)
                                                                onClientIpSelected("")
                                                                connectingToIp = ""
                                                            } else {
                                                                // 选中这台电脑，并**直接开始推流**——
                                                                // 原来这里只记录选中项却不启动服务，
                                                                // 结果按钮一直停在「连接中」、还得再点一次「开始」
                                                                onClientIpSelected(device.ip)
                                                                connectingToIp = device.ip
                                                                // 同步写入普通字段，确保权限回调立即触发时也能拿到正确的 IP
                                                                MainActivity.pendingCaptureIp = device.ip
                                                                recordAudioPermissionLauncher?.launch(
                                                                    android.Manifest.permission.RECORD_AUDIO
                                                                )
                                                            }
                                                        }) {
                                                            Text(
                                                                if (isConnectedToThis) "停止"
                                                                else if (isConnectingToThis) "连接中"
                                                                else "连接"
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        if (showEditor) {
                                            AlertDialog(
                                                onDismissRequest = { showEditor = false },
                                                title = {
                                                    Text(if (editingIndex < 0) "添加电脑" else "编辑电脑")
                                                },
                                                text = {
                                                    Column {
                                                        OutlinedTextField(
                                                            value = editName,
                                                            onValueChange = { editName = it },
                                                            label = { Text("设备名称") },
                                                            singleLine = true,
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                        Spacer(Modifier.height(8.dp))
                                                        OutlinedTextField(
                                                            value = editIp,
                                                            onValueChange = { editIp = it },
                                                            label = { Text("IPv4 地址") },
                                                            singleLine = true,
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                        Spacer(Modifier.height(8.dp))
                                                        OutlinedTextField(
                                                            value = editPort,
                                                            onValueChange = { editPort = it },
                                                            label = { Text("端口") },
                                                            singleLine = true,
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                        if (editError.isNotEmpty()) {
                                                            Spacer(Modifier.height(8.dp))
                                                            Text(
                                                                editError,
                                                                color = MaterialTheme.colorScheme.error,
                                                                style = MaterialTheme.typography.bodySmall
                                                            )
                                                        }
                                                    }
                                                },
                                                confirmButton = {
                                                    TextButton(onClick = {
                                                        val port = editPort.trim().toIntOrNull() ?: -1
                                                        when {
                                                            editName.trim().isEmpty() -> editError = "请填写设备名称"
                                                            !DeviceStore.isValidIpv4(editIp) -> editError = "请填写正确的 IPv4 地址"
                                                            port < 1 || port > 65535 -> editError = "端口需在 1~65535 之间"
                                                            else -> {
                                                                val target = DeviceStore.Device(editName.trim(), editIp.trim(), port)
                                                                if (editingIndex < 0) {
                                                                    if (!DeviceStore.add(context, target)) {
                                                                        editError = "最多只能保存 ${DeviceStore.MAX_COUNT} 台电脑"
                                                                        return@TextButton
                                                                    }
                                                                } else {
                                                                    DeviceStore.update(context, editingIndex, target)
                                                                }
                                                                reloadDevices()
                                                                showEditor = false
                                                            }
                                                        }
                                                    }) {
                                                        Text("保存")
                                                    }
                                                },
                                                dismissButton = {
                                                    TextButton(onClick = { showEditor = false }) {
                                                        Text("取消")
                                                    }
                                                }
                                            )
                                        }
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        if (isServiceRunning) {
                            Text(
                                if (isBroadcastMode) "正在广播…" else "正在等待连接…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            // 3. BOTTOM SECTION: Service Control Button
                Button(
                onClick = {
                    if (isServiceRunning) {
                        // STOP
                        isServiceRunning = false
                        if (isBroadcastMode) {
                             // Send disconnect to currently connected receiver
                             if (clientIp.isNotEmpty()) {
                                 onClientIpSelected("")
                             }
                             val intent = Intent(context, AudioCaptureService::class.java)
                             intent.action = AudioCaptureService.ACTION_STOP
                             context.startService(intent)
                        } else {
                            // 必须走 ACTION_STOP_SERVICE：它会 stopSelf() 并返回 START_NOT_STICKY，
                            // 而直接 stopService() 会让 START_STICKY 把服务再拉起来（表现为「点了停止还在响」）
                            val intent = Intent(context, AudioRelayService::class.java)
                            intent.action = AudioRelayService.ACTION_STOP_SERVICE
                            context.startService(intent)
                        }
                    } else {
                        // START
                        if (isBroadcastMode) {
                            // Check if receiver is selected first
                            if (clientIp.isEmpty()) {
                                Toast.makeText(context, "请先选择一台接收端设备", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            // 电脑放音只需麦克风，不再依赖 MediaProjection，
                            // 因此也不再要求「Android 10 及以上」
                            MainActivity.pendingCaptureIp = clientIp
                            recordAudioPermissionLauncher?.launch(android.Manifest.permission.RECORD_AUDIO)
                        } else {
                            isServiceRunning = true
                            val intent = Intent(context, AudioRelayService::class.java)
                            ContextCompat.startForegroundService(context, intent)
                        }
                    }
                },
                enabled = !isBroadcastMode || isServiceRunning || clientIp.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(bottom = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning)
                        MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                    else
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 2.dp,
                    pressedElevation = 4.dp
                )
            ) {
                Icon(
                    Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (isServiceRunning) "停止" else "开始",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    
    // Connection Request Confirmation Dialog
    pendingConnectionRequest?.let { (ip, name) ->
        var rememberDevice by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { onConnectionResponse(false) },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    "连接请求",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text(
                        text = "$name 请求连接到你的设备。",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "IP：$ip",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "是否允许该连接？",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    
                    // Remember device checkbox
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { rememberDevice = !rememberDevice }
                            .padding(8.dp)
                    ) {
                        Checkbox(
                            checked = rememberDevice,
                            onCheckedChange = { rememberDevice = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "记住此设备",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        onConnectionResponse(true)
                        if (rememberDevice) {
                            savePairedDevice(context, PairedDevice(name, ip, 5000))
                            Toast.makeText(context, "设备已保存到已配对列表", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(text = "允许")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { onConnectionResponse(false) }) {
                    Text(text = "拒绝")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }
    
    // About Dialog
    // Settings Dialog
    if (showSettingsDialog) {
        var tempAutoStart by remember { mutableStateOf(prefs.getBoolean(AppPrefs.KEY_AUTO_START, false)) }
        // 保活三开关（全程保活 + 一像素锚点 + 无声播放，后两项各自独立）
        var tempKeepAliveAlways by remember { mutableStateOf(prefs.getBoolean(AppPrefs.KEY_KEEP_ALIVE_ALWAYS, true)) }
        var tempOnePixel by remember { mutableStateOf(prefs.getBoolean(AppPrefs.KEY_KEEP_ALIVE_ONE_PIXEL, true)) }
        var tempSilentPlay by remember { mutableStateOf(prefs.getBoolean(AppPrefs.KEY_KEEP_ALIVE_SILENT_PLAY, true)) }
        // 中转配置
        var tempRelayServer by remember { mutableStateOf(prefs.getString(AppPrefs.KEY_RELAY_SERVER, AppPrefs.DEFAULT_RELAY_SERVER) ?: AppPrefs.DEFAULT_RELAY_SERVER) }
        var tempRelayEnabled by remember { mutableStateOf(prefs.getBoolean(AppPrefs.KEY_RELAY_ENABLED, true)) }
                    HorizontalDivider()

                    // 全程保活：20 项资源「App 运行即持有」
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "全程保活",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "App 运行期间持续持有各类锁与采集/监听资源，更费电但不易断线",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = tempKeepAliveAlways,
                            onCheckedChange = { tempKeepAliveAlways = it }
                        )
                    }

                    HorizontalDivider()

                    // 一像素锚点（独立开关）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "一像素锚点",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "锁屏时保留一个不可见的 1 像素窗口，降低被清理的概率",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = tempOnePixel,
                            onCheckedChange = { tempOnePixel = it }
                        )
                    }

                    HorizontalDivider()

                    // 无声播放锚点（独立开关）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "无声播放",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "空闲时循环播放听不到的音频；播放声音或录音时自动让位",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = tempSilentPlay,
                            onCheckedChange = { tempSilentPlay = it }
                        )
                    }

                    HorizontalDivider()


        // 外网地址上报相关临时状态（保存后才落盘）
        var tempReportEnabled by remember { mutableStateOf(prefs.getBoolean(AddressReporter.KEY_ENABLED, false)) }
        var tempReportUrl by remember { mutableStateOf(prefs.getString(AddressReporter.KEY_URL, "") ?: "") }
        var tempReportToken by remember { mutableStateOf(prefs.getString(AddressReporter.KEY_TOKEN, "") ?: "") }
        
        val configuration = LocalConfiguration.current
        val screenHeight = configuration.screenHeightDp.dp
        val dialogHeight = screenHeight * 0.65f  // Use 65% for better proportions
        
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dialogHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 连接分组标题
                    Text(
                        text = "连接",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // 中转服务器地址（蜂窝网络下声音经此服务器转发）
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "中转服务器地址",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "蜂窝网络下经此服务器转发声音",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tempRelayServer,
                            onValueChange = { tempRelayServer = it },
                            label = { Text("服务器地址") },
                            placeholder = { Text("039039.xyz:15151") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    HorizontalDivider()

                    // 允许中转
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "允许中转",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "关闭后蜂窝下无法使用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = tempRelayEnabled,
                            onCheckedChange = { tempRelayEnabled = it }
                        )
                    }

                    HorizontalDivider()

                    // 本机分组标题
                    Text(
                        text = "本机",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )


                    // Auto-start service setting
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "自动启动服务",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "打开 App 时自动启动服务",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = tempAutoStart,
                            onCheckedChange = { tempAutoStart = it }
                        )
                    }
                    
                    // 外网地址上报：把本机 IPv4/IPv6 定时同步到用户自己的中心服务器
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "公网地址上报",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "把本机公网 IPv4/IPv6 上报到你的服务器，让电脑端能在公网上找到手机并连接",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = tempReportEnabled,
                                onCheckedChange = { tempReportEnabled = it }
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = tempReportUrl,
                            onValueChange = { tempReportUrl = it },
                            label = { Text("上报接口地址") },
                            placeholder = { Text("http://your-server:8001/api/aurelay/report") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = tempReportToken,
                            onValueChange = { tempReportToken = it },
                            label = { Text("上报令牌") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = "最近上报结果：" + (prefs.getString(AddressReporter.KEY_LAST_RESULT, "尚未上报") ?: "尚未上报"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider()

                    // 诊断日志：出问题时打开开关 → 复现一次 → 上传，日志直接发到中心服务器
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val diagScope = rememberCoroutineScope()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "诊断日志",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "出问题时打开开关，复现一次后点「上传日志」",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = diagEnabled,
                                onCheckedChange = { checked ->
                                    diagEnabled = checked
                                    DiagLog.setEnabled(context, checked)
                                    diagResult =
                                        if (checked) "已开启诊断日志，请复现一次问题后上传" else "已关闭诊断日志"
                                }
                            )
                        }

                        if (diagResult.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = diagResult,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    diagText = DiagLog.dump(context)
                                    showDiagLogDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("查看日志")
                            }
                            Button(
                                onClick = {
                                    diagBusy = true
                                    diagResult = "正在上传日志…"
                                    diagScope.launch {
                                        val text = DiagLog.dump(context)
                                        val result = withContext(Dispatchers.IO) {
                                            DiagUploader.upload(context, text)
                                        }
                                        diagResult = result
                                        diagBusy = false
                                        Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                                    }
                                },
                                enabled = !diagBusy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (diagBusy) "上传中…" else "上传日志")
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 清空本地日志：删掉内存缓冲与磁盘上已轮转的日志文件
                        OutlinedButton(
                            onClick = {
                                DiagLog.clear(context)
                                diagResult = "本地日志已清空"
                                Toast.makeText(context, "本地日志已清空", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("清空日志")
                        }

                        Spacer(Modifier.height(8.dp))

                    }

                    HorizontalDivider()

                    // 版本信息：排查问题时请一并提供，避免「说不清装的是哪一版」
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "版本",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "排查问题时请一并提供这个版本号",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = DiagLog.appVersionName(context),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("Aurelay 版本", DiagLog.appVersionName(context))
                                )
                                Toast.makeText(context, "版本号已复制", Toast.LENGTH_SHORT).show()
                            }) {
                                Text("复制")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Save settings
                        prefs.edit().apply {
                            putBoolean(AppPrefs.KEY_AUTO_START, tempAutoStart)
                            putBoolean(AppPrefs.KEY_KEEP_ALIVE_ALWAYS, tempKeepAliveAlways)
                            putBoolean(AppPrefs.KEY_KEEP_ALIVE_ONE_PIXEL, tempOnePixel)
                            putBoolean(AppPrefs.KEY_KEEP_ALIVE_SILENT_PLAY, tempSilentPlay)
                            putString(AppPrefs.KEY_RELAY_SERVER, tempRelayServer.trim())
                            putBoolean(AppPrefs.KEY_RELAY_ENABLED, tempRelayEnabled)
                            putBoolean(AddressReporter.KEY_ENABLED, tempReportEnabled)
                            putString(AddressReporter.KEY_URL, tempReportUrl.trim())
                            putString(AddressReporter.KEY_TOKEN, tempReportToken.trim())
                            apply()
                        }

                        // 保活三开关立即生效（不必重启 App）
                        try {
                            if (tempKeepAliveAlways) {
                                ResourceHolder.acquireAll(context)
                            } else {
                                ResourceHolder.releaseAll(context)
                            }
                            if (tempOnePixel) OnePixelOverlay.show(context) else OnePixelOverlay.hide(context)
                            SilentPlayer.applyEnabled(context)
                        } catch (e: Exception) {
                            Log.e("Aurelay", "应用保活设置失败：${e.message}")
                        }

                        // 地址上报配置变更后立即生效：先停旧的，再按新配置启动
                        try {
                            AddressReporter.stop(context)
                            if (tempReportEnabled && tempReportUrl.trim().isNotEmpty()) {
                                AddressReporter.start(context)
                                AddressReporter.reportNow(context)
                                Toast.makeText(context, "已开启公网地址上报", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "已关闭公网地址上报", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e("AurelayReport", "更新上报配置失败：${e.message}")
                        }
                        
                        // 音频输出模式固定为「远端」（电脑放音 = 手机当电脑的麦克风，手机不自播），
                        // 设置项已删除，这里不再处理模式变更
                        showSettingsDialog = false
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 诊断日志查看对话框：可滚动查看，并支持刷新 / 复制 / 分享
    if (showDiagLogDialog) {
        val diagScroll = rememberScrollState()
        AlertDialog(
            onDismissRequest = { showDiagLogDialog = false },
            title = {
                Text(
                    text = "诊断日志",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "日志文件：" + DiagLog.filePath().ifEmpty { "不可用" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .verticalScroll(diagScroll)
                    ) {
                        Text(
                            text = diagText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagLogDialog = false }) {
                    Text("关闭")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            diagText = DiagLog.dump(context)
                            Toast.makeText(context, "日志已刷新", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("刷新")
                    }
                    TextButton(
                        onClick = {
                            diagText = DiagLog.dump(context)
                            try {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Aurelay 诊断日志", diagText))
                                Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "复制失败：${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("复制")
                    }
                    TextButton(
                        onClick = {
                            diagText = DiagLog.dump(context)
                            try {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Aurelay 诊断日志")
                                    putExtra(Intent.EXTRA_TEXT, diagText)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "分享诊断日志"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("分享")
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

// A real visualizer component that responds to actual audio data
@Composable
fun RealAudioVisualizer(audioLevels: FloatArray) {
    val brush = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary
        )
    )
    
    // Use spring animation with low bounce for smooth, gradual movement
    val animatedLevels = audioLevels.map { level ->
        animateFloatAsState(
            targetValue = level,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "audioLevel"
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        animatedLevels.forEachIndexed { index, animatedLevel ->
            val barHeight = 10f + (animatedLevel.value * 60f) // Min 10dp, max 70dp per side
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.height(140.dp)
            ) {
                // Top bar (grows upward)
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(barHeight.dp)
                        .clip(RoundedCornerShape(bottomStart = 1.5.dp, bottomEnd = 1.5.dp))
                        .background(brush)
                )
                
                // Center dot
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                
                // Bottom bar (grows downward)
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(barHeight.dp)
                        .clip(RoundedCornerShape(topStart = 1.5.dp, topEnd = 1.5.dp))
                        .background(brush)
                )
            }
        }
    }
}

// A fake visualizer component just for the mockup look (kept for preview)
@Composable
fun FakeAudioVisualizer() {
    val infiniteTransition = rememberInfiniteTransition(label = "visualizer")
    // Create a gradient brush for the bars
    val brush = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary
        )
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(12) { index ->
            // Animate height randomly to simulate audio
            val height by infiniteTransition.animateFloat(
                initialValue = 15f,
                targetValue = Random.nextInt(30, 120).toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = Random.nextInt(400, 800),
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ), label = "barHeight$index"
            )
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(brush)
            )
        }
    }
}

@Preview
@Composable
fun PreviewAurelayApp() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        // For preview, create a mock context
        AurelayApp(
            context = androidx.compose.ui.platform.LocalContext.current,
            isClientConnected = false,
            clientIp = "",
                audioLevels = FloatArray(24) { 0.5f },
                onClientIpSelected = {}
        )
    }
}
