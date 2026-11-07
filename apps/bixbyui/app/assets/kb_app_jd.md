---
name: jd
display: 京东
kind: app
packages: ["com.jingdong.app.mall"]
aliases: ["京东", "JD", "jd"]
sites: []
deeplinks: []
open: 京东
description: 购物：搜索商品、下单、物流
source: "OneSay 自写（格式照 Mika APP.md）；深链 / 界面描述未在测试机 上逐条实测的已注明推断"
---
## 是什么
京东，包名 `com.jingdong.app.mall`。

## 界面
首页顶部搜索框，底部标签：首页、新品 / 逛、消息、购物车、我的。商品页底部「加入购物车」「立即购买」。

## 功能
- **订单与物流**：「我的」→「待收货」「全部订单」。
- **下单**：提交订单与付款要用户确认。

## 已知现象
- 不给深链：京东站内链接需要 JSON 参数，没核实过的别拼。首页常有开屏广告，右上角「跳过」。
