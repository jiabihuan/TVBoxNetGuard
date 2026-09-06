package com.jiabihuan.tvnetguard.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jiabihuan.tvnetguard.R
import com.jiabihuan.tvnetguard.data.AppEntry
import com.jiabihuan.tvnetguard.data.RuleStore
import com.jiabihuan.tvnetguard.data.StatsStore
import com.jiabihuan.tvnetguard.util.Format

class AppAdapter(
    private val items: MutableList<AppEntry>,
    private val onClick: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        holder.entry = entry
        holder.bindStatic(entry)
        holder.bindDynamic(entry)
        holder.itemView.setOnClickListener { onClick(entry) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.iv_icon)
        private val name: TextView = view.findViewById(R.id.tv_name)
        private val pkg: TextView = view.findViewById(R.id.tv_package)
        private val speed: TextView = view.findViewById(R.id.tv_speed)
        private val limit: TextView = view.findViewById(R.id.tv_limit)

        var entry: AppEntry? = null

        fun bindStatic(e: AppEntry) {
            name.text = e.name
            pkg.text = e.packageName
            if (e.icon != null) icon.setImageDrawable(e.icon)
        }

        /** 速率与限速状态，每秒刷新 */
        fun bindDynamic(e: AppEntry) {
            val up = StatsStore.txRateOf(e.uid)
            val down = StatsStore.rxRateOf(e.uid)
            val total = StatsStore.totalTx(e.uid) + StatsStore.totalRx(e.uid)
            speed.text = "↑ ${Format.speed(up)}   ↓ ${Format.speed(down)}   累计 ${Format.bytes(total)}"

            val rule = RuleStore.get(e.uid)
            val ctx = itemView.context
            limit.text = when {
                rule.blocked -> ctx.getString(R.string.limit_blocked)
                rule.upKbps == 0 -> ctx.getString(R.string.limit_blocked)
                rule.upKbps > 0 -> {
                    val tag = if (rule.strict) "严格" else "平滑"
                    ctx.getString(R.string.limit_value, "${rule.upKbps} KB/s · $tag")
                }
                rule.downKbps >= 0 -> "下行 ${rule.downKbps} KB/s"
                else -> ctx.getString(R.string.limit_unset)
            }
            limit.setTextColor(
                itemView.resources.getColor(
                    if (rule.upKbps >= 0 || rule.blocked) R.color.up else R.color.text_secondary,
                    itemView.context.theme
                )
            )
        }
    }
}
