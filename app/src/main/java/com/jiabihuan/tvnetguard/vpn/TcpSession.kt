package com.jiabihuan.tvnetguard.vpn

import com.jiabihuan.tvnetguard.data.StatsStore
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一条 TCP 连接的用户态中转。
 *
 * 核心设计（这几条是「连得上但没速度 / 大流量卡死」的高频坑，逐一规避）：
 * 1. 连接建立（connect）是阻塞的，放到 connect 线程池，绝不堵住 tun 读循环；
 *    建立期间到达的包进 pending 队列，建立完成后顺序 flush。
 * 2. 上行发包前先过令牌桶：非严格模式阻塞排队（速率平滑，不触发 RTO 退避），
 *    严格模式拿不到配额直接丢包（绝不突发）。排队发生在串行执行器里，
 *    只阻塞自己这条连接，不影响别的 App。
 * 3. 接收窗口用来做背压：队列积压时把窗口通告小，让 App 自己慢下来，而不是无限堆内存。
 * 4. 校验和在 [Packets] 里统一重算（改了 seq/ack/MSS 后必须重算，否则对端直接丢包）。
 * 5. 所有退出路径都 close socket，防止 fd 泄漏把盒子拖死。
 */
internal class TcpSession(
    val key: String,
    private val clientIp: Int,
    private val clientPort: Int,
    private val remoteIp: Int,
    private val remotePort: Int,
    private val mtu: Int,
    private val vpn: GuardVpnService,
    private val uidResolver: UidResolver
) {

    private companion object {
        const val NEW = 0
        const val CONNECTING = 1
        const val ESTABLISHED = 2
        const val CLOSING = 3
        const val CLOSED = 4

        const val CONNECT_TIMEOUT = 15_000
        const val IDLE_TIMEOUT = 5 * 60 * 1000L
        const val MAX_PENDING = 64
        const val MAX_REORDER = 64
        val seq = AtomicInteger(0)
    }

    @Volatile
    var state = NEW
        private set

    @Volatile
    var uid = -1
        private set

    @Volatile
    var lastActive = System.currentTimeMillis()
        private set

    private val lock = Any()
    private val pending = LinkedBlockingQueue<ByteArray>(MAX_PENDING)
    private val reorder = HashMap<Long, ByteArray>()
    private val serial = SerialExecutor(VpnExecutors.upstream)

    private var socket: Socket? = null
    private var out: java.io.OutputStream? = null

    // 序列号状态（受 lock 保护）
    private var clientIsn = 0L
    private var ourIsn = 0L
    private var rcvNxt = 0L   // 期望收到的下一个 App 字节
    private var sndNxt = 0L   // 下一个要发给 App 的字节
    private var ourFinSent = false

    /** tun 侧 MSS：必须钳制，否则大包会在出口被丢弃或触发 PMTU 黑洞 */
    private val mss: Int get() = mtu - 40

    fun handleFromTun(pkt: Ipv4Packet) {
        lastActive = System.currentTimeMillis()
        when (state) {
            NEW -> {
                if (!pkt.hasSyn) return
                synchronized(lock) {
                    clientIsn = pkt.tcpSeq
                    ourIsn = (System.nanoTime() xor (key.hashCode().toLong() shl 32)) and 0xFFFFFFFFL
                    rcvNxt = seqAdd(clientIsn, 1)
                    sndNxt = ourIsn
                }
                state = CONNECTING
                pending.offer(copyOf(pkt))
                VpnExecutors.connect.execute { doConnect() }
            }
            CONNECTING -> {
                if (!pending.offer(copyOf(pkt))) {
                    // 队列满：丢包让 App 重传，比无限堆积内存好
                    StatsStore.addTx(uid, 0)
                }
            }
            ESTABLISHED, CLOSING -> serial.execute { process(pkt) }
            CLOSED -> Unit
        }
    }

    private fun doConnect() {
        uid = uidResolver.resolve(Packets.IPPROTO_TCP, clientIp, clientPort, remoteIp, remotePort)

        // 彻底断网的应用：直接 RST，连门都不给它开
        if (uid >= 0 && Limiter.isBlocked(uid)) {
            sendRst()
            close()
            return
        }

        val sock = Socket()
        try {
            if (!vpn.protect(sock)) {
                close()
                return
            }
            sock.tcpNoDelay = true
            sock.soTimeout = 0
            sock.connect(InetSocketAddress(Packets.intToAddress(remoteIp), remotePort), CONNECT_TIMEOUT)
        } catch (t: Throwable) {
            runCatching { sock.close() }
            sendRst()
            close()
            return
        }

        socket = sock
        out = sock.getOutputStream()
        state = ESTABLISHED

        sendFlags(Packets.SYN or Packets.ACK, mss = mss, advance = 1)
        startReader()

        // flush 建立期间排队的包，顺序不变
        while (true) {
            val raw = pending.poll() ?: break
            val p = Ipv4Packet(raw, raw.size)
            if (p.isValid(20)) process(p)
        }
        VpnLog.d("tcp connect $key uid=$uid")
    }

    private fun process(pkt: Ipv4Packet) {
        if (state == CLOSED) return
        lastActive = System.currentTimeMillis()

        if (pkt.hasRst) {
            close()
            return
        }

        val seqNum = pkt.tcpSeq

        // ---- 乱序 / 重复包处理 ----
        val expected = synchronized(lock) { rcvNxt }
        if (seqNum != expected) {
            if (isBefore(seqNum, expected)) {
                // 重复的旧包：回一个重复 ACK，帮对端判断是否需要快重传
                sendFlags(Packets.ACK)
                return
            }
            synchronized(lock) {
                if (reorder.size > MAX_REORDER) reorder.clear()
                reorder[seqNum] = copyOf(pkt)
            }
            sendFlags(Packets.ACK)
            return
        }

        handleOrdered(pkt)
        drainReorder()
    }

    /** 已确认按序到达的数据包处理主体（乱序包回填时也会走这里） */
    private fun handleOrdered(pkt: Ipv4Packet) {
        if (state == CLOSED) return
        val flags = pkt.tcpFlags
        val payloadLen = pkt.tcpPayloadLength()
        if (uid < 0) {
            uid = uidResolver.resolve(Packets.IPPROTO_TCP, clientIp, clientPort, remoteIp, remotePort)
        }
        if (uid >= 0 && Limiter.isBlocked(uid)) {
            close()
            return
        }

        var fin = false
        if (payloadLen > 0) {
            val off = pkt.tcpPayloadOffset()
            // 上行限速：按 IP 层实际字节数计（含头），这样"限速 10KB/s"就是线路上真的 10KB/s
            val bytes = pkt.totalLength
            val before = System.currentTimeMillis()
            Limiter.consumeUp(uid, bytes)
            val waited = System.currentTimeMillis() - before
            if (waited > 50) VpnLog.d("throttle $key ${bytes}B waited ${waited}ms")
            StatsStore.addTx(uid, bytes.toLong())
            try {
                out?.write(pkt.raw, off, payloadLen)
                out?.flush()
            } catch (t: Throwable) {
                close()
                return
            }
        }

        synchronized(lock) {
            rcvNxt = seqAdd(rcvNxt, payloadLen.toLong())
            if ((flags and Packets.FIN) != 0) {
                rcvNxt = seqAdd(rcvNxt, 1)
                fin = true
            }
        }

        if (fin) {
            runCatching { socket?.shutdownOutput() }
            sendFlags(Packets.ACK, advance = 0)
            if (!ourFinSent) {
                sendFlags(Packets.FIN or Packets.ACK, advance = 1)
                ourFinSent = true
            }
            state = CLOSING
            // 等待 App 回 ACK，随后由 cleanup 回收
            return
        }

        // CLOSING 状态下收到纯 ACK，说明四次挥手收尾，连接可以回收
        if (state == CLOSING && (flags and Packets.ACK) != 0 && payloadLen == 0) {
            close()
            return
        }

        sendFlags(Packets.ACK)

        // 把重排队列里能接上的包继续处理（简化：交给下一次数据包驱动）
        drainReorder()
    }

    private fun drainReorder() {
        while (true) {
            val raw = synchronized(lock) { reorder.remove(rcvNxt) } ?: break
            val p = Ipv4Packet(raw, raw.size)
            if (p.isValid(20)) handleOrdered(p)
        }
    }

    private fun startReader() {
        VpnExecutors.downstream.execute reader@{
            val buf = ByteArray(mss)
            val sock = socket ?: return@reader
            try {
                val input = sock.getInputStream()
                while (state != CLOSED) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        val bytes = n + 40 // IP+TCP 头估算
                        Limiter.consumeDown(uid, bytes)
                        StatsStore.addRx(uid, bytes.toLong())
                        emitToTun(Packets.ACK or Packets.PSH, buf, n)
                    }
                }
                // 对端关闭：给 App 发 FIN
                if (state != CLOSED && !ourFinSent) {
                    sendFlags(Packets.FIN or Packets.ACK, advance = 1)
                    ourFinSent = true
                    state = CLOSING
                }
            } catch (t: Throwable) {
                // 读异常视为连接终止
            } finally {
                if (state != CLOSED) {
                    runCatching { sock.close() }
                    state = CLOSING
                }
            }
        }
    }

    private fun sendRst() {
        sendFlags(Packets.RST or Packets.ACK, advance = 0)
    }

    /** 构造一个"网络 -> App"方向的 TCP 包并写入 tun */
    private fun sendFlags(flags: Int, advance: Int = 0, mss: Int = 0) {
        emitToTun(flags, null, 0, advance, mss)
    }

    private fun emitToTun(flags: Int, payload: ByteArray?, len: Int, advance: Int = 0, mss: Int = 0) {
        val packet: ByteArray
        synchronized(lock) {
            packet = Packets.buildTcp(
                srcIp = remoteIp, srcPort = remotePort,
                dstIp = clientIp, dstPort = clientPort,
                seq = sndNxt, ack = rcvNxt,
                flags = flags, window = receiveWindow(),
                payload = payload, payloadOffset = 0, payloadLen = len,
                mss = mss, ident = seq.incrementAndGet()
            )
            sndNxt = seqAdd(sndNxt, (len + advance).toLong())
        }
        vpn.writeToTun(packet)
    }

    /** 接收窗口：队列积压时缩小窗口，用背压让 App 自己降速，而不是堆内存 */
    private fun receiveWindow(): Int {
        val backlog = pending.size
        return when {
            backlog > 48 -> 4 * 1024
            backlog > 24 -> 16 * 1024
            else -> 64 * 1024 - 1
        }
    }

    fun isExpired(now: Long): Boolean = now - lastActive > IDLE_TIMEOUT

    fun close() {
        if (state == CLOSED) return
        state = CLOSED
        runCatching { out?.close() }
        runCatching { socket?.close() }
        synchronized(lock) { reorder.clear() }
        pending.clear()
    }

    private fun copyOf(pkt: Ipv4Packet): ByteArray {
        val raw = pkt.raw
        val len = pkt.len
        return if (raw.size == len) raw else raw.copyOf(len)
    }

    private fun seqAdd(a: Long, b: Long): Long = (a + b) and 0xFFFFFFFFL

    /** 考虑 32 位回绕的先后比较 */
    private fun isBefore(a: Long, b: Long): Boolean {
        val d = (a - b).toInt()
        return d < 0
    }
}
