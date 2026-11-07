#!/bin/bash
# 守护 v8 离线回归（含 v7 全部用例：没有 gesture_v3 标记时行为必须与 v7 一致）（不碰手机）：用 BIXBY_SRC 注入合成的 dmesg 行，BIXBY_TEST=1 打桩 launch/appAlive。
# 用法：bash test_hijack_v7.sh [sh解释器，默认 dash；也可 bash / mksh]
SHELL_BIN=${1:-dash}
D=$(cd "$(dirname "$0")" && pwd); DAEMON=${DAEMON:-$D/hijack_daemon.v8.sh}
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  PASS $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1"; [ -n "$2" ] && sed 's/^/      | /' "$2"; }

# run_case 名称 flag(0/1) alive(0/1) 事件脚本（"sleep 秒" 或 "D 秒.微秒" / "U 秒.微秒" / "RAW 文本"）
run_case() {
  name=$1; flag=$2; alive=$3; shift 3
  W=$(mktemp -d); : > "$W/ev"; : > "$W/bixby.trigger"; [ "$flag" != 0 ] && : > "$W/bixby.gesture_v2"
  [ "$flag" = 3 ] && : > "$W/bixby.gesture_v3"
  : > "$W/trig.hist"
  BIXBY_TEST=1 BIXBY_TEST_ALIVE=$alive BIXBY_DIR=$W BIXBY_SRC="tail -n +1 -s 0.02 -f $W/ev" \
    setsid $SHELL_BIN "$DAEMON" </dev/null >/dev/null 2>&1 &
  pid=$!
  ( last=""; while [ -d "$W" ]; do c=$(cat "$W/bixby.trigger" 2>/dev/null); [ "$c" != "$last" ] && { echo "$c" >> "$W/trig.hist"; last=$c; }; sleep 0.01; done ) &
  spid=$!
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
  kill $spid 2>/dev/null
  LOGF=$W/hijack.log; TRIG=$W/bixby.trigger; HIST=$W/trig.hist
}
has()   { grep -q -- "$1" "$LOGF"; }
count() { grep -c -- "$1" "$LOGF"; }

echo "== v8 offline tests ($SHELL_BIN, $(basename $DAEMON))"
echo "-- v7 compatible cases (no gesture_v3 flag)"

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


echo "-- v8 gesture v3 cases"
run_case v3_tap 3 1  "D 9000.000000" "sleep 0.12" "U 9000.120000"
grep -q "^down 1 9000000$" "$HIST" && grep -q "^press 1 120$" "$HIST" && ! has "LONG seq" && has "TAP seq=1 dur=120ms" \
  && [ "$(sed -n 1p "$HIST")" = "down 1 9000000" ] \
  && ok "v3 单击：按下只写 down，松手才写 press（带按住时长 120ms）" || { cat "$HIST"; bad v3_tap "$LOGF"; }

run_case v3_long 3 1  "D 9100.000000" "sleep 1.0" "U 9101.000000"
grep -q "^down 1 9100000$" "$HIST" && grep -q "^long 1 800$" "$HIST" && grep -q "^up 1 1000$" "$HIST" && ! grep -q "^press" "$HIST" \
  && ok "v3 长按 1 s：down → long（800ms）→ up，全程没有 press（App 不会响唤醒音）" || { cat "$HIST"; bad v3_long "$LOGF"; }

run_case v3_hold700 3 1  "D 9200.000000" "sleep 0.7" "U 9200.700000"
grep -q "^press 1 700$" "$HIST" && ! grep -q "^long" "$HIST" && ok "v3 按住 700ms 松开：短按（press 700），不判长按" || { cat "$HIST"; bad v3_hold700 "$LOGF"; }

run_case v3_double 3 1  "D 9300.000000" "sleep 0.1" "U 9300.100000" "sleep 0.2" "D 9300.300000" "sleep 0.1" "U 9300.400000"
grep -q "^press 1 100$" "$HIST" && grep -q "^press 2 100$" "$HIST" && ! grep -q "^long" "$HIST" && ok "v3 双击：两次 press（App 侧 800ms 去抖照旧）" || { cat "$HIST"; bad v3_double "$LOGF"; }

run_case v3_quick_then_long 3 1  "D 9400.000000" "sleep 0.1" "U 9400.100000" "sleep 0.3" "D 9400.400000" "sleep 1.0" "U 9401.400000"
grep -q "^press 1 100$" "$HIST" && grep -q "^long 2 800$" "$HIST" && grep -q "^up 2 1000$" "$HIST" && ! grep -q "^long 1" "$HIST" \
  && ok "v3 短按后马上长按：第 1 下 press，第 2 下 long + up" || { cat "$HIST"; bad v3_quick_then_long "$LOGF"; }

run_case v3_lost_release 3 1  "D 9500.000000" "sleep 1.2"
grep -q "^long 1 800$" "$HIST" && ! grep -q "^press" "$HIST" && ok "v3 松手行丢失：800ms 计时器照样判长按，不会卡住" || { cat "$HIST"; bad v3_lost_release "$LOGF"; }

run_case v3_tap_dead 3 0  "D 9600.000000" "sleep 0.12" "U 9600.120000"
has "MISS: app process dead after release" && has "TESTLAUNCH args=\[-e ui_state listening --ez arm true\]" \
  && ! has "MISS: app process dead after 0.7s" && ok "v3 App 没起来 + 单击：松手后冷启动 listening（按下时不拉起）" || bad v3_tap_dead "$LOGF"

run_case v3_long_dead 3 0  "D 9700.000000" "sleep 1.0" "U 9701.000000"
has "TESTLAUNCH args=\[-e ui_state listening -e gesture long --ez arm true\]" && ! has "TESTLAUNCH args=\[-e ui_state listening --ez arm true\]" \
  && ok "v3 App 没起来 + 长按：只用 gesture=long 拉起（不会先拉一个会响的唤醒）" || bad v3_long_dead "$LOGF"

run_case v3_no_v2flag 0 1  "D 9800.000000" "sleep 0.12" "U 9800.120000"
grep -q "^press 1 9800000$" "$HIST" && ! grep -q "^down" "$HIST" && ok "只有 v3 没有 v2 标记（或都没有）：按 v6/v7 旧协议写 press" || { cat "$HIST"; bad v3_no_v2flag "$LOGF"; }

echo "-- v8.1 on-device fixes (S-1 replay guard, S-2 late release)"
BIXBY_MARKER="bixbykey_hijack replay-marker T1" run_case replay 3 1  "D 100.000000" "U 100.150000" "D 200.000000" "U 201.100000" "RAW <5>[  300.000000] bixbykey_hijack replay-marker T1" "sleep 0.1" "D 400.000000" "sleep 0.12" "U 400.120000"
[ "$(count 'STALE down')" = 2 ] && [ "$(count PRESS)" = 1 ] && has "marker seen -> live" && has "TAP seq=1 dur=120ms" \
  && ok "S-1 重启回放：标记之前的 2 次旧按键全部忽略，标记之后的单击照常（seq=1）" || bad replay "$LOGF"

run_case latelong 3 1  "D 500.000000" "U 501.200000"
has "LATE-LONG seq=1 dur=1200ms" && grep -q "^long 1 800$" "$HIST" && ! grep -q "^press 1" "$HIST" \
  && ok "S-2 松手行先到但内核时长 1200ms：判长按（long），不出唤醒" || bad latelong "$LOGF"

run_case latehold 3 1  "D 600.000000" "U 602.500000"
has "LATE-LONG seq=1 dur=2500ms" && grep -q "^long 1 800$" "$HIST" && grep -q "^up 1 2500$" "$HIST" \
  && ok "S-2 松手行先到且按住 2500ms：long + up（随口记照样触发）" || bad latehold "$LOGF"

echo "-- v8.2 CPU fix: kernel spam is filtered before the shell"
SPAM=""; for i in $(seq 1 40); do SPAM="$SPAM|RAW <6>[  700.$(printf '%06d' $i)] usbpd-s2mm005 15-0033: IRQ1:01 spam line $i"; done
IFS='|' read -r -a SPAMA <<< "${SPAM#|}"
run_case spam_tap 3 1 "${SPAMA[@]}" "D 701.000000" "sleep 0.12" "U 701.120000" "${SPAMA[@]}"
grep -q "^down 1 701000$" "$HIST" && grep -q "^press 1 120$" "$HIST" && has "TAP seq=1 dur=120ms" && ! has "usbpd" \
  && ok "v8.2 噪声行（usbpd 刷屏 80 行）夹着单击：前置 grep 过滤后照常 down/press，日志里没有噪声行" || bad spam_tap "$LOGF"

echo "-- v8.3 boot fix: marker lost from the ring buffer -> guard times out"
BIXBY_LIVE_AFTER_S=2 BIXBY_MARKER="bixbykey_hijack replay-marker LOST" run_case marker_lost 3 1  "D 100.000000" "U 100.150000" "sleep 2.3" "D 400.000000" "sleep 0.12" "U 400.120000"
[ "$(count 'STALE down')" = 1 ] && has "replay guard timeout" && has "TAP seq=1 dur=120ms" && grep -q "^press 1 120$" "$HIST" \
  && ok "v8.3 标记丢失（开机时 ring buffer 已转走）：启动后 2 s 内的旧按键忽略，超时后真按键照常唤醒" || bad marker_lost "$LOGF"

echo "== RESULT pass=$PASS fail=$FAIL"
[ $FAIL -eq 0 ]
