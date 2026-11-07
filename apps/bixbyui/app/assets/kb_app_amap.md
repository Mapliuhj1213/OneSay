---
name: amap
display: 高德地图
kind: app
packages: ["com.autonavi.minimap"]
aliases: ["高德", "高德地图", "Amap", "AutoNavi"]
sites: []
deeplinks: ["amapuri://route/plan/", "androidamap://navi", "androidamap://poi"]
open: 高德地图
description: 地图、搜地点、路线规划与导航
source: "OneSay 自写（格式照 Mika APP.md）；深链 / 界面描述未在测试机 上逐条实测的已注明推断"
---
## 是什么
高德地图，包名 `com.autonavi.minimap`。

## 界面
首页是地图，顶部是搜索框「查找地点、公交、地铁」，下方有「路线」「打车」等入口；搜到地点后底部卡片有「路线」「导航」「收藏」。

## 功能
- **路线规划**：`amapuri://route/plan/?dname=目的地名&dlat=纬度&dlon=经度&dev=0&t=0`（t：0 驾车、1 公交、2 步行、3 骑行）；不知道经纬度时只给 dname 也能打开规划页再选。
- **导航**：`androidamap://navi?sourceApplication=bixbyui&poiname=名字&lat=纬度&lon=经度&dev=0`，会直接开始导航。
- **搜地点**：`androidamap://poi?sourceApplication=bixbyui&keywords=关键词`。

## 已知现象
- 地图区域是画布，控件树里读不到地图上的字；地点信息看底部卡片和列表。
- 首次打开可能弹定位权限 / 用户协议。深链参数在这台手机上未实测（推断）。
