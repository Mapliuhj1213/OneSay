# VYRSE 兼容：让 OneSay 充当 VYRSE 的「手机」

> 适用：OneSay 助手（`apps/bixbyui`）分支 `compat-vyrse`。
> 协议依据：VYRSE（https://github.com/felixthosharon/vyrse ，MIT），读的是 commit **41c5651**（2026-11-05）的源码原文：
> 电脑端 `desktop/src/main/phone-bridge.ts`，手机端 `app/android/app/src/main/java/dev/vyrse/phone/{VyrseLinkService,PhoneActions,PhoneControl,VyrseNotificationListener,BootReceiver}.java`，
> 手机端配对 `app/src/services/phone.ts`、`app/src/components/PhonePairing.tsx`，PC 设置页 `app/src/components/settings/PhoneSection.tsx`，
> 动作来源 `hermes-plugin/vyrse/phone.py`、`app/src/services/hermes/ui-control.ts`、`app/src/lib/vyrse/store.tsx`。
> VYRSE 自己没有协议文档。下面每一条都来自上述源码，不是猜的；VYRSE 后续版本改了协议，以它的源码为准。

## 1. 它是什么

VYRSE 是 Windows 上的 AI 助手（Electron 外壳，底层是 Hermes Agent）。它自带一个 Android App，用家里 Wi-Fi 跟电脑连起来：
电脑上的 AI 可以让手机响铃、开手电筒、读通知、弹通知，用户在手机上打开后还能「完全操控」手机。

OneSay 兼容的是其中**电脑 → 手机命令**这一段：用户在 OneSay 里输入电脑地址和 6 位配对码以后，VYRSE 电脑端会把 OneSay 当成一台已配对的手机，往它派命令；OneSay 用自己的能力执行这些命令，再把结果回给电脑。
VYRSE 手机 App 的其余功能（在手机上和电脑的 AI 聊天、语音、拍照发电脑 `/phone/snap`、日志上报 `/phone/log`、`/api/*` 和 WebSocket 转发）**不在兼容范围内**，OneSay 不调用这些接口。

## 2. 传输与地址

| 项 | 值 | 出处 |
|---|---|---|
| 端口 | **47630**，监听 `0.0.0.0` | `PHONE_PORT` |
| 协议 | **明文 HTTP**（WebSocket 也是 `ws://`），**不加密** | 源码注释原文："Traffic on the Wi-Fi is not encrypted (the user chose plain Wi-Fi over Tailscale); keys protect access." |
| 来源限制（电脑端） | 只接受 RFC 1918（10/8、172.16/12、192.168/16）、链路本地 169.254/16、回环 127.0.0.1 / ::1，其它直接断开连接 | `isPrivateAddress` |
| 发现 | **没有**。没有 mDNS、没有广播，手机端手动填 PC 地址 | `PhonePairing.tsx` |
| 地址写法 | `192.168.1.20`、`192.168.1.20:47630`、`http://192.168.1.20` 都可以，没写端口就补 47630，路径部分丢掉 | `phone.ts` `bridgeBase` |
| Windows 防火墙 | 电脑端有一个按钮，弹一次 UAC，把当前 Wi-Fi 设成「专用网络」，并且只对本地子网放行 47630 | `allowOnHomeWifi` |
| 总开关 | 电脑端默认关闭，要在 Settings → Phone 打开「phone access」以后才开始监听 | `store.enabled` |

**二维码：VYRSE 没有。** PC 设置页只用文字显示 6 位码和倒计时（`PhoneSection.tsx`），desktop/、app/ 两个目录的源码和依赖里都搜不到 QR 或二维码库。所以 OneSay 这边**只做手动输入**，不加扫码入口，也不替 VYRSE 发明二维码格式。

## 3. 认证

- 认证头：**`X-Hermes-Session-Token: <手机 key>`**。也可以放在查询参数 `?token=` 里，但 OneSay 只用请求头。
- 手机 key 由电脑在配对时生成：`crypto.randomBytes(32)`，base64url 编码，43 个字符。电脑只保存它的 **SHA-256 十六进制串**，比对时用 `timingSafeEqual`。设备表用 Windows DPAPI（Electron `safeStorage`）加密，存在 `phones.bin`。
- 每台手机的 key 独立，可以在 PC 上单独移除。移除以后，这台手机的下一个请求会收到 **401** `{"error":"This phone isn’t paired (or was removed). Pair it again."}`。
- 这个 key 只能用于桥接；Hermes 自己的 token 不会发给手机。

## 4. 配对

1. 用户在电脑上操作：Settings → Phone → 打开 phone access → 点「Pair a phone」。电脑生成码：`crypto.randomInt(0, 1_000_000)`，不足 6 位前面补 0。**有效期 2 分钟（`PAIR_MS = 120000`），最多试 5 次（`PAIR_TRIES = 5`）**，第 5 次错了这个码就作废。同一时间只有一个码。
2. （可选，OneSay 会做）**`GET /phone/hello`**：不需要认证，返回 `{"app":"vyrse","pc":"<主机名>"}`。OneSay 用它确认对面确实是 VYRSE，不是的话就不发配对码。
3. **`POST /phone/pair`**，`Content-Type: application/json`，请求体 `{"code":"123456","name":"<手机名>"}`。请求体上限 4096 字节；name 会去掉控制字符和 `<>`，截到 40 个字符，空的话用 "Android phone"。
   - 成功 **200**：`{"token":"<43 字符 base64url>","deviceId":"<uuid>","pc":"<主机名>"}`。配对码随即作废，设备落盘保存。
   - 没在配对，或码已过期 **403**：`{"error":"Pairing isn’t open. On the PC: Settings → Phone → Pair a phone."}`（注意 isn’t 里是弯引号 U+2019）。
   - 码错了 **403**：`{"error":"Wrong code."}`，同时计入次数。
4. `GET /phone/ping`（需要认证）：`{"ok":true,"hermes":<bool>,"hermesVersion":...,"pc":"..."}`。OneSay 不调用。

## 5. 电脑 → 手机命令（核心）

### 5.1 长轮询取命令

**`GET /phone/commands`**，带认证头。

- 电脑端记下这台手机的 `linkSeen`。队列里有命令就立即返回，否则**挂起最多 25 秒**（`POLL_MS = 25000`），到时返回空表。返回格式：
  ```json
  {"commands":[{"id":"<uuid>","action":"find","args":{"state":"start"}}]}
  ```
- 同一台手机在任意时刻只有一个挂起的轮询；新轮询进来时，旧的那个会被立即用空表答复。
- 队列**每台手机最多 20 条**，超出时丢最旧的（`q.slice(-20)`）。
- 「这台手机在线」的判定：最近 `POLL_MS + 15 s = 40 秒` 内来轮询过。派命令时选的是**最近一次来轮询的那台**（`linkedDevice`）。所以如果用户同时开着 VYRSE 自己的手机 App 和 OneSay，命令会发给最后来轮询的那一个。
- VYRSE 手机端的参数（OneSay 照抄）：读超时 **35 秒**，连接超时 5 秒；出错时退避 **2 秒 → 60 秒，每次翻倍**；成功一次就把退避重置到 2 秒。

### 5.2 回结果

**`POST /phone/command-result`**，带认证头，请求体：
```json
{"id":"<命令 id>","result":{ ...动作结果... }}
```
- 电脑端回复 `{"ok":true}`。请求体上限 3 MB（截图要用）。
- 电脑端等每条命令的结果最多 **20 秒**（`COMMAND_MS = 20000`），超时就告诉 AI "The phone didn’t answer in time."。结果里如果没有 `result` 字段，电脑会补成 `{ok:false, error:"empty result"}`。
- VYRSE 手机端回结果的读超时是 15 秒。

### 5.3 结果格式（所有动作通用）

- 成功：`{"ok":true, ...字段}`
- 失败：`{"ok":false,"error":"<给 AI 看的英文说明>"}`
- 完全操控里的敏感点击：`{"ok":false,"needs_confirmation":true,"label":"..."}`。电脑会问用户，用户同意后带 `confirmed:"yes"` 重发（Hermes 插件 `phone.py`）。OneSay v1 不支持完全操控，所以不会返回这个。
- 未知动作：`{"ok":false,"error":"Unknown phone action: <action>"}`（VYRSE 手机端原文）。

### 5.4 动作全集（VYRSE 定义）与 OneSay 映射

动作名来自 `hermes-plugin/vyrse/phone.py` 的 `ACTIONS`，以及 `ui-control.ts` 的 `PHONE_ACTIONS`。参数经过 PC 渲染层清洗，只剩字符串或数字，最多 8 个键，键名截到 20 个字符，字符串值截到 500 个字符。

| 动作 | 参数 | VYRSE 手机端返回 | OneSay v1 |
|---|---|---|---|
| `find` | `state`: `start`（默认）/ `stop` | `{ok, ringing:true, seconds:30}` / `{ok, ringing:false}` | **支持**。用闹钟铃声（没有就用来电铃声）、`USAGE_ALARM` 循环播放，同时把 `STREAM_ALARM` 拉到最大，再加振动；**30 秒后自动停，并恢复原来的闹钟音量**。连续响两次时，恢复的是第一次记下的原音量；铃声起不来也会恢复音量 |
| `flashlight` | `state`: `on` / `off` / `toggle`（默认） | `{ok, flashlight:"on"\|"off"}`；没有闪光灯时返回 `This phone has no flashlight.` | **支持**（CameraManager.setTorchMode）。其它 state 值会被拒绝 |
| `notifications` | `limit` 1–30（默认 12） | `{ok, count, notifications:[{app,title,text≤300 字符…,minutesAgo}]}`，跳过常驻通知和分组摘要 | **支持**，格式相同。另外：**支付、银行、钱包、密码类 App 和 OneSay 自己的通知不交给电脑**；标题或正文里出现「验证码 / 校验码 / code / OTP…」时，把 4–8 位数字打码成 `****`。没有通知使用权时，返回 `Notification access is off…` |
| `notify` | `title`（默认 "VYRSE"），`text` | `{ok, shown:true}` | **支持**。标题截到 80 个字符、正文截到 1000 个字符；没有通知权限时返回 ok:false |
| `battery` | — | `{ok, percent, charging}` | **支持** |
| `status` | — | `{ok, notificationAccess, link, accessibility, fullControl, model}` | **支持**。`accessibility` 和 `fullControl` 固定为 false，另外加了 `client:"OneSay"` |
| `open_app` | `name` | `{ok, opened:<应用名>}`。匹配分数：名字完全相同 3、前缀 2、包含 1。在 VYRSE 里，这个动作属于完全操控 | **支持**，按同样的分数匹配，但**不需要无障碍**，直接用桌面启动 Intent 打开。支付、银行、钱包、密码管理、应用商店和安装器，以及 OneSay 自己，一律拒绝，返回 `That app is off limits…`。拦截先按包名表，再按名字关键词兜底 |
| `media` | `command` | `{ok, media}` | 不支持（返回 `unsupported:true`） |
| `volume` | `level` 0–100 | `{ok, level}` | 不支持 |
| `screen` / `screenshot` / `tap` / `long_press` / `type` / `swipe` / `key` | 见 `phone.py` | 需要无障碍加「Allow full control」 | **不支持**：完全操控 v1 不开放 |
| `open_url` | `url`（http/https） | `{ok, opened:<host>}` | 不支持。也就是说，VYRSE 的「把文件发到手机」`send_file` 用不了：电脑端会把它变成临时下载链接 `http://<pc>:47630/phone/file/<token>/<name>`（15 分钟有效），再通过 `open_url` 让手机打开 |
| 其它任何字符串 | — | `Unknown phone action: …` | 同样拒绝 |

不支持的动作，OneSay 返回：`{"ok":false,"unsupported":true,"error":"OneSay doesn't support \"tap\" yet: …Full phone control is off."}`。这样 PC 上的 AI 能看懂原因，不会反复重试。

### 5.5 其它 PC 端接口（OneSay 不使用，列出来备查）

`GET /phone/file/<token>/<name>`（一次性下载链接）；`POST /phone/snap`（手机拍照发给 PC，最大 12 MB，校验 JPEG/PNG/WebP 文件头）；`POST /phone/log`（手机日志）；`POST /phone/desktop-call`（手机请 PC 执行 Alexa 动作）；`/api/*` 白名单 REST 转发给 Hermes，加上 `/api/ws`、`/api/audio/speak-stream` 两个 WebSocket，JSON-RPC 方法也有白名单。CORS 只允许 `https://localhost`、`http://localhost`、`capacitor://localhost` 这三个来源（Capacitor App 用）。OneSay 是原生请求，不发 Origin，不受 CORS 影响。

## 6. OneSay 端实现

| 层 | 位置 | 说明 |
|---|---|---|
| 插件本体（纯 Java 7 + org.json，不认识 Android） | `app/src/com/onesay/assistant/plug/vyrse/core/` | `VyrseProtocol`：常量、地址和配对码校验；`VyrseHttp.LanSocket`：用裸 Socket 写的明文 HTTP/1.1 客户端，只连家庭网络地址；`VyrsePairing`；`VyrseDispatcher`：白名单和映射；`AppGuard`：禁用 App 表；`RingController`：响铃和恢复音量；`VyrseLink`：长轮询、命令去重、401 处理 |
| 适配层 | `app/src/com/onesay/assistant/v2/vyrse/` | `VyrseSettings`：开关和配对信息，key 用 Keystore 加密存储；`VyrseAbilities`：Android 能力实现；`VyrseLinkService`：前台服务；`VyrseUi`：控制台页面 |
| 插件登记 | `v2/kernel/plugins/VyrsePlugin.java`，在 `BuiltinPlugins` 里登记 | **默认关**，kill-switch 是 `vyrse.off` |

**安全取舍**
- **总开关默认关**。关着的时候不起服务、不联网。第一次打开会先弹窗，说明连接是「局域网明文 HTTP、没有加密」，用户确认后才真正打开；细项页和常驻通知里也一直写着「明文」。
- 手机 key 通过 `SecretPrefs` 存成 Android Keystore 的 AES-GCM 密文（登记为 `bixby_vyrse/vyrse_token`），日志里不打印。
- **只把 key 发给家庭网络 IPv4 地址**：配对时和每次请求前都会检查。域名、https、公网地址、带 `@` 的地址一律拒绝。整个 App 仍然是 `usesCleartextTraffic="false"`：这里用裸 Socket 只连已校验的局域网地址，不放开全局明文策略。
- 先确认对面是 VYRSE（`/phone/hello` 返回 `app == "vyrse"`），确认之后才发配对码。
- 收到的动作按白名单执行，默认拒绝；完全操控（点击、输入、截屏、按键、打开网址）不实现，回复 `unsupported`。同一个命令 id 只执行一次。每轮最多处理 20 条命令。响应体最大 1 MB。
- 支付、银行、钱包、密码管理、应用商店和安装器类 App：**不打开，它们的通知也不交给电脑**；通知里的验证码会打码。
- 电脑把这台手机移除（返回 401）以后：清掉 key，停掉服务，发一条通知提醒重新配对，不再无限重试。
- 服务不导出（`exported=false`），也不监听任何端口；常驻通知上有「停止」按钮。开机或进程重启后，跟 phongent 一样由面板服务的 `onServiceCreate` 按开关把它拉起来。

## 7. 已知差异和限制

- 如果 VYRSE 自己的手机 App 和 OneSay 都配对了同一台电脑，命令会发给最后一次来轮询的那台（这是 VYRSE 的行为）。
- `send_file`、`media`、`volume`、完全操控都不支持。
- `open_app` 从后台服务启动 Activity，在 Android 10 及以上会受「后台启动 Activity」限制。OneSay 有悬浮窗权限时可以豁免；没有这个权限时，可能打不开，这一点**未在真机验证**。
- 明文 HTTP 由 VYRSE 的协议决定，OneSay 这边没法单方面加密。外出、公共 Wi-Fi 下请关掉。
