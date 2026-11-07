---
id: alarm_timer
name: 闹钟与倒计时
description: 定闹钟、几分钟后叫我、倒计时、查晨间播报闹钟
priority: 60
budget: 520
paths: [agent]
triggers:
  keywords: [闹钟, 叫醒, 叫我起床, 倒计时, 计时器, 定时器]
  regex: ["(\d+|[一二三四五六七八九十两半]+)(个)?(分钟|小时)(后|以后)?(叫我|提醒我|响)", "(早上|明早|明天|晚上).{0,6}(点).{0,4}(叫我|闹钟)"]
  intents: [clock]
  packages: [com.sec.android.app.clockpackage]
  examples: [明天早上七点叫我起床, 定个二十分钟的倒计时, 四十分钟后叫醒我]
requires:
  tools: [clock.alarm.set, clock.timer.set]
---
1. 某个时刻响：`clock.alarm.set`（hour 0–23，minute）。相对时间（「40 分钟后」）按提示里的换算结果填绝对时刻，不要说读不到时间。
2. 「X 分钟后 / 倒计时」：要的是计时就用 `clock.timer.set`（seconds）；用户说「叫我」且超过 1 小时更适合闹钟。
3. 没说几点：`ask_user` 问「几点？」，不要自己填。上午 / 下午不明（「8 点」）按最近的将来时刻，并在答复里说明。
4. `alarm.query` 只查晨间播报的那个闹钟，不是所有闹钟。
5. 答复报出具体时刻（「明天 07:00」），并说明是闹钟还是计时器。
