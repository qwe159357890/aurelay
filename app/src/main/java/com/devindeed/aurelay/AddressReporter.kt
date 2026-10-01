package com.devindeed.aurelay

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.UUID

/**
 * 本机地址上报器（外网模式专用）
 *
 * 原版 Aurelay 的发现机制（UDP 广播）只适用于同一局域网；而手机作为服务端时，
 * 在外网（蜂窝网络）下地址随时会变，PC 发送端无法主动得知。此上报器负责把手机
 * 当前的 IPv4 / IPv6 地址列表定时同步到用户自己的中心服务器（注册端点），
 * PC 自研发送端再从中心服务器查询并直连。
 *
 * 触发时机共有三种，缺一不可：
 * 1. 服务启动时立即上报一次；
 * 2. 每 60 秒周期上报（蜂窝地址会因切换基站等原因变化）；
 * 3. 网络切换瞬间上报（系统网络回调触发）。
 */
object AddressReporter {

    // 日志标签
    private const val TAG = "AurelayReport"

    // 配置项持久化键名（与 MainActivity 设置界面共用）
    const val KEY_ENABLED = "report_enabled"
    const val KEY_URL = "report_url"
    const val KEY_TOKEN = "report_token"
    const val KEY_DEVICE_ID = "report_device_id"
    const val KEY_LAST_RESULT = "report_last_result"

    // 周期上报间隔：60 秒
    private const val REPORT_INTERVAL_MS = 60_000L

    // 网络请求超时（毫秒）
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    // 上报线程与运行标志
    @Volatile private var reportThread: Thread? = null
    @Volatile private var running = false

    // 网络状态回调（用于网络切换时即时上报）
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 启动上报：立即上报一次，随后每 60 秒上报一次，并监听网络切换
    fun start(context: Context) {
        val appContext = context.applicationContext
        if (running) {
            Log.d(TAG, "上报器已在运行，忽略重复启动")
            return
        }
        if (!isEnabled(appContext)) {
            Log.i(TAG, "地址上报未启用（设置中开关关闭或未填写服务器地址）")
            return
        }
        running = true

        // 线程：立即上报 + 周期上报
        reportThread = Thread {
            while (running) {
                reportOnce(appContext)
                try {
                    Thread.sleep(REPORT_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }

        // 网络回调：网络恢复或切换时立刻补报一次
        registerNetworkCallback(appContext)
        Log.i(TAG, "地址上报已启动，间隔 ${REPORT_INTERVAL_MS / 1000} 秒")
    }

    // 停止上报：中断线程并注销网络回调
    fun stop(context: Context) {
        running = false
        reportThread?.interrupt()
        reportThread = null
        unregisterNetworkCallback(context.applicationContext)
        Log.i(TAG, "地址上报已停止")
    }

    // 判断上报功能是否已启用（开关打开且服务器地址非空）
    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val url = prefs.getString(KEY_URL, "")?.trim() ?: ""
        return prefs.getBoolean(KEY_ENABLED, false) && url.isNotEmpty()
    }

    // 读取或生成设备唯一标识（首次运行生成 UUID 并持久保存）
    fun getOrCreateDeviceId(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val existing = prefs.getString(KEY_DEVICE_ID, "")?.trim() ?: ""
        if (existing.isNotEmpty()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    // 立即上报一次（在后台线程执行，供服务启动时调用）
    fun reportNow(context: Context) {
        val appContext = context.applicationContext
        Thread { reportOnce(appContext) }.also { it.isDaemon = true; it.start() }
    }

    // 执行一次上报：采集本机地址并 POST 到中心服务器
    fun reportOnce(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!isEnabled(context)) {
            return
        }
        val url = prefs.getString(KEY_URL, "")?.trim() ?: ""
        val token = prefs.getString(KEY_TOKEN, "")?.trim() ?: ""

        // 采集本机 IPv4 / IPv6 地址
        val addresses = collectAddresses()
        val ipv4List = addresses.first
        val ipv6List = addresses.second

        val deviceId = getOrCreateDeviceId(context)
        val deviceName = getDeviceName()

        // 构造上报请求体（字段名与中心服务器约定一致）
        val payload = buildString {
            append("{")
            append("\"device_id\":\"").append(escapeJson(deviceId)).append("\",")
            append("\"device_name\":\"").append(escapeJson(deviceName)).append("\",")
            append("\"port\":").append(AudioRelayService.AUDIO_PORT).append(",")
            append("\"ipv4\":").append(toJsonArray(ipv4List)).append(",")
            append("\"ipv6\":").append(toJsonArray(ipv6List))
            append("}")
        }

        Log.i(TAG, "开始上报：$url")
        var connection: HttpURLConnection? = null
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // 令牌同时以两种常见方式带上，服务端任取其一即可
            if (token.isNotEmpty()) {
                conn.setRequestProperty("X-API-Key", token)
                conn.setRequestProperty("Authorization", "Bearer $token")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            val code = conn.responseCode
            val ok = code in 200..299
            val summary = if (ok) {
                "上报成功 (HTTP $code) IPv4=${ipv4List.size} IPv6=${ipv6List.size}"
            } else {
                "上报失败 HTTP $code"
            }
            saveResult(prefs, if (ok) "成功" else "HTTP $code")
            Log.i(TAG, "$summary 地址=$ipv4List $ipv6List")
        } catch (e: Exception) {
            // 结果里带上原因摘要（明文被拦、连接被拒、DNS 失败等），便于在设置页直接定位
            val detail = e.message?.trim()?.take(60) ?: e.javaClass.simpleName
            saveResult(prefs, "异常: $detail")
            Log.w(TAG, "上报失败：${e.javaClass.simpleName} - ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }

    // 记录最近一次上报结果与时间，供界面展示
    private fun saveResult(prefs: SharedPreferences, result: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        prefs.edit().putString(KEY_LAST_RESULT, "$result @ $time").apply()
    }

    // 采集本机所有可用的 IPv4 / IPv6 地址（已排除回环与链路本地地址）
    fun collectAddresses(): Pair<List<String>, List<String>> {
        val ipv4 = mutableListOf<String>()
        val ipv6 = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return Pair(ipv4, ipv6)
            for (networkInterface in java.util.Collections.list(interfaces)) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (address in java.util.Collections.list(networkInterface.inetAddresses)) {
                    if (address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    // 去掉 IPv6 地址上可能带的作用域后缀（如 %wlan0）
                    val clean = host.substringBefore('%')
                    when (address) {
                        is Inet4Address -> {
                            if (!address.isLinkLocalAddress && clean !in ipv4) ipv4.add(clean)
                        }
                        is Inet6Address -> {
                            if (!address.isLinkLocalAddress && clean !in ipv6) ipv6.add(clean)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "采集本机地址失败：${e.message}")
        }
        return Pair(ipv4, ipv6)
    }

    // 拼接 JSON 字符串数组
    private fun toJsonArray(items: List<String>): String {
        return items.joinToString(prefix = "[", postfix = "]") { "\"" + escapeJson(it) + "\"" }
    }

    // 转义 JSON 字符串中的特殊字符
    private fun escapeJson(raw: String): String {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    // 注册网络状态回调：网络重新可用或切换时立即补报一次地址
    private fun registerNetworkCallback(context: Context) {
        try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            if (networkCallback != null) return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "检测到网络可用，立即补报地址")
                    reportNow(context)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    // 网络能力变化（如从 WiFi 切到蜂窝）时也补报一次
                    Log.i(TAG, "网络能力发生变化，立即补报地址")
                    reportNow(context)
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            manager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "注册网络回调失败：${e.message}")
        }
    }

    // 注销网络状态回调
    private fun unregisterNetworkCallback(context: Context) {
        val callback = networkCallback ?: return
        try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            manager?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "注销网络回调失败：${e.message}")
        } finally {
            networkCallback = null
        }
    }
}
