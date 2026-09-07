package com.jiabihuan.tvnetguard.ui

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.jiabihuan.tvnetguard.App
import com.jiabihuan.tvnetguard.EngineState
import com.jiabihuan.tvnetguard.R
import com.jiabihuan.tvnetguard.data.AppLoader
import com.jiabihuan.tvnetguard.data.StatsStore
import com.jiabihuan.tvnetguard.util.Format
import com.jiabihuan.tvnetguard.util.Prefs

/**
 * 桌面悬浮窗：常驻显示实时上行 / 下行速率，单行横排、全透明背景。
 *
 * 兼容性说明（部分盒子固件"打开悬浮窗却不显示"的修复）：
 * 1. 很多电视固件的 [Settings.canDrawOverlays] 检查不可信（明明有权限却返回 false），
 *    因此不再把它当硬门槛——直接尝试挂窗，挂不上再说；
 * 2. 个别固件对 TYPE_APPLICATION_OVERLAY 的窗口 token 校验异常，做了窗口类型
 *    降级链（APPLICATION_OVERLAY -> PHONE -> SYSTEM_ALERT -> SYSTEM_OVERLAY），
 *    哪个能挂就用哪个；
 * 3. 全部失败时通过 [EngineState.note] 在主界面给出原因提示，不再静默退出。
 */
class FloatWindowService : Service() {

    companion object {
        const val ACTION_STOP = "com.jiabihuan.tvnetguard.float.STOP"

        /** 仅用于提示引导；部分固件该检查不可信，不作为硬门槛 */
        fun canShow(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val i = Intent(context, FloatWindowService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            } catch (t: Throwable) { /* 系统限制时静默 */ }
        }

        /** 系统设置里的悬浮窗权限页（部分固件路径不可用，失败即放弃） */
        fun openOverlaySettings(context: Context) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) { /* TV 上进不去就放弃 */ }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, FloatWindowService::class.java).setAction(ACTION_STOP)) }
        }
    }

    private var wm: WindowManager? = null
    private var view: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(App.Guard.NOTIFY_ID + 1, buildNotification())
        createWindow()
        handler.post(tick)
        return START_STICKY
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun createWindow() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        view = TextView(this).apply {
            text = "加载中…"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
            // 全透明：不画底色，白字 + 加强阴影描边，任何桌面上都看得清
            background = null
            setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
            includeFontPadding = false
            setPadding(dp(9), dp(5), dp(9), dp(5))
        }

        // 窗口类型降级链：哪个能挂上用哪个
        val types = ArrayList<Int>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            types += WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            types += WindowManager.LayoutParams.TYPE_PHONE
        }
        @Suppress("DEPRECATION")
        types += WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        @Suppress("DEPRECATION")
        types += WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY

        var added = false
        var lastErr: Throwable? = null
        for (type in types) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = dp(24)
            params.y = dp(24)
            try {
                wm?.addView(view, params)
                lp = params
                added = true
                break
            } catch (t: Throwable) {
                lastErr = t
            }
        }

        if (!added) {
            EngineState.note(
                "悬浮窗无法在此固件显示（${lastErr?.message ?: "未知原因"}）。" +
                    "可尝试到系统设置里给本应用开启「显示悬浮窗」权限后重启引擎"
            )
            stopSelf()
        }
    }

    private var lastCorner = -1

    private fun update() {
        val v = view ?: return
        applyCorner()
        StatsStore.tick()
        val lock = Prefs.floatLockUid
        // 单行横排：上行 下行 来源（整机或锁定的 App）
        v.text = if (lock >= 0) {
            val name = AppLoader.nameOf(this, lock) ?: "uid $lock"
            "↑ ${Format.speed(StatsStore.txRateOf(lock))}  ↓ ${Format.speed(StatsStore.rxRateOf(lock))}  $name"
        } else {
            "↑ ${Format.speed(StatsStore.globalTxRate)}  ↓ ${Format.speed(StatsStore.globalRxRate)}  整机"
        }
    }

    /** 应用位置设置：0=左上 1=右上 2=左下 3=右下。改设置后 1 秒内生效 */
    private fun applyCorner() {
        val v = view ?: return
        val manager = wm ?: return
        val params = lp ?: return
        val corner = Prefs.floatCorner.coerceIn(0, 3)
        if (corner == lastCorner) return
        params.gravity = when (corner) {
            1 -> Gravity.TOP or Gravity.END
            2 -> Gravity.BOTTOM or Gravity.START
            3 -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.TOP or Gravity.START
        }
        params.x = dp(24)
        params.y = dp(24)
        try {
            manager.updateViewLayout(v, params)
            lastCorner = corner
        } catch (t: Throwable) {
            // 视图还没挂上或窗口已关，下一轮再试
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.Guard.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle("星河守卫悬浮窗")
            .setContentText("实时速率常驻显示中")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try {
            wm?.removeView(view)
        } catch (t: Throwable) { /* ignore */ }
        view = null
        lp = null
        wm = null
        super.onDestroy()
    }
}
