# OneSay Windows 安装程序（NSIS）

生成 `OneSay-Setup-<版本>-x64.exe` 和同名 `.sha256`。和 zip 的内容相同，只是多了安装和卸载。

## 构建（一条命令）
```sh
ONESAY_BASE=/path/OneSay-0.5.0-windows-x64.zip bash installer/build-installer.sh [输出目录]
```
- `ONESAY_BASE`：上一版 zip，或者解开后的 `OneSay` 目录。用到里面的 `OneSay.exe`、`OneSay.exe.config`（`launcher/` 编出来的启动器）和 `runtime\`（jlink 精简过的 Temurin 17）。这几样在 Linux 上不重新构建，做法和 zip 交付相同。jar 每次用 `build.sh` 从源码构建。
- 版本号取构建出来的 jar 里的 `Implementation-Version`（由 `build.sh` 写入），不用另外填。
- 需要：JDK 17+、git、unzip、makensis 3.x（可用 `MAKENSIS=` 指定路径）。Debian/Ubuntu 上装 `apt install nsis` 就行。
- 默认输出到 `build/installer-out/`。中间文件在 `build/installer/`，这个目录已被 .gitignore 忽略。
- 项目根目录下有 `LICENSE` 时，安装程序会显示协议页。脚本会自动给它补上 UTF-8 BOM。

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
- 安装前检查 OneSay 是否在运行：方法是看安装目录里的 `runtime\bin\javaw.exe` 能不能以写方式打开。打不开就提示用户从托盘退出，然后点「重试」，不会强行结束进程。
- 支持静默安装：`OneSay-Setup-x.y.z-x64.exe /S`。OneSay 正在运行时，静默安装直接退出，返回码 2。

## 卸载
- 删除：程序文件（只删安装程序放进去的那些，目录里其他东西不动）、开始菜单和桌面上的 `OneSay.lnk`、卸载项，以及指向本目录的开机自启项。
- **默认保留用户数据**。可以勾选「同时删除我的数据」（默认不勾，勾了以后还会再确认一次），会删除：
  - `%APPDATA%\OneSay`：设置、配对钥匙、对话和记忆、日志
  - `%USERPROFILE%\OneSay\workspace`（`%USERPROFILE%\OneSay` 里没有别的东西时，这个目录也一起删）
  - 用户目录名里有非 ASCII 字符（比如中文用户名）时，程序把工作区放在 `%ProgramData%\OneSay\workspace`，这种情况下删的是这个目录
- 静默卸载：`Uninstall.exe /S`，数据保留。
