package com.onesay.assistant.v2.planner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * [planner] 一次性迁移包（{@link PlannerContract#BUNDLE_FORMAT}，纯 Java）：各库表原样导出，映射在计划 App（core/RawImport）。
 * 只导 S3 定时任务 / 待办、本地日程、事件触发任务、记一记里的码与票、灵动岛取件码浮卡。
 * <b>不导</b> jobs.db 的模板任务（每日简报等）和联网监控（watches）：它们仍由 OneSay 自己管，搬过去会变成「计划里能看、改了没用」的死副本。
 * sinceMs &gt; 0 时只导这之后新建的行（计划 App 不在时本地新增的，补导用；避免把用户在计划 App 里删掉的旧条目又导回去）。
 */
public final class MigrationBundle {
    private MigrationBundle() {}

    public static JSONObject build(Map<String, List<Map<String, Object>>> tables, String pickupCardsJson, long sinceMs, long now) {
        JSONObject b = new JSONObject();
        try {
            b.put("format", PlannerContract.BUNDLE_FORMAT).put("source_pkg", "com.onesay.assistant").put("exported_at", now).put("since", sinceMs);
            for (String t : new String[]{"sched_event", "s3_task", "s3_todo", "event_task", "quicknote"}) {
                List<Map<String, Object>> rows = tables.get(t);
                JSONArray a = new JSONArray();
                if (rows != null) {
                    for (Map<String, Object> r : rows) {
                        if (sinceMs > 0 && created(r) <= sinceMs) continue;
                        JSONObject o = new JSONObject();
                        for (Map.Entry<String, Object> e : r.entrySet()) o.put(e.getKey(), e.getValue() == null ? JSONObject.NULL : e.getValue());
                        a.put(o);
                    }
                }
                b.put(t, a);
            }
            JSONArray cards = new JSONArray();
            if (pickupCardsJson != null && pickupCardsJson.trim().startsWith("[")) {
                JSONArray in = new JSONArray(pickupCardsJson);
                for (int i = 0; i < in.length(); i++) {
                    JSONObject c = in.optJSONObject(i);
                    if (c != null && (sinceMs <= 0 || c.optLong("created") > sinceMs)) cards.put(c);
                }
            }
            b.put("pickup_cards", cards);
        } catch (Exception e) {
            throw new PlannerBackend.PlannerException("打包失败: " + e.getMessage(), e);
        }
        return b;
    }

    static long created(Map<String, Object> r) {
        Object v = r.get("created_at");
        if (v instanceof Number) return ((Number) v).longValue();
        return 0;
    }

    public static int count(JSONObject b) {
        int n = 0;
        java.util.Iterator<String> it = b.keys();
        while (it.hasNext()) {
            JSONArray a = b.optJSONArray(it.next());
            if (a != null) n += a.length();
        }
        return n;
    }
}
