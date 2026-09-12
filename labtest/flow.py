#!/usr/bin/env python3
"""
星河守卫 限速内核规则实测 —— 流量发生器 / 接收器

用法：
  python3 flow.py server-up                # 在服务端 netns 起 TCP/UDP 接收器，打印每秒速率
  python3 flow.py udp   <port> <kbps> <sec> # 以指定速率发 UDP 大包（模拟 PCDN 分片上传）
  python3 flow.py tcpup <port> <sec>        # TCP 上传（尽力发）
  python3 flow.py tcpdown <port> <sec>      # TCP 下载（接收，会产生大量上行 ACK）

客户端进程需先以 root 进入 netns，再由本脚本降权到目标 uid，
以便 iptables 的 -m owner --uid-owner 能按 uid 区分（对应 Android 的 App uid）。
"""
import json
import os
import socket
import sys
import threading
import time

SERVER_IP = "10.9.0.1"
PAYLOAD = 1400          # UDP 载荷：模拟 PCDN 视频分片，IP 包约 1428 字节（远大于 200 字节豁免阈值）
STATS = {}
LOCK = threading.Lock()
STOP = threading.Event()


def bump(key, n):
    with LOCK:
        STATS[key] = STATS.get(key, 0) + n


# ---------------- 服务端 ----------------
def udp_sink(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 << 20)
    s.bind((SERVER_IP, port))
    s.settimeout(0.3)
    while not STOP.is_set():
        try:
            data, _ = s.recvfrom(65535)
            bump(f"udp:{port}", len(data))
        except socket.timeout:
            continue
    s.close()


def tcp_sink(port):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((SERVER_IP, port))
    srv.listen(8)
    srv.settimeout(0.3)
    while not STOP.is_set():
        try:
            c, _ = srv.accept()
        except socket.timeout:
            continue
        threading.Thread(target=_tcp_rx, args=(c, port), daemon=True).start()
    srv.close()


def _tcp_rx(c, port):
    c.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 << 20)
    while True:
        try:
            b = c.recv(65536)
        except OSError:
            break
        if not b:
            break
        bump(f"tcp:{port}", len(b))
    c.close()


def tcp_source(port):
    """下行源：给客户端灌数据（客户端会回 ACK，用来验证上行限速是否饿死下载）"""
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((SERVER_IP, port))
    srv.listen(8)
    srv.settimeout(0.3)
    while not STOP.is_set():
        try:
            c, _ = srv.accept()
        except socket.timeout:
            continue
        threading.Thread(target=_tcp_tx, args=(c,), daemon=True).start()
    srv.close()


def _tcp_tx(c):
    buf = b"x" * 64000
    try:
        while not STOP.is_set():
            c.sendall(buf)
    except OSError:
        pass
    finally:
        try:
            c.shutdown(socket.SHUT_WR)
        except OSError:
            pass
        c.close()


def run_server():
    for p in (5001, 5002, 5003):
        threading.Thread(target=udp_sink, args=(p,), daemon=True).start()
    for p in (5101, 5102, 5103):
        threading.Thread(target=tcp_sink, args=(p,), daemon=True).start()
    for p in (5201, 5202, 5203):
        threading.Thread(target=tcp_source, args=(p,), daemon=True).start()

    t0 = time.time()
    while not STOP.is_set():
        time.sleep(0.5)
        with LOCK:
            cur = dict(STATS)
        # 输出累计 KB（便于测试脚本按时间窗做差值算速率）
        parts = [f"{k}={cur.get(k, 0)//1024}" for k in sorted(cur)]
        print(f"CUM {time.time():.2f} " + " ".join(parts), flush=True)
        # 同时打一行人眼可读的瞬时速率
        rates = []
        for k in sorted(cur):
            d = (cur.get(k, 0) - last.get(k, 0)) / 1024.0 / 0.5
            rates.append(f"{k}={d:.0f}KB/s")
        last = cur
        print(f"RATE {time.time():.2f} " + " ".join(rates), flush=True)


# ---------------- 客户端 ----------------
def drop_uid(uid):
    if uid > 0:
        os.setgroups([])
        os.setgid(uid)
        os.setuid(uid)
    return os.getuid()


def do_udp(port, kbps, sec, srv):
    """按 kbps 匀速发 UDP 大包。

    必须用**非阻塞** socket：一旦限速生效，内核发送队列会堆积，
    阻塞式 sendto 会把"应用发出的量"反向压到限速值以下（本地背压），
    测出来的就不是"限速拦住多少"而是"应用被背压到多少"。
    非阻塞下 EAGAIN 直接丢弃，服务端收到的才是真实放行量。
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 8 << 20)
    s.setblocking(False)
    # 刻意不 connect()：connect 过的 UDP socket 会接收对端 ICMP 错误并抛
    # ConnectionRefusedError，把压测进程直接搞崩
    budget = kbps * 1024
    t_end = time.time() + sec
    sent = 0
    eagain = 0
    errs = 0
    while time.time() < t_end:
        slot_end = time.time() + 0.02
        used = 0
        while used < budget * 0.02 and time.time() < slot_end:
            try:
                s.sendto(b"p" * PAYLOAD, (srv, port))
                sent += PAYLOAD
                used += PAYLOAD
            except BlockingIOError:
                eagain += PAYLOAD
            except OSError:
                errs += 1
    s.close()
    print(json.dumps({"tried_kbps": round(sent / 1024 / sec),
                      "eagain_kbps": round(eagain / 1024 / sec),
                      "err": errs}))


def do_tcpup(port, sec, srv):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 8 << 20)
    s.connect((srv, port))
    buf = b"u" * 64000
    t_end = time.time() + sec
    sent = 0
    while time.time() < t_end:
        try:
            s.sendall(buf)
            sent += len(buf)
        except OSError:
            break
    try:
        s.shutdown(socket.SHUT_WR)
    except OSError:
        pass
    s.close()
    print(json.dumps({"tried_kbps": round(sent / 1024 / sec)}))


def do_tcpdown(port, sec, srv):
    """下载：本端只收不发，唯一的上行流量就是 TCP ACK —— 验证 ACK 是否被限速饿死"""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 << 20)
    s.connect((srv, port))
    t_end = time.time() + sec
    got = 0
    s.settimeout(1.0)
    while time.time() < t_end:
        try:
            b = s.recv(65536)
        except socket.timeout:
            continue
        if not b:
            break
        got += len(b)
    s.close()
    print(json.dumps({"rx_kbps": round(got / 1024 / sec)}))


def main():
    mode = sys.argv[1]
    uid = int(os.environ.get("SIM_UID", "0"))
    real = drop_uid(uid)
    sys.stderr.write(f"# running as uid={real}\n")

    if mode == "server-up":
        run_server()
    elif mode == "udp":
        do_udp(int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4]), os.environ.get("SRV", SERVER_IP))
    elif mode == "tcpup":
        do_tcpup(int(sys.argv[2]), float(sys.argv[3]), os.environ.get("SRV", SERVER_IP))
    elif mode == "tcpdown":
        do_tcpdown(int(sys.argv[2]), float(sys.argv[3]), os.environ.get("SRV", SERVER_IP))


if __name__ == "__main__":
    main()
