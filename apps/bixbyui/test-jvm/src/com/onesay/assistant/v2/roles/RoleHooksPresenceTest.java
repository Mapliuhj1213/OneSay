package com.onesay.assistant.v2.roles;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/** [roles] 合并守卫：RoleHooks 注释 R-H1~H5 的挂钩被合并冲突「吃掉」时立刻报红（源码文本断言）。 */
public class RoleHooksPresenceTest {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void hooks() throws Exception {
        String s2 = src("app/src/com/onesay/assistant/v2/s2/S2Hooks.java");
        assertTrue("R-H1 生产引擎", s2.contains("com.onesay.assistant.v2.roles.RoleHooks.attach(ctx, e2)"));
        assertTrue("R-H1 自测引擎", s2.contains("com.onesay.assistant.v2.roles.RoleHooks.attach(ctx, e)"));
        assertTrue("role_ui 透传给面板", s2.contains("a.put(\"role_ui\", r.ui)"));
        assertTrue("S1 打车深链可被描述", s2.contains("S1Capabilities.taxi()"));
        String f = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue("CRLF", f.contains("\r\n"));
        assertFalse("FloatingSheetService.java 不能混入 LF 行", f.replace("\r\n", "").contains("\n"));
        String pl = com.onesay.assistant.v2.kernel.KernelWiring.plugin("RolesPlugin");
        assertTrue("R-H2", f.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onReplyShown(this, inv)") && pl.contains("RoleHooks.onReplyShown(svc, inv)"));
        assertTrue("R-H3", f.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onExecFailed(FloatingSheetService.this, cap, args, result)") && pl.contains("RoleHooks.onExecFailed(svc, cap, args, r)"));
        assertTrue("R-H4", f.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onExecuted(FloatingSheetService.this, cap, args, result)") && pl.contains("RoleHooks.onExecuted(svc, cap, args, r)"));
        assertTrue("R-H4 诊断 → S2 → 角色", com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.EXECUTED, "diag", "s2") && com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.EXECUTED, "s2", "roles"));
        assertTrue("R-H5", f.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onWake(this)") && pl.contains("RoleHooks.onWake(svc)"));
        java.util.List<String> wk = com.onesay.assistant.v2.kernel.FeatureKernel.get().orderIds(com.onesay.assistant.v2.kernel.FeatureHook.WAKE);
        assertTrue("R-H5 在识屏 onWake 之后（内核 WAKE 顺序）", wk.indexOf("screenref") >= 0 && wk.indexOf("screenref") < wk.indexOf("roles"));
        String u = src("app/src/com/onesay/assistant/v2/executor/UnifiedExecutor.java");
        assertTrue("app.launch 按包名", u.contains("args.has(\"role\")"));
        assertTrue("调试驱动", src("app/src/com/onesay/assistant/v2/test/PluginDebugDrivers.java").contains("RolesTestDriver.handle"));
        // R12：控制台改成页面栈，插件页由 Console.PluginPage 经内核 addConsolePage 画（记忆页里的「默认应用」分组）
        assertTrue("控制台卡片", src("app/src/com/onesay/assistant/v2/ui/console/Console.java").contains("FeatureKernel.get().addConsolePage(c, id, col)")
                && pl.contains("RoleHooks.summary(act)") && pl.contains("ConsoleHost.PAGE_MEMORY"));
        assertTrue("角色数据资产", new File(System.getProperty("bixby.root", "."), "app/assets/app_roles.json").isFile());
        assertFalse("硬编码目录已删除", new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/s2/HabitCatalog.java").exists());
    }
}
