package com.onesay.assistant.v2.summary;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * [summary] 合并守卫（源码文本断言）：挂钩 SU-H1~H6 在、CRLF 文件保持 CRLF；只读（复用既有 --compressed 采集 / 截图兜底、面板先让位）；
 * 不进能力表 / AgentLoop / L1；FLAG_SECURE 不上传；只有两个芯片；开关 + kill-switch；验收模块与 README。
 */
public class SummaryHooksPresenceTest {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    static void crlf(String rel, String s) {
        assertTrue(rel + " CRLF", s.contains("\r\n"));
        assertFalse(rel + " 不能混入 LF 行", s.replace("\r\n", "").contains("\n"));
    }

    @Test public void hooks() throws Exception {
        String l0 = src("app/src/com/onesay/assistant/v2/router/L0RuleRouter.java");
        assertTrue("SU-H1", l0.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".l0(mContext, raw)"));
        String pl = com.onesay.assistant.v2.kernel.KernelWiring.plugin("SummaryPlugin");
        assertTrue("SU-H1 插件 → Hooks", pl.contains("SummaryHooks.l0(ctx, raw)") && pl.contains("\"L0 命中规则: screen.summary\""));
        String ur = src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        crlf("UnifiedRouter.java", ur);
        assertTrue("K 分诊开关由内核汇总", com.onesay.assistant.v2.kernel.KernelWiring.hostCalls("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java", ".triageSwitches(mContext)"));
        assertTrue("K 本地接管", com.onesay.assistant.v2.kernel.KernelWiring.hostCalls("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java", ".takeover(mContext, query, mL2)"));
        assertTrue("K 不进 AgentLoop", com.onesay.assistant.v2.kernel.KernelWiring.hostCalls("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java", ".claims(mContext, query)"));
        assertTrue("SU-H2", pl.contains("SummaryHooks.route(ctx, query)") && pl.contains("\"[ROUTE-SUM] 长内容摘要接管: \""));
        int sum = ur.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".takeover(mContext, query, mL2)");
        assertTrue("SU-H2 在 S2 之前", com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.TAKEOVER, "imgsave", "summary") && com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.TAKEOVER, "summary", "s2"));
        assertTrue("SU-H2 在 L1 之前（不查不写缓存）", sum > 0 && sum < ur.indexOf("mL1.lookup(query"));
        assertTrue("SU-H3", pl.contains("SummaryHooks.claims(ctx, query)"));
        assertTrue("SU-H3 分诊开关", ur.contains("diagOn, implicitOn, imgOn, sumOn"));
        String f = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        crlf("FloatingSheetService.java", f);
        assertTrue("SU-H4", f.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".bypassScreenRef(this, command)") && pl.contains("SummaryHooks.bypassScreenRef(ctx, command)"));
        assertTrue("SU-H5", f.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".dispatch(this, inv, command)") && pl.contains("SummaryHooks.onDispatch(svc, inv, command)"));
        assertTrue("SU-H5 存图先于摘要", com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.DISPATCH, "imgsave", "summary"));
        assertTrue("SU-H6", f.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onServiceDestroy(this)") && pl.contains("SummaryHooks.onServiceDestroy(svc)"));
        assertTrue("调试驱动", src("app/src/com/onesay/assistant/v2/test/PluginDebugDrivers.java").contains("SummaryTestDriver.handle"));
        String t = src("app/src/com/onesay/assistant/v2/router/TriageDecider.java");
        assertTrue("分诊规则在记忆规则之前", t.indexOf("5s-screen-summary") < t.indexOf("3m-memory-recall"));
        assertFalse("不注册进能力表", src("app/src/com/onesay/assistant/v2/capability/BuiltinCapabilities.java").contains("screen.summary"));
        // R12 → 控制台整理第二步：摘要那一行搬到「开发者 › 诊断与摘要」页，仍经内核 addConsolePage 让插件画行（摘要 = 一行 + 细项页）
        assertTrue("控制台卡片", src("app/src/com/onesay/assistant/v2/ui/console/GroupPages.java").contains("FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_FEATURES, g, ConsoleGroups.DEV_PLUGINS, null)")
                && com.onesay.assistant.v2.kernel.KernelWiring.plugin("SummaryPlugin").contains("SummarySettings.KEY_ENABLED"));
    }

    @Test public void readOnlyCaptureContract() throws Exception {
        String c = src("app/src/com/onesay/assistant/v2/summary/SummaryController.java");
        assertTrue("面板先让位", c.indexOf("mSvc.s1HideSheetNow()") < c.indexOf("ScreenContextCapture.captureSync(mSvc)"));
        assertTrue("复用既有 --compressed 采集", c.contains("ScreenContextCapture.captureSync(mSvc)"));
        assertTrue(src("app/src/com/onesay/assistant/v2/vision/screenref/ScreenContextCapture.java").contains("uiautomator dump --compressed"));
        assertTrue("截图兜底走既有引擎 + 全黑不上传", c.contains("recognizeScreenGuarded") && c.contains("ERR_SECURE_BLACK"));
        String v = src("app/src/com/onesay/assistant/v2/vision/ScreenVisionEngine.java");
        assertTrue(v.contains("if (rejectBlack && isAllBlack(bmp))") && v.contains("PixelHeuristics.isAllBlack"));
        assertTrue("敏感闸门在调用模型之前", c.indexOf("SummaryGuard.screenReason") < c.indexOf("summarizeText(seq"));
        assertTrue("共用 L2 客户端", c.contains("l2.client().withReadTimeout(SummaryPrompt.READ_TIMEOUT_MS)"));
        assertFalse("不进 AgentLoop", c.contains("runAgentLoop") || c.contains("screenRefRunAgentLoop"));
        assertFalse("不写 L1 缓存", c.contains("getL1()") || c.contains("reportExecution"));
        for (String bad : new String[]{"input tap", "input swipe", "am start", "sendText", "performClick"}) assertFalse(bad, c.contains(bad));
        assertTrue("[SUMMARY] 日志", c.contains("[SUMMARY] 采集"));
        assertFalse("日志不打屏幕原文", c.contains("Log.i(TAG, \"[SUMMARY] 输入 \" + in.text") || c.contains("+ r.content())"));
    }

    @Test public void onlyTwoChips() throws Exception {
        String c = src("app/src/com/onesay/assistant/v2/summary/SummaryController.java");
        assertEquals(2, count(c, "labels.add("));
        assertTrue(c.contains("labels.add(SummaryHooks.CHIP_COPY)"));
        assertTrue("记下来跟随 S2 记忆开关", c.contains("if (S2Settings.memoryEnabled(mSvc)) labels.add(SummaryHooks.CHIP_NOTE)"));
        assertTrue("芯片走门禁", count(c, "mSvc.screenRefExecute(cap, a,") == 2);
        assertTrue("记下来先过 S2 敏感闸门", c.contains("SensitiveGuard.reason(content)"));
        assertEquals("复制摘要", SummaryHooks.CHIP_COPY);
        assertEquals("记下来", SummaryHooks.CHIP_NOTE);
    }

    @Test public void switchAndKill() {
        assertTrue(SummarySettings.decide(null, false, true));
        assertFalse(SummarySettings.decide(null, true, true));
        assertFalse(SummarySettings.decide(null, false, false));
        assertTrue(SummarySettings.decide(Boolean.TRUE, true, false));
        assertEquals("bixby_summary", SummarySettings.PREF);
        assertEquals("summary.off", SummarySettings.KILL);
    }

    @Test public void acceptanceModule() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        assertTrue(s.contains("cmd_summary() {"));
        assertTrue(s.contains("  summary)   cmd_summary ;;"));
        assertTrue(s.contains("Summary:V"));
        String readme = src("acceptance/README.md");
        assertTrue(readme.contains("### summary"));
        assertTrue(readme.contains("SU-0") && readme.contains("SU-M1"));
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }
}
