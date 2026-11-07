# 电脑联动（PcLink，feat/pclink）

手机端插件，配对电脑端 **OneSay 0.4.1** 起的版本，局域网直连（同一个 Wi-Fi），不经过任何服务器。

## 用户怎么用
1. 电脑上打开 OneSay → 设置 → 配对手机 → 点「显示」，屏幕上出现二维码（旁边有「复制配对串」）。
2. 手机控制台 → 设置 → 功能 → **电脑联动** → 扫码配对（第一次会要相机权限）。扫不了：电脑上点「复制配对串」，发到手机，点「粘贴配对码」。
3. 回到「电脑联动」页：上面显示电脑在线 / 离线；下面「让电脑做事」：锁屏、截图、剪贴板发到电脑、在电脑上打开网址、睡眠、关机（睡眠 / 关机先确认）。
4. 「提醒同步到电脑」三个开关（默认全开）：日程与定时提醒、自动任务结果、降水提醒 —— 电脑在线时弹 Windows 通知，离线就不发。
5. 语音：「电脑锁屏」「让电脑截个图」「电脑在线吗」。关机 / 睡眠只引导去控制台。

## 协议（与电脑端逐字节兼容，源码为准）
- 配对串 `onesay://pair?key=<base64url 32B>&peer=<电脑 id>&lan=ip:port,...[&relay=..&token=..]`；解析规则 = 电脑端 `Config.importPairing`。手机 v1 只走局域网，只有中转的配对串会被拒绝（提示去电脑端开局域网）。
- 手机 id 自动生成 `phone-xxxxxx`。电脑端 `peers` 为空时任何持钥设备都能下令，所以不需要向电脑「报到」；配对后页面会立刻去找电脑（ping + info 取电脑名）。
- 发现：UDP `{"t":"probe","from","to","n","tag"}` → 配对时的地址:端口+1、各网卡广播、255.255.255.255；只认 tag 正确的 `here`。探测不到再 TCP 试连记下的地址。
- 连接：WebSocket `GET /v1/ws?device=<手机 id>`，首帧 hello（签名 body `{kind:"hello"}`），电脑回 `devices`；密钥不对回 `error auth` + 4001。
- 签名：`base64url(HMAC-SHA256(key, "onesay.v1\n"+id+"\n"+from+"\n"+to+"\n"+规范JSON(body 去 sig)))`；规范 JSON = 键字典序、无空白（`PcJson` 照抄电脑端 `Json`）。校验顺序：签名 → v=1 → ts ±120 s → nonce 一次性。**手机和电脑时钟差超过 2 分钟会互相拒绝（STALE）**。
- 用到的电脑端指令：`ping` `info` `caps` `lock` `screenshot`（data.b64 JPEG）`clipboard_set` `open_url` `sleep`/`shutdown`（args.confirm=true）、本地处理 `notify`（kind reminder/auto/rain、title、text）。`memory_sync` 协议已互通测试，v1 界面没用。

## 代码
- 纯 Java（JVM 可测）：`pclink/PcJson` `PcB64` `PcProtocol` `PcPairing` `PcWs` `PcLanClient` `PcForward` `PcVoice`。
- Android：`pclink/PcLink`（配对存储，密钥经 SecretPrefs Keystore 加密，键 `bixby_pclink/pair_key`）、`PcLinkUi`（控制台页）、`PcScanActivity`（Camera + ZXing core 3.5.3，`libs/`）、`kernel/plugins/PcLinkPlugin`。
- 提醒中枢只加了一个旁路监听 `NoticeHooks.addSurfaceListener`（present 开头回调本次新上队列的提醒，不改队列）。
- 转发口径：CALENDAR→reminder；CALLBACK 标题/正文含「提醒」→reminder，否则 auto；WEATHER→rain；其余不转。同一去重键进程内只发一次。

## 耗电 / 隐私
- 默认开（上机版与开源构建相同），但没配对 = 什么都不做、不联网。
- 不常驻连接：页面开着且可见时每 10 秒探一次（最多 10 分钟），离开页面即停；按钮 / 口令 / 新提醒时才临时连一次，用完即断。
- 日志只打类别、结果、长度；不打密钥、配对串、提醒内容。
- 截图：有存储权限存 `Pictures/OneSay/`（可分享），否则存 App 私有图片目录（只能在页内查看）。

## 测试
- `PcProtocolTest`：规范 JSON、base64、签名 / 时间窗 / 重放、配对串、转发口径、语音口令。
- `PcDesktopInteropTest`：载入电脑端 jar 逐字节比对 Json / 签名 / 配对串解析；把电脑端以 `--config <绝对路径> app --tray`（`ONESAY_TEST_ACTIONS=1`、无界面）起成真局域网服务，跑 UDP 发现、hello、ping、caps、notify、memory_sync、screenshot、错钥被拒、离线不排队。jar 缺省 `/tmp/osd-build/onesay-desktop.jar`（`cd onesay-desktop && BUILD_DIR=/tmp/osd-build sh build.sh`），缺 jar 时整类跳过。
- `PcLinkWiringTest`：接线与约束（源码检查）。

## 下一步（v1 没做）
- 手机第三方 App 通知转发（需通知使用权）。
- 中转（不在同一 Wi-Fi）；记忆 / 计划同步；电脑上「设闹钟」= 让手机设闹钟（电脑 → 手机方向需要手机端常驻监听）。
- 真机与 Windows 电脑的扫码互通、Windows 通知样式只能用户实测。
