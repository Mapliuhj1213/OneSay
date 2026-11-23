package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B11：点名搜索引擎 / 识屏带问题的模板走本地，不被时效词、问句判成问答（t20k router.L0.template 9 条复现）。 */
public class B11ExplicitTargetTest {

    static L0RuleRouter L0;
    static final CompoundSplitter.Resolver RES = new CompoundSplitter.Resolver() {
        @Override public Invocation resolve(String c) {
            if (c == null || c.trim().isEmpty()) return null;
            Invocation i = L0.route(c);
            return i != null && SearchRouting.dropL0(i.sign, c) ? null : i;
        }
    };

    @BeforeClass public static void init() {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        L0 = L0RuleRouter.forRegistry(reg);
    }

    /** 零 LLM 流水线（同 UnifiedRouter.triage）：本地执行返回 Invocation，否则 null。 */
    static Invocation local(String q) {
        FollowupEdit.clearForTest();
        UnifiedRouter.Triage ft = FastPathTriage.triage(q, RES);
        if (ft != null) return ft.l0;
        Invocation l0 = FastPathTriage.l0WithCore(q, RES);
        TriageDecider.Decision d = TriageDecider.decide(q, new TriageDecider.Signals(l0 != null ? l0.sign : null, false, false));
        switch (d.label) {
            case ASK: case CLARIFY: case DIAGNOSE: case AMBIGUOUS: return null;
            default: return !d.useAgentLoop ? l0 : null;
        }
    }

    static void localIs(String q, String sign, String key, String val) {
        Invocation l = local(q);
        assertNotNull(q, l);
        assertEquals(q, sign, l.sign);
        if (key != null) assertEquals(q, val, l.args.optString(key));
    }

    @Test public void namedEngineSearchRunsLocally() {
        localIs("用必应搜索天气", "browser.search.bing", "keyword", "天气");
        localIs("google搜索天气", "browser.search.google", "keyword", "天气");
        localIs("用google搜天气", "browser.search.google", "keyword", "天气");
        localIs("用搜狗搜索天气", "browser.search.sogou", "keyword", "天气");
    }

    @Test public void screenQuestionRunsLocally() {
        for (String q : new String[]{"识别屏幕这是什么", "看屏幕这是什么", "帮我看看屏幕这是什么", "看看屏幕这是什么"}) {
            localIs(q, "vision.screen.understand", "content", "这是什么");
        }
    }

    @Test public void plainQuestionsStayAsk() {
        for (String q : new String[]{"搜索天气", "这是什么", "今天天气怎么样", "必应是什么", "google是谁开的"}) {
            assertNull(q, local(q));
        }
    }

    @Test public void multiStepStillGoesToLoop() {
        for (String q : new String[]{"看看屏幕上写的什么然后打开微信", "用必应搜索天气然后打开蓝牙并且告诉我结果"}) {
            Invocation l = local(q);
            assertTrue(q, l == null || !"vision.screen.understand".equals(l.sign) && !l.sign.startsWith("browser.search."));
        }
    }

    @Test public void negationStillWins() {
        for (String q : new String[]{"别用必应搜索天气", "不要识别屏幕这是什么"}) {
            assertNull(q, local(q));
        }
    }
}
