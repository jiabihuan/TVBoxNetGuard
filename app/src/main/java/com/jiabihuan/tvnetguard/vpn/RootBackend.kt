package com.jiabihuan.tvnetguard.vpn

import com.jiabihuan.tvnetguard.data.RuleStore
import com.jiabihuan.tvnetguard.util.Prefs
import com.jiabihuan.tvnetguard.util.RootShell
import kotlin.math.max

/**
 * 纯内核限速后端。**完全不依赖 VPN**，盒子拿到 root 即可使用。
 *
 * 两条内核路径，自动挑选可用的：
 * 1. `tc + htb + CLASSIFY`（字节级，最理想）—— 需要内核带 `sch_htb` 与 `xt_CLASSIFY`，
 *    且系统里有 `tc` 二进制（常见于有 root 的定制盒子）。
 * 2. `iptables owner + limit`（按包计数，按 1500 字节满包折算成 pps）——
 *    Android 盒子内核几乎都带 `xt_owner`，是兜底方案。
 *
 * 统计不在这里做，由 [RootStats] 从 `qtaguid` 读取（同样不需要 VPN）。
 *
 * 限速行为：
 * - 限速：超出配额**丢包**（让 TCP 自己降速），绝不重置连接；
 * - 彻底断网 / 上行=0：直接 DROP / REJECT，连门都不给开。
 */
object RootBackend {

    private const val OUT_CHAIN = "tng_out"
    private const val MANGLE_CHAIN = "tng_mangle"
    private const val STAT_CHAIN = RootStats.STAT_CHAIN
    private const val AVG_PACKET = 1500

    @Volatile
    var active = false
        private set

    @Volatile
    var method = "none"
        private set

    @Volatile
    var lastMessage: String = ""
        private set

    fun available(): Boolean = RootShell.hasRoot()

    /** 依据当前规则应用内核限速；空规则则清空。返回是否成功。 */
    fun apply(): Boolean {
        if (!RootShell.hasRoot()) {
            lastMessage = "未获取到 root 权限"
            active = false
            return false
        }
        val rules = RuleStore.all().filter { it.isLimited }
        if (rules.isEmpty()) {
            clear()
            return true
        }

        if (applyWithTc(rules)) {
            method = "tc"
            active = true
            return true
        }
        if (applyWithIptables(rules)) {
            method = "iptables"
            active = true
            return true
        }
        lastMessage = "内核不支持 owner 匹配，无法在内核层限速"
        active = false
        return false
    }

    private fun applyWithTc(rules: List<com.jiabihuan.tvnetguard.data.AppRule>): Boolean {
        val wan = findWan() ?: return false
        if (findTc() == null) return false

        val cmds = ArrayList<String>()
        cmds += "tc qdisc del dev $wan root 2>/dev/null"
        // mangle 链：分类 + 统计 + 断网
        cmds += "iptables -t mangle -F $MANGLE_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -X $MANGLE_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -F $STAT_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -X $STAT_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -N $MANGLE_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -N $STAT_CHAIN 2>/dev/null"

        cmds += "tc qdisc add dev $wan root handle 1: htb default 9999 2>/dev/null"
        cmds += "tc class add dev $wan parent 1: classid 1:1 htb rate 1000mbit 2>/dev/null"

        var idx = 10
        for (rule in rules) {
            val uid = rule.uid
            if (rule.blocked || rule.upKbps == 0) {
                cmds += "iptables -t mangle -A $MANGLE_CHAIN -m owner --uid-owner $uid -j DROP"
                continue
            }
            val rate = rule.upKbps
            cmds += "tc class add dev $wan parent 1:1 classid 1:$idx htb rate ${rate}kbit ceil ${rate}kbit"
            cmds += "iptables -t mangle -A $MANGLE_CHAIN -m owner --uid-owner $uid -j CLASSIFY --set-class 1:$idx"
            cmds += "iptables -t mangle -A $STAT_CHAIN -m owner --uid-owner $uid -j RETURN"
            idx++
        }
        cmds += "iptables -t mangle -A $MANGLE_CHAIN -j RETURN"
        cmds += "iptables -t mangle -A $STAT_CHAIN -j RETURN"
        cmds += "iptables -t mangle -I OUTPUT -j $STAT_CHAIN"
        cmds += "iptables -t mangle -I OUTPUT -j $MANGLE_CHAIN"

        val r = RootShell.run(cmds, timeoutSec = 25)
        val ok = !r.out.contains("RTNETLINK answers: Operation not supported") && !r.out.contains("No chain/target/match")
        lastMessage = if (ok) "tc+htb 字节级限速已生效（${rules.size} 个应用）" else "tc 不可用：${r.out.take(160)}"
        return ok
    }

    private fun applyWithIptables(rules: List<com.jiabihuan.tvnetguard.data.AppRule>): Boolean {
        val cmds = ArrayList<String>()
        cmds += "iptables -D OUTPUT -j $OUT_CHAIN 2>/dev/null"
        cmds += "iptables -F $OUT_CHAIN 2>/dev/null"
        cmds += "iptables -X $OUT_CHAIN 2>/dev/null"
        cmds += "iptables -N $OUT_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -F $STAT_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -X $STAT_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -N $STAT_CHAIN 2>/dev/null"

        for (rule in rules) {
            val uid = rule.uid
            when {
                rule.blocked || rule.upKbps == 0 -> {
                    cmds += "iptables -A $OUT_CHAIN -m owner --uid-owner $uid -j REJECT --reject-with tcp-reset"
                }
                rule.upKbps > 0 -> {
                    // 按包计数限速：折算成每秒包数（满包估算，实际速率通常偏低 = 更严格）
                    val pps = max(1, rule.upKbps * 1024 / AVG_PACKET)
                    val burst = max(2, pps / 5)
                    cmds += "iptables -A $OUT_CHAIN -m owner --uid-owner $uid -m limit --limit $pps/second --limit-burst $burst -j ACCEPT"
                    cmds += "iptables -A $OUT_CHAIN -m owner --uid-owner $uid -j DROP"
                }
            }
            cmds += "iptables -t mangle -A $STAT_CHAIN -m owner --uid-owner $uid -j RETURN"
        }
        cmds += "iptables -A $OUT_CHAIN -j RETURN"
        cmds += "iptables -I OUTPUT -j $OUT_CHAIN"
        cmds += "iptables -t mangle -A $STAT_CHAIN -j RETURN"
        cmds += "iptables -t mangle -I OUTPUT -j $STAT_CHAIN"

        val r = RootShell.run(cmds, timeoutSec = 25)
        val ok = !r.out.contains("No chain/target/match") && !r.out.contains("not supported")
        lastMessage = if (ok) "iptables 规则已生效（${rules.size} 个应用）" else "iptables 不可用：${r.out.take(160)}"
        return ok
    }

    fun clear() {
        val cmds = ArrayList<String>()
        cmds += "iptables -D OUTPUT -j $OUT_CHAIN 2>/dev/null"
        cmds += "iptables -F $OUT_CHAIN 2>/dev/null"
        cmds += "iptables -X $OUT_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -D OUTPUT -j $MANGLE_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -F $MANGLE_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -X $MANGLE_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -D OUTPUT -j $STAT_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -F $STAT_CHAIN 2>/dev/null"
        cmds += "iptables -t mangle -X $STAT_CHAIN 2>/dev/null"
        // 清掉 tc（常用出口都试一遍）
        for (d in arrayOf("eth0", "wlan0", "p2p0", "tun0")) {
            cmds += "tc qdisc del dev $d root 2>/dev/null"
        }
        RootShell.run(cmds, timeoutSec = 10)
        active = false
        method = "none"
        lastMessage = ""
    }

    private fun findTc(): String? {
        val r = RootShell.run(listOf("which tc 2>/dev/null; ls /system/bin/tc /system/xbin/tc 2>/dev/null"))
        return if (r.out.isNotBlank()) "tc" else null
    }

    private fun findWan(): String? {
        val r = RootShell.run(listOf("ip route get 8.8.8.8 2>/dev/null | head -1"))
        val line = r.out.trim()
        val i = line.indexOf("dev ")
        if (i >= 0) {
            val dev = line.substring(i + 4).split(Regex("\\s+"))[0]
            if (dev.isNotBlank()) return dev
        }
        val r2 = RootShell.run(listOf("ip link show up 2>/dev/null | grep -oE '(wlan|eth)[0-9]+' | head -1"))
        val d = r2.out.trim().split(Regex("\\s+")).firstOrNull()
        return if (d.isNullOrBlank()) null else d
    }
}
