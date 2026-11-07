---
id: express_track
name: 查快递
description: 查快递到哪了，单号来自剪贴板、屏幕或用户口述
priority: 55
budget: 520
paths: [agent]
triggers:
  keywords: [快递, 物流, 单号, 包裹, 到哪了, 取件码, 菜鸟, 顺丰, 中通, 圆通, 韵达, 京东物流, 驿站]
  regex: ["(sf|jd|yt|zto|jt)\d{8,}", "\d{12,15}"]
  examples: [帮我查一下这个快递到哪了, 我的包裹到哪了, 查下剪贴板里的单号]
requires:
  tools: [screen.context, web.search, web.read, app.launch]
---
1. 先拿单号：用户说了就用；说「这个 / 刚复制的」→ `screen.context`（含屏幕与剪贴板里的单号、取件码）。拿不到就 `ask_user`。
2. 查轨迹：`web.search`「快递公司 + 单号 查询」，再 `web.read` 官方查询页；顺丰等要验证码 / 手机尾号的网页查不了时，如实说，并建议打开对应 App（`app.launch`）。
3. 不知道哪家：按单号前缀猜（SF 顺丰、JD 京东、YT 圆通、ZTO/7 开头多为中通），猜的要说明。
4. 只报最新一两条轨迹 + 预计送达；不要编轨迹。取件码只给用户本人看，不外传。
