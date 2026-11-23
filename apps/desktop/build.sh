#!/bin/sh
# 构建与单测：需要 JDK 17+。产物 build/onesay-desktop.jar（[workflow-1120] 依赖按 deps/java.lock 下载校验后解进同一个 jar）。
# 用法：sh build.sh [test]
set -e
cd "$(dirname "$0")"
OUT=${BUILD_DIR:-build}
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
DEPS_DIR=${DEPS_DIR:-$OUT/deps}; export DEPS_DIR
sh deps/fetch.sh >/dev/null
DEPCP=$(grep -v '^#' deps/java.lock | while read -r p s; do [ -n "$p" ] && printf '%s:' "$DEPS_DIR/$(basename "$p")"; done)
javac --release 17 -encoding UTF-8 -cp "$DEPCP" -d "$OUT/classes" $(find src/main/java -name '*.java')
cp -r src/main/resources/. "$OUT/classes/"
sh deps/unpack.sh "$OUT/classes"
printf 'Main-Class: com.onesay.desktop.Main\nImplementation-Version: 0.5.1\n' > "$OUT/MANIFEST.MF"
jar --create --file "$OUT/onesay-desktop.jar" --manifest "$OUT/MANIFEST.MF" -C "$OUT/classes" .
echo "built $OUT/onesay-desktop.jar"
if [ "$1" = test ]; then
  LIBS=${TEST_LIBS:?set TEST_LIBS to a folder with junit-4.13.2.jar and hamcrest-core-1.3.jar}
  CP="$OUT/classes:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar"
  mkdir -p "$OUT/test-classes"
  javac --release 17 -encoding UTF-8 -cp "$CP" -d "$OUT/test-classes" $(find src/test/java -name '*.java')
  java -Djava.awt.headless=true -cp "$CP:$OUT/test-classes" org.junit.runner.JUnitCore com.onesay.desktop.CoreTest com.onesay.desktop.LanTest com.onesay.desktop.VoiceTest com.onesay.desktop.AppTest com.onesay.desktop.LinkV2Test com.onesay.desktop.PcGuardTest com.onesay.desktop.ShortcutGuardTest com.onesay.desktop.PcPluginTest com.onesay.desktop.Round2Test com.onesay.desktop.ApprovalGuardTest com.onesay.desktop.ApproveNotBlockTest com.onesay.desktop.MediaGetTest com.onesay.desktop.ConsoleAlignTest com.onesay.desktop.IntegTest com.onesay.desktop.BrandedHostTest com.onesay.desktop.AdvSuite com.onesay.desktop.SyncFix1114Test com.onesay.desktop.AppWhitelistSharedTest com.onesay.desktop.WhitelistDesktopTest com.onesay.desktop.WhitelistBrandTest com.onesay.desktop.SecM6ConfigMigrationTest com.onesay.desktop.ExeHandoffTest com.onesay.desktop.ChatAgent1118Test com.onesay.desktop.Approvals1118Test com.onesay.desktop.DevTools1118Test com.onesay.desktop.Overnight1119Test com.onesay.desktop.PhoneMirror1118Test com.onesay.desktop.Link1119E2ETest com.onesay.desktop.Misc7Test1119 com.onesay.desktop.Preview1120AuditTest com.onesay.desktop.McpTest com.onesay.desktop.Preview1120UiTest com.onesay.desktop.Preview1120FixTest com.onesay.desktop.Preview1120DeviceTest com.onesay.desktop.Preview1120Device2Test com.onesay.desktop.AlwaysOn1120Test com.onesay.desktop.Fix1120EveTest com.onesay.desktop.Workflow1120Test com.onesay.desktop.Dual1122Test com.onesay.desktop.Fix1123ATest com.onesay.desktop.Pair1123Test com.onesay.desktop.Fix1123cTest com.onesay.desktop.Merge1123Test com.onesay.desktop.LongCtx1123Test com.onesay.desktop.Sec1123DesktopTest com.onesay.desktop.Misc1123Test com.onesay.desktop.Dui1123Test
fi
