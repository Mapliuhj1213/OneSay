# OneSay Desktop 0.3.0（电脑端）

一句话让电脑干活：手机或另一台设备下令，电脑执行后回签名结果（截图直接回传）。
电脑关机或断网，下令端立即看到「离线」。

**连接方式**
- 局域网直连（默认）：同一个路由器下即可，不需要账号、服务器或公网。电脑开 TCP 47820，UDP 47821 应答配对设备的发现。只回应持有配对密钥的设备，没配对的人扫不到。
- 中转（可选插件）：不在同一 Wi-Fi 也要控制时才配。自建 Cloudflare Worker，见 onesay-relay。两条都开时，下令端先试局域网（约 1.5 秒），找不到再走中转。
- 局域网里内容有签名但未加密（同 Wi-Fi 的人理论上能抓到截图、剪贴板）；加密在计划中。

## 安全
- 中转只看收件人转发；每条指令和结果都用配对密钥做 HMAC-SHA256 签名，绑定消息 id、发件人、收件人，
  2 分钟时间窗 + 一次性编号防重放。只有中转令牌、没有配对密钥的人下不了令。
- 只执行白名单能力，不接任意命令。睡眠/关机必须带 `confirm=true`。
- 取文件、快捷指令、按名字开程序默认都关，在配置里指定文件夹/程序才开。

## Windows 上手（约 3 分钟）
需要 Java 17+。装过 Android Studio 的可直接用它自带的：`"C:\Program Files\Android\Android Studio\jbr\bin\java.exe"`
（下面的 `onesay.bat` 会自动找它）。

1. 首次设置：`onesay.bat setup`，本机名和端口直接回车；中转地址留空 = 只用局域网。首次 run 时防火墙弹窗勾「专用网络」
2. 常驻：`onesay.bat run`（托盘出现绿点 = 在线）；开机自启：`onesay.bat autostart on`
3. 下令端（另一台电脑，或先在同一台电脑另开窗口试）：
   - 电脑上 `onesay.bat pair` 拿配对串（整串等于钥匙，别发群里）
   - 下令端 `onesay.bat --config cli.properties join "<配对串>" --id cli-laptop`
   - `onesay.bat --config cli.properties send pc-机器名 screenshot` → 当前目录出现截图

## 长按空格说话（Windows）
像微信按住说话：按住空格超过 0.35 秒开始听，松手识别并执行，屏幕底部浮条显示听到什么、做了什么；按 Esc 取消。
- 需要自己的百炼（DashScope）API Key，`setup` 里填；不填就不开，空格键完全不受影响。识别模型 qwen3-asr-flash，与手机端同一做法。
- 能说：锁屏、截图（存到 图片\OneSay 并复制）、静音/大声点/小声点、暂停/下一首/上一首、打开 微信（需配置路径）/B站/百度/网址、执行 某个快捷指令、确认关机/取消关机、确认睡眠。
- 短按照常打出空格；打字快时空格和下一个键连按，顺序不会乱。带 Ctrl/Alt/Shift/Win 的组合（如切输入法）不拦。
- 不拦：全屏窗口（游戏、视频）、远程桌面（`ptt_skip` 可加程序名）、管理员权限的窗口。
- 已知限制：空格要松手才出字（短按约慢一拍）；中文输入法选词用空格时请实测；钩子是 PowerShell 编译的小段 C#，个别杀毒软件可能拦。配置 `ptt=off` 可关。

## 指令
| 指令 | 参数 | 说明 |
|---|---|---|
| ping / info / caps | | 在线、主机信息、能力清单 |
| screenshot | | 全部屏幕，长边 1600，JPEG |
| open_url | url=https://... | 只开 http/https |
| open_app | name=微信 | 需在配置 `apps=微信=C:\\...\\WeChat.exe;...` |
| clipboard_get / clipboard_set | text=... | 剪贴板互传 |
| file_list / file_get | name=文件名 | 需 `files_dir`，≤5 MB，只限该文件夹一层 |
| shortcut_list / shortcut_run | name=脚本名 | 需 `scripts_dir`，.ps1/.bat/.cmd |
| lock | | 锁屏 |
| volume | action=up/down/mute steps=5 | |
| media | action=play_pause/next/prev | |
| sleep / shutdown | confirm=true | 关机 60 秒倒计时，`shutdown /a` 可取消 |

## 开发
`sh build.sh test`（JDK 17，零第三方依赖；单测需 junit 4.13.2 + hamcrest 1.3）。
`sh test/e2e.sh`：中转 9 项 + 局域网 4 项（含错密钥、重放、离线）。单测 18 项含局域网全链路与发现。

## 下一步
- 长按空格按住说话（底层键盘钩子）
- 手机端接入
