---
name: phone
display: 电话
kind: app
packages: ["com.samsung.android.contacts", "com.samsung.android.dialer", "com.android.dialer"]
aliases: ["电话", "拨号", "打电话", "通话记录", "Phone", "联系人", "通讯录"]
sites: []
deeplinks: ["tel:"]
open: 电话
description: 拨号、通话记录、联系人
source: "OneSay 自写（格式照 Mika APP.md）；深链 / 界面描述未在测试机 上逐条实测的已注明推断"
---
## 是什么
电话，包名 `com.samsung.android.contacts`。

## 界面
三星 Android 8 的电话和联系人是同一个 App（com.samsung.android.contacts）：标签有键盘、最近记录、联系人。

## 功能
- **打电话**：`tel:号码` 只打开拨号盘（不直接拨出）；真正拨出是 phone.call（红卡，一律要用户确认，没有「始终允许」）。
- **通话记录 / 联系人**：优先用本机接口查，不点界面。

## 已知现象
- 不要替用户拨 MMI 码（*#06# 一类），会被拒绝。
