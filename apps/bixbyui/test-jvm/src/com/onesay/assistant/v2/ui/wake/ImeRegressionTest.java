package com.onesay.assistant.v2.ui.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

import com.onesay.assistant.v2.ui.wake.WakeStage.Stage;

/**
 * ★ r2/input（2026-10-24）键盘盖住输入框（LC-2 复发）回归护栏：几何 + 接线。
 *
 * <p>复发根因（docs/r2-input.md §3）：LC-2 只在 stage == TEXT 时给输入法让位；「⌨ 打字 → 发送 → 出结果」之后卡片窗保持可获焦、输入条仍可见，
 * 用户在结果页直接点输入框接着问 → stage 是 RESULT → 键盘弹起、卡片不让位 → 输入框和发送键在键盘下面。
 */
public class ImeRegressionTest {

    // ---------------------------------------------------------------- 跟踪条件

    @Test public void resultPageTapOnInputTracksIme() {
        assertTrue("复发场景：结果页点输入框", WindowFlowPolicy.trackIme(Stage.RESULT, true, true));
        assertTrue(WindowFlowPolicy.trackIme(Stage.ANSWERING, true, true));
        assertTrue(WindowFlowPolicy.trackIme(Stage.ERROR, true, true));
        assertTrue("打字阶段永远跟踪", WindowFlowPolicy.trackIme(Stage.TEXT, false, false));
        assertFalse("输入框没焦点", WindowFlowPolicy.trackIme(Stage.RESULT, true, false));
        assertFalse("输入条不可见", WindowFlowPolicy.trackIme(Stage.RESULT, false, true));
        assertFalse(WindowFlowPolicy.trackIme(Stage.LISTENING, false, false));
    }

    @Test public void focusEntersTextExceptIslandStages() {
        for (Stage s : new Stage[] {Stage.LISTENING, Stage.THINKING, Stage.ANSWERING, Stage.RESULT, Stage.CONFIRM, Stage.ERROR, Stage.TASK}) {
            assertTrue(s.name(), WindowFlowPolicy.enterTextOnFocus(s));
        }
        for (Stage s : new Stage[] {Stage.TEXT, Stage.HANDOFF, Stage.ISLAND, Stage.IDLE, null}) {
            assertFalse(String.valueOf(s), WindowFlowPolicy.enterTextOnFocus(s));
        }
    }

    // ---------------------------------------------------------------- 几何：输入条和发送键在键盘上方

    /** 模拟 FloatingSheetView 贴底排版：输入条底边 = 窗口高 − 底部内边距（基线 + 让位）。 */
    static int inputBottomInWindow(int winH, int padBase, int imeInset) {
        return winH - (padBase + imeInset);
    }

    @Test public void portraitInputSitsAboveKeyboard() {
        // Note 8 竖屏：全屏窗 0..2094（导航栏在下），三星键盘上沿 1224（LC-2 真机数据）
        int winTop = 0, winH = 2094, padBase = 101, imeTop = 1224;
        int inset = WindowFlowPolicy.imeInset(winTop + winH, imeTop, 2094, 0);
        assertEquals(870, inset);
        assertTrue(WindowFlowPolicy.inputAboveIme(winTop, inputBottomInWindow(winH, padBase, inset), imeTop));
        assertFalse("不让位就被盖住（复发时的样子）", WindowFlowPolicy.inputAboveIme(winTop, inputBottomInWindow(winH, padBase, 0), imeTop));
    }

    @Test public void landscapeInputSitsAboveKeyboard() {
        // 横屏：窗高 1080（导航栏在侧边），键盘上沿 552
        int winTop = 0, winH = 1080, padBase = 101, imeTop = 552;
        int inset = WindowFlowPolicy.imeInset(winH, imeTop, 1080, 0);
        assertEquals(528, inset);
        assertTrue(WindowFlowPolicy.inputAboveIme(winTop, inputBottomInWindow(winH, padBase, inset), imeTop));
        // 内容区：可用 1080，固定件 = 上内边距 60 + 下内边距(101+528) + 问句卡 150 + 输入条 120 → 只剩 121 px
        int density3 = 3;
        int chrome = 60 + 101 + inset + 150 + 120;
        int cap = WindowFlowPolicy.contentCap(1080, chrome, inset, 96 * density3, 40 * density3);
        assertEquals("键盘弹着：内容区按剩余高度给，不再硬撑 96dp", 121, cap);
        assertTrue("输入条 + 内容区放得下", chrome + cap <= 1080);
        int capOld = Math.max(96 * density3, 1080 - chrome);
        assertTrue("旧下限 96dp 时总高超出窗口 → 输入条被挤到键盘下（横屏不能用）", chrome + capOld > 1080);
    }

    @Test public void landscapeTinyLeftoverStillScrolls() {
        // 极端：剩余为负 → 内容区 40dp、可滚动；没有键盘时仍是 96dp
        assertEquals(120, WindowFlowPolicy.contentCap(1080, 1200, 528, 288, 120));
        assertEquals(288, WindowFlowPolicy.contentCap(1080, 1200, 0, 288, 120));
        assertEquals(800, WindowFlowPolicy.contentCap(2094, 1294, 0, 288, 120));
    }

    @Test public void keyboardHiddenRestoresPosition() {
        assertEquals(0, WindowFlowPolicy.imeInset(2094, 2094, 2094, 0));
        // 转屏后基线作废重记（onConfigurationChanged 清 mImeBaseVis）：横屏没有键盘时不能拿竖屏基线误判成「键盘在」
        assertEquals(0, WindowFlowPolicy.imeInset(1080, 1080, 1080, 0));
    }

    // ---------------------------------------------------------------- 接线护栏

    @Test public void wiringGuards() throws Exception {
        String view = read("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue("直接点输入框 → 焦点监听", view.contains("getInputEdit().setOnFocusChangeListener"));
        assertTrue(view.contains("WindowFlowPolicy.enterTextOnFocus(mStage.stage())"));
        assertTrue("旧界面也让位", view.contains("v2TrackIme(true);   // ★ r2/input：旧界面也跟踪输入法让位"));
        assertTrue("转屏重置基线", view.contains("onConfigurationChanged") && view.contains("mImeBaseVis = 0;"));
        assertTrue(view.contains("WindowFlowPolicy.contentCap(avail, chrome, mImeInset"));
        String bar = read("app/src/com/onesay/assistant/v2/ui/widget/PillSearchBar.java");
        assertTrue("长句换行可见", bar.contains("setMaxLines(4)") && bar.contains("setHorizontallyScrolling(false)"));
        assertTrue("横屏不用全屏提取界面", bar.contains("IME_FLAG_NO_EXTRACT_UI"));
        assertFalse("不能是多行输入类型（会把发送键变成回车）", bar.contains("TYPE_TEXT_FLAG_MULTI_LINE"));
        String svc = read("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue(svc.contains("SOFT_INPUT_ADJUST_RESIZE"));
        assertTrue("CRLF 保持", svc.contains("\r\n") && !svc.replace("\r\n", "").contains("\n"));
        assertTrue(read("app/src/com/onesay/assistant/v2/ui/MainActivity.java").contains("SOFT_INPUT_ADJUST_RESIZE"));
        assertTrue(read("app/src/com/onesay/assistant/v2/errand/ErrandBrowserActivity.java").contains("SOFT_INPUT_ADJUST_RESIZE"));
    }

    // ★ integrate 上机：全局沉浸式下 adjustResize 失效，Activity 手动让位
    @Test public void activityImePadUnderImmersive() throws Exception {
        assertEquals("键盘 998px 盖住底部", 998, WindowFlowPolicy.activityImePad(2220, 1222, 2220));
        assertEquals("adjustResize 已生效：内容区底边 = 可见区底边", 0, WindowFlowPolicy.activityImePad(1222, 1222, 2220));
        assertEquals("导航条 / 手势条高度不算键盘", 0, WindowFlowPolicy.activityImePad(2220, 2094, 2220));
        assertEquals("横屏键盘", 600, WindowFlowPolicy.activityImePad(1080, 480, 1080));
        assertEquals(0, WindowFlowPolicy.activityImePad(2220, 2400, 2220));
        assertEquals(0, WindowFlowPolicy.activityImePad(100, 0, 0));
        assertTrue(read("app/src/com/onesay/assistant/v2/ui/MainActivity.java").contains("ImeInsetPadder.attach(this, mConsole.root())"));
        assertTrue(read("app/src/com/onesay/assistant/v2/errand/ErrandBrowserActivity.java").contains("ImeInsetPadder.attach(this, root)"));
    }

    static String read(String rel) throws Exception {
        File dir = com.onesay.assistant.v2.testutil.TestRoot.start(ImeRegressionTest.class);
        for (int i = 0; i < 6 && dir != null; i++) {
            if (new File(dir, "app/AndroidManifest.xml").exists()) break;
            dir = dir.getParentFile();
        }
        return new String(Files.readAllBytes(new File(dir, rel).toPath()), StandardCharsets.UTF_8);
    }
}
