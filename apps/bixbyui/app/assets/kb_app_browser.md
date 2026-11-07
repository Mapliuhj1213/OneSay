---
name: browser
display: 三星浏览器
kind: app
packages: ["com.sec.android.app.sbrowser"]
aliases: ["浏览器", "三星浏览器", "Samsung Internet", "网页"]
sites: []
deeplinks: []
open: 三星浏览器
description: 三星浏览器：打开网页、搜索、书签
source: "OneSay 自写（格式照 Mika APP.md）；深链 / 界面描述未在测试机 上逐条实测的已注明推断"
---
## 是什么
三星浏览器，包名 `com.sec.android.app.sbrowser`。

## 界面
底部工具栏：后退、前进、主页、书签、标签页、菜单；地址栏在顶部（滚动时会收起）。

## 功能
- **打开网址**：直接用 `https://…` 深链打开最快。
- **读网页内容**：优先用读网页工具（web.read），比在浏览器里读屏省事、完整。
- **搜索**：地址栏输入关键词回车，用的是浏览器设置的默认搜索引擎。

## 已知现象
- 网页的控件树由 WebView 给出，文字多、按钮少；页面输入框用 SET_TEXT 可能不生效，会退到粘贴（粘完会还原剪贴板）。
