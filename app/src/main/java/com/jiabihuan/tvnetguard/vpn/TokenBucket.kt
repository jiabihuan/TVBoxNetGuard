package com.jiabihuan.tvnetguard.vpn

import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max

/**
 * 字节级令牌桶限速器。
 *
 * 两个用法：
 * - [acquire]：阻塞等待令牌，用于「排队延迟发送」。TCP 用它速率最平滑，不会触发 RTO 退避。
 * - [tryAcquire]：拿不到令牌立刻失败，用于「严格丢包」。UDP 与严格模式用它，
 *   宁可丢包也绝不允许任何突发流量冲出去。
 *
 * @param mtu 链路 MTU，用于计算令牌桶容量下界（保证至少一个包能被放行，不会永久饿死）
 */
class TokenBucket(private var rateBytesPerSec: Long, private val mtu: Int = 1500) {

    private val lock = ReentrantLock()
    private val notEmpty: Condition = lock.newCondition()

    private var minCapacity = max(mtu.toLong(), 4096L)

    /** 桶容量：默认允许约 1/4 秒的突发；严格模式下压缩到 1 个包 */
    private var capacity: Long = max(minCapacity, rateBytesPerSec / 4)
    private var tokens: Double = capacity.toDouble()
    private var lastNanos: Long = System.nanoTime()
    private var strict: Boolean = false

    fun configure(rateBytesPerSec: Long, strict: Boolean) {
        lock.lock()
        try {
            this.rateBytesPerSec = rateBytesPerSec
            this.strict = strict
            this.capacity = if (strict) max(mtu.toLong(), rateBytesPerSec / 20) else max(minCapacity, rateBytesPerSec / 4)
            if (tokens > capacity) tokens = capacity.toDouble()
            notEmpty.signalAll()
        } finally {
            lock.unlock()
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsed = now - lastNanos
        if (elapsed <= 0) return
        lastNanos = now
        val add = elapsed / 1_000_000_000.0 * rateBytesPerSec
        tokens = minOf(capacity.toDouble(), tokens + add)
    }

    /** 阻塞直到取到 n 字节令牌。rate <= 0 时直接返回（不限速）。 */
    fun acquire(n: Int) {
        if (rateBytesPerSec <= 0 || n <= 0) return
        lock.lock()
        try {
            while (true) {
                refill()
                if (tokens >= n) {
                    tokens -= n
                    return
                }
                val deficit = n - tokens
                var waitNanos = (deficit / rateBytesPerSec * 1_000_000_000.0).toLong() + 500_000L
                if (waitNanos > 1_000_000_000L) waitNanos = 1_000_000_000L
                if (waitNanos < 1_000_000L) waitNanos = 1_000_000L
                notEmpty.awaitNanos(waitNanos)
            }
        } finally {
            lock.unlock()
        }
    }

    /** 非阻塞取令牌。成功返回 true，配额不足返回 false（调用方应丢弃该包）。 */
    fun tryAcquire(n: Int): Boolean {
        if (n <= 0) return true
        if (rateBytesPerSec <= 0) return true
        lock.lock()
        try {
            refill()
            return if (tokens >= n) {
                tokens -= n
                true
            } else false
        } finally {
            lock.unlock()
        }
    }

    val isStrict: Boolean get() = strict
}
