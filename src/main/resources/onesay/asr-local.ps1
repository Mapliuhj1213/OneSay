# OneSay 本机离线语音识别：Windows 自带 System.Speech（听写语法），不联网、不上传。
# 用法：asr-local.ps1 -Check      → 输出 "OK zh-CN" 或 "NONE"
#       asr-local.ps1 -Wav 文件   → 输出 "TEXT:识别结果"
param([string]$Wav = '', [switch]$Check)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Add-Type -AssemblyName System.Speech
$all = [System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers()
$ri = $all | Where-Object { $_.Culture.Name -eq 'zh-CN' } | Select-Object -First 1
if (-not $ri) { $ri = $all | Where-Object { $_.Culture.TwoLetterISOLanguageName -eq 'zh' } | Select-Object -First 1 }
if (-not $ri) {
  if ($Check) { 'NONE'; exit 0 }
  'ERR:no_recognizer'; exit 2
}
if ($Check) { 'OK ' + $ri.Culture.Name; exit 0 }
$e = New-Object System.Speech.Recognition.SpeechRecognitionEngine($ri)
try {
  $e.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
  $e.SetInputToWaveFile($Wav)
  $r = $e.Recognize()
  if ($r) { 'TEXT:' + $r.Text } else { 'TEXT:' }
} finally { $e.Dispose() }
