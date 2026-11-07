package com.onesay.assistant.v2.summary;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.router.TriageDecider;

import org.junit.Test;

/** [summary] 意图正负例 + 分诊（5s-screen-summary = DO_READ、永不进 AgentLoop；开关关闭回到原判法）。 */
public class SummaryIntentTest {

    static final String[] POS = {
            "总结一下", "总结这页", "这篇讲了什么", "帮我概括一下", "太长不看",
            "总结", "总结一下吧", "请帮我总结一下", "归纳一下", "提炼一下要点", "总结一下重点",
            "总结一下这篇文章", "总结这个页面", "总结一下当前页面", "总结一下屏幕上的内容", "概括一下聊天记录",
            "把这篇总结一下", "这页概括一下", "聊天记录总结一下", "群里的消息总结一下", "总结一下这篇文章的要点",
            "这个页面说了啥", "这篇文章主要讲的是什么", "这个群在聊什么", "这页的重点是什么", "这段话什么意思",
            "上面的内容讲了啥", "太长了不想看", "太长了帮我总结一下", "TL;DR", "给我个摘要", "来个总结", "总结一下",
    };

    static final String[] NEG = {
            "总结我的记忆", "总结一下我的记忆", "总结一下我记的东西", "总结今天的邮件", "帮我总结一下今天的邮件", "总结一下邮箱",
            "总结一下二战的原因", "概括一下红楼梦", "年终总结怎么写", "帮我写个年终总结", "写一份总结", "什么是总结",
            "总结一下我今天的日程", "总结一下今天的新闻", "总结一下今天的天气", "这个视频讲了什么", "总结一下这部电影",
            "如何总结一篇文章", "打开设置", "保存图片", "这是什么", "复制这个", "我的总结写完了", "工作总结发给领导",
            "这首歌讲了什么", "总结经验教训",
    };

    @Test public void positives() {
        for (String q : POS) assertTrue(q, SummaryIntent.isTrigger(q));
    }

    @Test public void negatives() {
        for (String q : NEG) assertFalse(q, SummaryIntent.isTrigger(q));
    }

    @Test public void triageIsLocalReadNeverAgentLoop() {
        for (String q : POS) {
            TriageDecider.Decision d = TriageDecider.decide(q, TriageDecider.Signals.offline());
            assertEquals(q, TriageDecider.Label.DO_READ, d.label);
            assertEquals(q, "5s-screen-summary", d.rule);
            assertFalse(q, d.useAgentLoop);
        }
        for (String q : NEG) {
            assertNotEquals(q, "5s-screen-summary", TriageDecider.decide(q, TriageDecider.Signals.offline()).rule);
        }
    }

    @Test public void switchOffFallsBackToOldRule() {
        TriageDecider.Decision off = TriageDecider.decide("总结一下", TriageDecider.Signals.offline(), true, true, true, false);
        assertNotEquals("5s-screen-summary", off.rule);
        assertEquals("关闭后回到 #18 之前的判法", TriageDecider.Label.ASK, off.label);
        assertEquals(TriageDecider.Label.DO_READ, TriageDecider.decide("总结一下", TriageDecider.Signals.offline(), true, true, true, true).label);
        // 旧 5 参重载默认开
        assertEquals("5s-screen-summary", TriageDecider.decide("这篇讲了什么", TriageDecider.Signals.offline(), true, true, true).rule);
    }

    @Test public void imageSaveStillWinsAndMemoryUntouched() {
        assertEquals("6i-image-save", TriageDecider.decide("保存图片", TriageDecider.Signals.offline()).rule);
        assertNotEquals("5s-screen-summary", TriageDecider.decide("记一下车停在B2", TriageDecider.Signals.offline()).rule);
    }
}
