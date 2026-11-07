# 快捷指令（v1）· OneSay 侧

快捷指令 = 一串存好的工具调用，说名字（或存下时的原话）、在计划 App 里点、点桌面图标、或到点就逐步执行。
**数据在计划 App**（`shortcuts` / `shortcut_runs` 两张表，结构版本 3），**执行在 OneSay**。OneSay 自己不另存一份。
计划 App 那边的说明见 bixby-plan 仓库 `docs/SHORTCUTS.md` 和 `docs/provider-api.md`。

代码：`app/src/com/onesay/assistant/v2/shortcut/`（纯 Java：`ShortcutModel` `ShortcutActions` `ShortcutRunner` `ShortcutFlow` `ShortcutSafety` `ShortcutMatcher` `ShortcutStore`；Android 胶水：`ShortcutHooks`），构建开关 `app/config/com/onesay/assistant/v2/shortcut/ShortcutBuild.java`。
测试：`test-jvm/src/com/onesay/assistant/v2/shortcut/{ShortcutTest,ShortcutHooksTest,ShortcutFormatTest}.java`。

## 1. 行为（已定）

| 情况 | 结果 |
|---|---|
| 全部步骤成功 | 只报「已执行」（不判对不对；用户可点「不对」） |
| 用户在确认卡上没允许 / 安全策略拒绝（门禁 DENY、定时任务没授权、岛上没允许） | **如实停下**，状态 `denied`，**不转正常模式**、不改快捷指令 |
| 留空参数没回答 / 停止键 | 停下，状态 `cancelled`，不转正常模式 |
| 某步失败（含这台设备不能用的动作） | 自动转正常模式（普通 AgentLoop，同一个宿主回调）把剩下的做完 |
| 正常模式做完 | 用「已做成的步骤 + 正常模式实际调用的步骤」更新快捷指令，旧版本进 `history_json`，结果卡出「撤销更新」；用到点界面 / 查网页 / 读屏幕这类 v1 不能回放的工具 → 不更新，如实说明 |
| 正常模式也失败 | 状态 `fallback_fail`，不更新 |
| 「不对」连续两次（相邻两次运行） | `flagged=1`，计划 App 列表里标红 |

- 每一步都过 `ExecutionGate.evaluate`（和 AgentLoop 同一把尺）；需要确认就弹卡，允许后才 `approve` 并凭令牌执行（`ShortcutRunner` 在 `ExecutionGate.APPROVE_CALLERS` 白名单里）。
- **发消息 / 花钱 / 删除类每次都弹卡**（`ShortcutSafety.alwaysConfirm`，判据 = S3 红卡类 + 删除类接口），即使用户以前点过「始终允许」。
- `{{prev}}`：字符串参数里换成上一步输出；上一步是「问模型」或读隐私数据时，下一步按 tainted 过闸。
- **从对话保存**：普通任务做成后结果卡出「存为快捷指令」，点了直接存，**不弹窗、不追问**；名字 = 这句话截到 40 个字（与计划 App 一样按 UTF-16 长度算；截在表情中间就少截一个），整句原话同时作触发词；只存成功的步骤；参数名像口令 / 令牌、或值像密钥（`Redact` 认得出）→ 改成每次运行时问，不写进计划 App；用到点界面 / 查网页类工具 → 不存并说明原因。
- **定时快捷指令**：到点要重新授权一次（见 §4），之后照常跑；发消息 / 花钱 / 删除类仍每次在岛上确认，岛不可用时停在「等你确认」，绝不自动批准。
- 天气播报（`weather.say`）、保存剪贴板里的视频 / 图片（`media.save_clip`）**v1 永远置灰**（没有对应工具 / feat/media-save 未合入）。

## 2. 数据格式

和计划 App `core/Shortcuts.java` 逐项一致（`ShortcutFormatTest` 在两个仓库都在时比对常量）。格式版本 `FORMAT = 1`。

```
steps_json    [{"tool":"clock.alarm.set","args":{"hour":7,"minute":null},"ask":["minute"],"title":"定闹钟"}, …]   最多 20 步
              tool = 工具注册表签名或内部动作；args 值为 null 或名字在 ask 里 → 每次运行时问；字符串里的 {{prev}} → 上一步输出
triggers_json {"voice":1,"tap":1,"launcher":0,"time":0}
history_json  [{"v":1,"steps":[…],"at":ms,"why":"…"}, …]   旧版本，最新的在最后，最多 5 个
aliases_json  ["帮我定个七点的闹钟然后开勿扰", …]           对话里存的：原话也是触发词
```

`shortcut_runs.status`：`requested` → `queued` → `running` → `ok` / `fail` / `fallback_ok` / `fallback_fail` / `cancelled` / `denied`；超过 2 分钟没认领的 `requested` 标 `stale`。
`mode`：`shortcut` / `normal`（转了正常模式）。`steps_summary` 只有工具名和每步 ✓/✗，**不存参数**；`result` 经 `Redact` 脱敏、截到 300 字。

## 3. 跨进程接口与接线

全部经现有计划 App 后门（`PlannerBackend` / `AndroidPlannerBackend`，signature 权限 + 证书白名单）。计划 App 没装、`ping` 的 `schema` < 3、或关了适配层 → `PlannerLink.shortcutsOn()` 为假，快捷指令整体不生效。

| 方向 | 接口 | 用途 |
|---|---|---|
| OneSay → 计划 App | 查 / 改 `shortcuts`、插 / 改 `shortcut_runs` | 命中（缓存 30 s）、执行记录、更新步骤、「不对」、撤销 |
| OneSay → 计划 App | `call shortcut_actions`（arg = 动作清单 JSON） | 启动时推送带「这台设备能不能用 / 原因」的动作清单，计划 App 编辑页据此置灰 |
| 计划 App → OneSay | `shortcut_runs` 插 `status=requested`（点击 `tap` / 桌面 `launcher`） | `PlannerHooks` 的变更监听 → `ShortcutHooks.onPlannerChanged` 认领（改 `queued`）→ 排进任务队列（`q:`，用户当面发起，照常门禁 + 确认卡在岛上）；执行时沿用那一行 |
| 计划 App → OneSay | `automations` 一行（`mode=time`、`action_type=agent`、`action=执行快捷指令#<id>（名字）`、`src_key=shortcut:time:<id>`） | 定时触发：OneSay 的 S3 定时任务照常排程，到点以无人值守回调执行 |

| 接点 | 做什么 |
|---|---|
| `UnifiedRouter.shouldUseAgentLoop` 开头 | `ShortcutHooks.matches`：命中已存快捷指令（名字 / 原话 / 「执行快捷指令#id」）→ 走 AgentLoop 入口（在密钥检查之前：快捷指令本身不需要模型） |
| `UnifiedRouter.runAgentLoop` 开头 | `ShortcutHooks.tryRun`：命中就逐步执行，失败转正常模式也用同一个回调；「执行快捷指令#id」找不到 → 如实失败，不交给模型猜 |
| `UnifiedRouter.runAgentLoop` 结尾 | `rememberAgentRun`：记下普通任务实际调用的工具（「存为快捷指令」用） |
| `FloatingSheetService` 结果上屏后 | `onAgentResultShown`：结果卡芯片「存为快捷指令」/「不对」/「撤销更新」 |
| `PlannerHooks.onServiceCreate` | `installJobPerms`（同步，任何定时任务跑之前）；planner-init 线程里 `pushCatalog` + `onPlannerChanged` |

快捷指令入口在 AgentLoop 这一支（`shouldUseAgentLoop` 为真就不走 L2 编译）；`FloatingSheetService.dispatch` 里 L2 结果的提示词回吐判定（`"L2.final"`）仍在插件派发 / 计划执行之前，快捷指令没有在它前面加任何入口（`PromptLeakGuardTest.wiring` 守住）。AgentLoop 最终回答照常过 `agentloop.final` 判定。

## 4. 定时（无人值守）的安全行为

- 计划 App 里建的定时快捷指令没有 OneSay 的权限卡授权 → 第一次到点停在「需要重新授权」（`JobsEngine.WAIT_REAUTH`），岛上 / 控制台提示。
- 权限卡上的清单：`JobsEngine.analyze` 除了看指令文字，还经 `JobsEngine.CommandPerms`（`ShortcutHooks.installJobPerms` 注入）按这条快捷指令的**步骤**算（`ShortcutSafety.perms`：签名 + 副作用等级，与运行时 `PermissionGuard.check` 同一判据）。授权一次就覆盖全部步骤。
- 清单参与授权指纹：快捷指令的步骤改了（清单变）或改了名字（指令文字变）→ 下次到点再授权一次。计划 App 一时连不上时沿用上次算出的清单，避免指纹抖动。
- 内部动作（加日程 / 播报简报 / 问模型）不经 UnifiedExecutor，执行前先过 S3Runner 的同一道闸（`PermissionGuard.Preflight`，S3Runner 只是把原来 `execute` 里的闸口抽成 `preflight`，行为不变）。
- S3Runner 把「没授权」「岛上没允许」报成 `EXEC_FAILED`；`ShortcutSafety.unattendedVerdict` 按 detail 换成 `GATE_DENIED` / `USER_REJECTED`，快捷指令据此停下（`denied`），不转正常模式。
- 无人值守没有 ask_user：留空参数 → 取消。

## 5. 上机版专属动作与开关

- 构建开关 `ShortcutBuild.PRIVILEGED_ACTIONS`：随仓库的 `app/config/…/ShortcutBuild.java` = **false**（开源构建）；自用上机版在 `local-config/com/onesay/assistant/v2/shortcut/ShortcutBuild.java` 里设 true（模板在 `local-config.example/`）。`build_linux.sh` 查找顺序：`$LOCAL_CONFIG` → `local-config/` → `app/config/`，缺私有文件不报错。JVM 单测固定用 `app/config`。
- 上机版专属 = 动作清单里标了 privileged，或能力执行器是 root shell（`shell` / `custom`）：勿扰开 / 关、媒体音量、铃声音量、屏幕亮度、播报晨间简报。
- 开源构建：一律置灰「上机版专属（需要 root），开源构建已关闭」。上机版：再按能力探测（ROOT + EXEC_DAEMON，`FeatureAvailability`）裁决，探不到（未确认）不置灰。
- v1 动作清单（`ShortcutActions.ALL`）：定闹钟、倒计时、加待办、加日程（内部，写计划 App `events`）、打开 App、打开网页 / 深链、打开 Wi‑Fi 设置页、上面的上机版动作、问一句模型（内部，结果传给下一步）；天气播报、保存剪贴板媒体置灰。

## 6. 未接线 / 未验证

- **全部未上机验证**（本轮只跑 JVM）：语音命中、面板确认卡与 ask_user、结果卡芯片、计划 App 点击 / 桌面快捷方式认领、定时到点重新授权、动作清单推送、root 动作。
- 天气播报、保存剪贴板媒体：v1 未接线（置灰）。
- 定时运行里，「始终允许」过的发消息 / 花钱 / 删除类步骤可能连弹两次确认（快捷指令的强制确认一次 + S3 无人值守红卡确认一次），未改，待上机看体验。
- `shortcut_runs.trigger_info`：点击 / 桌面沿用计划 App 写的那一行（`tap` / `launcher`）；说名字 / 原话记 `voice`；按「执行快捷指令#id」命中且没有排队行的（定时）记 `time`。对话里命中也记 `voice`（`chat` 这个值 v1 没用到）。
- 计划 App 编辑后的快捷指令名字改了 → 定时自动任务的指令文字跟着变 → 授权指纹变，要再授权一次（偏保守，属预期）。
- 正常模式更新步骤只收能回放的工具；点界面 / 查网页类出现即不更新。
