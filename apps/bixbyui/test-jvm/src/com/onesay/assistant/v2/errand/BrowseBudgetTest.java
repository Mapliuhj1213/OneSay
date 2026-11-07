package com.onesay.assistant.v2.errand;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.errand.web.AgentAction;
import com.onesay.assistant.v2.errand.web.BrowsePolicy;
import com.onesay.assistant.v2.errand.web.BrowserAgent;
import com.onesay.assistant.v2.errand.web.PageSnapshot;
import com.onesay.assistant.v2.phone.TaskBudget;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** ★ fast-screen-v2：网页跑腿改用 TaskBudget（与 App 操作代理一致），到上限先问用户。 */
public class BrowseBudgetTest {

    static class Ask implements BrowserAgent.BudgetAsk {
        final List<String> asked = new ArrayList<String>();
        boolean answer;

        @Override public boolean askContinue(String reason, String info) {
            asked.add(reason + " | " + info);
            return answer;
        }
    }

    static BrowserAgentTest.World longThread() {
        BrowserAgentTest.World w = new BrowserAgentTest.World();
        w.add(BrowserAgentTest.page("https://bbs.example.com/t/1", "很长的正文"));
        return w;
    }

    @Test public void limitsMatchTaskBudget() {
        assertEquals(TaskBudget.DEF_MODEL_CALLS, BrowsePolicy.MAX_STEPS);
        assertEquals(TaskBudget.DEF_TOTAL_MS, BrowsePolicy.MAX_MS);
        assertEquals(TaskBudget.DEF_MODEL_CALLS, com.onesay.assistant.v2.router.L2LLMCompiler.AGENT_LOOP_MAX_ROUNDS);
    }

    @Test public void callLimitAsksUserAndExtendsOnYes() {
        Ask ask = new Ask();
        ask.answer = true;
        final int[] calls = {0};
        BrowserAgentTest.Script s = new BrowserAgentTest.Script() {
            @Override public AgentAction next(ErrandIntent i, PageSnapshot p, List<String> h, int step, int max) {
                calls[0]++;
                return calls[0] > TaskBudget.DEF_MODEL_CALLS + 3 ? AgentAction.done("读完了") : AgentAction.scroll(1);
            }
        };
        BrowseResult r = new BrowserAgent(longThread(), s, null, null, new BrowserAgentTest.FakeClock()).setBudgetAsk(ask)
                .run(BrowserAgentTest.READ, "https://bbs.example.com/t/1", null);
        assertEquals(1, ask.asked.size());
        assertTrue(ask.asked.get(0), ask.asked.get(0).contains("还要继续吗"));
        assertEquals(BrowseResult.Status.DONE, r.status);
        assertEquals("读完了", r.answer);
    }

    @Test public void callLimitWithNoReturnsPartial() {
        Ask ask = new Ask();
        BrowserAgent ag = new BrowserAgent(longThread(), new BrowserAgentTest.Script(), null, null, new BrowserAgentTest.FakeClock()).setBudgetAsk(ask);
        BrowseResult r = ag.run(BrowserAgentTest.READ, "https://bbs.example.com/t/1", null);
        assertEquals(1, ask.asked.size());
        assertEquals(BrowseResult.Status.LIMIT, r.status);
        assertEquals("阶段性结论", r.answer);
        assertEquals(TaskBudget.DEF_MODEL_CALLS, r.steps);
    }

    @Test public void timeAskedDoesNotCountTowardsBudget() {
        final BrowserAgentTest.FakeClock c = new BrowserAgentTest.FakeClock();
        Ask ask = new Ask() {
            @Override public boolean askContinue(String reason, String info) {
                c.t += 20 * 60_000L;   // 用户想了 20 分钟
                return super.askContinue(reason, info);
            }
        };
        ask.answer = true;
        final int[] n = {0};
        BrowserAgentTest.Script s = new BrowserAgentTest.Script() {
            @Override public AgentAction next(ErrandIntent i, PageSnapshot p, List<String> h, int step, int max) {
                c.t += 100_000L;
                return ++n[0] >= 7 ? AgentAction.done("好") : AgentAction.scroll(1);
            }
        };
        BrowseResult r = new BrowserAgent(longThread(), s, null, null, c).setBudgetAsk(ask).run(BrowserAgentTest.READ, "https://bbs.example.com/t/1", null);
        assertEquals(1, ask.asked.size());   // 480 s 时问一次；续了 240 s，等待的 20 分钟不算
        assertTrue(ask.asked.get(0).contains("秒"));
        assertEquals(BrowseResult.Status.DONE, r.status);
    }

    @Test public void repeatedFailuresAreStuckAndAsk() {
        BrowserAgentTest.World w = new BrowserAgentTest.World();
        w.add(BrowserAgentTest.page("https://www.example.com/a", "文章", BrowserAgentTest.link(1, "没反应的链接", "")));
        Ask ask = new Ask();
        BrowserAgentTest.Script s = new BrowserAgentTest.Script(AgentAction.click(1), AgentAction.click(1), AgentAction.click(1), AgentAction.click(1));
        BrowseResult r = new BrowserAgent(w, s, null, null, new BrowserAgentTest.FakeClock()).setBudgetAsk(ask).run(BrowserAgentTest.READ, "https://www.example.com/a", null);
        assertEquals(1, ask.asked.size());
        assertTrue(ask.asked.get(0), ask.asked.get(0).startsWith("好像卡住了"));
        assertEquals(BrowseResult.Status.LIMIT, r.status);
    }

    @Test public void withoutAskerBehavesLikeBefore() {
        BrowseResult r = new BrowserAgent(longThread(), new BrowserAgentTest.Script(), null, null, new BrowserAgentTest.FakeClock())
                .run(BrowserAgentTest.READ, "https://bbs.example.com/t/1", null);
        assertEquals(BrowseResult.Status.LIMIT, r.status);
    }

    // ★ fast-screen-v3：无人值守网页跑腿 = 8 次 / 3 分钟，不问、收尾注明「未完成」
    @Test public void unattendedStopsAtEightWithoutAskingAndMarksUnfinished() {
        Ask ask = new Ask();
        ask.answer = true;
        BrowserAgent ag = new BrowserAgent(longThread(), new BrowserAgentTest.Script(), null, null, new BrowserAgentTest.FakeClock())
                .setBudgetAsk(ask).setUnattended(true);
        BrowseResult r = ag.run(BrowserAgentTest.READ, "https://bbs.example.com/t/1", null);
        assertEquals(0, ask.asked.size());
        assertEquals(BrowseResult.Status.LIMIT, r.status);
        assertEquals(TaskBudget.UNATTENDED_MODEL_CALLS, r.steps);
        assertTrue(String.valueOf(ag.trace), String.valueOf(ag.trace).contains("未完成"));
    }
}
