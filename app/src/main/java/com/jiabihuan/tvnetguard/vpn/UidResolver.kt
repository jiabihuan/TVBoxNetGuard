package com.jiabihuan.tvnetguard.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.math.max

/**
 * 把一条连接归属到具体 uid（也就是具体 App），这是"按应用限速"的前提。
 *
 * 两条路径互补：
 * 1. Android 10+ 用 ConnectivityManager.getConnectionOwnerUid()，它是同步 Binder 调用，
 *    所以结果必须按连接缓存（我们正是这么做的：一个会话只解析一次）。
 * 2. Android 9 及以下 /proc/net/{tcp,udp}{,6} 里的 uid 列。注意坑：
 *    - 表头把 tx_queue:rx_queue 拆成两列，数据行却只有一列，所以 uid 固定在第 7 列（0 基）
 *    - IPv4 连接经常登记在 tcp6（::ffff 映射）里，必须两个文件都查
 *    - Android 10 开始 /proc/net 对普通应用不可读，这条路径在那之上基本失效
 */
class UidResolver(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var procCache: Map<Int, Int> = emptyMap()   // port -> uid
    @Volatile
    private var procCacheAt = 0L
    private val ttlMs = 3000L

    /**
     * @param protocol 6 = TCP, 17 = UDP
     * @return uid，失败返回 -1
     */
    fun resolve(protocol: Int, localIp: Int, localPort: Int, remoteIp: Int, remotePort: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uid = queryOwnerUid(protocol, localIp, localPort, remoteIp, remotePort)
            if (uid >= 0) return uid
        }
        return procLookup(protocol, localPort)
    }

    private fun queryOwnerUid(
        protocol: Int,
        localIp: Int,
        localPort: Int,
        remoteIp: Int,
        remotePort: Int
    ): Int {
        val manager = cm ?: return -1
        return try {
            val local = InetSocketAddress(toAddress(localIp), localPort)
            val remote = InetSocketAddress(toAddress(remoteIp), remotePort)
            manager.getConnectionOwnerUid(protocol, local, remote)
        } catch (t: Throwable) {
            -1
        }
    }

    private fun toAddress(ip: Int): InetAddress {
        val b = ByteArray(4)
        b[0] = ((ip ushr 24) and 0xFF).toByte()
        b[1] = ((ip ushr 16) and 0xFF).toByte()
        b[2] = ((ip ushr 8) and 0xFF).toByte()
        b[3] = (ip and 0xFF).toByte()
        return InetAddress.getByAddress(b)
    }

    private fun procLookup(protocol: Int, localPort: Int): Int {
        val now = System.currentTimeMillis()
        val cache = procCache
        if (now - procCacheAt > ttlMs || cache.isEmpty()) {
            synchronized(this) {
                if (now - procCacheAt > ttlMs || procCache.isEmpty()) {
                    procCache = rebuildProcCache(protocol)
                    procCacheAt = System.currentTimeMillis()
                }
            }
            return procCache[localPort] ?: -1
        }
        return cache[localPort] ?: -1
    }

    private fun rebuildProcCache(protocol: Int): Map<Int, Int> {
        val map = HashMap<Int, Int>(256)
        val v4 = if (protocol == Packets.IPPROTO_TCP) "/proc/net/tcp" else "/proc/net/udp"
        val v6 = if (protocol == Packets.IPPROTO_TCP) "/proc/net/tcp6" else "/proc/net/udp6"
        // Android 上 IPv4 连接常被登记在 tcp6，所以 v6 先查、后查 v4（v4 结果覆盖，更精确）
        parseProc(v6, map)
        parseProc(v4, map)
        return map
    }

    private fun parseProc(path: String, out: HashMap<Int, Int>) {
        try {
            val f = File(path)
            if (!f.exists()) return
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (index == 0) return@forEachIndexed // 跳过表头
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 10) return@forEachIndexed
                    val local = parts[1]
                    val colon = local.lastIndexOf(':')
                    if (colon < 0) return@forEachIndexed
                    val port = local.substring(colon + 1).toIntOrNull(16) ?: return@forEachIndexed
                    val uid = readUidColumn(parts)
                    if (uid >= 0) out[port] = uid
                }
            }
        } catch (t: Throwable) {
            // /proc/net 在 Android 10+ 基本读不到，静默降级到 API 29 路径
        }
    }

    /** uid 固定在第 7 列；个别内核偏移不同，向后兼容地试两列 */
    private fun readUidColumn(parts: List<String>): Int {
        for (i in 7..8) {
            val v = parts.getOrNull(i)?.toIntOrNull()
            if (v != null && v >= 0 && v <= 99999) return v
        }
        return -1
    }

    companion object {
        fun isValid(uid: Int): Boolean = uid >= 0 && uid <= 99_999
    }
}
