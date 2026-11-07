#!/usr/bin/env bash
# ============================================================
# OneSay 纯命令行构建脚本
# 适配环境：Windows / Git-Bash / JDK 8 / Android SDK 28 / Build-Tools 30.0.3
# 架构体系：五层架构（Wake / Capability / Router / Executor / UI）
#
# ⚠：本脚本**已落后、当前代码用它编不过**——没跟上 的 debug 变体
#   （不编译 app/variant/<变体>/BuildVariant → javac 找不到 com.onesay.assistant.v2.BuildVariant；也没有 res-debug 覆盖层、
#   applicationId 改名、debug.keystore、Manifest 权限 / action 改名，以及 release 剔除 debug 专用 assets）。
#   以 build_linux.sh 为准；要在 Windows 上出包，先按 build_linux.sh 同步本脚本。
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
W() { cygpath -m "$1"; }

SDK="${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk"
BT="$SDK/build-tools/30.0.3"
PLATFORM="$SDK/platforms/android-28/android.jar"
JAVA_HOME_DIR="/c/Program Files/Java/jdk1.8.0_201"
JDK="$JAVA_HOME_DIR/bin"
OUT="$ROOT/out"
KS="$ROOT/bixbyui.keystore"
VENDOR="$ROOT/../bixbyui/vendor"
# 邮件能力（IMAP）依赖 JavaMail 的 Android 移植版
#   （android-mail 1.6.5 / android-activation 1.6.5，字节码 major=51 即 Java 7，
#    与本工程 -source 1.7 兼容）。仅用于 classpath + 一起 dex，不进 bootclasspath。
MAILJARS="$ROOT/vendor/mail/android-mail-1.6.5.jar;$ROOT/vendor/mail/android-activation-1.6.5.jar"

export JAVA_HOME="$JAVA_HOME_DIR"
export PATH="$JDK:$PATH"

PLATFORMW="$(W "$PLATFORM")"
ROOTW="$(W "$ROOT")"
OUTW="$(W "$OUT")"
VENDORW="$(W "$VENDOR")"

for f in "$BT/aapt2.exe" "$BT/zipalign.exe" "$JDK/javac.exe" "$JDK/jar.exe" "$PLATFORM"; do
  [ -e "$f" ] || { echo "MISSING DEP: $f" >&2; exit 1; }
done

# DEBUG=1 才把 test/（V2SelfTest、TestRunReceiver）编进 APK；默认 release。
#   规则与 build_linux.sh 完全一致（见其头部注释与）。
DEBUG="${DEBUG:-0}"
TEST_RECEIVER_OPEN="${TEST_RECEIVER_OPEN:-0}"
if [ "$DEBUG" = "1" ]; then VARIANT="debug"; else VARIANT="release"; fi
echo "== VARIANT: $VARIANT"

mkdir -p "$OUT"
find "$OUT" -mindepth 1 -delete
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/8] aapt2 compile (resources -> flat zip)"
"$BT/aapt2.exe" compile --dir "$ROOTW/app/res" -o "$OUTW/res.zip"

echo "[2/8] aapt2 link    (manifest + resources + assets)"
MANIFEST="$OUT/AndroidManifest.xml"
if [ "$DEBUG" = "1" ]; then
  if [ "$TEST_RECEIVER_OPEN" = "1" ]; then
    sed 's#android:permission="com.onesay.assistant.permission.CONTROL"><!-- TEST-RECEIVER-PERMISSION -->#>#' \
      "$ROOT/app/AndroidManifest.xml" > "$MANIFEST"
  else
    cp "$ROOT/app/AndroidManifest.xml" "$MANIFEST"
  fi
else
  sed '/DEBUG-ONLY-BEGIN/,/DEBUG-ONLY-END/d' "$ROOT/app/AndroidManifest.xml" > "$MANIFEST"
fi
"$BT/aapt2.exe" link \
  -o "$OUTW/base.apk" \
  -I "$PLATFORMW" \
  --manifest "$(W "$MANIFEST")" \
  --java "$OUTW/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 28 \
  -A "$ROOTW/app/assets" \
  --auto-add-overlay \
  "$OUTW/res.zip"

echo "[3/8] javac        (Java 7/8 syntax compile)"
if [ "$DEBUG" = "1" ]; then
  find "$ROOT/app/src" "$OUT/gen" "$VENDOR/sherpa/java" -name "*.java"
else
  find "$ROOT/app/src" -path "$ROOT/app/src/com/onesay/assistant/v2/test" -prune -o -name "*.java" -print
  find "$OUT/gen" "$VENDOR/sherpa/java" "$ROOT/app/src-release" -name "*.java"
fi | while read -r p; do W "$p"; done > "$OUT/sources.txt"
"$JDK/javac.exe" -encoding UTF-8 -nowarn -source 1.7 -target 1.7 \
  -bootclasspath "$PLATFORMW" \
  -classpath "$PLATFORMW;$(W "$ROOT/vendor/mail/android-mail-1.6.5.jar");$(W "$ROOT/vendor/mail/android-activation-1.6.5.jar")" \
  -d "$OUTW/classes" @"$(W "$OUT/sources.txt")"

echo "[4/8] d8           (bytecode -> dex, min-api 24)"
( cd "$OUT/classes" && "$JDK/jar.exe" cf "$OUTW/classes.jar" . )
"$JDK/java.exe" -cp "$(W "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
  --min-api 24 --lib "$PLATFORMW" --output "$OUTW/dex" "$OUTW/classes.jar" \
  "$(W "$ROOT/vendor/mail/android-mail-1.6.5.jar")" \
  "$(W "$ROOT/vendor/mail/android-activation-1.6.5.jar")"

# [4b/8] 已删除：vosk + jna → classes2.dex（代码零引用）。
echo "[5/8] pack          (classes.dex + native libs -> apk)"
mkdir -p "$OUT/jni/lib/arm64-v8a"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT" && "$JDK/jar.exe" uf unsigned.apk -C dex classes.dex )
cp "$VENDOR/sherpa/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$OUT/jni/lib/arm64-v8a/"
cp "$VENDOR/sherpa/jniLibs/arm64-v8a/libonnxruntime.so" "$OUT/jni/lib/arm64-v8a/"
( cd "$OUT" && "$JDK/jar.exe" uf unsigned.apk \
    -C jni lib/arm64-v8a/libsherpa-onnx-jni.so \
    -C jni lib/arm64-v8a/libonnxruntime.so )

echo "[6/8] zipalign"
"$BT/zipalign.exe" -f -p 4 "$OUTW/unsigned.apk" "$OUTW/aligned.apk"

echo "[7/8] sign"
APKSIGNER() { "$JDK/java.exe" -cp "$(W "$BT/lib/apksigner.jar")" com.android.apksigner.ApkSignerTool "$@"; }
# 不再写明文口令（原来在命令行里写死 android/android）。与 build_linux.sh 同口径：
#    ① 环境变量 KS_PASS（/KEY_PASS）；② KS_PASS_FILE（/KEY_PASS_FILE）指向的文件；
#    ③ keystore 旁的本地未跟踪文件 bixbyui.keystore.pass；都没有就报错退出。
if [ -n "${KS_PASS:-}" ]; then KS_PASS_SRC="env:KS_PASS"
elif [ -n "${KS_PASS_FILE:-}" ] && [ -r "$KS_PASS_FILE" ]; then KS_PASS_SRC="file:$(W "$KS_PASS_FILE")"
elif [ -r "$KS.pass" ]; then KS_PASS_SRC="file:$(W "$KS.pass")"
else echo "缺少签名口令：设置 KS_PASS / KS_PASS_FILE，或放本地未跟踪的 $(basename "$KS").pass" >&2; exit 2; fi
if [ -n "${KEY_PASS:-}" ]; then KEY_PASS_SRC="env:KEY_PASS"
elif [ -n "${KEY_PASS_FILE:-}" ] && [ -r "$KEY_PASS_FILE" ]; then KEY_PASS_SRC="file:$(W "$KEY_PASS_FILE")"
else KEY_PASS_SRC="$KS_PASS_SRC"; fi
# apksigner 对同一个 file: 会按行顺序读（第 1 行给 store、第 2 行给 key），所以 key 口令与 store 相同时不传 --key-pass
#   （apksigner 缺省即用 store 口令）。
KEY_PASS_ARGS=(); [ "$KEY_PASS_SRC" != "$KS_PASS_SRC" ] && KEY_PASS_ARGS=(--key-pass "$KEY_PASS_SRC")
APKSIGNER sign --ks "$(W "$KS")" --ks-pass "$KS_PASS_SRC" "${KEY_PASS_ARGS[@]}" \
  --out "$OUTW/bixbyui.apk" "$OUTW/aligned.apk"

echo "[8/8] verify"
APKSIGNER verify --print-certs "$OUTW/bixbyui.apk" | head -3

# 任何 keystore / 口令文件进 git 索引即失败
bash "$ROOT/tools/ci/check_no_keystore.sh"

echo "$VARIANT" > "$OUT/BUILD_VARIANT"
echo "BUILD SUCCESS [$VARIANT]: $ROOT/out/bixbyui.apk ($(stat -c%s "$OUT/bixbyui.apk") bytes)"
