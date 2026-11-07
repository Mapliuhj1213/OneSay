# exec_daemon v4 —— 信任边界

需要 root。开源版不内置任何证书：在设备上用环境变量 `BIXBY_EXEC_SIG_REL` / `BIXBY_EXEC_SIG_DBG` 填你自己签名证书的 SHA-256，未设置时对应通道一律拒绝（`SIG_NOT_CONFIGURED`）。

## root-only 配置目录 `/data/adb/bixbyui_exec/`（700 root）
| 文件 | 作用 | 默认 |
|---|---|---|
| `key.<pkg>` | 每个包一把 HMAC 密钥（首次启动自动生成，600 root） | 自动 |
| `provision_key` | 存在 → 身份校验通过后把 `key.<pkg>` 交付到该包 `files/.bixby_key`（App uid、600） | 关 |
| `.req.* / .res.*` | 取件 / 结果暂存（用完即删） | 自动 |
| `exec.pid` | pidfile（原 `/data/local/tmp/.bixby_exec.pid`） | 自动 |
| `pins/<pkg>.uid` | 签名不可得时的 uid 固定 | 自动 |
| `nonce/` | 已用 nonce（120 s 内防重放） | 自动 |
| `require_mac` | 存在 → 无 MAC 请求一律拒 | **关**（App 侧 HMAC 落地后打开） |
| `allow_raw` | 存在 → `act=raw` 与过不了白名单的 legacy 复合脚本按 raw 执行（记 WARN） | **关** |
| `allow_any_setting` | 存在 → settings put 不限白名单键（黑名单键仍禁） | 关 |
| `strict_pin` | 存在 → 拿不到签名摘要时直接拒（不做首次信任） | 关 |

## 请求格式
- legacy（兼容）：`.bixby_req` = 一行命令。过白名单才执行；拒绝写回 `rc=77` + `DENY <原因>`。
- v4 结构化（推荐）：
```
#bixby-exec v4
ts=<unix 秒>
nonce=<8-64 位字母数字，一次性>
act=argv            # 或 raw（默认拒）
a=<argv0>
a=<argv1>
...
mac=<hex>           # = SHA256(key || SHA256(key || 除 mac 行外的全部内容，换行分隔，无末尾换行))
```
- 写入约定（ROOT-5）：App 先写 `.bixby_req.tmp` 再 `rename` 成 `.bixby_req`。

## 离线判定
`BIXBY_EXEC_DIR=/tmp/x BIXBY_EXEC_CHECK=1 sh exec_daemon.v4.sh '<命令>' [pkg]` → `ALLOW` / `DENY <原因>`，不执行。

## 回归
`bash tools/kdaemon/test_exec_daemon_v4.sh [dash|bash]`（需要 root/sudo 以模拟不同 uid）。


## v4.1 —— 让 `require_mac` 能开

| 缺口 | 守护侧做法 | 判定 / 拒绝码 |
|---|---|---|
| G1 固定模板走签名 | `act=tmpl` + 恰好一行 `a=<模板全文>`；或 `act=argv` 且 argv 以空格拼接逐字等于模板。MAC 先校验，再逐字匹配；执行守护自己的字面量 | `TMPL_UNKNOWN` / `TMPL_ARGC(n)`；日志 `tmpl=1 signed=1` |
| G2 识屏界面树 / 唤醒低优先级 | `uiautomator dump [--compressed] [本包路径]`；`nice -n 10` 只能包 `uiautomator dump` / `screencap`（到本包路径） | `UIAUTOMATOR_ARGC` / `UIAUTOMATOR_FLAG` / `NICE_ARGS` / `NICE_TARGET` / `NICE_MEDIA` |
| G3 写 `Pictures/` | 只许 `Pictures/OneSay[-Debug]/<≤2 级>/<图片>` 与固定 `Pictures/bixbyui[_debug]_shot.png`；`mkdir`/`cp`/`screencap` 由守护内建执行：逐级拒符号链接、中间目录与已存在目标属主须与 Pictures 根一致、设备号一致、先写不可预测临时名再 rename；`cp` 源只许本包可写前缀的普通文件 | `MEDIA_SUBDIR` / `MEDIA_PATH` / `MEDIA_DEPTH` / `MEDIA_EXT` / `MEDIA_COMPONENT` / `MEDIA_SRC_NOT_OWN` / `MEDIA_NO_PIPE` |
| G4 | `dumpsys nfc`、`dumpsys account`（不许带参数，可接受控过滤器） | `DUMPSYS_ARGC(svc)` |
| G7 控制台按键守护状态 | 固定模板（`ConsoleStatus.DAEMON_PROBE` 原文或 `bixby-status`），守护内建只读实现，只输出 `ALIVE\|DEAD` / `MOD\|NOMOD` / `hijack daemon - v<数字>` / `END` | — |
| G8 存图读别的 App 缓存 | **保持拦截**（含 allow_raw / act=raw） | `OTHER_APP_DATA(<包名>)`，日志带「v4 有意拦截」 |

