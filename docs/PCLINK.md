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
## 两轴 + 安全插件（link-v2，电脑作为被控端）

> 这一节替代上面「执行级别与信任检查」里**电脑一列**的旧三档矩阵。手机作为被控端已恢复到分档之前（见手机仓库 docs/PCLINK.md），下面只讲手机 → 电脑。

### 两轴（照搬 Codex 的 sandbox mode × approval policy，只参考思路、没拷代码）

- **沙箱档**（能动什么）：`read_only` 只读 / `workspace_write` 工作区可写（**默认**）/ `full_access` 完全访问。
- **审批策略**（什么时候问你）：`untrusted` 分类（**默认**：只有查询、无害动作、工作区内写不问）/ `on_request` 按需（沙箱以内的不问，超出沙箱或会被注意到 / 有破坏性的才问）/ `never` 从不（永不弹确认，要确认的直接拒；只有完全访问档下照做）。
- 配置键 `pc_sandbox`、`pc_approval`。旧配置只有 `pc_level` 时按下表映射，保存时仍写 `pc_level`（最接近的旧档）给旧版本读；回复里 `level` / `level_zh` 照旧，另加 `sandbox` / `approval`（及 `_zh`）。界面接口 `pc_level` 仍可用（按映射改两轴）。

| 旧档（pc_level） | → 沙箱档 · 审批策略 |
|---|---|
| read_only 只读 | 只读 · 分类 |
| auto 自动（默认） | 工作区可写 · 分类 |
| full 完全访问 | 完全访问 · 按需 |

### 矩阵（A 直接做 / C 电脑上确认，30 秒不点 = 拒 / D 拒绝 / S 只生成 .ps1 脚本，从不执行）

| 类别 | 只读（任一审批） | 工作区可写：分类 / 按需 / 从不 | 完全访问：分类 / 按需 / 从不 |
|---|---|---|---|
| 查询（ping / caps / info / 列快捷指令） | A | A / A / A | A / A / A |
| 读隐私、无害、打开程序（禁止类除外）、写工作区里的文件 | D | A / A / A | A / A / A |
| 简单设置（音量等） | D | C / C / D | C / C / A |
| 关机 / 睡眠、卸载程序 | D | C / C / D | C / C / A |
| 打开网址（先过网址检查；信任域名直接开） | D | C / 提醒 / 提醒 | C / 提醒 / 提醒 |
| 写剪贴板（先过剪贴板检查；敏感内容一律要人确认，「从不」= 拒） | D | C / A / A | C / A / A |
| 操作第三方程序 | D | D / D / D | A / A / A |
| 写工作区以外的文件（按 Windows ACL） | D | **C / C / D** | A / A / A |
| 运行工作区 scripts/ 里的脚本（内容也过插件） | D | **C / C / D** | C / C / A |
| 任意命令 sys_exec（按你自己的权限执行） | D | **C / C / D** | C / C / A |
| 快捷指令（批准过 = A；没批准 / 改过 = C；内容碰插件规则 = D） | D | 同左 | 同左 |
| 需要管理员（见下） | S | S | S |
| 给自己授权（ACL / Defender 排除 / 本地管理员组） | S | S | S |
| 远程改 OneSay 自己的设置 | D（self_grant_blocked） | D | D |
| 系统程序、支付 / 银行 / 密码 / 钱包 / 商店 / 安装器类 | D | D | D |

- **工作区**：`%USERPROFILE%\OneSay\workspace`（用户目录非 ASCII 时 `%ProgramData%\OneSay\workspace`）；工作区里的 `.git`、`.codex`、`.agents` 只读。
- **工作区可写档下的命令（第二轮，2026-11-08 用户决定）**：Windows 上 Java 没法把子进程关在工作区里（没有系统级沙箱），你知道这一点，所以命令、跑工作区脚本、工作区外写入改成「**电脑上确认后，按你自己的权限执行**」，不再只生成脚本。审批策略照常：分类 / 按需 → 先确认（30 秒不点 = 拒），从不 → 直接拒。安全插件规则全部在确认之前、照旧优先（永不提权、需要管理员只生成 .ps1、禁止自授权、默认断网、编码命令拦截）；OneSay 自己是管理员时确认了也拒。操作第三方程序仍只在完全访问档。
- **默认断网**：只读 / 工作区可写档下，命令要联网（curl / wget / Invoke-WebRequest / WebClient / bitsadmin / certutil -urlcache / UNC `\\主机\共享`…）时，域名必须在白名单 `net_allow` 里（含子域），否则 `plugin_blocked`。完全访问档不断网。

### 安全插件（默认开，只收紧不放宽）

原则：**普通用户能做的事不拦，Windows 自己会挡没有管理员的操作。** 插件只强制下面这些：

1. **永不提权**（关不掉）：OneSay 启动的子进程只走 CreateProcess（不走 ShellExecute / runas 动词，所以永远不会弹 UAC）。命令行里出现 `runas`、`-Verb RunAs`、`ShellExecute…runas`、`gsudo` / `psexec`、`/rl highest`、UAC 绕过程序（fodhelper / eventvwr / computerdefaults / sdclt / slui / wsreset / cmstp…）一律 `plugin_blocked`。OneSay 自己以管理员（高完整性级别）运行时，远程触发的子进程要降权启动；纯 Java 做不到，所以**拒绝**（`SafeExec.deElevate` 留了接口）；工作区外写文件同样拒绝。检测不了是不是管理员 = 当作是。
2. **需要管理员 → 只生成脚本**（关不掉）：HKLM / HKCR / HKU（其他用户）注册表、`reg load/unload/import/restore/copy`；服务与驱动（sc / *-Service / net start|stop / pnputil / devcon / bcdedit）；防火墙 / 网卡 / DNS / 系统级代理（netsh、*-Net*、Set-DnsClient*、route、ipconfig /release|renew）/ hosts；系统级计划任务（/ru SYSTEM、/rl highest、-User SYSTEM）；diskpart / format / vssadmin / dism / sfc / wbadmin / bootrec / fsutil / secedit / auditpol / net user|localgroup / setx /m / msiexec / 机器级 Set-ExecutionPolicy。生成 `scripts/*.ps1`（UTF-8 BOM + 中文头注释写明为什么要管理员、风险、怎么用管理员身份运行），原因码 `needs_admin_script`。
3. **当前用户注册表（HKCU）放开**，例外（仍只生成脚本）：`HKCU\Software\Classes` 下的 `shell\open\command` / `DelegateExecute` 劫持与 ms-settings / mscfile / exefile 等键（fodhelper / eventvwr 类 UAC 绕过）、`HKCU\Environment` 的 windir / SystemRoot / COR_PROFILER / COR_ENABLE_PROFILING 等、`HKCU\Software\Policies` 与 `…\CurrentVersion\Policies`。
4. **删除自己的文件、工作区外写文件**：按普通用户权限放行（Windows ACL 为准），但遵守沙箱档。
5. **禁止自授权**（关不掉）：远程指令不能改 OneSay 的档位 / 插件开关 / 信任列表 / 联网白名单 / 快捷指令批准表 / 配置文件、不能结束 OneSay、不能调本机界面接口（127.0.0.1:47830/api）；`settings`、`pc_level`、`plugin_off`、`trust_add`、`shortcut_approve` 等指令名一律拒。原因码 `self_grant_blocked`。只能在电脑界面上亲手改（界面接口要本机随机口令）。
6. 保留：网址校验、剪贴板校验、支付 / 银行 / 钱包类程序屏蔽、快捷指令（SHORTCUT）规则。
7. **编码 / 隐藏命令**（`-enc` / `-EncodedCommand`、`FromBase64String`、`iex` / `Invoke-Expression`）：单独一项「拦截编码 / 隐藏命令」，默认开（配置 `plugin_encoded`）。关掉后编码本身不再拦，但 `-enc` 解出来的内容照样过其他规则。
8. **先规范化再匹配**：大小写、`cmd /c` / `powershell -Command` 外壳、`^` 与反引号转义、引号拆词、`%VAR%` / `$env:VAR` 展开、8.3 短名（PROGRA~1、WINDOW~1、SYSTEM~1…）、`powershell -enc <base64>` 解码（UTF-16LE）、`HKEY_CURRENT_USER` / `Registry::` 写法。
9. **开关**：设置页「安全插件」关掉要确认两次（接口要 `plugin_off_confirm=true`）；关掉只关可关项（编码命令拦截），1 / 2 / 5 三项照样生效。插件检查时自己出错 = 拒绝（`plugin_blocked`）。

### 快捷指令（SHORTCUT）对齐新规则

仍按「文件名小写 + 内容 SHA-256」识别、第一次问、内容变化重问、执行前复核哈希、快捷指令目录不得在工作区内、256 KB 上限。内容检查改成：**只有**想提权、需要管理员、影响提权的 HKCU 键、想改 OneSay 自己的设置、给自己授权（沿用）、编码命令（可关）才 `shortcut_category_blocked`；普通用户能做的（改自己的 HKCU 键 / HKCU 代理、删自己的文件、启动程序…）照常批准。**先剥注释再匹配**：.ps1 的 `#` 行注释与 `<# #>` 块注释（引号里的 # 不算），.bat / .cmd 的 `REM` 与 `::`。

### 原因码（新增）

| 码 | 中文 |
|---|---|
| `plugin_blocked` | 安全插件拦下了（提权 / 看不出内容 / 沙箱断网 / .git 等只读 / 插件出错） |
| `needs_admin_script` | 这件事要管理员，已生成脚本，需你自己用管理员身份执行 |
| `self_grant_blocked` | 远程不能改 OneSay 自己的设置 |

第二轮起电脑作被控端不再回 `script_generated`（工作区可写档改成确认后执行；要管理员的回 `needs_admin_script`）；这个码只剩手机作被控端「给 App 授权的命令只生成脚本」时用。没有新增 exfil / credential / destructive 这类为普通用户行为加的码。

## 第二轮（2026-11-08）

### 1. 工作区可写档：确认后按你自己的权限执行
见上面矩阵加粗的三格和「工作区可写档下的命令」一条。顺序不变：插件结论 → 红线 → 信任检查 → 两轴矩阵；插件拦下的（提权 / 要管理员 / 自授权 / 编码 / 断网 / 授权）从不走到确认框。同一秒里生成的多份脚本不再互相覆盖（文件名后加 -2、-3…）。

### 2. 编码 / 隐藏命令拦截
保留：默认开（`plugin_encoded`），设置页单独一个开关；关掉后编码本身不拦，`-enc` 解出来的内容照样过其他规则。

### 3. 电脑 → 手机：加回来的 8 项
不分档、手机上不确认（分档之前的语义），手机控制台每项一个开关、挂在「接受电脑指令」总开关下；手机 `caps` 回 `caps`（开着的）和 `known`（支持的全部）。

| cmd | args | 手机端做什么 | 个人版默认 | 开源版默认 |
|---|---|---|---|---|
| `flashlight` | `on: true\|false` | 闪光灯开 / 关 | 开 | **关** |
| `setting_set` | `name`, `value` | 亮度 / 音量 / 屏幕超时 / 勿扰 / 铃声 / Wi-Fi / 蓝牙直接改（缺系统权限时说清楚，不自动申请）；飞行模式、移动数据、定位等走手机特权通道 | 开 | **关** |
| `file_write` | `path`, `text ≤ 256 KB` | 写 OneSay 工作区；工作区以外只写共享存储里的普通文件（不碰 Android/） | 开 | **关** |
| `file_list` | — | 列工作区文件 | 开 | **关** |
| `uninstall_app` | `package` | 弹系统卸载确认（最后由手机上的人点） | 开 | **关** |
| `script_list` | — | 列工作区 scripts/ | 开 | **关** |
| `script_run` | `name`（.sh） | 走特权通道运行；内容里有给 App 授权的命令就不运行 | 开 | **关** |
| `sys_exec` | `command ≤ 4000` | 走特权通道执行 | 开 | **关** |

手机侧仍保留的红线（`LinkPolicy.phoneLegacy` + `PcRx.handle`）：支付 / 银行 / 密码 / 钱包 / 商店 / 安装器类 App 和 OneSay 自己不打开 / 不卸载（`blocked_app`）；系统 App 不卸载、`pm uninstall/disable…` 也一样（`system_app`）；网址过不了检查不开（`untrusted_url`，含 IP / 内网 / 账号密码 / 仿冒域名）；剪贴板内容过不了检查不写（`clipboard_rejected`，像命令、坏链接、超过 10 KB），敏感内容不确认但标敏感、60 秒后清除；给 App 授权的命令只生成脚本（`script_generated`）；远程不能改 OneSay 设置。
电脑端「手机」页多了「更多」一栏能发出这 8 项；`PhoneCmds` 先查一遍参数，`sys_exec` / `script_run` 等 20 秒，`uninstall_app` / `setting_set` 等 12 秒。

### 4. 语音识别：默认先看云端
- 设置 `asr_pref`：`auto` 先看云端（默认）/ `cloud` 仅云端 / `local` 仅本机。旧配置只有 `asr_mode` 时：选过 `local` 的保持仅本机，其余（`cloud`、从没做出来的 `phone`）改成先看云端；保存时仍写 `asr_mode` 给旧版本。
- 先看云端：启动时（后台）和每次识别前看一次。云端可用 = 填了识别 Key **且**连得上：对识别地址发一个空的 `HEAD …/models`（**不带 Key、不带音频、不带任何内容**），1.5 秒内收到任何 HTTP 回应（401 也算）就算连得上；超时 / 出错 = 不可用。结果缓存：可用 60 秒、不可用 15 秒；换 Key / 地址 / 选择立即作废。
- 可用走云端（百炼 qwen3-asr-flash，和以前一样）；不可用回落本机离线识别（Windows 自带 System.Speech 中文听写，`asr-local.ps1`，录音写成临时 WAV 识别完即删，不联网）。「自动」下云端识别出错也当场回落本机，并把云端记成不可用。
- 仅云端：不探测、不回落，没填 Key 时不抢空格；仅本机：从不联网。
- 界面：设置 › 说话 显示「识别方式」三选一和「现在用的是：云端 / 本机离线 / 没有可用的识别」+ 原因；按住说话的提示行和浮条（「识别中…（云端）」）也显示。`/api/state` 带 `asr_pref` / `asr_engine` / `asr_engine_zh` / `asr_why`。
