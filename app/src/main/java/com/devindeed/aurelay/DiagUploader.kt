package com.devindeed.aurelay

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 诊断日志上传器
 *
 * 手机端把诊断日志 POST 到用户自己的中心服务器（与地址上报同一个服务），
 * 这样用户只要在手机上点一下「上传日志」，我们就能在服务器侧直接取到日志，
 * 不必依赖微信/邮件等外部通道来回传文件。
 *
 * 上传地址默认由「上报接口地址」推导：
 * - `http://host:8001/api/aurelay/report` → `http://host:8001/api/aurelay/log`
 * - 其它形式则取协议+主机+端口，再拼 `/api/aurelay/log`
 * 如需单独指定，可在设置页的「日志上传地址」里手工填写。
 *
 * 复用与地址上报完全相同的鉴权方式（X-API-Key 与 Authorization 双写）与
 * 「HTTP 状态恒为 200、业务结果看 body 的 code」响应约定。
 */
object DiagUploader {

    // 日志标签
    private const val TAG = "AurelayDiag"

    // 网络请求超时（毫秒）：上传体比地址上报大，读超时给得宽一些
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    // 单次上传的日志最大字符数（服务端也会二次限制）
    private const val MAX_UPLOAD_CHARS = 200_000

    /**
     * 由「上报接口地址」推导出日志上传地址
     *
     * :param reportUrl: 设置页填写的上报接口地址
     * :return: 推导出的日志上传地址；无法推导时返回空串
     */
    fun deriveLogUrl(reportUrl: String): String {
        val url = reportUrl.trim()
        if (url.isEmpty()) return ""
        val marker = url.indexOf("/api/aurelay/")
        if (marker >= 0) {
            return url.substring(0, marker) + "/api/aurelay/log"
        }
        val scheme = url.indexOf("://")
        if (scheme < 0) return ""
        val pathStart = url.indexOf('/', scheme + 3)
        val origin = if (pathStart < 0) url else url.substring(0, pathStart)
        return "$origin/api/aurelay/log"
    }

    /**
     * 取实际使用的日志上传地址（由「上报接口地址」推导）
     *
     * :param context: 任意上下文
     * :return: 日志上传地址；未配置上报接口地址时返回空串
     */
    fun resolveUploadUrl(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val reportUrl = prefs.getString(AddressReporter.KEY_URL, "")?.trim() ?: ""
        return deriveLogUrl(reportUrl)
    }

    /**
     * 上传日志文本到中心服务器（阻塞调用，请在后台线程执行）
     *
     * :param context: 任意上下文
     * :param content: 日志全文本
     * :return: 单行中文结果摘要（用于直接显示在界面上）
     */
    fun upload(context: Context, content: String): String {
        val app = context.applicationContext
        val url = resolveUploadUrl(app)
        if (url.isEmpty()) {
            return "失败：未配置日志上传地址，请先填写「上报接口地址」"
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val token = prefs.getString(AddressReporter.KEY_TOKEN, "")?.trim() ?: ""
        val deviceId = AddressReporter.getOrCreateDeviceId(app)
        val deviceName = Build.MODEL.ifEmpty { "Android 设备" }
        val text = if (content.length > MAX_UPLOAD_CHARS) {
            content.substring(content.length - MAX_UPLOAD_CHARS)
        } else {
            content
        }

        val payload = buildString {
            append("{")
            append("\"device_id\":\"").append(escapeJson(deviceId)).append("\",")
            append("\"device_name\":\"").append(escapeJson(deviceName)).append("\",")
            append("\"app_version\":\"").append(escapeJson(DiagLog.appVersion(app))).append("\",")
            append("\"content\":\"").append(escapeJson(text)).append("\"")
            append("}")
        }

        Log.i(TAG, "[上传] 开始上传日志到 $url，共 ${text.length} 字")
        var connection: HttpURLConnection? = null
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotEmpty()) {
                conn.setRequestProperty("X-API-Key", token)
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            val httpCode = conn.responseCode
            val bodyText = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                ""
            }
            // 中心服务器统一响应体：HTTP 状态恒为 200，业务结果在 body 的 code 字段
            val bizCode = AddressReporter.extractJsonInt(bodyText, "code")
            if (httpCode in 200..299 && (bizCode == null || bizCode == 0)) {
                val saved = AddressReporter.extractJsonString(bodyText, "message") ?: "上传成功"
                Log.i(TAG, "[上传] 日志上传成功 (HTTP $httpCode) $bodyText")
                return "上传成功：$saved"
            }
            val reason = AddressReporter.extractJsonString(bodyText, "message") ?: "HTTP $httpCode"
            Log.w(TAG, "[上传] 日志被服务端拒绝：$reason（HTTP $httpCode，body=$bodyText）")
            return "失败：$reason"
        } catch (e: Exception) {
            val detail = e.message?.trim()?.take(60) ?: e.javaClass.simpleName
            Log.w(TAG, "[上传] 日志上传异常：${e.javaClass.simpleName} - ${e.message}", e)
            return "异常：$detail"
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 转义 JSON 字符串中的反斜杠与双引号
     *
     * 日志里含大量换行与引号，必须转义；换行用 \n 转义后放入单行 JSON。
     *
     * :param raw: 原始字符串
     * :return: 转义后的字符串
     */
    private fun escapeJson(raw: String): String {
        val builder = StringBuilder(raw.length + 64)
        for (ch in raw) {
            when (ch) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch.code < 0x20) builder.append(' ') else builder.append(ch)
            }
        }
        return builder.toString()
    }
}
