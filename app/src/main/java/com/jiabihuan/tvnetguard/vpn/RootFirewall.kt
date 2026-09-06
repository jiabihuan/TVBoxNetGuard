package com.jiabihuan.tvnetguard.vpn

import com.jiabihuan.tvnetguard.data.RuleStore
import com.jiabihuan.tvnetguard.util.Prefs
import com.jiabihuan.tvnetguard.util.RootShell
import kotlin.math.max

/**
 * Root 加固引擎：在内核 iptables OUTPUT 链上再落一道闸。
 *
 * 为什么要有它：
 * - 免 root 方案依赖 VPN 接管，某些 App（尤其是直连 IP 的 UDP 应用、被设为
 *   "绕过 VPN" 的应用）可能不经过 tun。iptables 的 owner 匹配是按 uid 在内核里做的，
 *   这类流量同样逃不掉。
 * - 内核里丢弃是最"硬"的限速，用户态进程被杀也拦得住（规则不会随之消失）。
 *
 * 局限（写在前面，避免误解）：
 * - iptables 的 limit 是**按包**计数不是按字节，所以这里用平均包长折算成 pps。
 *   折算按 1500 字节的满包估算，实际速率通常**低于**设定值，也就是更严格。
 * - tc + CLASSIFY/htb 才是真正的字节级整形，但电视盒子内核（3.10 / 4.9 居多）
 *   经常把这些模块裁掉，实测可用性很低，所以不依赖它。
 */
object RootFirewall {

    private const val CHAIN = "tng_out"
    private const val CHAIN6 = "tng_out6"
    private const val AVG_PACKET = 1500

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var lastMessage: String = ""
        private set

    fun apply(): Boolean {
        if (!Prefs.rootMode) {
            clear()
            return false
        }
        if (!RootShell.hasRoot()) {
            lastMessage = "未获取到 root 权限"
            active = false
            return false
        }

        val cmds = ArrayList<String>()
        for (bin in arrayOf("iptables", "ip6tables")) {
            val chain = if (bin == "iptables") CHAIN else CHAIN6
            cmds += "$bin -D OUTPUT -j $chain 2>/dev/null"
            cmds += "$bin -F $chain 2>/dev/null"
            cmds += "$bin -X $chain 2>/dev/null"
            cmds += "$bin -N $chain 2>/dev/null"
        }

        for (rule in RuleStore.all()) {
            if (!rule.isLimited) continue
            val uid = rule.uid
            if (rule.blocked || rule.upKbps == 0) {
                // 彻底断网：上行下行一起丢，连 REJECT 都不回，做到最硬
                cmds += "iptables -A $CHAIN -m owner --uid-owner $uid -j DROP"
                cmds += "ip6tables -A $CHAIN6 -m owner --uid-owner $uid -j DROP 2>/dev/null"
                continue
            }
            if (rule.upKbps > 0) {
                val pps = max(1, rule.upKbps * 1024 / AVG_PACKET)
                val burst = max(2, pps / 5)
                cmds += "iptables -A $CHAIN -m owner --uid-owner $uid -m limit --limit $pps/second --limit-burst $burst -j ACCEPT"
                cmds += "iptables -A $CHAIN -m owner --uid-owner $uid -j DROP"
                cmds += "ip6tables -A $CHAIN6 -m owner --uid-owner $uid -m limit --limit $pps/second --limit-burst $burst -j ACCEPT 2>/dev/null"
                cmds += "ip6tables -A $CHAIN6 -m owner --uid-owner $uid -j DROP 2>/dev/null"
            }
        }

        cmds += "iptables -A $CHAIN -j RETURN"
        cmds += "iptables -I OUTPUT -j $CHAIN"
        cmds += "ip6tables -A $CHAIN6 -j RETURN 2>/dev/null"
        cmds += "ip6tables -I OUTPUT -j $CHAIN6 2>/dev/null"

        // 语法自检：链建成功才说明这套内核支持 owner 匹配
        cmds += "iptables -S $CHAIN"

        val r = RootShell.run(cmds, timeoutSec = 20)
        val ok = r.out.contains("-A $CHAIN") && !r.out.contains("No chain/target/match")
        active = ok
        lastMessage = if (ok) "iptables 规则已生效（${RuleStore.limitedUids().size} 个应用）" else "iptables 不可用：${r.out.take(200)}"
        VpnLog.d("root firewall apply ok=$ok msg=$lastMessage")
        return ok
    }

    fun clear() {
        if (!active && !Prefs.rootMode) return
        val cmds = listOf(
            "iptables -D OUTPUT -j $CHAIN 2>/dev/null",
            "iptables -F $CHAIN 2>/dev/null",
            "iptables -X $CHAIN 2>/dev/null",
            "ip6tables -D OUTPUT -j $CHAIN6 2>/dev/null",
            "ip6tables -F $CHAIN6 2>/dev/null",
            "ip6tables -X $CHAIN6 2>/dev/null",
            "exit"
        )
        RootShell.run(cmds, timeoutSec = 10)
        active = false
        lastMessage = ""
    }
}
