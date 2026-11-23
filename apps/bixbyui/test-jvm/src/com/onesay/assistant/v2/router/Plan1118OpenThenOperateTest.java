package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import com.onesay.assistant.v2.capability.CapabilityRegistry;

/** ★ plan-1118（真机 g01）：只打开了 App、原话里的 App 内动作没做 → 不许按 done 收工。 */
public class Plan1118OpenThenOperateTest {

    static OpenThenOperate.StepView sv(final String tool, final String args, final boolean ok) {
        return new OpenThenOperate.StepView() {
            @Override public String tool() { return tool; }
            @Override public JSONObject args() {
                try { return new JSONObject(args); } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public boolean ok() { return ok; }
        };
    }

    @Test public void remainingGoalSplitsOpenFromInAppAction() {
        assertEquals("往下滑到显示与亮度", OpenThenOperate.remainingGoal("打开设置，往下滑到显示与亮度"));
        assertEquals("往下滑到显示与亮度", OpenThenOperate.remainingGoal("打开设置往下滑到显示与亮度"));
        assertEquals("算 12×34，一个个点", OpenThenOperate.remainingGoal("打开计算器算 12×34，一个个点"));
        assertEquals("算12乘34", OpenThenOperate.remainingGoal("帮我打开计算器然后算12乘34"));
        assertNotNull(OpenThenOperate.remainingGoal("打开计算器，替我操作界面，依次点按按钮1、2、乘号、3、4、等号"));
        assertEquals("搜索耳机", OpenThenOperate.remainingGoal("打开淘宝，搜索耳机"));
        assertNotNull("不以打开开头、但点名界面动作", OpenThenOperate.remainingGoal("在设置里往下滑到显示与亮度"));
    }

    @Test public void plainOpenHasNoRemainingGoal() {
        assertNull(OpenThenOperate.remainingGoal("打开设置"));
        assertNull(OpenThenOperate.remainingGoal("打开蓝牙"));
        assertNull(OpenThenOperate.remainingGoal("打开计算器"));
        assertNull(OpenThenOperate.remainingGoal("打开设置，然后打开蓝牙"));
        assertNull(OpenThenOperate.remainingGoal("现在几点了"));
        assertNull(OpenThenOperate.remainingGoal("搜一下周杰伦的新歌"));
        assertNull(OpenThenOperate.remainingGoal(null));
    }

    @Test public void pendingOnlyWhenOpenSucceededAndNoUiAction() {
        String q = "打开设置，往下滑到显示与亮度";
        List<OpenThenOperate.StepView> s = new ArrayList<OpenThenOperate.StepView>();
        s.add(sv("route.open", "{\"intent\":\"settings.open\"}", true));
        assertEquals("往下滑到显示与亮度", OpenThenOperate.pendingGoal(q, s));
        s.add(sv("phone.operate", "{\"goal\":\"往下滑\"}", false));
        assertNull("phone.operate 试过（失败也算）就不再拦", OpenThenOperate.pendingGoal(q, s));

        List<OpenThenOperate.StepView> launch = new ArrayList<OpenThenOperate.StepView>();
        launch.add(sv("app.launch", "{\"app\":\"设置\"}", true));
        assertNotNull(OpenThenOperate.pendingGoal(q, launch));

        List<OpenThenOperate.StepView> failed = new ArrayList<OpenThenOperate.StepView>();
        failed.add(sv("app.launch", "{\"app\":\"设置\"}", false));
        assertNull("打开都没成功 → 不是这个问题", OpenThenOperate.pendingGoal(q, failed));

        List<OpenThenOperate.StepView> page = new ArrayList<OpenThenOperate.StepView>();
        page.add(sv("route.open", "{\"intent\":\"settings.open\",\"page\":\"display\"}", true));
        assertNull("直接到了设置子页 → 不算只打开", OpenThenOperate.pendingGoal(q, page));

        List<OpenThenOperate.StepView> search = new ArrayList<OpenThenOperate.StepView>();
        search.add(sv("route.open", "{\"intent\":\"goods.search\",\"q\":\"耳机\"}", true));
        assertNull("接口已经搜了 → 不算只打开", OpenThenOperate.pendingGoal("打开淘宝，搜索耳机", search));

        List<OpenThenOperate.StepView> plain = new ArrayList<OpenThenOperate.StepView>();
        plain.add(sv("app.launch", "{\"app\":\"设置\"}", true));
        assertNull(OpenThenOperate.pendingGoal("打开设置", plain));
    }

    @Test public void observationCarriesRemainingGoal() throws Exception {
        String obs = "{\"ok\":true,\"code\":\"OK\"}";
        String out = OpenThenOperate.withRemaining(obs, "route.open", new JSONObject("{\"intent\":\"settings.open\"}"), true, "打开设置，往下滑到显示与亮度");
        JSONObject o = new JSONObject(out);
        assertEquals("往下滑到显示与亮度", o.getString("remaining_goal"));
        assertTrue(o.getString("next").contains("phone.operate"));
        assertEquals(obs, OpenThenOperate.withRemaining(obs, "route.open", new JSONObject(), false, "打开设置，往下滑到显示与亮度"));
        assertEquals(obs, OpenThenOperate.withRemaining(obs, "route.open", new JSONObject(), true, "打开设置"));
        assertEquals(obs, OpenThenOperate.withRemaining(obs, "settings.brightness.get", new JSONObject(), true, "打开设置，往下滑到显示与亮度"));
    }

    @Test public void honestTailOnlyWhenAnswerDoesNotAdmitIt() {
        String t = OpenThenOperate.honestTail("已为你打开设置。", "往下滑到显示与亮度");
        assertTrue(t.startsWith("已为你打开设置。"));
        assertTrue(t.contains("「往下滑到显示与亮度」还没在屏幕上完成"));
        String admitted = "打开了设置，但往下滑到显示与亮度没能完成。";
        assertEquals(admitted, OpenThenOperate.honestTail(admitted, "往下滑到显示与亮度"));
    }

    @Test public void explicitUiCoversOneByOneTapping() {
        assertTrue(com.onesay.assistant.v2.agenttools.GuiFold.explicitUi("打开计算器算 12×34，一个个点"));
        assertTrue(com.onesay.assistant.v2.agenttools.GuiFold.explicitUi("依次点 1、2"));
    }

    @Test public void triageDoesNotAnswerOpenAndTapArithmeticAsAsk() {
        TriageDecider.Decision r = TriageDecider.decide("打开计算器，替我在计算器上点按算一下12乘34", TriageDecider.Signals.offline());
        assertNotNull(r);
        assertFalse("点名界面操作的不再判问答：" + r.label, r.label == TriageDecider.Label.ASK);
        TriageDecider.Decision plain = TriageDecider.decide("12乘34等于多少", TriageDecider.Signals.offline());
        assertEquals(TriageDecider.Label.ASK, plain.label);
    }

    // ---------- 循环层：只 app.launch 就想收工 → 打回 / 结局不记 done ----------

    @Test public void loopDoesNotAcceptDoneAfterOpenOnly() throws Exception {
        AgentLoop.sAssumeGuiToolForTest = true;
        try { openOnlyNudgedThenNotDone(); } finally { AgentLoop.sAssumeGuiToolForTest = false; }
    }

    @Test public void loopForcesOperateAfterNudge() throws Exception {
        AgentLoop.sAssumeGuiToolForTest = true;
        try {
            AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
            llm.replies.add(AgentLoopTest.call("app__launch", "{\"app\":\"设置\"}"));
            llm.replies.add(AgentLoopTest.say("已为你打开设置。"));
            llm.replies.add(AgentLoopTest.call("phone__operate", "{\"goal\":\"往下滑到显示与亮度\"}"));
            llm.replies.add(AgentLoopTest.say("已滑到显示与亮度。"));
            AgentLoopTest.FakeExec exec = new AgentLoopTest.FakeExec("app.launch", "phone.operate");
            AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("打开设置，往下滑到显示与亮度", exec, 8);
            boolean operated = false;
            for (AgentLoop.Step st : r.steps) if (st.tool.startsWith("phone.operate")) operated = true;
            assertTrue("打回后模型接着调了 phone.operate：" + r.steps.size(), operated);
            assertNull("调过 phone.operate 不再拦", OpenThenOperate.pendingGoal("打开设置，往下滑到显示与亮度", AgentLoop.stepViews(r.steps)));
            assertEquals(4, llm.toolsSeen.size());
        } finally { AgentLoop.sAssumeGuiToolForTest = false; }
    }

    private void openOnlyNudgedThenNotDone() throws Exception {
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.call("app__launch", "{\"app\":\"设置\"}"));
        llm.replies.add(AgentLoopTest.say("已为你打开设置，往下滑就能看到显示与亮度。"));
        llm.replies.add(AgentLoopTest.say("已为你打开设置。"));
        AgentLoopTest.FakeExec exec = new AgentLoopTest.FakeExec("app.launch");
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("打开设置，往下滑到显示与亮度", exec, 8);
        assertTrue(exec.executed.contains("app.launch"));
        // 第 2 轮之后的观测值里写着剩下的子目标
        JSONArray m2 = llm.messagesSeen.get(1);
        assertTrue("观测值带 remaining_goal：" + m2, m2.toString().contains("remaining_goal"));
        assertEquals("想收工被打回一次，多调一次模型", 3, llm.toolsSeen.size());
        assertTrue(llm.messagesSeen.get(2).toString().contains("phone.operate"));
        assertFalse("只打开没操作不能记 done", r.finished);
        assertEquals(com.onesay.assistant.v2.agent.Outcome.NOT_DONE, r.outcome);
        assertEquals(com.onesay.assistant.v2.agent.Outcome.Reason.PARTIAL, r.outcomeReason);
        assertTrue(r.answer, r.answer.contains("还没在屏幕上完成"));
    }

    @Test public void loopPlainOpenStillDone() {
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.call("app__launch", "{\"app\":\"设置\"}"));
        llm.replies.add(AgentLoopTest.say("已为你打开设置。"));
        AgentLoopTest.FakeExec exec = new AgentLoopTest.FakeExec("app.launch");
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("打开设置", exec, 8);
        assertTrue(r.finished);
        assertEquals(2, llm.toolsSeen.size());
    }
}
