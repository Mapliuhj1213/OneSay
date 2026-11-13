# 伴侣连接（Companion Link）

助手（`com.onesay.assistant` / debug 包 `com.onesay.assistant.debug`）和自家的**计划**（`com.onesay.plan`）、**时钟**（`com.onesay.clock`）之间的专用接口。
只要自家 App 装了、证书对得上、握手通过，待办 / 日程 / 闹钟这类意图**只走这条连接**（规则层，在大模型和 AgentLoop 之前）；
AgentLoop 不会再替这些意图去探测第三方 App，也不需要 shell / root。没装就保持原来的回落。

> 起因（2026-11-08 真机，oss-device-1108 报告 §3.4 / §4 / §5）：
> 1. 语音「删除待办 X」没有走计划 App，而是进了 AgentLoop，AgentLoop 经 root 探测第三方笔记 App，失败，待办没删掉；
> 2. debug 助手把 CONTROL 权限改名成 `com.onesay.assistant.debug.permission.CONTROL`，时钟清单里写死的却是 release 名，
>    `BRIEF_STATE` 广播还写死发给 `com.onesay.assistant`，所以 debug 组合下时钟 → 助手的简报状态到不了；
> 3. 助手收到简报广播后要经 root 读时钟私有目录对账，开源版没有 root，所以永远对账失败。

## 1. 一份版本化接口

| | 计划 | 时钟 | 助手 |
|---|---|---|---|
| 通道 | 已有数据 provider `com.onesay.plan.provider` 的 `call()` | 新 provider `com.onesay.clock.link`（只有 `call()`） | 有序广播 `com.onesay.link.action.QUERY`（`setPackage` = 配对的助手） |
| 保护 | signature 权限 `com.onesay.plan.permission.ACCESS` + 代码门卫 AccessPolicy | signature 权限 `com.onesay.clock.permission.ALARM_CONTROL` + 代码里再验权限和同证书（provider 的 `call()` 系统不查权限） | 助手的 signature 权限 `<助手包名>.permission.CONTROL` |
| 契约源码 | `apps/plan/.../core/LinkContract.java` | `apps/clock/.../core/LinkContract.java` | `apps/bixbyui/.../v2/companion/CompanionContract.java` |

三份契约逐项一致，由助手的 `CompanionLinkConsistencyTest` 读三边源码比对。

**接口名** `onesay.companion`，**版本** `1`（不兼容改动才 +1；新增函数只加进 caps）。

### 握手 `link.hello`

请求 extras：`client_version`（助手的接口版本）、`client_pkg`。
返回 Bundle：

| 键 | 含义 |
|---|---|
| `iface` | 必须是 `onesay.companion`（防同名 authority 冒充） |
| `version` | 对端接口版本 |
| `min_client` | 对端要求的助手最低接口版本 |
| `caps` | 能力（逗号串，例如 `createTask,listTasks,…`） |
| `app_version` | 对端 versionName |
| `descriptor` | 能力清单 JSON（见 §5，OACP 形状） |

### 函数（AppFunctions 风格命名）

| 对端 | 函数 | 参数（extras） | 结果 |
|---|---|---|---|
| 计划 | `createTask` | `title`，可选 `due_at` | `id` |
| 计划 | `listTasks` | 可选 `include_done` | `data` = 行 JSON 数组 |
| 计划 | `updateTask` | `id`、`title` | `count` |
| 计划 | `completeTask` | `id`，可选 `done`（默认 true） | `count`（同时停用它派生的提醒） |
| 计划 | `deleteTask` | `id` | `count`（同时删掉它派生的提醒，不留孤儿） |
| 计划 | `createEvent` | `title`、`start_at`，可选 `end_at`、`location` | `id` |
| 计划 | `listEvents` | 可选 `from`、`to` | `data` |
| 时钟 | `createAlarm` / `listAlarms` / `deleteAlarm` / `toggleAlarm` | 与旧 `ALARM_SET / QUERY / CANCEL / TOGGLE` 广播完全相同（`hour minute days label` / `id` / `id on`） | `code` + `data` = 闹钟表 JSON（与旧有序广播 resultData 同格式） |
| 时钟 | `getBriefStatus` | — | `data` = `files/state.json` 原文（`state` / `detail` / `at` …） |
| 助手 | `getAssistantStatus`、`link.hello` | — | resultData = JSON（版本、两个伴侣的连接状态；不含任何密钥 / 个人数据） |

结果码统一：`code` 1 成功 / 2 参数不对 / 3 找不到 / 4 不认识；另有 `ok`、`error`。
计划的所有写入都经 `Store`（同一套校验、变更广播、提醒重排）；时钟的四个闹钟函数和旧广播共用 `AlarmApiReceiver.handle`（同一把锁、落盘、撤贪睡、重排）。调试闹钟（id ≥ 900）经连接同样碰不到。

## 2. 发现（`CompanionLink`）

1. `PackageManager` 查包装没装；
2. `checkSignatures(助手, 对端) == SIGNATURE_MATCH`，并且 `resolveContentProvider(authority)` 的 `packageName` 就是对端包（防同名 authority 冒充）；
3. `link.hello` → `CompanionHandshake.evaluate`：

| 状态 | 条件 | 路由 |
|---|---|---|
| `ABSENT` | 没装（或总开关关） | 原回落不变 |
| `UNTRUSTED` | 签名不同 | 当第三方 App，原回落不变 |
| `NO_LINK` | 同证书，但没有 `link.hello`（旧版）/ 计划 App 开关关着 | 交给已有规则层；**不进 AgentLoop** |
| `VERSION_MISMATCH` | 对端 `version < 1` 或 `min_client > 助手版本` | 同上 |
| `MISSING_CAPS` | 缺必需能力 | 同上 |
| `READY` | 全对 | **只走连接** |

结果缓存：READY 60 s，其余 30 s。总开关：prefs `bixby_companion / enabled`（默认开）+ kill-file `companion.off`。
「在场」= 同证书（`NO_LINK` / `VERSION_MISMATCH` / `MISSING_CAPS` / `READY`）。

## 3. 路由优先级（`CompanionRouter`，纯 Java）

```
句子属于伴侣领域？（含「待办」→ 计划；「闹钟 / 叫醒我」→ 时钟；「日程」→ 计划）
  点名了别家 App（「在滴答清单里…」）或串了别的动作（「发给 / 分享 / 然后…」） → 不管（原流程）
  伴侣没装 / 签名不同                → NONE   原回落（含 AgentLoop）
  在场但连接没通                     → CLAIM  已有规则层处理，永不进 AgentLoop
  READY：
    删 / 改待办                      → REPLY  经连接 listTasks + deleteTask / updateTask，本地回话
    加 / 列 / 勾掉待办               → CLAIM  S3 规则层（连着计划 App 时 S3 的存储就是计划 App 的 provider）
                                        S3 待办开关关着 → 经连接 createTask / listTasks / completeTask
    清空待办                         → CLAIM  S3 出确认卡（伴侣不直接清空）
    日程 / 其他待办句                → CLAIM
    闹钟                             → CLAIM  FastIntents → AlarmBridge；AlarmBridge 连接通时走 createAlarm 等
```

删 / 改时的目标匹配：只在**未完成**待办里找；有且只有一条标题完全相同 → 用它；否则按 `S3Parser.similarity` 取最高分（≥ 0.5），
多条同分 → 反问「要删哪一条？」，找不到 → 「计划里没找到「X」这条待办。」；连接出错 → 如实说没改成。三种情况都不交给 AgentLoop。

接线（都是几行 `[companion]` 调用）：

| 位置 | 作用 |
|---|---|
| `UnifiedRouter.shouldUseAgentLoop` 最前面（快捷指令之后、API key / 诊断插件 / 分诊之前） | `CompanionHooks.claims` 为真 → 不进 AgentLoop |
| `PlannerPlugin.route`（TAKEOVER 285，S3 300 之前） | `CompanionHooks.route` 先判，REPLY 直接回；其余交回原规则层 |
| `PlannerPlugin.claims` | 同上的 claims |
| `AgentLoop` 工具过闸（`ContactMatcher.agentPrecheck` 之后） | `CompanionHooks.agentPrecheck`：伴侣在场时 `probe.* / app.* / ui.* / shell* / a11y.* / phone.* / intent.* / deeplink*` 碰到待办 / 日程 / 闹钟目标一律不执行（自家包除外） |
| `AndroidAlarmLink.callClock` | 时钟 READY → 走连接函数；否则旧有序广播 |
| `BriefStateReceiver` | 先经时钟连接 `getBriefStatus` 取 state.json 拼成原 `VERIFY_CMD` 输出交给原 `verifySource`；不通再试 root |

不新增内核插件（内核插件表、接管顺序不变）：伴侣挂在已有的计划插件槽位上，再加 `shouldUseAgentLoop` / `AgentLoop` 两处直调，计划 App 开关关着也照样挡住 AgentLoop。

## 4. 权限命名（debug / release 永远对得上）

助手 debug 包由 `apps/bixbyui/build_linux.sh` 的 sed 把 `com.onesay.assistant.permission.CONTROL` 改名为 `com.onesay.assistant.debug.permission.CONTROL`（原有机制，`BuildVariantTest` 钉住）。
计划、时钟清单里引用助手的地方改用**构建占位符** `${assistantAppId}`：

```xml
<uses-permission android:name="${assistantAppId}.permission.CONTROL" />
<meta-data android:name="com.onesay.link.ASSISTANT" android:value="${assistantAppId}" />
```

| 构建 | 默认 `ASSISTANT_APP_ID` |
|---|---|
| 计划 `BUILD_TYPE=debug bash build_linux.sh` | `com.onesay.assistant.debug` |
| 计划 `BUILD_TYPE=release`（默认） | `com.onesay.assistant` |
| 时钟 `build_linux.sh`（debug 包） | `com.onesay.assistant.debug` |
| 时钟 `build_release.sh` | `com.onesay.assistant` |

都可用环境变量 `ASSISTANT_APP_ID=…` 覆盖。脚本生成 `$OUT/AndroidManifest.xml` 交给 aapt2，有没填的 `${` 就拒绝构建。
运行时时钟 / 计划读 meta-data 得到配对的助手包名（再核对装了且同证书；没有 meta-data 时在 release / debug 两个候选里找）——
`BRIEF_STATE` 广播和反查都发给它。`CompanionLinkConsistencyTest.permissionNamesMatchInBothVariants` 在两个变体下逐条核对：
计划 / 时钟引用的每个助手权限都正好是助手清单（debug 按 build_linux.sh 的 sed 实际改名）里声明的那个。

助手清单不放 provider（debug / release 两包同装时 authority 会撞；`BuildVariantTest` 也禁止），所以助手一侧的反查走有序广播。

## 5. 能力清单：OACP 兼容、AppFunctions 风格命名

`link.hello` 的 `descriptor` 按 [OACP](https://github.com/OpenAppCapabilityProtocol/oacp)（Apache-2.0）0.3 的 `oacp.json` 形状：

```json
{"oacpVersion":"0.3","appId":"com.onesay.plan","displayName":"OneSay Plan",
 "capabilities":[{"id":"deleteTask","description":"删除一条待办",
   "parameters":[{"name":"id","type":"integer","required":true}],
   "completionMode":"sync_result",
   "invoke":{"android":{"type":"x-provider-call","authority":"com.onesay.plan.provider","method":"deleteTask"}}}],
 "x-onesayLink":{"iface":"onesay.companion","version":1,"min_client":1}}
```

- 函数名用 Android AppFunctions 风格的动词 + 名词（`createTask`、`deleteTask`、`createAlarm`…），以后迁到 AppFunctions 基本是一一映射。
- OACP 0.3 的调用方式只有 `activity` / `broadcast`；我们用的是 signature 权限保护的 provider `call()`，记为扩展类型 `x-provider-call`（助手一侧是 `x-ordered-broadcast`）。
- **没有**做 OACP 的发现 provider（`content://<pkg>.oacp/manifest`）和 `OACP.md`；以后要被别的 OACP 助手发现，只需把同一份清单经那个 provider 暴露，并另开一个**不带 signature 保护**的公开子集——这超出本轮范围，也与「只给自家同证书 App 用」的安全模型不同，需要另外决定。

## 6. 双向数据

- 助手 → 计划 / 时钟：上面的函数。
- 计划 / 时钟 → 助手：`AssistantLink.query(ctx, "getAssistantStatus")`（两个 App 打开时各在后台反查一次，只记日志 `[LINK]`）。
- 时钟 → 助手：`BRIEF_STATE` 广播（推）+ 助手经 `getBriefStatus` 拉取对账。

## 7. 测试

| 工程 | 测试 |
|---|---|
| 助手 | `CompanionRouterTest`（删待办在伴侣在场时只经连接、绝不进 AgentLoop / 探针；各种说法；在场但没连上；没装 / 签名不同保持原回落；改 / 找不到 / 多条同分 / 连接出错；清空留给 S3；加列勾按 S3 开关；闹钟 / 日程；点名别家 App / 多步不抢）、`CompanionHandshakeTest`（版本不对、缺能力、旧版没有 hello、接口名不对、权限名、OACP 形状）、`CompanionLinkConsistencyTest`（三边契约、两个变体的权限名、助手无 provider、接线顺序） |
| 计划 | `LinkContractTest`（契约、hello、OACP 形状、清单占位符与构建脚本、provider 门卫在前、删待办连带派生提醒） |
| 时钟 | `LinkTest`（契约、函数 ↔ 旧广播动作、调试闹钟碰不到、hello、OACP 形状、清单 provider / 占位符 / 构建脚本、StateBus 发给配对助手） |

## 8. 日志口径

助手 `OneSayCompanion` `[COMPANION]`（发现状态变化、路由结果、`link plan.deleteTask code=1`、拦下 AgentLoop 工具）；
计划 `BixbyPlan` `[LINK] deleteTask code=1 by=…`；时钟 `BixbyClock` `LINK hello` / `LINK getBriefStatus` / `LINK ALARM_QUERY rc=1`。
