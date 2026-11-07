---
id: nav_taxi
name: 导航与打车
description: 导航去某地、查路线、打车去某地
priority: 60
budget: 600
paths: [agent]
triggers:
  keywords: [导航, 带我去, 怎么去, 路线, 打车, 叫车, 叫个车, 滴滴, 高德, 百度地图, 开车去, 坐地铁]
  regex: ["(去|到).{1,12}(怎么走|多远|多久|路线)", "(打|叫)(个)?车(去|到)"]
  intents: [map]
  packages: [com.autonavi.minimap, com.baidu.BaiduMap, com.sdu.didi.psnger]
  examples: [导航去最近的加油站, 打车去机场, 从公司到火车站怎么走]
requires:
  tools: [app.launch, phone.operate, gui.unfold, screen.nav.to, web.search]
---
1. 目的地要具体：「公司 / 家」这类没记过的地点、或同名多处，先 `ask_user`；屏幕上的地址用 `screen.nav.to`。
2. 用户点名 App 就用那个；没点名按 App 知识里的默认顺序（高德优先）。深链只用 App 知识里给出的，别自己拼。
3. 只问「多远 / 多久」：能用 `web.search` 或地图深链看结果就别开始导航。
4. 打车：打开打车页、填好终点后停在「呼叫 / 确认叫车」前让用户确认（花钱）。不要替用户选车型加价。
5. 答复说清：用哪个 App、终点是什么、停在哪一步。
