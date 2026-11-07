---
name: gallery
display: 相册
kind: app
packages: ["com.sec.android.gallery3d"]
aliases: ["相册", "图库", "三星相册", "照片", "Gallery"]
sites: []
deeplinks: []
open: 相册
description: 三星相册：看照片 / 视频、相簿、分享、删除
source: "OneSay 自写（格式照 Mika APP.md）；深链 / 界面描述未在测试机 上逐条实测的已注明推断"
---
## 是什么
相册，包名 `com.sec.android.gallery3d`。

## 界面
底部（或顶部）标签：图片、相簿、故事 / 共享。图片页按日期分组的缩略图网格；点开是大图，底部有「分享」「编辑」「删除」等。

## 功能
- **数照片 / 找最近的**：读相册数量优先用系统接口（媒体库查询工具），不用一屏屏翻。
- **删除**：进回收站，属于删除类动作，要用户确认。
- **分享**：分享面板选目标 App 后会打开那个 App，属于对外动作。

## 已知现象
- 缩略图网格节点多但没有文字（只有日期标题），树质量常被判 partial；按日期标题和数量判断。
