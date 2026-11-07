#!/system/bin/sh
# ============================================================
# Bixby key takeover daemon - v7 "K L2" (2026-10-11, NOT DEPLOYED)
#
# Base: v6 as running on the Note 8 (/data/adb/modules/bixbykey_hijack/
# hijack_daemon.sh, md5 c3c1688437e176429b234209e2560f9a, copied read-only
# to the old v6 daemon, removed from the open-source tree). Everything v5/v6 fixed
# (single instance, never-blocking reader, appAlive via pidof, screen-off
# KEYCODE_WAKEUP, cold-start force launch) is kept verbatim.
#
# What L2 changes :
#   K-1  debounce 1 s -> 250 ms (DEBOUNCE_MS). v6 compared `date +%s`
#        seconds, so a double press inside the same/next second was eaten.
#   K-2  millisecond timestamps: kernel printk time parsed from the dmesg
#        line ("[ 1234.567890] ..."); fallback /proc/uptime (10 ms). toybox
#        0.7.3 `date` has no %N (measured on device 2026-10-11).
#   K-3  long press is decided by press SEQUENCE, not a shared $REL file.
#        v6: press -> rm $REL; release -> touch $REL; timer 0.65 s checks
#        $REL. With a 250 ms debounce a quick second press deletes the
#        first press's $REL and the first timer fires a FALSE long press.
#        v7: every press gets seq N; release writes N to $RELSEQ; the one-shot
#        timer for N fires LONG only if $RELSEQ < N at LONG_MS (= still held). Release also
#        logs the exact duration (kernel ts), so 50-sample stats need no
#        extra tooling.
#   K-4  gesture-aware trigger file. Content becomes
#           "press <seq> <t_ms>"   on key down   (app: wake as before)
#           "long <seq> <dur_ms>"  at LONG_MS    (app: 记一记 / cancel wake)
#        Old app builds ignore the content (any CLOSE_WRITE = press), so the
#        "long" write is ONLY done when the app has created the capability
#        flag $GESTURE_FLAG (new app creates it at start). Without the flag
#        v7 behaves like v6 on long press (launch mode=conversation).
#        Release is NOT written to the trigger (would be a 2nd CLOSE_WRITE).
#
# Test hooks (all off on device):
#   BIXBY_SRC="tail -f <file>"  event source instead of `dmesg -w`
#   BIXBY_TEST=1                no instance killing / pkill, no dumpsys /
#                               input / setprop / cmd / am; launch() and
#                               appAlive() are stubbed (BIXBY_TEST_ALIVE=0|1)
#   BIXBY_DIR=<dir>             LOG/REL/TRIGGER/PIDFILE/flag under <dir>
# ============================================================
if [ -n "${BIXBY_DIR:-}" ]; then
  LOG=$BIXBY_DIR/hijack.log
  RELSEQ=$BIXBY_DIR/.bixby_relseq
  TRIGGER=$BIXBY_DIR/bixby.trigger
  GESTURE_FLAG=$BIXBY_DIR/bixby.gesture_v2
  PIDFILE=$BIXBY_DIR/.bixby_hijack.pid
else
  LOG=/data/adb/bixbykey_hijack.log
  RELSEQ=/data/local/tmp/.bixby_relseq
  TRIGGER=/data/user/0/com.onesay.assistant/files/bixby.trigger
  GESTURE_FLAG=/data/user/0/com.onesay.assistant/files/bixby.gesture_v2
  PIDFILE=/data/local/tmp/.bixby_hijack.pid
fi
PKG=com.onesay.assistant
TARGET=$PKG/.WebViewUiActivity
DEBOUNCE_MS=${DEBOUNCE_MS:-250}
LONG_MS=${LONG_MS:-800}
CHECK_AFTER=0.7
TESTMODE=${BIXBY_TEST:-0}

log() { echo "$(date '+%F %T') $*" >> "$LOG" 2>/dev/null; }

log "key-daemon v7 start (pid $$) debounce=${DEBOUNCE_MS}ms long=${LONG_MS}ms"

if [ "$TESTMODE" != "1" ]; then
  # ---------- A. enforce a single instance (v5, unchanged) ----------
  for d in /proc/[0-9]*; do
    p=${d#/proc/}
    [ "$p" = "$$" ] && continue
    [ "$p" = "$PPID" ] && continue
    if grep -qa hijack_daemon "$d/cmdline" 2>/dev/null; then
      log "kill duplicate daemon pid=$p"
      kill -9 "$p" 2>/dev/null
    fi
  done
  pkill -f "^dmesg -w" 2>/dev/null
  sleep 1
fi
echo $$ > "$PIDFILE" 2>/dev/null

cleanup() {
  [ "$TESTMODE" != "1" ] && pkill -f "^dmesg -w" 2>/dev/null
  rm -f "$PIDFILE" 2>/dev/null
  exit 0
}
trap cleanup EXIT INT TERM

# ---------- launcher (v5, unchanged; stubbed in test mode) ----------
launch() {
  if [ "$TESTMODE" = "1" ]; then log "TESTLAUNCH args=[$*]"; return 0; fi
  out=$(cmd activity start-activity --user 0 -n "$TARGET" "$@" 2>&1)
  rc=$?
  if [ $rc -ne 0 ]; then
    log "cmd start-activity rc=$rc out=[$out] -> trying am"
    out=$(am start --user 0 -n "$TARGET" "$@" 2>&1)
    rc=$?
  fi
  log "launch args=[$*] rc=$rc out=[$out]"
}

# ---------- liveness probe (v6, unchanged; stubbed in test mode) ----------
appAlive() {
  if [ "$TESTMODE" = "1" ]; then [ "${BIXBY_TEST_ALIVE:-1}" = "1" ]; return $?; fi
  if command -v pidof >/dev/null 2>&1; then
    pidof "$PKG" >/dev/null 2>&1 && return 0
    return 1
  fi
  for d in /proc/[0-9]*; do
    p=${d#/proc/}
    [ "$p" = "$$" ] && continue
    n=$(tr '\0' '\n' < "$d/cmdline" 2>/dev/null | head -n 1) 2>/dev/null
    [ -n "$n" ] || continue
    [ "$n" = "$PKG" ] && return 0
  done
  return 1
}

# ---------- K-2 millisecond clock ----------
# kms LINE -> prints kernel time of the printk in ms ("[ 1234.567890] ..."),
# or /proc/uptime in ms when the line carries no parseable timestamp.
# Shell builtins only (no awk in Magisk early PATH).
kms() {
  t=${1#*\[}
  t=${t%%\]*}
  t=${t# }; t=${t# }; t=${t# }; t=${t# }; t=${t# }
  case "$t" in
    [0-9]*.[0-9][0-9][0-9]*)
      case "$t" in *[!0-9.]*) ;; *)
        s=${t%%.*}; f=${t#*.}; f=${f%"${f#???}"}; f=${f#0}; f=${f#0}
        echo $(( s * 1000 + f )); return ;;
      esac ;;
  esac
  up=; read up _ < /proc/uptime 2>/dev/null
  case "$up" in
    [0-9]*.[0-9][0-9]) s=${up%%.*}; f=${up#*.}; f=${f#0}; echo $(( s * 1000 + f * 10 )); return ;;
  esac
  echo $(( $(date +%s) * 1000 ))
}

if [ -n "${BIXBY_SRC:-}" ]; then SRC_CMD="$BIXBY_SRC"; else SRC_CMD="dmesg -w 2>/dev/null"; fi

last_ms=-100000
seq=0
down_ms=0
echo 0 > "$RELSEQ" 2>/dev/null

# ---------- main loop: must never block ----------
$SRC_CMD | while read -r line; do
  case "$line" in
    *"gpio_keys_gpio_report_event: 703 (1/0)"*)
      now=$(kms "$line")
      if [ $(( now - last_ms )) -lt "$DEBOUNCE_MS" ]; then
        log "DEBOUNCED dt=$(( now - last_ms ))ms"
        continue
      fi
      last_ms=$now
      seq=$(( seq + 1 ))
      down_ms=$now
      log "PRESS seq=$seq t=${now}ms"

      # (1) fast path (v5): only write if it already exists (owner/label).
      [ -f "$TRIGGER" ] && echo "press $seq $now" > "$TRIGGER" 2>/dev/null
      [ "$TESTMODE" != "1" ] && setprop bixbykey.pressed "$(date +%s)" 2>/dev/null

      # (2) screen-off handling + guaranteed launch (v5/v6, unchanged).
      ( if [ "$TESTMODE" != "1" ]; then
          st=$(dumpsys power 2>/dev/null | grep -m1 -o 'mWakefulness=[A-Za-z]*')
          case "$st" in
            *Awake*) : ;;
            *) log "screen off [$st] -> KEYCODE_WAKEUP"
               input keyevent 224 >/dev/null 2>&1
               sleep 0.4 ;;
          esac
        fi
        sleep $CHECK_AFTER
        if appAlive; then
          log "ok: app alive after ${CHECK_AFTER}s (seq=$seq)"
        else
          log "MISS: app process dead after ${CHECK_AFTER}s -> force launch (seq=$seq)"
          launch -e ui_state listening --ez arm true
        fi ) </dev/null >/dev/null 2>&1 &

      # (3) K-3 long press: one-shot timer bound to THIS press's seq.
      ( myseq=$seq
        sleep "$(( LONG_MS / 1000 )).$(printf '%03d' $(( LONG_MS % 1000 )))"
        r=0; read r < "$RELSEQ" 2>/dev/null
        # RELSEQ = last released seq. Released (r >= myseq) -> short press, do nothing.
        # (a newer press physically implies this one was released, so r < myseq only while held)
        if [ "${r:-0}" -lt "$myseq" ] 2>/dev/null; then
          if [ -f "$GESTURE_FLAG" ] && [ -f "$TRIGGER" ] && appAlive; then
            log "LONG seq=$myseq -> trigger gesture"
            echo "long $myseq $LONG_MS" > "$TRIGGER" 2>/dev/null
          elif [ -f "$GESTURE_FLAG" ]; then
            log "LONG seq=$myseq -> app not ready, launch gesture=long"
            launch -e ui_state listening -e gesture long --ez arm true
          else
            log "LONG seq=$myseq -> legacy conversation (app has no gesture flag)"
            launch -e ui_state listening -e mode conversation --ez arm true
          fi
        fi ) </dev/null >/dev/null 2>&1 &
      ;;
    *"gpio_keys_gpio_report_event: 703 (0/0)"*)
      up_ms=$(kms "$line")
      echo "$seq" > "$RELSEQ" 2>/dev/null
      log "RELEASE seq=$seq dur=$(( up_ms - down_ms ))ms"
      ;;
  esac
done
