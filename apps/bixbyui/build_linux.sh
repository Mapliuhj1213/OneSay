#!/usr/bin/env bash
# ============================================================
# OneSay 纯命令行构建脚本 —— Linux 版（与 build.sh 步骤一一对应）
#
# 适配环境：Linux（x86_64 或 aarch64）/ JDK 17（-source/-target 1.7 + android-28 bootclasspath）
#          / Android SDK platform android-28 / build-tools 30.0.3（d8.jar、apksigner.jar）
#          / aapt2 + zipalign：x86_64 主机用 build-tools 30.0.3 自带的；
#            aarch64 主机用 $TOOLS/native-aarch64（lzhiyong/android-sdk-tools 35.0.2 静态版）
#
# 可用环境变量覆盖：
#   TOOLS     工具根目录          默认 $HOME/onesay-tools
#   JAVA_HOME JDK                 默认 $TOOLS/jdk
#   SDK       Android SDK         默认 $TOOLS/android-sdk
#   VENDOR    sherpa（+mail）     默认 ./vendor（含 sherpa/java 源码时）→ ../bixbyui/vendor → $TOOLS/bixbyui-vendor 重建版
#   （Vosk/JNA 已从构建中移除 —— 代码零引用）
#   OUT       输出目录            release 默认 ./out-linux（不覆盖 Windows 构建的 ./out）；debug 默认 ./out-debug
#   KS / KS_PASS / KEY_PASS / KS_PASS_FILE / KEY_PASS_FILE   签名 keystore 与口令
#             release 默认工作区里的 bixbyui.keystore；debug 默认工作区里的 debug.keystore（两者都不进 git）
#             口令**不再有明文默认值**。按顺序取：
#               ① 环境变量 KS_PASS（apksigner 经 env: 读取，不出现在命令行）；
#               ② KS_PASS_FILE 指向的文件；③ 与 keystore 同名的本地未跟踪文件 "<KS>.pass"（如 debug.keystore.pass）；
#               都没有 → 报错退出。KEY_PASS / KEY_PASS_FILE 同理，缺省与 store 口令相同。
#   UNSIGNED  =1 时不签名，只输出 zipalign 后的未签名 APK（审查 / CI 用；不需要 keystore）
#   DEBUG     0（默认，release）/ 1（debug）
#             独立 debug 变体：DEBUG=1 产出可与 release 同时安装的包 ——
#               applicationId com.onesay.assistant.debug（aapt2 --rename-manifest-package；Java 包名不变）、
#               应用名「OneSay Debug」+ 橙底「D」角标图标（app/res-debug 覆盖层）、签名级权限 / 测试广播 action 带 .debug、
#               用 debug.keystore 签名、输出 out-debug/bixbyui-debug.apk；代码里的变体差异只经 app/variant/<变体>/BuildVariant
#               编译期常量注入（release 的 dex 因此与引入前逐字节一致）。DEBUG=0 的产物与之前逐项一致。
#             release 不编译 app/src/com/onesay/assistant/v2/test/**（V2SelfTest、TestRunReceiver），
#             改用 app/src-release 里的 V2SelfTest 占位，并从 Manifest 删除 DEBUG-ONLY 段（TestRunReceiver）。
#             DEBUG=1 时两者都打进 APK（TestRunReceiver 受签名级权限保护，adb 需 su 发广播）。
#   TEST_RECEIVER_OPEN  仅 DEBUG=1 时有效：=1 去掉 TestRunReceiver 的权限（兼容不用 su 的旧 adb 脚本，切勿用于日常包）
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${TOOLS:-$HOME/onesay-tools}"
export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk}"
JDK="$JAVA_HOME/bin"
SDK="${SDK:-$TOOLS/android-sdk}"
BT="$SDK/build-tools/30.0.3"
PLATFORM="$SDK/platforms/android-28/android.jar"
DEBUG="${DEBUG:-0}"
if [ "$DEBUG" = "1" ]; then
  # debug 变体：包名 / 输出目录 / 签名全部独立。release 分支下面这几个默认值与之前完全一样。
  APP_ID="com.onesay.assistant.debug"
  OUT="${OUT:-$ROOT/out-debug}"
  KS="${KS:-$ROOT/debug.keystore}"
  APK_NAME="bixbyui-debug.apk"
else
  APP_ID="com.onesay.assistant"
  OUT="${OUT:-$ROOT/out-linux}"
  KS="${KS:-$ROOT/bixbyui.keystore}"
  APK_NAME="bixbyui.apk"
fi
# 签名口令只从环境变量 / 本地未跟踪文件读，缺失即报错（不再有明文默认值）。
#   输出 apksigner / keytool 能直接用的口令来源：env:VAR 或 file:PATH（口令本身不进命令行、不进日志）。
resolve_pass() {  # $1=环境变量名 $2=口令文件变量名 $3=默认口令文件路径（可空）
  local var="$1" filevar="$2" deffile="$3" f
  if [ -n "${!var:-}" ]; then echo "env:$var"; return 0; fi
  f="${!filevar:-}"
  if [ -n "$f" ]; then
    [ -r "$f" ] || { echo "$filevar=$f 读不到" >&2; return 1; }
    echo "file:$f"; return 0
  fi
  if [ -n "$deffile" ] && [ -r "$deffile" ]; then echo "file:$deffile"; return 0; fi
  return 1
}
#别人 clone 后没有任何私有文件也能出 debug 包——显式 GEN_DEBUG_KEYSTORE=1 时，在 KS 指向的位置
#   生成一把本机自用的 debug keystore（随机口令写进同名 .pass，两者都被 .gitignore 挡住）。已有 keystore 时什么都不做；
#   release 永远不自动生成。
if [ "$DEBUG" = "1" ] && [ "${GEN_DEBUG_KEYSTORE:-0}" = "1" ] && [ ! -e "$KS" ]; then
  echo "== 生成本机 debug keystore：$KS（口令在 $KS.pass，不进 git）"
  ( umask 077; head -c 24 /dev/urandom | base64 | tr -d '/+=\n' > "$KS.pass" )
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storetype JKS -alias bixbyui-debug -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=OneSay local debug" -storepass:file "$KS.pass" -keypass:file "$KS.pass" >/dev/null 2>&1 \
    || { echo "生成 debug keystore 失败" >&2; rm -f "$KS" "$KS.pass"; exit 2; }
fi
UNSIGNED="${UNSIGNED:-0}"
if [ "$UNSIGNED" != "1" ]; then
KS_PASS_SRC="$(resolve_pass KS_PASS KS_PASS_FILE "$KS.pass")" || {
  echo "缺少签名口令：请设置环境变量 KS_PASS，或 KS_PASS_FILE=<口令文件>，或在 keystore 旁放本地未跟踪的 $(basename "$KS").pass（只含口令一行）" >&2
  exit 2
}
KEY_PASS_SRC="$(resolve_pass KEY_PASS KEY_PASS_FILE "")" || KEY_PASS_SRC="$KS_PASS_SRC"
fi
MAIL1="$ROOT/vendor/mail/android-mail-1.6.5.jar"
MAIL2="$ROOT/vendor/mail/android-activation-1.6.5.jar"

if [ -z "${VENDOR:-}" ]; then
  if [ -d "$ROOT/vendor/sherpa/java" ]; then VENDOR="$ROOT/vendor"
  elif [ -d "$ROOT/../bixbyui/vendor" ]; then VENDOR="$(cd "$ROOT/../bixbyui/vendor" && pwd)"
  else VENDOR="$TOOLS/bixbyui-vendor"; fi
fi

# aapt2 / zipalign：按主机架构挑选（build-tools 30.0.3 的 Linux 原生二进制只有 x86_64）
case "$(uname -m)" in
  x86_64|amd64) AAPT2="$BT/aapt2"; ZIPALIGN="$BT/zipalign" ;;
  *)            AAPT2="${AAPT2:-$TOOLS/native-aarch64/build-tools/aapt2}"
                ZIPALIGN="${ZIPALIGN:-$TOOLS/native-aarch64/build-tools/zipalign}" ;;
esac

# sherpa-onnx Java 层：原工程用源码（$VENDOR/sherpa/java）；重建版只有从旧产物中恢复的 .class（打成 jar）
SHERPA_SRC="$VENDOR/sherpa/java"
SHERPA_JAR="$VENDOR/sherpa/sherpa-onnx-classes.jar"

for f in "$AAPT2" "$ZIPALIGN" "$JDK/javac" "$JDK/jar" "$JDK/java" "$PLATFORM" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" \
         "$MAIL1" "$MAIL2" \
         "$VENDOR/sherpa/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$VENDOR/sherpa/jniLibs/arm64-v8a/libonnxruntime.so"; do
  [ -e "$f" ] || { echo "MISSING DEP: $f" >&2; exit 1; }
done
if [ ! -d "$SHERPA_SRC" ] && [ ! -f "$SHERPA_JAR" ]; then
  echo "MISSING DEP: $SHERPA_SRC (或 $SHERPA_JAR)" >&2; exit 1
fi

echo "== JDK: $("$JDK/java" -version 2>&1 | head -1)"
echo "== aapt2: $AAPT2"
echo "== VENDOR: $VENDOR"
echo "== OUT: $OUT"
TEST_RECEIVER_OPEN="${TEST_RECEIVER_OPEN:-0}"
if [ "$DEBUG" = "1" ]; then VARIANT="debug"; else VARIANT="release"; fi
if [ "$UNSIGNED" != "1" ]; then
[ -e "$KS" ] || { echo "MISSING DEP: $KS" >&2; exit 1; }
# debug 用 debug.keystore、release 用 bixbyui.keystore：防止把两把钥匙签反（显式 KS=… 的除外，自己负责）
case "$VARIANT:$(basename "$KS")" in
  release:debug.keystore) echo "release 不能用 debug.keystore 签名" >&2; exit 2 ;;
  debug:bixbyui.keystore) echo "debug 变体不能用 release 的 bixbyui.keystore 签名（两个包要同时安装，签名必须不同）" >&2; exit 2 ;;
esac
KT_PASS_ARG="-storepass:${KS_PASS_SRC%%:*}"; KT_PASS_VAL="${KS_PASS_SRC#*:}"
KS_SHA256="$("$JDK/keytool" -list -v -keystore "$KS" "$KT_PASS_ARG" "$KT_PASS_VAL" 2>/dev/null \
  | grep -m1 -E '^[[:space:]]*SHA256:' | sed 's/.*SHA256:[[:space:]]*//; s/://g' | tr 'A-F' 'a-f')" || true
[ -n "$KS_SHA256" ] || { echo "读不出 $KS 的证书指纹（口令不对或文件损坏）" >&2; exit 2; }
echo "== signer: $(basename "$KS") SHA-256 ${KS_SHA256:0:8}…${KS_SHA256: -8}"
fi
[ "$DEBUG" != "1" ] && [ "$TEST_RECEIVER_OPEN" = "1" ] && { echo "TEST_RECEIVER_OPEN=1 只能和 DEBUG=1 一起用" >&2; exit 2; }
echo "== VARIANT: $VARIANT$([ "$TEST_RECEIVER_OPEN" = "1" ] && echo ' (TestRunReceiver 无权限保护!)')  applicationId=$APP_ID"

mkdir -p "$OUT"
find "$OUT" -mindepth 1 -delete
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/8] aapt2 compile (resources -> flat zip)"
"$AAPT2" compile --dir "$ROOT/app/res" -o "$OUT/res.zip"
if [ "$DEBUG" = "1" ]; then
  # debug 覆盖层：@color/icbg 换色、@mipmap/ic_launcher 换成带「D」角标的前景（release 不编译这一目录）
  "$AAPT2" compile --dir "$ROOT/app/res-debug" -o "$OUT/res-debug.zip"
fi

echo "[2/8] aapt2 link    (manifest + resources + assets)"
# 生成本次构建用的 Manifest：release 删除 DEBUG-ONLY 段；debug 可选去掉测试接收器的权限
MANIFEST="$OUT/AndroidManifest.xml"
if [ "$DEBUG" = "1" ]; then
  if [ "$TEST_RECEIVER_OPEN" = "1" ]; then
    sed 's#android:permission="com.onesay.assistant.permission.CONTROL"><!-- TEST-RECEIVER-PERMISSION -->#>#' \
      "$ROOT/app/AndroidManifest.xml" > "$MANIFEST.src"
  else
    cp "$ROOT/app/AndroidManifest.xml" "$MANIFEST.src"
  fi
  # debug 变体隔离：
  #   · 签名级权限 com.onesay.assistant.permission.CONTROL → com.onesay.assistant.debug.permission.CONTROL（声明与所有引用一起改）。
  #     两个包签名不同，若声明同名权限，后装的那个会 INSTALL_FAILED_DUPLICATE_PERMISSION；
  #   · 测试广播 action com.onesay.assistant.action.RUN_TEST → com.onesay.assistant.debug.action.RUN_TEST；
  #   · 应用名 / 控制台标签 → OneSay Debug；图标由 res-debug 覆盖层负责，这里不动 android:icon。
  #   package 属性不在这里改：交给 aapt2 --rename-manifest-package（同时把相对类名补全成 com.onesay.assistant.*）。
  sed -e 's#com\.onesay\.assistant\.permission\.CONTROL#com.onesay.assistant.debug.permission.CONTROL#g' \
      -e 's#com\.onesay\.assistant\.action\.RUN_TEST#com.onesay.assistant.debug.action.RUN_TEST#g' \
      -e 's#com\.onesay\.assistant\.permission\.SUMMON#com.onesay.assistant.debug.permission.SUMMON#g' \
      -e 's#com\.onesay\.assistant\.action\.SUMMON#com.onesay.assistant.debug.action.SUMMON#g' \
      -e 's#android:label="OneSay 控制台"#android:label="OneSay Debug 控制台"#' \
      -e 's#android:label="OneSay"#android:label="OneSay Debug"#' \
      -e 's#android:label="控制 OneSay"#android:label="控制 OneSay Debug"#' \
      "$MANIFEST.src" > "$MANIFEST"
  rm -f "$MANIFEST.src"
  if grep -qE 'com\.onesay\.assistant\.(permission|action)\.' "$MANIFEST"; then
    echo "debug Manifest 里还有不带 .debug 的权限 / action：" >&2; grep -nE 'com\.onesay\.assistant\.(permission|action)\.' "$MANIFEST" >&2; exit 1
  fi
  grep -q 'android:label="OneSay Debug"' "$MANIFEST" || { echo "debug Manifest 应用名没换成 OneSay Debug" >&2; exit 1; }
else
  sed '/DEBUG-ONLY-BEGIN/,/DEBUG-ONLY-END/d' "$ROOT/app/AndroidManifest.xml" > "$MANIFEST"
  if grep -q "com.onesay.assistant.v2.test.TestRunReceiver" "$MANIFEST"; then echo "release Manifest 仍含 TestRunReceiver" >&2; exit 1; fi
fi
# assets：release 不带只给 debug / 开发者用的文件——
#   test_cmd.wav 只有 test/TestRunReceiver（release 不编译）读；kws_keywords.README.txt 是词表说明文档，运行期没人读。
#   test_wake.wav 保留（控制台「开发者」页「播放测试音频」在 release 也有）。debug 包照旧带全部 assets。
ASSETS_DIR="$ROOT/app/assets"
if [ "$DEBUG" != "1" ]; then
  ASSETS_DIR="$OUT/assets"
  mkdir -p "$ASSETS_DIR"
  cp -p "$ROOT/app/assets/"* "$ASSETS_DIR/"
  rm -f "$ASSETS_DIR/test_cmd.wav" "$ASSETS_DIR/kws_keywords.README.txt"
fi
LINK_VARIANT=()
if [ "$DEBUG" = "1" ]; then
  # applicationId 改成 com.onesay.assistant.debug；R / Manifest 类仍生成在 com.onesay.assistant（Java 包名不变）
  LINK_VARIANT=(--rename-manifest-package "$APP_ID" -R "$OUT/res-debug.zip")
fi
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$MANIFEST" \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 28 \
  -A "$ASSETS_DIR" \
  --auto-add-overlay \
  ${LINK_VARIANT[@]+"${LINK_VARIANT[@]}"} \
  "$OUT/res.zip"

echo "[3/8] javac        (Java 7 syntax, android-28 bootclasspath)"
SRC_DIRS=("$ROOT/app/src" "$OUT/gen")
# OneSay monorepo：共享插件接口与插件（libs/plugin-api、libs/plugins/*）编进 App。ONESAY_LIBS 可覆盖位置。
ONESAY_LIBS="${ONESAY_LIBS:-$ROOT/../../libs}"
for d in "$ONESAY_LIBS"/plugin-api/src "$ONESAY_LIBS"/plugins/*/src; do [ -d "$d" ] && SRC_DIRS+=("$d"); done
CP="$PLATFORM:$MAIL1:$MAIL2"
SHERPA_DEX_INPUT=()
if [ -d "$SHERPA_SRC" ]; then
  SRC_DIRS+=("$SHERPA_SRC")
else
  CP="$CP:$SHERPA_JAR"
  SHERPA_DEX_INPUT=("$SHERPA_JAR")   # 源码缺失时，sherpa 的 .class 作为独立输入并入主 dex（与源码编译结果等价）
fi
if [ "$DEBUG" = "1" ]; then
  find "${SRC_DIRS[@]}" -name "*.java" > "$OUT/sources.txt"
else
  # release：剔除测试包，换成 src-release 里的占位类
  { find "$ROOT/app/src" -path "$ROOT/app/src/com/onesay/assistant/v2/test" -prune -o -name "*.java" -print
    for d in "${SRC_DIRS[@]:1}"; do find "$d" -name "*.java"; done
    find "$ROOT/app/src-release" -name "*.java"; } > "$OUT/sources.txt"
fi
# 变体常量（只含编译期常量，见 app/variant/*/com/onesay/assistant/v2/BuildVariant.java）
find "$ROOT/app/variant/$VARIANT" -name "*.java" >> "$OUT/sources.txt"
#本机 / 本账号私有默认值（LocalDefaults，只含编译期常量）。优先 $LOCAL_CONFIG → ./local-config（未跟踪）
#   → app/config（随仓库的空默认值）。release 缺私有值时默认报错，避免悄悄换掉手机上的行为；ALLOW_EMPTY_LOCAL_DEFAULTS=1 放行。
LD_REL="com/onesay/assistant/v2/LocalDefaults.java"
if [ -n "${LOCAL_CONFIG:-}" ] && [ -f "$LOCAL_CONFIG/$LD_REL" ]; then LD_SRC="$LOCAL_CONFIG/$LD_REL"
elif [ -f "$ROOT/local-config/$LD_REL" ]; then LD_SRC="$ROOT/local-config/$LD_REL"
else
  LD_SRC="$ROOT/app/config/$LD_REL"
  [ "$VARIANT" = "release" ] && echo "== 提示：未找到 local-config/，用 app/config 的开源空默认值（可复制 local-config.example/ 自定义）"
fi
echo "== LocalDefaults: ${LD_SRC#$ROOT/}"
echo "$LD_SRC" >> "$OUT/sources.txt"
# feat/shortcuts：快捷指令构建开关（ShortcutBuild.PRIVILEGED_ACTIONS：上机版专属动作）。同样的查找顺序；
#   缺私有文件时用 app/config 的开源默认值（false = 需要 root 的系统开关动作置灰），release 也不报错。
SB_REL="com/onesay/assistant/v2/shortcut/ShortcutBuild.java"
if [ -n "${LOCAL_CONFIG:-}" ] && [ -f "$LOCAL_CONFIG/$SB_REL" ]; then SB_SRC="$LOCAL_CONFIG/$SB_REL"
elif [ -f "$ROOT/local-config/$SB_REL" ]; then SB_SRC="$ROOT/local-config/$SB_REL"
else SB_SRC="$ROOT/app/config/$SB_REL"; fi
echo "== ShortcutBuild: ${SB_SRC#$ROOT/}"
echo "$SB_SRC" >> "$OUT/sources.txt"
# 与 build.sh 一致用 -nowarn；JDK 17 对 -source 7 的「已过时」提示用 -Xlint:-options 关掉。
# 完整输出写入 $OUT/javac.log。设置 LINT=1 可打开 -Xlint:all 看全部警告。
JAVAC_FLAGS=(-encoding UTF-8 -source 1.7 -target 1.7 -Xlint:-options)
if [ "${LINT:-0}" = "1" ]; then JAVAC_FLAGS+=(-Xlint:all -Xmaxwarns 10000); else JAVAC_FLAGS+=(-nowarn); fi
set +e
"$JDK/javac" "${JAVAC_FLAGS[@]}" \
  -bootclasspath "$PLATFORM" \
  -classpath "$CP" \
  -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
JRC=$?
set -e
cat "$OUT/javac.log"
[ $JRC -eq 0 ] || { echo "JAVAC FAILED (exit $JRC), see $OUT/javac.log" >&2; exit $JRC; }

if [ "$DEBUG" != "1" ] && [ -e "$OUT/classes/com/onesay/assistant/v2/test/TestRunReceiver.class" ]; then
  echo "release 构建混入了 TestRunReceiver" >&2; exit 1
fi

# BuildVariant 只准被内联（编译期常量）。javac 会在引用方常量池里留一条用不到的 Class 条目（JDK 9+ 的依赖记录），
# 但字节码里不会有任何指令用它；所以先把 BuildVariant.class 从 dex 输入里拿掉，d8 之后再查 dex：
# 只要 dex 里出现 Lcom/onesay/assistant/v2/BuildVariant; 这个类型 = 有代码在运行期用它（非常量成员 / 当对象用），直接失败。
# release 的 classes.dex 因此与引入变体机制之前逐字节一致。
BV_CLASS="$OUT/classes/com/onesay/assistant/v2/BuildVariant.class"
[ -f "$BV_CLASS" ] || { echo "缺 BuildVariant.class（app/variant/$VARIANT 没编进来？）" >&2; exit 1; }
rm -f "$BV_CLASS"

echo "[4/8] d8           (bytecode -> dex, min-api 24)"
( cd "$OUT/classes" && "$JDK/jar" cf "$OUT/classes.jar" . )
"$JDK/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 24 --lib "$PLATFORM" --output "$OUT/dex" "$OUT/classes.jar" \
  ${SHERPA_DEX_INPUT[@]+"${SHERPA_DEX_INPUT[@]}"} "$MAIL1" "$MAIL2"

if grep -qa 'Lcom/onesay/assistant/v2/BuildVariant;' "$OUT/dex/classes.dex"; then
  echo "classes.dex 在运行期引用了 BuildVariant（app/variant/*/BuildVariant 只允许放编译期常量）" >&2; exit 1
fi

# [4b/8] 已删除：vosk + jna → classes2.dex。全工程无任何 org.vosk / com.sun.jna 引用，
#        sherpa-onnx 的 Java 层也不依赖 JNA（System.loadLibrary 直接加载 libsherpa-onnx-jni.so）。

echo "[5/8] pack          (classes.dex + native libs -> apk)"
mkdir -p "$OUT/jni/lib/arm64-v8a"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT" && "$JDK/jar" uf unsigned.apk -C dex classes.dex )
cp "$VENDOR/sherpa/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$OUT/jni/lib/arm64-v8a/"
cp "$VENDOR/sherpa/jniLibs/arm64-v8a/libonnxruntime.so" "$OUT/jni/lib/arm64-v8a/"
( cd "$OUT" && "$JDK/jar" uf unsigned.apk \
    -C jni lib/arm64-v8a/libsherpa-onnx-jni.so \
    -C jni lib/arm64-v8a/libonnxruntime.so )

echo "[6/8] zipalign"
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ "$UNSIGNED" = "1" ]; then
  APK_NAME="${APK_NAME%.apk}-unsigned.apk"; cp "$OUT/aligned.apk" "$OUT/$APK_NAME"; echo "[7-8/8] UNSIGNED=1：跳过签名"
else
echo "[7/8] sign"
APKSIGNER() { "$JDK/java" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool "$@"; }
# 口令来源是 env:VAR / file:PATH（见上面 resolve_pass），不再用 pass:明文。
# apksigner 对同一个 file: 会按行顺序读（第 1 行给 store、第 2 行给 key），所以 key 口令与 store 相同时不传 --key-pass
#   （apksigner 缺省即用 store 口令）。
KEY_PASS_ARGS=(); [ "$KEY_PASS_SRC" != "$KS_PASS_SRC" ] && KEY_PASS_ARGS=(--key-pass "$KEY_PASS_SRC")
APKSIGNER sign --ks "$KS" --ks-pass "$KS_PASS_SRC" "${KEY_PASS_ARGS[@]}" \
  --out "$OUT/$APK_NAME" "$OUT/aligned.apk"

echo "[8/8] verify"
APKSIGNER verify --verbose --print-certs "$OUT/$APK_NAME" | grep -E "Verified using|Signer #1 certificate (DN|SHA-256)"
"$ZIPALIGN" -c -p 4 "$OUT/$APK_NAME" && echo "zipalign check: OK"
fi
BADGING="$("$AAPT2" dump badging "$OUT/$APK_NAME" 2>/dev/null)"   # 不接 | head：pipefail 下 SIGPIPE 会让脚本以 141 退出
PKG_LINE="${BADGING%%$'\n'*}"
echo "badging: $PKG_LINE"
case "$PKG_LINE" in *"name='$APP_ID'"*) ;; *) echo "包名不是 $APP_ID" >&2; exit 1 ;; esac

# 构建后扫 git 索引，任何 keystore / 口令文件进了版本库就让构建失败。
bash "$ROOT/tools/ci/check_no_keystore.sh"

echo "$VARIANT" > "$OUT/BUILD_VARIANT"
echo "BUILD SUCCESS [$VARIANT]: $OUT/$APK_NAME ($(stat -c%s "$OUT/$APK_NAME") bytes)"
