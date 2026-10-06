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
     * :param context: 任意上下文（用于重新探测网络类型）
     * :return: 无返回值
     */
    fun onSystemNetworkChanged(context: Context) {
        val type = refresh(context)
        val snapshot: List<(NetType) -> Unit> = synchronized(lock) { ArrayList(listeners) }
        DiagLog.i("网络", "网络已切换到 $type，通知 ${snapshot.size} 个订阅者")
        for (listener in snapshot) {
            try {
                listener(type)
            } catch (e: Exception) {
                DiagLog.e("网络", "通知网络变化订阅者失败", e)
            }
        }
    }
}
