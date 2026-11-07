---
id: reminder_schedule
name: 提醒、日程与待办
description: 某个时间提醒我做事、加日程、记待办、记一下
priority: 55
budget: 600
paths: [agent]
triggers:
  keywords: [提醒我, 日程, 日历, 加到日历, 待办, 备忘, 记一下, 别忘了, 开会]
  regex: ["(明天|后天|下周|周[一二三四五六日天]|\d+号).{0,10}(提醒|开会|会议|约)", "(加|放|记)(到|进)?(日历|日程|备忘)"]
  intents: [schedule, calendar, todo]
  packages: [com.samsung.android.calendar]
  examples: [明天下午三点提醒我交报告, 把周五的会加到日历, 记一下要买牛奶]
requires:
  tools: [clock.alarm.set, screen.calendar.add, notes.new.text, phone.operate, gui.unfold]
---
1. 「X 点提醒我…」「记个待办」通常前门直接接走；到你这里多半是复合句，先 `task.todo` 拆开再逐项做。
2. 只要到点响一下：当天 / 明天的具体时刻用 `clock.alarm.set`，答复里说明是用闹钟提醒的。
3. 屏幕上有时间（「把这个加到日历」）：`screen.calendar.add`，由用户在日历页确认保存。
4. 其他日程：打开日历新建页填好标题和时间，保存前停下让用户确认；不要替用户邀请别人。
5. 纯记录类（「记一下 X」）：`notes.new.text` 写进三星笔记，内容照用户原话。
6. 没给时间又必须有时间时 `ask_user` 问，不要默认「明天 9 点」。
