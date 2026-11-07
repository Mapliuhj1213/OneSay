package com.onesay.assistant.v2.ui.wake;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

import com.onesay.assistant.v2.ui.wake.WakeStage.Stage;
import com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.TouchArea;

/**
 * ★ LC-1：全屏窗吃掉卡片外的第一下点击；★ LC-2：点 ⌨ 后输入框和上一条回答被输入法盖住。
 */
public class WindowTouchAndImeTest {

    // ---------------------------------------------------------------- LC-1

    /** 复现 LC-1：全屏窗在「我在听」/「正在想」时整窗可触摸 → 修复后只有卡片可触摸。 */
    @Test public void compactStagesOnlyCardIsTouchable() {
        assertEquals(TouchArea.CARD, WindowFlowPolicy.touchArea(true, Stage.LISTENING));
        assertEquals(TouchArea.CARD, WindowFlowPolicy.touchArea(true, Stage.THINKING));
        assertEquals(TouchArea.CARD, WindowFlowPolicy.touchArea(true, Stage.TASK));
        assertEquals(TouchArea.CARD, WindowFlowPolicy.touchArea(true, Stage.ERROR));
        assertEquals(TouchArea.CARD, WindowFlowPolicy.touchArea(true, Stage.IDLE));
    }

    /** 回答全屏阶段维持现状：整窗可触摸（点卡片外留白 = 收起）。 */
    @Test public void fullStagesKeepWholeWindow() {
        assertEquals(TouchArea.WINDOW, WindowFlowPolicy.touchArea(true, Stage.ANSWERING));
        assertEquals(TouchArea.WINDOW, WindowFlowPolicy.touchArea(true, Stage.RESULT));
        assertEquals(TouchArea.WINDOW, WindowFlowPolicy.touchArea(true, Stage.CONFIRM));
        assertEquals(TouchArea.WINDOW, WindowFlowPolicy.touchArea(true, Stage.TEXT));
    }

    /** wrap 窗本来就只包住卡片：不设 region。 */
    @Test public void wrapWindowUntouched() {
        for (Stage s : Stage.values()) assertEquals(TouchArea.WINDOW, WindowFlowPolicy.touchArea(false, s));
    }

    /** touchArea 与 cardFull 一一对应（region 切换点 = 卡片拉满 / 回紧凑的那一刻，窗口尺寸本身从不变）。 */
    @Test public void touchAreaMirrorsCardFull() {
        for (Stage s : Stage.values()) {
            assertEquals(s.name(), WindowFlowPolicy.cardFull(s), WindowFlowPolicy.touchArea(true, s) == TouchArea.WINDOW);
        }
    }

    @Test public void cardRectIsCardOnly() {
        // 1080×2094 的全屏窗，卡片左右 42 px 留白、上沿 1700、下留白 42 → 上半屏（「连接」在 y≈193）不在区域里
        int[] r = WindowFlowPolicy.cardTouchRect(42f, 1700.4f, 42f, 1080, 2094);
        assertArrayEquals(new int[] {42, 1700, 1038, 2052}, r);
        assertFalse(r[1] <= 193);
        // 输入法让位：下沿跟着上移
        assertEquals(2094 - 42 - 800, WindowFlowPolicy.cardTouchRect(42f, 900f, 42f + 800, 1080, 2094)[3]);
    }

    @Test public void cardRectDegenerateFallsBackToWindow() {
        assertNull("窗口没量好", WindowFlowPolicy.cardTouchRect(42f, 10f, 42f, 0, 0));
        assertNull("上沿比下沿还低", WindowFlowPolicy.cardTouchRect(42f, 2090f, 42f, 1080, 2094));
        assertNull("留白比宽还大", WindowFlowPolicy.cardTouchRect(600f, 10f, 42f, 1080, 2094));
        int[] neg = WindowFlowPolicy.cardTouchRect(-5f, -20f, -3f, 1080, 2094);
        assertArrayEquals(new int[] {0, 0, 1080, 2094}, neg);
    }

    /** JVM 的 android.jar 没有 @hide 类 → 反射失败要安静地返回 false / null（真机不支持时服务退回 wrap 窗），不能抛。 */
    @Test public void touchRegionCompatDegradesQuietly() {
        assertFalse(TouchRegionCompat.supported());
        assertNull(TouchRegionCompat.install(null, null));
        TouchRegionCompat.uninstall(null, new Object());
    }

    /** 接线守卫：服务挂窗前看 supported()；卡片 view 挂窗时装 region、摘窗时卸；状态切换路径不 updateViewLayout（不改窗口尺寸）。 */
    @Test public void wiringGuards() throws Exception {
        String svc = read("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue(svc.contains("if (full && !com.onesay.assistant.v2.ui.wake.TouchRegionCompat.supported()) full = false;"));
        String view = read("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue(view.contains("TouchRegionCompat.install(this, mTouchSource)"));
        assertTrue(view.contains("TouchRegionCompat.uninstall(this, mTouchToken)"));
        assertFalse("卡片 view 自己不改窗口尺寸", view.contains("updateViewLayout"));
        // ★ r2/input：跟踪条件从「stage == TEXT」放宽为 WindowFlowPolicy.trackIme（结果页直接点输入框也让位）
        assertTrue(view.contains("v2TrackIme(com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.trackIme(st,"));
    }

    // ---------------------------------------------------------------- LC-2

    /** 复现 LC-2（真机数据）：窗口底 2094、输入法上沿 1224，系统没压缩窗口 → 要让出 870 px。 */
    @Test public void imeCoversWindowNeedsInset() {
        assertEquals("可见区缩到输入法上沿", 870, WindowFlowPolicy.imeInset(2094, 1224, 2094, 0));
        assertEquals("只有输入法高度（反射）", 870, WindowFlowPolicy.imeInset(2094, 2094, 2094, 870));
        assertEquals("两个都有时以可见区为准", 870, WindowFlowPolicy.imeInset(2094, 1224, 2094, 700));
    }

    /**
     * 真机日志（预览包 11:04）：卡片窗改可获焦后窗口短暂盖住导航栏（win 2094 → 2220、vis 2220），输入法动画中 imeH=998；
     * 旧算法用旧底边 2094 − 998 → 让位 1124（偏大 126，一闪）。修正后与稳定值同一个输入法上沿 1222。
     */
    @Test public void windowCoveringNavBarDoesNotOvershoot() {
        assertEquals(998, WindowFlowPolicy.imeInset(2220, 2220, 2094, 998));
        assertEquals(2220 - 998, 2094 - 872);                                    // 两个时刻输入法上沿相同
        assertEquals(872, WindowFlowPolicy.imeInset(2094, 1222, 2220, 872));      // 稳定后
        assertEquals(0, WindowFlowPolicy.imeInset(2220, 2220, 2220, 0));          // 收起键盘
        assertEquals(0, WindowFlowPolicy.imeInset(2094, 2094, 2220, 0));
    }

    @Test public void noImeNoInset() {
        assertEquals(0, WindowFlowPolicy.imeInset(2094, 2094, 2094, 0));
        assertEquals("还没记下导航栏上沿", 0, WindowFlowPolicy.imeInset(2094, 2094, 0, 0));
        assertEquals(0, WindowFlowPolicy.imeInset(0, 1224, 2094, 870));
    }

    /** 系统已经把窗口压缩到输入法上方（adjustResize 生效）→ 不能再让一次。 */
    @Test public void alreadyResizedNoDoubleInset() {
        assertEquals(0, WindowFlowPolicy.imeInset(1224, 1224, 2094, 870));
        assertEquals(0, WindowFlowPolicy.imeInset(1224, 2094, 2094, 870));
    }

    @Test public void absurdValuesClamped() {
        int v = WindowFlowPolicy.imeInset(2094, 10, 2094, 0);
        assertTrue(v <= 2094 * 3 / 4);
    }

    private static String read(String rel) throws Exception {
        File f = new File(System.getProperty("bixby.root", "."), rel);
        if (!f.exists()) f = new File(rel);
        if (!f.exists()) f = new File("../" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
