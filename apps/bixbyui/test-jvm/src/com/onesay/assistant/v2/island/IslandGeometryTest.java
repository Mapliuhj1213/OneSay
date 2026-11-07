package com.onesay.assistant.v2.island;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/** [island v3] 几何：Note 8 上状态栏下 2dp / 居中、全屏 8dp、紧凑宽度夹紧、展开宽与高度上限、双活动成组；不再有无障碍。 */
public class IslandGeometryTest {

    static final float D = 2.625f;   // Note 8 默认 FHD+ 420dpi

    @Test public void note8AnchorBelowStatusBarCentred() {
        IslandGeometry.Anchor a = IslandGeometry.anchor(1080, D, 63, false);
        assertEquals(540, a.cx);
        assertEquals("63 + round(2×2.625)", 68, a.top);
        IslandGeometry.Anchor f = IslandGeometry.anchor(1080, D, 63, true);
        assertEquals("全屏：8dp", 21, f.top);
        IslandGeometry.Anchor u = IslandGeometry.anchor(1080, D, 0, false);
        assertEquals("状态栏未知 → 24dp + 2dp", Math.round(24 * D) + Math.round(2 * D), u.top);
        IslandGeometry.Anchor land = IslandGeometry.anchor(2220, D, 63, false);
        assertEquals("横屏：cx = 横屏宽/2", 1110, land.cx);
    }

    @Test public void expandedWidthAndHeightCap() {
        assertEquals(380f, IslandGeometry.expandedWidth(Math.round(411 * D), D) / D, 0.5f);
        assertEquals(336f, IslandGeometry.expandedWidth(Math.round(360 * D), D) / D, 0.5f);
        assertEquals(276f, IslandGeometry.expandedWidth(Math.round(300 * D), D) / D, 0.5f);
        // 846dp 屏、上沿 26dp → 0.40×846 − 26 ≈ 312dp
        float max = IslandGeometry.expandedMaxHeight(Math.round(846 * D), Math.round(26 * D)) / D;
        assertEquals(312f, max, 1f);
    }

    @Test public void compactWidthClampedAndInsideSafeZone() {
        assertEquals("最窄 98dp", 98f, IslandGeometry.compactWidth(10f * D, D) / D, 0.01f);
        assertEquals("封顶 120dp", 120f, IslandGeometry.compactWidth(400f * D, D) / D, 0.01f);
        float w = IslandGeometry.compactWidth(90f * D, D) / D;
        assertTrue(w >= 98f && w <= 120f);
        float left = 411f / 2f - 120f / 2f, right = 411f / 2f + 120f / 2f;
        assertTrue("411dp 宽时紧凑态落在 [100, 311] 内", left >= 100f && right <= 311f);
    }

    @Test public void dualGroupCentredNoOverlap() {
        float[] g = IslandGeometry.dualLayout(540, D);
        float mainR = g[0] + g[1], subL = g[2];
        assertEquals("间距 6dp", 6f * D, subL - mainR, 0.01f);
        assertEquals("组中心 = cx", 540f, (g[0] + g[2] + g[3]) / 2f, 0.01f);
        assertEquals(28f * D, g[1], 0.01f);
        assertEquals(44f * D, g[3], 0.01f);
    }

    @Test public void springSettlesWithoutLargeOvershootAndGateWaits() {
        IslandGeometry.Spring s = new IslandGeometry.Spring(98f, IslandGeometry.SPRING_OMEGA, IslandGeometry.SPRING_ZETA);
        s.t = 380f;
        float max = 0f;
        int frames = 0;
        while (!s.settled(0.3f) && frames < 200) {
            s.step(1f / 60f);
            max = Math.max(max, s.v);
            frames++;
        }
        assertTrue("约 0.5 s 内静止（帧数 " + frames + "）", frames < 45);
        assertTrue("ζ=0.86 只轻微过冲", max < 380f * 1.02f);
        assertFalse(IslandGeometry.contentGateOpen(100f, 380f, 28f, 198f));
        assertTrue(IslandGeometry.contentGateOpen(310f, 380f, 160f, 198f));
        assertTrue("收缩时不拦", IslandGeometry.contentGateOpen(380f, 200f, 198f, 28f));
    }

    static String file(String rel) throws Exception {
        File loc = com.onesay.assistant.v2.testutil.TestRoot.start(IslandGeometryTest.class);
        File dir = loc;
        for (int i = 0; i < 6 && dir != null; i++) {
            File f = new File(dir, rel);
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            dir = dir.getParentFile();
        }
        return new String(Files.readAllBytes(new File(rel).toPath()), StandardCharsets.UTF_8);
    }

    static boolean exists(String rel) throws Exception {
        File loc = com.onesay.assistant.v2.testutil.TestRoot.start(IslandGeometryTest.class);
        File dir = loc;
        for (int i = 0; i < 6 && dir != null; i++) {
            if (new File(dir, "app/AndroidManifest.xml").exists()) return new File(dir, rel).exists();
            dir = dir.getParentFile();
        }
        return new File(rel).exists();
    }

    @Test public void noAccessibilityAnywhereAndOverlayWindow() throws Exception {
        String full = file("app/AndroidManifest.xml");
        // ★ mika-plug（mika.a11y，B2 可选后端）：无障碍服务只许出现在 DEBUG-ONLY 段里；release 构建删掉该段后不得再有
        String m = full.replaceAll("(?s)<!-- DEBUG-ONLY-BEGIN.*?DEBUG-ONLY-END -->", "");
        assertFalse("release Manifest 不再有无障碍服务", m.contains("BIND_ACCESSIBILITY_SERVICE"));
        assertFalse("debug 段的无障碍服务也不能是岛服务", full.contains("IslandAccessibilityService"));
        assertFalse(m.contains("IslandAccessibilityService"));
        assertFalse("xml 配置已删", exists("app/res/xml/island_accessibility.xml"));
        assertFalse("服务类已删", exists("app/src/com/onesay/assistant/v2/island/IslandAccessibilityService.java"));
        int perms = 0, i = -1;
        while ((i = m.indexOf("<uses-permission ", i + 1)) >= 0) perms++;
        // ★ fix/triage-fast：+1 条 ACCESS_NOTIFICATION_POLICY（普通权限，勿扰快路径 setInterruptionFilter 要用户在「勿扰权限」里允许）
        // ★ feat/planner-link：+1 条 com.onesay.plan.permission.ACCESS（计划 App 后门的 signature 权限）
        // ★ 开源版闹钟：+1 条 com.onesay.clock.permission.ALARM_CONTROL（OneSay 时钟闹钟接口的 signature 权限；SET_ALARM 改成正确的 com.android.alarm 名，条数不变）
        assertEquals("uses-permission 不变（50 条，含 fix/triage-fast 的 ACCESS_NOTIFICATION_POLICY、planner-link 的 bixbyplan ACCESS、时钟 ALARM_CONTROL）", 50, perms);
        String ctl = file("app/src/com/onesay/assistant/v2/s1/IslandController.java");
        assertFalse(ctl.contains("TYPE_ACCESSIBILITY_OVERLAY"));
        assertFalse("不用挖孔模式", ctl.contains("LAYOUT_IN_DISPLAY_CUTOUT_MODE"));
        assertFalse("不读 DisplayCutout", ctl.contains("getBoundingRect"));
        assertFalse("岛窗口不用 NO_LIMITS", ctl.substring(ctl.indexOf("private void placeIsland"), ctl.indexOf("private void removeIsland"))
                .contains("FLAG_LAYOUT_NO_LIMITS"));
        assertTrue(ctl.contains("FLAG_WATCH_OUTSIDE_TOUCH"));
        assertTrue(ctl.contains("S1Overlay.windowType()"));
        // app/src 里没有 AccessibilityService 子类
        assertFalse(file("app/src/com/onesay/assistant/v2/island/IslandView.java").contains("AccessibilityService"));
    }
}
