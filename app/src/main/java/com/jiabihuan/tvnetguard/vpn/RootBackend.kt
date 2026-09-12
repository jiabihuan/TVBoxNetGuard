package com.jiabihuan.tvnetguard.vpn

import com.jiabihuan.tvnetguard.data.AppRule
import com.jiabihuan.tvnetguard.data.RuleStore
import com.jiabihuan.tvnetguard.util.Prefs
import com.jiabihuan.tvnetguard.util.RootShell
import kotlin.math.max

/**
 * 纯内核限速后端。**完全不依赖 VPN**，盒子拿到 root 即可使用。
 *
 * 规则同时下发到 **IPv4（iptables）与 IPv6（ip6tables）**，TCP / UDP 全覆盖，
 * 不给 PCDN 之类的应用留绕过路径（之前只下发 IPv4 是实测"限不住"的主因）。
 *
 * 两条内核路径，自动挑选可用的：
 * 1. `tc + htb + CLASSIFY`（字节级，最理想）—— 需要内核带 `sch_htb` 与 `xt_CLASSIFY`，
 *    且系统里有 `tc` 二进制（常见于有 root 的定制盒子）。IPv6 的包由 ip6tables mangle
 *    里同样的 CLASSIFY 规则送进同一批 htb class。
 * 2. `iptables/ip6tables owner + limit`（按包计数，按 1500 字节满包折算成 pps）——
 *    Android 盒子内核几乎都带 `xt_owner`，是兜底方案。
 *
 * 统计不在这里做，由 [RootStats] 从 `qtaguid` 读取；iptables 统计链挂在
 * **POSTROUTING**（丢包之后），计数口径就是"实际出了网卡的量"。
 *
 * 限速行为：
 * - 限速：超出配额**丢包 / 整形**（TCP 自己降速，UDP 的超额部分被直接压掉）；
 * - 彻底断网：TCP 用 REJECT(tcp-reset) 快速断开，其余（UDP 等）一律 DROP。
 */
object RootBackend {

    // ip6tables 与 iptables 的链空间相互独立，同名不冲突，清理时两边各清一遍
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

    /** root 流量闸：uid 0（root）出站上行限值，KB/s。太狠会拖死整机，取一个宽容值 */
    private const val GATE_KBPS = 512

    /**
     * 小包豁免阈值（字节，含 IP 头）。
     *
     * 纯 ACK 约 40~60 字节、DNS 查询通常 < 200 字节。**下载数据必须靠上行 ACK 确认**，
     * 如果把 ACK 也计入限速配额，上行一限死下行立马断——这就是早期版本"一开引擎
     * 整台盒子断网"的根因。所以小于该阈值的包一律直接放行，只对真正的大块数据
     * （视频分片、P2P 上传）限速。小包就算被恶意打满，60 字节 × pps 也才几 KB/s。
     */
    private const val SMALL_PKT = 200

    /** tc 路径下判定"大包"的阈值：只有超过它的包才送进限速 class */
    private const val BIG_PKT = 400

    /**
     * 实测记录（2026-09，veth + netns 双机模拟，目标限速 200 KB/s）：
     *
     * | 方案                        | 实测       |
     * | ---                         | ---        |
     * | `iptables -m limit`         | 172 KB/s ✅ |
     * | `iptables -m statistic nth` | 38 MB/s  ❌ |
     * | `tc + htb`                  | 190 KB/s ✅ |
     *
     * `-m statistic --mode nth` 是**无状态比例丢弃**，限速值取决于"应用原本能跑多快"，
     * 而这个值随环境剧烈变化（同一个 every=15，在 5 MB/s 宽带上是 333 KB/s，
     * 在高速链路上是 38 MB/s），完全不可控，已弃用。
     * 结论：能用 tc 就用 tc（字节级最准），否则用 `-m limit`（按包折算，略偏严但可靠）。
     */

    /** 依据当前规则应用内核限速；无规则且闸关闭则清空。返回是否成功。 */
    fun apply(): Boolean {
        if (!RootShell.hasRoot()) {
            lastMessage = "未获取到 root 权限"
            active = false
            return false
        }
        val rules = RuleStore.all().filter { it.isLimited }
        if (rules.isEmpty() && !Prefs.rootTrafficGate) {
            clear()
            return true
        }

        val hasV6 = hasIp6tables()
        if (applyWithTc(rules, hasV6)) {
            method = "tc"
            active = true
            if (Prefs.rootTrafficGate) lastMessage += "；root 流量闸已开启（uid 0 上行 ${GATE_KBPS} KB/s）"
            return true
        }
        if (applyWithIptables(rules, hasV6)) {
            method = "iptables"
            active = true
            if (Prefs.rootTrafficGate) lastMessage += "；root 流量闸已开启（uid 0 上行 ${GATE_KBPS} KB/s）"
            return true
        }
        lastMessage = "内核不支持 owner 匹配，无法在内核层限速"
        active = false
        return false
    }

    private fun hasIp6tables(): Boolean {
        val r = RootShell.run(listOf("which ip6tables 2>/dev/null; ls /system/bin/ip6tables /system/xbin/ip6tables 2>/dev/null"))
        return r.out.isNotBlank()
    }

    /** tc 路径下，每个被限速 App 的 class 编号要跨 tc/IPv4/IPv6 三处保持一致 */
    private class ClassIds(rules: List<AppRule>) {
        private val map = HashMap<Int, Int>()

        init {
            var idx = 10
            for (r in rules) {
                if (!r.blocked && r.upKbps > 0) map[r.uid] = idx++
            }
        }

        fun of(uid: Int): Int? = map[uid]
    }

    private fun applyWithTc(rules: List<AppRule>, hasV6: Boolean): Boolean {
        val wan = findWan() ?: return false
        if (findTc() == null) return false
        val ids = ClassIds(rules)

        // 1) tc 整形骨架：每 App 一个 class；default class 必须存在，
        //    否则未分类流量的行为不可控（可能直通）
        val tcCmds = ArrayList<String>()
        tcCmds += "tc qdisc del dev $wan root 2>/dev/null"
        tcCmds += "tc qdisc add dev $wan root handle 1: htb default 9999"
        tcCmds += "tc class add dev $wan parent 1: classid 1:1 htb rate 1000mbit ceil 1000mbit"
        tcCmds += "tc class add dev $wan parent 1: classid 1:9999 htb rate 1000mbit ceil 1000mbit"
        // root 流量闸：uid 0（root 提权流量）固定走低速 class 1:2
        if (Prefs.rootTrafficGate) {
            tcCmds += "tc class add dev $wan parent 1: classid 1:2 htb rate ${GATE_KBPS}kbit ceil ${GATE_KBPS}kbit"
        }
        for (rule in rules) {
            val id = ids.of(rule.uid) ?: continue
            tcCmds += "tc class add dev $wan parent 1:1 classid 1:$id htb rate ${rule.upKbps}kbit ceil ${rule.upKbps}kbit"
        }
        val r1 = RootShell.run(tcCmds, timeoutSec = 20)
        val tcOk = !r1.out.contains("RTNETLINK answers") &&
            !r1.out.contains("Cannot find device") &&
            !r1.out.contains("No such file or directory")
        if (!tcOk) {
            lastMessage = "tc 不可用：${r1.out.take(160)}"
            return false
        }
        // 安全阀：default class 必须真的建起来。HTB 的 default 指向不存在的 class 时，
        // 所有未分类流量（也就是整机正常上网流量）会被直接丢弃 = 一开引擎就断网。
        // 建不起来就回滚 qdisc，回退到 iptables 方案。
        val chk = RootShell.run(listOf("tc class show dev $wan 2>/dev/null | grep -c '1:9999'"), timeoutSec = 8)
        val hasDefault = chk.out.trim().trim('"').toIntOrNull()?.let { it > 0 } == true
        if (!hasDefault) {
            RootShell.run(listOf("tc qdisc del dev $wan root 2>/dev/null"), timeoutSec = 8)
            lastMessage = "tc 的 default class 未建成（会误伤正常流量），已回退 iptables 方案"
            return false
        }

        // 2) IPv4 mangle：分类 / 断网 / 统计
        val v4 = ArrayList<String>()
        v4 += "iptables -t mangle -F $MANGLE_CHAIN 2>/dev/null"
        v4 += "iptables -t mangle -X $MANGLE_CHAIN 2>/dev/null"
        v4 += "iptables -t mangle -N $MANGLE_CHAIN 2>/dev/null"
        v4 += "iptables -t mangle -F $STAT_CHAIN 2>/dev/null"
        v4 += "iptables -t mangle -X $STAT_CHAIN 2>/dev/null"
        v4 += "iptables -t mangle -N $STAT_CHAIN 2>/dev/null"
        // root 流量闸（IPv4）：uid 0 的**大包**才分类进 1:2 低速 class；
        // 小包（ACK/DNS）走 default class 不限，否则整机下行会被 ACK 断流卡死
        if (Prefs.rootTrafficGate) {
            v4 += "iptables -t mangle -A $MANGLE_CHAIN -m owner --uid-owner 0 -m length --length $BIG_PKT:65535 -j CLASSIFY --set-class 1:2"
        }
        for (rule in rules) {
            val uid = rule.uid
            val id = ids.of(uid)
            when {
                rule.blocked || rule.upKbps == 0 -> {
                    // 彻底断网：直接 DROP（IPv4）
                    v4 += "iptables -t mangle -A $MANGLE_CHAIN -m owner --uid-owner $uid -j DROP"
                }
                else -> {
                    // 禁 UDP（掐 P2P/PCDN）：放在 CLASSIFY 之前，UDP 包在此终止
                    if (rule.blockUdp) {
                        v4 += "iptables -t mangle -A $MANGLE_CHAIN -m owner --uid-owner $uid -p udp -j DROP"
                    }
                    if (id != null) {
                        // 只对大包分类限速，小包放行保住 ACK
                        v4 += "iptables -t mangle -A $MANGLE_CHAIN -m owner --uid-owner $uid -m length --length $BIG_PKT:65535 -j CLASSIFY --set-class 1:$id"
                    }
                }
            }
            v4 += "iptables -t mangle -A $STAT_CHAIN -m owner --uid-owner $uid -j RETURN"
        }
        v4 += "iptables -t mangle -A $MANGLE_CHAIN -j RETURN"
        v4 += "iptables -t mangle -A $STAT_CHAIN -j RETURN"
        v4 += "iptables -t mangle -I POSTROUTING -j $STAT_CHAIN"
        v4 += "iptables -t mangle -I POSTROUTING -j $MANGLE_CHAIN"
        val r2 = RootShell.run(v4, timeoutSec = 15)
        val v4Ok = !r2.out.contains("No chain/target/match") && !r2.out.contains("not supported")
        if (!v4Ok) {
            lastMessage = "iptables mangle 不可用：${r2.out.take(160)}"
            return false
        }

        // 3) IPv6 mangle：同样的分类 / 断网，把 IPv6 包送进同一批 htb class
        var v6Ok = true
        if (hasV6) {
            val v6 = ArrayList<String>()
            v6 += "ip6tables -t mangle -F $MANGLE_CHAIN 2>/dev/null"
            v6 += "ip6tables -t mangle -X $MANGLE_CHAIN 2>/dev/null"
            v6 += "ip6tables -t mangle -N $MANGLE_CHAIN 2>/dev/null"
            // root 流量闸（IPv6）
            if (Prefs.rootTrafficGate) {
                v6 += "ip6tables -t mangle -A $MANGLE_CHAIN -m owner --uid-owner 0 -m length --length $BIG_PKT:65535 -j CLASSIFY --set-class 1:2"
            }
            for (rule in rules) {
                val uid = rule.uid
                val id = ids.of(uid)
                when {
                    rule.blocked || rule.upKbps == 0 -> {
                        v6 += "ip6tables -t mangle -A $MANGLE_CHAIN -m owner --uid-owner $uid -j DROP"
                    }
                    else -> {
                        if (rule.blockUdp) {
                            v6 += "ip6tables -t mangle -A $MANGLE_CHAIN -m owner --uid-owner $uid -p udp -j DROP"
                        }
                        if (id != null) {
                            v6 += "ip6tables -t mangle -A $MANGLE_CHAIN -m owner --uid-owner $uid -m length --length $BIG_PKT:65535 -j CLASSIFY --set-class 1:$id"
                        }
                    }
                }
            }
            v6 += "ip6tables -t mangle -A $MANGLE_CHAIN -j RETURN"
            v6 += "ip6tables -t mangle -I POSTROUTING -j $MANGLE_CHAIN"
            val r3 = RootShell.run(v6, timeoutSec = 15)
            v6Ok = !r3.out.contains("No chain/target/match") &&
                !r3.out.contains("not supported") &&
                !r3.out.contains("not found")
        }

        lastMessage = if (v6Ok) {
            "tc+htb 字节级限速已生效（${rules.size} 个应用，IPv4+IPv6）"
        } else {
            "tc+htb 已生效（IPv4）；注意：内核缺 ip6tables，IPv6 未覆盖"
        }
        return true
    }

    private fun applyWithIptables(rules: List<AppRule>, hasV6: Boolean): Boolean {
        // 1) IPv4：filter OUTPUT owner + limit
        val v4 = ArrayList<String>()
        v4 += "iptables -D OUTPUT -j $OUT_CHAIN 2>/dev/null"
        v4 += "iptables -F $OUT_CHAIN 2>/dev/null"
        v4 += "iptables -X $OUT_CHAIN 2>/dev/null"
        v4 += "iptables -N $OUT_CHAIN 2>/dev/null"
        v4 += "iptables -t mangle -F $STAT_CHAIN 2>/dev/null"
        v4 += "iptables -t mangle -X $STAT_CHAIN 2>/dev/null"
        v4 += "iptables -t mangle -N $STAT_CHAIN 2>/dev/null"
        // root 流量闸（IPv4）：uid 0 出站限 GATE_KBPS，防提权应用绕过按应用限速。
        // 小包（ACK/DNS 等）先放行，否则会连带拖死整机的下行
        if (Prefs.rootTrafficGate) {
            v4 += "iptables -A $OUT_CHAIN -m owner --uid-owner 0 -m length --length 0:$SMALL_PKT -j ACCEPT"
            val pps0 = max(1, GATE_KBPS * 1024 / AVG_PACKET)
            val burst0 = max(2, pps0 / 5)
            v4 += "iptables -A $OUT_CHAIN -m owner --uid-owner 0 -m limit --limit $pps0/second --limit-burst $burst0 -j ACCEPT"
            v4 += "iptables -A $OUT_CHAIN -m owner --uid-owner 0 -j DROP"
        }
        for (rule in rules) {
            val uid = rule.uid
            when {
                rule.blocked || rule.upKbps == 0 -> {
                    // TCP 给 RST 快速断开；UDP 等其余协议一律 DROP（tcp-reset 对非 TCP 无效）
                    v4 += "iptables -A $OUT_CHAIN -m owner --uid-owner $uid -p tcp -j REJECT --reject-with tcp-reset"
                    v4 += "iptables -A $OUT_CHAIN -m owner --uid-owner $uid -j DROP"
                }
                else -> {
                    // 禁 UDP（掐 P2P/PCDN）：必须放在 limit ACCEPT 之前，否则 UDP 先被 ACCEPT 逃逸
                    if (rule.blockUdp) {
                        v4 += "iptables -A $OUT_CHAIN -m owner --uid-owner $uid -p udp -j DROP"
                    }
                    if (rule.upKbps > 0) {
                        // 小包豁免：保住 TCP ACK，避免"限速 = 断网"
                        v4 += "iptables -A $OUT_CHAIN -m owner --uid-owner $uid -m length --length 0:$SMALL_PKT -j ACCEPT"
                        // 按包计数限速：折算成每秒包数（满包估算，实际速率通常偏低 = 更严格）
                        val pps = max(1, rule.upKbps * 1024 / AVG_PACKET)
                        val burst = max(2, pps / 5)
                        v4 += "iptables -A $OUT_CHAIN -m owner --uid-owner $uid -m limit --limit $pps/second --limit-burst $burst -j ACCEPT"
                        v4 += "iptables -A $OUT_CHAIN -m owner --uid-owner $uid -j DROP"
                    }
                }
            }
            v4 += "iptables -t mangle -A $STAT_CHAIN -m owner --uid-owner $uid -j RETURN"
        }
        v4 += "iptables -A $OUT_CHAIN -j RETURN"
        v4 += "iptables -I OUTPUT -j $OUT_CHAIN"
        // 统计链挂 POSTROUTING：被 limit DROP 的包到不了这里，计数 = 实际出口流量
        v4 += "iptables -t mangle -A $STAT_CHAIN -j RETURN"
        v4 += "iptables -t mangle -I POSTROUTING -j $STAT_CHAIN"
        val r1 = RootShell.run(v4, timeoutSec = 20)
        val v4Ok = !r1.out.contains("No chain/target/match") && !r1.out.contains("not supported")
        if (!v4Ok) {
            lastMessage = "iptables 不可用：${r1.out.take(160)}"
            return false
        }

        // 2) IPv6：同名链、同样语义（与 IPv4 家族隔离互不影响）
        var v6Ok = false
        if (hasV6) {
            val v6 = ArrayList<String>()
            v6 += "ip6tables -D OUTPUT -j $OUT_CHAIN 2>/dev/null"
            v6 += "ip6tables -F $OUT_CHAIN 2>/dev/null"
            v6 += "ip6tables -X $OUT_CHAIN 2>/dev/null"
            v6 += "ip6tables -N $OUT_CHAIN 2>/dev/null"
            // root 流量闸（IPv6）
            if (Prefs.rootTrafficGate) {
                v6 += "ip6tables -A $OUT_CHAIN -m owner --uid-owner 0 -m length --length 0:$SMALL_PKT -j ACCEPT"
                val pps0 = max(1, GATE_KBPS * 1024 / AVG_PACKET)
                val burst0 = max(2, pps0 / 5)
                v6 += "ip6tables -A $OUT_CHAIN -m owner --uid-owner 0 -m limit --limit $pps0/second --limit-burst $burst0 -j ACCEPT"
                v6 += "ip6tables -A $OUT_CHAIN -m owner --uid-owner 0 -j DROP"
            }
            for (rule in rules) {
                val uid = rule.uid
                when {
                    rule.blocked || rule.upKbps == 0 -> {
                        v6 += "ip6tables -A $OUT_CHAIN -m owner --uid-owner $uid -j DROP"
                    }
                    else -> {
                        if (rule.blockUdp) {
                            v6 += "ip6tables -A $OUT_CHAIN -m owner --uid-owner $uid -p udp -j DROP"
                        }
                        if (rule.upKbps > 0) {
                            v6 += "ip6tables -A $OUT_CHAIN -m owner --uid-owner $uid -m length --length 0:$SMALL_PKT -j ACCEPT"
                            val pps = max(1, rule.upKbps * 1024 / AVG_PACKET)
                            val burst = max(2, pps / 5)
                            v6 += "ip6tables -A $OUT_CHAIN -m owner --uid-owner $uid -m limit --limit $pps/second --limit-burst $burst -j ACCEPT"
                            v6 += "ip6tables -A $OUT_CHAIN -m owner --uid-owner $uid -j DROP"
                        }
                    }
                }
            }
            v6 += "ip6tables -A $OUT_CHAIN -j RETURN"
            v6 += "ip6tables -I OUTPUT -j $OUT_CHAIN"
            val r2 = RootShell.run(v6, timeoutSec = 20)
            v6Ok = !r2.out.contains("No chain/target/match") &&
                !r2.out.contains("not supported") &&
                !r2.out.contains("not found")
        }

        lastMessage = if (v6Ok) {
            "iptables 限速已生效（${rules.size} 个应用，IPv4+IPv6）"
        } else {
            "iptables 已生效（IPv4）；注意：内核缺 ip6tables，IPv6 未覆盖"
        }
        return true
    }

    fun clear() {
        val cmds = ArrayList<String>()
        for (ipt in listOf("iptables", "ip6tables")) {
            cmds += "$ipt -D OUTPUT -j $OUT_CHAIN 2>/dev/null"
            cmds += "$ipt -F $OUT_CHAIN 2>/dev/null"
            cmds += "$ipt -X $OUT_CHAIN 2>/dev/null"
            cmds += "$ipt -t mangle -D POSTROUTING -j $MANGLE_CHAIN 2>/dev/null"
            cmds += "$ipt -t mangle -D POSTROUTING -j $STAT_CHAIN 2>/dev/null"
            cmds += "$ipt -t mangle -D OUTPUT -j $MANGLE_CHAIN 2>/dev/null"
            cmds += "$ipt -t mangle -D OUTPUT -j $STAT_CHAIN 2>/dev/null"
            cmds += "$ipt -t mangle -F $MANGLE_CHAIN 2>/dev/null"
            cmds += "$ipt -t mangle -X $MANGLE_CHAIN 2>/dev/null"
            cmds += "$ipt -t mangle -F $STAT_CHAIN 2>/dev/null"
            cmds += "$ipt -t mangle -X $STAT_CHAIN 2>/dev/null"
        }
        // 清掉 tc（常用出口都试一遍）
        for (d in arrayOf("eth0", "eth1", "wlan0", "p2p0", "tun0")) {
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
