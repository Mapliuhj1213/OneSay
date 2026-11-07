---
id: translate
name: 翻译
description: 中英日韩等互译、这句话用英语怎么说、翻译屏幕上的外文
priority: 50
budget: 420
paths: [agent, ask]
triggers:
  keywords: [翻译, 英文怎么说, 英语怎么说, 日语怎么说, 用英语, 译成, 什么意思]
  regex: ["(用|翻成|译成|翻译成)(英|日|韩|法|德|西班牙|俄|中)(文|语)", "(怎么|如何)(说|讲|写)"]
  examples: [翻译一下这句话, 我饿了用日语怎么说, 把屏幕上的英文翻译成中文]
requires:
  tools: [screen.context, vision.screen.understand]
---
1. 翻译直接用你自己的能力完成，不需要工具、不开翻译 App。
2. 原文在屏幕上（「这段 / 屏幕上的」）：先 `screen.context`，不够再 `vision.screen.understand` 拿原文。
3. 只给译文本身；最多再给一个常用的另一种说法（同一种目标语言）。不讲语法、不列其他语言、不加读音或「翻译如下」之类前缀。
4. 不翻译、不转述密码、验证码、身份证号这类敏感内容。
