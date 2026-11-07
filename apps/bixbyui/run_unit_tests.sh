#!/usr/bin/env bash
# ============================================================
# OneSay JVM 单元测试（不需要手机/模拟器）
#
#   bash run_unit_tests.sh                 # 编译 + 跑 test-jvm/src 下全部 *Test.java
#   bash run_unit_tests.sh NumberParserTest  # 只跑指定测试类（可写简单类名或全限定名，可多个）
#   BIXBY_TEST_LOG=1 bash run_unit_tests.sh  # 打印生产代码里的 android.util.Log 输出
#
# 原理：
#   1) 生产类（test-jvm/prod-classes.txt 列出）按 APK 同口径编译：-source/-target 1.7，
#      -bootclasspath android-28/android.jar，-sourcepath app/src（依赖自动带上）。
#   2) shims（android.util.Log、android.icu.text.Transliterator）单独编译，
#      运行时排在 android.jar 之前，替换掉会抛 "Stub!" 的桩实现。
#   3) 测试用 JUnit 4.13.2 编译运行；org.json 用 Android 原版实现的独立 jar（vaadin android-json），
#      同样排在 android.jar 之前。android.jar 放 classpath 最后，只为让引用到的 android 类型能被加载。
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${TOOLS:-$HOME/onesay-tools}"
export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk}"
JDK="$JAVA_HOME/bin"
PLATFORM="${SDK:-$TOOLS/android-sdk}/platforms/android-28/android.jar"
LIBS="$TOOLS/test-libs"
JUNIT="$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar"
JSON="$LIBS/android-json-0.0.20131108.vaadin1.jar"
ICU="$LIBS/icu4j-72.1.jar"
MAIL="$ROOT/vendor/mail/android-mail-1.6.5.jar:$ROOT/vendor/mail/android-activation-1.6.5.jar"
T="$ROOT/test-jvm"
B="${BIXBY_TEST_BUILD:-$T/build}"   # 可用 BIXBY_TEST_BUILD 把编译输出放到别处（如 /tmp）

for f in "$JDK/javac" "$PLATFORM" "$LIBS/junit-4.13.2.jar" "$LIBS/hamcrest-core-1.3.jar" "$JSON" "$ICU"; do
  [ -e "$f" ] || { echo "MISSING DEP: $f" >&2; exit 1; }
done

# AgentLoop 调用 vision/screenref/ScreenRefHooks，后者的面板侧挂钩引用
#   FloatingSheetService → WakeService → sherpa-onnx。javac -implicit 会把它们一并编译，所以**仅编译期**
#   需要 sherpa 的 Java 层（运行期测试不会加载这些类）。查找顺序与 build_linux.sh 的 VENDOR 一致。
# BuildVariant（编译期常量）按构建变体放在 app/variant/<变体>/；单测按 release 口径编译
#   （debug 一侧的常量由 BuildVariantTest 直接读源文件比对）。
#LocalDefaults 单测固定用随仓库的空默认值（app/config），结果与本机私有配置无关。
SRCPATH="$ROOT/app/src:$ROOT/app/variant/release:$ROOT/app/config"
# OneSay monorepo：共享插件接口与插件
ONESAY_LIBS="${ONESAY_LIBS:-$ROOT/../../libs}"
for d in "$ONESAY_LIBS"/plugin-api/src "$ONESAY_LIBS"/plugins/*/src; do [ -d "$d" ] && SRCPATH="$SRCPATH:$d"; done
SHERPA_CP=""
for v in "${VENDOR:-}" "$ROOT/vendor" "$ROOT/../bixbyui/vendor" "$TOOLS/bixbyui-vendor"; do
  [ -n "$v" ] || continue
  if [ -d "$v/sherpa/java" ]; then SRCPATH="$SRCPATH:$v/sherpa/java"; break; fi
  if [ -f "$v/sherpa/sherpa-onnx-classes.jar" ]; then SHERPA_CP=":$v/sherpa/sherpa-onnx-classes.jar"; break; fi
done

if [ -z "${BIXBY_TEST_SKIP_COMPILE:-}" ]; then
rm -rf "$B"; mkdir -p "$B/prod" "$B/shims" "$B/test"

echo "[1/4] compile production classes (-source 1.7, android-28 bootclasspath)"
grep -vE '^\s*(#|$)' "$T/prod-classes.txt" | sed "s#^#$ROOT/app/src/#" > "$B/prod-sources.txt"
"$JDK/javac" -encoding UTF-8 -source 1.7 -target 1.7 -Xlint:-options -nowarn \
  -bootclasspath "$PLATFORM" -classpath "$PLATFORM:$MAIL$SHERPA_CP" \
  -sourcepath "$SRCPATH" -implicit:class \
  -d "$B/prod" @"$B/prod-sources.txt"
echo "      $(find "$B/prod" -name '*.class' | wc -l) classes"

echo "[2/4] compile shims"
find "$T/shims" -name "*.java" > "$B/shim-sources.txt"
"$JDK/javac" -encoding UTF-8 -classpath "$ICU" -d "$B/shims" @"$B/shim-sources.txt"

echo "[3/4] compile tests"
find "$T/src" -name "*.java" > "$B/test-sources.txt"
"$JDK/javac" -encoding UTF-8 -Xlint:-options \
  -classpath "$B/prod:$JUNIT:$MAIL:$PLATFORM" \
  -d "$B/test" @"$B/test-sources.txt"

fi
[ -n "${BIXBY_TEST_COMPILE_ONLY:-}" ] && { echo "compile only: $B"; exit 0; }
echo "[4/4] run JUnit"
if [ $# -gt 0 ]; then
  CLASSES=()
  for a in "$@"; do
    if [[ "$a" == *.* ]]; then CLASSES+=("$a")
    else
      m="$(cd "$B/test" && find . -name "$a.class" | sed 's#^\./##; s#\.class$##; s#/#.#g' | head -1)"
      [ -n "$m" ] || { echo "no test class: $a" >&2; exit 2; }
      CLASSES+=("$m")
    fi
  done
else
  mapfile -t CLASSES < <(cd "$B/test" && find . -name "*Test.class" | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)
fi
# 运行时 classpath 顺序很重要：shims / android-json 必须在 android.jar 之前
RCP="$B/test:$B/prod:$B/shims:$JSON:$ICU:$JUNIT:$MAIL:$PLATFORM"
"$JDK/java" -Dfile.encoding=UTF-8 -Dbixby.root="$ROOT" ${JAVA_OPTS:-} -cp "$RCP" org.junit.runner.JUnitCore "${CLASSES[@]}"
