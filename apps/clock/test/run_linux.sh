#!/usr/bin/env bash
# JVM 测试：只编 app/src/com/onesay/clock/core（纯 Java）+ test/，用 json.jar 代替 Android 的 org.json。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${TOOLS:-$HOME/onesay-tools}"
JDK="${JAVA_HOME:-$TOOLS/jdk}/bin"
OUT="${OUT:-$ROOT/out-test}"
JSON="$ROOT/test/lib/json.jar"
rm -rf "$OUT"; mkdir -p "$OUT"
find "$ROOT/app/src/com/onesay/clock/core" "$ROOT/test" -name '*.java' | sort > "$OUT/sources.txt"
# 核心层不得引用 android.*（可 JVM 测的前提）
if grep -l "import android\." $(find "$ROOT/app/src/com/onesay/clock/core" -name '*.java'); then echo "!! core 引用了 android.*"; exit 1; fi
"$JDK/javac" -encoding UTF-8 -nowarn -source 1.7 -target 1.7 -Xlint:-options -cp "$JSON" -d "$OUT" @"$OUT/sources.txt" || { echo "!! COMPILE FAILED"; exit 1; }
# 每个测试类一段（各自一个 JVM，段内 ≤100 类）；任一段失败整体失败
cd "$ROOT" || exit 1
rc=0
for cls in com.onesay.clock.core.CoreTest com.onesay.clock.core.RainTest com.onesay.clock.core.LinkTest; do
  "$JDK/java" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Dbc.assets="$ROOT/app/assets" -cp "$OUT:$JSON" "$cls" || rc=1
done
exit $rc
