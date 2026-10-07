package com.devindeed.aurelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 保活兜底广播接收器
 *
 * 由 ResourceHolder 注册的 `AlarmManager` 周期唤醒（每 15 分钟一次）。
 * 被唤醒后检查接收服务是否存活，不存活则重新拉起，实现进程被杀后的自我恢复。
 * 注册后在「全程保活」开关打开期间不会取消，因此即使 App 进程不在也能被唤醒。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        // 保活兜底唤醒的动作名
        const val ACTION_KEEP_ALIVE = "com.devindeed.aurelay.KEEP_ALIVE"
    }

    /**
     * 接收保活兜底唤醒广播
     *
     * :param context: 广播上下文
     * :param intent: 广播意图
     * :return: 无返回值
     */
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        DiagLog.install(app)
        DiagLog.i("保活", "收到保活兜底唤醒")
        // 常驻保活服务无条件拉起：它只负责通知 + 保活 + 地址上报，
        // 与「自动启动服务」开关无关，进程被杀后也必须复活常驻通知。
        try {
            val keepAlive = Intent(app, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(keepAlive)
            } else {
                app.startService(keepAlive)
            }
            DiagLog.i("保活", "已尝试拉起常驻保活服务")
        } catch (e: Exception) {
            DiagLog.e("保活", "拉起常驻保活服务失败", e)
        }
        // 接收服务是否自启由「自动启动服务」开关决定（第二层，独立）。
        if (!AppPrefs.getBoolean(app, AppPrefs.KEY_AUTO_START, false)) {
            DiagLog.i("保活", "自动启动开关已关闭，不拉起接收服务（常驻通知与保活不受影响）")
            return
        }
        try {
            val service = Intent(app, AudioRelayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(service)
            } else {
                app.startService(service)
            }
            DiagLog.i("保活", "已尝试拉起接收服务")
        } catch (e: Exception) {
            DiagLog.e("保活", "拉起接收服务失败", e)
        }
    }
}
