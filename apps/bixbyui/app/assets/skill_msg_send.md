---
id: msg_send
name: 发微信 / QQ 消息
description: 给某人或某群发微信、QQ 消息（含转发屏幕上的内容）
priority: 70
budget: 620
paths: [agent]
triggers:
  keywords: [发微信, 微信发, 微信告诉, 微信说, 发qq, qq发, qq告诉, 回微信, 回复微信, 发消息给, 发个消息]
  regex: ["(微信|qq|企业微信).{0,10}(发|告诉|说|回|通知)", "(给|跟|和).{1,8}(发|回)(个)?(微信|qq|消息)"]
  intents: []
  packages: [com.tencent.mm, com.tencent.mobileqq]
  screen: [按住 说话, 发送]
  examples: [给妈妈发微信说我到家了, 微信告诉老王明天开会, 用qq给张三发个消息]
  exclude: [朋友圈, 红包, 转账, 短信]
requires:
  tools: [phone.operate, gui.unfold]
  perms: [root 或无障碍读屏]
conflicts: [sms_send]
---
1. 先凑齐三样：App（微信 / QQ）、收件人、正文。缺收件人或正文用 `ask_user` 问一句，不要自己编正文。
2. 微信 / QQ 没有发消息接口：`gui.unfold` 写明「发微信消息无接口」→ `phone.operate`，goal 写全：「在微信搜索联系人 X，进入会话，输入：……，停在发送前」。一次只交给它一个 App 一个目标。
3. 进会话后以标题为准核对对象（备注名 / 群名可能相似）；同名多人时停下问。
4. 发送是对外动作：最后一步一定先让用户确认（确认卡），用户说「发吧」才点发送。
5. 多条消息合成一条发，别连发刷屏。
6. 结尾说清：发给谁、发了什么、是否已发出（还是停在输入框）。
