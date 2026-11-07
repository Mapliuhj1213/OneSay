package com.onesay.assistant.v2.ui.console;

import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 2026-11-05 控制台整理：首页「计划 · 设置 · 模型」三区；计划区跳计划 App 对应页签，没装 / 开关关回落本机计划页；「高级」改名「模型与密钥」。 */
public class ConsolePlanSectionTest {

    static final String CON = "app/src/com/onesay/assistant/v2/ui/console/";

    static String src(String rel) {
        return KernelWiring.src(rel);
    }

    // ------------------------------------------------------------------ 结构

    /** 控制台整理第二步：计划区之后是「设置」分组（模型在最前），然后关于、开发者（最下）；不再有单独的「模型」区和「高级」。 */
    @Test public void homeHasPlanThenSettingsGroups() {
        String h = src(CON + "HomePage.java");
        int plan = h.indexOf("k.group(col, \"计划\")"), set = h.indexOf("k.group(col, \"设置\")");
        assertTrue("计划区", plan > 0);
        assertTrue("设置区在计划区之后", set > plan);
        assertTrue("计划区一行「打开计划」", h.indexOf("\"打开计划\"", plan) > plan && h.indexOf("\"打开计划\"", plan) < set);
        assertTrue("计划区一排直达页签", h.indexOf("PlanSection.TABS", plan) > plan && h.indexOf("PlanSection.TABS", plan) < set);
        assertFalse("模型并进设置区，不再单独一区", h.contains("k.group(col, \"模型\")"));
        assertFalse("首页不再有「高级」入口", h.contains("\"高级\""));
        assertTrue("模型行进模型与密钥页", h.indexOf("new AdvancedPage(c)", set) > set);
    }

    @Test public void homeKeepsHeroAndTwoTiles() {
        String h = src(CON + "HomePage.java");
        assertTrue("状态卡", h.contains("col.addView(hero());") && h.contains("ConsoleStatus.pills(s)") && h.contains("ConsoleStatus.banners(s)"));
        assertTrue("记一记卡", h.contains("k.tile(ConsoleIcon.NOTE, \"记一记\""));
        assertTrue("记忆卡", h.contains("k.tile(ConsoleIcon.MEMORY, \"记忆\""));
        int tiles = h.indexOf("tiles.addView(mMemTile.root"), plan = h.indexOf("k.group(col, \"计划\")");
        assertTrue("两张卡在三区之上", tiles > 0 && tiles < plan);
    }

    @Test public void advancedRenamedModelAndKeys() {
        String a = src(CON + "AdvancedPage.java");
        assertTrue(a.contains("super(c, \"模型与密钥\");"));
        assertFalse(a.contains("super(c, \"高级\");"));
        assertTrue("密钥 / 邮箱都还在", a.contains("KeysActivity.class") && a.contains("c.editAsrKey(") && a.contains("MailSettingsActivity.class"));
        String h = src(CON + "HomePage.java");
        assertTrue("插件列表 / 开发者入口搬到首页「开发者」", h.contains("new AdvancedPage.PluginsPage(c)") && h.contains("new DeveloperPage(c)"));
        assertTrue("唤醒卡片深链仍进这一页", src(CON + "Console.java").contains("nav.push(new AdvancedPage(this));"));
        assertTrue("没配 Key 的提示跟着改名", src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java").contains("设置 › 模型与密钥 › API 密钥 › DeepSeek"));
    }

    /** 日程 / 定时任务 / 待办的开关属设置：仍在「功能与权限」（PAGE_FEATURES）里，首页计划区只放入口不放开关。 */
    @Test public void switchesStayInFeatures() {
        String sched = src("app/src/com/onesay/assistant/v2/sched/SchedConsole.java");
        int f = sched.indexOf("ConsoleHost.PAGE_FEATURES.equals(page)");
        assertTrue(f > 0 && sched.indexOf("host.switchLinkRow(g, \"日程\"", f) > f);
        String s3 = src("app/src/com/onesay/assistant/v2/s3/S3Hooks.java");
        int f3 = s3.indexOf("ConsoleHost.PAGE_FEATURES.equals(page)");
        assertTrue(f3 > 0 && s3.indexOf("switchLinkRow(root, \"定时任务\"", f3) > f3 && s3.indexOf("switchLinkRow(root, \"待办\"", f3) > f3);
        assertTrue(src(CON + "FeaturesPage.java").contains("addConsolePage(c, ConsoleHost.PAGE_FEATURES, feats, null, ConsoleGroups.FEATURES_EXCLUDE)"));
        for (String id : new String[]{"sched", "s3", "planner"}) assertEquals(id + " 开关在「功能」", ConsoleGroups.FEATURES, ConsoleGroups.groupOfFeaturePlugin(id));
        String h = src(CON + "HomePage.java");
        int plan = h.indexOf("k.group(col, \"计划\")"), set = h.indexOf("k.group(col, \"设置\")");
        assertFalse("计划区没有开关", h.substring(plan, set).contains("switchRow") || h.substring(plan, set).contains("switchLinkRow"));
    }

    // ------------------------------------------------------------------ 直达页签 = 计划 App 页签

    @Test public void tabsMatchPlannerApp() {
        List<String> ids = new ArrayList<String>(), labels = new ArrayList<String>();
        for (String[] t : PlanSection.TABS) {
            labels.add(t[0]);
            ids.add(t[1]);
        }
        assertEquals(Arrays.asList("今天", "日程", "待办", "票", "取件码", "自动任务"), labels);
        assertEquals(Arrays.asList("today", "events", "todos", "tickets", "pickups", "automations"), ids);
        String link = src("app/src/com/onesay/assistant/v2/planner/PlannerLink.java");
        assertTrue("与 PlannerLink.open 文档的页签一致", link.contains("today/events/todos/tickets/pickups/automations"));
        assertTrue("首页走 manageInPlanner（开关 + 能打开）", src(CON + "HomePage.java").contains("PlannerLink.manageInPlanner(c.act, t)"));
    }

    // ------------------------------------------------------------------ 跳转与回落

    static final class Rec implements PlanSection.Opener, PlanSection.Local {
        final boolean can;
        final List<String> opened = new ArrayList<String>(), local = new ArrayList<String>();
        boolean boom;

        Rec(boolean can) {
            this.can = can;
        }

        @Override public boolean open(String tab) {
            opened.add(String.valueOf(tab));
            if (boom) throw new IllegalStateException("x");
            return can;
        }

        @Override public void show(String tab) {
            local.add(String.valueOf(tab));
        }
    }

    @Test public void installedJumpsToPlannerTab() {
        for (String[] t : PlanSection.TABS) {
            Rec r = new Rec(true);
            assertEquals(PlanSection.WENT_PLANNER, PlanSection.go(r, r, t[1]));
            assertEquals(Arrays.asList(t[1]), r.opened);
            assertTrue("跳过去了就不开本机页", r.local.isEmpty());
        }
        Rec r = new Rec(true);
        assertEquals(PlanSection.WENT_PLANNER, PlanSection.go(r, r, null));
        assertEquals("「打开计划」不带页签", Arrays.asList("null"), r.opened);
    }

    @Test public void notInstalledFallsBackLocal() {
        Rec r = new Rec(false);
        assertEquals(PlanSection.WENT_LOCAL, PlanSection.go(r, r, "todos"));
        assertEquals(Arrays.asList("todos"), r.local);
        r = new Rec(false);
        assertEquals(PlanSection.WENT_LOCAL, PlanSection.go(r, r, null));
        assertEquals(Arrays.asList("null"), r.local);
        r = new Rec(true);
        r.boom = true;
        assertEquals("拉起出异常也回落", PlanSection.WENT_LOCAL, PlanSection.go(r, r, "events"));
        assertEquals(Arrays.asList("events"), r.local);
        assertEquals(PlanSection.WENT_LOCAL, PlanSection.go(null, r, "pickups"));
    }

    @Test public void localTargets() {
        assertEquals("sched.list", PlanSection.localPage("events"));
        assertEquals("s3.todo", PlanSection.localPage("todos"));
        assertEquals("s3.tasks", PlanSection.localPage("automations"));
        assertEquals(PlanSection.LOCAL_QUICKNOTE, PlanSection.localPage("tickets"));
        assertEquals(PlanSection.LOCAL_QUICKNOTE, PlanSection.localPage("pickups"));
        assertNull("今天 → 本机计划总页", PlanSection.localPage("today"));
        assertNull("打开计划 → 本机计划总页", PlanSection.localPage(null));
        assertEquals("sched.list 与日程本机页同一个 id", "sched.list", com.onesay.assistant.v2.sched.SchedNotices.PAGE_LIST);
        String lp = src(CON + "LocalPlanPage.java");
        assertTrue(lp.contains("QuickNotePage.open(c.act, -1)") && lp.contains("c.openPage(pid") && lp.contains("c.push(new LocalPlanPage(c))"));
        for (String t : new String[]{"\"events\"", "\"todos\"", "\"automations\"", "\"tickets\""}) assertTrue("本机计划页有 " + t, lp.contains(t));
    }

    @Test public void planRowSubtitle() {
        assertTrue(PlanSection.sub(false, true).contains("没装"));
        assertTrue(PlanSection.sub(true, false).contains("开关关着"));
        assertTrue(PlanSection.sub(true, true).contains("OneSay Plan"));
        assertEquals("取件码", PlanSection.label("pickups"));
        assertEquals("计划", PlanSection.label("nope"));
    }
}
