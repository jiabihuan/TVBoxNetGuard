package com.jiabihuan.tvnetguard.vpn

import com.jiabihuan.tvnetguard.data.RuleStore
import com.jiabihuan.tvnetguard.util.Prefs
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 uid 维度管理上行 / 下行令牌桶，并叠加全局限速。
 *
 * 规则是热更新的：引擎每秒调用一次 [refresh]，把 RuleStore 里的最新规则
 * 同步到这里的无锁快照，数据包路径上只做 ConcurrentHashMap 读，不碰磁盘。
 */
object Limiter {

    private const val GLOBAL = -1000

    private class Cfg(
        val upBytes: Long,     // -1 不限，0 禁止
        val downBytes: Long,
        val strict: Boolean,
        val blocked: Boolean,
        val blockUdp: Boolean = false
    ) {
        val hasUpLimit: Boolean get() = upBytes >= 0
        val hasDownLimit: Boolean get() = downBytes >= 0
    }

    private val cfgMap = ConcurrentHashMap<Int, Cfg>()
    private val upBuckets = ConcurrentHashMap<Int, TokenBucket>()
    private val downBuckets = ConcurrentHashMap<Int, TokenBucket>()

    /** 被丢弃的字节数与包数，仅用于 UI 展示"有多严格" */
    @Volatile
    var droppedPackets = 0L
        private set
    @Volatile
    var droppedBytes = 0L
        private set

    private val NO_LIMIT = Cfg(-1, -1, false, false, false)

    fun refresh() {
        val gUp = kbpsToBytes(Prefs.globalUpKbps)
        val gDown = kbpsToBytes(Prefs.globalDownKbps)
        cfgMap[GLOBAL] = Cfg(gUp, gDown, Prefs.strictGlobal, false)

        val rules = RuleStore.all()
        val alive = HashSet<Int>()
        for (r in rules) {
            alive.add(r.uid)
            cfgMap[r.uid] = Cfg(
                upBytes = kbpsToBytes(r.upKbps),
                downBytes = kbpsToBytes(r.downKbps),
                strict = r.strict,
                blocked = r.blocked,
                blockUdp = r.blockUdp
            )
        }
        // 清理已删除规则的桶
        val itCfg = cfgMap.keys.iterator()
        while (itCfg.hasNext()) {
            val k = itCfg.next()
            if (k != GLOBAL && !alive.contains(k)) itCfg.remove()
        }
        val itUp = upBuckets.keys.iterator()
        while (itUp.hasNext()) {
            val k = itUp.next()
            if (k != GLOBAL && !alive.contains(k)) itUp.remove()
        }
        val itDown = downBuckets.keys.iterator()
        while (itDown.hasNext()) {
            val k = itDown.next()
            if (k != GLOBAL && !alive.contains(k)) itDown.remove()
        }
    }

    private fun cfg(uid: Int): Cfg = cfgMap[uid] ?: NO_LIMIT

    private fun bucket(map: ConcurrentHashMap<Int, TokenBucket>, uid: Int, bytes: Long, strict: Boolean): TokenBucket {
        var b = map[uid]
        if (b == null) {
            b = TokenBucket(bytes)
            map.putIfAbsent(uid, b)
            b = map[uid]!!
        }
        b.configure(bytes, strict)
        return b
    }

    /**
     * 上行准入判定。返回 false 表示这个包必须丢掉（禁止 / 严格模式超配额）。
     * 非严格模式下会阻塞排队，直到拿到配额为止 —— 这正是"限速但不丢数据"的做法。
     */
    fun consumeUp(uid: Int, bytes: Int) {
        val g = cfg(GLOBAL)
        if (g.blocked) {
            drop(bytes)
            return
        }
        if (g.hasUpLimit) {
            if (g.upBytes == 0L) {
                drop(bytes)
                return
            }
            val b = bucket(upBuckets, GLOBAL, g.upBytes, g.strict)
            if (g.strict) {
                if (!b.tryAcquire(bytes)) {
                    drop(bytes)
                    return
                }
            } else {
                b.acquire(bytes)
            }
        }

        val c = cfg(uid)
        if (c.blocked) {
            drop(bytes)
            return
        }
        if (c.hasUpLimit) {
            if (c.upBytes == 0L) {
                drop(bytes)
                return
            }
            val b = bucket(upBuckets, uid, c.upBytes, c.strict)
            if (c.strict) {
                if (!b.tryAcquire(bytes)) drop(bytes)
            } else {
                b.acquire(bytes)
            }
        }
    }

    /** 下行准入判定，语义同 [consumeUp] */
    fun consumeDown(uid: Int, bytes: Int) {
        val g = cfg(GLOBAL)
        if (g.hasDownLimit) {
            if (g.downBytes == 0L) {
                drop(bytes)
                return
            }
            val b = bucket(downBuckets, GLOBAL, g.downBytes, g.strict)
            if (g.strict && !b.tryAcquire(bytes)) {
                drop(bytes)
                return
            } else if (!g.strict) {
                b.acquire(bytes)
            }
        }

        val c = cfg(uid)
        if (c.blocked) {
            drop(bytes)
            return
        }
        if (c.hasDownLimit) {
            if (c.downBytes == 0L) {
                drop(bytes)
                return
            }
            val b = bucket(downBuckets, uid, c.downBytes, c.strict)
            if (c.strict && !b.tryAcquire(bytes)) {
                drop(bytes)
            } else if (!c.strict) {
                b.acquire(bytes)
            }
        }
    }

    /** 该 uid 是否被彻底断网 */
    fun isBlocked(uid: Int): Boolean = cfg(uid).blocked

    /** 该 uid 的 UDP 是否被整体丢弃（掐 P2P/PCDN） */
    fun isUdpBlocked(uid: Int): Boolean = cfg(uid).blockUdp

    private fun drop(bytes: Int) {
        droppedPackets++
        droppedBytes += bytes
    }

    fun kbpsToBytes(kbps: Int): Long = if (kbps < 0) -1L else kbps.toLong() * 1024L

    fun reset() {
        upBuckets.clear()
        downBuckets.clear()
        droppedPackets = 0
        droppedBytes = 0
        refresh()
    }
}
