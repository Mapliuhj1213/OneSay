package com.onesay.assistant.v2.mikaplug;

import android.content.Context;

import com.onesay.assistant.plug.rootcmd.core.CommandPolicy;
import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.ShellRunner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * ★ mika-plug（mika.rootcmd，默认关）：AgentLoop 常驻工具 {@code run_command} —— root shell + 白名单（{@link CommandPolicy}）。
 * 只读命令直接跑；危险命令拒绝；其余要用户确认（有人值守时走 ask_user 式提问，无人值守一律不跑）。经 root 守护（ShellExecutor），
 * 守护自己的拒绝规则照旧生效。输出最多 4000 字。
 */
public final class RootCmd {
    private RootCmd() {
    }

    public static final String SIGN = "run_command";
    public static final int OUTPUT_CHARS = 4000;

    /** 有人值守时向用户确认（选项：执行 / 不执行）；返回用户选的，null = 没人回答。 */
    public interface Asker {
        String ask(String question, List<String> options, int timeoutSec);
    }

    public static boolean enabled(Context ctx) {
        return MikaPlugs.on(ctx, MikaPlugs.ROOTCMD);
    }

    public static void appendSchema(Context ctx, JSONArray tools) {
        if (tools == null || !enabled(ctx)) return;
        try {
            for (int i = 0; i < tools.length(); i++) {
                JSONObject f = tools.optJSONObject(i) == null ? null : tools.optJSONObject(i).optJSONObject("function");
                if (f != null && SIGN.equals(f.optString("name"))) return;
            }
            JSONObject p = new JSONObject().put("type", "object")
                    .put("properties", new JSONObject().put("command", new JSONObject().put("type", "string").put("description", "一行 shell 命令")))
                    .put("required", new JSONArray().put("command"));
            JSONObject fn = new JSONObject().put("name", SIGN)
                    .put("description", "在手机上用 root 跑一行 shell 命令（Android 8，toybox）。只读命令（getprop / dumpsys / ls / cat / pm list / settings get …）直接执行；"
                            + "删除、刷写、改系统设置、提权、远程脚本一律拒绝；其余命令要用户确认。能用专门的工具就别用它。输出最多 4000 字。")
                    .put("parameters", p);
            tools.put(new JSONObject().put("type", "function").put("function", fn));
        } catch (Exception ignored) {
        }
    }

    public static String handle(Context ctx, JSONObject args, Asker asker, ShellRunner shell) {
        String cmd = args == null ? "" : args.optString("command", "").trim();
        CommandPolicy.Decision d = CommandPolicy.classify(cmd);
        try {
            JSONObject o = new JSONObject().put("tool", SIGN).put("command", cmd).put("policy", d.verdict.name().toLowerCase());
            if (d.verdict == CommandPolicy.Verdict.DENY) return o.put("ok", false).put("code", "DENIED").put("note", "不执行：" + d.reason).toString();
            if (d.verdict == CommandPolicy.Verdict.CONFIRM) {
                String a = asker == null ? null : asker.ask("要用 root 执行这条命令吗？（" + d.reason + "）\n" + cmd, Arrays.asList("执行", "不执行"), 60);
                if (a == null || !a.trim().startsWith("执行"))
                    return o.put("ok", false).put("code", "NOT_CONFIRMED").put("note", asker == null ? "这条命令要用户确认，现在没人能确认，没执行。" : "用户没同意，没执行。").toString();
            }
            ShellRunner sh = shell != null ? shell : new ShellExecutor(ctx);
            ShellExecutor.ShellResponse r = sh.execute(cmd);
            if (r == null) return o.put("ok", false).put("code", "NO_SHELL").toString();
            o.put("ok", r.isSuccess()).put("exitCode", r.rc).put("timedOut", r.isTimeout);
            if (r.isDenied) o.put("code", "DAEMON_DENIED").put("note", r.denyReason == null ? "root 守护拒绝" : r.denyReason);
            if (r.isDown) o.put("code", "ROOT_DOWN");
            o.put("output", CommandPolicy.clipOutput(r.output, OUTPUT_CHARS));
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"run_command\",\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }
}
