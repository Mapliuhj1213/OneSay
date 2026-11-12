# 更新记录

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本（0.x 阶段次版本可能含不兼容改动，但数据格式与Provider 接口始终保持向后兼容）。

## [未发布]（feat/borrow-1112-companion，2026-11-12，版本号未改）

### 新增
- 待办强提醒（思路来源：涛声 TaoSheng，MIT；代码自写）：到点持续响铃（闹钟音量）+ 锁屏整屏；音量键稍后（默认 10 分钟，设置可改）、电源键或「停止」停止、「完成」勾掉；响 5 分钟没人管自动稍后，最多 3 次（每条各自计次）。
- 并表同时提醒：几条强提醒同时到点（或一条在响时又到一条）合并成一次响铃、一条通知、一个整屏页，每条有自己的「完成」；音量键 = 全部稍后，电源键 / 停止 = 全部停止；全部完成后自动停铃，没完成的继续响。
- 给 OneSay 的提醒接口（数据接口 call，门卫照旧：本 App / signature 权限 / 同证书）：`listRemindedTasks`、`completeRemindedTask`，见 docs/provider-api.md。

### 变更
- 表结构 4：todos 加 `strong`（默认 0）；旧库升级只补这一列。

## [未发布]（refactor/oss）

用户可见行为不变；数据格式、Provider 接口、设置键名不变。

### 新增
- 设置页探测系统能力（Android 版本、通知权限 / 通知类别、精确闹钟权限、省电白名单、厂商）：不可用的项置灰并写明原因，探测不到按可用；不改已保存设置和提醒排程。
- `BUILD_TYPE=debug` 构建：不需要任何私有文件即可编出 debug 包。
- GitHub Actions：JVM 单测 + debug 包构建。
- 开源工程文件：README、CONTRIBUTING、CODE_OF_CONDUCT、SECURITY、THIRD_PARTY_NOTICES、LICENSE 占位、issue / PR 模板、.editorconfig。

### 变更
- release 签名配置改为环境变量 / 未跟踪的 `signing.env` 注入（模板 `signing.env.example`），去掉脚本里的私有默认路径；`EXPECT_CERT` 可覆盖。
- 纯逻辑摘到 `core/`：页签与 0 点刷新（`TabNav`）、自动任务 next_at 预估（`AutomationTiming`）、调用方 source 判定（`AccessPolicy.source`）、默认值（`Defaults`）、系统能力判定（`Capabilities`）。
- `core/` 公共 API 补 Javadoc。
- JVM 单测 68 → 112（补 core/recur 特征测试等）；测试固件里的真实门店名换成虚构名。

### 修复
- 全新 clone 因缺少空目录 `app/assets` 而构建失败。

### 移除
- 无调用的死代码：`PlanProvider.asMap`、`Pages.wrap`、`Ui.button`。

## [0.1.4] - 2026-11-05
- 没指定页签的打开（桌面图标、OneSay「打开计划」）一律进「今天」；从后台回来也回到今天，App 内部页返回不重置。
- 前台时每天 0 点（+1 秒）自动重画；改时间 / 时区 / 日期变化也重画；跨天回到顶部。

## [0.1.3] - 2026-11-05
- 删除票（含软删、过期归档）、日程软删、待办完成、取件码已取时撤销已弹出的提醒通知并重排闹钟。

## [0.1.2] - 2026-11-05
- 到点追补时同一条只发最新一档。
- 认票更严格：选座 / 待支付页不建票；取票码优先于订单号且允许带空格。

## [0.1.1] - 2026-11-05
- 票临期提醒：默认提前 120 + 30 分钟两档（设置可改，票自身 `remind_lead_min` 加档 / 0 关），文案带开场时间与取票码，锁屏可见，带「出示票码」按钮；过期票自动归档。
- 日程软删 `deleted_at` + 结构版本 2（升级幂等补列，数据不丢）；软删 7 天后清理。

## [0.1.0] - 2026-11-05
- 首个版本：六表 Provider（signature 权限）、界面（今天 / 日程 / 待办 / 票夹 / 取件 / 自动）、本地提醒、OneSay 一次性导入。
