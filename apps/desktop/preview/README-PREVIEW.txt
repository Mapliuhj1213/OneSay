OneSay Preview（电脑端预览版）0.5.1-preview.1120
================================================

这是预览版，和正式版 OneSay 分开装、分开存数据，可以同时在一台电脑上：

  程序位置   %LOCALAPPDATA%\Programs\OneSay-Preview
  数据目录   %APPDATA%\OneSay-Preview（设置、主题包、MCP 列表、浏览器缓存）
  界面端口   127.0.0.1:47930（正式版 47830）
  局域网端口 47920 / 47921（正式版 47820 / 47821）
  窗口标题   OneSay Preview

不会碰正式版：不覆盖 %LOCALAPPDATA%\Programs\OneSay，不改 %APPDATA%\OneSay，
不建开始菜单 / 桌面快捷方式，不写「应用和功能」里的卸载项。

安装
----
1. 把压缩包整个解压（得到 OneSay-Preview 文件夹）。
2. 在这个文件夹里右键 install-preview.ps1 →「使用 PowerShell 运行」，或在 PowerShell 里：
     powershell -ExecutionPolicy Bypass -File .\install-preview.ps1
   可选参数：
     -NoLan    以后用 run-preview.cmd 启动时不开局域网（等于 ONESAY_LAN=off）
     -Start    装完马上启动
3. 以后启动：双击 %LOCALAPPDATA%\Programs\OneSay-Preview\OneSay.exe
   或 run-preview.cmd（不开局域网，免得新位置的程序弹防火墙询问）。
也可以不安装：解压后直接双击 OneSay.exe。

关掉局域网
----------
设环境变量 ONESAY_LAN=off 再启动，这次就不监听局域网端口（手机直连用不了，中转和本机界面照常）。

卸载
----
  powershell -ExecutionPolicy Bypass -File "%LOCALAPPDATA%\Programs\OneSay-Preview\uninstall-preview.ps1"
默认只删程序文件夹、结束预览版进程，保留数据；加 -RemoveData 连 %APPDATA%\OneSay-Preview 一起删。
脚本只认 OneSay-Preview 这几个目录，不会动正式版。

这一版新加的
------------
- 外观：浅色 / 深色 / 跟随系统，自带 Nord、Catppuccin Latte、Catppuccin Mocha、Dracula、Solarized Light 配色；
  可导入主题包（JSON，只能改颜色变量，导入前校验）。
- MCP 连接器：选一个 MCP 服务器文件夹或配置文件，自动识别、加载，对话里的助手就能调用它的工具（调用照常过审批）。
- 新图标（Lucide）与设置页整理。
- 一批稳定性修复（取消 / 超时 / 下载跳转核对 / 命令结束子进程等）。

已知限制
--------
- 预览版不带安装程序，不自动更新。
- 开机自启的开关写的是「OneSay Preview」这一项，和正式版分开。
- 三方组件许可见 THIRD_PARTY_NOTICES.md。
