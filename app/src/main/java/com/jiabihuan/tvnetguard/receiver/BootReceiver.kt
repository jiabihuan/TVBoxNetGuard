package com.jiabihuan.tvnetguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.jiabihuan.tvnetguard.util.Prefs
import com.jiabihuan.tvnetguard.vpn.GuardVpnService

/** 开机 / 应用升级后自动接管流量 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        Prefs.init(context)
        com.jiabihuan.tvnetguard.data.RuleStore.init(context)

        if (Prefs.autoStart && VpnService.prepare(context) == null) {
            try {
                GuardVpnService.start(context)
            } catch (t: Throwable) {
                // 后台启动前台服务在 Android 12+ 可能被拒，等看门狗兜底
            }
        }
        WatchdogReceiver.schedule(context)
    }
}
