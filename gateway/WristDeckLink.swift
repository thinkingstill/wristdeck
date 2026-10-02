// WristDeckLink —— WristDeck 的 Mac 侧 BLE 网关（纯透传）
//
// 职责只有三件事：
//   1. 常驻扫描手表的 service UUID（只靠 UUID，**不靠设备名** —— 实测 local_name 常拿不到），
//      发现即连接 → 发现服务/特征 → 订阅通知
//   2. 连本机 Bridge 的 WebSocket。回环地址命中服务端 `isLocalIp()` 的回环信任域，**免 PIN**
//   3. 双向透传：BLE 通知 → WS 文本帧；WS 文本帧 → BLE write
//
// 它**故意不解释协议** —— 不认识 hello/cmd/ack，只搬字节。所以 Bridge 与浏览器扩展零改动，
// 协议将来要演进也只需要动手表侧一处（Protocol.kt）。
//
// ────────────────────────────────────────────────────────────────────────────
// ⚠️ 为什么必须是带 NSBluetoothAlwaysUsageDescription 的 .app，且用 `open` 启动
//
// macOS 的 CoreBluetooth 受 TCC 管控，系统要读"责任进程"所属 bundle 的 Info.plist。
// 缺那个键时**不弹授权框、直接 SIGABRT**（实测 exit=134，连 print 都来不及输出）。
// 实测三条路全崩：裸脚本 / 从 Terminal 起（Terminal 自己也缺该键）/ 直接跑 bundle 内可执行文件。
// 唯一可行：让 LaunchServices 启动（`open -n WristDeckLink.app`），进程才自己当责任主体。
// 所以别把本文件当脚本 `swift WristDeckLink.swift` 跑 —— 会 abort。请用 build.sh。
// ────────────────────────────────────────────────────────────────────────────
//
// ⚠️ Wi-Fi 与 BLE 互斥：Bridge 的 `state.watch` 是单实例，谁后连谁把前一个顶掉（code 4006）。
// 所以网关在跑的时候，手表必须切到"用蓝牙连接"；反过来手表用 Wi-Fi 直连时就不要开网关。
//
// 日志：同时写 stdout 与 ~/Library/Logs/WristDeckLink.log（`open` 起的进程拿不到 stdout）。

import Foundation
import CoreBluetooth

// ---------------- 配置 ----------------

let serviceUUID = CBUUID(string: "7A1B0001-6F3C-4B2E-9A55-1D0E2F3A4B5C")
let charUUID = CBUUID(string: "7A1B0002-6F3C-4B2E-9A55-1D0E2F3A4B5C")

/// 以 `role=watch` 连本机 Bridge。回环地址 ⇒ 服务端跳过 PIN 校验，`pin` 留空即可。
///
/// ⚠️ 这个 hello 不是"多此一举"，它是**会话重新对齐的关键**：
/// WS 断开重连后 Bridge 那边是一个全新的、没有 role 的会话；网关补发 hello 换来一个 `welcome`，
/// 再透传给手表 —— 手表那边就算没重发自己的 hello 也能回到 READY。
/// 没有它，WS 抖一次手表就会永久停在"连接中"。
let bridgeURL = URL(string: "ws://127.0.0.1:8787/ws?role=watch")!
let helloJSON = #"{"v":1,"t":"hello","role":"watch","dev":"ble-gateway","pin":""}"#

/// 实测 MTU 527 ⇒ ATT 单包上限 524B。留点余量，超了在日志里看得见（正常不会命中）。
let maxPayloadBytes = 500

/// 上行（BLE→WS）在 WS 未就绪时的暂存上限与保鲜期。
/// 手表一订阅就会立刻发 hello，丢了它就要等下一轮握手；但太老的消息补发出去只会造成
/// "用户 20 秒前按的手势现在才执行"，所以宁可丢。
let upQueueLimit = 32
let upQueueTTL: TimeInterval = 5

let logPath = (NSHomeDirectory() as NSString).appendingPathComponent("Library/Logs/WristDeckLink.log")

// ---------------- 持久状态：记住是哪块表 ----------------
//
// 为什么需要它：两块表播的是**同一个 service UUID**，而广播里没有任何能区分机型的字段
// （实测 2026-10-01：`CBAdvertisementDataManufacturerDataKey` / `ServiceDataKey` 都是空，
// `p.name` 也区分不了 —— 都是"OPPO Watch 3 xxxx"）。网关只维护一条连接，只能"谁先被扫到就连谁"。
// 后果不是"随机连接"，而是**用户手里那块表永久卡在"连接中"**、界面上毫无提示。
//
// 拿到 `hello` 的那一刻才知道对面是哪一块（`dev` 字段），所以仲裁点就在这里：
// 连错了就断开重扫，直到扫到想要的那块为止（见 `reject(_:)`）。
//
// 文件：`~/.wristbridge/wristdecklink.json`
//   { "device": "OPWW234", "lastDevice": "OPWW234" }
//   - `device`     ：**手工指定**，设了就永远优先（想钉死一块、或两块都想用但不想重装表）
//   - `lastDevice` ：网关自己维护，每次握手成功都会更新
//
// 缺文件 / 坏文件都当"没偏好"，绝不影响主链路。
struct GatewayState: Codable {
    var device: String?
    var lastDevice: String?
}

let stateDir = (NSHomeDirectory() as NSString).appendingPathComponent(".wristbridge")
let statePath = (stateDir as NSString).appendingPathComponent("wristdecklink.json")

func loadState() -> GatewayState {
    guard let d = FileManager.default.contents(atPath: statePath),
          let s = try? JSONDecoder().decode(GatewayState.self, from: d) else { return GatewayState() }
    return s
}

func saveState(_ s: GatewayState) {
    try? FileManager.default.createDirectory(atPath: stateDir, withIntermediateDirectories: true)
    if let d = try? JSONEncoder().encode(s) {
        try? d.write(to: URL(fileURLWithPath: statePath))
    }
}

// ---------------- 日志 ----------------

var logHandle: FileHandle?
let logFormatter = ISO8601DateFormatter()

/// 只在主队列上调用（BLE 与 WS 的回调都收敛到主队列），所以不需要加锁。
func log(_ msg: String) {
    if logHandle == nil {
        rotateLogIfNeeded()
        FileManager.default.createFile(atPath: logPath, contents: nil)
        logHandle = FileHandle(forWritingAtPath: logPath)
    }
    let line = "\(logFormatter.string(from: Date())) \(msg)"
    print(line)
    if let h = logHandle, let d = (line + "\n").data(using: .utf8) { h.write(d) }
}

/// 一个常驻进程的日志不能无限长：超过 2MB 就在启动时轮转一次。
private func rotateLogIfNeeded() {
    let fm = FileManager.default
    guard let attrs = try? fm.attributesOfItem(atPath: logPath),
          let size = attrs[.size] as? UInt64, size > 2 * 1024 * 1024 else { return }
    let backup = logPath + ".1"
    try? fm.removeItem(atPath: backup)
    try? fm.moveItem(atPath: logPath, toPath: backup)
}

func errText(_ e: Error?) -> String {
    guard let e else { return "无" }
    return e.localizedDescription
}

// ---------------- 网关 ----------------

/// 全程跑在主队列上（CBCentralManager 与 URLSession 的 delegateQueue 都传 .main），
/// 于是所有状态都被串行化，不需要任何锁 —— 这是刻意选的，避免出一堆难查的竞态。
final class Gateway: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, URLSessionWebSocketDelegate {

    // ---- BLE 侧 ----
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var txChar: CBCharacteristic?
    private var bleReady = false
    private var rescanTask: DispatchWorkItem?

    /// 从 `connect()` 到"订阅完成（`bleReady`）"这条链路的兜底超时。见 `armLinkTimeout()`。
    private var linkTimeoutTask: DispatchWorkItem?
    /// 每个阶段各给 8 秒。实测正常一轮「连接 + 发现服务 + 发现特征 + 订阅」合计约 3 秒，8s 足够宽裕。
    private let linkTimeout: TimeInterval = 8

    // ---- WS 侧 ----
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var wsOpen = false
    private var wsBackoffIndex = 0
    private var wsReconnectTask: DispatchWorkItem?

    // ---- 两个方向的缓冲 ----
    private var toWs: [(text: String, at: Date)] = []
    private var toBle: [Data] = []
    private var writeInFlight = false

    private let wsBackoff: [TimeInterval] = [1, 2, 4, 8, 15, 30]

    // ---- 设备偏好（两块表同时在播时的仲裁） ----
    private var state = loadState()
    /// 本次会话要优先找的机型：手工 `device` 优先，否则用记忆的 `lastDevice`。
    private var wantDevice: String? { state.device ?? state.lastDevice }
    private var mismatchRetries = 0
    /// 连错这么多次就放弃偏好、接受眼前这块。1/2^8 ≈ 0.4%：小到忽略，又保证"人不会被卡住"。
    private let maxMismatchRetries = 8
    /// 放弃之后本次会话不再挑（否则每次重连都要重试 8 轮，纯噪声）。
    private var preferenceExhausted = false

    func start() {
        log("=== WristDeckLink 启动 ===")
        log("日志文件 \(logPath)")
        log("Bridge   \(bridgeURL.absoluteString)")
        log("偏好机型 \(wantDevice ?? "未指定（任何一块都行）")（\(state.device != nil ? "手工指定" : "自动记忆")）")
        log("蓝牙授权 \(CBCentralManager.authorization.rawValue)  (0=未定 1=受限 2=拒绝 3=允许)")
        central = CBCentralManager(delegate: self, queue: .main)
        connectWs()
    }

    // ---------------- CBCentralManagerDelegate ----------------

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        log("蓝牙状态 raw=\(c.state.rawValue)  (3=unauthorized 4=poweredOff 5=poweredOn)")
        guard c.state == .poweredOn else {
            // 蓝牙被关掉/无权限：清干净，等它回来。这里不主动退避重试，靠状态回调再进一次。
            dropBle()
            return
        }
        startScan()
    }

    private func startScan() {
        rescanTask?.cancel()
        rescanTask = nil
        guard central.state == .poweredOn, peripheral == nil else { return }
        log("开始扫描 service=\(serviceUUID.uuidString)")
        // 直接用 service 过滤，比"全扫再自己挑"更省电，也避免被一堆无关广播打扰。
        central.scanForPeripherals(withServices: [serviceUUID], options: nil)
    }

    private func scheduleRescan(_ delay: TimeInterval) {
        rescanTask?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.startScan() }
        rescanTask = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData d: [String: Any], rssi: NSNumber) {
        log("发现手表 rssi=\(rssi) id=\(p.identifier.uuidString)")
        peripheral = p
        p.delegate = self
        c.stopScan()
        log("发起连接…")
        armLinkTimeout("连接")
        c.connect(p, options: nil)
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        // ⚠️ 超时丢连之后的**迟到回调**：系统还是把这条连接交给我们了，必须主动断开。
        // 否则下面的服务发现会把 `txChar` / `bleReady` 建在一条"我们已经放弃"的链路上，
        // 而 `peripheral` 早已被 `dropBle()` 置 nil ⇒ 状态显示"链路就绪"却一条都发不出去
        // （`flushToBle()` 有 `let p = peripheral` 守卫，会静默 return），
        // 且此后**再无任何东西会来纠正它** —— 比直接卡死更隐蔽。
        guard peripheral === p else {
            log("已连接 \(p.identifier.uuidString)，但该链路已被超时放弃 ⇒ 立即断开")
            c.cancelPeripheralConnection(p)
            return
        }
        log("已连接 \(p.identifier.uuidString)")
        armLinkTimeout("发现服务")
        p.discoverServices([serviceUUID])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        log("连接失败：\(errText(error))；1s 后重新扫描")
        dropBle()
        scheduleRescan(1)
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        log("已断开：\(errText(error))；1s 后重新扫描")
        dropBle()
        scheduleRescan(1)
    }

    /// 清掉一切与"当前这条 BLE 链路"绑定的状态。注意 `central` 本身不动。
    private func dropBle() {
        if bleReady { log("BLE 链路失效") }
        disarmLinkTimeout()
        bleReady = false
        writeInFlight = false
        toBle.removeAll()
        txChar = nil
        peripheral = nil
    }

    // ---------------- 链路超时（防僵死） ----------------

    /// 给"当前这一步"上闹钟：`connect()` / `discoverServices` / `discoverCharacteristics` / `setNotifyValue`
    /// 每一步都必须在这个时间内拿到回调，否则**自己**丢掉链路重扫。
    ///
    /// 为什么必须有：`didConnect → discoverServices` 之后如果 CoreBluetooth **不给回调**
    /// （实测 2026-10-01 18:35：连上了、此后 `didDiscoverServices` 永不回调），`bleReady` 就永远是 false。
    /// 而这条链路上**所有**现成的自救入口 —— `didFailToConnect` / `didDisconnectPeripheral` /
    /// `didDiscoverServices` 报错 / `didDiscoverCharacteristicsFor` 报错 —— **都要求"对方或系统先给消息"**，
    /// 回调本身不来时一个都走不到。更糟的是 `startScan()` 里那句 `guard peripheral == nil`：
    /// `peripheral` 早在 `didDiscover` 就赋值了，于是连"重扫"都被自己拦住
    /// ⇒ **进程活着，却既不连也不扫，永久僵死**（现场卡了 4 分钟以上，只能手动重启）。
    ///
    /// `stage` 只用于打日志，方便一眼看出卡在哪一步。
    private func armLinkTimeout(_ stage: String) {
        linkTimeoutTask?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self, !self.bleReady else { return }
            log("⚠️ \(stage)超过 \(Int(self.linkTimeout))s 无进展 ⇒ 丢弃重扫")
            self.dropBle()          // 顺带 disarm，避免残留一个已触发的 item
            self.scheduleRescan(1)
        }
        linkTimeoutTask = item
        DispatchQueue.main.asyncAfter(deadline: .now() + linkTimeout, execute: item)
    }

    private func disarmLinkTimeout() {
        linkTimeoutTask?.cancel()
        linkTimeoutTask = nil
    }

    // ---------------- CBPeripheralDelegate ----------------

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        if let e = error {
            log("发现服务失败：\(e.localizedDescription)；1s 后重来")
            dropBle()
            scheduleRescan(1)
            return
        }
        guard let s = p.services?.first(where: { $0.uuid == serviceUUID }) else {
            log("没找到目标服务；1s 后重来")
            dropBle()
            scheduleRescan(1)
            return
        }
        armLinkTimeout("发现特征")
        p.discoverCharacteristics([charUUID], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        if let e = error {
            log("发现特征失败：\(e.localizedDescription)；1s 后重来")
            dropBle()
            scheduleRescan(1)
            return
        }
        guard let ch = s.characteristics?.first(where: { $0.uuid == charUUID }) else {
            log("没找到目标特征；1s 后重来")
            dropBle()
            scheduleRescan(1)
            return
        }
        txChar = ch
        log("特征已就绪 properties=0x\(String(ch.properties.rawValue, radix: 16))，订阅通知…")
        armLinkTimeout("订阅通知")
        p.setNotifyValue(true, for: ch)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor ch: CBCharacteristic, error: Error?) {
        if let e = error {
            // 典型症状：手表侧 CCCD 没手动 addDescriptor ⇒ CBATTErrorDomain Code=10。
            log("订阅失败：\(e.localizedDescription)")
            return
        }
        guard ch.isNotifying else { return }
        disarmLinkTimeout()          // 链路真正可用了，撤掉兜底闹钟
        bleReady = true
        log("BLE 链路就绪（已订阅通知）")
        flushToBle()
    }

    /// 上行：手表 notify 出来的一条完整消息。
    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        if let e = error {
            log("收到通知出错：\(e.localizedDescription)")
            return
        }
        guard let data = ch.value, let text = String(data: data, encoding: .utf8) else { return }
        // 两块表同时在播时的仲裁：只有拿到 hello 才知道对面是哪一块。
        if let dev = helloDevice(text), reject(dev) { return }
        log("↑ \(text)")
        sendToWs(text)
    }

    // ---------------- 设备偏好仲裁 ----------------

    /// 从手表的 hello 里取机型。只认握手消息，别把别的 JSON 当设备信息。
    private func helloDevice(_ text: String) -> String? {
        guard text.contains("\"t\":\"hello\""),
              let obj = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]
        else { return nil }
        return obj["dev"] as? String
    }

    /// 返回 true = 这条 hello **作废**（已安排断开重扫）。
    ///
    /// ⚠️ 被拒的 hello 必须**整个丢掉、绝不能透传给 Bridge**：Bridge 对同一个 WS 会话只认一次
    /// `hello`，同一会话再发一次会被当成异常消息，握手直接坏掉。而网关到 Bridge 的 WS 是
    /// **全程复用的那一条** —— 所以"先决定、再转发"是唯一安全的顺序。
    private func reject(_ dev: String) -> Bool {
        guard let want = wantDevice, want != dev, !preferenceExhausted else {
            // ⚠️ `preferenceExhausted` 时必须 `persist: false`。
            //
            // 这个标志一旦置真就**整个会话不复位**，而"本次会话不再挑"之后，
            // **每一次重连**都会走到这一支 —— 若沿用默认的 `persist: true`，
            // 只要在那之后重连成功一次，`lastDevice` 就被覆盖，
            // 上面那句"不动记忆"的承诺当场失效。
            //
            // 实测（2026-10-01 02:29）：期望的 OPWW234 不在广播里 ⇒ 连试 8 次后回落到 OWW212
            // （这次 `persist: false` 正确、没写盘），但 **30 秒后一次重连**就把
            // `~/.wristbridge/wristdecklink.json` 的 `lastDevice` 写成了 `OWW212`，
            // 下次启动反过来优先找方表 —— 与设计意图正好相反。
            //
            // 语义核对：`want == dev`（正常连上期望那块）或 `wantDevice == nil`（无偏好）时
            // `preferenceExhausted` 必为 false ⇒ `persist: true`，行为与改动前完全一致。
            remember(dev, persist: !preferenceExhausted)
            return false
        }
        mismatchRetries += 1
        if mismatchRetries > maxMismatchRetries {
            preferenceExhausted = true
            log("⚠️ 连试 \(maxMismatchRetries) 次都没等到 \(want) ⇒ 本次会话改用 \(dev)，不再挑")
            // 期望的那块一直没出现，多半是它没开机 / 不在附近。这时**不动记忆**：
            // 下次启动仍优先它，等它回来就自动切回去。
            remember(dev, persist: false)
            return false
        }
        log("⚠️ 扫到的是 \(dev)，要的是 \(want)（第 \(mismatchRetries)/\(maxMismatchRetries) 次）⇒ 断开重扫")
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        dropBle()
        scheduleRescan(1)
        return true
    }

    /// 握手成功 ⇒ 记下来，下次启动优先找它。手工指定（`state.device`）时不动记忆。
    private func remember(_ dev: String, persist: Bool = true) {
        mismatchRetries = 0
        guard persist, state.device == nil, state.lastDevice != dev else { return }
        state.lastDevice = dev
        saveState(state)
        log("记住手表机型 \(dev)")
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor ch: CBCharacteristic, error: Error?) {
        writeInFlight = false
        if let e = error { log("下发写入失败：\(e.localizedDescription)") }
        flushToBle()
    }

    // ---------------- WebSocket ----------------

    private func connectWs() {
        wsReconnectTask?.cancel()
        wsReconnectTask = nil
        guard task == nil else { return }

        let cfg = URLSessionConfiguration.default
        cfg.waitsForConnectivity = false
        let s = URLSession(configuration: cfg, delegate: self, delegateQueue: .main)
        session = s
        let t = s.webSocketTask(with: bridgeURL)
        task = t
        log("连接 Bridge…")
        t.resume()
        receiveLoop(t)
    }

    private func receiveLoop(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] result in
            guard let self else { return }
            // 世代号式护栏：过期 task 的回调一律丢弃（与手表侧 WsTransport 同一个病）。
            guard t === self.task else { return }
            switch result {
            case .failure(let e):
                log("WS 接收中断：\(e.localizedDescription)")
                self.handleWsClosed()
            case .success(let msg):
                switch msg {
                case .string(let text): self.onWsText(text)
                case .data(let d): self.onWsText(String(decoding: d, as: UTF8.self))
                @unknown default: break
                }
                self.receiveLoop(t)
            }
        }
    }

    func urlSession(_ s: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didOpenWithProtocol proto: String?) {
        guard webSocketTask === task else { return }
        wsOpen = true
        wsBackoffIndex = 0
        log("WS 已连接")
        // ⚠️ 顺序不能换：Bridge 对"还没有 role 的会话"会把非 hello 消息一律打回 need_hello（4005）。
        sendToWsRaw(helloJSON)
        flushToWs()
    }

    func urlSession(_ s: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith code: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        guard webSocketTask === task else { return }
        log("WS 关闭 code=\(code.rawValue)")
        handleWsClosed()
    }

    private func handleWsClosed() {
        guard task != nil else { return }
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        session?.invalidateAndCancel()
        session = nil
        wsOpen = false
        toWs.removeAll()

        let delay = wsBackoff[min(wsBackoffIndex, wsBackoff.count - 1)]
        wsBackoffIndex += 1
        log("WS \(Int(delay))s 后重连")
        let item = DispatchWorkItem { [weak self] in self?.connectWs() }
        wsReconnectTask = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func sendToWs(_ text: String) {
        guard wsOpen else {
            toWs.append((text, Date()))
            if toWs.count > upQueueLimit { toWs.removeFirst() }
            return
        }
        sendToWsRaw(text)
    }

    private func sendToWsRaw(_ text: String) {
        guard let t = task else { return }
        t.send(.string(text)) { e in
            if let e { log("WS 发送失败：\(e.localizedDescription)") }
        }
    }

    private func flushToWs() {
        guard !toWs.isEmpty else { return }
        let now = Date()
        let valid = toWs.filter { now.timeIntervalSince($0.at) < upQueueTTL }
        if valid.count < toWs.count { log("丢弃 \(toWs.count - valid.count) 条过期上行消息") }
        toWs.removeAll()
        for item in valid { sendToWsRaw(item.text) }
    }

    /// 下行：Bridge 发来的一条完整消息。
    private func onWsText(_ text: String) {
        log("↓ \(text)")
        guard bleReady else {
            log("BLE 未就绪，丢弃这条下行消息")
            return
        }
        guard let data = text.data(using: .utf8) else { return }
        if data.count > maxPayloadBytes {
            log("⚠️ 下行 \(data.count)B 超过 ATT 单包上限，可能发不出去")
        }
        toBle.append(data)
        flushToBle()
    }

    /// `.withResponse` 同时只允许一个写在空中，所以串行化排队。
    private func flushToBle() {
        guard !writeInFlight, bleReady, toBle.isEmpty == false,
              let p = peripheral, let ch = txChar else { return }
        let data = toBle.removeFirst()
        writeInFlight = true
        p.writeValue(data, for: ch, type: .withResponse)
    }
}

// ---------------- 启动 ----------------

// 必须持有引用，否则信号源会被立刻释放（这样写出来是显式提醒，不是多余的）。
var signalSources: [DispatchSourceSignal] = []

let gateway = Gateway()
gateway.start()

// Ctrl-C / pkill 时留一条"是正常退出"的日志，方便和崩溃区分开。
signal(SIGINT, SIG_IGN)
signal(SIGTERM, SIG_IGN)
for sig in [SIGINT, SIGTERM] {
    let src = DispatchSource.makeSignalSource(signal: sig, queue: .main)
    src.setEventHandler {
        log("收到信号 \(sig)，退出")
        exit(0)
    }
    src.resume()
    signalSources.append(src)
}

RunLoop.main.run()
