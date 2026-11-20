#!/bin/sh
# 常驻分工端到端：本地中转（onesay-relay 常驻分工分支，wrangler dev）+ 电脑端 AlwaysOnService + 模拟手机 + 唤醒代理。
# 用法：RELAY_DIR=<onesay-relay 常驻分工分支目录> TEST_LIBS=<junit 目录> sh test/e2e-alwayson.sh
set -u
cd "$(dirname "$0")/.."
RELAY_DIR=${RELAY_DIR:-../onesay-relay}
PORT=${PORT:-8789}
B=${BUILD_DIR:-build}/e2e-aon
export NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost
rm -rf "$B" && mkdir -p "$B/c" "$B/t"
javac --release 17 -encoding UTF-8 -d "$B/c" $(find src/main/java -name '*.java') 2>&1 | grep -v '^Note' ; cp -r src/main/resources/. "$B/c/"
javac --release 17 -encoding UTF-8 -cp "$B/c:$TEST_LIBS/junit-4.13.2.jar:$TEST_LIBS/hamcrest-core-1.3.jar" -d "$B/t" src/test/java/com/onesay/desktop/AlwaysOnRelayE2E.java 2>&1 | grep -v '^Note'
TOKEN=$(sed -n 's/^RELAY_TOKEN=//p' "$RELAY_DIR/.dev.vars")
desc() { for c in $(pgrep -P "$1"); do echo "$c"; desc "$c"; done; }
stop_dev() { all=""; for p in $(pgrep -f "wrangler.* dev --port $PORT"); do all="$all $p $(desc $p)"; done; [ -n "$all" ] && kill $all 2>/dev/null; sleep 0.5; }
stop_dev
(cd "$RELAY_DIR" && rm -rf .wrangler/state && npx wrangler dev --port $PORT --ip 127.0.0.1 --var SWEEP_MS:1000 > "$B/relay.log" 2>&1) &
for i in $(seq 1 60); do curl -s 127.0.0.1:$PORT/healthz >/dev/null && break; sleep 1; done
RELAY_TOKEN="$TOKEN" timeout 90 java -Djava.awt.headless=true -cp "$B/c:$B/t" com.onesay.desktop.AlwaysOnRelayE2E "http://127.0.0.1:$PORT"
R=$?
stop_dev
exit $R
