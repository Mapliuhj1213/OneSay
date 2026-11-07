# OneSay

OneSay 是一组 Android 应用：一个语音助手，加两个配套小应用。

> 灵感来自 Samsung Bixby；非官方项目，与 Samsung 无关。Bixby 是 Samsung 的商标。

| App | 包名 | 做什么 |
|---|---|---|
| **OneSay**（助手） | `com.onesay.assistant` | 语音 / 文字入口：听懂一句话，去执行（设置开关、打开 App、查信息、记事、跑腿检索……）。也是插件宿主。 |
| **OneSay Plan** | `com.onesay.plan` | 放「帮你记下来的东西」：票、取件码、待办、日程、快捷指令。 |
| **OneSay Clock** | `com.onesay.clock` | 闹钟 + 晨间语音简报（报时、天气、降雨提醒、可选新闻）。 |

## 三个 App 怎么配合

- 助手是入口：你对 OneSay 说「记下这张票」「明早七点叫我」，它把数据写进 Plan，或者让 Clock 排闹钟。
- Plan 只负责存和展示，通过 ContentProvider 把数据开放给助手（接口说明见 `apps/plan/docs/provider-api.md`）。
- Clock 独立工作：到点响铃并念出简报。
- **闹钟落点**：装了 OneSay Clock（且与助手同一签名）时，「设个 7 点的闹钟」「关掉八点的闹钟」「删除所有闹钟」都写进 Clock（接口说明见 `apps/clock/docs/03-闹钟接口.md`）；没装 Clock 时，设闹钟交给系统时钟（`AlarmClock.ACTION_SET_ALARM`），手机上没有能接的时钟 App 就明确告诉你没设成功；开关 / 删除闹钟需要 OneSay Clock。
- **跨 App 通道只认同一证书**：Plan 的数据接口、助手 → Clock 的闹钟接口、Clock → 助手的简报状态广播都用 `signature` 级权限 / 运行期同签名校验保护，代码里不内置任何证书指纹；root、adb shell 没有特殊通道。所以三个 App 必须用同一把密钥签名——从 Releases 一起下载的包已经满足；自己编译时请三个都用你自己的同一把密钥签名（闹钟接口权限在助手和 Clock 两边都声明，签名不同时后装的那个会安装失败 `INSTALL_FAILED_DUPLICATE_PERMISSION`）。

单独装任何一个也能用：没装 Plan 时助手改用自己的本地存储；Clock 不依赖助手。

## 安装

从 **Releases** 页（<https://github.com/Mapliuhj1213/OneSay/releases>）下载三个 APK（同一版本、同一签名）安装即可，Android 7.0（API 24）及以上（Clock 支持 Android 6.0 起）。

## 配置 API 密钥（必做）

OneSay 不内置任何 Key。打开各 App 的「API 密钥」面板（助手：设置 › 模型与密钥 › API 密钥；Clock：设置 › API 密钥），每个服务一行：状态（未配置 / 已配置 / 测试通过 / 失败）、粘贴框、「测试连通性」、「去申请」（跳到官方控制台）。

| 服务 | 用在哪 | 助手 | Clock |
|---|---|---|---|
| DeepSeek（或任何 OpenAI 兼容服务） | 理解指令、回答问题、多步任务。服务地址（Base URL）和对话模型名可在面板里改，只收 https，预置 DeepSeek | **必填** | — |
| 阿里云百炼 DashScope | 语音识别、云端朗读、识屏 / 看图。兼容模式地址、实时识别地址（只收 `wss://`）、识别语言、整段 / 实时识别模型、识屏模型都可在面板里改，留空恢复预置 | **必填** | **必填**（朗读） |
| 阿里云智能语音 NLS | 备用一句话识别。AppKey / AccessKey、识别网关地址与 Token 签发地址（只收 https）都在面板里填 | 可选 | — |
| 云端朗读音色 | 念出回答 / 简报。音色默认空：在面板里「拉取音色列表」或手动填写音色 ID，可「试听」。服务地址可改成任何 DashScope 兼容服务（只收加密地址：助手 `wss://`，Clock `https://`） | **必填** | **必填** |
| Tavily / 博查 | 联网搜索（不填就用免 Key 的网页搜索） | 可选 | — |
| 和风天气 | 分钟级降雨提醒 | 可选 | 可选 |

**没有语音 Key 或没选音色 = 不朗读**（不会回退到系统 TTS）。
Clock 面板里还有「**预合成朗读表**」：选好音色后一次把报时、温度、天气、收尾等固定句合成到本机，之后闹钟播报的固定部分不需要联网（朗读表说明：`apps/clock/docs/phrase-table.md`）。

## 可选：执行后端（root / Shizuku）

有些动作需要 shell 身份（例如清理最近任务、部分系统开关）。默认**不选任何后端**，这些动作保持置灰。
在助手「设置 › 关于 › 设备能力与功能 › 执行后端（特权动作）」里可以选：

- **root**（`libs/plugins/exec-root`）：su 直连，或配合 root 执行守护；
- **Shizuku**（`libs/plugins/exec-shizuku`）：通过 Shizuku（root / 有线 adb / 无线调试启动）获得 ADB 级 shell。本版是接口桩，后端在线检测之后才点亮。

## 唤起入口

在助手「设置 › 关于 › 设备能力与功能 › 唤起入口」里每个入口一行开关，关掉的入口触发一律丢弃：

| 入口 | id | 默认 | 说明 |
|---|---|---|---|
| 默认数字助理 | `entry.assistant` | 开 | 系统「默认应用 › 数字助理」选 OneSay 后，长按 Home / 电源键或助理手势唤起（VoiceInteractionService） |
| 快捷开关 | `entry.qs_tile` | 开 | 通知栏编辑里把「OneSay」拖进快捷开关 |
| 桌面小部件 | `entry.widget` | 开 | 长按桌面 › 小部件 › OneSay |
| 桌面快捷方式 | `entry.shortcut` | 开 | 长按 OneSay 图标 › 「语音唤起」（Android 7.1+） |
| 本地唤醒词 | `entry.wake_word` | 关 | 见下一节 |
| 外部 App 唤起 | `entry.intent` | 关 | 公开 Intent，见下文 |
| 三星 Bixby 键（厂商插件） | `vendor.samsung.bixby_key` | 关 | 需要三星带 Bixby 键的机型 + root + 按键守护（`apps/bixbyui/tools/kdaemon`）；其他手机永远置灰 |

**外部 App 唤起**（默认关）：打开开关后，其他 App 可以

```bash
adb shell am start -a com.onesay.assistant.action.SUMMON --es com.onesay.assistant.extra.MODE voice   # 或 text：只打开面板
```

调用方 App 必须在自己的 Manifest 里 `<uses-permission android:name="com.onesay.assistant.permission.SUMMON"/>` 并在运行时向用户申请（危险级权限，系统会弹框）；没开开关时这个组件处于停用状态，别的 App 根本解析不到。

### 如何接入自定义入口

入口接口在共享模块 `libs/plugin-api` 的 `com.onesay.plugin.api.entry`：

- `EntryTrigger`：`id()`、`label()`、`description()`、`kind()`（`SYSTEM` 由系统 / 桌面投递，`LISTENER` 需要常驻监听）、`defaultEnabled()`、`isAvailable(EntryEnv)`、`start(EntryEnv)` / `stop(EntryEnv)`；
- `EntryRegistry`：注册、开关、可用性、`fire(id, mode)`（入口被触发时调用；宿主检查开关后才真正唤起）；
- `EntryEnv`：宿主提供的 Android Context、API 级别和设备能力（`ROOT`、`MIC`、`BIXBY_KEY`……）。

最小例子（摇一摇唤起，`libs/plugins/entry-shake/src/...`）：

```java
public final class ShakeEntry implements EntryTrigger {
    private android.hardware.SensorEventListener l;
    public String id() { return "entry.shake"; }
    public String label() { return "摇一摇"; }
    public String description() { return "亮屏时用力摇两下开始聆听"; }
    public Kind kind() { return Kind.LISTENER; }
    public boolean defaultEnabled() { return false; }          // 会开麦的入口一律默认关
    public Tri isAvailable(EntryEnv env) { return Tri.YES; }
    public String unavailableReason(EntryEnv env) { return null; }
    public boolean start(EntryEnv env) {
        android.content.Context c = (android.content.Context) env.androidContext();
        // 注册加速度传感器；检测到摇动时：
        //   EntryRegistry.fire(id(), Mode.VOICE);
        return true;
    }
    public void stop(EntryEnv env) { /* 注销传感器 */ }
}
```

然后在宿主 `apps/bixbyui/app/src/com/onesay/assistant/v2/entry/Entries.java` 的 `ensureInit` 里加一行 `EntryRegistry.register(new ShakeEntry());`，设置页会自动出现这一行开关。三星 Bixby 键就是这样接的（`com.onesay.assistant.vendor.samsung.BixbyKeyEntry`）。

## 可选：本地唤醒词

默认关闭，入口是快捷开关、小部件、桌面快捷方式或「默认数字助理」（长按电源 / 手势唤起）。
需要时先用 `apps/bixbyui/tools/fetch_kws_model.sh` 下载 sherpa-onnx 唤醒模型（模型许可在上游未明确，所以不随包），再在「设置 › 关于 › 设备能力与功能 › 本地唤醒词」里填你自己的唤醒词 token 序列（中文带声调拼音 token / 英文 CMU 音素，会按模型 `tokens.txt` 校验）。OneSay 不内置任何唤醒词。

## 插件 API

```
libs/
  plugin-api/                 共享接口（纯 Java）：com.onesay.plugin.api.*
    src/com/onesay/plugin/api/exec/ExecBackend.java   执行后端接口
    src/com/onesay/plugin/api/exec/ExecBackends.java  注册 / 选择 / 在线检测
    src/com/onesay/plugin/api/entry/                  唤起入口接口（EntryTrigger / EntryRegistry / EntryEnv / SummonHost）
  plugins/
    exec-root/                root 后端（com.onesay.plugin.exec.root）
    exec-shizuku/             Shizuku 后端（com.onesay.plugin.exec.shizuku）
```

- 插件是一个 `libs/plugins/<名字>/src` 源码目录，只依赖 `libs/plugin-api`。
- App 的构建脚本会把 `libs/plugin-api/src` 和 `libs/plugins/*/src` 加入源码路径（见 `apps/bixbyui/build_linux.sh` 的 `ONESAY_LIBS`），宿主在启动时注册（助手里是 `v2/platform/ExecBackendHost`）。
- 新插件：在 `libs/plugins/` 下建目录，实现 `plugin-api` 里的接口，在宿主里 `register` 一行即可；默认关，用户在设置里打开。

## 构建

不用 Gradle：aapt2 → javac（`-source 1.7`）→ d8 → zipalign → apksigner。需要 JDK 17、Android SDK platform 28、build-tools 30.0.3。

```bash
# 测试（三个 App）
cd apps/bixbyui && BIXBY_TEST_BUILD=/tmp/onesay-test bash run_unit_tests.sh
cd apps/plan    && OUT=/tmp/plan-test  bash test/run_linux.sh
cd apps/clock   && OUT=/tmp/clock-test bash test/run_linux.sh

# 打包（各 App 目录下的 build_linux.sh / build_release.sh）
```

签名：构建脚本从 `<keystore>.pass` 文件或环境变量读口令；`UNSIGNED=1`（Plan 需同时 `BUILD_TYPE=release`）可出未签名的 release 包供审查；debug keystore 自动生成在 `/tmp`，不进源码树；keystore 与口令文件都被 `.gitignore` 排除，**永远不要提交**。三个 App 请用同一把密钥签名（见上文）。

## 隐私

- Key 只存在本机：用 Android Keystore（AES-256-GCM，不可导出）加密后写入应用私有存储；加密失败就不保存，不落明文。
- Key 只发往对应服务的官方地址或你自己填写的地址（只收 https / wss），带 Key 的请求不跟随重定向；大模型请求发出前再核对一次目标主机。
- 密钥面板禁止截屏、不进最近任务缩略图（FLAG_SECURE），保存后清空剪贴板里的这把 Key；日志只记录长度。
- 没有统计、遥测或更新检查。不经你配置就会发生的外部请求只有一项「联网反查所在城市」，默认关（设置 › 关于 › 设备能力与功能 › 隐私与外部请求）。
- 三个 App 都是 `allowBackup=false`（targetSdk ≤ 30，云备份与设备间迁移都关闭），不会带走 Key。

## 已知限制

- **三星相关代码仍有残留**：Bixby 键已改成默认关的厂商入口插件（`vendor.samsung.bixby_key`），唤醒提示音的三星原声候选、三星浏览器已在 `com.onesay.assistant.vendor.samsung`；三星浏览器桌面模式跑腿、读三星天气时钟数据库的城市、三星设置项诊断、三星商店深链仍是通用代码里的分支，按能力探测启用，在其他手机上置灰或退回通用实现。
- **界面只有中文**：唤醒、识别默认中文（识别语言可在密钥面板改），日期格式按中文写死。
- **晨间简报联动**：助手只接收 OneSay Clock 的简报状态广播（`com.onesay.clock.BRIEF_STATE`）；「重播 / 跳过 / 详情」等控制命令仍指向旧接口，Clock 尚未实现。
- **Shizuku 后端是桩**：`libs/plugins/exec-shizuku` 只有接口与在线检测，还不能真正执行命令。
- **本地唤醒词需要手动准备**：要先运行 `apps/bixbyui/tools/fetch_kws_model.sh` 下载模型，再在设置里填自己的唤醒词 token 序列。
- **onnxruntime 版本未知**：`apps/bixbyui/vendor/sherpa/jniLibs` 里的 `libonnxruntime.so` 来自早期构建，确切版本号尚未核实（见 `THIRD_PARTY_NOTICES.md`）。
- **root 功能需自行配置证书**：root 执行守护（`apps/bixbyui/tools/kdaemon`）与快速读屏助手不内置任何证书，需在设备上设置 `BIXBY_EXEC_SIG_REL` / `BIXBY_EXEC_SIG_DBG` 并在 `FsPins` 填自己的证书指纹，否则这些通道一律拒绝。外部晨间播报 App 的来源对账同样依赖 root。
- 主要在 Android 8.0 上验证；真机验收清单（`apps/bixbyui/acceptance/`）是针对作者测试机写的，仅供参考。

## 反馈

请用 Issue 模板提交：写清 App 与版本、机型、Android 版本、ROM、配置了哪些服务（不要贴 Key）、复现步骤和打码后的日志。见 `CONTRIBUTING.md`。

## 声明

OneSay 是独立的非官方项目，与 Samsung Electronics 没有任何关联，也未获其认可。
许可证：**Apache License 2.0**（见 `LICENSE`，署名见 `NOTICE`）。Copyright 2026 Mapliuhj1213。第三方组件各自的许可证见 `THIRD_PARTY_NOTICES.md`。

---

## English

OneSay is a set of three Android apps, inspired by Samsung Bixby (unofficial, not affiliated with Samsung):

- **OneSay** (`com.onesay.assistant`) — voice/text assistant and plugin host.
- **OneSay Plan** (`com.onesay.plan`) — tickets, pickup codes, todos, schedules, shortcuts.
- **OneSay Clock** (`com.onesay.clock`) — alarms with a spoken morning briefing.

Inter-app channels only accept callers signed with the same certificate (signature-level permission / runtime signature match; no certificate fingerprints are hard-coded, no root/adb channel), so all three apps must be signed with the same key (the Releases builds are). Releases: <https://github.com/Mapliuhj1213/OneSay/releases>.

**Setup:** install the three APKs from Releases, then open the *API keys* panel in each app. DeepSeek (assistant) and Alibaba Cloud DashScope (speech recognition, cloud TTS, vision) are required; a cloud TTS voice must be picked — without a speech key and voice nothing is spoken (no system-TTS fallback). Tavily/Bocha search and QWeather rain alerts are optional. Keys are stored on-device only, encrypted with Android Keystore.

**Optional:** root / Shizuku execution backends (`libs/plugins/*`, off by default); a user-defined local wake word (off by default, model downloaded separately).

**Entry points:** default digital assistant, Quick Settings tile, home-screen widget, launcher shortcut, local wake word, a public `com.onesay.assistant.action.SUMMON` intent (off by default; callers need the dangerous-level permission `com.onesay.assistant.permission.SUMMON`), and the Samsung Bixby key as an optional vendor plugin (off by default). Each has an on/off switch under *Settings › About › Device capabilities › Entry points*. Custom entries implement `com.onesay.plugin.api.entry.EntryTrigger` (`libs/plugin-api`) and register in `Entries.ensureInit` — see *如何接入自定义入口* above.

**Known limitations:** the Bixby key is now an opt-in vendor entry plugin, but some Samsung-specific branches (Samsung Internet desktop-mode errands, Samsung weather-clock city, Samsung settings diagnostics, Galaxy Store deep links) remain in shared code (capability-probed, greyed out or falling back elsewhere); UI is Chinese-only; the Shizuku backend is a stub; the wake word needs a model download plus user token entry; the bundled onnxruntime version is unknown. No telemetry; the only outbound request not configured by you (reverse-geocoding your city) is off by default.

**Build:** plain command-line toolchain (aapt2 / javac / d8 / apksigner), see *构建* above. Never commit keystores or passphrases.

License: **Apache License 2.0** (see `LICENSE` and `NOTICE`), Copyright 2026 Mapliuhj1213. Third-party notices: `THIRD_PARTY_NOTICES.md`. Bixby is a trademark of Samsung.
