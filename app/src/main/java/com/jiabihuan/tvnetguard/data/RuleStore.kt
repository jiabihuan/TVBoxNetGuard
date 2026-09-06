package com.jiabihuan.tvnetguard.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 单应用的限速规则。
 *
 * @param upKbps   上行限速，KB/s。-1 = 不限；0 = 完全禁止上传；>0 = 限速值
 * @param downKbps 下行限速，KB/s。含义同上
 * @param strict   true = 严格模式（配额用尽直接丢包，绝不突发）；false = 排队延迟发送（速率平滑）
 * @param blocked  true = 该应用彻底断网（上下行全丢）
 */
data class AppRule(
    val uid: Int,
    val upKbps: Int = -1,
    val downKbps: Int = -1,
    val strict: Boolean = true,
    val blocked: Boolean = false
) {
    val isLimited: Boolean
        get() = blocked || upKbps >= 0 || downKbps >= 0

    companion object {
        fun parse(uid: Int, raw: String?): AppRule {
            if (raw == null) return AppRule(uid)
            val p = raw.split("|")
            if (p.size < 4) return AppRule(uid)
            return AppRule(
                uid = uid,
                upKbps = p[0].toIntOrNull() ?: -1,
                downKbps = p[1].toIntOrNull() ?: -1,
                strict = p[2] == "1",
                blocked = p[3] == "1"
            )
        }
    }

    fun serialize(): String = "$upKbps|$downKbps|${if (strict) 1 else 0}|${if (blocked) 1 else 0}"
}

object RuleStore {

    private const val FILE = "tvnetguard_rules"
    private lateinit var sp: SharedPreferences

    /** 规则变更版本号，VPN 引擎据此热更新，无需重启 */
    @Volatile
    var version: Long = 0
        private set

    private val cache = HashMap<Int, AppRule>()

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        synchronized(cache) {
            cache.clear()
            sp.all.forEach { (k, v) ->
                val uid = k.toIntOrNull() ?: return@forEach
                cache[uid] = AppRule.parse(uid, v as? String)
            }
        }
        version++
    }

    fun get(uid: Int): AppRule = synchronized(cache) { cache[uid] ?: AppRule(uid) }

    fun all(): List<AppRule> = synchronized(cache) { cache.values.toList() }

    fun limitedUids(): Set<Int> = synchronized(cache) {
        cache.values.filter { it.isLimited }.map { it.uid }.toSet()
    }

    fun put(rule: AppRule) {
        synchronized(cache) {
            if (rule.upKbps < 0 && rule.downKbps < 0 && !rule.blocked && !rule.strict) {
                cache.remove(rule.uid)
                sp.edit().remove(rule.uid.toString()).apply()
            } else {
                cache[rule.uid] = rule
                sp.edit().putString(rule.uid.toString(), rule.serialize()).apply()
            }
        }
        version++
    }

    fun remove(uid: Int) {
        synchronized(cache) { cache.remove(uid) }
        sp.edit().remove(uid.toString()).apply()
        version++
    }
}
