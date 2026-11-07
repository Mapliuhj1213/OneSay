---
id: app_manage
name: App 安装与卸载
description: 下载、安装、更新、卸载 App；找不到某个 App
priority: 55
budget: 600
paths: [agent]
triggers:
  keywords: [下载, 安装, 装个, 卸载, 删掉这个app, 更新app, 应用商店, 商店, 装一个]
  regex: ["(下载|安装|装|卸载|删除|删掉)(一下|个)?.{1,12}(app|应用|软件)?$", "(app|应用|软件).{0,4}(下载|安装|卸载)"]
  intents: [app]
  packages: [com.sec.android.app.samsungapps, com.android.vending]
  examples: [帮我下载微信, 把抖音卸载了, 装一个高德地图]
  exclude: [下载图片, 下载文件]
requires:
  tools: [store.search.market, store.search.play, store.detail.market, store.status.check, app.uninstall, probe.apps]
---
1. 先 `probe.apps` 看装没装；装了就说已安装（要打开就 `app.launch`）。
2. 下载：`store.status.check` 看哪个商店能用，只开一个（默认应用商店：`store.detail.market` / `store.search.market`；用户点名 Play 才用 `store.search.play`），停在详情页让用户点安装。
3. root 静默安装挂起未开放：不要用 shell 装 apk，也不要从网页下载 apk。
4. 卸载：`app.uninstall` 是高危，先说明会删掉数据并征得同意；系统应用不卸。
5. 答复说清停在哪一步（详情页 / 等用户点安装）。
