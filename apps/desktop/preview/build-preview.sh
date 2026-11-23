#!/bin/bash
# [preview-1120] 打预览版（OneSay Preview）：jar 带 onesay/flavor.properties，启动器用 PREVIEW 编译，打成免安装 zip。
# 不改正式版：build.sh 的版本号、installer/、release 配置都不动；预览版不用 NSIS 安装程序（它的卸载项和正式版同名），只出 zip。
# 用法：ONESAY_BASE=<上一版 OneSay-*-windows-x64.zip（只取 runtime\）> bash preview/build-preview.sh <输出目录>
# 可选：SUFFIX=-preview.1120（版本后缀）、DOTNET=dotnet 路径、SKIP_LAUNCHER=1（用 ONESAY_LAUNCHER=<目录> 给编好的）
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
OUT=$(realpath -m "${1:?输出目录}")
SUFFIX=${SUFFIX:--preview.1120}
BASE=${ONESAY_BASE:?ONESAY_BASE 指向上一版 zip}
VER=$(sed -n 's/.*static final String VERSION = "\(.*\)";/\1/p' "$HERE/src/main/java/com/onesay/desktop/Main.java")
FULL="$VER$SUFFIX"
B=$(mktemp -d)
trap 'rm -rf "$B"' EXIT
mkdir -p "$B/classes" "$OUT"
# [workflow-1120] 依赖按 deps/java.lock 下载校验后解进 jar（DEPS_DIR 可指向已下载的目录）
export DEPS_DIR=${DEPS_DIR:-$B/deps}
sh "$HERE/deps/fetch.sh" >/dev/null
DEPCP=$(grep -v '^#' "$HERE/deps/java.lock" | while read -r p s; do [ -n "$p" ] && printf '%s:' "$DEPS_DIR/$(basename "$p")"; done)
javac --release 17 -encoding UTF-8 -cp "$DEPCP" -d "$B/classes" $(find "$HERE/src/main/java" -name '*.java')
cp -r "$HERE/src/main/resources/." "$B/classes/"
sh "$HERE/deps/unpack.sh" "$B/classes"
printf 'flavor=preview\nsuffix=%s\n' "$SUFFIX" > "$B/classes/onesay/flavor.properties"
printf 'Main-Class: com.onesay.desktop.Main\nImplementation-Version: %s\nImplementation-Title: OneSay Preview\n' "$FULL" > "$B/MANIFEST.MF"
jar --create --file "$OUT/onesay-desktop-preview.jar" --manifest "$B/MANIFEST.MF" -C "$B/classes" .
echo "jar: $OUT/onesay-desktop-preview.jar ($FULL)"
# 启动器
if [ -z "${ONESAY_LAUNCHER:-}" ]; then
  L="$B/launcher-src"; cp -r "$HERE/launcher" "$L"
  cd "$L"
  DOTNET=${DOTNET:-dotnet}
  export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
  "$DOTNET" build -c Release -nologo -v q -o "$B/launcher" -p:DefineConstants=PREVIEW -p:Product="OneSay Preview" -p:AssemblyTitle="OneSay Preview" -p:Description="OneSay Preview" OneSay.csproj
  LOADER=$(find "${NUGET_PACKAGES:-$HOME/.nuget/packages}/microsoft.web.webview2" -path '*runtimes/win-x64/native/WebView2Loader.dll' | sort | tail -1)
  cp "$LOADER" "$B/launcher/WebView2Loader.dll"
  ONESAY_LAUNCHER="$B/launcher"
  cd - >/dev/null
fi
# 组装：OneSay-Preview\{OneSay.exe, dll, app\onesay-desktop.jar, runtime\, 说明}
R="$B/zip/OneSay-Preview"; mkdir -p "$R/app"
for f in OneSay.exe OneSay.exe.config Microsoft.Web.WebView2.Core.dll Microsoft.Web.WebView2.WinForms.dll WebView2Loader.dll; do cp "$ONESAY_LAUNCHER/$f" "$R/"; done
cp "$OUT/onesay-desktop-preview.jar" "$R/app/onesay-desktop.jar"
mkdir -p "$B/base"; unzip -q "$BASE" -d "$B/base"
RT=$(find "$B/base" -type d -name runtime | head -1)
[ -f "$RT/bin/server/jvm.dll" ] || { echo "ONESAY_BASE 里没有 runtime\\bin\\server\\jvm.dll" >&2; exit 1; }
cp -r "$RT" "$R/runtime"
cp "$HERE/LICENSE" "$R/LICENSE.txt"; cp "$HERE/NOTICE" "$R/NOTICE.txt"; cp "$HERE/THIRD_PARTY_NOTICES.md" "$R/"
cp "$HERE/preview/README-PREVIEW.txt" "$HERE/preview/install-preview.ps1" "$HERE/preview/uninstall-preview.ps1" "$R/"
( cd "$B/zip" && rm -f "$OUT/OneSay-Preview-$FULL-windows-x64.zip" && zip -qr "$OUT/OneSay-Preview-$FULL-windows-x64.zip" OneSay-Preview )
( cd "$OUT" && sha256sum onesay-desktop-preview.jar "OneSay-Preview-$FULL-windows-x64.zip" > SHA256SUMS.txt && cat SHA256SUMS.txt )
