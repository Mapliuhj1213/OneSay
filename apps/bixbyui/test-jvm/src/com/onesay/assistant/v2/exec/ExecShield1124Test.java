package com.onesay.assistant.v2.exec;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.a11y.A11yState;
import com.onesay.assistant.v2.a11y.A11yState.Route;
import com.onesay.assistant.v2.exec.ShieldMode.Mode;
import com.onesay.assistant.v2.fastscreen.FsProtocol;
import com.onesay.assistant.v2.fastscreen.FsTree;
import com.onesay.assistant.v2.fastscreen.NavKeys;
import com.onesay.assistant.v2.phone.ObserveFallback;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/**
 * [exec-1124 B] 护罩不再挡读屏：模式选择（无障碍在 → 无障碍浮层；只 Shizuku → 只留光晕；root / 安卓 8 → 原样）、
 * 手势导航下的触摸观察区、读屏「看不见」节点计数与偏少日志判定、接线守卫。
 */
public class ExecShield1124Test {

    @Test
    public void modeTable() {
        assertEquals("root 保持原样", Mode.LEGACY, ShieldMode.choose(Route.HELPER_ROOT, 36, true));
        assertEquals(Mode.LEGACY, ShieldMode.choose(Route.HELPER_ROOT, 26, false));
        assertEquals("安卓 8 保持原样", Mode.LEGACY, ShieldMode.choose(Route.HELPER, 26, false));
        assertEquals(Mode.LEGACY, ShieldMode.choose(Route.A11Y_FIRST, 27, true));
        assertEquals(Mode.LEGACY, ShieldMode.choose(Route.A11Y_ONLY, 27, true));
        assertEquals("两者都开 → 无障碍浮层", Mode.A11Y_OVERLAY, ShieldMode.choose(Route.A11Y_FIRST, 36, true));
        assertEquals("只无障碍 → 无障碍浮层", Mode.A11Y_OVERLAY, ShieldMode.choose(Route.A11Y_ONLY, 28, true));
        assertEquals("只 Shizuku → 只留光晕", Mode.GLOW_ONLY, ShieldMode.choose(Route.HELPER, 36, false));
        assertEquals("HELPER 路线（无障碍被关）即使实例在也只留光晕", Mode.GLOW_ONLY, ShieldMode.choose(Route.HELPER, 36, true));
        assertEquals("无障碍这会儿没连上 → 只留光晕，不退回会挡读屏的护罩", Mode.GLOW_ONLY, ShieldMode.choose(Route.A11Y_FIRST, 34, false));
        assertEquals(Mode.LEGACY, ShieldMode.choose(Route.NONE, 36, true));
        assertEquals(Mode.LEGACY, ShieldMode.choose(null, 36, true));
    }

    @Test
    public void modeFromRealRoutes() {
        // 魅族三种组合（安卓 16 = 36），经路线判定串起来
        assertEquals(Mode.GLOW_ONLY, ShieldMode.choose(A11yState.route(true, false, false, false), 36, false));
        assertEquals(Mode.A11Y_OVERLAY, ShieldMode.choose(A11yState.route(false, false, true, false), 36, true));
        assertEquals(Mode.A11Y_OVERLAY, ShieldMode.choose(A11yState.route(true, false, true, false), 36, true));
        // Note 8 root（安卓 8 = 26），无障碍开不开都原样
        assertEquals(Mode.LEGACY, ShieldMode.choose(A11yState.route(true, true, true, false), 26, true));
        assertEquals(Mode.LEGACY, ShieldMode.choose(A11yState.route(true, true, false, false), 26, false));
    }

    @Test
    public void touchAndRecovery() {
        assertTrue(ShieldMode.blocksTouch(Mode.LEGACY));
        assertTrue(ShieldMode.blocksTouch(Mode.A11Y_OVERLAY));
        assertFalse(ShieldMode.blocksTouch(Mode.GLOW_ONLY));
        assertEquals(Mode.A11Y_OVERLAY, ShieldMode.afterA11yLost(true));
        assertEquals("服务没回来：只留光晕", Mode.GLOW_ONLY, ShieldMode.afterA11yLost(false));
        assertEquals(2032, ShieldMode.TYPE_ACCESSIBILITY_OVERLAY);
        assertEquals(android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, ShieldMode.TYPE_ACCESSIBILITY_OVERLAY);
        for (Mode m : Mode.values()) assertFalse(ShieldMode.zh(m).isEmpty());
    }

    // ------------------------------------------------------------------ 手势导航观察区

    @Test
    public void gestureZones() {
        int[] z = NavKeys.gestureZones(1080, 2400, 60, 80);
        assertEquals(12, z.length);
        assertArrayEquals(new int[]{0, 0, 60, 2320, 0, 2320, 1080, 2400, 1020, 0, 1080, 2320}, z);
        assertEquals(12, NavKeys.gestureZones(0, 2400, 60, 80).length);
        assertEquals(0, NavKeys.gestureZones(0, 2400, 60, 80)[2]);
        int[] g = NavKeys.watchZones(true, 1080, 2400, 130, 0, 2.5f);
        assertEquals(12, g.length);
        assertEquals("24dp", 60, g[2]);
        assertEquals("32dp", 2400 - 80, g[5]);
        assertArrayEquals("三键不变", NavKeys.compute(1080, 2400, 130, 0), NavKeys.watchZones(false, 1080, 2400, 130, 0, 2.5f));
        assertEquals(8, NavKeys.watchZones(false, 1080, 2400, 130, 0, 2.5f).length);
    }

    /** 观察区和护罩上的 EdgeGesture 是同一套边缘带。 */
    @Test
    public void zonesMatchEdgeGesture() {
        int w = 1080, h = 2400, e = 60, b = 80;
        int[] z = NavKeys.gestureZones(w, h, e, b);
        EdgeGesture eg = new EdgeGesture(w, h, e, b, 100);
        int[][] pts = {{0, 1200}, {59, 1200}, {61, 1200}, {540, 1200}, {1079, 1200}, {1021, 600}, {540, 2399}, {5, 2390}, {540, 2319}, {540, 2321}};
        for (int[] p : pts) {
            int zone = eg.down(p[0], p[1]);
            eg.up();
            int mine = in(z, 4, p[0], p[1]) ? 3 : in(z, 0, p[0], p[1]) ? 1 : in(z, 8, p[0], p[1]) ? 2 : 0;
            assertEquals(p[0] + "," + p[1], zone, mine);
        }
    }

    static boolean in(int[] r, int o, int x, int y) {
        return x >= r[o] && x < r[o + 2] && y >= r[o + 1] && y < r[o + 3];
    }

    @Test
    public void protocolAcceptsGestureZones() {
        assertNull("三键 8 个数照旧", FsProtocol.validate(new String[]{"backwatch", "0", "0", "1", "1", "2", "2", "3", "3"}));
        assertNull("手势导航 12 个数", FsProtocol.validate(new String[]{"backwatch", "0", "0", "60", "2320", "0", "2320", "1080", "2400", "1020", "0", "1080", "2320"}));
        assertEquals("ARGC", FsProtocol.validate(new String[]{"backwatch", "0", "0", "1", "1", "2", "2", "3", "3", "4"}));
        assertNotNull(FsProtocol.validate(new String[]{"backwatch", "0", "0", "1", "1", "2", "2", "3", "3", "4", "4", "5", "99999"}));
    }

    @Test
    public void toasts() {
        String t = ExecState.navTakeoverToast(EdgeGesture.BACK, true);
        assertFalse("只留光晕时手势已经生效，不能再说再划一次", t.contains("再划一次") || t.contains("再上划"));
        assertTrue(t, t.contains("停下") && t.contains("继续"));
        assertEquals(ExecState.navTakeoverToast(EdgeGesture.BACK), ExecState.navTakeoverToast(EdgeGesture.BACK, false));
        for (String bad : new String[]{"啥", "咋", "呗", "Bixby", "三星", "小布", "DeX"}) assertFalse(t.contains(bad));
        assertEquals(EdgeGesture.BACK, ExecState.navWord(true, true));
        assertEquals(EdgeGesture.HOME, ExecState.navWord(true, false));
        assertEquals("按了返回键", ExecState.navWord(false, true));
        assertEquals("按了主页键", ExecState.navWord(false, false));
    }

    // ------------------------------------------------------------------ 读屏兜底日志

    @Test
    public void hiddenCountRoundTrip() throws Exception {
        FsTree t = new FsTree();
        t.pkg = "com.android.settings";
        FsTree.Node n = new FsTree.Node();
        n.cls = "android.widget.TextView";
        t.nodes.add(n);
        String j0 = t.toJson();
        assertFalse("0 不写，旧格式不变", j0.contains("hid"));
        t.hidden = 37;
        FsTree back = FsTree.fromJson(t.toJson());
        assertEquals(37, back.hidden);
        assertEquals(0, FsTree.fromJson(j0).hidden);
    }

    @Test
    public void thinInExec() {
        assertFalse("不在执行模式不记", ObserveFallback.thinInExec(false, 2, 100));
        assertTrue(ObserveFallback.thinInExec(true, 5, 0));
        assertTrue("真机那种：25 个元素、跳过一大片", ObserveFallback.thinInExec(true, 25, 120));
        assertFalse(ObserveFallback.thinInExec(true, 25, 30));
        assertFalse(ObserveFallback.thinInExec(true, 40, 9));
        assertFalse(ObserveFallback.thinInExec(true, 8, 0));
    }

    // ------------------------------------------------------------------ 接线守卫

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/" + rel).toPath()), "UTF-8");
    }

    @Test
    public void wiring() throws Exception {
        String sh = src("exec/ShieldOverlay.java");
        assertTrue("无障碍浮层类型", sh.contains("attach(w, ShieldMode.TYPE_ACCESSIBILITY_OVERLAY, thin)"));
        assertTrue("只留光晕整窗不接触摸", sh.contains("(thin || glow ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0)"));
        assertTrue("只留光晕不开孔", sh.contains("if (!mAttached || mThin || !ShieldMode.blocksTouch(mMode)) return;"));
        assertTrue(sh.contains("if (on && !ShieldMode.blocksTouch(mMode)) return;"));
        assertTrue("active() 只留光晕时返回 null", sh.contains("!s.mThin && ShieldMode.blocksTouch(s.mMode) ? s : null"));
        assertTrue("挂不上无障碍浮层退成只留光晕", sh.contains("mMode = ShieldMode.Mode.GLOW_ONLY;"));
        assertTrue("服务重绑后挂回", sh.contains("checkA11yHost()") && sh.contains("remount("));
        assertTrue("原护罩的开孔 / 手势边缘逻辑还在", sh.contains("mHoles.open(") && sh.contains("eg.move(") && sh.contains("FLAG_NOT_FOCUSABLE"));
        String c = src("exec/ExecModeController.java");
        int set = c.indexOf("mShield.setMode(sm,"), show = c.indexOf("mShield.show(false);");
        assertTrue("先定模式再挂", set > 0 && show > set);
        assertTrue(c.contains("ShieldMode.choose(driver.route(), android.os.Build.VERSION.SDK_INT"));
        assertTrue(c.contains("navTakeover(ExecState.navWord(mGestureNav, b > 0), \"触摸观察\")"));
        String hm = src("fastscreen/HelperMain.java");
        assertTrue("手势导航不再报 NO_BACK_BUTTON 退出", hm.contains("if (sBack2R == null) try {") && hm.contains("sBack2R != null && sBack2R.contains(sx, sy)"));
        assertTrue("跳过看不见的规则不变，只记数", hm.contains("if (parent >= 0 && !n.isVisibleToUser()) {\n            t.hidden++;"));
        String ab = src("a11y/A11yBackend.java");
        assertTrue(ab.contains("if (parent >= 0 && !n.isVisibleToUser()) {\n            t.hidden++;"));
        assertTrue(src("fastscreen/FastScreen.java").contains("NavKeys.watchZones(mApp)"));
        assertTrue(src("phone/FastScreenDriver.java").contains("ObserveFallback.thinInExec(shNow != null && shNow.attached(), u.els.size(), t.hidden)"));
    }
}
