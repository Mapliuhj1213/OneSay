package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/** 快捷指令（计划 App 侧）：格式校验、留空询问、版本与撤销、「不对」两次标红、动作清单（上机版专属置灰）、定时触发指令。 */
public class ShortcutsTest {

    static final String STEPS = "[{\"tool\":\"clock.alarm.set\",\"args\":{\"hour\":7,\"minute\":null},\"title\":\"定闹钟\"},"
            + "{\"tool\":\"dnd.on\",\"args\":{}},{\"tool\":\"llm.ask\",\"args\":{\"prompt\":\"\"},\"ask\":[\"prompt\"]}]";

    static Row shortcut() {
        return new Row().with("name", "晚安").with("steps_json", STEPS).with("version", 1L).with("history_json", "[]");
    }

    @Test public void validatorAcceptsAndNormalizesShortcutRow() {
        Validator.Result r = Validator.validate(Contract.T_SHORTCUTS, new Row().with("name", " 晚安 ").with("steps_json", STEPS), true, null);
        assertTrue(r.toString(), r.ok());
        assertEquals("晚安", r.values.get("name"));
        List<Shortcuts.Step> steps = Shortcuts.parseSteps((String) r.values.get("steps_json"));
        assertEquals(3, steps.size());
        assertEquals("clock.alarm.set", steps.get(0).tool);
        assertEquals(7, steps.get(0).args.optInt("hour"));
        // 序列化再解析不丢字段
        List<Shortcuts.Step> again = Shortcuts.parseSteps(Shortcuts.stepsJson(steps));
        assertEquals("定闹钟", again.get(0).title);
        assertEquals("[prompt]", again.get(2).ask.toString());
    }

    @Test public void validatorRejectsBadShortcuts() {
        assertFalse(Validator.validate(Contract.T_SHORTCUTS, new Row().with("name", "x").with("steps_json", "[]"), true, null).ok());
        assertFalse(Validator.validate(Contract.T_SHORTCUTS, new Row().with("name", "x").with("steps_json", "[{\"tool\":\"Rm -rf\"}]"), true, null).ok());
        assertFalse(Validator.validate(Contract.T_SHORTCUTS, new Row().with("name", "  ").with("steps_json", STEPS), true, null).ok());
        assertFalse(Validator.validate(Contract.T_SHORTCUTS, new Row().with("steps_json", STEPS), true, null).ok());
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 21; i++) many.append(i > 0 ? "," : "").append("{\"tool\":\"dnd.on\"}");
        assertFalse(Validator.validate(Contract.T_SHORTCUTS, new Row().with("name", "x").with("steps_json", many + "]"), true, null).ok());
        assertFalse(Validator.validate(Contract.T_SHORTCUTS, new Row().with("name", "x").with("steps_json", STEPS).with("origin", "web"), true, null).ok());
    }

    @Test public void runRowsValidated() {
        Row ok = new Row().with("shortcut_id", 3L).with("started_at", 1794137400000L).with("status", Contract.SC_FALLBACK_OK).with("trigger_info", "tap");
        assertTrue(Validator.validate(Contract.T_SHORTCUT_RUNS, ok, true, null).ok());
        assertFalse(Validator.validate(Contract.T_SHORTCUT_RUNS, new Row(ok).with("status", "maybe"), true, null).ok());
        assertFalse(Validator.validate(Contract.T_SHORTCUT_RUNS, new Row(ok).with("shortcut_id", 0L), true, null).ok());
        assertFalse(Validator.validate(Contract.T_SHORTCUT_RUNS, new Row(ok).with("trigger_info", "x"), true, null).ok());
        assertTrue(Validator.validate(Contract.T_SHORTCUT_RUNS, Shortcuts.request(3, Contract.TRIG_LAUNCHER, 1794137400000L), true, null).ok());
    }

    @Test public void blankParamsAreAskedAtRunTime() {
        List<Shortcuts.Step> steps = Shortcuts.parseSteps(STEPS);
        assertEquals("[minute]", Shortcuts.askParams(steps.get(0)).toString());
        assertTrue(Shortcuts.askParams(steps.get(1)).isEmpty());
        assertEquals("[prompt]", Shortcuts.askParams(steps.get(2)).toString());
    }

    @Test public void replaceKeepsOldVersionAndUndoRestoresIt() throws Exception {
        Row cur = shortcut();
        String newer = "[{\"tool\":\"clock.alarm.set\",\"args\":{\"hour\":7,\"minute\":30}}]";
        Row w = Shortcuts.replaceSteps(cur, newer, "失败后按正常模式更新", 1794137400000L);
        assertEquals(newer, w.s("steps_json"));
        assertEquals(2L, w.l("version"));
        cur.putAll(w);
        assertTrue(Shortcuts.canUndo(cur));
        Row u = Shortcuts.undo(cur);
        assertNotNull(u);
        assertEquals(new org.json.JSONArray(STEPS).toString(), u.s("steps_json"));
        cur.putAll(u);
        assertFalse(Shortcuts.canUndo(cur));
        assertNull(Shortcuts.undo(cur));
    }

    @Test public void historyCappedAtFive() {
        Row cur = shortcut();
        for (int i = 0; i < 8; i++) cur.putAll(Shortcuts.replaceSteps(cur, "[{\"tool\":\"dnd.on\",\"args\":{\"i\":" + i + "}}]", "", 1794137400000L + i));
        assertEquals(Shortcuts.HISTORY_KEEP, Shortcuts.history(cur).length());
    }

    @Test public void twoConsecutiveWrongFlagsRed() {
        Row cur = shortcut();
        cur.putAll(Shortcuts.beginRun(cur, 1L));
        cur.putAll(Shortcuts.markWrong(cur));
        assertEquals(1L, cur.l("wrong_streak"));
        assertEquals(0L, cur.l("flagged"));
        cur.putAll(Shortcuts.markWrong(cur));   // 同一次运行点两下不算两次
        assertEquals(1L, cur.l("wrong_streak"));
        cur.putAll(Shortcuts.beginRun(cur, 2L));
        cur.putAll(Shortcuts.markWrong(cur));
        assertEquals(2L, cur.l("wrong_streak"));
        assertEquals(1L, cur.l("flagged"));
        assertTrue(cur.s("flag_reason").contains("不对"));
        // 修改步骤后清零
        cur.putAll(Shortcuts.replaceSteps(cur, "[{\"tool\":\"dnd.off\"}]", "用户修改", 3L));
        assertEquals(0L, cur.l("flagged"));
        assertEquals(0L, cur.l("wrong_streak"));
    }

    @Test public void wrongNotConsecutiveRestartsCount() {
        Row cur = shortcut();
        cur.putAll(Shortcuts.beginRun(cur, 1L));
        cur.putAll(Shortcuts.markWrong(cur));
        cur.putAll(Shortcuts.beginRun(cur, 2L));   // 这次没点「不对」
        cur.putAll(Shortcuts.beginRun(cur, 3L));
        cur.putAll(Shortcuts.markWrong(cur));
        assertEquals(1L, cur.l("wrong_streak"));
        assertEquals(0L, cur.l("flagged"));
    }

    @Test public void defaultCatalogGreysPrivilegedAndMissingTools() {
        Shortcuts.Actions.Catalog c = Shortcuts.Actions.defaults();
        assertFalse(c.probed);
        for (String s : new String[]{"dnd.on", "dnd.off", "volume.media.set", "settings.brightness.set", "brief.play"}) {
            Shortcuts.Actions.Action a = c.get(s);
            assertTrue(s, a.privileged);
            assertFalse(s + " 开源版默认关", a.available);
            assertEquals(Shortcuts.Actions.PRIV_REASON, a.reason);
        }
        assertFalse(c.get("media.save_clip").available);
        assertTrue(c.get("media.save_clip").reason.length() > 0);
        for (String s : new String[]{"clock.alarm.set", "s3.todo.add", "plan.event.add", "app.launch", "browser.open", "llm.ask"}) {
            assertTrue(s, c.get(s).available);
            assertFalse(s, c.get(s).privileged);
        }
    }

    @Test public void pushedCatalogWins() throws Exception {
        String json = new JSONObject().put("format", 1).put("actions", new org.json.JSONArray()
                .put(new JSONObject().put("sign", "dnd.on").put("label", "打开勿扰").put("privileged", true).put("available", true))
                .put(new JSONObject().put("sign", "app.launch").put("label", "打开 App").put("available", false).put("reason", "需要 Android 8.0")
                        .put("params", new org.json.JSONArray().put(new JSONObject().put("name", "app").put("label", "App"))))
                .put(new JSONObject().put("sign", "BAD SIGN"))).toString();
        Shortcuts.Actions.Catalog c = Shortcuts.Actions.parse(json);
        assertNotNull(c);
        assertTrue(c.probed);
        assertEquals(2, c.actions.size());
        assertTrue(c.get("dnd.on").available);
        assertFalse(c.get("app.launch").available);
        assertEquals("需要 Android 8.0", c.get("app.launch").reason);
        assertEquals("app", c.get("app.launch").params.get(0).name);
        assertNull(Shortcuts.Actions.parse("{\"format\":99,\"actions\":[]}"));
        assertNull(Shortcuts.Actions.parse("not json"));
    }

    @Test public void timedTriggerIsAnAgentAutomationThatBixbyCanRecognise() {
        Row a = Shortcuts.timedAutomation(12, "晚安");
        assertTrue(Validator.validate(Contract.T_AUTOMATIONS, new Row(a).with("hour", 22L).with("minute", 30L).with("repeat", 1L), true, null).ok());
        assertEquals(12, Shortcuts.commandId(a.s("action")));
        assertEquals("shortcut:time:12", a.s(Contract.C_SRC_KEY));
        assertEquals(-1, Shortcuts.commandId("提醒我喝水"));
    }

    @Test public void triggersAndNames() {
        assertTrue(Shortcuts.trigger(null, Contract.TRIG_VOICE));
        assertFalse(Shortcuts.trigger(null, Contract.TRIG_LAUNCHER));
        String t = Shortcuts.triggers(false, true, true, false);
        assertFalse(Shortcuts.trigger(t, Contract.TRIG_VOICE));
        assertTrue(Shortcuts.trigger(t, Contract.TRIG_LAUNCHER));
        assertEquals(Shortcuts.normName("晚 安！"), Shortcuts.normName("晚安"));
        assertNotNull(Shortcuts.checkName("一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一"));
    }

    @Test public void staleRequests() {
        Row r = Shortcuts.request(1, "tap", 1000L);
        assertFalse(Shortcuts.isStale(r, 1000L + Shortcuts.REQUEST_STALE_MS));
        assertTrue(Shortcuts.isStale(r, 1001L + Shortcuts.REQUEST_STALE_MS));
    }

    @Test public void schemaHasShortcutTables() {
        assertNotNull(Schema.table(Contract.T_SHORTCUTS));
        assertNotNull(Schema.table(Contract.T_SHORTCUT_RUNS));
        assertEquals(4, Contract.SCHEMA_VERSION);   // [borrow-1112] 原为 3：todos 加 strong 列（强提醒）升到 4
        assertTrue(Schema.SHORTCUTS.createSql().contains("triggers_json TEXT DEFAULT '{\"voice\":1"));
        // 执行记录里没有参数列（不存敏感参数）
        assertNull(Schema.SHORTCUT_RUNS.col("args"));
        assertNull(Schema.SHORTCUT_RUNS.col("steps_json"));
    }

    @Test public void pinShortcutGreyedWithReason() {
        Capabilities.Env e = new Capabilities.Env();
        e.sdkInt = 25;
        Capabilities.Status st = Capabilities.of(Capabilities.Feature.PIN_SHORTCUT, e);
        assertFalse(st.available);
        assertTrue(st.reason.contains("8.0"));
        e.sdkInt = 28;
        e.pinShortcutSupported = Boolean.FALSE;
        st = Capabilities.of(Capabilities.Feature.PIN_SHORTCUT, e);
        assertFalse(st.available);
        assertTrue(st.reason.contains("桌面"));
        e.pinShortcutSupported = null;   // 探测不到按可用
        assertTrue(Capabilities.of(Capabilities.Feature.PIN_SHORTCUT, e).available);
    }
}
