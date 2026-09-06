package com.jiabihuan.tvnetguard.util

import java.io.DataOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 极简 root shell 封装：
 * 1. [run] 一次性 su —— 用于低频、需要干净状态的操作（应用/清理内核规则）；
 * 2. [runSession] 常驻 su 会话 —— 用于每秒的统计采样，su 只拉起一次，
 *    不会反复触发 root 授权提示，且单条命令开销极低。
 */
object RootShell {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    @Volatile
    private var rootCached: Boolean? = null

    fun hasRoot(): Boolean {
        rootCached?.let { return it }
        val r = run(listOf("id"), timeoutSec = 5)
        val ok = r.out.contains("uid=0") || r.out.contains("root")
        rootCached = ok
        return ok
    }

    fun forget() {
        rootCached = null
        closeSession()
    }

    /** 一次性执行（每次新起 su），适合低频命令 */
    fun run(cmds: List<String>, timeoutSec: Int = 10): Result {
        return try {
            val pb = ProcessBuilder("su")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val os = DataOutputStream(p.outputStream)
            for (c in cmds) os.writeBytes("$c\n")
            os.writeBytes("exit\n")
            os.flush()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                p.destroy()
                return Result(-2, out, "timeout")
            }
            Result(p.exitValue(), out, "")
        } catch (t: Throwable) {
            Result(-1, "", t.message ?: t.toString())
        }
    }

    // ---------------- 常驻 root 会话 ----------------

    private var sessProc: Process? = null
    private var sessIn: DataOutputStream? = null
    private var sessRaw: InputStream? = null
    private var seq = 0

    /**
     * 在常驻 su 会话里执行一条命令，返回 stdout（到结束标记为止）。
     * 超时或会话异常时销毁会话返回 null，下一轮自动重建。
     */
    fun runSession(cmd: String, timeoutMs: Long = 4000): String? = synchronized(this) {
        try {
            if (!ensureSession()) return null
            val p = sessProc ?: return null
            val marker = "@@TNG${seq++}@@"
            sessIn!!.writeBytes("$cmd\necho $marker\$?\n")
            sessIn!!.flush()
            val raw = sessRaw!!
            val buf = ByteArray(8192)
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            var done = false
            while (System.currentTimeMillis() < deadline) {
                while (raw.available() > 0) {
                    val n = raw.read(buf)
                    if (n > 0) sb.append(String(buf, 0, n, Charsets.UTF_8))
                }
                if (sb.indexOf(marker) >= 0) { done = true; break }
                if (!p.isAlive) break
                Thread.sleep(12)
            }
            if (!done) { closeSession(); return null }
            sb.toString().substringBefore(marker)
        } catch (t: Throwable) {
            closeSession()
            null
        }
    }

    private fun ensureSession(): Boolean {
        if (sessProc?.isAlive == true && sessIn != null && sessRaw != null) return true
        closeSession()
        return try {
            // stderr 不并入 stdout，避免 cat/iptables 的报错混进输出干扰解析
            val p = ProcessBuilder("su").start()
            sessProc = p
            sessIn = DataOutputStream(p.outputStream)
            sessRaw = p.inputStream
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun closeSession() {
        runCatching { sessIn?.close() }
        runCatching { sessProc?.destroy() }
        sessIn = null
        sessRaw = null
        sessProc = null
    }
}
