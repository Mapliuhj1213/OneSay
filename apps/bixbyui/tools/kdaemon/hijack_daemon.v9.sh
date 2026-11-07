#!/system/bin/sh
# ============================================================
# Bixby key takeover daemon - v9 "key target switch" (2026-10-22, debug variant)
#
# v9 = v8.3 + optional switch of WHICH app gets the key :
#   default                 -> release com.onesay.assistant (byte-for-byte v8.3 behaviour:
#                              same TARGET, trigger/flag paths, pidof name)
#   /data/adb/bixbyui_key_target containing "debug"
#                           -> debug variant com.onesay.assistant.debug
#                              TARGET  com.onesay.assistant.debug/com.onesay.assistant.WebViewUiActivity
#                              (the ".WebViewUiActivity" shorthand would expand to
#                              com.onesay.assistant.debug.WebViewUiActivity, which does not exist:
#                              applicationId changed, Java package did not)
#                              trigger /data/user/0/com.onesay.assistant.debug/files/bixby.trigger
#   The flag is re-read on every key DOWN (one `[ -f ]` + `read`), so switching needs
#   no daemon restart; down/long/release of one press always use the same target.
#   If the flag says debug but com.onesay.assistant.debug is not installed (no data dir),
#   the press falls back to release and the log says so.
#   /data/adb is root-only: no app (not even the debug app) can flip the switch.
#   Test hooks: BIXBY_DIR -> flag $BIXBY_DIR/key_target, debug data dir $BIXBY_DIR/debug.
#
# ---- v8 header (kept) ----
# Bixby key takeover daemon - v8.1 "silent long press" (2026-10-11, R10)
#
# v8.2 (2026-10-15, R14): CPU fix only, key protocol unchanged.
#   The Note 8 kernel logs ~220 lines/s (usbpd-s2mm005 / muic spam: the
#   4896-line ring buffer covered just 22 s). mksh `read -r` on a pipe reads
#   ONE BYTE PER read(2) syscall, so the `while read` subshell burned ~15-19%
#   of a core 24/7 (4 h 37 min CPU in 24.8 h, measured 10-15 02:12).
#   Now `grep --line-buffered -F` (toybox 0.7.3 has it) drops every line that
#   is not a Bixby-key line or our replay marker BEFORE the shell sees it;
#   grep reads in blocks and flushes each match immediately (no added latency).
#
# v8.3 (2026-10-15 R14 follow-up): BOOT FIX for the S-1 replay guard.
#   Seen after the 10-15 cold boot: the marker was written to /dev/kmsg BEFORE
#   `dmesg -w` started; at boot the kernel logs so fast that the 4096-line ring
#   had already rotated the marker out when dmesg dumped it, so the marker was
#   never seen and EVERY real press was logged "STALE ... ignored" forever
#   (key dead until the daemon was restarted). Now:
#   - the marker is written AFTER the pipeline is up (it arrives in the live
#     stream, behind the whole replay dump), and re-written every 2 s until the
#     loop has seen it (flag file $PIDFILE.live), max 30 tries;
#   - hard fallback: a key line arriving >= LIVE_AFTER_S (20 s) after start
#     turns the guard off ("replay guard timeout -> live").
#
# v8 = v7 + gesture protocol v3 (only when the app created $GESTURE_V3):
#   key down          -> "down <seq> <t_ms>"   (app: silent prepare only, no sound/panel)
#   release < LONG_MS -> "press <seq> <dur_ms>" (app: wake as before; fires on RELEASE)
#   held LONG_MS      -> "long <seq> <LONG_MS>" (app: 记一记, no wake sound at all)
#   release after long-> "up <seq> <dur_ms>"    (app: logs only; reserved for 随口记)
#   Who owns a press (short vs long) is decided by an atomic mkdir claim
#   ($CLAIM.<seq>): release and the LONG_MS timer race for it, exactly one wins.
#   Lost release line: the LONG_MS timer still fires (worst case = long press).
#   Cold start (app dead): short -> launch listening at release; long -> launch
#   gesture=long. Screen-off KEYCODE_WAKEUP stays on key down.
#   Without $GESTURE_V3 the daemon behaves byte-for-byte like v7 (offline test).
#
# v8.1 (2026-10-11 19:30, R10 on-device review):
#   S-1 stale replay: `dmesg -w` first dumps the whole kernel ring buffer, so
#       every daemon (re)start replayed old key lines ~10 s later = phantom
#       wakes (seen on the Note 8 with v7 AND v8). Lines whose kernel time is
#       before a marker the daemon writes to /dev/kmsg at start are now
#       skipped ("STALE"). Test hook: BIXBY_MARKER=<text>.
#   S-2 late release: under load the release line can win the claim even
#       though the kernel says it was held >= LONG_MS (replay showed
#       "TAP dur=1026ms"). Release now trusts the kernel duration: held
#       >= LONG_MS -> "long", and >= HOLD_MS (2000) additionally "up" 0.3 s
#       later so 随口记 still works.
#
# ---- v7 header (kept) ----
# Bixby key takeover daemon - v7 "K L2" (2026-10-11)
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
REL_PKG=com.onesay.assistant
DBG_PKG=com.onesay.assistant.debug
if [ -n "${BIXBY_DIR:-}" ]; then
  LOG=$BIXBY_DIR/hijack.log
  RELSEQ=$BIXBY_DIR/.bixby_relseq
  REL_FILES=$BIXBY_DIR
  DBG_FILES=$BIXBY_DIR/debug
  TARGET_FLAG=$BIXBY_DIR/key_target
  CLAIM=$BIXBY_DIR/.bixby_claim
  PIDFILE=$BIXBY_DIR/.bixby_hijack.pid
else
  LOG=/data/adb/bixbykey_hijack.log
  RELSEQ=/data/local/tmp/.bixby_relseq
  REL_FILES=/data/user/0/$REL_PKG/files
  DBG_FILES=/data/user/0/$DBG_PKG/files
  TARGET_FLAG=/data/adb/bixbyui_key_target
  CLAIM=/data/local/tmp/.bixby_claim
  PIDFILE=/data/local/tmp/.bixby_hijack.pid
fi

# v9: which app owns the key. Sets PKG / TARGET / TRIGGER / GESTURE_FLAG / GESTURE_V3.
# Release values are exactly the v8.3 constants.
CUR_TGT=
pick_target() {
  want=release
  if [ -f "$TARGET_FLAG" ]; then
    t=; read t < "$TARGET_FLAG" 2>/dev/null
    [ "$t" = "debug" ] && want=debug
  fi
  if [ "$want" = "debug" ] && [ ! -d "$DBG_FILES" ] && [ ! -d "${DBG_FILES%/files}" ]; then
    [ "$CUR_TGT" != "release-fallback" ] && log "key target flag = debug but $DBG_PKG is not installed -> release"
    want=release-fallback
  fi
  if [ "$want" = "debug" ]; then
    PKG=$DBG_PKG
    TARGET=$DBG_PKG/com.onesay.assistant.WebViewUiActivity
    FILES=$DBG_FILES
  else
    PKG=$REL_PKG
    TARGET=$PKG/.WebViewUiActivity
    FILES=$REL_FILES
  fi
  TRIGGER=$FILES/bixby.trigger
  GESTURE_FLAG=$FILES/bixby.gesture_v2
  GESTURE_V3=$FILES/bixby.gesture_v3
  if [ "$want" != "$CUR_TGT" ]; then
    [ -n "$CUR_TGT" ] && log "key target -> $want ($PKG)"
    CUR_TGT=$want
  fi
}
DEBOUNCE_MS=${DEBOUNCE_MS:-250}
LONG_MS=${LONG_MS:-800}
CHECK_AFTER=0.7
TESTMODE=${BIXBY_TEST:-0}

log() { echo "$(date '+%F %T') $*" >> "$LOG" 2>/dev/null; }

pick_target
log "key-daemon v9 start (pid $$) debounce=${DEBOUNCE_MS}ms long=${LONG_MS}ms target=$CUR_TGT ($PKG)"

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
  rm -f "$PIDFILE" "$PIDFILE.live" 2>/dev/null
  exit 0
}
trap cleanup EXIT INT TERM

# ---------- launcher (v5, unchanged; stubbed in test mode) ----------
launch() {
  if [ "$TESTMODE" = "1" ]; then log "TESTLAUNCH args=[$*] target=$TARGET"; return 0; fi
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

# S-1: anything the kernel logged before we started is history, not a key press.
# Clock-agnostic: we drop a marker into the kernel log BEFORE starting `dmesg -w`;
# the ring-buffer dump ends with it, so every key line before the marker is replay.
# (printk time and /proc/uptime drift apart after suspend, so no time compare.)
HOLD_MS=${BIXBY_HOLD_MS:-2000}
MARKER="bixbykey_hijack replay-marker $$"
LIVEFLAG=$PIDFILE.live
LIVE_AFTER_S=${BIXBY_LIVE_AFTER_S:-20}
START_S=$(date +%s)
rm -f "$LIVEFLAG" 2>/dev/null
READY=1
if [ "$TESTMODE" = "1" ]; then
  [ -n "${BIXBY_MARKER:-}" ] && { MARKER=$BIXBY_MARKER; READY=0; }
elif [ -w /dev/kmsg ]; then
  READY=0
  log "replay guard: key lines before \"$MARKER\" are ignored (fallback live after ${LIVE_AFTER_S}s)"
  # v8.3: write the marker only once `dmesg -w` is running, retry until seen.
  ( sleep 1; i=0
    while [ ! -f "$LIVEFLAG" ] && [ $i -lt 30 ]; do
      echo "$MARKER" > /dev/kmsg 2>/dev/null || break
      i=$(( i + 1 )); sleep 2
    done ) </dev/null >/dev/null 2>&1 &
else
  log "replay guard: /dev/kmsg not writable -> guard off"
fi

# guard_live: called on a key line while READY!=1; turns the guard off after the timeout.
guard_live() {
  [ $(( $(date +%s) - START_S )) -ge "$LIVE_AFTER_S" ] 2>/dev/null || return 1
  READY=1; : > "$LIVEFLAG" 2>/dev/null
  log "replay guard timeout (${LIVE_AFTER_S}s, marker not seen) -> live"
  return 0
}

last_ms=-100000
seq=0
down_ms=0
V3=0
echo 0 > "$RELSEQ" 2>/dev/null
rm -rf "$CLAIM".* 2>/dev/null

# ---------- main loop: must never block ----------
# v8.2: pre-filter (see header) - only key lines and the replay marker reach the shell.
$SRC_CMD | grep --line-buffered -F -e 'gpio_keys_gpio_report_event: 703' -e "$MARKER" | while read -r line; do
  case "$line" in
    *"$MARKER"*)
      [ "$READY" != 1 ] && { log "replay guard: marker seen -> live"; : > "$LIVEFLAG" 2>/dev/null; }
      READY=1
      ;;
    *"gpio_keys_gpio_report_event: 703 (1/0)"*)
      now=$(kms "$line")
      if [ "$READY" != 1 ] && ! guard_live; then log "STALE down t=${now}ms (ring-buffer replay) -> ignored"; continue; fi
      if [ $(( now - last_ms )) -lt "$DEBOUNCE_MS" ]; then
        log "DEBOUNCED dt=$(( now - last_ms ))ms"
        continue
      fi
      last_ms=$now
      seq=$(( seq + 1 ))
      down_ms=$now
      pick_target   # v9: snapshot the key target for this whole press
      log "PRESS seq=$seq t=${now}ms"
      V3=0; [ -f "$GESTURE_V3" ] && [ -f "$GESTURE_FLAG" ] && V3=1
      rmdir "$CLAIM.$(( seq - 3 ))" 2>/dev/null

      # (1) fast path (v5): only write if it already exists (owner/label).
      #     v8/v3: key down only prepares; the wake happens at release.
      if [ "$V3" = 1 ]; then
        [ -f "$TRIGGER" ] && echo "down $seq $now" > "$TRIGGER" 2>/dev/null
      else
        [ -f "$TRIGGER" ] && echo "press $seq $now" > "$TRIGGER" 2>/dev/null
      fi
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
        [ "$V3" = 1 ] && exit 0   # v3: cold start is decided at release / long
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
          if [ "$V3" = 1 ] && ! mkdir "$CLAIM.$myseq" 2>/dev/null; then
            log "LONG seq=$myseq lost the claim (released at the edge) -> short press"
          elif [ -f "$GESTURE_FLAG" ] && [ -f "$TRIGGER" ] && appAlive; then
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
      if [ "$READY" != 1 ] && ! guard_live; then log "STALE up t=${up_ms}ms (ring-buffer replay) -> ignored"; continue; fi
      echo "$seq" > "$RELSEQ" 2>/dev/null
      dur=$(( up_ms - down_ms ))
      log "RELEASE seq=$seq dur=${dur}ms"
      if [ "$V3" = 1 ]; then
        if [ "$dur" -ge "$LONG_MS" ] 2>/dev/null && mkdir "$CLAIM.$seq" 2>/dev/null; then
          # S-2: release won the race but the kernel says it was a long press.
          log "LATE-LONG seq=$seq dur=${dur}ms (release beat the timer) -> long"
          if [ -f "$TRIGGER" ] && appAlive; then
            echo "long $seq $LONG_MS" > "$TRIGGER" 2>/dev/null
            if [ "$dur" -ge "$HOLD_MS" ] 2>/dev/null; then
              ( sleep 0.3; echo "up $seq $dur" > "$TRIGGER" 2>/dev/null ) </dev/null >/dev/null 2>&1 &
            fi
          else
            launch -e ui_state listening -e gesture long --ez arm true
          fi
        elif mkdir "$CLAIM.$seq" 2>/dev/null; then
          log "TAP seq=$seq dur=${dur}ms -> press at release"
          [ -f "$TRIGGER" ] && echo "press $seq $dur" > "$TRIGGER" 2>/dev/null
          ( sleep $CHECK_AFTER
            if appAlive; then :; else
              log "MISS: app process dead after release -> force launch (seq=$seq)"
              launch -e ui_state listening --ez arm true
            fi ) </dev/null >/dev/null 2>&1 &
        else
          log "UP seq=$seq dur=${dur}ms (after long)"
          [ -f "$TRIGGER" ] && echo "up $seq $dur" > "$TRIGGER" 2>/dev/null
        fi
      fi
      ;;
  esac
done
