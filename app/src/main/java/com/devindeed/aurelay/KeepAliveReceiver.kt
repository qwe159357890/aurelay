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
        if (!AppPrefs.getBoolean(app, AppPrefs.KEY_KEEP_ALIVE_ALWAYS, true)) {
            DiagLog.i("保活", "全程保活开关已关闭，不拉起服务")
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
