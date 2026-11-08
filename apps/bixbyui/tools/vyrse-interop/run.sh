#!/usr/bin/env bash
# VYRSE 原桥接代码（node）↔ OneSay 客户端（JVM）互通测试。用法见 README.md。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
: "${VYRSE_SRC:?set VYRSE_SRC to a vyrse checkout}"
TOOLS="${TOOLS:-$HOME/onesay-tools}"; LIBS="$TOOLS/test-libs"; B="${BIXBY_TEST_BUILD:-$ROOT/test-jvm/build}"
W="$(mktemp -d)"; cp "$VYRSE_SRC/desktop/src/main/phone-bridge.ts" "$W/phone-bridge.mts"
( cd "$VYRSE_SRC" && git rev-parse --short HEAD 2>/dev/null || echo unknown ) > "$W/vyrse-commit.txt"
VY_TMP="$W" VY_BRIDGE="$W/phone-bridge.mts" VY_CTL=47631 node --experimental-strip-types --no-warnings "$HERE/harness.mjs" > "$W/harness.log" 2>&1 &
NP=$!
for i in $(seq 1 50); do grep -q '"ready":true' "$W/harness.log" 2>/dev/null && break; sleep 0.2; done
cat "$W/harness.log"
CP="$B/test:$B/prod:$B/shims:$LIBS/android-json-0.0.20131108.vaadin1.jar:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:${SDK:-$TOOLS/android-sdk}/platforms/android-28/android.jar"
set +e
"${JAVA_HOME:-$TOOLS/jdk}/bin/java" -Dfile.encoding=UTF-8 -Dvyrse.interop.ctl=http://127.0.0.1:47631 -Dvyrse.interop.commit="$(cat "$W/vyrse-commit.txt")" \
  -cp "$CP" org.junit.runner.JUnitCore com.onesay.assistant.plug.vyrse.core.VyrseInteropTest
RC=$?
kill $NP 2>/dev/null; wait $NP 2>/dev/null; rm -rf "$W"
exit $RC
