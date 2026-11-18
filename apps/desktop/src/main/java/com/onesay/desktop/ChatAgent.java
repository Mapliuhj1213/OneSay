package com.onesay.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * [chat-agent-1118] 电脑端「对话」页的任务执行循环。
 *
 * <p>以前「对话」只把话交给大模型聊天（没有任何工具，系统提示还写着「你不能直接操作电脑」），
 * 所以「帮我下载 X 并装好」这类目标只会得到一份手动步骤。现在：模型带着工具（执行 PowerShell 命令、打开网址、写文件）一轮轮做，
 * 每一步都走和手机远程指令同一条安全链（{@link Agent#runLocal}：安全插件 → 两轴矩阵 / 白名单 → 电脑上的确认框 → 一次性凭证），
 * 只读查询直接做，改动类照旧弹确认框（只认本机鼠标 / 键盘点击）。
 *
 * <p>收工条件：模型不再调工具（给出答复）；用户在确认框里拒绝 / 没人确认（停下，不绕路重试）；同一步原样重复 {@link #MAX_REPEAT} 次；
 * 做满 {@link #MAX_STEPS} 步（提示说「继续」接着做）；用户点了停止。每一步写进对话（via=step），说「继续」时模型能看到做到哪了。
 */
public final class ChatAgent {
    /** 确认框 / 日志里显示的发起方。 */
    public static final String FROM = "本机对话";
    public static final int MAX_STEPS = 15;
    public static final int MAX_REPEAT = 3;
    public static final int OUTPUT_TO_MODEL = 6000;

    /** 模型：给消息和工具，回一轮。 */
    public interface Brain {
        Llm.Turn next(List<Map<String, Object>> messages, List<Map<String, Object>> tools) throws Exception;
    }

    /** 执行一个电脑能力（App 接到 {@link Agent#runLocal}）。 */
    public interface Exec {
        Actions.Result run(String cmd, Map<String, Object> args) throws Exception;
    }

    /** 把一步进展写进对话。 */
    public interface Steps {
        void add(String text);
    }

    public static final class Outcome {
        public final String text;
        /** done / denied / timeout / loop / max_steps / stopped / error。 */
        public final String status;
        public final int steps;

        Outcome(String text, String status, int steps) {
            this.text = text;
            this.status = status;
            this.steps = steps;
        }
    }

    public final AtomicBoolean cancel = new AtomicBoolean();
    /** 模型暂时失败（{@link Llm.Transient}：超时 / 429 / 5xx / 空回复）时最多重试几次；每次等 backoffMs × 次数。 */
    static final int RETRIES = 2;
    volatile long backoffMs = 1500;

    // ------------------------------------------------------------------ 工具定义

    static Map<String, Object> prop(String type, String desc) {
        Map<String, Object> m = new TreeMap<>();
        m.put("type", type);
        m.put("description", desc);
        return m;
    }

    static Map<String, Object> fn(String name, String desc, Map<String, Object> props, List<String> required) {
        Map<String, Object> params = new TreeMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", new ArrayList<Object>(required));
        Map<String, Object> f = new TreeMap<>();
        f.put("name", name);
        f.put("description", desc);
        f.put("parameters", params);
        Map<String, Object> t = new TreeMap<>();
        t.put("type", "function");
        t.put("function", f);
        return t;
    }

    public static List<Map<String, Object>> tools() {
        List<Map<String, Object>> l = new ArrayList<>();
        Map<String, Object> rc = new TreeMap<>();
        rc.put("command", prop("string", "一条 Windows PowerShell 5.1 命令（非交互，不能弹窗等输入）。可以用分号串多步。"));
        rc.put("timeout_sec", prop("integer", "最长等多少秒，默认 60，最多 900（下载大文件时调大）。"));
        l.add(fn("run_command", "在这台 Windows 电脑上执行 PowerShell 命令并返回输出（以当前用户权限，不提权）。只读查询直接执行；会改动电脑的命令 OneSay 会先弹确认框请用户点允许。", rc, List.of("command")));
        Map<String, Object> ou = new TreeMap<>();
        ou.put("url", prop("string", "要打开的 http(s) 网址"));
        l.add(fn("open_url", "用默认浏览器打开网址（给用户看页面时用；下载文件请用 run_command）。", ou, List.of("url")));
        Map<String, Object> wf = new TreeMap<>();
        wf.put("path", prop("string", "文件路径：相对路径写在 OneSay 工作区里；工作区以外要给完整路径（会先请用户确认）。"));
        wf.put("text", prop("string", "文件内容（UTF-8 文本，最多 256 KB）"));
        l.add(fn("write_file", "写一个文本文件。", wf, List.of("path", "text")));
        return l;
    }

    /** 模型工具名 → 电脑能力名 + 参数。认不出返回 null。 */
    static Object[] map(Llm.ToolCall c) {
        Map<String, Object> a;
        try {
            a = Json.obj(c.arguments);
        } catch (RuntimeException e) {
            a = null;
        }
        if (a == null) return null;
        Map<String, Object> out = new TreeMap<>();
        switch (c.name) {
            case "run_command": {
                Object cmd = a.get("command");
                if (cmd == null || String.valueOf(cmd).isBlank()) return null;
                out.put("command", String.valueOf(cmd));
                Object t = a.get("timeout_sec");
                if (t instanceof Number) out.put("timeout_sec", Math.max(5L, Math.min(900L, ((Number) t).longValue())));
                return new Object[] {"sys_exec", out};
            }
            case "open_url": {
                Object u = a.get("url");
                if (u == null || String.valueOf(u).isBlank()) return null;
                out.put("url", String.valueOf(u));
                return new Object[] {"open_url", out};
            }
            case "write_file": {
                Object p = a.get("path");
                if (p == null || String.valueOf(p).isBlank()) return null;
                out.put("path", String.valueOf(p));
                out.put("text", a.get("text") == null ? "" : String.valueOf(a.get("text")));
                return new Object[] {"file_write", out};
            }
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ 提示词

    /** 模型把活推回给用户（「我无法直接操作电脑」「需要你手动」）：第一轮这样说就确定性地打回一次。 */
    static final Pattern PUNT = Pattern.compile("(无法|不能|没法|没有办法)(直接)?(操作|控制|访问|在)(你的|您的|这台)?(电脑|计算机|系统)|需要你(自己)?手动|请你手动|你可以按(下面|以下|这个)(的)?(顺序|步骤)");

    static boolean punts(String s) {
        return s != null && PUNT.matcher(s).find();
    }

    /** 当前权限 / 审批策略写给模型的一句（从电脑档位来）。 */
    public static String policyLine(LinkPolicy.Mode mode) {
        LinkPolicy.Mode m = mode == null ? LinkPolicy.Mode.DEFAULT : mode;
        String base = "当前权限档：" + m.sandbox.zh + "，审批：" + m.approval.zh + "。";
        if (m.sandbox == LinkPolicy.Sandbox.READ_ONLY) return base + "这一档只能查询，改动类操作会被拒绝；需要改动时如实告诉用户可以在 OneSay 设置里调整权限档。";
        if (m.approval == LinkPolicy.Approval.NEVER) return base + "需要批准的操作会被直接拒绝、不会弹确认，不要反复申请；被拒时如实告诉用户。";
        return base + "要用户点头的步骤，OneSay 会在电脑上弹确认框交给用户决定，所以直接发起工具调用就行，不必先在回复里请示。"
                + "权限档只决定哪些步骤要用户点头，不是不做的理由：该做的步骤照常调用；真被挡下，看返回的原因再决定换个做法还是向用户说明。";
    }

    /** 工具清单（由注册的工具生成，提示词和实际能力不会对不上）。 */
    static String toolLines() {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> t : tools()) {
            Object f = t.get("function");
            if (!(f instanceof Map)) continue;
            b.append("- ").append(((Map<?, ?>) f).get("name")).append("：").append(((Map<?, ?>) f).get("description")).append('\n');
        }
        return b.toString();
    }

    public static String systemPrompt(String now, String workspace, String home, String memory) {
        return systemPrompt(now, workspace, home, memory, LinkPolicy.Mode.DEFAULT);
    }

    public static String systemPrompt(String now, String workspace, String home, String memory, LinkPolicy.Mode mode) {
        return "你是 OneSay，运行在用户这台 Windows 电脑上的助手（和用户手机上的 OneSay 共用记忆），能真正操作这台电脑。用简体中文回答，先给结论，简短直接。现在是 " + now + "。\n"
                + "可用工具（run_command 能做下载文件、安装 / 启动程序、查文件和设置、调用 adb 等命令行工具）：\n" + toolLines()
                + policyLine(mode) + "\n"
                + "用户给你一个目标时：自己规划，一步步调用工具把它做完，每步看结果再决定下一步；能由你完成的步骤不要推给用户手动做，也不要只给教程。"
                + "缺信息先自己用命令查（比如程序装在哪、版本号、文件在不在）；只有真正需要用户拍板的事（花钱、多个选择差别很大、要账号密码）才问。\n"
                + "安全：只读查询会直接执行；会改动电脑的命令（建 / 删文件、安装、联网下载、启动程序等）OneSay 会弹确认框，用户在电脑上点「允许」后才执行，"
                + "被拒绝或没人确认就停下说明，不要换个写法绕过。不要提权（runas / UAC），不要关闭或绕过杀毒软件、防火墙，不要改 OneSay 自己的设置，不要输出或保存密码和 Key。"
                + "只从官方或可信来源下载软件。\n"
                + "只读查询尽量写成单条简单命令（Get-ChildItem / Get-ItemProperty / Test-Path 之类，可以用 | 接 Select-Object、Where-Object，不用分号、括号、变量赋值），这样能直接执行、不打扰用户；会改动电脑的那一步可以顺带把验证写在同一条里，少弹一次确认。\n"
                + "环境：Windows PowerShell 5.1，非交互；单条命令默认最长 60 秒（run_command 的 timeout_sec 最多 900）；命令的工作目录是 OneSay 工作区 " + workspace
                + "；用户目录 " + home + "，桌面通常是 " + home + "\\Desktop。\n"
                + "用户说「继续」「接着做」时，根据上面对话里已经做过的步骤（▶ 开头的记录）接着做。做完后用一两句话汇报结果（做了什么、现在是什么状态）。"
                + (memory == null || memory.isBlank() ? "" : "\n\n关于用户的长期记忆（可能过时，以用户最新说法为准）：\n" + memory);
    }

    // ------------------------------------------------------------------ 循环

    public Outcome run(List<Map<String, Object>> context, Brain brain, Exec exec, Steps steps) {
        List<Map<String, Object>> msgs = new ArrayList<>(context);
        List<Map<String, Object>> tools = tools();
        int done = 0;
        boolean nudged = false;
        String lastKey = "";
        int repeat = 0;
        cancel.set(false);
        while (true) {
            if (cancel.get()) return new Outcome("已停止。" + (done > 0 ? "做到第 " + done + " 步，说「继续」可以接着做。" : ""), "stopped", done);
            Llm.Turn t;
            try {
                t = nextWithRetry(brain, msgs, tools);
            } catch (Exception e) {
                String m = e.getMessage() == null ? "大模型出错" : e.getMessage();
                return new Outcome(done > 0 ? m + "。已经做了 " + done + " 步，说「继续」接着做。" : m, "error", done);
            }
            if (t.calls.isEmpty()) {
                String text = t.content == null ? "" : t.content.trim();
                if (!nudged && done == 0 && punts(text)) {
                    // 第一轮就把活推给用户：打回一次（内部提示，不进对话）
                    nudged = true;
                    msgs.add(t.message);
                    msgs.add(Llm.msg("user", "（OneSay 内部提示）你能用工具直接在这台电脑上操作，不需要用户手动。请直接调用工具开始做；只有真正需要用户决定的事才问。"));
                    continue;
                }
                return new Outcome(text.isEmpty() ? "做完了。" : text, "done", done);
            }
            msgs.add(t.message);
            if (t.content != null && !t.content.isBlank()) steps.add(t.content.trim());
            for (Llm.ToolCall c : t.calls) {
                if (cancel.get()) {
                    msgs.add(toolMsg(c.id, "{\"ok\":false,\"text\":\"用户点了停止\"}"));
                    continue;
                }
                Object[] m = map(c);
                if (m == null) {
                    msgs.add(toolMsg(c.id, badArgs(c)));
                    continue;
                }
                String cmd = (String) m[0];
                @SuppressWarnings("unchecked") Map<String, Object> args = (Map<String, Object>) m[1];
                String key = cmd + "|" + Json.stringify(args);
                repeat = key.equals(lastKey) ? repeat + 1 : 1;
                lastKey = key;
                if (repeat >= MAX_REPEAT) {
                    String what = describe(cmd, args);
                    steps.add("▶ " + what + "\n✗ 同一步已经原样重复 " + repeat + " 次，停下");
                    return new Outcome("同一步（" + what + "）原样重复了 " + repeat + " 次还没进展，我先停下。你看一下情况，或者换个说法告诉我下一步怎么做。", "loop", done);
                }
                if (done >= MAX_STEPS) {
                    msgs.add(toolMsg(c.id, "{\"ok\":false,\"text\":\"步数到上限，没执行\"}"));
                    return new Outcome("已经做了 " + done + " 步还没做完，先停一下。说「继续」我接着做。", "max_steps", done);
                }
                Actions.Result r;
                try {
                    r = exec.run(cmd, args);
                } catch (Exception e) {
                    r = Actions.Result.fail(cmd + " 出错：" + e.getClass().getSimpleName());
                }
                if (r == null) r = Actions.Result.fail("没有结果");
                done++;
                String reason = r.data == null ? "" : String.valueOf(r.data.getOrDefault("reason", ""));
                steps.add(stepText(cmd, args, r));
                msgs.add(toolMsg(c.id, resultJson(r)));
                if (LinkPolicy.R_CONFIRM_DENIED.equals(reason))
                    return new Outcome("你在确认框里拒绝了这一步（" + describe(cmd, args) + "），我停在这里，没有换别的办法去做。要换个做法或继续，直接告诉我。", "denied", done);
                if (LinkPolicy.R_CONFIRM_TIMEOUT.equals(reason))
                    return new Outcome("确认框没人点（60 秒到了，或窗口被切走），这一步没执行：" + describe(cmd, args) + "。说「继续」我会重新请求，到时在 OneSay 窗口里点「允许」。", "timeout", done);
            }
        }
    }

    /** 只重试暂时性的失败（超时 / 连不上 / 429 / 5xx / 空回复），Key 错、余额不足、400 这类直接报。 */
    Llm.Turn nextWithRetry(Brain brain, List<Map<String, Object>> msgs, List<Map<String, Object>> tools) throws Exception {
        for (int i = 0; ; i++) {
            try {
                return brain.next(msgs, tools);
            } catch (Llm.Transient e) {
                if (i >= RETRIES || cancel.get()) throw e;
                try { Thread.sleep(backoffMs * (i + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    /** 参数有问题时回给模型的说明（让它改了再调，不中断任务）。 */
    static String badArgs(Llm.ToolCall c) {
        String raw = clip(c.arguments, 300);
        boolean known = c.name.equals("run_command") || c.name.equals("open_url") || c.name.equals("write_file");
        if (!known) return Json.stringify(Map.of("ok", false, "error", "INVALID_TOOL", "text", "不认识的工具：" + c.name + "。可用的工具：run_command、open_url、write_file"));
        Map<String, Object> a;
        try {
            a = Json.obj(c.arguments);
        } catch (RuntimeException e) {
            a = null;
        }
        if (a == null) return Json.stringify(Map.of("ok", false, "error", "INVALID_ARGS", "text", "参数不是合法 JSON，请改正后重试：" + raw));
        String need = c.name.equals("run_command") ? "command" : c.name.equals("open_url") ? "url" : "path";
        return Json.stringify(Map.of("ok", false, "error", "INVALID_ARGS", "text", "参数无效：缺少或为空的字段 " + need + "，请改正后重试：" + raw));
    }

    static Map<String, Object> toolMsg(String id, String content) {
        Map<String, Object> m = new TreeMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", id == null ? "" : id);
        m.put("content", content);
        return m;
    }

    static String describe(String cmd, Map<String, Object> a) {
        switch (cmd) {
            case "sys_exec": return "执行命令：" + clip(LinkPolicy.oneLine(String.valueOf(a.get("command"))), 200);
            case "open_url": return "打开网址：" + clip(String.valueOf(a.get("url")), 200);
            case "file_write": return "写文件：" + clip(String.valueOf(a.get("path")), 200);
            default: return cmd;
        }
    }

    static String stepText(String cmd, Map<String, Object> a, Actions.Result r) {
        StringBuilder b = new StringBuilder("▶ ").append(describe(cmd, a)).append('\n');
        b.append(r.ok ? "✓ " : "✗ ").append(clip(r.text == null ? "" : r.text, 200));
        Object out = r.data == null ? null : r.data.get("output");
        if (out != null && !String.valueOf(out).isBlank()) b.append("\n").append(clip(String.valueOf(out).trim(), 400));
        return b.toString();
    }

    static String resultJson(Actions.Result r) {
        Map<String, Object> m = new TreeMap<>();
        m.put("ok", r.ok);
        m.put("text", r.text == null ? "" : r.text);
        if (r.data != null) {
            for (String k : new String[] {"output", "exit", "reason", "reason_zh", "path"}) {
                Object v = r.data.get(k);
                if (v == null) continue;
                m.put(k, "output".equals(k) ? clip(String.valueOf(v), OUTPUT_TO_MODEL) : v);
            }
        }
        return Json.stringify(m);
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…（截断）";
    }
}
