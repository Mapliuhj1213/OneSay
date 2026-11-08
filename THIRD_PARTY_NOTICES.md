# 第三方组件与参考（OneSay 电脑端）

## 随包分发
| 组件 | 位置 | 许可 | 说明 |
|---|---|---|---|
| qrcode-generator（Kazuhiko Arase） | `src/main/resources/onesay/ui/qr.js` | MIT | 设置页二维码 |
| Eclipse Temurin 17 运行时（jlink 精简） | Windows 包 `runtime\` | GPLv2 + Classpath Exception | 许可见 `runtime\legal`；不改动 |

## 设计参考（没有拷贝代码）
| 项目 | 许可 | 参考了什么 | 用在哪 |
|---|---|---|---|
| LocalSend（https://github.com/localsend/localsend） | Apache-2.0 | 组播发现（224.0.0.0/24 组，因为部分安卓只收这一段）、上线 announce 后对方立刻回应、每台设备自签证书并用证书 SHA-256 指纹识别设备 | `LanServer`（组播 / announce）、`Tls` |
| LocalSend 协议文档（https://github.com/localsend/protocol） | **无许可证** | 只阅读，未拷贝任何文字或代码 | — |
| Home Assistant Companion for Android（https://github.com/home-assistant/android） | Apache-2.0 | 电脑→手机指令集的划分（command_screen_on / command_volume_level / command_launch_app / command_activity）、用闹钟音频流最大音量找手机 | `PhoneCmds`、`Intent.phone` |
| OpenAI Codex CLI（https://github.com/openai/codex） | Apache-2.0 | 沙箱档（read-only / workspace-write / danger-full-access）× 审批策略（untrusted / on-request / never）两轴与 workspace-write 沙箱（只许改工作区、.git 等只读、默认断网）的思路 | `LinkPolicy`、`PcGuard` |
| KDE Connect、Sefirah | GPL | 只在产品形态上参考（手机↔电脑互相下令、找手机），没有阅读或使用其代码 | — |

以上「设计参考」的实现全部是本项目自己写的 Java 代码。若将来拷贝或改写上述 Apache-2.0 项目的代码，须在文件头保留许可与改动声明，并在此登记。
