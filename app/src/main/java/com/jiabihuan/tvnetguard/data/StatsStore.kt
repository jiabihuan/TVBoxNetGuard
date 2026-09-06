package com.jiabihuan.tvnetguard.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 流量统计中心：按 uid 累计上下行字节数，并给出实时速率（字节/秒）。
 * 数据由 VPN 引擎写入，UI 与通知栏通过 [tick] 采样读取。
 */
object StatsStore {

    private val tx = ConcurrentHashMap<Int, AtomicLong>()
    private val rx = ConcurrentHashMap<Int, AtomicLong>()
    private val txRate = ConcurrentHashMap<Int, Long>()
    private val rxRate = ConcurrentHashMap<Int, Long>()

    private val lastTx = ConcurrentHashMap<Int, Long>()
    private val lastRx = ConcurrentHashMap<Int, Long>()

    @Volatile
    private var lastSampleAt = System.currentTimeMillis()
    @Volatile
    private var lastGlobalTx = 0L
    @Volatile
    private var lastGlobalRx = 0L
    @Volatile
    var globalTxRate = 0L
        private set
    @Volatile
    var globalRxRate = 0L
        private set

    fun addTx(uid: Int, bytes: Long) {
        tx.getOrPut(uid) { AtomicLong(0) }.addAndGet(bytes)
    }

    fun addRx(uid: Int, bytes: Long) {
        rx.getOrPut(uid) { AtomicLong(0) }.addAndGet(bytes)
    }

    fun totalTx(uid: Int): Long = tx[uid]?.get() ?: 0L
    fun totalRx(uid: Int): Long = rx[uid]?.get() ?: 0L
    fun txRateOf(uid: Int): Long = txRate[uid] ?: 0L
    fun rxRateOf(uid: Int): Long = rxRate[uid] ?: 0L

    fun uids(): Set<Int> = tx.keys + rx.keys

    /** 采样一次速率。建议每 1 秒调用一次（UI 与通知共用）。 */
    fun tick(now: Long = System.currentTimeMillis()) {
        val dtMs = now - lastSampleAt
        if (dtMs < 200) return
        val factor = 1000.0 / dtMs

        var gTx = 0L
        var gRx = 0L
        val keys = tx.keys + rx.keys
        for (uid in keys) {
            val curTx = tx[uid]?.get() ?: 0L
            val curRx = rx[uid]?.get() ?: 0L
            gTx += curTx
            gRx += curRx

            val pTx = lastTx[uid] ?: 0L
            val pRx = lastRx[uid] ?: 0L
            var rTx = ((curTx - pTx) * factor).toLong()
            var rRx = ((curRx - pRx) * factor).toLong()
            if (rTx < 0) rTx = 0
            if (rRx < 0) rRx = 0

            // 轻度平滑，避免 UI 数字跳动（0.6 新值 + 0.4 旧值）
            txRate[uid] = ((txRate[uid] ?: 0L) * 0.4 + rTx * 0.6).toLong()
            rxRate[uid] = ((rxRate[uid] ?: 0L) * 0.4 + rRx * 0.6).toLong()

            lastTx[uid] = curTx
            lastRx[uid] = curRx
        }

        var grTx = ((gTx - lastGlobalTx) * factor).toLong()
        var grRx = ((gRx - lastGlobalRx) * factor).toLong()
        if (grTx < 0) grTx = 0
        if (grRx < 0) grRx = 0
        globalTxRate = (globalTxRate * 0.4 + grTx * 0.6).toLong()
        globalRxRate = (globalRxRate * 0.4 + grRx * 0.6).toLong()

        lastGlobalTx = gTx
        lastGlobalRx = gRx
        lastSampleAt = now
    }

    fun reset() {
        tx.clear(); rx.clear(); txRate.clear(); rxRate.clear()
        lastTx.clear(); lastRx.clear()
        lastGlobalTx = 0; lastGlobalRx = 0
        globalTxRate = 0; globalRxRate = 0
        lastSampleAt = System.currentTimeMillis()
    }
}
