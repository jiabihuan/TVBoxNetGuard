package com.jiabihuan.tvnetguard.ui

import android.app.Activity
import android.app.Dialog
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.jiabihuan.tvnetguard.R
import com.jiabihuan.tvnetguard.data.AppEntry
import com.jiabihuan.tvnetguard.data.AppRule
import com.jiabihuan.tvnetguard.data.RuleStore
import com.jiabihuan.tvnetguard.vpn.Limiter

/** 单个应用的上行限速设置 */
object AppLimitDialog {

    fun show(activity: Activity, entry: AppEntry, onSaved: () -> Unit) {
        val dialog = Dialog(activity)
        dialog.setContentView(R.layout.dialog_limit)
        dialog.setCancelable(true)

        val title = dialog.findViewById<TextView>(R.id.tv_title)
        val subtitle = dialog.findViewById<TextView>(R.id.tv_subtitle)
        val etUp = dialog.findViewById<EditText>(R.id.et_up)
        val etDown = dialog.findViewById<EditText>(R.id.et_down)
        val cbStrict = dialog.findViewById<CheckBox>(R.id.cb_strict)
        val cbBlock = dialog.findViewById<CheckBox>(R.id.cb_block_all)

        title.text = entry.name
        subtitle.text = entry.packageName

        val rule = RuleStore.get(entry.uid)
        etUp.setText(if (rule.upKbps > 0) rule.upKbps.toString() else "")
        etDown.setText(if (rule.downKbps > 0) rule.downKbps.toString() else "")
        cbStrict.isChecked = rule.strict
        cbBlock.isChecked = rule.blocked

        fun setPreset(up: Int) {
            etUp.setText(if (up > 0) up.toString() else "")
        }
        dialog.findViewById<Button>(R.id.btn_preset_off).setOnClickListener { setPreset(-1) }
        dialog.findViewById<Button>(R.id.btn_preset_10).setOnClickListener { setPreset(10) }
        dialog.findViewById<Button>(R.id.btn_preset_50).setOnClickListener { setPreset(50) }
        dialog.findViewById<Button>(R.id.btn_preset_block).setOnClickListener { setPreset(0) }

        dialog.findViewById<Button>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<Button>(R.id.btn_save).setOnClickListener {
            val up = parseLimit(etUp.text.toString())
            val down = parseLimit(etDown.text.toString())
            val saved = AppRule(
                uid = entry.uid,
                upKbps = up,
                downKbps = down,
                strict = cbStrict.isChecked,
                blocked = cbBlock.isChecked
            )
            RuleStore.put(saved)
            // 引擎每秒会同步一次规则，这里主动刷一下，让设置"立刻"生效
            Limiter.refresh()
            Toast.makeText(activity, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            onSaved()
            dialog.dismiss()
        }

        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        etUp.requestFocus()
        dialog.show()
    }

    /** 空 = 不限；0 = 彻底禁止 */
    private fun parseLimit(raw: String): Int {
        val t = raw.trim()
        if (t.isEmpty()) return -1
        return t.toIntOrNull()?.coerceAtLeast(0) ?: -1
    }
}
