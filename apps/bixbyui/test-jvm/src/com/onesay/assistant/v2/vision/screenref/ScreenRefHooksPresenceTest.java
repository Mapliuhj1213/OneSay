package com.onesay.assistant.v2.vision.screenref;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * [screenref] 合并守卫：既有文件里的挂钩被合并冲突"吃掉"时立刻报红（清单）。
 * 只做源码文本断言，不加载 Android 类。
 */
public class ScreenRefHooksPresenceTest {

    static String src(String rel) throws Exception {
        File loc = com.onesay.assistant.v2.testutil.TestRoot.start(ScreenRefHooksPresenceTest.class);
        File dir = loc;
        for (int i = 0; i < 6 && dir != null; i++) {
            File f = new File(dir, "app/src/" + rel);
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            dir = dir.getParentFile();
        }
        return new String(Files.readAllBytes(new File("app/src/" + rel).toPath()), StandardCharsets.UTF_8);
    }

    static final String K = "com.onesay.assistant.v2.kernel.FeatureKernel.get()";

    /** 第 4 阶段（D-3a）：AgentLoop 只调内核挂钩，识屏由 ScreenRefPlugin 经内核提供（排第一）。 */
    @Test public void agentLoopHooks() throws Exception {
        String s = src("com/onesay/assistant/v2/router/AgentLoop.java");
        assertFalse("AgentLoop 不再直调 ScreenRefHooks", s.contains("ScreenRefHooks."));
        assertTrue("H1 appendTools", s.contains(K + ".appendAgentTools(mContext, tools, query)"));
        assertTrue("H2 withScreenHint", s.contains(K + ".agentPrompt(") && s.contains("prompt.tainted"));
        assertTrue("H3 describe", s.contains(K + ".describeForAgent(mContext, name)"));
        assertTrue("H4 handleLocalTool", s.contains(K + ".agentPrecheck(mContext, name, args)"));
        assertTrue("H4b executeForAgent", s.contains(K + ".executeForAgent(mContext, cap, args)"));
        String pl = src("com/onesay/assistant/v2/kernel/plugins/ScreenRefPlugin.java");
        for (String d : new String[]{"ScreenRefHooks.appendTools(ctx, tools, query)", "ScreenRefHooks.withScreenHint(ctx, query, content)",
                "ScreenRefHooks.isScreenInjected(before, after)", "ScreenRefHooks.describe(ctx, name)",
                "ScreenRefHooks.handleLocalTool(ctx, name, args)", "ScreenRefHooks.executeForAgent(ctx, cap, args)"}) {
            assertTrue("插件转给 " + d, pl.contains(d));
        }
    }

    @Test public void floatingSheetServiceHooks() throws Exception {
        String s = src("com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertFalse("FSS 不再直调识屏唤醒", s.contains("ScreenRefHooks.onWake("));
        assertFalse("FSS 不再直调识屏拦截", s.contains("ScreenRefHooks.interceptCommand("));
        assertFalse("FSS 不再直调还焦点", s.contains("ScreenRefHooks.restoreFocus()"));
        assertTrue("H6 interceptCommand", s.contains(K + ".screenReference(this, command)"));
        assertTrue("H7 restoreFocus", s.contains(K + ".onTextMode(this)"));
        assertTrue("H8 bridge block", s.contains("[screenref] BEGIN") && s.contains("public void screenRefExecute(")
                && s.contains("public void screenRefRunAgentLoop(") && s.contains("public boolean screenRefSheetAlive()"));
        String pl = src("com/onesay/assistant/v2/kernel/plugins/ScreenRefPlugin.java");
        assertTrue("H5 onWake", pl.contains("ScreenRefHooks.onWake(svc, svc.getSheetView(), svc.pluginWindowManager(), svc.pluginSheetParams())"));
        assertTrue(pl.contains("ScreenRefHooks.interceptCommand(svc, command)") && pl.contains("ScreenRefHooks.restoreFocus()")
                && pl.contains("ScreenRefHooks.showChipsForContext(svc, (ScreenContext) region)"));
        // 内核 WAKE（识屏排第一）必须在 setListeningPrompt 之前（面板挂载前让出焦点）
        int wake = s.indexOf(K + ".onWake(this)");
        int listen = s.indexOf("setListeningPrompt(prompt);", wake);
        assertTrue("onWake 必须先于 setListeningPrompt", wake > 0 && listen > wake && listen - wake < 400);
        String rs = src("com/onesay/assistant/v2/s1/RegionSelectController.java");
        assertFalse("圈选不再直调识屏芯片", rs.contains("ScreenRefHooks."));
        assertTrue(rs.contains(K + ".regionChips(mSvc, region)"));
    }
}
