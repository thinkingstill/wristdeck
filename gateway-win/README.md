# WristDeck Windows 网关（BLE）

Mac 版网关 `gateway/WristDeckLink.swift` 的 Windows 实现：用 **Python + bleak** 重写 BLE 侧，
外加一个**托盘壳**把 Node Bridge 一起托管。Windows 上蓝牙不需要 macOS 那套
`Info.plist` / TCC 授权，所以没有 `.app` 打包问题 —— 直接跑脚本就行。

```
手表（BLE 外设）  ⟷  gateway-win（Python + bleak）  ⟷  ws://127.0.0.1:8787  ⟷  bridge（Node）  ⟷  浏览器扩展
                              ↑
                     托盘常驻，同时以子进程守护 bridge/server.js
```

网关和 Mac 版一样是**纯透传**：不认识 `hello` / `cmd` / `ack`，只搬字节。
所以 Bridge、浏览器扩展、手表 APK **零改动**。

---

## 1. 前置条件

| 组件 | 要求 | 本机实测 |
|---|---|---|
| 系统 | Windows 10 1809+ / Windows 11（bleak 走 WinRT 蓝牙 API） | Windows 11 24H2 / build 26100.9445 |
| 蓝牙 | 适配器需支持 **BLE Central**（几乎都支持）；系统蓝牙开关必须打开 | Intel AX201（`USB\VID_8087&PID_07DC`），驱动 Intel 23.100.0 |
| Python | **3.10+**（用到 `asyncio` 的现代写法与 `X \| None` 注解） | 3.13.12 |
| Node.js | **≥ 18**（`bridge/server.js` 用了 ESM + 顶层 `await`），只有托盘托管 Bridge 时才需要 | 22.22.2 |
| 手表 | OPPO Watch 3（OWW212 方形 / OPWW234 圆形）/ Android 11 | OWW212，Android 11（API 30） |

> 不需要管理员权限，不需要装驱动，不需要改防火墙（纯 BLE 链路时）。

---

## 2. 安装

```powershell
git clone https://github.com/thinkingstill/wristdeck.git
cd wristdeck
powershell -ExecutionPolicy Bypass -File gateway-win\install.ps1
```

脚本会依次：建 `.venv` → 装 4 个 Python 依赖 → 在 `bridge\` 里 `npm install`
→ 写 HKCU 开机自启项 → 打印自检结果。可选开关：

| 开关 | 作用 |
|---|---|
| `-NoAutostart` | 不写自启项 |
| `-NoVenv` | 不建虚拟环境，直接装进当前 Python |
| `-NoBridgeDeps` | 跳过 `npm install` |
| `-PythonExe <路径>` | 指定 Python 解释器 |
| `-Firewall` | 额外放行 8787 入站（**只有**手表走 Wi-Fi 直连时才需要，需要管理员） |

---

## 3. 日常使用

```powershell
gateway-win\start.ps1          # 无控制台，进托盘
gateway-win\start.ps1 -Console # 带控制台，日志滚屏（调试用）
gateway-win\stop.ps1           # 兜底停止（正常请用托盘菜单「退出」）
```

托盘图标颜色 = 链路状态：

| 颜色 | 含义 |
|---|---|
| 🟢 绿 | BLE 链路就绪（已订阅通知） |
| 🟡 琥珀 | 正在连接 / 订阅 |
| 🔵 蓝 | 正在扫描（没扫到手表广播） |
| ⚪ 灰 | 空闲 / 链路已清理 |
| 🔴 红 | 出错（看日志） |

右键菜单：**打开状态页**（双击图标也是）、**打开日志目录**、**重启 BLE 链路**、
**重启 Bridge**、**开机自启**（勾选开关）、**退出**。

> ⚠️ **手表侧要切到「用蓝牙连接」**（手表设置页 → 连接方式），否则手表会走 Wi-Fi 直连，
> 而 Bridge 的 `state.watch` 是单实例 —— 两条链路会互相顶替（`code 4006`）。
>
> ⚠️ **同一块表只能有一个网关连着**。如果 Mac 上的 `WristDeckLink` 还在跑，先关掉它，
> 否则两边会抢同一块表。本网关用具名 mutex 保证 Windows 侧只跑一个实例，但管不了 Mac。

---

## 4. 文件说明

| 文件 | 职责 |
|---|---|
| `win_gateway.py` | **BLE↔WS 透传内核**。可独立运行（`python win_gateway.py`），托盘也用它 |
| `tray.py` | 托盘壳：内嵌跑内核 + 以子进程守护 `bridge/server.js` + 开机自启开关 + 单实例互斥 |
| `install.ps1` / `start.ps1` / `stop.ps1` | 安装 / 启动 / 停止 |
| `requirements.txt` | `bleak` `websockets` `pystray` `pillow` |

独立运行内核（不带托盘、不托管 Bridge，适合排障）：

```powershell
.venv\Scripts\python.exe gateway-win\win_gateway.py            # 前台，日志滚屏
.venv\Scripts\python.exe gateway-win\win_gateway.py --status   # 只打印配置与偏好
.venv\Scripts\python.exe gateway-win\win_gateway.py --device OPWW234   # 钉死用哪块表
```

---

## 5. 配置

`%USERPROFILE%\.wristbridge\gateway-win.json`（首次运行自动生成）：

| 字段 | 默认 | 说明 |
|---|---|---|
| `bridgePort` | `8787` | Bridge 端口 |
| `bridgeHost` | `127.0.0.1` | Bridge 主机。**保持回环**才能免 PIN |
| `nodePath` | `null` | `node.exe` 路径，空则从 `PATH` 找 |
| `bridgeScript` | `null` | `server.js` 路径，空则用 `<仓库>/bridge/server.js` |
| `bleKeepaliveSec` | `0` | BLE 保活心跳间隔，`0` = 关（与 Mac 版一致，不额外发报文） |
| `device` | `null` | **手工指定机型**（如 `OPWW234`），设了就永远优先找它 |

机型偏好存在 `%USERPROFILE%\.wristbridge\wristdecklink.json` —— **与 Mac 版共用同一个文件**，
所以在 Mac 上认过的那块表，换到 Windows 不用重配。

日志：`%USERPROFILE%\.wristbridge\logs\gateway.log`（超过 2MB 自动轮转成 `.log.1`）。
Bridge 子进程的 stdout 也汇进这条流水，前缀 `[bridge]`。

---

## 6. 与 Mac 版的对应关系

语义逐条对齐，差的只有技术栈：

| Mac（CoreBluetooth） | Windows（bleak / WinRT） |
|---|---|
| `CBCentralManager.scanForPeripherals(withServices:)` | `BleakScanner.discover()` + 按 service UUID 过滤 |
| `connect` → `discoverServices` → `discoverCharacteristics` | `client.services`（连接后自动发现） |
| `setNotifyValue(true)` | `client.start_notify()` |
| `writeValue(_:for:type:.withResponse)` | `write_gatt_char(..., response=False)` |
| `URLSessionWebSocketTask` | `websockets` |
| 按阶段各 8s 的链路兜底超时 | 每阶段 `asyncio.wait_for(8s)` |
| 上行暂存 32 条 / 5s 保鲜 | 同 |
| 下行未就绪即丢（刻意不对称） | 同 |
| WS 退避 1/2/4/8/15/30 | 同 |
| 网关自发 `hello`（`dev=ble-gateway`） | 同 |
| 双表仲裁（`hello.dev` 比对记忆机型，8 次后放弃） | 同 |
| 日志 2MB 轮转 | 同 |
| `.app` bundle + `open` 启动（TCC 硬约束） | **不需要**，直接跑脚本 |

两处**刻意的差异**：

1. **写用 write-without-response**。Mac 上 `writeValue` 走 `.withResponse`；Windows 侧实测手表
   日志是 `need_rsp=0` 且 `pong` 正常返回，单帧 115B 直发成功，省一个往返。写入仍串行化排队。
2. **地址绝不缓存**。手表 LE 地址会随机轮换（一晚实测过 4 个不同地址），所以每一轮都重新扫描、
   按 service UUID 命中 —— 这一点 Mac 版也是同样做法。

---

## 7. 本机实测结论（2026-10-02）

主机 Gigabyte B85N PHOENIX WIFI / Windows 11 24H2 26100.9445 / Python 3.13.12 /
bleak + websockets 17.1 / 手表 OPPO Watch 3（OWW212，Android 11）。

| 项目 | 结果 |
|---|---|
| 适配器能力位 | `IsLowEnergySupported=True`、`IsCentralRoleSupported=True`、`MaxAdvertisementDataLength=31`（legacy） |
| 扫描命中 | 按 service UUID 命中，RSSI −52 ~ −55，`local_name='OPPO Watch 3 9748'` |
| 连接 + 订阅 | 扫描→连接→订阅合计约 **3s** |
| MTU | **527**（单包 ATT 负载上限 524B），与手表侧 `onMtuChanged mtu=527` 一致 |
| 上行（BLE→WS→Bridge） | 网关日志 `↑ {"t":"hello","role":"watch","dev":"OWW212",...}`；Bridge 侧 `/api/log` 出现 `手表 hello √ OWW212` |
| 下行（WS→BLE→手表） | 网关 `↓ welcome` / `↓ ping`；手表 logcat `WristDeck: BLE ← 收到写入 65B`（welcome）、`39B`（ping） |
| 双向闭环 | 手表收到 ping 后回 `pong`，网关中继上桥 —— 每 15s 一轮 |
| 字节级透传 | 两侧长度逐条对得上（65B / 39B），证明中继没有改动内容 |
| 托盘托管 | 一个托盘图标同时拉起 Bridge（`0.0.0.0:8787` LISTENING）+ 内嵌网关；Bridge stdout 汇入同一日志 |
| 长连接保持（180s） | **11/11 轮 ping→pong 全中**，RTT 167–317ms（均值约 190ms），全程 182s 未掉线 |
| 重连压测（此前） | 5/5 全过，每轮 MTU 均 527，RTT 105–219ms |

**尚未验证**（如实标注，不要当已通过）：

| 项目 | 原因 |
|---|---|
| 真实 `cmd` → `ack`（在真表上按一下方向键） | 测试时手表正充电，系统 `SysUI.Charging` 窗口抢占焦点，`adb shell input tap` 打不到 App 上。链路本身已由 `hello`/`welcome`/`ping`/`pong` 双向证实 |
| 扩展 → 网页真的换了视频 | 与本次移植无关（Mac 上已验证），且需要人在浏览器前操作 |
| 长时间稳定性（连续挂几天） | 未跑。3 分钟长连接保持已过（11/11），但小时级/天级未验 |
| 开机自启项在真实重启后的行为 | 注册表项写入逻辑已实现，但没做过重启验证 |
| 圆形表 OPWW234 | 本次只验了方表 OWW212 |

---

## 8. 排障

| 现象 | 原因 / 处理 |
|---|---|
| 托盘图标一直是**蓝色**（扫描中），日志刷「扫描到 N 个设备，无一命中目标 service」 | 手表没在广播。最常见是**手表处于「断开」状态**或**灭屏太久被系统掐掉服务**。唤醒手表、打开 App、确认它是「已连接」状态；日志里会明确写出这句话 |
| 图标**灰色** + 状态页 `/api/status` 的 `watch` 是 `null` | Bridge 在，但网关没连上手表。看日志里是"未扫到"还是"连接失败" |
| `WS 接收中断：ConnectionClosedError` 每隔约 45s 一次 | **正常**。手表没连上时没人回 `pong`，Bridge 30s 无消息就断开该会话，网关按退避重连。手表连上后就不再发生 |
| `adb devices` 里手表是 `unauthorized` | 手表上点「允许 USB 调试」 |
| 两块表都在附近，连错了一块 | 网关会按 `hello.dev` 仲裁：连错就断开重扫，最多 8 次后接受眼前这块。想钉死就在配置里写 `"device": "OPWW234"` |
| 状态页打得开但手表连不上 | 确认手表设置页的「连接方式」是**蓝牙**，而不是 Wi-Fi 直连 |
| 点「连接」后很快又断 | 检查是不是 Mac 上的网关也在跑，两边抢同一块表 |

---

## 9. 已知边界

- **BLE 只有 legacy 广播**：`IsExtendedAdvertisingSupported=False` ⇒ 广告包 31 字节预算，
  设备名必须放 scan response，实测有时 `local_name` 拿不到 —— 所以**识别一律靠 service UUID**。
- **手表广播不连续**：灭屏一段时间后手表可能停广播（这台 Qualcomm 栈上的现象，不是 Windows 的问题）。
  网关的做法是**持续重扫**（每轮 8s 扫描 + 1s 间隔），而不是只扫一次，所以手表一回来就能连上。
- **Wi-Fi 与 BLE 互斥**：Bridge 的 `state.watch` 单实例，两条链路会互相顶替。
- **托盘「开机自启」用 HKCU Run**（无需管理员）。若要"开机即起、无需登录"，得改成计划任务或
  Windows 服务，同时要处理会话隔离下的蓝牙访问 —— 本次没做。
