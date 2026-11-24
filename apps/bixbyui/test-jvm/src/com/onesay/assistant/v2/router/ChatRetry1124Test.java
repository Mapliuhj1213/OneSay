package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.agenttools.GuiFold;
import com.onesay.assistant.v2.askonce.AskOnce;
import com.onesay.assistant.v2.errand.ErrandIntent;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * [chat-1124] 真机（魅族）对话复现：
 * <ol>
 *   <li>任务没做成以后用户纠正「你打开了淘宝并不是美团当然找不到」→ 被认成购物新问题，出了购物方式的固定说明；</li>
 *   <li>再说「再试一次」→ 当成全新的一句，按这四个字挑工具（只挑到亮度、删闹钟），于是答「我没有打开 App 的能力」；</li>
 *   <li>中间那轮固定追问没进会话历史，模型看不到上下文。</li>
 * </ol>
 */
public class ChatRetry1124Test {

    static final String ORIGINAL = "打开美团拼好饭找到最近一张订单点击再来一单地址照旧，在付款前停下。";
    static final String FAILED = "我试了三次，都没能进到拼好饭的订单页，所以「再来一单」这一步没做成。";
    static final String CORRECTION = "你打开了淘宝并不是美团当然找不到";

    @Before @After public void reset() {
        TaskContinuation.clear();
    }

    static void plain(String s) {
        assertFalse(s, s.contains("啥") || s.contains("咋") || s.contains("呗"));
    }

    // ------------------------------------------------------------------ 认出「再试一次」和纠错

    @Test public void retryAndCorrectionPhrases() {
        for (String s : new String[]{"再试一次", "再试一次。", "你再试试", "重试", "再来一次", "重新试一下", "那你再试试吧", "再做一遍", "重来"}) {
            assertTrue(s, RetryIntent.isRetry(s));
        }
        for (String s : new String[]{"再来一单", "再说一次", "再试一次把亮度调到50", "试试看这个耳机", "好"}) {
            assertFalse(s, RetryIntent.isRetry(s));
        }
        for (String s : new String[]{CORRECTION, "你打开错了", "打开错了", "不是淘宝是美团", "不对，是美团", "我说的是美团", "应该是美团", "点错了"}) {
            assertTrue(s, RetryIntent.isCorrection(s));
        }
        for (String s : new String[]{"今天天气怎么样", "帮我买个蓝牙耳机", "打开美团"}) {
            assertFalse(s, RetryIntent.isCorrection(s));
        }
    }

    // ------------------------------------------------------------------ 场景 3：纠错不再被认成购物、不出固定说明

    @Test public void repro_correctionWasParsedAsShopping() {
        ErrandIntent in = ErrandIntent.parse(CORRECTION);
        assertFalse("纠错不是购物", in.kind == ErrandIntent.Kind.SHOP);
        assertFalse("固定追问不出", AskOnce.askable(CORRECTION));
        assertEquals("真要在网站上找东西照旧是购物", ErrandIntent.Kind.SHOP, ErrandIntent.parse("在淘宝找一个蓝牙耳机").kind);
        assertTrue("真在问新的购物事：照旧可以问一次", AskOnce.askable("帮我买个蓝牙耳机"));
        assertFalse("接着上一轮的整句不出追问", AskOnce.askable(ORIGINAL + " " + TaskContinuation.MARKER + "用户答：再试一次"));
        assertFalse("重试不出追问", AskOnce.askable("再试一次"));
    }

    // ------------------------------------------------------------------ 场景 4：「再试一次」接回没做成的任务、工具给全

    @Test public void repro_retryWithoutRecordIsAFreshSentence() {
        TaskContinuation.record(ORIGINAL, FAILED, TaskContinuation.Origin.AGENT, 1000L);   // 改前只有这一种记录：结尾不是提议 → 链断
        assertNull("改前：「再试一次」接不回去", TaskContinuation.rewrite("再试一次", 2000L));
    }

    @Test public void retryContinuesUnfinishedTaskWithFullTools() {
        TaskContinuation.recordUnfinished(ORIGINAL, FAILED, TaskContinuation.Origin.AGENT, 1000L);
        String rw = TaskContinuation.rewrite("再试一次", 2000L);
        assertNotNull(rw);
        assertTrue(rw.startsWith(ORIGINAL));
        assertTrue(TaskContinuation.isContinuation(rw));
        assertTrue(TaskContinuation.isRetry(rw));
        assertTrue(rw, rw.contains("不要说自己没有这个能力"));
        plain(rw);
        assertEquals("原任务那句照常被认出", ORIGINAL, TaskContinuation.strip(rw));
        // 分诊：直接进 AgentLoop
        TriageDecider.Decision d = TriageDecider.decide(rw, TriageDecider.Signals.offline());
        assertEquals(TriageDecider.Label.DO_ACT, d.label);
        assertEquals("0c-continue", d.rule);
        assertTrue(d.useAgentLoop);
        // 工具打分只用原任务那句（不再按「再试一次」四个字挑工具）
        assertEquals(ORIGINAL, CapabilityRetriever.scoreQuery(rw));
        // 点界面的工具一开局就给
        assertTrue(GuiFold.initial(rw, "DO_ACT", true).expanded);
        // 只接一次
        assertNull(TaskContinuation.rewrite("再试一次", 3000L));
    }

    @Test public void correctionContinuesWithUserWords() {
        TaskContinuation.recordUnfinished(ORIGINAL, FAILED, TaskContinuation.Origin.AGENT, 1000L);
        String rw = TaskContinuation.rewrite(CORRECTION, 2000L);
        assertNotNull(rw);
        assertTrue(rw.startsWith(ORIGINAL));
        assertTrue("带上用户的纠正", rw.contains("纠正你：" + CORRECTION));
        assertEquals(CORRECTION, TaskContinuation.answerOf(rw.substring(rw.indexOf(TaskContinuation.MARKER))));
        TriageDecider.Decision d = TriageDecider.decide(rw, TriageDecider.Signals.offline());
        assertEquals("纠正里的「不是」不被当成「叫我别做」", "0c-continue", d.rule);
        assertEquals("纠正里提到的别的 App 不参与挑工具", ORIGINAL, CapabilityRetriever.scoreQuery(rw));
    }

    @Test public void retryChainsAcrossAttemptsAndExpires() {
        TaskContinuation.recordUnfinished(ORIGINAL, FAILED, TaskContinuation.Origin.AGENT, 1000L);
        String rw = TaskContinuation.rewrite("再试一次", 2000L);
        TaskContinuation.recordUnfinished(rw, "还是没做成", TaskContinuation.Origin.AGENT, 3000L);   // 重做又没成：仍是同一个任务
        String rw2 = TaskContinuation.rewrite("你再试试", 4000L);
        assertEquals("不叠加标记", ORIGINAL, TaskContinuation.strip(rw2));
        assertEquals(1, rw2.split(TaskContinuation.MARKER, -1).length - 1);
        TaskContinuation.recordUnfinished(ORIGINAL, FAILED, TaskContinuation.Origin.AGENT, 1000L);
        assertNull("过了 15 分钟不接", TaskContinuation.rewrite("再试一次", 1000L + TaskContinuation.UNFINISHED_TTL_MS + 1));
        TaskContinuation.recordUnfinished(ORIGINAL, FAILED, TaskContinuation.Origin.AGENT, 1000L);
        assertNull("说了别的：链断", TaskContinuation.rewrite("今天天气怎么样", 2000L));
        assertNull(TaskContinuation.rewrite("再试一次", 2500L));
    }

    @Test public void doneTaskIsNotRetried() {
        TaskContinuation.record(ORIGINAL, "已经停在付款页了，你来付。", TaskContinuation.Origin.AGENT, 1000L);
        assertNull(TaskContinuation.rewrite("再试一次", 2000L));
    }

    @Test public void unfinishedOutcomes() {
        List<AgentLoop.Step> none = new ArrayList<AgentLoop.Step>();
        assertTrue("取消", AgentLoop.unfinished(new AgentLoop.Result(null, none, false, AgentLoop.REASON_CANCELLED)));
        assertTrue("没做成", AgentLoop.unfinished(new AgentLoop.Result(null, none, false, "卡住了")));
        assertFalse("做成了", AgentLoop.unfinished(new AgentLoop.Result("好了", none, true, null)));
        List<AgentLoop.Step> st = new ArrayList<AgentLoop.Step>();
        st.add(new AgentLoop.Step("phone__operate", new JSONObject(), "{\"ok\":false}", false, false));
        assertTrue("点界面最后一次没成，模型却写了收工的话", AgentLoop.unfinished(new AgentLoop.Result(FAILED, st, true, null)));
        st.add(new AgentLoop.Step("phone__operate", new JSONObject(), "{\"ok\":true}", true, false));
        assertFalse(AgentLoop.unfinished(new AgentLoop.Result("好了", st, true, null)));
    }

    // ------------------------------------------------------------------ 上下文：固定追问那一轮也进会话历史

    @Test public void askOnceTurnGoesIntoHistory() throws Exception {
        ConversationMemory m = ConversationMemory.getInstance();
        m.clear();
        try {
            AskOnce.Ask a = AskOnce.askShop("帮我买个蓝牙耳机");
            AskOnce.noteAsked("帮我买个蓝牙耳机", a);
            List<ConversationMemory.Turn> h = m.snapshot();
            assertEquals(1, h.size());
            assertEquals("帮我买个蓝牙耳机", h.get(0).question);
            assertEquals(a.text, h.get(0).answer);
        } finally {
            m.clear();
        }
    }

    @Test public void wiring() {
        String al = KernelWiring.src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        assertTrue("没做成就记下", al.contains("TaskContinuation.recordUnfinished(query"));
        String ah = KernelWiring.src("app/src/com/onesay/assistant/v2/askonce/AskOnceHooks.java");
        assertTrue(ah.contains("if (!AskOnce.askable(q)) return null;"));
        assertTrue(ah.contains("AskOnce.noteAsked(query, a);"));
        String cr = KernelWiring.src("app/src/com/onesay/assistant/v2/router/CapabilityRetriever.java");
        assertTrue(cr.contains("scoreAll(scoreQuery(query))"));
        String pp = KernelWiring.src("app/src/com/onesay/assistant/v2/kernel/plugins/PhonePlugin.java");
        assertTrue("跑腿关着也能接回", pp.contains("ErrandHooks.interceptContinuation(svc, command)"));
        String td = KernelWiring.src("app/src/com/onesay/assistant/v2/router/TriageDecider.java");
        int cont = td.indexOf("[chat-1124] 接续 / 重试改写过的整句");
        int neg = td.indexOf("if (isNegatedCommand(q))");
        assertTrue("接续先于否定句规则", cont > 0 && neg > cont);
    }
}
