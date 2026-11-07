---
id: shop_pay
name: 购物比价与下单（停在付款前）
description: 买东西、入手、下单、比价、问价、点外卖、充值缴费；多平台核价、给版本推荐，只做到下单 / 付款页前，绝不替用户付款
priority: 75
budget: 700
paths: [agent]
triggers:
  keywords: [买, 下单, 购买, 入手, 想买, 加购物车, 外卖, 点餐, 比价, 付款, 支付, 充值, 缴费, 话费, 淘宝, 京东, 天猫, 美团, 拼多多, 支付宝]
  regex: ["(帮我|给我)?(买|订|点)(个|份|一|些|杯|箱)", "(帮我|给我|我想|想|我要)(买|入手|下单)", "(多少钱|哪家便宜|最便宜|到手价|券后)"]
  intents: []
  packages: [com.taobao.taobao, com.jingdong.app.mall, com.tmall.wireless, com.sankuai.meituan, com.eg.android.AlipayGphone, com.xunmeng.pinduoduo]
  screen: [提交订单, 立即支付, 确认付款, 去结算]
  examples: [帮我在京东买一箱牛奶, 帮我买雷蛇巴塞利斯蛇v3, 点一份麦当劳外卖, 给我的手机充50块话费]
  exclude: [买了吗, 买过, 买的快递, 买的东西到, 买单, 买菜谱]
requires:
  tools: [web.search, web.read, research.spawn, browser.open, app.launch, phone.operate, gui.unfold]
---
1. 铁律：绝不下单、绝不付款。最远停在「提交订单 / 付款」页前，说清停在哪、总价多少、还差用户做什么。登录 / 验证码 / 支付密码 / 人脸：停下交给用户。
2. 不问预算。用户没说版本 / 型号：先列这个系列在售各版本的差别，按最常见需求默认推荐一款并给理由；用户说「你决定 / 你看着办」就直接选定，不再让用户挑。
3. 先 `web.read` 比价网条目（provider=compare，有各店券后价与历史最低）→ 再读商品页核价 → 不够才搜「商品 京东 价格」兜底；至少 2 个平台，摘要不算核价；按版本分组，V3 / V4 分开。
4. 每条报价写：平台 + 店铺类型（自营 / 官方旗舰 / 授权旗舰 / 专卖）+ 价格 + 日期 + 券后 / 满减 / 限时条件 + 来源。
5. 标题和正文规格对不上的报价丢掉；二手 / 翻新 / 无配件版单独标。提一句新一代型号和当前大促（如双 11）。
6. 没装购物 App 也能比价；要进店铺页就 `browser.open` 打开链接交给用户。装了 App 且用户要进 App：`phone.operate` 一次一个 App，goal 写到「加入购物车并进入结算页，停在提交订单前」。
7. 只提议工具表里有的动作，不提议替用户付款。
