# 参与 OneSay

**最有用的是能复现的问题报告。** PR 欢迎，但不是期待——一个写清楚的 Issue 往往比代码更省时间。

## 提 Issue

用模板（不能开空白 Issue）。必填：App 与版本、机型、Android 版本、ROM、配置了哪些服务（只勾选，**不要贴 Key**）、复现步骤、期望与实际结果。
功能建议请先写「要解决什么问题」，再写想法。

## 怎么安全地取日志

1. 电脑装好 adb，手机打开 USB 调试。
2. 清空旧日志，复现一次问题，然后导出：
   ```bash
   adb logcat -c
   # 在手机上复现问题
   adb logcat -d -v time > onesay.log
   ```
3. 只保留相关部分（OneSay 的标签多以 `[` 开头，例如 `[TTS]` `[KEYS]` `[ASR]`；Clock 的日志以 `NEWS` `WX` `PHRASE` 等开头）。
4. **贴出前检查并打码**：任何 Key / Token / `Authorization` / `Bearer`、手机号、邮箱、地址、经纬度、联系人和消息内容。OneSay 自己只记录 Key 的长度，但别的 App 的日志可能会混进来。
5. 截图同样先打码。

## 提 PR

- 三个 App 的 JVM 测试都要过（命令见 README「构建」）。
- 不要提交 keystore、口令、`local-config/`、设备日志或任何个人数据（`.gitignore` 已排除常见文件）。
- 新增需要 Key 的服务：Key 走 Keystore 加密存储，只在「API 密钥」面板出现；请求只发 https / wss，不跟随重定向。
- 厂商专属代码放进 `vendor/<厂商>/` 适配包，默认关闭，通用路径不能依赖它。

## 安全问题

Key 泄露、越权调用等安全问题请不要公开提交 Issue，见 `SECURITY.md`。
