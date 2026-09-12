#!/usr/bin/env python3
"""
限速内核方案横向对比：limit(旧) vs statistic nth(新) vs tc+htb
目标限速 200 KB/s，检查两件事：
  1) 能不能限住（不能太高）
  2) 会不会断流（不能是 0 —— 断流 = 应用直接断网）
"""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lab

SEC = 5.0
TARGET = 200


def trial(name, cmds=None, tc_cmds=None):
    lab.clear_rules()
    subprocess.run(f"ip netns exec {lab.NS_C} tc qdisc del dev veth_c root 2>/dev/null", shell=True)
    lab.ipt("-t mangle -F OUTPUT 2>/dev/null")
    if tc_cmds:
        for c in tc_cmds:
            r = subprocess.run(f"ip netns exec {lab.NS_C} {c}", shell=True,
                               capture_output=True, text=True)
            if r.returncode:
                print(f"    [tc 失败] {r.stderr.strip()[:70]}")
    if cmds:
        lab.ipt("-N tng_out 2>/dev/null")
        for c in cmds:
            lab.ipt(c)
        lab.ipt("-A tng_out -j RETURN")
        lab.ipt("-I OUTPUT -j tng_out")

    t0 = time.time()
    p = lab.spawn("udp", 10001, 5001, SEC, 3000)
    try:
        out, err = p.communicate(timeout=SEC + 8)
    except subprocess.TimeoutExpired:
        os.killpg(os.getpgid(p.pid), 9)
        out, err = "", "timeout"

    # 链计数器
    counters = subprocess.run(f"ip netns exec {lab.NS_C} iptables -L tng_out -n -v -x",
                              shell=True, capture_output=True, text=True).stdout
    acc = drop_ = 0
    for line in counters.splitlines():
        f = line.split()
        if len(f) > 2 and f[0].isdigit():
            if f[2] == "ACCEPT":
                acc += int(f[0])
            elif f[2] == "DROP":
                drop_ += int(f[0])

    time.sleep(0.5)
    t1 = time.time()
    rows = lab.window_rows(t0 + 0.5, t1 + 1.5)   # 只统计本轮窗口
    if len(rows) >= 2:
        got = (rows[-1][1].get("udp:5001", 0) - rows[0][1].get("udp:5001", 0)) / (rows[-1][0] - rows[0][0])
    else:
        got = 0
    verdict = "断流!" if got < 5 else ("偏松" if got > TARGET * 1.6 else "OK")
    print(f"  {name:<40} 实收 {got:>8.0f} KB/s | 放行 {acc:>7} 丢 {drop_:>8} 包 | {verdict}")
    if err.strip() and err.strip() != "timeout":
        print(f"      client_err: {err.strip()[:100]}")
    return got


def main():
    print("== 准备环境 ==")
    lab.setup()
    srv, f = lab.start_server()
    try:
        print(f"\n== 目标：把 uid 10001 的上行压到约 {TARGET} KB/s，且不能断流 ==")
        trial("0) 基线：无规则")
        trial("1) 旧方案 -m limit 136/s burst 27",
              ["-A tng_out -m owner --uid-owner 10001 -m limit --limit 136/second "
               "--limit-burst 27 -j ACCEPT",
               "-A tng_out -m owner --uid-owner 10001 -j DROP"])
        trial("2) 新方案 -m statistic nth every 15",
              ["-A tng_out -m owner --uid-owner 10001 -m statistic --mode nth "
               "--every 15 --packet 0 -j ACCEPT",
               "-A tng_out -m owner --uid-owner 10001 -j DROP"])
        trial("3) 新方案 + 小包豁免(<=200B 放行)",
              ["-A tng_out -m owner --uid-owner 10001 -m length --length 0:200 -j ACCEPT",
               "-A tng_out -m owner --uid-owner 10001 -m statistic --mode nth "
               "--every 15 --packet 0 -j ACCEPT",
               "-A tng_out -m owner --uid-owner 10001 -j DROP"])
        trial("4) tc+htb 1600kbit + CLASSIFY（字节级）",
              tc_cmds=["tc qdisc add dev veth_c root handle 1: htb default 9999",
                       "tc class add dev veth_c parent 1: classid 1:1 htb rate 1000mbit ceil 1000mbit",
                       "tc class add dev veth_c parent 1: classid 1:9999 htb rate 1000mbit ceil 1000mbit",
                       "tc class add dev veth_c parent 1:1 classid 1:10 htb rate 1600kbit ceil 1600kbit"],
              cmds=["-t mangle -A OUTPUT -m owner --uid-owner 10001 -j CLASSIFY --set-class 1:10"])
    finally:
        lab.clear_rules()
        subprocess.run(f"ip netns exec {lab.NS_C} tc qdisc del dev veth_c root 2>/dev/null", shell=True)
        os.killpg(os.getpgid(srv.pid), 9)
        f.close()


if __name__ == "__main__":
    main()
