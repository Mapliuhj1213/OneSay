package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.kernel.KernelWiring;
import com.onesay.assistant.v2.router.longctx.LongCtx;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ rel/fix-1123 第二轮整合：分诊统一出口（RouteArbiter「只答一次」，fix/1123-bug2）和长对话出口自检「重答一次」
 * （fix/1123-longctx-phone）能共存 —— 重答在交付之前就换掉了第一次的答复，走的是同一个出口、只交付一次，
 * 不会被「只答一次」当成第二次交付丢掉，也不会让用户先听到复读的那句、再听到重答的那句。
 */
public class Merge1123bExitTest {

    @Before public void setUp() {
        LongCtx.setEnabled(true);
        LongCtx.setSelfCheck(true);
        ConversationMemory.getInstance().clear();
    }

    @After public void tearDown() {
        ConversationMemory.getInstance().clear();
        LongCtx.setEnabled(true);
        LongCtx.setSelfCheck(true);
    }

    /** 记下出口收到了什么（照 UnifiedRouter.cascade 的用法：外面包一层 RouteArbiter.once）。 */
    static final class Sink implements UnifiedRouter.StreamHandler {
        final List<String> got = new ArrayList<String>();

        @Override public void onStage(int stage) {
            got.add("stage" + stage);
        }

        @Override public void onPartial(String t) {
            got.add("partial:" + t);
        }

        @Override public void onFinal(Invocation inv) {
            got.add("final:" + (inv == null ? "null" : inv.args.optString("text")));
        }
    }

    static Invocation reply(String text, String q) throws Exception {
        JSONObject a = new JSONObject();
        a.put("text", text);
        a.put("query", q);
        return new Invocation("ask.reply", a, 0.9, Invocation.RouteSource.L2_LLM, q);
    }

    static final class Scripted implements L2LLMCompiler.BodyCaller {
        final List<String> replies = new ArrayList<String>();
        int calls;

        @Override public String call(String body) {
            calls++;
            return calls <= replies.size() ? replies.get(calls - 1) : null;
        }
    }

    /** 问答通道（非流式）：askTurn 里复述 → 重答一次，出口只交付一次，交付的是重答。 */
    @Test public void askRedoIsTheOneDelivery() throws Exception {
        ConversationMemory m = ConversationMemory.getInstance();
        m.beginTurn("天空为什么是蓝色的");
        m.record("天空为什么是蓝色的", "阳光里的蓝光被空气散射得最多。");
        m.beginTurn("鸡蛋煮几分钟最好");
        Scripted s = new Scripted();
        s.replies.add("你问的是「鸡蛋煮几分钟最好」。");
        s.replies.add("水开后煮六分钟是溏心，九分钟全熟。");
        String answer = L2LLMCompilerAccess.askTurn("系统", "鸡蛋煮几分钟最好", "鸡蛋煮几分钟最好", m, 300, s, false);
        assertEquals("自检打回、重答了一次", 2, s.calls);
        assertEquals(1, m.retries());

        Sink raw = new Sink();
        UnifiedRouter.StreamHandler h = RouteArbiter.once(raw, "鸡蛋煮几分钟最好");
        h.onFinal(reply(answer, "鸡蛋煮几分钟最好"));
        assertEquals("出口只收到一次、是重答的那句", java.util.Arrays.asList("final:水开后煮六分钟是溏心，九分钟全熟。"), raw.got);
        assertEquals(1, ((RouteArbiter.Counted) h).finals());
        assertEquals("记进会话的也是重答", "水开后煮六分钟是溏心，九分钟全熟。", m.snapshot().get(m.snapshot().size() - 1).answer);
    }

    /** 流式问答：半成品照常上屏；重答替换终稿，终稿只交付一次，没被「只答一次」丢掉；之后误发的重复交付才丢。 */
    @Test public void streamRedoReplacesDraftOnce() throws Exception {
        Sink raw = new Sink();
        UnifiedRouter.StreamHandler h = RouteArbiter.once(raw, "鸡蛋煮几分钟最好");
        h.onStage(1);
        h.onPartial("你问的是");
        h.onPartial("你问的是「鸡蛋煮几分钟最好」。");
        // askStream：recheck 在 onDone 之前 → onDone 拿到的就是重答
        h.onFinal(reply("水开后煮六分钟是溏心。", "鸡蛋煮几分钟最好"));
        h.onFinal(reply("你问的是「鸡蛋煮几分钟最好」。", "鸡蛋煮几分钟最好"));   // 假设以后有人漏写 return 又交付一次
        assertEquals(java.util.Arrays.asList("stage1", "partial:你问的是", "partial:你问的是「鸡蛋煮几分钟最好」。",
                "final:水开后煮六分钟是溏心。"), raw.got);
        assertEquals(2, ((RouteArbiter.Counted) h).finals());
    }

    /** 工具循环（AgentLoop）：纯聊天收工前自检打回，在循环里重答，Result 只有一个答复（重答的）。 */
    @Test public void agentLoopRedoStaysInsideOneResult() throws Exception {
        ConversationMemory.getInstance().beginTurn("天空为什么是蓝色的");
        ConversationMemory.getInstance().record("天空为什么是蓝色的", "阳光里的蓝光被空气散射得最多。");
        ConversationMemory.getInstance().beginTurn("鸡蛋煮几分钟最好");
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.say("你问的是「鸡蛋煮几分钟最好」。"));
        llm.replies.add(AgentLoopTest.say("水开后煮六分钟是溏心，九分钟全熟。"));
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("鸡蛋煮几分钟最好", new AgentLoopTest.FakeExec(), 0);
        assertEquals("循环里重答一次", 2, llm.messagesSeen.size());
        assertTrue(r.answer, r.answer.contains("六分钟"));
        assertFalse("复读的那句没交付", r.answer.contains("你问的是"));
        Sink raw = new Sink();
        UnifiedRouter.StreamHandler h = RouteArbiter.once(raw, "鸡蛋煮几分钟最好");
        h.onFinal(reply(r.answer, "鸡蛋煮几分钟最好"));
        assertEquals(1, raw.got.size());
        assertEquals(1, ((RouteArbiter.Counted) h).finals());
    }

    /** 接线：级联只包一层 once 且之后都用包过的 h；三处重答都在交付 / 记账之前。 */
    @Test public void wiringRedoBeforeExit() {
        String ur = KernelWiring.src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        int wrap = ur.indexOf("RouteArbiter.once(h0, query)");
        assertTrue(wrap > 0);
        assertEquals("只包一层", wrap, ur.lastIndexOf("RouteArbiter.once("));
        assertEquals("h0 只在包的那行用", 2, count(ur, "h0"));   // 形参 + 包的那一行

        String l2 = KernelWiring.src("app/src/com/onesay/assistant/v2/router/L2LLMCompiler.java");
        int stream = l2.indexOf("public boolean askStream(");
        int re = l2.indexOf("answer = recheck(", stream);
        int done = l2.indexOf("cb.onDone(answer)", stream);
        assertTrue("流式：重答在 onDone 之前", stream > 0 && re > stream && done > re);
        int turn = l2.indexOf("static String askTurn(");
        int re2 = l2.indexOf("answer = recheck(", turn);
        int rec2 = l2.indexOf("mem.record(", turn);
        assertTrue("非流式：重答在记终稿之前", turn > 0 && re2 > turn && rec2 > re2);

        String al = KernelWiring.src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        int chk = al.indexOf("ReplyCheck.check(");
        int cont = al.indexOf("continue;", chk);
        int conclude = al.indexOf("轮收工，答复=", chk);
        assertTrue("循环：打回在收工之前（continue 回到下一轮）", chk > 0 && cont > chk && conclude > cont);
    }

    static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) n++;
        return n;
    }
}
