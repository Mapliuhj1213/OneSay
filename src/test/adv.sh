#!/bin/sh
# [adv-1114] 找茬式对抗测试（电脑端）。只编译 / 运行测试。AdvSuite 已在 build.sh 的默认清单里（fix-1114）。
# 用法：TEST_LIBS=<junit 目录> sh src/test/adv.sh [gate|bugs|all|<类名>...]
#   gate（默认）：AdvSuite —— 全部对抗用例，排除已知真 bug；应当全过
#   bugs        ：AdvFixedBugsTest —— 原来的 71 条已知真 bug（AdvKnownBugsTest），2026-11-14 修好后改名挪回门禁，现在应当全过
#   all         ：原有 159 条 + gate
set -e
cd "$(dirname "$0")/../.."
OUT=${BUILD_DIR:-build}
LIBS=${TEST_LIBS:?set TEST_LIBS to a folder with junit-4.13.2.jar and hamcrest-core-1.3.jar}
CP="$OUT/classes:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar"
rm -rf "$OUT/classes" "$OUT/test-classes" && mkdir -p "$OUT/classes" "$OUT/test-classes"
javac --release 17 -encoding UTF-8 -d "$OUT/classes" $(find src/main/java -name '*.java')
cp -r src/main/resources/. "$OUT/classes/"
javac --release 17 -encoding UTF-8 -cp "$CP" -d "$OUT/test-classes" $(find src/test/java -name '*.java')
OLD="com.onesay.desktop.CoreTest com.onesay.desktop.LanTest com.onesay.desktop.VoiceTest com.onesay.desktop.AppTest com.onesay.desktop.LinkV2Test com.onesay.desktop.PcGuardTest com.onesay.desktop.ShortcutGuardTest com.onesay.desktop.PcPluginTest com.onesay.desktop.Round2Test com.onesay.desktop.ApprovalGuardTest com.onesay.desktop.ApproveNotBlockTest com.onesay.desktop.MediaGetTest com.onesay.desktop.ConsoleAlignTest com.onesay.desktop.IntegTest com.onesay.desktop.BrandedHostTest"
case "${1:-gate}" in
  gate) CLASSES="com.onesay.desktop.AdvSuite" ;;
  bugs) CLASSES="com.onesay.desktop.AdvFixedBugsTest" ;;
  all) CLASSES="$OLD com.onesay.desktop.AdvSuite" ;;
  *) CLASSES=""; for c in "$@"; do CLASSES="$CLASSES com.onesay.desktop.$c"; done ;;
esac
exec java -Djava.awt.headless=true -cp "$CP:$OUT/test-classes" org.junit.runner.JUnitCore $CLASSES
