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

    /**
     * root 模式专用：qtaguid / iptables 给出的是**绝对累计值**，
     * 直接覆盖，tick() 会基于相邻两次快照的差值算出实时速率（与 addTx 增量模型可互换，
     * 但切换模式前务必 [reset]，避免两类数据叠加）。
     * Pair 语义与 RootStats 约定一致：**first = 下行(rx)累计，second = 上行(tx)累计**。
     */
    fun setSnapshot(snapshot: Map<Int, Pair<Long, Long>>) {
        for ((uid, pr) in snapshot) {
            rx.getOrPut(uid) { AtomicLong(0) }.set(pr.first)
            tx.getOrPut(uid) { AtomicLong(0) }.set(pr.second)
        }
    }

    // 整机绝对累计（root 模式来自 TrafficStats）：>= 0 表示有效
    @Volatile
    private var snapRx = -1L
    @Volatile
    private var snapTx = -1L
    @Volatile
    private var lastSnapRx = -1L
    @Volatile
    private var lastSnapTx = -1L

    /**
     * 整机速率专用通道：直接喂 TrafficStats.getTotalRx/TxBytes() 这类**系统级绝对累计值**，
     * 不依赖按-uid 数据是否齐全，root 模式下整机上下行也因此永远准确。
     */
    fun setGlobalSnapshot(rxTotal: Long, txTotal: Long) {
        snapRx = rxTotal
        snapTx = txTotal
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

        val srx = snapRx
        val stx = snapTx
        if (srx >= 0 && lastSnapRx >= 0) {
            // root 模式：整机速率来自系统级绝对累计（TrafficStats）差分，最准
            var grTx = ((stx - lastSnapTx) * factor).toLong()
            var grRx = ((srx - lastSnapRx) * factor).toLong()
            if (grTx < 0) grTx = 0
            if (grRx < 0) grRx = 0
            globalTxRate = (globalTxRate * 0.4 + grTx * 0.6).toLong()
            globalRxRate = (globalRxRate * 0.4 + grRx * 0.6).toLong()
        } else if (srx < 0) {
            // VPN 模式（无整机快照）：整机 = 所有 uid 之和
            var grTx = ((gTx - lastGlobalTx) * factor).toLong()
            var grRx = ((gRx - lastGlobalRx) * factor).toLong()
            if (grTx < 0) grTx = 0
            if (grRx < 0) grRx = 0
            globalTxRate = (globalTxRate * 0.4 + grTx * 0.6).toLong()
            globalRxRate = (globalRxRate * 0.4 + grRx * 0.6).toLong()
        }
        // srx >= 0 但 lastSnapRx < 0：第一拍，只建立基线，速率保持 0
        lastSnapRx = srx
        lastSnapTx = stx
        lastGlobalTx = gTx
        lastGlobalRx = gRx
        lastSampleAt = now
    }

    fun reset() {
        tx.clear(); rx.clear(); txRate.clear(); rxRate.clear()
        lastTx.clear(); lastRx.clear()
        lastGlobalTx = 0; lastGlobalRx = 0
        globalTxRate = 0; globalRxRate = 0
        snapRx = -1; snapTx = -1
        lastSnapRx = -1; lastSnapTx = -1
        lastSampleAt = System.currentTimeMillis()
    }
}
