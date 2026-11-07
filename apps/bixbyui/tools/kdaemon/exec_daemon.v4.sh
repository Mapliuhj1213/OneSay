#!/system/bin/sh
# ============================================================
# Root 命令执行守护（文件 IPC）—— v4（2026-10-25，渗透测试修复 fix-root）
#
# v4 = v3 + 真正的信任边界。v3 之前守护的信任模型只有一条：
#   「谁的进程拥有那个私有 files 目录、谁往 .bixby_req 里写字节，守护就把内容当 root 执行」
# （pentest-20261024/root.md §0-§1，ROOT-1 严重）。v4 把它改成「身份 + 完整性 + 受控命令集」：
#
#   1) ROOT-1a 调用方 uid 校验：请求文件属主必须 == 目标包 files 目录属主，且必须是 app uid（非 root）。
#   2) ROOT-1b 身份绑定：每轮（带 TTL 缓存）现查包身份——签名证书 SHA-256 / APK 摘要 / uid 固定（pin）。
#      冒名安装同包名会改变签名与 uid，这里会被拒。
#   3) ROOT-1c 命令白名单：不再 `sh -c "$cmd"`。请求被切成 argv，按「程序 + 参数规则」白名单放行，
#      单段命令直接以 argv 执行（根本不经过 shell）；受控管道只允许「只读命令 | grep/head/tail/tr/cut/wc」，
#      且各段参数仍以位置参数传入，绝不拼进命令串。管道 / ; / && / 重定向 / 反引号 / $() 一律拒绝。
#      无法模板化的自由命令保留为 act=raw，但**默认拒绝**，需 root 显式开 $SECDIR/allow_raw。
#   4) ROOT-1d nonce + MAC 请求认证：守护与 App 共享一把 root-only 密钥（$SECDIR/key，600 root）。
#      请求带 ts + nonce + mac，守护校验通过才执行；nonce 不可重放，ts 必须在时窗内。
#      ⚠ App 侧配套改动不在本线程（见 fix-root.md「App 侧待办」），所以默认是 warn 模式：
#        无 mac 的请求仍执行但记 WARN；root 建 $SECDIR/require_mac 后转为强制（无 mac / mac 错 → 拒）。
#   5) ROOT-4 符号链接防护：.bixby_req / .bixby_res / 临时文件一律拒绝符号链接，属主异常拒绝。
#   6) ROOT-5 读-清空原子化：先把请求 mv 成 .bixby_req.proc.<seq>（同目录 rename 原子）再读，
#      杜绝「读到半条」「第二条被清空吞掉」。
#   7) ROOT-6 pid 文件移到 root-only 的 $SECDIR 下，并校验属主必须是 root。
#
# v4.3（2026-10-31，r2/fast-screen）：只加一个固定模板 bixby-fastscreen-start —— 拉起「常驻 root 读屏助手」
#   （app_process 跑请求方自己 APK 里的 com.onesay.assistant.v2.fastscreen.HelperMain，参数只有包名 + uid）。
#   请求方身份（uid + 证书）照常先过 ident_ok；APK 路径取自 packages.xml 的 codePath，必须在 /data/app/ 下、是普通文件；
#   命令串由守护自己拼（不接受任何参数），已在跑就不重复拉。助手只开放固定方法（读树 / 截图 / 点按 / 按键…），没有任意 shell。
# v4.2（2026-10-29，daemon-mac：让 require_mac 在真机上真正可用）：
#   真机 Note8 mksh 上纯 sh hex2bin 把 1240 字节证书 DER 转二进制要 ~71 s，空闲 IDENT_TTL(60s) 后的首条请求
#   因此超过 STALE_S=3 被丢弃。改为启动时自检选用原生 `xxd -r -p`（toybox → Magisk busybox → PATH），纯 sh 仅兜底。
#   签名/MAC/吊销/require_mac 语义不变；工具输出错只会导致 SIG_MISMATCH（拒），不会放行。
# v4.4（2026-11-02，int/1102 统一 = fix/triage-fast + fix/device-1102 两处改动，同一版本号）：
#   ① fix/triage-fast：新增内建固定模板 bixby-clear-recents（清空后台，见 clear_recents）。
#      手机上的守护升级前，App 侧收到 TMPL_UNKNOWN（rc=77）会退回「多任务键 + 读屏助手点全部关闭」。
#   ② fix/device-1102：am start 放行 `-p <包名>`（隐式 Intent 限定接收包：导航用默认地图 App 的 geo:）。
#      只收合法包名（is_pkg）；broadcast 带 -p 仍拒（AM_BCAST_PKG）。旧守护拒 → App 回退到不带包名。
# v4.1（2026-10-25，fix-root2：收口集成报告 G1/G2/G3/G4/G7/G8，让 require_mac 能开）：
#   G1 签名请求也能走固定模板：act=tmpl + 恰好一行 a=<模板全文>（或 act=argv 而 argv 以空格拼起来逐字等于模板），
#      MAC 校验通过后才做模板匹配；执行的仍是守护自己那份字面量 / 内建实现。
#   G2 uiautomator dump [--compressed] [本包路径]；nice -n 10 仅作 uiautomator dump / screencap 的前缀（固定 -n 10）。
#   G3 受控媒体写：mkdir / cp / screencap 到 Pictures/<本包媒体子目录> 或固定截图文件，守护自己执行，
#      逐级拒符号链接、校验属主 / 设备号 / SELinux 标签与 Pictures 根一致，先写临时名再 rename（不跟随目标处的链接）。
#   G4 dumpsys nfc、dumpsys account（只许不带参数，后面可接受控过滤器）。
#   G7 按键守护状态模板（ConsoleStatus.DAEMON_PROBE 原文 或 bixby-status）：守护内建实现，只读固定路径，
#      只输出 ALIVE|DEAD / MOD|NOMOD / 规范化版本行 / END，不回显任何文件内容。
#   G8 读别的 App 私有数据（存图缓存层）保持拦截：拒绝码改为明确的 OTHER_APP_DATA(<包名>)，allow_raw 也放不开。
#
# 兼容性：
#   · 协议向后兼容：legacy 明文命令请求照旧接受，但要过白名单（过不了 → rc=77 DENY，写进 .bixby_res，
#     App 能拿到明确失败，而不是静默）。
#   · 日志格式沿用 v1/v3：`exec rc=` / `exec[debug] rc=` / `exec STALE dropped`，新增 `DENY` / `WARN` 行。
#   · 测试钩子 BIXBY_EXEC_DIR 完整保留（离线 PoC 继续能跑），另加 $BIXBY_EXEC_DIR/sec 作为
#     /data/adb/bixbyui_exec 的替身、$BIXBY_EXEC_DIR/packages.xml 作为 /data/system/packages.xml 的替身。
#
# ⚠ 环境：Magisk 早期 PATH 极简且无 awk；本脚本只用 sh 内建 + date/sleep/cat/stat/chown/chmod/
#   chcon/ls/mv/rm/timeout/sed/grep/tr/od + sha256sum（PATH → /data/adb/magisk/busybox → toybox 依次找；
#   都没有时：签名校验退到 uid pin，MAC 请求判 MAC_nohash 拒绝）。
# ============================================================

umask 077
# ---------- 路径与配置 ----------
if [ -n "${BIXBY_EXEC_DIR:-}" ]; then
  DIR=${BIXBY_EXEC_REL_DIR:-$BIXBY_EXEC_DIR/rel}      # v4：可覆盖成 <W>/u0/com.onesay.assistant/files，用来测包目录属主
  DBG_DIR=${BIXBY_EXEC_DBG_DIR:-$BIXBY_EXEC_DIR/dbg}
  DBG_FLAG=$BIXBY_EXEC_DIR/debug_exec
  LOG=$BIXBY_EXEC_DIR/exec.log
  SECDIR=${BIXBY_EXEC_SECDIR:-$BIXBY_EXEC_DIR/sec}
else
  DIR=/data/user/0/com.onesay.assistant/files
  DBG_DIR=/data/user/0/com.onesay.assistant.debug/files
  DBG_FLAG=/data/adb/bixbyui_debug_exec
  LOG=/data/adb/bixbykey_exec.log
  SECDIR=/data/adb/bixbyui_exec
fi
REQ=$DIR/.bixby_req
RES=$DIR/.bixby_res

PKG_REL=${BIXBY_EXEC_PKG_REL:-com.onesay.assistant}
PKG_DBG=${BIXBY_EXEC_PKG_DBG:-com.onesay.assistant.debug}

PIDFILE=$SECDIR/exec.pid          # ROOT-6：从 /data/local/tmp 搬到 root-only 目录
KEYFILE=$SECDIR/key               # ROOT-1d：HMAC 密钥前缀；每个包一把 key.<pkg>，600 root
FLAG_PROVISION=$SECDIR/provision_key  # 开：身份校验通过后把该包的密钥交付到它的 files/.bixby_key（600，App uid）
PINDIR=$SECDIR/pins               # 包身份 pin（签名不可验证时的 uid pin）
NONCEDIR=$SECDIR/nonce            # 已用 nonce（防重放）
FLAG_ALLOW_RAW=$SECDIR/allow_raw      # 开：允许非白名单自由命令（默认关）
FLAG_REQUIRE_MAC=$SECDIR/require_mac  # 开：强制 MAC（默认关，等 App 侧落地后打开）
FLAG_STRICT_PIN=$SECDIR/strict_pin    # 开：没有可验证身份时直接拒（默认 TOFU 首次固定）


# 单条命令的墙钟上限。取 10s：比 App 侧 rootExec 的 14s 短。
CMD_TIMEOUT=10
# v3：请求文件超过这么多秒没被取走就视为过期。
STALE_S=${BIXBY_EXEC_STALE_S:-3}
# 身份校验缓存秒数（避免每 0.15 s 跑一次 dumpsys）
IDENT_TTL=${BIXBY_EXEC_IDENT_TTL:-60}
# MAC 请求 ts 允许的偏差（秒）
MAC_SKEW=${BIXBY_EXEC_MAC_SKEW:-10}
# nonce 保留窗口（秒）
NONCE_KEEP=${BIXBY_EXEC_NONCE_KEEP:-120}

# ---------- 期望的签名证书 SHA-256 ----------
# 开源版不内置任何证书：在设备上用环境变量 BIXBY_EXEC_SIG_REL / BIXBY_EXEC_SIG_DBG 填你自己签名证书的 SHA-256
# （小写 hex，apksigner verify --print-certs 可查）。未设置 = 对应通道一律拒（SIG_NOT_CONFIGURED，fail closed）。
# BIXBY_EXEC_REVOKED_SIGS：空格分隔的吊销证书列表，即便配成期望值也一律拒。
EXPECT_SIG_REL=${BIXBY_EXEC_SIG_REL:-}
EXPECT_SIG_DBG=${BIXBY_EXEC_SIG_DBG:-}
REVOKED_SIGS=" ${BIXBY_EXEC_REVOKED_SIGS:-} "

# ---------- v4.1（fix-root2）：受控媒体写 / 守护状态模板 ----------
# 媒体目录（存图兜底 / 截图到相册）：只允许本包自己的子目录 Pictures/<MEDIA_SUB> 与固定截图文件 Pictures/<SHOT>，
#   两种写法都是守护自己执行（逐级 mkdir、cp/screencap 先写到不可预测的临时名再 rename），见 media_*。
#   App 侧路径：Environment.getExternalStoragePublicDirectory(PICTURES) = /storage/emulated/0/Pictures；ShellPrograms 用 /sdcard/Pictures。
MEDIA_ROOTS="/storage/emulated/0/Pictures /sdcard/Pictures"
# 按键守护状态模板（ConsoleStatus.DAEMON_PROBE）读的固定路径
HJ_PIDFILE=/data/local/tmp/.bixby_hijack.pid
HJ_MODDIR=/data/adb/modules/bixbykey_hijack
if [ -n "${BIXBY_EXEC_DIR:-}" ]; then          # 测试钩子：只在离线 PoC 下可覆盖
  MEDIA_ROOTS=${BIXBY_EXEC_MEDIA_ROOTS:-$MEDIA_ROOTS}
  HJ_PIDFILE=$BIXBY_EXEC_DIR/hijack.pid
  HJ_MODDIR=$BIXBY_EXEC_DIR/modules/bixbykey_hijack
fi

# 离线校验模式：BIXBY_EXEC_CHECK=1 sh exec_daemon.v4.sh '<命令>' [pkg]
#   只跑白名单判定，打印 ALLOW / DENY <原因>，不执行、不建目录、不起守护（给 App 侧 CI / 兼容矩阵用）。
CHECK_ONLY=${BIXBY_EXEC_CHECK:-0}

if [ "$CHECK_ONLY" != 1 ]; then
mkdir -p "$SECDIR" "$PINDIR" "$NONCEDIR" 2>/dev/null
chmod 700 "$SECDIR" "$PINDIR" "$NONCEDIR" 2>/dev/null

fi
log() { [ "$CHECK_ONLY" = 1 ] && return 0; echo "$(date '+%F %T') $1" >> "$LOG"; }
[ "$CHECK_ONLY" = 1 ] || log "exec-daemon v4 start (pid $$)"
if [ "$CHECK_ONLY" != 1 ]; then

# ---------- 单实例保护（ROOT-6：pidfile 必须是 root 的） ----------
if [ -e "$PIDFILE" ]; then
  po=$(stat -c %u "$PIDFILE" 2>/dev/null)
  if [ -L "$PIDFILE" ] || { [ -n "$po" ] && [ "$po" != "0" ]; }; then
    log "pidfile tampered (owner=$po, symlink=$([ -L "$PIDFILE" ] && echo yes || echo no)) -> ignored & recreated"
    rm -f "$PIDFILE" 2>/dev/null
  else
    old=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$old" ] && [ -d "/proc/$old" ] && grep -q "exec_daemon" "/proc/$old/cmdline" 2>/dev/null; then
      log "already running pid=$old, exit"
      exit 0
    fi
  fi
fi
echo $$ > "$PIDFILE"; chmod 600 "$PIDFILE" 2>/dev/null

# ---------- 密钥（ROOT-1d） ----------
ensure_key() { # ensure_key <pkg>
  kf=$KEYFILE.$1
  [ -s "$kf" ] && return 0
  k=''
  if [ -r /dev/urandom ]; then
    k=$(od -An -tx1 -N32 /dev/urandom 2>/dev/null | tr -d ' \n')
  fi
  [ -n "$k" ] || k=$(date '+%s%N')$$
  printf '%s' "$k" > "$kf" 2>/dev/null
  chmod 600 "$kf" 2>/dev/null
  log "exec key generated ($kf, 600 root)"
}
ensure_key "$PKG_REL"
ensure_key "$PKG_DBG"
fi


# 简化 HMAC：mac = SHA256( key || SHA256( key || payload ) )。嵌套结构抗长度扩展；
# Android 上没有 openssl，sha256sum 是唯一可用的哈希。App 侧必须用同一构造（见报告）。
mac_of() { # mac_of <payload> <pkg>
  _k=$(cat "$KEYFILE.$2" 2>/dev/null)
  [ -n "$_k" ] || return 1
  _i=$(sha256_str "$_k$1") || return 1
  _o=$(sha256_str "$_k$_i") || return 1
  printf '%s' "$_o"
}

nonce_used() { [ -e "$NONCEDIR/$1" ]; }
nonce_mark() {
  printf '%s' "$2" > "$NONCEDIR/$1" 2>/dev/null
  # 清理过期 nonce（文件内容是 ts）
  now=$(date '+%s')
  for f in "$NONCEDIR"/*; do
    [ -e "$f" ] || continue
    t=$(cat "$f" 2>/dev/null); [ -n "$t" ] || t=0
    [ $((now - t)) -gt "$NONCE_KEEP" ] && rm -f "$f" 2>/dev/null
  done
}

# ---------- 包身份校验（ROOT-1a / ROOT-1b） ----------
# 签名来源：/data/system/packages.xml（Android 9 为文本 XML；root 可读）。
#   <package name="P" ... userId="10234" ...> <sigs count="1" ...> <cert index="N" key="<DER 证书 hex>"/>
#   同一证书第二次出现时只写 <cert index="N" />，key 在文件里第一次出现处。
#   证书 SHA-256 = sha256(DER)，与 apksigner / 的「证书 SHA-256」同一口径（已用 out-final 两个 APK 核对）。
# ⚠ Android 12+ packages.xml 是二进制 ABX，本解析会失败 → 退到 uid pin（见下）；Note8/Android 9 不受影响。
if [ -n "${BIXBY_EXEC_DIR:-}" ]; then
  PKGXML=${BIXBY_EXEC_PKGXML:-$BIXBY_EXEC_DIR/packages.xml}
else
  PKGXML=${BIXBY_EXEC_PKGXML:-/data/system/packages.xml}
fi

# sha256 <文件>：PATH 里的 sha256sum → Magisk busybox → toybox
sha256_file() {
  for h in sha256sum "/data/adb/magisk/busybox sha256sum" "toybox sha256sum"; do
    r=$($h "$1" 2>/dev/null) && [ -n "$r" ] && { printf '%s' "${r%% *}"; return 0; }
  done
  return 1
}
sha256_str() {
  for h in sha256sum "/data/adb/magisk/busybox sha256sum" "toybox sha256sum"; do
    r=$(printf '%s' "$1" | $h 2>/dev/null) && [ -n "$r" ] && { printf '%s' "${r%% *}"; return 0; }
  done
  return 1
}

hexd() { case $1 in [0-9]) HX=$1 ;; a|A) HX=10 ;; b|B) HX=11 ;; c|C) HX=12 ;; d|D) HX=13 ;; e|E) HX=14 ;; f|F) HX=15 ;; esac; }   # 不 fork
# hex2bin_sh <hex> <输出文件>：不 fork 的纯 sh 实现（兜底；真机 mksh 上 1240 字节约 71 s，太慢，只在原生工具都不可用时才用）
hex2bin_sh() {
  _h=$1; _fmt=''; : > "$2"
  while [ -n "$_h" ]; do
    _b=${_h%"${_h#??}"}; _h=${_h#??}
    case $_b in [0-9a-fA-F][0-9a-fA-F]) ;; *) return 1 ;; esac
    # 不用 $((0x..))：Android 的 /system/bin/sh 是 mksh，十六进制字面量支持随版本而异
    hexd "${_b%?}"; _v=$((HX * 16)); hexd "${_b#?}"; _v=$((_v + HX))
    _fmt="$_fmt\\$((_v / 64))$((_v / 8 % 8))$((_v % 8))"
    if [ ${#_fmt} -gt 2000 ]; then printf "$_fmt" >> "$2"; _fmt=''; fi
  done
  [ -n "$_fmt" ] && printf "$_fmt" >> "$2"
  return 0
}

# v4.2（daemon-mac，2026-10-29）：hex2bin 改走原生 `xxd -r -p`（toybox / Magisk busybox / PATH），纯 sh 只作兜底。
#   启动时对 00..ff 全 256 字节的已知向量做一次自检（sha256 必须等于下面的常量），通过的工具才会被采用；
#   工具输出错 → 证书摘要算错 → 只会 SIG_MISMATCH（失败即拒），不会放行，MAC/签名校验语义不变。
H2B_VEC=000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff
H2B_VEC_SHA=40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880
H2B_TOOL=''
h2b_pick() {
  H2B_TOOL=''
  _tools='toybox:xxd /data/adb/magisk/busybox:xxd xxd'
  # 测试钩子（只在离线 PoC 下）：BIXBY_EXEC_H2B_TOOLS 覆盖候选，'none' = 强制纯 sh
  [ -n "${BIXBY_EXEC_DIR:-}" ] && [ -n "${BIXBY_EXEC_H2B_TOOLS:-}" ] && _tools=$BIXBY_EXEC_H2B_TOOLS
  _t=$SECDIR/.h2b.$$
  for _x in $_tools; do
    [ "$_x" = none ] && continue
    _x=$(printf '%s' "$_x" | tr ':' ' ')
    rm -f "$_t"
    printf '%s' "$H2B_VEC" | $_x -r -p > "$_t" 2>/dev/null || continue
    [ "$(sha256_file "$_t" 2>/dev/null)" = "$H2B_VEC_SHA" ] && { H2B_TOOL=$_x; break; }
  done
  rm -f "$_t"
  if [ -n "$H2B_TOOL" ]; then log "hex2bin: native '$H2B_TOOL -r -p'（自检通过）"; else log "hex2bin: WARN 无可用原生 xxd -r -p，退回纯 sh（真机首条签名校验会很慢）"; fi
}
# hex2bin <hex> <输出文件>：先校验输入是偶数长度的纯十六进制，再用自检通过的原生工具；失败或长度不符退回纯 sh
hex2bin() {
  case $1 in ''|*[!0-9a-fA-F]*) return 1 ;; esac
  [ $(( ${#1} % 2 )) -eq 0 ] || return 1
  if [ -n "$H2B_TOOL" ]; then
    rm -f "$2"
    if printf '%s' "$1" | $H2B_TOOL -r -p > "$2" 2>/dev/null && [ "$(stat -c %s "$2" 2>/dev/null)" = "$(( ${#1} / 2 ))" ]; then
      return 0
    fi
    log "hex2bin: WARN '$H2B_TOOL' 输出异常，本次退回纯 sh"
  fi
  hex2bin_sh "$1" "$2"
}

# pkg_info <pkg> → PI_UID、PI_SIG（证书 sha256，拿不到为空）、PI_NCERT
pkg_info() {
  p=$1; PI_UID=''; PI_SIG=''; PI_NCERT=''
  [ -r "$PKGXML" ] && [ ! -L "$PKGXML" ] || return 1
  pre=$(printf '%s' "$p" | sed 's/\./\\./g')
  blk=$(sed -n "/<package name=\"$pre\" /,/<\/package>/p" "$PKGXML" 2>/dev/null)
  [ -n "$blk" ] || return 1
  head1=${blk%%
*}
  case $head1 in *' userId="'*) PI_UID=${head1#*' userId="'}; PI_UID=${PI_UID%%\"*} ;; esac
  case $head1 in *' sharedUserId="'*) PI_UID=${head1#*' sharedUserId="'}; PI_UID=${PI_UID%%\"*} ;; esac
  case $blk in *'<sigs count="'*) PI_NCERT=${blk#*'<sigs count="'}; PI_NCERT=${PI_NCERT%%\"*} ;; esac
  case $blk in *'<cert index="'*) ;; *) return 1 ;; esac
  idx=${blk#*'<cert index="'}; idx=${idx%%\"*}
  is_num "$idx" || return 1
  line=$(grep -m1 "<cert index=\"$idx\" key=\"" "$PKGXML" 2>/dev/null)
  key=${line#*' key="'}; key=${key%%\"*}
  [ -n "$key" ] && [ "$key" != "$line" ] || return 1
  t=$SECDIR/.cert.$$
  hex2bin "$key" "$t" || { rm -f "$t"; return 1; }
  PI_SIG=$(sha256_file "$t"); rm -f "$t"
  return 0
}

# ident_ok <pkg> <filesdir> <tag> <slot>
# 成功 0；失败 1 并设置 DENY。结果按 slot 缓存 IDENT_TTL 秒（键 = 目录属主 uid，属主一变立刻重查）。
IDENT_T_rel=0; IDENT_U_rel=''; IDENT_T_dbg=0; IDENT_U_dbg=''
ident_ok() {
  p=$1; d=$2; tag=$3; slot=$4
  duid=$(stat -c %u "$d" 2>/dev/null)
  if [ -z "$duid" ]; then DENY="NO_FILES_DIR"; return 1; fi
  if [ "$duid" = "0" ]; then DENY="FILESDIR_OWNED_BY_ROOT"; return 1; fi
  # 包数据目录（/data/user/0/<pkg>）属主必须与 files 目录一致（测试钩子下 files 目录即包目录）
  pd=${d%/files}
  if [ "$pd" != "$d" ] && [ -d "$pd" ]; then
    puid=$(stat -c %u "$pd" 2>/dev/null)
    [ "$puid" = "$duid" ] || { DENY="PKGDIR_UID_MISMATCH(pkg=$puid files=$duid)"; return 1; }
  fi

  now=$(date '+%s')
  # 缓存键 = 目录属主 uid + packages.xml 的 mtime/大小：装/卸/更新任何包都会改写 packages.xml，立刻失效重查
  ckey="$duid:$(stat -c %Y:%s "$PKGXML" 2>/dev/null)"
  eval "ct=\$IDENT_T_$slot; cu=\$IDENT_U_$slot"
  if [ "$cu" = "$ckey" ] && [ $((now - ct)) -lt "$IDENT_TTL" ]; then return 0; fi

  exp=$EXPECT_SIG_REL; [ "$slot" = "dbg" ] && exp=$EXPECT_SIG_DBG
  pf=$PINDIR/$p.uid
  if pkg_info "$p"; then
    if [ -n "$PI_UID" ] && [ "$PI_UID" != "$duid" ]; then
      log "exec$tag ALERT UID_MISMATCH pkg=$p packages.xml=$PI_UID dir=$duid"
      DENY="UID_MISMATCH"; return 1
    fi
    if [ -n "$PI_NCERT" ] && [ "$PI_NCERT" != "1" ]; then
      log "exec$tag ALERT MULTI_CERT pkg=$p count=$PI_NCERT"
      DENY="MULTI_CERT"; return 1
    fi
    if [ -n "$PI_SIG" ]; then
      slo=$(printf '%s' "$PI_SIG" | tr 'A-F' 'a-f'); elo=$(printf '%s' "$exp" | tr 'A-F' 'a-f')
      case $REVOKED_SIGS in *" $slo "*)
        log "exec$tag ALERT REVOKED_CERT pkg=$p got=$slo（已泄露的证书，一律拒）"
        DENY="REVOKED_CERT"; return 1 ;;
      esac
      if [ -z "$elo" ]; then
        log "exec$tag ALERT SIG_NOT_CONFIGURED pkg=$p（未设置 BIXBY_EXEC_SIG_REL / BIXBY_EXEC_SIG_DBG）"
        DENY="SIG_NOT_CONFIGURED"; return 1
      fi
      if [ "$slo" != "$elo" ]; then
        log "exec$tag ALERT SIG_MISMATCH pkg=$p got=$slo want=$elo uid=$duid"
        DENY="SIG_MISMATCH"; return 1
      fi
      # 签名对：记下 uid（合法重装 uid 会变，签名对就更新 pin）
      printf '%s' "$duid" > "$pf" 2>/dev/null; chmod 600 "$pf" 2>/dev/null
      eval "IDENT_T_$slot=$now; IDENT_U_$slot=\$ckey"
      return 0
    fi
  fi

  # 拿不到签名（packages.xml 读不了 / ABX / 没有哈希工具）→ uid pin（首次信任）
  if [ -f "$FLAG_STRICT_PIN" ]; then
    log "exec$tag DENY NO_IDENTITY pkg=$p (strict_pin 开，签名摘要不可得)"
    DENY="NO_IDENTITY"; return 1
  fi
  if [ -s "$pf" ]; then
    pin=$(cat "$pf" 2>/dev/null)
    if [ "$pin" != "$duid" ]; then
      log "exec$tag ALERT PIN_MISMATCH pkg=$p uid=$duid pinned=$pin（签名不可验证；确认是合法重装后 rm $pf）"
      DENY="PIN_MISMATCH"; return 1
    fi
  else
    printf '%s' "$duid" > "$pf" 2>/dev/null; chmod 600 "$pf" 2>/dev/null
    log "exec$tag WARN TOFU_PIN pkg=$p uid=$duid — 签名摘要不可得，首次见到即固定 uid"
  fi
  eval "IDENT_T_$slot=$now; IDENT_U_$slot=\$ckey"
  return 0
}

# ---------- 文件安全检查（ROOT-4） ----------
# safe_file <path> <期望属主uid>；符号链接 / 属主不符 → 1
safe_file() {
  [ -L "$1" ] && { DENY="SYMLINK"; return 1; }
  o=$(stat -c %u "$1" 2>/dev/null)
  [ -n "$o" ] || { DENY="STAT_FAIL"; return 1; }
  [ "$o" = "$2" ] || { DENY="BAD_OWNER(req=$o dir=$2)"; return 1; }
  return 0
}

# ---------- 引号安全的 argv 组装 ----------
shq() { # 把 $1 包成单引号字面量
  _s=$1; _o=''
  while :; do
    case $_s in
      *\'*) _o=$_o${_s%%\'*}"'\\''"; _s=${_s#*\'} ;;
      *) break ;;
    esac
  done
  printf "'%s'" "$_o$_s"
}

# strip_devnull：去掉「只指向 /dev/null 或 2>&1」的无害重定向（App 里大量命令带 2>/dev/null）。
#   仅匹配以空白分隔的完整单词；其它任何重定向仍由 tokenize 拒绝。stderr 本来就会被捕获，去掉不改变安全性。
strip_devnull() {
  _in=" $1 "; _out=''
  for _r in ' 2>/dev/null ' ' 1>/dev/null ' ' >/dev/null ' ' </dev/null ' ' 2>&1 ' ' 2> /dev/null ' ' > /dev/null '; do
    while :; do
      case $_in in *"$_r"*) _in="${_in%%"$_r"*} ${_in#*"$_r"}" ;; *) break ;; esac
    done
  done
  printf '%s' "$_in"
}

# tokenize <命令串> → QARGS（可 eval set -- 的引号串）。遇到 shell 元字符返回 1。
tokenize() {
  _s=$(strip_devnull "$1"); QARGS=''; _cur=''; _have=0; _st=0
  while [ -n "$_s" ]; do
    _r=${_s#?}; _c=${_s%"$_r"}; _s=$_r
    if [ "$_st" = 1 ]; then
      if [ "$_c" = "'" ]; then _st=0; else _cur=$_cur$_c; fi
      continue
    fi
    if [ "$_st" = 2 ]; then
      case $_c in
        '"') _st=0 ;;
        '$'|'`'|'\') DENY="METACHAR($_c in dquote)"; return 1 ;;
        *) _cur=$_cur$_c ;;
      esac
      continue
    fi
    case $_c in
      ' '|'	')
        [ "$_have" = 1 ] && { QARGS="$QARGS $(shq "$_cur")"; _cur=''; _have=0; } ;;
      "'") _st=1; _have=1 ;;
      '"') _st=2; _have=1 ;;
      '|')
        [ "$_have" = 1 ] && { QARGS="$QARGS $(shq "$_cur")"; _cur=''; _have=0; }
        QARGS="$QARGS '|'" ;;
      ';'|'&'|'>'|'<'|'`'|'$'|'('|')'|'\'|'
')
        DENY="METACHAR($_c)"; return 1 ;;
      *) _cur=$_cur$_c; _have=1 ;;
    esac
  done
  [ "$_st" = 0 ] || { DENY="UNBALANCED_QUOTE"; return 1; }
  [ "$_have" = 1 ] && QARGS="$QARGS $(shq "$_cur")"
  [ -n "$QARGS" ] || { DENY="EMPTY"; return 1; }
  return 0
}

# ---------- 路径白名单 ----------
# 每个包能读写的前缀，serve 时按包算好
PFX_W=''; PFX_R=''
set_prefixes() {
  p=$1; CUR_PKG=$1
  set_media "$1"
  PFX_W="/data/user/0/$p/ /data/data/$p/ /data/local/tmp/$p/ /storage/emulated/0/Android/data/$p/ /sdcard/Android/data/$p/"
  # 只读：OneSay 时钟的导出文件（BriefDetail 走 cat 读）、三星天气时钟库（HomeCityPolicy）
  PFX_R="$PFX_W /data/data/com.onesay.clock/files/ /data/data/com.sec.android.daemonapp/databases/WeatherClock /sys/class/leds/torch-sec1/brightness /data/adb/modules/bixbykey_hijack/ /storage/emulated/0/DCIM/ /sdcard/DCIM/ /storage/emulated/0/Pictures/ /sdcard/Pictures/ /storage/emulated/0/Download/ /sdcard/Download/"
  if [ -n "${BIXBY_EXEC_DIR:-}" ]; then
    PFX_W="$PFX_W $BIXBY_EXEC_DIR/"
    PFX_R="$PFX_R $BIXBY_EXEC_DIR/"
  fi
}
path_ok() { # $1 路径 $2 r|w
  case $1 in
    /*) ;;
    *) DENY="PATH_NOT_ABS($1)"; return 1 ;;
  esac
  case $1 in
    *..*) DENY="PATH_DOTDOT"; return 1 ;;
  esac
  [ -L "$1" ] && { DENY="PATH_SYMLINK($1)"; return 1; }
  list=$PFX_R; [ "$2" = w ] && list=$PFX_W
  for pre in $list; do
    case $1 in "$pre"*) return 0 ;; esac
    [ "$1" = "$pre" ] && return 0
  done
  DENY="PATH_NOT_ALLOWED($1)"
  return 1
}

# ---------- v4.1 G3：受控媒体写（Pictures/<本包子目录>） ----------
# 只允许两种目标：
#   ① $MEDIA_SUB 子树（存图：Pictures/OneSay[-Debug]/<前台包名>/<文件>，最多三级）
#   ② 固定截图文件 $MEDIA_SHOT（ui.screenshot：Pictures/bixbyui[_debug]_shot.png）
# 判定只看路径形状；真正的写由守护自己做（media_run），逐级拒符号链接 + 设备号/属主一致性 + 先写临时名再 rename。
set_media() { # set_media <pkg>
  case $1 in
    "$PKG_DBG") MEDIA_SUB=OneSay-Debug; MEDIA_SHOT=bixbyui_debug_shot.png ;;
    *)          MEDIA_SUB=OneSay;       MEDIA_SHOT=bixbyui_shot.png ;;
  esac
}
# media_check <路径> <dir|file>
#   0 = 合法媒体目标（设 M_ROOT / M_REST）；1 = 落在媒体根下但不合规（设 DENY）；2 = 根本不是媒体路径（交回 path_ok）
media_check() {
  _p=$1; _k=$2; M_ROOT=''; M_REST=''
  for _r in $MEDIA_ROOTS; do
    case $_p in "$_r"|"$_r"/*) M_ROOT=$_r; M_REST=${_p#"$_r"}; M_REST=${M_REST#/}; break ;; esac
  done
  [ -n "$M_ROOT" ] || return 2
  case $_p in *..*|*//*|*/) DENY="MEDIA_PATH($_p)"; return 1 ;; esac
  [ -n "$M_REST" ] || { DENY="MEDIA_ROOT_ITSELF"; return 1; }
  if [ "$_k" = file ] && [ "$M_REST" = "$MEDIA_SHOT" ]; then return 0; fi
  case $M_REST in "$MEDIA_SUB"|"$MEDIA_SUB"/*) ;; *) DENY="MEDIA_SUBDIR($M_REST)"; return 1 ;; esac
  _n=0; _rest=$M_REST
  while [ -n "$_rest" ]; do
    _c=${_rest%%/*}; if [ "$_c" = "$_rest" ]; then _rest=''; else _rest=${_rest#*/}; fi
    case $_c in ''|.*|*[!A-Za-z0-9._-]*) DENY="MEDIA_COMPONENT($_c)"; return 1 ;; esac
    _n=$((_n + 1))
  done
  [ $_n -le 3 ] || { DENY="MEDIA_DEPTH($_n)"; return 1; }
  if [ "$_k" = file ]; then
    [ $_n -ge 2 ] || { DENY="MEDIA_FILE_SHALLOW($M_REST)"; return 1; }
    case ${_p##*/} in *.png|*.jpg|*.jpeg|*.gif|*.webp) ;; *) DENY="MEDIA_EXT(${_p##*/})"; return 1 ;; esac
  fi
  return 0
}
# media_mkdir <dir>（已过 media_check dir）：逐级建，拒符号链接，设备号必须与媒体根一致
media_mkdir() {
  media_check "$1" dir || { echo "refuse: ${DENY:-not a media path}" >&2; return 1; }
  [ -d "$M_ROOT" ] && [ ! -L "$M_ROOT" ] || { echo "media root missing: $M_ROOT" >&2; return 1; }
  _dev=$(stat -c %d "$M_ROOT" 2>/dev/null)
  _own=$(stat -c %u "$M_ROOT" 2>/dev/null)
  _d=$M_ROOT; _rest=$M_REST
  while [ -n "$_rest" ]; do
    _c=${_rest%%/*}; if [ "$_c" = "$_rest" ]; then _rest=''; else _rest=${_rest#*/}; fi
    _d=$_d/$_c
    [ -L "$_d" ] && { echo "refuse: symlink in media path ($_d)" >&2; return 1; }
    if [ ! -e "$_d" ]; then
      mkdir "$_d" 2>/dev/null || { echo "mkdir failed: $_d" >&2; return 1; }
      media_label "$_d" "${_d%/*}" 775
    elif [ ! -d "$_d" ]; then
      echo "not a directory: $_d" >&2; return 1
    elif [ "$(stat -c %u "$_d" 2>/dev/null)" != "$_own" ]; then
      # 已存在的中间目录属主必须与 Pictures 根一致（sdcardfs 上天然一致；不一致说明被别的 uid 预置/挂载）
      echo "refuse: media dir owner mismatch ($_d)" >&2; return 1
    fi
    [ "$(stat -c %d "$_d" 2>/dev/null)" = "$_dev" ] || { echo "refuse: media path crosses filesystem ($_d)" >&2; return 1; }
  done
  MEDIA_DIR=$_d
  return 0
}
# media_put <目标文件> <writer…>：writer 写到不可预测的临时名，再 rename 到目标（不跟随目标处的符号链接）
media_put() {
  _dst=$1; shift
  media_check "$_dst" file || { echo "refuse: ${DENY:-not a media path}" >&2; return 1; }
  _par=${_dst%/*}
  if [ "$_par" = "$M_ROOT" ]; then      # 固定截图文件直接在 Pictures 根下
    [ -d "$M_ROOT" ] && [ ! -L "$M_ROOT" ] || { echo "media root missing: $M_ROOT" >&2; return 1; }
    _own=$(stat -c %u "$M_ROOT" 2>/dev/null); MEDIA_DIR=$M_ROOT
  else
    media_mkdir "$_par" || return 1
  fi
  _t=$MEDIA_DIR/.bixbytmp.$$.$(date '+%s')
  rm -f "$_t" 2>/dev/null
  "$@" "$_t" || { rm -f "$_t" 2>/dev/null; return 1; }
  [ -s "$_t" ] && [ -f "$_t" ] && [ ! -L "$_t" ] || { rm -f "$_t" 2>/dev/null; echo "empty output" >&2; return 1; }
  media_label "$_t" "$MEDIA_DIR" 664
  [ -L "$_dst" ] && rm -f "$_dst" 2>/dev/null
  if [ -e "$_dst" ] && [ "$(stat -c %u "$_dst" 2>/dev/null)" != "$_own" ]; then
    rm -f "$_t"; echo "refuse: existing target owner mismatch" >&2; return 1
  fi
  [ -d "$_dst" ] && { rm -f "$_t"; echo "refuse: target is a directory" >&2; return 1; }
  mv -f "$_t" "$_dst" 2>/dev/null || { rm -f "$_t" 2>/dev/null; return 1; }
  return 0
}
# media_label <新建项> <父目录> <mode>：属主/组与 SELinux 标签取父目录的（与 fix_owner 同口径），
#   这样 root 建出来的文件不会是 root:root/错标签（媒体扫描、App 自己都能读）。sdcardfs 上这些本就是派生的，无害。
media_label() {
  _o=$(stat -c %u:%g "$2" 2>/dev/null); [ -n "$_o" ] && chown "$_o" "$1" 2>/dev/null
  _x=$(ls -Zd "$2" 2>/dev/null); _x=${_x%% *}
  case $_x in *:*:*) chcon "$_x" "$1" 2>/dev/null ;; esac
  chmod "$3" "$1" 2>/dev/null
}
_cp_to() {                                 # media_put 的 writer：cp <src> <tmp>（src 再核一次：普通文件、非链接）
  [ -f "$1" ] && [ ! -L "$1" ] || { echo "refuse: source not a regular file" >&2; return 1; }
  timeout -k 2 $CMD_TIMEOUT cp -f "$1" "$2"
}
_shot_to() { timeout -k 2 $CMD_TIMEOUT screencap -p "$1"; }   # media_put 的 writer：screencap -p <tmp>
media_run() {
  case $MEDIA_ACT in
    mkdir) media_mkdir "$MEDIA_DST" ;;
    cp) media_put "$MEDIA_DST" _cp_to "$MEDIA_SRC" ;;
    screencap) media_put "$MEDIA_DST" _shot_to ;;
    *) echo "internal: bad MEDIA_ACT" >&2; return 1 ;;
  esac
}

# ---------- v4.1 G7：按键守护状态（只读固定路径，只输出状态字符串） ----------
# App 侧逐字常量（ConsoleStatus.DAEMON_PROBE）；守护不执行它，只按这份内建实现回答。
STATUS_PROBE_CMD="a=DEAD; p=\$(cat /data/local/tmp/.bixby_hijack.pid 2>/dev/null); if [ -n \"\$p\" ] && grep -qa hijack_daemon /proc/\$p/cmdline 2>/dev/null; then a=ALIVE; else for d in /proc/[0-9]*; do grep -qa hijack_daemon \$d/cmdline 2>/dev/null && { a=ALIVE; break; }; done; fi; echo \$a; M=/data/adb/modules/bixbykey_hijack; if [ -d \$M ]; then echo MOD; else echo NOMOD; fi; head -n 6 \$M/hijack_daemon.sh 2>/dev/null | grep -m1 'daemon - v' ; echo END"
status_report() {
  _a=DEAD; _p=''
  [ -f "$HJ_PIDFILE" ] && [ ! -L "$HJ_PIDFILE" ] && { IFS= read -r _p < "$HJ_PIDFILE"; } 2>/dev/null   # 内建 read（toybox head 没有 -c）
  case $_p in ''|*[!0-9]*) _p='' ;; esac
  if [ -n "$_p" ] && grep -qa hijack_daemon "/proc/$_p/cmdline" 2>/dev/null; then
    _a=ALIVE
  else
    for _d in /proc/[0-9]*; do
      grep -qa hijack_daemon "$_d/cmdline" 2>/dev/null && { _a=ALIVE; break; }
    done
  fi
  echo "$_a"
  if [ -d "$HJ_MODDIR" ]; then echo MOD; else echo NOMOD; fi
  # 版本行只回显规范化后的版本号，绝不回显模块脚本原文
  _v=$(head -n 6 "$HJ_MODDIR/hijack_daemon.sh" 2>/dev/null | grep -m1 'daemon - v')
  case $_v in
    *'daemon - v'*)
      _v=${_v#*'daemon - v'}; _v=${_v%%[!0-9.]*}
      case $_v in ''|*[!0-9.]*) _v='' ;; esac
      [ -n "$_v" ] && echo "hijack daemon - v$_v" ;;
  esac
  echo END
}

is_num() { case $1 in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }
is_tok() { case $1 in ''|*[!A-Za-z0-9_.-]*) return 1 ;; *) return 0 ;; esac; }   # 标识符类
is_nonce() { case $1 in *[!A-Za-z0-9]*) return 1 ;; esac; [ ${#1} -ge 8 ] && [ ${#1} -le 64 ]; }
is_pkg() { case $1 in ''|*[!A-Za-z0-9_.]*) return 1 ;; *) return 0 ;; esac; }

# ---------- settings 可写键 ----------
# 绝对禁止（拿到就等于接管设备/关防护）：无障碍服务、输入法、ADB、安装来源、包校验、锁屏、设备管理……
# allow_any_setting 开关也放不开这些。
SETTINGS_DENY=" enabled_accessibility_services accessibility_enabled touch_exploration_enabled default_input_method enabled_input_methods selected_input_method_subtype adb_enabled adb_wifi_enabled development_settings_enabled install_non_market_apps package_verifier_enable verifier_verify_adb_installs package_verifier_user_consent lockscreen.disabled lock_screen_owner_info lockscreen.password_type device_provisioned user_setup_complete enabled_notification_listeners enabled_notification_policy_access_packages voice_interaction_service assistant sms_default_application dialer_default_application http_proxy global_http_proxy_host global_http_proxy_port always_on_vpn_app always_on_vpn_lockdown "
# 本通道真实写过的键（DiagKnowledge / caps/* / DiagEngine 回滚，2026-10-25 从仓库收集）。
# 新加能力要写新键：要么加进这里（随守护版本下发），要么 root 建 $SECDIR/allow_any_setting。
SETTINGS_ALLOW=" global:airplane_mode_on global:auto_time global:auto_time_zone global:bluetooth_on global:data_roaming global:low_power global:mode_ringer global:wifi_on global:wifi_scan_always_enabled global:wifi_sleep_policy global:zen_mode secure:location_mode secure:location_providers_allowed secure:night_display_activated secure:wake_gesture_enabled system:accelerometer_rotation system:all_sound_off system:aod_mode system:blue_light_filter system:edge_lighting system:enable_smart_capture system:font_scale system:haptic_feedback_enabled system:intelligent_sleep_mode system:motion_pick_up system:screen_brightness system:screen_brightness_mode system:screen_off_pocket system:screen_off_timeout system:user_rotation system:vibrate_when_ringing system:volume_music_speaker system:volume_notification_speaker system:volume_ring_speaker "
FLAG_ANY_SETTING=$SECDIR/allow_any_setting
setting_writable() {
  case $SETTINGS_DENY in *" $2 "*) DENY="SETTINGS_KEY_FORBIDDEN($2)"; return 1 ;; esac
  case $SETTINGS_ALLOW in *" $1:$2 "*) return 0 ;; esac
  [ -f "$FLAG_ANY_SETTING" ] && return 0
  DENY="SETTINGS_KEY_NOT_ALLOWED($1:$2)"; return 1
}

# ---------- 命令白名单 ----------
# 首段（真正干活的程序）。返回 0 放行。
check_main() {
  eval "set -- $1"
  prog=$1; shift
  case $prog in
    settings)
      case ${1:-} in get|put|delete) ;; *) DENY="SETTINGS_VERB(${1:-})"; return 1 ;; esac
      case ${2:-} in system|secure|global) ;; *) DENY="SETTINGS_NS(${2:-})"; return 1 ;; esac
      is_tok "${3:-}" || { DENY="SETTINGS_KEY"; return 1; }
      if [ "$1" != get ]; then setting_writable "$2" "$3" || return 1; fi
      if [ "$1" = put ]; then
        [ $# -eq 4 ] || { DENY="SETTINGS_PUT_ARGC($#)"; return 1; }
        case $4 in *[!A-Za-z0-9_.:,/+-]*) DENY="SETTINGS_VALUE"; return 1 ;; esac
      else
        [ $# -eq 3 ] || { DENY="SETTINGS_ARGC($#)"; return 1; }
      fi
      return 0 ;;
    am)
      case ${1:-} in start|broadcast|start-activity) ;; *) DENY="AM_VERB(${1:-})"; return 1 ;; esac
      verb=$1
      if [ "$verb" = broadcast ]; then
        case " $* " in *" -n "*) ;; *) DENY="AM_BCAST_IMPLICIT"; return 1 ;; esac
      fi
      shift
      while [ $# -gt 0 ]; do
        case $1 in
          -n) is_comp "${2:-}" || { DENY="AM_COMPONENT(${2:-})"; return 1; }
              # root 发广播能打到别包未导出的 receiver：只允许本通道真实用过的接收方（自己 + 晨报伴生包）
              if [ "$verb" = broadcast ]; then
                case ${2%%/*} in "$CUR_PKG"|com.onesay.clock) ;; *) DENY="AM_BCAST_TARGET(${2%%/*})"; return 1 ;; esac
              fi
              shift 2 ;;
          -a) is_tok "${2:-}" || { DENY="AM_ACTION(${2:-})"; return 1; }; shift 2 ;;
          -d) uri_ok "${2:-}" || return 1; shift 2 ;;
          -t) shift 2 ;;
          # device-1102：隐式 Intent 限定接收包（导航「用默认地图 App」geo: -p <包名>）。只收合法包名，最小放宽
          -p) [ "$verb" = broadcast ] && { DENY="AM_BCAST_PKG"; return 1; }
              is_pkg "${2:-}" || { DENY="AM_PKG(${2:-})"; return 1; }; shift 2 ;;
          --es|--ei|--el|--ez|--ef|-e) [ $# -ge 3 ] || { DENY="AM_EXTRA_ARGC"; return 1; }; shift 3 ;;
          --user) is_num "${2:-}" || { DENY="AM_USER"; return 1; }; shift 2 ;;
          -f) is_tok "${2:-}" || { DENY="AM_FLAGS"; return 1; }; shift 2 ;;
          --receiver-foreground|--activity-clear-top|--activity-single-top|--activity-new-task|-W|-S) shift ;;
          -*) DENY="AM_FLAG($1)"; return 1 ;;
          *) DENY="AM_ARG($1)"; return 1 ;;
        esac
      done
      # 无 -n 的隐式广播（系统 action 冒充）不放行；AIRPLANE_MODE 走固定模板
      return 0 ;;
    pm)
      case ${1:-} in
        path|list|dump) is_pkg "${2:-}" || [ "$1" = list ] || { DENY="PM_PKG"; return 1; }; return 0 ;;
        clear|enable|disable|disable-user) is_pkg "${2:-}" || { DENY="PM_PKG"; return 1; }; [ $# -le 3 ] || { DENY="PM_ARGC"; return 1; }; return 0 ;;
        *) DENY="PM_VERB(${1:-})"; return 1 ;;
      esac ;;
    cmd)
      case ${1:-} in
        package)
          case ${2:-} in list|dump|resolve-activity|query-activities|path) ;; *) DENY="CMDPKG_VERB(${2:-})"; return 1 ;; esac ;;
        wifi) case ${2:-} in status|set-wifi-enabled|set) ;; *) DENY="CMDWIFI_VERB(${2:-})"; return 1 ;; esac ;;
        netpolicy) case ${2:-} in get|list) ;; *) DENY="CMDNETPOLICY_VERB(${2:-})"; return 1 ;; esac ;;
        connectivity) ;;
        appops)   # PowerWhitelist / ReliabilityCheck：只许 get/set 已知后台 op，模式限 allow|ignore|deny|default
          case ${2:-} in get|set) ;; *) DENY="APPOPS_VERB(${2:-})"; return 1 ;; esac
          is_pkg "${3:-}" || { DENY="APPOPS_PKG"; return 1; }
          case ${4:-} in RUN_IN_BACKGROUND|RUN_ANY_IN_BACKGROUND|WAKE_LOCK|START_FOREGROUND|SYSTEM_ALERT_WINDOW) ;; *) DENY="APPOPS_OP(${4:-})"; return 1 ;; esac
          if [ "$2" = set ]; then
            case ${5:-} in allow|ignore|deny|default) ;; *) DENY="APPOPS_MODE(${5:-})"; return 1 ;; esac
            [ $# -eq 5 ] || { DENY="APPOPS_ARGC"; return 1; }
          else
            [ $# -eq 4 ] || { DENY="APPOPS_ARGC"; return 1; }
          fi ;;
        *) DENY="CMD_SVC(${1:-})"; return 1 ;;
      esac
      return 0 ;;
    dumpsys)
      case ${1:-} in
        nfc|account)   # v4.1 G4：NFC 状态回读 / 商店状态的 Google 账号数——只许不带参数（后面可接受控过滤器）
          [ $# -eq 1 ] || { DENY="DUMPSYS_ARGC(${1})"; return 1; }
          return 0 ;;
      esac
      case ${1:-} in
        battery|deviceidle|wifi|activity|window|audio|media.camera|media.audio_policy|package|display|diskstats|meminfo|cpuinfo|power|alarm|notification|connectivity|statusbar|input_method|sensorservice|usagestats|telephony.registry) ;;
        *) DENY="DUMPSYS_SVC(${1:-})"; return 1 ;;
      esac
      return 0 ;;
    svc)
      case ${1:-} in wifi|data|bluetooth|nfc|usb|power) ;; *) DENY="SVC_SUB(${1:-})"; return 1 ;; esac
      case ${2:-} in enable|disable) ;; *) DENY="SVC_VERB(${2:-})"; return 1 ;; esac
      [ $# -eq 2 ] || { DENY="SVC_ARGC($#)"; return 1; }
      return 0 ;;
    content)
      [ "${1:-}" = query ] || { DENY="CONTENT_VERB(${1:-})"; return 1; }
      shift
      [ "${1:-}" = --uri ] || { DENY="CONTENT_NO_URI"; return 1; }
      case ${2:-} in content://*) ;; *) DENY="CONTENT_URI(${2:-})"; return 1 ;; esac
      return 0 ;;
    media)
      [ "${1:-}" = volume ] || { DENY="MEDIA_VERB(${1:-})"; return 1; }
      shift
      while [ $# -gt 0 ]; do
        case $1 in
          --stream) is_num "${2:-}" || { DENY="MEDIA_STREAM"; return 1; }; shift 2 ;;
          --set) is_num "${2:-}" || { DENY="MEDIA_LEVEL"; return 1; }; shift 2 ;;
          --get|--show) shift ;;
          *) DENY="MEDIA_ARG($1)"; return 1 ;;
        esac
      done
      return 0 ;;
    input)
      [ "${1:-}" = keyevent ] || { DENY="INPUT_VERB(${1:-})"; return 1; }
      is_num "${2:-}" || case ${2:-} in KEYCODE_[A-Z_]*) ;; *) DENY="INPUT_KEY(${2:-})"; return 1 ;; esac
      [ $# -eq 2 ] || { DENY="INPUT_ARGC($#)"; return 1; }
      return 0 ;;
    service)
      [ "${1:-}" = call ] || { DENY="SERVICE_VERB(${1:-})"; return 1; }
      case ${2:-} in bluetooth_manager) ;; *) DENY="SERVICE_NAME(${2:-})"; return 1 ;; esac
      return 0 ;;
    getprop)
      [ $# -le 1 ] || { DENY="GETPROP_ARGC"; return 1; }
      [ $# -eq 0 ] || is_tok "$1" || { DENY="GETPROP_KEY"; return 1; }
      return 0 ;;
    date)
      [ $# -le 1 ] || { DENY="DATE_ARGC"; return 1; }
      [ $# -eq 0 ] || case $1 in +*) ;; *) DENY="DATE_ARG"; return 1 ;; esac
      return 0 ;;
    id|whoami|uptime) [ $# -eq 0 ] || { DENY="NOARG_CMD"; return 1; }; return 0 ;;
    echo) return 0 ;;
    cat)
      [ $# -ge 1 ] || { DENY="CAT_ARGC"; return 1; }
      for a in "$@"; do path_ok "$a" r || return 1; done
      return 0 ;;
    head|tail)
      # 作为首段读文件（ConsoleStatus: head -n 6 <模块脚本>）
      saw=0
      while [ $# -gt 0 ]; do
        case $1 in
          -n) is_num "${2:-}" || { DENY="HEAD_N"; return 1; }; shift 2 ;;
          -[0-9]*) shift ;;
          -*) DENY="HEAD_FLAG($1)"; return 1 ;;
          *) path_ok "$1" r || return 1; saw=1; shift ;;
        esac
      done
      [ "$saw" = 1 ] || { DENY="HEAD_NO_FILE"; return 1; }
      return 0 ;;
    rm)
      [ $# -ge 1 ] || { DENY="RM_ARGC"; return 1; }
      for a in "$@"; do
        case $a in -f|-rf|-fr) continue ;; -*) DENY="RM_FLAG($a)"; return 1 ;; esac
        path_ok "$a" w || return 1
      done
      return 0 ;;
    mkdir)
      n=0; last=''
      for a in "$@"; do case $a in -p) ;; -*) DENY="MKDIR_FLAG($a)"; return 1 ;; *) n=$((n + 1)); last=$a ;; esac; done
      # v4.1 G3：媒体目录 → 只许单个路径，由守护逐级建（media_mkdir）
      if [ $n -eq 1 ]; then
        media_check "$last" dir; mc=$?
        [ $mc = 0 ] && { MEDIA_ACT=mkdir; MEDIA_DST=$last; return 0; }
        [ $mc = 1 ] && return 1
      fi
      for a in "$@"; do
        case $a in -p) continue ;; esac
        path_ok "$a" w || return 1
      done
      return 0 ;;
    cp)
      n=0; src=''; dst=''
      for a in "$@"; do
        case $a in -f|-r|-rf) continue ;; -*) DENY="CP_FLAG($a)"; return 1 ;; esac
        n=$((n + 1)); [ $n = 1 ] && src=$a; [ $n = 2 ] && dst=$a
      done
      [ $n -eq 2 ] || { DENY="CP_ARGC($n)"; return 1; }
      path_ok "$src" r || return 1
      # v4.1 G3：目标在媒体目录 → 源只许本包可写前缀里的普通文件（App 自己的缓存），由守护 media_put 执行
      media_check "$src" file; [ $? = 2 ] || { DENY="MEDIA_SRC_NOT_OWN($src)"; return 1; }   # 媒体目录不能当源（不当中转站）
      media_check "$dst" file; mc=$?
      if [ $mc = 0 ]; then
        path_ok "$src" w || { DENY="MEDIA_SRC_NOT_OWN($src)"; return 1; }
        MEDIA_ACT=cp; MEDIA_SRC=$src; MEDIA_DST=$dst; return 0
      fi
      [ $mc = 1 ] && return 1
      path_ok "$dst" w || return 1
      return 0 ;;
    chmod)
      [ $# -ge 2 ] || { DENY="CHMOD_ARGC"; return 1; }
      case $1 in 600|640|644|660|666|700|755) ;; *) DENY="CHMOD_MODE($1)"; return 1 ;; esac
      m=$1; shift
      for a in "$@"; do path_ok "$a" w || return 1; done
      return 0 ;;
    stat)
      [ "${1:-}" = -c ] || { DENY="STAT_FLAG"; return 1; }
      [ $# -eq 3 ] || { DENY="STAT_ARGC($#)"; return 1; }
      path_ok "$3" r || return 1
      return 0 ;;
    screencap)
      f=''
      for a in "$@"; do case $a in -p) continue ;; -*) DENY="SCREENCAP_FLAG($a)"; return 1 ;; *) f=$a ;; esac; done
      [ -n "$f" ] || { DENY="SCREENCAP_NO_PATH"; return 1; }
      media_check "$f" file; mc=$?
      if [ $mc = 0 ]; then
        case $f in *.png) ;; *) DENY="SCREENCAP_MEDIA_NOT_PNG"; return 1 ;; esac
        MEDIA_ACT=screencap; MEDIA_DST=$f; return 0
      fi
      [ $mc = 1 ] && return 1
      path_ok "$f" w || return 1
      return 0 ;;
    uiautomator)
      [ "${1:-}" = dump ] || { DENY="UIAUTOMATOR_VERB"; return 1; }
      shift
      # v4.1 G2：识屏指代用 --compressed（固定开关，无值）；最多一个输出路径，且必须在本包可写前缀
      [ "${1:-}" = --compressed ] && shift
      [ $# -le 1 ] || { DENY="UIAUTOMATOR_ARGC"; return 1; }
      [ $# -eq 1 ] && { case $1 in -*) DENY="UIAUTOMATOR_FLAG($1)"; return 1 ;; esac; path_ok "$1" w || return 1; }
      return 0 ;;
    nice)
      # v4.1 G2：唤醒采集的低优先级前缀。只认字面量 `nice -n 10`，且只能包 uiautomator dump / screencap（到本包路径），
      #   被包的命令照常过白名单；不许包媒体写（那条路径由守护自己执行，不经 nice）。
      [ "${1:-}" = -n ] && [ "${2:-}" = 10 ] || { DENY="NICE_ARGS(${1:-} ${2:-})"; return 1; }
      shift 2
      case ${1:-} in uiautomator|screencap) ;; *) DENY="NICE_TARGET(${1:-})"; return 1 ;; esac
      _nq=''; for a in "$@"; do _nq="$_nq $(shq "$a")"; done
      check_main "$_nq" || return 1
      [ -z "$MEDIA_ACT" ] || { DENY="NICE_MEDIA"; return 1; }
      return 0 ;;
    ip)
      [ "${1:-}" = addr ] && [ "${2:-}" = show ] || { DENY="IP_VERB"; return 1; }
      return 0 ;;
    *) DENY="PROG_NOT_WHITELISTED($prog)"; return 1 ;;
  esac
}

is_comp() { case $1 in ''|*[!A-Za-z0-9_./]*) return 1 ;; */*) return 0 ;; *) return 1 ;; esac; }
uri_ok() {
  case $1 in
    http://*|https://*|geo:*|tel:*|smsto:*|sms:*|market://*|samsungapps://*|content://*|package:*|file:///data/user/0/*) return 0 ;;
    *) DENY="URI_SCHEME($1)"; return 1 ;;
  esac
}

# 后续管道段：只允许纯过滤器，且参数不得再含路径
check_filter() {
  eval "set -- $1"
  prog=$1; shift
  case $prog in
    grep|egrep|fgrep)
      # 只当过滤器：最多 1 个位置参数（模式）。第二个位置参数就是文件名 → 拒（不许读文件系统）。
      eo=0; np=0
      for a in "$@"; do
        if [ "$eo" = 0 ]; then
          case $a in
            --) eo=1; continue ;;
            -*[rRfd]*|--*) DENY="GREP_FLAG($a)"; return 1 ;;
            -[A-Za-z0-9]*) continue ;;
          esac
        fi
        np=$((np + 1))
      done
      [ $np -le 1 ] || { DENY="GREP_FILE_ARG"; return 1; }
      return 0 ;;
    head|tail)
      for a in "$@"; do
        case $a in -n|-[0-9]*) continue ;; -*) DENY="FILTER_FLAG($a)"; return 1 ;; esac
        is_num "$a" || { DENY="FILTER_ARG($a)"; return 1; }
      done
      return 0 ;;
    tr)   # tr 没有文件参数
      return 0 ;;
    wc)
      for a in "$@"; do case $a in -[lcwm]) ;; *) DENY="WC_ARG($a)"; return 1 ;; esac; done
      return 0 ;;
    cut)  # 只允许 -d X / -f N / -c N，任何多余位置参数（=文件，含相对路径）拒绝
      while [ $# -gt 0 ]; do
        case $1 in
          -d|-f|-c) [ $# -ge 2 ] || { DENY="CUT_ARGC"; return 1; }; shift 2 ;;
          -d?*|-f?*|-c?*) shift ;;
          *) DENY="CUT_ARG($1)"; return 1 ;;
        esac
      done
      return 0 ;;
    *) DENY="FILTER_NOT_WHITELISTED($prog)"; return 1 ;;
  esac
}

# ---------- 固定模板（含 ; / && / > 的 App 内置常量命令，逐字匹配） ----------
# 这些命令是 App 代码里的字面量（ConnectivityCaps / SystemCaps），无参数，逐字相等才放行，执行的是守护
# 自己这份字面量而不是请求内容。新增模板随守护版本下发。
fixed_template() {
  case $1 in
    'settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true'|\
    'settings put global airplane_mode_on 0 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false'|\
    'settings put secure location_providers_allowed +gps; settings put secure location_providers_allowed +network'|\
    'settings put secure location_providers_allowed -gps; settings put secure location_providers_allowed -network'|\
    'echo 1 > /sys/class/leds/torch-sec1/brightness'|\
    'echo 0 > /sys/class/leds/torch-sec1/brightness'|\
    'reboot'|'reboot -p')   # SystemCaps 重启/关机：App 侧有确认卡；守护侧只放这两种字面量
      FIXED_CMD=$1; FIXED_KIND=sh; return 0 ;;
    'bixby-status'|"$STATUS_PROBE_CMD")   # v4.1 G7：按键守护状态。不执行请求内容，走内建 status_report
      FIXED_CMD=bixby-status; FIXED_KIND=status; return 0 ;;
    'bixby-fastscreen-start')   # v4.3：拉起常驻读屏助手（内建实现 fastscreen_start，不执行请求内容）
      FIXED_CMD=bixby-fastscreen-start; FIXED_KIND=fastscreen; return 0 ;;
    'bixby-clear-recents')   # v4.4（fix/triage-fast）：清空后台（内建实现 clear_recents，不执行请求内容、不带参数）
      FIXED_CMD=bixby-clear-recents; FIXED_KIND=recents; return 0 ;;
  esac
  return 1
}

# ---------- v4.3：常驻读屏助手 ----------
# fastscreen_start <pkg> <uid>：pkg/uid 是 handle() 里已过 ident_ok 的请求方（不是请求内容）。
FS_MAIN=com.onesay.assistant.v2.fastscreen.HelperMain
fastscreen_running() { # fastscreen_running <pkg> → 0 = 抽象 socket @bixbyui_fs.<pkg> 已在监听
  grep -q " @bixbyui_fs\.$1\$" /proc/net/unix 2>/dev/null
}
fastscreen_start() {
  _p=$1; _u=$2
  is_pkg "$_p" || { echo "BAD_PKG"; return 1; }
  is_num "$_u" || { echo "BAD_UID"; return 1; }
  [ "$_u" -ge 10000 ] || { echo "BAD_UID"; return 1; }
  if fastscreen_running "$_p"; then echo "ALREADY"; return 0; fi
  [ -r "$PKGXML" ] && [ ! -L "$PKGXML" ] || { echo "NO_PKGXML"; return 1; }
  _pre=$(printf '%s' "$_p" | sed 's/\./\\./g')
  _head=$(grep -m1 "<package name=\"$_pre\" " "$PKGXML" 2>/dev/null)
  case $_head in *' codePath="'*) _cp=${_head#*' codePath="'}; _cp=${_cp%%\"*} ;; *) echo "NO_CODEPATH"; return 1 ;; esac
  case $_cp in /data/app/*) ;; *) echo "BAD_CODEPATH"; return 1 ;; esac
  case $_cp in *..*|*[!A-Za-z0-9_./=+-]*) echo "BAD_CODEPATH"; return 1 ;; esac
  _apk=$_cp/base.apk
  [ -f "$_apk" ] && [ ! -L "$_apk" ] || { echo "NO_APK"; return 1; }
  _log=$SECDIR/fastscreen.$_p.log
  ( CLASSPATH=$_apk exec app_process /system/bin --nice-name=bixbyui_fs:$_p $FS_MAIN "$_p" "$_u" </dev/null >"$_log" 2>&1 ) &
  _i=0
  while [ $_i -lt 30 ]; do   # 最多 3 s 等 socket 起来
    fastscreen_running "$_p" && { echo "STARTED"; return 0; }
    sleep 0.1; _i=$((_i + 1))
  done
  echo "START_TIMEOUT"; return 1
}

# ---------- v4.4（fix/triage-fast）：清空后台 ----------
# 回桌面 → 移除普通应用栈（Android 8：1 全屏 / 2 自由窗口 / 3 分屏 / 4 画中画；0 桌面、5 最近任务、6 助手不动；
# 移除栈 = 结束其中任务并从最近任务里删掉）→ am kill-all（只杀可安全回收的后台进程）。只输出计数。
clear_recents() {
  input keyevent 3 >/dev/null 2>&1
  _n=0
  for _s in $(am stack list 2>/dev/null | sed -n 's/^Stack id=\([0-9][0-9]*\) .*/\1/p'); do
    case $_s in 1|2|3|4) am stack remove "$_s" >/dev/null 2>&1 && _n=$((_n + 1)) ;; esac
  done
  am kill-all >/dev/null 2>&1
  echo "removed_stacks=$_n killall=done"
}

# run_template <tmp> <res> <odir> <tag> <signed 0|1>：执行 FIXED_CMD / FIXED_KIND（在子 shell 里调用）
run_template() {
  t0=$(date '+%s')
  if [ "$FIXED_KIND" = status ]; then
    out=$(status_report 2>&1); rc=$?
  elif [ "$FIXED_KIND" = fastscreen ]; then
    out=$(fastscreen_start "$pkg" "$duid" 2>&1); rc=$?
  elif [ "$FIXED_KIND" = recents ]; then
    out=$(clear_recents 2>&1); rc=$?
  else
    out=$(timeout -k 2 $CMD_TIMEOUT sh -c "$FIXED_CMD" 2>&1); rc=$?
  fi
  write_res "$1" "$2" "$3" "$rc" "$out"
  log "exec$4 rc=$rc elapsed=$(( $(date '+%s') - t0 ))s tmpl=1 signed=$5 cmd=$FIXED_CMD"
}

# ---------- v4.1 G8：读别的 App 私有数据 —— 有意拦截，给出明确的拒绝码 ----------
# other_app_ref <命令文本>：文本里出现别的包的私有数据路径（/data/data|/data/user[_de]/N|Android/data 下非本包、
#   且不在本包只读白名单前缀里）→ 0，并设 OTHER_PKG。用于把拒绝原因统一成 OTHER_APP_DATA(<包>)，
#   以及在 allow_raw / act=raw 下也照样拒（存图缓存层 CacheMatcher 就是这一类，v4 设计上不给）。
other_app_ref() {
  OTHER_PKG=''
  case $1 in */data/*|*/Android/data/*) ;; *) return 1 ;; esac   # 快路径：不 fork
  _t=$(printf '%s' "$1" | tr "\"'\$();&|<>{}\`=" '                ')
  set -f
  for _w in $_t; do
    case $_w in
      /data/data/*|/data/user/[0-9]*/*|/data/user_de/[0-9]*/*|/sdcard/Android/data/*|/storage/emulated/[0-9]*/Android/data/*|/data/media/[0-9]*/Android/data/*) ;;
      *) continue ;;
    esac
    _ok=0
    for _pre in $PFX_R; do case $_w in "$_pre"*) _ok=1; break ;; esac; done
    [ "$_ok" = 1 ] && continue
    case $_w in
      /data/data/*) _q=${_w#/data/data/} ;;
      /data/user/*|/data/user_de/*) _q=${_w#/data/user*/}; _q=${_q#*/} ;;
      *) _q=${_w#*/Android/data/} ;;
    esac
    _q=${_q%%/*}
    [ -n "$_q" ] && [ "$_q" != "$CUR_PKG" ] || continue
    OTHER_PKG=$_q; set +f; return 0
  done
  set +f
  return 1
}
# refine_deny <命令文本>：白名单拒了且涉及别的 App 私有数据 → 原因改成 OTHER_APP_DATA(<包>)
refine_deny() {
  if other_app_ref "$1"; then DENY="OTHER_APP_DATA($OTHER_PKG)"; return 0; fi
  return 1
}

# authorize：输入 QARGS（引号串），输出 EXEC_Q（最终 argv 引号串）+ EXEC_PROG（管道程序，单段为空）
authorize() {
  MEDIA_ACT=''; MEDIA_SRC=''; MEDIA_DST=''
  eval "set -- $QARGS"
  s1=''; s2=''; s3=''; n1=0; n2=0; n3=0; stage=1
  for a in "$@"; do
    if [ "$a" = "|" ]; then
      stage=$((stage + 1))
      [ $stage -gt 3 ] && { DENY="TOO_MANY_STAGES"; return 1; }
      continue
    fi
    q=$(shq "$a")
    case $stage in
      1) s1="$s1 $q"; n1=$((n1 + 1)) ;;
      2) s2="$s2 $q"; n2=$((n2 + 1)) ;;
      3) s3="$s3 $q"; n3=$((n3 + 1)) ;;
    esac
  done
  [ $n1 -gt 0 ] || { DENY="EMPTY_STAGE"; return 1; }
  check_main "$s1" || return 1
  [ -n "$MEDIA_ACT" ] && [ $stage -ge 2 ] && { DENY="MEDIA_NO_PIPE"; return 1; }
  [ $stage -ge 2 ] && { [ $n2 -gt 0 ] || { DENY="EMPTY_STAGE2"; return 1; }; check_filter "$s2" || return 1; }
  [ $stage -ge 3 ] && { [ $n3 -gt 0 ] || { DENY="EMPTY_STAGE3"; return 1; }; check_filter "$s3" || return 1; }

  EXEC_Q="$s1$s2$s3"
  if [ $stage -eq 1 ]; then
    EXEC_PROG=''
  else
    # 只用数字和引号拼程序串：参数仍通过位置参数传入，绝不拼进命令
    i=1; p=''
    for n in $n1 $n2 $n3; do
      [ "$n" = 0 ] && continue
      [ -n "$p" ] && p="$p |"
      j=0
      while [ $j -lt "$n" ]; do p="$p \"\$$i\""; i=$((i + 1)); j=$((j + 1)); done
    done
    EXEC_PROG=$p
  fi
  return 0
}

# ---------- 结果落盘 ----------
fix_owner() {
  _fd=${2:-$DIR}
  owner=$(stat -c %u "$_fd" 2>/dev/null)
  [ -n "$owner" ] && chown "$owner:$owner" "$1" 2>/dev/null
  ctx=$(ls -Zd "$_fd" 2>/dev/null); ctx=${ctx%% *}
  [ -n "$ctx" ] && chcon "$ctx" "$1" 2>/dev/null
  chmod 600 "$1" 2>/dev/null
}

write_res() { # $1 tmp（仅取其序号后缀） $2 res $3 odir $4 rc $5 out
  # ROOT-4：结果先写在 root-only 的 $SECDIR（App 碰不到，不可能被预置成符号链接），改好属主/标签后
  #   rename 进 App 目录。rename 不跟随目标处的符号链接；目标若是目录则拒写（safe_mv）。
  st=$SECDIR/.res.${1##*.}.$$
  rm -f "$st" 2>/dev/null
  { echo "$4"; echo "$5"; } > "$st" 2>/dev/null
  fix_owner "$st" "$3"
  safe_mv "$st" "$2"
}

run_one() { # $1 tmp $2 res $3 odir $4 tag $5 原始命令（仅日志） ; EXEC_Q/EXEC_PROG 已就绪
  tmp=$1; res=$2; odir=$3; tag=$4; cmdlog=$5
  t0=$(date '+%s')
  if [ -n "$MEDIA_ACT" ]; then
    # v4.1 G3：受控媒体写由守护自己执行（不按请求 argv 跑 mkdir/cp/screencap）
    out=$(media_run 2>&1)
  elif [ -z "$EXEC_PROG" ]; then
    eval "set -- $EXEC_Q"
    out=$(timeout -k 2 $CMD_TIMEOUT "$@" 2>&1)
  else
    eval "set -- $EXEC_Q"
    out=$(timeout -k 2 $CMD_TIMEOUT sh -c "$EXEC_PROG" bixby-exec "$@" 2>&1)
  fi
  rc=$?
  timedout=0
  if [ $rc -ge 124 ] && [ $rc -le 143 ]; then
    timedout=1; rc=124
    out="TIMEOUT: command exceeded ${CMD_TIMEOUT}s and was killed"
  fi
  write_res "$tmp" "$res" "$odir" "$rc" "$out"
  now=$(date '+%s')
  if [ $timedout = "1" ]; then
    log "exec$tag TIMEOUT rc=$rc elapsed=$((now - t0))s cmd=$cmdlog"
  else
    log "exec$tag rc=$rc elapsed=$((now - t0))s cmd=$cmdlog"
  fi
}

deny() { # $1 tmp $2 res $3 odir $4 tag $5 reason $6 cmd
  write_res "$1" "$2" "$3" 77 "DENY $5"
  log "exec$4 DENY $5 cmd=$6"
}

provision_key() { # $1 pkg $2 files 目录 $3 tag
  kd=$2/.bixby_key
  want=$(cat "$KEYFILE.$1" 2>/dev/null); [ -n "$want" ] || return 0
  if [ -f "$kd" ] && [ ! -L "$kd" ] && [ "$(cat "$kd" 2>/dev/null)" = "$want" ]; then return 0; fi
  [ -d "$kd" ] && [ ! -L "$kd" ] && { log "exec$3 WARN key target is a directory, skip"; return 0; }
  [ -L "$kd" ] && rm -f "$kd" 2>/dev/null
  st=$SECDIR/.keystage.$$
  rm -f "$st"; printf '%s' "$want" > "$st" 2>/dev/null
  fix_owner "$st" "$2"
  safe_mv "$st" "$kd"
  log "exec$3 key provisioned -> $kd"
}

# safe_mv <src> <dst>：dst 是符号链接 → 先删链接本身；dst 是目录 → 不写（防「mv 进目录」）
safe_mv() {
  [ -L "$2" ] && rm -f "$2" 2>/dev/null
  if [ -d "$2" ]; then rm -f "$1"; log "WARN refuse to mv into directory $2"; return 1; fi
  mv -f "$1" "$2" 2>/dev/null
}

# ---------- 请求解析（v4 结构化 / legacy 明文） ----------
# 解析结果：PARSED_CMD（要授权的命令串）、PARSED_RAW（1=act=raw）、MAC_STATE（none|ok|bad|replay|skew|nohash）
parse_request() {
  body=$1
  PARSED_RAW=0; PARSED_TMPL=0; MAC_STATE=none; PARSED_CMD=''; ARGV_JOIN=''; NARGS=0
  case $body in
    '#bixby-exec v4'*) ;;
    *) PARSED_CMD=$body; return 0 ;;   # legacy：整串当命令
  esac

  signed=''; mac=''; ts=''; nonce=''; act=''; args=''; first=1
  set -f
  OLDIFS=$IFS; IFS='
'
  for line in $body; do
    IFS=$OLDIFS
    if [ "$first" = 1 ]; then first=0; signed=$line; IFS='
'; continue; fi
    case $line in
      mac=*) mac=${line#mac=} ;;
      ts=*) ts=${line#ts=}; signed="$signed
$line" ;;
      nonce=*) nonce=${line#nonce=}; signed="$signed
$line" ;;
      act=*) act=${line#act=}; signed="$signed
$line" ;;
      a=*) args="$args $(shq "${line#a=}")"; NARGS=$((NARGS + 1))
           if [ $NARGS = 1 ]; then ARGV_JOIN=${line#a=}; else ARGV_JOIN="$ARGV_JOIN ${line#a=}"; fi
           signed="$signed
$line" ;;
      *) signed="$signed
$line" ;;
    esac
    IFS='
'
  done
  IFS=$OLDIFS
  set +f

  if [ -n "$mac" ]; then
    if ! want=$(mac_of "$signed" "$pkg"); then
      MAC_STATE=nohash
    else
      if [ "$want" != "$mac" ]; then
        MAC_STATE=bad
      else
        now=$(date '+%s')
        is_num "$ts" || ts=0
        _dd=$((now - ts)); [ $_dd -lt 0 ] && _dd=$((-_dd))
        if [ $_dd -gt "$MAC_SKEW" ]; then MAC_STATE=skew
        elif ! is_nonce "$nonce"; then MAC_STATE=bad
        elif nonce_used "$nonce"; then MAC_STATE=replay
        else nonce_mark "$nonce" "$now"; MAC_STATE=ok
        fi
      fi
    fi
  fi

  case $act in
    argv|'') PARSED_CMD=''; QARGS_PRE=$args ;;
    raw) PARSED_RAW=1; eval "set -- $args"; PARSED_CMD=${1:-} ;;
    tmpl)   # v4.1 G1：签名的固定模板请求——恰好一行 a=<模板全文>，MAC 通过后在 handle 里逐字匹配
      [ "$NARGS" = 1 ] || { DENY="TMPL_ARGC($NARGS)"; return 1; }
      PARSED_TMPL=1; QARGS_PRE=''; PARSED_CMD=$ARGV_JOIN ;;
    *) DENY="ACT_UNKNOWN($act)"; return 1 ;;
  esac
  return 0
}

# ---------- 处理一个通道 ----------
# handle <filesdir> <pkg> <tag> <slot>
handle() {
  d=$1; pkg=$2; tag=$3; slot=$4
  sreq=$d/.bixby_req
  sres=$d/.bixby_res
  [ -s "$sreq" ] || return 0

  DENY=''
  duid=$(stat -c %u "$d" 2>/dev/null)

  # ROOT-4：请求文件不得是符号链接；属主必须与 files 目录属主一致（ROOT-1a）
  if ! safe_file "$sreq" "${duid:-x}"; then
    r=$DENY
    rm -f "$sreq" 2>/dev/null
    deny "$d/.bixby_res.tmp.deny" "$sres" "$d" "$tag" "$r" "(请求文件已丢弃)"
    return 0
  fi

  # ROOT-1b：包身份
  if ! ident_ok "$pkg" "$d" "$tag" "$slot"; then
    r=$DENY
    rm -f "$sreq" 2>/dev/null
    deny "$d/.bixby_res.tmp.deny" "$sres" "$d" "$tag" "$r" "(identity)"
    return 0
  fi

  # ROOT-1d 密钥交付（受控握手）：只有通过 uid+签名校验的包才拿得到自己那把密钥。
  #   写法与结果文件一样：在 root-only 的 $SECDIR 里备好、改属主/标签，再 rename 进 App 目录（不跟随符号链接）。
  [ -f "$FLAG_PROVISION" ] && provision_key "$pkg" "$d" "$tag"

  # ROOT-5：读-清空原子化——先 rename 走，再读
  seq=$((seq + 1))
  # 取件目标在 root-only 的 $SECDIR（同在 /data 分区，rename 原子）：取走之后 App 侧谁也碰不到这份内容，
  #   不存在「读之前被换成符号链接」的窗口；属主随 rename 保留，取件后再核一次。
  proc=$SECDIR/.req.$slot.$seq
  rm -f "$proc" 2>/dev/null
  mv -f "$sreq" "$proc" 2>/dev/null || { log "exec$tag DENY TAKE_FAIL"; return 0; }
  if ! safe_file "$proc" "$duid"; then
    r=$DENY; rm -f "$proc"
    deny "$d/.bixby_res.tmp.deny" "$sres" "$d" "$tag" "$r(after take)" "(请求文件已丢弃)"
    return 0
  fi
  # 不再由 root 重建空请求文件：App 侧 File.length() 对不存在的文件返回 0，「取走=清空」语义不变；
  # 下一条请求由 App 自己新建（属主天然是 App uid），避免 root 建文件→chown 之间的窗口。

  old=0; stale "$proc" && old=1
  body=$(cat "$proc" 2>/dev/null)
  rm -f "$proc" 2>/dev/null
  [ -n "$body" ] || return 0

  if [ "$old" = 1 ]; then
    log "exec$tag STALE dropped cmd=$body"
    return 0
  fi

  set_prefixes "$pkg"
  DENY=''; QARGS_PRE=''
  if ! parse_request "$body"; then
    deny "$d/.bixby_res.tmp.$seq" "$sres" "$d" "$tag" "$DENY" "$body"
    return 0
  fi

  # ROOT-1d：MAC
  case $MAC_STATE in
    ok) ;;
    none)
      if [ -f "$FLAG_REQUIRE_MAC" ]; then
        deny "$d/.bixby_res.tmp.$seq" "$sres" "$d" "$tag" "MAC_REQUIRED" "$body"; return 0
      fi
      log "exec$tag WARN NO_MAC（require_mac 未开；App 侧 HMAC 落地后请开启）" ;;
    *)
      deny "$d/.bixby_res.tmp.$seq" "$sres" "$d" "$tag" "MAC_$MAC_STATE" "$body"; return 0 ;;
  esac
  sg=0; [ "$MAC_STATE" = ok ] && sg=1

  # v4.1 G1：固定模板——明文逐字、签名 act=tmpl、或签名 act=argv 而 argv 拼起来逐字等于模板，三种都认；
  #   签名的两种此处 MAC 已校验通过。执行的永远是守护自己的字面量 / 内建实现，不是请求内容。
  if [ "$PARSED_RAW" = 0 ]; then
    tcmd=$PARSED_CMD; [ -n "$QARGS_PRE" ] && tcmd=$ARGV_JOIN
    if fixed_template "$tcmd"; then
      ( run_template "$d/.bixby_res.tmp.$seq" "$sres" "$d" "$tag" "$sg" ) &
      return 0
    fi
    if [ "$PARSED_TMPL" = 1 ]; then
      deny "$d/.bixby_res.tmp.$seq" "$sres" "$d" "$tag" "TMPL_UNKNOWN" "$PARSED_CMD"; return 0
    fi
  fi

  # v4.1 G8：别的 App 私有数据——任何形式（含 allow_raw / act=raw）都拒，原因写明
  if [ "$PARSED_RAW" = 1 ] || [ -z "$QARGS_PRE" ]; then
    rtext=$PARSED_CMD
  else
    rtext=$ARGV_JOIN
  fi
  if other_app_ref "$rtext"; then
    deny "$d/.bixby_res.tmp.$seq" "$sres" "$d" "$tag" "OTHER_APP_DATA($OTHER_PKG)" "$rtext（v4 有意拦截：不读别的 App 私有数据）"
    return 0
  fi

  # 授权
  # 过渡期：legacy 明文请求过不了白名单、且 root 开了 allow_raw → 按 raw 执行（记 WARN）。
  #   App 里还有一批复合脚本（存图/识屏/速记缩略图/城市库/后台白名单/控制台状态…，见 fix-root.md §5），
  #   在它们迁到模板/拆分之前，不开 allow_raw 这些功能会报 DENY。开了它，身份/uid/符号链接/pid 的修复照样生效，
  #   但 ROOT-1「注入即 root」在这条路径上**没有**被白名单挡住——只是过渡手段，App 迁移完必须删掉标记。
  if [ "$PARSED_RAW" = 0 ] && [ -z "$QARGS_PRE" ] && [ -f "$FLAG_ALLOW_RAW" ]; then
    DENY=''
    if ! { tokenize "$PARSED_CMD" && authorize; }; then
      log "exec$tag WARN LEGACY_RAW (would DENY $DENY) cmd=$PARSED_CMD"
      PARSED_RAW=1
    fi
  fi

  if [ "$PARSED_RAW" = 1 ]; then
    if [ ! -f "$FLAG_ALLOW_RAW" ]; then
      deny "$d/.bixby_res.tmp.$seq" "$sres" "$d" "$tag" "RAW_DISABLED" "$PARSED_CMD"; return 0
    fi
    EXEC_Q=''; EXEC_PROG=''
    log "exec$tag WARN RAW_ALLOWED cmd=$PARSED_CMD"
    ( t0=$(date '+%s'); out=$(timeout -k 2 $CMD_TIMEOUT sh -c "$PARSED_CMD" 2>&1); rc=$?
      write_res "$d/.bixby_res.tmp.$seq" "$sres" "$d" "$rc" "$out"
      log "exec$tag rc=$rc elapsed=$(( $(date '+%s') - t0 ))s cmd=$PARSED_CMD" ) &
    return 0
  fi

  if [ -n "$QARGS_PRE" ]; then
    QARGS=$QARGS_PRE                      # v4 结构化：参数已分好，不需要分词
    cmdlog=$(printf '%s' "$QARGS_PRE" | tr -d "'")
  else
    if ! tokenize "$PARSED_CMD"; then
      deny "$d/.bixby_res.tmp.$seq" "$sres" "$d" "$tag" "$DENY" "$PARSED_CMD"; return 0
    fi
    cmdlog=$PARSED_CMD
  fi

  DENY=''
  if ! authorize; then
    deny "$d/.bixby_res.tmp.$seq" "$sres" "$d" "$tag" "$DENY" "$cmdlog"
    return 0
  fi

  ( run_one "$d/.bixby_res.tmp.$seq" "$sres" "$d" "$tag" "$cmdlog" ) &
  return 0
}

stale() {
  m=$(stat -c %Y "$1" 2>/dev/null)
  [ -n "$m" ] || return 1
  now=$(date '+%s')
  [ $((now - m)) -gt "$STALE_S" ]
}

if [ "$CHECK_ONLY" = 1 ]; then
  set_prefixes "${2:-$PKG_REL}"
  DENY=''
  if fixed_template "$1"; then echo "ALLOW (template)"; exit 0; fi
  if tokenize "$1" && authorize; then
    if other_app_ref "$1"; then echo "DENY OTHER_APP_DATA($OTHER_PKG)"; exit 77; fi
    echo "ALLOW"; exit 0
  fi
  refine_deny "$1"
  echo "DENY $DENY"; exit 77
fi

# ---------- 主循环 ----------
# App 首装前 files 目录可能不存在，等它出现（最多 ~5 分钟）
n=0
while [ ! -d "$DIR" ] && ! { [ -f "$DBG_FLAG" ] && [ -d "$DBG_DIR" ]; } && [ $n -lt 100 ]; do sleep 3; n=$((n + 1)); done
log "files dir ready (waited ${n}x3s)"
h2b_pick

DBG_ON=0
seq=0
while true; do
  [ -d "$DIR" ] && handle "$DIR" "$PKG_REL" "" rel
  if [ -f "$DBG_FLAG" ] && [ -d "$DBG_DIR" ]; then
    [ "$DBG_ON" = 1 ] || { DBG_ON=1; log "debug exec ON ($DBG_DIR)"; }
    handle "$DBG_DIR" "$PKG_DBG" "[debug]" dbg
  elif [ "$DBG_ON" = 1 ]; then
    DBG_ON=0; log "debug exec OFF"
  fi
  sleep 0.15
done
