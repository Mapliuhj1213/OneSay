package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.ShellExecutor;

/**
 * 默认后端：经 root 守护执行填充好的 shell 命令（executor = shell，以及没被其它后端接走的 intent 类能力，
 * 它们的模板本来就是 {@code am start ...}）。必须注册在最后。
 */
public final class ShellBackend implements ExecutorBackend {

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        return true;
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        ShellExecutor.ShellResponse resp;
        ShellPrograms.Program prog = req.cap != null ? ShellPrograms.forSign(req.cap.sign) : null;
        if (prog != null) {
            // 2026-10-25 exec 守护 v4：带条件分支的模板改成多次单条命令（ShellPrograms），不再发复合脚本
            try {
                java.util.Map<String, String> slots = com.onesay.assistant.v2.executor.ShellCommand.slotValues(
                        req.args != null ? req.args : new org.json.JSONObject(), req.component, req.pkg);
                resp = prog.run(req.shell, slots);
            } catch (IllegalArgumentException e) {
                return BackendOutcome.terminal(ExecutionResult.failed(ErrorCode.SCHEMA_INVALID,
                        "参数不符合规范：" + e.getMessage(), e.getMessage(), req.cap, req.args));
            }
        } else {
            resp = req.shell.execute(req.filledCmd);
        }
        if (resp.isTimeout) {
            return BackendOutcome.terminal(ExecutionResult.failed(ErrorCode.CHANNEL_TIMEOUT,
                    "系统执行响应超时，请重试", resp.output, req.cap, req.args));
        }
        if (resp.isDenied) {
            // 2026-10-25 exec 守护 v4：rc=77 DENY —— 被守护按安全策略拒绝，不混进「命令失败」
            return BackendOutcome.terminal(ExecutionResult.failed(ErrorCode.DAEMON_DENIED,
                    userDenyMessage(req.cap, resp.denyReason), resp.output, req.cap, req.args));
        }
        if (resp.isDown) {
            return BackendOutcome.terminal(ExecutionResult.failed(ErrorCode.CHANNEL_DOWN,
                    "系统特权守护服务未启动", resp.output, req.cap, req.args));
        }
        if (resp.rc != 0) {
            return BackendOutcome.terminal(ExecutionResult.failed(classifyRc(resp),
                    "系统执行指令失败（返回码: " + resp.rc + "）", resp.output, req.cap, req.args));
        }
        return BackendOutcome.output(resp.output);
    }

    /**
     * ★ device-1102：守护拒绝时给用户的话——说清<b>哪一类</b>动作被拦、为什么、怎么开，而不是一句「被安全策略拦下了」。
     * 守护拒绝发生在 App 之外（root 白名单），确认卡放不开，所以这里不升级成卡，而是说明解法。
     */
    public static String userDenyMessage(Capability cap, String reason) {
        String what = actionKind(cap);
        String r = reason == null ? "" : reason;
        String why;
        String how;
        if (r.startsWith("MAC_") || r.startsWith("SIG_") || r.startsWith("UID_") || r.startsWith("NO_IDENTITY")
                || r.startsWith("REVOKED") || r.startsWith("PIN_") || r.startsWith("MULTI_CERT")) {
            why = "守护没认出 OneSay 的身份";
            how = "重启手机或重新授权 root 后再试";
        } else if (r.startsWith("METACHAR") || r.startsWith("RAW_DISABLED") || r.startsWith("TOO_MANY_STAGES")) {
            why = "这条命令是旧写法，新版守护不再执行";
            how = "更新 OneSay 和特权守护到同一版本后即可";
        } else {
            why = "这条命令不在守护的白名单里" + (r.length() > 0 ? "（" + r + "）" : "");
            how = "更新特权守护的白名单后即可";
        }
        return what + "被手机上的系统特权守护拦下了，没有执行：" + why + "。" + how + "。";
    }

    /** 能力 → 人话里的动作类别。 */
    static String actionKind(Capability cap) {
        String s = cap == null || cap.sign == null ? "" : cap.sign;
        if (s.contains("nav") || s.contains("taxi") || s.contains("map")) return "打开地图导航";
        if (s.startsWith("phone.call") || s.contains("dial")) return "打电话";
        if (s.startsWith("sms")) return "发短信";
        if (s.startsWith("app.")) return "打开 App";
        if (s.contains("alarm") || s.contains("timer")) return "设置闹钟";
        if (s.contains("browser") || s.contains("url") || s.contains("web")) return "打开网页";
        if (s.startsWith("connectivity.") || s.startsWith("settings.") || s.startsWith("volume.")) return "改系统设置";
        return "这一步";
    }

    /** 非零返回码细分：权限拒绝单独标出，其余仍为 EXEC_FAILED。 */
    static ErrorCode classifyRc(ShellExecutor.ShellResponse resp) {
        String o = resp.output == null ? "" : resp.output.toLowerCase();
        if (o.contains("permission denial") || o.contains("securityexception")
                || o.contains("permission denied")) {
            return ErrorCode.PERMISSION_DENIED;
        }
        return ErrorCode.EXEC_FAILED;
    }
}
