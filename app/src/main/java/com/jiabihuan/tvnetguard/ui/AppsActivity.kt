package com.jiabihuan.tvnetguard.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jiabihuan.tvnetguard.R
import com.jiabihuan.tvnetguard.data.AppEntry
import com.jiabihuan.tvnetguard.data.AppLoader
import com.jiabihuan.tvnetguard.data.StatsStore
import com.jiabihuan.tvnetguard.util.Prefs
import java.util.Collections

/** 应用列表：实时速率 + 点击进入上行限速设置 */
class AppsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val items = ArrayList<AppEntry>()
    private lateinit var adapter: AppAdapter
    private lateinit var recycler: RecyclerView
    private var sortByTraffic = true

    private val ticker = object : Runnable {
        override fun run() {
            StatsStore.tick()
            refreshSpeeds()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter(items) { entry ->
            AppLimitDialog.show(this, entry) {
                adapter.notifyDataSetChanged()
            }
        }
        recycler.adapter = adapter

        val btnSort = findViewById<Button>(R.id.btn_sort)
        val btnSystem = findViewById<Button>(R.id.btn_system)
        btnSort.setOnClickListener {
            sortByTraffic = !sortByTraffic
            btnSort.text = if (sortByTraffic) getString(R.string.apps_sort_traffic) else getString(R.string.apps_sort_name)
            load()
        }
        btnSystem.setOnClickListener {
            Prefs.showSystem = !Prefs.showSystem
            btnSystem.text = if (Prefs.showSystem) "隐藏系统应用" else getString(R.string.apps_show_system)
            load()
        }
        btnSystem.text = if (Prefs.showSystem) "隐藏系统应用" else getString(R.string.apps_show_system)
        btnSort.requestFocus()

        load()
    }

    private fun load() {
        Thread {
            val list = AppLoader.load(this, Prefs.showSystem).toMutableList()
            handler.post {
                items.clear()
                items.addAll(list)
                sort()
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun sort() {
        if (sortByTraffic) {
            items.sortWith(
                compareByDescending<AppEntry> {
                    StatsStore.totalTx(it.uid) + StatsStore.totalRx(it.uid)
                }.thenBy { it.name.lowercase() }
            )
        } else {
            items.sortWith(compareBy({ it.name.lowercase() }, { it.packageName }))
        }
    }

    /** 只刷可见行的文字，不重排列表 —— TV 遥控器下重排会抢走焦点，体验很差 */
    private fun refreshSpeeds() {
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? AppAdapter.Holder
            val entry = holder?.entry ?: continue
            holder.bindDynamic(entry)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }
}
