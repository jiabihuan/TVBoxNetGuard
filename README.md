# 流量守卫 TV（TVBoxNetGuard）

给 **Android 电视 / 电视盒子** 用的流量监控与 **按应用上行限速** 工具。

核心诉求只有两个，但都做到很硬：

1. 实时看到整机、以及每个 App 的**上行 / 下行速率与累计流量**；
2. 给**指定 App** 设一个上行速度上限，或者干脆让它一个字节都传不出去 ——
   上限按 IP 层实际字节算（含包头），标 10 KB/s 线路上就不会超过 10 KB/s。

> 典型场景：盒子上的某个 App 在后台疯狂上传（P2P、日志上报、投屏/远程协助类应用），
> 把整条宽带的上行塞满，导致家里其他设备网络卡顿。装上它，把那个 App 的上行摁到 10 KB/s 或直接掐断。

---

## 它是怎么做到的

Android 上**没有公开的 API 能按 uid 限速**（`TrafficStats` 只能统计，`NetworkStatsManager` 需要系统权限）。
真正能落地的只有两条路，本项目两条都做了：

### 1. 免 Root 主引擎：`VpnService` + 用户态 NAT + 令牌桶

```
App ──▶ tun ──▶ [读包] ──▶ 五元组归会话 ──▶ uid 归属 ──▶ 令牌桶限速 ──▶ 真实 socket ──▶ 网络
     ◀──     ◀── [构造 IP 包写回]  ◀──────────── 下行数据 ◀──────────────────────────┘
```

- 建立 tun 接管全部 IPv4 流量（IPv6 默认也接管并丢弃，堵死绕过路径）；
- 每个 TCP 连接在用户态做 NAT 中转：自己维护 seq/ack、MSS 钳制、重排队列、FIN 半关闭；
- 每个 UDP 流做端口映射转发；
- **限速发生在写真实 socket 之前**，按 IP 层字节数扣令牌桶。

限速两种模式，可针对每个 App 单独选：

| 模式 | 行为 | 适用 |
| --- | --- | --- |
| **排队延迟发送**（默认关闭严格模式） | 令牌不够就阻塞等待，直到配额到账再发 | TCP，速率最平滑，不触发 RTO 退避，吞吐最大化 |
| **严格丢包**（推荐，默认开启） | 令牌不够**直接丢包**，绝不允许任何突发 | 要"死死摁住"的场景；UDP 一律走这种模式 |

严格模式的代价是 TCP 会触发重传退避，实测吞吐会低于设定值 —— **换来的是绝无突发**，
这正符合"很严格很严格"的诉求。

### 2. Root 加固引擎：`iptables` 内核级硬闸（可选）

有 root 时，额外在内核 `OUTPUT` 链上按 uid 落规则：

```bash
# 彻底断网
iptables -A tng_out -m owner --uid-owner <uid> -j DROP
# 限速（limit 按包计数，按 1500 字节满包折算成 pps）
iptables -A tng_out -m owner --uid-owner <uid> -m limit --limit N/second --limit-burst B -j ACCEPT
iptables -A tng_out -m owner --uid-owner <uid> -j DROP
```

用户态进程被杀，内核规则还在，这是第二道保险。

---

## 快速开始

### 安装

```bash
git clone https://github.com/jiabihuan/TVBoxNetGuard.git
cd TVBoxNetGuard
./gradlew assembleDebug
adb install -f app/build/outputs/apk/debug/app-debug.apk
```

或者直接用 Android Studio 打开项目，Run 到盒子上（需要 `adb connect <盒子IP>`）。

### 使用

1. 打开应用，点 **启动限速引擎**，在系统弹窗里点确定授权 VPN；
2. 进入 **应用限速设置**，选中目标 App；
3. 填上行上限（KB/s），或用预设：**不限 / 10 KB/s / 50 KB/s / 彻底禁止**；
4. 保存后 **1 秒内热生效**，不用重启引擎，已有连接也会被立即掐断重连。

设置项说明：

| 设置 | 说明 |
| --- | --- |
| 开机自动启动 | 盒子重启后自动接管流量 |
| 看门狗 | 服务被系统或清理软件杀掉后 60 秒内自动拉起 |
| 全局严格模式 | 所有超限流量一律丢包 |
| Root 加固 | 额外启用 iptables 内核级拦截（无 root 自动跳过） |
| 接管并阻断 IPv6 | **建议保持开启**，关掉后 App 可走 IPv6 绕过全部限速 |
| 全局上行/下行限速 | 整机兜底限速，与单应用规则叠加生效（取更严的那个） |

---

## 严格性到底强在哪

| 手段 | 说明 |
| --- | --- |
| 全流量接管 | 默认连 IPv6 一起接管并丢弃，堵死"绕过 VPN"的口子 |
| 按 IP 层字节计 | 限速与统计都算上 IP/TCP/UDP 头，不玩"净荷字节"的文字游戏 |
| 严格模式丢包 | 配额用尽直接丢，桶容量压到 1/20 秒的量，突发窗口极小 |
| 有界队列 | 排队上限 64 包，超了就丢，不会把内存堆爆也不会无限缓冲 |
| 规则热生效 | 保存后 1 秒内同步，已有连接立即掐断，不存在"老连接继续跑满" |
| 内核加固 | root 模式下 iptables 在内核拦，用户态进程死了也拦得住 |
| 自保护 | 前台服务常驻 + `STOP_STICKY` + 开机广播 + 看门狗定时拉起 |

---

## 已知限制（实话实说）

- **吞吐上限**：用户态转发每包都要过一遍 JVM，千兆宽带跑不满，用在盒子（百兆/千兆、实际上传几十 Mbps）上完全够。
- **iptables 限速按包不按字节**：`iptables` 的 `limit` 匹配器只数包，这里按 1500 字节满包折算成 pps，
  实际速率通常**低于**设定值。内核 `tc` + `CLASSIFY`/`htb` 才是字节级整形，但电视盒子内核（3.10/4.9 居多）
  普遍把这些模块裁掉了，实测可用性很低，所以不依赖它。
- **Android 12+ 后台启动限制**：开机自启与看门狗在某些定制系统上可能被系统拦下，代码做了兜底不会崩溃，
  实在拉不起来时手动打开一次应用即可。
- **QUIC（HTTP/3）**：基于 UDP，走的是丢包式限速，速率控制不如 TCP 精确，但上限一样卡得住。
- **uid 归属失败时**：极少数情况下拿不到 owner uid，这类流量**只统计不限速**，避免误杀其他应用。

---

## 工程结构

```
app/src/main/java/com/jiabihuan/tvnetguard/
├── vpn/
│   ├── GuardVpnService.kt   主引擎：建 tun、读包分发、定时同步规则与通知
│   ├── SessionManager.kt    会话表（五元组 -> 连接）与过期回收
│   ├── TcpSession.kt        用户态 TCP 中转：seq/ack 重写、背压、限速、FIN 处理
│   ├── UdpSession.kt        用户态 UDP NAT
│   ├── Packets.kt           IPv4/TCP/UDP 解析与构造、校验和重算
│   ├── TokenBucket.kt       令牌桶（阻塞排队 / 非阻塞丢弃两种语义）
│   ├── Limiter.kt           按 uid 管理令牌桶 + 全局限速 + 规则热更新
│   ├── UidResolver.kt       连接归属 uid（API29+ /proc/net 双路径）
│   ├── RootFirewall.kt      iptables 加固引擎
│   └── Executors.kt         线程池与串行执行器
├── data/                    规则、统计、应用列表
├── ui/                      TV 遥控器友好的三个界面
└── util/                    配置、格式化、root shell
```

---

## 技术参考

实现过程中参考了以下公开项目的**设计思路**（本项目为独立实现，非其衍生作品）：

- [M66B/NetGuard](https://github.com/M66B/NetGuard) — 免 root 按应用防火墙，uid 归属与连接缓存策略
- [xjasonlyu/tun2socks](https://github.com/xjasonlyu/tun2socks)、[heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — 用户态 TCP 状态机的常见坑（MSS 钳制、窗口、乱序处理）

---

## 许可

MIT
