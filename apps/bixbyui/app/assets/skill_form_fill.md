---
id: form_fill
name: 表单填写
description: 在 App 或网页里填表、报名、登记、填写地址和资料
priority: 50
budget: 560
paths: [agent]
triggers:
  keywords: [填表, 填写, 表单, 报名, 登记, 填一下, 填地址, 填资料, 注册]
  regex: ["(帮我)?(把|在).{0,10}(填|写)(上|好|进去)", "(填|写)(一下)?.{0,6}(表|单|资料|信息)"]
  screen: [提交, 必填, 请输入]
  examples: [帮我把这个报名表填一下, 把我的地址填进去, 帮我填一下收货信息]
requires:
  tools: [phone.operate, gui.unfold, ask_user, memory.search]
---
1. 字段值只用用户给的或用户让记下的（`memory.search` 查），不编、不猜身份证号 / 手机号。缺哪项 `ask_user`，一次问 1–2 项。
2. 填表交给 `phone.operate`（先 `gui.unfold`），goal 列清每个字段 → 值，最后写「填完停在提交前」。
3. 提交 / 报名 / 申请是对外动作：停下把填好的内容念给用户确认后再提交。
4. 密码、支付密码、验证码不代填，交给用户。
5. 填完核对一遍读回值，答复列出已填 / 未填项。
