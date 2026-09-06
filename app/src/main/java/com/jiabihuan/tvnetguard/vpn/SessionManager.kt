package com.jiabihuan.tvnetguard.vpn

import java.util.concurrent.ConcurrentHashMap

/**
 * 会话表：把 tun 里的每个包按五元组归到具体连接上。
 * 同时负责过期回收，避免盒子长时间运行后 fd 与内存泄漏。
 */
internal class SessionManager(
    private val vpn: GuardVpnService,
    private val uidResolver: UidResolver,
    private val mtu: Int
) {

    private val tcp = ConcurrentHashMap<String, TcpSession>()
    private val udp = ConcurrentHashMap<String, UdpSession>()

    @Volatile
    var peakSessions = 0
        private set

    fun handleTcp(pkt: Ipv4Packet) {
        val key = key(pkt.srcIp, pkt.srcPort, pkt.dstIp, pkt.dstPort)
        var s = tcp[key]
        if (s == null) {
            s = TcpSession(key, pkt.srcIp, pkt.srcPort, pkt.dstIp, pkt.dstPort, mtu, vpn, uidResolver)
            val prev = tcp.putIfAbsent(key, s)
            if (prev != null) s = prev
        }
        s.handleFromTun(pkt)
        if (tcp.size > peakSessions) peakSessions = tcp.size
    }

    fun handleUdp(pkt: Ipv4Packet) {
        val key = key(pkt.srcIp, pkt.srcPort, pkt.dstIp, pkt.dstPort)
        var s = udp[key]
        if (s == null) {
            s = UdpSession(key, pkt.srcIp, pkt.srcPort, pkt.dstIp, pkt.dstPort, vpn, uidResolver)
            val prev = udp.putIfAbsent(key, s)
            if (prev != null) s = prev
        }
        s.handleFromTun(pkt)
    }

    /** 规则变更后，把已被"彻底断网"的应用现有连接立即掐断 */
    fun killBlocked() {
        val now = System.currentTimeMillis()
        sweepTcp { s -> s.isExpired(now) || (s.uid >= 0 && Limiter.isBlocked(s.uid)) }
        sweepUdp { s -> s.isExpired(now) || (s.uid >= 0 && Limiter.isBlocked(s.uid)) }
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        sweepTcp { s -> s.isExpired(now) || s.state == TcpClosed }
        sweepUdp { s -> s.isExpired(now) }
    }

    private fun sweepTcp(dead: (TcpSession) -> Boolean) {
        val it = tcp.entries.iterator()
        while (it.hasNext()) {
            val s = it.next().value
            if (dead(s)) {
                s.close()
                it.remove()
            }
        }
    }

    private fun sweepUdp(dead: (UdpSession) -> Boolean) {
        val it = udp.entries.iterator()
        while (it.hasNext()) {
            val s = it.next().value
            if (dead(s)) {
                s.close()
                it.remove()
            }
        }
    }

    fun closeAll() {
        tcp.values.forEach { it.close() }
        udp.values.forEach { it.close() }
        tcp.clear()
        udp.clear()
    }

    fun activeCount(): Int = tcp.size + udp.size

    private fun key(a: Int, ap: Int, b: Int, bp: Int): String = "$a:$ap-$b:$bp"

    private companion object {
        const val TcpClosed = 4
    }
}
