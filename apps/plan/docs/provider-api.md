# OneSay Plan · Provider 接口

计划 App（包名 `com.onesay.plan`）把日程、待办、票、取件码、自动任务、运行记录、快捷指令放在自己的库里，OneSay 通过这里写的接口读写。结构版本 **3**（`ping` 返回的 `schema`；3 = 加了快捷指令两张表，OneSay 看到 < 3 就不启用快捷指令）。

## 1. 地址

| 用途 | URI |
|---|---|
| 表（增删改查） | `content://com.onesay.plan.provider/<表>`，表名：`events` `todos` `tickets` `pickups` `automations` `runs` `shortcuts` `shortcut_runs` |
| 单行 | `content://com.onesay.plan.provider/<表>/<_id>` |
| 今天（只读聚合） | `content://com.onesay.plan.provider/today` |
| 票的二维码图 | `content://com.onesay.plan.provider/tickets/<_id>/qr`（`openFile` 用 `r` 读、`w` 写 PNG/JPG） |
| 打开界面 | `Intent("com.onesay.plan.action.OPEN")`，extra `tab` = `today/events/todos/tickets/pickups/automations`；不带 `tab` 进「今天」，不认识的值保持当前页（`core/TabNav`） |

## 2. 权限和门卫

- 自定义权限 `com.onesay.plan.permission.ACCESS`，级别 **signature**：只有和 Plan 用同一张证书签名的应用会被系统授予。仓库不内置任何证书指纹。
- 每次读写，Provider 按下面的顺序判断（`core/AccessPolicy.java`）：
  1. 自己（同 uid）→ 放行
  2. root（uid 0）、adb shell（uid 2000）→ **拒绝**（开源版没有 root / 调试通道）
  3. 持有 ACCESS 权限 → 放行
  4. 调用方与 Plan 签名一致（`PackageManager.checkSignatures` = `SIGNATURE_MATCH`）→ 放行。兜底「先装调用方、后装 Plan」时系统不补授 signature 权限的情况
  5. 其他一律拒绝，返回 `SecurityException`
- 门卫结果（SELF / PERMISSION / SAME_SIGNER）决定 `source` 的默认值，出问题时能看出是谁写的。

## 3. 字段

每张表都有：`_id`、`note`（≤2000）、`source`（≤32，默认 `ui`）、`src_key`（≤200，**唯一**，可为空）、`created_at`、`updated_at`。时间都用毫秒时间戳。

- `src_key` 是去重键：同一个 `src_key` 再插一次，返回已有那一行的 id，不会新建。约定前缀：`bixbyui:sched:<id>`、`bixbyui:todo:<id>`、`bixbyui:qn:<id>`（记一记）、`bixbyui:card:<id>`（灵动岛浮卡）、`bixbyui:ticket:…`；测试数据用 `test:demo:` 和 `test:ticket:`，方便一条命令删干净。
- 校验在 `core/Validator.java`：缺必填项、未知列、超长、取值越界都会抛 `IllegalArgumentException`，并带中文原因。

### events

| 列 | 类型 | 必填 | 默认 | 长度上限 |
|---|---|---|---|---|
| title | TEXT | 是 |  | 200 |
| start_at | INT | 是 |  |  |
| end_at | INT |  | 0 |  |
| all_day | INT |  | 0 |  |
| location | TEXT |  | '' | 200 |
| repeat | INT |  | 0 |  |
| week_mask | INT |  | 0 |  |
| month_day | INT |  | 0 |  |
| lunar | INT |  | 0 |  |
| lunar_month | INT |  | 0 |  |
| lunar_day | INT |  | 0 |  |
| lead_min | INT |  | -1 |  |
| ext_id | TEXT |  | '' | 200 |
| deleted_at | INT |  | 0 |  |

### todos

| 列 | 类型 | 必填 | 默认 | 长度上限 |
|---|---|---|---|---|
| title | TEXT | 是 |  | 200 |
| due_at | INT |  | 0 |  |
| done_at | INT |  | 0 |  |
| remind | INT |  | 1 |  |
| priority | INT |  | 0 |  |
| list | TEXT |  | '' | 40 |

### tickets

| 列 | 类型 | 必填 | 默认 | 长度上限 |
|---|---|---|---|---|
| kind | TEXT | 是 |  | 16 |
| title | TEXT | 是 |  | 200 |
| number | TEXT |  | '' | 40 |
| from_place | TEXT |  | '' | 80 |
| to_place | TEXT |  | '' | 80 |
| depart_at | INT | 是 |  |  |
| arrive_at | INT |  | 0 |  |
| venue | TEXT |  | '' | 120 |
| seat | TEXT |  | '' | 80 |
| gate | TEXT |  | '' | 40 |
| passenger | TEXT |  | '' | 60 |
| order_no | TEXT |  | '' | 60 |
| qr_text | TEXT |  | '' | 2000 |
| qr_path | TEXT | 只读 | '' |  |
| status | INT |  | 0 |  |
| remind_lead_min | INT |  | 120 |  |

### pickups

| 列 | 类型 | 必填 | 默认 | 长度上限 |
|---|---|---|---|---|
| code | TEXT | 是 |  | 40 |
| kind | TEXT |  | '取件码' | 16 |
| station | TEXT |  | '' | 120 |
| carrier | TEXT |  | '' | 40 |
| app_pkg | TEXT |  | '' | 120 |
| deadline_at | INT |  | 0 |  |
| taken_at | INT |  | 0 |  |
| remind | INT |  | 1 |  |

### automations

| 列 | 类型 | 必填 | 默认 | 长度上限 |
|---|---|---|---|---|
| title | TEXT | 是 |  | 200 |
| mode | TEXT | 是 |  | 16 |
| trigger_type | TEXT | 是 |  | 32 |
| trigger_json | TEXT |  | '{}' | 4000 |
| action_type | TEXT | 是 |  | 16 |
| action | TEXT |  | '' | 2000 |
| enabled | INT |  | 1 |  |
| repeat | INT |  | 0 |  |
| week_mask | INT |  | 0 |  |
| hour | INT |  | 0 |  |
| minute | INT |  | 0 |  |
| once_at | INT |  | 0 |  |
| next_at | INT |  | -1 |  |
| guard_json | TEXT |  | '{}' | 2000 |
| state_json | TEXT |  | '{}' | 4000 |
| last_run_at | INT |  | 0 |  |
| last_result | TEXT |  | '' | 2000 |
| last_missed_at | INT |  | 0 |  |
| todo_id | INT |  | 0 |  |
| owner | TEXT |  | 'bixbyui' | 40 |

### runs

| 列 | 类型 | 必填 | 默认 | 长度上限 |
|---|---|---|---|---|
| automation_id | INT | 是 |  |  |
| started_at | INT | 是 |  |  |
| ended_at | INT |  | 0 |  |
| status | TEXT | 是 |  | 16 |
| result | TEXT |  | '' | 2000 |
| trigger_info | TEXT |  | '' | 500 |

### shortcuts

快捷指令定义（格式详见 `docs/SHORTCUTS.md` §2）。执行由 OneSay 负责。

| 列 | 类型 | 必填 | 默认 | 长度上限 |
|---|---|---|---|---|
| name | TEXT | 是 |  | 40 |
| aliases_json | TEXT |  | '[]' | 2000 |
| steps_json | TEXT | 是 |  | 8000 |
| triggers_json | TEXT |  | '{"voice":1,"tap":1,"launcher":0,"time":0}' | 500 |
| enabled | INT |  | 1 |  |
| version | INT |  | 1 |  |
| history_json | TEXT |  | '[]' | 16000 |
| run_seq | INT |  | 0 |  |
| wrong_streak | INT |  | 0 |  |
| last_wrong_seq | INT |  | 0 |  |
| flagged | INT |  | 0 |  |
| flag_reason | TEXT |  | '' | 200 |
| last_run_at | INT |  | 0 |  |
| last_status | TEXT |  | '' | 16 |
| last_result | TEXT |  | '' | 500 |
| origin | TEXT |  | 'ui' | 16 |

### shortcut_runs

快捷指令执行记录。**不存参数**：只有工具名和每步 ✓/✗、脱敏后的一句结果。每条快捷指令最多留最近 30 条。

| 列 | 类型 | 必填 | 默认 | 长度上限 |
|---|---|---|---|---|
| shortcut_id | INT | 是 |  |  |
| started_at | INT | 是 |  |  |
| ended_at | INT |  | 0 |  |
| status | TEXT | 是 |  | 16 |
| mode | TEXT |  | 'shortcut' | 16 |
| trigger_info | TEXT |  | '' | 32 |
| steps_summary | TEXT |  | '' | 1000 |
| result | TEXT |  | '' | 500 |
| failed_step | INT |  | -1 |  |
| updated | INT |  | 0 |  |
| run_seq | INT |  | 0 |  |

取值说明：
- `tickets.kind`：`train` `flight` `bus` `show` `movie` `other`；`status`：0 有效 / 1 已用 / 2 取消；`order_no` 存取票码（有取票码时优先）或订单号；`qr_path` 只读，要写二维码图请走 `/tickets/<id>/qr`。
- `tickets.remind_lead_min`：默认 120 表示「按设置里的档位提醒」（默认提前 120 分钟和 30 分钟各一次）；填别的正数会多加一档；填 0 表示这张票不提醒。
- `events.deleted_at`：大于 0 表示软删（OneSay 支持 10 分钟内撤销），7 天后自动清掉。
- `automations.mode`：`time` / `trigger`；`action_type`：`remind` / `agent` / `builtin`；`runs.status`：`ok` `fail` `skip` `missed` `running`。
- `shortcuts.name`：唯一（去空白标点、小写后比较），重名插入 / 改名会被拒；不能换行。`steps_json` 1–20 步，`tool` 只许小写字母、数字、下划线和点；`triggers_json` 必须是 JSON 对象，`aliases_json` / `history_json` 必须是 JSON 数组；`origin`：`ui` / `chat`。删除一条快捷指令会连带删它的执行记录和它的定时触发（`src_key = shortcut:time:<id>` 的自动任务及其运行记录）。
- `shortcut_runs.status`：`requested`（计划 App 点击 / 桌面发起）`queued`（OneSay 已认领）`running` `ok` `fail` `fallback_ok` `fallback_fail` `cancelled` `denied` `stale`（发起 2 分钟没人认领）；`mode`：`shortcut` / `normal`；`trigger_info`：`voice` `tap` `launcher` `time` `chat` 或空；`failed_step` 从 0 数，-1 = 没失败；`updated` = 这次转正常模式后更新了步骤。
- 快捷指令的定时触发就是一条 `automations`：`mode=time`、`action_type=agent`、`action=执行快捷指令#<id>（名字）`、`src_key=shortcut:time:<id>`、`owner=bixbyui`。

## 4. call 方法

`ContentResolver.call(content://com.onesay.plan.provider, method, arg, extras)`

| method | arg / extras | 返回 Bundle |
|---|---|---|
| `ping` | — | `schema`(int) `version` `who` `today`（一句话） 各表 `count_<表>` |
| `import_bundle` | arg = JSON（`format` 必须是 `bixbyui-raw-v1`） | `inserted` `duplicate` `rejected` `counts` `detail` |
| `import_file` | arg = 文件名（默认 `bixbyui.json`，放在计划 App 的 `files/import/`） | 同上；导完把文件改名为 `.done` |
| `log_run` | extras：`automation_id` `started_at` `ended_at` `status` `result` `trigger_info` | `run_id`；每个自动任务最多保留最近的若干条 |
| `shortcut_actions` | arg = 动作清单 JSON（OneSay 推送，带每个动作在这台设备上能不能用和原因；格式见 `docs/SHORTCUTS.md` §3） | `ok`；格式不对抛 `IllegalArgumentException`。存成 `files/shortcut_actions.json`，编辑快捷指令时以它为准 |

## 5. 变更广播

每次写入后：
- `notifyChange` 发给对应表和 `/today`
- 发广播 `com.onesay.plan.action.DATA_CHANGED`，**receiverPermission = ACCESS**（只有 OneSay 收得到），extras：`table` `op`（insert/update/delete/import）`id` `by`（谁写的）
- 重新排计划 App 自己的本地提醒（自动任务、运行记录、快捷指令和快捷指令执行记录除外）

## 6. 提醒由谁负责

- 计划 App 负责**日程、待办、票、取件码**的到点系统通知（一个精确闹钟，到点把这段时间里该发的通知都发出去，再排下一个）。同一条数据一次只发最新的一档。
- 票：默认开场或出发前 **120 分钟和 30 分钟**各提醒一次，可以在设置「票提前几分钟提醒」里改（用逗号分隔，留空就是不提醒）。通知在锁屏上能看到，带开场时间、影厅、座位和取票码；点通知或点「出示票码」会全屏打开票和二维码。
- 过期的票：到达时间（没有就用出发时间）过去 3 小时后自动归档为「已用」。
- OneSay 的灵动岛不重复提醒同一条（计划 App 连上时，日程到点提醒交给计划 App 的系统通知，见 OneSay `planner/PlannerDedupe`）。
- **自动任务**到点执行仍由 OneSay 负责（模板任务、联网监控也留在 OneSay）。计划 App 只负责展示和编辑，结果通过 `log_run` 写回来。

## 7. 示例

外部 App 访问必须与 Plan 同一证书签名，并声明 `<uses-permission android:name="com.onesay.plan.permission.ACCESS"/>`：

```java
Uri u = Uri.parse("content://com.onesay.plan.provider");
Bundle pong = getContentResolver().call(u, "ping", null, null);
Cursor c = getContentResolver().query(Uri.withAppendedPath(u, "todos"),
        new String[]{"_id", "title", "due_at", "src_key"}, null, null, null);
```

adb shell、`su -c content …` 等方式会收到 `SecurityException: 计划 App：调用方没有权限（需要 com.onesay.plan.permission.ACCESS）`，这是开源版的有意设计。
