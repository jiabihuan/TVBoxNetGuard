package com.jiabihuan.tvnetguard.vpn

import com.jiabihuan.tvnetguard.util.RootShell

/**
 * root 授权审计：从 Magisk / SuperSU 的日志与授权库里挖出
 * "哪些应用请求过 root / 被允许过 root"。
 *
 * 机顶盒的 su 授权经常是"默认允许"，用户根本不知道哪个应用拿了 root ——
 * 而拿到 root 的应用会以 uid 0 发包绕过按应用的限速规则。这份审计就是给用户的线索。
 *
 * 说明：真正的"拒绝授权"必须由 root 管理器（Magisk/超级用户）执行，
 * 我们不动 su 二进制与授权数据库（改坏会导致盒子无法挽救），
 * 我们做的是【审计曝光】+【root 流量闸】双保险。
 */
object RootAudit {

    data class Record(val uid: Int, val verb: String, val raw: String)

    private val LOG_PATHS = listOf(
        "/data/adb/magisk.log",
        "/cache/magisk.log",
        "/data/cache/magisk.log",
        "/data/local/magisk.log",
        "/data/adb/ksu/log" // KernelSU
    )

    /** 扫描（需 root）。返回按发现顺序的记录，最多 40 条。 */
    fun scan(): List<Record> {
        val out = ArrayList<Record>()

        // 1) 授权日志：抓含 uid 且含 allow/deny/grant 的行
        for (p in LOG_PATHS) {
            val text = RootShell.runSession("cat $p 2>/dev/null", 3000) ?: continue
            for (line in text.lines()) {
                val rec = parseLogLine(line) ?: continue
                if (out.none { it.uid == rec.uid && it.raw == rec.raw }) out.add(rec)
            }
        }

        // 2) Magisk 授权库（policies 表）：magisk CLI 若可用直接查
        val db = RootShell.runSession(
            "magisk --sqlite \"SELECT uid,policy FROM policies\" 2>/dev/null",
            3000
        )
        if (db != null) {
            for (line in db.lines()) {
                val m = Regex("uid[=\\s]+(\\d+)").find(line) ?: continue
                val uid = m.groupValues[1].toIntOrNull() ?: continue
                val policy = Regex("policy[=\\s]+(\\d+)").find(line)?.groupValues?.get(1) ?: "?"
                val raw = "授权库 policies：uid $uid policy=$policy"
                if (out.none { it.uid == uid && it.raw == raw }) {
                    out.add(Record(uid, "授权库", raw))
                }
            }
        }

        return out.take(40)
    }

    private fun parseLogLine(line: String): Record? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val lower = t.lowercase()
        val verb = when {
            lower.contains("allow") || lower.contains("grant") || lower.contains("允许") -> "允许"
            lower.contains("deny") || lower.contains("reject") || lower.contains("拒绝") -> "拒绝"
            lower.contains("uid") -> "记录"
            else -> return null
        }
        val uid = Regex("uid[=\\s]+(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return Record(uid, verb, t.take(90))
    }
}
