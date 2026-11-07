# 分钟级降水（和风天气）——语音问雨 + 灵动岛下雨 / 停雨提醒

分支 `feat/rain-minutely`（基于 `feat/shortcuts`）。计划 App 不改。

## 用户能做什么

- **语音问**：「会下雨吗」「什么时候下雨」「雨什么时候停」「要带伞吗」「天气怎么样」（下雪同理）。本地工具直接答，不过大模型；答复写进会话历史（追问有上下文），对话窗不挂「以上由 AI 生成」。
  - 例：「现在在下小雨，大概 33 分钟后停，不过之后还会再下。间歇性降雨还将持续90分钟。」
  - 没雨：「未来两小时不会下雨。」；「天气怎么样」再加实况：「现在 26 度，阴。未来两小时不会下雨。」
  - 查不到如实说：国外 →「你这里没有分钟级降水数据，和风天气这项只覆盖国内。」；网络错 →「查降水的时候网络出错了，等会儿再问我一次吧。」；定位不到 / Key 被拒同理。
- **灵动岛**：只有**未来 60 分钟内要开始或要停**时才上岛：胶囊「12 分钟后下雨」「雨约 40 分钟后停」，状态变了就地更新（亮屏时每分钟按缓存重算标题，不联网），结束自动收起。点开 = 和风 summary + 2 小时柱状条（24 根，每根 5 分钟，现在 · 1 小时 · 2 小时）。「知道了」/ × 点掉的那一阵不再出现，下一阵照常提醒。
- **快捷指令「播报天气」**：解除置灰，执行 = 说一句「现在 X 度，天气；未来两小时降水」。Key / Host 不齐时按能力探测置灰并写原因。

## 不接哪些问句（留给原来的联网问答）

分钟级只有 2 小时，所以：问别的城市（「北京会下雨吗」，常用城市除外）、问超出两小时的时段（今天 / 今晚 / 明天 / 周末 / 下午三点……，包括「今天天气怎么样」——日预报照旧走联网问答）、办事句（提醒 / 闹钟 / 记一下 / 买伞）都不接。分钟级不可用（没 Key / Host、`rain.off`）时所有问句都交回原流程；「天气怎么样」查失败也交回原流程。

## 解析口径（`RainAnalysis`，JVM 全测）

- `precip > 0` = 这 5 分钟有降水（用户定的阈值）；第 i 个点覆盖 `[fxTime, fxTime+5min)`；已整段过去的点丢掉，全过期 = 数据太旧。
- 状态：DRY（两小时无降水）/ STARTS（现在没下，X 分钟后开始）/ STOPS（现在在下，X 分钟后第一次停）/ CONTINUES（两小时都不停）；停了之后还会再下 = 「下下停停」。
- 分钟数 = 事件点起点 − 现在，向上取整。雨还是雪看第一个有降水的点的 `type`。强度：5 分钟量 ×12 折小时雨强，<2.5 小雨 / <8 中雨 / <16 大雨 / 以上暴雨（雪 <1 / <3 / 以上），只用来说话。
- 播报：先说自己算的大白话（直接回答问的那件事），再接和风 `summary`；summary 的第一个分钟数已在前一句里 = 重复，不念。岛上倒计时只用自己算的结果。

## 后台节奏与耗电

- 默认每 15 分钟；正在下 / 预计 30 分钟内变化 → 每 5 分钟；**息屏 → 30 分钟**（岛也看不见）；亮屏广播到达时立刻对一次。
- 失败退避：连续 n 次网络失败 → 5×2^(n−1) 分钟，封顶 60；没 Key / 定位 / 不在覆盖范围 / Key 被拒 → 直接 60 分钟。
- 唤醒走提醒中枢的共用闹钟（S3Scheduler，不另开闹钟 / 服务）；有提醒在岛上且亮屏时每分钟重算一次标题（纯本地，最多 60 分钟）。
- **定位**：只用粗定位、**不主动开 GPS**：① 已授权时取各 provider 最后已知位置（6 h 内）；② 可选：三星机型上系统天气里选中城市的经纬度（需要 root，只读复制库，缓存 12 h；其他机型跳过）。经纬度四舍五入两位小数（约 1 km）再发出；日志不打坐标 / Host / Key。

### 每月请求量估算（免费额度 5 万次 / 月）

| 场景 | 每天 | 每月（30 天） |
|---|---|---|
| 晴天：亮屏 16 h ×4 次 + 息屏 8 h ×2 次 | 80 | **2,400** |
| 天天下雨：亮屏 16 h ×12 次 + 息屏 ×2 次 | 208 | **6,240** |
| 极端：全天亮屏且一直下（24×12） | 288 | 8,640 |

语音提问 4 分钟内同一位置复用上一次结果；「天气怎么样」多 1 次实况请求。即使每天问 50 次也只 +1,500 / 月。控制台底部写着估算；`[RAIN] 拉取 … 本进程第 N 次` 日志可核对。

## 配置与密钥

| 项 | 放哪 | 默认（app/config） | 自定义（local-config） |
|---|---|---|---|
| API Host（`xxx.re.qweatherapi.com`，和风账号专属） | `local-config/…/LocalDefaults.QWEATHER_HOST`（未跟踪）；控制台可改（SecretPrefs 加密） | 空 → 置灰「缺和风天气 API Host」 | 已填 |
| 岛提醒出厂开关 | `LocalDefaults.RAIN_ALERTS_DEFAULT` | `false` | `true` |
| API Key（32 位） | **只在设备**：`bixby_rain/qweather_key`，SecretPrefs → Android Keystore 密文（`ks1:`），控制台「功能 › 分钟级降水 › 和风天气 API Key」填 | — | 需在设备上填 |

- Key、Host 都不进 git / 日志 / 报告。`RainWiringTest.privateHostNeverInTrackedTrees` 扫随仓库的源码 / 文档 / 夹具，发现 local-config 里的 Host 就失败；日志行静态检查不含 key/host/坐标。
- 请求头 `X-QW-Api-Key`；不手动加 `Accept-Encoding`，由 HttpURLConnection 透明解 gzip。
- 能力探测（`RainService.unavailableReason`）：`rain.off` → 已停用；没 Host → 缺 Host；没 Key → 缺 Key。不可用时控制台开关置灰并写原因。

## 测试注入（真机冒烟用）

`/data/local/tmp/bixby_rain_fake.json`（只有 shell / root 能写，App 按固定文件名只读）存在时，拉取直接用它（和风原样格式，可多带一个 `now` 对象），不联网、不需要 Key，日志标 `[RAIN] 测试注入`。**收尾必须删掉。**

## 代码地图

- 纯逻辑（JVM）：`rain/RainForecast`（解析）、`RainAnalysis`（状态 / 分钟数 / 柱状条）、`RainVoice`（问句分类 + 回答 + 失败话术）、`RainPolicy`（节奏、估算、岛提醒、『这一阵』去重）。
- Android：`rain/RainService`（配置 / 定位 / 拉取 / 缓存 / 注入）、`RainHooks`（语音接管、快捷指令、提醒来源 `Source`）、`kernel/plugins/RainPlugin`（插件、控制台）。
- 宿主改动：`IslandNotice.Source.WEATHER` + `bars`；`IslandView.rainBars`；`NoticeHooks.syncNow`；`HomeCityProvider.sysWeatherLatLon`；`UnifiedRouter.REMEMBER_PLUGINS += rain`；`ShortcutActions.weatherProbe`。
- 测试：`test-jvm/src/com/onesay/assistant/v2/rain/`（`RainMinutelyTest`、`RainWiringTest`），夹具 `test-jvm/fixtures/rain/`（`real-*` = 真实响应样本，去掉 Key / Host / 位置、fxLink 换首页；`synthetic-*` 由真实样本改 precip）。
