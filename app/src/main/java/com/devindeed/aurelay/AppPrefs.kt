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

    // 历史电脑列表（JSON 数组，上限 10 条）
    const val KEY_DEVICES_JSON = "devices_json"

    // 上次链路方式（服务重启后恢复现场用：lan / relay / 空）
    const val KEY_LAST_LINK_MODE = "last_link_mode"

    // 默认中转服务器地址（复用中心服务器的 15151 端口，不再另开端口）
    const val DEFAULT_RELAY_SERVER = "039039.xyz:15151"

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
