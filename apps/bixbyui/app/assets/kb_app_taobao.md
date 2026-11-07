---
name: taobao
display: 淘宝
kind: app
packages: ["com.taobao.taobao"]
aliases: ["淘宝", "手淘", "Taobao"]
sites: []
deeplinks: ["taobao://s.taobao.com/search"]
open: 淘宝
description: 购物：搜索商品、下单、物流
source: "OneSay 自写（格式照 Mika APP.md）；深链 / 界面描述未在测试机 上逐条实测的已注明推断"
---
## 是什么
淘宝，包名 `com.taobao.taobao`。

## 界面
首页顶部搜索框，底部标签：首页、视频 / 逛逛、消息、购物车、我的淘宝。商品页底部有「加入购物车」「立即购买」。

## 功能
- **搜索**：`taobao://s.taobao.com/search?q=关键词` 直接到搜索结果（推断，未实测）；也可点首页搜索框输入。
- **物流 / 订单**：「我的淘宝」→「待收货」「全部订单」，订单详情里有物流。
- **下单**：选规格 → 立即购买 → 提交订单 → 付款，都要用户确认。

## 已知现象
- 搜索框里的灰字是推荐词（hint），不是已输入的内容。
- 商品列表很长、多为图片，名字在描述文字里；价格带 ¥。
