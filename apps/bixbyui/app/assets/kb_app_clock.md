---
name: clock
display: 时钟
kind: app
packages: ["com.sec.android.app.clockpackage"]
aliases: ["时钟", "闹钟", "计时器", "秒表", "世界时钟", "Clock", "Alarm"]
sites: []
deeplinks: ["android.intent.action.SET_ALARM", "android.intent.action.SET_TIMER", "android.intent.action.SHOW_ALARMS"]
open: 时钟
description: 三星时钟：闹钟、世界时钟、秒表、计时器
source: "OneSay 自写（格式照 Mika APP.md）；深链 / 界面描述未在测试机 上逐条实测的已注明推断"
---
## 是什么
时钟，包名 `com.sec.android.app.clockpackage`。

## 界面
顶部标签：闹钟、世界时钟、秒表、计时器。闹钟列表每行一个开关。

## 功能
- **定闹钟 / 计时器**：优先用闹钟接口（clock.alarm.set 等，`SET_ALARM` / `SET_TIMER` action），不点界面。
- **看闹钟**：`SHOW_ALARMS` 打开闹钟页。

## 已知现象
- 闹钟开关只靠开关状态表示开没开，读 checked 状态，不要靠点开关来判断（点了就切换了）。
