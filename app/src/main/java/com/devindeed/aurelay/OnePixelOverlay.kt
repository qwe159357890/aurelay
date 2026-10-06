package com.devindeed.aurelay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 一像素前台锚点（8.3.2 保活第 11 层）
 *
 * 原理：进程持有「可见窗口」时会被系统判为 VISIBLE / FOREGROUND（oom_adj 0~1），
 * 而退到后台只是 CACHED（oom_adj 6+）。在内存回收与国产 ROM 锁屏后的一键清理中，
 * 前者的存活率明显更高。这里常驻一个 1×1 像素、alpha 0.01、不可交互的悬浮窗，
 * 用户肉眼不可见，但 WindowManager 认为本进程在「画图」。
 *
 * ⚠️ 关键参数不能改：
 * - alpha 绝不能设 0（全透明会被系统判定为不可见而优化掉，锚点直接失效）；
 * - 不要跟随 SCREEN_ON / SCREEN_OFF 反复增删，会被 ROM 判为异常行为。
 *
 * 前置条件：`SYSTEM_ALERT_WINDOW` 权限（已在 8.2.3 C 组申请）。
 */
object OnePixelOverlay {

    // 窗口管理器
    private var windowManager: WindowManager? = null

    // 已添加的一像素视图
    private var overlayView: View? = null

    // 主线程调度器（WindowManager 操作统一切到主线程执行）
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 判断是否具备悬浮窗权限
     *
     * :param context: 任意上下文
     * :return: true 表示已授权（Android 6 以下恒为 true）
     */
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context.applicationContext)
        } else {
            true
        }
    }

    /**
     * 判断是否已经显示了一像素窗口
     *
     * :return: true 表示已显示
     */
    fun isShowing(): Boolean = overlayView != null

    /**
     * 显示一像素锚点（幂等，已显示时直接返回）
     *
     * 无悬浮窗权限时不显示，只记一条警告日志（由设置页把开关置灰）。
     *
     * :param context: 任意上下文
     * :return: 无返回值
     */
    fun show(context: Context) {
        if (overlayView != null) return
        if (!hasPermission(context)) {
            DiagLog.w("保活", "一像素锚点：无悬浮窗权限")
            return
        }
        val app = context.applicationContext
        mainHandler.post {
            try {
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                val view = View(app)
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    1,
                    1,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.START
                params.x = 0
                params.y = 0
                params.alpha = 0.01f
                wm.addView(view, params)
                windowManager = wm
                overlayView = view
                DiagLog.i("保活", "一像素锚点 显示")
            } catch (e: Exception) {
                DiagLog.e("保活", "一像素锚点 显示失败", e)
            }
        }
    }

    /**
     * 移除一像素锚点（幂等）
     *
     * :param context: 任意上下文
     * :return: 无返回值
     */
    fun hide(context: Context) {
        mainHandler.post {
            try {
                val view = overlayView ?: return@post
                windowManager?.removeView(view)
                overlayView = null
                windowManager = null
                DiagLog.i("保活", "一像素锚点 移除")
            } catch (e: Exception) {
                DiagLog.e("保活", "一像素锚点 移除失败", e)
            }
        }
    }
}
