package com.onesay.assistant.v2.applog;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.util.LogText;

import org.json.JSONObject;

/**
 * [settings-diag-1124] 关键链路的日志点（纯 Java）。一处定口径：每条都以固定标签开头，导出后能 grep。
 *
 * <table>
 *   <tr><td>[CAP]</td><td>能力调用结果：签名、成功 / 失败码、有没有回读核对、参数只列键名</td></tr>
 *   <tr><td>[GATE]</td><td>门禁结论：放行 / 拦截 / 要确认（红黄卡）</td></tr>
 *   <tr><td>[CONFIRM]</td><td>确认卡：用户点了允许 / 拒绝或超时</td></tr>
 *   <tr><td>[PHONE]</td><td>替你操作每一步的轨迹（launch-mismatch / app-drift / 模型第几步…），模型输出只记种类和长度</td></tr>
 *   <tr><td>[VISION]</td><td>带图请求走哪条路、实际用的模型名</td></tr>
 *   <tr><td>[SESSION]</td><td>会话到期 / 清空（只记轮数）</td></tr>
 *   <tr><td>[SELFSET]</td><td>聊天里读 / 改 OneSay 自己的设置</td></tr>
 * </table>
 * 路由决策、AgentLoop 每步、[RETRY] / [CONTINUE] 这类行本来就走 SafeLog，SafeLog 会顺手写进同一个文件。
 */
public final class AppLogPoints {

    private AppLogPoints() {}

    public static void exec(Capability cap, JSONObject args, ExecutionResult r, boolean viaToken) {
        try {
            StringBuilder b = new StringBuilder("[CAP] ");
            b.append(cap == null ? "null" : cap.sign);
            if (r == null) {
                b.append(" → 没有结果");
            } else {
                b.append(r.ok ? " → 成功" : " → 失败 code=" + r.code);
                if (r.ok) b.append(r.verified ? "（已回读核对）" : "（没回读）");
            }
            if (viaToken) b.append(" 令牌");
            b.append(" 参数=").append(LogText.args(args));
            AppLog.append(r != null && !r.ok ? 'W' : 'I', "Exec", b.toString());
        } catch (Throwable ignored) {
        }
    }

    public static void gate(Capability cap, String decision, int tier, boolean tainted, String reason) {
        try {
            AppLog.i("Gate", "[GATE] " + (cap == null ? "null" : cap.sign) + " → " + decision
                    + (tier > 0 ? (tier == 2 ? " 红卡" : " 黄卡") : "") + (tainted ? " 读过外部内容" : "")
                    + (reason == null || reason.length() == 0 ? "" : " 原因=" + clip(reason, 80)));
        } catch (Throwable ignored) {
        }
    }

    public static void confirm(String sign, boolean approved) {
        try {
            AppLog.i("Gate", "[CONFIRM] " + sign + (approved ? " → 用户允许" : " → 拒绝 / 超时 / 收起"));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 替你操作的轨迹行。release 包：「model#N 种类 …模型输出」只留前两段（输出可能含屏幕文字 / 要输入的内容），其余短代码原样。
     */
    public static void phoneTrace(String line) {
        try {
            AppLog.i("PhoneAgent", "[PHONE] " + traceLine(line, com.onesay.assistant.v2.BuildVariant.IS_DEBUG));
        } catch (Throwable ignored) {
        }
    }

    static String traceLine(String line, boolean debug) {
        if (line == null) return "null";
        if (debug) return clip(line, 300);
        if (line.startsWith("model#")) {
            String[] p = line.split(" ", 4);
            StringBuilder b = new StringBuilder(p[0]);
            if (p.length > 1) b.append(' ').append(p[1]);
            if (p.length > 2 && p[2].startsWith("err=")) b.append(' ').append(clip(p[2], 60));
            b.append(" 输出").append(LogText.len(line));
            return b.toString();
        }
        // 其余是固定短代码 + 包名 / 编号（launch-mismatch com.x、app-drift com.y、budget …）
        return clip(line, 160);
    }

    public static void vision(String purpose, String model, String path) {
        try {
            AppLog.i("Vision", "[VISION] " + purpose + " → " + path + " 模型=" + model);
        } catch (Throwable ignored) {
        }
    }

    public static void session(String what, int turns) {
        try {
            AppLog.i("Session", "[SESSION] " + what + " 轮数=" + turns);
        } catch (Throwable ignored) {
        }
    }

    public static void selfset(String msg) {
        try {
            AppLog.i("SelfSet", "[SELFSET] " + msg);
        } catch (Throwable ignored) {
        }
    }

    static String clip(String s, int n) {
        if (s == null) return "null";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
