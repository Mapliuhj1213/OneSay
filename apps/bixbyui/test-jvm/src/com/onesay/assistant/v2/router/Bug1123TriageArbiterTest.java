package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.KernelWiring;
import com.onesay.assistant.v2.sched.SchedEngine;
import com.onesay.assistant.v2.sched.SchedStore;
import com.onesay.assistant.v2.s3.S3Parser;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * ★ fix/1123-bug2：分诊 4 套抢答 → 一张优先级表、一个出口、只答一次（{@link RouteArbiter}）。
 *
 * <p>复现：修前 {@code FeatureKernel.takeover} 按插件优先级挨个问，内容插件排在快路径前 ——
 * 「每天 6 点 50 叫我」快路径是闹钟，提醒插件（300）先接走；「随口记 明天下午三点开会」日程插件（290）先接走。
 */
public class Bug1123TriageArbiterTest {

    private static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    static final class Fake extends FeaturePlugin {
        final String id;
        final int prio;
        final String sign;
        boolean claim;
        final List<String> calls = new ArrayList<String>();

        Fake(String id, int prio, String sign) {
            this.id = id;
            this.prio = prio;
            this.sign = sign;
        }

        @Override public String id() { return id; }
        @Override public String title() { return id; }
        @Override public boolean enabled(Context ctx) { return true; }
        @Override public boolean kernelGated() { return false; }
        @Override public int priority(FeatureHook hook) { return prio; }
        @Override public Invocation route(Context ctx, String q, L2LLMCompiler l2) {
            calls.add("route:" + q);
            return sign == null ? null : new Invocation(sign, new JSONObject(), 1.0, Invocation.RouteSource.L0_RULE, q);
        }
        @Override public boolean claims(Context ctx, String q) {
            calls.add("claims:" + q);
            return claim;
        }
    }

    /** 和线上同档位的一套假插件：装应用卡 50 / 日程 290 / 提醒待办 300 / 诊断 500 / 记一记 8000，都「什么都接」。 */
    private static FeatureKernel kernel(Fake... ps) {
        FeatureKernel k = FeatureKernel.newEmpty();
        for (Fake p : ps) k.register(p);
        return k;
    }

    // ------------------------------------------------------------------ 复现：冲突真实存在（线上同一批纯函数）

    @Test public void repro_conflictsAreReal() {
        long now = System.currentTimeMillis();
        // 「每天 6 点 50 叫我」：快路径 = 闹钟，提醒插件的解析器同时认成「新建提醒」
        assertEquals("clock.alarm.set", FastIntents.route("每天6点50叫我").sign);
        S3Parser.Parsed p = S3Parser.parse("每天6点50叫我", now, SH);
        assertNotNull(p);
        assertEquals(S3Parser.Type.CREATE, p.type);
        // 「随口记 明天下午三点开会」：点名随口记，日程引擎同时认成「建日程」
        assertEquals(com.onesay.assistant.v2.quicknote.QuickNoteIntent.Kind.SUIKOUJI,
                com.onesay.assistant.v2.quicknote.QuickNoteIntent.of("随口记 明天下午三点开会"));
        assertNotNull(new SchedEngine(new SchedStore.Memory(), SH).plan("随口记 明天下午三点开会", now));
    }

    // ------------------------------------------------------------------ 一张表

    @Test public void oneOrderTable() {
        assertEquals(Arrays.asList(RouteArbiter.Exit.SELF_TEST_OR_SLOT, RouteArbiter.Exit.STATE_PLUGIN, RouteArbiter.Exit.EXPLICIT_PLUGIN,
                RouteArbiter.Exit.FAST_PATH, RouteArbiter.Exit.CONTENT_PLUGIN, RouteArbiter.Exit.MISSING_APP, RouteArbiter.Exit.TRIAGE),
                RouteArbiter.ORDER);
        // 分档线 = 日程插件；线上的日程 / 提醒待办 / 记一下 / 诊断都在内容档，装应用卡 / 存图 / 摘要 / 雨天 / 电脑联动 / 计划 App 在状态档
        FeatureKernel real = FeatureKernel.get();
        for (String id : new String[]{"sched", "s3", "s2", "diag"}) {
            FeaturePlugin pl = real.find(id);
            assertNotNull(id, pl);
            assertTrue(id + " 在内容档", pl.priority(FeatureHook.TAKEOVER) >= RouteArbiter.CONTENT_TIER);
        }
        for (String id : new String[]{"roles", "imgsave", "summary", "rain", "pclink", "planner"}) {
            FeaturePlugin pl = real.find(id);
            assertNotNull(id, pl);
            assertTrue(id + " 在状态档", pl.priority(FeatureHook.TAKEOVER) < RouteArbiter.CONTENT_TIER);
        }
        assertTrue("记一记在内容档最后（没点名时不抢）", real.find(RouteArbiter.QUICKNOTE).priority(FeatureHook.TAKEOVER) >= RouteArbiter.CONTENT_TIER);
    }

    // ------------------------------------------------------------------ 修后

    @Test public void alarmSentenceNotGrabbedByReminderPlugin() {
        Fake s3 = new Fake("s3", 300, "s3.task.add");
        Fake sched = new Fake("sched", 290, "ask.reply");
        FeatureKernel k = kernel(s3, sched);
        RouteArbiter.Take t = RouteArbiter.takeover(k, null, "每天6点50叫我", null);
        assertNull("快路径整句命中 → 内容插件让路", t.hit);
        assertEquals(RouteArbiter.Exit.FAST_PATH, t.exit);
        assertTrue("提醒插件根本没被问（不会先建了提醒再丢掉）", s3.calls.isEmpty());
        assertTrue(sched.calls.isEmpty());
        assertNull("内核入口同口径", k.takeover(null, "明天早上7点叫我", null));
        assertNull(k.takeover(null, "40分钟后叫我", null));
        // 快路径不中的提醒句照旧归提醒插件
        assertEquals("s3.task.add", kernel(new Fake("s3", 300, "s3.task.add")).takeover(null, "每天6点50叫我吃药", null).inv.sign);
    }

    @Test public void explicitQuickNoteBeatsSchedule() {
        Fake sched = new Fake("sched", 290, "ask.reply#sched");
        Fake qn = new Fake(RouteArbiter.QUICKNOTE, 8000, "ask.reply#qn");
        FeatureKernel k = kernel(sched, qn);
        RouteArbiter.Take t = RouteArbiter.takeover(k, null, "随口记 明天下午三点开会", null);
        assertEquals(RouteArbiter.Exit.EXPLICIT_PLUGIN, t.exit);
        assertSame(qn, t.hit.plugin);
        assertTrue("日程插件没被问（不会多建一条日程）", sched.calls.isEmpty());
        assertEquals("ask.reply#qn", k.takeover(null, "帮我记一下 明天下午三点开会", null).inv.sign);
        // 没点名的日程句照旧是日程
        assertEquals("ask.reply#sched", k.takeover(null, "明天下午三点开会", null).inv.sign);
        // 「记一下 X」不算点名记一记（S2 的活），按原顺序
        assertNull(RouteArbiter.explicitOwner("记一下明天下午三点开会"));
    }

    @Test public void explicitOwnerDeclinedFallsBackInOrderWithoutAskingTwice() {
        Fake sched = new Fake("sched", 290, "ask.reply#sched");
        Fake qn = new Fake(RouteArbiter.QUICKNOTE, 8000, null);   // 记一记关着 / 不接
        FeatureKernel k = kernel(sched, qn);
        assertEquals("ask.reply#sched", k.takeover(null, "随口记 明天下午三点开会", null).inv.sign);
        assertEquals("点名插件只问一次", 1, qn.calls.size());
    }

    @Test public void statePluginsStillFirst() {
        Fake roles = new Fake("roles", 50, "roles.dismiss");
        Fake s3 = new Fake("s3", 300, "s3.task.add");
        FeatureKernel k = kernel(roles, s3);
        RouteArbiter.Take t = RouteArbiter.takeover(k, null, "每天6点50叫我", null);
        assertEquals(RouteArbiter.Exit.STATE_PLUGIN, t.exit);
        assertSame(roles, t.hit.plugin);
    }

    @Test public void diagDoesNotGrabFastPathSentence() {
        Fake diag = new Fake("diag", 500, "ask.reply#diag");
        FeatureKernel k = kernel(diag);
        assertNull(k.takeover(null, "打开蓝牙", null));
        assertTrue(diag.calls.isEmpty());
        assertEquals("ask.reply#diag", k.takeover(null, "WiFi老是断", null).inv.sign);
    }

    @Test public void noFastNoExplicitKeepsOldOrder() {
        Fake late = new Fake("late", 500, "late.sign");
        Fake early = new Fake("early", 100, "early.sign");
        Fake mid = new Fake("mid", 300, "mid.sign");
        FeatureKernel k = kernel(late, mid, early);
        assertEquals("early.sign", k.takeover(null, "x", null).inv.sign);
        Fake mid2 = new Fake("mid", 300, "mid.sign");
        Fake late2 = new Fake("late", 500, "late.sign");
        assertEquals("mid.sign", kernel(late2, mid2).takeover(null, "x", null).inv.sign);
        assertTrue(late2.calls.isEmpty());
    }

    @Test public void negationNeverTaken() {
        Fake s3 = new Fake("s3", 300, "s3.task.add");
        Fake qn = new Fake(RouteArbiter.QUICKNOTE, 8000, "ask.reply");
        FeatureKernel k = kernel(s3, qn);
        assertNull(k.takeover(null, "别每天6点50叫我", null));
        assertNull(RouteArbiter.explicitOwner("不要随口记这个"));
        assertTrue(s3.calls.isEmpty());
    }

    @Test public void explicitClaimsForAgentLoopDecision() {
        Fake qn = new Fake(RouteArbiter.QUICKNOTE, 8000, "ask.reply");
        qn.claim = true;
        FeatureKernel k = kernel(qn);
        assertTrue(RouteArbiter.explicitClaims(k, null, "随口记 WiFi老是断"));
        assertFalse("没点名不问", RouteArbiter.explicitClaims(k, null, "WiFi老是断"));
        assertEquals(1, qn.calls.size());
    }

    // ------------------------------------------------------------------ 只答一次

    @Test public void onceDeliversSingleFinal() {
        final List<String> got = new ArrayList<String>();
        UnifiedRouter.StreamHandler raw = new UnifiedRouter.StreamHandler() {
            @Override public void onStage(int stage) { got.add("stage" + stage); }
            @Override public void onPartial(String t) { got.add("partial:" + t); }
            @Override public void onFinal(Invocation inv) { got.add("final:" + (inv == null ? "null" : inv.sign)); }
        };
        UnifiedRouter.StreamHandler h = RouteArbiter.once(raw, "每天6点50叫我");
        assertSame("不重复包", h, RouteArbiter.once(h, "q"));
        h.onStage(1);
        h.onPartial("好");
        h.onFinal(new Invocation("clock.alarm.set", new JSONObject(), 1.0, Invocation.RouteSource.L0_RULE, "q"));
        h.onFinal(new Invocation("s3.task.add", new JSONObject(), 1.0, Invocation.RouteSource.L0_RULE, "q"));
        h.onPartial("迟到的");
        assertEquals(Arrays.asList("stage1", "partial:好", "final:clock.alarm.set"), got);
        assertEquals(2, ((RouteArbiter.Counted) h).finals());
    }

    // ------------------------------------------------------------------ 接线

    @Test public void wiring() {
        String ur = KernelWiring.src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        assertTrue("级联出口包 once", ur.contains("RouteArbiter.once(h0, query)"));
        assertTrue("进不进循环与表对齐", ur.contains("RouteArbiter.explicitClaims("));
        int fast = ur.indexOf("if (fast) return false;");
        int expl = ur.indexOf("RouteArbiter.explicitClaims(");
        int diag = ur.indexOf("wantsAgentLoop(mContext, query, mL2)");
        assertTrue("快路径 → 点名插件 → 诊断", fast > 0 && expl > fast && diag > expl);
        String fk = KernelWiring.src("app/src/com/onesay/assistant/v2/kernel/FeatureKernel.java");
        assertTrue("内核 takeover 走表", fk.contains("RouteArbiter.takeover(this, ctx, query, l2).hit"));
    }
}
