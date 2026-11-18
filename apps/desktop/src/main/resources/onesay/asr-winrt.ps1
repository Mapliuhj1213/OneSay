# OneSay 按住说话的兜底识别：Windows 自带的语音识别（Windows.Media.SpeechRecognition，Windows 10+）。
# 用在：没填识别 Key、也没有 System.Speech 中文离线识别时。它自己从默认麦克风听（不经 OneSay 录音）。
# 注意：Windows 的听写需要在「设置 › 隐私和安全性 › 语音」里打开「联机语音识别」，由 Windows 自己处理语音（OneSay 不上传任何东西）。
# 用法：asr-winrt.ps1 -Check   → 输出 "OK zh-CN" 或 "NONE ..."
#       asr-winrt.ps1          → 准备好后输出 "READY"；标准输入收到 "GO" 开始听，收到 "STOP" 停（再给 2 秒收尾），输出 "TEXT:识别结果" 或 "ERR:原因"；收到 "QUIT" 退出
param([switch]$Check)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $null = [Windows.Media.SpeechRecognition.SpeechRecognizer, Windows.Media.SpeechRecognition, ContentType = WindowsRuntime]
  $null = [Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime]
} catch { 'NONE no_winrt'; exit 0 }
$langs = @([Windows.Media.SpeechRecognition.SpeechRecognizer]::SupportedTopicLanguages)
$lang = $langs | Where-Object { $_.LanguageTag -like 'zh-CN*' -or $_.LanguageTag -like 'zh-Hans*' } | Select-Object -First 1
if (-not $lang) { $lang = $langs | Where-Object { $_.LanguageTag -like 'zh*' } | Select-Object -First 1 }
if (-not $lang) {
  $sys = [Windows.Media.SpeechRecognition.SpeechRecognizer]::SystemSpeechLanguage
  if ($sys -and $sys.LanguageTag -like 'zh*') { $lang = $sys }
}
if ($Check) { if ($lang) { 'OK ' + $lang.LanguageTag } else { 'NONE no_zh' }; exit 0 }
if (-not $lang) { 'ERR:no_zh'; exit 2 }
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $null = $t.Wait(-1); $t.Result }
$rec = New-Object Windows.Media.SpeechRecognition.SpeechRecognizer($lang)
$rec.Constraints.Add((New-Object Windows.Media.SpeechRecognition.SpeechRecognitionTopicConstraint([Windows.Media.SpeechRecognition.SpeechRecognitionScenario]::Dictation, 'dictation')))
$c = Await ($rec.CompileConstraintsAsync()) ([Windows.Media.SpeechRecognition.SpeechRecognitionCompilationResult])
if ($c.Status -ne 'Success') { 'ERR:compile_' + $c.Status; exit 3 }
$rec.Timeouts.InitialSilenceTimeout = [TimeSpan]::FromSeconds(8)
$rec.Timeouts.EndSilenceTimeout = [TimeSpan]::FromSeconds(1.2)
'READY'
[Console]::Out.Flush()
$in = [Console]::In
while ($true) {
  $line = $in.ReadLine()
  if ($line -eq $null -or $line -eq 'QUIT') { break }
  if ($line -ne 'GO') { continue }
  $op = $rec.RecognizeAsync()
  $task = $asTask.MakeGenericMethod([Windows.Media.SpeechRecognition.SpeechRecognitionResult]).Invoke($null, @($op))
  $stop = $in.ReadLineAsync()
  while (-not $task.IsCompleted -and -not $stop.IsCompleted) { Start-Sleep -Milliseconds 40 }
  if (-not $task.IsCompleted) { $null = $task.Wait(2000) }
  if (-not $task.IsCompleted) { try { $op.Cancel() } catch { } ; 'TEXT:' }
  elseif ($task.IsFaulted) {
    $m = $task.Exception.InnerException.HResult
    if ($m -eq -2147199735) { 'ERR:privacy' } else { 'ERR:' + $task.Exception.InnerException.Message }
  } else {
    $r = $task.Result
    if ($r.Status -ne 'Success') { 'ERR:' + $r.Status } else { 'TEXT:' + $r.Text }
  }
  [Console]::Out.Flush()
  if (-not $stop.IsCompleted) { $null = $stop.Wait() }
  if ($stop.Result -eq 'QUIT' -or $stop.Result -eq $null) { break }
}
$rec.Dispose()
