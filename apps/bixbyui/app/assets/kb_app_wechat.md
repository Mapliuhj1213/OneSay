---
name: wechat
display: 微信
kind: app
packages: ["com.tencent.mm"]
aliases: ["WeChat", "weixin", "微信", "wechat"]
sites: []
open: 微信
pace: careful
description: 聊天、公众号与社交
deeplinks: ["weixin://"]
source: "部分移植自 Mika core/src/main/resources/knowledge/apps/wechat/APP.md（Apache-2.0，Copyright 2026 wangzan101，上游 ebb0e44）；OneSay 补了 Android 8 说明与深链"
---
## 是什么
微信是聊天与社交 App,包名 `com.tencent.mm`。

## 界面
主要结构通常是聊天列表、通讯录、发现和个人页,具体布局随版本、账号状态和字体设置而不同。顶部搜索会同时返回联系人、群聊、聊天记录、小程序和公众号,结果按分组显示。

## 功能
- **会话**:未读标记、置顶、草稿、服务通知和普通消息都在列表里,容易混淆。联系人显示名、备注名、群名和头像可能相似,进入会话后标题才是实际对象。输入框里有文字只是草稿,发出后会话里才有本人的气泡。群聊中每条消息有发言人。
- **公众号**:内容与个人会话是分开的。

## 在 OneSay 里（Android 8）
- 打开：`weixin://` 只能把微信拉到前台，不能直达某个人的会话；找人要在微信里搜。
- 发消息、发朋友圈、转账、发红包都是对外动作，执行前要用户确认；同一会话连着发多条会被外发节奏拉开间隔。
- 扫一扫在首页右上角「+」菜单里。
