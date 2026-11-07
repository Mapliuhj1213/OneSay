---
name: sms
display: 短信
kind: app
packages: ["com.samsung.android.messaging"]
aliases: ["短信", "信息", "三星信息", "Messages", "SMS"]
sites: []
deeplinks: ["smsto:", "sms:"]
open: 短信
description: 收发短信、查看验证码与通知短信
source: "OneSay 自写（格式照 Mika APP.md）；深链 / 界面描述未在测试机 上逐条实测的已注明推断"
---
## 是什么
短信，包名 `com.samsung.android.messaging`。

## 界面
会话列表，顶部搜索；点开会话后底部输入框和发送按钮。

## 功能
- **发短信**：`smsto:号码` 打开写短信界面（正文要自己填）；发送是对外动作，要用户确认。能用短信接口（sms.send，黄卡）就别点界面。
- **读验证码**：验证码只给用户看，不进记录、不外传。

## 已知现象
- 联系人显示名可能和用户说的称呼不同，发之前核对会话标题里的号码 / 名字。
