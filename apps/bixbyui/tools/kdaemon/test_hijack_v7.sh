#!/bin/bash
# K L2 守护 v7 离线回归（不碰手机）：用 BIXBY_SRC 注入合成的 dmesg 行，BIXBY_TEST=1 打桩 launch/appAlive。
# 用法：bash test_hijack_v7.sh [sh解释器，默认 dash；也可 bash / mksh]
SHELL_BIN=${1:-dash}
D=$(cd "$(dirname "$0")" && pwd); DAEMON=$D/hijack_daemon.v7.sh
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  PASS $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1"; [ -n "$2" ] && sed 's/^/      | /' "$2"; }

# run_case 名称 flag(0/1) alive(0/1) 事件脚本（"sleep 秒" 或 "D 秒.微秒" / "U 秒.微秒" / "RAW 文本"）
run_case() {
  name=$1; flag=$2; alive=$3; shift 3
  W=$(mktemp -d); : > "$W/ev"; : > "$W/bixby.trigger"; [ "$flag" = 1 ] && : > "$W/bixby.gesture_v2"
  BIXBY_TEST=1 BIXBY_TEST_ALIVE=$alive BIXBY_DIR=$W BIXBY_SRC="tail -n +1 -s 0.02 -f $W/ev" \
    setsid $SHELL_BIN "$DAEMON" </dev/null >/dev/null 2>&1 &
  pid=$!
  sleep 0.4
  for step in "$@"; do
    set -- $step
    case "$1" in
      sleep) sleep "$2" ;;
      D) echo "<6>[ $2] [2:  irq/12-gpio:  123] gpio_keys_gpio_report_event: 703 (1/0)" >> "$W/ev" ;;
      U) echo "<6>[ $2] [2:  irq/12-gpio:  123] gpio_keys_gpio_report_event: 703 (0/0)" >> "$W/ev" ;;
      RAW) shift; echo "$*" >> "$W/ev" ;;
    esac
  done
  sleep 1.6
  kill -- -$pid 2>/dev/null; kill $pid 2>/dev/null; pkill -f "tail -n +1 -s 0.02 -f $W/ev" 2>/dev/null
  sleep 0.1
  LOGF=$W/hijack.log; TRIG=$W/bixby.trigger
}
has()   { grep -q -- "$1" "$LOGF"; }
count() { grep -c -- "$1" "$LOGF"; }

echo "== K L2 v7 offline tests ($SHELL_BIN)"

run_case single 1 1  "D 1000.000000" "sleep 0.12" "U 1000.120000"
has "PRESS seq=1 t=1000000ms" && has "RELEASE seq=1 dur=120ms" && ! has "LONG" \
  && grep -q "^press 1 1000000$" "$TRIG" && ok "单击：PRESS + RELEASE 120ms，无长按，trigger=press" || bad single "$LOGF"

run_case double 1 1  "D 2000.000000" "sleep 0.1" "U 2000.100000" "sleep 0.2" "D 2000.300000" "sleep 0.1" "U 2000.400000"
[ "$(count PRESS)" = 2 ] && ! has "LONG" && ! has DEBOUNCED && ok "双击 300ms：两次都进日志、不误判长按（v6 去抖 1 s 会吞第二下）" || bad double "$LOGF"

run_case bounce 1 1  "D 3000.000000" "sleep 0.03" "U 3000.030000" "sleep 0.05" "D 3000.080000" "sleep 0.05" "U 3000.130000"
[ "$(count PRESS)" = 1 ] && has "DEBOUNCED dt=80ms" && ok "抖动 80ms：去抖吞掉" || bad bounce "$LOGF"

run_case long_flag 1 1  "D 4000.000000" "sleep 1.0" "U 4001.000000"
has "LONG seq=1 -> trigger gesture" && grep -q "^long 1 800$" "$TRIG" && has "RELEASE seq=1 dur=1000ms" \
  && ok "长按 1 s（App 有 gesture 标记且存活）：800ms 写 trigger「long 1 800」" || bad long_flag "$LOGF"

run_case long_legacy 0 1  "D 5000.000000" "sleep 1.0" "U 5001.000000"
has "legacy conversation" && has "TESTLAUNCH args=\[-e ui_state listening -e mode conversation --ez arm true\]" \
  && grep -q "^press 1 5000000$" "$TRIG" && ok "长按（旧 App 无标记）：保持 v6 行为（conversation），不写 long 到 trigger" || bad long_legacy "$LOGF"

run_case long_dead 1 0  "D 6000.000000" "sleep 1.0" "U 6001.000000"
has "MISS: app process dead" && has "gesture=long" && has "TESTLAUNCH args=\[-e ui_state listening -e gesture long --ez arm true\]" \
  && ok "长按（App 进程死了）：冷启动兜底 + 带 gesture=long 拉起" || bad long_dead "$LOGF"

run_case hold700 1 1  "D 7000.000000" "sleep 0.7" "U 7000.700000"
! has "LONG" && has "RELEASE seq=1 dur=700ms" && ok "按住 700ms 松开：不触发长按" || bad hold700 "$LOGF"

run_case quick_then_long 1 1  "D 8000.000000" "sleep 0.1" "U 8000.100000" "sleep 0.3" "D 8000.400000" "sleep 1.0" "U 8001.400000"
[ "$(count "LONG seq=")" = 1 ] && has "LONG seq=2" && ! has "LONG seq=1" \
  && ok "短按后马上长按：只有第 2 下判长按（v6 共享 REL 文件会让第 1 下误判）" || bad quick_then_long "$LOGF"

run_case no_ts 1 1  "RAW gpio_keys_gpio_report_event: 703 (1/0)" "sleep 0.1" "RAW gpio_keys_gpio_report_event: 703 (0/0)"
has "PRESS seq=1 t=" && has "RELEASE seq=1 dur=" && ! has "LONG" && ok "行里没有内核时间戳：退回 /proc/uptime，照常工作" || bad no_ts "$LOGF"

echo "== RESULT pass=$PASS fail=$FAIL"
[ $FAIL -eq 0 ]
