# OneSay Preview 安装脚本（只装预览版）
# 装到 %LOCALAPPDATA%\Programs\OneSay-Preview；不碰正式版目录 %LOCALAPPDATA%\Programs\OneSay、
# 不建快捷方式、不写卸载项（HKCU\...\Uninstall）。
param([switch]$NoLan, [switch]$Start)
$ErrorActionPreference = 'Stop'
$src = $PSScriptRoot
$dst = Join-Path $env:LOCALAPPDATA 'Programs\OneSay-Preview'
$release = Join-Path $env:LOCALAPPDATA 'Programs\OneSay'
if ((Resolve-Path $src).Path.TrimEnd('\') -ieq $release.TrimEnd('\')) { throw '这个脚本在正式版目录里，停止。' }
foreach ($f in 'OneSay.exe', 'app\onesay-desktop.jar', 'runtime\bin\server\jvm.dll') {
  if (-not (Test-Path (Join-Path $src $f))) { throw "安装包不完整：缺少 $f（请把压缩包整个解压后再运行）" }
}
# 只结束从预览版目录启动的进程
Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($dst, [StringComparison]::OrdinalIgnoreCase) } | ForEach-Object {
  Write-Host "结束正在运行的预览版：$($_.Id)"; Stop-Process -Id $_.Id -Force
}
Start-Sleep -Milliseconds 500
if ((Resolve-Path $src).Path.TrimEnd('\') -ine $dst.TrimEnd('\')) {
  if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
  New-Item -ItemType Directory -Force -Path $dst | Out-Null
  Copy-Item -Recurse -Force (Join-Path $src '*') $dst
}
$cmd = Join-Path $dst 'run-preview.cmd'
Set-Content -Path $cmd -Encoding ASCII -Value "@echo off`r`nset ONESAY_LAN=off`r`nstart `"`" `"%~dp0OneSay.exe`"`r`n"
Write-Host "已装到 $dst"
Write-Host "启动：$dst\OneSay.exe（或 run-preview.cmd，不开局域网）"
Write-Host "卸载：powershell -ExecutionPolicy Bypass -File `"$dst\uninstall-preview.ps1`""
if ($Start) {
  if ($NoLan) { $env:ONESAY_LAN = 'off' }
  Start-Process -FilePath (Join-Path $dst 'OneSay.exe') -WorkingDirectory $dst
}
