# 应用白名单 · 同步协议与分层策略（app-whitelist-1116）

手机个人版、手机开源版、电脑端（含开源仓 `apps/desktop`）共用这一份文档、同一组用例 `docs/app-whitelist-vectors.json`
和三个逐字相同（除 package 行）的纯 Java 文件：`AppWhitelist`（数据 + 同步格式 + 合并）、`PrivacyScan`（隐私识别）、
`ActionPolicy`（分层策略），外加同一份测试 `AppWhitelistSharedTest`。文末登记了它们的 sha256，测试会核对，一端改了另一端没改就红。

## 1. 白名单是什么
- 用户手动加：手机 = Android 包名（如 `com.tencent.mm`），电脑 = Windows 程序 exe 名（如 `notepad.exe`，只认文件名、不分大小写）。两端都能加删两种平台的条目。
- 白名单里的应用，**除了付款和发送隐私资料**，助手的操作不再逐步要你确认。默认空；加的时候弹一次确认框，写明风险（`AppWhitelist.riskText`，两端同一段话）。
- 不能加的（加了等于关掉审批）：助手自己、系统安装器 / 权限管理 / Shizuku / Magisk / SystemUI；Windows 上的命令行、脚本解释器、常被滥用的系统程序和管理工具（cmd、powershell、wscript、mshta、rundll32、regsvr32、reg、msiexec、schtasks、sc、net、wmic、python、node、explorer、taskmgr、winget、curl…，完整名单见 `AppWhitelist.NEVER_*`）。
- 远程指令不能加删白名单（`whitelist_add` 等算「自授权」，硬拒）；只能在本机界面上改，经同步到另一端。
- **另一端同步来的条目，本机确认一次才生效**（2026-11-16 用户定，可改）：电脑推给手机的，在手机上「待确认」，用户在手机控制台看过风险说明点「确认生效」后才放行；手机推给电脑的，在电脑设置里同样确认一次。本机自己加的直接生效（加之前已弹过风险确认）。对方删了又加回来（`added_at` 变了）要重新确认。确认记录只存本机（存盘格式多一个 `confirmed_here`），**不进同步格式**，对方伪造也不认；旧存盘文件没有确认记录 → 全部待确认。

## 2. 同步格式（JSON，version 1）
```json
{"format":"onesay.app_whitelist","version":1,"device":"pc-xxx","entries":[
  {"platform":"android","id":"com.example.chat","name":"聊天","added_at":1763280000000,"updated_at":1763280000000,
   "origin":"phone-1","origin_kind":"phone","deleted":false}]}
```
| 字段 | 说明 |
|---|---|
| `format` / `version` | 固定 `onesay.app_whitelist` / `1`。格式名不对、版本大于 1、不是对象、`entries` 不是数组、JSON 后面有多余内容 → **整份拒收** |
| `platform` | `android` 或 `windows`，别的丢掉这一条 |
| `id` | android：包名原样（比较不分大小写）；windows：只取文件名、小写、补 `.exe`；写法不对或在「不能加」名单里 → 丢掉这一条 |
| `name` | 显示名，去掉控制符 / 零宽 / 方向控制符，最长 64 |
| `added_at` / `updated_at` | 毫秒时间戳；`updated_at` 是合并依据 |
| `origin` / `origin_kind` | 最后写这一条的设备 id / `phone`、`pc` |
| `deleted` | 墓碑：删除不真删，留一条 `deleted=true`，离线的一端重连后不会把它「复活」 |

**合并 = 按条目最后写入优先**：`updated_at` 大的赢；相同时墓碑赢（删除更安全）；再相同按 `origin`、`name`、`added_at` 比，保证交换律、结合律、幂等（测试 `mergeVectorsAndCommutes`）。本机写入时如果时钟往回跳，新 `updated_at` = 旧值 + 1。
上限：活条目 200，总条目（含墓碑）1000，超出先丢最老的墓碑。

**传输**：现有 link-v2 签名通道，指令 `whitelist_sync`，参数 `{"doc": <整份 JSON 文本>}`；收到的一端合并、存盘，回 `{"doc": <自己合并后的整份>}`，发起方再合并一次，两边就一样了。
- 电脑：手机常连（hold）上来后 5 秒内推一次；本机加删后马上推；手机发来的在 `Agent.local` 里合并。电脑端判类别 `QUERY`（任何沙箱档都能同步）。
- 手机：常连会话里收到电脑的 `whitelist_sync` 直接合并回整份（不走「电脑可以让手机做的事」逐项开关）；本机加删后经 `PcLink.run` 推给电脑。
- 对方带来的新放行条目会提醒用户（通知 / 日志）「要在本机确认一次才生效」，设置里能确认生效或删除。本机存盘文件读坏了 → 当空白名单用、不合并、不往外发，界面提示「清空重建」。

## 3. 隐私资料（PrivacyScan）
类别：`id_card` 身份证号（18 位校验位 / 15 位）、`bank_card` 银行卡号（2–6 开头 15–19 位 + Luhn）、`phone` 手机号、`email`、`address` 住址（关键词或「省市区…路街…号」组合）、`real_name` 真名（「姓名 / 收件人 / 持卡人…」后 2–5 个汉字）、`doc_keyword` 证件 / 银行卡字样、`redacted` 手机端脱敏占位符（`[手机号#1]`…，说明原文是隐私）、`image_unknown` 认不出内容的图片。
- 全角折半角、去零宽，数字间空格 / 横线不影响。
- 图片：有识别出的文字（OCR / 截图分析）按文字判 + 证件 / 银行卡 / 快递单字样；**没有文字的一律 `image_unknown`，当隐私**。
- 日志只写类别；`PrivacyScan.scrub` 再把邮箱、手机号、长数字打码作最后一道保险。

## 4. 分层策略（ActionPolicy）
各端原有门禁先算出**原结论**，再按顺序过：

| 层 | 规则 | 结论 |
|---|---|---|
| L1 硬拒 | 原结论是拒绝 / 只生成脚本（付款最后一下、自授权 / 自批准、远程 / 语音批准、不提权、要管理员、只读档、「从不询问」档、可疑命令硬拒、插件硬拦…） | 原样，任何东西都放不松 |
| L2 支付 / 隐私外发 | 目标在白名单，且这一步是付款相关，或要把隐私资料（文字或图片）发送 / 粘贴 / 上传 / 填写出去 | 确认，**不能**「以后不用问」；「从不询问」下拒绝 |
| L3 读过隐私后外发 | 目标在白名单，本次任务（电脑 = 这台手机 10 分钟内的会话）读到过隐私，这一步是外发 | 确认，不能记住 |
| L3e 读过外部内容后外发 | 目标在白名单，本次任务读过网页 / 短信 / 通知 / 下载内容等外部文字（`Facts.readExternal`），这一步是外发（2026-11-16 用户定「保持」，可改） | 确认，不能记住；「从不询问」下拒绝 |
| L3b 认不准 | 写法可疑 / 守卫认不准（电脑 8.3 短名、可能联网认不出、编码 IP…；手机密码 / 验证码框、读过外部内容后的发送） | 确认，不能记住（用户规则：认不准的弹确认） |
| L3u 卸载 | 目标在白名单，这一步是卸载（`Kind.UNINSTALL`；2026-11-16 用户定，可改） | 确认，不能记住；「从不询问」下拒绝 |
| L4 白名单 | 目标在白名单、原结论是「要确认」、本次放行没超过 40 步 | 放行，写审计；超过 40 步后照常问 |
| L5 默认 | 其余 | 原结论 |

**不在白名单的动作，结论和改前完全一样**（L2、L3、L3e、L3u、L4 只对白名单应用起作用）。白名单读坏了、条目还没在本机确认，都当不在白名单。

审计日志一行：`时间 \t 端 \t 平台:应用id \t 层 \t 结论 \t 动作类型 [\t out=类别] [\t read=类别] [\t ext=1]`，不含内容。
电脑：配置目录 `whitelist_audit.log`（1 MB 轮转）；手机：App 私有目录 `whitelist_audit.log`（同样轮转）。

## 5. 两端怎么落地
| | 手机（个人版 / 开源版） | 电脑 |
|---|---|---|
| 目标应用 | 手机操作任务（phone.operate）当前前台包名；工具调用参数里带的 `package` | 打开 / 关掉 / 卸载程序的 exe；`sys_exec` 第一段就是那个 exe 的**单条**命令（有管道、串联、重定向、变量、子表达式、`cmd` / `powershell` / `start` 的不算） |
| 付款 | SensitiveGuard 判「付款 / 下单」、模型自报 money、屏上有金额（Mika） | 支付 / 银行 / 钱包 / 商店类程序；付款类指令名 |
| 外发 | 发送 / 分享 / 提交类按钮，往输入框写字，PUBLIC_COMMS 工具 | 剪贴板、打开网址、联网命令、带参数的命令 |
| 读到隐私 | 本次任务读到的界面文字、输入过的内容 | 手机读走的剪贴板 / 文件 / 截图（截图按认不出的图片） |
| 读过外部内容 | 本次任务读到过浏览器 / 短信 / 邮件 / 通知栏界面（RiskLabel 污点） | 这台手机本次会话里从网上拉过内容（链接下载 `media_get` / `media_fetch`）；手机端读过外部内容后发给电脑的指令，在手机这一侧就要确认（ExecutionGate 污点不放行） |
| 卸载 | 界面上点「卸载」类按钮、`app.uninstall`（本来就是红卡） | 卸载程序指令；单条命令里带 `uninstall` / `/uninstall` / `卸载` |
| 硬规则 | 付款最后一下本人点（userMustTap）、锁屏拒、ROOT 污点拒、红卡不可逆名单、通信类 | 三条硬规则、插件硬拦、只读档、「从不询问」 |

## 6. 共享文件登记（sha256，去掉 package 行；改了要重算：`tools/hashes.sh`）
<!-- hashes -->
- AppWhitelist.java `c08775ba9a327a2daebbd3ea9c233c0276e43f9de0675cccfc2add423f4b0658`
- PrivacyScan.java `9ca5d2d640073e63821b51f0f086a2cddb4fb03fd736f8704a7cf0404c5e820c`
- ActionPolicy.java `169758b85c7c5df65705faa8cc4a6e8423e542b641a15fa1737cc9fd578462a4`
- AppWhitelistSharedTest.java `eedeae2fa50dfde7a839132a17e6ccc48a13c6d5d6c5979fb280afe10a1f663c`
- app-whitelist-vectors.json `0ca4c18c03eb2448cd0cb37a8cb269456b272f325c66452769e27b91c78dddbc`
