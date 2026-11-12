# 变更记录（OneSay Clock com.onesay.clock）

## 未发布（feat/borrow-1112-companion，2026-11-12，版本号未改）
社区借鉴：思路来源 Wake Me（WeckMich，GPL-3.0，https://github.com/anka1981/WeckMich）——只读功能说明，代码全部自写，未复制任何源码（见仓库根 NOTICE / THIRD_PARTY_NOTICES.md）。
- 重复规则（core/RepeatRule）：大小周（两周交替）、每月第 n 个周几（含「最后一个周五」）、每隔 N 天；编辑页预览接下来 5 次。时钟没有节假日 / 调休表，预览不标注节假日。
- 暂停而不删除（core/Pause）：暂停到某天，或跳过接下来 N 次；编辑页列出会被跳过的日期，到期自动恢复。
- 可靠性自检（core/Health + ui/CheckActivity）：精确闹钟权限、电池优化白名单、闹钟音量 0、勿扰挡闹钟、通知权限，一键跳设置。
- 错过的闹钟（core/Missed）：开机 / 改时间 / 改时区 / 升级后补发「错过了 X 点的闹钟」通知，不补响。
- 闹钟接口（AlarmApi）：ALARM_SET 只复用「每周几」规则的同时刻闹钟；dump 对新规则的闹钟附带 `rule` 文案（每周几的格式不变）。
- 旧闹钟数据逐字节兼容（新字段只在非默认时写入）。

## 0.1.6（versionCode 8）— 首个开源版本

- 闹钟：列表与编辑（时间、重复、名称、智能播报、稍后提醒、振动、铃声）；只用 `setAlarmClock` 排最早一次，开机 / 升级 / 改时间或时区后自动重排；响铃无人操作 N 分钟后自动停止。
- 智能播报：固定模板「时间 + 温度 + 天气」→ 可选降雨提醒（和风天气分钟级降水 / 逐小时预报）→ 可选新闻 → 收尾句；朗读表见 `docs/phrase-table.md`。
- 云端语音：DashScope 兼容接口，服务地址用户可配（只收 https），Key 只发往所配主机；可预合成固定句离线播报。
- 播报状态经 `BRIEF_STATE` 广播给 OneSay（接收方受 signature 级权限保护，需同一证书签名），见 `docs/02-BRIEF_STATE接口.md`。
- release 构建：`build_release.sh`（d8 `--release`、javac `-g:none`、断言不可调试；keystore 由用户提供，`RELEASE_SIGNER_SHA256` 可选钉死证书，`UNSIGNED=1` 出未签名包）。debug 构建：`build_linux.sh`（未提供 keystore 时在 /tmp 生成本机 debug keystore）。
- release 包的 ControlReceiver 不导出，并忽略测试类命令。
- JVM 测试：CoreTest + RainTest。
