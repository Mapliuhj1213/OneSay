#!/usr/bin/env bash
# key_daemon.sh 的主机端测试：假 getevent 输入 + 假时钟，检查写出的触发记录。
set -euo pipefail
D=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
cat > "$T/ev.txt" <<'X'
/dev/input/event0: EV_KEY KEY_VOLUMEUP DOWN
/dev/input/event0: EV_KEY KEY_POWER DOWN
/dev/input/event0: EV_SYN SYN_REPORT 00000000
/dev/input/event0: EV_KEY KEY_POWER UP
X
echo 1000 > "$T/clk"
OUT=$(KD_DIR="$T" KD_SRC="cat $T/ev.txt" KD_CLOCK="cat $T/clk" sh "$D/key_daemon.sh"; cat "$T/key.trigger")
[ "$OUT" = "up 1 0" ] || { echo "FAIL: last record = '$OUT'"; exit 1; }
# 只认目标键：音量键不该产生记录
KD_DIR="$T" KD_KEY=KEY_HOME KD_SRC="cat $T/ev.txt" KD_CLOCK="cat $T/clk" sh "$D/key_daemon.sh"
[ "$(cat "$T/key.trigger")" = "up 1 0" ] || { echo "FAIL: other key wrote a record"; exit 1; }
echo "test_key_daemon: OK"
