package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/** 一次性迁移：OneSay 原样导出 → 计划 App 的行。每行都要能通过字段校验、带稳定 src_key。 */
public class RawImportTest {

    static final long NOW = RemindersTest.NOW;

    static JSONObject bundle() throws Exception {
        JSONObject b = new JSONObject().put("format", Contract.BUNDLE_FORMAT);
        b.put("sched_event", new JSONArray()
                .put(new JSONObject().put("id", 1).put("title", "开会").put("start_at", NOW + 3600000L).put("end_at", 0).put("all_day", 0)
                        .put("location", "3 楼").put("note", "").put("repeat", 0).put("week_mask", 0).put("month_day", 0).put("lead_min", -1)
                        .put("source", "voice").put("created_at", NOW - 1000).put("updated_at", NOW).put("deleted", 0).put("deleted_at", 0)
                        .put("lunar", 0).put("lunar_month", 0).put("lunar_day", 0).put("ext_id", ""))
                .put(new JSONObject().put("id", 2).put("title", "删掉的").put("start_at", NOW).put("deleted", 1))
                .put(new JSONObject().put("id", 3).put("title", "每周例会").put("start_at", NOW).put("repeat", 2).put("week_mask", 2)));
        b.put("s3_todo", new JSONArray()
                .put(new JSONObject().put("id", 5).put("text", "交水费").put("due_at", NOW + 7200000L).put("created_at", NOW - 5).put("done_at", 0).put("remind_task_id", 9))
                .put(new JSONObject().put("id", 6).put("text", "  ").put("due_at", 0)));
        b.put("s3_task", new JSONArray()
                .put(new JSONObject().put("id", 9).put("kind", 0).put("repeat", 0).put("week_mask", 0).put("hour", 15).put("minute", 0)
                        .put("once_at", NOW + 7200000L).put("title", "交水费").put("command", "").put("next_at", NOW + 7200000L)
                        .put("last_fired_at", 0).put("last_missed_at", 0).put("last_result", "").put("enabled", 1).put("created_at", NOW).put("todo_id", 5))
                .put(new JSONObject().put("id", 10).put("kind", 1).put("repeat", 1).put("hour", 8).put("minute", 0).put("title", "")
                        .put("command", "总结一下今天的邮件").put("enabled", 1).put("next_at", -1)));
        b.put("event_task", new JSONArray()
                .put(new JSONObject().put("id", 1).put("created_at", NOW).put("json", new JSONObject().put("title", "回家").put("source", "WIFI")
                        .put("on", true).put("target", "Home").put("command", "打开勿扰").put("enabled", true).put("dailyCap", 3).toString()))
                .put(new JSONObject().put("id", 2).put("json", "{坏掉")));
        b.put("job", new JSONArray().put(new JSONObject().put("id", 1).put("title", "每日简报").put("template_id", "daily_digest").put("repeat", 1)
                .put("hour", 7).put("minute", 30).put("enabled", 1).put("next_deliver_at", -1)));
        b.put("watches", new JSONArray().put(new JSONObject().put("spec", new JSONObject().put("id", "w1").put("title", "显卡降价").put("query", "RTX 5090 降价")
                .put("interval_min", 360).put("enabled", true))));
        b.put("quicknote", new JSONArray()
                .put(new JSONObject().put("id", 11).put("code_kind", "取件码").put("code_value", "5-2-1234").put("code_state", 1).put("app_label", "菜鸟")
                        .put("entities", "[{\"t\":\"ADDRESS\",\"v\":\"示例驿站（虚构）\"}]").put("created_at", NOW - 100))
                .put(new JSONObject().put("id", 12).put("code_kind", "取餐码").put("code_value", "A88").put("code_state", 2).put("updated_at", NOW))
                .put(new JSONObject().put("id", 13).put("code_kind", "券码").put("code_value", "XYZ999"))
                .put(new JSONObject().put("id", 14).put("category", "订单").put("title", "12306 订单").put("summary", "")
                        .put("body", "G1234次 上海虹桥-北京南 11月8日 14:30开 05车12F号 检票口 A12").put("created_at", NOW))
                .put(new JSONObject().put("id", 15).put("category", "聊天").put("title", "聊天").put("body", "晚上吃啥")));
        b.put("pickup_cards", new JSONArray()
                .put(new JSONObject().put("id", "取件码:8-8-8888").put("code", "8-8-8888").put("kind", "取件码").put("station", "丰巢")
                        .put("created", NOW - 1000).put("expires", NOW + 3600000L).put("dismissed", false))
                .put(new JSONObject().put("id", "取件码:5-2-1234").put("code", "5-2-1234").put("kind", "取件码").put("noteId", 11)
                        .put("created", NOW).put("expires", NOW + 3600000L)));
        return b;
    }

    @Test public void mapsEverythingSensibly() throws Exception {
        RawImport.Plan p = RawImport.map(bundle(), NOW, RemindersTest.TZ);
        Map<String, List<Row>> r = p.rows;
        assertEquals(2, r.get(Contract.T_EVENTS).size());          // 删掉的不导
        assertEquals(1, r.get(Contract.T_TODOS).size());           // 空文本不导
        assertEquals(5, r.get(Contract.T_AUTOMATIONS).size());     // 2 定时 + 1 触发 + 1 简报 + 1 监控（坏 JSON 跳过）
        assertEquals(3, r.get(Contract.T_PICKUPS).size());         // 2 个码类记一记 + 1 张短信浮卡（记一记来的那张不重复）
        assertEquals(1, r.get(Contract.T_TICKETS).size());
        assertFalse(p.skipped.isEmpty());
    }

    /** 新增 0 条，已存在 0 条」——5 ≠ 0 + 0：不进计划 App 的行（普通记一记等）没记账。 */
    @Test public void everyExportedRowIsEitherMappedOrSkipped() throws Exception {
        JSONObject b = bundle();
        int exported = 0;
        java.util.Iterator<String> it = b.keys();
        while (it.hasNext()) {
            JSONArray a = b.optJSONArray(it.next());
            if (a != null) exported += a.length();
        }
        RawImport.Plan p = RawImport.map(b, NOW, RemindersTest.TZ);
        assertEquals("导出行数 = 映射行数 + 跳过行数：" + p.skipped, exported, p.total() + p.skipped.size());
        assertEquals(6, p.skipped.size());
    }

    @Test public void fivePlainQuickNotesAreFiveSkips() throws Exception {
        JSONArray q = new JSONArray();
        for (int i = 1; i <= 5; i++) q.put(new JSONObject().put("id", i).put("category", "文章").put("title", "随手记 " + i).put("body", "看看这个"));
        RawImport.Plan p = RawImport.map(new JSONObject().put("format", Contract.BUNDLE_FORMAT).put("quicknote", q), NOW, RemindersTest.TZ);
        assertEquals(0, p.total());
        assertEquals(5, p.skipped.size());
        assertEquals("新增 0 条，已存在 0 条，跳过 5 条", RawImport.summary(0, 0, 0, p.skipped.size()));
        assertEquals("新增 2 条，已存在 1 条", RawImport.summary(2, 1, 0, 0));
        assertEquals("新增 2 条，已存在 1 条，没导进来 1 条，跳过 3 条", RawImport.summary(2, 1, 1, 3));
    }

    @Test public void everyRowPassesValidatorAndHasSrcKey() throws Exception {
        RawImport.Plan p = RawImport.map(bundle(), NOW, RemindersTest.TZ);
        for (Map.Entry<String, List<Row>> e : p.rows.entrySet()) {
            for (Row row : e.getValue()) {
                Row x = new Row(row);
                x.remove("created_at");
                Validator.Result v = Validator.validate(e.getKey(), x, true, null);
                assertTrue(e.getKey() + " " + row + " → " + v, v.ok());
                assertTrue(row.s("src_key").startsWith("bixbyui:"));
            }
        }
    }

    @Test public void srcKeysStableAcrossRuns() throws Exception {
        RawImport.Plan a = RawImport.map(bundle(), NOW, RemindersTest.TZ), b = RawImport.map(bundle(), NOW + 99999, RemindersTest.TZ);
        for (String t : Contract.TABLES)
            for (int i = 0; i < a.rows.get(t).size(); i++) assertEquals(a.rows.get(t).get(i).s("src_key"), b.rows.get(t).get(i).s("src_key"));
    }

    @Test public void detailsMapped() throws Exception {
        RawImport.Plan p = RawImport.map(bundle(), NOW, RemindersTest.TZ);
        Row wifi = null, digest = null, agent = null;
        for (Row a : p.rows.get(Contract.T_AUTOMATIONS)) {
            if ("wifi".equals(a.s("trigger_type"))) wifi = a;
            if ("daily_digest".equals(a.s("action"))) digest = a;
            if ("总结一下今天的邮件".equals(a.s("action"))) agent = a;
        }
        assertNotNull(wifi);
        assertEquals("trigger", wifi.s("mode"));
        assertEquals("Home", new JSONObject(wifi.s("trigger_json")).getString("target"));
        assertEquals(3, new JSONObject(wifi.s("guard_json")).getInt("dailyCap"));
        assertEquals("builtin", digest.s("action_type"));
        assertEquals(7, digest.l("hour"));
        assertEquals("agent", agent.s("action_type"));
        assertEquals("总结一下今天的邮件", agent.s("title"));
        Row taken = null, onIsland = null;
        for (Row x : p.rows.get(Contract.T_PICKUPS)) {
            if ("A88".equals(x.s("code"))) taken = x;
            if ("5-2-1234".equals(x.s("code"))) onIsland = x;
        }
        assertTrue(taken.l("taken_at") > 0);
        assertEquals(0, onIsland.l("taken_at"));
        assertEquals("示例驿站（虚构）", onIsland.s("station"));
        Row ticket = p.rows.get(Contract.T_TICKETS).get(0);
        assertEquals("G1234", ticket.s("number"));
        assertEquals("bixbyui:qn:14", ticket.s("src_key"));
        Row ev = p.rows.get(Contract.T_EVENTS).get(0);
        assertEquals(NOW - 1000, ev.l("created_at"));
        assertEquals("3 楼", ev.s("location"));
    }

    @Test public void emptyOrForeignBundle() throws Exception {
        assertEquals(0, RawImport.map(new JSONObject(), NOW, RemindersTest.TZ).total());
        assertEquals(0, RawImport.map(null, NOW, RemindersTest.TZ).total());
    }
}
