package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ★ arch/island-progress（C 路）：分诊里「是否一开始就上岛」——单步请求（含达成型单步）不再包成岛上的任务，
 * 多步 / 混合照旧立即上岛；不改「是否进 AgentLoop」。
 */
public class TriageIslandTest {

    static TriageDecider.Decision d(String q) {
        return TriageDecider.decide(q, TriageDecider.Signals.offline());
    }

    @Test public void multiAndMixedGoUpFront() {
        assertTrue(TriageDecider.islandUpFront(d("打开wifi然后打开蓝牙")));
        assertTrue(TriageDecider.islandUpFront(d("如果电量低于20就打开省电模式")));
        assertTrue(TriageDecider.islandUpFront(d("先查一下电量再决定要不要开省电模式")));
    }

    @Test public void singleStepIsLazyButStillUsesLoop() {
        TriageDecider.Signals l0 = new TriageDecider.Signals("store.search.samsung", false, false);
        TriageDecider.Decision dl = TriageDecider.decide("下载微信", l0);
        assertTrue("达成型单步照旧进循环", dl.useAgentLoop);
        assertFalse("但不一开始就上岛", TriageDecider.islandUpFront(dl));
        assertFalse(TriageDecider.islandUpFront(d("能不能把亮度调高")));
        assertFalse(TriageDecider.islandUpFront(null));
    }

    @Test public void islandDecisionDoesNotChangeLoopDecision() {
        String[] qs = {"打开wifi然后打开蓝牙", "下载微信", "能不能把亮度调高", "如果明天下雨就提醒我带伞", "wifi是什么"};
        for (String q : qs) {
            TriageDecider.Decision x = d(q);
            TriageDecider.islandUpFront(x);
            assertTrue(q, x.toString().equals(d(q).toString()));
        }
    }
}
