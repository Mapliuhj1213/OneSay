#!/usr/bin/env bash
# OneSay Plan（com.onesay.plan）纯命令行构建：aapt2 → javac(1.7) → d8 → zipalign → apksigner。无 Gradle。
#
#   BUILD_TYPE=debug   bash build_linux.sh   # 任何人 clone 后即可构建：debug 签名、android:debuggable=true，不需要任何私有文件
#   BUILD_TYPE=release bash build_linux.sh   # 默认；需要签名配置（见下），并校验证书指纹
#
# 签名配置（只用于 release）：环境变量优先，其次读仓库根目录未跟踪的 signing.env（模板见 signing.env.example）。
#   KS            keystore 路径（必填，keystore 不进 git）
#   KS_ALIAS      默认 mb
#   KS_PASS / KS_PASS_FILE   口令只从环境变量或本地未跟踪文件读，绝不写进脚本 / 仓库 / 日志；也可放在 "$KS.pass"
#   EXPECT_CERT   可选：期望的签名证书 SHA-256（小写十六进制，无冒号）；设置后证书不符即失败。
#   UNSIGNED=1    release 不签名，只输出 zipalign 后的未签名 APK（审查 / CI 用，不需要 keystore）
# 与 OneSay / Clock 互通：三个 App 必须用同一证书签名（Plan 只认同证书调用方，见 core/AccessPolicy）。
#
# 工具链：TOOLS（默认 $HOME/onesay-tools）下的 jdk / android-sdk（platforms;android-28 + build-tools;30.0.3），
#   也可分别用 JAVA_HOME / SDK / AAPT2 / ZIPALIGN 覆盖。输出目录 OUT（默认 /tmp/onesay-plan-<类型>）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD_TYPE="${BUILD_TYPE:-release}"
case "$BUILD_TYPE" in release|debug) ;; *) echo "BUILD_TYPE 只能是 release 或 debug" >&2; exit 2 ;; esac

# signing.env：KEY=VALUE 逐行读取（不 source、不执行），只填环境里还没有的变量
if [ "$BUILD_TYPE" = release ] && [ -r "$ROOT/signing.env" ]; then
  while IFS='=' read -r k v; do
    case "$k" in KS|KS_ALIAS|KS_PASS_FILE|EXPECT_CERT) [ -z "${!k:-}" ] && export "$k=$v" ;; esac
  done < <(grep -E '^[A-Z_]+=' "$ROOT/signing.env" | sed 's/[[:space:]]*$//')
fi

TOOLS="${TOOLS:-$HOME/onesay-tools}"
export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk}"
JDK="$JAVA_HOME/bin"
SDK="${SDK:-${ANDROID_HOME:-$TOOLS/android-sdk}}"
BT="$SDK/build-tools/30.0.3"
PLATFORM="$SDK/platforms/android-28/android.jar"
OUT="${OUT:-/tmp/onesay-plan-$BUILD_TYPE}"
UNSIGNED="${UNSIGNED:-0}"
KS_ALIAS="${KS_ALIAS:-mb}"
EXPECT_CERT="${EXPECT_CERT:-}"

if [ "$BUILD_TYPE" = release ] && [ "$UNSIGNED" = "1" ]; then
  APK_NAME=bixbyplan-unsigned.apk; LINK_EXTRA=(); KS=""
elif [ "$BUILD_TYPE" = release ]; then
  [ -n "${KS:-}" ] || { echo "缺少 KS（keystore 路径）：设置环境变量或在 signing.env 里写 KS=…；只想自己编来试试请用 BUILD_TYPE=debug" >&2; exit 2; }
  if [ -n "${KS_PASS:-}" ]; then PASS_SRC="env:KS_PASS"; elif [ -n "${KS_PASS_FILE:-}" ]; then PASS_SRC="file:$KS_PASS_FILE";
  elif [ -r "$KS.pass" ]; then PASS_SRC="file:$KS.pass"; else echo "缺少签名口令：设置 KS_PASS 或 KS_PASS_FILE" >&2; exit 2; fi
  APK_NAME=bixbyplan.apk; LINK_EXTRA=()
else
  # Android 惯例的 debug keystore（口令 android 是公开约定，不是秘密）；没有就生成一把
  KS="${DEBUG_KS:-/tmp/onesay-plan-debug.keystore}"; KS_ALIAS=androiddebugkey; PASS_SRC="pass:android"
  APK_NAME=bixbyplan-debug.apk; LINK_EXTRA=(--debug-mode)
fi

case "$(uname -m)" in
  x86_64|amd64) AAPT2="${AAPT2:-$BT/aapt2}"; ZIPALIGN="${ZIPALIGN:-$BT/zipalign}" ;;
  *)            AAPT2="${AAPT2:-$TOOLS/native-aarch64/build-tools/aapt2}"; ZIPALIGN="${ZIPALIGN:-$TOOLS/native-aarch64/build-tools/zipalign}" ;;
esac
for f in "$AAPT2" "$ZIPALIGN" "$JDK/javac" "$PLATFORM" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar"; do
  [ -e "$f" ] || { echo "MISSING DEP: $f" >&2; exit 1; }
done
if [ "$BUILD_TYPE" = debug ] && [ ! -e "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  "$JDK/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  echo "      生成 debug keystore：$KS"
fi
[ "$UNSIGNED" = "1" ] || [ -e "$KS" ] || { echo "MISSING DEP: keystore $KS" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
# 伴侣连接：Manifest 占位符 ${assistantAppId} → 配对的助手包名（debug 构建配 debug 助手，release 配 release；ASSISTANT_APP_ID 可覆盖）
if [ "$BUILD_TYPE" = debug ]; then ASSISTANT_APP_ID="${ASSISTANT_APP_ID:-com.onesay.assistant.debug}"; else ASSISTANT_APP_ID="${ASSISTANT_APP_ID:-com.onesay.assistant}"; fi
sed "s#\${assistantAppId}#$ASSISTANT_APP_ID#g" "$ROOT/app/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
grep -q '\${' "$OUT/AndroidManifest.xml" && { echo "Manifest 里还有没填的占位符" >&2; exit 1; }
echo "      伴侣连接：助手 = $ASSISTANT_APP_ID"
echo "[1/7] aapt2 compile ($BUILD_TYPE)"; "$AAPT2" compile --dir "$ROOT/app/res" -o "$OUT/res.zip"
echo "[2/7] aapt2 link"
ASSETS=(); [ -d "$ROOT/app/assets" ] && ASSETS=(-A "$ROOT/app/assets")
"$AAPT2" link -o "$OUT/base.apk" -I "$PLATFORM" --manifest "$OUT/AndroidManifest.xml" --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 26 "${ASSETS[@]}" "${LINK_EXTRA[@]}" --auto-add-overlay "$OUT/res.zip"
echo "[3/7] javac"
find "$ROOT/app/src" "$OUT/gen" -name "*.java" | sort > "$OUT/sources.txt"
"$JDK/javac" -encoding UTF-8 -Xlint:-options -Xlint:deprecation -source 1.7 -target 1.7 -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
  -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1 || { cat "$OUT/javac.log"; echo "javac FAILED" >&2; exit 1; }
grep -c "warning" "$OUT/javac.log" | sed 's/^/      javac 警告 /' || true
echo "[4/7] d8"
D8_MODE=--release; [ "$BUILD_TYPE" = debug ] && D8_MODE=--debug
( cd "$OUT/classes" && "$JDK/jar" cf "$OUT/classes.jar" . )
"$JDK/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 $D8_MODE --min-api 24 --lib "$PLATFORM" --output "$OUT/dex" "$OUT/classes.jar"
cp "$OUT/base.apk" "$OUT/unsigned.apk"; ( cd "$OUT" && "$JDK/jar" uf unsigned.apk -C dex classes.dex )
echo "[5/7] zipalign"; "$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ "$UNSIGNED" = "1" ]; then
  cp "$OUT/aligned.apk" "$OUT/$APK_NAME"; echo "[6-7/7] UNSIGNED=1：跳过签名"; CERT="unsigned"
else
echo "[6/7] sign (alias $KS_ALIAS)"
"$JDK/java" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "$PASS_SRC" --out "$OUT/$APK_NAME" "$OUT/aligned.apk"
echo "[7/7] verify"
CERT="$("$JDK/java" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify --print-certs "$OUT/$APK_NAME" | grep -m1 'SHA-256 digest' | awk '{print $NF}')"
if [ "$BUILD_TYPE" = release ]; then
  [ -z "$EXPECT_CERT" ] || [ "$CERT" = "$EXPECT_CERT" ] || { echo "证书不对：${CERT:0:8}…（应为 ${EXPECT_CERT:0:8}…；fork 请设置 EXPECT_CERT）" >&2; exit 3; }
fi
"$ZIPALIGN" -c -p 4 "$OUT/$APK_NAME" && echo "zipalign OK"
rm -f "$OUT/$APK_NAME.idsig"
fi
echo "cert ${CERT:0:8}…${CERT: -8}"
echo "OUTPUT: $OUT/$APK_NAME ($(stat -c%s "$OUT/$APK_NAME") bytes) sha256 $(sha256sum "$OUT/$APK_NAME" | cut -c1-64)"
