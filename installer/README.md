# OneSay Windows 安装程序（NSIS）

生成 `OneSay-Setup-<版本>-x64.exe` 和同名 `.sha256`。和 zip 的内容相同，只是多了安装和卸载。

## 构建（一条命令）
```sh
ONESAY_BASE=/path/OneSay-0.5.0-windows-x64.zip bash installer/build-installer.sh [输出目录]
```
- `ONESAY_BASE`：上一版 zip，或者解开后的 `OneSay` 目录。只用里面的 `runtime\`（jlink 精简过的 Temurin 17，要有 `runtime\bin\server\jvm.dll`），在 Linux 上不重新构建。jar 每次用 `build.sh` 从源码构建。
- 启动器 `OneSay.exe` 每次用 `launcher/build-launcher.sh` 从源码构建（.NET 8 SDK，Linux 上就能编；`DOTNET=` 可指定路径），或者用 `ONESAY_LAUNCHER=<目录>` 给编好的。产物 5 个文件：`OneSay.exe`、`OneSay.exe.config`、`Microsoft.Web.WebView2.Core.dll`、`Microsoft.Web.WebView2.WinForms.dll`、`WebView2Loader.dll`。
- **启动器的做法（2026-11-14 起）**：OneSay.exe 在自己进程里加载 `runtime\bin\server\jvm.dll`（JNI_CreateJavaVM，jpackage 同款），跑 jar 的 main，自己不退出；主窗口用 WebView2 开在 OneSay.exe 里（没有 WebView2 运行时才退回 Edge 应用窗口）。所以任务管理器、Windows 防火墙弹窗、按 exe 认程序的 Dock 软件看到的都是 OneSay（名字 / 图标 / 版本信息都是 OneSay），不再是「OpenJDK Platform binary / Eclipse Adoptium」。AppUserModelID 固定为 `OneSay.Desktop`，进程里设置，开始菜单和桌面快捷方式也写同一个（安装程序调用 `OneSay.exe --shortcut <lnk>` 写入）。
- 版本号取构建出来的 jar 里的 `Implementation-Version`（由 `build.sh` 写入），不用另外填。
- 需要：JDK 17+、unzip、makensis 3.x（可用 `MAKENSIS=` 指定路径）。Debian/Ubuntu 上装 `apt install nsis` 就行。
- 默认输出到 `build/installer-out/`。中间文件在 `build/installer/`，这个目录已被 .gitignore 忽略。
- 项目根目录下有 `LICENSE` 时，安装程序会显示协议页。脚本会自动给它补上 UTF-8 BOM。
- **不附 source.zip**（2026-11-13 定）。安装目录里直接放 `LICENSE.txt`（Apache-2.0）、`NOTICE.txt`、`THIRD_PARTY_NOTICES.md`；自带 Java 运行时（Eclipse Temurin，GPLv2 + Classpath Exception）的许可在 `runtime\legal\`。
- 发布者（「设置 › 应用」里显示）：**OneSay**；文件属性里的版权：**© 2026 OneSay contributors**。
- **首版不签名**：安装程序和 `Uninstall.exe` 都没有代码签名。下载后运行时 Windows SmartScreen 会提示「Windows 已保护你的电脑 / 未知发布者」，点「更多信息 → 仍要运行」继续。以后签名时，证书上的名字要和发布者一致。

## 安装程序做什么
- **只为当前用户安装，不需要管理员权限**：`RequestExecutionLevel user`（manifest 为 asInvoker），不弹 UAC，不写 HKLM。
- 默认目录：`%LOCALAPPDATA%\Programs\OneSay`。选到没有写权限的位置（比如 Program Files）时，当场提示换一个。
- 写入当前用户的卸载项 `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\OneSay`，在「设置 › 应用」里可以卸载。
- 组件：程序（必选）、开始菜单快捷方式（必选，路径和程序自己检查的那个相同，所以不会出现两个）、桌面快捷方式（默认勾选）、开机自动启动（默认不勾）。开机自启写的是 `HKCU\...\Run` 里的 `OneSay = "<目录>\OneSay.exe" --tray`，和程序设置页的开关是同一个值。
- 会在 `%APPDATA%\OneSay\desktop-shortcut.done` 写一个标记，这样程序第一次启动时不会自己再建桌面快捷方式（快捷方式改由安装程序负责）。
- 完成页上可以勾「立即启动 OneSay」。
- 界面用简体中文；系统语言是英文时自动切到英文。

## 升级和覆盖安装
- 根据 HKCU 卸载项里的 `DisplayVersion` / `InstallLocation` 判断有没有装过。装过的话跳过选组件和选目录两页，直接装到原来的目录。原来有没有桌面快捷方式、有没有开机自启，都照旧保留。
- 只替换 `app\`、`runtime\` 和 exe，用户数据不在安装目录里，不会被动到。
- 已装版本比这个安装包新时，会先问一句。
- 安装前检查 OneSay 是否在运行：方法是看安装目录里的 `OneSay.exe`（新版）和 `runtime\bin\javaw.exe` / `java.exe`（旧版）能不能以写方式打开。打不开就提示用户从托盘退出，然后点「重试」，不会强行结束进程。
- 支持静默安装：`OneSay-Setup-x.y.z-x64.exe /S`。OneSay 正在运行时，静默安装直接退出，返回码 2。

## 卸载
- 删除：程序文件（只删安装程序放进去的那些，目录里其他东西不动）、开始菜单和桌面上的 `OneSay.lnk`、卸载项，以及指向本目录的开机自启项。
- **默认保留用户数据**。可以勾选「同时删除我的数据」（默认不勾，勾了以后还会再确认一次），会删除：
  - `%APPDATA%\OneSay`：设置、配对钥匙、对话和记忆、日志
  - `%USERPROFILE%\OneSay\workspace`（`%USERPROFILE%\OneSay` 里没有别的东西时，这个目录也一起删）
  - `%LOCALAPPDATA%\OneSay\tools`：链接下载用的 yt-dlp / FFmpeg
  - 用户目录名里有非 ASCII 字符（比如中文用户名）时，程序把工作区和下载工具放在 `%ProgramData%\OneSay\accounts\<账户标记>\` 下（**每个 Windows 账户一个子目录**，标记规则见 docs/PCLINK.md「按账户的目录」，例如 `C:\Users\张三` → `_5F20_4E09`），删的是本账户的这个子目录，不碰同一台电脑上其他账户的
  - **不删** `%USERPROFILE%\OneSay\Downloads`（链接下载下来的文件是你自己的；这个目录不按中文用户名回退）
- 静默卸载：`Uninstall.exe /S`，数据保留。

## 链接下载用的工具（按需下载，不随安装包）
安装包里**没有** yt-dlp 和 FFmpeg。要用「链接下载」时，在 OneSay「设置 › 下载」里点「安装」，程序才从官方发布页下载：
- **yt-dlp**（Unlicense）：github.com/yt-dlp/yt-dlp 的发布页，按官方 `SHA2-256SUMS` 校验。
- **FFmpeg**：只用 FFmpeg-Builds 的 **LGPL 静态构建**（`ffmpeg-nX.Y-latest-win64-lgpl-X.Y.zip`，ARM64 用 winarm64），按官方 `checksums.sha256` 校验；发布页上找不到 LGPL 构建就不自动装（不会退到 GPL 构建）。
- 下载时边下边算 SHA-256，和官方校验和对上才放到位；装好后每次运行前再核对一次，对不上拒绝运行。
- 存放位置：`%LOCALAPPDATA%\OneSay\tools`；用户目录含中文等非 ASCII 时 `%ProgramData%\OneSay\accounts\<账户标记>\tools`。下载的视频 / 音频放 `%USERPROFILE%\OneSay\Downloads`（设置里可改），**不回退**。
- 卸载时勾「同时删除我的数据」会删掉这些工具；不勾则保留。
