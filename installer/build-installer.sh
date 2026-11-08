#!/bin/bash
# 一条命令生成 OneSay 安装程序 OneSay-Setup-<版本>-x64.exe 及其 .sha256。
#
# 用法：
#   ONESAY_BASE=<上一版 zip 或已解开的 OneSay 目录> bash installer/build-installer.sh [输出目录]
#
# ONESAY_BASE 提供 Windows 专用、在 Linux 上不重建的部分：
#   OneSay.exe + OneSay.exe.config（launcher/ 的 C# 启动器）和 runtime\（jlink 精简 Temurin 17）。
#   与 zip 交付做法相同：jar 每次从源码构建，这两样沿用上一版包里的。
# 需要：JDK 17+（javac/jar，JAVA_HOME 或 PATH）、git、unzip、makensis 3.x（MAKENSIS 可指定路径）。
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
for f in OneSay.exe OneSay.exe.config runtime/bin/javaw.exe runtime/lib/modules; do
  [ -f "$BASEDIR/$f" ] || { echo "ONESAY_BASE 里缺 $f" >&2; exit 1; }
done

# 4) 组装待打包目录
STAGE="$WORK/stage"
mkdir -p "$STAGE/app"
cp "$BASEDIR/OneSay.exe" "$BASEDIR/OneSay.exe.config" "$STAGE/"
cp -r "$BASEDIR/runtime" "$STAGE/runtime"
cp "$JAR" "$STAGE/app/onesay-desktop.jar"
# 与 zip 一样随附源码（Temurin GPLv2+CE 与第三方许可说明在里面）；按当前提交打
git archive --format=zip -o "$STAGE/app/source.zip" HEAD

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
