package com.onesay.assistant.v2.kernel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import com.onesay.assistant.v2.kernel.plugins.HelloPlugin;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * [kernel] 插件内核：注册表 / 挂钩顺序（= 迁移前硬编码顺序）/ 隔离 / 门控 / 生命周期 / 分诊信号 / 示例插件。
 */
public class FeatureKernelTest {

    // ------------------------------------------------------------------ 内置注册表

    @Test public void builtinRegistry() {
        List<String> ids = new ArrayList<String>();
        for (FeaturePlugin p : FeatureKernel.get().plugins()) ids.add(p.id());
        assertEquals(Arrays.asList("s1", "island", "s2", "roles", "diag", "summary", "imgsave", "s3", "screenref", "quicknote", "brief", "conn", "errand", "callback", "notice", "sched", "planner", "rain", "phone", "phongent", "suggest", "askonce", "pclink", "hello"), ids);   // oss-device-1108 +pclink（移植电脑联动插件）;   // 2026-11-06 +rain，2026-11-05 +planner，2026-11-03 +askonce，2026-11-02 +phongent，B 线 +conn，lane C3 +errand，lane-a +callback +notice +sched
        assertEquals("id 唯一", ids.size(), new HashSet<String>(ids).size());
        for (FeaturePlugin p : FeatureKernel.get().plugins()) {
            assertNotNull(p.id() + " title", p.title());
            assertTrue(p.id() + " logTag", p.logTag().startsWith("[") && p.logTag().endsWith("]"));
            assertNotNull(p.id() + " prefs", p.prefsName());
            assertTrue(p.id() + " prefs 约定 bixby_xxx", p.prefsName().startsWith("bixby_"));
            assertTrue(p.id() + " kill-switch", p.killFiles().length > 0);
            for (String k : p.killFiles()) assertTrue(p.id() + " kill 约定 xxx.off: " + k, k.endsWith(".off"));
            boolean migrated = !"hello".equals(p.id()) && !"quicknote".equals(p.id()) && !"brief".equals(p.id()) && !"conn".equals(p.id()) && !"errand".equals(p.id()) && !"callback".equals(p.id()) && !"notice".equals(p.id()) && !"sched".equals(p.id()) && !"phone".equals(p.id()) && !"phongent".equals(p.id()) && !"suggest".equals(p.id()) && !"askonce".equals(p.id()) && !"planner".equals(p.id()) && !"rain".equals(p.id());   // 新插件（hello / 记一记 / 简报上岛 / 连接器 / 跑腿 / 回调中枢）由内核门控
            assertEquals(p.id() + " 迁移插件不由内核门控（内部按子开关现查）", !migrated, p.kernelGated());
        }
    }

    @Test public void acceptanceModulesExistInScript() {
        String sh = KernelWiring.src("acceptance/device_acceptance.sh");
        for (FeaturePlugin p : FeatureKernel.get().plugins()) {
            String m = p.acceptanceModule();
            if (m == null) continue;
            assertTrue("验收模块 cmd_" + m, sh.contains("cmd_" + m + "()"));   // 第 3 阶段：每个声明了模块的插件都必须有
        }
    }

    @Test public void exampleDefaultOff() {
        FeaturePlugin h = FeatureKernel.get().find("hello");
        assertNotNull(h);
        assertFalse(h.defaultOn());
        assertTrue(h.kernelGated());
        assertFalse("无 Context → 关", h.enabled(null));
    }

    // ------------------------------------------------------------------ 顺序 = 迁移前顺序

    private static List<String> participants(FeatureHook hook, String... ids) {
        List<String> keep = Arrays.asList(ids);
        List<String> out = new ArrayList<String>();
        for (String id : FeatureKernel.get().orderIds(hook)) if (keep.contains(id)) out.add(id);
        return out;
    }

    @Test public void orderMatchesPreMigration() {
        // UnifiedRouter ⓪c2..⓪e：存图 → 摘要 → S3 → S2 → 诊断（示例插件垫底）
        assertEquals(Arrays.asList("imgsave", "summary", "s3", "s2", "diag", "hello"),
                participants(FeatureHook.TAKEOVER, "imgsave", "summary", "s3", "s2", "diag", "hello"));
        // L0RuleRouter：存图 → 摘要
        assertEquals(Arrays.asList("imgsave", "summary"), participants(FeatureHook.L0, "imgsave", "summary"));
        // FloatingSheetService.processCommand：圈一下 → 存图待选
        assertEquals(Arrays.asList("s1", "imgsave"), participants(FeatureHook.INTERCEPT, "s1", "imgsave"));
        // 跳过识屏：S2 → S3 → 诊断 → 存图 → 摘要
        assertEquals(Arrays.asList("s2", "s3", "diag", "imgsave", "summary"),
                participants(FeatureHook.BYPASS_SCREENREF, "s2", "s3", "diag", "imgsave", "summary"));
        // dispatchInvocation：存图 → 摘要
        assertEquals(Arrays.asList("imgsave", "summary"), participants(FeatureHook.DISPATCH, "imgsave", "summary"));
        // 能力描述兜底：S2 → 诊断 → S3
        assertEquals(Arrays.asList("s2", "diag", "s3"), participants(FeatureHook.DESCRIBE, "s2", "diag", "s3"));
        // 问答上屏：诊断 → 角色
        assertEquals(Arrays.asList("diag", "roles"), participants(FeatureHook.REPLY_SHOWN, "diag", "roles"));
        // 执行成功：诊断 → S2 → 角色
        assertEquals(Arrays.asList("diag", "s2", "roles"), participants(FeatureHook.EXECUTED, "diag", "s2", "roles"));
        // 服务创建：S1 → S3；销毁：S3Runner → S1 → 存图 → 摘要
        assertEquals(Arrays.asList("s1", "s3"), participants(FeatureHook.SERVICE_CREATE, "s1", "s3"));
        assertEquals(Arrays.asList("s3", "s1", "imgsave", "summary"),
                participants(FeatureHook.SERVICE_DESTROY, "s3", "s1", "imgsave", "summary"));
        // UnifiedExecutor：S2 → S3 → 诊断 → 连接器（B 线新增，都在 Intent / Shell 之前）
        List<String> be = new ArrayList<String>();
        for (com.onesay.assistant.v2.executor.backend.ExecutorBackend b : FeatureKernel.get().executorBackends()) be.add(b.getClass().getSimpleName());
        assertEquals(Arrays.asList("S2Backend", "S3Backend", "DiagBackend", "ConnBackend"), be);
        // MainActivity：S2 → 默认应用 → 诊断 → 摘要 → 灵动岛 → 定时任务
        assertEquals(Arrays.asList("s2", "roles", "diag", "summary", "island", "s3"),
                participants(FeatureHook.CONSOLE, "s2", "roles", "diag", "summary", "island", "s3"));
    }

    @Test public void moreHostsMigrated() {
        String a = KernelWiring.src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        assertFalse("AgentLoop 不再直调 S2Hooks", a.contains("S2Hooks."));
        String e = KernelWiring.src("app/src/com/onesay/assistant/v2/executor/UnifiedExecutor.java");
        assertFalse(e.contains("new com.onesay.assistant.v2.executor.backend.S2Backend()") || e.contains("S3Backend()") || e.contains("DiagBackend()"));
        String m = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/MainActivity.java");
        for (String bad : new String[]{"S3Hooks.addConsoleCard", "RoleHooks.summary", "SummarySettings.", "DiagSettings.", "IslandSettings.", "S2Settings."}) {
            assertFalse("MainActivity 不再直接画 " + bad, m.contains(bad));
        }
        String f = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertFalse(f.contains("S1Hooks.onNewWake") || f.contains("RoleHooks.onWake") || f.contains("S2Hooks.onExecuted"));
        int early = f.indexOf(KernelWiring.K + ".onWakeEarly(this)");
        int wake = f.indexOf(KernelWiring.K + ".onWake(this)");
        assertTrue("唤醒顺序：WAKE_EARLY → WAKE（识屏在 WAKE 里排第一）", early > 0 && wake > early);
        assertFalse("FSS 不再直调识屏唤醒", f.contains("ScreenRefHooks.onWake("));
        assertEquals("screenref", FeatureKernel.get().orderIds(FeatureHook.WAKE).get(0));
    }

    @Test public void hostsNoLongerCallMigratedHooksDirectly() {
        String ur = KernelWiring.src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        for (String bad : new String[]{"ImageSaveHooks.", "SummaryHooks.", "S3Hooks.", "S2Hooks.", "DiagHooks.", "S3Settings.", "S2Settings."}) {
            assertFalse("UnifiedRouter 不再直调 " + bad + "（防止双重调用）", ur.contains(bad));
        }
        String l0 = KernelWiring.src("app/src/com/onesay/assistant/v2/router/L0RuleRouter.java");
        assertFalse(l0.contains("ImageSaveHooks.l0") || l0.contains("SummaryHooks.l0"));
        String f = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        for (String bad : new String[]{"S2Hooks.bypassScreenRef", "S3Hooks.bypassScreenRef", "DiagHooks.", "ImageSaveHooks.", "SummaryHooks.",
                "RoleHooks.onReplyShown", "RoleHooks.onExecuted", "RoleHooks.onExecFailed", "S2Hooks.onNoteAdded", "S2Hooks.describe",
                "S3Hooks.describe", "S3Runner.onServiceCreate", "S3Runner.onServiceDestroy", "S1Hooks.onServiceCreate",
                "S1Hooks.onServiceDestroy", "S1Hooks.interceptCommand"}) {
            assertFalse("FloatingSheetService 不再直调 " + bad, f.contains(bad));
        }
    }

    // ------------------------------------------------------------------ 隔离 / 门控 / 生命周期（假插件）

    static Invocation inv(String sign) {
        return new Invocation(sign, new JSONObject(), 1.0, Invocation.RouteSource.L0_RULE, "q");
    }

    static class Fake extends FeaturePlugin {
        final String id;
        final int prio;
        boolean on = true;
        boolean gated = false;
        boolean boom = false;
        boolean initBoom = false;
        String takeSign = null;
        boolean claim = false;
        boolean dispatchClaim = false;
        String signal = null;
        boolean triage = false;
        final List<String> calls = new ArrayList<String>();

        Fake(String id, int prio) {
            this.id = id;
            this.prio = prio;
        }

        void maybeBoom() {
            if (boom) throw new IllegalStateException("boom " + id);
        }

        @Override public String id() { return id; }
        @Override public String title() { return id; }
        @Override public boolean enabled(Context ctx) { return on; }
        @Override public boolean kernelGated() { return gated; }
        @Override public int priority(FeatureHook hook) { return prio; }
        @Override public void onInit(Context ctx) { calls.add("init"); if (initBoom) throw new RuntimeException("init"); }
        @Override public void onEnable(Context ctx) { calls.add("enable"); }
        @Override public void onDisable(Context ctx) { calls.add("disable"); }
        @Override public String triageSignal() { return signal; }
        @Override public boolean triageOn(Context ctx) { maybeBoom(); return triage; }
        @Override public Invocation route(Context ctx, String q, L2LLMCompiler l2) { calls.add("route"); maybeBoom(); return takeSign == null ? null : inv(takeSign); }
        @Override public Invocation l0(Context ctx, String raw) { calls.add("l0"); maybeBoom(); return takeSign == null ? null : inv(takeSign); }
        @Override public boolean claims(Context ctx, String q) { calls.add("claims"); maybeBoom(); return claim; }
        @Override public boolean interceptCommand(FloatingSheetService svc, String c) { calls.add("intercept"); maybeBoom(); return claim; }
        @Override public boolean bypassScreenRef(Context ctx, String c) { calls.add("bypass"); maybeBoom(); return claim; }
        @Override public boolean dispatch(FloatingSheetService svc, Invocation i, String c) { calls.add("dispatch"); maybeBoom(); return dispatchClaim; }
        @Override public void onExecuted(FloatingSheetService svc, com.onesay.assistant.v2.capability.Capability cap, JSONObject a, com.onesay.assistant.v2.executor.ExecutionResult r) { calls.add("executed"); maybeBoom(); }
        @Override public void onServiceDestroy(FloatingSheetService svc) { calls.add("destroy"); maybeBoom(); }
    }

    @Test public void firstNonNullWinsInPriorityOrder() {
        FeatureKernel k = FeatureKernel.newEmpty();
        Fake late = new Fake("late", 500);
        late.takeSign = "late.sign";
        Fake early = new Fake("early", 100);
        early.takeSign = "early.sign";
        k.register(late).register(early);
        assertEquals(Arrays.asList("early", "late"), k.orderIds(FeatureHook.TAKEOVER));
        FeatureKernel.Hit h = k.takeover(null, "x", null);
        assertSame(early, h.plugin);
        assertEquals("early.sign", h.inv.sign);
        assertTrue("后面的不再调用", late.calls.isEmpty());
    }

    @Test public void samePriorityKeepsRegistrationOrder() {
        FeatureKernel k = FeatureKernel.newEmpty();
        k.register(new Fake("b", 1)).register(new Fake("a", 1)).register(new Fake("c", 0));
        assertEquals(Arrays.asList("c", "b", "a"), k.orderIds(FeatureHook.CLAIMS));
    }

    @Test public void duplicateIdRejected() {
        FeatureKernel k = FeatureKernel.newEmpty();
        k.register(new Fake("x", 1));
        try {
            k.register(new Fake("x", 2));
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test public void throwingPluginDoesNotAffectOthers() {
        FeatureKernel k = FeatureKernel.newEmpty();
        Fake bad = new Fake("bad", 100);
        bad.boom = true;
        bad.takeSign = "bad";
        bad.claim = true;
        Fake good = new Fake("good", 200);
        good.takeSign = "good.sign";
        good.claim = true;
        k.register(bad).register(good);
        assertEquals("good.sign", k.takeover(null, "q", null).inv.sign);
        assertEquals("good.sign", k.l0(null, "q").inv.sign);
        assertTrue(k.claims(null, "q"));
        assertTrue(k.interceptCommand(null, "q"));
        assertTrue(k.bypassScreenRef(null, "q"));
        k.onExecuted(null, null, null, null);
        k.onServiceDestroy(null);
        assertTrue(good.calls.contains("executed") && good.calls.contains("destroy"));
    }

    @Test public void dispatchExceptionCountsAsHandled() {
        FeatureKernel k = FeatureKernel.newEmpty();
        Fake bad = new Fake("bad", 100);
        bad.boom = true;
        Fake next = new Fake("next", 200);
        next.dispatchClaim = true;
        k.register(bad).register(next);
        assertTrue("认领后抛异常：不落到通用执行路径", k.dispatch(null, inv("x"), "c"));
        assertFalse("后面的插件不再被调", next.calls.contains("dispatch"));
        FeatureKernel k2 = FeatureKernel.newEmpty();
        k2.register(new Fake("none", 1));
        assertFalse("没人认领 → 宿主照常执行", k2.dispatch(null, inv("x"), "c"));
    }

    @Test public void gatedPluginSkippedWhenOff() {
        FeatureKernel k = FeatureKernel.newEmpty();
        Fake g = new Fake("g", 1);
        g.gated = true;
        g.on = false;
        g.takeSign = "g";
        g.claim = true;
        k.register(g);
        assertNull(k.takeover(null, "q", null));
        assertFalse(k.claims(null, "q"));
        assertTrue("关闭的门控插件一个挂钩都不调", g.calls.isEmpty());
        g.on = true;
        assertEquals("g", k.takeover(null, "q", null).inv.sign);
    }

    @Test public void lifecycle() {
        FeatureKernel k = FeatureKernel.newEmpty();
        Fake a = new Fake("a", 1);
        Fake off = new Fake("off", 2);
        off.on = false;
        Fake broken = new Fake("broken", 3);
        broken.initBoom = true;
        broken.takeSign = "broken";
        k.register(a).register(off).register(broken);
        k.assemble(null);
        k.assemble(null);
        assertEquals("onInit 只一次 + onEnable", Arrays.asList("init", "enable"), a.calls);
        assertEquals(Arrays.asList("init"), off.calls);
        assertEquals(FeatureKernel.State.ENABLED, k.state("a"));
        assertEquals(FeatureKernel.State.DISABLED, k.state("off"));
        assertEquals(FeatureKernel.State.FAILED, k.state("broken"));
        assertNull("FAILED 插件整轮跳过", k.takeover(null, "q", null));
        a.on = false;
        off.on = true;
        assertEquals(2, k.refresh(null));
        assertEquals("disable", a.calls.get(a.calls.size() - 1));
        assertEquals("enable", off.calls.get(off.calls.size() - 1));
        assertEquals(0, k.refresh(null));
    }

    @Test public void triageSwitchesAggregate() {
        FeatureKernel k = FeatureKernel.newEmpty();
        Fake d = new Fake("d", 1);
        d.signal = TriageSwitches.DIAG;
        d.triage = true;
        Fake s = new Fake("s", 2);
        s.signal = TriageSwitches.SUMMARY;
        s.triage = false;
        Fake boom = new Fake("boom", 3);
        boom.signal = TriageSwitches.S3;
        boom.boom = true;
        k.register(d).register(s).register(boom);
        TriageSwitches t = k.triageSwitches(null);
        assertTrue(t.diag);
        assertFalse(t.sum);
        assertFalse("抛异常按关闭", t.s3);
        assertFalse("没有插件声明 → 关", t.img);
        assertFalse(t.implicit);
    }

    @Test public void builtinTriageSignalsCoverAllFive() {
        List<String> sig = new ArrayList<String>();
        for (FeaturePlugin p : FeatureKernel.get().plugins()) if (p.triageSignal() != null) sig.add(p.triageSignal());
        assertEquals(new HashSet<String>(Arrays.asList(TriageSwitches.DIAG, TriageSwitches.IMPLICIT_MEMORY, TriageSwitches.IMAGE_SAVE,
                TriageSwitches.SUMMARY, TriageSwitches.S3)), new HashSet<String>(sig));
        assertEquals("每个信号只有一个插件", 5, sig.size());
    }

    @Test public void declaredTriageRulesExistInDecider() {
        String t = KernelWiring.src("app/src/com/onesay/assistant/v2/router/TriageDecider.java");
        for (FeaturePlugin p : FeatureKernel.get().plugins()) {
            for (String r : p.triageRules()) assertTrue(p.id() + " 规则 " + r + " 在 TriageDecider 里", t.contains(r));
        }
    }

    // ------------------------------------------------------------------ 开关 / 示例插件

    @Test public void featureSwitch() {
        assertTrue(FeatureSwitch.decide(null, false, true));
        assertFalse(FeatureSwitch.decide(null, true, true));
        assertTrue(FeatureSwitch.decide(Boolean.TRUE, true, false));
        assertFalse(FeatureSwitch.decide(Boolean.FALSE, false, true));
        FeatureSwitch s = new FeatureSwitch("bixby_x", "enabled", true, "x.off");
        assertFalse("无 Context → 关", s.on(null));
        s.override = Boolean.TRUE;
        assertTrue(s.on(null));
        assertEquals("bixby_x.enabled def=true kill=x.off", s.toString());
    }

    @Test public void helloPlugin() {
        assertTrue(HelloPlugin.matches("插件自检"));
        assertTrue(HelloPlugin.matches(" 内核自检。"));
        assertFalse(HelloPlugin.matches("打开蓝牙"));
        assertFalse(HelloPlugin.matches(null));
        FeatureKernel k = FeatureKernel.newEmpty();
        HelloPlugin h = new HelloPlugin();
        k.register(h);
        assertNull("默认关：内核不调", k.takeover(null, "插件自检", null));
        assertFalse(k.claims(null, "插件自检"));
        HelloPlugin.SWITCH.override = Boolean.TRUE;
        try {
            FeatureKernel.Hit hit = k.takeover(null, "插件自检", null);
            assertNotNull(hit);
            assertEquals("ask.reply", hit.inv.sign);
            assertTrue(hit.inv.args.optString("text").startsWith("插件内核在线："));
            assertTrue(k.claims(null, "插件自检"));
            assertNull("别的句子不接", k.takeover(null, "打开蓝牙", null));
        } finally {
            HelloPlugin.SWITCH.override = null;
        }
    }
}
