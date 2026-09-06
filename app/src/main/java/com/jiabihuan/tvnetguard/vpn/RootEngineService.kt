package com.jiabihuan.tvnetguard.vpn

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.jiabihuan.tvnetguard.App
import com.jiabihuan.tvnetguard.EngineState
import com.jiabihuan.tvnetguard.R
import com.jiabihuan.tvnetguard.data.RuleStore
import com.jiabihuan.tvnetguard.data.StatsStore
import com.jiabihuan.tvnetguard.ui.MainActivity
import com.jiabihuan.tvnetguard.util.Format
import com.jiabihuan.tvnetguard.util.Prefs
import com.jiabihuan.tvnetguard.util.RootShell
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 纯内核限速引擎：**不建立 VPN**，直接在前台服务里跑循环 ——
 * 每秒从内核读统计（qtaguid）、按需把规则同步进 iptables/tc。
 * 限速与统计全部发生在内核态，所以 TV 盒子即使没有 VPN 兼容性也照常工作。
 */
class RootEngineService : Service() {

    companion object {
        const val ACTION_STOP = "com.jiabihuan.tvnetguard.root.STOP"

        fun start(context: Context) {
            val i = Intent(context, RootEngineService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                VpnLog.e("start root engine failed", t)
            }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, RootEngineService::class.java).setAction(ACTION_STOP)) }
        }
    }

    private var scheduler: ScheduledExecutorService? = null
    private var lastRuleVersion = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine()
            stopSelf()
            return START_NOT_STICKY
        }
        if (EngineState.kind != EngineState.ROOT) startEngine()
        return START_STICKY
    }

    private fun startEngine() {
        if (!RootShell.hasRoot()) {
            EngineState.setStopped()
            EngineState.note("未获取到 root 权限，请在设置里改用「免 Root VPN」模式")
            stopSelf()
            return
        }

        StatsStore.reset()
        startForeground(App.Guard.NOTIFY_ID, buildNotification())
        EngineState.setRunning(EngineState.ROOT)

        RootBackend.apply()
        startScheduler()
        VpnLog.d("root engine started, method=${RootBackend.method}")
    }

    private fun startScheduler() {
        scheduler?.shutdownNow()
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "tng-root-tick").apply { isDaemon = true }
        }
        var count = 0
        scheduler?.scheduleAtFixedRate({
            if (EngineState.kind != EngineState.ROOT) return@scheduleAtFixedRate
            try {
                // 整机速率：TrafficStats 系统级绝对累计，无需 root、不受 SELinux 限制，
                // 按-uid 数据再怎么缺，整机上下行也永远是准的
                StatsStore.setGlobalSnapshot(
                    TrafficStats.getTotalRxBytes().coerceAtLeast(0),
                    TrafficStats.getTotalTxBytes().coerceAtLeast(0)
                )
                // 按-uid 绝对累计（qtaguid，root 读；兜底 iptables 仅上行）
                StatsStore.setSnapshot(RootStats.sample())
                // 基于相邻两次快照的差值算速率
                StatsStore.tick()
                checkRootBypass()

                if (lastRuleVersion != RuleStore.version) {
                    lastRuleVersion = RuleStore.version
                    RootBackend.apply()
                }
                if (++count % 2 == 0) updateNotification()
            } catch (t: Throwable) {
                VpnLog.w("root tick error: ${t.message}")
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private var rootUidTicks = 0
    private var unknownTicks = 0

    /**
     * 绕过检测：应用一旦被授予 root，它的上传会以 uid 0（root）发出，
     * 内核里按 uid 匹配的限速规则就不再命中 —— 这就是"给了 root 就限不住"的原理。
     * 检测到 root 身份上行异常（或整机上行远超被限应用之和）时在主界面告警。
     */
    private fun checkRootBypass() {
        // 1) uid 0（root）上行异常：系统自身 root 上行通常只有几 KB/s
        val rootTx = StatsStore.txRateOf(0)
        if (rootTx > 300_000) {
            if (++rootUidTicks >= 10) {
                EngineState.note(
                    "警告：检测到 root 身份上行 ${Format.speed(rootTx)}/s —— 可能有应用正通过 root 权限绕过限速。" +
                        "请打开 root 管理器（Magisk/超级用户），把该应用设为「拒绝」并记住"
                )
            }
            return
        }
        rootUidTicks = 0

        // 2) 兜底：整机上行远超被限应用实测之和，说明有流量走了"未分类通道"
        val global = StatsStore.globalTxRate
        val limitedSum = RuleStore.all()
            .filter { it.isLimited && !it.blocked }
            .sumOf { StatsStore.txRateOf(it.uid) }
        val unknown = global - limitedSum
        if (global > 1_000_000 && unknown > 500_000) {
            if (++unknownTicks >= 10) {
                EngineState.note(
                    "提示：整机上行 ${Format.speed(global)}/s 中约 ${Format.speed(unknown)}/s 来自未限速流量。" +
                        "若远超预期，请检查是否有应用通过 root 提权绕过了限速"
                )
            }
        } else {
            unknownTicks = 0
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        runCatching { nm.notify(App.Guard.NOTIFY_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val method = when (RootBackend.method) {
            "tc" -> "内核 tc+htb 字节级"
            "iptables" -> "内核 iptables"
            else -> "内核"
        }
        val text = "统计：${RootBackend.lastMessage.ifBlank { method }}"
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.Guard.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setContentTitle("星河守卫（纯 Root 模式）")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun stopEngine() {
        EngineState.setStopped()
        scheduler?.shutdownNow()
        scheduler = null
        RootBackend.clear()
        RootShell.closeSession() // 释放常驻 su 会话
        StatsStore.reset()
        VpnLog.d("root engine stopped")
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }
}
