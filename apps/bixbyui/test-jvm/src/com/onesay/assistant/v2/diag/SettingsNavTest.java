package com.onesay.assistant.v2.diag;

import com.onesay.assistant.v2.fastscreen.FsTree;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** 通用设置导航：计划（含没配 key 的兜底）、路径抽取、控件树匹配、每次确认。 */
public class SettingsNavTest {

    static FsTree.Node node(int parent, String text, int flags) {
        FsTree.Node n = new FsTree.Node();
        n.parent = parent;
        n.text = text;
        n.flags = flags;
        return n;
    }

    /** 一个典型设置页：行（可点）→ 标题 + 说明 + 开关。 */
    static FsTree page(boolean checked) {
        FsTree t = new FsTree();
        t.nodes.add(node(-1, "", FsTree.F_SCROLL));                                   // 0 列表
        t.nodes.add(node(0, "", FsTree.F_CLICK));                                     // 1 行：屏幕超时
        t.nodes.add(node(1, "屏幕超时", 0));                                          // 2
        t.nodes.add(node(1, "30 秒", 0));                                             // 3
        t.nodes.add(node(0, "", FsTree.F_CLICK));                                     // 4 行：息屏显示
        t.nodes.add(node(4, "息屏显示", 0));                                          // 5
        t.nodes.add(node(4, "锁屏后显示时间和通知", 0));                               // 6
        t.nodes.add(node(4, "", FsTree.F_CHECKABLE | (checked ? FsTree.F_CHECKED : 0)));   // 7 开关
        return t;
    }

    @Test public void plan_withKey_endsWithWebLookupAndFollow() {
        List<SettingsNav.Step> p = SettingsNav.plan("息屏显示", "android.settings.DISPLAY_SETTINGS", null, true);
        assertEquals(SettingsNav.Step.Kind.OPEN_ACTION, p.get(0).kind);
        assertEquals("android.settings.DISPLAY_SETTINGS", p.get(0).arg);
        assertEquals(SettingsNav.Step.Kind.FIND, p.get(1).kind);
        assertTrue(kinds(p).contains(SettingsNav.Step.Kind.SETTINGS_SEARCH));
        assertTrue(kinds(p).contains(SettingsNav.Step.Kind.WEB_LOOKUP));
        assertTrue(kinds(p).indexOf(SettingsNav.Step.Kind.WEB_LOOKUP) < kinds(p).lastIndexOf(SettingsNav.Step.Kind.FOLLOW_PATH));
        assertFalse(kinds(p).contains(SettingsNav.Step.Kind.REPORT_NO_KEY));
    }

    @Test public void plan_noKey_saysSoPlainly() {
        List<SettingsNav.Step> p = SettingsNav.plan("防误触", null, null, false);
        assertEquals(SettingsNav.ACTION_SETTINGS, p.get(0).arg);
        assertFalse(kinds(p).contains(SettingsNav.Step.Kind.WEB_LOOKUP));
        assertEquals(SettingsNav.Step.Kind.REPORT_NO_KEY, p.get(p.size() - 1).kind);
        assertTrue(SettingsNav.NO_KEY.contains("key"));
    }

    @Test public void plan_knownPathTriedBeforeSearch() {
        List<SettingsNav.Step> p = SettingsNav.plan("防误触", "android.settings.DISPLAY_SETTINGS", Arrays.asList("显示", "防误触"), false);
        int follow = kinds(p).indexOf(SettingsNav.Step.Kind.FOLLOW_PATH);
        assertTrue(follow > 0 && follow < kinds(p).indexOf(SettingsNav.Step.Kind.SETTINGS_SEARCH));
        assertEquals(Arrays.asList("显示", "防误触"), p.get(follow).path);
    }

    static List<SettingsNav.Step.Kind> kinds(List<SettingsNav.Step> p) {
        java.util.ArrayList<SettingsNav.Step.Kind> k = new java.util.ArrayList<SettingsNav.Step.Kind>();
        for (SettingsNav.Step s : p) k.add(s.kind);
        return k;
    }

    @Test public void parsePath_variousSeparators() {
        assertEquals(Arrays.asList("显示", "息屏显示"), SettingsNav.parsePath("打开设置 > 显示 > 息屏显示，即可开启。", "息屏显示"));
        assertEquals(Arrays.asList("锁屏", "Always On Display"), SettingsNav.parsePath("Settings › 锁屏 › Always On Display", "息屏显示"));
        assertEquals(Arrays.asList("显示与亮度", "护眼模式"), SettingsNav.parsePath("路径：设置 → 显示与亮度 → 「护眼模式」", "护眼模式"));
        assertTrue(SettingsNav.parsePath("这款手机支持护眼模式，非常好用", "护眼模式").isEmpty());
        assertTrue(SettingsNav.parsePath(null, "x").isEmpty());
    }

    @Test public void parsePath_prefersLineEndingAtTarget() {
        String txt = "设置 > 关于手机 > 版本号\n进入 设置 > 安全 > 防误触模式";
        assertEquals(Arrays.asList("安全", "防误触模式"), SettingsNav.parsePath(txt, "防误触"));
    }

    @Test public void find_toggleRowReportsState() {
        SettingsNav.Match m = SettingsNav.find(page(true), "息屏显示");
        assertNotNull(m);
        assertEquals(5, m.labelNode);
        assertEquals(4, m.clickNode);
        assertEquals(7, m.toggleNode);
        assertEquals(Boolean.TRUE, m.checked);
        assertEquals("锁屏后显示时间和通知", m.summary);
        assertTrue(m.describe("息屏显示").contains("开着"));
        assertEquals(Boolean.FALSE, SettingsNav.find(page(false), "息屏显示").checked);
    }

    @Test public void find_bySynonym_andValueRow() {
        assertNotNull("英文别名", SettingsNav.find(page(false), "息屏显示"));
        FsTree t = page(false);
        t.nodes.get(5).text = "Always On Display";
        SettingsNav.Match m = SettingsNav.find(t, "息屏显示");
        assertNotNull(m);
        assertEquals("Always On Display", m.matchedName);
        SettingsNav.Match v = SettingsNav.find(page(false), "屏幕超时");
        assertNull("不是开关", v.checked);
        assertEquals(-1, v.toggleNode);
        assertEquals("30 秒", v.summary);
    }

    @Test public void find_missingAndSkipsOwnPanel() {
        assertNull(SettingsNav.find(page(true), "防误触"));
        FsTree t = page(true);
        FsTree.Win w = new FsTree.Win();
        w.id = 7; w.pkg = "com.onesay.assistant";
        t.windows.add(w);
        for (FsTree.Node n : t.nodes) n.win = 7;
        assertNull("本 App 面板里的文字不算", SettingsNav.find(t, "息屏显示", "com.onesay.assistant"));
        assertNotNull(SettingsNav.find(t, "息屏显示", null));
    }

    @Test public void searchBoxAndEditable() {
        FsTree t = new FsTree();
        t.nodes.add(node(-1, "", 0));
        FsTree.Node s = node(0, "", FsTree.F_CLICK);
        s.desc = "搜索设置";
        t.nodes.add(s);
        assertEquals(1, SettingsNav.findSearchBox(t));
        assertEquals(-1, SettingsNav.findEditable(t));
        t.nodes.add(node(0, "", FsTree.F_EDIT));
        assertEquals(2, SettingsNav.findEditable(t));
    }

    @Test public void alwaysConfirm_andQueryHasModel() {
        assertTrue(SettingsNav.needsConfirm("息屏显示"));
        String q = SettingsNav.webQuery("Google", "Pixel 8", 34, "护眼模式");
        assertTrue(q.contains("Pixel 8") && q.contains("Android 14") && q.contains("护眼模式"));
        assertEquals("设置 › 显示 › 护眼模式", SettingsNav.pathText(Arrays.asList("显示", "护眼模式")));
    }

    @Test public void knowledgeHasNoVendorKeys_andUiFindFixesUseStandardActions() {
        for (DiagKnowledge.Key k : DiagKnowledge.KEYS.values()) {
            assertFalse(k.id, k.id.contains("intelligent_sleep") || k.id.contains("aod_mode") || k.id.contains("edge_lighting")
                    || k.id.contains("blue_light_filter") || k.id.contains("screen_off_pocket"));
        }
        int ui = 0;
        for (DiagKnowledge.Fix f : DiagKnowledge.FIXES.values()) {
            if (f.kind != DiagKnowledge.FixKind.UI_FIND) continue;
            ui++;
            assertTrue(f.id, f.capSign.startsWith("android.settings."));
            assertFalse(f.writes());
        }
        assertTrue(ui >= 5);
    }
}
