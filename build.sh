#!/bin/sh
# 构建与单测：需要 JDK 17+。产物 build/onesay-desktop.jar（无第三方依赖）。
# 用法：sh build.sh [test]
set -e
cd "$(dirname "$0")"
OUT=${BUILD_DIR:-build}
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
javac --release 17 -encoding UTF-8 -d "$OUT/classes" $(find src/main/java -name '*.java')
cp -r src/main/resources/. "$OUT/classes/"
printf 'Main-Class: com.onesay.desktop.Main\nImplementation-Version: 0.5.1\n' > "$OUT/MANIFEST.MF"
jar --create --file "$OUT/onesay-desktop.jar" --manifest "$OUT/MANIFEST.MF" -C "$OUT/classes" .
echo "built $OUT/onesay-desktop.jar"
if [ "$1" = test ]; then
  LIBS=${TEST_LIBS:?set TEST_LIBS to a folder with junit-4.13.2.jar and hamcrest-core-1.3.jar}
  CP="$OUT/classes:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar"
  mkdir -p "$OUT/test-classes"
  javac --release 17 -encoding UTF-8 -cp "$CP" -d "$OUT/test-classes" $(find src/test/java -name '*.java')
  java -Djava.awt.headless=true -cp "$CP:$OUT/test-classes" org.junit.runner.JUnitCore com.onesay.desktop.CoreTest com.onesay.desktop.LanTest com.onesay.desktop.VoiceTest com.onesay.desktop.AppTest com.onesay.desktop.LinkV2Test com.onesay.desktop.PcGuardTest com.onesay.desktop.ShortcutGuardTest com.onesay.desktop.PcPluginTest
fi
