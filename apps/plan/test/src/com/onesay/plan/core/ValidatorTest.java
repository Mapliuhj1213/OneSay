package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.junit.Test;

/** 字段校验：后门写进来的数据不合常识就挡回去，原因是大白话。 */
public class ValidatorTest {

    static final long T = 1762300800000L; // 2025-11-05 08:00 CST

    static Validator.Result ins(String t, Row r) { return Validator.validate(t, r, true, null); }

    @Test public void unknownTableAndColumn() {
        assertFalse(ins("nope", new Row().with("a", 1)).ok());
        Validator.Result r = ins(Contract.T_TODOS, new Row().with("title", "x").with("hack", 1));
        assertFalse(r.ok());
        assertTrue(r.error.contains("hack"));
    }

    @Test public void readOnlyColumnsRejected() {
        for (String c : new String[]{"_id", "created_at", "updated_at"})
            assertFalse(c, ins(Contract.T_TODOS, new Row().with("title", "x").with(c, 5)).ok());
        assertFalse(ins(Contract.T_TICKETS, new Row().with("kind", "train").with("title", "x").with("depart_at", T).with("qr_path", "/etc/passwd")).ok());
    }

    @Test public void requiredOnInsertOnly() {
        assertFalse(ins(Contract.T_TODOS, new Row().with("note", "x")).ok());
        assertFalse(ins(Contract.T_TODOS, new Row().with("title", "   ")).ok());
        assertTrue(Validator.validate(Contract.T_TODOS, new Row().with("done_at", T), false, new Row().with("title", "x")).ok());
        assertFalse(Validator.validate(Contract.T_TODOS, new Row().with("title", ""), false, new Row().with("title", "x")).ok());
    }

    @Test public void intCoercion() {
        Validator.Result r = ins(Contract.T_TODOS, new Row().with("title", "x").with("due_at", String.valueOf(T)).with("remind", true));
        assertTrue(r.toString(), r.ok());
        assertEquals(T, r.values.get("due_at"));
        assertEquals(1L, r.values.get("remind"));
        assertFalse(ins(Contract.T_TODOS, new Row().with("title", "x").with("due_at", "明天")).ok());
        assertFalse(ins(Contract.T_TODOS, new Row().with("title", "x").with("due_at", 1.5)).ok());
    }

    @Test public void textTrimmedAndCapped() {
        Validator.Result r = ins(Contract.T_TODOS, new Row().with("title", "  交水费  "));
        assertEquals("交水费", r.values.get("title"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 201; i++) sb.append('长');
        assertFalse(ins(Contract.T_TODOS, new Row().with("title", sb.toString())).ok());
    }

    @Test public void eventTimes() {
        assertTrue(ins(Contract.T_EVENTS, new Row().with("title", "开会").with("start_at", T)).ok());
        assertFalse(ins(Contract.T_EVENTS, new Row().with("title", "开会").with("start_at", 15)).ok());
        assertFalse(ins(Contract.T_EVENTS, new Row().with("title", "开会").with("start_at", T).with("end_at", T - 1)).ok());
        assertFalse(ins(Contract.T_EVENTS, new Row().with("title", "开会").with("start_at", T).with("repeat", 2)).ok());
        assertTrue(ins(Contract.T_EVENTS, new Row().with("title", "开会").with("start_at", T).with("repeat", 2).with("week_mask", 4)).ok());
        assertFalse(ins(Contract.T_EVENTS, new Row().with("title", "开会").with("start_at", T).with("lead_min", -5)).ok());
    }

    @Test public void updateCrossCheckUsesExisting() {
        Row ex = new Row().with("title", "开会").with("start_at", T).with("end_at", T + 3600000L);
        assertFalse(Validator.validate(Contract.T_EVENTS, new Row().with("start_at", T + 7200000L), false, ex).ok());
        assertTrue(Validator.validate(Contract.T_EVENTS, new Row().with("start_at", T + 7200000L).with("end_at", 0), false, ex).ok());
    }

    @Test public void ticketKinds() {
        assertTrue(ins(Contract.T_TICKETS, new Row().with("kind", "train").with("title", "G1").with("depart_at", T)).ok());
        assertFalse(ins(Contract.T_TICKETS, new Row().with("kind", "rocket").with("title", "x").with("depart_at", T)).ok());
        assertFalse(ins(Contract.T_TICKETS, new Row().with("kind", "train").with("title", "x")).ok());
        assertFalse(ins(Contract.T_TICKETS, new Row().with("kind", "train").with("title", "x").with("depart_at", T).with("arrive_at", T - 60000)).ok());
        assertFalse(ins(Contract.T_TICKETS, new Row().with("kind", "train").with("title", "x").with("depart_at", T).with("status", 7)).ok());
    }

    @Test public void pickupCode() {
        assertTrue(ins(Contract.T_PICKUPS, new Row().with("code", "5-2-1234")).ok());
        assertTrue(ins(Contract.T_PICKUPS, new Row().with("code", "A12")).ok());
        assertFalse(ins(Contract.T_PICKUPS, new Row().with("code", "1")).ok());
        assertFalse(ins(Contract.T_PICKUPS, new Row().with("code", "12 34; DROP")).ok());
        assertFalse(ins(Contract.T_PICKUPS, new Row().with("code", "-123")).ok());
    }

    @Test public void automationModes() {
        Row ok = new Row().with("title", "吃药").with("mode", "time").with("trigger_type", "time").with("action_type", "remind").with("hour", 8);
        assertTrue(ins(Contract.T_AUTOMATIONS, ok).ok());
        assertFalse(ins(Contract.T_AUTOMATIONS, new Row(ok).with("trigger_type", "wifi")).ok());
        assertFalse(ins(Contract.T_AUTOMATIONS, new Row(ok).with("mode", "cron")).ok());
        assertFalse(ins(Contract.T_AUTOMATIONS, new Row(ok).with("hour", 24)).ok());
        assertFalse(ins(Contract.T_AUTOMATIONS, new Row(ok).with("minute", 60)).ok());
        assertFalse(ins(Contract.T_AUTOMATIONS, new Row(ok).with("repeat", 4)).ok());
        Row trig = new Row().with("title", "回家").with("mode", "trigger").with("trigger_type", "wifi").with("trigger_json", "{\"target\":\"家\"}")
                .with("action_type", "agent").with("action", "打开勿扰");
        assertTrue(ins(Contract.T_AUTOMATIONS, trig).ok());
        assertFalse(ins(Contract.T_AUTOMATIONS, new Row(trig).with("trigger_type", "telepathy")).ok());
        assertFalse(ins(Contract.T_AUTOMATIONS, new Row(trig).with("trigger_json", "[1,2]")).ok());
        assertFalse(ins(Contract.T_AUTOMATIONS, new Row(trig).with("trigger_json", "{坏")).ok());
        assertFalse(ins(Contract.T_AUTOMATIONS, new Row(trig).with("action", "")).ok());
        assertFalse(ins(Contract.T_AUTOMATIONS, new Row(trig).with("action_type", "shell")).ok());
    }

    @Test public void runs() {
        assertTrue(ins(Contract.T_RUNS, new Row().with("automation_id", 3).with("started_at", T).with("status", "ok")).ok());
        assertFalse(ins(Contract.T_RUNS, new Row().with("automation_id", 3).with("started_at", T).with("status", "great")).ok());
        assertFalse(ins(Contract.T_RUNS, new Row().with("automation_id", 0).with("started_at", T).with("status", "ok")).ok());
    }

    @Test public void upgradeAddsOnlyMissingColumns() {
        java.util.Set<String> have = new java.util.HashSet<String>();
        for (Schema.Col c : Schema.EVENTS.cols) if (!c.name.equals("deleted_at")) have.add(c.name);
        java.util.List<String> l = Schema.EVENTS.addMissingSql(have);
        assertEquals(1, l.size());
        assertEquals("ALTER TABLE events ADD COLUMN deleted_at INTEGER DEFAULT 0", l.get(0));
        have.add("deleted_at");
        assertTrue(Schema.EVENTS.addMissingSql(have).isEmpty());
    }

    @Test public void schemaSqlHasAllColumnsAndUniqueSrcKey() {
        for (Schema.Table t : Schema.ALL) {
            String sql = t.createSql();
            for (Schema.Col c : t.cols) assertTrue(t.name + "." + c.name, sql.contains(c.name + " "));
            assertTrue(t.indexSql()[0].contains("UNIQUE"));
            assertNotNull(Schema.table(t.name));
        }
        assertEquals(Contract.TABLES.length, Schema.ALL.length);
    }
}
