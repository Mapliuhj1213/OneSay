package com.onesay.assistant.v2.router;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.AlarmBridge;
import com.onesay.assistant.v2.soul.SoulCommands;

import org.json.JSONObject;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * ★ device-1102 问题 6'（删除所有闹钟不再反问几点）+ 问题 3（会话 10 轮 / 6000 字 / 单答 1200 字保尾、旧轮折叠摘要、
 * 回吐不入史、5 分钟保持、「新对话」重置）。
 */
public class Device1102AlarmConvTest {

    static CapabilityRegistry reg;
    static L0RuleRouter l0;

    @BeforeClass public static void setUp() {
        reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        l0 = L0RuleRouter.forRegistry(reg);
    }

    @After public void tearDown() {
        ConversationMemory.getInstance().clear();
    }

    static String sign(String q) {
        Invocation inv = l0.route(q);
        return inv == null ? null : inv.sign;
    }

    // ---- 闹钟「全部」 ----

    @Test public void allAlarmsRouteWithoutAskingTime() {
        for (String q : new String[]{"删除所有闹钟", "把闹钟全删了", "清空闹钟", "取消所有闹钟"}) {
            assertEquals(q, AlarmBridge.SIGN_CANCEL_ALL, sign(q));
        }
        for (String q : new String[]{"关掉所有闹钟", "把闹钟全关了", "关闭全部闹钟"}) {
            assertEquals(q, AlarmBridge.SIGN_TOGGLE_ALL, sign(q));
        }
        assertNotEquals("单个闹钟不被「全部」吞掉", AlarmBridge.SIGN_CANCEL_ALL, sign("删除八点的闹钟"));
    }

    @Test public void cancelAllNeedsNoParamsButConfirms() {
        Capability c = reg.describe(AlarmBridge.SIGN_CANCEL_ALL);
        assertNotNull(c);
        assertTrue("不可撤销 → 确认卡一次", c.confirm);
        assertTrue(c.paramsSchema.toJsonSchema().optJSONArray("required") == null
                || c.paramsSchema.toJsonSchema().optJSONArray("required").length() == 0);
        Capability t = reg.describe(AlarmBridge.SIGN_TOGGLE_ALL);
        assertFalse("全关可逆，不弹卡", t.confirm);
    }

    @Test public void allIdsFromDump() throws Exception {
        JSONObject d = new JSONObject("{\"alarms\":[{\"id\":3,\"hour\":7},{\"id\":9,\"hour\":8},{\"hour\":9},{\"id\":-1}]}");
        assertEquals(Arrays.asList(3, 9), AlarmBridge.allIds(d));
        assertTrue(AlarmBridge.allIds(null).isEmpty());
    }

    // ---- 会话记忆 ----

    static final class FakeClock implements ConversationMemory.Clock {
        long t = 1_000_000L;
        @Override public long now() { return t; }
    }

    @Test public void twoTurnsBothReachTheNextRequest() throws Exception {
        ConversationMemory m = ConversationMemory.getInstance();
        m.record("北京明天天气怎么样", "明天北京晴，18 度。", false);
        m.record("那上海呢", "上海明天小雨，15 度。", false);
        String body = L2LLMCompiler.buildBody("SYS", m.snapshot(), "后天呢", 100, false);
        org.json.JSONArray msgs = new JSONObject(body).getJSONArray("messages");
        assertEquals("system + 2 轮问答 + 当前", 6, msgs.length());
        assertEquals("北京明天天气怎么样", msgs.getJSONObject(1).getString("content"));
        assertEquals("上海明天小雨，15 度。", msgs.getJSONObject(4).getString("content"));
    }

    /** ★ int/1102：预算改按 token（32k）—— 短问答 12 轮全部原样保留（旧版 10 轮硬上限已去掉）；超安全上限才折叠。 */
    @Test public void manyShortTurnsAllKeptUnderTokenBudget() {
        FakeClock clk = new FakeClock();
        ConversationMemory m = ConversationMemory.newForTest(clk);
        for (int i = 1; i <= 12; i++) {
            m.record("问题" + i, "回答" + i, false);
            clk.t += 1000;
        }
        List<ConversationMemory.Turn> s = m.snapshot();
        assertEquals(12, m.size());
        assertEquals("没有折叠", 0, m.foldedTurns());
        assertEquals("问题1", s.get(0).question);
        assertEquals("问题12", s.get(s.size() - 1).question);
        // 轮数安全上限（64）之外才折叠
        for (int i = 13; i <= ConversationMemory.MAX_TURNS + 3; i++) m.record("问题" + i, "回答" + i, false);
        s = m.snapshot();
        assertEquals(ConversationMemory.MAX_TURNS, m.size());
        assertEquals(ConversationMemory.SUMMARY_QUESTION, s.get(0).question);
        assertTrue(s.get(0).answer.contains("问题1") && s.get(0).answer.contains("回答3"));
    }

    @Test public void longAnswerKeepsConclusionTail() {
        ConversationMemory m = ConversationMemory.newForTest(new FakeClock());
        StringBuilder a = new StringBuilder("步骤：");
        for (int i = 0; i < 2000; i++) a.append("第").append(i).append("步；");
        a.append("结论：已经帮你把亮度调到 30%。");
        m.record("调一下亮度", a.toString(), false);
        String stored = m.snapshot().get(0).answer;
        assertTrue("单答 ≤ 4000 token: " + com.onesay.assistant.v2.router.ctx.TokenEstimator.estimate(stored),
                com.onesay.assistant.v2.router.ctx.TokenEstimator.estimate(stored) <= ConversationMemory.MAX_ANSWER_TOKENS);
        assertTrue("确实截过", stored.length() < a.length() && stored.contains("…"));
        assertTrue("结尾的结论保住了: " + stored.substring(stored.length() - 30), stored.endsWith("结论：已经帮你把亮度调到 30%。"));
        // 旧上限 1200 字下会被截的 3000 字回答，现在原样保留
        StringBuilder mid = new StringBuilder();
        for (int i = 0; i < 3000; i++) mid.append('中');
        m.record("再说说", mid.toString(), false);
        assertEquals(3000, m.snapshot().get(1).answer.length());
    }

    @Test public void totalBudgetFoldsInsteadOfDropping() {
        ConversationMemory m = ConversationMemory.newForTest(new FakeClock());
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3900; i++) big.append('长');   // 每轮约 3.9k token
        for (int i = 0; i < 12; i++) m.record("长问题" + i, big.toString(), false);
        assertTrue("历史 ≤ 预算: " + m.historyTokens(), m.historyTokens() <= m.budgetTokens());
        assertTrue("成批折叠到 75% 以下: " + m.historyTokens(), m.historyTokens() <= (int) (m.budgetTokens() * ConversationMemory.FOLD_LOW_WATER) + 4000);
        assertTrue("被挤出的轮进了摘要", m.snapshot().get(0).answer.contains("长问题0"));
        assertTrue(m.foldedTurns() > 0);
        assertEquals("最新一轮还在", "长问题11", m.snapshot().get(m.snapshot().size() - 1).question);
    }

    @Test public void budgetConfigurableAndClamped() {
        ConversationMemory m = ConversationMemory.newForTest(new FakeClock());
        assertEquals(32768, m.budgetTokens());
        assertEquals(8192, m.setBudgetTokens(8192));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1900; i++) big.append('长');
        for (int i = 0; i < 6; i++) m.record("问" + i, big.toString(), false);
        assertTrue(m.historyTokens() <= 8192);
        assertTrue("改小预算后立即折叠", m.foldedTurns() > 0);
        assertEquals("下限 2048", ConversationMemory.MIN_HISTORY_BUDGET_TOKENS, m.setBudgetTokens(100));
        assertEquals("0 = 恢复默认", 32768, m.setBudgetTokens(0));
    }

    @Test public void keepWindowSurvivesPanelCloseThenExpires() {
        FakeClock clk = new FakeClock();
        ConversationMemory m = ConversationMemory.newForTest(clk);
        m.record("北京明天天气怎么样", "晴", false);
        m.onPanelClosed();
        clk.t += 60_000;
        m.onPanelShown();
        assertEquals("1 分钟后再唤醒：接着上轮", 1, m.size());
        m.onPanelClosed();
        clk.t += ConversationMemory.DEFAULT_KEEP_MS;
        assertFalse(m.hasContext());
        assertTrue(m.isEmpty());
        m.setKeepMs(30_000L);
        m.record("a", "b", false);
        clk.t += 31_000;
        assertTrue("可配", m.expireIfIdle());
    }

    @Test public void leakedTextNeverEntersHistory() {
        // 宿主（WakeService/AsrHotwords）上机时给全局守卫装热词前缀与词表；单测里手动装
        com.onesay.assistant.plug.leakguard.core.PromptLeakGuard.global().setHotwordPrefix(com.onesay.assistant.v2.asr.AsrHotwords.HOTWORD_PROMPT_PREFIX);
        com.onesay.assistant.plug.leakguard.core.PromptLeakGuard.global().addHotwords(Arrays.asList(com.onesay.assistant.v2.asr.AsrHotwords.staticWords()));
        ConversationMemory m = ConversationMemory.newForTest(new FakeClock());
        String hot = com.onesay.assistant.v2.asr.AsrHotwords.HOTWORD_PROMPT_PREFIX + "必应、bing、百度、谷歌、QQ邮箱、支付宝、微信、抖音";
        m.record(hot, "好的", false);
        assertEquals("热词表回吐的问句整轮不记", 0, m.size());
        m.record("今天天气怎么样", hot, false);
        assertEquals(1, m.size());
        assertFalse("回吐的答复只记占位", m.snapshot().get(0).answer.contains("必应"));
    }

    @Test public void newTopicPhrasesResetConversation() {
        for (String q : new String[]{"新对话", "开始新对话", "换个话题", "我们换个话题吧", "重新开始", "不聊这个了"}) {
            SoulCommands.Cmd c = SoulCommands.parse(q);
            assertNotNull(q, c);
            assertEquals(q, SoulCommands.Type.CLEAR_CHAT, c.type);
        }
        assertNull("普通句子不误判", SoulCommands.parse("换个话题聊聊天气怎么样"));
    }
}
