# OneSay Plan（com.onesay.plan）

> A small Android app (Java, no Gradle, no third-party runtime libraries) that keeps **today / events / todos / tickets / pickup codes / automations** in one place, with an exported, signature-protected `ContentProvider` so the companion assistant app *OneSay* can read and write them. Documentation is in Chinese.
>
> 灵感来自 Samsung Bixby；本项目是非官方的个人项目，与 Samsung 无关。Bixby 是 Samsung 的商标。界面全部用代码绘制，不包含 Samsung 的任何资源。

一个独立的小应用，用来放 OneSay 帮你记下来的东西。OneSay 通过「Provider 接口」读写这里的数据。没装计划 App 时，OneSay 会自动改用自己的本地存储，功能不会少。

## 功能

- **今天**：一页看完今天的日程、待办、快到时间的票和取件码。打开 App（桌面图标、OneSay「打开计划」、从后台回来）默认进「今天」；前台时每天 0 点自动刷新。
- **日程**：支持重复（每天 / 每周 / 工作日 / 每月 / 每年）、农历和提前提醒；工作日按国务院节假日与调休表；OneSay 删除日程后 10 分钟内可以撤销。
- **待办**：到点会提醒，在通知上就能点「完成」。
- **票夹**：火车、航班、电影、演出。可以从短信或订单文字识别（待支付和选座页不会建票）；全屏出示二维码或取票码；开场或出发前 2 小时和 30 分钟各提醒一次（可以改）；过期后自动归档。
- **取件码**：截止前 2 小时提醒，通知上有「复制取件码」和「已取」。
- **自动任务**：查看和编辑 OneSay 的定时任务和触发任务，可以看最近的运行记录。到点执行仍然由 OneSay 负责。
- **设置**（右上角 ⋮）：到点提醒开关、票提醒档位、从 OneSay 导入。打开设置时会探测系统能力（通知权限、通知类别、精确闹钟权限、省电策略），不可用的项置灰并写明原因；探测不到的按可用处理。

## 设备要求

- Android 7.0（API 24）及以上；targetSdk 26，按 Android 8.0 行为运行。
- 不联网、不申请危险权限。只用：开机广播（重排提醒）、振动、唤醒锁。
- 部分厂商的省电策略可能延迟后台提醒（设置页会提示）。

## 构建和测试

不用 Gradle，纯命令行：aapt2 → javac(1.7) → d8 → zipalign → apksigner。需要 JDK 17、Android SDK 的 `platforms;android-28` 和 `build-tools;30.0.3`。

```sh
# 任何人 clone 后即可：debug 包（debug 签名、可调试），不需要任何私有文件
BUILD_TYPE=debug JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/sdk bash build_linux.sh   # → /tmp/onesay-plan-debug/bixbyplan-debug.apk

# JVM 单测（JUnit 4，纯 Java 的 core/ 层）
bash tools/fetch_test_libs.sh /tmp/test-libs        # Maven Central，SHA-256 校验
TEST_LIBS=/tmp/test-libs OUT=/tmp/plan-test JAVA_HOME=… ANDROID_HOME=… bash test/run_linux.sh

# 不签名的 release（审查用）
BUILD_TYPE=release UNSIGNED=1 bash build_linux.sh

# 签名的 release：签名配置从环境变量或未跟踪的 signing.env 读取，模板见 signing.env.example
cp signing.env.example signing.env   # 填 KS / KS_ALIAS；口令用 KS_PASS 或 KS_PASS_FILE，不要写进文件
bash build_linux.sh
```

- Plan 只接受与自己**同一证书**签名的调用方（signature 级权限 + 运行期同签名校验），不内置任何证书指纹；要和 OneSay 互通，两者用同一把 keystore 签。可选 `EXPECT_CERT` 在构建时钉死证书指纹。
- keystore 和口令都不进 git（见 `.gitignore`）；口令不会出现在脚本输出里。
- debug 包和已安装的 release 包名相同、证书不同，不能覆盖安装，需要先卸载（会清掉数据）。
- CI：仓库根目录 `.github/workflows/ci.yml` 跑 JVM 单测并验证 debug 包能编出来。

## 与 OneSay 的对接接口

完整说明（URI、字段表、取值、门卫顺序、call、广播、示例命令）见 [docs/provider-api.md](docs/provider-api.md)。摘要：

| 类型 | 名称 | 说明 |
|---|---|---|
| ContentProvider | `content://com.onesay.plan.provider/{events,todos,tickets,pickups,automations,runs}[/<_id>]` | 增删改查；写入走同一套校验（`core/Validator`） |
| 只读聚合 | `content://com.onesay.plan.provider/today` | 列 `kind ref_id at title sub flag` |
| 文件 | `content://com.onesay.plan.provider/tickets/<_id>/qr` | 票二维码图，`openFile` r/w |
| call | `ping` / `import_bundle` / `import_file` / `log_run` | 见文档第 4 节 |
| 权限 | `com.onesay.plan.permission.ACCESS`（signature） | 另有运行期同签名校验兜底；root / adb shell 无特殊通道，见 `core/AccessPolicy` |
| 广播 | `com.onesay.plan.action.DATA_CHANGED` | receiverPermission = ACCESS；extras `table op id by` |
| Activity | `com.onesay.plan.action.OPEN` | extra `tab` = `today/events/todos/tickets/pickups/automations`；不带 `tab` 进「今天」；不认识的值保持当前页（`core/TabNav`） |
| 数据去重 | `src_key` | 唯一；OneSay 导入用 `bixbyui:<来源>:<原 id>` 前缀（如 `bixbyui:todo:12`） |

常量都在 `core/Contract.java`，OneSay 侧的 `PlannerContract` 与它逐项一致。**改这些名字会破坏已安装用户的数据和 OneSay 对接**，请不要改。

## 隐私

- 所有数据只存在本机 App 私有目录（`plan.db`、`files/qr/`），不联网、不上传、`allowBackup=false`。
- 数据接口只对与 Plan 同一证书签名的 App 开放；root、adb shell 一律拒绝。
- 票提醒在锁屏可见（含取票码）、票全屏页可在锁屏上显示，这是为了检票方便的有意设计；介意的话可在系统通知设置里隐藏锁屏内容。
- 日志只记计数、时刻和调用方包名，不记票据、取件码等内容。
- 测试固件全部是虚构数据；请不要在 issue、PR、测试里提交真实票据、取件码、手机号、地址或截图（见 SECURITY.md）。

## 目录

```
app/src/com/onesay/plan/
  core/      纯 Java，可 JVM 单测：契约、表结构、校验、门卫、提醒排程、认票、导入映射、页签规则、系统能力判定、默认值
  core/recur 重复日程、农历、节假日表、定时排期
  data/      数据层：Provider（数据接口）、数据库、唯一写入口 Store、设置
  platform/  Android 适配：系统能力探测
  remind/    精确闹钟和通知、开机重排
  ui/        界面：首页六个页签、编辑框、票全屏
test/        JUnit 单测 + JVM shims（android.util.Log、ICU 农历）
tools/       export_bundle.py（从 OneSay 库导出迁移包，调试用）、fetch_test_libs.sh
docs/        Provider 接口说明、快捷指令
```

## 参与

见仓库根目录的 [CONTRIBUTING.md](../../CONTRIBUTING.md)、[CODE_OF_CONDUCT.md](../../CODE_OF_CONDUCT.md)、[SECURITY.md](../../SECURITY.md)。版本记录见 [CHANGELOG.md](CHANGELOG.md)。

## 许可

Apache License 2.0，见根目录 [LICENSE](../../LICENSE) 与 [NOTICE](../../NOTICE)（Copyright 2026 Mapliuhj1213）。第三方组件见 [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)。
