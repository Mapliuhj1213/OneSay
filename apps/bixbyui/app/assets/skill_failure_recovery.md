---
id: failure_recovery
name: 失败恢复与卡住处理
description: 助手自己的工具失败、找不到按钮、卡住、重试时的处理（手机本身的故障归设备诊断）
priority: 45
budget: 600
paths: [agent]
triggers:
  keywords: [没反应, 失败了, 不行, 卡住, 重试, 再试一次, 怎么又, 还是不行, 没成功, 出错]
  regex: ["(怎么|为什么|咋).{0,6}(不行|没用|失败|没反应)", "(再|重新)(试|来)(一次|一下|下)"]
  examples: [刚才怎么没反应, 再试一次, 还是不行]
conflicts: [device_diag]
requires:
  tools: [end_not_done, probe.apps, app.launch, vision.screen.understand]
---
1. 看失败码再决定：NOT_CONFIGURED / 未配置 → 换下一档做法；被安全策略拦截 / 用户拒绝 → 不换路绕过，直接说明。
2. 降级链：专用接口 → `probe.apps` / `probe.app_intents` 找入口 → `app.launch` 打开 → `vision.screen.understand` 读屏 → 点界面。每档最多试两次。
3. 收到守卫提醒（无效果 / 反复读同一页）：换入口或换说法，不要再点同一个地方。
4. 用户说「再试一次」：先看上次失败在哪一步，换没试过的路，不要原样重放。
5. 都不行：`end_not_done`，答复写清「试了什么、为什么没成、用户可以怎么做」。
