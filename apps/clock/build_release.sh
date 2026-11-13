#!/usr/bin/env bash
# OneSay Clock（com.onesay.clock）release 构建：与 build_linux.sh 同一条 aapt2 → javac(1.7) → d8 → zipalign → apksigner 流水线，差别：
#   · 编 app/variant/release（BuildMode.RELEASE=true：ControlReceiver 忽略 TEST_ALARM/DEMO_ALARMS/BRIEF_DELETE，调试覆盖不读）
#   · d8 --release（不带调试信息）；javac -g:none
#   · 签名用你自己的 release keystore（KS=…，必填）；可选 RELEASE_SIGNER_SHA256=<64 位十六进制> 钉死证书指纹
#   · UNSIGNED=1：不签名，只输出 zipalign 后的未签名 APK（用于审查 / CI）
#   · 断言 APK 不可调试（无 android:debuggable / application-debuggable）
# 口令来源与 OneSay build_linux.sh 相同：① 环境变量 KS_PASS ② KS_PASS_FILE ③ keystore 旁未跟踪的 "<KS>.pass"；都没有就报错。
#   口令只以 env:/file: 形式交给 keytool / apksigner，不进命令行、不进日志、不进 git。KEY_PASS / KEY_PASS_FILE 同理，缺省同 store。
# 输出默认在 /tmp/bixbyclock-release/bixbyclock-release.apk（OUT=… 可改）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${TOOLS:-$HOME/onesay-tools}"
export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk}"
JDK="$JAVA_HOME/bin"
SDK="${SDK:-$TOOLS/android-sdk}"
BT="$SDK/build-tools/30.0.3"
PLATFORM="$SDK/platforms/android-28/android.jar"
OUT="${OUT:-/tmp/bixbyclock-release}"
UNSIGNED="${UNSIGNED:-0}"
[ "$UNSIGNED" = "1" ] || : "${KS:?设置 KS=<你的 release keystore 路径>，或 UNSIGNED=1}"
KS_ALIAS="${KS_ALIAS:-mb}"
# 开源版：证书指纹钉死改为可选（RELEASE_SIGNER_SHA256=<64 位十六进制>）；不设则不钉。三个 App 要互通签名级权限时必须用同一把钥匙。
RELEASE_SIGNER_SHA256="${RELEASE_SIGNER_SHA256:-}"
APK_NAME="bixbyclock-release.apk"

resolve_pass() {  # $1=环境变量名 $2=口令文件变量名 $3=默认口令文件路径（可空）→ 输出 env:VAR 或 file:PATH
  local var="$1" filevar="$2" deffile="$3" f
  if [ -n "${!var:-}" ]; then echo "env:$var"; return 0; fi
  f="${!filevar:-}"
  if [ -n "$f" ]; then [ -r "$f" ] || { echo "$filevar=$f 读不到" >&2; return 1; }; echo "file:$f"; return 0; fi
  if [ -n "$deffile" ] && [ -r "$deffile" ]; then echo "file:$deffile"; return 0; fi
  return 1
}
if [ "$UNSIGNED" != "1" ]; then
KS_PASS_SRC="$(resolve_pass KS_PASS KS_PASS_FILE "$KS.pass")" || { echo "缺少签名口令：设置 KS_PASS 或 KS_PASS_FILE，或放 $(basename "$KS").pass" >&2; exit 2; }
KEY_PASS_SRC="$(resolve_pass KEY_PASS KEY_PASS_FILE "")" || KEY_PASS_SRC="$KS_PASS_SRC"
fi

case "$(uname -m)" in
  x86_64|amd64) AAPT2="${AAPT2:-$BT/aapt2}"; ZIPALIGN="${ZIPALIGN:-$BT/zipalign}" ;;
  *)            AAPT2="${AAPT2:-$TOOLS/native-aarch64/build-tools/aapt2}"; ZIPALIGN="${ZIPALIGN:-$TOOLS/native-aarch64/build-tools/zipalign}" ;;
esac
for f in "$AAPT2" "$ZIPALIGN" "$JDK/javac" "$JDK/keytool" "$PLATFORM" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar"; do
  [ -e "$f" ] || { echo "MISSING DEP: $f" >&2; exit 1; }
done
grep -q 'android:debuggable' "$ROOT/app/AndroidManifest.xml" && { echo "Manifest 里有 android:debuggable，release 拒绝构建" >&2; exit 2; }

if [ "$UNSIGNED" != "1" ]; then
[ -e "$KS" ] || { echo "MISSING DEP: $KS" >&2; exit 1; }
# 可选的 keystore 指纹钉死（keytool 的 -storepass:env / -storepass:file 读口令）
case "$KS_PASS_SRC" in env:*) KT=(-storepass:env "${KS_PASS_SRC#env:}") ;; file:*) KT=(-storepass:file "${KS_PASS_SRC#file:}") ;; esac
KS_SHA256="$("$JDK/keytool" -list -v -keystore "$KS" -alias "$KS_ALIAS" "${KT[@]}" 2>/dev/null \
  | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | tr -d ':' | tr 'A-F' 'a-f')"
[ -n "$KS_SHA256" ] || { echo "读不出 $KS（别名 $KS_ALIAS）的证书指纹（口令不对或文件损坏）" >&2; exit 2; }
[ -z "$RELEASE_SIGNER_SHA256" ] || [ "$KS_SHA256" = "$RELEASE_SIGNER_SHA256" ] || { echo "拒绝签名：keystore 证书 ${KS_SHA256:0:8}… 不是 release 证书 ${RELEASE_SIGNER_SHA256:0:8}…" >&2; exit 2; }
echo "== signer: $(basename "$KS") alias=$KS_ALIAS SHA-256 ${KS_SHA256:0:8}…${KS_SHA256: -8}"
fi

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
echo "[1/8] aapt2 compile"; "$AAPT2" compile --dir "$ROOT/app/res" -o "$OUT/res.zip"
echo "[2/8] aapt2 link"
# 伴侣连接：Manifest 占位符 ${assistantAppId} → 配对的助手包名（ASSISTANT_APP_ID 可覆盖）
ASSISTANT_APP_ID="${ASSISTANT_APP_ID:-com.onesay.assistant}"
mkdir -p "$OUT"; sed "s#\${assistantAppId}#$ASSISTANT_APP_ID#g" "$ROOT/app/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
grep -q '\${' "$OUT/AndroidManifest.xml" && { echo "Manifest 里还有没填的占位符" >&2; exit 1; }
echo "== 伴侣连接：助手 = $ASSISTANT_APP_ID"
"$AAPT2" link -o "$OUT/base.apk" -I "$PLATFORM" --manifest "$OUT/AndroidManifest.xml" --java "$OUT/gen" \
  --min-sdk-version 23 --target-sdk-version 28 -A "$ROOT/app/assets" -0 ogg --auto-add-overlay "$OUT/res.zip"
echo "[3/8] javac (release variant)"
find "$ROOT/app/src" "$ROOT/app/variant/release" "$OUT/gen" -name "*.java" | sort > "$OUT/sources.txt"
"$JDK/javac" -encoding UTF-8 -nowarn -g:none -Xlint:-options -source 1.7 -target 1.7 -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | tee "$OUT/javac.log"
[ "${PIPESTATUS[0]}" = "0" ] || { echo "javac FAILED" >&2; exit 1; }
echo "[4/8] d8 --release"
( cd "$OUT/classes" && "$JDK/jar" cf "$OUT/classes.jar" . )
"$JDK/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 23 --lib "$PLATFORM" --output "$OUT/dex" "$OUT/classes.jar"
cp "$OUT/base.apk" "$OUT/unsigned.apk"; ( cd "$OUT" && "$JDK/jar" uf unsigned.apk -C dex classes.dex )
echo "[5/8] zipalign"; "$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ "$UNSIGNED" = "1" ]; then
  APK_NAME="bixbyclock-release-unsigned.apk"; cp "$OUT/aligned.apk" "$OUT/$APK_NAME"; echo "[6-7/8] UNSIGNED=1：跳过签名"
else
echo "[6/8] sign"
APKSIGNER() { "$JDK/java" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool "$@"; }
KEY_PASS_ARGS=(); [ "$KEY_PASS_SRC" != "$KS_PASS_SRC" ] && KEY_PASS_ARGS=(--key-pass "$KEY_PASS_SRC")
APKSIGNER sign --ks "$KS" --ks-key-alias "$KS_ALIAS" --ks-pass "$KS_PASS_SRC" "${KEY_PASS_ARGS[@]}" --out "$OUT/$APK_NAME" "$OUT/aligned.apk"
echo "[7/8] verify"
APKSIGNER verify --print-certs "$OUT/$APK_NAME" | grep -E "Signer #1 certificate (DN|SHA-256)"
"$ZIPALIGN" -c -p 4 "$OUT/$APK_NAME" && echo "zipalign OK"
fi
echo "[8/8] release 断言"
BADGING="$("$AAPT2" dump badging "$OUT/$APK_NAME" 2>/dev/null)"
echo "badging: ${BADGING%%$'\n'*}"
case "$BADGING" in *application-debuggable*) echo "APK 可调试，拒绝" >&2; exit 1 ;; esac
"$AAPT2" dump xmltree "$OUT/$APK_NAME" --file AndroidManifest.xml 2>/dev/null | grep -q debuggable && { echo "Manifest 带 debuggable，拒绝" >&2; exit 1; }
echo "debuggable: none"
echo "OUTPUT: $OUT/$APK_NAME ($(stat -c%s "$OUT/$APK_NAME") bytes) sha256 $(sha256sum "$OUT/$APK_NAME" | cut -c1-64)"
