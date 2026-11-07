package com.onesay.plan.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 快捷指令的纯逻辑（计划 App 侧，JVM 全测）。格式与 OneSay {@code shortcut/ShortcutModel} 逐项一致，说明见 docs/SHORTCUTS.md。
 *
 * <h3>steps_json</h3>
 * <pre>
 * [{"tool":"clock.alarm.set","args":{"hour":7,"minute":0},"ask":["minute"],"title":"定闹钟"}, …]
 * </pre>
 * <ul>
 *   <li>{@code tool}：OneSay 工具注册表里的签名（或快捷指令内部动作，如 {@code llm.ask}）；</li>
 *   <li>{@code args}：参数；值为 JSON null，或参数名列在 {@code ask} 里 → 每次运行时问；</li>
 *   <li>字符串参数里的 {@code {{prev}}} 换成上一步的输出（「问一句模型并把结果传给下一步」）。</li>
 * </ul>
 * <h3>版本</h3>
 * 失败后按正常模式更新步骤时，旧步骤推进 history_json（最多 {@link #HISTORY_KEEP} 个），version + 1；撤销 = 弹出最近一个。
 * <h3>「不对」</h3>
 * 每次运行 run_seq + 1；点「不对」记下当前 run_seq，和上一次「不对」的 run_seq 相邻 → 连续次数 + 1，否则从 1 重新数；
 * 连续 {@link #WRONG_TO_FLAG} 次 → flagged=1（列表里标红提示修改）。用户改步骤 / 撤销后清零。
 */
public final class Shortcuts {
    private Shortcuts() {}

    public static final String DEFAULT_TRIGGERS = "{\"voice\":1,\"tap\":1,\"launcher\":0,\"time\":0}";
    public static final int MAX_STEPS = 20;
    public static final int HISTORY_KEEP = 5;
    public static final int WRONG_TO_FLAG = 2;
    public static final String PREV = "{{prev}}";
    static final Pattern TOOL = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)*");

    // ------------------------------------------------------------------ 步骤

    /** 一步。 */
    public static final class Step {
        public String tool = "";
        public JSONObject args = new JSONObject();
        public List<String> ask = new ArrayList<String>();
        public String title = "";

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
    }

    /** 解析 steps_json；格式不对抛 IllegalArgumentException（中文原因）。 */
    public static List<Step> parseSteps(String json) {
        String err = checkSteps(json);
        if (err != null) throw new IllegalArgumentException(err);
        List<Step> out = new ArrayList<Step>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Step s = new Step();
                s.tool = o.getString("tool").trim();
                JSONObject args = o.optJSONObject("args");
                s.args = args == null ? new JSONObject() : args;
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

    /** 校验 steps_json；通过返回 null，否则返回原因。 */
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
            if (o.has("ask")) {
                JSONArray ask = o.optJSONArray("ask");
                if (ask == null) return "第 " + (i + 1) + " 步的 ask 不是数组";
                for (int k = 0; k < ask.length(); k++) if (ask.optString(k, "").length() == 0) return "第 " + (i + 1) + " 步的 ask 里有空名字";
            }
        }
        return null;
    }

    public static String stepsJson(List<Step> steps) {
        JSONArray a = new JSONArray();
        for (Step s : steps) a.put(s.toJson());
        return a.toString();
    }

    /** 这一步每次运行时要问的参数（ask 列表 + 值为 null 的参数，按出现顺序去重）。 */
    public static List<String> askParams(Step s) {
        List<String> out = new ArrayList<String>(s.ask);
        if (s.args != null) {
            Iterator<?> it = s.args.keys();
            while (it.hasNext()) {
                String k = String.valueOf(it.next());
                if (s.args.isNull(k) && !out.contains(k)) out.add(k);
            }
        }
        return out;
    }

    /** 列表里的一行说明：「定闹钟 → 打开勿扰 → 问模型」。 */
    public static String summary(List<Step> steps, Actions.Catalog cat) {
        StringBuilder sb = new StringBuilder();
        for (Step s : steps) {
            if (sb.length() > 0) sb.append(" → ");
            String label = s.title != null && s.title.length() > 0 ? s.title : cat == null ? null : cat.labelOf(s.tool);
            sb.append(label == null ? s.tool : label);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 名字

    /** 名字归一化（比较 / 去重 / 语音命中用）：去空白和常见标点、转小写。 */
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

    /** 名字是否可用：非空、单行、≤40 字。不可用返回原因。 */
    public static String checkName(String name) {
        if (name == null || normName(name).length() == 0) return "快捷指令要有名字（也是语音触发词）";
        if (name.indexOf('\n') >= 0) return "名字不能换行";
        if (name.trim().length() > 40) return "名字最多 40 个字";
        return null;
    }

    // ------------------------------------------------------------------ 触发方式

    public static boolean trigger(String triggersJson, String key) {
        try {
            JSONObject o = new JSONObject(triggersJson == null || triggersJson.length() == 0 ? DEFAULT_TRIGGERS : triggersJson);
            return o.optInt(key, defaultTrigger(key)) != 0;
        } catch (JSONException e) {
            return defaultTrigger(key) != 0;
        }
    }

    static int defaultTrigger(String key) {
        return Contract.TRIG_VOICE.equals(key) || Contract.TRIG_TAP.equals(key) ? 1 : 0;
    }

    public static String triggers(boolean voice, boolean tap, boolean launcher, boolean time) {
        return "{\"voice\":" + (voice ? 1 : 0) + ",\"tap\":" + (tap ? 1 : 0) + ",\"launcher\":" + (launcher ? 1 : 0) + ",\"time\":" + (time ? 1 : 0) + "}";
    }

    /** 定时触发对应的自动任务指令（OneSay 的 S3 到点照常跑 AgentLoop，入口识别这句后改走快捷指令）。 */
    public static String timedCommand(long id, String name) {
        return "执行快捷指令#" + id + "（" + (name == null ? "" : name.trim()) + "）";
    }

    static final Pattern CMD_ID = Pattern.compile("执行快捷指令#(\\d+)");

    /** 从指令里取快捷指令 id；不是这种指令返回 -1。 */
    public static long commandId(String command) {
        if (command == null) return -1;
        Matcher m = CMD_ID.matcher(command);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** 定时触发的自动任务行（mode=time、action_type=agent）。hour/minute/repeat/week_mask 由调用方补。 */
    public static Row timedAutomation(long id, String name) {
        return new Row().with("title", "快捷指令：" + name).with("mode", Contract.MODE_TIME).with("trigger_type", "time")
                .with("action_type", Contract.ACT_AGENT).with("action", timedCommand(id, name))
                .with(Contract.C_SRC_KEY, Contract.SC_TIME_SRC_PREFIX + id).with("owner", "bixbyui");
    }

    // ------------------------------------------------------------------ 版本 / 撤销

    /** 用新步骤替换（旧步骤进历史）。返回要写入的列。 */
    public static Row replaceSteps(Row cur, String newSteps, String why, long now) {
        String err = checkSteps(newSteps);
        if (err != null) throw new IllegalArgumentException(err);
        JSONArray hist = history(cur);
        try {
            JSONObject old = new JSONObject().put("v", cur.l("version", 1)).put("steps", new JSONArray(cur.s("steps_json")))
                    .put("at", now).put("why", why == null ? "" : why);
            hist.put(old);
        } catch (JSONException e) {
            throw new IllegalArgumentException("旧步骤损坏，没法保存版本：" + e.getMessage());
        }
        while (hist.length() > HISTORY_KEEP) hist = drop(hist, 0);
        return new Row().with("steps_json", newSteps).with("history_json", hist.toString()).with("version", cur.l("version", 1) + 1)
                .with("flagged", 0).with("wrong_streak", 0).with("flag_reason", "");
    }

    /** 撤销到上一个版本；没有历史返回 null。 */
    public static Row undo(Row cur) {
        JSONArray hist = history(cur);
        if (hist.length() == 0) return null;
        JSONObject last = hist.optJSONObject(hist.length() - 1);
        if (last == null || last.optJSONArray("steps") == null) return null;
        return new Row().with("steps_json", last.optJSONArray("steps").toString()).with("history_json", drop(hist, hist.length() - 1).toString())
                .with("version", cur.l("version", 1) + 1).with("flagged", 0).with("wrong_streak", 0).with("flag_reason", "");
    }

    public static boolean canUndo(Row cur) {
        return history(cur).length() > 0;
    }

    static JSONArray history(Row cur) {
        try {
            String h = cur.s("history_json");
            return new JSONArray(h.length() == 0 ? "[]" : h);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static JSONArray drop(JSONArray a, int idx) {
        JSONArray b = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (i != idx) b.put(a.opt(i));
        return b;
    }

    // ------------------------------------------------------------------ 运行 / 「不对」

    /** 开始一次运行：run_seq + 1。 */
    public static Row beginRun(Row cur, long now) {
        return new Row().with("run_seq", cur.l("run_seq") + 1).with("last_run_at", now);
    }

    /** 用户对最近一次运行点了「不对」。 */
    public static Row markWrong(Row cur) {
        long seq = cur.l("run_seq");
        long lastWrong = cur.l("last_wrong_seq");
        if (seq > 0 && lastWrong == seq) return new Row();   // 同一次运行重复点，不再加
        long streak = lastWrong > 0 && lastWrong == seq - 1 ? cur.l("wrong_streak") + 1 : 1;
        Row w = new Row().with("wrong_streak", streak).with("last_wrong_seq", seq);
        if (streak >= WRONG_TO_FLAG) w.with("flagged", 1).with("flag_reason", "连续 " + streak + " 次说「不对」，请检查步骤");
        return w;
    }

    /** 计划 App 点击 / 桌面快捷方式发起一次运行（等 OneSay 认领）。 */
    public static Row request(long shortcutId, String trigger, long now) {
        return new Row().with("shortcut_id", shortcutId).with("started_at", now).with("status", Contract.SC_REQUESTED)
                .with("trigger_info", trigger == null ? "" : trigger);
    }

    /** 发起后多久没被认领算「OneSay 没在运行」。 */
    public static final long REQUEST_STALE_MS = 2L * 60L * 1000L;

    public static boolean isStale(Row run, long now) {
        return Contract.SC_REQUESTED.equals(run.s("status")) && now - run.l("started_at") > REQUEST_STALE_MS;
    }

    /**
     * 计划 App 处于沉浸全屏时 OneSay 的岛被隐藏，点「运行」后用户只能在通知里看到结果 →
     * 计划 App 自己盯住这次请求，结束时在 App 内提示一句。还没结束（requested / queued / running）返回 null。
     */
    /**
     * 详情页「最近运行」一行原来是「状态 · 结果」，OneSay 成功时结果就是「已执行」→ 显示成「已执行 · 已执行」。
     * 结果与状态说的是同一件事（相同 / 状态以它开头）就只显示状态；结果为空同样只显示状态。
     */
    public static String historyStatus(String statusLabel, String result) {
        String st = statusLabel == null ? "" : statusLabel.trim(), res = result == null ? "" : result.trim();
        if (res.length() == 0 || res.equals(st) || (st.length() > 0 && st.startsWith(res))) return st;
        if (st.length() == 0) return res;
        return st + " · " + res;
    }

    public static String finishedText(String name, Row run) {
        if (run == null) return null;
        String st = run.s("status"), res = run.s("result").trim(), n = "「" + (name == null ? "" : name.trim()) + "」";
        if (st.length() == 0 || Contract.SC_REQUESTED.equals(st) || Contract.SC_QUEUED.equals(st) || Contract.SC_RUNNING.equals(st)) return null;
        String tail = res.length() > 0 && !res.equals("已执行") ? "：" + clip(res, 80) : "";
        if (Contract.SC_OK.equals(st)) return n + "已执行" + tail;
        if (Contract.SC_FALLBACK_OK.equals(st)) return n + "已执行（步骤已更新，可在详情里撤销）";
        if (Contract.SC_CANCELLED.equals(st)) return n + "已取消";
        if (Contract.SC_DENIED.equals(st)) return n + "没授权，没执行" + tail;
        if (Contract.SC_STALE.equals(st)) return n + "没执行：OneSay 没在运行";
        return n + "没做完" + tail;
    }

    /** 这次请求最多盯多久（认领超时 + 一分钟执行）。 */
    public static final long WATCH_MS = REQUEST_STALE_MS + 60000L;

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------ 桌面固定图标

    /** 固定到桌面的快捷方式 id（ShortcutInfo id）。 */
    public static String pinId(long shortcutId) {
        return "sc-" + shortcutId;
    }

    /** 删掉后桌面上留下的图标被禁用时，系统显示的说明（ShortcutManager.disableShortcuts）。 */
    public static final String PIN_DISABLED = "这条快捷指令已删除";
    /** 点到删掉的快捷指令的旧图标。 */
    public static final String PIN_GONE = "这条快捷指令已经删除了。长按这个图标，选「移除」就能把它从桌面拿掉";

    // ------------------------------------------------------------------ 动作清单

    /**
     * v1 动作清单（计划 App 手动拼步骤用）。默认表与 OneSay {@code ShortcutActions} 一致；OneSay 连上后会推送一份带
     * 「这台设备能不能用 / 原因」的清单（{@link Contract#M_SHORTCUT_ACTIONS}），以推送的为准。
     */
    public static final class Actions {
        private Actions() {}

        public static final class Param {
            public final String name, label, type;

            public Param(String name, String label, String type) { this.name = name; this.label = label; this.type = type; }
        }

        public static final class Action {
            public final String sign, label, group;
            public final List<Param> params = new ArrayList<Param>();
            /** 上机版专属（需要 root / 特权），开源构建默认不可用。 */
            public boolean privileged;
            public boolean available = true;
            public String reason = "";

            Action(String sign, String label, String group) { this.sign = sign; this.label = label; this.group = group; }

            Action p(String name, String label, String type) {
                params.add(new Param(name, label, type));
                return this;
            }

            Action priv() {
                privileged = true;
                return this;
            }
        }

        public static final class Catalog {
            public final List<Action> actions = new ArrayList<Action>();
            /** true = OneSay 推送过（可用性是探测过的）；false = 默认表（没探测）。 */
            public boolean probed;

            public Action get(String sign) {
                for (Action a : actions) if (a.sign.equals(sign)) return a;
                return null;
            }

            public String labelOf(String sign) {
                Action a = get(sign);
                return a == null ? null : a.label;
            }
        }

        public static final String PRIV_REASON = "上机版专属（需要 root），开源版不可用";

        /** 默认表：没推送过时 privileged 项置灰（开源版保守），其余按可用处理。 */
        public static Catalog defaults() {
            Catalog c = new Catalog();
            List<Action> l = c.actions;
            l.add(new Action("clock.alarm.set", "定闹钟", "闹钟").p("hour", "几点（0–23）", "int").p("minute", "几分", "int"));
            l.add(new Action("clock.timer.set", "倒计时", "闹钟").p("seconds", "多少秒", "int"));
            l.add(new Action("s3.todo.add", "加待办", "待办").p("text", "内容", "string"));
            l.add(new Action("plan.event.add", "加日程", "日程").p("title", "标题", "string").p("date", "日期（today / tomorrow / 2026-11-07）", "string")
                    .p("time", "时间（HH:mm，留空 = 全天）", "string"));
            l.add(new Action("app.launch", "打开 App", "打开").p("app", "App 名字", "string"));
            l.add(new Action("browser.open", "打开网页 / 深链", "打开").p("url", "网址或深链（https:// / 应用 scheme）", "string"));
            l.add(new Action("settings.page.wifi", "打开 Wi‑Fi 设置页", "打开"));
            l.add(new Action("dnd.on", "打开勿扰", "系统开关").priv());
            l.add(new Action("dnd.off", "关闭勿扰", "系统开关").priv());
            l.add(new Action("volume.media.set", "媒体音量", "系统开关").p("level", "音量（0–15）", "int").priv());
            l.add(new Action("volume.ring.set", "铃声音量", "系统开关").p("level", "音量（0–15）", "int").priv());
            l.add(new Action("settings.brightness.set", "屏幕亮度", "系统开关").p("level", "亮度（0–255）", "int").priv());
            l.add(new Action("brief.play", "播报晨间简报", "播报").priv());
            Action w = new Action("weather.say", "播报天气", "播报");
            w.available = false;
            w.reason = "还没有单步天气工具（v1 未接线）";
            l.add(w);
            Action m = new Action("media.save_clip", "保存剪贴板里的视频 / 图片", "保存");
            m.available = false;
            m.reason = "这一版没有该工具";
            l.add(m);
            l.add(new Action("llm.ask", "问一句模型（结果传给下一步）", "模型").p("prompt", "要问的话", "string"));
            for (Action a : l) {
                if (a.privileged) {
                    a.available = false;
                    a.reason = PRIV_REASON;
                }
            }
            return c;
        }

        /** 解析 OneSay 推送的清单；格式不对返回 null（调用方用默认表）。 */
        public static Catalog parse(String json) {
            try {
                JSONObject root = new JSONObject(json);
                if (root.optInt("format", 0) != Contract.SHORTCUT_FORMAT) return null;
                JSONArray a = root.getJSONArray("actions");
                Catalog c = new Catalog();
                c.probed = true;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String sign = o.optString("sign", "");
                    if (!TOOL.matcher(sign).matches()) continue;
                    Action x = new Action(sign, o.optString("label", sign), o.optString("group", ""));
                    x.privileged = o.optBoolean("privileged", false);
                    x.available = o.optBoolean("available", true);
                    x.reason = o.optString("reason", "");
                    JSONArray ps = o.optJSONArray("params");
                    if (ps != null) for (int k = 0; k < ps.length(); k++) {
                        JSONObject p = ps.getJSONObject(k);
                        x.p(p.optString("name"), p.optString("label", p.optString("name")), p.optString("type", "string"));
                    }
                    c.actions.add(x);
                }
                return c.actions.isEmpty() ? null : c;
            } catch (JSONException e) {
                return null;
            }
        }
    }
}
