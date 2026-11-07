#!/bin/bash
# exec 守护 v2 离线回归（不碰手机）：BIXBY_EXEC_DIR 把 release / debug 的 files 目录、标记、日志都换到临时目录。
# 用法：bash test_exec_daemon_v2.sh [sh解释器，默认 dash；也可 bash / mksh]
SHELL_BIN=${1:-dash}
D=$(cd "$(dirname "$0")" && pwd); DAEMON=${DAEMON:-$D/exec_daemon.v2.sh}
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL $1"; [ -n "${2:-}" ] && sed 's/^/      | /' "$2"; }
W=$(mktemp -d); mkdir -p "$W/rel" "$W/dbg"
BIXBY_EXEC_DIR=$W setsid $SHELL_BIN "$DAEMON" </dev/null >/dev/null 2>&1 &
PID=$!
sleep 0.5
# wait_res 文件 秒：等结果文件出现非空内容
wait_res() { i=0; while [ ! -s "$1" ] && [ $i -lt $(( $2 * 20 )) ]; do sleep 0.05; i=$((i+1)); done; [ -s "$1" ]; }
LOGF=$W/exec.log

echo "== exec daemon v2 offline tests ($SHELL_BIN)"
printf 'echo rel-1' > "$W/rel/.bixby_req"; printf 'echo dbg-1' > "$W/dbg/.bixby_req"
wait_res "$W/rel/.bixby_res" 3; sleep 0.5
[ "$(cat "$W/rel/.bixby_res")" = "$(printf '0\nrel-1')" ] && [ ! -e "$W/dbg/.bixby_res" ] && [ "$(cat "$W/dbg/.bixby_req")" = "echo dbg-1" ] \
  && grep -q "exec rc=0 elapsed=.*cmd=echo rel-1" "$LOGF" \
  && ok "默认（无标记）：只服务 release，日志格式同 v1；debug 的请求原样留着、不执行" || bad default "$LOGF"

: > "$W/debug_exec"
wait_res "$W/dbg/.bixby_res" 3
[ "$(cat "$W/dbg/.bixby_res")" = "$(printf '0\ndbg-1')" ] && grep -q "debug exec ON" "$LOGF" && grep -q "exec\[debug\] rc=0 .*cmd=echo dbg-1" "$LOGF" \
  && [ "$(cat "$W/rel/.bixby_res")" = "$(printf '0\nrel-1')" ] \
  && ok "建标记后：debug 请求在 debug 自己的 .bixby_res 里拿到结果，release 结果文件不动" || bad flag_on "$LOGF"

: > "$W/rel/.bixby_res"; : > "$W/dbg/.bixby_res"
printf 'echo rel-2; exit 3' > "$W/rel/.bixby_req"; printf 'echo dbg-2' > "$W/dbg/.bixby_req"
wait_res "$W/rel/.bixby_res" 3; wait_res "$W/dbg/.bixby_res" 3
[ "$(cat "$W/rel/.bixby_res")" = "$(printf '3\nrel-2')" ] && [ "$(cat "$W/dbg/.bixby_res")" = "$(printf '0\ndbg-2')" ] \
  && ok "两个包同时发请求：各回各的结果文件（rc 也不串：release rc=3、debug rc=0）" || bad concurrent "$LOGF"

rm -f "$W/debug_exec"; sleep 0.5; : > "$W/dbg/.bixby_res"
printf 'echo dbg-3' > "$W/dbg/.bixby_req"; sleep 1
[ ! -s "$W/dbg/.bixby_res" ] && [ "$(cat "$W/dbg/.bixby_req")" = "echo dbg-3" ] && grep -q "debug exec OFF" "$LOGF" \
  && ok "删标记：立刻停止给 debug 执行（不用重启守护）" || bad flag_off "$LOGF"

: > "$W/rel/.bixby_res"; printf 'echo rel-3' > "$W/rel/.bixby_req"; wait_res "$W/rel/.bixby_res" 3
[ "$(cat "$W/rel/.bixby_res")" = "$(printf '0\nrel-3')" ] && ok "关掉 debug 后 release 照常" || bad rel_after "$LOGF"

kill -- -$PID 2>/dev/null; kill $PID 2>/dev/null; sleep 0.2; rm -rf "$W"
echo "== RESULT pass=$PASS fail=$FAIL"
[ $FAIL -eq 0 ]
