package com.onesay.assistant.v2.planner;

import com.onesay.assistant.v2.events.EventTask;
import com.onesay.assistant.v2.s3.S3Parser;
import com.onesay.assistant.v2.s3.S3Store;
import com.onesay.assistant.v2.sched.SchedEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [planner] OneSay 内部对象 ↔ 计划 App 行（纯 Java，JVM 全测）。写出去的 Map 只含计划 App 允许外部写的列
 * （不含 _id / created_at / updated_at）。
 */
public final class PlannerMap {
    private PlannerMap() {}

    static long l(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static long l(Map<String, Object> m, String k, long dflt) {
        return m.containsKey(k) && m.get(k) != null ? l(m, k) : dflt;
    }

    static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    // ------------------------------------------------------------------ 日程

    public static Map<String, Object> fromEvent(SchedEvent e) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("title", e.title == null ? "" : e.title);
        m.put("start_at", e.startAt);
        m.put("end_at", e.endAt);
        m.put("all_day", e.allDay ? 1 : 0);
        m.put("location", e.location == null ? "" : e.location);
        m.put("note", e.note == null ? "" : e.note);
        m.put("repeat", e.repeat);
        m.put("week_mask", e.weekMask);
        m.put("month_day", e.monthDay);
        m.put("lunar", e.lunar ? 1 : 0);
        m.put("lunar_month", e.lunarMonth);
        m.put("lunar_day", e.lunarDay);
        m.put("lead_min", e.leadMin);
        m.put("source", e.source == null || e.source.length() == 0 ? "voice" : e.source);
        m.put("ext_id", e.extId == null ? "" : e.extId);
        m.put("deleted_at", e.deleted ? Math.max(1L, e.deletedAt) : 0L);
        return m;
    }

    public static SchedEvent toEvent(Map<String, Object> m) {
        SchedEvent e = new SchedEvent();
        e.id = l(m, "_id");
        e.title = s(m, "title");
        e.startAt = l(m, "start_at");
        e.endAt = l(m, "end_at");
        e.allDay = l(m, "all_day") != 0;
        e.location = s(m, "location");
        e.note = s(m, "note");
        e.repeat = (int) l(m, "repeat");
        e.weekMask = (int) l(m, "week_mask");
        e.monthDay = (int) l(m, "month_day");
        e.lunar = l(m, "lunar") != 0;
        e.lunarMonth = (int) l(m, "lunar_month");
        e.lunarDay = (int) l(m, "lunar_day");
        e.leadMin = (int) l(m, "lead_min", -1);
        e.source = s(m, "source");
        e.extId = s(m, "ext_id");
        e.createdAt = l(m, "created_at");
        e.updatedAt = l(m, "updated_at");
        e.deletedAt = l(m, "deleted_at");
        e.deleted = e.deletedAt > 0;
        return e;
    }

    // ------------------------------------------------------------------ S3 定时任务 → 自动任务（定时）

    public static Map<String, Object> fromTask(S3Store.Task t) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        boolean agent = t.kind == S3Parser.KIND_AGENT;
        String title = t.title != null && t.title.trim().length() > 0 ? t.title.trim() : (agent ? t.command : "提醒");
        m.put("title", title.length() > 200 ? title.substring(0, 200) : title);
        m.put("mode", PlannerContract.MODE_TIME);
        m.put("trigger_type", "time");
        m.put("action_type", agent ? PlannerContract.ACT_AGENT : PlannerContract.ACT_REMIND);
        m.put("action", agent ? t.command : (t.title == null ? "" : t.title));
        m.put("enabled", t.enabled ? 1 : 0);
        m.put("repeat", t.repeat);
        m.put("week_mask", t.weekMask);
        m.put("hour", t.hour);
        m.put("minute", t.minute);
        m.put("once_at", t.onceAt);
        m.put("next_at", t.nextAt);
        m.put("last_run_at", t.lastFiredAt);
        m.put("last_missed_at", t.lastMissedAt);
        m.put("last_result", t.lastResult == null ? "" : t.lastResult);
        m.put("todo_id", t.todoId);
        m.put("owner", PlannerContract.OWNER_S3);
        m.put("source", "bixbyui");
        return m;
    }

    /** 只有 mode=time 且动作是 remind / agent 的行才是 S3 任务（builtin = 其他模块的模板任务，不归 S3 跑）。 */
    public static boolean isS3Task(Map<String, Object> m) {
        String at = s(m, "action_type");
        return PlannerContract.MODE_TIME.equals(s(m, "mode")) && (PlannerContract.ACT_AGENT.equals(at) || PlannerContract.ACT_REMIND.equals(at));
    }

    public static S3Store.Task toTask(Map<String, Object> m) {
        S3Store.Task t = new S3Store.Task();
        t.id = l(m, "_id");
        boolean agent = PlannerContract.ACT_AGENT.equals(s(m, "action_type"));
        t.kind = agent ? S3Parser.KIND_AGENT : S3Parser.KIND_REMINDER;
        t.title = agent ? s(m, "title") : (s(m, "action").length() > 0 ? s(m, "action") : s(m, "title"));
        t.command = agent ? s(m, "action") : "";
        t.repeat = (int) l(m, "repeat");
        t.weekMask = (int) l(m, "week_mask");
        t.hour = (int) l(m, "hour");
        t.minute = (int) l(m, "minute");
        t.onceAt = l(m, "once_at");
        t.nextAt = l(m, "next_at", -1L);
        t.lastFiredAt = l(m, "last_run_at");
        t.lastMissedAt = l(m, "last_missed_at");
        t.lastResult = s(m, "last_result");
        t.enabled = l(m, "enabled", 1) != 0;
        t.createdAt = l(m, "created_at");
        t.todoId = l(m, "todo_id");
        return t;
    }

    // ------------------------------------------------------------------ 待办

    public static Map<String, Object> fromTodo(S3Store.Todo t) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("title", t.text == null ? "" : t.text);
        m.put("due_at", t.dueAt);
        m.put("done_at", t.doneAt);
        m.put("remind", t.dueAt > 0 ? 1 : 0);
        m.put("source", "bixbyui");
        return m;
    }

    public static S3Store.Todo toTodo(Map<String, Object> m) {
        S3Store.Todo t = new S3Store.Todo();
        t.id = l(m, "_id");
        t.text = s(m, "title");
        t.dueAt = l(m, "due_at");
        t.createdAt = l(m, "created_at");
        t.doneAt = l(m, "done_at");
        return t;
    }

    // ------------------------------------------------------------------ 事件任务 → 自动任务（触发）

    public static Map<String, Object> fromEventTask(EventTask t) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        try {
            JSONObject trig = new JSONObject().put("on", t.onConnect).put("target", t.target == null ? "" : t.target)
                    .put("apps", new JSONArray(t.apps)).put("keywords", new JSONArray(t.keywords));
            JSONObject guard = new JSONObject().put("debounceMs", t.debounceMs).put("windowStart", t.windowStart).put("windowEnd", t.windowEnd)
                    .put("dailyCap", t.dailyCap).put("maxFailures", t.maxFailures);
            JSONObject st = new JSONObject().put("paused", t.paused).put("pausedReason", t.pausedReason == null ? "" : t.pausedReason)
                    .put("failures", t.failures).put("dayKey", t.dayKey == null ? "" : t.dayKey).put("firesToday", t.firesToday);
            String title = t.title != null && t.title.trim().length() > 0 ? t.title.trim() : t.describeTrigger();
            m.put("title", title.length() > 200 ? title.substring(0, 200) : title);
            m.put("mode", PlannerContract.MODE_TRIGGER);
            m.put("trigger_type", t.source.name().toLowerCase(Locale.ROOT));
            m.put("trigger_json", trig.toString());
            m.put("guard_json", guard.toString());
            m.put("state_json", st.toString());
            m.put("action_type", PlannerContract.ACT_AGENT);
            m.put("action", t.command == null ? "" : t.command);
            m.put("enabled", t.enabled ? 1 : 0);
            m.put("last_run_at", t.lastFireAt);
            m.put("owner", PlannerContract.OWNER_EVENTS);
            m.put("source", "bixbyui");
        } catch (Exception e) {
            throw new PlannerBackend.PlannerException("事件任务转换失败: " + e.getMessage(), e);
        }
        return m;
    }

    /** 计划 App 里的触发任务，OneSay 现在能检测的四种才转成 EventTask（其余如 sms / clip 先存着，见 TRIGGERS_DETECTED_TODAY）。 */
    public static EventTask toEventTask(Map<String, Object> m) {
        EventTask.Source src = EventTask.Source.parse(s(m, "trigger_type"));
        if (src == null || !PlannerContract.MODE_TRIGGER.equals(s(m, "mode"))) return null;
        EventTask t = new EventTask();
        t.id = l(m, "_id");
        t.title = s(m, "title");
        t.source = src;
        t.command = s(m, "action");
        t.enabled = l(m, "enabled", 1) != 0;
        t.lastFireAt = l(m, "last_run_at");
        t.createdAt = l(m, "created_at");
        JSONObject trig = obj(s(m, "trigger_json")), guard = obj(s(m, "guard_json")), st = obj(s(m, "state_json"));
        t.onConnect = trig.optBoolean("on", true);
        t.target = trig.optString("target", "");
        t.apps = list(trig.optJSONArray("apps"));
        t.keywords = list(trig.optJSONArray("keywords"));
        t.debounceMs = guard.optLong("debounceMs", EventTask.DEFAULT_DEBOUNCE_MS);
        t.windowStart = guard.optInt("windowStart", -1);
        t.windowEnd = guard.optInt("windowEnd", -1);
        t.dailyCap = guard.optInt("dailyCap", EventTask.DEFAULT_DAILY_CAP);
        t.maxFailures = guard.optInt("maxFailures", EventTask.DEFAULT_MAX_FAILURES);
        t.paused = st.optBoolean("paused", false);
        t.pausedReason = st.optString("pausedReason", "");
        t.failures = st.optInt("failures", 0);
        t.dayKey = st.optString("dayKey", "");
        t.firesToday = st.optInt("firesToday", 0);
        return t;
    }

    static JSONObject obj(String s) {
        try {
            return new JSONObject(s == null || s.length() == 0 ? "{}" : s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static List<String> list(JSONArray a) {
        List<String> l = new ArrayList<String>();
        if (a != null) for (int i = 0; i < a.length(); i++) if (a.optString(i, "").trim().length() > 0) l.add(a.optString(i).trim());
        return l;
    }

    // ------------------------------------------------------------------ 取件码

    public static Map<String, Object> pickup(String code, String kind, String station, String pkg, long deadline, String srcKey, String source) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code == null ? "" : code.replaceAll("\\s+", ""));
        m.put("kind", kind == null || kind.length() == 0 ? "取件码" : kind);
        m.put("station", station == null ? "" : station);
        m.put("app_pkg", pkg == null ? "" : pkg);
        m.put("deadline_at", deadline);
        if (srcKey != null) m.put("src_key", srcKey);
        m.put("source", source == null ? "bixbyui" : source);
        return m;
    }
}
