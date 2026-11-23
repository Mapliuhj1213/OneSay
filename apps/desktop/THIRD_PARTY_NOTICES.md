# 第三方组件与参考（OneSay 电脑端）

## 随包分发
| 组件 | 位置 | 许可 | 说明 |
|---|---|---|---|
| qrcode-generator（Kazuhiko Arase） | `src/main/resources/onesay/ui/qr.js` | MIT | 设置页二维码 |
| Eclipse Temurin 17 运行时（jlink 精简） | Windows 包 `runtime\` | GPLv2 + Classpath Exception | 许可见 `runtime\legal`；不改动 |
| Microsoft WebView2 SDK 1.0.4314.35（`Microsoft.Web.WebView2.Core.dll`、`Microsoft.Web.WebView2.WinForms.dll`、`WebView2Loader.dll`） | Windows 包安装目录 | BSD-3-Clause 式（Microsoft，原文见本文末尾） | OneSay.exe 用它在自己的进程里显示主窗口；网页引擎用的是 Windows 自带的 WebView2 运行时，不随包 |
| Lucide 图标（https://lucide.dev ，约 60 个 SVG 内联为 sprite） | `src/main/resources/onesay/ui/index.html` 开头的 `<svg>` sprite | ISC（© Lucide Icons and Contributors）；其中部分图标源自 Feather，MIT（© 2013-present Cole Bemis） | 界面线条图标；只用了 path 数据，未改动形状。原文见本文「Lucide 许可原文」 |
| Nord 配色（Sven Greb） | `src/main/resources/onesay/themes/nord.json` | MIT | 只取色值，映射到 OneSay 的 CSS 颜色变量 |
| Catppuccin Latte / Mocha 配色（Catppuccin） | `themes/catppuccin-latte.json`、`themes/catppuccin-mocha.json` | MIT | 同上 |
| Dracula 配色（Dracula Theme） | `themes/dracula.json` | MIT | 同上 |
| Solarized Light 配色（Ethan Schoonover） | `themes/solarized-light.json` | MIT | 同上 |
| [workflow-1120] quickjs4j 0.1.1（roastedroot） | 解进 jar（`io/roastedroot/quickjs4j/**`） | Apache-2.0 | 工作流代码节点 / JS 技能的沙箱；内含 QuickJS（MIT，© Fabrice Bellard、Charlie Gordon）与 Javy（Apache-2.0 WITH LLVM-exception，Bytecode Alliance）编译出的模块。版本与 SHA-256 锁在 `deps/java.lock`。许可原文见 jar 内 `onesay/third-party/java-licenses.txt` |
| [workflow-1120] Endive 1.1.0（runtime / wasi / wasm / log，Bytecode Alliance，原 Chicory） | 解进 jar（`run/endive/**`） | Apache-2.0 | 纯 Java 的 WebAssembly 运行时，quickjs4j 依赖 |
| [workflow-1120] Jackson 2.22（databind 2.22.3、core 2.22.3、annotations 2.22，FasterXML） | 解进 jar（`com/fasterxml/jackson/**`） | Apache-2.0 | quickjs4j 依赖；NOTICE 原文随 jar 在 `META-INF/onesay-third-party/` |
| [workflow-1120] 自由布局画布库 @flowgram.ai/free-layout-editor 1.0.16 及其插件（Bytedance） | `src/main/resources/onesay/ui/workflow.js`（esbuild 打成单文件） | MIT | 工作流页的画布（拖节点、连线、撤销）。界面上不显示它的名字、没有署名角标。打进包的全部 60 个 npm 包（React 18.3.1 MIT、inversify MIT、rxjs Apache-2.0、reflect-metadata Apache-2.0、@phosphor/* BSD-3-Clause、make-plural Unicode-DFS-2016、tslib 0BSD、styled-components MIT、lodash MIT 等）的名字、版本、许可原文见 `onesay/ui/workflow-licenses.txt`（构建时自动生成），清单见 `web/workflow/bundled-packages.json`；版本由 `web/workflow/package-lock.json` 锁定 |

## 运行时按需下载（不随安装包附带，由你在设置里点「安装」后从官方发布页下载到 %LOCALAPPDATA%\OneSay\tools）
| 组件 | 来源 | 许可 | 说明 |
|---|---|---|---|
| yt-dlp | https://github.com/yt-dlp/yt-dlp/releases（官方 SHA2-256SUMS 校验） | Unlicense | 链接下载的引擎，以独立子进程调用，参数由 OneSay 固定白名单拼装 |
| FFmpeg（FFmpeg-Builds 的 **LGPL** 静态构建，如 ffmpeg-n8.1-latest-win64-lgpl-8.1.zip） | https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases（官方 checksums.sha256 校验） | LGPL-3.0（构建时 --enable-version3，不含 GPL 组件） | yt-dlp 合并音视频 / 转 m4a、mp3 时调用；独立可执行文件，不与 OneSay 链接；许可原文随 zip 里的 LICENSE.txt 一起解到 tools\ffmpeg\ |
| scrcpy **v5.0.1** Windows 发行版（scrcpy-win64-v5.0.1.zip / scrcpy-winarm64-v5.0.1.zip） | https://github.com/Genymobile/scrcpy/releases（版本锁定；代码里写死官方 SHA-256，并与发布页 SHA256SUMS.txt 再核对一次） | Apache-2.0（Copyright (C) 2018 Genymobile；Copyright (C) 2018-2026 Romain Vimont） | 「手机投屏」：OneSay 以独立子进程启动 scrcpy.exe（参数由 OneSay 固定白名单拼装），不与 OneSay 链接；包里的 LICENSE.txt 一起解到 tools\scrcpy\。只在你在「手机投屏」页点「安装」后下载 |

只认文件名里带 `-lgpl-` 的静态构建；发布页上找不到 LGPL 构建时不自动安装（GPL 构建只能由你自己作为本机外部工具单独安装）。

### scrcpy 官方包里附带的其他组件（随 scrcpy 一起下载，OneSay 不改动）
| 文件 | 来源 | 许可 | 说明 |
|---|---|---|---|
| adb.exe、AdbWinApi.dll、AdbWinUsbApi.dll | Android SDK Platform-Tools r37.0.1（scrcpy 构建脚本 app/deps/adb_windows.sh 从 dl.google.com 取） | Android SDK 条款（**待核实**） | **TODO(adb-redistribution)**：platform-tools 的再分发条款和 Apache-2.0 不是一回事，发布前要核实；核实前**不要**把 adb 打进安装包或 release 配置，只走「用户点安装 → 从 scrcpy 官方发布页下载」这条路 |
| avcodec / avformat / avutil / swresample DLL | FFmpeg（scrcpy 自己的精简构建：app/deps/ffmpeg.sh，--disable-everything，未见 --enable-gpl） | LGPL-2.1+（按构建参数推断，**待核实**） | 只解码 H.264 / H.265 / AV1 等 |
| SDL3.dll | SDL | zlib | 窗口与输入 |
| libusb-1.0.dll | libusb | LGPL-2.1+ | scrcpy 的 OTG 模式用 |

scrcpy 仓库没有 NOTICE 文件（v5.0.1 标签下 /NOTICE 为 404），许可证原文见下。

## scrcpy 许可声明（Apache-2.0）
```
Copyright (C) 2018 Genymobile
Copyright (C) 2018-2026 Romain Vimont

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## 测试用、不随包分发
| 组件 | 许可 | 说明 |
|---|---|---|
| MCP 示例服务器 @modelcontextprotocol/server-everything 2.3.1（https://github.com/modelcontextprotocol/servers） | 见其仓库 LICENSE（package.json 写 SEE LICENSE IN LICENSE） | 只在开发 / 真机测试「MCP 连接器」时临时放到测试电脑上，**不打进 OneSay 安装包或 zip**；OneSay 以独立子进程（stdio）启动用户自己选的 MCP 服务器，不与其链接 |

## 设计参考（没有拷贝代码）
| 项目 | 许可 | 参考了什么 | 用在哪 |
|---|---|---|---|
| LocalSend（https://github.com/localsend/localsend） | Apache-2.0 | 组播发现（224.0.0.0/24 组，因为部分安卓只收这一段）、上线 announce 后对方立刻回应、每台设备自签证书并用证书 SHA-256 指纹识别设备 | `LanServer`（组播 / announce）、`Tls` |
| LocalSend 协议文档（https://github.com/localsend/protocol） | **无许可证** | 只阅读，未拷贝任何文字或代码 | — |
| Home Assistant Companion for Android（https://github.com/home-assistant/android） | Apache-2.0 | 电脑→手机指令集的划分（command_screen_on / command_volume_level / command_launch_app / command_activity）、用闹钟音频流最大音量找手机 | `PhoneCmds`、`Intent.phone` |
| OpenAI Codex CLI（https://github.com/openai/codex） | Apache-2.0 | 沙箱档（read-only / workspace-write / danger-full-access）× 审批策略（untrusted / on-request / never）两轴与 workspace-write 沙箱（只许改工作区、.git 等只读、默认断网）的思路 | `LinkPolicy`、`PcGuard` |
| omniget（https://github.com/OpenSelena/omniget） | GPL-3.0 | 只读思路：链接 → 预览（标题 / 时长 / 大小）→ 下载队列 → 进度；引擎（yt-dlp / FFmpeg）按需下载而不是打包。**没有拷贝或改写任何代码** | `Media`、`MediaArgs`、`MediaTools`、`MediaCookies`、手机端 `PcMedia` |
| KDE Connect、Sefirah | GPL | 只在产品形态上参考（手机↔电脑互相下令、找手机），没有阅读或使用其代码 | — |

以上「设计参考」的实现全部是本项目自己写的 Java 代码。若将来拷贝或改写上述 Apache-2.0 项目的代码，须在文件头保留许可与改动声明，并在此登记。

## Microsoft WebView2 SDK 许可原文
```
Copyright (C) Microsoft Corporation. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

   * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
   * The name of Microsoft Corporation, or the names of its contributors 
may not be used to endorse or promote products derived from this
software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Lucide 许可原文（含 Feather 部分）
```
ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

---

The following Lucide icons are derived from the Feather project:

airplay, alert-circle, alert-octagon, alert-triangle, aperture, arrow-down-circle, arrow-down-left, arrow-down-right, arrow-down, arrow-left-circle, arrow-left, arrow-right-circle, arrow-right, arrow-up-circle, arrow-up-left, arrow-up-right, arrow-up, at-sign, calendar, cast, check, chevron-down, chevron-left, chevron-right, chevron-up, chevrons-down, chevrons-left, chevrons-right, chevrons-up, circle, clipboard, clock, code, columns, command, compass, corner-down-left, corner-down-right, corner-left-down, corner-left-up, corner-right-down, corner-right-up, corner-up-left, corner-up-right, crosshair, database, divide-circle, divide-square, dollar-sign, download, external-link, feather, frown, hash, headphones, help-circle, info, italic, key, layout, life-buoy, link-2, link, loader, lock, log-in, log-out, maximize, meh, minimize, minimize-2, minus-circle, minus-square, minus, monitor, moon, more-horizontal, more-vertical, move, music, navigation-2, navigation, octagon, pause-circle, percent, plus-circle, plus-square, plus, power, radio, rss, search, server, share, shopping-bag, sidebar, smartphone, smile, square, table-2, tablet, target, terminal, trash-2, trash, triangle, tv, type, upload, x-circle, x-octagon, x-square, x, zoom-in, zoom-out

The MIT License (MIT) (for the icons listed above)

Copyright (c) 2013-present Cole Bemis

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

## Nord 许可原文
```
MIT License (MIT)

Copyright (c) 2016-present Sven Greb <development@svengreb.de> (https://www.svengreb.de)

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

## Catppuccin 许可原文
```
MIT License

Copyright (c) 2021 Catppuccin

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

## Dracula 许可原文
```
The MIT License (MIT)

Copyright (c) 2023 Dracula Theme

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

## Solarized 许可原文
```
Copyright (c) 2011 Ethan Schoonover

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

## [workflow-1120] 只借思路、没有搬代码
- 工作流节点图的外形（nodes / edges、节点 {id,type,meta.position,data}）沿用上面画布库自己的 JSON 格式；节点 data 的分法参考了 Coze Studio（Apache-2.0）的 `canvas.go`，只参考格式，没有复制代码。
- 「按参数声明生成节点」的思路参考 ComfyUI 的 INPUT_TYPES、n8n 社区节点的分发方式：只借思路，没有使用它们的代码（n8n 是 Sustainable Use License）。

