package com.jiabihuan.tvnetguard.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import com.jiabihuan.tvnetguard.EngineState
import com.jiabihuan.tvnetguard.util.Prefs
import com.jiabihuan.tvnetguard.util.RootShell
import com.jiabihuan.tvnetguard.vpn.GuardVpnService
import com.jiabihuan.tvnetguard.vpn.RootEngineService
import com.jiabihuan.tvnetguard.vpn.VpnLog

/**
 * 看门狗：引擎被系统或清理软件杀掉后，60 秒内重新拉起。
 * 注意 Android 12+ 对后台启动前台服务有限制，这里做了兜底 try-catch，
 * 拉不起来也不会崩溃；配合 stopWithTask=false 与 START_STICKY 双保险。
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        if (Prefs.autoStart && Prefs.watchdog && !EngineState.running) {
            if (VpnService.prepare(context) == null) {
                try {
                    if (Prefs.mode == MODE_ROOT && RootShell.hasRoot()) {
                        RootEngineService.start(context)
                    } else {
                        GuardVpnService.start(context)
                    }
                    VpnLog.d("watchdog restarted engine")
                } catch (t: Throwable) {
                    VpnLog.w("watchdog start failed: ${t.message}")
                }
            }
        }
        schedule(context)
    }

    companion object {
        private const val REQ = 8801
        private const val MODE_ROOT = 1

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = pending(context)
            if (!Prefs.watchdog || !Prefs.autoStart) {
                am.cancel(pi)
                return
            }
            val trigger = SystemClock.elapsedRealtime() + 60_000
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                }
            } catch (t: Throwable) {
                VpnLog.w("watchdog schedule failed: ${t.message}")
            }
        }

        private fun pending(context: Context): PendingIntent {
            val i = Intent(context, WatchdogReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(context, REQ, i, flags)
        }
    }
}
