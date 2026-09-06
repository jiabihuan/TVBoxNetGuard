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
import com.jiabihuan.tvnetguard.EngineState
import com.jiabihuan.tvnetguard.R
import com.jiabihuan.tvnetguard.data.RuleStore
import com.jiabihuan.tvnetguard.data.StatsStore
import com.jiabihuan.tvnetguard.util.Format
import com.jiabihuan.tvnetguard.util.Prefs
import com.jiabihuan.tvnetguard.util.RootShell
import com.jiabihuan.tvnetguard.vpn.GuardVpnService
import com.jiabihuan.tvnetguard.vpn.RootBackend
import com.jiabihuan.tvnetguard.vpn.RootEngineService

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
        // 进入界面只探测一次 root，刷新时复用结果，避免每秒拉起 su 弹授权
        EngineState.rootAvailable = RootShell.hasRoot()
        refreshing = true
        handler.post(ticker)
    }

    override fun onPause() {
        refreshing = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun toggleEngine() {
        if (EngineState.running) {
            stopEngine()
            return
        }
        when (Prefs.mode) {
            MODE_VPN -> prepareThenStartVpn()
            MODE_ROOT -> startRoot()
            else -> {
                if (EngineState.rootAvailable) startRoot() else prepareThenStartVpn()
            }
        }
        handler.postDelayed({ refresh() }, 500)
    }

    private fun stopEngine() {
        if (EngineState.kind == EngineState.ROOT) RootEngineService.stop(this)
        else GuardVpnService.stop(this)
    }

    private fun startRoot() {
        RootEngineService.start(this)
    }

    private fun prepareThenStartVpn() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) startActivityForResult(prepare, REQ_VPN)
        else GuardVpnService.start(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) prepareThenStartVpn()
        else if (requestCode == REQ_VPN) tvTip.text = "未授权 VPN，无法用免 Root 模式。可在设置里切换到「纯 Root」"
    }

    private fun refresh() {
        if (!refreshing) return
        StatsStore.tick()

        val running = EngineState.running
        val kind = EngineState.kind
        val engineLabel = when (kind) {
            EngineState.ROOT -> "纯 Root 内核"
            EngineState.VPN -> "免 Root VPN"
            else -> ""
        }
        tvState.text = if (running) "引擎运行中 · $engineLabel" else getString(R.string.engine_stopped)
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

        val modeLabel = when (Prefs.mode) {
            MODE_ROOT -> getString(R.string.mode_root)
            MODE_VPN -> getString(R.string.mode_vpn)
            else -> if (EngineState.rootAvailable) "自动（将用 Root）" else "自动（将用 VPN）"
        }
        tvMode.text = "${getString(R.string.label_mode)}：$modeLabel"
        tvSessions.text = "${getString(R.string.label_session_count)}：${EngineState.sessionCount}"
        tvLimited.text = "限速应用：${RuleStore.limitedUids().size} 个"

        tvTip.text = when {
            !running -> "启动后，盒子该 App 的上行速度会被内核掐住。"
            kind == EngineState.ROOT && RootBackend.method.isNotEmpty() -> "内核限速方式：${RootBackend.method}。${RootBackend.lastMessage}"
            kind == EngineState.VPN && Prefs.rootMode -> "免 Root 模式已叠加 Root 加固：${RootBackend.lastMessage}"
            else -> "运行中。到「应用限速设置」给指定 App 设上行上限。"
        }
        tvTip.visibility = View.VISIBLE
    }

    companion object {
        private const val REQ_VPN = 1001
        private const val MODE_AUTO = 0
        private const val MODE_ROOT = 1
        private const val MODE_VPN = 2
    }
}
