#!/bin/sh
# 构建 WristDeckLink.app
#
#   ./build.sh          编译 + 打包 + ad-hoc 签名
#   ./build.sh run      构建完顺带启动
#
# ⚠️ 只能通过 `open -n WristDeckLink.app` 启动（或双击）。直接跑
#    WristDeckLink.app/Contents/MacOS/WristDeckLink 会因 TCC 责任主体不是本 bundle
#    而 SIGABRT（exit=134）。原因见 WristDeckLink.swift 顶部注释。
#
# 停止：pkill -f WristDeckLink
# 日志：~/Library/Logs/WristDeckLink.log

set -e
cd "$(dirname "$0")"

APP="WristDeckLink.app"
BIN="$APP/Contents/MacOS/WristDeckLink"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"

# -framework 不必显式写：CoreBluetooth 与 Foundation 由 import 自动链接。
swiftc -O -o "$BIN" WristDeckLink.swift

cp Info.plist "$APP/Contents/Info.plist"

# ad-hoc 签名（- 表示不指定身份）。没有这一步，TCC 有时会拒绝把 bundle 当作稳定身份。
codesign --force --deep --sign - "$APP" >/dev/null

echo "已构建 $APP"

if [ "$1" = "run" ]; then
    pkill -f "$APP/Contents/MacOS/WristDeckLink" 2>/dev/null || true
    sleep 1
    open -n "$APP"
    echo "已启动。日志：tail -f ~/Library/Logs/WristDeckLink.log"
else
    echo "启动：open -n $APP"
fi
