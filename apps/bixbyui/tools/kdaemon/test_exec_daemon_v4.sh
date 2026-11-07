#!/bin/bash
# ============================================================
# PoC/回归 — exec_daemon v4 的信任边界（fix-root，2026-10-25）
#
# 原 PoC（pentest-20261024/poc/poc_root_trustmodel.sh）证明 v3「只认目录+文件，不认身份」。
# 本脚本在同一套离线钩子上，对 **v4** 跑完整攻防矩阵：
#   旧 PoC 的四条攻击（P1/P2 任意命令、P3 冒名 debug、P4 冒名重装）现在都应当被**拒绝**，
#   同时合法请求仍然通过、v3 的功能行为（stale、双通道、标记开关）不回归。
#
# 与旧 PoC 的关键区别：本脚本用 **真实的不同 uid** 跑“攻击者”和“App”，不再只靠目录归属假装：
#   - sudo setpriv --reuid=<uid> 写请求文件 → 守护侧 stat -c %u 看到的就是真实属主；
#   - 包目录 chown 给对应 uid，模拟 /data/user/0/<pkg> 的归属；
#   - packages.xml 夹具里放真实的 DER 证书 hex（release / debug / 攻击者自签），
#     守护按 sha256(DER) 比对 的期望值。
# 需要 root（sandbox 里有 passwordless sudo）。没有 root 时自动跳过需要 uid 的用例并说明。
#
# 用法：bash poc_root_trustmodel_v4.sh [sh解释器，默认 dash]
# ============================================================
set -u
# 守护是 root 且 umask 077（日志/结果对非 root 不可读，这是对的）——测试编排本身也以 root 跑，
# 「App」和「攻击者」的写入通过 setpriv 降到各自 uid。
if [ "$(id -u)" != 0 ] && sudo -n true 2>/dev/null; then exec sudo -n -E bash "$0" "$@" 2>&1 | grep -v 'unable to resolve host'; exit "${PIPESTATUS[0]}"; fi
SHELL_BIN=${1:-dash}
HERE=$(cd "$(dirname "$0")" && pwd)
WT=${WT:-$(cd "$HERE/../.." && pwd)}
DAEMON=${DAEMON:-$WT/tools/kdaemon/exec_daemon.v4.sh}
V3=${V3:-$WT/tools/kdaemon/exec_daemon.v3.sh}
FIX=$WT/tools/kdaemon/testdata
# 测试证书是随仓库的合成证书（CN=OneSay test fixture …）；把期望值 / 吊销名单经环境变量交给守护
export BIXBY_EXEC_SIG_REL=$(sha256sum "$FIX/release_cert.der" | cut -c1-64)
export BIXBY_EXEC_SIG_DBG=$(sha256sum "$FIX/debug_cert_rotated.der" | cut -c1-64)
export BIXBY_EXEC_REVOKED_SIGS=$(sha256sum "$FIX/debug_cert_old_revoked.der" | cut -c1-64)

PASS=0; FAIL=0; SKIP=0
ok(){ PASS=$((PASS+1)); echo "  [PASS] $1"; }
no(){ FAIL=$((FAIL+1)); echo "  [FAIL] $1"; [ -n "${2:-}" ] && echo "         $2"; }
sk(){ SKIP=$((SKIP+1)); echo "  [SKIP] $1"; }

UID_REL=10234; UID_DBG=10235; UID_ATK=10999
HAVE_ROOT=0; [ "$(id -u)" = 0 ] && HAVE_ROOT=1
R(){ "$@"; }                               # 已经是 root
AS(){ u=$1; shift; setpriv --reuid="$u" --regid="$u" --clear-groups "$@"; }

hex(){ od -An -tx1 -v "$1" | tr -d ' \n'; }

# packages.xml 夹具：<pkg> → (uid, 证书 DER)
mkxml(){ # $1 输出路径；后续三元组 pkg uid certfile
  out=$1; shift
  { echo '<?xml version="1.0" encoding="utf-8"?>'; echo '<packages>'; } > "$out"
  i=0
  while [ $# -ge 3 ]; do
    p=$1; u=$2; c=$3; shift 3
    {
      echo "    <package name=\"$p\" codePath=\"/data/app/$p-1\" userId=\"$u\">"
      echo "        <sigs count=\"1\" schemeVersion=\"2\">"
      echo "            <cert index=\"$i\" key=\"$(hex "$c")\" />"
      echo "        </sigs>"
      echo "    </package>"
    } >> "$out"
    i=$((i+1))
  done
  echo '</packages>' >> "$out"
}

start(){ # start <workdir>  —— 以 root 起守护（真机上它就是 root）
  W=$1
  if [ "$HAVE_ROOT" = 1 ]; then
    env BIXBY_EXEC_DIR="$W" BIXBY_EXEC_REL_DIR="$W/u0/com.onesay.assistant/files" \
         BIXBY_EXEC_DBG_DIR="$W/u0/com.onesay.assistant.debug/files" BIXBY_EXEC_PKGXML="$W/packages.xml" \
         setsid "$SHELL_BIN" "$DAEMON" </dev/null >/dev/null 2>&1 &
  else
    BIXBY_EXEC_DIR="$W" BIXBY_EXEC_REL_DIR="$W/u0/com.onesay.assistant/files" \
      BIXBY_EXEC_DBG_DIR="$W/u0/com.onesay.assistant.debug/files" BIXBY_EXEC_PKGXML="$W/packages.xml" \
      setsid "$SHELL_BIN" "$DAEMON" </dev/null >/dev/null 2>&1 &
  fi
  DPID=$!
  sleep 0.7
}
stop(){ pkill -f "$DAEMON" 2>/dev/null; kill -- -$DPID 2>/dev/null; kill $DPID 2>/dev/null; sleep 0.3; }

# 等结果文件非空
wait_res(){ i=0; while [ ! -s "$1" ] && [ $i -lt ${2:-60} ]; do sleep 0.05; i=$((i+1)); done; [ -s "$1" ]; }
res_rc(){ head -1 "$1" 2>/dev/null; }
res_body(){ tail -n +2 "$1" 2>/dev/null; }
# 请求文件被守护「取走」= 原文件消失或为空
taken(){ [ ! -e "$1" ] || [ ! -s "$1" ]; }

setup(){   # 建一个干净的工作区：两个包目录 + packages.xml + sec 目录
  W=$(mktemp -d); REL=$W/u0/com.onesay.assistant/files; DBG=$W/u0/com.onesay.assistant.debug/files
  mkdir -p "$REL" "$DBG" "$W/sec"
  mkxml "$W/packages.xml" com.onesay.assistant $UID_REL "$FIX/release_cert.der" \
                          com.onesay.assistant.debug $UID_DBG "$FIX/debug_cert_rotated.der"
  if [ "$HAVE_ROOT" = 1 ]; then
    R chown -R $UID_REL:$UID_REL "$W/u0/com.onesay.assistant"
    R chown -R $UID_DBG:$UID_DBG "$W/u0/com.onesay.assistant.debug"
    R chmod 777 "$W"            # 让各 uid 能在 $W 下建战利品文件（真机上无关）
  fi
  LOGF=$W/exec.log
}

# 以某个 uid 写请求文件（文件属主 = 该 uid，这正是 ROOT-1a 校验的东西）
write_req(){ # write_req <uid> <目标文件> <内容>
  if [ "$HAVE_ROOT" = 1 ]; then
    printf '%s' "$3" | R setpriv --reuid="$1" --regid="$1" --clear-groups tee "$2" >/dev/null
  else
    printf '%s' "$3" > "$2"
  fi
}

echo "== PoC/回归：exec_daemon v4 信任边界（解释器 $SHELL_BIN, root=$HAVE_ROOT）=="
echo

# ============================================================
echo "-- A. 旧 PoC 的四条攻击，在 v4 上应当全部被拒 --"
# ============================================================
setup; start "$W"

# A1（原 P1/P2）：任意命令。合法 uid 写，但命令是自由 shell（写文件 + 读身份）
LOOT=$W/ROOTED_release
write_req $UID_REL "$REL/.bixby_req" "id > $LOOT 2>&1; echo PWNED-REL >> $LOOT"
sleep 1.2
if [ ! -e "$LOOT" ] && grep -q "exec DENY METACHAR" "$LOGF"; then
  ok "A1 任意命令：带 ; 和重定向的自由 shell 被拒（DENY METACHAR），没有任何命令被执行"
else
  no "A1 未拦住" "loot=$( [ -e "$LOOT" ] && cat "$LOOT" ); log=$(grep -c DENY "$LOGF")"
fi
# App 能拿到明确失败而不是静默
if wait_res "$REL/.bixby_res" 40 && [ "$(res_rc "$REL/.bixby_res")" = 77 ]; then
  ok "A1b 拒绝有回执：.bixby_res 写回 rc=77 + 原因（$(res_body "$REL/.bixby_res" | head -1)）"
else
  no "A1b 拒绝没有回执" "res=[$(cat "$REL/.bixby_res" 2>/dev/null)]"
fi

# A2（原 P3b）：冒名 debug 包 —— 目录属主 / packages.xml 里的签名都换成攻击者的
: > "$W/debug_exec"
sleep 0.5
if [ "$HAVE_ROOT" = 1 ]; then
  R rm -rf "$W/u0/com.onesay.assistant.debug"; mkdir -p "$DBG"
  R chown -R $UID_ATK:$UID_ATK "$W/u0/com.onesay.assistant.debug"
  mkxml "$W/packages.xml" com.onesay.assistant $UID_REL "$FIX/release_cert.der" \
                          com.onesay.assistant.debug $UID_ATK "$FIX/attacker_cert.der"
  LOOT_D=$W/ROOTED_debug
  write_req $UID_ATK "$DBG/.bixby_req" "echo PWNED-DBG"
  sleep 1.5
  ATK_SHA=$(sha256sum "$FIX/attacker_cert.der"); ATK_SHA=${ATK_SHA%% *}
  if grep -q "exec\[debug\] ALERT SIG_MISMATCH pkg=com.onesay.assistant.debug got=$ATK_SHA" "$LOGF" && ! grep -q 'exec\[debug\] rc=0' "$LOGF"; then
    ok "A2 冒名 debug 包：守护从 packages.xml 算出的证书摘要 = 攻击者证书 ${ATK_SHA:0:8}…，与期望的 debug 证书不符 → 拒 + ALERT 日志，命令没执行"
  else
    no "A2 冒名 debug 未拦住" "$(grep 'debug' "$LOGF" | tail -3)"
  fi
  if [ "$(res_rc "$DBG/.bixby_res")" = 77 ]; then
    ok "A2b 冒名 debug 的拒绝同样写回 rc=77（$(res_body "$DBG/.bixby_res" | head -1)）"
  else
    no "A2b 冒名 debug 无回执" "res=[$(cat "$DBG/.bixby_res" 2>/dev/null)]"
  fi
else
  sk "A2/A2b 冒名 debug（需要 root 才能换目录属主）"; SKIP=$((SKIP+1))
fi

# A3（原 P4）：冒名重装 release —— 签名正确但 uid 与 packages.xml 不一致（攻击者占了目录）
if [ "$HAVE_ROOT" = 1 ]; then
  mkxml "$W/packages.xml" com.onesay.assistant $UID_REL "$FIX/release_cert.der" \
                          com.onesay.assistant.debug $UID_DBG "$FIX/debug_cert_rotated.der"
  R chown -R $UID_ATK:$UID_ATK "$W/u0/com.onesay.assistant"
  sleep 0.1
  : > "$LOGF"
  write_req $UID_ATK "$REL/.bixby_req" "id"
  sleep 1.5
  if grep -q 'ALERT UID_MISMATCH' "$LOGF" && ! grep -q 'exec rc=' "$LOGF"; then
    ok "A3 冒名占用 release：目录属主 uid=$UID_ATK ≠ packages.xml 里 com.onesay.assistant 的 uid=$UID_REL → 拒 + ALERT"
  else
    no "A3 冒名 release 未拦住" "$(tail -3 "$LOGF")"
  fi
  R chown -R $UID_REL:$UID_REL "$W/u0/com.onesay.assistant"
else
  sk "A3 冒名重装 release（需要 root）"
fi

# A4：同包名、同目录，但请求文件属主是**别的 uid**（第三方把字节塞进来）
if [ "$HAVE_ROOT" = 1 ]; then
  # 真机上第三方 uid 写不进别包私有目录（沙箱）；这里用 root 放一个属主为攻击者 uid 的请求文件，
  # 代表「沙箱之外的路径把别人的字节塞进来」（如硬链接 / 备份恢复 / 另一 root 组件误写）。
  : > "$LOGF"; rm -f "$REL/.bixby_res"
  printf 'id' > "$REL/.bixby_req"; chown $UID_ATK:$UID_ATK "$REL/.bixby_req"
  sleep 1.2
  if grep -q 'DENY BAD_OWNER' "$LOGF" && ! grep -q 'exec rc=' "$LOGF"; then
    ok "A4 调用方 uid 校验：请求文件属主 $UID_ATK ≠ 包目录属主 $UID_REL → 拒（ROOT-1a）"
  else
    no "A4 uid 校验没生效" "$(tail -3 "$LOGF")"
  fi
else
  sk "A4 调用方 uid 校验（需要 root）"
fi
stop

# ============================================================
echo
echo "-- B. 符号链接与属主（ROOT-4） --"
# ============================================================
setup; start "$W"
TARGET=$W/VICTIM; echo original > "$TARGET"; chmod 644 "$TARGET"
# B1：.bixby_req 是指向别处的符号链接
if [ "$HAVE_ROOT" = 1 ]; then
  echo "id" > "$W/evil_req"; R chown $UID_ATK:$UID_ATK "$W/evil_req"
  AS $UID_REL ln -sf "$W/evil_req" "$REL/.bixby_req"
else
  echo "id" > "$W/evil_req"; ln -sf "$W/evil_req" "$REL/.bixby_req"
fi
sleep 1.2
if grep -q 'DENY SYMLINK' "$LOGF" && ! grep -q 'exec rc=' "$LOGF"; then
  ok "B1 请求文件是符号链接 → 拒，不跟随（ROOT-4）"
else
  no "B1 符号链接请求未拦住" "$(tail -3 "$LOGF")"
fi

# B2：.bixby_res 被预置成指向敏感文件的符号链接，合法请求的结果不得写穿到目标
rm -f "$REL/.bixby_req"
if [ "$HAVE_ROOT" = 1 ]; then AS $UID_REL ln -sf "$TARGET" "$REL/.bixby_res"; else ln -sf "$TARGET" "$REL/.bixby_res"; fi
write_req $UID_REL "$REL/.bixby_req" "id"
sleep 1.5
if [ "$(cat "$TARGET")" = original ] && [ ! -L "$REL/.bixby_res" ]; then
  ok "B2 结果文件被预置成符号链接：mv 原子替换，链接目标 $TARGET 未被 root 写穿"
else
  no "B2 结果写穿了符号链接" "target=[$(head -1 "$TARGET")] res_is_link=$([ -L "$REL/.bixby_res" ] && echo yes || echo no)"
fi

# B3：临时结果文件被预置成符号链接
rm -f "$REL/.bixby_res"; echo original > "$TARGET"
if [ "$HAVE_ROOT" = 1 ]; then AS $UID_REL ln -sf "$TARGET" "$REL/.bixby_res.tmp.99"; else ln -sf "$TARGET" "$REL/.bixby_res.tmp.99"; fi
write_req $UID_REL "$REL/.bixby_req" "id"
sleep 1.5
if [ "$(cat "$TARGET")" = original ]; then
  ok "B3 App 目录里预置的 .bixby_res.tmp.* 符号链接无效：结果改在 root-only 的 sec/ 里暂存，目标未被改写"
else
  no "B3 临时文件写穿了符号链接" "target=[$(head -1 "$TARGET")]"
fi
stop

# ============================================================
echo
echo "-- C. 命令白名单（ROOT-1c） --"
# ============================================================
setup; start "$W"
try(){ # try <期望 allow|deny> <命令> <说明>
  rm -f "$REL/.bixby_res"; : > "$LOGF"
  write_req $UID_REL "$REL/.bixby_req" "$2"
  wait_res "$REL/.bixby_res" 50
  rc=$(res_rc "$REL/.bixby_res")
  if [ "$1" = deny ]; then
    if [ "$rc" = 77 ]; then ok "C 拒绝：$3 → $(res_body "$REL/.bixby_res" | head -1)"
    else no "C 本应拒绝却放行：$3" "rc=$rc out=[$(res_body "$REL/.bixby_res" | head -1)]"; fi
  else
    if [ -n "$rc" ] && [ "$rc" != 77 ]; then ok "C 放行：$3 (rc=$rc)"
    else no "C 本应放行却被拒：$3" "rc=$rc out=[$(res_body "$REL/.bixby_res" | head -1)]"; fi
  fi
}
try deny  'id; cat /data/system/packages.xml'            '命令串里的 ;'
try deny  'id && id'                                     '&&'
try deny  'id > /data/local/tmp/x'                       '重定向'
try deny  'echo `id`'                                    '反引号'
try deny  'echo $(id)'                                   '$()'
try deny  'sh -c id'                                     '非白名单程序 sh'
try deny  'toybox nc -l -p 4444'                         '非白名单程序 toybox'
try deny  'settings put secure enabled_accessibility_services evil/evil'  '黑名单设置键（无障碍服务）'
try deny  'settings put global adb_enabled 1'            '黑名单设置键（adb）'
try deny  'settings put system some_new_key 1'           '不在白名单的设置键'
try deny  'cat /data/system/packages.xml'                'cat 读白名单外路径'
try deny  'cat /data/user/0/com.onesay.assistant/files/../../../../etc/passwd'  '路径里的 ..'
try deny  'rm -rf /data/user/0/com.tencent.mm/files'     '写别的包的目录'
try deny  'am broadcast -n com.android.settings/.X -a y' '给别包发广播'
try deny  'dumpsys battery | grep -r x /etc'             '管道段里 grep 读文件'
try allow 'id'                                           '合法：id'
try allow 'settings get system screen_brightness'        '合法：settings get'
try allow 'settings put system screen_brightness 120'    '合法：白名单设置键 put'
try allow 'am start -n com.onesay.assistant/.WebViewUiActivity'   '合法：am start -n'
try allow 'dumpsys battery | grep -m1 " level:"'         '合法：受控管道 dumpsys|grep'
try allow 'echo 1 > /sys/class/leds/torch-sec1/brightness' '合法：固定模板（手电筒）'
try allow 'settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true' '合法：固定模板（飞行模式）'
try allow 'rm -f /data/user/0/com.onesay.assistant/files/scr.raw' '合法：清自己目录里的文件'

# raw 开关
rm -f "$REL/.bixby_res"; : > "$LOGF"
write_req $UID_REL "$REL/.bixby_req" "$(printf '#bixby-exec v4\nact=raw\na=id; echo RAW-RAN')"
wait_res "$REL/.bixby_res" 50
if [ "$(res_rc "$REL/.bixby_res")" = 77 ] && grep -q 'DENY RAW_DISABLED' "$LOGF"; then
  ok "C raw：act=raw 默认拒绝（RAW_DISABLED）"
else
  no "C raw 默认没拒" "res=[$(cat "$REL/.bixby_res")]"
fi
R touch "$W/sec/allow_raw" 2>/dev/null || touch "$W/sec/allow_raw"
rm -f "$REL/.bixby_res"
write_req $UID_REL "$REL/.bixby_req" "$(printf '#bixby-exec v4\nact=raw\na=echo RAW-RAN')"
wait_res "$REL/.bixby_res" 50
if res_body "$REL/.bixby_res" | grep -q RAW-RAN; then
  ok "C raw：root 建 sec/allow_raw 后放行（逃生通道可用，默认关）"
else
  no "C raw 开关打开后仍不通" "res=[$(cat "$REL/.bixby_res")]"
fi
# 过渡期：legacy 复合脚本在 allow_raw 下执行（记 WARN LEGACY_RAW）；身份校验仍在
rm -f "$REL/.bixby_res"; : > "$LOGF"
write_req $UID_REL "$REL/.bixby_req" 'echo a; echo LEGACY-RAN'
wait_res "$REL/.bixby_res" 50
if res_body "$REL/.bixby_res" | grep -q LEGACY-RAN && grep -q 'WARN LEGACY_RAW' "$LOGF"; then
  ok "C 过渡：allow_raw 开时 legacy 复合脚本可执行，并记 WARN LEGACY_RAW（App 迁移前的兼容开关）"
else
  no "C 过渡开关不通" "res=[$(cat "$REL/.bixby_res")]"
fi
rm -f "$REL/.bixby_res"; : > "$LOGF"
printf 'echo a; echo ATK' > "$REL/.bixby_req"; chown $UID_ATK:$UID_ATK "$REL/.bixby_req"
sleep 1.2
if ! grep -q 'exec rc=' "$LOGF" && grep -q 'DENY BAD_OWNER' "$LOGF"; then
  ok "C 过渡：allow_raw 开着时，错 uid 的请求仍被拒（身份校验不受该开关影响）"
else
  no "C allow_raw 绕过了身份校验" "$(tail -2 "$LOGF")"
fi
R rm -f "$W/sec/allow_raw" 2>/dev/null || rm -f "$W/sec/allow_raw"
stop

# ============================================================
echo
echo "-- D. nonce/HMAC（ROOT-1d） --"
# ============================================================
setup; start "$W"
KF=$W/sec/key.com.onesay.assistant
KEY=$(cat "$KF" 2>/dev/null)
kmode=$(stat -c %a "$KF"); kown=$(stat -c %u "$KF")
KEY_DBG=$(cat "$W/sec/key.com.onesay.assistant.debug" 2>/dev/null)
if [ -n "$KEY" ] && [ -n "$KEY_DBG" ] && [ "$KEY" != "$KEY_DBG" ] && [ "$kmode" = 600 ] && [ "$(stat -c %a "$W/sec")" = 700 ] && { [ "$kown" = 0 ] || [ "$HAVE_ROOT" = 0 ]; }; then
  ok "D0 密钥：每包一把（release/debug 不同），sec/ 700、key.* 600、属主 uid=$kown"
else
  no "D0 密钥权限不对" "mode=$kmode owner=$kown"
fi
mac(){ # mac <signed payload>
  i=$(printf '%s%s' "$KEY" "$1" | sha256sum); i=${i%% *}
  o=$(printf '%s%s' "$KEY" "$i" | sha256sum); printf '%s' "${o%% *}"
}
mkreq(){ # mkreq <ts> <nonce> <argv…>  → 打印完整请求体
  ts=$1; nc=$2; shift 2
  body="#bixby-exec v4
ts=$ts
nonce=$nc
act=argv"
  for a in "$@"; do body="$body
a=$a"; done
  printf '%s\nmac=%s\n' "$body" "$(mac "$body")"
}
NOW=$(date +%s)
rm -f "$REL/.bixby_res"; : > "$LOGF"
write_req $UID_REL "$REL/.bixby_req" "$(mkreq "$NOW" nonce0001aaaa id)"
wait_res "$REL/.bixby_res" 50
if [ "$(res_rc "$REL/.bixby_res")" = 0 ] && ! grep -q 'WARN NO_MAC' "$LOGF"; then
  ok "D1 带正确 MAC 的结构化请求：通过（rc=0，无 NO_MAC 告警）"
else
  no "D1 合法 MAC 请求没过" "res=[$(cat "$REL/.bixby_res")] log=$(tail -2 "$LOGF")"
fi
# D2 重放
rm -f "$REL/.bixby_res"; : > "$LOGF"
write_req $UID_REL "$REL/.bixby_req" "$(mkreq "$NOW" nonce0001aaaa id)"
wait_res "$REL/.bixby_res" 50
if [ "$(res_rc "$REL/.bixby_res")" = 77 ] && grep -q 'MAC_replay' "$LOGF"; then
  ok "D2 重放同一个 nonce：拒（MAC_replay）"
else
  no "D2 重放没拦住" "res=[$(cat "$REL/.bixby_res")]"
fi
# D3 改命令不改 mac
rm -f "$REL/.bixby_res"; : > "$LOGF"
B=$(mkreq "$NOW" nonce0002bbbb id); B2=$(printf '%s' "$B" | sed 's/^a=id$/a=reboot/')
write_req $UID_REL "$REL/.bixby_req" "$B2"
wait_res "$REL/.bixby_res" 50
if [ "$(res_rc "$REL/.bixby_res")" = 77 ] && grep -q 'MAC_bad' "$LOGF"; then
  ok "D3 篡改被签名的命令（id→reboot）而不改 mac：拒（MAC_bad）"
else
  no "D3 篡改没拦住" "res=[$(cat "$REL/.bixby_res")]"
fi
# D4 过期时间戳
rm -f "$REL/.bixby_res"; : > "$LOGF"
write_req $UID_REL "$REL/.bixby_req" "$(mkreq $((NOW-600)) nonce0003cccc id)"
wait_res "$REL/.bixby_res" 50
if [ "$(res_rc "$REL/.bixby_res")" = 77 ] && grep -q 'MAC_skew' "$LOGF"; then
  ok "D4 时间戳超出时窗：拒（MAC_skew）"
else
  no "D4 时窗没生效" "res=[$(cat "$REL/.bixby_res")]"
fi
# D5 legacy 无 mac：warn 模式放行，强制模式拒
rm -f "$REL/.bixby_res"; : > "$LOGF"
write_req $UID_REL "$REL/.bixby_req" "id"
wait_res "$REL/.bixby_res" 50
if [ "$(res_rc "$REL/.bixby_res")" = 0 ] && grep -q 'WARN NO_MAC' "$LOGF"; then
  ok "D5 兼容：legacy 明文请求（无 mac）仍执行，但记 WARN NO_MAC"
else
  no "D5 legacy 兼容坏了" "res=[$(cat "$REL/.bixby_res")]"
fi
R touch "$W/sec/require_mac" 2>/dev/null || touch "$W/sec/require_mac"
rm -f "$REL/.bixby_res"; : > "$LOGF"
write_req $UID_REL "$REL/.bixby_req" "id"
wait_res "$REL/.bixby_res" 50
if [ "$(res_rc "$REL/.bixby_res")" = 77 ] && grep -q 'MAC_REQUIRED' "$LOGF"; then
  ok "D6 root 建 sec/require_mac 后：无 mac 的请求被拒（App 侧落地后即可打开）"
else
  no "D6 强制模式没生效" "res=[$(cat "$REL/.bixby_res")]"
fi
# D7 受控握手：provision_key 开 → 身份校验通过后把本包的密钥交付到 files/.bixby_key（App uid、600）
touch "$W/sec/provision_key"
rm -f "$REL/.bixby_res"; : > "$LOGF"
B=$(mkreq "$(date +%s)" nonce0007gggg id)
write_req $UID_REL "$REL/.bixby_req" "$B"
wait_res "$REL/.bixby_res" 50
if [ -f "$REL/.bixby_key" ] && [ "$(cat "$REL/.bixby_key")" = "$KEY" ] && [ "$(stat -c %u "$REL/.bixby_key")" = "$UID_REL" ] && [ "$(stat -c %a "$REL/.bixby_key")" = 600 ] && [ "$(res_rc "$REL/.bixby_res")" = 0 ]; then
  ok "D7 密钥交付：files/.bixby_key 属主 $UID_REL、600、内容=本包密钥；同时该请求正常执行"
else
  no "D7 密钥交付不对" "key=$( [ -f "$REL/.bixby_key" ] && stat -c '%u %a' "$REL/.bixby_key") res=[$(cat "$REL/.bixby_res")]"
fi
# D8 冒名包拿不到密钥：错签名的 debug 包目录里不会出现 .bixby_key
touch "$W/debug_exec"
mkxml "$W/packages.xml" com.onesay.assistant $UID_REL "$FIX/release_cert.der" com.onesay.assistant.debug $UID_DBG "$FIX/attacker_cert.der"
write_req $UID_DBG "$DBG/.bixby_req" "id"
sleep 1.3
if [ ! -e "$DBG/.bixby_key" ] && grep -q 'exec\[debug\] ALERT SIG_MISMATCH' "$LOGF"; then
  ok "D8 签名不符的包：被拒，且拿不到密钥（.bixby_key 不交付）"
else
  no "D8 冒名包拿到了密钥" "$(ls -la "$DBG" 2>/dev/null | grep bixby_key)"
fi
# D9 预置 .bixby_key 为指向敏感文件的符号链接：交付不得写穿
mkxml "$W/packages.xml" com.onesay.assistant $UID_REL "$FIX/release_cert.der" com.onesay.assistant.debug $UID_DBG "$FIX/debug_cert_rotated.der"
echo original > "$W/VICTIM2"
AS $UID_DBG ln -sf "$W/VICTIM2" "$DBG/.bixby_key"
sleep 0.2
write_req $UID_DBG "$DBG/.bixby_req" "id"
sleep 1.5
if [ "$(cat "$W/VICTIM2")" = original ] && [ -f "$DBG/.bixby_key" ] && [ ! -L "$DBG/.bixby_key" ]; then
  ok "D9 .bixby_key 被预置成符号链接：链接被替换，目标未被写穿"
else
  no "D9 密钥交付写穿了符号链接" "victim=[$(cat "$W/VICTIM2")]"
fi
rm -f "$W/sec/provision_key" "$W/debug_exec"
R rm -f "$W/sec/require_mac" 2>/dev/null || rm -f "$W/sec/require_mac"
stop

# ============================================================
echo
echo "-- E. ROOT-5 原子取件 / ROOT-6 pidfile --"
# ============================================================
setup; start "$W"
# E1：多行命令写一半不会被读走半条（取件用 mv，写入用同目录 rename）
: > "$LOGF"; rm -f "$REL/.bixby_res"
write_req $UID_REL "$REL/.bixby_req.stage" "id"
if [ "$HAVE_ROOT" = 1 ]; then AS $UID_REL mv "$REL/.bixby_req.stage" "$REL/.bixby_req"; else mv "$REL/.bixby_req.stage" "$REL/.bixby_req"; fi
wait_res "$REL/.bixby_res" 50
if [ "$(res_rc "$REL/.bixby_res")" = 0 ]; then
  ok "E1 取件：rename 进来的请求被完整读取并执行（ROOT-5 的写入侧约定）"
else
  no "E1 rename 请求没被执行" "res=[$(cat "$REL/.bixby_res")]"
fi
# E2：守护取件后不留 .proc 残骸
if ! ls "$W"/sec/.req.* "$W"/sec/.res.* >/dev/null 2>&1 && ! ls "$REL"/.bixby_res.tmp.* >/dev/null 2>&1; then
  ok "E2 取件/落盘：请求 rename 进 root-only 的 sec/ 再读，结果在 sec/ 备好再 rename 出去，两边都不留残骸"
else
  no "E2 留下了残骸" "$(ls -a "$W/sec" "$REL" 2>/dev/null | tr '\n' ' ')"
fi
# E3：pidfile 在 root-only 的 sec 目录，属主 root、600
pm=$( R stat -c %a "$W/sec/exec.pid" 2>/dev/null || stat -c %a "$W/sec/exec.pid" )
pu=$( R stat -c %u "$W/sec/exec.pid" 2>/dev/null || stat -c %u "$W/sec/exec.pid" )
if [ "$pm" = 600 ] && { [ "$pu" = 0 ] || [ "$HAVE_ROOT" = 0 ]; }; then
  ok "E3 pidfile 已移出 /data/local/tmp：$W/sec/exec.pid，600、属主 uid=$pu（ROOT-6）"
else
  no "E3 pidfile 权限不对" "mode=$pm owner=$pu"
fi
stop
# E4：非 root 预置的 pidfile 不能阻止守护启动
if [ "$HAVE_ROOT" = 1 ]; then
  echo 1 > "$W/sec/exec.pid.fake"; R chown $UID_ATK:$UID_ATK "$W/sec/exec.pid"
  echo 1 > /dev/null
  R sh -c "echo 99999 > $W/sec/exec.pid; chown $UID_ATK:$UID_ATK $W/sec/exec.pid"
  : > "$LOGF"
  start "$W"
  if grep -q 'pidfile tampered' "$LOGF" && grep -q 'exec-daemon v4 start' "$LOGF"; then
    ok "E4 pidfile 被非 root 预置：忽略并重建，守护照常启动（ROOT-6 DoS 失效）"
  else
    no "E4 预置 pidfile 仍能 DoS" "$(tail -3 "$LOGF")"
  fi
  stop
else
  sk "E4 pidfile 预置 DoS（需要 root）"
fi

# ============================================================
echo
echo "-- F. v3 行为不回归（stale / 双通道 / 标记开关） --"
# ============================================================
setup; start "$W"
# F1 stale
: > "$LOGF"; rm -f "$REL/.bixby_res"
write_req $UID_REL "$REL/.bixby_req.stage" "id"
if [ "$HAVE_ROOT" = 1 ]; then R touch -d '-20 seconds' "$REL/.bixby_req.stage"; R chown $UID_REL:$UID_REL "$REL/.bixby_req.stage"; AS $UID_REL mv "$REL/.bixby_req.stage" "$REL/.bixby_req";
else touch -d '-20 seconds' "$REL/.bixby_req.stage"; mv "$REL/.bixby_req.stage" "$REL/.bixby_req"; fi
sleep 1.2
if grep -q 'exec STALE dropped' "$LOGF" && [ ! -s "$REL/.bixby_res" ]; then
  ok "F1 v3 不回归：过期请求丢弃、不写结果、日志 STALE dropped"
else
  no "F1 stale 行为变了" "$(tail -3 "$LOGF")"
fi
# F2 debug 标记默认关
: > "$LOGF"; rm -f "$DBG/.bixby_res"
write_req $UID_DBG "$DBG/.bixby_req" "id"
sleep 1
if [ ! -s "$DBG/.bixby_res" ] && [ -s "$DBG/.bixby_req" ]; then
  ok "F2 v3 不回归：无 /data/adb/bixbyui_debug_exec 标记时 debug 请求原样留着、不执行"
else
  no "F2 debug 默认开着？" "req=[$(cat "$DBG/.bixby_req" 2>/dev/null)] res=[$(cat "$DBG/.bixby_res" 2>/dev/null)]"
fi
# F3 建标记后合法 debug 请求通过
: > "$W/debug_exec"
wait_res "$DBG/.bixby_res" 60
if [ "$(res_rc "$DBG/.bixby_res")" = 0 ] && grep -q 'exec\[debug\] rc=0' "$LOGF" && ! grep -q 'TOFU_PIN' "$W/exec.log"; then
  ok "F3 v3 不回归：建标记后，签名/uid 都对的 debug 包请求被执行，日志标 [debug]（走的是签名比对，不是 TOFU 兜底）"
else
  no "F3 合法 debug 请求没过" "res=[$(cat "$DBG/.bixby_res" 2>/dev/null)] $(grep debug "$LOGF" | tail -2)"
fi
# F3b 吊销名单里的 debug 证书签的包：哪怕 uid 都对，也按 REVOKED_CERT 拒
mkxml "$W/packages.xml" com.onesay.assistant $UID_REL "$FIX/release_cert.der" com.onesay.assistant.debug $UID_DBG "$FIX/debug_cert_old_revoked.der"
sleep 0.2; : > "$LOGF"; rm -f "$DBG/.bixby_res"
write_req $UID_DBG "$DBG/.bixby_req" "id"
sleep 1.3
if grep -q 'ALERT REVOKED_CERT' "$LOGF" && ! grep -q 'exec\[debug\] rc=' "$LOGF"; then
  ok "F3b 旧 debug 证书（已随仓库泄露）签的包：REVOKED_CERT 拒 + ALERT（配合 fix-ipc 的密钥轮换）"
else
  no "F3b 吊销证书仍被接受" "$(tail -2 "$LOGF")"
fi
mkxml "$W/packages.xml" com.onesay.assistant $UID_REL "$FIX/release_cert.der" com.onesay.assistant.debug $UID_DBG "$FIX/debug_cert_rotated.der"
# F4 删标记立刻停
rm -f "$W/debug_exec"; sleep 0.6; rm -f "$DBG/.bixby_res"; : > "$LOGF"
write_req $UID_DBG "$DBG/.bixby_req" "id"
sleep 1
if [ ! -s "$DBG/.bixby_res" ]; then
  ok "F4 v3 不回归：删标记后立刻停止给 debug 执行"
else
  no "F4 删标记后仍执行" "res=[$(cat "$DBG/.bixby_res")]"
fi
# F5 两个包各回各的结果
: > "$W/debug_exec"; sleep 0.4
rm -f "$REL/.bixby_res" "$DBG/.bixby_res"
write_req $UID_REL "$REL/.bixby_req" "echo rel-x"
write_req $UID_DBG "$DBG/.bixby_req" "echo dbg-x"
wait_res "$REL/.bixby_res" 60; wait_res "$DBG/.bixby_res" 60
if res_body "$REL/.bixby_res" | grep -q rel-x && res_body "$DBG/.bixby_res" | grep -q dbg-x; then
  ok "F5 v3 不回归：两个包同时发请求，各回各的 .bixby_res，不串"
else
  no "F5 结果串了" "rel=[$(cat "$REL/.bixby_res")] dbg=[$(cat "$DBG/.bixby_res")]"
fi
# F6 BIXBY_EXEC_DIR 测试钩子仍在（旧式单目录用法）
stop
# 与原 PoC 一样以普通用户跑（目录、请求、守护同一个非 root uid）
W2=$(mktemp -d); mkdir -p "$W2/rel" "$W2/dbg"; chown -R 1000:1000 "$W2"
setpriv --reuid=1000 --regid=1000 --clear-groups env BIXBY_EXEC_DIR=$W2 setsid "$SHELL_BIN" "$DAEMON" </dev/null >/dev/null 2>&1 &
HPID=$!; sleep 0.8
setpriv --reuid=1000 --regid=1000 --clear-groups sh -c "printf 'echo hook-ok' > $W2/rel/.bixby_req"
i=0; while [ ! -s "$W2/rel/.bixby_res" ] && [ $i -lt 60 ]; do sleep 0.05; i=$((i+1)); done
if tail -n +2 "$W2/rel/.bixby_res" 2>/dev/null | grep -q hook-ok; then
  ok "F6 测试钩子：BIXBY_EXEC_DIR 旧式单目录用法原样可用（离线 PoC 继续能跑）"
else
  no "F6 BIXBY_EXEC_DIR 钩子坏了" "res=[$(cat "$W2/rel/.bixby_res" 2>/dev/null)] log=[$(tail -3 "$W2/exec.log" 2>/dev/null)]"
fi
kill -- -$HPID 2>/dev/null; kill $HPID 2>/dev/null

# ============================================================
echo
echo "-- G. 对照：同样的攻击打在 v3 上 --"
# ============================================================
if [ -f "$V3" ]; then
  W3=$(mktemp -d); mkdir -p "$W3/rel"
  BIXBY_EXEC_DIR=$W3 setsid "$SHELL_BIN" "$V3" </dev/null >/dev/null 2>&1 &
  P3=$!; sleep 0.6
  L3=$W3/ROOTED
  printf 'id > "%s" 2>&1; echo PWNED >> "%s"' "$L3" "$L3" > "$W3/rel/.bixby_req"
  i=0; while [ ! -s "$W3/rel/.bixby_res" ] && [ $i -lt 60 ]; do sleep 0.05; i=$((i+1)); done
  if [ -f "$L3" ] && grep -q PWNED "$L3"; then
    ok "G v3 对照：同一条命令在 v3 上仍被无条件执行（证明 A1 的拦截来自 v4 的改动，不是环境差异）"
  else
    no "G v3 对照没复现（环境问题？）"
  fi
  kill -- -$P3 2>/dev/null; kill $P3 2>/dev/null; rm -rf "$W3"
else
  sk "G v3 对照（找不到 $V3）"
fi

# ============================================================
echo
echo "-- I. 签名不可得时的兜底（Android 12+ ABX packages.xml / 读不到）：uid pin --"
# ============================================================
setup; rm -f "$W/packages.xml"; start "$W"
: > "$LOGF"; rm -f "$REL/.bixby_res"
write_req $UID_REL "$REL/.bixby_req" "id"
wait_res "$REL/.bixby_res" 60
if [ "$(res_rc "$REL/.bixby_res")" = 0 ] && grep -q "WARN TOFU_PIN pkg=com.onesay.assistant uid=$UID_REL" "$LOGF"; then
  ok "I1 拿不到签名：首次见到即固定 uid（WARN TOFU_PIN），请求照常执行"
else
  no "I1 TOFU 兜底没按预期" "res=[$(cat "$REL/.bixby_res" 2>/dev/null)] $(tail -2 "$LOGF")"
fi
chown -R $UID_ATK:$UID_ATK "$W/u0/com.onesay.assistant"; : > "$LOGF"; rm -f "$REL/.bixby_res"
write_req $UID_ATK "$REL/.bixby_req" "id"
sleep 1.3
if grep -q 'ALERT PIN_MISMATCH' "$LOGF" && ! grep -q 'exec rc=' "$LOGF"; then
  ok "I2 固定后冒名重装（uid $UID_REL→$UID_ATK）：PIN_MISMATCH 拒 + ALERT"
else
  no "I2 uid pin 没挡住冒名" "$(tail -2 "$LOGF")"
fi
stop
setup; rm -f "$W/packages.xml"; touch "$W/sec/strict_pin"; start "$W"
: > "$LOGF"; rm -f "$REL/.bixby_res"
write_req $UID_REL "$REL/.bixby_req" "id"
wait_res "$REL/.bixby_res" 60
if [ "$(res_rc "$REL/.bixby_res")" = 77 ] && grep -q 'DENY NO_IDENTITY' "$LOGF"; then
  ok "I3 strict_pin 开：签名不可验证时一律拒（不做首次信任）"
else
  no "I3 strict_pin 没生效" "res=[$(cat "$REL/.bixby_res" 2>/dev/null)]"
fi
stop

# ============================================================
echo
echo "-- J. v4.1（fix-root2）：签名路径的固定模板 / 新模板，require_mac 开启后明文拒、签名过 --"
# ============================================================
# 桩程序：settings/am/reboot/screencap/uiautomator/dumpsys 全部换成只记账的桩（PATH 前置），绝不碰沙箱本机。
STUB=$(mktemp -d); STUBLOG=$STUB/calls.log; : > "$STUBLOG"; chmod 777 "$STUB"; chmod 666 "$STUBLOG"
for p in settings am reboot dumpsys; do
  printf '#!/bin/sh\necho "STUB-%s $*" >> %s\necho "STUB-%s $*"\n' "$p" "$STUBLOG" "$p" > "$STUB/$p"
done
# dumpsys 桩按服务给出 App 会 grep 的行
cat > "$STUB/dumpsys" <<EOS
#!/bin/sh
echo "STUB-dumpsys \$*" >> $STUBLOG
case \$1 in nfc) echo "mState=on" ;; account) printf 'Account {name=a, type=com.google}\nAccount {name=b, type=com.google}\n' ;; *) echo "svc \$1" ;; esac
EOS
cat > "$STUB/uiautomator" <<EOS
#!/bin/sh
echo "STUB-uiautomator \$*" >> $STUBLOG
for f; do :; done
case \$f in /*) echo '<hierarchy rotation="0"/>' > "\$f"; echo "UI hierchary dumped to: \$f" ;; *) echo '<hierarchy/>' ;; esac
EOS
cat > "$STUB/screencap" <<EOS
#!/bin/sh
echo "STUB-screencap \$*" >> $STUBLOG
for f; do :; done
printf '\211PNG-STUB-DATA' > "\$f"
EOS
chmod 755 "$STUB"/*
# 安全闸：守护里 sh -c 'reboot' 必须解析到桩，否则整组跳过（绝不在沙箱里真重启）
if [ "$(env PATH="$STUB:$PATH" sh -c 'command -v reboot')" != "$STUB/reboot" ]; then
  sk "J/M 整组（PATH 桩不生效，为安全起见不跑 reboot 模板）"; J_OK=0
else J_OK=1; fi

startj(){ # 带桩 PATH、媒体根、hijack 钩子起守护
  W=$1
  env PATH="$STUB:$PATH" BIXBY_EXEC_DIR="$W" BIXBY_EXEC_REL_DIR="$W/u0/com.onesay.assistant/files" \
      BIXBY_EXEC_DBG_DIR="$W/u0/com.onesay.assistant.debug/files" BIXBY_EXEC_PKGXML="$W/packages.xml" \
      BIXBY_EXEC_MEDIA_ROOTS="$W/sdcard/Pictures" \
      setsid "$SHELL_BIN" "$DAEMON" </dev/null >/dev/null 2>&1 &
  DPID=$!; sleep 0.7
}
NC=0
signreq(){ # signreq <act> <argv…> → 用守护交付给 App 的 .bixby_key 签名（与 App ExecProtocol 同构）
  act=$1; shift; NC=$((NC+1))
  body="#bixby-exec v4
ts=$(date +%s)
nonce=r2$(od -An -tx1 -N12 /dev/urandom | tr -d ' \n')
act=$act"
  for a in "$@"; do body="$body
a=$a"; done
  i=$(printf '%s%s' "$AKEY" "$body" | sha256sum); i=${i%% *}
  o=$(printf '%s%s' "$AKEY" "$i" | sha256sum)
  printf '%s\nmac=%s\n' "$body" "${o%% *}"
}
send(){ # send <body> → 等结果；RC / OUT
  rm -f "$REL/.bixby_res"; : > "$LOGF"
  write_req $UID_REL "$REL/.bixby_req" "$1"
  wait_res "$REL/.bixby_res" 80
  RC=$(res_rc "$REL/.bixby_res"); OUT=$(res_body "$REL/.bixby_res")
}
expect_exec(){ # expect_exec <说明> <桩日志里应出现的串，可空>
  if [ -n "$RC" ] && [ "$RC" != 77 ] && { [ -z "$2" ] || grep -qF -- "$2" "$STUBLOG"; }; then ok "$1 (rc=$RC)"
  else no "$1" "rc=$RC out=[$(printf '%s' "$OUT" | head -2 | tr '\n' ' ')] log=[$(tail -1 "$LOGF")]"; fi
}
expect_deny(){ # expect_deny <说明> <原因前缀>
  if [ "$RC" = 77 ] && printf '%s' "$OUT" | grep -q "^DENY $2"; then ok "$1 → $(printf '%s' "$OUT" | head -1)"
  else no "$1（本应 DENY $2）" "rc=$RC out=[$(printf '%s' "$OUT" | head -1)]"; fi
}

if [ "$J_OK" = 1 ]; then
setup; startj "$W"
touch "$W/sec/provision_key"
send "id"                                   # 首次明文请求：拿到密钥交付（与集成那轮同样的握手）
AKEY=$(cat "$REL/.bixby_key" 2>/dev/null)
[ -n "$AKEY" ] && ok "J0 provision_key：App 侧拿到 files/.bixby_key（后续请求全部用它签名）" || no "J0 没拿到密钥"
touch "$W/sec/require_mac"

TPL_AIR1='settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true'
TPL_AIR0='settings put global airplane_mode_on 0 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false'
TPL_LOC1='settings put secure location_providers_allowed +gps; settings put secure location_providers_allowed +network'
TPL_LOC0='settings put secure location_providers_allowed -gps; settings put secure location_providers_allowed -network'
TPL_T1='echo 1 > /sys/class/leds/torch-sec1/brightness'
TPL_T0='echo 0 > /sys/class/leds/torch-sec1/brightness'
PROBE=$(cat "$HERE/testdata/daemon_probe.txt")

# J1 明文模板在 require_mac 下被拒（证明开关生效）
for t in "$TPL_AIR1" "$TPL_LOC1" "$TPL_T1" reboot "$PROBE"; do
  send "$t"; expect_deny "J1 require_mac：明文模板「${t%% *}…」" MAC_REQUIRED
done
# J2 签名 act=tmpl：8 条 App 固定模板 + 状态模板，全部执行
: > "$STUBLOG"
send "$(signreq tmpl "$TPL_AIR1")"; expect_exec "J2 签名模板：飞行模式开" "STUB-am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true"
send "$(signreq tmpl "$TPL_AIR0")"; expect_exec "J2 签名模板：飞行模式关" "STUB-am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
send "$(signreq tmpl "$TPL_LOC1")"; expect_exec "J2 签名模板：定位开" "STUB-settings put secure location_providers_allowed +network"
send "$(signreq tmpl "$TPL_LOC0")"; expect_exec "J2 签名模板：定位关" "STUB-settings put secure location_providers_allowed -network"
send "$(signreq tmpl "$TPL_T1")"; if [ "$RC" != 77 ] && grep -q 'tmpl=1 signed=1 cmd=echo 1 >' "$LOGF"; then ok "J2 签名模板：手电开（守护执行了自己的字面量，rc=$RC——沙箱无该 sysfs 节点）"; else no "J2 手电开" "rc=$RC $(tail -1 "$LOGF")"; fi
send "$(signreq tmpl "$TPL_T0")"; if [ "$RC" != 77 ] && grep -q 'tmpl=1 signed=1 cmd=echo 0 >' "$LOGF"; then ok "J2 签名模板：手电关（rc=$RC）"; else no "J2 手电关" "rc=$RC $(tail -1 "$LOGF")"; fi
send "$(signreq tmpl reboot)"; expect_exec "J2 签名模板：重启（桩）" "STUB-reboot "
send "$(signreq tmpl 'reboot -p')"; expect_exec "J2 签名模板：关机（桩）" "STUB-reboot -p"
# J3 act=argv 而 argv 拼起来逐字等于模板（reboot 这类分得开的）也认
: > "$STUBLOG"
send "$(signreq argv reboot -p)"; expect_exec "J3 签名 act=argv [reboot,-p] = 模板 → 执行模板" "STUB-reboot -p"
# J4 模板不能绕过 MAC / 不能夹带
B=$(signreq tmpl reboot); B=$(printf '%s' "$B" | sed 's/^a=reboot$/a=reboot -p/')
send "$B"; expect_deny "J4 篡改签名模板内容（reboot→reboot -p）" MAC_bad
send "$(signreq tmpl 'reboot; id')"; expect_deny "J4 act=tmpl 但不是模板" TMPL_UNKNOWN
send "$(signreq tmpl reboot -p)"; expect_deny "J4 act=tmpl 必须恰好一行 a=" "TMPL_ARGC"
B=$(signreq tmpl "$TPL_T1"); send "$B"; send "$B"; expect_deny "J4 签名模板重放" MAC_replay

# J5 新模板：签名请求执行，明文请求拒
mkdir -p "$REL/scr"; chown $UID_REL:$UID_REL "$REL/scr"
: > "$STUBLOG"
send "$(signreq argv uiautomator dump --compressed "$REL/scr/sr_ui.xml")"
expect_exec "J5 签名：uiautomator dump --compressed（识屏指代）" "STUB-uiautomator dump --compressed $REL/scr/sr_ui.xml"
send "uiautomator dump --compressed $REL/scr/sr_ui.xml"; expect_deny "J5 明文：uiautomator dump --compressed" MAC_REQUIRED
: > "$STUBLOG"
send "$(signreq argv nice -n 10 uiautomator dump --compressed "$REL/scr/sr_ui.xml")"
expect_exec "J5 签名：nice -n 10 前缀（唤醒采集）" "STUB-uiautomator dump --compressed"
send "$(signreq argv dumpsys nfc '|' grep -m1 mState)"
if [ "$RC" = 0 ] && [ "$OUT" = "mState=on" ]; then ok "J5 签名：dumpsys nfc | grep -m1 mState → $OUT"; else no "J5 dumpsys nfc" "rc=$RC out=[$OUT]"; fi
send "$(signreq argv dumpsys account '|' grep -c com.google)"
if [ "$RC" = 0 ] && [ "$OUT" = 2 ]; then ok "J5 签名：dumpsys account | grep -c com.google → $OUT"; else no "J5 dumpsys account" "rc=$RC out=[$OUT]"; fi
send "dumpsys nfc | grep -m1 mState"; expect_deny "J5 明文：dumpsys nfc" MAC_REQUIRED
send "dumpsys account | grep -c com.google"; expect_deny "J5 明文：dumpsys account" MAC_REQUIRED

# J6 新模板的非法参数（签名也不放）
send "$(signreq argv nice -n 5 uiautomator dump "$REL/scr/x.xml")"; expect_deny "J6 nice 只认 -n 10" "NICE_ARGS"
send "$(signreq argv nice -n 10 sh -c id)"; expect_deny "J6 nice 只能包 uiautomator/screencap" "NICE_TARGET(sh)"
send "$(signreq argv nice -n 10 nice -n 10 id)"; expect_deny "J6 nice 不能套娃" "NICE_TARGET(nice)"
send "$(signreq argv uiautomator dump --compressed --verbose "$REL/scr/x.xml")"; expect_deny "J6 uiautomator 额外开关" "UIAUTOMATOR_ARGC"
send "$(signreq argv uiautomator dump --compressed /data/system/x.xml)"; expect_deny "J6 uiautomator 写白名单外路径" "PATH_NOT_ALLOWED"
send "$(signreq argv uiautomator dump --compressed "$REL/../../../x.xml")"; expect_deny "J6 uiautomator 路径带 .." "PATH_DOTDOT"
ln -sf /etc/passwd "$REL/scr/lnk.xml"
send "$(signreq argv uiautomator dump --compressed "$REL/scr/lnk.xml")"; expect_deny "J6 uiautomator 输出是符号链接" "PATH_SYMLINK"
send "$(signreq argv dumpsys nfc --all)"; expect_deny "J6 dumpsys nfc 不许带参数" "DUMPSYS_ARGC"
send "$(signreq argv dumpsys account '|' grep -r x /etc)"; expect_deny "J6 dumpsys account 后接 grep 读文件" "GREP_FLAG"
send "$(signreq argv dumpsys 'nfc;id')"; expect_deny "J6 签名参数里的 ; 是字面量（不是命令分隔）" "DUMPSYS_SVC(nfc;id)"

# J7 状态模板（G7）：只读固定路径，只回状态串
bash -c 'exec -a hijack_daemon sleep 30' & HJ=$!
sleep 0.2; echo "$HJ" > "$W/hijack.pid"
mkdir -p "$W/modules/bixbykey_hijack"
printf '#!/system/bin/sh\n# BixbyKey hijack daemon - v9.2 SECRET-LINE-CONTENT\nTOKEN=abc\n' > "$W/modules/bixbykey_hijack/hijack_daemon.sh"
send "$(signreq tmpl "$PROBE")"
if [ "$RC" = 0 ] && [ "$OUT" = "$(printf 'ALIVE\nMOD\nhijack daemon - v9.2\nEND')" ]; then ok "J7 签名状态模板（App 原文 DAEMON_PROBE）：ALIVE/MOD/v9.2/END，不回显模块脚本其它内容"
else no "J7 状态模板输出不对" "rc=$RC out=[$OUT]"; fi
send "$(signreq tmpl bixby-status)"; [ "$RC" = 0 ] && printf '%s' "$OUT" | grep -q '^END$' && ok "J7 bixby-status 别名同样可用" || no "J7 bixby-status" "rc=$RC out=[$OUT]"
kill $HJ 2>/dev/null; wait $HJ 2>/dev/null
echo SECRET-PID-FILE > "$W/secret"; rm -f "$W/hijack.pid"; ln -s "$W/secret" "$W/hijack.pid"; rm -rf "$W/modules"
send "$(signreq tmpl bixby-status)"
if [ "$RC" = 0 ] && ! printf '%s' "$OUT" | grep -q SECRET && printf '%s' "$OUT" | grep -q '^NOMOD$'; then ok "J7 pidfile 被换成指向秘密文件的链接：只输出 $(printf '%s' "$OUT" | tr '\n' '/')，不泄露内容"
else no "J7 状态模板泄露了文件内容" "out=[$OUT]"; fi
send "$PROBE"; expect_deny "J7 明文状态探针（require_mac 下）" MAC_REQUIRED

# J8 存图缓存层（G8）：保持拦截，拒绝码清晰
LISTCMD=$(cat "$HERE/testdata/cache_list_cmd.txt")
send "$(signreq argv find /data/data/com.tencent.mm/cache -type f)"; expect_deny "J8 签名 argv 读别的 App 缓存" "OTHER_APP_DATA(com.tencent.mm)"
send "$(signreq argv stat -c '%Y %s %n' /data/user/0/com.tencent.mm/cache/a.jpg)"; expect_deny "J8 签名 stat 别的 App 私有文件" "OTHER_APP_DATA(com.tencent.mm)"
send "$(signreq argv cp -f /data/data/com.tencent.mm/cache/a.jpg "$REL/scr/c_0")"; expect_deny "J8 签名 cp 别的 App 缓存到自己目录" "OTHER_APP_DATA(com.tencent.mm)"
rm -f "$W/sec/require_mac"; touch "$W/sec/allow_raw"
send "$LISTCMD"; expect_deny "J8 allow_raw 开着、明文 CacheMatcher.listCommand 也拒（不走 LEGACY_RAW）" "OTHER_APP_DATA(com.tencent.mm)"
grep -q 'v4 有意拦截' "$LOGF" && ok "J8 日志写明「v4 有意拦截：不读别的 App 私有数据」" || no "J8 日志没写明" "$(tail -1 "$LOGF")"
send "$(signreq raw "$LISTCMD")"; expect_deny "J8 act=raw + allow_raw 也拒" "OTHER_APP_DATA(com.tencent.mm)"
rm -f "$W/sec/allow_raw"
stop

# ============================================================
echo
echo "-- M. v4.1 G3：受控媒体写（Pictures/<本包子目录>，O_NOFOLLOW 等价 / 属主 / 设备号） --"
# ============================================================
setup; mkdir -p "$W/sdcard/Pictures"; chown 0:0 "$W/sdcard" "$W/sdcard/Pictures"; chmod 755 "$W/sdcard/Pictures"
MR=$W/sdcard/Pictures
startj "$W"; touch "$W/sec/provision_key"; send "id"; AKEY=$(cat "$REL/.bixby_key" 2>/dev/null); touch "$W/sec/require_mac"
mkdir -p "$REL/imgsave"; printf 'PNGBYTES-1' > "$REL/imgsave/out_a.png"; chown -R $UID_REL:$UID_REL "$REL/imgsave"
send "$(signreq argv mkdir -p "$MR/OneSay/com.tencent.mm")"
if [ "$RC" = 0 ] && [ -d "$MR/OneSay/com.tencent.mm" ] && [ "$(stat -c %u "$MR/OneSay/com.tencent.mm")" = 0 ]; then ok "M1 签名 mkdir -p Pictures/OneSay/<包>：逐级建好，属主随 Pictures 根"; else no "M1 媒体 mkdir" "rc=$RC out=[$OUT]"; fi
send "$(signreq argv cp -f "$REL/imgsave/out_a.png" "$MR/OneSay/com.tencent.mm/a.png")"
if [ "$RC" = 0 ] && [ "$(cat "$MR/OneSay/com.tencent.mm/a.png")" = PNGBYTES-1 ] && ! ls -a "$MR/OneSay/com.tencent.mm" | grep -q bixbytmp; then ok "M2 签名 cp 本包缓存 → Pictures/OneSay/<包>/a.png：内容一致、无临时残留"; else no "M2 媒体 cp" "rc=$RC out=[$OUT]"; fi
: > "$STUBLOG"
send "$(signreq argv screencap -p "$MR/bixbyui_shot.png")"
if [ "$RC" = 0 ] && grep -q 'PNG-STUB-DATA' "$MR/bixbyui_shot.png" && grep -q 'STUB-screencap -p' "$STUBLOG"; then ok "M3 签名 screencap -p Pictures/bixbyui_shot.png（ui.screenshot）：先写临时名再 rename"; else no "M3 截图到相册" "rc=$RC out=[$OUT]"; fi
send "screencap -p $MR/bixbyui_shot.png"; expect_deny "M3 明文截图到相册（require_mac 下）" MAC_REQUIRED
# 越界
send "$(signreq argv cp -f "$REL/imgsave/out_a.png" "$MR/Other/a.png")"; expect_deny "M4 写 Pictures 下别的目录" "MEDIA_SUBDIR"
send "$(signreq argv screencap -p "$MR/evil.png")"; expect_deny "M4 截图写到非固定文件名" "MEDIA_SUBDIR"
send "$(signreq argv cp -f "$REL/imgsave/out_a.png" "$MR/OneSay/x/a.sh")"; expect_deny "M4 媒体目录只许图片扩展名" "MEDIA_EXT"
send "$(signreq argv mkdir -p "$MR/OneSay/../../x")"; expect_deny "M4 媒体路径带 .." "MEDIA_PATH"
send "$(signreq argv mkdir -p "$MR/OneSay/a/b/c")"; expect_deny "M4 媒体目录超过三级" "MEDIA_DEPTH"
send "$(signreq argv cp -f "$MR/OneSay/com.tencent.mm/a.png" "$MR/OneSay/x/b.png")"; expect_deny "M4 源不在本包可写前缀（不许把媒体当中转）" "MEDIA_SRC_NOT_OWN"
send "$(signreq argv cp -f /data/data/com.tencent.mm/cache/a.jpg "$MR/OneSay/x/b.jpg")"; expect_deny "M4 源是别的 App 私有数据" "OTHER_APP_DATA"
send "$(signreq argv cp -f "$REL/imgsave/out_a.png" "$MR/OneSay/x/b.png" "$MR/OneSay/x/c.png")"; expect_deny "M4 cp 多参数" "CP_ARGC"
send "$(signreq argv nice -n 10 screencap -p "$MR/bixbyui_shot.png")"; expect_deny "M4 nice 不能包媒体写" "NICE_MEDIA"
# 符号链接
VD=$W/victimdir; mkdir -p "$VD"; ln -s "$VD" "$MR/OneSay/evil"
send "$(signreq argv cp -f "$REL/imgsave/out_a.png" "$MR/OneSay/evil/a.png")"
if [ "$RC" != 0 ] && [ -z "$(ls -A "$VD")" ]; then ok "M5 媒体路径里的目录符号链接：拒（rc=$RC，$(printf '%s' "$OUT" | head -1)），链接目标未被写入"; else no "M5 跟随了目录符号链接" "rc=$RC ls=$(ls -A "$VD")"; fi
mkdir -p "$MR/OneSay/s"; echo original > "$W/VICTIM3"; ln -s "$W/VICTIM3" "$MR/OneSay/s/a.png"
send "$(signreq argv cp -f "$REL/imgsave/out_a.png" "$MR/OneSay/s/a.png")"
if [ "$(cat "$W/VICTIM3")" = original ] && [ -f "$MR/OneSay/s/a.png" ] && [ ! -L "$MR/OneSay/s/a.png" ]; then ok "M5 目标文件被预置成符号链接：rename 替换链接本身，目标未被写穿"; else no "M5 写穿了目标符号链接" "victim=[$(cat "$W/VICTIM3")]"; fi
ln -s "$W/secret" "$REL/imgsave/lnk.png"
send "$(signreq argv cp -f "$REL/imgsave/lnk.png" "$MR/OneSay/s/l.png")"; expect_deny "M5 源是符号链接" "PATH_SYMLINK"
ln -s "$W/sdcard/Pictures/OneSay" "$MR/OneSay-lnk" 2>/dev/null
# 错属主
mkdir -p "$MR/OneSay/atk"; chown $UID_ATK:$UID_ATK "$MR/OneSay/atk"
send "$(signreq argv cp -f "$REL/imgsave/out_a.png" "$MR/OneSay/atk/a.png")"
if [ "$RC" != 0 ] && [ ! -e "$MR/OneSay/atk/a.png" ] && printf '%s' "$OUT" | grep -q 'owner mismatch'; then ok "M6 中间目录属主被换成别的 uid：拒（$(printf '%s' "$OUT" | head -1)）"; else no "M6 错属主目录没拦住" "rc=$RC out=[$OUT]"; fi
echo x > "$MR/OneSay/s/own.png"; chown $UID_ATK:$UID_ATK "$MR/OneSay/s/own.png"
send "$(signreq argv cp -f "$REL/imgsave/out_a.png" "$MR/OneSay/s/own.png")"
if [ "$RC" != 0 ] && [ "$(cat "$MR/OneSay/s/own.png")" = x ]; then ok "M6 已存在的目标文件属主不对：拒，不覆盖"; else no "M6 覆盖了错属主文件" "rc=$RC"; fi
# debug 通道只能写自己的子目录
send "$(signreq argv mkdir -p "$MR/OneSay-Debug/x")"; expect_deny "M7 release 通道不能写 debug 的子目录" "MEDIA_SUBDIR"
stop
rm -rf "$STUB"
fi

# ============================================================
echo
echo "-- K. 离线判定：新模板 × shell 元字符（管道只许接受控过滤器） --"
# ============================================================
P0=/data/user/0/com.onesay.assistant/cache/scratch
for base in "uiautomator dump --compressed $P0/screenref/sr_ui.xml" "nice -n 10 uiautomator dump --compressed $P0/screenref/sr_ui.xml" "dumpsys nfc" "dumpsys account" "mkdir -p /storage/emulated/0/Pictures/OneSay/com.tencent.mm" "cp -f $P0/imgsave/out_a.png /storage/emulated/0/Pictures/OneSay/com.tencent.mm/a.png" "screencap -p /sdcard/Pictures/bixbyui_shot.png" "bixby-status"; do
  for suf in '; id' ' && id' ' > /data/local/tmp/x' ' `id`' ' $(id)' ' | sh' ' || id'; do
    r=$(BIXBY_EXEC_DIR=/tmp/chk BIXBY_EXEC_CHECK=1 "$SHELL_BIN" "$DAEMON" "$base$suf")
    case $r in DENY*) ok "K 「${base%% /*}…」+「$suf」→ $r" ;; *) no "K 「$base$suf」本应拒" "$r" ;; esac
  done
done
# debug 变体：自己的子目录 / 截图文件名（BuildVariant.PUBLIC_DIR / PUBLIC_FILE_TAG），且不能写 release 的
for c in "mkdir -p /storage/emulated/0/Pictures/OneSay-Debug/com.tencent.mm" "screencap -p /sdcard/Pictures/bixbyui_debug_shot.png"; do
  r=$(BIXBY_EXEC_DIR=/tmp/chk BIXBY_EXEC_CHECK=1 "$SHELL_BIN" "$DAEMON" "$c" com.onesay.assistant.debug)
  [ "$r" = ALLOW ] && ok "K debug 通道：$c → ALLOW" || no "K debug 通道 $c" "$r"
done
r=$(BIXBY_EXEC_DIR=/tmp/chk BIXBY_EXEC_CHECK=1 "$SHELL_BIN" "$DAEMON" "screencap -p /sdcard/Pictures/bixbyui_shot.png" com.onesay.assistant.debug)
case $r in DENY*) ok "K debug 通道不能写 release 的截图文件 → $r" ;; *) no "K debug 写了 release 截图" "$r" ;; esac
r=$(BIXBY_EXEC_DIR=/tmp/chk BIXBY_EXEC_CHECK=1 "$SHELL_BIN" "$DAEMON" "screencap -p /sdcard/Pictures/bixbyui_shot.png | grep x")
case $r in "DENY MEDIA_NO_PIPE") ok "K 媒体写不许接管道 → $r" ;; *) no "K 媒体写接管道" "$r" ;; esac

# ============================================================
echo
echo "-- H. 离线判定模式（不执行，给 App 侧 CI 用）：App 源码里的静态命令字面量 --"
# ============================================================
CM=$HERE/testdata/compat_cmds.txt
if [ -f "$CM" ]; then
  na=0; nd=0; : > "${TMPDIR:-/tmp}/compat_result.tsv"
  while IFS="$(printf '\t')" read -r f c; do
    r=$(BIXBY_EXEC_DIR=/tmp/chk BIXBY_EXEC_CHECK=1 "$SHELL_BIN" "$DAEMON" "$c")
    case $r in ALLOW*) na=$((na+1)) ;; *) nd=$((nd+1)) ;; esac
    printf '%s\t%s\t%s\n' "$r" "$f" "$c" >> "${TMPDIR:-/tmp}/compat_result.tsv"
  done < "$CM"
  # 3 条是 McpServerConfig/WatchSpec 里以 "id " 开头的报错文案，不是命令（抽取误报）
  real_deny=$(grep -v '^ALLOW' "${TMPDIR:-/tmp}/compat_result.tsv" | grep -vc 'McpServerConfig\|WatchSpec')
  if [ "$real_deny" = 0 ]; then
    ok "H 静态命令字面量 $((na+nd)) 条：放行 $na，拒 $nd（全部是抽取误报的报错文案），真实命令 0 条被误拒"
  else
    no "H 有 $real_deny 条真实 App 命令被白名单误拒" "$(grep -v '^ALLOW' "${TMPDIR:-/tmp}/compat_result.tsv" | grep -v 'McpServerConfig\|WatchSpec' | head -5)"
  fi
  # 判定模式是纯判定：不能执行任何东西
  r=$(BIXBY_EXEC_DIR=/tmp/chk BIXBY_EXEC_CHECK=1 "$SHELL_BIN" "$DAEMON" "id"); 
  [ "$r" = ALLOW ] && ok "H 判定模式只打印 ALLOW/DENY，不执行" || no "H 判定模式输出异常" "$r"
else
  sk "H 兼容矩阵（缺 $CM）"
fi

echo
echo "== RESULT pass=$PASS fail=$FAIL skip=$SKIP =="
[ $FAIL -eq 0 ]
