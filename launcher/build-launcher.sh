#!/bin/bash
# 编 OneSay.exe 启动器（C#，.NET Framework 4.8，x64）。Linux 上用 .NET 8 SDK 即可（不需要 Windows）。
# 用法：bash launcher/build-launcher.sh [输出目录]   默认 build/launcher/
# 产物：OneSay.exe、OneSay.exe.config、Microsoft.Web.WebView2.Core.dll、Microsoft.Web.WebView2.WinForms.dll、WebView2Loader.dll
# DOTNET 可指定 dotnet 路径。
set -euo pipefail
OUT=$(realpath -m "${1:-$(dirname "$0")/../build/launcher}")
cd "$(dirname "$0")"
DOTNET=${DOTNET:-dotnet}
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
rm -rf obj bin "$OUT"
"$DOTNET" build -c Release -nologo -v q -o "$OUT" OneSay.csproj
# WebView2Loader.dll（x64）不一定被复制到输出根目录，直接从 NuGet 包里取
LOADER=$(find "${NUGET_PACKAGES:-$HOME/.nuget/packages}/microsoft.web.webview2" -path '*runtimes/win-x64/native/WebView2Loader.dll' | sort | tail -1)
[ -n "$LOADER" ] && cp "$LOADER" "$OUT/WebView2Loader.dll"
# 只留运行要用的
find "$OUT" -maxdepth 1 -type f ! \( -name OneSay.exe -o -name OneSay.exe.config -o -name 'Microsoft.Web.WebView2.Core.dll' -o -name 'Microsoft.Web.WebView2.WinForms.dll' -o -name WebView2Loader.dll \) -delete
find "$OUT" -mindepth 1 -type d -exec rm -rf {} +
for f in OneSay.exe OneSay.exe.config Microsoft.Web.WebView2.Core.dll Microsoft.Web.WebView2.WinForms.dll WebView2Loader.dll; do
  [ -f "$OUT/$f" ] || { echo "启动器产物缺 $f" >&2; exit 1; }
done
ls -l "$OUT"
