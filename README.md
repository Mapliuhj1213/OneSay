# OneSay Desktop 0.1.0（电脑端最小版）

一句话让电脑干活：手机/另一台设备经 OneSay Relay 下令，电脑执行后回签名结果（截图直接回传）。
电脑关机或断网，下令端立即看到「离线」。

## 安全
- 中转只看收件人转发；每条指令和结果都用配对密钥做 HMAC-SHA256 签名，绑定消息 id、发件人、收件人，
  2 分钟时间窗 + 一次性编号防重放。只有中转令牌、没有配对密钥的人下不了令。
- 只执行白名单能力，不接任意命令。睡眠/关机必须带 `confirm=true`。
- 取文件、快捷指令、按名字开程序默认都关，在配置里指定文件夹/程序才开。

## Windows 上手（约 3 分钟）
需要 Java 17+。装过 Android Studio 的可直接用它自带的：`"C:\Program Files\Android\Android Studio\jbr\bin\java.exe"`
（下面的 `onesay.bat` 会自动找它）。

1. 首次设置：`onesay.bat setup`，填中转地址（如 `https://onesay.example.com`）、中转令牌、本机名（默认 pc-机器名）
2. 常驻：`onesay.bat run`（托盘出现绿点 = 在线）；开机自启：`onesay.bat autostart on`
3. 下令端（另一台电脑，或先在同一台电脑另开窗口试）：
   - 电脑上 `onesay.bat pair` 拿配对串（整串等于钥匙，别发群里）
   - 下令端 `onesay.bat --config cli.properties join "<配对串>" --id cli-laptop`
   - `onesay.bat --config cli.properties send pc-机器名 screenshot` → 当前目录出现截图

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
`sh test/e2e.sh`：本地 wrangler 中转 + 电脑端 + 命令行全链路 9 项（含错密钥、重放、离线）。

## 下一步
- 局域网直连（开源版默认）：电脑内置小服务 + 路由器内广播发现，中转改为可选插件
- 长按空格按住说话（底层键盘钩子）
- 手机端接入
