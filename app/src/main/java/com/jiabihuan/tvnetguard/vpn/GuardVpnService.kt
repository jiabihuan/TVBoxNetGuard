package com.jiabihuan.tvnetguard.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.jiabihuan.tvnetguard.App
import com.jiabihuan.tvnetguard.EngineState
import com.jiabihuan.tvnetguard.R
import com.jiabihuan.tvnetguard.data.RuleStore
import com.jiabihuan.tvnetguard.data.StatsStore
import com.jiabihuan.tvnetguard.ui.MainActivity
import com.jiabihuan.tvnetguard.util.Format
import com.jiabihuan.tvnetguard.util.Prefs
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主引擎。
 *
 * 工作方式：建立 tun 拿到盒子全部 IPv4 流量 -> 按五元组拆到会话 ->
 * 在会话里做用户态 NAT 转发，转发前先过令牌桶 -> 达到按应用限制上行速度的目的。
 *
 * 严格性体现在四处：
 * 1. 全流量接管（含 IPv6，可配置），不给"绕过 VPN"留口子；
 * 2. 限速按 IP 层实际字节算（含头），标 10KB/s 线路上就不会超过 10KB/s；
 * 3. 严格模式下配额用尽直接丢包，不允许任何突发；
 * 4. 可选 root 加固，在内核 OUTPUT 链上再补一道 iptables 硬闸。
 */
class GuardVpnService : VpnService() {

    companion object {
        private const val TUN_ADDR = "10.1.10.1"
        private const val TUN_PREFIX = 32

        const val ACTION_START = "com.jiabihuan.tvnetguard.action.START"
        const val ACTION_STOP = "com.jiabihuan.tvnetguard.action.STOP"

        @Volatile
        var running = false
            private set

        @Volatile
        var startedAt = 0L
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var sessionCount = 0
            private set

        fun start(context: Context) {
            val i = Intent(context, GuardVpnService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                VpnLog.e("start service failed", t)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, GuardVpnService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }
    }

    private var vpnFd: ParcelFileDescriptor? = null
    private var vpnIn: FileInputStream? = null
    private var vpnOut: FileOutputStream? = null
    private var readerThread: Thread? = null
    private var sessions: SessionManager? = null
    private var uidResolver: UidResolver? = null
    private var scheduler: ScheduledExecutorService? = null
    private var lastRuleVersion = -1L
    private val looping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        uidResolver = UidResolver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startEngine()
        return START_STICKY
    }

    private fun startEngine() {
        val mtu = Prefs.mtu
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(TUN_ADDR, TUN_PREFIX)
            .addRoute("0.0.0.0", 0)
            .setMtu(mtu)
            .setBlocking(true)

        // 接管 IPv6 并丢弃：否则应用可以走 IPv6 绕过所有限速
        if (Prefs.blockIpv6) {
            runCatching { builder.addRoute("::", 0) }
        }
        runCatching { builder.addDnsServer("223.5.5.5") }
        runCatching { builder.addDnsServer("119.29.29.29") }
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            // 自身不在已安装列表里（理论上不会发生），忽略
        }

        val fd = try {
            builder.establish()
        } catch (t: Throwable) {
            VpnLog.e("establish failed", t)
            null
        }
        if (fd == null) {
            lastError = "建立 VPN 失败，请确认没有其他 VPN 或 Always-on VPN 占用"
            stopSelf()
            return
        }

        vpnFd = fd
        vpnIn = FileInputStream(fd.fileDescriptor)
        vpnOut = FileOutputStream(fd.fileDescriptor)

        Limiter.reset()
        sessions = SessionManager(this, uidResolver!!, mtu)
        running = true
        startedAt = System.currentTimeMillis()
        lastError = null

        startForeground(App.Guard.NOTIFY_ID, buildNotification())
        startReader()
        startScheduler()

        // 规则可能在引擎停着的时候改过，进来先同步一次
        lastRuleVersion = -1L
        if (Prefs.rootMode) RootBackend.apply()
        EngineState.setRunning(EngineState.VPN)
        VpnLog.d("engine started, mtu=$mtu")
    }

    private fun startReader() {
        if (!looping.compareAndSet(false, true)) return
        readerThread = Thread({
            val buf = ByteArray(4096)
            val input = vpnIn
            while (running && input != null) {
                val n = try {
                    input.read(buf)
                } catch (t: Throwable) {
                    -1
                }
                if (n <= 0) {
                    if (!running) break
                    continue
                }
                if (n < 20) continue
                // 必须拷贝：buf 会被下一次 read 覆盖，而下游是异步处理的
                val pkt = Ipv4Packet(buf.copyOf(n), n)
                if (!pkt.isValid(20)) continue
                if (pkt.version != 4) continue // IPv6 一律丢弃（严格模式下不给绕过机会）
                try {
                    when (pkt.protocol) {
                        Packets.IPPROTO_TCP -> sessions?.handleTcp(pkt)
                        Packets.IPPROTO_UDP -> sessions?.handleUdp(pkt)
                        else -> Unit // ICMP 等直接丢
                    }
                } catch (t: Throwable) {
                    VpnLog.w("dispatch error: ${t.message}")
                }
            }
            looping.set(false)
        }, "tng-tun-reader").apply { isDaemon = true; start() }
    }

    private fun startScheduler() {
        scheduler?.shutdownNow()
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "tng-tick").apply { isDaemon = true }
        }
        var tickCount = 0
        scheduler?.scheduleAtFixedRate({
            if (!running) return@scheduleAtFixedRate
            try {
                StatsStore.tick()
                Limiter.refresh()

                if (lastRuleVersion != RuleStore.version) {
                    lastRuleVersion = RuleStore.version
                    sessions?.killBlocked()
                    if (Prefs.rootMode) RootBackend.apply()
                }

                if (++tickCount % 5 == 0) {
                    sessions?.cleanup()
                    sessionCount = sessions?.activeCount() ?: 0
                }
                if (tickCount % 2 == 0) {
                    updateNotification()
                }
            } catch (t: Throwable) {
                VpnLog.w("tick error: ${t.message}")
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    /** 写回 tun，必须是同步的：并发写会把包写坏 */
    @Synchronized
    fun writeToTun(packet: ByteArray) {
        val out = vpnOut ?: return
        try {
            out.write(packet)
        } catch (t: Throwable) {
            // tun 已关闭，忽略
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
        val text = getString(
            R.string.notify_text,
            Format.speed(StatsStore.globalTxRate),
            Format.speed(StatsStore.globalRxRate),
            RuleStore.limitedUids().size
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.Guard.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setContentTitle(getString(R.string.notify_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** 用户从系统设置里断开 VPN */
    override fun onRevoke() {
        stopEngine()
        super.onRevoke()
        stopSelf()
    }

    private fun stopEngine() {
        running = false
        EngineState.setStopped()
        looping.set(false)
        scheduler?.shutdownNow()
        scheduler = null
        sessions?.closeAll()
        sessions = null
        runCatching { vpnIn?.close() }
        runCatching { vpnOut?.close() }
        runCatching { vpnFd?.close() }
        vpnIn = null
        vpnOut = null
        vpnFd = null
        RootBackend.clear()
        VpnLog.d("engine stopped")
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }
}
