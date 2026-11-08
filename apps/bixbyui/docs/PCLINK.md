# 电脑联动（PcLink，feat/pclink → link-v2）

**协议 v2（link-v2，开源版 vc22，配电脑端 0.5.0）**：TLS + 证书指纹钉死、组播发现 + announce、电脑 → 手机指令。v1 配对码与 0.4.x 电脑端照旧能用（明文、有签名）。

手机端插件，配对电脑端 **OneSay 0.4.1** 起的版本，局域网直连（同一个 Wi-Fi），不经过任何服务器。

## 用户怎么用
1. 电脑上打开 OneSay → 设置 → 配对手机 → 点「显示」，屏幕上出现二维码（旁边有「复制配对串」）。
2. 手机控制台 → 设置 → 功能 → **电脑联动** → 扫码配对（第一次会要相机权限）。扫不了：电脑上点「复制配对串」，发到手机，点「粘贴配对码」。
3. 回到「电脑联动」页：上面显示电脑在线 / 离线；下面「让电脑做事」：锁屏、截图、剪贴板发到电脑、在电脑上打开网址、睡眠、关机（睡眠 / 关机先确认）。
4. 「提醒同步到电脑」三个开关（默认全开）：日程与定时提醒、自动任务结果、降水提醒 —— 电脑在线时弹 Windows 通知，离线就不发。
5. 语音：「电脑锁屏」「让电脑截个图」「电脑在线吗」。关机 / 睡眠只引导去控制台。
6. v2「连接安全」：显示「已加密」+ 电脑 / 本机证书指纹（前 8 位，与电脑设置页一致）；旧版配对码显示「未加密」并提示升级电脑端后重扫。
7. v2「电脑可以让手机做的事」：总开关「接受电脑指令」（= 常连通道；开源版默认关；打开后「写剪贴板 / 打开网址 / 打开 App」仍默认关，要逐项打开）+ 每项开关：找手机（响铃）、点亮屏幕、调媒体音量、
   在手机上弹通知、查看电量和状态、写手机剪贴板、打开网址、打开 App（后三项标「会改动手机」）。页脚列最近 10 条电脑指令（只记名字和结果）。


## 协议 v2（link-v2）
完整说明见电脑端仓库 `docs/PCLINK.md`（两端同一份）。要点：
- 配对串末尾 `&fp=<电脑证书指纹 43 字符>&v=2`；`v>2` 拒绝、`v=2` 缺 fp 拒绝、v1 串（无 fp）照旧接受走明文（`PcPairing`）。
- 证书：EC P-256 自签（`PcTls`，与电脑端 `Tls` 同算法，互通测试比对），指纹 = base64url(SHA-256(DER))。本机私钥 SecretPrefs 加密存 `bixby_pclink/tls_key`。
- 连接：配对带 fp → 同一端口 47820 先 TLS（只认该指纹、出示本机证书），握手不过 = `fail=tls`，**不退回明文**；
  hello 带签名的 `cfp`（本机证书指纹）和 `hold`（常连）。电脑首次记住本机指纹；换证书 → 4003、用过 TLS 又走明文 → 4002，手机都报 `fail=pin` 并说明怎么修。
- 发现：探测另发组播 224.0.0.167:47821；兜底对记下的地址 `GET /healthz`。
- 电脑 → 手机（`PcRx` / `PcRxPolicy` / `PcRxAndroid`）：常连线程（`Threads.start("pclink-rx")`，插件 onInit 在后台线程拉起）只在配对 + 总开关开 + Wi-Fi 时跑：
  找电脑 → hold 连接 → 每 25 s ping → 断了退避 2→60 s，退避期间拿 MulticastLock 听电脑 announce，听到立刻重连。
  电脑来的每条指令：只认配对那台电脑 → 验签 / 时间窗 / nonce → 开关 → 参数检查 → 执行 → 回签名结果。
  找手机 = USAGE_ALARM + 临时闹钟音量最大（静音 / 振动也响；系统「完全勿扰」会挡）、振动、亮屏、带「停止」的通知，到时 / stop / 点通知停并还原音量。

## 协议 v1（与电脑端逐字节兼容，源码为准）
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
- v2「接受电脑指令」开着时：只在 Wi-Fi 下保持一条到电脑的连接（每 25 秒一个几字节的 ping），电脑不在时退避等待（最长 60 秒探一次 + 听 announce）。
  深度 Doze 时系统会断网，这期间电脑找不到手机（电池白名单可减轻）；不想要就关掉总开关，回到 v1 行为。

## 测试
- `PcProtocolTest`：规范 JSON、base64、签名 / 时间窗 / 重放、配对串、转发口径、语音口令。
- `PcDesktopInteropTest`：载入电脑端 jar 逐字节比对 Json / 签名 / 配对串解析；把电脑端以 `--config <绝对路径> app --tray`（`ONESAY_TEST_ACTIONS=1`、无界面）起成真局域网服务，跑 UDP 发现、hello、ping、caps、notify、memory_sync、screenshot、错钥被拒、离线不排队。jar 缺省 `/tmp/osd-build/onesay-desktop.jar`（`cd onesay-desktop && BUILD_DIR=/tmp/osd-build sh build.sh`），缺 jar 时整类跳过。
- `PcLinkWiringTest`：接线与约束（源码检查）。
- v2：`PcLinkV2Test`（证书 / 指纹、配对串 v2 + v1、hello cfp/hold、电脑指令验签 / 重放 / 过期 / 伪造 / 非配对电脑、开关与参数、announce、接线）；
  `PcLinkV2InteropTest`（起电脑端 0.5.0 jar：指纹与配对串逐字节一致、TLS 钉指纹下令 + 截图、错指纹 / 换证书 / 降级被拒、旧版明文仍可用、
  经电脑端 `/api/phone` 走常连通道让手机执行、一次性连接不顶掉常连、断开后报离线）。jar 缺省 `/tmp/osd-build/onesay-desktop.jar`，不是 0.5.0+ 整类跳过。

## 下一步（v1 没做）
- 手机第三方 App 通知转发（需通知使用权）。
- 中转（不在同一 Wi-Fi）；记忆 / 计划同步；电脑上「设闹钟」= 让手机设闹钟（电脑 → 手机方向需要手机端常驻监听）。
- 真机与 Windows 电脑的扫码互通、Windows 通知样式只能用户实测。
