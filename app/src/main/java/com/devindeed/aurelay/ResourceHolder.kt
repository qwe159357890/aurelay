package com.devindeed.aurelay

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * 全程保活资源持有器（8.3.1 受控资源总表中可集中管理的部分）
 *
 * 设计目标：把「使用时申请、用完就释放」改成「App 运行期间一直持有」，
 * 由一个单例统一 acquire / release，避免多处代码各自持有导致释放不彻底。
 *
 * 切换「全程保活」开关的效果：
 * - 打开：立即持有下列全部资源，直到进程结束或用户关闭开关；
 * - 关闭：立即释放（若正在推流/接收，则由各服务在本次流结束后自行释放）。
 *
 * 不在本类管理的资源（由各自服务按开关值决定行为）：
 * 前台服务常驻、中转长连接、ServerSocket(:5000)、地址上报定时器、
 * MediaProjection/AudioRecord、MediaCodec、AudioTrack、NFC（需 Activity）。
 */
object ResourceHolder {

    // 唤醒锁与 WiFi 锁的标签（Logcat 排查用）
    private const val WAKE_TAG = "Aurelay:KeepAlive"
    private const val WIFI_TAG = "Aurelay:WifiLock"
    private const val MULTICAST_TAG = "Aurelay:Multicast"

    // 保活兜底闹钟的请求码
    private const val ALARM_REQUEST_CODE = 9001

    // 保活兜底周期任务的 ID
    private const val JOB_ID = 9002

    // 兜底唤醒/任务周期（15 分钟，系统允许的最小值）
    private const val PERIOD_MS = 15 * 60 * 1000L

    // 唤醒锁
    private var wakeLock: PowerManager.WakeLock? = null

    // WiFi 高性能锁
    private var wifiLock: WifiManager.WifiLock? = null

    // 组播锁（UDP 已删除，权限仍申请；此处保留持有逻辑以便将来恢复组播）
    private var multicastLock: WifiManager.MulticastLock? = null

    // 网络回调（监听网络切换，触发链路切换）
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 位置监听
    private var locationListener: LocationListener? = null

    // 传感器监听
    private var sensorListener: SensorEventListener? = null

    // 蓝牙扫描回调
    private var scanCallback: android.bluetooth.le.ScanCallback? = null

    // 已打开的相机设备（后台会被系统静默断流，属预期）
    private var cameraDevice: android.hardware.camera2.CameraDevice? = null

    // 是否已持有资源（避免重复 acquire）
    @Volatile private var holding = false

    /**
     * 持有全部受控资源（幂等，重复调用只会记一条调试日志）
     *
     * :param context: 任意上下文
     * :return: 无返回值
     */
    @Suppress("MissingPermission")
    fun acquireAll(context: Context) {
        if (holding) return
        holding = true
        val app = context.applicationContext
        DiagLog.i("保活", "全程保活：开始持有受控资源")

        acquireWakeLock(app)
        acquireWifiLock(app)
        acquireMulticastLock(app)
        registerAlarm(app)
        registerJob(app)
        registerNetworkCallback(app)
        registerLocation(app)
        registerSensor(app)
        startBluetoothScan(app)
        openCamera(app)
    }

    /**
     * 释放全部受控资源（幂等）
     *
     * :param context: 任意上下文
     * :return: 无返回值
     */
    fun releaseAll(context: Context) {
        if (!holding) return
        holding = false
        val app = context.applicationContext
        DiagLog.i("保活", "全程保活：释放受控资源")

        releaseWakeLock()
        releaseWifiLock()
        releaseMulticastLock()
        cancelAlarm(app)
        cancelJob(app)
        unregisterNetworkCallback(app)
        unregisterLocation(app)
        unregisterSensor(app)
        stopBluetoothScan(app)
        closeCamera()
    }

    /**
     * 获取并持有 CPU 唤醒锁
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun acquireWakeLock(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            lock.setReferenceCounted(false)
            if (!lock.isHeld) lock.acquire()
            wakeLock = lock
            DiagLog.i("保活", "已持有 PARTIAL_WAKE_LOCK")
        } catch (e: Exception) {
            DiagLog.e("保活", "获取唤醒锁失败", e)
        }
    }

    /**
     * 释放 CPU 唤醒锁
     *
     * :return: 无返回值
     */
    private fun releaseWakeLock() {
        try {
            val lock = wakeLock ?: return
            if (lock.isHeld) lock.release()
            wakeLock = null
            DiagLog.i("保活", "已释放 PARTIAL_WAKE_LOCK")
        } catch (e: Exception) {
            DiagLog.e("保活", "释放唤醒锁失败", e)
        }
    }

    /**
     * 获取并持有 WiFi 高性能锁（避免 WiFi 省电降频导致断流）
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun acquireWifiLock(context: Context) {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            @Suppress("DEPRECATION")
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
            lock.setReferenceCounted(false)
            if (!lock.isHeld) lock.acquire()
            wifiLock = lock
            DiagLog.i("保活", "已持有 WifiLock")
        } catch (e: Exception) {
            DiagLog.e("保活", "获取 WifiLock 失败", e)
        }
    }

    /**
     * 释放 WiFi 高性能锁
     *
     * :return: 无返回值
     */
    private fun releaseWifiLock() {
        try {
            val lock = wifiLock ?: return
            if (lock.isHeld) lock.release()
            wifiLock = null
            DiagLog.i("保活", "已释放 WifiLock")
        } catch (e: Exception) {
            DiagLog.e("保活", "释放 WifiLock 失败", e)
        }
    }

    /**
     * 获取并持有组播锁（UDP 已删除，此为预留实现）
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun acquireMulticastLock(context: Context) {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            val lock = wm.createMulticastLock(MULTICAST_TAG)
            lock.setReferenceCounted(false)
            if (!lock.isHeld) lock.acquire()
            multicastLock = lock
            DiagLog.i("保活", "已持有 MulticastLock")
        } catch (e: Exception) {
            DiagLog.e("保活", "获取 MulticastLock 失败", e)
        }
    }

    /**
     * 释放组播锁
     *
     * :return: 无返回值
     */
    private fun releaseMulticastLock() {
        try {
            val lock = multicastLock ?: return
            if (lock.isHeld) lock.release()
            multicastLock = null
        } catch (e: Exception) {
            DiagLog.e("保活", "释放 MulticastLock 失败", e)
        }
    }

    /**
     * 注册保活兜底闹钟（注册后不取消，进程不在时也能唤醒拉起）
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun registerAlarm(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = keepAlivePendingIntent(context) ?: return
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + PERIOD_MS,
                PERIOD_MS,
                pi
            )
            DiagLog.i("保活", "已注册 AlarmManager 兜底（周期 ${PERIOD_MS / 60000} 分钟）")
        } catch (e: Exception) {
            DiagLog.e("保活", "注册 AlarmManager 兜底失败", e)
        }
    }

    /**
     * 取消保活兜底闹钟（仅在用户关闭全程保活开关时调用）
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun cancelAlarm(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = keepAlivePendingIntent(context) ?: return
            am.cancel(pi)
            DiagLog.i("保活", "已取消 AlarmManager 兜底")
        } catch (e: Exception) {
            DiagLog.e("保活", "取消 AlarmManager 兜底失败", e)
        }
    }

    /**
     * 构造保活兜底用的 PendingIntent（显式指向保活广播接收器）
     *
     * :param context: 应用上下文
     * :return: 可用的 PendingIntent；构造失败返回 null
     */
    private fun keepAlivePendingIntent(context: Context): PendingIntent? {
        val intent = Intent(context, KeepAliveReceiver::class.java)
        intent.action = KeepAliveReceiver.ACTION_KEEP_ALIVE
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
    }

    /**
     * 注册保活兜底周期任务（setPersisted 可在重启后继续）
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun registerJob(context: Context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val component = ComponentName(context, KeepAliveJobService::class.java)
            val info = JobInfo.Builder(JOB_ID, component)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .setRequiresCharging(false)
                .build()
            js.schedule(info)
            DiagLog.i("保活", "已注册 JobScheduler 兜底（周期 ${PERIOD_MS / 60000} 分钟）")
        } catch (e: Exception) {
            DiagLog.e("保活", "注册 JobScheduler 兜底失败", e)
        }
    }

    /**
     * 取消保活兜底周期任务
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun cancelJob(context: Context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            js.cancel(JOB_ID)
            DiagLog.i("保活", "已取消 JobScheduler 兜底")
        } catch (e: Exception) {
            DiagLog.e("保活", "取消 JobScheduler 兜底失败", e)
        }
    }

    /**
     * 注册网络变化回调（网络切换时触发链路决策）
     *
     * 真正的链路切换业务在 NetworkWatcher 里，这里只负责「常驻注册」本身。
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun registerNetworkCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    DiagLog.i("保活", "网络可用")
                    NetworkWatcher.onSystemNetworkChanged(context)
                }

                override fun onLost(network: android.net.Network) {
                    DiagLog.i("保活", "网络丢失")
                    NetworkWatcher.onSystemNetworkChanged(context)
                }
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            DiagLog.i("保活", "已注册 NetworkCallback")
        } catch (e: Exception) {
            DiagLog.e("保活", "注册 NetworkCallback 失败", e)
        }
    }

    /**
     * 注销网络变化回调
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun unregisterNetworkCallback(context: Context) {
        try {
            val callback = networkCallback ?: return
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            cm.unregisterNetworkCallback(callback)
            networkCallback = null
        } catch (e: Exception) {
            DiagLog.e("保活", "注销 NetworkCallback 失败", e)
        }
    }

    /**
     * 注册位置监听（GPS + 网络双源；数据仅计数写日志，不用于业务）
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    @Suppress("MissingPermission")
    private fun registerLocation(context: Context) {
        try {
            if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                DiagLog.w("保活", "位置监听未注册：缺少定位权限")
                return
            }
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    DiagLog.d("监听", "位置更新一次（仅计数，不使用）")
                }

                override fun onLocationChanged(locations: MutableList<Location>) {
                    DiagLog.d("监听", "位置批量更新 ${locations.size} 条（仅计数）")
                }

                override fun onFlushComplete(requestCode: Int) {
                    // 无需处理
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                    // 无需处理
                }

                override fun onProviderEnabled(provider: String) {
                    // 无需处理
                }

                override fun onProviderDisabled(provider: String) {
                    // 无需处理
                }
            }
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 60_000L, 0f, listener)
                }
            }
            locationListener = listener
            DiagLog.i("保活", "已注册位置监听")
        } catch (e: Exception) {
            DiagLog.e("保活", "注册位置监听失败", e)
        }
    }

    /**
     * 注销位置监听
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun unregisterLocation(context: Context) {
        try {
            val listener = locationListener ?: return
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            lm.removeUpdates(listener)
            locationListener = null
        } catch (e: Exception) {
            DiagLog.e("保活", "注销位置监听失败", e)
        }
    }

    /**
     * 注册传感器监听（加速度 + 陀螺仪；数据仅计数写日志）
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun registerSensor(context: Context) {
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    // 仅计数，不做业务处理
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    // 无需处理
                }
            }
            var registered = false
            for (type in intArrayOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) {
                val sensor = sm.getDefaultSensor(type)
                if (sensor != null) {
                    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                    registered = true
                }
            }
            if (registered) {
                sensorListener = listener
                DiagLog.i("保活", "已注册传感器监听")
            }
        } catch (e: Exception) {
            DiagLog.e("保活", "注册传感器监听失败", e)
        }
    }

    /**
     * 注销传感器监听
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    private fun unregisterSensor(context: Context) {
        try {
            val listener = sensorListener ?: return
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            sm.unregisterListener(listener)
            sensorListener = null
        } catch (e: Exception) {
            DiagLog.e("保活", "注销传感器监听失败", e)
        }
    }

    /**
     * 启动低功耗蓝牙扫描（结果仅计数写日志）
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    @Suppress("MissingPermission")
    private fun startBluetoothScan(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)) {
                    DiagLog.w("保活", "蓝牙扫描未启动：缺少 BLUETOOTH_SCAN 权限")
                    return
                }
            }
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
            val scanner = adapter.bluetoothLeScanner ?: return
            val callback = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
                    // 仅计数，不做业务处理
                }

                override fun onScanFailed(errorCode: Int) {
                    DiagLog.w("保活", "蓝牙扫描失败：错误码 $errorCode")
                }
            }
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            scanner.startScan(null, settings, callback)
            scanCallback = callback
            DiagLog.i("保活", "已启动蓝牙低功耗扫描")
        } catch (e: Exception) {
            DiagLog.e("保活", "启动蓝牙扫描失败", e)
        }
    }

    /**
     * 停止蓝牙扫描
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    @Suppress("MissingPermission")
    private fun stopBluetoothScan(context: Context) {
        try {
            val callback = scanCallback ?: return
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
            adapter.bluetoothLeScanner?.stopScan(callback)
            scanCallback = null
        } catch (e: Exception) {
            DiagLog.e("保活", "停止蓝牙扫描失败", e)
        }
    }

    /**
     * 打开并保持相机会话（Android 9+ 后台会被静默断流，属预期行为）
     *
     * :param context: 应用上下文
     * :return: 无返回值
     */
    @Suppress("MissingPermission")
    private fun openCamera(context: Context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
            if (!hasPermission(context, Manifest.permission.CAMERA)) {
                DiagLog.w("保活", "相机未打开：缺少相机权限")
                return
            }
            val cm = context.getSystemService(Context.CAMERA_SERVICE)
                as? android.hardware.camera2.CameraManager ?: return
            val cameraId = cm.cameraIdList.firstOrNull() ?: return
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            cm.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                    cameraDevice = camera
                    DiagLog.i("保活", "已打开相机会话（仅持句柄，不预览）")
                }

                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                    cameraDevice = null
                    DiagLog.w("保活", "相机已断开（后台断流属预期）")
                }

                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                    cameraDevice = null
                    DiagLog.w("保活", "相机打开失败：错误码 $error")
                }
            }, handler)
        } catch (e: Exception) {
            DiagLog.e("保活", "打开相机失败", e)
        }
    }

    /**
     * 关闭相机会话
     *
     * :return: 无返回值
     */
    private fun closeCamera() {
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            DiagLog.e("保活", "关闭相机失败", e)
        }
    }

    /**
     * 判断某项权限是否已授予
     *
     * :param context: 任意上下文
     * :param permission: 权限名
     * :return: true 表示已授予
     */
    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 把系统媒体音量置 0（仅电脑放音方向使用，防止扬声器被麦克风采到形成回授）
     *
     * ⚠️ 只在电脑放音方向调用：手机放音方向必须保留音量，否则会听不到声音。
     *
     * :param context: 任意上下文
     * :return: 无返回值
     */
    fun muteMediaVolume(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                }
            } else {
                @Suppress("DEPRECATION")
                am.setStreamMute(AudioManager.STREAM_MUSIC, true)
            }
            DiagLog.i("保活", "已将系统媒体音量静音（电脑放音防回授）")
        } catch (e: Exception) {
            DiagLog.e("保活", "静音系统媒体音量失败", e)
        }
    }

    /**
     * 恢复系统媒体音量
     *
     * :param context: 任意上下文
     * :return: 无返回值
     */
    fun unmuteMediaVolume(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }
            } else {
                @Suppress("DEPRECATION")
                am.setStreamMute(AudioManager.STREAM_MUSIC, false)
            }
            DiagLog.i("保活", "已恢复系统媒体音量")
        } catch (e: Exception) {
            DiagLog.e("保活", "恢复系统媒体音量失败", e)
        }
    }
}
