package com.devindeed.aurelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 常驻保活前台服务（无条件启动）
 *
 * ## 为什么要有这个独立服务
 *
 * 此前「保活 + 音频接收」被耦合在同一个 [AudioRelayService] 里：
 * 打开 App 就 `startForegroundService(AudioRelayService)`，而它的 `onCreate`
 * 里会 `startForeground(...)` + `applyKeepAlive()` + `startByNetwork()`。
 * 于是「撑起常驻通知」和「开始接收电脑声音」被绑成了一件事——
 * 用户点「停止」后常驻通知消失，进程随即被系统回收、地址上报中断。
 *
 * 现在拆开：
 * - **[KeepAliveService]**（本类）：无条件启动，**只**负责前台通知与保活资源，
 *   不监听端口、不连中转、不碰音频。用户永远不该手动关它。
 * - **[AudioRelayService]**：退化为纯接收服务，只在用户点「开始」时启动。
 *
 * 因此「打开 App 不自动启动接收服务」与「常驻通知永远存在」两件事不再冲突：
 * 前者由 AudioRelayService 不再无条件启动保证，后者由本服务保证。
 *
 * ## 与 AudioRelayService 的通知分工
 *
 * | 通知 ID | 归属服务 | 何时出现 |
 * |---|---|---|
 * | [NOTIFICATION_ID] | 本服务 | App 运行期间**永远**存在，不可划走 |
 * | 1003 | AudioRelayService | 真的有电脑连入播放时才出现，结束即撤销 |
 * | 1002 | AudioCaptureService | 电脑放音推送时才出现，断开即撤销 |
 */
class KeepAliveService : Service() {

    companion object {
        // 常驻通知 ID。与 AudioRelayService 的会话通知（1003）分开，互不干扰
        const val NOTIFICATION_ID = 2001

        // 通知渠道 ID（与 AudioRelayService 的分开，便于用户分别管理）
        const val CHANNEL_KEEP_ALIVE = "keepAliveChannel"

        // 刷新前台通知的指令（设置页保存后发一条，让常驻通知立即反映最新开关状态）
        const val ACTION_REFRESH = "com.devindeed.aurelay.action.KEEP_ALIVE_REFRESH"

        /** 服务启动时刻，供界面显示「已连续运行 X」 */
        @Volatile
        var startedAt: Long = 0L
            private set

        /**
         * 取本次启动时刻（毫秒时间戳）
         *
         * :return: 启动时刻；服务未启动时返回 0
         */
        fun startedAtMillis(): Long = startedAt
    }

    // 服务启动时刻：通知显示「已运行 X 分 X 秒」
    private var serviceStartAt: Long = 0L

    private lateinit var notificationManager: android.app.NotificationManager

    /**
     * 服务创建：建立通知渠道、挂上前台通知、持有保活资源
     *
     * :return: 无返回值
     */
    override fun onCreate() {
        super.onCreate()
        serviceStartAt = System.currentTimeMillis()
        startedAt = serviceStartAt
        DiagLog.install(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        // 前台通知：这是「常驻通知永远存在」的实现点——本服务无条件启动，
        // 因此用户不点任何按钮，通知栏也会有这一条。
        promoteToForeground()
        // 记下本次打开 App 的时刻，界面据此显示「已连续运行」时长
        AppPrefs.setString(this, AppPrefs.KEY_APP_OPEN_AT, serviceStartAt.toString())
        // 保活资源：WakeLock / AlarmManager 兜底 / JobScheduler 兜底 / 网络回调 /
        // 位置 / 传感器 / 蓝牙 / 相机等（详见 ResourceHolder）。
        // 注意：这与音频接收**完全无关**，即便用户点「停止」，这里依然持有。
        applyKeepAlive()
        // 地址上报同样归本服务：它必须与接收服务解耦，
        // 否则用户一按「停止」，PC 端就再也查不到这台设备（这是历史踩过的坑）。
        AddressReporter.start(this)
        DiagLog.i("保活", "常驻保活服务已启动：通知与保活资源已就绪（不涉及音频接收）")
    }

    /**
     * 服务启动命令：只处理「刷新通知」，其余一律忽略
     *
     * @param intent: 启动意图
     * @param flags: 系统标志
     * @param startId: 启动编号
     * @return: int START_STICKY 让系统在进程被杀后重建本服务，保活不失
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagLog.install(this)
        if (intent?.action == ACTION_REFRESH) {
            // 设置页保存后：刷新通知 + 按新开关重新应用保活资源 + 按新配置重启地址上报。
            // 统一由本服务操作，避免 MainActivity 直接操作资源产生双重持有/释放竞态。
            promoteToForeground()
            applyKeepAlive()
            AddressReporter.stop(this)
            if (AddressReporter.isEnabled(this)) {
                AddressReporter.start(this)
            }
        }
        // START_STICKY：进程被系统回收后自动重建，常驻通知随之恢复
        return START_STICKY
    }

    /**
     * 服务销毁：释放保活资源
     *
     * @return: 无返回值
     */
    override fun onDestroy() {
        super.onDestroy()
        try {
            ResourceHolder.releaseAll(this)
        } catch (e: Exception) {
            DiagLog.w("保活", "释放保活资源失败：${e.message}")
        }
        try {
            OnePixelOverlay.hide(this)
        } catch (e: Exception) {
            DiagLog.w("保活", "隐藏一像素锚点失败：${e.message}")
        }
        try {
            SilentPlayer.setScenarioAllowed(this, false)
        } catch (e: Exception) {
            DiagLog.w("保活", "停止无声播放失败：${e.message}")
        }
        try {
            AddressReporter.stop(this)
        } catch (e: Exception) {
            DiagLog.w("保活", "停止地址上报失败：${e.message}")
        }
        DiagLog.i("保活", "常驻保活服务已销毁")
    }

    /**
     * 绑定入口：本服务不提供绑定
     *
     * @param intent: 绑定意图
     * @return: IBinder? 固定返回 null
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 按「全程保活」开关持有或释放受控资源，并同步两个独立锚点开关
     *
     * 与 AudioRelayService.applyKeepAlive() 的逻辑一致，但作用在本服务上。
     *
     * @return: 无返回值
     */
    private fun applyKeepAlive() {
        if (AppPrefs.getBoolean(this, AppPrefs.KEY_KEEP_ALIVE_ALWAYS, true)) {
            ResourceHolder.acquireAll(this)
        } else {
            ResourceHolder.releaseAll(this)
        }
        // 一像素锚点（独立开关，默认关闭）。
        // ⚠️ 默认开启会让系统每次都弹出「"Aurelay声音中继"正在其他应用的上层运行 /
        //   显示内容…」提示并引导用户去设置里关闭——用户实测非常困扰。
        //   原因是它用 TYPE_APPLICATION_OVERLAY 悬浮窗实现，属于「在其他应用上层显示」。
        if (AppPrefs.getBoolean(this, AppPrefs.KEY_KEEP_ALIVE_ONE_PIXEL, false)) {
            OnePixelOverlay.show(this)
        } else {
            OnePixelOverlay.hide(this)
        }
        // 无声播放锚点（独立开关）
        SilentPlayer.applyEnabled(this)
    }

    /**
     * 挂上前台通知并保持常驻
     *
     * 走 [ServiceCompat.startForeground] 而非 `notify()`：后者刷出的是**普通通知**，
     * 服务一旦被系统降级到后台就不再受保护，用户可以随手划掉。
     *
     * @return: 无返回值
     */
    private fun promoteToForeground() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 与 AudioRelayService 的通知刷新保持同一前台类型（mediaPlayback），
                // 避免 SPECIAL_USE 在 Android 14 以下设备上的类型兼容问题。
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            DiagLog.i("保活", "常驻通知已挂载（前台服务身份，不可划走）")
        } catch (e: Exception) {
            Log.w("KeepAlive", "挂前台通知失败：${e.message}")
            DiagLog.e("保活", "挂前台通知失败：${e.javaClass.simpleName}：${e.message}")
        }
    }

    /**
     * 构建常驻通知（两行式：标题=状态 / 正文=极短动作提示）
     *
     * ⚠️ 不要挂 `NotificationCompat.MediaStyle()`：它会把通知折叠成媒体播放器样式，
     *   既把两行文案压成一行小字，又在部分国产 ROM 上不遵守 ongoing（能被划走）。
     *
     * @return: 通知对象
     */
    private fun buildNotification(): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(this, 0, openApp, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_KEEP_ALIVE)
            .setContentTitle("Aurelay 声音中继")
            .setContentText("声音中继已开启 · 点此管理")
            .setSmallIcon(com.devindeed.aurelay.R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // 常驻三件套：ongoing + 不重复提醒 + 不显示时间列
            // （时间列会把两行式挤歪；运行时长由界面「已连续运行 X」承担）
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * 创建常驻通知渠道
     *
     * @return: 无返回值
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_KEEP_ALIVE,
                "常驻保活",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "App 运行期间保持后台常驻"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
