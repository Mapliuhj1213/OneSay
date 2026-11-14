#!/bin/bash
# 一条命令生成 OneSay 安装程序 OneSay-Setup-<版本>-x64.exe 及其 .sha256。
#
# 用法：
#   ONESAY_BASE=<上一版 zip 或已解开的 OneSay 目录> bash installer/build-installer.sh [输出目录]
#
# ONESAY_BASE 只提供 runtime\（jlink 精简 Temurin 17，在 Linux 上不重建，沿用上一版包里的）。
# jar 每次从源码构建；启动器 OneSay.exe（launcher/，C#）也从源码构建（launcher/build-launcher.sh，要 .NET 8 SDK，
#   DOTNET 可指定路径）。已经编好的可以用 ONESAY_LAUNCHER=<目录> 直接给（里面要有 build-launcher.sh 列出的 5 个文件）。
#   2026-11-14 起启动器在本进程里跑 Java（jvm.dll）并用 WebView2 开窗口，旧包里的 OneSay.exe 不能再用。
# 需要：JDK 17+（javac/jar，JAVA_HOME 或 PATH）、unzip、makensis 3.x（MAKENSIS 可指定路径）、.NET 8 SDK（或 ONESAY_LAUNCHER）。
# 首版不签名（2026-11-13 定）：exe 和 Uninstall.exe 都没有代码签名，SmartScreen 会提示「未知发布者」。
# 可选：installer 目录外的项目根有 LICENSE 文件时，安装程序显示协议页。
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
OUT=$(realpath -m "${1:-$ROOT/build/installer-out}")
WORK="$ROOT/build/installer"
BASE=${ONESAY_BASE:?请设置 ONESAY_BASE 为上一版 OneSay-*-windows-x64.zip 或解开的 OneSay 目录}
MAKENSIS=${MAKENSIS:-makensis}
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$WORK" && mkdir -p "$WORK" "$OUT"

# 1) 从源码构建 jar（复用 build.sh，产物在 build/installer/jar/）
BUILD_DIR="$WORK/jar" sh build.sh >/dev/null
JAR="$WORK/jar/onesay-desktop.jar"

# 2) 版本号从构建出的 jar 读（build.sh 写进 MANIFEST 的 Implementation-Version）
VERSION=$(unzip -p "$JAR" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Implementation-Version: *//p')
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "读不到版本号：'$VERSION'" >&2; exit 1; }

# 3) 取启动器与运行时
if [ -d "$BASE" ]; then BASEDIR="$BASE"
else
  mkdir -p "$WORK/base" && unzip -q "$BASE" -d "$WORK/base"
  BASEDIR="$WORK/base/OneSay"
fi
for f in runtime/bin/javaw.exe runtime/bin/server/jvm.dll runtime/lib/modules; do
  [ -f "$BASEDIR/$f" ] || { echo "ONESAY_BASE 里缺 $f" >&2; exit 1; }
done

# 4) 组装待打包目录
STAGE="$WORK/stage"
mkdir -p "$STAGE/app"
if [ -n "${ONESAY_LAUNCHER:-}" ]; then LAUNCHER="$ONESAY_LAUNCHER"
else
  LAUNCHER="$WORK/launcher"
  bash launcher/build-launcher.sh "$LAUNCHER" >/dev/null
fi
for f in OneSay.exe OneSay.exe.config Microsoft.Web.WebView2.Core.dll Microsoft.Web.WebView2.WinForms.dll WebView2Loader.dll; do
  [ -f "$LAUNCHER/$f" ] || { echo "启动器目录 $LAUNCHER 里缺 $f" >&2; exit 1; }
  cp "$LAUNCHER/$f" "$STAGE/"
done
cp -r "$BASEDIR/runtime" "$STAGE/runtime"
cp "$JAR" "$STAGE/app/onesay-desktop.jar"
# 不再随附 source.zip（2026-11-13 定）。许可与第三方说明直接放在安装目录：
#   LICENSE.txt（Apache-2.0）、NOTICE.txt、THIRD_PARTY_NOTICES.md；Temurin 的 GPLv2+CE 等在 runtime\legal 里。
cp "$ROOT/LICENSE" "$STAGE/LICENSE.txt"
cp "$ROOT/NOTICE" "$STAGE/NOTICE.txt"
cp "$ROOT/THIRD_PARTY_NOTICES.md" "$STAGE/THIRD_PARTY_NOTICES.md"

# 5) 构建 exe
EXE="$OUT/OneSay-Setup-$VERSION-x64.exe"
ARGS=(-V2 -INPUTCHARSET UTF8 "-DVERSION=$VERSION" "-DSTAGE=$STAGE" "-DOUTFILE=$EXE" "-DICON=$ROOT/launcher/onesay.ico")
if [ -f "$ROOT/LICENSE" ]; then
  # 协议页按 UTF-8 显示需要 BOM（否则中文会乱码），这里统一补上
  { printf '\xEF\xBB\xBF'; sed '1s/^\xEF\xBB\xBF//' "$ROOT/LICENSE"; } > "$WORK/LICENSE.txt"
  ARGS+=("-DLICENSE=$WORK/LICENSE.txt")
fi
"$MAKENSIS" "${ARGS[@]}" installer/OneSay.nsi

# 6) sha256
( cd "$OUT" && sha256sum "$(basename "$EXE")" > "$(basename "$EXE").sha256" )
echo "版本 $VERSION"
ls -l "$EXE"
cat "$EXE.sha256"
