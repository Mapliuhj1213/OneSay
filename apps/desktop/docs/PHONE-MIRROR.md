# 手机投屏（phone-mirror M1，test/phone-mirror-1118）

电脑端「设备 › 手机投屏」页：用 USB 线连手机，在电脑上看手机、键鼠操作、拖文件过去。底层是官方 scrcpy（Apache-2.0），OneSay 只负责启动它、管设备列表、传文件。

## 做了什么
- **投屏组件按需安装**：页里点「安装」→ 从 github.com/Genymobile/scrcpy 发布页下载 **v5.0.1**（版本锁定），SHA-256 三方一致（代码里写死的官方值 / 发布页 SHA256SUMS.txt / 下载内容）才解压到 `%LOCALAPPDATA%\OneSay\tools\scrcpy\`；每个文件的 SHA-256 记进 `scrcpy.json`，每次用前核对（被改过就拒绝运行）。不随安装包附带。
- **设备检测**：组件装好后每 2 秒 `adb devices -l`；没装组件时不起任何进程。
  - 待授权 → 卡片「在手机上允许 USB 调试」+ Windows 通知
  - 授权后 → 显示「可投屏」+ 机型 / 安卓版本 + **「投屏」按钮**。**插上不会自动投屏**，点了才开窗口。
  - 第一次可投屏时读一次 getprop（品牌、型号、SDK、版本号）判断 Flyme / 音频（安卓 11+）/ 单 App 窗口（安卓 10+）。
- **拖文件**（拖到页里设备卡片上的虚线框，或「选择文件」）：`.apk` → `adb install -r`；`.apks/.xapk/.apkm/.aab` → 拒绝并说明；其他 → `adb push` 到 `/sdcard/Download/`（同名自动加「 (1)」，名字清洗）并触发媒体扫描。
  - 魅族 `INSTALL_FAILED_USER_RESTRICTED` → 中文提示打开开发者选项「USB 安装」，并给「改为放到 Download」按钮（10 分钟内有效）。
  - 其他安装 / 推送失败码都翻成中文（签名不同、降级、空间不足、ABI、取消、安全检测、断开 / 未授权 / 超时…）。
- **剪贴板**：scrcpy 自带双向同步（界面上写明注意事项）。
- **中文输入**：设置「键盘模式」普通 / 物理键盘（`--keyboard=uhid`，用手机输入法打中文）；UHID 失败时窗口关闭原因提示改回普通键盘、用 Ctrl+V。
- **单 App 窗口（原型）**：「列出 App」（`pm list packages -3`，只显示包名）→「开窗口」= `--new-display=720x1280/240 --start-app=<pkg> --flex-display --display-ime-policy=local --no-vd-destroy-content --no-audio`；每台最多 3 个，同一个 App 只能一个窗口；安卓 10 以下不给开。
- **Flyme 设置指引卡**：开发者选项 / USB 调试 / USB 安装 / 无线调试（以后）/ 支付页面黑屏说明。
- **安全**：只响应电脑界面上的点击和拖拽（本机口令保护的 `/api/mirror`、`/upload/mirror_drop`）；不注册成 agent 工具、不进 `agent.local`，手机指令和对话都调不到（测试 `notAnAgentTool` 守着）。所有命令走 argv 数组，序列号 / 包名白名单校验。

## 怎么试（Windows）
1. 用这个分支的 jar 启动 OneSay，左侧「设备 › 手机投屏」。
2. 点「安装」（约 11 MB）。
3. 手机打开 USB 调试后插线 → 手机上点「允许」→ 页面变「可投屏」→ 点「投屏」。
4. 拖一个 APK / 图片到设备卡片的虚线框。
5. 「列出 App」→ 某个 App「开窗口」。

## 待上机验证
- 魅族 21 Pro：投屏出画面时间、音频、UHID + 搜狗/讯飞拼音、`--new-display` 是否可信屏 / Flyme 副屏装饰、3 个 App 窗口的负载、「USB 安装」打开流程、改为放到 Download 后手机上安装、中文文件名 push。
- Note 8（安卓 8）：整屏镜像、无音频提示、单 App 窗口被正确拒绝、UHID 失败提示。
- Windows：scrcpy.exe 子进程是否弹控制台窗口；adb r37 和电脑上其他 adb（MuMu / Android Studio）版本不同时会互相 kill server，要观察。
- TODO(adb-redistribution)：adb.exe 的再分发条款发布前核实（见 THIRD_PARTY_NOTICES.md），核实前不进安装包 / release 配置。
