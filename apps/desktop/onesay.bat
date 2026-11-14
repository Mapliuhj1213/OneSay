@echo off
setlocal
set "JAVA=java"
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe"
if defined ONESAY_JAVA set "JAVA=%ONESAY_JAVA%"
chcp 65001 >nul
"%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "%~dp0onesay-desktop.jar" %*
