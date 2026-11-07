#!/usr/bin/env bash
# ============================================================
# CI 断言：签名私钥 / 口令文件绝不能进 git 版本库
#
#   bash tools/ci/check_no_keystore.sh            # 扫索引（git ls-files）：命中即 exit 1
#   bash tools/ci/check_no_keystore.sh --history  # 另外扫当前分支全部历史提交：命中只告警（exit 0），
#                                                  #   加 --strict-history 时历史命中也 exit 1
#   REPO=/path/to/repo bash tools/ci/check_no_keystore.sh
#
# 命中规则（大小写不敏感）：*.keystore *.jks *.p12 *.pfx *.keystore.pass 以及 .signing/ 目录下任何文件。
# build_linux.sh 构建结尾自动调用（无 --history），失败即构建失败。
# 历史里残留的旧 debug.keystore（e60370a 起）需要用户确认后用 git filter-repo / BFG 清除，本脚本只负责报告。
# ============================================================
set -euo pipefail

REPO="${REPO:-$(cd "$(dirname "$0")/../.." && pwd)}"
HISTORY=0; STRICT_HISTORY=0
for a in "$@"; do
  case "$a" in
    --history) HISTORY=1 ;;
    --strict-history) HISTORY=1; STRICT_HISTORY=1 ;;
    *) echo "未知参数：$a" >&2; exit 2 ;;
  esac
done

PATTERN='(\.keystore|\.jks|\.p12|\.pfx|\.keystore\.pass)$|(^|/)\.signing/'

if ! git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1; then
  echo "[keystore-check] $REPO 不是 git 仓库，跳过（非 CI 环境）"
  exit 0
fi

hits="$(git -C "$REPO" ls-files -z | tr '\0' '\n' | grep -iE "$PATTERN" || true)"
if [ -n "$hits" ]; then
  echo "[keystore-check] FAIL：以下签名私钥 / 口令文件在 git 索引里（必须 git rm --cached 并加进 .gitignore）：" >&2
  echo "$hits" | sed 's/^/    /' >&2
  exit 1
fi
echo "[keystore-check] OK：索引里没有 keystore / 口令文件"

if [ "$HISTORY" = "1" ]; then
  hist="$(git -C "$REPO" log --all --format='' --name-only --diff-filter=A 2>/dev/null | grep -iE "$PATTERN" | sort -u || true)"
  if [ -n "$hist" ]; then
    echo "[keystore-check] WARN：历史提交里曾经加入过以下文件（私钥已视为泄露，需轮换；清历史需用户确认）：" >&2
    echo "$hist" | sed 's/^/    /' >&2
    [ "$STRICT_HISTORY" = "1" ] && exit 1
  else
    echo "[keystore-check] OK：历史提交里也没有 keystore / 口令文件"
  fi
fi
exit 0
