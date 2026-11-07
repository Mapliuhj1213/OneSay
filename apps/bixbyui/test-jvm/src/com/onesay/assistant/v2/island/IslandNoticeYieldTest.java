package com.onesay.assistant.v2.island;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-10-23 BUG-I2：提醒展开时唤醒面板，提醒立即让位（收回队列 / 紧凑态），面板关闭后恢复展开；
 * 不影响全屏回答（面板里的任务）、岛上任务、确认卡（只产生 RENDER，单测锁定）。
 */
public class IslandNoticeYieldTest {

    IslandModel m;

    @Before public void setUp() {
        m = new IslandModel();
        m.setMode(IslandModel.Mode.ISLAND);
        m.drainTimers();
    }

    static List<IslandModel.NoticeRow> one(String id, boolean high) {
        return IslandNoticeModelTest.rows(IslandNoticeModelTest.row(id, high, "知道了"));
    }

    @Test public void expandedNoticeYieldsImmediatelyAndComesBackAfterPanelCloses() {
        m.setNotices(one("a", false), true, false);
        assertEquals(IslandModel.Kind.EXP_NOTICE, m.view().kind);
        IslandModel.Timer stale = m.drainTimers().get(0);

        List<IslandModel.Effect> e = m.panelShown(true);
        assertEquals("面板一出现就收：只重画", Arrays.asList(IslandModel.Effect.RENDER), e);
        assertEquals("退回紧凑态（回到队列）", IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        assertEquals("提醒还在队列里", 1, m.noticeCount());
        assertTrue(m.noticeYieldPending());
        assertTrue("原来的 6 s 计时作废", m.timerDue(stale.name, stale.seq, null).isEmpty());
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        assertTrue("面板在屏期间不重复处理", m.panelShown(true).isEmpty());

        e = m.panelShown(false);
        assertEquals(Arrays.asList(IslandModel.Effect.RENDER), e);
        assertEquals("面板关了 → 恢复展开", IslandModel.Kind.EXP_NOTICE, m.view().kind);
        assertFalse(m.noticeYieldPending());
        List<IslandModel.Timer> t = m.drainTimers();
        assertEquals("恢复后照常 6 s 自动收", IslandModel.NOTICE_ALERT_MS, t.get(0).delayMs);
        m.timerDue(t.get(0).name, t.get(0).seq, null);
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        assertTrue("没有欠账了：再开关一次面板不会再弹", m.panelShown(true).isEmpty() && m.panelShown(false).isEmpty());
    }

    @Test public void userOpenedNoticeAlsoYields() {
        m.setNotices(one("a", false), false, false);
        m.tapMain();
        assertEquals(IslandModel.Kind.EXP_NOTICE, m.view().kind);
        m.panelShown(true);
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        m.panelShown(false);
        assertEquals(IslandModel.Kind.EXP_NOTICE, m.view().kind);
    }

    @Test public void compactNoticeUntouched() {
        m.setNotices(one("a", false), false, false);
        assertTrue("没展开 → 面板出现时什么都不做", m.panelShown(true).isEmpty());
        assertFalse(m.noticeYieldPending());
        assertTrue(m.panelShown(false).isEmpty());
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
    }

    @Test public void alertArrivingWhilePanelShownExpandsAfterClose() {
        m.panelShown(true);
        List<IslandModel.Effect> e = m.setNotices(one("a", true), true, true);
        IslandNoticeModelTest.noSheetOrTaskEffects(e);
        assertEquals("面板在屏：只排队", IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        assertTrue(m.noticeYieldPending());
        m.panelShown(false);
        assertEquals("面板关了再展开", IslandModel.Kind.EXP_NOTICE, m.view().kind);
    }

    @Test public void taskCollapsedToIslandKeepsMainRoleNoRestore() {
        m.setNotices(one("a", false), true, false);
        m.panelShown(true);
        m.taskStarted(0L, "打车去公司");
        m.requestCollapse(IslandModel.CollapseReason.AUTO);
        m.drainTimers();
        List<IslandModel.Effect> e = m.panelShown(false);
        IslandNoticeModelTest.noSheetOrTaskEffects(e);
        IslandModel.Snapshot s = m.view();
        assertEquals("任务进岛后岛不空闲：提醒不抢，留在角标", IslandModel.Kind.MINIMAL_DUAL, s.kind);
        assertEquals(IslandModel.Act.TASK, s.act);
        assertEquals(IslandModel.Act.NOTICE, s.bubble);
        assertTrue(m.isRunning());
        assertFalse(m.noticeYieldPending());
    }

    @Test public void fullAnswerTaskInPanelUntouched() {
        m.setNotices(one("a", false), true, false);
        m.taskStarted(0L, "介绍一下长城的历史");
        List<IslandModel.Effect> e = m.panelShown(true);
        IslandNoticeModelTest.noSheetOrTaskEffects(e);
        assertEquals("面板里的回答 / 任务不动", IslandModel.Sheet.EXPANDED, m.sheet());
        assertTrue(m.isRunning());
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        e = m.panelShown(false);
        IslandNoticeModelTest.noSheetOrTaskEffects(e);
        assertEquals("任务还在跑（岛不空闲）→ 不展开", IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
    }

    @Test public void confirmCardNotTouched() {
        m.setNotices(one("a", false), false, false);
        m.bgStarted("s3-1", "定时任务", 0L);
        m.bgConfirm("s3-1", "要调亮度吗", null);
        assertEquals(IslandModel.Kind.EXP_CONFIRM, m.view().kind);
        assertTrue("确认卡展开时面板出现：不归提醒管", m.panelShown(true).isEmpty());
        assertEquals(IslandModel.Kind.EXP_CONFIRM, m.view().kind);
        m.panelShown(false);
        assertEquals(IslandModel.Kind.EXP_CONFIRM, m.view().kind);
    }

    @Test public void fullscreenHighYieldsAndReturnsNormalStaysQueued() {
        m.setFullscreen(true);
        m.setNotices(one("h", true), true, true);
        assertEquals(IslandModel.Kind.EXP_NOTICE, m.view().kind);
        m.panelShown(true);
        assertFalse(IslandModel.Kind.EXP_NOTICE == m.view().kind);
        m.panelShown(false);
        assertEquals("全屏下高优先级照样恢复", IslandModel.Kind.EXP_NOTICE, m.view().kind);
    }

    @Test public void clearedQueueDropsPendingRestore() {
        m.setNotices(one("a", false), true, false);
        m.panelShown(true);
        m.setNotices(new java.util.ArrayList<IslandModel.NoticeRow>(), false, false);
        assertFalse(m.noticeYieldPending());
        assertTrue(m.panelShown(false).isEmpty());
        assertEquals(IslandModel.Kind.HIDDEN, m.view().kind);
    }

    // ------------------------------------------------------------------ Android 接线（源码）

    static String src(String rel) throws Exception {
        File f = new File(System.getProperty("bixby.root", "."), rel);
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    @Test public void wiring() throws Exception {
        String fss = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertFalse("FloatingSheetService.java 保持 CRLF", fss.replace("\r\n", "").contains("\n"));
        // BUG-I2b 起：不再在各条挂摘路径上各调一次 reportPanel()，改成卡片窗 addView / removeView 的唯一汇合点（PanelPresenceWiringTest 细查）
        assertFalse("旧的分散通知已删掉", fss.contains("reportPanel()"));
        assertTrue(fss.contains("com.onesay.assistant.v2.s1.S1Hooks.onPanelVisibility(on);"));
        assertTrue("合并成一次主线程回调", fss.contains("mMainHandler.post(r);") && fss.contains("new PanelPresence("));
        String hooks = src("app/src/com/onesay/assistant/v2/s1/S1Hooks.java");
        assertTrue(hooks.contains("sIsland.panelVisibility(on)"));
        String ctl = src("app/src/com/onesay/assistant/v2/s1/IslandController.java");
        assertTrue(ctl.contains("List<IslandModel.Effect> e = mModel.panelShown(on);") && ctl.contains("apply(e, \"panel(\" + on + \")\");"));
        assertTrue("没效果也记一行 panel(on)（上机能 grep 到）", ctl.contains("\"[ISLAND] panel(\" + on + \") → (无变化)"));
        assertTrue("投递提醒时的取样也走让位 / 恢复", ctl.contains("apply(mModel.panelShown(panel),"));
        assertFalse(ctl.contains("mModel.setPanelShowing(panel);"));
    }

    // ------------------------------------------------------------------ BUG-I2b：面板关闭时岛还在「全屏」修饰里（LC-5 全屏回答）

    /** 复现上机现象：提醒展开 → 唤醒面板（让位）→ 全屏回答把岛判成全屏 → HIDE_SHEET 关面板（此时还是全屏）→ 稍后退出全屏。 */
    @Test public void lc5FullscreenAnswerThenCloseRestoresAfterFullscreenExit() {
        m.setNotices(one("a", false), true, false);
        m.drainTimers();
        m.panelShown(true);
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        m.setFullscreen(true);                        // fullscreen(true) → HIDDEN（全屏回答卡）
        assertTrue(m.noticeYieldPending());

        List<IslandModel.Effect> e = m.panelShown(false);   // 面板摘掉时「退出全屏」还没到
        assertTrue("还在全屏：先不展开", e.isEmpty());
        assertTrue("欠账没丢（修复前这里被清掉了）", m.noticeYieldPending());
        assertTrue(m.noticeYieldWaitingFullscreen());
        List<IslandModel.Timer> t = m.drainTimers();
        assertEquals("noticeYieldFs", t.get(0).name);
        assertEquals(IslandModel.NOTICE_YIELD_FS_GRACE_MS, t.get(0).delayMs);

        e = m.setFullscreen(false);                   // fullscreen(false)
        IslandNoticeModelTest.noSheetOrTaskEffects(e);
        assertEquals("退出全屏 → 补展开", IslandModel.Kind.EXP_NOTICE, m.view().kind);
        assertFalse(m.noticeYieldPending());
        assertFalse(m.noticeYieldWaitingFullscreen());
        List<IslandModel.Timer> t2 = m.drainTimers();
        assertEquals("照常 6 s", IslandModel.NOTICE_ALERT_MS, t2.get(0).delayMs);
        assertTrue("宽限计时到点不再动它", m.timerDue(t.get(0).name, t.get(0).seq, null).isEmpty());
        assertEquals(IslandModel.Kind.EXP_NOTICE, m.view().kind);
        m.timerDue(t2.get(0).name, t2.get(0).seq, null);
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
    }

    @Test public void reallyFullscreenAfterGraceStaysQueued() {
        m.setNotices(one("a", false), true, false);
        m.drainTimers();
        m.panelShown(true);
        m.setFullscreen(true);
        m.panelShown(false);
        IslandModel.Timer g = m.drainTimers().get(0);
        assertTrue(m.timerDue(g.name, g.seq, null).isEmpty());
        assertFalse("宽限期过了还在全屏（真的在看视频）→ 作废", m.noticeYieldPending());
        m.setFullscreen(false);
        assertEquals("之后退出全屏也不再弹，提醒留在队列", IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        assertEquals(1, m.noticeCount());
    }

    @Test public void fullscreenExitWhileIslandBusyStaysQueued() {
        m.setNotices(one("a", false), true, false);
        m.panelShown(true);
        m.setFullscreen(true);
        m.panelShown(false);
        m.bgStarted("s3-1", "定时任务", 0L);
        m.bgConfirm("s3-1", "要调亮度吗", null);
        m.setFullscreen(false);
        assertEquals("岛不空闲（确认卡）→ 不展开", IslandModel.Kind.EXP_CONFIRM, m.view().kind);
        assertFalse(m.noticeYieldPending());
    }

    @Test public void panelReopensDuringGraceKeepsDebtForNextClose() {
        m.setNotices(one("a", false), true, false);
        m.panelShown(true);
        m.setFullscreen(true);
        m.panelShown(false);
        assertTrue(m.noticeYieldWaitingFullscreen());
        m.panelShown(true);                           // 宽限期里又唤醒了一次
        assertFalse(m.noticeYieldWaitingFullscreen());
        m.setFullscreen(false);
        assertEquals("面板在屏：不展开", IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        assertTrue(m.noticeYieldPending());
        m.panelShown(false);
        assertEquals("这次关闭（不在全屏）→ 展开", IslandModel.Kind.EXP_NOTICE, m.view().kind);
    }

    @Test public void fullscreenExitWithoutDebtDoesNothingNew() {
        m.setNotices(one("a", false), false, false);
        m.setFullscreen(true);
        m.setFullscreen(false);
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
    }
}
