# 变更记录（OneSay Clock com.onesay.clock）

## 0.1.6（versionCode 8）— 首个开源版本

- 闹钟：列表与编辑（时间、重复、名称、智能播报、稍后提醒、振动、铃声）；只用 `setAlarmClock` 排最早一次，开机 / 升级 / 改时间或时区后自动重排；响铃无人操作 N 分钟后自动停止。
- 智能播报：固定模板「时间 + 温度 + 天气」→ 可选降雨提醒（和风天气分钟级降水 / 逐小时预报）→ 可选新闻 → 收尾句；朗读表见 `docs/phrase-table.md`。
- 云端语音：DashScope 兼容接口，服务地址用户可配（只收 https），Key 只发往所配主机；可预合成固定句离线播报。
- 播报状态经 `BRIEF_STATE` 广播给 OneSay（接收方受 signature 级权限保护，需同一证书签名），见 `docs/02-BRIEF_STATE接口.md`。
- release 构建：`build_release.sh`（d8 `--release`、javac `-g:none`、断言不可调试；keystore 由用户提供，`RELEASE_SIGNER_SHA256` 可选钉死证书，`UNSIGNED=1` 出未签名包）。debug 构建：`build_linux.sh`（未提供 keystore 时在 /tmp 生成本机 debug keystore）。
- release 包的 ControlReceiver 不导出，并忽略测试类命令。
- JVM 测试：CoreTest + RainTest。
