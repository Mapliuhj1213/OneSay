# 朗读表（Phrase table）

OneSay Clock 闹钟播报里**所有固定说法**都来自一个数据文件：

```
app/assets/phrases/zh-CN.json
```

代码（`core/Bank.java`、`core/Template.java`、`core/RainLine.java`）只负责「按什么顺序拼哪些件」，不再写死任何文案。改说法、换语言，只改这个文件。

## 文件结构

```jsonc
{
  "lang": "zh-CN",          // 语言标记（目前只读 zh-CN.json）
  "format": 1,              // 文件格式版本；结构变了才加
  "rates": { "normal": 0.9, "news": 0.8 },   // 语速档：件里写 "rate": "normal" | "news"
  "weather": [ { "id": "xiaoyu", "word": "小雨" }, ... ],      // 天气键 → 天气词
  "newsSources": [ { "id": "toutiao", "name": "今日头条" }, ... ], // 新闻源键 → 播报名
  "rain": { ... },          // 降雨提醒句式（见下）
  "pieces": [ { "key": "greet", "text": "嘿，你好，", "rate": "normal", "group": "fixed", "note": "开场问候" }, ... ]
}
```

### pieces：固定件（共 278 件）

每个件是一段**单独合成、单独缓存**的语音。字段：

| 字段 | 含义 |
|---|---|
| `key` | 件的键，代码按键取件（不要改键名，只改 `text`） |
| `text` | 实际念出来的文字（含标点，标点影响停顿语气） |
| `rate` | `normal` 或 `news`，对应 `rates` 里的语速 |
| `group` | 分组，仅供阅读：`fixed` / `hour` / `minute` / `tempC` / `tempD` / `weather` / `newsLead` |
| `note` | 说明（可选） |

| 键 | 组 | 什么时候念 |
|---|---|---|
| `greet` | fixed | 开场「嘿，你好，」 |
| `timel` | fixed | 「现在是本地时间」，后接时、分 |
| `h00`–`h23` | hour | 小时读法（`h02` = 「两点」） |
| `m00`–`m59` | minute | 分钟读法（`m00` = 「整，」，`m05` = 「零五分，」） |
| `tempNow` | fixed | 「室外温度为」，后接 `tc{温度}` |
| `tc-30`–`tc50` | tempC | 「X摄氏度，」（当前温度） |
| `tempLo` / `tempHi` | fixed | 「最低 / 最高温度为」，后接 `td{温度}` |
| `td-30`–`td50` | tempD | 「X度，」（最低 / 最高温） |
| `wx_{天气键}` | weather | 天气句，如 `wx_xiaoyu` = 「今天是小雨天气。」 |
| `x_umbrella` | fixed | 带伞提醒（设置开关，默认关；有降雨提醒句时不说） |
| `x_birthday` | fixed | 生日句（设置开关，默认关） |
| `lead_{新闻源键}` | newsLead | 新闻引导「我们来看看由X提供的消息吧。」 |
| `nonews` | fixed | 没取到新闻时的过渡句（设置开关，默认不说） |
| `closer` / `bless` | fixed | 收尾两句 |

温度超出 −30~50 ℃ 时不念温度句（没有对应件）。

### rain：降雨提醒句式

降雨提醒（和风天气分钟级 + 逐小时，需要和风 Key）是**整句现合成**的变量件，句式在这里：

| 键 | 占位符 | 例 |
|---|---|---|
| `now` | `{what}` `{tail}` | 现在正在下雨，出门记得带伞。 |
| `soon` | `{minutes}` `{intensity}` `{tail}` | 大约15分钟后有小雨，出门记得带伞。 |
| `later` | `{partOfDay}` `{what}` `{tail}` | 今天下午可能有雨，出门记得带伞。 |
| `tail` | — | 句尾「出门记得带伞。」 |
| `what.rain` / `what.snow` | — | 「雨」/「雪」 |
| `intensity.rain` / `.snow` | 3 项：小 / 中 / 大 | 按 5 分钟最大降水量换算 |
| `partOfDay` | 6 项 | 0–5 凌晨 / 6–10 上午 / 11–12 中午 / 13–16 下午 / 17–18 傍晚 / 19–23 晚上 |

`{minutes}` 是阿拉伯数字（向上取到 5 的倍数），由合成服务读出。

## 怎么拼

`Template.pieces()` 按下面的顺序挑件，件与件之间插入停顿（`core/Pauses.java`，毫秒）：

```
greet · timel · h{时} · m{分}
[tempNow · tc{温度} [· tempLo · td{低} · tempHi · td{高}]]      ← 有天气且在范围内
[wx_{天气键}]                                                    ← 认得这个天气
[降雨提醒句]  或  [x_umbrella]                                    ← 二选一
[x_birthday]
[lead_{源} · 新闻标题 ×5]  或  [nonews]  或  （跳过）
closer · bless
```

新闻标题和降雨句是**变量件**：按文本现合成并缓存（`files/news/`）。其余都是朗读表固定件（`files/phrases/{key}-{文本指纹}.wav`）。

## 语音与缓存

- 开源版不随包带任何预录语音。所有件都用你在「API 密钥」面板里配置的云端语音（服务地址 + Key + 音色）合成。
- **没有 Key 或没选音色 = 不出声**（没有系统 TTS 兜底）。
- 面板里的「**预合成朗读表**」一次把 278 个固定件合成到本机缓存（第一次选好音色时会主动问一次）。合成完以后，闹钟播报的固定部分完全不用联网。
- 运行时缺哪个件，就按需现合成一次并缓存。
- **服务地址 / 模型 / 音色任一变化**，旧缓存全部作废（新闻件缓存也一起作废），需要重新预合成。
- 缓存文件名带「文本 + 语速」指纹：改了某个件的 `text`，只有这个件会重新合成。

## 改说法

1. 只改 `text`，别改 `key`。
2. 保留句末标点习惯（逗号 = 后面还有，句号 = 一句结束），停顿表是按这个调的。
3. 跑 `bash test/run_linux.sh`：测试会核对件数、键和数字读法。
   如果有意改了数字读法（例如「十三点」改「下午一点」），同步改 `test/.../CoreTest.java` 里的读法断言（它拿 `core/ZhNum.java` 比对）。

## 加一种语言

1. 复制 `zh-CN.json` 为 `<语言>.json`，逐条翻译 `text`、`weather[].word`、`newsSources[].name` 和 `rain` 里的句式；件的键与数量保持不变（时 24、分 60、温度 −30~50 各 81、天气 18）。
2. 小时 / 分钟 / 温度是**逐个列出**的件，直接写目标语言的读法即可，不需要写数字转换代码。
3. 目前 `App` 固定加载 `Bank.ASSET`（`phrases/zh-CN.json`）。要按系统语言切换，在 `App.onCreate` 里按 `Locale` 选文件名再调 `Bank.install(...)`。
4. 选一个支持该语言的音色；语速档 `rates` 可按语言调整。
