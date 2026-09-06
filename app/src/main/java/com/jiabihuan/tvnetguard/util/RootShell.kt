package com.jiabihuan.tvnetguard.util

import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

/**
 * 极简 root shell 封装。root 加固是"可选项"：
 * 拿不到 root 就自动退回免 root 的 VPN 引擎，不影响主功能。
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
    }

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
}
