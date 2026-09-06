package com.jiabihuan.tvnetguard.vpn

import com.jiabihuan.tvnetguard.util.RootShell
import java.io.File

/**
 * root 模式下的按-uid 流量统计来源。
 *
 * 快照 Pair 约定：**first = rx（下行）累计字节，second = tx（上行）累计字节**，
 * 与 [com.jiabihuan.tvnetguard.data.StatsStore.setSnapshot] 保持一致。
 *
 * 三层来源，逐级回退：
 * 1. app 进程直接读 `/proc/net/xt_qtaguid/stats` —— Android 7/8 老内核可用；
 * 2. 走常驻 root 会话 `cat` 同一文件 —— Android 9+ 的 SELinux 会拦 app 读 proc_net，
 *    但拦不住 root；这是 root 盒子上最准的按-uid 上下行来源；
 * 3. iptables 统计链 `tng_stat` —— 仅上行（owner 匹配只在 OUTPUT 有效），
 *    作为 qtaguid 彻底不存在时的兜底。
 *
 * 整机速率不在这里：整机直接用 TrafficStats（见 RootEngineService），永远准。
 */
object RootStats {

    private const val QTAGUID = "/proc/net/xt_qtaguid/stats"
    const val STAT_CHAIN = "tng_stat"

    fun sample(): Map<Int, Pair<Long, Long>> {
        qtaguidDirect()?.let { return it }
        qtaguidViaRoot()?.let { return it }
        return iptablesStat() ?: emptyMap()
    }

    fun hasQtaguid(): Boolean = File(QTAGUID).exists()

    /**
     * 解析 qtaguid 文本。格式：
     * idx iface acct_tag_hex uid_tag_int cnt_set rx_bytes rx_packets tx_bytes tx_packets ...
     * 同一 uid 会因 iface / tag 不同出现多行，全部累加。
     */
    private fun parseQtaguid(text: String): Map<Int, Pair<Long, Long>>? {
        val map = HashMap<Int, Pair<Long, Long>>()
        try {
            text.lineSequence().forEach { line ->
                val c = line.trim().split(Regex("\\s+"))
                if (c.size < 8) return@forEach
                if (c[0] == "idx") return@forEach // 表头行
                val uid = c[3].toIntOrNull() ?: return@forEach
                if (uid < 0) return@forEach
                val rx = c[5].toLongOrNull() ?: 0L
                val tx = c[7].toLongOrNull() ?: 0L
                val cur = map[uid] ?: (0L to 0L)
                map[uid] = (cur.first + rx) to (cur.second + tx)
            }
        } catch (t: Throwable) {
            return null
        }
        return if (map.isEmpty()) null else map
    }

    private fun qtaguidDirect(): Map<Int, Pair<Long, Long>>? {
        val f = File(QTAGUID)
        if (!f.canRead()) return null
        return try {
            parseQtaguid(f.readText())
        } catch (t: Throwable) {
            null
        }
    }

    // root 读 qtaguid：2 秒缓存，既省开销又不丢秒级速率
    @Volatile
    private var lastRootQAt = 0L
    @Volatile
    private var cachedRootQ: Map<Int, Pair<Long, Long>>? = null

    private fun qtaguidViaRoot(): Map<Int, Pair<Long, Long>>? {
        val now = System.currentTimeMillis()
        val cached = cachedRootQ
        if (cached != null && now - lastRootQAt < 2000) return cached
        val m = RootShell.runSession("cat $QTAGUID", 3000)?.let { parseQtaguid(it) }
        if (m != null) {
            cachedRootQ = m
            lastRootQAt = now
        }
        return m
    }

    // iptables 统计兜底：仅上行，5 秒缓存（走常驻会话，不弹 root 授权）
    @Volatile
    private var lastIptSampleAt = 0L
    @Volatile
    private var cachedIpt: Map<Int, Pair<Long, Long>>? = null

    private fun iptablesStat(): Map<Int, Pair<Long, Long>>? {
        val now = System.currentTimeMillis()
        val cached = cachedIpt
        if (cached != null && now - lastIptSampleAt < 5000) return cached
        if (!RootShell.hasRoot()) return null
        val out = RootShell.runSession("iptables -t mangle -L $STAT_CHAIN -v -x -n", 4000)
            ?: return null
        val map = HashMap<Int, Pair<Long, Long>>()
        out.lines().forEach { line ->
            if (!line.contains("owner UID match")) return@forEach
            val c = line.trim().split(Regex("\\s+"))
            if (c.size < 2) return@forEach
            val bytes = c[1].toLongOrNull() ?: return@forEach
            val uid = line.substringAfter("owner UID match ").trim().toIntOrNull() ?: return@forEach
            // (rx=0, tx=字节)：这条链只能看到上行
            if (uid >= 0) map[uid] = 0L to bytes
        }
        cachedIpt = if (map.isEmpty()) null else map
        lastIptSampleAt = now
        return cachedIpt
    }
}
