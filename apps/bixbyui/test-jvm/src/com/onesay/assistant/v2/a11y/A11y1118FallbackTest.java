package com.onesay.assistant.v2.a11y;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.agent.Outcome;
import com.onesay.assistant.v2.agent.PhoneFailure;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.vision.CapturePlan;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

/**
 * ★ a11y-1118（真机 Meizu 21 Pro / Flyme 12.7 / SDK 36，只有 Shizuku，无障碍服务已打开并绑定）：
 * phone.operate 以前只认「读屏助手 ping 通」或「mika.a11y 插件开着（出厂默认关）+ 服务连着」，助手拉不起来时直接
 * PERMISSION_DENIED「读屏助手不可用」，无障碍后端一次都没用上。现在：助手不可用、服务连着 → 整个任务走无障碍；
 * 都不行 → 原因里带「打开无障碍」，结果卡挂入口芯片。
 */
public class A11y1118FallbackTest {

    static final String CLS = A11yState.SERVICE_CLASS;

    @Test public void settingsMatchIncludesDebugPackageAndShortForm() {
        String dbg = "com.onesay.assistant.debug";
        assertTrue(A11yState.enabledIn("com.onesay.assistant.debug/com.onesay.assistant.v2.a11y.OneSayA11yService", dbg, CLS));
        assertTrue(A11yState.enabledIn("com.other/.Svc:com.onesay.assistant.debug/com.onesay.assistant.v2.a11y.OneSayA11yService:x.y/.Z", dbg, CLS));
        assertTrue(A11yState.enabledIn(" COM.ONESAY.ASSISTANT.DEBUG/com.onesay.assistant.v2.a11y.onesaya11yservice ", dbg, CLS));
        assertTrue(A11yState.enabledIn("com.onesay.assistant/.v2.a11y.OneSayA11yService", "com.onesay.assistant", CLS));
        // release 装着、debug 没开：不能因为前缀相同就算 debug 开了
        assertFalse(A11yState.enabledIn("com.onesay.assistant/.v2.a11y.OneSayA11yService", dbg, CLS));
        assertFalse(A11yState.enabledIn("com.onesay.assistant.debugx/com.onesay.assistant.v2.a11y.OneSayA11yService", dbg, CLS));
        assertFalse(A11yState.enabledIn("com.onesay.assistant.debug/com.onesay.assistant.v2.a11y.Other", dbg, CLS));
        // 短写法按包名补全：debug 包的短写法会补成 com.onesay.assistant.debug.v2…，不是我们的类
        assertFalse(A11yState.enabledIn("com.onesay.assistant.debug/.v2.a11y.OneSayA11yService", dbg, CLS));
        assertFalse(A11yState.enabledIn(null, dbg, CLS));
        assertFalse(A11yState.enabledIn("", dbg, CLS));
        assertFalse(A11yState.enabledIn("garbage", dbg, CLS));
    }

    @Test public void chooseMatrix() {
        assertEquals(A11yState.Backend.HELPER, A11yState.choose(true, true, true, false, "").backend);
        assertEquals(A11yState.Backend.HELPER, A11yState.choose(true, false, false, false, "").backend);
        // 真机现场：助手被 Shizuku 白名单拒，服务连着 → 无障碍
        A11yState.Choice c = A11yState.choose(false, true, true, false, "start failed: DENY SHIZUKU_PROG_NOT_WHITELISTED(bixby-fastscreen-start)");
        assertEquals(A11yState.Backend.A11Y, c.backend);
        assertNull(c.reason);
        assertFalse(c.offerA11ySettings);
        // 设置里开着但实例暂时没连上（Flyme 解绑 / 重绑间隙）
        c = A11yState.choose(false, false, true, false, "backoff");
        assertEquals(A11yState.Backend.NONE, c.backend);
        assertTrue(c.reason.startsWith("读屏助手不可用"));
        assertTrue(c.reason.contains("还没连上"));
        assertTrue(c.offerA11ySettings);
        // 没打开
        c = A11yState.choose(false, false, false, false, "start failed: DENY X");
        assertEquals(A11yState.Backend.NONE, c.backend);
        assertTrue(c.reason, c.reason.startsWith("读屏助手不可用"));
        assertTrue(c.reason.contains(A11yState.NEED_A11Y));
        assertTrue(c.reason.contains("设置 › 无障碍"));
        assertTrue(c.offerA11ySettings);
        // 开发者显式关了无障碍后端：不用、也不引导
        c = A11yState.choose(false, true, true, true, "");
        assertEquals(A11yState.Backend.NONE, c.backend);
        assertFalse(c.offerA11ySettings);
    }

    @Test public void failureTellsModelToOfferEntryNotSteps() {
        String why = A11yState.choose(false, false, false, false, "start failed").reason;
        PhoneFailure f = PhoneFailure.classify(false, why, "");
        assertEquals(ErrorCode.PERMISSION_DENIED, f.code);
        assertEquals(Outcome.BLOCKED, f.outcome);
        assertEquals(Outcome.Reason.DEVICE_UNAVAILABLE, f.reason);
        assertEquals(PhoneFailure.A11Y_HINT, f.hint);
        assertTrue(f.hint.contains("打开无障碍"));
        assertTrue(f.hint.contains("别只把操作步骤列给用户"));
        // 旧原因（正忙 / 未带标记）照旧
        PhoneFailure g = PhoneFailure.classify(false, "已有一个操作任务在进行", "");
        assertEquals(Outcome.Reason.DEVICE_UNAVAILABLE, g.reason);
        assertFalse(g.hint.contains("打开无障碍"));
        assertFalse(PhoneFailure.classify(false, "读屏助手不可用：backoff", "").hint.contains("打开无障碍"));
    }

    @Test public void entryWindowAndChip() {
        assertTrue(A11yEntry.pending(1000L, 1000L));
        assertTrue(A11yEntry.pending(1000L + A11yEntry.WINDOW_MS - 1, 1000L));
        assertFalse(A11yEntry.pending(1000L + A11yEntry.WINDOW_MS, 1000L));
        assertFalse(A11yEntry.pending(5000L, 0L));
        assertFalse(A11yEntry.pending(500L, 1000L));
        assertEquals(Collections.singletonList("打开无障碍"), A11yEntry.chips(true));
        assertTrue(A11yEntry.chips(false).isEmpty());
    }

    @Test public void userVisibleTextHasNoInternalBrandNames() {
        for (String s : Arrays.asList(
                A11yState.choose(false, false, false, false, "").reason,
                A11yState.choose(false, false, true, false, "").reason,
                A11yState.choose(false, true, true, true, "").reason,
                A11yState.choose(false, false, false, false, "start failed: DENY SHIZUKU_PROG_NOT_WHITELISTED(bixby-fastscreen-start)").reason,
                A11yState.choose(false, false, true, false, "connect bixbyui_fs.com.onesay.assistant.debug Bixby").reason,
                PhoneFailure.A11Y_HINT, A11yEntry.CHIP,
                CapturePlan.userError(null), CapturePlan.userError("x"),
                CapturePlan.describe(true, "SHIZUKU_SCREENCAP_PATH(/x.png)", false, false, 77, "", false, -1),
                CapturePlan.describe(false, "", true, false, -1, "Shizuku 没在运行", false, -1),
                CapturePlan.describe(false, "", false, true, -1, "", false, -1),
                CapturePlan.describe(false, "", false, false, 0, "", false, -1),
                CapturePlan.describe(false, "", false, false, 0, "", true, 0),
                CapturePlan.describe(false, "", false, false, 0, "", true, 12))) {
            assertNotNull(s);
            String l = s.toLowerCase(java.util.Locale.ROOT);
            assertFalse(s, l.contains("bixby") || s.contains("三星") || s.contains("小布") || l.contains("samsung"));
        }
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(Paths.get(System.getProperty("bixby.root"), "app/src", rel)), "UTF-8");
    }

    @Test public void wiring() throws Exception {
        String hooks = src("com/onesay/assistant/v2/phone/PhoneHooks.java");
        assertTrue(hooks.contains("drv.chooseBackend()"));
        assertFalse("不能再只看助手 / mika.a11y 插件", hooks.contains("!drv.observeOk() && !drv.b2Ready()"));
        assertTrue(hooks.contains("A11yEntry.note(be.offerA11ySettings)"));
        String drv = src("com/onesay/assistant/v2/phone/FastScreenDriver.java");
        assertTrue(drv.contains("if (mA11yFallback) return true;"));
        String b = src("com/onesay/assistant/v2/a11y/A11yBackend.java");
        assertTrue("onUnbind 只清自己（Flyme 解绑 / 重绑交错）", b.contains("boolean mine = sInstance == this;"));
        assertFalse(b.contains("        sInstance = null;\n        if (mKeyWake != null) mKeyWake.stop();"));
        String fss = src("com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue(fss.contains("A11yEntry.onAgentResultShown(FloatingSheetService.this)"));
        String fs = src("com/onesay/assistant/v2/fastscreen/FastScreen.java");
        assertTrue(fs.contains("ExecBackendHost.spawnFastScreenHelper(mApp, SPAWN_SETTLE_MS)"));
        String vis = src("com/onesay/assistant/v2/vision/ScreenVisionEngine.java");
        assertTrue(vis.contains("CapturePlan.steps(shzOnly)"));
        assertTrue(vis.contains("CapturePlan.userError("));
    }
}
