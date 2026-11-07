package com.onesay.assistant.v2.planner;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.kernel.KernelWiring;
import com.onesay.assistant.v2.quicknote.QuickNoteIntent;
import com.onesay.assistant.v2.router.TriageDecider;
import com.onesay.assistant.v2.s2.InMemoryMemoryStore;
import com.onesay.assistant.v2.s2.S2Capabilities;
import com.onesay.assistant.v2.s2.S2Engine;

import org.junit.Test;

import java.util.TimeZone;

/**
 * 票类口令（2026-11-05）：「帮我记下这张票」「把这张票存下来」「记下票」→ 识屏 → 计划 App 票夹（PlannerPlugin 285，经 PlannerLink），
 * 计划 App 不在 → 记一记识屏（QuickNoteIntent.TICKET，抢在 S2 之前）；S2 与分诊不再把它路由到 memory.add。
 */
public class PlannerTicketVoiceRouteTest {

    static final String[] TICKET_PHRASES = {"帮我记下这张票", "把这张票存下来", "记下票", "记一下票", "帮我把这张票记下来", "存一下这张电影票",
            "把这张票存起来", "帮我存一下这张票", "记下这张火车票", "这张票记一下", "票放进计划里", "帮我记下这张票。"};
    static final String[] NOT_TICKET = {"帮我买张票", "票价多少", "记一记", "记一下车停在B2-17", "帮我记一下 明早八点交房租", "记下票价是 45 元",
            "查一下这张票什么时候发车"};

    @Test public void phrasesAndQuickNoteFallback() {
        for (String q : TICKET_PHRASES) {
            assertTrue(q, PlannerVoice.ticket(q));
            assertEquals("计划 App 不在 → 记一记识屏：" + q, QuickNoteIntent.Kind.TICKET, QuickNoteIntent.of(q));
            assertTrue("抢在 S2 之前：" + q, QuickNoteIntent.beforeS2(q));
        }
        for (String q : NOT_TICKET) {
            assertFalse(q, PlannerVoice.ticket(q));
            assertNotEquals(q, QuickNoteIntent.Kind.TICKET, QuickNoteIntent.of(q));
        }
        assertEquals("随口记照旧", QuickNoteIntent.Kind.SUIKOUJI, QuickNoteIntent.of("帮我记一下 明早八点交房租"));
        assertEquals("记一记照旧", QuickNoteIntent.Kind.RUN, QuickNoteIntent.of("记一记"));
    }

    @Test public void s2AndTriageNeverMemoryAdd() {
        S2Engine en = new S2Engine(new InMemoryMemoryStore(), new InMemoryMemoryStore(), TimeZone.getTimeZone("Asia/Shanghai"));
        S2Engine.Flags f = new S2Engine.Flags(true, true, false, true);
        long now = 1791600000000L;
        for (String q : TICKET_PHRASES) {
            S2Engine.Result r = en.handle(q, now, f);
            assertTrue("S2 不接 / 不写记忆：" + q + " → " + r, r == null || !S2Capabilities.SIGN_NOTE_ADD.equals(r.sign));
            TriageDecider.Decision d = TriageDecider.decide(q, TriageDecider.Signals.offline(), true, true);
            assertEquals(q, TriageDecider.RULE_TICKET_SAVE, d.rule);
            assertEquals(q, TriageDecider.Label.DO_ACT, d.label);
            assertFalse(q, d.useAgentLoop);
        }
        // 原口径不退化：「记下…」普通句照旧 memory.add
        S2Engine.Result r = en.handle("记下车停在B2-17", now, f);
        assertNotNull(r);
        assertEquals(S2Capabilities.SIGN_NOTE_ADD, r.sign);
        assertEquals("6m-memory-write", TriageDecider.decide("记下车停在B2-17", TriageDecider.Signals.offline(), true, true).rule);
    }

    @Test public void wiringPlannerFirstThenQuickNote() {
        String hooks = KernelWiring.src("app/src/com/onesay/assistant/v2/planner/PlannerHooks.java");
        assertTrue("计划 App 在：识屏认票进票夹（经 PlannerLink）", hooks.contains("PlannerVoice.ticket(q)") && hooks.contains("saveTicketFromScreen(ctx, b)")
                && hooks.contains("PlannerLink link = PlannerLink.get(ctx)") && hooks.contains("if (!link.on()) return null;"));
        String pp = KernelWiring.src("app/src/com/onesay/assistant/v2/kernel/plugins/PlannerPlugin.java");
        assertTrue(pp.contains("case TAKEOVER: return 285;"));
        assertTrue("不被识屏指代抢走", pp.contains("PlannerVoice.ticket(command)"));
        String qp = KernelWiring.src("app/src/com/onesay/assistant/v2/kernel/plugins/QuickNotePlugin.java");
        assertTrue("计划 App 不在：记一记识屏", qp.contains("case TICKET:") && qp.contains("captureAndSave(ctx, \"voice\")"));
        String s2 = KernelWiring.src("app/src/com/onesay/assistant/v2/s2/S2Engine.java");
        assertTrue(s2.contains("PlannerVoice.ticket(raw)) return null;"));
    }
}
