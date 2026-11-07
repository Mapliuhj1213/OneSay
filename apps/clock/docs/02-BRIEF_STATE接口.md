# BRIEF_STATE 状态接口（给 OneSay 灵动岛）

> 格式照搬 v2 `V2State`（a29b5e3），只换包名。**本仓库只提供接口，没有改 OneSay。** OneSay 的 BriefPlugin 目前读的是 v2 路径，要接新 App 需在 OneSay 侧改读本接口（另开线程）。

## 广播
- Action：`com.onesay.clock.BRIEF_STATE`
- 发送方式：`sendBroadcast(intent.setPackage(...))` 发两份：一份给自己（`com.onesay.clock`），一份给 `com.onesay.assistant`（未安装时无副作用）。显式包名，不是全局广播。
- 动态注册即可接收（Android 8 隐式广播限制不影响显式包名广播）。

| extra | 类型 | 说明 |
|---|---|---|
| `state` | String | `IDLE` / `GENERATING` / `READY` / `RINGING` / `PLAYING` / `DONE` / `ERROR` |
| `detail` | String | 补充：GENERATING 时是触发原因（pregen/retry/manual/ctrl）；READY 时是降级标签列表如 `[]`、`[no_weather]`、`[news_skipped_voice_fail]`；RINGING 时是模式 `BRIEF/LIVE/RINGTONE`；DONE 时是 `stop/snooze` |
| `briefPath` | String | `/data/data/com.onesay.clock/files/brief/brief.json`（App 私有，OneSay 需 root 读） |
| `at` | long | 本次状态时间（ms） |
| `title` | String | 有成品时：`{城市} 今日播报` 或 `今日播报` |
| `l1` | String | 天气句，如「今天是多云天气。」 |
| `l2` / `l3` | String | 第 1 / 2 条新闻标题 |
| `generatedAt` / `forAlarmAt` | long | 成品生成时刻 / 对应的闹钟时刻（ms） |
| `source` | String | 新闻来源中文名（腾讯新闻 / 今日头条 / 哔哩哔哩 / 网络），新增字段 |

## 文件
- `files/state.json`：最近一次广播的同一份内容（JSON），原子写。
- `files/brief/brief.json`：成品元数据（`forAlarmAt, generatedAt, day, script, newsSource, weatherLine, wxKey, city, temp, news[], durationMs, degraded[], genMs`）。
- `files/brief/brief.wav`：成品音频（24 kHz 单声道 PCM WAV）。

## 与 v2 的差异
- Action 为 `com.onesay.clock.BRIEF_STATE`；文件路径在 `com.onesay.clock` 下。
- 多了 `at`、`source` 两个字段；其余字段名和含义不变。
