#!/usr/bin/env python3
"""
Mac 侧 BLE 扫描：验证手表探针的广播能否被 macOS 的 CoreBluetooth 看到。

这是 BLE 路线（手表做 Peripheral、Mac 做 Central）能否成立的关键一环 ——
手表侧 startAdvertisingSet 成功只是"本机说自己能广播"，只有被 macOS 真正
扫到，才证明这条路端到端通。

用法：
  python3 scan_ble.py [秒数]

依赖 Python 3.10+ 且已 `pip install bleak`。
首次运行 macOS 会弹「允许终端使用蓝牙」，必须点允许。
"""

import asyncio
import sys
from datetime import datetime

from bleak import BleakScanner

# 与 watch/app/src/main/java/io/wristdeck/ui/BtProbeActivity.kt 里的 SVC_UUID 一致
TARGET_UUID = "7a1b0001-6f3c-4b2e-9a55-1d0e2f3a4b5c"


def stamp() -> str:
    return datetime.now().strftime("%H:%M:%S")


async def main() -> int:
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 12.0
    seen: dict[str, tuple] = {}
    hit = False

    def on_detect(device, adv_data):
        nonlocal hit
        uuids = [u.lower() for u in (adv_data.service_uuids or [])]
        name = adv_data.local_name or device.name or "-"
        rssi = adv_data.rssi
        addr = device.address

        if addr not in seen:
            seen[addr] = (name, rssi, tuple(uuids))
            print(f"[{stamp()}] 发现 {addr}  rssi={rssi:>4}  name={name}  uuids={list(uuids)}")

        if TARGET_UUID in uuids:
            hit = True
            print(f"[{stamp()}] >>> 命中目标服务 UUID！ {addr} {name} rssi={rssi} <<<")

    print(f"[{stamp()}] 开始扫描 {seconds:.0f}s（目标 service={TARGET_UUID}）…")
    print(f"[{stamp()}] 若弹出「允许…使用蓝牙」请点允许。")
    async with BleakScanner(on_detect):
        await asyncio.sleep(seconds)

    print(f"\n[{stamp()}] 扫描结束，共发现 {len(seen)} 个设备。")
    if hit:
        print("结论：Mac 能扫到手表广播 ✓  —— BLE 路线端到端可见性成立")
        return 0
    print("结论：未扫到目标广播 ✗")
    print("  - 手表探针是否已启动、日志是否出现 VERDICT-ADV 广播已开启")
    print("  - 手表是否离 Mac 太远 / 蓝牙是否被其它设备占用")
    return 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
