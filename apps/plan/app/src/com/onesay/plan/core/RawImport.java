package com.onesay.plan.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 一次性迁移：把 OneSay 原样导出的各库行（{@link Contract#BUNDLE_FORMAT}）映射成计划 App 的行（纯 Java）。
 * OneSay 只负责「原样导出」（不懂计划 App 的结构），映射只在这里写一份、在这里测。
 *
 * <p>导入包（JSON 对象）可带的键，缺哪个就跳过哪个：
 * <pre>
 * sched_event   sched.db / sched_event 全部列（软删 deleted=1 的不导）
 * s3_task       s3_tasks.db / task 全部列          → automations（mode=time）
 * s3_todo       s3_tasks.db / todo 全部列          → todos
 * event_task    jobs.db / event_task {id,json,created_at} → automations（mode=trigger）
 * job           jobs.db / job 全部列（模板任务，如每日简报）→ automations（mode=time, action_type=builtin|agent）
 * watches       bixby_conn prefs 的 watches（JSON 数组）→ automations（trigger_type=web_watch）
 * quicknote     quicknote.db / quicknote 全部列：码类（取件码 / 取餐码）→ pickups；能认出票的 → tickets
 * pickup_cards  bixby_s1_pickup prefs 的 cards（JSON 数组）→ pickups
 * </pre>
 * 每行带 src_key「bixbyui:&lt;来源&gt;:&lt;原 id&gt;」，计划 App 按 src_key 去重：重复导入不会出重复条目，
 * 已经在计划 App 里改过的条目也不会被覆盖（只插不改）。源数据一律只读。
 */
public final class RawImport {
    private RawImport() {}

    /** 映射结果：每张表要插入的行（都带 src_key），以及跳过的条目说明。 */
    public static final class Plan {
        public final Map<String, List<Row>> rows = new LinkedHashMap<String, List<Row>>();
        public final List<String> skipped = new ArrayList<String>();

        Plan() {
            for (String t : Contract.TABLES) rows.put(t, new ArrayList<Row>());
        }

        void add(String table, Row r) { rows.get(table).add(r); }

        /** 要插入的总行数。 */
        public int total() {
            int n = 0;
            for (List<Row> l : rows.values()) n += l.size();
            return n;
        }

        /** 各表行数摘要（日志 / 界面用）。 */
        public String counts() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<Row>> e : rows.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey()).append('=').append(e.getValue().size());
            }
            return sb.length() == 0 ? "（空）" : sb.toString();
        }
    }

    /** 把 {@link Contract#BUNDLE_FORMAT} 导入包映射成各表行；坏行跳过并记入 {@link Plan#skipped}，不抛出。 */
    public static Plan map(JSONObject b, long now, TimeZone tz) {
        Plan p = new Plan();
        if (b == null) return p;
        JSONArray a;
        if ((a = b.optJSONArray("sched_event")) != null) for (int i = 0; i < a.length(); i++) schedEvent(p, a.optJSONObject(i), now);
        if ((a = b.optJSONArray("s3_todo")) != null) for (int i = 0; i < a.length(); i++) s3Todo(p, a.optJSONObject(i));
        if ((a = b.optJSONArray("s3_task")) != null) for (int i = 0; i < a.length(); i++) s3Task(p, a.optJSONObject(i));
        if ((a = b.optJSONArray("event_task")) != null) for (int i = 0; i < a.length(); i++) eventTask(p, a.optJSONObject(i));
        if ((a = b.optJSONArray("job")) != null) for (int i = 0; i < a.length(); i++) job(p, a.optJSONObject(i));
        if ((a = b.optJSONArray("watches")) != null) for (int i = 0; i < a.length(); i++) watch(p, a.optJSONObject(i));
        if ((a = b.optJSONArray("quicknote")) != null) for (int i = 0; i < a.length(); i++) quicknote(p, a.optJSONObject(i), now, tz);
        if ((a = b.optJSONArray("pickup_cards")) != null) for (int i = 0; i < a.length(); i++) pickupCard(p, a.optJSONObject(i), now);
        return p;
    }

    /** 导入结果一句话：新增 + 已存在 + 没导进来 + 跳过 = 导出的行数（补「跳过」）。 */
    public static String summary(int inserted, int duplicate, int rejected, int skipped) {
        return "新增 " + inserted + " 条，已存在 " + duplicate + " 条" + (rejected > 0 ? "，没导进来 " + rejected + " 条" : "")
                + (skipped > 0 ? "，跳过 " + skipped + " 条" : "");
    }

    static Row base(String src, String id, long created) {
        return new Row().with("source", "import").with("src_key", "bixbyui:" + src + ":" + id).with("created_at", created);
    }

    static void schedEvent(Plan p, JSONObject o, long now) {
        if (o == null) return;
        if (o.optInt("deleted", 0) != 0) { p.skipped.add("sched_event:" + o.optLong("id") + " 已删除"); return; }
        if (o.optString("title", "").trim().length() == 0 || o.optLong("start_at") <= 0) { p.skipped.add("sched_event:" + o.optLong("id") + " 缺字段"); return; }
        Row r = base("sched", String.valueOf(o.optLong("id")), o.optLong("created_at", now));
        r.with("title", o.optString("title").trim()).with("start_at", o.optLong("start_at")).with("end_at", o.optLong("end_at"))
                .with("all_day", o.optInt("all_day")).with("location", o.optString("location", "")).with("note", o.optString("note", ""))
                .with("repeat", o.optInt("repeat")).with("week_mask", o.optInt("week_mask")).with("month_day", o.optInt("month_day"))
                .with("lunar", o.optInt("lunar")).with("lunar_month", o.optInt("lunar_month")).with("lunar_day", o.optInt("lunar_day"))
                .with("lead_min", o.optInt("lead_min", -1)).with("ext_id", o.optString("ext_id", ""));
        p.add(Contract.T_EVENTS, r);
    }

    static void s3Todo(Plan p, JSONObject o) {
        if (o == null) return;
        if (o.optString("text", "").trim().length() == 0) { p.skipped.add("s3_todo:" + o.optLong("id") + " 没有内容"); return; }
        Row r = base("todo", String.valueOf(o.optLong("id")), o.optLong("created_at"));
        r.with("title", o.optString("text").trim()).with("due_at", o.optLong("due_at")).with("done_at", o.optLong("done_at"))
                .with("remind", o.optLong("remind_task_id") > 0 || o.optLong("due_at") > 0 ? 1 : 0);
        p.add(Contract.T_TODOS, r);
    }

    static void s3Task(Plan p, JSONObject o) {
        if (o == null) return;
        boolean agent = o.optInt("kind") == 1;
        String title = o.optString("title", "").trim(), cmd = o.optString("command", "").trim();
        if (title.length() == 0) title = agent ? cmd : "提醒";
        if (agent && cmd.length() == 0) { p.skipped.add("s3_task:" + o.optLong("id") + " 没有指令"); return; }
        Row r = base("s3", String.valueOf(o.optLong("id")), o.optLong("created_at"));
        r.with("title", title).with("mode", Contract.MODE_TIME).with("trigger_type", "time").with("trigger_json", "{}")
                .with("action_type", agent ? Contract.ACT_AGENT : Contract.ACT_REMIND).with("action", agent ? cmd : title)
                .with("enabled", o.optInt("enabled", 1)).with("repeat", o.optInt("repeat")).with("week_mask", o.optInt("week_mask"))
                .with("hour", o.optInt("hour")).with("minute", o.optInt("minute")).with("once_at", o.optLong("once_at"))
                .with("next_at", o.optLong("next_at", -1)).with("last_run_at", o.optLong("last_fired_at"))
                .with("last_missed_at", o.optLong("last_missed_at")).with("last_result", o.optString("last_result", ""))
                .with("todo_id", o.optLong("todo_id")).with("owner", "bixbyui.s3");
        p.add(Contract.T_AUTOMATIONS, r);
    }

    static void eventTask(Plan p, JSONObject row) {
        if (row == null) return;
        JSONObject o;
        try {
            o = new JSONObject(row.optString("json", "{}"));
        } catch (Exception e) {
            p.skipped.add("event_task:" + row.optLong("id") + " JSON 坏了");
            return;
        }
        String src = o.optString("source", "").toLowerCase(java.util.Locale.ROOT);
        if (src.length() == 0) { p.skipped.add("event_task:" + row.optLong("id") + " 没有触发源"); return; }
        try {
            JSONObject trig = new JSONObject().put("on", o.optBoolean("on", true)).put("target", o.optString("target", ""))
                    .put("apps", o.optJSONArray("apps") == null ? new JSONArray() : o.optJSONArray("apps"))
                    .put("keywords", o.optJSONArray("keywords") == null ? new JSONArray() : o.optJSONArray("keywords"));
            JSONObject guard = new JSONObject().put("debounceMs", o.optLong("debounceMs", 600000)).put("windowStart", o.optInt("windowStart", -1))
                    .put("windowEnd", o.optInt("windowEnd", -1)).put("dailyCap", o.optInt("dailyCap", 5)).put("maxFailures", o.optInt("maxFailures", 3));
            JSONObject st = new JSONObject().put("paused", o.optBoolean("paused")).put("pausedReason", o.optString("pausedReason", ""))
                    .put("failures", o.optInt("failures")).put("dayKey", o.optString("dayKey", "")).put("firesToday", o.optInt("firesToday"));
            String cmd = o.optString("command", "").trim();
            Row r = base("ev", String.valueOf(row.optLong("id")), o.optLong("createdAt", row.optLong("created_at")));
            r.with("title", o.optString("title", "").trim().length() > 0 ? o.optString("title").trim() : cmd)
                    .with("mode", Contract.MODE_TRIGGER).with("trigger_type", src).with("trigger_json", trig.toString())
                    .with("guard_json", guard.toString()).with("state_json", st.toString())
                    .with("action_type", Contract.ACT_AGENT).with("action", cmd).with("enabled", o.optBoolean("enabled", true) ? 1 : 0)
                    .with("last_run_at", o.optLong("lastFireAt")).with("owner", "bixbyui.events");
            if (cmd.length() == 0) { p.skipped.add("event_task:" + row.optLong("id") + " 没有指令"); return; }
            p.add(Contract.T_AUTOMATIONS, r);
        } catch (Exception e) {
            p.skipped.add("event_task:" + row.optLong("id") + " " + e.getMessage());
        }
    }

    static void job(Plan p, JSONObject o) {
        if (o == null) return;
        String tpl = o.optString("template_id", ""), cmd = o.optString("command", "").trim();
        if (tpl.length() == 0 && cmd.length() == 0) { p.skipped.add("job:" + o.optLong("id") + " 没有模板也没有指令"); return; }
        Row r = base("job", String.valueOf(o.optLong("id")), o.optLong("created_at"));
        r.with("title", o.optString("title", "").trim().length() > 0 ? o.optString("title").trim() : (tpl.length() > 0 ? tpl : cmd))
                .with("mode", Contract.MODE_TIME).with("trigger_type", "time")
                .with("action_type", tpl.length() > 0 ? Contract.ACT_BUILTIN : Contract.ACT_AGENT).with("action", tpl.length() > 0 ? tpl : cmd)
                .with("enabled", o.optInt("enabled", 1)).with("repeat", o.optInt("repeat")).with("week_mask", o.optInt("week_mask"))
                .with("hour", o.optInt("hour")).with("minute", o.optInt("minute")).with("once_at", o.optLong("once_at"))
                .with("next_at", o.optLong("next_deliver_at", -1)).with("owner", "bixbyui.jobs");
        p.add(Contract.T_AUTOMATIONS, r);
    }

    static void watch(Plan p, JSONObject o) {
        if (o == null) return;
        JSONObject s = o.optJSONObject("spec") != null ? o.optJSONObject("spec") : o;
        String q = s.optString("query", "").trim();
        if (q.length() == 0) { p.skipped.add("watch:" + s.optString("id", "") + " 没有搜索词"); return; }
        try {
            JSONObject trig = new JSONObject().put("query", q).put("interval_min", s.optInt("interval_min", 360)).put("mode", s.optString("mode", "new"))
                    .put("keywords", s.optJSONArray("keywords") == null ? new JSONArray() : s.optJSONArray("keywords"));
            Row r = base("watch", s.optString("id", q), s.optLong("created_at"));
            r.with("title", s.optString("title", q)).with("mode", Contract.MODE_TRIGGER).with("trigger_type", "web_watch")
                    .with("trigger_json", trig.toString()).with("action_type", Contract.ACT_REMIND).with("action", "有新结果时告诉我")
                    .with("enabled", s.optBoolean("enabled", true) ? 1 : 0).with("owner", "bixbyui.watch");
            p.add(Contract.T_AUTOMATIONS, r);
        } catch (Exception e) {
            p.skipped.add("watch:" + q + " " + e.getMessage());
        }
    }

    static void quicknote(Plan p, JSONObject o, long now, TimeZone tz) {
        if (o == null) return;
        String id = String.valueOf(o.optLong("id"));
        if (o.optInt("deleted", 0) != 0) { p.skipped.add("quicknote:" + id + " 已删除"); return; }
        String kind = o.optString("code_kind", ""), code = o.optString("code_value", "").trim();
        if (code.length() > 0 && (kind.equals("取件码") || kind.equals("取餐码"))) {
            Row r = base("qn", id, o.optLong("created_at"));
            int st = o.optInt("code_state");
            r.with("code", code.replaceAll("\\s+", "")).with("kind", kind).with("station", where(o)).with("app_pkg", o.optString("app_pkg", ""))
                    .with("taken_at", st == 2 || st == 3 ? Math.max(o.optLong("updated_at"), 1L) : 0L).with("note", o.optString("summary", ""));
            if (Validator.CODE.matcher(r.s("code")).matches()) p.add(Contract.T_PICKUPS, r);
            else p.skipped.add("quicknote:" + id + " 码格式不对");
            return;
        }
        String text = o.optString("title", "") + "\n" + o.optString("summary", "") + "\n" + o.optString("body", "");
        Row t = TicketText.parse(text, o.optLong("created_at", now), tz);
        if (t != null) {
            t.putAll(base("qn", id, o.optLong("created_at")));
            t.with("note", o.optString("summary", ""));
            p.add(Contract.T_TICKETS, t);
        } else {
            // 不是码也不是票的记一记（文章、截图、随口记…）本来就不进计划 App，但要记一笔——
            //   否则日志「导出 5 行：新增 0 条，已存在 0 条」对不上（5 ≠ 0 + 0）
            p.skipped.add("quicknote:" + id + " 不是码也不是票（留在记一记）");
        }
    }

    static String where(JSONObject o) {
        try {
            JSONArray ents = new JSONArray(o.optString("entities", "[]"));
            for (int i = 0; i < ents.length(); i++) {
                JSONObject e = ents.optJSONObject(i);
                if (e != null && "ADDRESS".equals(e.optString("t"))) return e.optString("v", "");
            }
        } catch (Exception ignored) {
        }
        String app = o.optString("app_label", "");
        return app;
    }

    static void pickupCard(Plan p, JSONObject o, long now) {
        if (o == null) return;
        String code = o.optString("code", "").replaceAll("\\s+", "");
        if (code.length() == 0) { p.skipped.add("pickup_card:" + o.optString("id", "") + " 没有码"); return; }
        if (o.optLong("noteId") > 0) { p.skipped.add("pickup_card:" + o.optString("id", code) + " 记一记来的码（由 quicknote 那条导）"); return; }   // 避免重复
        Row r = base("card", o.optString("id", code), o.optLong("created", now));
        boolean expired = o.optLong("expires") > 0 && o.optLong("expires") < now;
        r.with("code", code).with("kind", o.optString("kind", "取件码")).with("station", o.optString("station", ""))
                .with("app_pkg", o.optString("pkg", "")).with("source", "import")
                .with("taken_at", o.optBoolean("dismissed") || expired ? Math.max(o.optLong("expires"), 1L) : 0L);
        if (Validator.CODE.matcher(code).matches()) p.add(Contract.T_PICKUPS, r);
        else p.skipped.add("pickup_card:" + o.optString("id", code) + " 码格式不对");
    }
}
