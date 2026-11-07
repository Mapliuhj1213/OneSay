---
name: alipay
display: 支付宝
kind: app
packages: ["com.eg.android.AlipayGphone"]
aliases: ["支付宝", "Alipay", "阿里支付"]
sites: []
deeplinks: ["alipays://platformapi/startapp?saId=10000007", "alipays://platformapi/startapp?saId=20000056"]
open: 支付宝
description: 付款、收款、转账、扫码、生活缴费与小程序
source: "OneSay 自写（格式照 Mika APP.md）；深链 / 界面描述未在测试机 上逐条实测的已注明推断"
---
## 是什么
支付宝，包名 `com.eg.android.AlipayGphone`。

## 界面
首页上方一排是「扫一扫」「收付款」「出行」「卡包」，下面是常用服务宫格和搜索框；底部标签：首页、理财、生活、消息、我的。很多服务是小程序，打开后顶部有「…」和关闭按钮。

## 功能
- **扫一扫**：深链 `saId=10000007` 直达；扫到付款码、链接后还要用户确认。
- **付款码**：深链 `saId=20000056` 打开；付款码是敏感信息，不要读出来、不要截图外传。
- **转账 / 付款 / 缴费**：都是花钱的不可逆动作，每一步确认都要用户亲自点；不替用户输支付密码。

## 已知现象
- 打开后常有活动弹窗，右上角或下方有「×」。
- 付款、登录、实名相关页面可能禁止读屏，树会读不到内容（标成受保护）。
- 深链在这台手机上未实测（推断）。
