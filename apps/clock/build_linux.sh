#!/usr/bin/env bash
# OneSay Clock（com.onesay.clock）纯命令行构建：aapt2 → javac(1.7) → d8 → zipalign → apksigner。沿用 v1 的无 Gradle 做法。
# 签名：debug 包。KS / KS_PASS / KS_ALIAS 可由环境变量提供；未提供时在 /tmp 生成一把本机 debug keystore（口令 android，Android 惯例，非秘密），绝不写进源码树。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${TOOLS:-$HOME/onesay-tools}"
export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk}"
JDK="$JAVA_HOME/bin"
SDK="${SDK:-$TOOLS/android-sdk}"
BT="$SDK/build-tools/30.0.3"
PLATFORM="$SDK/platforms/android-28/android.jar"
OUT="${OUT:-/tmp/onesay-clock-debug}"
KS="${KS:-}"
if [ -z "$KS" ] || [ -z "${KS_PASS:-}" ]; then
  KS="${DEBUG_KS:-/tmp/onesay-clock-debug.keystore}"; KS_PASS=android; KEY_PASS=android; KS_ALIAS=androiddebugkey
  [ -f "$KS" ] || "$JDK/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  echo "== 使用本机 debug keystore：$KS"
fi
export KS_PASS; KEY_PASS="${KEY_PASS:-$KS_PASS}"; export KEY_PASS; KS_ALIAS="${KS_ALIAS:-bixbyclock}"
case "$(uname -m)" in
  x86_64|amd64) AAPT2="${AAPT2:-$BT/aapt2}"; ZIPALIGN="${ZIPALIGN:-$BT/zipalign}" ;;
  *)            AAPT2="${AAPT2:-$TOOLS/native-aarch64/build-tools/aapt2}"; ZIPALIGN="${ZIPALIGN:-$TOOLS/native-aarch64/build-tools/zipalign}" ;;
esac
for f in "$AAPT2" "$ZIPALIGN" "$JDK/javac" "$PLATFORM" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" "$KS"; do
  [ -e "$f" ] || { echo "MISSING DEP: $f" >&2; exit 1; }
done
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
echo "[1/7] aapt2 compile"; "$AAPT2" compile --dir "$ROOT/app/res" -o "$OUT/res.zip"
echo "[2/7] aapt2 link"
"$AAPT2" link -o "$OUT/base.apk" -I "$PLATFORM" --manifest "$ROOT/app/AndroidManifest.xml" --java "$OUT/gen" \
  --min-sdk-version 23 --target-sdk-version 28 -A "$ROOT/app/assets" -0 ogg --auto-add-overlay "$OUT/res.zip"
echo "[3/7] javac"
find "$ROOT/app/src" "$ROOT/app/variant/debug" "$OUT/gen" -name "*.java" | sort > "$OUT/sources.txt"
"$JDK/javac" -encoding UTF-8 -nowarn -Xlint:-options -source 1.7 -target 1.7 -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | tee "$OUT/javac.log"
[ "${PIPESTATUS[0]}" = "0" ] || { echo "javac FAILED" >&2; exit 1; }
echo "[4/7] d8"
( cd "$OUT/classes" && "$JDK/jar" cf "$OUT/classes.jar" . )
"$JDK/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 23 --lib "$PLATFORM" --output "$OUT/dex" "$OUT/classes.jar"
cp "$OUT/base.apk" "$OUT/unsigned.apk"; ( cd "$OUT" && "$JDK/jar" uf unsigned.apk -C dex classes.dex )
echo "[5/7] zipalign"; "$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
echo "[6/7] sign"
"$JDK/java" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass env:KS_PASS --key-pass env:KEY_PASS --out "$OUT/bixbyclock-debug.apk" "$OUT/aligned.apk"
echo "[7/7] verify"
"$JDK/java" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify --print-certs "$OUT/bixbyclock-debug.apk" | head -2
"$ZIPALIGN" -c -p 4 "$OUT/bixbyclock-debug.apk" && echo "zipalign OK"
echo "OUTPUT: $OUT/bixbyclock-debug.apk ($(stat -c%s "$OUT/bixbyclock-debug.apk") bytes)"
