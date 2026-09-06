package com.jiabihuan.tvnetguard.ui

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.jiabihuan.tvnetguard.App
import com.jiabihuan.tvnetguard.R
import com.jiabihuan.tvnetguard.data.AppLoader
import com.jiabihuan.tvnetguard.data.StatsStore
import com.jiabihuan.tvnetguard.util.Format
import com.jiabihuan.tvnetguard.util.Prefs

/**
 * 桌面悬浮窗：常驻显示实时上行 / 下行速率。
 * 默认显示整机总速率；在应用限速里把某个 App「锁定到浮窗」后，只显示该 App 的速率。
 * 用 TYPE_APPLICATION_OVERLAY，且 NOT_FOCUSABLE + NOT_TOUCHABLE，不挡桌面操作。
 */
class FloatWindowService : Service() {

    companion object {
        const val ACTION_STOP = "com.jiabihuan.tvnetguard.float.STOP"

        fun canShow(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun start(context: Context) {
            if (!canShow(context)) {
                // TV 上引导到设置页可能被卡住，至少别崩；提示交给 UI
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (t: Throwable) { /* 进不去就放弃 */ }
                return
            }
            val i = Intent(context, FloatWindowService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            } catch (t: Throwable) { /* 系统限制时静默 */ }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, FloatWindowService::class.java).setAction(ACTION_STOP)) }
        }
    }

    private var wm: WindowManager? = null
    private var view: TextView? = null
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
        if (!canShow(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(App.Guard.NOTIFY_ID + 1, buildNotification())
        createWindow()
        handler.post(tick)
        return START_STICKY
    }

    private fun createWindow() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        view = TextView(this).apply {
            text = "加载中…"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(18, 10, 18, 10)
            background = resources.getDrawable(R.drawable.bg_item, theme)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 40
        params.y = 40
        try {
            wm?.addView(view, params)
        } catch (t: Throwable) {
            stopSelf()
        }
    }

    private var lastCorner = -1

    private fun update() {
        val v = view ?: return
        applyCorner()
        StatsStore.tick()
        val lock = Prefs.floatLockUid
        v.text = if (lock >= 0) {
            val name = AppLoader.nameOf(this, lock) ?: "uid $lock"
            "↑ ${Format.speed(StatsStore.txRateOf(lock))}\n↓ ${Format.speed(StatsStore.rxRateOf(lock))}\n$name"
        } else {
            "↑ ${Format.speed(StatsStore.globalTxRate)}\n↓ ${Format.speed(StatsStore.globalRxRate)}\n整机"
        }
    }

    /** 应用位置设置：0=左上 1=右上 2=左下 3=右下。改设置后 1 秒内生效 */
    private fun applyCorner() {
        val v = view ?: return
        val manager = wm ?: return
        val corner = Prefs.floatCorner.coerceIn(0, 3)
        if (corner == lastCorner) return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.gravity = when (corner) {
            1 -> Gravity.TOP or Gravity.END
            2 -> Gravity.BOTTOM or Gravity.START
            3 -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.TOP or Gravity.START
        }
        lp.x = 40
        lp.y = 40
        try {
            manager.updateViewLayout(v, lp)
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
        wm = null
        super.onDestroy()
    }
}
