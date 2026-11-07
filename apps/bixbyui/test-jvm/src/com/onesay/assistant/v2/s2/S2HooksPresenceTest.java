package com.onesay.assistant.v2.s2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * [s2] 合并守卫：既有文件里的 S2 挂钩（S2Hooks 类注释 S2-H1~H8）被合并冲突"吃掉"时立刻报红。只做源码文本断言。
 */
public class S2HooksPresenceTest {

    static String src(String rel) throws Exception {
        File loc = com.onesay.assistant.v2.testutil.TestRoot.start(S2HooksPresenceTest.class);
        File dir = loc;
        for (int i = 0; i < 6 && dir != null; i++) {
            File f = new File(dir, rel);
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            dir = dir.getParentFile();
        }
        return new String(Files.readAllBytes(new File(rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void routerHooks() throws Exception {
        String u = src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        assertTrue("UnifiedRouter.java 保持 CRLF", u.contains("\r\n"));
        assertFalse("UnifiedRouter.java 不能混入 LF 行", u.replace("\r\n", "").contains("\n"));
        assertTrue("K 分诊开关由内核汇总", com.onesay.assistant.v2.kernel.KernelWiring.hostCalls("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java", ".triageSwitches(mContext)"));
        assertTrue("K 本地接管", com.onesay.assistant.v2.kernel.KernelWiring.hostCalls("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java", ".takeover(mContext, query, mL2)"));
        assertTrue("K 不进 AgentLoop", com.onesay.assistant.v2.kernel.KernelWiring.hostCalls("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java", ".claims(mContext, query)"));
        String pl = com.onesay.assistant.v2.kernel.KernelWiring.plugin("MemoryPlugin");
        assertTrue("S2-H1", pl.contains("S2Hooks.route(ctx, query)") && pl.contains("\"[ROUTE-S2] S2 本地接管: \""));
        assertTrue("S2-H1 S3 先于 S2（记个待办）", com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.TAKEOVER, "s3", "s2"));
        assertTrue("S2-H2", pl.contains("S2Hooks.claims(ctx, query)"));
        int s2 = u.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".takeover(mContext, query, mL2)");
        int triage = u.indexOf("final Triage tr = triage(query);");
        int askCache = u.indexOf("mL1.lookup(query, \"ASK\")");
        assertTrue("S2 在分诊与 ASK 缓存之前", s2 > 0 && s2 < triage && s2 < askCache);
    }

    @Test public void panelHooks() throws Exception {
        String s = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue("CRLF", s.contains("\r\n"));
        assertFalse("FloatingSheetService.java 不能混入 LF 行", s.replace("\r\n", "").contains("\n"));
        String pl = com.onesay.assistant.v2.kernel.KernelWiring.plugin("MemoryPlugin");
        assertTrue("S2-H3", s.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".bypassScreenRef(this, command)") && pl.contains("S2Hooks.bypassScreenRef(ctx, command)"));
        assertTrue("识屏挂钩仍在（第 4 阶段起经内核 SCREEN_REFERENCE）", s.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".screenReference(this, command)"));
        assertTrue("S2-H3 在识屏之前", s.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".bypassScreenRef") < s.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".screenReference(this, command)"));
        assertTrue("S2-H4", s.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".describe(inv.sign)") && pl.contains("S2Hooks.describe(sign)"));
        assertEquals("S2-H4 描述顺序 S2 → 诊断 → S3", java.util.Arrays.asList("s2", "diag", "s3"),
                filter(com.onesay.assistant.v2.kernel.KernelWiring.class, com.onesay.assistant.v2.kernel.FeatureHook.DESCRIBE, "s2", "diag", "s3"));
        assertTrue("S2-H5 插件", pl.contains("S2Hooks.onExecuted(ctx, query, cap, args, r)"));
        assertTrue("S2-H5 单步", s.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".afterExec(FloatingSheetService.this, query, cap, args, result)"));
        assertTrue("S2-H5 循环", s.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".afterExec(FloatingSheetService.this, command, cap, args, r)"));
    }

    @Test public void executorLoopAndChipHooks() throws Exception {
        String pl = com.onesay.assistant.v2.kernel.KernelWiring.plugin("MemoryPlugin");
        assertTrue("S2-H6", src("app/src/com/onesay/assistant/v2/executor/UnifiedExecutor.java").contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".executorBackends()")
                && pl.contains("new com.onesay.assistant.v2.executor.backend.S2Backend()"));
        String a = src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        assertTrue("S2-H7a", a.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".appendAgentTools(mContext, tools, query)") && pl.contains("S2Hooks.appendTools(ctx, tools, query)"));
        assertTrue("S2-H7b", a.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".describeForAgent(mContext, name)") && pl.contains("S2Hooks.describeForAgent(ctx, name)"));
        assertTrue("S2-H7c", a.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".executeForAgent(mContext, cap, args)") && pl.contains("S2Hooks.executeForAgent(ctx, cap, args)"));
        java.util.List<String> at = com.onesay.assistant.v2.kernel.FeatureKernel.get().orderIds(com.onesay.assistant.v2.kernel.FeatureHook.AGENT_TOOLS);
        assertTrue("S2-H7 识屏工具在前（第 4 阶段起由内核优先级保证）", at.indexOf("screenref") >= 0 && at.indexOf("screenref") < at.indexOf("s2"));
        String c = src("app/src/com/onesay/assistant/v2/s1/ClipboardWatcher.java");
        assertTrue("S2-H8", c.contains("S2Hooks.chipAction(mSvc, chip.kind.name(), chip.entity, a)"));
        String r = src("app/src/com/onesay/assistant/v2/test/PluginDebugDrivers.java");   // 第 3 阶段：调试入口经插件声明分发
        assertTrue("debug 入口", r.contains("S2TestDriver.handle("));
    }

    static java.util.List<String> filter(Class<?> unused, com.onesay.assistant.v2.kernel.FeatureHook hook, String... ids) {
        java.util.List<String> keep = java.util.Arrays.asList(ids);
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String id : com.onesay.assistant.v2.kernel.FeatureKernel.get().orderIds(hook)) if (keep.contains(id)) out.add(id);
        return out;
    }
}
