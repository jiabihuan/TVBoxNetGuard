package com.jiabihuan.tvnetguard.vpn

import com.jiabihuan.tvnetguard.util.Prefs
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 引擎用到的线程池。上行池给得比较宽，因为限速排队会长时间占用线程。 */
internal object VpnExecutors {

    private fun factory(name: String): ThreadFactory {
        val idx = AtomicInteger(0)
        return ThreadFactory { r ->
            Thread(r, "$name-${idx.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
    }

    /** connect 是阻塞的，单独一个池，避免把上行池占满导致整网卡住 */
    val connect: ExecutorService = ThreadPoolExecutor(
        2, 16, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(), factory("tng-connect")
    )

    /** 上行（限速排队、写真实 socket） */
    val upstream: ExecutorService = ThreadPoolExecutor(
        4, 64, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(4096), factory("tng-up")
    )

    /** 下行（阻塞读取真实 socket，一个连接一条） */
    val downstream: ExecutorService = ThreadPoolExecutor(
        4, 128, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(4096), factory("tng-down")
    )
}

/**
 * 串行执行器：同一个会话的任务严格按提交顺序执行，
 * 但只在实际有活儿的时候占一个池线程 —— 这样既能对单条连接做阻塞限速，
 * 又不会每条连接都常驻一个线程。
 */
internal class SerialExecutor(private val pool: Executor) : Executor {

    private val tasks = ArrayDeque<Runnable>()
    private var active: Runnable? = null

    @Synchronized
    override fun execute(command: Runnable) {
        tasks.offer(Runnable {
            try {
                command.run()
            } catch (t: Throwable) {
                VpnLog.w("serial task failed: ${t.message}")
            } finally {
                scheduleNext()
            }
        })
        if (active == null) {
            active = tasks.poll()
            active?.let { pool.execute(it) }
        }
    }

    @Synchronized
    private fun scheduleNext() {
        active = tasks.poll()
        active?.let { pool.execute(it) }
    }
}

internal object VpnLog {
    private const val TAG = "TVNetGuard"

    fun d(msg: String) {
        if (Prefs.logEnabled) android.util.Log.d(TAG, msg)
    }

    fun w(msg: String) {
        if (Prefs.logEnabled) android.util.Log.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        android.util.Log.e(TAG, msg, t)
    }
}
