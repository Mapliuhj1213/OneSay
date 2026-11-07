package com.onesay.assistant.v2.diag;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * [diag] 合并守卫：既有文件里的设置诊断挂钩（DiagHooks 类注释 D-H1~H6）被合并冲突"吃掉"时立刻报红。只做源码文本断言。
 */
public class DiagHooksPresenceTest {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void routerHooks() throws Exception {
        String u = src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        assertFalse("UnifiedRouter.java 不能混入 LF 行", u.replace("\r\n", "").contains("\n"));
        assertTrue("K 分诊开关由内核汇总", com.onesay.assistant.v2.kernel.KernelWiring.hostCalls("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java", ".triageSwitches(mContext)"));
        assertTrue("K 本地接管", com.onesay.assistant.v2.kernel.KernelWiring.hostCalls("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java", ".takeover(mContext, query, mL2)"));
        assertTrue("K 不进 AgentLoop", com.onesay.assistant.v2.kernel.KernelWiring.hostCalls("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java", ".claims(mContext, query)"));
        String pl = com.onesay.assistant.v2.kernel.KernelWiring.plugin("DiagPlugin");
        assertTrue("D-H1", pl.contains("DiagHooks.route(ctx, query, l2)") && pl.contains("\"[ROUTE-DIAG] 设置诊断接管: \""));
        assertTrue("D-H1 只有 ask.reply 先跑分诊", pl.contains("return \"ask.reply\".equals(inv.sign);"));
        assertTrue("D-H2", pl.contains("DiagHooks.claims(ctx, query)"));
        assertTrue("D-H2b", pl.contains("DiagHooks.enabled(ctx)") && pl.contains("TriageSwitches.DIAG"));
        assertTrue("诊断在 S2 之后", com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.TAKEOVER, "s2", "diag"));
        int dg = u.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".takeover(mContext, query, mL2)");
        int triage = u.indexOf("final Triage tr = triage(query);");
        int askCache = u.indexOf("mL1.lookup(query, \"ASK\")");
        assertTrue("诊断在分诊与 ASK 缓存之前（S2 之后由上面的 KernelWiring.before 锁定）", dg > 0 && dg < triage && dg < askCache);
        int diagAsk = u.indexOf("d.label == TriageDecider.Label.DIAGNOSE");
        assertTrue("DIAGNOSE 兜底在 ASK 缓存之前", diagAsk > 0 && diagAsk < askCache);
    }

    @Test public void panelAndExecutorHooks() throws Exception {
        String s = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertFalse("FloatingSheetService.java 不能混入 LF 行", s.replace("\r\n", "").contains("\n"));
        String pl = com.onesay.assistant.v2.kernel.KernelWiring.plugin("DiagPlugin");
        assertTrue("D-H3", pl.contains("DiagHooks.bypassScreenRef(ctx, command)"));
        assertTrue("D-H3 在识屏之前", s.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".bypassScreenRef(this, command)") > 0
                && s.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".bypassScreenRef(this, command)") < s.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".screenReference(this, command)"));
        assertTrue("D-H4a", s.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".describe(inv.sign)") && pl.contains("DiagHooks.describe(sign)"));
        assertTrue("D-H4b", s.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onReplyShown(this, inv)") && pl.contains("DiagHooks.onReplyShown(svc, inv)"));
        assertTrue("D-H4b 诊断先于角色", com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.REPLY_SHOWN, "diag", "roles"));
        assertTrue("D-H5", s.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onExecuted(FloatingSheetService.this, cap, args, result)") && pl.contains("DiagHooks.onExecuted(svc, cap, r)"));
        String e = src("app/src/com/onesay/assistant/v2/executor/UnifiedExecutor.java");
        assertTrue("D-H6", e.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".executorBackends()") && com.onesay.assistant.v2.kernel.KernelWiring.plugin("DiagPlugin").contains("new com.onesay.assistant.v2.executor.backend.DiagBackend()"));
        assertTrue("DiagBackend 在 Shell 后端之前", e.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".executorBackends()") < e.indexOf("new ShellBackend()"));
        String a = src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        assertFalse("AgentLoop 不暴露任何诊断写能力", a.contains("diag."));
    }
}
