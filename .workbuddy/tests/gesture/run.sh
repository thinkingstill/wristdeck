#!/usr/bin/env bash
# WristDeck 手势识别器 · JVM 离线测试台
#
# 为什么要在 Mac 上跑：用真表验一轮要编译 APK、装表、离座、做动作、抓日志，
# 一轮好几分钟。识别器被刻意设计成**零 Android 依赖**，就是为了能在 JVM 上秒级回归。
#
# 用法：
#   ./run.sh                 跑边界回归套件
#   ./run.sh <日志文件>       回放日志里 GestureProbe 打出的真实轨迹
#   ./run.sh -v <日志文件>    回放并逐帧打印
#   ./run.sh --emit <文件>    生成一份与真机同格式的夹具（自测用）
#
# 抓真实轨迹：
#   1) 表上开识别调试模式，做一轮动作
#   2) adb -s <serial> logcat -d -s WristDeck:* > /tmp/wrist.log
#   3) ./run.sh /tmp/wrist.log
set -euo pipefail

JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
KOTLINC="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc"
STDLIB="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" \
            -name 'kotlin-stdlib-*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)"
SRC="$(cd "$(dirname "$0")/../../../watch" && pwd)/app/src/main/java/io/wristdeck/gesture/WristGestureDetector.kt"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"

if [[ -z "$STDLIB" ]]; then
  echo "找不到 kotlin-stdlib jar，检查 ~/.gradle/caches/modules-2/... 是否已下载依赖" >&2
  exit 1
fi
if [[ ! -x "$KOTLINC" ]]; then
  echo "找不到 kotlinc（期望在 Android Studio 插件目录）：$KOTLINC" >&2
  exit 1
fi

export JAVA_HOME="$JBR"
rm -rf "$OUT" && mkdir -p "$OUT"

# 每次都用**当前源码**重新编译，避免拿着旧 class 自欺欺人。
"$KOTLINC" -nowarn -classpath "$STDLIB" -d "$OUT" "$SRC" 2>&1 | grep -vi "^warning" || true
"$JBR/bin/javac" -nowarn -cp "$OUT:$STDLIB" -d "$OUT" "$HERE/Sim.java" "$HERE/Sim2.java"

# 无参数 = 跑全套：Sim（14 场景状态机）+ Sim2（边界/回放）。
# 两个套件都要跑——曾经 Sim 长期没接进来，改坏了状态机也不会报（等于没有回归）。
if [[ $# -eq 0 ]]; then
  "$JBR/bin/java" -cp "$OUT:$STDLIB" Sim || exit 1
  exec "$JBR/bin/java" -cp "$OUT:$STDLIB" Sim2
fi
exec "$JBR/bin/java" -cp "$OUT:$STDLIB" Sim2 "$@"
