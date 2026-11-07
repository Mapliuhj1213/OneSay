# OneSay 长按空格说话的钩子进程。由 OneSay Desktop 启动，标准输出回报 READY/START/END/CANCEL。
param([string]$Cs, [string]$Skip = "mstsc.exe")
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
try {
  Add-Type -TypeDefinition ([IO.File]::ReadAllText($Cs, [Text.Encoding]::UTF8)) -Language CSharp
  [OneSayHotkey]::Run($Skip)
} catch {
  [Console]::Out.WriteLine("LOG 钩子出错：" + $_.Exception.Message)
  exit 1
}
