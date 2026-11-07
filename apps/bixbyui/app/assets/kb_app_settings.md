---
name: settings
display: Android 设置
kind: app
packages: ["com.android.settings"]
aliases: ["系统设置", "手机设置", "Android Settings", "Settings", "设置"]
sites: []
open: 设置
description: 安卓系统设置,管理网络、显示、权限、账号和系统信息
deeplinks: ["android.settings.WIFI_SETTINGS", "android.settings.BLUETOOTH_SETTINGS", "android.settings.DISPLAY_SETTINGS", "android.settings.SOUND_SETTINGS", "android.settings.APPLICATION_DETAILS_SETTINGS", "android.settings.DEVICE_INFO_SETTINGS", "android.settings.LOCATION_SOURCE_SETTINGS"]
source: "部分移植自 Mika core/src/main/resources/knowledge/apps/android-settings/APP.md（Apache-2.0，Copyright 2026 wangzan101，上游 ebb0e44）；OneSay 补了 Android 8 说明与深链"
---
## 是什么
安卓系统自带的设置,包名 `com.android.settings`。

## 界面
首页是分类列表,顶部有搜索框;点进分类后是逐层的子页面和详情页。设置项的名称、分类和层级会随系统版本与手机厂商而不同,有的厂商还会加自己的入口。返回按钮的文字和样子随页面变化,是否回到首页以页面标题和结构判断。

## 功能
- **搜索设置项**:顶部搜索框可直接按名称找到设置项。
- **开关类设置**:Wi-Fi、蓝牙、飞行模式、定位等。开关的状态有时只靠颜色和位置表示,界面树里不一定有明确的「开/关」文字。
- **关于手机**:型号、系统版本等,同一页里还可能有序列号、IMEI、设备名称等标识。
- **权限、开发者选项、账号、安全、系统更新、安装来源、重置**:修改后会真实改变设备状态。

## 已知现象
点击开关会立即切换状态,所以只想知道当前状态时,要读无障碍属性或看截图,不能靠点击。

## 在 OneSay 里（三星 Android 8）
- 三星设置首页是「连接」「声音和振动」「通知」「显示」「锦囊妙计」「设备维护」「应用程序」「锁屏」「生物识别和安全」「云和账户」「辅助功能」「常规管理」「软件更新」「关于手机」等分组；子项摘要（如「连接 · WLAN、蓝牙」）里出现的字不代表已经进到那一页。
- 能用系统接口（开关 WLAN / 蓝牙 / 亮度等工具）就别点界面；上面的设置页 action 可以直接打开对应页面。
