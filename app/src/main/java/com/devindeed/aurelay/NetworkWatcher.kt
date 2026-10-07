package com.devindeed.aurelay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 网络类型监视器（链路决策的唯一依据）
 *
 * 手机放音的链路策略由当前默认网络决定：
 * - WiFi / 以太网 → 局域网直连（ServerSocket(:5000) 被动等待电脑接入）
 * - 蜂窝 / 其它   → 服务器中转（主动出站连中转服务器）
 *
 * 网络切换由 ResourceHolder 注册的 `NetworkCallback` 回调到这里，
 * 再由这里通知各订阅者（服务与界面）。
 */
object NetworkWatcher {

    /**
     * 网络类型枚举
     */
    enum class NetType {
        // 无线局域网（含以太网）
        WIFI,
        // 蜂窝移动网络
        CELLULAR,
        // 其它（VPN、蓝牙网络共享等）
        OTHER,
        // 无可用网络
        NONE
    }

    // 订阅者列表（回调运行在网络回调线程，调用方需自行切回主线程）
    private val listeners = ArrayList<(NetType) -> Unit>()

    // 最近一次探测到的网络类型
    @Volatile private var current: NetType = NetType.NONE

    // 监听器锁
    private val lock = Any()

    // 网络变化去抖时长（毫秒）：切换瞬间网络处于过渡态，延迟这么久再探测，
    // 才能拿到真实的最终类型（否则 WiFi→蜂窝切换时 detect 仍返回 WIFI）
    private const val DEBOUNCE_MS = 800L

    /**
     * 探测当前默认网络的类型
     *
     * :param context: 任意上下文
     * :return: 当前网络类型；无网络时返回 NONE
     */
    fun detect(context: Context): NetType {
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return NetType.NONE
            val network = cm.activeNetwork ?: return NetType.NONE
            val caps = cm.getNetworkCapabilities(network) ?: return NetType.OTHER
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.CELLULAR
                else -> NetType.OTHER
            }
        } catch (e: Exception) {
            DiagLog.e("网络", "探测网络类型失败", e)
            NetType.NONE
        }
    }

    /**
     * 取最近一次探测到的网络类型（不重新探测）
     *
     * :return: 最近一次的网络类型
     */
    fun lastKnown(): NetType = current

    /**
     * 刷新并缓存当前网络类型
     *
     * :param context: 任意上下文
     * :return: 刷新后的网络类型
     */
    fun refresh(context: Context): NetType {
        val type = detect(context)
        current = type
        DiagLog.i("网络", "当前网络类型：$type")
        return type
    }

    /**
     * 添加网络变化订阅者
     *
     * :param listener: 网络变化回调，参数为新的网络类型
     * :return: 无返回值
     */
    fun addListener(listener: (NetType) -> Unit) {
        synchronized(lock) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }
    }

    /**
     * 移除网络变化订阅者
     *
     * :param listener: 之前添加过的回调
     * :return: 无返回值
     */
    fun removeListener(listener: (NetType) -> Unit) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    /**
     * 系统网络变化入口（由 ResourceHolder 的 NetworkCallback 调用）
     *
     * 先重新探测网络类型并缓存，再通知全部订阅者，保证订阅者拿到的是最新值。
     *
     * ⚠️ 去重：Android 切网络时 NetworkCallback 通常会**连续触发多次**
     * （onLost / onAvailable / onCapabilitiesChanged 各一次，有时还夹带
     * onLinkPropertiesChanged）。若不区分「网络类型是否真的变了」，会导致
     * 订阅者（服务层）在同一网络类型下反复「重新选择链路」——实测 18:44:14
     * 切蜂窝后 18:44:28 又触发一次 CELLULAR，服务层重走 startRelayLink，
     * 中转链路被打断、重连，进而诱发「残留帧 → forceReconnect」死循环。
     * 这里只在类型真正变化时通知，其余变化（如 IP 变更、能力微调）忽略。
     *
     * ⚠️ 过渡态陷阱（本次 19:18 事故的根因）：WiFi→蜂窝切换时，onLost(WiFi)
     * 触发的一瞬间蜂窝网络往往**还没就绪**，此刻 detect() 拿到的 activeNetwork
     * 仍是旧 WiFi，返回 WIFI——与缓存的 WIFI 相同，被上面的去重直接 return。
     * 等蜂窝真正就绪后，系统又未必再触发一次 onAvailable（或触发时 detect
     * 仍短暂返回 WIFI），于是 CELLULAR 永远检测不到，中转链路（R）从不启动，
     * 电脑端 8 秒一跳空转（实测服务器日志 19:18:34~19:20:20 每 8 秒一次
     * 「发送方已接入→已下线」）。因此这里不能「立即 detect」，
     * 而要**延迟一段时间**等网络尘埃落定后再探测，拿到真实的最终类型。
     *
     * :param context: 任意上下文（用于重新探测网络类型）
     * :return: 无返回值
     */
    fun onSystemNetworkChanged(context: Context) {
        // 延迟重探测：切换瞬间系统网络处于过渡态（旧网已丢、新网未就绪），
        // 立即 detect 会拿到旧类型；等 800ms 让蜂窝/WiFi 真正建立后再探测。
        // 回调本身可能连发多次，用独立线程去抖，取最后一次的稳定结果。
        val appContext = context.applicationContext
        val snapshot: List<(NetType) -> Unit> = synchronized(lock) { ArrayList(listeners) }
        Thread({
            try {
                Thread.sleep(DEBOUNCE_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            val type = detect(appContext)
            val previous = current
            current = type
            DiagLog.i("网络", "当前网络类型：$type")
            if (type == previous) {
                // 延迟后类型仍与缓存一致：说明确实没变，或回调是抖动，忽略
                return@Thread
            }
            DiagLog.i("网络", "网络已切换到 $type，通知 ${snapshot.size} 个订阅者")
            for (listener in snapshot) {
                try {
                    listener(type)
                } catch (e: Exception) {
                    DiagLog.e("网络", "通知网络变化订阅者失败", e)
                }
            }
        }, "AurelayNetDebounce").start()
    }
}
