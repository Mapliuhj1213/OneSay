# VYRSE 互通测试（headless）

用 **VYRSE 自己的电脑端桥接代码**（`desktop/src/main/phone-bridge.ts`，原文件不改，只把扩展名复制成 `.mts`）在 Node 里跑起来：
把 Electron / ws / Alexa / 类型模块换成最小桩（`stubs/`，由 `loader.mjs` 重定向），再由 `harness.mjs` 开一个只监听 127.0.0.1 的控制口，
负责：开配对码、往手机队列派命令、查状态、移除手机。随后 JVM 侧的 `VyrseInteropTest` 用 OneSay 的真实客户端代码
（`VyrsePairing` + `VyrseLink` + `VyrseHttp.LanSocket` + `VyrseDispatcher`，只把手机能力换成假的）去连 127.0.0.1:47630。

```
VYRSE_SRC=/path/to/vyrse bash tools/vyrse-interop/run.sh     # 需要 node ≥ 22.6（type stripping）、JDK、已编译的 test-jvm
```

桩只替换了 Electron 的 app / ipcMain / safeStorage（不落盘）、ws（WebSocket 转发不测）、Alexa、Hermes 后端（返回 null：`/api/*` 不测）。
`/phone/hello`、`/phone/pair`、`/phone/commands`、`/phone/command-result`、来源地址检查、配对码 2 分钟 / 5 次、key 哈希比对、
队列 / 长轮询 / 20 秒等结果，全部是 VYRSE 原代码在跑。
