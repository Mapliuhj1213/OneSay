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
| 任意系统命令（su / Shizuku / shell） | 脚本 / 脚本 / 确认（显示完整命令） | 脚本 / 脚本 / 脚本 |
| 给自己授权（pm grant、appops、无障碍 / 通知监听、设备管理器、Shizuku 批准、ACL） | 脚本 / 脚本 / 脚本 | 脚本 / 脚本 / 脚本 |
| 系统 App 的卸载 / 停用 | 拒绝 | 拒绝 |
| 支付 / 银行 / 密码 / 钱包 / 应用商店 / 安装器类 App | 拒绝 | 拒绝 |

「确认」= 被控端弹「允许一次 / 拒绝」，30 秒不点算拒绝；「脚本」= 只在工作区 `scripts/` 生成带中文头注释（做什么、风险、怎么执行）的 .sh（手机）或 .ps1（电脑），通知「已生成脚本，需你自己执行」，从不执行。

- 工作区：手机 `getExternalFilesDir("workspace")`；电脑 `%USERPROFILE%\OneSay\workspace`，用户目录含非 ASCII 时用 `%ProgramData%\OneSay\workspace`。路径检查拒绝 `..`、绝对路径、盘符、UNC、设备路径、NTFS 备用流、Windows 保留名，并用规范路径比对（符号链接跳出也拒）。
- 网址检查：只许 http/https；拒绝 user:pass@、IP 字面量 / 内网主机（除非在信任列表）、混合文字 / 整段西里尔或希腊仿冒的 IDN（含 punycode）、超长（2048）、控制字符和反斜杠。v1 不做联网信誉查询。
- 剪贴板检查：超过 10 KB、像命令 / 脚本、含过不了网址检查的链接 → 拒绝；验证码、银行卡号（Luhn）、私钥 / 密钥、助记词、密码 → 敏感：要确认，Android 13+ 标 `EXTRA_IS_SENSITIVE`，60 秒后自动清除。
- 回复 `data` 带 `level` / `level_zh`，没执行时带 `reason`（`denied_by_level`、`needs_confirm_timeout`、`confirm_denied`、`untrusted_url`、`blocked_app`、`clipboard_rejected`、`script_generated`、`system_app`、`unknown_command`）和 `reason_zh`；生成脚本时带 `path`。
- 默认级别：自动（个人版、开源版都是；开源版「接受电脑指令」总开关仍默认关）。手机的级别只能在手机上改，电脑上只读显示；电脑自己的级别在电脑设置页改。打开「完全访问」两边都要再确认一次。

## 恢复：手机作为被控端不再分档（link-v2，2026-11-08）

上面「执行级别与信任检查」一节里**手机一列**已经撤掉：电脑 → 手机恢复到分档之前的语义 ——

- 只看控制台「电脑联动」里的「接受电脑指令」总开关和 8 项逐项开关（找手机、亮屏、媒体音量、通知、状态、剪贴板、网址、打开 App）；
  个人版默认全开，开源版总开关默认关、剪贴板 / 网址 / 打开 App 默认关（和分档之前一样）。
- 手机上不再弹「允许一次 / 拒绝」，不再按类别拦网址 / 剪贴板 / App，回复不带 `level`；控制台没有「执行级别」页。
- ~~分档时加的手机端新指令一并撤掉~~ → 第二轮（见下）按分档之前的语义加回来了。
- 手机 → 电脑时，电脑作为被控端仍走电脑端的两轴 + 安全插件（见电脑端 docs/PCLINK.md「两轴 + 安全插件」）；
  控制台触发电脑上可能要确认的指令（快捷指令、关机 / 睡眠、网址、剪贴板、设置、脚本、命令、卸载 / 操作程序）手机等 40 秒。
- `LinkPolicy`（三份同一份）里手机作被控端的判定是 `phoneLegacy`（第二轮起 `PcRx.handle` 每条指令都调用它，见下）。

## 第二轮：8 个指令加回来（不分档、不确认，红线照旧）（link-v2，2026-11-08）

- 手电筒 `flashlight`、改设置 `setting_set`、写文件 `file_write`、看工作区文件 `file_list`、卸载 App `uninstall_app`（弹系统卸载确认）、看工作区脚本 `script_list`、运行工作区脚本 `script_run`（.sh，走特权通道）、系统命令 `sys_exec`（走特权通道）。
- 按「分档之前」的语义：没有执行级别、手机上不弹确认；控制台「电脑联动」里每项一个开关，和原来 8 项一起挂在「接受电脑指令」总开关下。开源版总开关默认关，这 8 项也都默认关。
- `caps` 回 `caps`（现在开着的）和 `known`（支持的全部 16 项）。电脑端「手机」页的「更多」一栏能发出它们。
- 手机侧保留的红线（`PcRx.handle` → `LinkPolicy.phoneLegacy`）：
  - 支付 / 银行 / 密码 / 钱包 / 应用商店 / 安装器类 App 和 OneSay 自己：不打开、不卸载，`sys_exec` 里的 `pm uninstall / disable / clear…` 也一样 → `blocked_app`；系统 App 不卸载 → `system_app`。
  - 网址：只许 http/https，带账号密码、IP / 内网地址、仿冒域名（含 punycode）、超长的不开 → `untrusted_url`（手机端没有信任列表）。
  - 剪贴板：像命令 / 脚本、带坏链接、超过 10 KB 的不写 → `clipboard_rejected`；验证码 / 卡号 / 私钥 / 助记词 / 密码这类敏感内容不弹确认，但标 `EXTRA_IS_SENSITIVE`、60 秒后自动清除。
  - 给 App 授权（pm grant、appops、无障碍 / 通知监听、设备管理器、Shizuku…）的命令只生成脚本、从不执行 → `script_generated`；工作区脚本里藏这类命令也不替你运行。
  - 远程不能改 OneSay 自己的设置；电脑带来的 `_` 开头参数一律丢掉。
- 改设置缺系统权限（修改系统设置、勿扰权限）时只说清楚，不自动申请。

## 能批就不拦（2026-11-10，分支 feat/approve-not-block）

> 这一节**覆盖**上面各节里「关不掉 / 一律拒」的说法。原则：除了下面三条硬规则，原来直接拒的都改成**问你**；你批了就做，还能勾「以后这类不用问」。

### 1. 硬拒只剩三条（批了也不行，「以后这类不用问」也绕不过，根本不弹确认）

| # | 规则 | 原因码 | 两端 |
|---|---|---|---|
| ① | **付款 / 转账的最后一下必须你亲手点**：远程付款类指令（`pay`、`pay_confirm`、`transfer`、`checkout`、`place_order`…）一律拒；手机上的机器人遇到「确认支付 / 立即付款 / 确认转账 / 提交订单并支付 / 免密支付 / 支付¥28 / Pay now…」这类最终按钮停下、不问、你允许了也不点（`SensitiveGuard.userMustTap`），前面的步骤照常 | `pay_user_tap` | 电脑 / 手机 |
| ② | **机器人 / 远程不能改 OneSay 自己的权限，也不能自己批准**：改档位 / 插件 / 信任列表 / 白名单 / 批准表 / 记住的类型（含 `approve_kinds` 配置项、`remember_type` 等指令名）、远程卸载 / 停用 / 结束 OneSay 自己、给 App 授权（`pm grant` / `appops` / 无障碍 / 设备管理员…只生成脚本）；「回答确认框」类指令（`confirm` / `approve` / `allow`…）和语音都批不了 | `self_grant_blocked` / `script_generated` / `voice_approval_blocked` | 电脑 / 手机 |
| ③ | **电脑不提权**：子进程永不 runas、不弹 UAC；要管理员的只生成 `.ps1` | `plugin_blocked`（想提权）/ `needs_admin_script` | 电脑 |

### 2. 原来拒的，改成问你

| 原来 | 现在（`untrusted` / `on_request`） | `never` 从不询问 |
|---|---|---|
| 系统程序（explorer、winlogon…）、支付 / 银行 / 密码 / 钱包 / 商店类程序：打开 / 卸载 / 操作 | 电脑上确认（操作第三方程序仍只在完全访问档；只读档照旧不做） | 照旧拒（`system_app` / `blocked_app`） |
| 网址里是 IP / 内网 / 带账号密码 / 疑似仿冒域名 | 电脑上确认，确认框写明是哪一种（格式不对的网址、非 http(s) 照旧直接拒） | 照旧拒（`untrusted_url`） |
| 剪贴板像命令 / 含坏链接（`clipboard_rejected`） | 电脑上确认（完全访问也问） | 照旧拒 |
| 工作区里的 `.git` / `.codex` / `.agents`（`plugin_blocked`） | 电脑上确认后写 | 照旧拒 |
| 远程卸载 / 停用 / 结束 OneSay 自己 | **不变：硬规则 ②** | — |
| 永不提权、要管理员只生成脚本、编码命令（可关）、沙箱断网 | **不变** | — |

**手机作为被控端（电脑 → 手机），回到分档之前（round2 之前）**：
- 网址里的 IP / 内网地址（家里 NAS、路由器）**不再拒**，直接开；带账号密码、疑似仿冒的在**手机上弹确认卡**；格式不对的照旧拒。
- 剪贴板**去掉 10 KB 上限**（只保留 10 万字防内存的上限）和「像命令就拒」；敏感内容（验证码 / 卡号 / 密码…）照旧标敏感，**60 秒清除改成可选设置，默认关**（手机控制台「电脑联动」页「敏感内容 60 秒后自动清除」；开源版同样默认关）。
- 打开 / 卸载支付类与系统 App、`sys_exec` 里 `pm uninstall / disable…` 这类 App → **手机上弹确认卡**（原来 `blocked_app` / `system_app` 拒）。OneSay 自己：打开照常，卸载 / 停用是硬规则 ②。
- 「分档指令」（改系统设置、运行脚本、系统命令、写文件…，即第二轮加回来的 8 项）：开关照旧，个人版默认开、**开源版默认关**；内容碰到上面那几类时弹卡。

### 3. 「以后这类不用问」

- 确认框（电脑上 / 手机确认卡上）带一个勾选：勾上再点「允许」= 以后**同一类动作**直接执行（电脑会弹一条通知提醒）。
- 按动作类型记：`pc.setting` 电脑设置、`pc.power` 关机 / 睡眠、`pc.uninstall` 卸载程序、`pc.script_run` 运行工作区脚本、`pc.shell_exec` 执行命令、`pc.outside_write` 写工作区以外、`pc.url` / `pc.url.risky` 网址、`pc.clipboard*` 剪贴板、`pc.blocked_app.*` 支付类程序、`pc.system_app` 系统程序、`pc.workspace.protected` 工作区只读目录；手机侧 `phone.blocked_app.open` / `phone.blocked_app.uninstall` / `phone.system_app` / `phone.url.risky`。不认识的类型不收、不给勾选。
- 快捷指令照旧按「文件名 + 内容哈希」批准（不按类型）；三条硬规则根本不弹确认，也就记不了。
- 查看 / 撤销：电脑设置页「以后这类不用问」、手机控制台「电脑联动」页同名分组。只能在本机界面上改（电脑 `/api/remembered` 要本机口令；配置键 `approve_kinds`，远程碰这个词就是 `self_grant_blocked`）。

### 4. 审批更顺手

- **手机屏幕确认卡可以批准电脑上的确认**：电脑要确认时，把签名的 `confirm_offer`（编号 `cid`、一次性 `token`、内容、可记住的类型、剩余时间）发到这台手机的常连通道；手机弹卡（`PcConfirmActivity`），真人手指点「允许」后回签名的 `confirm_answer`（`cid`、`token`、`allow`、`remember`、`input=touch`、`shown_ms`）。电脑端 `ConfirmGate.checkPhone` 只在**同一台配对手机 + 签名对 + token 对 + 手机上显示满 0.7 秒 + 离发出也满 0.7 秒 + input=touch** 时收；电脑那边结束（别处点了 / 超时）会发 `confirm_close` 收卡。手机卡在等时，电脑窗口失焦 / 界面没动静不再按拒绝（手机可能在批）；超时照旧。
- **手机卡只认真人手指**：按钮只在手指按下又在按钮里抬起时算数；被别的窗口遮住（`FLAG_WINDOW_IS_OBSCURED` / `PARTIALLY_OBSCURED`）、无障碍代点（`ACTION_CLICK`、`performClick`、带无障碍标记的注入事件）、OneSay 自己的机器人刚注入过手势（1.5 秒内）都不算；窗口 `FLAG_SECURE`（截屏看不到）；焦点默认在「拒绝」；60 秒不点、返回键、关掉 = 拒绝。
- **语音 / 远程指令 / 机器人自身仍批不了**：`voice_approval_blocked` 保留；`confirm` / `approve` 类远程指令全拒；有卡在等时对手机说「允许 / 好的」只回一句「要在屏幕上点」。
- **超时 30 秒 → 60 秒**（`LinkPolicy.CONFIRM_TIMEOUT_MS`，电脑和手机同一个数）。手机 → 电脑可能要确认的指令手机等 75 秒；电脑 → 手机可能在手机上弹卡的（`open_url` / `open_app` / `uninstall_app` / `sys_exec`）电脑多等 65 秒。
- **完全访问 + 从不询问**照旧直接执行（不问）。
- **审批在执行层强制**：`PcGuard.check` 放行时发一张一次性 `Pass`（绑定这次的指令和参数），`Agent` 执行前核对，参数换了 / 用过一次 / 没有凭证 = 不执行。思路借鉴 DIYgod/pinky（Apache-2.0，https://github.com/DIYgod/pinky）的「审批在网关执行层强制」，代码是自己写的。

### 5. 原因码（新增）

| 码 | 中文 |
|---|---|
| `pay_user_tap` | 付款 / 转账的最后一下要你自己点，远程和机器人都不能代点 |
