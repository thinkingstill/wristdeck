#!/usr/bin/env bash
# WristDeck 圆表版面规划器 · JVM 离线测试台
#
# 为什么要在 Mac 上跑：圆表只有一块，验一轮要编译 APK、装表、点亮、截图核对。
# 而"这个尺寸下按钮会不会压到圆外"是**纯几何问题**，能在 JVM 上毫秒级算清楚。
# DisplayGeometry 被刻意做成零 Android 依赖，就是为了这个。
#
# 用法：./run.sh
set -euo pipefail

JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
KOTLINC="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc"
STDLIB="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" \
            -name 'kotlin-stdlib-*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)"
SRC="$(cd "$(dirname "$0")/../../../watch" && pwd)/app/src/main/java/io/wristdeck/ui/DisplayGeometry.kt"
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
"$KOTLINC" -nowarn -classpath "$STDLIB" -d "$OUT" "$SRC" "$HERE/Verify.kt" 2>&1 | grep -vi "^warning" || true

exec "$JBR/bin/java" -cp "$OUT:$STDLIB" io.wristdeck.ui.VerifyKt
