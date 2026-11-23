package com.onesay.assistant.v2.shortcut;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.planner.PlannerBackend;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** ★ a11y-labels：快捷指令 / 定时步骤 —— 每步失败策略（on_fail）、按上一步成败分支（when）、「点屏幕上的按钮」动作、旧格式兼容。 */
public class A11yShortcutStepTest {

    static ShortcutTest.FakePorts ports() {
        return new ShortcutTest.FakePorts()
                .add(ShortcutTest.cap("app.launch", SideEffect.DEVICE_CONTROL, 0, false, "intent", null))
                .add(ShortcutTest.cap("s3.todo.add", SideEffect.PERSISTENT_WRITE, 0, false, "s3", null))
                .add(ShortcutTest.cap("sms.send", SideEffect.PUBLIC_COMMS, 2, true, "intent", null));
    }

    static ShortcutModel.Shortcut sc(ShortcutModel.Step... steps) {
        ShortcutStore store = new ShortcutStore(new PlannerBackend.Mem());
        List<ShortcutModel.Step> l = new ArrayList<ShortcutModel.Step>();
        for (ShortcutModel.Step s : steps) l.add(s);
        return store.get(store.insert("测试", null, l, "ui"));
    }

    static ShortcutModel.Step st(String tool, String onFail, String when) throws Exception {
        ShortcutModel.Step s = new ShortcutModel.Step(tool, new JSONObject());
        s.onFail = onFail;
        s.when = when;
        return s;
    }

    @Test public void oldFormatWithoutOnFailStillStopsAtTheFirstFailure() throws Exception {
        ShortcutTest.FakePorts p = ports();
        p.results.put("app.launch", ExecutionResult.failed(ErrorCode.EXEC_FAILED, "打不开", "x", p.lookup("app.launch"), new JSONObject()));
        ShortcutModel.Shortcut s = sc(ShortcutModel.parseSteps("[{\"tool\":\"app.launch\",\"args\":{}},{\"tool\":\"s3.todo.add\",\"args\":{}}]").toArray(new ShortcutModel.Step[0]));
        ShortcutRunner.Result r = ShortcutRunner.run(s, p);
        assertEquals(ShortcutRunner.Status.FAILED, r.status);
        assertEquals(0, r.failedIndex);
        assertFalse(p.executed.contains("s3.todo.add"));
    }

    @Test public void onFailContinueKeepsGoingAndWhenBranchesOnThePreviousStep() throws Exception {
        ShortcutTest.FakePorts p = ports();
        p.results.put("app.launch", ExecutionResult.failed(ErrorCode.EXEC_FAILED, "打不开", "x", p.lookup("app.launch"), new JSONObject()));
        ShortcutModel.Shortcut s = sc(st("app.launch", "continue", ""), st("s3.todo.add", "", "prev_failed"), st("llm.ask", "", "prev_ok"));
        s.steps.get(2).args.put("prompt", "x");
        ShortcutRunner.Result r = ShortcutRunner.run(s, p);
        assertEquals(ShortcutRunner.Status.OK, r.status);
        assertTrue(r.runs.get(0).continuedAfterFail);
        assertFalse(r.runs.get(0).ok);
        assertTrue("上一步失败 → else 分支做了", p.executed.contains("s3.todo.add"));
        assertTrue("todo 成功 → prev_ok 分支也做了", p.executed.contains("llm.ask"));
        assertEquals("app.launch ✗ → s3.todo.add ✓ → llm.ask ✓", r.summary());
    }

    @Test public void whenPrevOkIsSkippedAfterAFailureAndSkipDoesNotResetTheBranch() throws Exception {
        ShortcutTest.FakePorts p = ports();
        p.results.put("app.launch", ExecutionResult.failed(ErrorCode.EXEC_FAILED, "打不开", "x", p.lookup("app.launch"), new JSONObject()));
        ShortcutModel.Shortcut s = sc(st("app.launch", "continue", ""), st("s3.todo.add", "", "prev_ok"), st("llm.ask", "", "prev_failed"));
        s.steps.get(2).args.put("prompt", "x");
        ShortcutRunner.Result r = ShortcutRunner.run(s, p);
        assertEquals(ShortcutRunner.Status.OK, r.status);
        assertTrue(r.runs.get(1).skipped);
        assertFalse(p.executed.contains("s3.todo.add"));
        assertTrue("跳过不改「上一步」的成败", p.executed.contains("llm.ask"));
        assertEquals("app.launch ✗ → s3.todo.add – → llm.ask ✓", r.summary());
    }

    @Test public void refusalStopsEvenWithOnFailContinue() throws Exception {
        ShortcutTest.FakePorts p = ports();
        p.allowConfirm = false;
        ShortcutModel.Shortcut s = sc(st("sms.send", "continue", ""), st("s3.todo.add", "", ""));
        ShortcutRunner.Result r = ShortcutRunner.run(s, p);
        assertTrue("门禁拒绝 / 用户没确认都停：" + r.status, r.status == ShortcutRunner.Status.REJECTED || r.status == ShortcutRunner.Status.DENIED);
        assertFalse(p.executed.contains("s3.todo.add"));
        p.allowConfirm = true;
        ShortcutModel.Shortcut s2 = sc(st("s3.todo.add", "continue", ""), st("app.launch", "", ""));
        p.results.put("s3.todo.add", ExecutionResult.failed(ErrorCode.USER_REJECTED, "不点", "x", p.lookup("s3.todo.add"), new JSONObject()));
        r = ShortcutRunner.run(s2, p);
        assertEquals("执行时被拒也停", ShortcutRunner.Status.REJECTED, r.status);
        assertFalse(p.executed.contains("app.launch"));
    }

    @Test public void onFailAndWhenRoundTripAndUnknownValuesFallBackToOldBehaviour() throws Exception {
        List<ShortcutModel.Step> l = ShortcutModel.parseSteps("[{\"tool\":\"app.launch\",\"args\":{},\"on_fail\":\"continue\",\"when\":\"prev_ok\"},"
                + "{\"tool\":\"app.launch\",\"args\":{},\"on_fail\":\"retry\",\"when\":\"sometimes\"}]");
        assertEquals("continue", l.get(0).onFail);
        assertEquals("prev_ok", l.get(0).when);
        assertEquals("", l.get(1).onFail);
        assertEquals("", l.get(1).when);
        JSONObject j = l.get(0).toJson();
        assertEquals("continue", j.getString("on_fail"));
        assertEquals("prev_ok", j.getString("when"));
        assertFalse(l.get(1).toJson().has("on_fail"));
        assertFalse(l.get(1).toJson().has("when"));
    }

    @Test public void uiTapIsAnInternalActionInTheCatalog() throws Exception {
        assertTrue(ShortcutActions.isInternal(ShortcutActions.UI_TAP));
        assertNotNull(ShortcutActions.internalCap(ShortcutActions.UI_TAP));
        assertNull(ShortcutActions.unavailableReason(ShortcutActions.UI_TAP, ShortcutActions.internalCap(ShortcutActions.UI_TAP), null, false));
        ShortcutActions.uiTapProbe = new ShortcutActions.Probe() {
            @Override public String reason() {
                return "读屏助手不可用";
            }
        };
        try {
            assertEquals("点屏幕：读屏助手不可用", ShortcutActions.unavailableReason(ShortcutActions.UI_TAP, ShortcutActions.internalCap(ShortcutActions.UI_TAP), null, false));
        } finally {
            ShortcutActions.uiTapProbe = null;
        }
        JSONArray a = new JSONObject(ShortcutActions.catalogJson(new ShortcutActions.Lookup() {
            @Override public com.onesay.assistant.v2.capability.Capability lookup(String sign) {
                return ShortcutActions.internalCap(sign);
            }
        }, null, false)).getJSONArray("actions");
        // [a11y-labels-1115 计划 App 已同步] 原断言「不推给计划 App」改为：推，且带中文名和 target 参数、可用
        JSONObject tap = null;
        for (int i = 0; i < a.length(); i++)
            if (ShortcutActions.UI_TAP.equals(a.getJSONObject(i).getString("sign"))) tap = a.getJSONObject(i);
        assertNotNull("计划 App 清单已同步，ui.tap 要推过去", tap);
        assertEquals("点屏幕上的按钮", tap.getString("label"));
        assertTrue(tap.getBoolean("available"));
        assertFalse("不是上机版专属", tap.getBoolean("privileged"));
        assertEquals(1, tap.getJSONArray("params").length());
        assertEquals("target", tap.getJSONArray("params").getJSONObject(0).getString("name"));
        assertEquals("string", tap.getJSONArray("params").getJSONObject(0).getString("type"));
        // 步骤 JSON 里照常能用
        java.util.List<ShortcutModel.Step> l = ShortcutModel.parseSteps("[{\"tool\":\"ui.tap\",\"args\":{\"target\":\"发送\"}}]");
        assertEquals(ShortcutActions.UI_TAP, l.get(0).tool);
        ShortcutTest.FakePorts p = ports();
        ShortcutRunner.Result r = ShortcutRunner.run(sc(l.get(0)), p);
        assertEquals(ShortcutRunner.Status.OK, r.status);
        assertEquals(java.util.Arrays.asList(ShortcutActions.UI_TAP), p.executed);
    }

    @Test public void uiTapCatalogEntryAsksForTheButtonText() {
        ShortcutActions.Action a = ShortcutActions.get(ShortcutActions.UI_TAP);
        assertNotNull(a);
        assertEquals("屏幕", a.group);
        assertFalse(a.privileged());
        assertNotNull(a.param("target"));
        assertEquals("要点哪个按钮？说按钮上的字", ShortcutActions.question(ShortcutActions.UI_TAP, "target", ShortcutActions.internalCap(ShortcutActions.UI_TAP)));
        for (String t : new String[]{a.label, a.group, a.param("target").label, a.param("target").question})
            assertFalse("用户可见文字不带品牌字样：" + t, t.contains("Bixby") || t.contains("三星"));
    }

    @Test public void uiTapPushedGreyedWhenProbeSaysNo() throws Exception {
        ShortcutActions.uiTapProbe = new ShortcutActions.Probe() {
            @Override public String reason() {
                return "读屏助手不可用";
            }
        };
        try {
            JSONArray a = new JSONObject(ShortcutActions.catalogJson(new ShortcutActions.Lookup() {
                @Override public com.onesay.assistant.v2.capability.Capability lookup(String sign) {
                    return ShortcutActions.internalCap(sign);
                }
            }, null, false)).getJSONArray("actions");
            boolean seen = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!ShortcutActions.UI_TAP.equals(o.getString("sign"))) continue;
                seen = true;
                assertFalse(o.getBoolean("available"));
                assertEquals("点屏幕：读屏助手不可用", o.getString("reason"));
            }
            assertTrue(seen);
        } finally {
            ShortcutActions.uiTapProbe = null;
        }
    }
}
