# OneSay

一个跑在 Android 手机上的语音助手与悬浮面板：按键或唤醒词叫出来，听你说话，在本地或云端路由，然后直接把事办了（开关设置、设闹钟、打开 App、读屏回答、多步代操作），结果以卡片和「灵动岛」显示。

设备相关的能力（实体按键、root、厂商专属 App）经能力探测按机型启用，不支持的在控制台置灰并写明原因。

> 灵感来自 Samsung Bixby；本项目是非官方的个人项目，与 Samsung 无关。Bixby 是 Samsung 的商标。

当前版本：**2.5.0（versionCode 21）**，包名 `com.onesay.assistant`（debug 变体 `com.onesay.assistant.debug`，可与 release 并存）。


---

## 功能

- **唤醒**：本地唤醒词（sherpa-onnx KWS）、Bixby 键短按 / 长按 / 双击（需 root 按键守护）、控制台里的模拟按键；息屏唤醒先亮屏；可续期的捕获窗、能量门 + 流式识别断句；播报中可打断。
- **路由**：本地 L0 规则直达（时间、开关、计算等不出网）→ 分诊（问答 / 执行 / 复合任务）→ L1 技能缓存 → 流式问答 / AgentLoop 多步代理。
- **执行**：统一执行网关 `UnifiedExecutor`——Schema 校验、高危确认卡、执行后回读验证；系统级动作经 root 执行守护（文件 IPC + HMAC）。
- **闹钟 / 提醒 / 日程 / 待办**、**App 管理**（未安装出安装卡）、**邮箱（IMAP）**、**连接器（MCP / Google）**、**浏览器跑腿与比价**。
- **识屏 / 传图 / 相机对话**：截图内容一律按不可信资料处理。
- **灵动岛与主动建议**：长任务进度、消息提醒、截图后一键分析、取件码 / 链接识别等，带频控与夜间静默。
- **插件体系**：`FeatureKernel` 生命周期挂钩 + `com.onesay.assistant.plug.*` 插件（富内容输出、技能包、App 知识库、防泄露、证据账本、操作守卫、多设备执行端……）。
- **设备能力与功能开关**：启动时探测 root、Shizuku、无障碍、厂商机型、Android 版本、按键等，在「关于 › 设备能力与功能」按注册表列出每个功能可用与否和原因（如「需要 root」「仅三星」）。

## 设备要求

| 项 | 要求 |
|---|---|
| Android | minSdk 24，targetSdk 28；主要在 Android 8.0（API 26）上验证 |
| CPU | arm64-v8a（仓库只带 arm64 的 sherpa-onnx / onnxruntime 库） |
| root | **可选**。没有 root 时语音唤醒、问答、卡片、灵动岛、闹钟 / 日程、邮箱等可用；系统设置类执行、Bixby 键、root 读屏 / 存原图等不可用（控制台会置灰并写明原因） |
| 三星 | **可选**。Bixby 键劫持、三星浏览器桌面模式跑腿、三星时钟联动只在三星机型上可用 |
| 权限 | 麦克风、悬浮窗（灵动岛）、通知；按功能还会申请联系人、短信、电话、定位、相机等（每项都有开关） |
| 网络 | 问答 / 云端识别 / 识屏需要你自己的模型 API Key 与服务地址（在「API 密钥」页填：服务地址只收 https，预置 DeepSeek / 百炼；Key 用 Keystore 加密保存、只发往所填主机、不进日志） |

## 构建

纯 Java（`-source/-target 1.7`），不用 Gradle；命令行脚本完成 aapt2 → javac → d8 → 打包 → zipalign → apksigner。

**依赖**（默认在 `$HOME/onesay-tools`，用环境变量改）：

| 变量 | 内容 |
|---|---|
| `TOOLS` | 工具根目录（下面几项的默认位置），默认 `$HOME/onesay-tools` |
| `JAVA_HOME` | JDK 17 |
| `SDK` | Android SDK：`platforms/android-28`、`build-tools/30.0.3` |
| `AAPT2` / `ZIPALIGN` | aarch64 主机需要原生版本（x86_64 用 SDK 自带） |
| `VENDOR` | 默认仓库内 `vendor/`（已带 sherpa-onnx 与 Jakarta Mail） |

**debug 包（clone 下来就能编，不需要任何私有文件）**：

```bash
DEBUG=1 GEN_DEBUG_KEYSTORE=1 KS=/tmp/onesay-debug.keystore OUT=/tmp/onesay-debug bash build_linux.sh
# 第一次会在 KS 指定处生成本机自用的 debug keystore（随机口令在同名 .pass，均在源码树外）
# 输出 /tmp/onesay-debug/bixbyui-debug.apk

# 只想审查 / 不签名：
UNSIGNED=1 OUT=/tmp/onesay-release bash build_linux.sh   # → bixbyui-unsigned.apk
```

**release 包**：签名库、口令、本机私有默认值都需自备，且永不入库。

```bash
cp -r local-config.example local-config      # 填自己的云端朗读音色等（可选）
KS=/path/to/release.keystore KS_PASS_FILE=/path/to/pass.txt bash build_linux.sh
```

- 口令只从环境变量 `KS_PASS` / `KEY_PASS` 或口令文件读，不出现在命令行与日志；构建结尾检查版本库里没有 keystore。
- 缺 `local-config/` 时用 `app/config` 的开源空默认值。
- OneSay、Plan、Clock 之间的跨 App 通道只认**同一证书**签名的对方：三个 App 要互通，就用同一把 keystore 签。
- `build.sh`（Windows）是早期脚本，不支持 debug 变体与 LocalDefaults，已不维护，请用 `build_linux.sh`（可在 WSL / Git Bash 下跑）。

## 测试

```bash
bash tools/ci/fetch_test_deps.sh            # 第一次：下载 JUnit 等到 $TOOLS/test-libs
bash run_unit_tests.sh                      # 全量 JVM 单元测试（不需要手机），请在本目录（apps/bixbyui）运行
bash run_unit_tests.sh ChatWindowTest       # 只跑指定测试类
BIXBY_TEST_BUILD=/tmp/bjvm bash run_unit_tests.sh   # 编译输出放到别处
```

全量约 3000 个用例，需全部通过才合并。真机验收脚本：`acceptance/device_acceptance.sh`（需要自备已 root 的测试机，说明见 `acceptance/README.md`）。CI 见仓库根目录 `.github/workflows/`。

## 目录结构

```
app/
  AndroidManifest.xml
  src/com/onesay/assistant/v2/        主程序（Android 适配 + 业务）
    platform/                设备能力探测、功能注册表、设备相关接口（唤醒入口 / root / 厂商）
    wake/ asr/ tts/          唤醒、识别、合成
    router/ agent/           路由、分诊、AgentLoop
    executor/                统一执行网关与各后端（shell / intent / 邮件 …）
    ui/                      悬浮面板、对话窗、控制台
    kernel/                  插件内核（FeatureKernel / FeatureSwitch）
  src/com/onesay/assistant/plug/      可插拔模块：*/core 为纯 Java 核心，*/host 为 Android 宿主
  config/                    随仓库的空 LocalDefaults（本机私有值放 local-config/，不进 git）
  src-release/               release 占位类（不含调试接收器）
  variant/{debug,release}/   编译期变体常量
  res/ res-debug/ assets/
test-jvm/                    JVM 单元测试与夹具
vendor/                      第三方二进制与源码（sherpa-onnx、Jakarta Mail）
tools/                       CI 检查、按键 / 执行守护脚本、探针
acceptance/                  真机验收脚本
docs/                        功能说明（快捷指令、分钟级降雨）
```

## 隐私

- 唤醒词识别、L0 规则、声纹都在本机完成；只有你触发问答 / 云端识别 / 识屏时，才把文字、语音或截图发给**你自己配置**的模型服务。
- API Key 用 Android Keystore 加密存储，界面只显示打码后的末几位，日志不打印。
- 截图、读到的屏幕文字一律当不可信资料：密码框整节点丢弃，卡号 / 证件号打码；识屏中间文件放在 App 私有目录。
- 发短信、打电话、付款、发邮件等对外动作都有确认卡；电话一律红卡，没有「始终允许」。
- 仓库里的测试夹具都是合成或已脱敏的数据；提 issue 时请不要贴未打码的日志、截图或 Key（见 [SECURITY.md](../../SECURITY.md)）。

## 参与

见仓库根目录的 [CONTRIBUTING.md](../../CONTRIBUTING.md)、[CODE_OF_CONDUCT.md](../../CODE_OF_CONDUCT.md)，以及本目录的 [CHANGELOG.md](CHANGELOG.md)。

## 许可

Apache License 2.0，见根目录 [LICENSE](../../LICENSE) 与 [NOTICE](../../NOTICE)（Copyright 2026 Mapliuhj1213）。移植自 Mika / phongent 的部分同为 Apache-2.0，署名见 `NOTICE`；其余第三方组件见 [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)。
