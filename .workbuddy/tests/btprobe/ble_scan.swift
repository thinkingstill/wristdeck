// Mac 侧 BLE 探针（CoreBluetooth / Central 模式）：扫描 → 连接 → 发现服务 → 写入 → 订阅。
//
// 为什么要包成 .app bundle 而不是直接跑 Python / 从助手进程跑：
//   macOS 上 CoreBluetooth 受 TCC 管控，系统要判断「谁在申请」。判断依据是进程
//   所属 bundle 的 Info.plist 里有没有 NSBluetoothAlwaysUsageDescription。
//   缺这个键时系统**不弹授权框、直接 SIGABRT**（实测 exit=134）：
//     - 从 WorkBuddy.app 派生 → WorkBuddy 缺该键 → abort
//     - 从 Terminal.app 派生 → Terminal 也缺该键 → abort
//     - 直接跑 bundle 内的可执行文件 → responsible process 仍是调用方 → abort
//   唯一可行：让 LaunchServices 启动（open -n MacBleScan.app），进程才自己当责任主体。
//
// 结果同时写 stdout 与 /tmp/btprobe_scan_out.txt。
//
// 用法：
//   MacBleScan           仅扫描 15s
//   MacBleScan --connect 扫到即连接、发现服务、写入、订阅通知

import Foundation
import CoreBluetooth

let outPath = "/tmp/btprobe_scan_out.txt"
let svcUUID = "7A1B0001-6F3C-4B2E-9A55-1D0E2F3A4B5C"
let charUUID = "7A1B0002-6F3C-4B2E-9A55-1D0E2F3A4B5C"
// 注意：`open -n <app> --args --connect` 实测传不进参数（argv 里看不到），
// 所以连接设为默认行为，用 --scan-only 才退回纯扫描。
let doConnect = !CommandLine.arguments.contains("--scan-only")

var handle: FileHandle?

func log(_ s: String) {
    print(s)
    if let h = handle, let d = (s + "\n").data(using: .utf8) { h.write(d) }
}

func finish(_ code: Int32) -> Never {
    log("end \(Date())")
    handle?.closeFile()
    exit(code)
}

FileManager.default.createFile(atPath: outPath, contents: nil)
handle = FileHandle(forWritingAtPath: outPath)

log("=== WristDeck Mac BLE 探针 (connect=\(doConnect)) ===")
log("bundle = \(Bundle.main.bundleIdentifier ?? "nil")")
log("authorization = \(CBCentralManager.authorization.rawValue)  (0=未定 1=受限 2=拒绝 3=允许)")

final class Probe: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    var central: CBCentralManager!
    var seen = Set<String>()
    var target: CBPeripheral?
    var hitCount = 0
    var notifyCount = 0
    var done = false

    func start() {
        central = CBCentralManager(delegate: self, queue: nil)
    }

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        log("state raw = \(c.state.rawValue)  (3=unauthorized 4=poweredOff 5=poweredOn)")
        guard c.state == .poweredOn else {
            log("VERDICT: 蓝牙不可用 state=\(c.state.rawValue)")
            finish(2)
        }
        log("扫描中，目标 service = \(svcUUID)")
        c.scanForPeripherals(withServices: nil, options: nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) {
            guard !self.done else { return }
            if self.target == nil {
                c.stopScan()
                log("15s 未发现目标；共见 \(self.seen.count) 个设备")
                log("VERDICT: 未命中 ✗")
                finish(1)
            }
        }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData d: [String: Any], rssi: NSNumber) {
        let name = (d[CBAdvertisementDataLocalNameKey] as? String) ?? p.name ?? "-"
        let uuids = (d[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID])?.map { $0.uuidString } ?? []
        if seen.insert(p.identifier.uuidString).inserted {
            log("FOUND \(p.identifier.uuidString)  rssi=\(rssi)  name=\(name)  uuids=\(uuids)")
        }
        guard uuids.contains(where: { $0.uppercased() == svcUUID }) else { return }

        hitCount += 1
        log(">>> 命中目标广播 #\(hitCount)  rssi=\(rssi) <<<")

        if doConnect && target == nil {
            target = p
            p.delegate = self
            c.stopScan()
            log("停止扫描，发起连接 …")
            c.connect(p, options: nil)
        }
    }

    // ---------- 连接之后 ----------

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        log("VERDICT-CONNECT 已连接 ✓  \(p.identifier.uuidString)")
        log("  最大写入长度 withResponse=\(p.maximumWriteValueLength(for: .withResponse))  withoutResponse=\(p.maximumWriteValueLength(for: .withoutResponse))")
        p.discoverServices([CBUUID(string: svcUUID)])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        log("连接失败: \(String(describing: error))")
        finish(4)
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        log("已断开: \(String(describing: error))")
        if !done { finish(5) }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        log("didDiscoverServices err=\(String(describing: error)) 已发现=\(p.services?.map { $0.uuid.uuidString } ?? [])")
        guard let s = p.services?.first(where: { $0.uuid.uuidString.uppercased() == svcUUID }) else {
            log("VERDICT: 未发现目标服务 ✗")
            finish(6)
        }
        p.discoverCharacteristics(nil, for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        let chars = s.characteristics ?? []
        log("服务 \(s.uuid.uuidString) 下特征数=\(chars.count)")
        for ch in chars {
            log("  char \(ch.uuid.uuidString)  properties=0x\(String(ch.properties.rawValue, radix: 16))")
        }
        guard let rx = chars.first(where: { $0.uuid.uuidString.uppercased() == charUUID }) else {
            log("VERDICT: 未发现目标特征 ✗")
            finish(7)
        }

        let payload = "hello-from-mac \(Int(Date().timeIntervalSince1970))"
        log("VERDICT-SERVICES 服务与特征发现成功 ✓")
        log("写入 \(payload.utf8.count) 字节 → \(rx.uuid.uuidString)")
        p.writeValue(payload.data(using: .utf8)!, for: rx, type: .withResponse)

        if rx.properties.contains(.notify) || rx.properties.contains(.indicate) {
            log("订阅通知 …")
            p.setNotifyValue(true, for: rx)
        }
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor ch: CBCharacteristic, error: Error?) {
        if let e = error {
            log("VERDICT-WRITE 写入失败 ✗  \(e)")
            done = true
            finish(8)
        }
        log("VERDICT-WRITE 写入成功 ✓  —— BLE 数据通路（Mac→手表）成立")
        // 再等几秒，看有没有手表发来的通知（手势数据就是走这个方向）。
        DispatchQueue.main.asyncAfter(deadline: .now() + 9) {
            self.done = true
            log(self.notifyCount > 0
                ? "VERDICT-NOTIFY 收到 \(self.notifyCount) 条通知 ✓ —— 手表→Mac 方向成立"
                : "VERDICT-NOTIFY 9s 内未收到通知 ✗")
            log("=== 结论：BLE 链路端到端可用（扫描/连接/发现/写入 全通）===")
            finish(0)
        }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        let text = ch.value.flatMap { String(data: $0, encoding: .utf8) } ?? "<binary>"
        notifyCount += 1
        log("<<< 收到通知 #\(notifyCount) \(ch.uuid.uuidString): \(text)")
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor ch: CBCharacteristic, error: Error?) {
        log("通知订阅状态 isNotifying=\(ch.isNotifying) err=\(String(describing: error))")
    }
}

let probe = Probe()
log("start \(Date())")
probe.start()

DispatchQueue.main.asyncAfter(deadline: .now() + 45) {
    log("TIMEOUT 45s")
    finish(3)
}

RunLoop.main.run()
