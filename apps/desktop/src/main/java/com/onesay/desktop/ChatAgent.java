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
 * <p>[approvals-1118] 任务是最小单位，没有步数上限：做到模型给出答复为止；用户在确认框里拒绝 / 没人确认（停下，不绕路重试）；
 * 同一步原样重复 {@link #MAX_REPEAT} 次、或连续 {@link #MAX_FAILS} 步失败（没有进展）；用户点了停止 / 说「停」；
 * 兜底的总时长上限（设置里可改，默认 {@link #DEFAULT_TIMEOUT_MIN} 分钟）。每一步带一句给人看的说明（工具参数 description），写进任务卡。
 * 任务进行中用户发来的话进 {@link #inbox}，下一步之前交给模型（不会丢）。
 */
public final class ChatAgent {
    /** 确认框 / 日志里显示的发起方。 */
    public static final String FROM = "本机对话";
    public static final int MAX_REPEAT = 3;
    /** 连续这么多步失败（不含被拒）就当没有进展，停下。 */
    public static final int MAX_FAILS = 5;
    public static final int DEFAULT_TIMEOUT_MIN = 120;
    /** 总时长上限（毫秒）；Chat 按设置填。 */
    public volatile long timeoutMs = DEFAULT_TIMEOUT_MIN * 60_000L;
    /** 任务进行中用户发来的话（下一步之前交给模型）。 */
    public final java.util.concurrent.ConcurrentLinkedQueue<String> inbox = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 交给模型后回调（界面把「已收到，下一步处理」改成已处理）。 */
    public volatile java.util.function.Consumer<String> delivered = x -> { };
    /** 当前权限预设（变了就在下一步之前告诉模型）。 */
    public volatile java.util.function.Supplier<ChatPolicy.Preset> preset = () -> ChatPolicy.Preset.DEFAULT;
    /** 上次告诉模型的预设（系统提示里写的那个）。 */
    volatile ChatPolicy.Preset toldPreset;
    /** 时钟（测试换）。 */
    volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
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

        /**
         * [approvals-1118] 结构化的一步（任务卡用）：phase = start / done / note；desc 给人看的一句；tool、detail（命令 / 网址 / 路径）；
         * done 时 ok、summary（一行结论）、output（原始输出，界面折叠在「详情」里）。默认退回成一行文字。
         */
        default void step(Map<String, Object> ev) {
            if ("done".equals(ev.get("phase"))) add(String.valueOf(ev.getOrDefault("text", "")));
            else if ("note".equals(ev.get("phase"))) add(String.valueOf(ev.getOrDefault("desc", "")));
        }
    }

    /** 停止的说法（任务进行中发来就停）。 */
    static boolean stopWord(String s) {
        return s != null && s.trim().matches("(?s)[\\s,，。.!！]*(停|停止|停下|别做了|不要做了|取消|stop)[\\s,，。.!！]*");
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

    static final String DESC = "这一步在做什么：给用户看的一句简短中文（10–20 字，如「下载 Mindustry 安装包」「查 MuMu 装在哪」）";

    public static List<Map<String, Object>> tools() {
        List<Map<String, Object>> l = new ArrayList<>();
        Map<String, Object> rc = new TreeMap<>();
        rc.put("description", prop("string", DESC));
        rc.put("command", prop("string", "一条 Windows PowerShell 5.1 命令（非交互，不能弹窗等输入）。可以用分号串多步。"));
        rc.put("timeout_sec", prop("integer", "最长等多少秒，默认 60，最多 900（下载大文件时调大）。"));
        l.add(fn("run_command", "在这台 Windows 电脑上执行 PowerShell 命令并返回输出（以当前用户权限，不提权）。只读查询、在桌面 / 下载 / 文档 / 工作区里新建文件直接执行；其余按权限预设可能要用户批准一次。", rc, List.of("description", "command")));
        Map<String, Object> ou = new TreeMap<>();
        ou.put("description", prop("string", DESC));
        ou.put("url", prop("string", "要打开的 http(s) 网址"));
        l.add(fn("open_url", "用默认浏览器打开网址（给用户看页面时用；下载文件请用 run_command）。", ou, List.of("description", "url")));
        Map<String, Object> oa = new TreeMap<>();
        oa.put("description", prop("string", DESC));
        oa.put("name", prop("string", "程序名（开始菜单里的名字，如「MuMu模拟器」「哔哩哔哩」），或安装目录里 exe 的完整路径"));
        l.add(fn("open_app", "打开这台电脑上已安装的程序（不带参数）。", oa, List.of("description", "name")));
        Map<String, Object> wf = new TreeMap<>();
        wf.put("description", prop("string", DESC));
        wf.put("path", prop("string", "文件路径：相对路径写在 OneSay 工作区里；工作区以外要给完整路径（会先请用户确认）。"));
        wf.put("text", prop("string", "文件内容（UTF-8 文本，最多 256 KB）"));
        l.add(fn("write_file", "写一个文本文件。", wf, List.of("description", "path", "text")));
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
            case "open_app": {
                Object n = a.get("name");
                if (n == null || String.valueOf(n).isBlank()) return null;
                String v = String.valueOf(n).trim();
                if (v.matches("(?i)[a-z]:\\\\.*")) out.put("path", v); else out.put("name", v);
                return new Object[] {"open_app", out};
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
        return systemPrompt(now, workspace, home, memory, policyLine(mode));
    }

    /** [approvals-1118] 本机对话按权限预设写提示。 */
    public static String systemPrompt(String now, String workspace, String home, String memory, ChatPolicy.Preset preset) {
        return systemPrompt(now, workspace, home, memory, ChatPolicy.policyLine(preset));
    }

    static String systemPrompt(String now, String workspace, String home, String memory, String policy) {
        return "你是 OneSay，运行在用户这台 Windows 电脑上的助手（和用户手机上的 OneSay 共用记忆），能真正操作这台电脑。用简体中文回答，先给结论，简短直接。现在是 " + now + "。\n"
                + "可用工具（run_command 能做下载文件、安装 / 启动程序、查文件和设置、调用 adb 等命令行工具）：\n" + toolLines()
                + policy + "\n"
                + "每次调用工具都要填 description：用一句简短中文说这一步在做什么（给用户看的进度）。\n"
                + "用户给你一个目标时：自己规划，一步步调用工具把它做完，每步看结果再决定下一步；能由你完成的步骤不要推给用户手动做，也不要只给教程。"
                + "缺信息先自己用命令查（比如程序装在哪、版本号、文件在不在）；只有真正需要用户拍板的事（花钱、多个选择差别很大、要账号密码）才问。\n"
                + "安全：要批准的步骤 OneSay 会在任务卡上请用户点（用户可以一次批准整个任务）；"
                + "被拒绝或没人确认就停下说明，不要换个写法绕过。不要提权（runas / UAC），不要关闭或绕过杀毒软件、防火墙，不要改 OneSay 自己的设置，不要输出或保存密码和 Key。"
                + "只从官方或可信来源下载软件。\n"
                + "只读查询写成简单命令（Get-* / Test-Path，可以 | 接 Select-Object、Where-Object、Sort-Object，不要变量赋值）；写文件、下载用字面的完整路径（不要用变量拼路径），放在桌面 / 下载 / 文档 / 工作区里，这样能直接执行、少打扰用户。"
                + "打开网页用 open_url，打开已装的程序用 open_app。\n"
                + "环境：Windows PowerShell 5.1，非交互；单条命令默认最长 60 秒（run_command 的 timeout_sec 最多 900）；命令的工作目录是 OneSay 工作区 " + workspace
                + "；用户目录 " + home + "，桌面通常是 " + home + "\\Desktop。\n"
                + "用户说「继续」「接着做」时，根据上面对话里已经做过的步骤（▶ 开头的记录）接着做。做完后用一两句话汇报结果（做了什么、现在是什么状态）。"
                + (memory == null || memory.isBlank() ? "" : "\n\n关于用户的长期记忆（可能过时，以用户最新说法为准）：\n" + memory);
    }

    // ------------------------------------------------------------------ 循环

    public Outcome run(List<Map<String, Object>> context, Brain brain, Exec exec, Steps steps) {
        List<Map<String, Object>> msgs = new ArrayList<>(context);
        List<Map<String, Object>> tools = tools();
        int done = 0, fails = 0;
        boolean nudged = false, fakeNudged = false;
        String lastKey = "";
        int repeat = 0;
        cancel.set(false);
        long t0 = clock.getAsLong();
        while (true) {
            // 任务进行中用户发来的话：下一步之前交给模型（「停」就停）
            for (String in; (in = inbox.poll()) != null; ) {
                try { delivered.accept(in); } catch (RuntimeException ignored) { }
                if (stopWord(in)) { cancel.set(true); continue; }
                msgs.add(Llm.msg("user", "（任务进行中用户发来）" + in + "\n（OneSay 内部提示：先看这句话：是补充 / 纠正就照着调整，是问题就简短回答后接着做，叫你停就停下汇报；是和当前任务无关的新任务，就先把当前任务收个尾并说明，然后再做新的。）"));
            }
            ChatPolicy.Preset now = preset.get();
            if (toldPreset != null && now != null && now != toldPreset) {
                msgs.add(Llm.msg("user", ChatPolicy.changedNote(toldPreset, now)));
                steps.step(Map.of("phase", "note", "desc", "权限预设改成了「" + now.zh + "」"));
            }
            if (now != null) toldPreset = now;
            if (cancel.get()) return new Outcome("已停止。" + (done > 0 ? "做了 " + done + " 步，说「继续」可以接着做。" : ""), "stopped", done);
            if (clock.getAsLong() - t0 > timeoutMs)
                return new Outcome("这个任务已经做了 " + ((clock.getAsLong() - t0) / 60000) + " 分钟，到了设置里的时长上限，先停下。说「继续」我接着做。", "time_limit", done);
            Llm.Turn t;
            try {
                t = nextWithRetry(brain, msgs, tools);
            } catch (Exception e) {
                String m = e.getMessage() == null ? "大模型出错" : e.getMessage();
                return new Outcome(done > 0 ? m + "。已经做了 " + done + " 步，说「继续」接着做。" : m, "error", done);
            }
            if (t.calls.isEmpty()) {
                String raw = t.content == null ? "" : t.content.trim();
                String text = stripFakeSteps(raw);
                if (!text.equals(raw) && !fakeNudged && done == 0) {
                    // [approvals-1118] 模型把「▶ 执行命令 … ✓ 完成」当文字写出来，其实一步没做：打回一次，叫它真的调工具（这段文字不进对话）
                    fakeNudged = true;
                    msgs.add(Llm.msg("assistant", text.isEmpty() ? "（略）" : text));
                    msgs.add(Llm.msg("user", "（OneSay 内部提示）你刚才在回复里写了执行步骤 / 结果的文字，但没有真正调用工具，那些步骤并没有执行。"
                            + "要做的步骤请直接调用工具；已经真实执行过的结果以工具返回为准。"));
                    continue;
                }
                if (!nudged && done == 0 && punts(text)) {
                    // 第一轮就把活推给用户：打回一次（内部提示，不进对话）
                    nudged = true;
                    msgs.add(t.message);
                    msgs.add(Llm.msg("user", "（OneSay 内部提示）你能用工具直接在这台电脑上操作，不需要用户手动。请直接调用工具开始做；只有真正需要用户决定的事才问。"));
                    continue;
                }
                if (!inbox.isEmpty()) { msgs.add(t.message); continue; } // 收工前用户又说了话：再给模型看一眼
                return new Outcome(text.isEmpty() ? "做完了。" : text, "done", done);
            }
            msgs.add(t.message);
            if (t.content != null && !t.content.isBlank()) steps.step(Map.of("phase", "note", "desc", clip(t.content.trim(), 300), "text", t.content.trim()));
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
                String desc = stepDesc(c, cmd, args);
                String key = cmd + "|" + Json.stringify(args);
                repeat = key.equals(lastKey) ? repeat + 1 : 1;
                lastKey = key;
                if (repeat >= MAX_REPEAT) {
                    String what = describe(cmd, args);
                    msgs.add(toolMsg(c.id, "{\"ok\":false,\"text\":\"同一步重复太多次，没执行\"}"));
                    steps.step(stepEv("done", done + 1, desc, cmd, args, false, "同一步原样重复了 " + repeat + " 次，停下", "", "▶ " + what + "\n✗ 同一步已经原样重复 " + repeat + " 次，停下"));
                    return new Outcome("同一步（" + desc + "）原样重复了 " + repeat + " 次还没进展，我先停下。你看一下情况，或者换个说法告诉我下一步怎么做。", "loop", done);
                }
                steps.step(stepEv("start", done + 1, desc, cmd, args, true, "", "", ""));
                Actions.Result r;
                try {
                    r = exec.run(cmd, args);
                } catch (Exception e) {
                    r = Actions.Result.fail(cmd + " 出错：" + e.getClass().getSimpleName());
                }
                if (r == null) r = Actions.Result.fail("没有结果");
                done++;
                String reason = r.data == null ? "" : String.valueOf(r.data.getOrDefault("reason", ""));
                Object out = r.data == null ? null : r.data.get("output");
                steps.step(stepEv("done", done, desc, cmd, args, r.ok, summary(r), out == null ? "" : String.valueOf(out), stepText(cmd, args, r)));
                msgs.add(toolMsg(c.id, resultJson(r)));
                if (LinkPolicy.R_CONFIRM_DENIED.equals(reason))
                    return new Outcome("你在确认框里拒绝了这一步（" + desc + "），我停在这里，没有换别的办法去做。要换个做法或继续，直接告诉我。", "denied", done);
                if (LinkPolicy.R_CONFIRM_TIMEOUT.equals(reason))
                    return new Outcome("批准没人点（60 秒到了，或窗口被切走），这一步没执行：" + desc + "。说「继续」我会重新请求，到时在任务卡上点「允许」。", "timeout", done);
                fails = r.ok ? 0 : fails + 1;
                if (fails >= MAX_FAILS)
                    return new Outcome("连续 " + fails + " 步都失败了（最后一步：" + desc + "：" + summary(r) + "），看起来没有进展，我先停下。你看一下情况，或者告诉我换个思路。", "no_progress", done);
            }
        }
    }

    /** 像 OneSay 步骤记录的行（▶ 执行命令… / ✓ … / ✗ …）：模型自己写的这种行一律不当真。 */
    static final Pattern FAKE_STEP = Pattern.compile("(?m)^\\s*(▶|✓|✗|☑|✔|✘)\\s*.*$|^\\s*(执行命令|打开网址|写文件|打开程序)：.*$");

    /** 去掉回复里模型伪造的步骤行；没有就原样返回。 */
    static String stripFakeSteps(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        if (!FAKE_STEP.matcher(s).find()) return s;
        return FAKE_STEP.matcher(s).replaceAll("").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** 这一步给人看的一句：模型给的 description（截短），没给就用动作本身。 */
    static String stepDesc(Llm.ToolCall c, String cmd, Map<String, Object> args) {
        try {
            Map<String, Object> a = Json.obj(c.arguments);
            Object d = a == null ? null : a.get("description");
            if (d != null && !String.valueOf(d).isBlank()) return clip(LinkPolicy.oneLine(String.valueOf(d).trim()), 60);
        } catch (RuntimeException ignored) { }
        return clip(describe(cmd, args), 60);
    }

    /** 一行结论：成功用结果文字，失败把原因压成一行。 */
    static String summary(Actions.Result r) {
        String t = r.text == null ? "" : LinkPolicy.oneLine(r.text);
        if (!r.ok && r.data != null && r.data.get("reason_zh") != null) t = t + "（" + r.data.get("reason_zh") + "）";
        if (!r.ok && r.data != null && r.data.get("output") != null) {
            String o = String.valueOf(r.data.get("output")).trim();
            String first = "";
            for (String line : o.split("\\R")) { if (!line.isBlank()) { first = line.trim(); break; } }
            if (!first.isEmpty()) t = t + "：" + first;
        }
        return clip(t, 160);
    }

    static Map<String, Object> stepEv(String phase, int n, String desc, String cmd, Map<String, Object> args, boolean ok, String summary, String output, String text) {
        Map<String, Object> m = new TreeMap<>();
        m.put("phase", phase);
        m.put("n", (long) n);
        m.put("desc", desc);
        m.put("tool", cmd);
        m.put("detail", describe(cmd, args));
        m.put("ok", ok);
        m.put("summary", summary);
        m.put("output", clip(output, 16000));
        m.put("text", text);
        return m;
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
        boolean known = c.name.equals("run_command") || c.name.equals("open_url") || c.name.equals("write_file") || c.name.equals("open_app");
        if (!known) return Json.stringify(Map.of("ok", false, "error", "INVALID_TOOL", "text", "不认识的工具：" + c.name + "。可用的工具：run_command、open_url、open_app、write_file"));
        Map<String, Object> a;
        try {
            a = Json.obj(c.arguments);
        } catch (RuntimeException e) {
            a = null;
        }
        if (a == null) return Json.stringify(Map.of("ok", false, "error", "INVALID_ARGS", "text", "参数不是合法 JSON，请改正后重试：" + raw));
        String need = c.name.equals("run_command") ? "command" : c.name.equals("open_url") ? "url" : c.name.equals("open_app") ? "name" : "path";
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
            case "open_app": return "打开程序：" + clip(String.valueOf(a.containsKey("path") ? a.get("path") : a.get("name")), 200);
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
