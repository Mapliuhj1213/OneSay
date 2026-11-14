#!/usr/bin/env bash
# ============================================================
# 找茬式对抗测试（2026-11-14）：助手 / 计划 / 时钟 三块单独跑。
#
#   bash tools/adv-tests/run.sh            # 门禁内的对抗用例（Adv*Test，三块都跑）
#   bash tools/adv-tests/run.sh bugs       # 只跑「已知缺陷」用例（默认门禁排除）。2026-11-14 修复后已全部移回门禁，目录暂空
#   bash tools/adv-tests/run.sh gate assistant|plan|clock   # 只跑一块
#
# 依赖与各 App 原有测试相同：TOOLS（默认 ~/onesay-tools）下的 jdk、android-sdk/platforms/android-28、test-libs。
# PROD=<目录> 可复用已编好的助手生产类（目录下有 prod/ 与 shims/），省掉约 20 秒编译。
# 门禁内的 Adv*Test 也在各 App 原有的测试入口里（助手 test-jvm/src、计划 test/src；时钟 test/run_linux.sh 的类清单）。
# ============================================================
set -uo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
TOOLS="${TOOLS:-$HOME/onesay-tools}"
JDK="${JAVA_HOME:-$TOOLS/jdk}/bin"
PLATFORM="${SDK:-$TOOLS/android-sdk}/platforms/android-28/android.jar"
L="$TOOLS/test-libs"
JUNIT="$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar"
JSON="$L/android-json-0.0.20131108.vaadin1.jar"
ICU="$L/icu4j-72.1.jar"
OUT="${OUT:-/tmp/onesay-adv}"
MODE="${1:-gate}"
ONLY="${2:-all}"
# 助手 App 目录（目录名是历史遗留，按入口脚本找）
ASSIST="$(dirname "$(ls "$REPO"/apps/*/run_unit_tests.sh | head -1)")"
PLAN="$REPO/apps/plan"
CLOCK="$REPO/apps/clock"
rc=0

run_assistant() {
  local B="$OUT/assistant" T="$ASSIST/test-jvm"
  mkdir -p "$B"
  local EXTRA="$ASSIST/vendor/mail/android-mail-1.6.5.jar:$ASSIST/vendor/mail/android-activation-1.6.5.jar:$ASSIST/libs/zxing-core-3.5.3.jar:$ASSIST/libs/shizuku-api-13.1.5.jar"
  local PRODCP
  if [ -n "${PROD:-}" ]; then
    PRODCP="$PROD/prod:$PROD/shims"
  else
    rm -rf "$B/prod" "$B/shims"; mkdir -p "$B/prod" "$B/shims"
    local SRCPATH="$ASSIST/app/src:$ASSIST/app/variant/release:$ASSIST/app/config"
    for d in "$REPO"/libs/plugin-api/src "$REPO"/libs/plugins/*/src; do [ -d "$d" ] && SRCPATH="$SRCPATH:$d"; done
    [ -d "$ASSIST/vendor/sherpa/java" ] && SRCPATH="$SRCPATH:$ASSIST/vendor/sherpa/java"
    grep -vE '^\s*(#|$)' "$T/prod-classes.txt" | sed "s#^#$ASSIST/app/src/#" > "$B/prod-sources.txt"
    "$JDK/javac" -encoding UTF-8 -source 1.7 -target 1.7 -Xlint:-options -nowarn -bootclasspath "$PLATFORM" \
      -classpath "$PLATFORM:$EXTRA" -sourcepath "$SRCPATH" -implicit:class -d "$B/prod" @"$B/prod-sources.txt" || return 1
    find "$T/shims" -name "*.java" > "$B/shim-sources.txt"
    "$JDK/javac" -encoding UTF-8 -classpath "$ICU" -d "$B/shims" @"$B/shim-sources.txt" || return 1
    PRODCP="$B/prod:$B/shims"
  fi
  local SRC="$T/src"; [ "$MODE" = bugs ] && SRC="$T/adv-bugs/src"
  rm -rf "$B/test"; mkdir -p "$B/test"
  find "$SRC" -name "Adv*.java" | sort > "$B/test-sources.txt"
  [ -s "$B/test-sources.txt" ] || { echo "[assistant] no tests in $SRC"; return 0; }
  "$JDK/javac" -encoding UTF-8 -Xlint:-options -nowarn -classpath "$PRODCP:$JUNIT:$EXTRA:$PLATFORM" -d "$B/test" @"$B/test-sources.txt" || return 1
  mapfile -t CLS < <(cd "$B/test" && find . -name "Adv*Test.class" | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)
  echo "[assistant] ${#CLS[@]} classes ($MODE)"
  "$JDK/java" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -cp "$B/test:$PRODCP:$JSON:$ICU:$JUNIT:$EXTRA:$PLATFORM" \
    org.junit.runner.JUnitCore "${CLS[@]}"
}

run_plan() {
  local B="$OUT/plan"
  rm -rf "$B"; mkdir -p "$B/shims" "$B/main"
  local CP="$JUNIT:$JSON:$ICU"
  "$JDK/javac" -encoding UTF-8 -nowarn -d "$B/shims" -cp "$CP" $(find "$PLAN/test/shims" -name '*.java') || return 1
  local SRC="$PLAN/test/src"; [ "$MODE" = bugs ] && SRC="$PLAN/test-adv-bugs/src"
  { find "$PLAN/app/src/com/onesay/plan/core" -name '*.java'; find "$SRC" -name 'Adv*.java'; } | sort > "$B/sources.txt"
  "$JDK/javac" -encoding UTF-8 -nowarn -source 1.7 -target 1.7 -Xlint:-options -cp "$B/shims:$CP:$PLATFORM" -d "$B/main" @"$B/sources.txt" || return 1
  local CLS; CLS=$(cd "$SRC" && find . -name 'Adv*Test.java' | sed 's#^\./##; s#\.java$##; s#/#.#g' | sort)
  [ -n "$CLS" ] || { echo "[plan] no tests in $SRC"; return 0; }
  echo "[plan] $(echo $CLS | wc -w) classes ($MODE)"
  (cd "$PLAN" && "$JDK/java" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Dplan.root="$PLAN" -cp "$B/main:$B/shims:$CP:$PLATFORM" org.junit.runner.JUnitCore $CLS)
}

run_clock() {
  local B="$OUT/clock" CJ="$CLOCK/test/lib/json.jar"
  rm -rf "$B"; mkdir -p "$B"
  local SRC="$CLOCK/test"; [ "$MODE" = bugs ] && SRC="$CLOCK/test-adv-bugs"
  { find "$CLOCK/app/src/com/onesay/clock/core" -name '*.java'; find "$SRC" -name 'Adv*.java'; } | sort > "$B/sources.txt"
  "$JDK/javac" -encoding UTF-8 -nowarn -source 1.7 -target 1.7 -Xlint:-options -cp "$CJ" -d "$B" @"$B/sources.txt" || return 1
  local CLS; CLS=$(cd "$SRC" && find . -name 'Adv*Test.java' | sed 's#^\./##; s#\.java$##; s#/#.#g' | sort)
  local r=0
  for c in $CLS; do
    (cd "$CLOCK" && "$JDK/java" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Dbc.assets="$CLOCK/app/assets" -cp "$B:$CJ" "$c") || r=1
  done
  return $r
}

for part in assistant plan clock; do
  [ "$ONLY" = all ] || [ "$ONLY" = "$part" ] || continue
  echo "=== $part ==="
  "run_$part" || rc=1
done
exit $rc
