---
id: phone_call
name: 打电话
description: 给联系人或号码打电话、回拨
priority: 70
budget: 460
paths: [agent]
triggers:
  keywords: [打电话, 打个电话, 拨打, 拨号, 回电话, 回拨, 呼叫]
  regex: ["(给|跟).{1,8}打(个)?电话", "打给.{1,8}", "call.{1,10}"]
  packages: [com.samsung.android.contacts, com.samsung.android.incallui]
  examples: [给妈妈打个电话, 打电话给10086, 回拨刚才那个号码]
requires:
  tools: [phone.call]
---
1. 用 `phone.call`，number 可填号码或通讯录名字（本机解析，名字和号码不出本机）。
2. 拨出一律弹红卡要用户确认，没有「始终允许」；用户拒绝就结束，不要换路拨。
3. 多个号码 / 没找到：照实说，让用户在面板上直接说「打电话给 X」选；不要猜号码。
4. 「刚才那个号码 / 屏幕上的电话」：先 `screen.context` 取号码再拨。
5. 不拨 *#06# 这类 MMI 码；紧急号码照用户说的拨，不拦。
