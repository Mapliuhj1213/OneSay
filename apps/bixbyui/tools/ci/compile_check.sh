#!/usr/bin/env bash
# ============================================================
# 「lint」：不出 APK，只把全部生产源码按 APK 同口径编译一遍（-source 1.7，android-28 bootclasspath），
# 统计 javac 警告数；再检查 shell 脚本语法、prod-classes.txt 里列的文件都存在。（refactor/oss 2026-11-06）
#   本仓库没用 Gradle，Android Lint 跑不了；这里先守住「能编、语法对、清单不漂移」。
#   OUT=/tmp/bixby-lint bash tools/ci/compile_check.sh
# 代码只引用 android.R（不引用应用自己的 R），所以不需要先跑 aapt2。
# ============================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TOOLS="${TOOLS:-$HOME/onesay-tools}"
export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk}"
PLATFORM="${SDK:-$TOOLS/android-sdk}/platforms/android-28/android.jar"
OUT="${OUT:-/tmp/bixby-lint}"
rm -rf "$OUT"; mkdir -p "$OUT/classes"

# 1) prod-classes.txt 不漂移
missing=0
while read -r f; do
  [ -f "$ROOT/app/src/$f" ] || { echo "[lint] prod-classes.txt 列了不存在的文件：$f" >&2; missing=$((missing+1)); }
done < <(grep -vE '^\s*(#|$)' "$ROOT/test-jvm/prod-classes.txt")
[ "$missing" -eq 0 ] || exit 1
echo "[lint] prod-classes.txt OK"

# 2) shell 语法
fail=0
while IFS= read -r -d '' s; do bash -n "$s" 2>/dev/null || { echo "[lint] 语法错误：${s#$ROOT/}" >&2; fail=1; }; done \
  < <(cd "$ROOT" && git ls-files -z '*.sh' | sed -z "s#^#$ROOT/#")
[ "$fail" -eq 0 ] || exit 1
echo "[lint] shell 语法 OK"

# 3) 全量 javac（debug 口径：含 com.onesay.assistant.v2.test 调试代码；release 口径的差异由构建脚本覆盖）
{ find "$ROOT/app/src" -name '*.java'; find "$ROOT/app/variant/debug" "$ROOT/app/config" -name '*.java';
  find "$ROOT/vendor/sherpa/java" -name '*.java'; find "$ROOT/../../libs" -path '*/src/*' -name '*.java' 2>/dev/null; } > "$OUT/sources.txt"
set +e
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 1.7 -target 1.7 -Xlint:all -Xlint:-options -Xmaxwarns 100000 \
  -bootclasspath "$PLATFORM" -classpath "$PLATFORM:$ROOT/vendor/mail/android-mail-1.6.5.jar:$ROOT/vendor/mail/android-activation-1.6.5.jar" \
  -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
rc=$?
set -e
warn="$(grep -cE ': warning:' "$OUT/javac.log" || true)"
if [ "$rc" -ne 0 ]; then grep -E ': error:' "$OUT/javac.log" | head -40 >&2; echo "[lint] javac 失败（完整输出 $OUT/javac.log）" >&2; exit 1; fi
echo "[lint] javac OK，$(wc -l < "$OUT/sources.txt") 个源文件，警告 $warn 条（-Xlint:all；完整输出 $OUT/javac.log）"
