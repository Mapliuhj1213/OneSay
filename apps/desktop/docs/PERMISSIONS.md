# 电脑端权限 / 审批链路（app-whitelist-1116 梳理 + 分层重构）

## 一、改前现状（93bb42a）

手机发来的每条远程指令：

```
LanServer / RelayClient ──帧──▶ Agent.handle
  │ Protocol.verify（签名 + 时间窗 + 一次性编号） ── 不过 → 拒（BAD_SIG / REPLAY…）
  │ cfg.peerAllowed(from)                           ── 不过 → 「此设备未被允许下令」
  ▼
PcGuard.check(from, cmd, args)
  ├─ category()  → LinkPolicy.pcCat / classifyShell(PC) / 工作区内外
  ├─ Facts：checkUrl / checkClip / blockedApp·appSelf·appSystem / MediaArgs / protectedWorkspacePath
  │         suspect()（8.3 短名、默认断网时认不准的可能联网命令）  ← fix-1114「可疑一律弹确认」
  │         shortcutFacts（快捷指令 id + 内容哈希）
  ├─ plugin()  → LinkPolicy.inspectCommand（提权 / UAC 绕过 / 要管理员 / 自授权 / 编码隐藏命令 / 断网）；插件出错 = 拒
  ├─ LinkPolicy.decide(Target.PC, Mode(沙箱档 × 审批策略), Cat, Facts)
  │     decideCore 顺序：远程/语音批准 → 付款最后一下 → 插件结论 → 自授权 / 给自己授权 / 卸载关掉自己 → 未知
  │                      → 查询 → 要管理员（只生成脚本）→ 只读档 → 第三方程序（只完全访问）
  │                      → 系统程序 / 支付类程序（approve-not-block：问你，「从不询问」拒）→ 网址 / 剪贴板信任检查
  │                      → 快捷指令 → 链接下载 → 两轴矩阵（设置 / 关机 / 卸载 / 脚本 / 命令 / 工作区外写）
  │     外层：suspect / oddWriting → 升级成不能记住的确认（「从不询问」下拒）
  ├─ LinkPolicy.remembered（「以后这类不用问」按动作类型；硬规则不是 CONFIRM 所以绕不过）
  └─ 结论分派
        DENY    → 回原因码（插件 / 自授权的同时通知电脑前的人）
        SCRIPT  → 在工作区 scripts/ 生成 .ps1，回路径
        CONFIRM → Confirms.ask：页面里的确认卡（ConfirmGate：0.7 秒后可点、失焦 / 无响应 / Esc / 60 秒 = 拒、只认本机鼠标键盘）
                  + 同时发 confirm_offer 到配对手机（手机屏幕真人触摸、一次性 token）
                  ALLOW / REMEMBER / ALWAYS → 发一次性 Pass；拒 / 超时 → 回原因码
        ALLOW   → 发一次性 Pass（完全访问下的提醒走 notify）
  ▼
Agent：Pass.consume(cmd, 同一份 args) 才执行（审批在执行层强制）；SafeExec.REMOTE：子进程永不提权
```

本机界面（127.0.0.1 + 口令）：设置页的危险操作走页面自己的确认框（`ask()`，默认焦点「取消」、危险操作红色）；
「以后这类不用问」「快捷指令批准表」只能在本机看 / 撤销；远程改设置类指令一律 `self_grant_blocked`。

## 二、改后（统一分层，与手机 ExecutionGate 同一套语义）

```
PcGuard.check
  ├─（同上）category / Facts / plugin / LinkPolicy.decide / remembered     ← 原结论，一字未改
  ├─ layered()  ★ 新：ActionPolicy（三端逐字相同）
  │     L1 硬拒      原结论 DENY / SCRIPT → 原样（三条硬规则、插件硬拦、只读档、「从不询问」…）
  │     L2 支付/隐私外发  目标程序在白名单 且（支付类程序 / 付款指令，或命令·剪贴板·网址里认出隐私）→ 不能记住的确认
  │     L3 读过隐私后外发 目标在白名单 且 这台手机 10 分钟会话里读走过隐私（剪贴板 / 文件 / 截图）且这一步联网外发 → 不能记住的确认
  │     L3b 认不准   suspect / oddWriting → 不能记住的确认（原来就有，这里再守一次，白名单放不松）
  │     L4 白名单    目标在白名单、原结论 CONFIRM、会话放行 < 40 步 → ALLOW（带提醒通知）+ 审计
  │     L5 默认      原结论
  └─ 结论分派（同上；确认卡上补「⚠ 原因」）
```

- 只有 L4 会放松，而且只对**白名单程序**、只把 CONFIRM 变 ALLOW；DENY / SCRIPT 永远不变。
- 不启用白名单（`PcGuard.whitelist == null`）或目标不在白名单：结论与改前逐条一致（门禁 5113 条原用例全过，`noWhitelistStoreMeansOldBehaviour`）。
- 「目标程序」只认：打开 / 关掉 / 卸载程序的 exe，以及 `sys_exec` 第一段就是那个 exe 的单条命令（管道、串联、重定向、变量、子表达式、cmd / powershell / start 都不算）。
  命令行、脚本解释器、系统管理工具本身不能加进白名单（`AppWhitelist.NEVER_WINDOWS`）。
- 远程加删白名单的指令算自授权（硬拒）；白名单只在本机界面改，经 `whitelist_sync` 同步。

## 三、和手机 ExecutionGate 的对应

| 层 | 手机 | 电脑 |
|---|---|---|
| L1 | 锁屏拒、ROOT 污点拒、通信号码拒、付款最后一下（userMustTap）、红卡不可逆名单 | 三条硬规则、插件、只读档、从不询问 |
| L5 原结论 | ToolGate + ConsentPolicy（始终允许）/ SensitiveGuard + RiskLabel + Mika | LinkPolicy.decide + remembered |
| L2–L4 | `whitelist/PhoneWhitelistGate`（phone.operate 每步、工具调用带 package 的） | `PcGuard.layered` |
| 判定 | 同一个 `ActionPolicy.decide`，同一组 `docs/app-whitelist-vectors.json` |
