# 第三方组件（Third-party notices）

OneSay 自身以 Apache License 2.0 发布（见 `LICENSE`、`NOTICE`）。下列第三方部分各自遵循其许可证。

## 随仓库 / APK 分发

| 组件 | 位置 | 版本 / 来源 | 许可证 | 许可证文本 |
|---|---|---|---|---|
| sherpa-onnx（Java 层源码） | `apps/bixbyui/vendor/sherpa/java/com/k2fsa/sherpa/onnx/` | k2-fsa/sherpa-onnx，Copyright Xiaomi Corporation 及贡献者 | Apache-2.0 | `licenses/Apache-2.0.txt` |
| sherpa-onnx JNI（预编译） | `apps/bixbyui/vendor/sherpa/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` | 同上（二进制内版本字串含 1.12.x） | Apache-2.0 | `licenses/Apache-2.0.txt` |
| ONNX Runtime（预编译，随 sherpa-onnx 包） | `apps/bixbyui/vendor/sherpa/jniLibs/arm64-v8a/libonnxruntime.so` | microsoft/onnxruntime（二进制内版本字串含 1.20.x / 1.24.x），Copyright (c) Microsoft Corporation | MIT | 见下方 MIT 文本 |
| Jakarta Mail for Android | `apps/bixbyui/vendor/mail/android-mail-1.6.5.jar` | com.sun.mail:android-mail:1.6.5（Eclipse EE4J） | EPL-2.0 或 GPL-2.0 with Classpath Exception | `licenses/EPL-2.0-or-GPL-2.0-with-CPE.txt`（取自 jar 内 `META-INF/LICENSE.txt`） |
| ZXing core（扫码配对电脑） | `apps/bixbyui/libs/zxing-core-3.5.3.jar` | com.google.zxing:core:3.5.3，Copyright ZXing authors（未改动） | Apache-2.0 | `licenses/Apache-2.0.txt` |
| Shizuku API 13.1.5（可选插件「Shizuku 特权」，默认关） | `apps/bixbyui/libs/shizuku-api-13.1.5.jar` | dev.rikka.shizuku:api / provider / aidl / shared 13.1.5（Maven Central，四个 aar 里的 classes.jar 原样合并，未改动；aar SHA-1 见 `apps/bixbyui/libs/README-shizuku.md`），Copyright (c) 2021 RikkaW | MIT | `licenses/MIT-Shizuku-API.txt` |
| Mika（自带 Key 服务商层） | `apps/bixbyui/app/src/com/onesay/assistant/v2/llm/`（文件头有声明的 6 个文件） | https://github.com/wangzan101/mika @8d61573eaed7d36fa37093bab233c94a5e79dd4d，Copyright 2026 wangzan101；Kotlin → Java 7 改写 | Apache-2.0 | `NOTICE`、`licenses/Apache-2.0.txt` |
| Jakarta Activation for Android | `apps/bixbyui/vendor/mail/android-activation-1.6.5.jar` | com.sun.mail:android-activation:1.6.5 | EPL-2.0 或 GPL-2.0 with Classpath Exception（jar 内无独立 LICENSE，按上游项目） | 同上 |
| Mika（部分代码移植） | `apps/bixbyui/app/src/com/onesay/assistant/plug/{screen,action,guard,evidence,outbox,intent,inbox,outcome,appkb}/core`、`com/onesay/assistant/v2/a11y`、`app/assets/kb_app_*.md` | https://github.com/wangzan101/mika @ebb0e44，Copyright 2026 wangzan101；Kotlin → Java 7 改写，文件头有声明 | Apache-2.0 | `NOTICE`、`licenses/Apache-2.0.txt` |
| phongent（部分代码移植） | `apps/bixbyui/app/src/com/onesay/assistant/plug/phongent/core/Pg*.java` 等 | https://github.com/mylea1/phongent @0b57e35 | Apache-2.0 | `NOTICE`、`licenses/Apache-2.0.txt` |
| mermaid | `apps/bixbyui/app/assets/richout_mermaid.min.js` | 9.1.7，Copyright (c) 2014-2022 Knut Sveidqvist | MIT | `apps/bixbyui/app/assets/richout_LICENSES.txt` |
| Chart.js | `apps/bixbyui/app/assets/richout_chart.umd.js` | 3.9.1，Copyright (c) 2014-2022 Chart.js Contributors | MIT | 同上 |
| Poppins Bold（仅图标字形） | `apps/*/app/res/mipmap-*/ic_launcher*.png`、`apps/*/app/res/drawable-*/ic_launcher_fg*.png`（「One」桌面图标，字形渲染为位图；仓库与 APK 都不含字体文件） | Indian Type Foundry / Jonny Pinhorn | SIL OFL 1.1（不限制用其渲染出的图像） | https://openfontlicense.org |

## 设计参考（社区借鉴 2026-11-12 · 助手，未拷贝代码）
| 项目 | 许可 | 参考了什么 | 用在哪 |
|---|---|---|---|
| Agent-VScreen（github.com/kilolonion/agent-vscreen） | MIT | 每步回执 ok / code / next、STALE_ELEMENT、until 等待 + 等待期间画面变化时间轴、边框颜色表示谁在控屏（参考实现思路，代码自写） | `phone/StepReceipt`、`PhoneAgent.waitUntil`、`exec/ControlColor` |
| OpenPhoneUse（github.com/joungminsung/OpenPhoneUse） | MIT | 模型自标风险、关键词只能加严、读过外部文字后发送一律确认、读不回回报「已请求」（未采用「点名单个删除免确认」） | `phone/RiskLabel`、`executor/ToolGate` |
| 涛声 TaoSheng（github.com/0xCoach/TaoSheng） | MIT | 主模型出错换备用模型、说「发了」勾待办 | `net/LlmFallback`、`net/BackupModelConfig`、`planner/TodoVoiceDone`、`planner/TodoVoiceFlow` |
| android-remote-control-mcp（github.com/danielealbano/android-remote-control-mcp） | GPL-3.0-or-later | **思路来源**：只读了说明文档（Privacy Mode：个人信息换稳定占位符、输入占位符时本地换回），没有阅读或使用任何代码 | `privacy/Redactor`、`privacy/RedactingLlm` |

## 设计参考（link-v2 电脑联动 v2，未拷贝代码）
| 项目 | 许可 | 参考了什么 | 用在哪 |
|---|---|---|---|
| LocalSend（github.com/localsend/localsend） | Apache-2.0 | 组播发现（224.0.0.0/24）、announce 后立刻回应、自签证书 + SHA-256 指纹识别设备 | `pclink/PcTls`、`PcRx`、`PcLanClient` |
| LocalSend 协议文档（github.com/localsend/protocol） | 无许可证 | 只阅读，未拷贝 | — |
| OpenAI Codex CLI（github.com/openai/codex） | Apache-2.0 | 沙箱档 × 审批策略两轴与 workspace-write 沙箱的思路（手机端现已不分档，只在电脑端用） | `pclink/LinkPolicy`、`PcRx`（执行级别） |
| Home Assistant Companion for Android（github.com/home-assistant/android） | Apache-2.0 | 电脑→手机指令集（亮屏 / 音量 / 打开 App / 打开网址）、闹钟流最大音量找手机 | `pclink/PcRxPolicy`、`PcRxAndroid` |
| omniget（github.com/OpenSelena/omniget） | GPL-3.0 | 只读思路：链接 → 预览 → 下载队列 → 进度、引擎按需下载（思路参考 omniget，代码自写，没有拷贝或改写任何代码）；手机端只发链接给电脑，不跑 yt-dlp | `pclink/PcMedia`、`PcMediaAndroid`、`PcShareActivity` |
| KDE Connect、Sefirah | GPL | 只参考产品形态，没有阅读或使用代码 | — |

## 只在测试时使用

| 组件 | 位置 | 许可证 |
|---|---|---|
| org.json（JSON-java） | `apps/clock/test/lib/json.jar`（20231013） | Public Domain |
| JUnit 4.13.2 / Hamcrest 1.3 | CI 从 Maven Central 下载，不进仓库 | EPL-1.0 / BSD-3-Clause |
| android-json 0.0.20131108.vaadin1 | 同上 | Apache-2.0 |
| ICU4J 72.1 | 同上 | Unicode / ICU |

## 不随仓库分发

| 资源 | 说明 |
|---|---|
| 唤醒词模型 | `sherpa-onnx-kws-zipformer-*`，由 `apps/bixbyui/tools/fetch_kws_model.sh` 在本地下载；模型权重许可在上游未明确，所以仓库与发布的 APK 都不带。 |
| 云端语音 | 所有语音都由用户自己配置的云端服务现合成，仓库与 APK 不含任何预录 / 预合成语音。 |

## MIT License（ONNX Runtime）

```
MIT License

Copyright (c) Microsoft Corporation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 商标

「Samsung」「Galaxy」「Bixby」是三星电子的商标。OneSay 与三星无任何关联。
