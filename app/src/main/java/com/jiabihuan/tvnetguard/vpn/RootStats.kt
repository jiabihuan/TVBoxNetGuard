package com.jiabihuan.tvnetguard.vpn

import com.jiabihuan.tvnetguard.util.RootShell
import java.io.File

/**
 * root 模式下的流量统计来源。
 *
 * 统计不需要 VPN 接管，直接从内核读绝对累计值：
 * 1. `/proc/net/xt_qtaguid/stats` —— Android 内核的流量账本，按 uid 给出上下行字节，
 *    最准也最常用（Android 7~12 几乎都有）；
 * 2. 兜底：读取我们自己建的 iptables 统计链 `tng_stat` 的字节计数
 *    （仅上行，因为 owner 匹配只在 OUTPUT 有效，下行在内核里无法按 uid 归因）。
 */
object RootStats {

    private const val QTAGUID = "/proc/net/xt_qtaguid/stats"
    const val STAT_CHAIN = "tng_stat"

    fun sample(): Map<Int, Pair<Long, Long>> {
        qtaguid()?.let { return it }
        return iptablesStat() ?: emptyMap()
    }

    fun hasQtaguid(): Boolean = File(QTAGUID).exists()

    private fun qtaguid(): Map<Int, Pair<Long, Long>>? {
        val f = File(QTAGUID)
        if (!f.canRead()) return null
        val map = HashMap<Int, Pair<Long, Long>>()
        try {
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val c = line.split(Regex("\\s+"))
                    if (c.size < 8) return@forEach
                    val uid = c[3].toIntOrNull() ?: return@forEach
                    val rx = c[5].toLongOrNull() ?: 0L
                    val tx = c[7].toLongOrNull() ?: 0L
                    val cur = map[uid] ?: (0L to 0L)
                    map[uid] = (cur.first + rx) to (cur.second + tx)
                }
            }
        } catch (t: Throwable) {
            return null
        }
        return if (map.isEmpty()) null else map
    }

    private fun iptablesStat(): Map<Int, Pair<Long, Long>>? {
        if (!RootShell.hasRoot()) return null
        val r = RootShell.run(listOf("iptables -t mangle -L $STAT_CHAIN -v -x -n 2>/dev/null"))
        if (!r.ok) return null
        val map = HashMap<Int, Pair<Long, Long>>()
        r.out.lines().forEach { line ->
            if (!line.contains("owner UID match")) return@forEach
            val c = line.trim().split(Regex("\\s+"))
            if (c.size < 2) return@forEach
            val bytes = c[1].toLongOrNull() ?: return@forEach
            val uid = line.substringAfter("owner UID match ").trim().toIntOrNull() ?: return@forEach
            if (uid >= 0) map[uid] = (0L to bytes)
        }
        return if (map.isEmpty()) null else map
    }
}
