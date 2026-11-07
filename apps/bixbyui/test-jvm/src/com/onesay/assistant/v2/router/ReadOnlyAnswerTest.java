package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** D3：只读数值工具跳过第二轮大模型（模板 / 白名单 / 原话判定 / 开关 / AgentLoop 接入）。 */
public class ReadOnlyAnswerTest {

    @After public void reset() {
        ReadOnlyAnswer.setOverrideForTest(null);
    }

    // ---------------------------------------------------------------- 白名单

    @Test public void whitelistIsExactlyNumericLocalReads() {
        String[] expect = { "battery.level", "volume.media.get", "volume.ring.get", "volume.notif.get",
                "volume.alarm.state", "settings.brightness.get", "settings.screentimeout.get", "settings.font.get" };
        assertEquals(expect.length, ReadOnlyAnswer.WHITELIST.size());
        for (String s : expect) assertTrue(s, ReadOnlyAnswer.isWhitelisted(s));
        // 开关类 / 列表类 / 写操作不在名单
        for (String s : new String[] { "battery.saver.state", "alarm.query", "mail.inbox.latest",
                "settings.brightness.set", "volume.media.up", "probe.shell_read", "memory.search" }) {
            assertFalse(s, ReadOnlyAnswer.isWhitelisted(s));
        }
    }

    /** 名单里每一条在真实能力表里都存在、风险 0、没有参数、shell 执行 —— 防止将来改能力表后名单悄悄失真。 */
    @Test public void whitelistEntriesAreRealReadOnlyCapabilities() {
        CapabilityRegistry reg = CapabilityRegistry.getInstance();
        for (String s : ReadOnlyAnswer.WHITELIST.keySet()) {
            Capability c = reg.describe(s);
            assertNotNull("能力表里没有 " + s, c);
            assertEquals(s + " 必须只读", 0, c.risk);
            assertFalse(s + " 不该要确认", c.confirm);
            assertTrue(s + " 不该带参数", c.paramsSchema.getRules().isEmpty());
            assertEquals(s, "shell", c.executorType);
            assertFalse(s + " 不该有占位符", c.cmdTemplate.contains("{"));
        }
    }

    // ---------------------------------------------------------------- 模板

    @Test public void rendersBattery() {
        assertEquals("当前电量 85%。", ReadOnlyAnswer.renderRaw("battery.level", "  level: 85"));
        assertEquals("当前电量 100%。", ReadOnlyAnswer.renderRaw("battery.level", "level: 100\n"));
        assertNull(ReadOnlyAnswer.renderRaw("battery.level", "level: 180"));
        assertNull(ReadOnlyAnswer.renderRaw("battery.level", "Can't find service: battery"));
        assertNull(ReadOnlyAnswer.renderRaw("battery.level", ""));
    }

    @Test public void rendersVolumes() {
        assertEquals("当前媒体音量是 7。", ReadOnlyAnswer.renderRaw("volume.media.get", "volume is 7"));
        assertEquals("当前铃声音量是 8（最大 15）。",
                ReadOnlyAnswer.renderRaw("volume.ring.get", "[v] will control stream=2\nvolume is 8 in range [0..15]"));
        assertEquals("当前通知音量是 0，已静音。", ReadOnlyAnswer.renderRaw("volume.notif.get", "volume is 0"));
        assertEquals("当前闹钟音量是 11。", ReadOnlyAnswer.renderRaw("volume.alarm.state", "volume is 11"));
        assertNull(ReadOnlyAnswer.renderRaw("volume.media.get", "Error: java.lang.SecurityException"));
    }

    @Test public void rendersBrightnessTimeoutFont() {
        assertEquals("当前屏幕亮度 128（满格 255，约 50%）。", ReadOnlyAnswer.renderRaw("settings.brightness.get", "128"));
        assertNull("超出 0..255 交给模型", ReadOnlyAnswer.renderRaw("settings.brightness.get", "300"));
        assertNull(ReadOnlyAnswer.renderRaw("settings.brightness.get", "null"));
        assertEquals("屏幕 30 秒无操作后自动关闭。", ReadOnlyAnswer.renderRaw("settings.screentimeout.get", "30000"));
        assertEquals("屏幕 2 分钟无操作后自动关闭。", ReadOnlyAnswer.renderRaw("settings.screentimeout.get", "120000"));
        assertEquals("屏幕 1 分 30 秒无操作后自动关闭。", ReadOnlyAnswer.renderRaw("settings.screentimeout.get", "90000"));
        assertNull("三星「永不」交给模型", ReadOnlyAnswer.renderRaw("settings.screentimeout.get", "2147483647"));
        assertEquals("当前字体缩放 1.15 倍（标准为 1.0）。", ReadOnlyAnswer.renderRaw("settings.font.get", "1.15"));
        assertNull(ReadOnlyAnswer.renderRaw("settings.font.get", "abc"));
    }

    @Test public void renderNeedsSuccessAndWhitelist() {
        Capability cap = CapabilityRegistry.getInstance().describe("battery.level");
        ExecutionResult ok = ExecutionResult.success(cap, new JSONObject(), "当前电量信息：level: 85", false, "level: 85");
        assertEquals("当前电量 85%。", ReadOnlyAnswer.render("battery.level", ok));
        ExecutionResult noDetail = ExecutionResult.success(cap, new JSONObject(), "当前电量信息：level: 42", false, "");
        assertEquals("detail 空时退到 userMessage", "当前电量 42%。", ReadOnlyAnswer.render("battery.level", noDetail));
        ExecutionResult fail = ExecutionResult.failed(ErrorCode.EXEC_FAILED, "失败", "level: 85", cap, new JSONObject());
        assertNull(ReadOnlyAnswer.render("battery.level", fail));
        assertNull(ReadOnlyAnswer.render("battery.saver.state", ok));
        assertNull(ReadOnlyAnswer.render("battery.level", null));
    }

    // ---------------------------------------------------------------- 原话判定

    @Test public void pureReadQuestionsAllowed() {
        for (String q : new String[] { "电量还剩多少", "现在音量多大", "亮度多少", "通知音量是多少",
                "电量百分比多少", "铃声和闹钟音量多少", "字体多大", "息屏时间是多少" }) {
            assertTrue(q, ReadOnlyAnswer.queryAllows(q));
        }
    }

    @Test public void conditionalOrActionQuestionsDenied() {
        for (String q : new String[] { "电量低于20就打开省电", "电量够不够看一部电影", "音量多大，太小就调大",
                "亮度多少，帮我调到一半", "电量多少，然后设个闹钟", "为什么电量掉这么快", "亮度正常吗",
                "电量多少的话提醒我充电", "", "   " }) {
            assertFalse(q, ReadOnlyAnswer.queryAllows(q));
        }
        assertFalse(ReadOnlyAnswer.queryAllows(null));
    }

    // ---------------------------------------------------------------- AgentLoop 接入

    /** 假执行器：按 sign 返回预置原始输出（detail）。 */
    static final class RawExec implements AgentLoop.Callback {
        final Map<String, String> raw = new HashMap<String, String>();
        final List<String> executed = new ArrayList<String>();
        RawExec put(String sign, String out) { raw.put(sign, out); return this; }
        @Override public ExecutionResult execute(Capability cap, JSONObject args) {
            executed.add(cap.sign);
            String o = raw.get(cap.sign);
            if (o == null) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "失败", "x", cap, args);
            return ExecutionResult.success(cap, args, cap.replyTemplate.replace("{out}", o), false, o);
        }
        @Override public AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, JSONObject args, String reason) {
            return AgentLoop.ConfirmVerdict.DENY;
        }
    }

    static JSONObject calls(String... wireNames) throws Exception {
        JSONArray arr = new JSONArray();
        for (String w : wireNames) {
            JSONObject fn = new JSONObject();
            fn.put("name", w);
            fn.put("arguments", "{}");
            JSONObject c = new JSONObject();
            c.put("id", "c" + System.nanoTime());
            c.put("type", "function");
            c.put("function", fn);
            arr.put(c);
        }
        JSONObject m = new JSONObject();
        m.put("role", "assistant");
        m.put("content", "");
        m.put("tool_calls", arr);
        return m;
    }

    @Test public void batteryAnswerSkipsSecondRound() throws Exception {
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(calls("battery__level"));
        llm.replies.add(AgentLoopTest.say("电量还有 85%。"));
        RawExec exec = new RawExec().put("battery.level", "  level: 85");
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("电量还剩多少", exec, 0);
        assertEquals("只调了一轮模型", 1, llm.toolsSeen.size());
        assertTrue(r.finished);
        assertEquals("当前电量 85%。", r.answer);
        assertEquals(1, r.steps.size());
        assertNotNull("技能缓存判定照旧可用", AgentLoop.deterministicSingleStep(r));
    }

    @Test public void twoWhitelistedReadsInOneBatchAreJoined() throws Exception {
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(calls("battery__level", "settings__brightness__get"));
        RawExec exec = new RawExec().put("battery.level", "level: 60").put("settings.brightness.get", "51");
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("电量和亮度多少", exec, 0);
        assertEquals(1, llm.toolsSeen.size());
        assertEquals("当前电量 60%。当前屏幕亮度 51（满格 255，约 20%）。", r.answer);
    }

    @Test public void switchOffKeepsOldBehaviour() throws Exception {
        ReadOnlyAnswer.setOverrideForTest(Boolean.FALSE);
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(calls("battery__level"));
        llm.replies.add(AgentLoopTest.say("电量还有 85%。"));
        RawExec exec = new RawExec().put("battery.level", "level: 85");
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("电量还剩多少", exec, 0);
        assertEquals("关掉后照旧两轮", 2, llm.toolsSeen.size());
        assertEquals("电量还有 85%。", r.answer);
        // 第二轮收到的消息与改动前一致：最后一条是 tool 结果
        JSONArray msgs = llm.messagesSeen.get(1);
        assertEquals("tool", msgs.getJSONObject(msgs.length() - 1).getString("role"));
    }

    @Test public void mixedBatchFallsBackToModel() throws Exception {
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(calls("battery__level", "battery__saver__state"));
        llm.replies.add(AgentLoopTest.say("电量 85%，省电模式没开。"));
        RawExec exec = new RawExec().put("battery.level", "level: 85").put("battery.saver.state", "0");
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("电量多少省电模式状态", exec, 0);
        assertEquals("非白名单混在一批里 → 照旧第二轮", 2, llm.toolsSeen.size());
        assertEquals("电量 85%，省电模式没开。", r.answer);
    }

    @Test public void failedOrUnparseableReadFallsBack() throws Exception {
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(calls("settings__brightness__get"));
        llm.replies.add(AgentLoopTest.say("没读到亮度。"));
        RawExec exec = new RawExec().put("settings.brightness.get", "null");
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("亮度多少", exec, 0);
        assertEquals(2, llm.toolsSeen.size());
        assertEquals("没读到亮度。", r.answer);

        AgentLoopTest.ScriptedLlm llm2 = new AgentLoopTest.ScriptedLlm();
        llm2.replies.add(calls("battery__level"));
        llm2.replies.add(AgentLoopTest.say("读取失败。"));
        AgentLoop.Result r2 = new AgentLoop(CapabilityRegistry.getInstance(), llm2).run("电量多少", new RawExec(), 0);
        assertEquals("执行失败 → 照旧第二轮", 2, llm2.toolsSeen.size());
        assertEquals("读取失败。", r2.answer);
    }

    @Test public void conditionalQueryStillGetsSecondRound() throws Exception {
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(calls("battery__level"));
        llm.replies.add(AgentLoopTest.say("电量 85%，不用开省电。"));
        RawExec exec = new RawExec().put("battery.level", "level: 85");
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm)
                .run("电量低于20就打开省电", exec, 0);
        assertEquals(2, llm.toolsSeen.size());
        assertEquals("电量 85%，不用开省电。", r.answer);
    }

    @Test public void whitelistedReadAfterEarlierStepsIsNotShortcut() throws Exception {
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(calls("probe__apps"));
        llm.replies.add(calls("battery__level"));
        llm.replies.add(AgentLoopTest.say("好了。"));
        RawExec exec = new RawExec().put("probe.apps", "{\"ok\":true}").put("battery.level", "level: 85");
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("电量多少", exec, 0);
        assertEquals("只有第一批调用才直出", 3, llm.toolsSeen.size());
        assertEquals("好了。", r.answer);
    }

    @Test public void countCallsIgnoresNulls() throws Exception {
        JSONArray a = new JSONArray();
        a.put(new JSONObject());
        a.put(JSONObject.NULL);
        a.put(new JSONObject());
        assertEquals(2, AgentLoop.countCalls(a));
        assertEquals(0, AgentLoop.countCalls(null));
    }
}
