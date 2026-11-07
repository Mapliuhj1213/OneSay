# 快捷指令（v1）· 计划 App 侧

快捷指令 = 一串存好的工具调用。**计划 App 存数据、给界面**（列表 / 运行 / 详情 / 撤销 / 编辑、桌面快捷方式、定时触发），**OneSay 负责执行**（逐步过门禁和确认卡、失败转正常模式、写执行记录）。
OneSay 侧的说明见 OneSay 仓库 `docs/SHORTCUTS.md`。

代码：`core/Shortcuts.java`（纯逻辑，JVM 全测）、`core/Contract.java` / `core/Schema.java` / `core/Validator.java`（表结构与校验）、`data/Store.java`（重名拒绝、删除连带、记录裁剪）、`data/Settings.java`（动作清单文件）、`ui/ShortcutUi.java`（自动任务页「快捷指令」段）、`ui/ShortcutRunActivity.java`（桌面快捷方式落点）。测试：`test/src/com/onesay/plan/core/ShortcutsTest.java`。

## 1. 概览

- 结构版本 **3**（`ping` 返回 `schema`）：新增 `shortcuts`、`shortcut_runs` 两张表（字段见 `docs/provider-api.md` §3）。OneSay 看到 `schema` < 3 就不启用快捷指令。
- 名字唯一（按 `Shortcuts.normName` 比，去空白标点、小写）：名字也是语音触发词，重名会分不清，插入 / 改名时拒绝。名字 ≤ 40 字。
- 删一条快捷指令 → 连带删它的执行记录和它的定时触发（`src_key = shortcut:time:<id>` 的自动任务及其运行记录）。
- 每条快捷指令最多留 30 条执行记录（`Schema.SHORTCUT_RUNS_KEEP`）。

## 2. 数据格式

与 OneSay `shortcut/ShortcutModel.java` 逐项一致（OneSay 的 `ShortcutFormatTest` 在两个仓库都在时比对常量）。格式版本 `Contract.SHORTCUT_FORMAT = 1`。

```
steps_json    [{"tool":"clock.alarm.set","args":{"hour":7,"minute":null},"ask":["minute"],"title":"定闹钟"}, …]   1–20 步
              tool：OneSay 工具注册表签名或快捷指令内部动作（llm.ask / plan.event.add / brief.play），只许 [a-z0-9_] 和点
              args：参数；值为 JSON null，或名字列在 ask 里 → 每次运行时问
              字符串参数里的 {{prev}} → 上一步的输出
triggers_json {"voice":1,"tap":1,"launcher":0,"time":0}      语音 / 点击 / 桌面 / 定时
history_json  [{"v":1,"steps":[…],"at":ms,"why":"…"}, …]    旧版本，最新的在最后，最多 5 个；撤销 = 弹出最近一个
aliases_json  ["帮我定个七点的闹钟然后开勿扰", …]            对话里存的：原话也是触发词
origin        ui（计划 App 里建）/ chat（OneSay 对话里存）
```

- **版本**：步骤被替换（手动修改，或 OneSay 失败后按正常模式更新）→ 旧步骤进 `history_json`，`version + 1`，清掉标红。
- **「不对」**：每次运行 `run_seq + 1`；点「不对」记下当时的 `run_seq`，和上一次「不对」相邻 → `wrong_streak + 1`，否则从 1 数；连续 2 次 → `flagged=1` + `flag_reason`，列表里标红。
- **执行记录**（`shortcut_runs`）只存工具名和每步 ✓/✗（`steps_summary`）、脱敏后的一句结果，**不存参数**（可能有号码、正文、密钥）。
- `status`：`requested`（计划 App 发起）→ `queued`（OneSay 已认领）→ `running` → `ok` / `fail` / `fallback_ok` / `fallback_fail` / `cancelled` / `denied`；`stale` = 发起 2 分钟没被认领（界面显示「OneSay 没在运行」）。`mode`：`shortcut` / `normal`（转了正常模式）。

## 3. 跨进程接口

全部走现有 Provider 接口（`content://com.onesay.plan.provider`，signature 权限 / 同签名校验，见 `docs/provider-api.md` §2）。

| 方向 | 接口 | 用途 |
|---|---|---|
| 计划 App → OneSay | 插 `shortcut_runs`：`shortcut_id`、`started_at`、`status=requested`、`trigger_info=tap/launcher` | 点「运行」或点桌面图标。写入后照常 `notifyChange` + `DATA_CHANGED` 广播；OneSay 的变更监听认领（改 `queued`）、排进它的任务队列，执行时沿用这一行写结果 |
| 计划 App → OneSay | 插 / 改 `automations`：`mode=time`、`trigger_type=time`、`action_type=agent`、`action=执行快捷指令#<id>（名字）`、`src_key=shortcut:time:<id>`、`owner=bixbyui` | 定时触发：复用自动任务，OneSay 照常排程、到点执行；关掉定时 = 删这行 |
| OneSay → 计划 App | 查 / 改 `shortcuts`，插 / 改 `shortcut_runs` | 命中、执行记录、更新步骤、「不对」、撤销、对话里「存为快捷指令」（`origin=chat`） |
| OneSay → 计划 App | `call shortcut_actions`，arg = 动作清单 JSON | 每次 OneSay 服务启动推送一次；计划 App 校验后原子写到 `files/shortcut_actions.json`，编辑页以它为准（没推送过用 `Shortcuts.Actions.defaults()`，上机版专属项置灰） |

动作清单 JSON：`{"format":1,"actions":[{"sign":"dnd.on","label":"打开勿扰","group":"系统开关","privileged":true,"available":false,"reason":"上机版专属（需要 root），开源构建已关闭","params":[{"name":"level","label":"…","type":"int|string"}]}, …]}`。

自动任务页里修改快捷指令不会写本地提醒（`shortcuts` / `shortcut_runs` 的写入不触发 `Remind.schedule`）。

## 4. 上机版专属动作与开关

- 由 OneSay 的构建开关 `ShortcutBuild.PRIVILEGED_ACTIONS` 决定（开源构建 false，自用上机版在 OneSay 的 `local-config` 打开）。计划 App 自己没有这个开关，只展示 OneSay 推来的可用性。
- 上机版专属动作：勿扰开 / 关、媒体音量、铃声音量、屏幕亮度、播报晨间简报。开源构建一律置灰并写原因；上机版再按 OneSay 的 root / 执行守护探测裁决。
- v1 永远置灰：播报天气、保存剪贴板里的视频 / 图片。
- 「加到桌面」：需要 Android 8.0+ 且桌面支持固定快捷方式（`Capabilities.PIN_SHORTCUT`，`ShortcutManager.isRequestPinShortcutSupported()`），否则开关置灰写原因。

## 5. 未接线 / 未验证

- **全部未上机验证**（本轮只跑 JVM）：自动任务页「快捷指令」段的界面、点击运行被 OneSay 认领、`stale` 显示、定时触发、动作清单推送后置灰是否刷新。
- `ShortcutRunActivity` 不导出：固定快捷方式由桌面以本 App 身份启动，理论上能进；**未在真机桌面（含 One UI）上验证**。
- 定时快捷指令：计划 App 里建的定时触发没有经过 OneSay 的权限卡，第一次到点 OneSay 会停在「需要重新授权」，授权一次后按快捷指令的步骤覆盖；改步骤或改名字后再授权一次（OneSay `docs/SHORTCUTS.md` §4）。
- 播报天气、保存剪贴板媒体 v1 未接线。
