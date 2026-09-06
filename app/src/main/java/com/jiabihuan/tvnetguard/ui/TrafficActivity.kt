package com.jiabihuan.tvnetguard.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jiabihuan.tvnetguard.R
import com.jiabihuan.tvnetguard.data.AppEntry
import com.jiabihuan.tvnetguard.data.AppLoader
import com.jiabihuan.tvnetguard.data.StatsStore
import com.jiabihuan.tvnetguard.util.Format

/**
 * 流量详情：按「实际产生流量」列出每个 uid 的上行 / 下行速率与累计，
 * 排行查看，点击任意应用进入限速设置。
 *
 * 与「应用限速设置」的区别：这里以 StatsStore 的流量数据为准，
 * 能看到 root 提权进程（uid 0）、系统（uid 1000）等不出现在应用列表里的流量来源。
 */
class TrafficActivity : Activity() {

    data class Row(val uid: Int, val name: String, val pkg: String)

    private class Adapter(
        private val items: MutableList<Row>,
        private val onClick: (Row) -> Unit
    ) : RecyclerView.Adapter<Adapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_traffic, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = items[position]
            holder.bind(r)
            holder.itemView.setOnClickListener { onClick(r) }
        }

        override fun getItemCount(): Int = items.size

        class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
            private val name: TextView = view.findViewById(R.id.tv_name)
            private val total: TextView = view.findViewById(R.id.tv_total)
            private val up: TextView = view.findViewById(R.id.tv_up)
            private val down: TextView = view.findViewById(R.id.tv_down)

            var row: Row? = null

            fun bind(r: Row) {
                row = r
                name.text = r.name
                refresh(r)
            }

            /** 只刷新数字，不动列表顺序（TV 遥控器下重排会抢走焦点） */
            fun refresh(r: Row) {
                total.text = buildString {
                    if (r.pkg.isNotEmpty()) append(r.pkg + " · ")
                    append("累计 ↑ ")
                    append(Format.bytes(StatsStore.totalTx(r.uid)))
                    append(" · ↓ ")
                    append(Format.bytes(StatsStore.totalRx(r.uid)))
                }
                up.text = "↑ " + Format.speed(StatsStore.txRateOf(r.uid))
                down.text = "↓ " + Format.speed(StatsStore.rxRateOf(r.uid))
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val rows = ArrayList<Row>()
    private val entryByUid = HashMap<Int, AppEntry>()
    private lateinit var adapter: Adapter
    private lateinit var recycler: RecyclerView
    private lateinit var btnUp: Button
    private lateinit var btnDown: Button
    private var sortByUp = true

    private val ticker = object : Runnable {
        override fun run() {
            StatsStore.tick()
            refreshVisible()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_traffic)

        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = Adapter(rows) { row ->
            AppLimitDialog.show(this, entryFor(row)) {}
        }
        recycler.adapter = adapter

        btnUp = findViewById(R.id.btn_sort_up)
        btnDown = findViewById(R.id.btn_sort_down)
        btnUp.setOnClickListener { setSort(true) }
        btnDown.setOnClickListener { setSort(false) }
        btnUp.requestFocus()

        loadEntries()
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun setSort(byUp: Boolean) {
        if (sortByUp == byUp) return
        sortByUp = byUp
        markSortButtons()
        rebuild()
    }

    private fun markSortButtons() {
        btnUp.text = if (sortByUp) "✔ " + getString(R.string.traffic_sort_up) else getString(R.string.traffic_sort_up)
        btnDown.text = if (!sortByUp) "✔ " + getString(R.string.traffic_sort_down) else getString(R.string.traffic_sort_down)
    }

    /** 应用名映射：列表外的 uid（root / 系统）也能显示可读名称 */
    private fun loadEntries() {
        Thread {
            val list = AppLoader.load(this, true)
            handler.post {
                entryByUid.clear()
                for (e in list) entryByUid[e.uid] = e
                rebuild()
            }
        }.start()
    }

    private fun displayName(uid: Int): Pair<String, String> {
        entryByUid[uid]?.let { return it.name to it.packageName }
        val special = when (uid) {
            0 -> "root（提权进程）"
            1000 -> "系统（android）"
            9999 -> "media/media"
            1013 -> "媒体服务"
            else -> null
        }
        if (special != null) return special to ""
        val n = AppLoader.nameOf(this, uid)
        return (n ?: "uid $uid") to ""
    }

    private fun entryFor(row: Row): AppEntry =
        entryByUid[row.uid] ?: AppEntry(row.name, row.pkg, row.uid, null, true, true)

    /** 重新收集有流量的 uid 并排序（进入页面 / 切换排序时） */
    private fun rebuild() {
        val list = ArrayList<Row>()
        for (uid in StatsStore.uids()) {
            val rate = StatsStore.txRateOf(uid) + StatsStore.rxRateOf(uid)
            val total = StatsStore.totalTx(uid) + StatsStore.totalRx(uid)
            if (rate <= 0 && total < 1024) continue
            val (name, pkg) = displayName(uid)
            list.add(Row(uid, name, pkg))
        }
        list.sortWith(
            compareByDescending<Row> {
                if (sortByUp) StatsStore.txRateOf(it.uid) else StatsStore.rxRateOf(it.uid)
            }.thenByDescending {
                if (sortByUp) StatsStore.totalTx(it.uid) else StatsStore.totalRx(it.uid)
            }
        )
        rows.clear()
        rows.addAll(list)
        markSortButtons()
        adapter.notifyDataSetChanged()
    }

    /** 每秒刷新可见行的速率数字 */
    private fun refreshVisible() {
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? Adapter.Holder ?: continue
            holder.row?.let { holder.refresh(it) }
        }
    }
}
