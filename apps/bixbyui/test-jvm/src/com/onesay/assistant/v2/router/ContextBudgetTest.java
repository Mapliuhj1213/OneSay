package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.router.ctx.ModelWindows;
import com.onesay.assistant.v2.router.ctx.TokenEstimator;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** ★ int/1102：token 估算 / 模型窗口表 / 请求级夹紧 / 两轮与多轮记忆 / 长对话（≥20 轮）。 */
public class ContextBudgetTest {

    @After public void reset() {
        ModelWindows.setOverrides("");
    }

    private static String rep(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    @Test public void estimatorIsConservative() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals("CJK 1 字 1 token", 4, TokenEstimator.estimate("今天天气"));
        assertEquals("ASCII 4 字符 1 token（向上取整）", 3, TokenEstimator.estimate("hello world"));
        assertEquals("4 字 + 3 个 ASCII（向上取整 1）", 4 + 1, TokenEstimator.estimate("打开蓝牙 ok"));
        assertEquals("emoji 代理对算 1 个", 1, TokenEstimator.estimate("\uD83D\uDE00"));
        assertEquals(TokenEstimator.estimate("你好") + TokenEstimator.PER_MESSAGE, TokenEstimator.message("你好"));
        assertTrue(TokenEstimator.isCjk('中') && !TokenEstimator.isCjk('a'));
    }

    @Test public void clipKeepTailByTokens() {
        String s = rep('前', 5000) + "结论：好了";
        String c = TokenEstimator.clipKeepTail(s, 300);
        assertTrue(TokenEstimator.estimate(c) <= 300);
        assertTrue(c.endsWith("结论：好了") && c.startsWith("前") && c.contains("…"));
        assertSame("没超不动", "短句", TokenEstimator.clipKeepTail("短句", 300));
    }

    @Test public void windowTableAndOverrides() {
        assertEquals(1048576, ModelWindows.window("deepseek-flash"));
        assertTrue(ModelWindows.verified("deepseek-flash"));
        assertEquals("旧名 deepseek-chat 待核实 → 保守 32k", 32768, ModelWindows.window("deepseek-chat"));
        assertFalse(ModelWindows.verified("deepseek-chat"));
        assertEquals("未知模型保守 32k", ModelWindows.FALLBACK, ModelWindows.window("some-new-model"));
        ModelWindows.setOverrides("deepseek-chat=131072, bad=12, junk, qwen3-vl-plus=abc");
        assertEquals(131072, ModelWindows.window("DeepSeek-Chat"));
        assertTrue(ModelWindows.verified("deepseek-chat"));
        assertEquals("非法项跳过", ModelWindows.FALLBACK, ModelWindows.window("bad"));
        assertTrue(ModelWindows.table().contains("deepseek-chat=131072"));
    }

    private static List<ConversationMemory.Turn> turns(int n, int answerChars) {
        List<ConversationMemory.Turn> l = new ArrayList<ConversationMemory.Turn>();
        for (int i = 0; i < n; i++) l.add(new ConversationMemory.Turn("问" + i, rep('答', answerChars), false));
        return l;
    }

    @Test public void fitsUntouchedWhenRoomy() {
        List<ConversationMemory.Turn> h = turns(5, 200);
        ContextBudget.Fit f = ContextBudget.fit(32768, 3000, h, 50, 1500, 32768);
        assertEquals(5, f.history.size());
        assertEquals(0, f.folded);
        assertEquals(1500, f.maxTokens);
        assertFalse(f.outputClamped || f.overflow);
        assertTrue(f.total() <= 32768);
    }

    @Test public void historyCompressedFirstThenOutput() {
        // 32k 窗口、系统 8k、本轮 1k、输出 2k → 历史最多 ≈ 21k；给 40 轮 × 1000 字
        List<ConversationMemory.Turn> h = turns(40, 1000);
        ContextBudget.Fit f = ContextBudget.fit(32768, 8000, h, 1000, 2000, 32768);
        assertTrue(f.log(), f.total() + ContextBudget.SAFETY <= 32768);
        assertEquals("先压历史，不动输出", 2000, f.maxTokens);
        assertTrue(f.folded > 0);
        assertEquals("折叠成摘要放第一条", ConversationMemory.SUMMARY_QUESTION, f.history.get(0).question);
        assertTrue(f.history.get(0).answer.contains("问0"));
        assertEquals("最近一轮保留原文", "问39", f.history.get(f.history.size() - 1).question);
        assertEquals("不改调用方的列表", 40, h.size());
        // 系统提示词本身就把窗口吃得差不多：历史清空后才压输出
        ContextBudget.Fit g = ContextBudget.fit(32768, 30000, h, 1000, 2000, 32768);
        assertEquals(0, g.historyTokens);
        assertTrue(g.outputClamped);
        assertTrue(g.maxTokens >= ContextBudget.MIN_OUTPUT && g.maxTokens < 2000);
        // 连最低输出都放不下 → 标 overflow，不截用户原话
        ContextBudget.Fit o = ContextBudget.fit(32768, 33000, h, 1000, 2000, 32768);
        assertTrue(o.overflow);
        assertEquals(ContextBudget.MIN_OUTPUT, o.maxTokens);
    }

    @Test public void historyBudgetCapsEvenWithHugeWindow() {
        List<ConversationMemory.Turn> h = turns(60, 1000);   // ≈ 60k token
        ContextBudget.Fit f = ContextBudget.fit(1048576, 3000, h, 100, 1500, 32768);
        assertTrue("1M 窗口也守 32k 历史预算: " + f.historyTokens, f.historyTokens <= 32768);
        assertTrue(f.folded > 0);
    }

    @Test public void taintedSurvivesRequestFold() {
        List<ConversationMemory.Turn> h = turns(30, 1500);
        h.set(0, new ConversationMemory.Turn("读一下邮件", "[读信回执] …", true));
        ContextBudget.Fit f = ContextBudget.fit(32768, 4000, h, 100, 1500, 32768);
        assertTrue("被折进摘要的外部内容仍标污点", f.history.get(0).isTainted());
    }

    /** 两轮记忆：第二轮请求体里带第一轮问答（buildBody 走夹紧后仍在）。 */
    @Test public void twoTurnMemoryInRequestBody() throws Exception {
        ConversationMemory m = ConversationMemory.newForTest(new ConversationMemory.Clock() {
            @Override public long now() { return 1000L; }
        });
        m.record("北京明天天气怎么样", "北京明天晴，20 度。", false);
        String body = L2LLMCompiler.buildBody("你是助手", m.snapshot(), "那上海呢", 800, false);
        JSONArray msgs = new JSONObject(body).getJSONArray("messages");
        assertEquals(4, msgs.length());
        assertEquals("北京明天天气怎么样", msgs.getJSONObject(1).getString("content"));
        assertEquals("那上海呢", msgs.getJSONObject(3).getString("content"));
        assertEquals(800, new JSONObject(body).getInt("max_tokens"));
    }

    /** 长对话（≥20 轮，每轮 600 字回答）：记忆不丢最近轮、超预算才折叠、请求总量 ≤ 窗口。 */
    @Test public void longConversationTwentyFiveTurns() throws Exception {
        final long[] t = {0L};
        ConversationMemory m = ConversationMemory.newForTest(new ConversationMemory.Clock() {
            @Override public long now() { return t[0]; }
        });
        for (int i = 1; i <= 25; i++) {
            m.record("第" + i + "轮的问题：帮我想想方案" + i, rep('析', 600) + "结论：方案" + i, false);
            t[0] += 20000;
        }
        assertEquals("25 × ≈620 token ≈ 15.5k < 32k：全部原样保留", 25, m.size());
        assertEquals(0, m.foldedTurns());
        String sys = rep('系', 6000);
        String body = L2LLMCompiler.buildBody(sys, m.snapshot(), "刚才第 3 轮说的方案再展开一下", 1500, false);
        JSONArray msgs = new JSONObject(body).getJSONArray("messages");
        assertEquals(1 + 25 * 2 + 1, msgs.length());
        assertTrue("第 3 轮还在原文里", msgs.getJSONObject(5).getString("content").contains("第3轮"));
        int total = 0;
        for (int i = 0; i < msgs.length(); i++) total += TokenEstimator.message(msgs.getJSONObject(i).getString("content"));
        assertTrue("请求总量 ≤ 窗口: " + total, total + 1500 <= ModelWindows.window(L2LLMCompiler.ASK_MODEL));
        // 再聊 40 轮：历史预算触发成批折叠；最早的进摘要，最近的原文，请求仍放得下
        for (int i = 26; i <= 65; i++) {
            m.record("第" + i + "轮的问题", rep('析', 600) + "结论：方案" + i, false);
            t[0] += 20000;
        }
        assertTrue(m.foldedTurns() > 0);
        assertTrue(m.historyTokens() <= m.budgetTokens());
        List<ConversationMemory.Turn> s = m.snapshot();
        assertEquals(ConversationMemory.SUMMARY_QUESTION, s.get(0).question);
        assertTrue(s.get(0).answer.contains("第1轮"));
        assertTrue(s.get(s.size() - 1).question.contains("第65轮"));
        String body2 = L2LLMCompiler.buildBody(sys, s, "继续", 1500, false);
        JSONArray m2 = new JSONObject(body2).getJSONArray("messages");
        int total2 = 0;
        for (int i = 0; i < m2.length(); i++) total2 += TokenEstimator.message(m2.getJSONObject(i).getString("content"));
        // ★ int/1102 模型迁移：问答默认 deepseek-flash（1M 窗口）—— 历史按 32k 预算折叠，请求总量 ≤ 窗口
        assertTrue("夹紧后 ≤ 窗口: " + total2, total2 + new JSONObject(body2).getInt("max_tokens") + ContextBudget.SAFETY
                <= ModelWindows.window(com.onesay.assistant.v2.net.LlmModels.chat()));
        // 窗口真是 32k 的模型（覆盖成 32k 模拟）：请求级夹紧仍保证 ≤ 32k
        ModelWindows.setOverrides(com.onesay.assistant.v2.net.LlmModels.chat() + "=32768");
        try {
            String body3 = L2LLMCompiler.buildBody(sys, s, "继续", 1500, false);
            JSONArray m3 = new JSONObject(body3).getJSONArray("messages");
            int total3 = 0;
            for (int i = 0; i < m3.length(); i++) total3 += TokenEstimator.message(m3.getJSONObject(i).getString("content"));
            assertTrue("32k 窗口夹紧: " + total3, total3 + new JSONObject(body3).getInt("max_tokens") + ContextBudget.SAFETY <= 32768);
        } finally {
            ModelWindows.setOverrides("");
        }
        assertEquals("最新一问仍在", "继续", m2.getJSONObject(m2.length() - 1).getString("content"));
    }
}
