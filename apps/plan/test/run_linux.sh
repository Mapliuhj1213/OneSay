#!/usr/bin/env bash
# JVM 单测（OUT 可覆盖，默认 out-test/；建议 OUT=/tmp/plan-test）：编 app/src/com/onesay/plan/core（纯 Java）+ test/src；org.json 用 Android 原版独立 jar，农历 ICU 用 shims 转 icu4j。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# 依赖：JDK 8–17（JAVA_HOME）、android-28 的 android.jar（SDK / ANDROID_HOME）、test-libs（TEST_LIBS，可用 tools/fetch_test_libs.sh 下载）
TOOLS="${TOOLS:-$HOME/onesay-tools}"
JDK="${JAVA_HOME:-$TOOLS/jdk}/bin"
PLATFORM="${PLATFORM:-${SDK:-${ANDROID_HOME:-$TOOLS/android-sdk}}/platforms/android-28/android.jar}"
L="${TEST_LIBS:-$TOOLS/test-libs}"
for f in "$JDK/javac" "$PLATFORM" "$L/junit-4.13.2.jar"; do [ -e "$f" ] || { echo "MISSING DEP: $f（见 README「构建和测试」）" >&2; exit 1; }; done
CP="$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar:$L/android-json-0.0.20131108.vaadin1.jar:$L/icu4j-72.1.jar"
OUT="${OUT:-$ROOT/out-test}"
rm -rf "$OUT"; mkdir -p "$OUT/shims" "$OUT/main"
# core 只许用 java.* / org.json（以及农历的 android.icu、CnHolidays 的 android.util.Log —— 由 shims 顶替）
if grep -n "^import android\." $(find "$ROOT/app/src/com/onesay/plan/core" -name '*.java'); then echo "!! core 引用了 android.*"; exit 1; fi
"$JDK/javac" -encoding UTF-8 -nowarn -d "$OUT/shims" -cp "$CP" $(find "$ROOT/test/shims" -name '*.java')
find "$ROOT/app/src/com/onesay/plan/core" "$ROOT/test/src" -name '*.java' | sort > "$OUT/sources.txt"
"$JDK/javac" -encoding UTF-8 -nowarn -source 1.7 -target 1.7 -Xlint:-options -cp "$OUT/shims:$CP:$PLATFORM" -d "$OUT/main" @"$OUT/sources.txt"
CLASSES=$(cd "$ROOT/test/src" && find . -name '*Test.java' | sed 's#^\./##; s#\.java$##; s#/#.#g' | sort)
cd "$ROOT" && "$JDK/java" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Dplan.root="$ROOT" -cp "$OUT/main:$OUT/shims:$CP:$PLATFORM" org.junit.runner.JUnitCore $CLASSES
