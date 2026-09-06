package com.jiabihuan.tvnetguard.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.jiabihuan.tvnetguard.EngineState
import com.jiabihuan.tvnetguard.R
import com.jiabihuan.tvnetguard.receiver.WatchdogReceiver
import com.jiabihuan.tvnetguard.util.Prefs
import com.jiabihuan.tvnetguard.vpn.GuardVpnService
import com.jiabihuan.tvnetguard.vpn.Limiter
import com.jiabihuan.tvnetguard.vpn.RootEngineService

/** 设置页：用代码构建。针对遥控器做了焦点处理——每行单独可聚焦、有高亮背景。 */
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

        root.addView(modeRow())

        root.addView(switchRow("Root 加固（VPN 模式下叠加）", "免 Root 模式同时用内核 iptables 兜底", Prefs.rootMode) {
            Prefs.rootMode = it
        })

        root.addView(switchRow("接管并阻断 IPv6", "防止应用走 IPv6 绕过限速（默认开启）", Prefs.blockIpv6) {
            Prefs.blockIpv6 = it
            if (GuardVpnService.running) {
                GuardVpnService.stop(this)
                GuardVpnService.start(this)
            }
        })

        root.addView(switchRow("桌面悬浮窗", "在桌面上常驻显示实时上下行速率", Prefs.floatWindow) {
            Prefs.floatWindow = it
            if (it) FloatWindowService.start(this) else FloatWindowService.stop(this)
        })

        root.addView(cornerRow())

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

        // 让第一个可聚焦项拿到遥控器焦点（标题不可聚焦，所以取第二个子项）
        root.getChildAt(1)?.requestFocus()
    }

    private fun titleView(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setPadding(0, 8, 0, 24)
    }

    private fun focusBg() = resources.getDrawable(R.drawable.bg_item, theme)

    private fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        // Switch 先建好：行容器要在 apply 块里引用它
        val sw = Switch(this).apply {
            isChecked = checked
            isFocusable = false   // 焦点交给整行，避免高亮跑到开关内部
            isClickable = false
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(20, 18, 20, 18)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            background = focusBg()   // 获得焦点时显示蓝边高亮
            setOnClickListener { sw.isChecked = !sw.isChecked }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    sw.isChecked = !sw.isChecked
                    true
                } else false
            }
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
            background = focusBg()
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

    private fun modeRow(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
        }
        wrap.addView(TextView(this).apply {
            text = "工作模式"
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        })
        wrap.addView(TextView(this).apply {
            text = "盒子有 root 用「纯 Root」最稳，不占 VPN；无 root 才用「免 Root VPN」"
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 4, 0, 10)
        })
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val labels = listOf("自动" to 0, "纯 Root" to 1, "免 Root VPN" to 2)
        for ((name, value) in labels) {
            val btn = Button(this).apply {
                text = name
                setPadding(24, 12, 24, 12)
                background = focusBg()
                setOnClickListener {
                    Prefs.mode = value
                    if (EngineState.running) {
                        if (EngineState.kind == EngineState.ROOT) RootEngineService.stop(this@SettingsActivity)
                        else GuardVpnService.stop(this@SettingsActivity)
                    }
                    updateModeButtons(group)
                }
            }
            group.addView(btn)
        }
        wrap.addView(group)
        wrap.post { updateModeButtons(group) }
        return wrap
    }

    private fun updateModeButtons(group: LinearLayout) {
        val values = listOf(0, 1, 2)
        for (i in 0 until group.childCount) {
            val b = group.getChildAt(i) as Button
            val v = values.getOrElse(i) { 0 }
            val on = v == Prefs.mode
            b.text = if (on) "✔ ${nameOfValue(v)}" else nameOfValue(v)
            b.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
    }

    private fun nameOfValue(v: Int): String = when (v) {
        0 -> "自动"
        1 -> "纯 Root"
        else -> "免 Root VPN"
    }

    /** 悬浮窗位置：0=左上 1=右上 2=左下 3=右下 */
    private fun cornerRow(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
        }
        wrap.addView(TextView(this).apply {
            text = "悬浮窗位置"
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        })
        wrap.addView(TextView(this).apply {
            text = "改完 1 秒内自动挪过去，不用重启悬浮窗"
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 4, 0, 10)
        })
        val group = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf("左上" to 0, "右上" to 1, "左下" to 2, "右下" to 3)
        for ((name, value) in labels) {
            val btn = Button(this).apply {
                text = name
                setPadding(24, 12, 24, 12)
                background = focusBg()
                setOnClickListener {
                    Prefs.floatCorner = value
                    updateCornerButtons(group)
                }
            }
            group.addView(btn)
        }
        wrap.addView(group)
        wrap.post { updateCornerButtons(group) }
        return wrap
    }

    private fun updateCornerButtons(group: LinearLayout) {
        val values = listOf(0, 1, 2, 3)
        for (i in 0 until group.childCount) {
            val b = group.getChildAt(i) as Button
            val v = values.getOrElse(i) { 0 }
            val on = v == Prefs.floatCorner
            b.text = if (on) "✔ ${cornerName(v)}" else cornerName(v)
            b.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
    }

    private fun cornerName(v: Int): String = when (v) {
        0 -> "左上"
        1 -> "右上"
        2 -> "左下"
        else -> "右下"
    }

    private fun aboutView(): TextView = TextView(this).apply {
        text = "星河守卫 TV v1.1.0"
        setTextColor(resources.getColor(R.color.text_secondary, theme))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(20, 32, 20, 20)
    }
}
