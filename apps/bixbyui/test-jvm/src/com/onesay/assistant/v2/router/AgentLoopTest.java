package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ExecutionResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** P0-2：AgentLoop —— 挖到新接口后刷新 tools、邮件降级链、不许空手说做不到、收尾必带方案。 */
public class AgentLoopTest {

    /** 按轮次脚本化的假模型；记录每轮收到的 tools 与 messages。 */
    static final class ScriptedLlm implements AgentLoop.Llm {
        final List<JSONObject> replies = new ArrayList<JSONObject>();
        final List<JSONArray> toolsSeen = new ArrayList<JSONArray>();
        final List<JSONArray> messagesSeen = new ArrayList<JSONArray>();
        @Override public boolean hasApiKey() { return true; }
        @Override public JSONObject call(JSONArray messages, JSONArray tools, int maxTokens) {
            try {
                toolsSeen.add(new JSONArray(tools.toString()));
                messagesSeen.add(new JSONArray(messages.toString()));
            } catch (Exception e) { throw new RuntimeException(e); }
            int i = toolsSeen.size() - 1;
            return i < replies.size() ? replies.get(i) : say("好的。");
        }
    }

    static JSONObject say(String text) {
        try {
            JSONObject m = new JSONObject();
            m.put("role", "assistant");
            m.put("content", text);
            return m;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static JSONObject call(String wireName, String argsJson) {
        try {
            JSONObject fn = new JSONObject();
            fn.put("name", wireName);
            fn.put("arguments", argsJson);
            JSONObject c = new JSONObject();
            c.put("id", "c" + System.nanoTime());
            c.put("type", "function");
            c.put("function", fn);
            JSONObject m = new JSONObject();
            m.put("role", "assistant");
            m.put("content", "");
            m.put("tool_calls", new JSONArray().put(c));
            return m;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static boolean hasTool(JSONArray tools, String wire) {
        for (int i = 0; i < tools.length(); i++) {
            JSONObject f = tools.optJSONObject(i).optJSONObject("function");
            if (f != null && wire.equals(f.optString("name"))) return true;
        }
        return false;
    }

    /** 假执行器：probe.app_intents 时"挖到"并注册一个新接口；其余按 okSigns 决定成败。 */
    static final class FakeExec implements AgentLoop.Callback {
        final List<String> okSigns;
        final List<String> executed = new ArrayList<String>();
        FakeExec(String... okSigns) { this.okSigns = Arrays.asList(okSigns); }
        @Override public ExecutionResult execute(Capability cap, JSONObject args) {
            executed.add(cap.sign);
            if ("probe.app_intents".equals(cap.sign)) {
                CapabilityRegistry reg = CapabilityRegistry.getInstance();
                if (reg.describe("androidqqmailtest.view") == null) {
                    reg.register(new Capability.Builder("androidqqmailtest.view", "QQ邮箱 查看")
                            .schema(new ParamsSchema()).executor("intent").build());
                }
                String out = "{\"ok\":true,\"package\":\"com.tencent.androidqqmail\",\"found\":1,"
                        + "\"interfaces\":[{\"component\":\"x/.y\",\"action\":\"view\",\"tool\":\"androidqqmailtest.view\",\"registered\":true}]}";
                return ExecutionResult.success(cap, args, out, false, out);
            }
            if (okSigns.contains(cap.sign)) return ExecutionResult.success(cap, args, "已完成", true, "");
            return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, "邮件还没配置", "mail not configured", cap, args);
        }
        @Override public AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, JSONObject args, String reason) {
            return AgentLoop.ConfirmVerdict.DENY;
        }
    }

    @Test public void minedInterfacesBecomeCallableInNextRound() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(call("probe__app_intents", "{\"package\":\"com.tencent.androidqqmail\"}"));
        llm.replies.add(call("androidqqmailtest__view", "{}"));
        llm.replies.add(say("已经打开 QQ 邮箱的查看界面。"));
        FakeExec exec = new FakeExec("androidqqmailtest.view");
        AgentLoop loop = new AgentLoop(CapabilityRegistry.getInstance(), llm);

        AgentLoop.Result r = loop.run("用QQ邮箱看看", exec, 0);

        assertFalse("第一轮还没有新接口", hasTool(llm.toolsSeen.get(0), "androidqqmailtest__view"));
        assertTrue("第二轮 tools 必须包含刚挖到的接口", hasTool(llm.toolsSeen.get(1), "androidqqmailtest__view"));
        assertTrue(r.finished);
        assertEquals(Arrays.asList("probe.app_intents", "androidqqmailtest.view"), exec.executed);
        assertTrue("观察值告诉模型新工具名", r.steps.get(0).observation.contains("callable_tools_added"));
    }

    @Test public void queryInfoFamilyIncludesAppLaunch() {
        assertTrue(Arrays.asList(AgentLoop.goalFamilySigns(ActionLexicon.Intent.QUERY_INFO)).contains("app.launch"));
    }

    @Test public void mailRequestHasWholeFallbackChain() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(say("好的"));
        new AgentLoop(CapabilityRegistry.getInstance(), llm).run("我的邮件有什么", new FakeExec(), 0);
        JSONArray t = llm.toolsSeen.get(0);
        assertTrue(hasTool(t, "mail__inbox__latest"));
        assertTrue(hasTool(t, "app__launch"));
        assertTrue(hasTool(t, "vision__screen__understand"));
        assertTrue(hasTool(t, "probe__apps"));
    }

    @Test public void giveUpWithoutProbeChainIsNudgedOnce() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(call("mail__inbox__latest", "{}"));
        llm.replies.add(say("抱歉，我无法读取你的QQ邮箱。"));        // 想放弃（只试了 IMAP）
        llm.replies.add(call("app__launch", "{\"app\":\"QQ邮箱\"}"));  // 被提醒后换路
        llm.replies.add(call("vision__screen__understand", "{}"));
        llm.replies.add(say("QQ邮箱最新一封来自张三，主题是周报。"));
        FakeExec exec = new FakeExec("app.launch", "vision.screen.understand");
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("读一下QQ邮箱的邮件", exec, 0);

        JSONArray msgs = llm.messagesSeen.get(2);
        String last = msgs.getJSONObject(msgs.length() - 1).optString("content");
        assertTrue("第三轮前插入了探针链提醒", last.contains("系统提醒"));
        assertEquals(Arrays.asList("mail.inbox.latest", "app.launch", "vision.screen.understand"), exec.executed);
        assertTrue(r.finished);
        assertEquals("QQ邮箱最新一封来自张三，主题是周报。", r.answer);
    }

    @Test public void observationCarriesErrorCode() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(call("mail__inbox__latest", "{}"));
        llm.replies.add(call("app__launch", "{\"app\":\"QQ邮箱\"}"));
        llm.replies.add(say("已打开。"));
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm)
                .run("读一下QQ邮箱的邮件", new FakeExec("app.launch"), 0);
        assertTrue(r.steps.get(0).observation.contains("\"code\":\"SCHEMA_INVALID\""));
    }

    @Test public void finalGiveUpAlwaysHasAttemptsAndPlan() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(say("做不到。"));
        llm.replies.add(say("还是做不到。"));
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm)
                .run("读一下QQ邮箱的邮件", new FakeExec(), 0);
        assertNotNull(r.answer);
        assertTrue(r.answer, r.answer.contains("我") && r.answer.contains("你可以"));
        assertTrue("邮件场景给出 IMAP 配置方案", r.answer.contains("IMAP"));
    }

    @Test public void normalSuccessAnswerIsUntouched() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(call("battery__level", "{}"));
        llm.replies.add(say("电量还有 80%。"));
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm)
                .run("电量还剩多少", new FakeExec("battery.level"), 0);
        assertEquals("电量还有 80%。", r.answer);
    }

    @Test public void deterministicSingleStepDetection() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(call("battery__level", "{}"));
        llm.replies.add(say("电量还有 80%。"));
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm)
                .run("电量还剩多少", new FakeExec("battery.level"), 0);
        AgentLoop.Step st = AgentLoop.deterministicSingleStep(r);
        assertNotNull(st);
        assertEquals("battery.level", st.tool);
        assertTrue(st.verified);

        ScriptedLlm llm2 = new ScriptedLlm();
        llm2.replies.add(call("probe__apps", "{\"keyword\":\"QQ\"}"));
        llm2.replies.add(say("装了。"));
        AgentLoop.Result r2 = new AgentLoop(CapabilityRegistry.getInstance(), llm2)
                .run("装了QQ没", new FakeExec("probe.apps"), 0);
        assertNull("探针步骤不当技能缓存", AgentLoop.deterministicSingleStep(r2));
    }

    @Test public void priorFailureHintIsPassedToModel() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(say("好"));
        new AgentLoop(CapabilityRegistry.getInstance(), llm)
                .run("读一下QQ邮箱的邮件", new FakeExec(), 0, "我试了：mail.inbox.latest（失败）");
        JSONArray msgs = llm.messagesSeen.get(0);
        assertTrue(msgs.getJSONObject(msgs.length() - 1).optString("content").contains("刚才失败过"));
    }

    @Test public void promptHasFallbackChainAndNoContradiction() {
        String p = L2LLMCompiler.buildAgentLoopPrompt(10);
        assertFalse("旧规则『不要读屏幕找邮件』必须删除", p.contains("也不要读屏幕找邮件"));
        assertFalse(p.contains("不要**去点 QQ 邮箱的界面"));
        assertTrue(p.contains("降级链"));
        assertTrue(p.contains("app.launch"));
        assertTrue(p.contains("用户）下一步可以怎么做"));
        assertTrue(p.contains("最多 " + AgentLoop.PROBE_BUDGET_MAX + " 次"));
    }

    // ================= 2026-10-07 集成：F1 观察值 / F2 令牌回调 / F3 污点传播 =================

    /** 实现 GatedCallback 的假宿主：记录每步收到的令牌与污点，确认卡按 verdict 裁决。 */
    static final class GatedFake implements AgentLoop.GatedCallback {
        final AgentLoop.ConfirmVerdict verdict;
        final List<String> log = new ArrayList<String>();
        final List<ExecutionGate.ConfirmToken> tokens = new ArrayList<ExecutionGate.ConfirmToken>();
        int confirmTier = -1;
        boolean confirmTainted;
        GatedFake(AgentLoop.ConfirmVerdict v) { verdict = v; }
        @Override public ExecutionResult execute(Capability cap, JSONObject args) {
            throw new AssertionError("GatedCallback 宿主不应再走旧 execute");
        }
        @Override public AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, JSONObject args, String reason) {
            throw new AssertionError("GatedCallback 宿主不应再走三参 onRequireConfirm");
        }
        @Override public ExecutionResult execute(Capability cap, JSONObject args,
                                                 ExecutionGate.ConfirmToken token, boolean tainted) {
            log.add(cap.sign + "|tainted=" + tainted + "|token=" + (token == null ? "-" : token.state().name()));
            tokens.add(token);
            if ("mail.inbox.latest".equals(cap.sign)) {
                return ExecutionResult.failed(ErrorCode.NOT_CONFIGURED, "邮箱未配置", "", "去设置里填写授权码",
                        cap, args);
            }
            return ExecutionResult.success(cap, args, "已完成", ExecutionResult.Verification.VERIFIED, "");
        }
        @Override public AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, JSONObject args, String reason,
                                                                  int tier, boolean tainted) {
            confirmTier = tier;
            confirmTainted = tainted;
            log.add("confirm:" + cap.sign);
            return verdict;
        }
    }

    @Test public void observationUsesModelObservationWithHint() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(call("mail__inbox__latest", "{}"));
        llm.replies.add(say("邮箱还没配置，请到设置里填写授权码。"));
        GatedFake host = new GatedFake(AgentLoop.ConfirmVerdict.DENY);
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("读一下邮件", host, 2);
        String obs = r.steps.get(0).observation;
        assertTrue(obs, obs.contains("\"code\":\"NOT_CONFIGURED\""));
        assertTrue("F1：hint 回灌给模型", obs.contains("去设置里填写授权码"));
        assertTrue("F1：失败带 retryable", obs.contains("\"retryable\""));
        assertTrue("F1：verified 为三态名", obs.contains("\"verified\":\"FAILED\"") || obs.contains("\"verified\":\"UNVERIFIED\""));
    }

    @Test public void stepsAfterScreenReadAreTaintedAndNeedApprovedToken() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(call("vision__screen__understand", "{}"));
        llm.replies.add(call("app__launch", "{\"app\":\"微信\"}"));
        llm.replies.add(say("好了。"));
        GatedFake host = new GatedFake(AgentLoop.ConfirmVerdict.ALLOW);
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm)
                .run("看看屏幕上写的什么然后打开微信", host, 3);
        assertEquals(Arrays.asList(
                "vision.screen.understand|tainted=false|token=-",
                "confirm:app.launch",
                "app.launch|tainted=true|token=APPROVED"), host.log);
        assertTrue("污点步骤弹红卡", host.confirmTainted);
        assertEquals(com.onesay.assistant.v2.executor.ConsentPolicy.TIER_RED, host.confirmTier);
        assertTrue(r.steps.get(1).gated);
        assertTrue(r.steps.get(1).ok);
    }

    @Test public void failedMailReadDoesNotTaint() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(call("mail__inbox__latest", "{}"));
        llm.replies.add(call("app__launch", "{\"app\":\"QQ邮箱\"}"));
        llm.replies.add(say("已打开 QQ 邮箱。"));
        GatedFake host = new GatedFake(AgentLoop.ConfirmVerdict.DENY);
        new AgentLoop(CapabilityRegistry.getInstance(), llm).run("读一下QQ邮箱的邮件", host, 3);
        assertEquals(Arrays.asList(
                "mail.inbox.latest|tainted=false|token=-",
                "app.launch|tainted=false|token=-"), host.log);
    }

    @Test public void deniedConfirmRejectsTokenAndSkipsExecution() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(call("vision__screen__understand", "{}"));
        llm.replies.add(call("app__launch", "{\"app\":\"微信\"}"));
        llm.replies.add(say("好的，没有打开。"));
        GatedFake host = new GatedFake(AgentLoop.ConfirmVerdict.DENY);
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm)
                .run("看看屏幕然后打开微信", host, 3);
        assertEquals(Arrays.asList("vision.screen.understand|tainted=false|token=-", "confirm:app.launch"), host.log);
        assertTrue(r.steps.get(1).observation.contains("user_denied"));
        assertFalse(r.steps.get(1).ok);
    }

    @Test public void readsUntrustedContentClassification() {
        CapabilityRegistry reg = CapabilityRegistry.getInstance();
        assertTrue(AgentLoop.readsUntrustedContent(reg.describe("vision.screen.understand")));
        assertTrue(AgentLoop.readsUntrustedContent(reg.describe("mail.inbox.latest")));
        assertFalse(AgentLoop.readsUntrustedContent(reg.describe("mail.draft.new")));
        assertFalse(AgentLoop.readsUntrustedContent(reg.describe("app.launch")));
        assertFalse(AgentLoop.readsUntrustedContent(null));
    }

    @Test public void probeAppIntentsGetsUserGoalForRanking() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(call("probe__app_intents", "{\"package\":\"com.tencent.androidqqmail\"}"));
        llm.replies.add(say("好的。"));
        final List<String> goals = new ArrayList<String>();
        AgentLoop.Callback cb = new AgentLoop.Callback() {
            @Override public ExecutionResult execute(Capability cap, JSONObject args) {
                goals.add(args.optString("goal"));
                return ExecutionResult.success(cap, args, "{}", false, "");
            }
            @Override public AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, JSONObject args, String reason) {
                return AgentLoop.ConfirmVerdict.DENY;
            }
        };
        new AgentLoop(CapabilityRegistry.getInstance(), llm).run("读一下QQ邮箱最新邮件", cb, 2);
        assertEquals(1, goals.size());
        assertTrue(goals.get(0), goals.get(0).startsWith("读一下QQ邮箱最新邮件"));
    }
}
