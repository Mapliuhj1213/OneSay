package com.onesay.assistant.v2.ui.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ★ r2/kb-fix：键盘高度 / 偏移计算（数值取自 Note 8 真机：竖屏 1080x2220，导航栏上沿 2094，讯飞键盘上沿 1222）。 */
public class ImeGeometryTest {

    static final int SH = 2220;

    @Test public void visibleFrameGivesKeyboardTop() {
        assertEquals(1222, ImeGeometry.topFromVisible(2094, 1222, SH));
        // 锁屏托底全屏（无导航栏，基线 = 屏底），键盘弹出时导航栏回来：上沿仍是 1222
        assertEquals(1222, ImeGeometry.topFromVisible(2220, 1222, SH));
    }

    @Test public void smallJitterIsNotAKeyboard() {
        // 导航栏 126px 出现 / 消失不是键盘
        assertEquals(-1, ImeGeometry.topFromVisible(2220, 2094, SH));
        assertEquals(-1, ImeGeometry.topFromVisible(2094, 2094, SH));
        assertEquals(-1, ImeGeometry.topFromVisible(0, 1222, SH));
    }

    @Test public void absurdReadingRejected() {
        // 可见区塌到 100px：读数异常
        assertEquals(-1, ImeGeometry.topFromVisible(2094, 100, SH));
    }

    @Test public void heightFromReflection() {
        assertEquals(1222, ImeGeometry.topFromHeight(2094, 872, SH));
        assertEquals(-1, ImeGeometry.topFromHeight(2094, 0, SH));
        assertEquals(-1, ImeGeometry.topFromHeight(2094, 40, SH));     // 候选栏抖动量级
    }

    @Test public void combineTakesHighestTrustedTop() {
        assertEquals(1100, ImeGeometry.combineTop(1222, -1, 1100));   // 表情面板更高：取更高的
        assertEquals(1222, ImeGeometry.combineTop(-1, 1222, -1));
        assertEquals(-1, ImeGeometry.combineTop(-1, -1, -1));         // 全部读不到 = 没弹
        assertEquals(-1, ImeGeometry.combineTop());
    }

    @Test public void keyboardHeight() {
        assertEquals(872, ImeGeometry.keyboardHeight(2094, 1222));
        assertEquals(0, ImeGeometry.keyboardHeight(2094, -1));
        assertEquals(0, ImeGeometry.keyboardHeight(2094, 2094));
    }

    @Test public void liftPutsWindowBottomOnKeyboardTop() {
        // 全屏卡片窗底 2094，键盘上沿 1222 → 抬 872（与 v7 真机日志一致）
        assertEquals(872, ImeGeometry.liftFor(2094, 1222, 0, 2094 * 3 / 4));
        // 留 12px 缝
        assertEquals(884, ImeGeometry.liftFor(2094, 1222, 12, 0));
        // 锁屏托底：窗底 2220 → 抬 998
        assertEquals(998, ImeGeometry.liftFor(2220, 1222, 0, 0));
    }

    @Test public void liftResetsWhenKeyboardHidden() {
        assertEquals(0, ImeGeometry.liftFor(2094, -1, 0, 0));       // 收起 → 复位，不留空白
        assertEquals(0, ImeGeometry.liftFor(1100, 1222, 0, 0));     // 窗口已在键盘上方（ADJUST_RESIZE 真生效时）
        assertEquals(0, ImeGeometry.liftFor(0, 1222, 0, 0));        // 窗口还没量好
    }

    @Test public void liftIsCapped() {
        assertEquals(500, ImeGeometry.liftFor(2094, 300, 0, 500));
    }

    @Test public void keyboardHeightChangesFollow() {
        // 中文 872 → 切英文无候选 760 → 表情面板 990：偏移都跟着变
        int a = ImeGeometry.liftFor(2094, 2094 - 872, 0, 0);
        int b = ImeGeometry.liftFor(2094, 2094 - 760, 0, 0);
        int c = ImeGeometry.liftFor(2094, 2094 - 990, 0, 0);
        assertEquals(872, a);
        assertEquals(760, b);
        assertEquals(990, c);
        assertTrue(ImeGeometry.changed(a, b));
        assertTrue(ImeGeometry.changed(b, c));
    }

    @Test public void changedIgnoresTinyJitterButNotShowHide() {
        assertFalse(ImeGeometry.changed(872, 872));
        assertFalse(ImeGeometry.changed(872, 874));
        assertTrue(ImeGeometry.changed(872, 880));
        assertTrue(ImeGeometry.changed(0, 1));
        assertTrue(ImeGeometry.changed(2, 0));
    }

    @Test public void rotationLandscape() {
        // 横屏 2220x1080：导航栏在右侧，可见区底 1080，键盘上沿 600
        int lh = 1080;
        assertEquals(600, ImeGeometry.topFromVisible(1080, 600, lh));
        assertEquals(480, ImeGeometry.liftFor(1080, 600, 0, 0));
    }

    @Test public void baseOnlyGrows() {
        assertEquals(2094, ImeGeometry.updateBase(0, 2094, SH));
        assertEquals(2220, ImeGeometry.updateBase(2094, 2220, SH));   // 导航栏消失
        assertEquals(2094, ImeGeometry.updateBase(2094, 1222, SH));   // 键盘弹着不拉低基线
        assertEquals(2094, ImeGeometry.updateBase(2094, 2080, SH));   // 小抖动
        assertEquals(2094, ImeGeometry.updateBase(2094, 0, SH));
    }

    @Test public void landscapeTightKeepsInputVisible() {
        // 真机 v2：横屏 1080 高，三星键盘让位 603；固定件含图标行 147 后共 1100 → 放不下
        int[] t = ImeGeometry.tightLayout(1080, 1100, 147, 603, 105);
        assertEquals(1, t[0]);                       // 收图标行
        assertTrue(t[1] >= 0 && 1100 - 147 + t[1] <= 1080);
        // 图标行已收还是放不下 → 内容区 0
        int[] u = ImeGeometry.tightLayout(1080, 1100, 0, 603, 105);
        assertEquals(0, u[0]);
        assertEquals(0, u[1]);
        // 竖屏放得下 / 键盘没弹：不动
        assertEquals(0, ImeGeometry.tightLayout(2094, 1500, 147, 872, 105)[0]);
        assertEquals(594, ImeGeometry.tightLayout(2094, 1500, 147, 872, 105)[1]);
        assertEquals(0, ImeGeometry.tightLayout(1080, 1100, 147, 0, 105)[0]);
    }

    @Test public void lockPolicy() {
        assertTrue(KbLockPolicy.shouldExtend(true, 60000));
        assertFalse(KbLockPolicy.shouldExtend(false, 60000));
        assertFalse(KbLockPolicy.shouldExtend(true, KbLockPolicy.HOLD_CAP_MS));
        assertTrue(KbLockPolicy.collapseOnHostGone(true, true));
        assertFalse(KbLockPolicy.collapseOnHostGone(false, true));    // 已解锁：面板照常可见
        assertFalse(KbLockPolicy.collapseOnHostGone(true, false));
    }
}
