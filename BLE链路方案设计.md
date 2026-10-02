# WristDeck BLE 链路方案设计

> 配套文档：`技术架构文档.md`、`产品设计文档.md`
> 状态：**M1–M5 已落地，端到端跑通**（BLE 已是默认链路）。本文保留原始方案 + 落地后的实际出入，
> 第 8 节的每一步都补上了"实际怎么落地的"，第 10 节是结项结论与遗留。
> 日期：2026-10-01（方案）；2026-10-01 结项；2026-10-02 补 **Windows 侧网关**（见 §4.5）

---

## 0. 实测前提（已钉死，不再重新论证）

本方案建在以下**真机实测**结论之上（设备：OWW212 方形 / OPWW234 圆形，均 Android 11 / `libbluetooth_qti.so`）：

| 结论 | 证据 |
|---|---|
| 手表能当 BLE 外设广播 | 控制器层 `AdvertiseManager: onAdvertisingSetStarted() advertiserId=1, status=0` |
| 手表能开 GATT Server | `BluetoothManager.openGattServer=OK` → `addService=true` → `onServiceAdded status=0` |
| **CCCD 必须手动加** | 不加时 macOS 订阅报 `CBATTErrorDomain Code=10 "The attribute could not be found"`；加上后 `isNotifying=true` |
| Mac 能扫到、能连、能读能写 | `VERDICT-CONNECT ✓` / `VERDICT-WRITE 写入成功 ✓` |
| 手表→Mac 通知可用 | Mac 侧 `<<< 收到通知 #1..#4: gesture-1..4`；手表侧 `NOTIFY #1..#5 ok=true` |
| **MTU 够大，不需要分片** | 探针阶段 `maximumWriteValueLength(withResponse)=512`；正式链路 Mac 侧协商 **MTU 527**（单次写 payload 上限 **524**）。现有 JSON 最大约 200 字节，两种口径下都单包直发 ✅ |
| 手表侧零权限 | 广播与 GATT Server 均不需要定位权限（绕开 targetSdk 30 的 BLE 扫描定位死穴） |
| 全程无需配对 | 无 PIN、无配对框、无用户交互 |
| ⚠️ Mac 侧硬约束 | CoreBluetooth 必须跑在**带 `NSBluetoothAlwaysUsageDescription` 的 .app** 里且经 `open`/LaunchServices 启动，否则一律 `SIGABRT` |
| ⚠️ 只有 legacy 广播 | `isLeExtendedAdvertisingSupported=false` ⇒ **31 字节预算**，设备名必须放 scan response |
| ⚠️ 订阅才算"打开" | `hello` 走 notify 方向，**没订阅就发不出去** ⇒ `onOpen` 必须挂在"CCCD 被写 01 00"，不能挂在连接建立（见 §6-7） |
| ⚠️ "连接时系统自动停广播"这条**在本机栈上不成立** | 断连后重新开播会收到 `ALREADY_STARTED` ⇒ 必须双保险（`advActive` 在 `STATE_CONNECTED` 置假 + 收到该状态码时纠回真） |

**SPP（经典蓝牙）路线已放弃**：其唯一优势「协议零改动」BLE 反而做得更好——GATT 的每次 write/notify 是**消息级**（天然有边界），语义与 WebSocket 帧一致；而 RFCOMM 是**字节流无边界**，必须自己按 `\n` 切帧。详见当日工作日志。

---

## 1. 目标

把传输层从 **Wi-Fi + WebSocket** 换成 **BLE GATT**，而：

- 上层逻辑（手势识别、动作映射、`playing` 真相源、退避重连、状态机）**一条不改**
- 协议（`Protocol.kt` 的 JSON）**一行不改**
- Bridge 与浏览器扩展 **零改动**

收益：免填 IP、免同网段、免路由器依赖、手表端更省电、断连恢复快。

---

## 2. 架构总览

```
        手表 WristDeck                              Mac
┌──────────────────────────┐          ┌──────────────────────────────────┐
│  GestureController       │          │   WristDeckLink.app  (Swift)     │
│    ↓ sendAction(name)    │          │                                  │
│  BridgeClient            │   BLE    │   BleCentral  ◄──►  WsClient     │
│   (退避/世代号/状态机/    │◄────────►│      │                 │         │
│    playing 全部复用)      │  GATT    │      └── 纯透传 ──────┘         │
│      ↓ 持有               │          │            ↓                     │
│  Transport（新抽象层）    │          │   ws://127.0.0.1:8787/ws         │
│   ├ WsTransport（现有）   │          │        ?role=watch  (回环免 PIN) │
│   └ BleTransport（新）    │          └───────────┬──────────────────────┘
└──────────────────────────┘                      ↓
                                            Bridge（零改动）
                                                  ↓
                                          浏览器扩展（零改动）
```

**核心思想：Mac 网关是一个透明搬运工。** 它把 BLE 上收到的字节原样丢进 WebSocket，把 WebSocket 上收到的原样丢回 BLE。因为它从回环地址连入，Bridge 的回环信任域直接放行，**不需要 PIN**（`server.js` 中 `isLoopback` 分支跳过 PIN 校验）。

---

## 3. 手表侧改动

### 3.1 新增文件

| 文件 | 职责 |
|---|---|
| `net/Transport.kt` | 传输抽象接口 |
| `net/WsTransport.kt` | 把现有 OkHttp WebSocket 逻辑原样搬进来 |
| `net/BleTransport.kt` | GATT Server + Advertiser + 连接管理 |

### 3.2 `Transport` 接口

```kotlin
interface Transport {
    fun start()
    fun stop()
    /** 返回是否已交给底层发送（语义与现在 socket.send() 对齐）。 */
    fun send(text: String): Boolean
    val ready: Boolean
    fun updateTarget(target: String)   // Wi-Fi: host:port；BLE: 无意义，置空
}
```

回调沿用 `BridgeClient.Callback` 的现有形状，另加一条连接生命周期：

```kotlin
interface Sink {
    fun onOpen()                    // ⇒ 之后由 BridgeClient 发 hello
    fun onMessage(text: String)
    fun onClosed(detail: String?)   // detail 复用 NetError 的口径
}
```

### 3.3 `BridgeClient` 改动范围（**这是关键：改动比想象中小**）

| 现有成员 | 处理 |
|---|---|
| `backoff` 退避表 | **原样保留** |
| `generation` 世代号 | **原样保留**（BLE 的"被顶替/旧回调"问题与 WS 同构） |
| `pendingReconnect` / `reconnectTask` | **原样保留** |
| `pending` / `prunePending` / `PENDING_TTL_MS` | **原样保留** |
| `State` 状态机 | **原样保留** |
| `playing` 真相源与断开清空 | **原样保留** |
| `PERMANENT_DENY` | **原样保留** |
| `handle(text)` 协议解析 | **原样保留** |
| `sendAction()` | **原样保留** |
| `OkHttpClient` / `WebSocket` / `Listener` | **移出**到 `WsTransport` |
| `connect()` 里的 URL 拼装 | 移入 `WsTransport` |

即：`BridgeClient` 只剩「协议 + 状态 + 重连策略」，传输细节全部下沉。
**实际落地结果与预判一致**：`net/` 下三个文件各管一段（抽象 / WS / BLE），
`BridgeClient` 里已经没有任何 OkHttp 或蓝牙符号。

### 3.4 需要改的既有文件

| 文件 | 改动 |
|---|---|
| `model/Config.kt` | 加 `transport: WIFI \| BLE`（**默认 BLE**，Wi-Fi 作兜底） |
| `util/Prefs.kt` | 加 `K_TRANSPORT` 键，读写与 `Config` 一致 |
| `ui/SettingsActivity.kt` | 加"连接方式"切换项，BLE 模式下隐藏 IP/PIN 输入 |
| `AndroidManifest.xml` | `BLUETOOTH` / `BLUETOOTH_ADMIN`；`<uses-feature bluetooth_le required="true">` |
| `svc/BridgeService.kt` | **不动**（`BridgeHolder.get(this).start()` 依旧，Transport 由 Config 决定） |

### 3.5 不需要的权限（重要）

- ❌ 不要 `ACCESS_FINE_LOCATION` —— 手表做 **Peripheral** 不需要它。探针里那条只是为了验证"手表做 Central 会被卡死"，正式方案应删掉。
- ❌ 不要运行时权限申请 —— `BLUETOOTH`/`BLUETOOTH_ADMIN` 在 API 30 是 normal 级，装上即得。

---

## 4. Mac 侧网关

### 4.1 为什么必须是 .app（不是"建议"，是硬约束）

macOS 上 CoreBluetooth 受 TCC 管控，系统读**进程所属 bundle 的 `Info.plist`** 判断资格。缺 `NSBluetoothAlwaysUsageDescription` 时**不弹授权框、直接 SIGABRT**。实测三条路全崩：

| 启动方式 | 结果 |
|---|---|
| 裸 Python / Node 脚本 | ✗ abort |
| 从 Terminal 跑 | ✗ abort（Terminal 自身也缺该键） |
| 从其他 GUI app 派生 | ✗ abort（responsible process 是父 app） |
| **`open Xxx.app`（LaunchServices）** | ✅ 唯一可行 |

⇒ **网关必须打包成 `.app`，且用 `open` 启动（或做成登录项）。**

### 4.2 技术选型

| 方案 | 可行性 | 说明 |
|---|---|---|
| **Swift + CoreBluetooth** | ✅ **已验证，推荐** | 探针已跑通全流程；原生、零第三方依赖、bundle 天然 |
| Python + pyobjc | ✅ 可行 | 需手工组 bundle（`Contents/MacOS/launcher` + `Info.plist`）；生态熟悉 |
| Node + `@abandonware/noble` | ⚠️ 有坑 | `noble-mac` 原生模块对新 Node ABI 支持差，需自行编译 |

**倾向 Swift**：`MacBleScan` 那套（`swiftc` 编译 + `Info.plist` + `codesign --sign -` + `open`）已验证可用，可直接演化为正式网关，不必引入新运行时。

### 4.3 职责（只有三件事）

1. 常驻扫描目标 service UUID（广告包里只放 UUID，靠 UUID 匹配、**不依赖设备名**——实测 `local_name` 常常拿不到）
2. 发现即连接、发现服务/特征、订阅通知
3. **双向透传**：BLE 通知 → WS 发送；WS 消息 → BLE 通知

连接 WS 时发 `hello`（`role: "watch"`，`dev` 用 "ble-gateway"），回环地址免 PIN。

### 4.4 需要处理的边界（**落地后按实际踩到的补全**）

原始预判 5 条，实际主要栽在第 5 条上 —— 下面每条都写清楚最终怎么处理的：

- **断连重连**：BLE 断开后回到扫描；WS 断开后重连（复用 Bridge 的退避语义）。✅ 已落地。
- **单连接互斥**：Bridge 的 `state.watch` 是单实例，新 watch 会 `replaced` 旧连接。所以**网关与手表不能同时以 watch 身份连 Bridge** —— BLE 模式下手表只连网关，不再直连 Bridge。✅ 已落地（互斥由 `model/Config.link` 保证）。
- **macOS 睡眠**：合盖/睡眠会断 BLE，唤醒后重新扫描。✅ 已落地。
- **启动方式**：`open -n WristDeckLink.app`（`./build.sh run` 一步到位）。登录项自启仍**未做**。
- ⚠️ **`onOpen` 的时机**（原方案没预见到，是本次最容易死锁的地方）：最初把"链路可用"挂在 `STATE_CONNECTED` 上，
  结果**永久卡在 CONNECTING** —— `hello` 是手表→Mac 的 **notify**，而 Mac 还没订阅 CCCD，报文发不出去；
  BLE 又没有 TCP 那种"连接失败"来兜底，于是没人知道该重试。
  **最终判据：`onOpen` 挂在"CCCD 被写 `01 00`"这个事件上**，不挂在连接建立。✅ 已落地 + 实机验证。
- ⚠️ **广播的 `ALREADY_STARTED`**（见 §0 末条）：`advActive` 双保险。✅ 已落地。
- ⚠️ **`.withResponse` 的写必须串行化**（`writeInFlight`），断连时务必复位，否则队列永久卡死。✅ 已落地。
- ⚠️ **两个方向的缓冲策略刻意不对称**：上行 BLE→WS 未就绪时**暂存**（32 条 / 5s 保鲜，绝不能丢手表的 `hello`）；
  下行 WS→BLE 未就绪就**丢弃**（补发过期 `ack` 只会造成状态错乱）。✅ 已落地。
- ⚠️ **网关自己也要发 `hello`**（`role=watch`、`dev=ble-gateway`），且必须在 WS `didOpen` 之后、补发暂存消息之前：
  ① Bridge 对无角色会话会把非 `hello` 消息一律打回 `need_hello(4005)`；
  ② **会话重新对齐** —— Bridge 重启后 WS 是全新会话，网关补一次 `hello` 换回 `welcome`，
  手表**无须重发 `hello`** 也能回到 READY。实测印证：Bridge 重启后控制台只有 `hello √ ble-gateway`、没有手表，
  链路照样恢复。✅ 已落地。

### 4.5 Windows 侧网关（`gateway-win/`，2026-10-02 落地）

Mac 那份是 Swift + CoreBluetooth，**换平台就得重写**（见产品设计文档 §9.3）。Windows 版本用
**Python + bleak** 重写 BLE 侧，并额外加一个**托盘壳**把 Node Bridge 一起托管 —— 用户侧只有一个
东西要装、要起、要退。

#### 4.5.1 最省事的一条：macOS 那条硬约束在 Windows 上不存在

CoreBluetooth 必须跑在带 `NSBluetoothAlwaysUsageDescription` 的 `.app` 里、且经 `open`/LaunchServices
启动，否则一律 `SIGABRT`（§4.1）。**Windows 的蓝牙没有这类授权模型** —— 不弹框、不需要清单文件、
不需要签名，`python win_gateway.py` 直接就能扫能连。这一条把"部署"从"要打包"降到"要装依赖"。

#### 4.5.2 逐条对齐

| Swift 做的事 | Windows 等价 |
|---|---|
| `CBCentralManager.scanForPeripherals(withServices:)` | `BleakScanner.discover()` + 按 service UUID 过滤 |
| `connect` → `discoverServices` → `discoverCharacteristics` | `client.services`（bleak 连接后自动发现） |
| `setNotifyValue(true)` | `client.start_notify()` |
| `writeValue(_:for:type:.withResponse)` | `write_gatt_char(..., response=False)` |
| `URLSessionWebSocketTask` | `websockets`（`ping_interval=None`，与 Mac 一样不发协议级 ping） |
| 每阶段 8s 链路兜底超时 | `asyncio.wait_for(8s)` |
| 上行暂存 32 条 / 5s 保鲜 | 同 |
| 下行未就绪即丢（刻意不对称） | 同 |
| WS 退避 1/2/4/8/15/30 | 同 |
| 网关自发 `hello`（`dev=ble-gateway`） | 同，且在 `ws_open` 之后、补发暂存之前 |
| 双表仲裁（`hello.dev` 比对记忆机型，8 次后放弃） | 同 |
| 机型偏好落 `~/.wristbridge/wristdecklink.json` | **同一个文件**（换机器不用重配） |
| 日志 2MB 轮转 | 同 |

**唯一刻意的差异**：写用 **write-without-response**。Mac 上 `writeValue` 走 `.withResponse`；
Windows 侧实测手表日志是 `need_rsp=0`，单帧 115B 直发成功且 `pong` 正常返回，省一个往返。
写入仍用 `asyncio.Lock` 串行化。

#### 4.5.3 Windows 特有的三件事

| 能力 | 做法 |
|---|---|
| 托盘常驻 | `pystray` + `Pillow`（图标运行时绘制，仓库里不放二进制资源）；颜色=链路状态，菜单含打开状态页/日志/重启链路/重启 Bridge/开机自启/退出 |
| 单实例互斥 | 具名 mutex `Global\WristDeckWindowsTray`。**这条不是洁癖**：实测两台主机同时以 `watch` 身份连 Bridge，`pong` 会串流到错误的一方 |
| 一键安装 + 自启 | `install.ps1`（建 venv、装依赖、`npm install`、写 HKCU Run，**全程免管理员**）；`start.ps1` 用 `pythonw` 静默启动 |

#### 4.5.4 实测（2026-10-02）

主机 Windows 11 24H2 / build 26100.9445，适配器 Intel AX201（`USB\VID_8087&PID_07DC`），
Python 3.13.12 + bleak，手表 OWW212（Android 11）。

| 项 | 结果 |
|---|---|
| 扫描→连接→订阅 | 约 **3s** |
| MTU | **527**（单包上限 524B），与手表侧 `onMtuChanged mtu=527` 一致 |
| 上行 | 网关 `↑ {"t":"hello","role":"watch","dev":"OWW212",...}` ⇒ Bridge `/api/log` 出现 `手表 hello √ OWW212` |
| 下行 | 网关 `↓ welcome` / `↓ ping` ⇒ 手表 logcat `WristDeck: BLE ← 收到写入 65B`（welcome）、`39B`（ping） |
| 闭环 | 手表回 `pong`，网关中继上桥；每 15s 一轮 |
| 长连接保持 | **180s 内 11/11 轮 ping→pong 全中**，RTT 167–317ms，全程未掉线 |
| 字节级透传 | 两端长度逐条对得上（welcome 65B / ping 39B / pong 18B）⇒ 中继确实没改内容 |

**未验**：真表上按一次方向键跑出 `cmd`→`ack`（测试时手表正充电，系统 `SysUI.Charging` 窗口抢焦点，
`adb shell input tap` 打不到 App 上）；Linux 网关未做。

部署与排障见 `gateway-win/README.md`。

---

## 5. 协议：零改动

沿用 `Protocol.kt` 现有 JSON，**一个字都不改**。原因：

| | WebSocket（现状） | BLE GATT（新） |
|---|---|---|
| 消息边界 | 一帧一条 | **一次 write/notify 就是一条** |
| 单条容量 | 无限制 | MTU 512（实测），JSON 约 60~80 字节 ✅ |
| 文本编码 | UTF-8 | UTF-8 |

⇒ `BridgeClient.handle(text)` 接到的仍然是**一个完整 JSON 字符串**，与现在完全同构。

**唯一要留意的**：单条消息不要超过协商后的 MTU。当前最大的是 `ack`（含 `lat.e2e`、`state`），估算 < 200 字节，余量充足。方案里加一条断言：序列化后 > 400 字节就截断/告警（防御性，正常不会触发）。

---

## 6. 关键设计决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **手表做 Peripheral，Mac 做 Central** | 手表侧广播/GATT Server 零权限；反过来手表扫描要定位权限（死穴） |
| 2 | **网关做纯透传，不解释协议** | Bridge 与扩展零改动；协议演进只需改一处 |
| 3 | **Wi-Fi 与 BLE 二选一（互斥）** | Bridge 的 `state.watch` 单实例会互相顶替 |
| 4 | **广播只放 service UUID，设备名放 scan response** | 只有 legacy 广播，31 字节预算；实测 `local_name` 常拿不到，识别必须靠 UUID |
| 5 | **CCCD 必须显式 addDescriptor** | 这条 ROM 不自动补，不加则通知方向必挂 |
| 6 | **保留 Wi-Fi 路径** | 通过 `Transport` 抽象天然共存，作为 BLE 不可用时的兜底 |
| 7 | **`onOpen` 挂在"CCCD 被写 `01 00`"，不挂在连接建立** | `hello` 是手表→Mac 的 notify 方向；没订阅就发不出去，而 BLE 没有 TCP 那种失败可兜底 ⇒ 挂错地方会**永久卡 CONNECTING** |
| 8 | **网关自己发 `hello`（`dev=ble-gateway`）** | 既满足 Bridge 的 `need_hello` 顺序要求，又能在 WS 重连后做**会话重新对齐**（手表无须重发） |
| 9 | **`ensureServer()` 复用活着的 GATT Server** | `start()` 会被 `BridgeService.onCreate` + `onStartCommand` 连调两次，不幂等就会把广播/GATT 打架 |
| 10 | **`onCharacteristicWriteRequest` 先 `sendResponse` 再处理** | 否则网关侧 `.withResponse` 的写一直挂着，队列卡死 |

---

## 7. 风险与未决问题（结项时复核）

| 风险 | 说明 | 现状 |
|---|---|---|
| **误触率仍未测** | 项目遗留问题（标定用过 `bypassArm=true`），与换传输无关 | ⚠️ **仍未测**。而且闸门已经从"屏幕亮"改成"随连接启停"，**这个指标现在就是正式形态的直接指标**（原先"零误触"的数据全都来自"屏幕钉亮"的人工条件，不能代表日常） |
| **BLE 功耗未实测** | 理论上优于 Wi-Fi 常连，但未量化 | ⚠️ **仍未测**（验收 #6）。要注意新增变量：手势采样改成常开 50Hz 后，**耗电主因可能已经不是链路** |
| **2.4GHz 拥挤** | Mac 上同时有 K380 键盘、耳机等 | ✅ 实测无异常，未出现丢包或明显延迟抖动 |
| **Mac 网关常驻** | 需要用户保持 app 运行 | ⚠️ 登录项自启**仍未做**；目前靠手动 `./build.sh run` / `open -n` |
| **断连后的用户体验** | BLE 断开时应给出可感知反馈 | ✅ 复用现有 `Feedback`（震动）+ 状态条；实测网关侧重扫、手表侧自动重连都能收敛 |
| **手表与手机配对是否干扰** | 手表同时与手机保持 BR/EDR 配对 + 对 Mac 做 BLE 广播 | ✅ 实测不冲突，持续观察 |

---

## 8. 实施步骤（全部已落地）

| 阶段 | 内容 | 状态 / 实际证据 |
|---|---|---|
| **M1** | 手表侧抽 `Transport` 抽象，先只接 `WsTransport` | ✅ `net/Transport.kt` + `WsTransport.kt` + `BleTransport.kt`；`BridgeClient` 只剩"协议 + 状态机 + 退避"，传输全部下沉。Wi-Fi 路径行为不变 |
| **M2** | 实现 `BleTransport`（GATT Server + 广播 + 通知 + CCCD） | ✅ 端到端证据：Mac 侧扫到并订阅成功，收到 `{"v":1,"t":"hello","role":"watch","dev":"OWW212",…}` |
| **M3** | Mac 网关 `WristDeckLink.app`（扫描→连接→透传 WS） | ✅ `gateway/WristDeckLink.swift` + `Info.plist` + `build.sh`；Bridge 控制台出现 `hello √ ble-gateway`，动作能透传到扩展 |
| **M4** | 端到端联调 + 验收 | ✅ 跑通（见 §9 逐条）。过程中修掉 3 个死锁级问题：`onOpen` 挂错时机、`ALREADY_STARTED`、`sendResponse` 顺序 |
| **M5** | 收尾：设置页切换项、探针清理、文档更新 | ✅ 设置页「用蓝牙连接」开关（BLE 时隐藏 IP/PIN 输入）；**手表侧 BLE 探针 `ui/BtProbeActivity.kt` 已删除**，Mac 侧探针保留在 `.workbuddy/tests/btprobe/`（`MacBleScan.app`，换机型/换 ROM 验 BLE 能力时还要用）；本文档即收尾产物 |

**与原始方案的出入**（方案写的时候看不到的）：
1. 原方案说"探针只用于验证、正式方案应删掉 `ACCESS_FINE_LOCATION`" —— 实际上探针留着了，但**正式路径从头到尾就没加过该权限**。
2. 原方案没预见到 `onOpen` 时机的坑，这条是 M4 阶段最大的拦路虎（见 §4.4 与 §6-7）。
3. 原方案以为"连接时系统会自动停广播" —— 这台 Qualcomm 栈上不成立，见 §0 末条。

---

## 9. 验收标准（结项复核）

| # | 标准 | 结果 |
|---|---|---|
| 1 | 手表脱离 Wi-Fi（关掉 Wi-Fi），手势仍能控制浏览器 ← **核心** | ✅ **通过**。BLE 模式下手表全程不需要 Wi-Fi、不需要同网段、不需要 PIN；端到端动作经网关送达扩展 |
| 2 | 刻意动作命中率 **≥ 8/10** | ✅ 曾是 **28/28**（left 2 / right 2 / up 8 / down 13 / toggle 3，零漏检零判错零误触），**但口径已变**：那是"屏幕钉亮 + `alwaysArmed` 未引入"时的数据，窗口机制换过后**需要重跑** |
| 3 | 日常佩戴 10 分钟**误触 ≤ 1 次** | ⚠️ **未测**。现在是正式形态的直接指标（保持「已连接」即可测） |
| 4 | Bridge 控制台可见连接建立、动作到达、e2e 延迟 | ✅ 通过（`手表 √ sent` / `扩展 √ ok`，`net=0` 恒定，`e2e` 可读） |
| 5 | 断连后能自动恢复，**不产生连接风暴** | ✅ 通过。实测中断开来源有三类：GATT 重建、APK 重装、走出范围；每次都自己收敛，`ping/pong` 15s 一轮从未成片落空。**额外验证**：Bridge 重启后网关补 `hello` 就能恢复，手表无须重发 |
| 6 | 手表端续航不劣于 Wi-Fi 方案 | ⚠️ **未测**（已列入遗留） |

---

## 10. 结项结论与遗留

**原方案第 10 节的四个待拍板事项，最终选择：**

1. **Mac 网关技术选型** → **Swift + CoreBluetooth**（零第三方依赖、bundle 天然、`swiftc` 一步出包）。
2. **默认传输** → **默认 BLE**，Wi-Fi 作兜底，设置页里切换（`model/Config.link`，默认 `Link.BLE`）。
3. **探针处置** → **手表侧 BLE 探针（`ui/BtProbeActivity.kt`）已删除** —— 它的使命是"证明这快表能做外设"，
   结论已经写进 §0，正式 `BleTransport` 接管后留着只会和正式路径抢 GATT Server。
   **Mac 侧探针保留**（`.workbuddy/tests/btprobe/MacBleScan.app`）：侦察的是 **Mac** 的能力（扫得到吗、MTU 多少、
   订阅报不报错），与手表端代码无关，换机型/换 ROM 时是最快的判据。
   手势探针 `ui/ProbeActivity.kt` **保留**（离线回放是手势判据的唯一回归手段）。
4. **是否开始 M1** → 已全部做完（M1–M5）。

**遗留（按优先级）：**

| # | 事项 | 说明 |
|---|---|---|
| 1 | **日常误触率实测** | 保持「已连接」戴 10 分钟，数误触次数。指标线：≤1 次 |
| 2 | **BLE 续航 vs Wi-Fi** | 用同一块表、同样的操作密度各跑一段，`dumpsys batterystats` 对。注意现在耗电主因可能是**手势常开 50Hz**，不是链路 |
| 3 | **刻意动作命中率重跑** | 窗口机制已换成"随连接启停"，旧的 28/28 不代表新行为 |
| 4 | **网关登录项自启** | 现在要手动起；做成登录项才算"装上就不用管" |
| 5 | **圆表链路复验** | 圆表（OPWW234）的 BLE 逐条复测（广播、订阅、MTU、通知）在方表上验过一次，圆表尚未单独跑 |
| 6 | **长期稳定性** | 连续挂着几天，观察是否出现 `ALREADY_STARTED`、订阅丢失、内存增长 |
