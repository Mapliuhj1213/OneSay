package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.agent.LoopTools;
import com.onesay.assistant.v2.agent.Outcome;
import com.onesay.assistant.v2.agent.OutcomeText;
import com.onesay.assistant.v2.agent.PhoneFailure;
import com.onesay.assistant.v2.agenttools.AgentToolset;
import com.onesay.assistant.v2.agenttools.GuiFold;
import com.onesay.assistant.v2.agenttools.ToolTier;
import com.onesay.assistant.v2.agenttools.ToolTiers;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.prompt.AgentPromptAssembler;
import com.onesay.assistant.v2.prompt.PromptSource;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.onesay.assistant.v2.router.AgentLoopTest.*;
import static org.junit.Assert.*;

/** ★ arch/integrate：A × B × C 合并后的接线（常驻工具、提示词 loop_tools 段、phone.operate 失败细分、收尾说法）。 */
public class ArchIntegrateTest {

    @After public void tearDown() {
        AgentToolset.end();
    }

    static final List<String> RESIDENT_ALL = Arrays.asList("web.search", "web.read", "research.spawn", "task.todo", "ask_user", "end_not_done");

    static AgentPromptAssembler.Result assemble(List<String> mount) {
        AgentPromptAssembler.Mounted m = new AgentPromptAssembler.Mounted(mount);
        return AgentPromptAssembler.assemble(AgentPromptAssembler.parse(PromptSource.agentPrompt(null)), m,
                AgentPromptAssembler.vars(m, mount.size()), true);
    }

    static List<String> plus(List<String> a, List<String> b) {
        List<String> out = new ArrayList<String>(a);
        out.addAll(b);
        return out;
    }

    // ------------------------------------------------------------------ 提示词

    @Test public void loopToolsSectionFitsBudgetAndKeepsAppChoiceLast() {
        List<String> full = plus(Arrays.asList("probe.apps", "probe.app_intents", "probe.shell_read", "app.launch",
                "vision.screen.understand", "settings.brightness.set", "store.search.samsung", "memory.search",
                ToolTiers.SIGN_GUI_OPERATE), RESIDENT_ALL);
        AgentPromptAssembler.Result r = assemble(full);
        assertTrue("超预算段 " + r.overBudget, r.overBudget.isEmpty());
        assertTrue("不应丢段 " + r.dropped, r.dropped.isEmpty());
        assertTrue(r.text.length() <= AgentPromptAssembler.TOTAL_BUDGET);
        List<String> ids = new ArrayList<String>(r.sizes.keySet());
        assertTrue(ids.contains("loop_tools"));
        assertEquals("app_choice 仍在最后", "app_choice", ids.get(ids.size() - 1));
        assertTrue(r.text.contains("【查信息、任务清单与问人（常驻工具）】"));
        assertTrue(r.text.contains("先用 web.search"));
        assertTrue(r.text.contains("用 task.todo 写下子目标"));
        assertTrue(r.text.contains("用 ask_user 问一句"));
        assertTrue(r.text.contains("调用 end_not_done"));
        assertTrue("tiers 段里搜索工具那行也随 web.search 出现", r.text.contains("搜索 / 读网页工具就在你的工具列表里"));
        assertFalse("needs 标记不外泄", r.text.contains("<!--"));
    }

    @Test public void loopToolsLinesFollowMountedResidents() {
        AgentPromptAssembler.Result none = assemble(Arrays.asList("settings.brightness.set", "time.now"));
        assertFalse(none.sizes.containsKey("loop_tools"));
        // 无人值守口径：没有 web、没有 ask_user，只有 task.todo / end_not_done
        AgentPromptAssembler.Result un = assemble(Arrays.asList("time.now", "task.todo", "end_not_done"));
        assertTrue(un.text.contains("用 task.todo 写下子目标"));
        assertTrue(un.text.contains("调用 end_not_done"));
        assertFalse(un.text.contains("用 ask_user 问一句"));
        assertFalse(un.text.contains("先用 web.search"));
        assertFalse(un.text.contains("最终答复里写明来源网站"));
    }

    @Test public void residentsAreListedSeparatelyNotAsApiTier() {
        AgentPromptAssembler.Mounted m = new AgentPromptAssembler.Mounted(plus(Arrays.asList("time.now"), RESIDENT_ALL));
        assertEquals("接口档只有 time.now", Arrays.asList("time.now"), m.inTier(ToolTier.API));
        assertEquals(Arrays.asList("web.search", "web.read", "research.spawn"), m.inTier(ToolTier.INFO));
        assertEquals(Arrays.asList("task.todo", "ask_user", "end_not_done"), m.resident());
        String inv = AgentPromptAssembler.tierInventory(m);
        assertTrue(inv, inv.startsWith("接口 1 个"));
        assertTrue(inv, inv.contains("常驻：task.todo、ask_user、end_not_done"));
        assertTrue(ToolTiers.isResident("ask_user"));
        assertTrue(ToolTiers.isResident("task__todo"));
        assertFalse(ToolTiers.isResident("web.search"));
        assertEquals(ToolTier.INFO, ToolTiers.ofSign("web__read"));
        assertEquals(ToolTier.INFO, ToolTiers.ofSign("research.spawn"));
    }

    // ------------------------------------------------------------------ AgentLoop 接线

    @Test public void residentToolsSurviveGuiUnfoldReexport() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(call("store__search__samsung", "{\"keyword\":\"微信\"}"));
        llm.replies.add(say("商店里没找到。"));
        String q = "下载微信";
        new AgentLoop(CapabilityRegistry.getInstance(), llm).run(q, new FakeExec(), 0);
        AgentToolset.Session s = AgentToolset.currentFor(q);
        assertNotNull(s);
        assertEquals(GuiFold.Reason.UPPER_TIERS_FAILED, s.fold());
        assertTrue("第 2 轮之前确实重新导出过", llm.toolsSeen.size() >= 2);
        JSONArray first = llm.toolsSeen.get(0), second = llm.toolsSeen.get(1);
        assertTrue(hasTool(first, "task__todo"));
        assertTrue(hasTool(first, "end_not_done"));
        assertTrue("GUI 展开重新导出后 task.todo 还在", hasTool(second, "task__todo"));
        assertTrue("GUI 展开重新导出后 end_not_done 还在", hasTool(second, "end_not_done"));
        assertFalse("FakeExec 不是 LoopEvents → 不提供 ask_user", hasTool(second, "ask_user"));
        String sys = llm.messagesSeen.get(0).getJSONObject(0).optString("content");
        assertEquals("规则段只出现一次（不再由 Java 追加）", sys.indexOf("【查信息、任务清单与问人（常驻工具）】"),
                sys.lastIndexOf("【查信息、任务清单与问人（常驻工具）】"));
        assertTrue(sys.contains("调用 end_not_done"));
        assertFalse(sys.contains("用 ask_user 问一句"));
    }

    @Test public void appendIsIdempotentAfterReexport() {
        JSONArray t = new JSONArray();
        LoopTools.append(t, new LoopTools.Offer(true, true));
        LoopTools.append(t, new LoopTools.Offer(true, true));
        assertEquals(6, t.length());
    }

    // ------------------------------------------------------------------ phone.operate 失败细分

    @Test public void phoneFailureClassification() {
        PhoneFailure f = PhoneFailure.classify(false, "没有配置大模型 Key", "");
        assertEquals(ErrorCode.NOT_CONFIGURED, f.code);
        assertEquals(Outcome.BLOCKED, f.outcome);
        assertEquals(Outcome.Reason.NO_KEY, f.reason);

        f = PhoneFailure.classify(false, "读屏助手不可用：not bound", "");
        assertEquals(Outcome.BLOCKED, f.outcome);
        assertEquals(Outcome.Reason.DEVICE_UNAVAILABLE, f.reason);
        assertEquals(Outcome.Reason.DEVICE_UNAVAILABLE, PhoneFailure.classify(false, "已有一个操作任务在进行", "").reason);

        f = PhoneFailure.classify(false, "模型没有返回", "");
        assertEquals(ErrorCode.TIMEOUT, f.code);
        assertEquals(Outcome.Reason.MODEL_UNAVAILABLE, f.reason);

        f = PhoneFailure.classify(false, "页面要求先登录账号才能继续", "");
        assertEquals(Outcome.NEEDS_USER, f.outcome);
        assertEquals(Outcome.Reason.LOGIN, f.reason);
        assertTrue(f.hint.contains("end_not_done(subtype=login_required)"));
        assertEquals(Outcome.Reason.CAPTCHA, PhoneFailure.classify(false, "弹出了滑块验证", "").reason);
        assertEquals(Outcome.Reason.PAYMENT, PhoneFailure.classify(false, "", "到了支付页面").reason);

        f = PhoneFailure.classify(false, "同一个动作连做了 3 次，界面都没变化", "");
        assertEquals(Outcome.NOT_DONE, f.outcome);
        assertEquals(Outcome.Reason.LOOP_GUARD, f.reason);
        assertEquals(Outcome.Reason.BUDGET, PhoneFailure.classify(false, "未完成：已经问了模型 8 次", "").reason);
        assertEquals(Outcome.Reason.BUDGET, PhoneFailure.classify(false, "已执行 40 个动作（安全上限）", "").reason);

        f = PhoneFailure.classify(false, "列表里没找到这个联系人", "");
        assertEquals(ErrorCode.NOT_FOUND_APP, f.code);
        assertEquals(Outcome.Reason.ROUTE_FAILED, f.reason);

        f = PhoneFailure.classify(false, "java.lang.IllegalStateException: x", "");
        assertEquals(Outcome.Reason.ERROR, f.reason);
        assertEquals(Outcome.Reason.ROUTE_FAILED, PhoneFailure.classify(false, "说不清的原因", "").reason);

        f = PhoneFailure.classify(true, "用户停止", "");
        assertEquals("界面那一步叫停 ≠ 停止整个任务（CANCELLED 会让 AgentLoop 直接收尾）", ErrorCode.USER_REJECTED, f.code);
        assertEquals(Outcome.CANCELLED, f.outcome);
        assertEquals(Outcome.Reason.USER_STOPPED, f.reason);
        assertEquals(ErrorCode.USER_REJECTED, PhoneFailure.classify(true, "用户没有同意：要付款吗", "").code);
        assertEquals("outcome=needs_user reason=login_required", PhoneFailure.classify(false, "请先登录", "").tag());
    }

    @Test public void phonePluginUsesClassification() throws Exception {
        String src = com.onesay.assistant.v2.kernel.KernelWiring.src("app/src/com/onesay/assistant/v2/kernel/plugins/PhonePlugin.java");
        assertTrue(src.contains("PhoneFailure.classify(r.status == PhoneAgent.Status.STOPPED, r.reason, r.answer)"));
        assertFalse("不再一律 EXEC_FAILED", src.contains("ExecutionResult.failed(com.onesay.assistant.v2.executor.ErrorCode.EXEC_FAILED, text, detail, null, c, args)"));
    }

    // ------------------------------------------------------------------ 收尾说法

    @Test public void panelWordingFollowsOutcome() {
        assertEquals("需要你来", OutcomeText.title(Outcome.NEEDS_USER));
        assertEquals("暂时做不了", OutcomeText.title(Outcome.BLOCKED));
        assertEquals("部分完成", OutcomeText.title(Outcome.NOT_DONE));
        assertEquals("已停止", OutcomeText.title(Outcome.CANCELLED));
        assertEquals("#FF453A", OutcomeText.color(Outcome.BLOCKED));
        assertEquals("要你先登录，登录好再叫我", OutcomeText.line(Outcome.NEEDS_USER, Outcome.Reason.LOGIN, "x"));
        assertEquals("没等到你的回答，先停在这里", OutcomeText.line(Outcome.NEEDS_USER, Outcome.Reason.NO_ANSWER, "x"));
        assertEquals("模型服务没有响应，稍后再试", OutcomeText.line(Outcome.BLOCKED, Outcome.Reason.MODEL_UNAVAILABLE, "x"));
        assertEquals("还没配置大模型 Key", OutcomeText.line(Outcome.BLOCKED, Outcome.Reason.NO_KEY, "未配置 LLM Key"));
        assertEquals("not_done 照旧用循环原因", "已用完 8 轮", OutcomeText.line(Outcome.NOT_DONE, Outcome.Reason.BUDGET, "已用完 8 轮"));
        assertTrue(OutcomeText.line(Outcome.NEEDS_USER, "weird", "要扫码").endsWith("要扫码"));
        assertEquals("任务已完成。", OutcomeText.fallbackAnswer(true, Outcome.DONE, null, null));
        assertEquals("需要你来：付款要你亲自确认", OutcomeText.fallbackAnswer(false, Outcome.NEEDS_USER, Outcome.Reason.PAYMENT, "r"));
        assertEquals("任务未完成：r", OutcomeText.fallbackAnswer(false, Outcome.NOT_DONE, Outcome.Reason.BUDGET, "r"));
    }

    @Test public void panelHostUsesOutcomeText() throws Exception {
        String fss = com.onesay.assistant.v2.kernel.KernelWiring.src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue(fss.contains("OutcomeText.title(fr.outcome)"));
        assertTrue(fss.contains("OutcomeText.line(fr.outcome, fr.outcomeReason, fr.reason)"));
        assertTrue(fss.contains("OutcomeText.fallbackAnswer(fr.finished, fr.outcome, fr.outcomeReason, fr.reason)"));
        assertFalse("不再一律「部分完成」", fss.contains("mSheetView.updateState(\"部分完成\""));
        String raw = new String(java.nio.file.Files.readAllBytes(new java.io.File(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java").toPath()), "UTF-8");
        assertEquals("FSS 保持 CRLF", raw.split("\r\n", -1).length, raw.split("\n", -1).length);
    }
}
