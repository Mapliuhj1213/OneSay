; OneSay 电脑端安装程序（NSIS 3，Unicode）
; 按用户安装：不要管理员、不弹 UAC、不写 HKLM。
; 由 installer/build-installer.sh 调用，必须传入：
;   -DVERSION=0.5.1        版本号（从构建出的 jar 的 Implementation-Version 读）
;   -DSTAGE=/abs/stage     待打包目录（OneSay.exe、OneSay.exe.config、app\、runtime\）
;   -DOUTFILE=/abs/x.exe   输出文件
;   -DICON=/abs/onesay.ico 图标
; 可选：
;   -DLICENSE=/abs/LICENSE 有项目许可证时显示协议页

!ifndef VERSION | STAGE | OUTFILE | ICON
  !error "缺少 -DVERSION / -DSTAGE / -DOUTFILE / -DICON，请用 build-installer.sh 构建"
!endif

Unicode true
ManifestDPIAware true
ManifestSupportedOS all
RequestExecutionLevel user
SetCompressor /SOLID lzma
SetCompressorDictSize 64

!define APPNAME   "OneSay"
!define APPEXE    "OneSay.exe"
!define UNINSTEXE "Uninstall.exe"
!define UNINSTKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\OneSay"
!define RUNKEY    "Software\Microsoft\Windows\CurrentVersion\Run"

Name "${APPNAME}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\OneSay"
InstallDirRegKey HKCU "${UNINSTKEY}" "InstallLocation"
BrandingText "OneSay ${VERSION}"
ShowInstDetails nevershow
ShowUninstDetails nevershow

VIProductVersion "${VERSION}.0"
VIFileVersion "${VERSION}.0"
VIAddVersionKey /LANG=0 "ProductName" "OneSay"
VIAddVersionKey /LANG=0 "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=0 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=0 "FileDescription" "OneSay Setup"
VIAddVersionKey /LANG=0 "CompanyName" "OneSay"
VIAddVersionKey /LANG=0 "LegalCopyright" "© 2026 OneSay contributors"

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "FileFunc.nsh"
!include "WordFunc.nsh"
!include "x64.nsh"

Var Upgrading      ; 1 = 已装过（升级 / 重装）
Var OldVersion

; ---------------------------------------------------------------- 界面
!define MUI_ICON   "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_ABORTWARNING
!define MUI_COMPONENTSPAGE_NODESC

!define MUI_PAGE_CUSTOMFUNCTION_SHOW WelcomeShow
!insertmacro MUI_PAGE_WELCOME
!ifdef LICENSE
  !define MUI_PAGE_CUSTOMFUNCTION_PRE SkipIfUpgrading
  !insertmacro MUI_PAGE_LICENSE "${LICENSE}"
!endif
!define MUI_PAGE_CUSTOMFUNCTION_PRE SkipIfUpgrading
!insertmacro MUI_PAGE_COMPONENTS
!define MUI_PAGE_CUSTOMFUNCTION_PRE SkipIfUpgrading
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE DirLeave
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APPEXE}"
!define MUI_FINISHPAGE_RUN_TEXT "$(TxtRunNow)"
!insertmacro MUI_PAGE_FINISH

!define MUI_UNCOMPONENTSPAGE_NODESC
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_COMPONENTS
!insertmacro MUI_UNPAGE_INSTFILES

; 第一个是默认语言；系统界面语言是英文时自动用英文
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"

; ---------------------------------------------------------------- 文字
LangString SecAppName      ${LANG_SIMPCHINESE} "OneSay 程序（必需）"
LangString SecAppName      ${LANG_ENGLISH}     "OneSay (required)"
LangString SecStartName    ${LANG_SIMPCHINESE} "开始菜单快捷方式"
LangString SecStartName    ${LANG_ENGLISH}     "Start menu shortcut"
LangString SecDesktopName  ${LANG_SIMPCHINESE} "桌面快捷方式"
LangString SecDesktopName  ${LANG_ENGLISH}     "Desktop shortcut"
LangString SecAutoName     ${LANG_SIMPCHINESE} "开机自动启动（只进托盘）"
LangString SecAutoName     ${LANG_ENGLISH}     "Start with Windows (tray only)"
LangString TxtRunNow       ${LANG_SIMPCHINESE} "立即启动 OneSay"
LangString TxtRunNow       ${LANG_ENGLISH}     "Launch OneSay now"
LangString TxtWelcomeNew   ${LANG_SIMPCHINESE} "将在你的电脑上安装 OneSay ${VERSION}。$\r$\n$\r$\n只为当前用户安装，不需要管理员权限。程序自带运行环境，不需要另装 Java。$\r$\n$\r$\n点「下一步」继续。"
LangString TxtWelcomeNew   ${LANG_ENGLISH}     "This will install OneSay ${VERSION} for the current user. No administrator rights are needed, and Java is included.$\r$\n$\r$\nClick Next to continue."
LangString TxtWelcomeUpg   ${LANG_SIMPCHINESE} "已安装 OneSay $OldVersion，将更新为 ${VERSION}。$\r$\n$\r$\n只替换程序文件，你的设置、配对、对话记录和工作区都会保留，快捷方式与开机自启保持原样。$\r$\n$\r$\n点「下一步」继续。"
LangString TxtWelcomeUpg   ${LANG_ENGLISH}     "OneSay $OldVersion is installed and will be updated to ${VERSION}.$\r$\n$\r$\nOnly program files are replaced. Your settings, pairings, chats and workspace are kept.$\r$\n$\r$\nClick Next to continue."
LangString TxtDowngrade    ${LANG_SIMPCHINESE} "已安装的 OneSay $OldVersion 比这个安装包（${VERSION}）新。$\r$\n仍要安装这个旧版本吗？你的数据会保留。"
LangString TxtDowngrade    ${LANG_ENGLISH}     "The installed OneSay $OldVersion is newer than this installer (${VERSION}).$\r$\nInstall the older version anyway? Your data is kept."
LangString TxtRunning      ${LANG_SIMPCHINESE} "OneSay 正在运行。$\r$\n$\r$\n请在屏幕右下角托盘的 OneSay 图标上点右键，选「退出」，然后点「重试」。"
LangString TxtRunning      ${LANG_ENGLISH}     "OneSay is running.$\r$\n$\r$\nRight-click the OneSay icon in the system tray, choose Exit, then click Retry."
LangString TxtNeed64       ${LANG_SIMPCHINESE} "OneSay 需要 64 位 Windows 10 或 11。"
LangString TxtNeed64       ${LANG_ENGLISH}     "OneSay requires 64-bit Windows 10 or 11."
LangString TxtNoWrite      ${LANG_SIMPCHINESE} "没有权限写入这个文件夹：$\r$\n$INSTDIR$\r$\n$\r$\nOneSay 不用管理员权限安装，请选一个你自己能写的位置（默认位置就可以）。"
LangString TxtNoWrite      ${LANG_ENGLISH}     "Cannot write to this folder:$\r$\n$INSTDIR$\r$\n$\r$\nOneSay installs without administrator rights. Please choose a folder you can write to (the default is fine)."
LangString SecDataName     ${LANG_SIMPCHINESE} "同时删除我的数据（设置、配对、对话记录、工作区、下载工具）"
LangString SecDataName     ${LANG_ENGLISH}     "Also delete my data (settings, pairings, chats, workspace, download tools)"
LangString TxtConfirmData  ${LANG_SIMPCHINESE} "将永久删除你这个账户的 OneSay 设置、配对钥匙、对话记录、工作区里的文件和下载工具，无法恢复。下载到「OneSay\Downloads」的文件会保留。$\r$\n$\r$\n确定删除吗？选「否」只卸载程序、保留数据。"
LangString TxtConfirmData  ${LANG_ENGLISH}     "This permanently deletes this account's OneSay settings, pairing keys, chats, workspace files and download tools. Files in OneSay\Downloads are kept.$\r$\n$\r$\nDelete them? Choose No to remove only the program and keep your data."

; ---------------------------------------------------------------- 公用：OneSay 是否在运行
; 运行中的 exe 不能以写方式打开（共享冲突）：自带运行时的 javaw.exe / java.exe 打不开 = 正在运行。
; 不强杀，只提示用户自己退出。
!macro CHECK_RUNNING_FN un
Function ${un}CheckRunning
  retry:
  StrCpy $R1 0
  ${ForEach} $R0 0 1 + 1
    ${If} $R0 == 0
      StrCpy $R2 "$INSTDIR\runtime\bin\javaw.exe"
    ${Else}
      StrCpy $R2 "$INSTDIR\runtime\bin\java.exe"
    ${EndIf}
    ${If} ${FileExists} "$R2"
      ClearErrors
      FileOpen $R3 "$R2" a
      ${If} ${Errors}
        StrCpy $R1 1
      ${Else}
        FileClose $R3
      ${EndIf}
    ${EndIf}
  ${Next}
  ${If} $R1 == 1
    MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "$(TxtRunning)" /SD IDCANCEL IDRETRY retry
    SetErrorLevel 2
    Quit
  ${EndIf}
FunctionEnd
!macroend
!insertmacro CHECK_RUNNING_FN ""
!insertmacro CHECK_RUNNING_FN "un."

; ---------------------------------------------------------------- 安装

Function SkipIfUpgrading
  ${If} $Upgrading == 1
    Abort
  ${EndIf}
FunctionEnd

; 选了需要管理员的位置（如 C:\Program Files）时当场提示，而不是装到一半报错
Function DirLeave
  StrCpy $R5 0
  ${IfNot} ${FileExists} "$INSTDIR\*.*"
    StrCpy $R5 1
  ${EndIf}
  ClearErrors
  CreateDirectory "$INSTDIR"
  FileOpen $R6 "$INSTDIR\.onesay-write-test" w
  ${If} ${Errors}
    ${If} $R5 == 1
      RMDir "$INSTDIR"
    ${EndIf}
    MessageBox MB_OK|MB_ICONEXCLAMATION "$(TxtNoWrite)"
    Abort
  ${EndIf}
  FileClose $R6
  Delete "$INSTDIR\.onesay-write-test"
  ${If} $R5 == 1
    RMDir "$INSTDIR"
  ${EndIf}
FunctionEnd

Function WelcomeShow
  ${If} $Upgrading == 1
    SendMessage $mui.WelcomePage.Text ${WM_SETTEXT} 0 "STR:$(TxtWelcomeUpg)"
  ${Else}
    SendMessage $mui.WelcomePage.Text ${WM_SETTEXT} 0 "STR:$(TxtWelcomeNew)"
  ${EndIf}
FunctionEnd

Section "$(SecAppName)" SecApp
  SectionIn RO
  SetShellVarContext current
  Call CheckRunning

  ; 只替换我们自己的文件夹，不碰安装目录里别的东西；用户数据不在这里
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  SetOutPath "$INSTDIR"
  File /r "${STAGE}/*"
  WriteUninstaller "$INSTDIR\${UNINSTEXE}"

  ; 控制面板「应用」里的卸载项（仅当前用户）
  WriteRegStr   HKCU "${UNINSTKEY}" "DisplayName"          "OneSay"
  WriteRegStr   HKCU "${UNINSTKEY}" "DisplayVersion"       "${VERSION}"
  WriteRegStr   HKCU "${UNINSTKEY}" "Publisher"            "OneSay"
  WriteRegStr   HKCU "${UNINSTKEY}" "DisplayIcon"          "$INSTDIR\${APPEXE},0"
  WriteRegStr   HKCU "${UNINSTKEY}" "InstallLocation"      "$INSTDIR"
  WriteRegStr   HKCU "${UNINSTKEY}" "UninstallString"      '"$INSTDIR\${UNINSTEXE}"'
  WriteRegStr   HKCU "${UNINSTKEY}" "QuietUninstallString" '"$INSTDIR\${UNINSTEXE}" /S'
  WriteRegDWORD HKCU "${UNINSTKEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTKEY}" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  IntFmt $0 "0x%08X" $0
  WriteRegDWORD HKCU "${UNINSTKEY}" "EstimatedSize" "$0"

  ; 程序第一次用 OneSay.exe 启动时会自己建桌面快捷方式（建过一次就记在这个标记里）。
  ; 快捷方式改由安装程序负责：写上标记，用户没勾桌面快捷方式时程序也不会再建。
  CreateDirectory "$APPDATA\OneSay"
  ${IfNot} ${FileExists} "$APPDATA\OneSay\desktop-shortcut.done"
    FileOpen $0 "$APPDATA\OneSay\desktop-shortcut.done" w
    FileWrite $0 "1"
    FileClose $0
  ${EndIf}
SectionEnd

Section "$(SecStartName)" SecStart
  SectionIn RO
  SetShellVarContext current
  ; 与程序自己核对的路径相同（%APPDATA%\Microsoft\Windows\Start Menu\Programs\OneSay.lnk），不会出现两个
  CreateShortcut "$SMPROGRAMS\OneSay.lnk" "$INSTDIR\${APPEXE}" "" "$INSTDIR\${APPEXE}" 0 SW_SHOWNORMAL "" "OneSay"
SectionEnd

Section "$(SecDesktopName)" SecDesktop
  SetShellVarContext current
  CreateShortcut "$DESKTOP\OneSay.lnk" "$INSTDIR\${APPEXE}" "" "$INSTDIR\${APPEXE}" 0 SW_SHOWNORMAL "" "OneSay"
SectionEnd

Section /o "$(SecAutoName)" SecAuto
  ; 与程序设置页「开机自动启动」写的是同一个值（名字 OneSay，参数 --tray），两边开关一致
  WriteRegStr HKCU "${RUNKEY}" "OneSay" '"$INSTDIR\${APPEXE}" --tray'
SectionEnd

; .onInit 要用到各 Section 的编号，所以放在 Section 之后
Function .onInit
  ${IfNot} ${RunningX64}
  ${AndIfNot} ${IsNativeARM64}
    MessageBox MB_OK|MB_ICONSTOP "$(TxtNeed64)" /SD IDOK
    SetErrorLevel 3
    Quit
  ${EndIf}
  SetShellVarContext current

  StrCpy $Upgrading 0
  ReadRegStr $OldVersion HKCU "${UNINSTKEY}" "DisplayVersion"
  ReadRegStr $R0 HKCU "${UNINSTKEY}" "InstallLocation"
  ${If} $OldVersion != ""
  ${AndIf} $R0 != ""
  ${AndIf} ${FileExists} "$R0\${APPEXE}"
    StrCpy $Upgrading 1
    StrCpy $INSTDIR $R0
    ${VersionCompare} "$OldVersion" "${VERSION}" $R1
    ${If} $R1 == 1
      MessageBox MB_YESNO|MB_ICONQUESTION "$(TxtDowngrade)" /SD IDYES IDYES downgrade_ok
      SetErrorLevel 4
      Quit
      downgrade_ok:
    ${EndIf}
    ; 升级沿用原来的选择：有桌面快捷方式才更新它，开过自启才更新自启项
    ${IfNot} ${FileExists} "$DESKTOP\OneSay.lnk"
      !insertmacro UnselectSection ${SecDesktop}
    ${EndIf}
    ReadRegStr $R2 HKCU "${RUNKEY}" "OneSay"
    ${If} $R2 != ""
      !insertmacro SelectSection ${SecAuto}
    ${EndIf}
  ${EndIf}
FunctionEnd

; ---------------------------------------------------------------- 卸载
Function un.onInit
  SetShellVarContext current
FunctionEnd

Section "un.$(SecAppName)" UnSecApp
  SectionIn RO
  SetShellVarContext current
  Call un.CheckRunning

  Delete "$SMPROGRAMS\OneSay.lnk"
  Delete "$DESKTOP\OneSay.lnk"

  ; 开机自启：只删指向本安装目录的那一项
  ReadRegStr $0 HKCU "${RUNKEY}" "OneSay"
  StrLen $1 '"$INSTDIR\${APPEXE}"'
  StrCpy $2 $0 $1
  ${If} $2 == '"$INSTDIR\${APPEXE}"'
    DeleteRegValue HKCU "${RUNKEY}" "OneSay"
  ${EndIf}
  DeleteRegKey HKCU "${UNINSTKEY}"

  ; 只删安装程序放进去的东西；安装目录里用户自己放的文件不动
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  Delete "$INSTDIR\${APPEXE}"
  Delete "$INSTDIR\OneSay.exe.config"
  Delete "$INSTDIR\LICENSE.txt"
  Delete "$INSTDIR\NOTICE.txt"
  Delete "$INSTDIR\THIRD_PARTY_NOTICES.md"
  Delete "$INSTDIR\${UNINSTEXE}"
  RMDir "$INSTDIR"
SectionEnd

; 默认不勾：只有用户明确选了才删数据
Section /o "un.$(SecDataName)" UnSecData
  SetShellVarContext current
  MessageBox MB_YESNO|MB_ICONEXCLAMATION|MB_DEFBUTTON2 "$(TxtConfirmData)" /SD IDNO IDYES delete_data
  Return
  delete_data:
  ; 配置、配对钥匙、对话与记忆、日志（程序写在 %APPDATA%\OneSay）
  RMDir /r "$APPDATA\OneSay"
  ; 工作区：%USERPROFILE%\OneSay\workspace；用户目录含非 ASCII 字符时程序改用
  ; %ProgramData%\OneSay\accounts\<账户标记>\workspace（每个账户一个子目录，规则同 UserDirs.accountTag）。
  ; 下载工具：%LOCALAPPDATA%\OneSay\tools；含非 ASCII 时 %ProgramData%\OneSay\accounts\<账户标记>\tools。
  ; 只删本账户自己的；%USERPROFILE%\OneSay\Downloads（下载的文件）不删。
  RMDir /r "$PROFILE\OneSay\workspace"
  RMDir "$PROFILE\OneSay"
  RMDir /r "$LOCALAPPDATA\OneSay\tools"
  RMDir "$LOCALAPPDATA\OneSay"
  ReadEnvStr $1 "ProgramData"
  ${If} $1 != ""
    Push $PROFILE
    Call un.AccountTag
    Pop $2
    RMDir /r "$1\OneSay\accounts\$2"
    RMDir "$1\OneSay\accounts"
    RMDir "$1\OneSay"
  ${EndIf}
SectionEnd

; 输入：栈顶 = 用户目录（$PROFILE）；输出：账户标记。与 UserDirs.accountTag 相同：
; 取最后一段，A–Z a–z 0–9 和 - 原样保留，其余每个 UTF-16 字符写成 _XXXX（4 位大写十六进制）；空 = default。
Function un.AccountTag
  Exch $0
  Push $1
  Push $2
  Push $3
  Push $4
  Push $5
  Push $9
  ; 去掉结尾的 \ 或 /
  ${Do}
    StrCpy $1 $0 1 -1
    ${If} $1 == "\"
    ${OrIf} $1 == "/"
      StrCpy $0 $0 -1
    ${Else}
      ${Break}
    ${EndIf}
  ${Loop}
  ; 取最后一段
  StrLen $1 $0
  StrCpy $2 $1
  ${DoWhile} $2 > 0
    IntOp $3 $2 - 1
    StrCpy $4 $0 1 $3
    ${If} $4 == "\"
    ${OrIf} $4 == "/"
      ${Break}
    ${EndIf}
    StrCpy $2 $3
  ${Loop}
  StrCpy $0 $0 "" $2
  StrLen $1 $0
  StrCpy $5 ""
  StrCpy $2 0
  ${DoWhile} $2 < $1
    StrCpy $3 $0 1 $2
    System::Call "*(&w2 r3)p.r9"
    System::Call "*$9(&i2 .r4)"
    System::Free $9
    IntOp $4 $4 & 0xFFFF
    ${If} $4 >= 0x30
    ${AndIf} $4 <= 0x39
      StrCpy $5 "$5$3"
    ${ElseIf} $4 >= 0x41
    ${AndIf} $4 <= 0x5A
      StrCpy $5 "$5$3"
    ${ElseIf} $4 >= 0x61
    ${AndIf} $4 <= 0x7A
      StrCpy $5 "$5$3"
    ${ElseIf} $4 == 0x2D
      StrCpy $5 "$5$3"
    ${Else}
      IntFmt $4 "_%04X" $4
      StrCpy $5 "$5$4"
    ${EndIf}
    IntOp $2 $2 + 1
  ${Loop}
  ${If} $5 == ""
    StrCpy $5 "default"
  ${EndIf}
  StrCpy $0 $5
  Pop $9
  Pop $5
  Pop $4
  Pop $3
  Pop $2
  Pop $1
  Exch $0
FunctionEnd
