package com.onesay.assistant.v2.kernel;

import static org.junit.Assert.*;

import android.content.Context;

import com.onesay.assistant.v2.island.IslandModel;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 第 4 阶段（D-3a，用户拍板「插件现在就上」）：识屏挂钩整体进内核 + 插件岛上活动经内核接进 IslandController。
 * 行为不变的证明：① 识屏在每个挂钩上排第一（= 迁移前宿主里识屏调用写在内核调用之前）；② 新挂钩的分发语义；
 * ③ 岛通道：没挂载 = 空操作（= 以前 sIsland == null），挂载后原样转交并带 owner。
 */
public class KernelPhase4Test {

    static final FeatureHook[] SR_HOOKS = {
            FeatureHook.WAKE, FeatureHook.AGENT_TOOLS, FeatureHook.AGENT_PROMPT, FeatureHook.AGENT_DESCRIBE,
            FeatureHook.AGENT_PRECHECK, FeatureHook.AGENT_EXECUTE, FeatureHook.SCREEN_REFERENCE,
            FeatureHook.TEXT_MODE, FeatureHook.REGION_CHIPS,
    };

    @Test public void screenRefIsFirstOnEveryHookItProvides() {
        for (FeatureHook h : SR_HOOKS) {
            assertEquals(h.name(), "screenref", FeatureKernel.get().orderIds(h).get(0));
        }
        // 迁移前顺序：WAKE 里识屏在角色之前；AGENT_* 里识屏在 S2 之前
        List<String> wake = FeatureKernel.get().orderIds(FeatureHook.WAKE);
        assertTrue(wake.indexOf("screenref") < wake.indexOf("roles"));
        for (FeatureHook h : new FeatureHook[]{FeatureHook.AGENT_TOOLS, FeatureHook.AGENT_DESCRIBE, FeatureHook.AGENT_EXECUTE}) {
            List<String> o = FeatureKernel.get().orderIds(h);
            assertTrue(h.name(), o.indexOf("screenref") < o.indexOf("s2"));
        }
    }

    @Test public void hostsNoLongerCallScreenRefHooksDirectly() {
        for (String f : new String[]{"app/src/com/onesay/assistant/v2/router/AgentLoop.java", "app/src/com/onesay/assistant/v2/s1/RegionSelectController.java"}) {
            assertFalse(f, KernelWiring.src(f).contains("ScreenRefHooks."));
        }
        String fss = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        // 只剩识屏桥接块里的 executeLocal（那是插件回调宿主的执行通道，不是挂钩）
        int n = 0, i = -1;
        while ((i = fss.indexOf("ScreenRefHooks.", i + 1)) >= 0) n++;
        assertEquals(1, n);
        assertTrue(fss.contains("ScreenRefHooks.executeLocal(this, cap, args, query)"));
    }

    // ---- 新挂钩分发语义（假插件） ----

    static class P extends FeaturePlugin {
        final String id;
        final int prio;
        final List<String> log;
        boolean gated, on = true, boom;
        String[] kinds = new String[0];

        P(String id, int prio, List<String> log) {
            this.id = id;
            this.prio = prio;
            this.log = log;
        }

        @Override public String id() { return id; }
        @Override public String title() { return id; }
        @Override public boolean enabled(Context ctx) { return on; }
        @Override public boolean kernelGated() { return gated; }
        @Override public int priority(FeatureHook hook) { return prio; }
        @Override public String[] islandActivityKinds() { return kinds; }

        @Override public String agentPrompt(Context ctx, String query, String content) {
            if (boom) throw new RuntimeException("boom");
            log.add(id + ":prompt");
            return content + "+" + id;
        }

        @Override public boolean agentPromptTainted(String before, String after) {
            return id.startsWith("t") && !after.equals(before);
        }

        @Override public String agentPrecheck(Context ctx, String name, JSONObject args) {
            log.add(id + ":pre");
            return id.equals("b") ? "obs-" + id : null;
        }

        @Override public boolean screenReference(FloatingSheetService svc, String command) {
            log.add(id + ":ref");
            return id.equals("a");
        }

        @Override public void onTextMode(FloatingSheetService svc) {
            log.add(id + ":text");
        }

        @Override public int regionChips(FloatingSheetService svc, Object region) {
            log.add(id + ":chips");
            return id.equals("b") ? 3 : 0;
        }
    }

    @Test public void agentPromptChainsAndReportsTaint() {
        List<String> log = new ArrayList<String>();
        FeatureKernel k = FeatureKernel.newEmpty();
        P t = new P("tscreen", 1, log);
        P x = new P("x", 2, log);
        P bad = new P("bad", 3, log);
        bad.boom = true;
        k.register(x).register(t).register(bad);
        FeatureKernel.Prompt r = k.agentPrompt(null, "q", "c");
        assertEquals("抛异常的插件跳过、其余链式改写", "c+tscreen+x", r.content);
        assertTrue(r.tainted);
        // ctx == null 不判污点的口径留在宿主（AgentLoop：mContext != null && prompt.tainted）
        assertTrue(KernelWiring.src("app/src/com/onesay/assistant/v2/router/AgentLoop.java").contains("mContext != null && prompt.tainted"));
        // 只有不注入不可信内容的插件 → 不 tainted
        FeatureKernel k2 = FeatureKernel.newEmpty().register(new P("x", 1, log));
        assertFalse(k2.agentPrompt(null, "q", "c").tainted);
    }

    @Test public void precheckReferenceChipsTextModeSemantics() {
        List<String> log = new ArrayList<String>();
        FeatureKernel k = FeatureKernel.newEmpty();
        k.register(new P("b", 2, log)).register(new P("a", 1, log)).register(new P("c", 3, log));
        assertEquals("obs-b", k.agentPrecheck(null, "screen.copy", new JSONObject()));
        assertEquals(java.util.Arrays.asList("a:pre", "b:pre"), log);
        log.clear();
        assertTrue(k.screenReference(null, "复制这个"));
        assertEquals(Collections.singletonList("a:ref"), log);
        log.clear();
        assertEquals(3, k.regionChips(null, new Object()));
        assertEquals(java.util.Arrays.asList("a:chips", "b:chips"), log);
        log.clear();
        k.onTextMode(null);
        assertEquals(java.util.Arrays.asList("a:text", "b:text", "c:text"), log);
    }

    // ---- 岛通道 ----

    static class FakeHost implements IslandHost {
        final List<String> calls = new ArrayList<String>();
        Listener listener;

        @Override public Context hostContext() { return null; }
        @Override public boolean islandMode() { return true; }
        @Override public void setListener(String owner, Listener l) { listener = l; calls.add(owner + ":listener"); }
        @Override public void setScopedListener(String owner, Listener l) { calls.add(owner + ":scopedListener"); }
        @Override public void bgStarted(String owner, String id, String title) { calls.add(owner + ":bgStarted:" + id + ":" + title); }
        @Override public void bgStep(String owner, String id, String text) { calls.add(owner + ":bgStep:" + id); }
        @Override public void bgConfirm(String owner, String id, String title, String detail) { calls.add(owner + ":bgConfirm:" + id); }
        @Override public void bgConfirmResolved(String owner, String id) { calls.add(owner + ":bgConfirmResolved:" + id); }
        @Override public void bgEnded(String owner, String id, boolean ok, String reason) { calls.add(owner + ":bgEnded:" + id + ":" + ok); }
        @Override public void remindFired(String owner, String id, String title, long atMs, int lateMin) { calls.add(owner + ":remind:" + id); }
        @Override public void removeBg(String owner, String id) { calls.add(owner + ":removeBg:" + id); }
        @Override public void setTodos(String owner, List<IslandModel.TodoRow> rows, int today, boolean chip) { calls.add(owner + ":todos:" + today); }
        @Override public void setNoteListener(String owner, NoteListener l) { calls.add(owner + ":noteListener"); }
        @Override public void noteStarted(String owner, String id, String title) { calls.add(owner + ":noteStarted:" + id); }
        @Override public void noteResult(String owner, String id, boolean ok, String title, String detail, String meta, List<String> actions) {
            calls.add(owner + ":noteResult:" + id + ":" + ok + ":" + (actions == null ? 0 : actions.size()));
        }
        @Override public boolean addCode(String owner, String kind, String code, String station, String pkg, long ttlMs, long noteId) {
            calls.add(owner + ":addCode:" + kind + ":" + code + ":" + ttlMs);
            return true;
        }
        @Override public void removeCode(String owner, String kind, String code) { calls.add(owner + ":removeCode:" + kind); }
        @Override public void setNotices(String owner, java.util.List<com.onesay.assistant.v2.island.IslandModel.NoticeRow> rows, boolean alert, boolean high) { calls.add(owner + ":notices:" + rows.size()); }
        @Override public void setNoticeListener(String owner, NoticeListener l) { calls.add(owner + ":noticeListener"); }
        @Override public void setSuggestListener(String o, SuggestListener l) { }
        @Override public boolean suggestShow(String o, String id, String t, String sub, String th, List<String> a, long ttl) { return false; }
        @Override public void suggestUpdate(String o, String id, String sub) { }
        @Override public void suggestWithdraw(String o, String id) { }
    }

    @Test public void islandChannelNoHostIsNoop() {
        FeatureKernel k = FeatureKernel.newEmpty();
        IslandHost.Channel ch = k.island("s3");
        assertFalse(ch.ready());
        assertFalse(ch.islandMode());
        ch.bgStarted("t1", "x");   // 不抛
        ch.setTodos(new ArrayList<IslandModel.TodoRow>(), 1, true);
        assertFalse(k.islandAttached());
    }

    @Test public void islandChannelForwardsWithOwnerAndGates() {
        List<String> log = new ArrayList<String>();
        FeatureKernel k = FeatureKernel.newEmpty();
        P s3 = new P("s3", 1, log);
        s3.kinds = new String[]{IslandHost.KIND_BG, IslandHost.KIND_REMIND, IslandHost.KIND_TODO};
        P neu = new P("neu", 1, log);   // 新插件：kernelGated + 关
        neu.gated = true;
        neu.on = false;
        k.register(s3).register(neu);
        FakeHost h = new FakeHost();
        k.attachIsland(h);
        IslandHost.Channel ch = k.island("s3");
        assertTrue(ch.ready());
        assertTrue(ch.islandMode());
        ch.setListener(null);
        ch.bgStarted("t1", "读邮件");
        ch.bgStep("t1", "正在");
        ch.bgConfirm("t1", "发邮件", "d");
        ch.bgConfirmResolved("t1");
        ch.bgEnded("t1", true, null);
        ch.remindFired("r1", "喝水", 0L, 0);
        ch.removeBg("t1");
        ch.setTodos(new ArrayList<IslandModel.TodoRow>(), 2, true);
        assertEquals(java.util.Arrays.asList("s3:listener", "s3:bgStarted:t1:读邮件", "s3:bgStep:t1", "s3:bgConfirm:t1",
                "s3:bgConfirmResolved:t1", "s3:bgEnded:t1:true", "s3:remind:r1", "s3:removeBg:t1", "s3:todos:2"), h.calls);
        // 新插件关着：通道丢弃
        h.calls.clear();
        assertFalse(k.island("neu").ready());
        k.island("neu").bgStarted("n1", "x");
        assertTrue(h.calls.isEmpty());
        // 没声明种类 / 没注册的 owner：照样转交（只警告）
        k.island("ghost").remindFired("g1", "x", 0L, 0);
        assertEquals(Collections.singletonList("ghost:remind:g1"), h.calls);
        // 只卸载自己挂的
        k.detachIsland(new FakeHost());
        assertTrue(k.islandAttached());
        k.detachIsland(h);
        assertFalse(k.island("s3").ready());
    }

    @Test public void s3GoesThroughKernelNotS1Hooks() {
        String r = KernelWiring.src("app/src/com/onesay/assistant/v2/s3/S3Runner.java");
        assertFalse("S3 不再直调 S1Hooks（跨功能依赖改走内核）", r.contains("S1Hooks."));
        assertTrue(r.contains("FeatureKernel.get().island(\"s3\")"));
        String s1 = KernelWiring.src("app/src/com/onesay/assistant/v2/s1/S1Hooks.java");
        assertFalse(s1.contains("s3BgStarted") || s1.contains("setBgListener"));
        assertTrue(s1.contains("attachIsland(sIsland)") && s1.contains("detachIsland(sIsland)"));
        String ic = KernelWiring.src("app/src/com/onesay/assistant/v2/s1/IslandController.java");
        assertTrue(ic.contains("implements com.onesay.assistant.v2.kernel.IslandHost"));
        assertTrue("岛 dump 标出活动归属", ic.contains("\" owners=\" + owners()"));
        assertEquals(java.util.Arrays.asList("bg", "remind", "todo"),
                java.util.Arrays.asList(FeatureKernel.get().find("s3").islandActivityKinds()));
        assertEquals(java.util.Arrays.asList("task", "pickup"),
                java.util.Arrays.asList(FeatureKernel.get().find("s1").islandActivityKinds()));
    }
}
