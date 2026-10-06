package com.devindeed.aurelay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 历史电脑列表存储（电脑放音模式下手动填写的目标电脑）
 *
 * 去掉 UDP 广播发现后，电脑放音只能手动填写目标电脑的 IPv4 与端口。
 * 这里把填过的电脑持久化下来，最多 10 条，支持重命名、删除、重新排序无关。
 *
 * 存储格式（`devices_json`，JSON 数组）：
 * [{"name":"PC-Audio","ip":"192.168.31.10","port":5000}]
 */
object DeviceStore {

    // 最大保存条数
    const val MAX_COUNT = 10

    // 默认端口
    const val DEFAULT_PORT = 5000

    // 默认设备名
    const val DEFAULT_NAME = "Aurelay-Desktop"

    /**
     * 一条历史电脑记录
     */
    data class Device(
        // 设备昵称（用户可改）
        val name: String,
        // IPv4 地址
        val ip: String,
        // 端口
        val port: Int
    )

    /**
     * 读取全部历史电脑
     *
     * :param context: 任意上下文
     * :return: 电脑列表（按保存顺序）；解析失败时返回空列表
     */
    fun list(context: Context): List<Device> {
        val raw = AppPrefs.getString(context, AppPrefs.KEY_DEVICES_JSON, "[]")
        val result = ArrayList<Device>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                result.add(
                    Device(
                        name = item.optString("name", DEFAULT_NAME),
                        ip = item.optString("ip", ""),
                        port = item.optInt("port", DEFAULT_PORT)
                    )
                )
            }
        } catch (e: Exception) {
            DiagLog.e("设备", "解析历史电脑列表失败", e)
        }
        return result
    }

    /**
     * 保存全部历史电脑
     *
     * :param context: 任意上下文
     * :param devices: 待保存的列表（超过上限时会被截断）
     * :return: 无返回值
     */
    fun save(context: Context, devices: List<Device>) {
        try {
            val array = JSONArray()
            devices.take(MAX_COUNT).forEach {
                val item = JSONObject()
                item.put("name", it.name)
                item.put("ip", it.ip)
                item.put("port", it.port)
                array.put(item)
            }
            AppPrefs.setString(context, AppPrefs.KEY_DEVICES_JSON, array.toString())
        } catch (e: Exception) {
            DiagLog.e("设备", "保存历史电脑列表失败", e)
        }
    }

    /**
     * 新增一条电脑记录（点「保存」即刻入列表，上限 10 条）
     *
     * :param context: 任意上下文
     * :param device: 待新增的电脑
     * :return: true 表示新增成功；false 表示已达上限
     */
    fun add(context: Context, device: Device): Boolean {
        val current = ArrayList(list(context))
        if (current.size >= MAX_COUNT) return false
        current.add(device)
        save(context, current)
        DiagLog.i("设备", "新增电脑：${device.name} ${device.ip}:${device.port}")
        return true
    }

    /**
     * 修改指定位置的电脑记录
     *
     * :param context: 任意上下文
     * :param index: 列表下标
     * :param device: 新的内容
     * :return: true 表示修改成功；false 表示下标越界
     */
    fun update(context: Context, index: Int, device: Device): Boolean {
        val current = ArrayList(list(context))
        if (index < 0 || index >= current.size) return false
        current[index] = device
        save(context, current)
        DiagLog.i("设备", "修改电脑：${device.name} ${device.ip}:${device.port}")
        return true
    }

    /**
     * 删除指定位置的电脑记录
     *
     * :param context: 任意上下文
     * :param index: 列表下标
     * :return: true 表示删除成功；false 表示下标越界
     */
    fun remove(context: Context, index: Int): Boolean {
        val current = ArrayList(list(context))
        if (index < 0 || index >= current.size) return false
        val removed = current.removeAt(index)
        save(context, current)
        DiagLog.i("设备", "删除电脑：${removed.name}")
        return true
    }

    /**
     * 校验 IPv4 地址格式是否合法
     *
     * :param ip: 待校验的地址
     * :return: true 表示是合法 IPv4
     */
    fun isValidIpv4(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return false
            for (ch in part) {
                if (ch !in '0'..'9') return false
            }
            val value = part.toIntOrNull() ?: return false
            if (value > 255) return false
        }
        return true
    }
}
