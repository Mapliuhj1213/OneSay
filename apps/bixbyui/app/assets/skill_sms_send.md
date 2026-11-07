---
id: sms_send
name: 发短信
description: 用系统短信给联系人或号码发短信
priority: 65
budget: 480
paths: [agent]
triggers:
  keywords: [发短信, 短信告诉, 短信说, 回短信, 发条短信, 发个短信]
  regex: ["短信.{0,8}(给|告诉|通知|说)", "(给|跟).{1,8}发(个|条)?短信"]
  packages: [com.samsung.android.messaging]
  examples: [给爸爸发短信说晚点回, 短信告诉张三我到了]
  exclude: [验证码]
requires:
  tools: [sms.send]
conflicts: [msg_send]
---
1. 直接用 `sms.send`：number 可以填号码，也可以填通讯录里的名字（本机解析成号码，名字和号码不发给云端）。
2. 正文必须来自用户原话；没说内容就 `ask_user` 问，不要代写长文。
3. 工具返回「通讯录里有多个号码 / 没找到」时：照实告诉用户，让他在面板上直接说「发短信给 X」选人，不要猜号码。
4. 发送会弹确认卡，用户拒绝就停，不换别的路偷偷发。
5. 不要读、转述或转发验证码短信。
