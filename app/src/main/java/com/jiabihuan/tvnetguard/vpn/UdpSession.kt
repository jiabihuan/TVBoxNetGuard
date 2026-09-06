package com.jiabihuan.tvnetguard.vpn

import com.jiabihuan.tvnetguard.data.StatsStore
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 一条 UDP 流的 NAT 中转。
 *
 * UDP 没有重传保序机制，排队延迟发送只会让数据过期，
 * 所以对 UDP 一律按"拿不到配额就丢"处理 —— 这也正符合"严格"的诉求。
 */
internal class UdpSession(
    val key: String,
    private val clientIp: Int,
    private val clientPort: Int,
    private val remoteIp: Int,
    private val remotePort: Int,
    private val vpn: GuardVpnService,
    private val uidResolver: UidResolver
) {

    private companion object {
        const val IDLE_TIMEOUT = 120_000L
        const val BUF_SIZE = 2048
    }

    @Volatile
    var state = 0

    @Volatile
    var uid = -1
        private set

    @Volatile
    var lastActive = System.currentTimeMillis()
        private set

    @Volatile
    private var closed = false

    private val serial = SerialExecutor(VpnExecutors.upstream)
    private var socket: DatagramSocket? = null
    private val initLock = Any()

    fun handleFromTun(pkt: Ipv4Packet) {
        lastActive = System.currentTimeMillis()
        if (closed) return
        ensureSocket() ?: return
        val raw = if (pkt.raw.size == pkt.len) pkt.raw else pkt.raw.copyOf(pkt.len)
        serial.execute { send(Ipv4Packet(raw, raw.size)) }
    }

    private fun ensureSocket(): DatagramSocket? {
        socket?.let { return it }
        synchronized(initLock) {
            socket?.let { return it }
            uid = uidResolver.resolve(Packets.IPPROTO_UDP, clientIp, clientPort, remoteIp, remotePort)
            if (uid >= 0 && Limiter.isBlocked(uid)) {
                closed = true
                return null
            }
            val s = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    vpn.protect(this)
                    connect(Packets.intToAddress(remoteIp), remotePort)
                }
            } catch (t: Throwable) {
                VpnLog.w("udp socket failed: ${t.message}")
                return null
            }
            socket = s
            startReader(s)
            return s
        }
    }

    private fun send(pkt: Ipv4Packet) {
        val s = socket ?: return
        val off = pkt.udpPayloadOffset()
        val len = pkt.udpPayloadLength()
        if (len <= 0) return
        if (uid >= 0 && Limiter.isBlocked(uid)) return

        val bytes = pkt.totalLength
        Limiter.consumeUp(uid, bytes)
        StatsStore.addTx(uid, bytes.toLong())
        try {
            s.send(DatagramPacket(pkt.raw, off, len))
        } catch (t: Throwable) {
            close()
        }
    }

    private fun startReader(s: DatagramSocket) {
        VpnExecutors.downstream.execute {
            val buf = ByteArray(BUF_SIZE)
            try {
                while (!closed) {
                    val p = DatagramPacket(buf, BUF_SIZE)
                    s.receive(p)
                    val n = p.length
                    if (n <= 0) continue
                    lastActive = System.currentTimeMillis()
                    val bytes = n + 28 // IP + UDP 头
                    Limiter.consumeDown(uid, bytes)
                    StatsStore.addRx(uid, bytes.toLong())
                    val out = Packets.buildUdp(
                        remoteIp, clientIp, remotePort, clientPort, buf, 0, n
                    )
                    vpn.writeToTun(out)
                }
            } catch (t: Throwable) {
                // socket 关闭时会抛，正常退出路径
            }
        }
    }

    fun isExpired(now: Long): Boolean = now - lastActive > IDLE_TIMEOUT

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }
}
