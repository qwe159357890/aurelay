package com.devindeed.aurelay

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * 应用偏好设置的统一入口
 *
 * 把所有持久化键名集中到这里管理，避免各文件硬编码字符串写错、写偏。
 * 存储载体固定为 `PreferenceManager.getDefaultSharedPreferences`（与原有代码一致）。
 */
object AppPrefs {

    // 自动启动服务（语义已扩展为「打开 App + 开机」）
    const val KEY_AUTO_START = "auto_start_service"

    // 全程保活：8.3.1 受控资源总表 20 项「App 运行即持有」
    const val KEY_KEEP_ALIVE_ALWAYS = "keep_alive_always"

    // 一像素锚点（8.3.2，独立开关）
    const val KEY_KEEP_ALIVE_ONE_PIXEL = "keep_alive_one_pixel"

    // 无声播放锚点（8.3.3，独立开关）
    const val KEY_KEEP_ALIVE_SILENT_PLAY = "keep_alive_silent_play"

    // 地址上报开关
    const val KEY_REPORT_ENABLED = "report_enabled"

    // 上报接口地址
    const val KEY_REPORT_URL = "report_url"

    // 上报令牌
    const val KEY_REPORT_TOKEN = "report_token"

    // 设备标识（首次运行生成）
    const val KEY_REPORT_DEVICE_ID = "report_device_id"

    // 最近一次上报结果文案
    const val KEY_REPORT_LAST_RESULT = "report_last_result"

    // 允许服务器中转
    const val KEY_RELAY_ENABLED = "relay_enabled"

    // 中转服务器地址（形如 039039.xyz:15151）
    const val KEY_RELAY_SERVER = "relay_server"

    // 链路模式（用户可选三档，取代原先「按网络类型强制自动切换」）：
    //   auto 自动（WiFi→局域网直连、蜂窝→服务器中转，默认）
    //   lan  强制局域网直连（无视网络类型，只监听 5000 端口等电脑接入）
    //   relay 强制服务器中转（无视网络类型，主动出站连中转服务器）
    // 用途：手机连着 WiFi 但电脑不在同一局域网（如访客 WiFi / AP 隔离 / 异地）时，
    // 自动模式会误判「WiFi 就该直连」导致电脑永远连不上，用户可手动强制中转绕开。
    const val KEY_LINK_MODE = "link_mode"
    const val LINK_MODE_AUTO = "auto"
    const val LINK_MODE_LAN = "lan"
    const val LINK_MODE_RELAY = "relay"

    // 历史电脑列表（JSON 数组，上限 10 条）
    const val KEY_DEVICES_JSON = "devices_json"

    // 上次链路方式（服务重启后恢复现场用：lan / relay / 空）
    const val KEY_LAST_LINK_MODE = "last_link_mode"

    // 本次打开App 的时刻（毫秒时间戳）。
    // 用途：界面显示「已连续运行 X 小时 Y 分 Z 秒」，用来确认保活是否真的持续。
    // 每次打开 App 重新写入，因此「上次 1 点打开、2 点半查看」会显示 1 小时 30 分。
    const val KEY_APP_OPEN_AT = "app_open_at"

    // 默认中转服务器地址（复用中心服务器的 15151 端口，不再另开端口）
    const val DEFAULT_RELAY_SERVER = "039039.xyz:15151"

    // 中转服务器域名 DNS 解析失败时的兜底 IP（服务器实际公网 IPv4）。
    // 实测蜂窝网络下切网后第一次连接偶发「Unable to resolve host 039039.xyz」，
    // 但重连又成功，说明是运营商 DNS 抖动而非域名失效。兜底 IP 保证链路稳定建立。
    const val RELAY_FALLBACK_IP = "122.6.190.161"

    // 中转的 WebSocket 路径（与 center-server 的 aurelay.relay_path 一致）
    const val RELAY_PATH = "/api/aurelay/relay"

    // 默认上报接口地址
    const val DEFAULT_REPORT_URL = "http://039039.xyz:15151/api/aurelay/report"

    // 默认上报令牌
    const val DEFAULT_REPORT_TOKEN = "qwe951753"

    /**
     * 读取布尔型设置项
     *
     * :param context: 任意上下文（内部会取 applicationContext）
     * :param key: 键名，取自本文件的 KEY_* 常量
     * :param default: 键不存在时的默认值
     * :return: 设置值；读取异常时返回 default
     */
    fun getBoolean(context: Context, key: String, default: Boolean): Boolean {
        return try {
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                .getBoolean(key, default)
        } catch (e: Exception) {
            default
        }
    }

    /**
     * 写入布尔型设置项
     *
     * :param context: 任意上下文
     * :param key: 键名
     * :param value: 待写入的值
     * :return: 无返回值
     */
    fun setBoolean(context: Context, key: String, value: Boolean) {
        try {
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                .edit().putBoolean(key, value).apply()
        } catch (e: Exception) {
            DiagLog.w("设置", "写入 $key 失败：${e.message}")
        }
    }

    /**
     * 读取字符串型设置项
     *
     * :param context: 任意上下文
     * :param key: 键名
     * :param default: 键不存在时的默认值
     * :return: 设置值；读取异常时返回 default
     */
    fun getString(context: Context, key: String, default: String): String {
        return try {
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                .getString(key, default) ?: default
        } catch (e: Exception) {
            default
        }
    }

    /**
     * 写入字符串型设置项
     *
     * :param context: 任意上下文
     * :param key: 键名
     * :param value: 待写入的值
     * :return: 无返回值
     */
    fun setString(context: Context, key: String, value: String) {
        try {
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                .edit().putString(key, value).apply()
        } catch (e: Exception) {
            DiagLog.w("设置", "写入 $key 失败：${e.message}")
        }
    }
}
