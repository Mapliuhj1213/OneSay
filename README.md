# OneSay

**一句话使唤你的手机，连电脑也能隔空指挥。** 自己配大模型，数据留在本机。

OneSay 是一套开源的中文语音助手：

| 部分 | 做什么 |
|---|---|
| **OneSay 助手**（Android） | 说一句话或打一行字，它去做：开关设置、打开 App、查信息、记事、替你点屏幕跑完多步任务。也是插件宿主。 |
| **OneSay 计划**（Android） | 存「帮你记下来的东西」：待办、日程、票、取件码、快捷指令。 |
| **OneSay 时钟**（Android） | 闹钟 + 晨间语音简报（报时、天气、降雨提醒）。 |
| **OneSay 电脑端**（Windows） | 和手机配对后互相下指令：手机让电脑开网页、跑快捷指令、下载链接；电脑让手机响铃找手机、亮屏、发剪贴板。按住空格说话，侧栏看手机上的计划。 |

三个手机 App 单独装也能用；装在一起时，助手把待办写进计划、把闹钟交给时钟。

---

## 安装

### Windows 电脑端　`测试中`

电脑端源码在 `apps/desktop`。**安装包还没有发布到 Releases**，现在可以自己从源码打包（见下文「从源码构建」）；以后发布时会附同名的 `.sha256` 文件。

1. **核对文件没被改过**（推荐）。拿到 `OneSay-Setup-<版本>-x64.exe` 后，在它所在的目录打开 PowerShell：
   ```powershell
   Get-FileHash .\OneSay-Setup-<版本>-x64.exe -Algorithm SHA256
   ```
   或者用 `certutil -hashfile OneSay-Setup-<版本>-x64.exe SHA256`。
   算出来的值要和 `.sha256` 文件里那一串**完全一样**，不一样就别运行。
2. **安装包没有代码签名**，所以 Windows SmartScreen 会提示「Windows 已保护你的电脑 / 未知发布者」。点 **更多信息 → 仍要运行**。这个提示是因为没签名，不说明文件有问题。请先做第 1 步核对。
3. 安装时**不要管理员权限**，也不会弹 UAC，默认装到 `%LOCALAPPDATA%\Programs\OneSay`，只写当前用户的注册表。可选「桌面快捷方式」（默认勾）和「开机自动启动」（默认不勾）。
4. **第一次启动时 Windows 防火墙会弹窗**，问是否允许 OneSay 通信。勾选 **「专用网络」** 后点「允许」；公用网络不用勾。不允许的话，手机在局域网里连不上电脑。
5. 支持 64 位 Windows（x64 / ARM64）。安装包自带精简版 Java 运行时，不用另装 Java。

卸载：「设置 › 应用」里卸载 OneSay。「同时删除我的数据」默认不勾，勾上后还会再问一次。
升级：直接运行新版安装包，装回原来的目录，原来的设置保留。

### Android 手机端

- 需要 Android 7.0 及以上（时钟支持 Android 6.0 起）。
- **签名安装包还没发布。** 发布前请自己编译（见下文「从源码构建」）。三个 App 之间的通道只认同一把签名，所以三个都要用**你自己的同一把密钥**签名（签名不一致时，后装的那个会安装失败）。
- 第一次打开后，在手机上手动打开这些权限：麦克风、悬浮窗、相机（扫码配对用）、存储；如果要稳定在后台唤醒，再把助手加进电池优化白名单。
- 必填：**主模型 Key**（默认 DeepSeek，也可以换成任何 OpenAI 兼容服务）和**语音识别 / 朗读 Key**（阿里云百炼）。OneSay 不内置任何 Key。

### 手机和电脑配对　`测试中`

1. 电脑和手机连同一个 Wi-Fi（同一个路由器）。
2. 电脑端打开「设置 › 连接」，在「配对手机」那里点「显示」，生成一个二维码。
3. 手机打开 OneSay 助手「设置 › 连接 › 电脑联动」，扫这个二维码（需要相机权限）。
4. 电脑端「已连接」里出现这台手机、显示「在线」就好了。之后手机说「让电脑打开 xx 网页」、电脑上点「找手机」都可以用。

说明：

- 二维码 / 配对串**等于钥匙**，不要截图发到群里。担心泄露时在电脑端点「换一把钥匙」，旧码立刻作废，再重新扫码。
- 同一个 Wi-Fi 下直接连：双向 TLS，两端各用自签证书，按证书指纹互认，电脑监听 TCP 47820，用 UDP 47821 应答已配对设备的发现。没配对的设备扫不到这台电脑。
- 连不上时先看 **Windows 防火墙**：第一次启动的弹窗要勾「专用网络」。如果当时点了取消，到「Windows 安全中心 › 防火墙和网络保护 › 允许应用通过防火墙」里给 OneSay 勾上「专用」。另外确认电脑当前网络的类型是「专用」，不是「公用」。
- 不在同一个网络时可以配中转（可选，需要自建），见 `apps/desktop/docs/PCLINK.md`。

---

## 安全：能批就不拦

原则：**除了三条硬规则，其余敏感动作都先问你；你批了就做，不会一律拒绝。**

### 三条硬规则（你批了也不行，根本不会弹确认）

1. **付款 / 转账的最后一下必须你亲手点。** 机器人可以帮你走到收银台，遇到「确认支付 / 立即付款 / 确认转账」这类按钮就停下；远程发来的付款类指令一律拒绝。
2. **机器人和远程指令不能改 OneSay 自己的权限，也不能自己批准。** 改档位、改信任列表、改「以后这类不用问」清单、远程卸载或停用 OneSay，都会被拒。给其他 App 授权的命令只生成脚本，由你自己执行。
3. **电脑不提权。** OneSay 启动的程序永远不以管理员身份运行，也不弹 UAC；需要管理员权限的操作只生成一份 `.ps1` 脚本，写明为什么需要管理员，由你自己决定要不要运行。

### 怎么问你

- **确认卡**：电脑右下角或手机屏幕上弹出，写明要做什么、为什么要问。60 秒不点 = 拒绝。
- **只认真人手指**：手机确认卡被别的窗口挡住、被无障碍服务代点、或刚被机器人点过屏幕时，点击都不算；默认焦点在「拒绝」。
- **语音和远程都不能批准**：卡片在等时对手机说「好的 / 允许」，它只会提醒你在屏幕上点。电脑上的确认可以在已配对手机的确认卡上批。
- **「以后这类不用问」**：确认卡上勾选后，同一类动作以后直接做。在「设置 › 权限与审批 › 以后这类不用问」里能看到全部，每一条都能撤销。
- **读过外部文字后，发送必须确认**：一次任务里只要读过网页、短信、邮件或通知，后面任何「发送 / 分享 / 提交」都会弹卡，就算你以前点过「始终允许」也一样。这是为了防止网页里藏的指令让助手替你乱发消息。`测试中`
- **发给云端前先脱敏**：手机号、身份证号、银行卡号、邮箱、验证码在本机换成 `[手机号#1]` 这类代号再发给大模型；需要往输入框里填时，在本机换回真实内容。「设置 › 模型与密钥」里可以关，默认开。`测试中`

### 电脑端的两个开关（沙箱档 × 审批策略）

| 沙箱档 | 能做什么 |
|---|---|
| 只读 | 只查状态，其他都不做 |
| **工作区可写**（默认） | 自由读写工作区 `%USERPROFILE%\OneSay\workspace`；执行命令、跑工作区脚本、写工作区以外的文件，先问你，批了就按你的用户权限执行 |
| 完全访问 | 按你的用户权限操作整台电脑（仍受三条硬规则约束） |

| 审批策略 | 什么时候问 |
|---|---|
| **分类**（默认） | 按动作类别，敏感的先问 |
| 按需 | 超出沙箱时才问 |
| 从不 | 一次都不问，沙箱外的直接拒绝 |

另外有一个默认开的**安全插件**：工作区里的 `.git` 等目录只读；只读档和工作区可写档下命令默认不联网，只有加进「命令可以联网的域名」的才行；拦截 `-enc`、`iex` 这类看不出真实意图的编码命令（这一项可以单独关）。三条硬规则关不掉。

### 接口优先

助手做一件事时，按这个顺序选路：**系统接口**（闹钟、计时、日历、联系人、设置页）→ **你配置过的服务接口**（天气、搜索等，没填 Key 就跳过；开源版默认关）→ **App 深链**（打不开就用浏览器打开对应网页）→ 最后才**替你点屏幕**。订票、外卖、下单只帮你打开到下单页，付款留给你。执行过程在回答上方的时间轴里能逐步展开查看，随时可以按停止键中断。部分 App 深链还没在真机上验证。`测试中`

---

## 链接下载　`测试中`

手机分享一个视频链接给 OneSay（分享菜单「下到电脑」，或者说「用电脑下载这个视频」），电脑下载完，通知栏显示进度。

- **下载工具按需安装、校验后才用**：安装包里不带下载工具。你在「设置 › 下载」点「安装」时，才从官方发布页下载 yt-dlp 和 FFmpeg，和官方公布的 SHA-256 对上才放到位；以后每次运行前再校验一遍，不对就不运行。
- **FFmpeg 只用 LGPL 构建，不随安装包分发。** 发布页上找不到 LGPL 构建时不会自动安装。
- **登录内容只用你自己导出的 `cookies.txt`**（Netscape 格式），只保留对应网站那几行，只有当前 Windows 账户能读；下载时用临时副本，用完就删。OneSay 不读浏览器 Cookie，也不装浏览器扩展。
- **链接检查**：只收 http / https；链接里正常的 `&`、`=`、`%` 查询参数都保留（比如 `?v=xxx&t=10`）；空格、`; | $ < > " ' \ { } ^`、反引号、以 `-` 开头、带账号密码的链接都拒绝。链接作为单独一个参数交给下载工具，不经过命令行解释。会自动去掉 `utm_*`、`spm`、`fbclid` 这类跟踪参数。
- **只下载你有权下载的内容。** OneSay 不破解 DRM，也不绕过网站的付费或权限限制。
- 下好的文件在 `%USERPROFILE%\OneSay\Downloads`。5 MB 以内的可以传回手机，更大的只告诉你电脑上的路径。

---

## 设置在哪里

手机和电脑的设置按同样的名字、同样的顺序分组：

| 分组 | 手机 | 电脑 |
|---|---|---|
| 连接 | 电脑联动、多设备执行端、连接器 | 配对手机、已连接、局域网直连、中转、本机证书指纹 |
| 权限与审批 | 系统权限、屏幕与剪贴板、以后这类不用问 | 沙箱档、审批策略、信任的网址域名、命令可以联网的域名、开机自动启动、以后这类不用问 |
| 模型与密钥 | 主模型（接口地址 / 模型名 / API Key）、备用模型、其他模型与密钥、发送与回答、思考模式、账户 | 服务商、接口地址、模型名、API Key、连接测试、语音识别 Key |
| 语音 | 声纹、唤醒手感、唤醒提示音、朗读 | 按住空格说话、识别方式、这些程序里不拦空格 |
| 替你操作 | 替你操作 App、接口优先、浏览器跑腿、控屏颜色 | 可打开的程序、共享给手机的文件夹、快捷指令文件夹、控屏颜色 |
| 功能 | 灵动岛、主动建议、日程、待办、计划 App、晨间简报、分钟级降水…（只有手机有） | — |
| 下载 | 把链接下到电脑 | 下载工具、默认清晰度、下载文件夹、登录内容（cookies） |
| 插件 | 各插件状态 | 安全插件 |
| 关于 | 版本、签名证书、外观、开源组件… | 版本、本机名、工作区 |

手机最下面还有一个「开发者」，默认折叠（诊断、调试工具）。

控屏颜色在两端一样：**蓝紫** = 助手在操作，**琥珀** = 等你确认，**绿** = 你接手了，**红** = 已停止。

## 从源码构建

仓库结构：`apps/` 下是三个手机 App（助手、`plan` 计划、`clock` 时钟）和 `desktop` 电脑端；`libs/` 是插件接口和执行后端插件。

**手机端**不用 Gradle：aapt2 → javac → d8 → zipalign → apksigner。需要 JDK 17、Android SDK platform 28、build-tools 30.0.3。每个 App 目录下有 `build_linux.sh`（打包）和测试脚本（助手 `run_unit_tests.sh`，计划 / 时钟 `test/run_linux.sh`）。签名口令从 `<keystore>.pass` 文件或环境变量读；keystore 和口令文件都在 `.gitignore` 里，**永远不要提交**。三个 App 请用同一把密钥签名。

**电脑端**（`apps/desktop`）只要 JDK 17，没有第三方依赖：

```sh
cd apps/desktop
sh build.sh          # 产物 build/onesay-desktop.jar
sh build.sh test     # 跑全部单元测试
```

Windows 安装包用 NSIS 打，在 Linux 上也能打，步骤见 `apps/desktop/installer/README.md`。

## 隐私

- Key 只存在本机：手机上用 Android Keystore 加密后写入应用私有存储，加密失败就不保存。带 Key 的请求只走 https / wss，不跟随重定向。
- 没有统计、遥测或更新检查。
- 电脑和手机之间的流量走双向 TLS，只认配对时记下的证书。

## 反馈

请用 Issue 模板提交：写清 App 与版本、手机型号 / Windows 版本、复现步骤和打码后的日志（不要贴 Key）。见 `CONTRIBUTING.md`。

---

## 许可

OneSay 以 **Apache License 2.0** 发布（见 `LICENSE`、`NOTICE`），免费，可以商用。

**随包分发的第三方组件**（各自遵循自己的许可，详见 `THIRD_PARTY_NOTICES.md`）：

- Eclipse Temurin 17 运行时（Windows 安装包里的 `runtime\`）：GPLv2 + Classpath Exception，许可原文在 `runtime\legal`
- sherpa-onnx、ZXing：Apache-2.0；ONNX Runtime、qrcode-generator、mermaid、Chart.js：MIT；Jakarta Mail / Activation：EPL-2.0 或 GPL-2.0 with Classpath Exception
- 移植了 Mika、phongent 的部分代码（Apache-2.0，文件头有声明）

**按需下载、不随包分发**：yt-dlp（Unlicense）、FFmpeg LGPL 构建（LGPL-3.0）。

**只借了思路、没有使用代码的项目**（致谢）：LocalSend、Home Assistant Companion for Android、OpenAI Codex CLI、pinky（Apache-2.0）；Agent-VScreen、OpenPhoneUse、涛声 TaoSheng（MIT）；omniget、android-remote-control-mcp、Wake Me / WeckMich（GPL，只读了功能说明或文档）；KDE Connect、Sefirah（只参考了产品形态）。

---

## English

**OneSay** is an open-source, Chinese-first voice assistant suite: an Android assistant, a planner and an alarm clock, plus a Windows companion that pairs with your phone so each can send commands to the other. You bring your own LLM key (DeepSeek by default, any OpenAI-compatible endpoint works). Personal data such as phone and ID numbers is masked on-device before anything goes to the cloud.

**Safety model, "ask, don't block":** apart from three hard rules, sensitive actions show a confirmation card instead of being refused. The three hard rules: you tap the final pay button yourself; the robot and remote commands can never change OneSay's own permissions or approve themselves (voice cannot approve either); the PC app never elevates, and anything that needs admin is written out as a `.ps1` script for you to run. Approvals can be remembered per action type and revoked at any time.

**Install (Windows, beta):** the Windows app lives in `apps/desktop`; no installer has been published to Releases yet, so build it from source for now (`sh build.sh`, then `installer/build-installer.sh`). Check an installer with `Get-FileHash … -Algorithm SHA256` against its `.sha256`. The installer is unsigned, so SmartScreen will warn: click **More info → Run anyway**. Per-user install, no admin rights needed. On first launch Windows Firewall asks whether to allow OneSay: tick **Private networks**, otherwise your phone cannot reach the PC on the LAN.

**Pairing:** put the phone and PC on the same Wi-Fi, open *Settings › Connection* on the PC and click *Show* under *Pair phone* to get a QR code, and scan it from the phone app under *Settings › Connection › PC link*. The QR code is the key; don't share it. Android builds: build from source and sign all three apps with the same key; signed releases are not published yet.

Licensed under Apache-2.0. The bundled Temurin runtime is GPLv2 with the Classpath Exception. yt-dlp and an LGPL build of FFmpeg are downloaded on demand and verified against their official checksums; they are never bundled. Only download content you have the right to download. OneSay does not circumvent DRM. Features marked `测试中` (testing) have passed automated tests but not yet on real devices.
