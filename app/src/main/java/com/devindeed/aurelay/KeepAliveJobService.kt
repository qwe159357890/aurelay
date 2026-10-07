package com.devindeed.aurelay

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build

/**
 * 保活兜底周期任务（JobScheduler）
 *
 * `setPersisted(true)` 使其在系统重启后依然有效。
 * 任务被调度执行时进程必然处于运行状态，这正是它在保活上的价值；
 * 执行体内再顺带检查一次接收服务是否存活。
 */
class KeepAliveJobService : JobService() {

    /**
     * 周期任务开始执行
     *
     * :param params: 任务参数
     * :return: false 表示任务已同步完成，不需要保持唤醒锁
     */
    override fun onStartJob(params: JobParameters?): Boolean {
        DiagLog.install(this)
        DiagLog.i("保活", "JobScheduler 兜底任务触发")
        // 常驻保活服务无条件拉起：只负责通知 + 保活 + 地址上报，与开关无关。
        try {
            val keepAlive = Intent(this, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(keepAlive)
            } else {
                startService(keepAlive)
            }
            DiagLog.i("保活", "已尝试拉起常驻保活服务")
        } catch (e: Exception) {
            DiagLog.e("保活", "JobScheduler 拉起保活服务失败", e)
        }
        // 接收服务是否自启由「自动启动服务」开关决定（第二层，独立）。
        if (AppPrefs.getBoolean(this, AppPrefs.KEY_AUTO_START, false)) {
            try {
                val service = Intent(this, AudioRelayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(service)
                } else {
                    startService(service)
                }
                DiagLog.i("保活", "已尝试拉起接收服务")
            } catch (e: Exception) {
                DiagLog.e("保活", "JobScheduler 拉起接收服务失败", e)
            }
        } else {
            DiagLog.i("保活", "自动启动开关已关闭，不拉起接收服务")
        }
        return false
    }

    /**
     * 周期任务被系统中止
     *
     * :param params: 任务参数
     * :return: false 表示不需要重新调度（系统会按周期再次调度）
     */
    override fun onStopJob(params: JobParameters?): Boolean = false
}
