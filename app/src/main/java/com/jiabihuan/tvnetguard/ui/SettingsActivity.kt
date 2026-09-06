package com.jiabihuan.tvnetguard.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.jiabihuan.tvnetguard.R
import com.jiabihuan.tvnetguard.receiver.WatchdogReceiver
import com.jiabihuan.tvnetguard.util.Prefs
import com.jiabihuan.tvnetguard.vpn.GuardVpnService
import com.jiabihuan.tvnetguard.vpn.Limiter

/** 设置页：用代码构建，省掉一堆布局文件，改动也直观 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 40, 56, 40)
            setBackgroundColor(resources.getColor(R.color.bg, theme))
        }
        scroll.addView(root)

        root.addView(titleView(getString(R.string.title_settings)))

        root.addView(switchRow(getString(R.string.set_autostart), "盒子开机后自动接管流量", Prefs.autoStart) {
            Prefs.autoStart = it
            WatchdogReceiver.schedule(this)
        })

        root.addView(switchRow(getString(R.string.set_watchdog), "服务被系统回收后自动拉起", Prefs.watchdog) {
            Prefs.watchdog = it
            WatchdogReceiver.schedule(this)
        })

        root.addView(switchRow(getString(R.string.set_strict_global), "全局按丢包处理超限流量", Prefs.strictGlobal) {
            Prefs.strictGlobal = it
            Limiter.refresh()
        })

        root.addView(switchRow(getString(R.string.set_root), "内核 iptables 再落一道硬闸（需 root）", Prefs.rootMode) {
            Prefs.rootMode = it
            if (it) {
                Thread { com.jiabihuan.tvnetguard.vpn.RootFirewall.apply() }.start()
            } else {
                com.jiabihuan.tvnetguard.vpn.RootFirewall.clear()
            }
        })

        root.addView(switchRow("接管并阻断 IPv6", "防止应用走 IPv6 绕过限速（默认开启）", Prefs.blockIpv6) {
            Prefs.blockIpv6 = it
            if (GuardVpnService.running) {
                GuardVpnService.stop(this)
                GuardVpnService.start(this)
            }
        })

        root.addView(switchRow(getString(R.string.set_log), "在 logcat 中输出限速细节（排障用）", Prefs.logEnabled) {
            Prefs.logEnabled = it
        })

        root.addView(editRow(getString(R.string.set_global_up), Prefs.globalUpKbps) {
            Prefs.globalUpKbps = it
            Limiter.refresh()
        })

        root.addView(editRow("全局下行限速（KB/s，0 为不限）", Prefs.globalDownKbps) {
            Prefs.globalDownKbps = it
            Limiter.refresh()
        })

        root.addView(editRow(getString(R.string.set_mtu), Prefs.mtu) {
            Prefs.mtu = it
        })

        root.addView(aboutView())

        setContentView(scroll)
    }

    private fun titleView(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setPadding(0, 8, 0, 24)
    }

    private fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(20, 18, 20, 18)
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            this.text = title
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        })
        texts.addView(TextView(this).apply {
            this.text = desc
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 4, 0, 0)
        })
        row.addView(texts)

        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        return row
    }

    private fun editRow(title: String, value: Int, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
        }
        row.addView(TextView(this).apply {
            this.text = title
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        val edit = EditText(this).apply {
            setText(if (value < 0) "" else value.toString())
            hint = "留空 = 不限"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(16, 12, 16, 12)
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val raw = text.toString().trim()
                    onChange(if (raw.isEmpty()) -1 else (raw.toIntOrNull() ?: -1))
                }
            }
        }
        row.addView(edit)
        return row
    }

    private fun aboutView(): TextView = TextView(this).apply {
        text = "流量守卫 TV v1.0.0\n免 root 方案基于 VpnService 用户态转发 + 令牌桶限速；" +
            "root 加固基于 iptables owner 匹配。\n项目地址：github.com/jiabihuan/TVBoxNetGuard"
        setTextColor(resources.getColor(R.color.text_secondary, theme))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(20, 32, 20, 20)
    }
}
