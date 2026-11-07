---
name: baidumap
display: 百度地图
kind: app
packages: ["com.baidu.BaiduMap"]
aliases: ["百度地图", "百度导航", "BaiduMap"]
sites: []
deeplinks: ["baidumap://map/direction", "baidumap://map/place/search", "baidumap://map/navi"]
open: 百度地图
description: 地图、搜地点、路线与导航
source: "OneSay 自写（格式照 Mika APP.md）；深链 / 界面描述未在测试机 上逐条实测的已注明推断"
---
## 是什么
百度地图，包名 `com.baidu.BaiduMap`。

## 界面
首页地图上方是搜索框，底部有「路线」「打车」等入口；地点详情卡片有「到这去」「导航」。

## 功能
- **路线**：`baidumap://map/direction?destination=name:目的地&mode=driving&src=andr.bixbyui.voice`（mode：driving / transit / walking / riding）。
- **搜地点**：`baidumap://map/place/search?query=关键词&src=andr.bixbyui.voice`。
- **导航**：`baidumap://map/navi?query=目的地&src=andr.bixbyui.voice`。

## 已知现象
- 地图页节点很多（实测 443 个，读树较慢），控件树读不到地图上的字。
- 深链在这台手机上未实测（推断）。
