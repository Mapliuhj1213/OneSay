package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.errand.ErrandIntent;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ arch/agent-loop（B 路）：「帮我调研 / 查资料 / 查一下＋话题」进 AgentLoop；跑腿只留点名跑腿 / 读链接 / 购物。 */
public class TriageWebResearchTest {

    @After public void reset() {
        TriageDecider.setWebResearch(true);
    }

    static TriageDecider.Decision d(String q) {
        return TriageDecider.decide(q, TriageDecider.Signals.offline());
    }

    @Test public void researchSentencesGoToAgentLoop() {
        String[] yes = {
                "帮我调研一下扫地机器人",
                "调研一下2026年国产大模型的价格",
                "帮我查资料，电动车电池怎么保养比较好",
                "去网上查一下小米su7的口碑",
                "帮我网上找找露营装备",
                "查一下iPhone 17的评测",
                "帮我查一下杭州三日游攻略",
                "对比一下石头和科沃斯",
        };
        for (String q : yes) {
            TriageDecider.Decision x = d(q);
            assertTrue(q + " → " + x, x.useAgentLoop);
            assertTrue(q + " → " + x, x.rule.startsWith("5w-web-research"));
            assertTrue(x.goalOriented);
        }
    }

    @Test public void deviceWeatherAndActionsUntouched() {
        String[] no = {"查一下电量", "查一下明天天气", "查一下比特币价格", "打开浏览器搜一下天气", "帮我下载微信", "查一下我的日程",
                "什么是量子计算", "查一下wifi开着吗"};
        for (String q : no) {
            TriageDecider.Decision x = d(q);
            assertFalse(q + " → " + x, x.rule.startsWith("5w-web-research"));
        }
    }

    @Test public void switchOffRestoresOldRules() {
        TriageDecider.setWebResearch(false);
        assertFalse(d("帮我调研一下扫地机器人").rule.startsWith("5w"));
    }

    @Test public void explicitErrandStillRecognised() {
        assertTrue(ErrandIntent.explicitErrand("帮我跑个腿调研一下扫地机器人"));
        assertTrue(ErrandIntent.explicitErrand("用浏览器查一下这个"));
        assertFalse(ErrandIntent.explicitErrand("帮我调研一下扫地机器人"));
        // parse 本身不变（ErrandHooks.intentFor 才按开关收窄）
        assertEquals(ErrandIntent.Kind.RESEARCH, ErrandIntent.parse("帮我调研一下扫地机器人").kind);
    }

    @Test public void errandHooksNarrowedAndLoopWired() throws Exception {
        String eh = AgentLoopBudgetTest.src("app/src/com/onesay/assistant/v2/errand/ErrandHooks.java");
        assertTrue(eh.contains("in.kind == ErrandIntent.Kind.RESEARCH && !ErrandIntent.explicitErrand(query)"));
        assertTrue(eh.contains("LoopConfig.researchInLoop(ctx)"));
        String ur = AgentLoopBudgetTest.src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        assertTrue(ur.contains("TriageDecider.setWebResearch(com.onesay.assistant.v2.agent.LoopConfig.researchInLoop(mContext))"));
        String fss = AgentLoopBudgetTest.src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue(fss.contains("new AgentLoop.LoopAwareCallback()"));
        assertTrue(fss.contains("com.onesay.assistant.v2.s1.S1Hooks.onSubgoals(goals)"));
        assertTrue(fss.contains("com.onesay.assistant.v2.agent.AskUserSupport.interceptVoice(command)"));
        assertTrue(fss.contains("com.onesay.assistant.v2.s1.S1Hooks.answerAskUser(id, options.get(index))"));
        // 语音回填在开新请求（beginForegroundRequest）之前
        int ic = fss.indexOf("AskUserSupport.interceptVoice(command)");
        int bf = fss.indexOf("beginForegroundRequest();   // ★ LC-5");
        assertTrue(ic > 0 && bf > ic);
    }

    @Test public void s1HooksAskUserContract() throws Exception {
        com.onesay.assistant.v2.s1.S1Hooks.onAskUser("t-1", "选哪个？", java.util.Arrays.asList("a", "b"), 60);
        assertTrue(com.onesay.assistant.v2.s1.S1Hooks.answerAskUser("t-1", "b"));
        assertFalse("只收一次", com.onesay.assistant.v2.s1.S1Hooks.answerAskUser("t-1", "a"));
        assertEquals("b", com.onesay.assistant.v2.s1.S1Hooks.awaitAskUser("t-1", 10));
        com.onesay.assistant.v2.s1.S1Hooks.onAskUser("t-2", "q", null, 60);
        com.onesay.assistant.v2.s1.S1Hooks.cancelAskUser("t-2");
        assertNull(com.onesay.assistant.v2.s1.S1Hooks.awaitAskUser("t-2", 10));
        java.util.List<com.onesay.assistant.v2.island.Subgoal> g = new java.util.ArrayList<com.onesay.assistant.v2.island.Subgoal>();
        g.add(new com.onesay.assistant.v2.island.Subgoal("g1", "查天气", "done"));
        g.add(new com.onesay.assistant.v2.island.Subgoal("g2", "设闹钟", "doing"));
        com.onesay.assistant.v2.s1.S1Hooks.onSubgoals(g);
        assertEquals(2, com.onesay.assistant.v2.s1.S1Hooks.lastSubgoals().size());
    }
}
