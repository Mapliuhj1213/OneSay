# OneSay Preview 卸载脚本：只删预览版。正式版（%LOCALAPPDATA%\Programs\OneSay、%APPDATA%\OneSay）不动。
param([switch]$RemoveData)
$ErrorActionPreference = 'Stop'
$dst = Join-Path $env:LOCALAPPDATA 'Programs\OneSay-Preview'
$data = Join-Path $env:APPDATA 'OneSay-Preview'
foreach ($p in $dst, $data) { if (-not ($p.TrimEnd('\') -like '*\OneSay-Preview')) { throw "路径不对，停止：$p" } }
Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($dst, [StringComparison]::OrdinalIgnoreCase) } | ForEach-Object {
  Write-Host "结束预览版进程：$($_.Id)"; Stop-Process -Id $_.Id -Force
}
Start-Sleep -Milliseconds 800
# 开机自启项只删预览版那一项
$run = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
if (Get-ItemProperty -Path $run -Name 'OneSay Preview' -ErrorAction SilentlyContinue) { Remove-ItemProperty -Path $run -Name 'OneSay Preview'; Write-Host '已删开机自启（OneSay Preview）' }
if (Test-Path $dst) {
  # 脚本自己在这个目录里：先把别的删掉，自己留到最后
  Get-ChildItem -Force $dst | Where-Object { $_.FullName -ne $PSCommandPath } | Remove-Item -Recurse -Force
  Write-Host "已删程序：$dst"
}
if ($RemoveData -and (Test-Path $data)) { Remove-Item -Recurse -Force $data; Write-Host "已删数据：$data" } elseif (Test-Path $data) { Write-Host "数据保留在 $data（加 -RemoveData 一起删）" }
$self = $PSCommandPath
if ($self -and $self.StartsWith($dst, [StringComparison]::OrdinalIgnoreCase)) {
  Start-Process -WindowStyle Hidden -FilePath cmd.exe -ArgumentList "/c ping -n 2 127.0.0.1 >nul & rmdir /s /q `"$dst`""
} elseif (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
Write-Host '预览版已卸载。'
