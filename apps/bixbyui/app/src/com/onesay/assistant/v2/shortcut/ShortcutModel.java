package com.onesay.assistant.v2.shortcut;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [shortcut] 快捷指令的数据模型（纯 Java，JVM 全测）。数据存在计划 App 的 {@code shortcuts} 表；格式与计划 App
 * {@code core/Shortcuts.java} 逐项一致（ShortcutFormatTest 在两个仓库都在时比对常量），说明见 docs/SHORTCUTS.md §2。
 *
 * <pre>
 * steps_json    [{"tool":"clock.alarm.set","args":{"hour":7,"minute":null},"ask":["minute"],"title":"定闹钟"}, …]
 *               参数值为 null 或名字在 ask 里 → 每次运行时问；字符串里的 {{prev}} → 上一步的输出
 * triggers_json {"voice":1,"tap":1,"launcher":0,"time":0}
 * history_json  [{"v":1,"steps":[…],"at":ms,"why":"…"}, …]   旧版本，最新的在最后，最多 5 个
 * aliases_json  ["帮我定个七点的闹钟然后开勿扰", …]           从对话里存的：原话也算触发词（「第二次做同样的事」）
 * </pre>
 */
public final class ShortcutModel {

    private ShortcutModel() {}

    // ------------------------------------------------------------------ 与计划 App Contract 一致的常量

    public static final String T_SHORTCUTS = "shortcuts", T_RUNS = "shortcut_runs";
    public static final String M_SHORTCUT_ACTIONS = "shortcut_actions";
    public static final String SC_REQUESTED = "requested", SC_QUEUED = "queued", SC_RUNNING = "running", SC_OK = "ok", SC_FAIL = "fail",
            SC_FALLBACK_OK = "fallback_ok", SC_FALLBACK_FAIL = "fallback_fail", SC_CANCELLED = "cancelled", SC_DENIED = "denied", SC_STALE = "stale";
    public static final String TRIG_VOICE = "voice", TRIG_TAP = "tap", TRIG_LAUNCHER = "launcher", TRIG_TIME = "time", TRIG_CHAT = "chat";
    public static final String SC_TIME_SRC_PREFIX = "shortcut:time:";
    public static final int FORMAT = 1;
    public static final String DEFAULT_TRIGGERS = "{\"voice\":1,\"tap\":1,\"launcher\":0,\"time\":0}";
    public static final int MAX_STEPS = 20;
    public static final int HISTORY_KEEP = 5;
    public static final int WRONG_TO_FLAG = 2;
    public static final String PREV = "{{prev}}";
    public static final long REQUEST_STALE_MS = 2L * 60L * 1000L;

    // ------------------------------------------------------------------ 类型

    /** 一步：工具签名 + 参数 + 每次要问的参数名。 */
    public static final class Step {
        public String tool = "";
        public JSONObject args = new JSONObject();
        public final List<String> ask = new ArrayList<String>();
        public String title = "";

        public Step() {}

        public Step(String tool, JSONObject args) {
            this.tool = tool == null ? "" : tool;
            this.args = args == null ? new JSONObject() : args;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("tool", tool);
                o.put("args", args == null ? new JSONObject() : args);
                if (!ask.isEmpty()) o.put("ask", new JSONArray(ask));
                if (title != null && title.length() > 0) o.put("title", title);
            } catch (JSONException ignored) {
            }
            return o;
        }

        /** 每次运行时要问的参数（ask ∪ 值为 null 的参数）。 */
        public List<String> askParams() {
            List<String> out = new ArrayList<String>(ask);
            if (args != null) {
                Iterator<?> it = args.keys();
                while (it.hasNext()) {
                    String k = String.valueOf(it.next());
                    if (args.isNull(k) && !out.contains(k)) out.add(k);
                }
            }
            return out;
        }

        @Override public String toString() {
            return tool + (args == null ? "" : args.toString());
        }
    }

    /** 一条快捷指令（计划 App shortcuts 表的一行）。 */
    public static final class Shortcut {
        public long id;
        public String name = "";
        public final List<String> aliases = new ArrayList<String>();
        public List<Step> steps = new ArrayList<Step>();
        public String triggersJson = DEFAULT_TRIGGERS;
        public boolean enabled = true;
        public long version = 1;
        public String historyJson = "[]";
        public long runSeq, wrongStreak, lastWrongSeq;
        public boolean flagged;
        public String origin = "ui";

        public boolean trigger(String key) {
            return ShortcutModel.trigger(triggersJson, key);
        }

        @Override public String toString() {
            return "Shortcut#" + id + "「" + name + "」v" + version + " " + steps.size() + " 步";
        }
    }

    // ------------------------------------------------------------------ 解析 / 序列化

    static final Pattern TOOL = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)*");

    public static List<Step> parseSteps(String json) {
        String err = checkSteps(json);
        if (err != null) throw new IllegalArgumentException(err);
        List<Step> out = new ArrayList<Step>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Step s = new Step(o.getString("tool").trim(), o.optJSONObject("args"));
                JSONArray ask = o.optJSONArray("ask");
                if (ask != null) for (int k = 0; k < ask.length(); k++) s.ask.add(ask.getString(k));
                s.title = o.optString("title", "");
                out.add(s);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException("steps_json 不是合法 JSON：" + e.getMessage());
        }
        return out;
    }

    /** 与计划 App Shortcuts.checkSteps 同一套规则；通过返回 null。 */
    public static String checkSteps(String json) {
        if (json == null || json.trim().length() == 0) return "快捷指令至少要有一步";
        JSONArray a;
        try {
            a = new JSONArray(json);
        } catch (JSONException e) {
            return "steps_json 不是 JSON 数组";
        }
        if (a.length() == 0) return "快捷指令至少要有一步";
        if (a.length() > MAX_STEPS) return "快捷指令最多 " + MAX_STEPS + " 步";
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) return "第 " + (i + 1) + " 步不是对象";
            String tool = o.optString("tool", "").trim();
            if (tool.length() == 0 || tool.length() > 80 || !TOOL.matcher(tool).matches()) return "第 " + (i + 1) + " 步的工具名不对：" + tool;
            if (o.has("args") && o.optJSONObject("args") == null) return "第 " + (i + 1) + " 步的 args 不是对象";
        }
        return null;
    }

    public static String stepsJson(List<Step> steps) {
        JSONArray a = new JSONArray();
        for (Step s : steps) a.put(s.toJson());
        return a.toString();
    }

    /** 计划 App 行（列名 → 值）→ 快捷指令。步骤坏了抛 IllegalArgumentException。 */
    public static Shortcut fromRow(Map<String, Object> r) {
        Shortcut s = new Shortcut();
        s.id = l(r, "_id");
        s.name = str(r, "name");
        s.steps = parseSteps(str(r, "steps_json"));
        String t = str(r, "triggers_json");
        s.triggersJson = t.length() == 0 ? DEFAULT_TRIGGERS : t;
        s.enabled = !r.containsKey("enabled") || l(r, "enabled") != 0;
        s.version = r.containsKey("version") ? l(r, "version") : 1;
        String h = str(r, "history_json");
        s.historyJson = h.length() == 0 ? "[]" : h;
        s.runSeq = l(r, "run_seq");
        s.wrongStreak = l(r, "wrong_streak");
        s.lastWrongSeq = l(r, "last_wrong_seq");
        s.flagged = l(r, "flagged") != 0;
        s.origin = str(r, "origin").length() == 0 ? "ui" : str(r, "origin");
        try {
            JSONArray a = new JSONArray(str(r, "aliases_json").length() == 0 ? "[]" : str(r, "aliases_json"));
            for (int i = 0; i < a.length(); i++) if (a.optString(i, "").length() > 0) s.aliases.add(a.optString(i));
        } catch (JSONException ignored) {
        }
        return s;
    }

    static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    static long l(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return v == null ? 0 : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean trigger(String triggersJson, String key) {
        int def = TRIG_VOICE.equals(key) || TRIG_TAP.equals(key) ? 1 : 0;
        try {
            return new JSONObject(triggersJson == null || triggersJson.length() == 0 ? DEFAULT_TRIGGERS : triggersJson).optInt(key, def) != 0;
        } catch (JSONException e) {
            return def != 0;
        }
    }

    /** 名字归一化：去空白和常见标点、转小写（与计划 App normName 一致）。 */
    public static String normName(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        String t = s.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c) || "，。！？、,.!?;；:：\"'“”‘’「」『』《》()（）~～…·".indexOf(c) >= 0) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 版本 / 撤销 / 「不对」（返回要写入的列）

    public static Map<String, Object> replaceSteps(Shortcut cur, List<Step> newSteps, String why, long now) {
        String nj = stepsJson(newSteps);
        String err = checkSteps(nj);
        if (err != null) throw new IllegalArgumentException(err);
        JSONArray hist = history(cur.historyJson);
        try {
            hist.put(new JSONObject().put("v", cur.version).put("steps", new JSONArray(stepsJson(cur.steps))).put("at", now).put("why", why == null ? "" : why));
        } catch (JSONException e) {
            throw new IllegalArgumentException("旧步骤损坏：" + e.getMessage());
        }
        while (hist.length() > HISTORY_KEEP) hist = drop(hist, 0);
        Map<String, Object> w = new java.util.LinkedHashMap<String, Object>();
        w.put("steps_json", nj);
        w.put("history_json", hist.toString());
        w.put("version", cur.version + 1);
        w.put("flagged", 0);
        w.put("wrong_streak", 0);
        w.put("flag_reason", "");
        return w;
    }

    /** 撤销到上一版；没有历史返回 null。 */
    public static Map<String, Object> undo(Shortcut cur) {
        JSONArray hist = history(cur.historyJson);
        if (hist.length() == 0) return null;
        JSONObject last = hist.optJSONObject(hist.length() - 1);
        if (last == null || last.optJSONArray("steps") == null) return null;
        Map<String, Object> w = new java.util.LinkedHashMap<String, Object>();
        w.put("steps_json", last.optJSONArray("steps").toString());
        w.put("history_json", drop(hist, hist.length() - 1).toString());
        w.put("version", cur.version + 1);
        w.put("flagged", 0);
        w.put("wrong_streak", 0);
        w.put("flag_reason", "");
        return w;
    }

    public static boolean canUndo(Shortcut cur) {
        return history(cur.historyJson).length() > 0;
    }

    public static Map<String, Object> beginRun(Shortcut cur, long now) {
        Map<String, Object> w = new java.util.LinkedHashMap<String, Object>();
        w.put("run_seq", cur.runSeq + 1);
        w.put("last_run_at", now);
        return w;
    }

    /** 「不对」：和上一次「不对」相邻的运行 → 连续次数 + 1，否则从 1 数；到 2 标红。同一次运行重复点不加。 */
    public static Map<String, Object> markWrong(Shortcut cur) {
        Map<String, Object> w = new java.util.LinkedHashMap<String, Object>();
        long seq = cur.runSeq;
        if (seq > 0 && cur.lastWrongSeq == seq) return w;
        long streak = cur.lastWrongSeq > 0 && cur.lastWrongSeq == seq - 1 ? cur.wrongStreak + 1 : 1;
        w.put("wrong_streak", streak);
        w.put("last_wrong_seq", seq);
        if (streak >= WRONG_TO_FLAG) {
            w.put("flagged", 1);
            w.put("flag_reason", "连续 " + streak + " 次说「不对」，请检查步骤");
        }
        return w;
    }

    static JSONArray history(String json) {
        try {
            return new JSONArray(json == null || json.length() == 0 ? "[]" : json);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static JSONArray drop(JSONArray a, int idx) {
        JSONArray b = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (i != idx) b.put(a.opt(i));
        return b;
    }

    // ------------------------------------------------------------------ 定时触发的指令

    static final Pattern CMD_ID = Pattern.compile("执行快捷指令#(\\d+)");

    /** 定时触发（计划 App 写的自动任务 action）/ 点击运行（OneSay 排进任务队列的指令）。 */
    public static String runCommand(long id, String name) {
        return "执行快捷指令#" + id + "（" + (name == null ? "" : name.trim()) + "）";
    }

    public static long commandId(String command) {
        if (command == null) return -1;
        Matcher m = CMD_ID.matcher(command);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /**
     * 这次运行的触发方式（点击 / 桌面图标运行在日志里记成 time）。
     * 说名字 / 原话 = voice；「执行快捷指令#id」= 计划 App 排进来的请求（有排队中的请求行就用它的 trigger_info：tap / launcher），
     * 没有请求行 = 定时触发（time）。
     */
    public static String effectiveTrigger(boolean byCommand, Map<String, Object> queuedRequest) {
        if (!byCommand) return TRIG_VOICE;
        String t = queuedRequest == null ? "" : str(queuedRequest, "trigger_info");
        return t.length() > 0 ? t : TRIG_TIME;
    }

    // ------------------------------------------------------------------ 给人看的标题（内部指令文字不上屏）

    static final Pattern CMD_NAME = Pattern.compile("执行快捷指令#\\d+（(.*)）\\s*$");
    /** 执行开始时岛 / 队列上只说这一句（用户定：命中只说「正在执行快捷指令」）。 */
    public static final String RUNNING_TITLE = "正在执行快捷指令";
    public static final String DONE_SUFFIX = "已执行";

    /** 「执行快捷指令#12（晚安）」里的名字（晚安）；不是这种指令 / 没名字 → ""。 */
    public static String commandName(String command) {
        if (command == null) return "";
        Matcher m = CMD_NAME.matcher(command);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 是不是排进任务队列 / 定时触发的「执行快捷指令#id」。 */
    public static boolean isRunCommand(String command) {
        return commandId(command) > 0;
    }

    /** 结束时的标题：快捷指令的名字（取不到名字就叫「快捷指令」）。正文另给（成功 = 「已执行」）。 */
    public static String doneTitle(String command) {
        String n = commandName(command);
        return n.length() > 0 ? n : "快捷指令";
    }

    /** 任务队列 / 定时任务列表 / 授权提示里的标题：快捷指令 →「快捷指令「名字」」，其余原样。 */
    public static String displayTitle(String command) {
        if (!isRunCommand(command)) return command == null ? "" : command;
        return "快捷指令「" + doneTitle(command) + "」";
    }

    static final Pattern CMD_ANY = Pattern.compile("执行快捷指令#\\d+（([^）]*)）");

    /**
     * 日志里也不出现内部指令「执行快捷指令#id（名字）」（任务队列「轮到 run#1[q:执行快捷指令#2（加待办）…]」、
     * S3-AGENT「开始 a1：执行快捷指令#2（加待办）」）——换成「快捷指令「名字」」；没名字叫「快捷指令」。其余文字原样。
     */
    public static String logText(String s) {
        if (s == null || s.indexOf("执行快捷指令#") < 0) return s == null ? "" : s;
        Matcher m = CMD_ANY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String n = m.group(1).trim();
            m.appendReplacement(sb, Matcher.quoteReplacement("快捷指令「" + (n.length() > 0 ? n : "快捷指令") + "」"));
        }
        m.appendTail(sb);
        String out = sb.toString();
        return out.indexOf("执行快捷指令#") < 0 ? out : out.replaceAll("执行快捷指令#\\d+", "快捷指令");
    }

    /** 结束时一行话：「晚安 已执行」/「晚安 没完成：…」。 */
    public static String doneLine(String command, boolean ok, String reason) {
        String t = doneTitle(command);
        if (ok) return t + " " + DONE_SUFFIX;
        return t + " 没完成" + (reason == null || reason.trim().length() == 0 ? "" : "：" + reason.trim());
    }
}
