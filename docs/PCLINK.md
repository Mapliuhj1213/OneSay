# 电脑联动协议 v2（OneSay 电脑端 0.5.0 ↔ 手机「电脑联动」插件）

源码为准；本文是协议说明。手机端对应文档在手机仓库 `docs/PCLINK.md`。

## 变化一览（相对 v1 / 0.4.x）

| | v1（0.4.x） | v2（0.5.0） |
|---|---|---|
| 端口 | TCP 47820（WebSocket），UDP 47821（发现） | **不变**。TCP 47820 上按首字节分流：0x16 = TLS，其他 = v1 明文 |
| 配对串 | `onesay://pair?key=&peer=&lan=[&relay=&token=]` | 末尾加 `&fp=<电脑证书指纹>&v=2`（旧版忽略不认识的参数，所以旧手机拿新串照样明文能用） |
| 加密 | 无（只有 HMAC 签名） | TLS 1.2/1.3，**钉证书指纹**，双向证书 |
| 发现 | UDP 单播（提示地址）+ 广播 | 再加 **组播 224.0.0.167:47821** 与电脑定时 **announce**；兜底改成 `GET /healthz` 探记下的地址 |
| 方向 | 只有 手机 → 电脑 | 加 **电脑 → 手机**（手机的常连通道） |
| 签名 | HMAC-SHA256 + ts ±120 s + 一次性 nonce | **完全不变**（body 里 `v` 仍是 1，签名前缀仍是 `onesay.v1`），逐字节兼容 |

## 1. 证书与指纹

- 每台设备第一次运行生成 EC P-256 钥匙 + X.509 v3 自签证书（CN=`onesay-<设备id>`，SHA256withECDSA，有效期 −1 天 ~ +20 年、不超过 2049-12-31）。
  不靠任何 CA；电脑端存在 `desktop.properties` 的 `tls_key`/`tls_cert`（文件权限 600），手机端私钥经 Keystore 加密存 `bixby_pclink/tls_key`。
- 指纹 = `base64url(SHA-256(证书 DER))`，无填充，43 个字符。给人核对时显示前 4 字节十六进制 `1A2B-3C4D`（电脑设置页、手机控制台都显示）。
- 证书一旦生成就不变：换了证书，对方钉的指纹就对不上。

## 2. 配对串 v2

```
onesay://pair?key=<32B base64url>&peer=<电脑id>&lan=<ip:port,...>[&relay=..&token=..]&fp=<43 字符>&v=2
```
- 解析：`v` 缺省 = 1；`v>2` 拒绝（提示先升级）；`fp` 有就必须是 43 字符 base64url（32 字节）；`v=2` 但缺 `fp`（且有局域网）拒绝。
- v1 串仍然接受：`fp` 为空 → 这对设备之间走 v1 明文。
- 整串等于钥匙，只在自己的设备之间传。

## 3. 发现

- 电脑在 UDP 端口 = 局域网端口 + 1（默认 47821）上收三种探测：单播、广播、**组播 224.0.0.167**（与 LocalSend 一样用 224.0.0.0/24：部分安卓只收这一段）。
- 探测 `{"t":"probe","from","to","n","tag"}`，标签 = `base64url(HMAC(key, "onesay.lan\n" + "probe\n"+from+"\n"+to+"\n"+n))`；
  应答 `{"t":"here","id","port","n","tag","v":2,"tls":true}`，标签只覆盖 v1 字段（`here\n id \n port \n n`），`v`/`tls` 只是提示。没配对的人扫不到电脑。
- **announce**：电脑开机、之后每 60 秒、以及要给手机下令而手机没连着时（每 1.5 秒一次直到连上或放弃），往组播 + 各网卡广播发
  `{"t":"ann","v":2,"port","n","tag"}`，标签 = tag(key, `"ann\n" + 电脑id + "\n" + port + "\n" + n`)。**不带电脑 id**：没配对的人只看到一串随机数。
  手机常连通道在退避等待时听这个包，验过标签立刻重连（LocalSend「组播 announce → 对方马上回应」的思路）。
- 兜底：UDP 全被挡时，对记下的地址发明文 `GET /healthz`，回 `HTTP/1.1 200 ... ok` 才算找到（比只看 TCP 能不能连上准）。

## 4. 连接与握手

1. TCP 连 47820。v2 客户端（配对串带 `fp`）**一定**先做 TLS：只认指纹 = 配对串 `fp` 的服务端证书（不信系统 CA、不查主机名、不看有效期），
   同时出示自己的证书（电脑 `needClientAuth`）。握手不过 = 失败，**绝不退回明文**。
2. 照旧 `GET /v1/ws?device=<id>` 升级 WebSocket，首帧 hello：
   `{"type":"hello","from","id","body": seal({"kind":"hello","cfp":<本机证书指纹>,"hold":true?})}`。`cfp`、`hold` 都在签名里。
3. 电脑端校验顺序：签名 / 版本 / 时间窗 / nonce（与 v1 相同）→ 允许名单 →
   - TLS 连接：`cfp` 必须等于 TLS 上对方实际出示的证书指纹；这台设备第一次加密连上 → 按设备 id 记住指纹（`lan_pins`）；之后不一致 → `error pin` + 关闭码 **4003**。
   - 明文连接：这台设备 id 用 TLS 连过（有记住的指纹）或电脑关了明文兼容（`lan_plain=off`）→ `error tls_required` + **4002**（防降级）。没见过的旧版手机照常放行。
   - 钥匙不对仍是 `error auth` + 4001。
4. 通过后电脑回 `devices`。`hold=true` 的连接是这台手机的**常连通道**（电脑往手机下令走它）；同一台手机再开的一次性连接（按钮下令）
   不会顶掉常连，电脑把回复按 `body.re` 送回发起的那条连接。
- 电脑端「换一把钥匙」同时清掉所有记住的指纹；「忘掉手机证书」只清指纹（手机重装后用）。

## 5. 指令（两个方向同一套信封与签名）

信封 `{"type":"msg","to","id","body"}`，body：指令 `{"kind":"cmd","cmd","args","v":1,"ts","nonce","sig"}`、结果 `{"kind":"result","re","ok","text","data",...}`。
签名 = `base64url(HMAC-SHA256(key, "onesay.v1\n"+id+"\n"+from+"\n"+to+"\n"+规范JSON(body 去 sig)))`，规范 JSON = 键字典序、无空白。
接收方：签名 → v=1 → |ts−now| ≤ 120 s → nonce 一次性（REPLAY）。

### 手机 → 电脑（与 v1 相同）
`ping info caps lock screenshot clipboard_get clipboard_set open_url volume media sleep shutdown(confirm) notify memory_sync …`

### 电脑 → 手机（v2 新增，HA 伴侣 App 通知指令的思路）
| cmd | args | 手机端做什么 | 个人版默认 | 开源版默认 |
|---|---|---|---|---|
| `find_phone` | `action: start\|stop`，`seconds ≤ 120`（默认 30） | 闹钟音频流 + 临时把闹钟音量调到最大，静音 / 振动模式也响；振动、亮屏、带「停止」的通知；到时 / stop / 点通知停并还原音量 | 开 | 开 |
| `screen_on` | — | 点亮屏幕（不解锁） | 开 | 开 |
| `volume_set` | `percent 0–100` | 媒体音量 | 开 | 开 |
| `notify` | `title`, `text` | 手机通知 | 开 | 开 |
| `status` | — | `battery` `charging` `screen_on` `ringer` `music_volume` `model` `android` | 开 | 开 |
| `clipboard_set` | `text ≤ 10 万字` | 写手机剪贴板 | 开 | **关** |
| `open_url` | `url`（只许 http/https） | 系统浏览器 / 对应 App 打开 | 开 | **关** |
| `open_app` | `package` 或 `name`（桌面名字） | 启动 App | 开 | **关** |
| `ping` / `caps` | — | 在线 / 当前开着的项 | 总是 | 总是 |

- 手机端总开关「接受电脑指令」= 常连通道开关（个人版默认开、开源版默认关）；每项另有开关。关着的项回 `ok=false`「手机上没开…」。
- 电脑端先查一遍参数（`PhoneCmds.check`），手机端再查一遍（`PcRxPolicy.check`）。
- 手机离线：电脑发 announce 等最多 4 秒，等不到直接「手机离线，没发出去」，**不排队**。

### 常连通道（手机）
- 只在配对、总开关开、连着 Wi-Fi 时运行；找电脑 → 连（hold）→ 每 25 秒发一次 `ping`（电脑 95 秒没动静就断）→ 断了退避 2 s→60 s，
  退避期间持组播锁听 announce，听到立刻重连。钥匙 / 证书不对时退避 5 分钟并在控制台说明怎么修。

## 6. 电脑端界面与口令
- 「手机」页：找手机 / 停止响铃 / 点亮屏幕 / 媒体音量 / 剪贴板发到手机 / 打开网址 / 打开 App / 发通知 / 电量和状态。
- 「对话」里说：让手机响一下、手机别响了、手机亮屏、把手机音量调到 30、手机打开微信（或包名 / 网址 / B站）、把剪贴板发到手机、在手机上提醒我…、手机还有多少电。
- 设置 › 配对手机：显示本机证书指纹（与手机控制台一致）、「忘掉手机证书」。

## 7. 测试
- 电脑端 `LinkV2Test`（14 例）：证书 / 指纹、配对串 v2 + v1 兼容、TLS 钉指纹、错指纹被拒、降级 4002、换证书 4003、明文兼容开关、
  重放 / 过期被拒、常连下令 + 一次性连接不顶掉常连、回复错签被拒、离线不排队、announce / probe 字段、口令规则。
- 手机端 `PcLinkV2Test`（单元）+ `PcLinkV2InteropTest`（起真的电脑端 jar，真 socket + TLS：指纹算法与配对串逐字节一致、
  钉指纹下令与截图、错指纹 / 换证书 / 降级被拒、旧版明文仍可用、`/api/phone` 经常连通道让手机执行、断开后报离线）。

## 8. 第三方
- 设计参考（未拷贝代码，均 Apache-2.0）：LocalSend（组播发现与 announce、证书 SHA-256 指纹识别设备），Home Assistant Companion for Android（电脑→手机指令集、闹钟流最大音量找手机）。
  LocalSend 的协议文档仓库没有许可证：只阅读，未拷贝任何内容。KDE Connect / Sefirah（GPL）只在产品形态上参考，没有看或用其代码。

## 执行级别与信任检查（link-v2）

远程指令（电脑→手机、手机→电脑）在执行前都要过同一份策略模块 `LinkPolicy`（两端各一份，只有 package 不同），参数 `target = PC | PHONE`。三档级别仿照 Codex CLI 的审批模式（read-only / auto / full-access）和 workspace-write 沙箱：

| 类别 | 手机作被控端：只读 / 自动 / 完全访问 | 电脑作被控端：只读 / 自动 / 完全访问 |
|---|---|---|
| 状态 / 电量 / 信息查询 | 允许 / 允许 / 允许 | 允许 / 允许 / 允许 |
| 无害动作（找手机 30 秒自动停、手电筒、亮屏、通知；电脑：媒体键、锁屏） | 拒绝 / 允许 / 允许 | 拒绝 / 允许 / 允许 |
| 简单设置（亮度、音量、勿扰、屏幕超时、Wi-Fi / 蓝牙开关） | 拒绝 / 允许 / 允许 | 拒绝 / 确认 / 确认 |
| 关机 / 睡眠（电脑） | — | 拒绝 / 确认 / 确认 |
| 网络 / 安全设置（超出简单开关） | 脚本 / 确认 / 允许 | 脚本 / 脚本 / 脚本（A 类） |
| 电脑 A 类：注册表、网络配置、服务 / 驱动 | — | 脚本 / 脚本 / 脚本 |
| 打开 App（禁止类除外） | 拒绝 / 允许 / 允许 | 拒绝 / 允许 / 允许 |
| 打开网址（先过网址检查） | 拒绝 / 信任域名直接开，否则确认 / 允许（不在信任列表时提醒） | 同左 |
| 写剪贴板（先过剪贴板检查） | 拒绝 / 确认 / 允许（敏感内容一律确认） | 同左 |
| 写工作区里的文件 | 拒绝 / 允许 / 允许 | 拒绝 / 允许 / 允许 |
| 写工作区以外的文件 | 脚本 / 确认 / 允许 | 脚本 / 脚本 / 脚本 |
| 卸载第三方 App（走系统卸载界面） | 拒绝 / 确认 / 确认 | 拒绝 / 确认 / 确认 |
| 操作第三方 App | 拒绝 / 确认 / 允许 | 拒绝 / 拒绝 / 允许 |
| 运行工作区 scripts/ 里已有的脚本 | 拒绝 / 拒绝 / 每次确认 | 拒绝 / 拒绝 / 每次确认 |
| 电脑上你定义的快捷指令（shortcut_run） | — | 拒绝 / 第一次问 / 第一次问（批准「以后直接执行」后直接执行；内容碰严格规则的一律拒） |
| 任意系统命令（su / Shizuku / shell） | 脚本 / 脚本 / 确认（显示完整命令） | 脚本 / 脚本 / 脚本 |
| 给自己授权（pm grant、appops、无障碍 / 通知监听、设备管理器、Shizuku 批准、ACL） | 脚本 / 脚本 / 脚本 | 脚本 / 脚本 / 脚本 |
| 系统 App 的卸载 / 停用 | 拒绝 | 拒绝 |
| 支付 / 银行 / 密码 / 钱包 / 应用商店 / 安装器类 App | 拒绝 | 拒绝 |

「确认」= 被控端弹「允许一次 / 拒绝」，30 秒不点算拒绝；「脚本」= 只在工作区 `scripts/` 生成带中文头注释（做什么、风险、怎么执行）的 .sh（手机）或 .ps1（电脑），通知「已生成脚本，需你自己执行」，从不执行。

- 工作区：手机 `getExternalFilesDir("workspace")`；电脑 `%USERPROFILE%\OneSay\workspace`，用户目录含非 ASCII 时用 `%ProgramData%\OneSay\workspace`。路径检查拒绝 `..`、绝对路径、盘符、UNC、设备路径、NTFS 备用流、Windows 保留名，并用规范路径比对（符号链接跳出也拒）。
- 网址检查：只许 http/https；拒绝 user:pass@、IP 字面量 / 内网主机（除非在信任列表）、混合文字 / 整段西里尔或希腊仿冒的 IDN（含 punycode）、超长（2048）、控制字符和反斜杠。v1 不做联网信誉查询。
- 剪贴板检查：超过 10 KB、像命令 / 脚本、含过不了网址检查的链接 → 拒绝；验证码、银行卡号（Luhn）、私钥 / 密钥、助记词、密码 → 敏感：要确认，Android 13+ 标 `EXTRA_IS_SENSITIVE`，60 秒后自动清除。
- 回复 `data` 带 `level` / `level_zh`，没执行时带 `reason`（`denied_by_level`、`needs_confirm_timeout`、`confirm_denied`、`untrusted_url`、`blocked_app`、`clipboard_rejected`、`script_generated`、`system_app`、`unknown_command`、`shortcut_needs_approval`、`shortcut_changed`、`shortcut_category_blocked`）和 `reason_zh`；生成脚本时带 `path`。
- 快捷指令（电脑快捷指令文件夹里的 .ps1 / .bat / .cmd）：只认列表里有的，按「文件名（小写）+ 内容 SHA-256」批准。第一次从手机触发、或内容改过（哈希对不上），电脑上弹「允许 / 以后直接执行 / 拒绝」，30 秒不点算拒绝；没批准下来时 `reason` 是 `shortcut_needs_approval` / `shortcut_changed`，确认结果放 `confirm_result`。点「以后直接执行」后，自动 / 完全访问下再触发直接执行；只读一律拒绝。批准表存在配置 `shortcut_approvals`，快捷指令页能看、能撤销；改内容或改名都要重新批准；执行前再核对一次哈希。内容涉及注册表 / 网络配置 / 服务驱动 / 授权，或用了编码命令 / `iex` 等看不出内容的写法，或文件在手机能写的工作区里 → `shortcut_category_blocked`，只能你自己在电脑上运行，不能批准。
- 默认级别：自动（个人版、开源版都是；开源版「接受电脑指令」总开关仍默认关）。手机的级别只能在手机上改，电脑上只读显示；电脑自己的级别在电脑设置页改。打开「完全访问」两边都要再确认一次。
