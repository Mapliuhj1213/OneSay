package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.fastscreen.FsTree;

import org.junit.Test;

/** ★ fast-screen-v2：精简控件树（编号稳定、名字合并、质量、差异、签名）。 */
public class UiTreeTest {

    static FakeScreens.B settings() {
        return FakeScreens.screen("com.android.settings", "com.android.settings.Settings")
                .row("连接", "WLAN、蓝牙、流量使用")
                .row("密码与安全", "锁屏、指纹")
                .toggle("飞行模式", false)
                .edit("搜索设置", "search_src_text")
                .text("纯说明文字");
    }

    @Test public void keepsNamedAndInteractiveNodesAndMergesRowLabels() {
        UiTree u = settings().ui(new UiTree.Ids());
        assertEquals("com.android.settings", u.pkg);
        // 根 FrameLayout 没字不可点 → 不留；两行条目各一个元素（子 TextView 已并进名字，不重复列）
        assertEquals(5, u.els.size());
        assertEquals("连接 · WLAN、蓝牙、流量使用", u.els.get(0).label);
        assertTrue(u.els.get(0).click);
        assertEquals("密码与安全 · 锁屏、指纹", u.els.get(1).label);
        assertTrue(u.els.get(2).check);
        assertTrue(u.els.get(3).edit);
        assertEquals("search_src_text", u.els.get(3).rid);
        assertEquals("纯说明文字", u.els.get(4).label);
    }

    @Test public void idsStableAcrossScreensWithinTask() {
        UiTree.Ids ids = new UiTree.Ids();
        UiTree a = settings().ui(ids);
        int wifiId = a.findText("连接").id;
        // 新屏：前面多了一个元素，原有元素编号不变
        UiTree b = FakeScreens.screen("com.android.settings", "x").button("新横幅")
                .row("连接", "WLAN、蓝牙、流量使用").row("密码与安全", "锁屏、指纹").ui(ids);
        assertEquals(wifiId, b.findText("连接").id);
        assertNotEquals(wifiId, b.findText("新横幅").id);
    }

    @Test public void duplicateLabelsGetDistinctIds() {
        UiTree u = FakeScreens.screen("p.k", "a").button("确定").button("确定").ui(new UiTree.Ids());
        assertEquals(2, u.els.size());
        assertNotEquals(u.els.get(0).id, u.els.get(1).id);
    }

    @Test public void renderShowsKindStateCoordsAndTruncates() {
        UiTree u = settings().ui(new UiTree.Ids());
        String r = u.render(3);
        assertTrue(r, r.startsWith("前台：com.android.settings / Settings"));
        assertTrue(r, r.contains("开关「飞行模式」 已关"));
        assertTrue(r, r.contains("@540,"));
        assertTrue(r, r.contains("还有 2 个元素"));
        String line = UiTree.line(u.els.get(3));
        assertTrue(line, line.contains("输入框") && line.contains("#search_src_text"));
    }

    @Test public void offscreenAndZeroSizeNodesDropped() {
        FakeScreens.B b = FakeScreens.screen("p.k", "a").button("看得见");
        FsTree.Node off = new FsTree.Node();
        off.parent = 0;
        off.text = "屏外";
        off.l = 0;
        off.t = 3000;
        off.r = 100;
        off.b = 3100;
        off.flags = FakeScreens.C;
        b.t.nodes.add(off);
        FsTree.Node zero = new FsTree.Node();
        zero.parent = 0;
        zero.text = "零大小";
        zero.flags = FakeScreens.C;
        b.t.nodes.add(zero);
        UiTree u = b.ui(null);
        assertEquals(1, u.els.size());
        assertFalse(u.hasText("屏外"));
    }

    @Test public void qualityFlagsUnnamedControlsAndBlindCanvas() {
        assertEquals(UiTree.Q_UNUSABLE, new UiTree().quality());
        assertEquals(UiTree.Q_OK, settings().ui(null).quality());
        FakeScreens.B unnamed = FakeScreens.screen("p.k", "a").text("标题");
        for (int i = 0; i < 5; i++) unnamed.node("android.widget.ImageView", "", "", FakeScreens.C);
        assertEquals(UiTree.Q_PARTIAL, unnamed.ui(null).quality());
        // 大块无字区域（地图）
        FakeScreens.B map = FakeScreens.screen("com.baidu.BaiduMap", "a").button("搜索");
        FsTree.Node canvas = new FsTree.Node();
        canvas.parent = 0;
        canvas.cls = "android.view.SurfaceView";
        canvas.l = 0;
        canvas.t = 300;
        canvas.r = 1080;
        canvas.b = 2000;
        canvas.flags = FsTree.F_ENABLED;
        map.t.nodes.add(canvas);
        UiTree m = map.ui(null);
        assertTrue(m.blindArea > 0.35f);
        assertEquals(UiTree.Q_PARTIAL, m.quality());
        assertTrue(m.render(10).contains("控件树不全"));
    }

    @Test public void diffReportsAppChangeImeAndAddedGone() {
        UiTree.Ids ids = new UiTree.Ids();
        UiTree a = FakeScreens.screen("p.a", "x.Main").button("首页").button("搜索").ui(ids);
        UiTree b = FakeScreens.screen("p.a", "x.Search").button("首页").button("结果一").ui(ids);
        String d = UiTree.diff(a, b, 5);
        assertTrue(d, d.contains("换到页面 Search"));
        assertTrue(d, d.contains("新出现：结果一"));
        assertTrue(d, d.contains("消失：搜索"));
        assertEquals("界面没有变化", UiTree.diff(a, a, 5));
        UiTree c = FakeScreens.screen("p.b", "y").button("x").ui(ids);
        assertTrue(UiTree.diff(a, c, 5).contains("前台从 p.a 变成 p.b"));
        assertEquals("（动作后读不到界面）", UiTree.diff(a, null, 5));
    }

    @Test public void hasNewTextIgnoresLabelsAlreadyPresent() {
        UiTree.Ids ids = new UiTree.Ids();
        UiTree home = FakeScreens.screen("p", "a").row("连接", "Wi-Fi， 蓝牙").ui(ids);
        UiTree conn = FakeScreens.screen("p", "b").toggle("蓝牙", false).row("连接", "Wi-Fi， 蓝牙").ui(ids);
        assertFalse(home.hasNewText("蓝牙", home));
        assertTrue(conn.hasNewText("蓝牙", home));
        assertTrue(conn.hasNewText("蓝牙", null));
    }

    @Test public void signatureTracksToggleAndEditText() {
        UiTree.Ids ids = new UiTree.Ids();
        String off = FakeScreens.screen("p", "a").toggle("WLAN", false).ui(ids).signature();
        String on = FakeScreens.screen("p", "a").toggle("WLAN", true).ui(ids).signature();
        assertNotEquals(off, on);
        assertEquals(off, FakeScreens.screen("p", "a").toggle("WLAN", false).ui(ids).signature());
    }

    @Test public void fromJsonRoundTripKeepsFlags() throws Exception {
        FsTree t = settings().t;
        t.ime = true;
        FsTree back = FsTree.fromJson(t.toJson());
        UiTree u = UiTree.from(back, null, 1080, 2220);
        assertTrue(u.ime);
        assertTrue(u.render(50).contains("输入法已弹出"));
        assertEquals(5, u.els.size());
    }
}
