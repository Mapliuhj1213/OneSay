#!/bin/sh
# v4.2 hex2bin 单测（daemon-mac）：从守护脚本里抽出 hex2bin 相关函数，在 dash/bash 下测正确性、自检、兜底与计时
# 用法：sh test_hex2bin_v4.sh [守护脚本]（dash / bash / mksh 均可；需 python3 造假 xxd）
HERE=$(cd "$(dirname "$0")" && pwd)
D=${1:-$HERE/exec_daemon.v4.sh}
FIX=$HERE/testdata
T=$(mktemp -d); mkdir -p $T/good $T/bad
printf '%s\n' '#!/usr/bin/env python3' 'import sys,binascii' 'if sys.argv[1:3]!=["-r","-p"]: sys.exit(2)' 'sys.stdout.buffer.write(binascii.unhexlify("".join(sys.stdin.read().split())))' > $T/good/xxd
printf '%s\n' '#!/usr/bin/env python3' 'import sys,binascii' 'b=bytearray(binascii.unhexlify("".join(sys.stdin.read().split())))' 'b[-1]^=1' 'sys.stdout.buffer.write(bytes(b))' > $T/bad/xxd
chmod +x $T/good/xxd $T/bad/xxd; PATH=$T/good:$PATH; SECDIR=$T; LOG=$T/log; CHECK_ONLY=0; BIXBY_EXEC_DIR=$T
log() { echo "$1" >> "$LOG"; }
eval "$(sed -n '/^sha256_file() {/,/^}/p;/^hexd() /p;/^hex2bin_sh() {/,/^}/p;/^H2B_VEC=/p;/^H2B_VEC_SHA=/p;/^h2b_pick() {/,/^}/p;/^hex2bin() {/,/^}/p' "$D")"
P=0; F=0; ok(){ P=$((P+1)); echo "  [PASS] $1"; }; no(){ F=$((F+1)); echo "  [FAIL] $1"; }
now_ms(){ date +%s%N | cut -c1-13; }
for c in debug_cert_rotated debug_cert_old_revoked release_cert attacker_cert; do
  hx=$(od -An -tx1 -v "$FIX/$c.der" | tr -d ' \n'); want=$(sha256sum "$FIX/$c.der" | cut -d' ' -f1)
  BIXBY_EXEC_H2B_TOOLS=xxd; h2b_pick
  [ "$H2B_TOOL" = xxd ] && ok "$c 原生自检通过选用 xxd" || no "$c 未选用原生"
  t0=$(now_ms); hex2bin "$hx" $T/a; t1=$(now_ms); g=$(sha256_file $T/a)
  [ "$g" = "$want" ] && ok "$c 原生 sha=${g%${g#????????}}… 正确（$((t1-t0)) ms, ${#hx} hex）" || no "$c 原生 sha 错"
  BIXBY_EXEC_H2B_TOOLS=none; h2b_pick
  t0=$(now_ms); hex2bin "$hx" $T/b; t1=$(now_ms); g2=$(sha256_file $T/b)
  [ -z "$H2B_TOOL" ] && [ "$g2" = "$want" ] && ok "$c 纯 sh 兜底 sha 一致（$((t1-t0)) ms）" || no "$c 兜底错"
done
# 坏工具：自检拒绝，退回纯 sh
PATH_SAVE=$PATH; PATH=$T/bad:$PATH; BIXBY_EXEC_H2B_TOOLS=xxd; h2b_pick; PATH=$PATH_SAVE
[ -z "$H2B_TOOL" ] && ok "输出错位的 xxd 被自检拒绝（退回纯 sh）" || no "坏 xxd 被采用"
# 输入校验
BIXBY_EXEC_H2B_TOOLS=xxd; h2b_pick
hex2bin "abc" $T/c && no "奇数长度未拒" || ok "奇数长度 hex 拒"
hex2bin "zz00" $T/c && no "非 hex 未拒" || ok "非 hex 字符拒"
hex2bin "" $T/c && no "空串未拒" || ok "空串拒"
hex2bin "00ff' ; touch $T/pwn ; '" $T/c; [ ! -e $T/pwn ] && ok "注入串不执行" || no "注入执行了"
# 全 256 字节向量
hex2bin "$H2B_VEC" $T/v; [ "$(sha256_file $T/v)" = "$H2B_VEC_SHA" ] && ok "00..ff 向量 sha 正确" || no "向量错"
echo "== h2b unit pass=$P fail=$F =="; rm -rf "$T"; [ $F = 0 ]
