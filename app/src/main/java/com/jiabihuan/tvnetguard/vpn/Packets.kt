package com.jiabihuan.tvnetguard.vpn

import java.nio.ByteBuffer

/**
 * IPv4 / TCP / UDP 报文的解析与构造。
 * 全部走纯 Java 数组操作，无 JNI，方便在任意盒子上跑。
 */
object Packets {

    const val IPPROTO_ICMP = 1
    const val IPPROTO_TCP = 6
    const val IPPROTO_UDP = 17

    // TCP flags
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    const val IP_HEADER_LEN = 20
    const val TCP_HEADER_LEN = 20
    const val UDP_HEADER_LEN = 8

    /** 16 位反码求和（RFC 1071）。initial 用于带上伪首部。 */
    fun sum(data: ByteArray, offset: Int, length: Int, initial: Long = 0L): Int {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.toInt()
    }

    fun ipChecksum(data: ByteArray): Int {
        val ihl = (data[0].toInt() and 0x0F) * 4
        val s = sum(data, 0, ihl)
        return (s.inv()) and 0xFFFF
    }

    private fun pseudoSum(srcIp: Int, dstIp: Int, protocol: Int, len: Int): Long {
        var s = 0L
        s += (srcIp ushr 16) and 0xFFFF
        s += srcIp and 0xFFFF
        s += (dstIp ushr 16) and 0xFFFF
        s += dstIp and 0xFFFF
        s += protocol.toLong()
        s += len.toLong()
        return s
    }

    /**
     * 构造一个 IPv4 + TCP 报文。checksum 会就地算好。
     * @param mss 大于 0 时会在 SYN 包里带上 MSS 选项（必须做 MSS 钳制，否则大包会被路径 MTU 黑洞吞掉）
     */
    fun buildTcp(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray? = null,
        payloadOffset: Int = 0,
        payloadLen: Int = 0,
        mss: Int = 0,
        ident: Int = 0
    ): ByteArray {
        val optLen = if (mss > 0) 4 else 0
        val tcpLen = TCP_HEADER_LEN + optLen + payloadLen
        val total = IP_HEADER_LEN + tcpLen
        val out = ByteArray(total)
        val b = ByteBuffer.wrap(out)

        // ---- IPv4 ----
        b.put(0, 0x45.toByte())
        b.putShort(2, total.toShort())
        b.putShort(4, ident.toShort())
        b.putShort(6, 0x4000.toShort()) // Don't Fragment
        b.put(8, 64.toByte())           // TTL
        b.put(9, IPPROTO_TCP.toByte())
        b.putShort(10, 0)               // checksum 占位
        b.putInt(12, srcIp)
        b.putInt(16, dstIp)

        // ---- TCP ----
        var o = IP_HEADER_LEN
        b.putShort(o, srcPort.toShort())
        b.putShort(o + 2, dstPort.toShort())
        b.putInt(o + 4, seq.toInt())
        b.putInt(o + 8, ack.toInt())
        b.put(o + 12, ((tcpHeaderWords(optLen)) shl 4).toByte())
        b.put(o + 13, flags.toByte())
        b.putShort(o + 14, window.coerceAtMost(0xFFFF).toShort())
        b.putShort(o + 16, 0) // checksum 占位
        b.putShort(o + 18, 0) // urgent
        o += TCP_HEADER_LEN
        if (mss > 0) {
            b.put(o, 2.toByte())     // kind = MSS
            b.put(o + 1, 4.toByte()) // length
            b.putShort(o + 2, mss.toShort())
            o += 4
        }
        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, payloadOffset, out, o, payloadLen)
        }

        // checksum
        out[10] = 0; out[11] = 0
        val ipc = (sum(out, 0, IP_HEADER_LEN).inv()) and 0xFFFF
        b.putShort(10, ipc.toShort())

        out[IP_HEADER_LEN + 16] = 0
        out[IP_HEADER_LEN + 17] = 0
        val tc = ((sum(out, IP_HEADER_LEN, tcpLen, pseudoSum(srcIp, dstIp, IPPROTO_TCP, tcpLen))).inv()) and 0xFFFF
        b.putShort(IP_HEADER_LEN + 16, tc.toShort())
        return out
    }

    private fun tcpHeaderWords(optLen: Int): Int = (TCP_HEADER_LEN + optLen) / 4

    /** 构造 IPv4 + UDP 报文。IPv4 下 UDP 校验和填 0 是合法的，省一次计算。 */
    fun buildUdp(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLen: Int,
        ident: Int = 0
    ): ByteArray {
        val udpLen = UDP_HEADER_LEN + payloadLen
        val total = IP_HEADER_LEN + udpLen
        val out = ByteArray(total)
        val b = ByteBuffer.wrap(out)

        b.put(0, 0x45.toByte())
        b.putShort(2, total.toShort())
        b.putShort(4, ident.toShort())
        b.putShort(6, 0x4000.toShort())
        b.put(8, 64.toByte())
        b.put(9, IPPROTO_UDP.toByte())
        b.putShort(10, 0)
        b.putInt(12, srcIp)
        b.putInt(16, dstIp)

        var o = IP_HEADER_LEN
        b.putShort(o, srcPort.toShort())
        b.putShort(o + 2, dstPort.toShort())
        b.putShort(o + 4, udpLen.toShort())
        b.putShort(o + 6, 0)
        o += UDP_HEADER_LEN
        if (payloadLen > 0) System.arraycopy(payload, payloadOffset, out, o, payloadLen)

        val ipc = (sum(out, 0, IP_HEADER_LEN).inv()) and 0xFFFF
        b.putShort(10, ipc.toShort())
        return out
    }

    fun ipToString(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    fun intToAddress(ip: Int): java.net.InetAddress {
        val b = ByteArray(4)
        b[0] = ((ip ushr 24) and 0xFF).toByte()
        b[1] = ((ip ushr 16) and 0xFF).toByte()
        b[2] = ((ip ushr 8) and 0xFF).toByte()
        b[3] = (ip and 0xFF).toByte()
        return java.net.InetAddress.getByAddress(b)
    }
}

/** 已解析的入站（设备 -> 网络）IPv4 报文视图 */
class Ipv4Packet(val raw: ByteArray, val len: Int) {

    val version: Int get() = (raw[0].toInt() shr 4) and 0x0F
    val ihl: Int get() = (raw[0].toInt() and 0x0F) * 4
    val protocol: Int get() = raw[9].toInt() and 0xFF
    val totalLength: Int get() = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
    val srcIp: Int get() = ByteBuffer.wrap(raw).getInt(12)
    val dstIp: Int get() = ByteBuffer.wrap(raw).getInt(16)
    /** 传输层起始偏移（IP 头可能带选项） */
    val transportOffset: Int get() = ihl

    val srcPort: Int
        get() = ((raw[transportOffset].toInt() and 0xFF) shl 8) or (raw[transportOffset + 1].toInt() and 0xFF)

    val dstPort: Int
        get() = ((raw[transportOffset + 2].toInt() and 0xFF) shl 8) or (raw[transportOffset + 3].toInt() and 0xFF)

    // ---- TCP ----
    val tcpSeq: Long
        get() = (ByteBuffer.wrap(raw).getInt(transportOffset + 4)).toLong() and 0xFFFFFFFFL
    val tcpAck: Long
        get() = (ByteBuffer.wrap(raw).getInt(transportOffset + 8)).toLong() and 0xFFFFFFFFL
    val tcpFlags: Int get() = raw[transportOffset + 13].toInt() and 0xFF
    val tcpWindow: Int
        get() = ((raw[transportOffset + 14].toInt() and 0xFF) shl 8) or (raw[transportOffset + 15].toInt() and 0xFF)

    /** TCP 负载起始偏移 */
    fun tcpPayloadOffset(): Int = transportOffset + ((raw[transportOffset + 12].toInt() and 0xFF) shr 4) * 4

    fun tcpPayloadLength(): Int = (totalLength - tcpPayloadOffset()).coerceAtLeast(0)

    val hasSyn: Boolean get() = (tcpFlags and Packets.SYN) != 0
    val hasAck: Boolean get() = (tcpFlags and Packets.ACK) != 0
    val hasRst: Boolean get() = (tcpFlags and Packets.RST) != 0
    val hasFin: Boolean get() = (tcpFlags and Packets.FIN) != 0
    val hasPsh: Boolean get() = (tcpFlags and Packets.PSH) != 0

    // ---- UDP ----
    fun udpPayloadOffset(): Int = transportOffset + Packets.UDP_HEADER_LEN

    fun udpPayloadLength(): Int = (totalLength - udpPayloadOffset()).coerceAtLeast(0)

    fun isValid(minLen: Int): Boolean =
        version == 4 && ihl >= Packets.IP_HEADER_LEN && totalLength in minLen..len
}
