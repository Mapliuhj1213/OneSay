---
id: settings_toggle
name: 系统开关与设置
description: WiFi、蓝牙、亮度、音量、手电、勿扰、飞行模式、屏幕超时等开关与设置
priority: 50
budget: 560
paths: [agent]
triggers:
  keywords: [wifi, 无线网, 蓝牙, 亮度, 调亮, 调暗, 音量, 静音, 手电, 勿扰, 免打扰, 飞行模式, 流量, 热点, 省电, 自动旋转, 息屏, 屏幕超时, nfc, 定位]
  regex: ["(打开|关闭|关掉|开启|关上|调到|调成).{0,6}(wifi|蓝牙|亮度|音量|手电|勿扰|飞行|流量|定位|旋转)"]
  intents: [settings, net, media, sys]
  packages: [com.android.settings]
  examples: [把亮度调到一半, 打开蓝牙, 开启勿扰模式, 声音小一点]
requires:
  tools: [connectivity.wifi.on, settings.brightness.set, volume.media.set, settings.page.sound]
---
1. 有直接接口就一步到位：`connectivity.wifi.on` / `off`、`connectivity.bt.on` / `off`、`settings.brightness.set` / `up` / `down`、`volume.media.set`、`torch.on`、`connectivity.airplane.on`、`settings.screentimeout.set` 等；做完用对应 `*.state` / `*.get` 读回核对。
2. 「调高一点」：用 up / down 类；「一半」= 50%。
3. 勿扰模式没有直接接口：`settings.page.sound` 打开声音设置页，再让用户点，或必要时 `gui.unfold` → `phone.operate` 只切这一个开关。
4. 飞行模式、关数据会断网：先说明后果再做。
5. 答复报出改后的实际值（「亮度 50%」），读回不一致要照实说。
