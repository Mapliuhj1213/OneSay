#!/system/bin/sh
# ============================================================
# Root 命令执行守护（文件 IPC）—— v2（2026-10-22，debug 变体-debug-variant.md）
#
# v2 = v1（设备上 /data/adb/modules/bixbykey_hijack/exec_daemon.sh，10-10 拉回的副本见
#      旧 v1 守护（已删除））+ 可选地同时为 debug 包 com.onesay.assistant.debug 服务：
#   · 默认只服务 release（/data/user/0/com.onesay.assistant/files/.bixby_req|.bixby_res），
#     路径、协议、超时、日志格式与 v1 完全一样；
#   · /data/adb/bixbyui_debug_exec 存在（root 才能建）且 debug 包已安装时，
#     额外轮询 /data/user/0/com.onesay.assistant.debug/files/.bixby_req，结果写回 debug 自己的 .bixby_res，
#     属主 / SELinux 标签按 debug 自己的 files 目录修（两个包 uid 不同，互不串）；
#   · 标记文件每轮现查：删掉立即停止给 debug 执行，不用重启守护。
#   ⚠ 守护按「包名对应的私有目录」信任请求：谁占了 com.onesay.assistant.debug 这个包名谁就拿到 root。
#     所以默认关；只在真机验证 debug 期间建标记，验证完删掉。
#   测试钩子：BIXBY_EXEC_DIR=<目录> → release 目录 <目录>/rel、debug 目录 <目录>/dbg、
#            标记 <目录>/debug_exec、日志 <目录>/exec.log、pid <目录>/exec.pid（离线测试 test_exec_daemon_v2.sh）。
#
# ---- v1 header (kept) ----
#
# 背景：本机 Magisk 的 per-app su 被拒（rc=13 Permission denied，授权库已损），
#       87 个映射表动作全部失效。本守护由 Magisk service.sh 以 root 启动，
#       是我们自己的进程，不受 Magisk 授权体系约束 —— 完全绕开该问题。
#
# 协议：
#   App 写 <files>/.bixby_req（命令文本，单行/多行均可）
#   → 守护执行 sh -c，捕获 stdout+stderr
#   → 写 <files>/.bixby_res（输出），并把属主改成 App uid 使 App 可读
#
# ⚠ 环境：Magisk 早期 PATH 极简且无 awk；只用 sh 内建 + date/sleep/cat/stat/chown/chmod。
#
# ★ BUG-001 修复（2026-10-01）：
#   旧版本 `out=$(sh -c "$cmd" 2>&1)` 没有超时。实测 `svc nfc disable`
#   在 NFC 服务处于 turning on 异常态时永不返回，守护被独占 91 秒，
#   其后 16 条本可成功的指令全部超时并降级到已损毁的 su，产出
#   "Permission denied" 误导性错误 —— 自测因此得到 PASS=59 FAIL=16 的假结论。
#   现在：① 每条命令用 timeout 包住（toybox 语法是 `timeout 8`，**没有** -t 选项；
#        本机实测 `timeout -t` 直接报 Unknown option t，写了等于没写）；
#        ② 执行放到后台子进程，主循环立刻回到轮询，绝不被单条命令占住；
#        ③ 结果先写 .tmp 再 mv 原子替换，App 不会读到写一半的 JSON/文本。
# ============================================================
if [ -n "${BIXBY_EXEC_DIR:-}" ]; then
  DIR=$BIXBY_EXEC_DIR/rel
  DBG_DIR=$BIXBY_EXEC_DIR/dbg
  DBG_FLAG=$BIXBY_EXEC_DIR/debug_exec
  LOG=$BIXBY_EXEC_DIR/exec.log
  PIDFILE=$BIXBY_EXEC_DIR/exec.pid
else
  DIR=/data/user/0/com.onesay.assistant/files
  DBG_DIR=/data/user/0/com.onesay.assistant.debug/files
  DBG_FLAG=/data/adb/bixbyui_debug_exec
  LOG=/data/adb/bixbykey_exec.log
  PIDFILE=/data/local/tmp/.bixby_exec.pid
fi
REQ=$DIR/.bixby_req
RES=$DIR/.bixby_res

# 单条命令的墙钟上限。取 10s：比 App 侧 rootExec 的 14s 短，
# 保证"超时"由守护自己报出来（App 能拿到明确结果）而不是 App 空等。
CMD_TIMEOUT=10

echo "$(date '+%F %T') exec-daemon v2 start (pid $$)" >> "$LOG"

# 单实例保护：两个守护同时轮询同一请求文件会互相抢，导致结果错乱。
# ⚠ 必须校验 cmdline：pidfile 跨重启存活且 pid 会被复用，只看 /proc/$pid
#   存在会误判 → 重启后再不启动（自动化链静默失效）。
if [ -f "$PIDFILE" ]; then
  old=$(cat "$PIDFILE" 2>/dev/null)
  if [ -n "$old" ] && [ -d "/proc/$old" ] && grep -q "exec_daemon" "/proc/$old/cmdline" 2>/dev/null; then
    echo "$(date '+%F %T') already running pid=$old, exit" >> "$LOG"
    exit 0
  fi
fi
echo $$ > "$PIDFILE"

# App 首装前 files 目录可能不存在，等它出现（最多 ~5 分钟）
# v2：debug 已开放且 debug 的 files 目录在，也算就绪（只装了 debug 时不必干等 5 分钟）
n=0
while [ ! -d "$DIR" ] && ! { [ -f "$DBG_FLAG" ] && [ -d "$DBG_DIR" ]; } && [ $n -lt 100 ]; do sleep 3; n=$((n + 1)); done
echo "$(date '+%F %T') files dir ready (waited ${n}x3s)" >> "$LOG"

# 把 root 在 App 私有目录里新建的文件"还"给 App：
#   root 新建的文件是 app_data_file:s0，缺 App 数据隔离类别 :c512,c768，
#   App 打开会被 SELinux 拒（表现为 FileNotFoundException Permission denied）。
# v2：第二个参数 = 这条请求所属包的 files 目录（缺省 release 的 $DIR），属主 / 标签照它修。
fix_owner() {
  d=${2:-$DIR}
  owner=$(stat -c %u "$d" 2>/dev/null)
  [ -n "$owner" ] && chown "$owner:$owner" "$1" 2>/dev/null
  ctx=$(ls -Zd "$d" 2>/dev/null); ctx=${ctx%% *}
  [ -n "$ctx" ] && chcon "$ctx" "$1" 2>/dev/null
  chmod 600 "$1" 2>/dev/null
}

# 执行一条命令并落盘结果。只在后台子进程里跑，主循环不等待。
# v2：$3 = 结果文件（缺省 release 的 $RES），$4 = 所属 files 目录，$5 = 日志标签（release 为空 → 日志与 v1 同格式）
run_one() {
  cmd="$1"
  tmp="$2"
  res=${3:-$RES}
  odir=${4:-$DIR}
  tag=${5:-}
  t0=$(date '+%s')
  out=$(timeout -k 2 $CMD_TIMEOUT sh -c "$cmd" 2>&1)
  rc=$?
  # toybox timeout 用信号终止子进程，rc = 128 + signal（实测 SIGTERM→142，
  # -s KILL→136）。正常命令不会落在这个区间，据此判定"超时"。
  timedout=0
  if [ $rc -ge 124 ] && [ $rc -le 143 ]; then
    timedout=1
    rc=124
    out="TIMEOUT: command exceeded ${CMD_TIMEOUT}s and was killed"
  fi
  { echo "$rc"; echo "$out"; } > "$tmp" 2>/dev/null
  fix_owner "$tmp" "$odir"
  mv -f "$tmp" "$res" 2>/dev/null
  now=$(date '+%s')
  if [ $timedout = "1" ]; then
    echo "$(date '+%F %T') exec${tag} TIMEOUT rc=$rc elapsed=$((now - t0))s cmd=$cmd" >> "$LOG"
  else
    echo "$(date '+%F %T') exec${tag} rc=$rc elapsed=$((now - t0))s cmd=$cmd" >> "$LOG"
  fi
}

# v2：轮询一个包的请求文件。$1 = files 目录，$2 = 日志标签。
serve() {
  sreq=$1/.bixby_req
  if [ -s "$sreq" ]; then
    cmd=$(cat "$sreq" 2>/dev/null)
    : > "$sreq" 2>/dev/null
    if [ -n "$cmd" ]; then
      seq=$((seq + 1))
      ( run_one "$cmd" "$1/.bixby_res.tmp.$seq" "$1/.bixby_res" "$1" "$2" ) &
    fi
  fi
}
DBG_ON=0

seq=0
while true; do
  if [ -s "$REQ" ]; then
    cmd=$(cat "$REQ" 2>/dev/null)
    : > "$REQ" 2>/dev/null
    if [ -n "$cmd" ]; then
      seq=$((seq + 1))
      # ★ 后台执行：主循环立刻回到 sleep 轮询，一条卡死的命令
      #   最多只影响它自己那一次调用，不再连锁拖垮后续指令。
      ( run_one "$cmd" "$RES.tmp.$seq" ) &
    fi
  fi
  # v2：debug 包（默认关；标记每轮现查）
  if [ -f "$DBG_FLAG" ] && [ -d "$DBG_DIR" ]; then
    [ "$DBG_ON" = 1 ] || { DBG_ON=1; echo "$(date '+%F %T') debug exec ON ($DBG_DIR)" >> "$LOG"; }
    serve "$DBG_DIR" "[debug]"
  elif [ "$DBG_ON" = 1 ]; then
    DBG_ON=0; echo "$(date '+%F %T') debug exec OFF" >> "$LOG"
  fi
  sleep 0.15
done
