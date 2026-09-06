package com.jiabihuan.tvnetguard.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.jiabihuan.tvnetguard.R
import com.jiabihuan.tvnetguard.data.RuleStore
import com.jiabihuan.tvnetguard.data.StatsStore
import com.jiabihuan.tvnetguard.util.Format
import com.jiabihuan.tvnetguard.util.Prefs
import com.jiabihuan.tvnetguard.vpn.GuardVpnService
import com.jiabihuan.tvnetguard.vpn.Limiter
import com.jiabihuan.tvnetguard.vpn.RootFirewall

/** 主界面：引擎开关 + 整机上下行实时速率 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false

    private lateinit var tvState: TextView
    private lateinit var tvUpSpeed: TextView
    private lateinit var tvDownSpeed: TextView
    private lateinit var tvUpTotal: TextView
    private lateinit var tvDownTotal: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvSessions: TextView
    private lateinit var tvLimited: TextView
    private lateinit var tvTip: TextView
    private lateinit var btnToggle: Button

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvState = findViewById(R.id.tv_engine_state)
        tvUpSpeed = findViewById(R.id.tv_up_speed)
        tvDownSpeed = findViewById(R.id.tv_down_speed)
        tvUpTotal = findViewById(R.id.tv_up_total)
        tvDownTotal = findViewById(R.id.tv_down_total)
        tvMode = findViewById(R.id.tv_mode)
        tvSessions = findViewById(R.id.tv_sessions)
        tvLimited = findViewById(R.id.tv_limited_apps)
        tvTip = findViewById(R.id.tv_tip)
        btnToggle = findViewById(R.id.btn_toggle)

        btnToggle.setOnClickListener { toggleEngine() }
        findViewById<Button>(R.id.btn_apps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnToggle.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refreshing = true
        handler.post(ticker)
    }

    override fun onPause() {
        refreshing = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun toggleEngine() {
        if (GuardVpnService.running) {
            GuardVpnService.stop(this)
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            startActivityForResult(prepare, REQ_VPN)
        } else {
            startEngine()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) startEngine()
        else if (requestCode == REQ_VPN) {
            tvTip.text = "未授权 VPN，无法接管流量"
        }
    }

    private fun startEngine() {
        GuardVpnService.start(this)
        handler.postDelayed({ refresh() }, 400)
    }

    private fun refresh() {
        if (!refreshing) return
        StatsStore.tick()

        val running = GuardVpnService.running
        tvState.text = if (running) getString(R.string.engine_running) else getString(R.string.engine_stopped)
        tvState.setTextColor(
            if (running) resources.getColor(R.color.down, theme)
            else resources.getColor(R.color.danger, theme)
        )
        btnToggle.text = if (running) getString(R.string.btn_stop) else getString(R.string.btn_start)

        tvUpSpeed.text = Format.speed(StatsStore.globalTxRate)
        tvDownSpeed.text = Format.speed(StatsStore.globalRxRate)

        var totalTx = 0L
        var totalRx = 0L
        for (uid in StatsStore.uids()) {
            totalTx += StatsStore.totalTx(uid)
            totalRx += StatsStore.totalRx(uid)
        }
        tvUpTotal.text = "${getString(R.string.label_total_up)} ${Format.bytes(totalTx)}"
        tvDownTotal.text = "${getString(R.string.label_total_down)} ${Format.bytes(totalRx)}"

        val mode = when {
            Prefs.rootMode && RootFirewall.active -> getString(R.string.mode_vpn_root)
            Prefs.rootMode -> getString(R.string.mode_root)
            else -> getString(R.string.mode_vpn)
        }
        tvMode.text = "${getString(R.string.label_mode)}：$mode"
        tvSessions.text = "${getString(R.string.label_session_count)}：${GuardVpnService.sessionCount}"
        tvLimited.text = "限速应用：${RuleStore.limitedUids().size} 个 · 已丢包：${Limiter.droppedPackets}"

        tvTip.text = when {
            !running -> "启动后，盒子所有流量会经过本机转发，即可按应用限速。"
            Prefs.rootMode && !RootFirewall.active -> "提示：${RootFirewall.lastMessage}"
            Limiter.droppedPackets > 0 -> "严格模式已丢弃 ${Format.bytes(Limiter.droppedBytes)} 上行数据"
            else -> "运行中。按「应用限速设置」给指定 App 设置上行上限。"
        }
        tvTip.visibility = View.VISIBLE
    }

    companion object {
        private const val REQ_VPN = 1001
    }
}
