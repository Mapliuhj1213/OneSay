---
name: calendar
display: 日历
kind: app
packages: ["com.samsung.android.calendar"]
aliases: ["日历", "三星日历", "日程", "Calendar"]
sites: []
deeplinks: ["android.intent.action.INSERT"]
open: 日历
description: 三星日历：看日程、新建事件、提醒
source: "OneSay 自写（格式照 Mika APP.md）；深链 / 界面描述未在测试机 上逐条实测的已注明推断"
---
## 是什么
日历，包名 `com.samsung.android.calendar`。

## 界面
月 / 周 / 日视图切换在左上菜单；右下「+」新建事件，编辑页有标题、时间、提醒、重复、地点。

## 功能
- **新建日程**：优先用日程接口（本地日程 / 日历工具）；界面方式用 `android.intent.action.INSERT`（data = content://com.android.calendar/events）打开新建页再填。
- **查日程**：优先读日历接口，不点界面。

## 已知现象
- 农历、节假日显示在日期格子里，会混进控件树文字。
