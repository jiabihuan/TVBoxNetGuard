#!/usr/bin/env python3
"""
星河守卫 内核限速规则 —— 真机模拟实测驱动

在两个 netns 之间架 veth，用**不同 uid 的进程**模拟不同 App：
  uid 10001 = 剧迷TV（被限速目标，跑 PCDN 式 UDP 大包）
  uid 10002 = 普通应用（不能受影响）
  uid 0     = root / 系统服务（验证 root 流量闸是否误伤）

规则生成逻辑逐行照抄 app/.../vpn/RootBackend.kt 的 applyWithIptables()，
包括 pps 折算（1500 字节满包）、规则顺序、以及 1.4.0 新增的小包豁免。
通过 small_pkt=0 可以关掉豁免，用来复现 1.2.0/1.3.0 的"整机断网"现象。
"""
import os
import re
import signal
import subprocess
import sys
import time

NS_C, NS_S = "tng_c", "tng_s"
SRV_IP = "10.9.0.1"
LOG = "/tmp/tnglab/server.log"
CH = "tng_out"
AVG_PACKET = 1500

# uid -> 端口后缀
PORTS = {10001: 1, 10002: 2, 0: 3}
PY = sys.executable
FLOW = os.path.join(os.path.dirname(os.path.abspath(__file__)), "flow.py")


def sh(cmd, ns=None, check=False):
    full = f"ip netns exec {ns} {cmd}" if ns else cmd
    r = subprocess.run(full, shell=True, capture_output=True, text=True)
    if check and r.returncode != 0:
        print(f"  !! {full}\n     {r.stderr.strip()}", file=sys.stderr)
    return r


def ipt(cmd):
    return sh(f"iptables {cmd}", ns=NS_C)


# ---------------- 拓扑 ----------------
def setup():
    sh("ip netns del tng_c 2>/dev/null; ip netns del tng_s 2>/dev/null")
    sh("ip netns add tng_c; ip netns add tng_s")
    sh("ip link del veth_c 2>/dev/null")
    sh("ip link add veth_c type veth peer name veth_s")
    sh("ip link set veth_s netns tng_s; ip link set veth_c netns tng_c")
    sh(f"ip -n {NS_C} addr add 10.9.0.2/24 dev veth_c")
    sh(f"ip -n {NS_S} addr add {SRV_IP}/24 dev veth_s")
    sh(f"ip -n {NS_C} link set veth_c up; ip -n {NS_S} link set veth_s up")
    sh(f"ip -n {NS_C} link set lo up; ip -n {NS_S} link set lo up")
    time.sleep(0.3)


# ---------------- 规则（照抄 RootBackend.applyWithIptables） ----------------
def clear_rules():
    for c in (f"-D OUTPUT -j {CH}", f"-F {CH}", f"-X {CH}"):
        ipt(f"{c} 2>/dev/null")


def apply_rules(uid, up_kbps=-1, block_udp=False, gate=False,
                gate_kbps=512, small_pkt=200):
    """返回本次下发的规则条数"""
    clear_rules()
    ipt(f"-N {CH} 2>/dev/null")
    n = 0

    # ---- root 流量闸：uid 0 出站 ----
    if gate:
        if small_pkt:  # 1.4.0 新增：小包（ACK/DNS）豁免
            ipt(f"-A {CH} -m owner --uid-owner 0 -m length --length 0:{small_pkt} -j ACCEPT"); n += 1
        pps0 = max(1, gate_kbps * 1024 // AVG_PACKET)
        burst0 = max(2, pps0 // 5)
        ipt(f"-A {CH} -m owner --uid-owner 0 -m limit --limit {pps0}/second "
            f"--limit-burst {burst0} -j ACCEPT"); n += 1
        ipt(f"-A {CH} -m owner --uid-owner 0 -j DROP"); n += 1

    # ---- 应用规则 ----
    if block_udp:
        ipt(f"-A {CH} -m owner --uid-owner {uid} -p udp -j DROP"); n += 1
    if up_kbps > 0:
        if small_pkt:
            ipt(f"-A {CH} -m owner --uid-owner {uid} -m length --length 0:{small_pkt} -j ACCEPT"); n += 1
        pps = max(1, up_kbps * 1024 // AVG_PACKET)
        burst = max(2, pps // 5)
        ipt(f"-A {CH} -m owner --uid-owner {uid} -m limit --limit {pps}/second "
            f"--limit-burst {burst} -j ACCEPT"); n += 1
        ipt(f"-A {CH} -m owner --uid-owner {uid} -j DROP"); n += 1

    ipt(f"-A {CH} -j RETURN")
    ipt(f"-I OUTPUT -j {CH}")
    return n


# ---------------- 服务端 ----------------
def start_server():
    os.makedirs("/tmp/tnglab", exist_ok=True)
    if os.path.exists(LOG):
        os.remove(LOG)
    f = open(LOG, "w")
    p = subprocess.Popen(f"ip netns exec {NS_S} {PY} {FLOW} server-up",
                         shell=True, stdout=f, stderr=subprocess.STDOUT,
                         preexec_fn=os.setsid)
    time.sleep(1.0)
    return p, f


def window_rows(t0, t1):
    """取时间窗内所有 CUM 采样行"""
    rows = []
    for line in open(LOG):
        if not line.startswith("CUM "):
            continue
        parts = line.split()
        ts = float(parts[1])
        if not (t0 <= ts <= t1):
            continue
        vals = {}
        for kv in parts[2:]:
            k, v = kv.split("=")
            vals[k] = int(v)          # 单位 KB
        rows.append((ts, vals))
    return rows


def uplink_rate(kind, uid, t0, t1):
    """服务端实测收到的速率 KB/s（用窗内首尾采样做差分，边界自动对齐）"""
    rows = window_rows(t0, t1)
    if len(rows) < 2:
        return 0.0
    (ta, a), (tb, b) = rows[0], rows[-1]
    if tb - ta <= 0:
        return 0.0
    key = f"{kind}:{(5000 if kind == 'udp' else 5100) + PORTS[uid]}"
    return (b.get(key, 0) - a.get(key, 0)) / (tb - ta)


# ---------------- 客户端 ----------------
def spawn(mode, uid, port, sec, rate=0):
    env = dict(os.environ, SIM_UID=str(uid), SRV=SRV_IP)
    if mode == "udp":
        cmd = f"{PY} {FLOW} udp {port} {rate} {sec}"
    else:
        cmd = f"{PY} {FLOW} {mode} {port} {sec}"
    return subprocess.Popen(f"ip netns exec {NS_C} {cmd}", shell=True,
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, preexec_fn=os.setsid)


def wait_procs(procs, hard=6.0):
    """等客户端退出；限速导致 TCP 僵死时客户端可能卡在 sendall，超时强杀"""
    outs = {}
    for uid, p in procs.items():
        try:
            outs[uid] = p.communicate(timeout=hard)[0]
        except subprocess.TimeoutExpired:
            os.killpg(os.getpgid(p.pid), 9)
            try:
                outs[uid] = p.communicate(timeout=2)[0]
            except Exception:
                outs[uid] = ""
            print(f"    (uid={uid} 客户端超时被杀：该限速已把它卡死)")
    return outs


def run_round(mode, sec, rate=0):
    """三个 uid 同时跑同一种流量，返回各自的实测速率 KB/s"""
    procs = {}
    t0 = time.time()
    for uid, suf in PORTS.items():
        port = (5000 if mode == "udp" else 5100 if mode == "tcpup" else 5200) + suf
        procs[uid] = spawn(mode, uid, port, sec, rate)
    outs = wait_procs(procs)
    time.sleep(0.5)      # 等服务端把最后一段累计值刷出来
    t1 = time.time()

    res = {}
    for uid, suf in PORTS.items():
        if mode in ("udp", "tcpup"):
            res[uid] = uplink_rate(mode, uid, t0 + 0.2, t1 - 0.2)
        else:
            m = re.search(r'"rx_kbps"\s*:\s*(\d+)', outs[uid] or "")
            res[uid] = float(m.group(1)) if m else 0.0
    return res


# ---------------- 场景 ----------------
def show(title, up, down):
    print(f"\n  {title}")
    print(f"    {'uid':<10}{'上行(UDP)':>14}{'下行(TCP)':>14}")
    for uid in (10001, 10002, 0):
        name = {10001: "剧迷TV", 10002: "普通应用", 0: "root/系统"}[uid]
        print(f"    {str(uid)+' '+name:<10}{up[uid]:>11.0f} KB/s{down[uid]:>11.0f} KB/s")


def main():
    print("== 搭建 veth + 双 netns 实验环境 ==")
    setup()
    srv, logf = start_server()
    sec = 3.0
    try:
        print("\n############ 场景 1：基线（不下发任何规则）############")
        clear_rules()
        up = run_round("udp", sec, rate=900)
        down = run_round("tcpdown", sec)
        show("基线：三个 uid 都全速", up, down)
        base_up, base_down = up, down

        print("\n############ 场景 2：给剧迷TV(10001) 上行限速 200 KB/s ############")
        apply_rules(uid=10001, up_kbps=200)
        up = run_round("udp", sec, rate=900)
        down = run_round("tcpdown", sec)
        show("期望：10001 被压到 ~200，另两个不受影响", up, down)
        s2 = (up, down)

        print("\n############ 场景 3：给剧迷TV 禁 UDP（掐 PCDN）############")
        apply_rules(uid=10001, block_udp=True)
        up = run_round("udp", sec, rate=900)
        down = run_round("tcpdown", sec)
        show("期望：10001 的 UDP 归零，TCP 下载照常；另两个不受影响", up, down)
        s3 = (up, down)

        print("\n############ 场景 4：★复现旧版 bug★ root 闸 128 KB/s 且【无】小包豁免 ############")
        apply_rules(uid=10001, up_kbps=200, gate=True, gate_kbps=128, small_pkt=0)
        up = run_round("udp", sec, rate=900)
        down = run_round("tcpdown", sec)
        show("旧版：uid 0 的 ACK 被闸卡死 → 下行被饿死（用户遇到的'全断网'）", up, down)
        s4 = (up, down)

        print("\n############ 场景 5：1.4.0 修复后 root 闸 512 KB/s + 小包豁免 ############")
        apply_rules(uid=10001, up_kbps=200, gate=True, gate_kbps=512, small_pkt=200)
        up = run_round("udp", sec, rate=900)
        down = run_round("tcpdown", sec)
        show("新版：10001 仍被限，uid 0 下行恢复正常", up, down)
        s5 = (up, down)

        print("\n\n==================== 汇总对比 ====================")
        print(f"{'场景':<34}{'剧迷TV上行':>12}{'普通应用上行':>14}{'root上行':>11}"
              f"{'剧迷TV下行':>13}{'普通下行':>11}{'root下行':>11}")
        rows = [
            ("1 基线（无规则）", base_up, base_down),
            ("2 限 10001 = 200KB/s", s2[0], s2[1]),
            ("3 禁 10001 的 UDP", s3[0], s3[1]),
            ("4 旧版 root闸128+无豁免", s4[0], s4[1]),
            ("5 新版 root闸512+豁免", s5[0], s5[1]),
        ]
        for name, u, d in rows:
            print(f"{name:<34}{u[10001]:>9.0f} KB/s{u[10002]:>11.0f} KB/s{u[0]:>8.0f} KB/s"
                  f"{d[10001]:>10.0f} KB/s{d[10002]:>8.0f} KB/s{d[0]:>8.0f} KB/s")
        print("\n（表中数值均为服务端/客户端实测有效吞吐，单位 KB/s）")
    finally:
        clear_rules()
        os.killpg(os.getpgid(srv.pid), signal.SIGTERM)
        logf.close()


if __name__ == "__main__":
    main()
