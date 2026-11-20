---
id: text-stats
name: 文本统计
description: 数一段文字有多少字、多少行、出现最多的词。示例技能，不联网、不读文件。
version: 1.0.0
---

# 文本统计

给它一段文字（text），返回：
- chars：字数（不算空白）
- lines：行数
- top：出现最多的词（按空白和标点切）

这是 OneSay 自带的示例技能，演示技能包的写法：SKILL.md 写说明，skill.json 声明输入输出和权限，scripts/main.js 是入口。
