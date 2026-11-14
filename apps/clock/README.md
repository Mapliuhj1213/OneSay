# OneSay Clock（com.onesay.clock）

闹钟 + 晨间语音简报。总体说明（安装、密钥、构建、隐私）见仓库根目录 `README.md`。

## 功能

- **闹钟**：列表、编辑（时间、重复、名称、智能播报、稍后提醒、振动、铃声）。每次只用 `setAlarmClock` 排最早的一次；开机、升级、改时间 / 时区后自动重排。
- **智能播报**：「嘿，你好，现在是本地时间 X 点 X 分，室外温度……今天是 X 天气。」→（可选）降雨提醒 →（可选）新闻标题 →「目前就是这样了，一天好心情。」
  所有固定说法在朗读表 `app/assets/phrases/zh-CN.json`，说明见 [docs/phrase-table.md](docs/phrase-table.md)。
- **语音**：全部由你在「API 密钥」面板配置的云端语音（DashScope 兼容，服务地址可改，只收 https）合成。没有 Key 或没选音色 = 不朗读。「预合成朗读表」可一次合成全部固定句，之后闹钟不联网也能报时报天气。
- **降雨提醒**（需和风天气 Key + Host）：「大约 15 分钟后有小雨，出门记得带伞。」等，没雨不说。
- **新闻**（可选，默认全关）：今日头条热榜、B 站热搜、腾讯要闻，都是非官方公开接口，在设置里逐个打开。
- **对外闹钟接口**：同一证书签名的 OneSay 助手可以设 / 开关 / 删 / 查闹钟（signature 级权限保护，见 [docs/03-闹钟接口.md](docs/03-闹钟接口.md)）。
- **永不静音**：人声、铃声走闹钟音量；成品缺失或播放出错时回落到铃声，再不行用代码合成的提示音。
- **不播旧稿**：成品带目标闹钟时间与日期，校验不过就只拼「时间 + 缓存天气 + 收尾」。

## 可选厂商适配

天气只用 open-meteo（免 key）+ 系统定位 / 你设置的城市，不读任何厂商天气 App。

## 文档

- `docs/phrase-table.md` — 朗读表格式、占位符、拼接顺序、改说法与加语言
- `docs/02-BRIEF_STATE接口.md` — 给助手的简报状态接口
- `CHANGELOG.md` — 变更记录

## 测试与构建

```bash
OUT=/tmp/clock-test bash test/run_linux.sh     # 纯 Java 核心（CoreTest + RainTest）
bash build_release.sh                          # release 包；签名口令见根 README「构建」
```
