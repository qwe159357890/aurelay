package com.devindeed.aurelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机 / 应用更新完成广播接收器
 *
 * 两层独立：
 * 1. 常驻保活服务（KeepAliveService）无条件拉起——只负责通知 + 保活 + 地址上报；
 * 2. 接收服务（AudioRelayService）由「自动启动服务」开关决定是否拉起。
 *
 * ⚠️ Android 15+ 限制 BOOT_COMPLETED 接收器启动 `mediaPlayback` 等类型的前台服务，
 * 会抛 `ForegroundServiceStartNotAllowedException`；此处用 try-catch 兜住并记日志，
 * 属预期行为（用户手动打开 App 仍然可用）。
 */
class BootReceiver : BroadcastReceiver() {

    /**
     * 接收开机与应用更新广播
     *
     * :param context: 广播上下文
     * :param intent: 广播意图
     * :return: 无返回值
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        DiagLog.install(context)
        DiagLog.i("开机", "收到广播：$action")
        val app = context.applicationContext

        // 第一层（无条件）：开机自启硬编码拉起常驻保活服务。
        // KeepAliveService 只负责通知 + 保活资源 + 地址上报，不碰音频，
        // 因此与「自动启动服务」开关无关，任何时候开机/更新都会拉起。
        try {
            val keepAlive = Intent(app, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(keepAlive)
            } else {
                app.startService(keepAlive)
            }
            DiagLog.i("开机", "已拉起常驻保活服务")
        } catch (e: Exception) {
            DiagLog.e("开机", "拉起保活服务失败（Android 15+ 开机限制属预期）", e)
        }

        // 第二层（独立）：是否接收音频由「自动启动服务」开关决定。
        if (!AppPrefs.getBoolean(context, AppPrefs.KEY_AUTO_START, false)) {
            DiagLog.i("开机", "自动启动服务开关未打开，不拉起接收服务")
            return
        }

        // 延迟 10 秒，等系统网络就绪后再拉起接收服务
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val service = Intent(app, AudioRelayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(service)
                } else {
                    app.startService(service)
                }
                DiagLog.i("开机", "已尝试拉起接收服务")
            } catch (e: Exception) {
                DiagLog.e("开机", "拉起接收服务失败（Android 15+ 开机限制属预期）", e)
            }
        }, 10_000L)
    }
}
