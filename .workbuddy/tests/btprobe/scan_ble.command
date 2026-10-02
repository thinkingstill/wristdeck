#!/bin/bash
# 双击即可运行（macOS 会用 Terminal 打开并执行）。
#
# 为什么必须由你手动双击、而不是助手直接跑：
#   助手进程是从 WorkBuddy.app 派生的，而 WorkBuddy.app 的 Info.plist 里只有
#   NSBluetoothPeripheralUsageDescription、没有 NSBluetoothAlwaysUsageDescription。
#   macOS 发现缺这个键时不会弹权限框，而是直接 SIGABRT（实测 exit=134，连
#   print 都来不及输出）。所以任何从助手派生的进程都碰不到 CoreBluetooth。
#   Terminal 有自己的蓝牙授权，双击这条路能正常弹框。
#
# 首次运行会弹「Terminal 想使用蓝牙」，请点「允许」。

# Python 解释器：默认取 PATH 里的 python3。
# 若把 bleak 装在别处（虚拟环境 / conda），运行前覆盖即可：
#   PY=/path/to/python ./scan_ble.command
PY="${PY:-$(command -v python3 || echo python3)}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG=/tmp/btprobe_scan_out.txt

{
  echo "start $(date '+%H:%M:%S')"
  "$PY" "$SCRIPT_DIR/scan_ble.py" 15
  echo "exit=$?"
  echo "end $(date '+%H:%M:%S')"
} 2>&1 | tee "$LOG"

echo
echo "-------------------------------------------------------"
echo "扫描结束。结果同时保存在：$LOG"
echo "回到对话里说一声「跑完了」，助手会去读结果。"
echo
read -n 1 -s -r -p "按任意键关闭此窗口…"
