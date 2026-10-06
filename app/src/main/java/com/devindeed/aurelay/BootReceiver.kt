package com.devindeed.aurelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机 / 应用更新完成广播接收器
 *
 * 「自动启动服务」开关打开时，开机后延迟 10 秒拉起接收服务，
 * 让手机在未打开 App 的情况下也能被电脑端连接。
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
        if (!AppPrefs.getBoolean(context, AppPrefs.KEY_AUTO_START, false)) {
            DiagLog.i("开机", "自动启动服务开关未打开，忽略本次广播")
            return
        }
        val app = context.applicationContext
        // 延迟 10 秒，等系统网络就绪后再拉起服务
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
