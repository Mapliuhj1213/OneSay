package com.onesay.assistant.v2.shortcut;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.executor.ConsentPolicy;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.planner.PlannerBackend;
import com.onesay.assistant.v2.planner.PlannerContract;
import com.onesay.assistant.v2.platform.DeviceCap;
import com.onesay.assistant.v2.platform.DeviceProfile;
import com.onesay.assistant.v2.platform.Tri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 快捷指令（OneSay 侧）：保存 / 读取、参数留空询问、逐步执行、失败转正常模式并更新（保留旧版本、可撤销）、正常模式失败不更新、
 * 确认卡不可绕过、「不对」两次标红、能力不支持置灰（含开源构建关闭上机版专属动作）、命中、执行记录不存参数。
 */
public class ShortcutTest {

    // ------------------------------------------------------------------ 假宿主

    static Capability cap(String sign, SideEffect e, int risk, boolean confirm, String executor, ParamsSchema s) {
        return new Capability.Builder(sign, sign + " 摘要").domain("test").category("test").sideEffect(e).risk(risk, confirm)
                .executor(executor).schema(s == null ? new ParamsSchema() : s).build();
    }

    static final class FakePorts implements ShortcutRunner.Ports {
        final Map<String, Capability> caps = new HashMap<String, Capability>();
        final Map<String, String> unavailable = new HashMap<String, String>();
        final Map<String, ExecutionResult> results = new HashMap<String, ExecutionResult>();
        final LinkedList<String> answers = new LinkedList<String>();
        final List<String> asked = new ArrayList<String>();
        final List<String> announced = new ArrayList<String>();
        final List<String> confirmed = new ArrayList<String>();
        final List<String> executed = new ArrayList<String>();
        final List<JSONObject> execArgs = new ArrayList<JSONObject>();
        final List<Boolean> execTainted = new ArrayList<Boolean>();
        final List<ExecutionGate.ConfirmToken> tokens = new ArrayList<ExecutionGate.ConfirmToken>();
        boolean allowConfirm = true;

        FakePorts add(Capability c) {
            caps.put(c.sign, c);
            return this;
        }

        @Override public Capability lookup(String sign) {
            Capability c = caps.get(sign);
            return c != null ? c : ShortcutActions.internalCap(sign);
        }

        @Override public String unavailableReason(String sign, Capability cap) {
            if (unavailable.containsKey(sign)) return unavailable.get(sign);
            return ShortcutActions.unavailableReason(sign, cap, null, true);
        }

        @Override public String ask(String question, String param) {
            asked.add(question);
            return answers.isEmpty() ? null : answers.removeFirst();
        }

        @Override public boolean confirm(Capability cap, JSONObject args, String reason, int tier, boolean tainted) {
            confirmed.add(cap.sign);
            return allowConfirm;
        }

        @Override public ExecutionResult execute(Capability cap, JSONObject args, ExecutionGate.ConfirmToken token, boolean tainted) {
            executed.add(cap.sign);
            execArgs.add(args);
            execTainted.add(tainted);
            tokens.add(token);
            ExecutionResult r = results.get(cap.sign);
            if (r != null) return r;
            String out = ShortcutActions.LLM_ASK.equals(cap.sign) ? "模型说：明天降温" : "好了";
            return ExecutionResult.success(cap, args, out, true, "fake");
        }

        @Override public void announce(String text) {
            announced.add(text);
        }

        @Override public boolean cancelled() {
            return false;
        }
    }

    static final class FakeNormal implements ShortcutFlow.Normal {
        final List<String> prompts = new ArrayList<String>();
        ShortcutFlow.NormalResult next = new ShortcutFlow.NormalResult();

        @Override public ShortcutFlow.NormalResult run(String prompt) {
            prompts.add(prompt);
            return next;
        }
    }

    PlannerBackend.Mem mem;
    ShortcutStore store;
    FakePorts ports;
    FakeNormal normal;

    static final ParamsSchema ALARM = new ParamsSchema().addInteger("hour", true, 0, 23, null).addInteger("minute", false, 0, 59, "0");

    @Before public void setUp() {
        mem = new PlannerBackend.Mem();
        store = new ShortcutStore(mem);
        ports = new FakePorts()
                .add(cap("clock.alarm.set", SideEffect.DEVICE_CONTROL, 0, false, "alarmbridge", ALARM))
                .add(cap("app.launch", SideEffect.DEVICE_CONTROL, 0, false, "intent", new ParamsSchema().addString("app", true, null)))
                .add(cap("volume.media.set", SideEffect.DEVICE_CONTROL, 1, false, "shell", new ParamsSchema().addInteger("level", true, 0, 15, null)))
                .add(cap("dnd.on", SideEffect.DEVICE_CONTROL, 1, false, "custom", null))
                .add(cap("s3.todo.add", SideEffect.PERSISTENT_WRITE, 1, false, "s3", new ParamsSchema().addString("text", true, null)))
                .add(cap("sms.send", SideEffect.PUBLIC_COMMS, 2, true, "intent", null))
                .add(cap("pay.order", SideEffect.PERSISTENT_WRITE, 2, true, "intent", null))
                .add(cap("file.delete", SideEffect.DESTRUCTIVE, 1, false, "shell", null))
                .add(cap("phone.operate", SideEffect.DEVICE_CONTROL, 1, false, "agent", null));
        normal = new FakeNormal();
    }

    @After public void tearDown() {
        ConsentPolicy.revoke("pay.order");
    }

    static ShortcutModel.Step step(String tool, String argsJson) throws Exception {
        return new ShortcutModel.Step(tool, new JSONObject(argsJson));
    }

    long saveShortcut(String name, ShortcutModel.Step... steps) {
        List<ShortcutModel.Step> l = new ArrayList<ShortcutModel.Step>();
        for (ShortcutModel.Step s : steps) l.add(s);
        return store.insert(name, null, l, "ui");
    }

    // ------------------------------------------------------------------ 保存 / 读取

    @Test public void saveFromChatStoresActualToolsAndArgs() throws Exception {
        List<ShortcutFlow.NormalStep> steps = new ArrayList<ShortcutFlow.NormalStep>();
        steps.add(new ShortcutFlow.NormalStep("task.todo", new JSONObject(), true));   // 循环控制类：略过
        steps.add(new ShortcutFlow.NormalStep("clock__alarm__set", new JSONObject("{\"hour\":7,\"minute\":0}"), true));   // wire 名还原成签名
        steps.add(new ShortcutFlow.NormalStep("app.launch", new JSONObject("{\"app\":\"微信\"}"), false));   // 失败的不存
        steps.add(new ShortcutFlow.NormalStep("app.launch", new JSONObject("{\"app\":\"网易云音乐\",\"api_key\":\"sk-abcdef123456\"}"), true));
        ShortcutFlow.Saved sv = ShortcutFlow.saveFromChat("明早七点叫我然后打开网易云", steps, store, ports);
        assertNull(sv.error, sv.error);
        ShortcutModel.Shortcut s = store.get(sv.id);
        assertEquals("明早七点叫我然后打开网易云", s.name);
        assertEquals("chat", s.origin);
        assertEquals(2, s.steps.size());
        assertEquals("clock.alarm.set", s.steps.get(0).tool);
        assertEquals(7, s.steps.get(0).args.getInt("hour"));
        assertEquals("网易云音乐", s.steps.get(1).args.getString("app"));
        // 密钥不进计划 App：改成每次问
        assertTrue(s.steps.get(1).args.isNull("api_key"));
        assertTrue(s.steps.get(1).askParams().contains("api_key"));
        assertFalse(mem.list(PlannerContract.T_SHORTCUTS).toString().contains("sk-abcdef123456"));
        // 再存一次同一句：不重复建
        ShortcutFlow.Saved again = ShortcutFlow.saveFromChat("明早七点叫我然后打开网易云", steps, store, ports);
        assertTrue(again.existed);
        assertEquals(sv.id, again.id);
        assertEquals(1, store.all().size());
    }

    @Test public void saveRefusesGuiReplay() throws Exception {
        List<ShortcutFlow.NormalStep> steps = new ArrayList<ShortcutFlow.NormalStep>();
        steps.add(new ShortcutFlow.NormalStep("phone.operate", new JSONObject("{\"goal\":\"点外卖\"}"), true));
        ShortcutFlow.Saved sv = ShortcutFlow.saveFromChat("点个外卖", steps, store, ports);
        assertNotNull(sv.error);
        assertTrue(store.all().isEmpty());
        ShortcutFlow.Saved none = ShortcutFlow.saveFromChat("随便聊聊", new ArrayList<ShortcutFlow.NormalStep>(), store, ports);
        assertNotNull(none.error);
    }

    @Test public void rowRoundTrip() throws Exception {
        ShortcutModel.Step a = step("clock.alarm.set", "{\"hour\":7,\"minute\":null}");
        a.title = "定闹钟";
        long id = saveShortcut("晚安", a, step("dnd.on", "{}"));
        ShortcutModel.Shortcut s = store.get(id);
        assertEquals("晚安", s.name);
        assertEquals(1, s.version);
        assertTrue(s.trigger(ShortcutModel.TRIG_VOICE));
        assertFalse(s.trigger(ShortcutModel.TRIG_LAUNCHER));
        assertEquals("定闹钟", s.steps.get(0).title);
        assertEquals("[minute]", s.steps.get(0).askParams().toString());
        assertNotNull(ShortcutModel.checkSteps("[{\"tool\":\"rm -rf\"}]"));
    }

    // ------------------------------------------------------------------ 留空询问 / 逐步执行

    @Test public void blankParamsAreAskedAndCoerced() throws Exception {
        long id = saveShortcut("起床", step("clock.alarm.set", "{\"hour\":7,\"minute\":null}"));
        ports.answers.add("三十");
        ShortcutFlow.Outcome o = ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        assertEquals(ShortcutModel.SC_OK, o.status);
        assertEquals("已执行", o.text);
        assertEquals("[几分？]", ports.asked.toString());
        assertEquals(30, ports.execArgs.get(0).getInt("minute"));
        assertEquals(7, ports.execArgs.get(0).getInt("hour"));
    }

    @Test public void noAnswerCancelsWithoutFallback() throws Exception {
        long id = saveShortcut("起床", step("clock.alarm.set", "{\"hour\":null}"));
        ShortcutFlow.Outcome o = ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        assertEquals(ShortcutModel.SC_CANCELLED, o.status);
        assertTrue(ports.executed.isEmpty());
        assertTrue("取消不转正常模式", normal.prompts.isEmpty());
    }

    @Test public void stepsRunInOrderAnnounceOnceAndPassPrevOutput() throws Exception {
        long id = saveShortcut("出门", step("llm.ask", "{\"prompt\":\"今天要带伞吗\"}"), step("s3.todo.add", "{\"text\":\"{{prev}}\"}"),
                step("app.launch", "{\"app\":\"地图\"}"));
        ShortcutFlow.Outcome o = ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        assertEquals(ShortcutModel.SC_OK, o.status);
        assertEquals("[llm.ask, s3.todo.add, app.launch]", ports.executed.toString());
        assertEquals("只说一句", 1, ports.announced.size());
        assertEquals(ShortcutRunner.ANNOUNCE, ports.announced.get(0));
        assertEquals("模型说：明天降温", ports.execArgs.get(1).getString("text"));
        assertFalse(ports.execTainted.get(0));
        assertTrue("模型输出喂给下一步 → 按不可信过闸", ports.execTainted.get(1));
        assertFalse(ports.execTainted.get(2));
        assertEquals("模型输出写进待办 = 不可信参数的写入 → 门禁要求确认卡", "[s3.todo.add]", ports.confirmed.toString());
    }

    // ------------------------------------------------------------------ 失败 → 正常模式 → 更新 / 撤销

    @Test public void failureFallsBackToNormalAndUpdatesKeepingOldVersion() throws Exception {
        long id = saveShortcut("晚安", step("clock.alarm.set", "{\"hour\":7}"), step("app.launch", "{\"app\":\"白噪音\"}"));
        ports.results.put("app.launch", ExecutionResult.failed(ErrorCode.NOT_FOUND_APP, "没找到白噪音", "x", null, null));
        ShortcutFlow.NormalResult nr = new ShortcutFlow.NormalResult();
        nr.finished = true;
        nr.steps.add(new ShortcutFlow.NormalStep("app.launch", new JSONObject("{\"app\":\"潮汐\"}"), true));
        normal.next = nr;
        String before = ShortcutModel.stepsJson(store.get(id).steps);

        ShortcutFlow.Outcome o = ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        assertEquals(ShortcutModel.SC_FALLBACK_OK, o.status);
        assertTrue(o.updated);
        assertTrue(o.text, o.text.contains("更新了快捷指令"));
        assertEquals(1, normal.prompts.size());
        String prompt = normal.prompts.get(0);
        assertTrue(prompt, prompt.contains("已经做好了，不要重复"));
        assertTrue(prompt, prompt.contains("没找到白噪音"));
        ShortcutModel.Shortcut s = store.get(id);
        assertEquals(2, s.version);
        assertEquals("clock.alarm.set", s.steps.get(0).tool);
        assertEquals("潮汐", s.steps.get(1).args.getString("app"));
        assertTrue(ShortcutModel.canUndo(s));
        // 执行记录：fallback_ok，updated=1，失败在第 2 步
        Map<String, Object> run = mem.list(PlannerContract.T_SHORTCUT_RUNS).get(0);
        assertEquals("fallback_ok", run.get("status"));
        assertEquals(1, ((Number) run.get("updated")).intValue());
        assertEquals(1, ((Number) run.get("failed_step")).intValue());
        assertEquals("normal", run.get("mode"));
        // 撤销 → 回到旧版本
        assertTrue(ShortcutFlow.undo(id, store));
        assertEquals(before, ShortcutModel.stepsJson(store.get(id).steps));
        assertFalse(ShortcutFlow.undo(id, store));
    }

    @Test public void normalModeFailureDoesNotUpdate() throws Exception {
        long id = saveShortcut("晚安", step("app.launch", "{\"app\":\"白噪音\"}"));
        ports.results.put("app.launch", ExecutionResult.failed(ErrorCode.EXEC_FAILED, "打不开", "x", null, null));
        ShortcutFlow.NormalResult nr = new ShortcutFlow.NormalResult();
        nr.finished = false;
        nr.reason = "轮数用完";
        nr.steps.add(new ShortcutFlow.NormalStep("app.launch", new JSONObject("{\"app\":\"潮汐\"}"), true));
        normal.next = nr;
        ShortcutFlow.Outcome o = ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        assertEquals(ShortcutModel.SC_FALLBACK_FAIL, o.status);
        assertFalse(o.updated);
        assertTrue(o.text.contains("没有改"));
        assertEquals(1, store.get(id).version);
        assertEquals("白噪音", store.get(id).steps.get(0).args.getString("app"));
        assertEquals("fallback_fail", mem.list(PlannerContract.T_SHORTCUT_RUNS).get(0).get("status"));
    }

    @Test public void normalModeWithGuiStepFinishesButDoesNotUpdate() throws Exception {
        long id = saveShortcut("外卖", step("app.launch", "{\"app\":\"美团\"}"));
        ports.results.put("app.launch", ExecutionResult.failed(ErrorCode.NOT_FOUND_APP, "没装", "x", null, null));
        ShortcutFlow.NormalResult nr = new ShortcutFlow.NormalResult();
        nr.finished = true;
        nr.answer = "已经下单页面打开了";
        nr.steps.add(new ShortcutFlow.NormalStep("phone.operate", new JSONObject("{\"goal\":\"x\"}"), true));
        normal.next = nr;
        ShortcutFlow.Outcome o = ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        assertEquals(ShortcutModel.SC_FALLBACK_OK, o.status);
        assertFalse(o.updated);
        assertTrue(o.text.startsWith("已经下单页面打开了"));
        assertEquals(1, store.get(id).version);
    }

    @Test public void unsupportedStepFallsBackToNormalMode() throws Exception {
        long id = saveShortcut("安静", step("dnd.on", "{}"));
        ports.unavailable.put("dnd.on", ShortcutActions.REASON_PRIVILEGED_OFF);
        ShortcutFlow.NormalResult nr = new ShortcutFlow.NormalResult();
        nr.finished = true;
        nr.answer = "已经打开勿扰设置页";
        normal.next = nr;
        ShortcutFlow.Outcome o = ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        assertTrue(ports.executed.isEmpty());
        assertTrue(normal.prompts.get(0).contains(ShortcutActions.REASON_PRIVILEGED_OFF));
        assertEquals(ShortcutModel.SC_FALLBACK_OK, o.status);
    }

    // ------------------------------------------------------------------ 确认卡不可绕过

    @Test public void commsStepAlwaysShowsCardAndOnlyRunsWithApprovedToken() throws Exception {
        long id = saveShortcut("报平安", step("sms.send", "{\"number\":\"13800138000\",\"text\":\"到家了\"}"));
        ports.allowConfirm = false;
        ShortcutFlow.Outcome o = ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        assertEquals("[sms.send]", ports.confirmed.toString());
        assertTrue("没确认就不执行", ports.executed.isEmpty());
        assertEquals(ShortcutModel.SC_DENIED, o.status);
        assertTrue("没确认不转正常模式", normal.prompts.isEmpty());

        ports.allowConfirm = true;
        ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        assertEquals("[sms.send]", ports.executed.toString());
        ExecutionGate.ConfirmToken t = ports.tokens.get(0);
        assertNotNull("凭确认卡签发的令牌执行", t);
        assertEquals(ExecutionGate.TokenState.APPROVED, t.state());
        assertEquals("sms.send", t.sign);
    }

    @Test public void alwaysAllowDoesNotSkipCardForSpendingOrDelete() throws Exception {
        ConsentPolicy.allowAlways("pay.order");   // 用户以前点过「始终允许」
        assertTrue("门禁因始终允许放行", ExecutionGate.evaluate(ports.lookup("pay.order"), new JSONObject(), false).allowed());
        long id = saveShortcut("买咖啡", step("pay.order", "{\"item\":\"拿铁\"}"));
        ports.allowConfirm = false;
        ShortcutFlow.Outcome o = ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        assertEquals("快捷指令里照样弹卡", "[pay.order]", ports.confirmed.toString());
        assertTrue(ports.executed.isEmpty());
        assertEquals(ShortcutModel.SC_DENIED, o.status);

        // 删除类：风险低于 2 门禁本来直接放行，快捷指令里也要弹卡
        assertTrue(ExecutionGate.evaluate(ports.lookup("file.delete"), new JSONObject(), false).allowed());
        long id2 = saveShortcut("清缓存", step("file.delete", "{\"path\":\"/sdcard/tmp\"}"));
        ShortcutFlow.run(store.get(id2), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        assertEquals("[pay.order, file.delete]", ports.confirmed.toString());
        assertTrue(ports.executed.isEmpty());

        ports.allowConfirm = true;
        ShortcutFlow.run(store.get(id2), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        assertEquals("[file.delete]", ports.executed.toString());
    }

    @Test public void benignStepsDoNotAskForConfirmation() {
        assertFalse(ShortcutSafety.alwaysConfirm(ports.lookup("clock.alarm.set")));
        assertFalse(ShortcutSafety.alwaysConfirm(ports.lookup("app.launch")));
        assertTrue(ShortcutSafety.alwaysConfirm(ports.lookup("sms.send")));
        assertTrue(ShortcutSafety.alwaysConfirm(null));
    }

    @Test public void runnerIsWhitelistedApproveCaller() {
        assertTrue(java.util.Arrays.asList(ExecutionGate.APPROVE_CALLERS).contains("shortcut/ShortcutRunner.java"));
    }

    // ------------------------------------------------------------------ 「不对」两次标红

    @Test public void twoConsecutiveWrongMarksRed() throws Exception {
        long id = saveShortcut("晚安", step("app.launch", "{\"app\":\"潮汐\"}"));
        ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1L + 1794137400000L);
        assertFalse(ShortcutFlow.markWrong(id, store));
        assertFalse("同一次运行点两下不算两次", ShortcutFlow.markWrong(id, store));
        ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 2L + 1794137400000L);
        assertTrue(ShortcutFlow.markWrong(id, store));
        Map<String, Object> row = mem.get(PlannerContract.T_SHORTCUTS, id);
        assertEquals(1, ((Number) row.get("flagged")).intValue());
        assertTrue(String.valueOf(row.get("flag_reason")).contains("不对"));
        // 执行结果仍只报「已执行」（不由机器判定对不对）
        assertEquals("ok", row.get("last_status"));
    }

    @Test public void wrongNotConsecutiveDoesNotFlag() throws Exception {
        long id = saveShortcut("晚安", step("app.launch", "{\"app\":\"潮汐\"}"));
        ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        ShortcutFlow.markWrong(id, store);
        ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400001L);
        ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400002L);
        assertFalse(ShortcutFlow.markWrong(id, store));
    }

    // ------------------------------------------------------------------ 能力不支持置灰（含开源构建）

    @Test public void ossBuildKeepsPrivilegedActionsOff() {
        assertFalse("开源构建默认关闭上机版专属动作", ShortcutBuild.PRIVILEGED_ACTIONS);
    }

    @Test public void privilegedActionsGreyedInOssBuildAndByProbe() {
        Capability dnd = ports.lookup("dnd.on"), vol = ports.lookup("volume.media.set"), app = ports.lookup("app.launch");
        assertTrue(ShortcutActions.isPrivileged("dnd.on", dnd));
        assertTrue("执行器是 root shell 的也算", ShortcutActions.isPrivileged("x.y", vol));
        assertFalse(ShortcutActions.isPrivileged("app.launch", app));
        assertEquals(ShortcutActions.REASON_PRIVILEGED_OFF, ShortcutActions.unavailableReason("dnd.on", dnd, null, false));
        assertEquals(ShortcutActions.REASON_PRIVILEGED_OFF, ShortcutActions.unavailableReason("volume.media.set", vol, null, false));
        assertNull("非特权动作在开源构建可用", ShortcutActions.unavailableReason("app.launch", app, null, false));
        // 上机版：探测为没有 root → 置灰写原因；没探过（UNKNOWN）→ 不置灰
        DeviceProfile noRoot = new DeviceProfile.Builder().sdkInt(28).set(DeviceCap.ROOT, Tri.NO).set(DeviceCap.EXEC_DAEMON, Tri.NO).build();
        String why = ShortcutActions.unavailableReason("dnd.on", dnd, noRoot, true);
        assertNotNull(why);
        assertTrue(why, why.contains("root"));
        assertNull(ShortcutActions.unavailableReason("dnd.on", dnd, null, true));
        DeviceProfile rooted = new DeviceProfile.Builder().sdkInt(28).set(DeviceCap.ROOT, Tri.YES).set(DeviceCap.EXEC_DAEMON, Tri.YES).build();
        assertNull(ShortcutActions.unavailableReason("dnd.on", dnd, rooted, true));
    }

    @Test public void missingToolsGreyedWithReason() {
        assertNotNull(ShortcutActions.unavailableReason(ShortcutActions.WEATHER, ShortcutActions.internalCap(ShortcutActions.WEATHER), null, true));
        assertNotNull(ShortcutActions.unavailableReason(ShortcutActions.MEDIA_SAVE, ShortcutActions.internalCap(ShortcutActions.MEDIA_SAVE), null, true));
        assertTrue(ShortcutActions.unavailableReason("no.such", null, null, true).contains("没有这个工具"));
        assertNull(ShortcutActions.unavailableReason(ShortcutActions.LLM_ASK, ShortcutActions.internalCap(ShortcutActions.LLM_ASK), null, false));
    }

    @Test public void catalogPushedToPlannerCarriesAvailability() throws Exception {
        String json = ShortcutActions.catalogJson(new ShortcutActions.Lookup() {
            @Override public Capability lookup(String sign) {
                return ports.lookup(sign);
            }
        }, null, false);
        JSONObject root = new JSONObject(json);
        assertEquals(ShortcutModel.FORMAT, root.getInt("format"));
        JSONArray a = root.getJSONArray("actions");
        Map<String, JSONObject> by = new HashMap<String, JSONObject>();
        for (int i = 0; i < a.length(); i++) by.put(a.getJSONObject(i).getString("sign"), a.getJSONObject(i));
        assertFalse(by.get("dnd.on").getBoolean("available"));
        assertTrue(by.get("dnd.on").getBoolean("privileged"));
        assertEquals(ShortcutActions.REASON_PRIVILEGED_OFF, by.get("dnd.on").getString("reason"));
        assertTrue(by.get("clock.alarm.set").getBoolean("available"));
        assertEquals("hour", by.get("clock.alarm.set").getJSONArray("params").getJSONObject(0).getString("name"));
        assertFalse(by.get(ShortcutActions.WEATHER).getBoolean("available"));
        assertFalse("没注册的工具置灰", by.get("browser.open").getBoolean("available"));
    }

    // ------------------------------------------------------------------ 命中

    @Test public void matcherByNamePrefixAliasAndCommand() throws Exception {
        long a = saveShortcut("晚安", step("dnd.on", "{}"));
        long b = store.insert("明早七点叫我", java.util.Arrays.asList("明早七点叫我，再开个白噪音"), java.util.Arrays.asList(step("clock.alarm.set", "{\"hour\":7}")), "chat");
        List<ShortcutModel.Shortcut> all = store.all();
        assertEquals(a, ShortcutMatcher.match("晚安。", all).shortcut.id);
        assertEquals(a, ShortcutMatcher.match("执行快捷指令 晚安", all).shortcut.id);
        assertEquals(a, ShortcutMatcher.match("运行晚安", all).shortcut.id);
        assertEquals("第二次做同样的事", b, ShortcutMatcher.match("明早七点叫我, 再开个白噪音", all).shortcut.id);
        assertNull("只认整句", ShortcutMatcher.match("晚安曲推荐一下", all));
        ShortcutMatcher.Hit h = ShortcutMatcher.match(ShortcutModel.runCommand(b, "明早七点叫我"), all);
        assertTrue(h.byCommand);
        assertEquals(b, h.shortcut.id);
        // 关了语音触发：说名字不命中，定时 / 点击的指令仍命中
        Map<String, Object> off = new HashMap<String, Object>();
        off.put("triggers_json", "{\"voice\":0,\"tap\":1}");
        store.update(a, off);
        all = store.all();
        assertNull(ShortcutMatcher.match("晚安", all));
        assertNotNull(ShortcutMatcher.match(ShortcutModel.runCommand(a, "晚安"), all));
        // 停用：都不命中
        Map<String, Object> dis = new HashMap<String, Object>();
        dis.put("enabled", 0);
        store.update(a, dis);
        assertNull(ShortcutMatcher.match(ShortcutModel.runCommand(a, "晚安"), store.all()));
    }

    // ------------------------------------------------------------------ 计划 App 发起的运行 / 执行记录

    @Test public void plannerRequestsAreClaimedAndReused() throws Exception {
        long id = saveShortcut("晚安", step("app.launch", "{\"app\":\"潮汐\"}"));
        long now = 1794137400000L;
        Map<String, Object> req = new HashMap<String, Object>();
        req.put("shortcut_id", id);
        req.put("started_at", now - 1000);
        req.put("status", "requested");
        req.put("trigger_info", "tap");
        long reqId = mem.insert(PlannerContract.T_SHORTCUT_RUNS, req);
        Map<String, Object> old = new HashMap<String, Object>(req);
        old.put("started_at", now - ShortcutModel.REQUEST_STALE_MS - 5000);
        long oldId = mem.insert(PlannerContract.T_SHORTCUT_RUNS, old);
        List<Map<String, Object>> taken = store.takeRequests(now);
        assertEquals(1, taken.size());
        assertEquals("queued", mem.get(PlannerContract.T_SHORTCUT_RUNS, reqId).get("status"));
        assertEquals("过期的不执行", "stale", mem.get(PlannerContract.T_SHORTCUT_RUNS, oldId).get("status"));
        assertTrue(store.takeRequests(now).isEmpty());
        ShortcutFlow.Outcome o = ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_TIME, store, ports, normal, now);
        assertEquals("沿用排队的那一行", reqId, o.runId);
        assertEquals("ok", mem.get(PlannerContract.T_SHORTCUT_RUNS, reqId).get("status"));
        assertEquals("tap", mem.get(PlannerContract.T_SHORTCUT_RUNS, reqId).get("trigger_info"));
    }

    @Test public void runRecordsHoldNoArgsAndAreRedacted() throws Exception {
        long id = saveShortcut("报平安", step("sms.send", "{\"number\":\"13800138000\",\"text\":\"到家了 token=abc123secret\"}"));
        ports.results.put("sms.send", ExecutionResult.failed(ErrorCode.EXEC_FAILED, "发不出 access_token=zzz999", "x", null, null));
        normal.next = new ShortcutFlow.NormalResult();   // 正常模式也失败
        ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_VOICE, store, ports, normal, 1794137400000L);
        String runs = mem.list(PlannerContract.T_SHORTCUT_RUNS).toString();
        assertFalse(runs, runs.contains("13800138000"));
        assertFalse(runs, runs.contains("到家了"));
        assertFalse(runs, runs.contains("zzz999"));
        assertTrue(runs, runs.contains("sms.send ✗"));
    }

    @Test public void commandFormat() {
        assertEquals(12, ShortcutModel.commandId(ShortcutModel.runCommand(12, "晚安")));
        assertEquals(-1, ShortcutModel.commandId("晚安"));
        assertEquals("执行快捷指令#12（晚安）", ShortcutModel.runCommand(12, "晚安"));
    }

    // ------------------------------------------------------------------ 收尾：名字截字 / 定时（无人值守）拒绝 / 权限清单

    @Test public void chatNameIsFirst40CharsWithoutSplittingSurrogates() throws Exception {
        List<ShortcutFlow.NormalStep> steps = new ArrayList<ShortcutFlow.NormalStep>();
        steps.add(new ShortcutFlow.NormalStep("app.launch", new JSONObject("{\"app\":\"地图\"}"), true));
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < 39; i++) q.append('路');
        q.append("\uD83D\uDE97").append("然后再打开音乐放点歌");   // 第 40、41 个 UTF-16 单元是一个表情（代理对）
        ShortcutFlow.Saved sv = ShortcutFlow.saveFromChat(q.toString(), steps, store, ports);
        assertNull(sv.error);
        ShortcutModel.Shortcut s = store.get(sv.id);
        assertTrue("不超过计划 App 的 40 字上限（UTF-16）", s.name.length() <= 40);
        assertEquals("截在表情中间 → 少截一个，不留半个表情", 39, s.name.length());
        assertFalse(Character.isHighSurrogate(s.name.charAt(s.name.length() - 1)));
        // 普通长句：正好 40 个字
        StringBuilder q2 = new StringBuilder();
        for (int i = 0; i < 45; i++) q2.append('灯');
        ShortcutFlow.Saved sv2 = ShortcutFlow.saveFromChat(q2.toString(), steps, store, ports);
        assertNull(sv2.error);
        assertEquals(40, store.get(sv2.id).name.length());
        assertTrue("原话整句作触发词", s.aliases.contains(q.toString()));
    }

    @Test public void unattendedRejectOrUngrantedStopsWithoutFallback() throws Exception {
        long id = saveShortcut("晚安", step("clock.alarm.set", "{\"hour\":7}"), step("app.launch", "{\"app\":\"白噪音\"}"));
        // 定时运行：S3Runner 的权限闸说没授权（EXEC_FAILED + detail「jobs ungranted」）→ 宿主胶水换成 GATE_DENIED
        ports.results.put("clock.alarm.set", ShortcutSafety.unattendedVerdict(ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                "这一步没有授权，已停下等你确认", "jobs ungranted", ports.lookup("clock.alarm.set"), new JSONObject())));
        ShortcutFlow.Outcome o = ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_TIME, store, ports, normal, 1794137400000L);
        assertEquals(ShortcutModel.SC_DENIED, o.status);
        assertTrue("安全策略拦下不转正常模式", normal.prompts.isEmpty());
        assertEquals("[clock.alarm.set]", ports.executed.toString());
        // 岛上没允许（每次确认 / 红卡）→ USER_REJECTED → 同样停下
        ports.results.put("clock.alarm.set", ShortcutSafety.unattendedVerdict(ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                "需要你确认才能执行，已安全跳过", "s3 unattended red", ports.lookup("clock.alarm.set"), new JSONObject())));
        o = ShortcutFlow.run(store.get(id), ShortcutModel.TRIG_TIME, store, ports, normal, 1794137400000L);
        assertEquals(ShortcutModel.SC_DENIED, o.status);
        assertTrue(normal.prompts.isEmpty());
        assertEquals("快捷指令没改", 1, store.get(id).version);
        // 其他失败原样（照常转正常模式）
        ExecutionResult other = ExecutionResult.failed(ErrorCode.EXEC_FAILED, "x", "boom", null, null);
        assertSame(other, ShortcutSafety.unattendedVerdict(other));
    }

    @Test public void scheduledShortcutGrantCoversItsSteps() throws Exception {
        final long id = saveShortcut("晚安", step("clock.alarm.set", "{\"hour\":7}"), step("app.launch", "{\"app\":\"白噪音\"}"),
                step("plan.event.add", "{\"title\":\"睡觉\"}"), step("sms.send", "{}"));
        java.util.Set<com.onesay.assistant.v2.jobs.Perm> perms = ShortcutSafety.perms(store.get(id), new ShortcutActions.Lookup() {
            @Override public Capability lookup(String sign) {
                return ports.lookup(sign);
            }
        });
        assertTrue(perms.contains(com.onesay.assistant.v2.jobs.Perm.SYSTEM_SETTINGS));
        assertTrue(perms.contains(com.onesay.assistant.v2.jobs.Perm.OPEN_APP));
        boolean perRun = false;
        for (com.onesay.assistant.v2.jobs.Perm p : perms) perRun |= !p.preGrantable;
        assertTrue("发短信仍在清单里（每次确认，不可预授权）", perRun);
        com.onesay.assistant.v2.jobs.JobStore.Job j = new com.onesay.assistant.v2.jobs.JobStore.Job();
        j.command = ShortcutModel.runCommand(id, "晚安");
        try {
            com.onesay.assistant.v2.jobs.JobsEngine.setCommandPerms(new com.onesay.assistant.v2.jobs.JobsEngine.CommandPerms() {
                @Override public java.util.Set<com.onesay.assistant.v2.jobs.Perm> permsFor(String command) {
                    return ShortcutModel.commandId(command) == id ? java.util.EnumSet.of(com.onesay.assistant.v2.jobs.Perm.SYSTEM_SETTINGS) : null;
                }
            });
            assertTrue("授权清单按快捷指令的步骤算", com.onesay.assistant.v2.jobs.JobsEngine.analyze(j).contains(com.onesay.assistant.v2.jobs.Perm.SYSTEM_SETTINGS));
            com.onesay.assistant.v2.jobs.JobsEngine.setCommandPerms(new com.onesay.assistant.v2.jobs.JobsEngine.CommandPerms() {
                @Override public java.util.Set<com.onesay.assistant.v2.jobs.Perm> permsFor(String command) {
                    throw new IllegalStateException("计划 App 连不上");
                }
            });
            assertNotNull("解析出错不崩，按指令文字算", com.onesay.assistant.v2.jobs.JobsEngine.analyze(j));
        } finally {
            com.onesay.assistant.v2.jobs.JobsEngine.setCommandPerms(null);
        }
        assertFalse(com.onesay.assistant.v2.jobs.JobsEngine.analyze(j).contains(com.onesay.assistant.v2.jobs.Perm.SYSTEM_SETTINGS));
    }
}
