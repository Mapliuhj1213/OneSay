package com.onesay.assistant.v2.island;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 岛上的消息提醒：一次一条 +N、点开列出全部、左滑 / 点叉忽略；与全屏回答、岛上任务、确认卡并存时不覆盖原有内容。 */
public class IslandNoticeModelTest {

    IslandModel m;

    @Before public void setUp() {
        m = new IslandModel();
        m.setMode(IslandModel.Mode.ISLAND);
        m.drainTimers();
    }

    static IslandModel.NoticeRow row(String id, boolean high, String... actions) {
        return new IslandModel.NoticeRow(id, "标题" + id, "摘要", "日程", high, Arrays.asList(actions));
    }

    static List<IslandModel.NoticeRow> rows(IslandModel.NoticeRow... r) {
        return new ArrayList<IslandModel.NoticeRow>(Arrays.asList(r));
    }

    static void noSheetOrTaskEffects(List<IslandModel.Effect> e) {
        assertFalse("不收起 / 不挂回面板、不取消任务：" + e, e.contains(IslandModel.Effect.ATTACH_SHEET) || e.contains(IslandModel.Effect.DETACH_SHEET)
                || e.contains(IslandModel.Effect.CANCEL_TASK) || e.contains(IslandModel.Effect.END_SESSION));
    }

    @Test public void idleAlertExpandsThenCollapsesToCompactPlusN() {
        List<IslandModel.Effect> e = m.setNotices(rows(row("a", false, "加入"), row("b", false), row("c", false)), true, false);
        assertEquals(Arrays.asList(IslandModel.Effect.RENDER), e);
        IslandModel.Snapshot s = m.view();
        assertEquals("空闲时新提醒自动展开", IslandModel.Kind.EXP_NOTICE, s.kind);
        assertEquals(3, s.noticeCount);
        assertEquals("a", s.noticeFocus);
        List<IslandModel.Timer> t = m.drainTimers();
        assertEquals("noticeAlert", t.get(0).name);
        assertEquals(IslandModel.NOTICE_ALERT_MS, t.get(0).delayMs);
        m.timerDue(t.get(0).name, t.get(0).seq, null);
        s = m.view();
        assertEquals("到时收成紧凑：一次只显示一条", IslandModel.Kind.COMPACT_NOTICE, s.kind);
        assertEquals("标题a", s.title);
        assertEquals("其余显示 +N（N = 总数 − 1）", 3, s.noticeCount);
    }

    @Test public void tapExpandsListsAllAndUserOpenedDoesNotAutoCollapse() {
        m.setNotices(rows(row("a", false), row("b", false)), false, false);
        assertEquals("不要求展开 → 紧凑", IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        m.tapMain();
        IslandModel.Snapshot s = m.view();
        assertEquals("点按 = 展开", IslandModel.Kind.EXP_NOTICE, s.kind);
        assertEquals("展开后列出全部", 2, s.notices.size());
        assertTrue(m.drainTimers().isEmpty());
        m.noticeFocus("b");
        assertEquals("b", m.view().noticeFocus);
        m.collapseExpanded(IslandModel.CollapseReason.OUTSIDE_TAP);
        assertEquals("点岛外收起（LC-1：岛窗口只认岛的触摸区域，外面的点击穿透）", IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
    }

    @Test public void swipeLeftOrXDismisses() {
        m.setNotices(rows(row("a", false), row("b", false), row("c", false)), false, false);
        List<IslandModel.Effect> e = m.noticeDismiss(null);
        assertEquals(Arrays.asList(IslandModel.Effect.NOTICE_DISMISS, IslandModel.Effect.RENDER), e);
        assertEquals("紧凑态左滑 = 忽略队首", "a", m.lastTarget());
        assertEquals("标题b", m.view().title);
        m.longPress();
        m.noticeDismiss("c");
        assertEquals("点叉忽略指定那条", "c", m.lastTarget());
        assertEquals(1, m.view().noticeCount);
        m.noticeDismiss(null);
        assertEquals("最后一条没了 → 岛收起", IslandModel.Kind.HIDDEN, m.view().kind);
        assertTrue(m.noticeDismissAll().isEmpty());
    }

    @Test public void actionOnFocusedRow() {
        m.setNotices(rows(row("a", false, "加入", "知道了"), row("b", false, "立即查看", "推迟", "取消发送")), false, false);
        assertTrue("没展开时点动作无效", m.noticeAction(0).isEmpty());
        m.tapMain();
        m.noticeFocus("b");
        List<IslandModel.Effect> e = m.noticeAction(2);
        assertEquals(Arrays.asList(IslandModel.Effect.NOTICE_ACTION, IslandModel.Effect.RENDER), e);
        assertEquals("b#2", m.lastTarget());
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        m.tapMain();
        assertTrue("越界无效", m.noticeAction(5).isEmpty());
    }

    // ------------------------------------------------------------------ 并存：不打断全屏回答 / 岛上任务 / 确认卡

    @Test public void panelShowingOnlyQueues() {
        m.panelShown(true);   // 没有展开的提醒：只记下面板在屏，不产生效果（旧 setPanelShowing 已删，宿主只走 panelShown）
        List<IslandModel.Effect> e = m.setNotices(rows(row("a", true)), true, true);
        noSheetOrTaskEffects(e);
        assertEquals("全屏回答 / 聆听时不自动展开，只排队", IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        assertTrue(m.drainTimers().isEmpty());
        assertEquals(IslandModel.Sheet.IDLE, m.sheet());
    }

    @Test public void panelTaskRunningNotInterrupted() {
        m.taskStarted(0L, "打车去公司");
        m.drainTimers();
        List<IslandModel.Effect> e = m.setNotices(rows(row("a", true)), true, true);
        noSheetOrTaskEffects(e);
        assertEquals("面板里的任务继续是面板的", IslandModel.Sheet.EXPANDED, m.sheet());
        assertTrue(m.isRunning());
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
        assertTrue("没有自动展开计时", m.drainTimers().isEmpty());
    }

    @Test public void islandTaskStaysMainNoticeIsBadge() {
        m.taskStarted(0L, "打车去公司");
        m.requestCollapse(IslandModel.CollapseReason.AUTO);
        m.step("确认上车点");
        m.drainTimers();
        IslandModel.Snapshot before = m.view();
        List<IslandModel.Effect> e = m.setNotices(rows(row("a", false), row("b", false)), true, false);
        noSheetOrTaskEffects(e);
        IslandModel.Snapshot s = m.view();
        assertEquals("岛上任务还是主角", IslandModel.Kind.MINIMAL_DUAL, s.kind);
        assertEquals(IslandModel.Act.TASK, s.act);
        assertEquals("提醒以紧凑角标出现", IslandModel.Act.NOTICE, s.bubble);
        assertEquals("+1", s.bubbleText);
        assertEquals(before.title, s.title);
        assertEquals(before.stepNo, s.stepNo);
        assertTrue(m.isRunning());
        m.taskEnded(false, true, true, false, null, 1000L);
        m.timerDue("doneHide", m.taskSeq(), null);
        assertEquals("任务结束后提醒接着显示", IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
    }

    @Test public void confirmCardNeverCoveredAndWinsOverNotice() {
        m.taskStarted(0L, "发短信");
        m.requestCollapse(IslandModel.CollapseReason.AUTO);
        m.confirmRequired("要发送短信吗", "给妈妈");
        m.setNotices(rows(row("a", true)), true, true);
        assertEquals("确认卡不被提醒顶掉", IslandModel.Kind.EXP_CONFIRM, m.view().kind);
        // 先展开提醒，再来确认卡：确认卡顶上来
        IslandModel m2 = new IslandModel();
        m2.setMode(IslandModel.Mode.ISLAND);
        m2.setNotices(rows(row("a", false)), true, false);
        assertEquals(IslandModel.Kind.EXP_NOTICE, m2.view().kind);
        m2.bgStarted("s3-1", "定时任务", 0L);
        m2.bgConfirm("s3-1", "要调亮度吗", null);
        assertEquals(IslandModel.Kind.EXP_CONFIRM, m2.view().kind);
        m2.bgConfirmResolved("s3-1");
        m2.bgEnded("s3-1", true, null, 1L);
        assertTrue("确认完提醒还在", m2.noticeCount() == 1);
    }

    @Test public void backgroundTaskOrNoteRunningIsBusy() {
        m.bgStarted("a1", "定时任务", 0L);
        m.setNotices(rows(row("x", false)), true, false);
        assertEquals("后台任务在岛上跑 → 只排队", IslandModel.Kind.MINIMAL_DUAL, m.view().kind);
        assertEquals(IslandModel.Act.AGENT, m.view().act);
    }

    @Test public void fullscreenHidesNormalButHighAlertPasses() {
        m.setFullscreen(true);
        m.setNotices(rows(row("a", false)), true, false);
        assertEquals("全屏：普通提醒不显示（排队）", IslandModel.Kind.HIDDEN, m.view().kind);
        IslandModel m2 = new IslandModel();
        m2.setMode(IslandModel.Mode.ISLAND);
        m2.setFullscreen(true);
        m2.setNotices(rows(row("a", true)), true, true);
        assertEquals("全屏：高优先级（日程）算关键事件放行", IslandModel.Kind.EXP_NOTICE, m2.view().kind);
        m.setFullscreen(false);
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, m.view().kind);
    }

    @Test public void modeNoneShowsNothing() {
        IslandModel n = new IslandModel();
        n.setNotices(rows(row("a", false)), true, false);
        assertEquals("岛没开：不画（Android 侧改发通知）", IslandModel.Kind.HIDDEN, n.view().kind);
    }

    @Test public void emptyRowsClear() {
        m.setNotices(rows(row("a", false)), true, false);
        m.setNotices(new ArrayList<IslandModel.NoticeRow>(), false, false);
        assertEquals(IslandModel.Kind.HIDDEN, m.view().kind);
        assertNull(m.noticeFocus());
        assertTrue("同样内容不重复渲染", m.setNotices(new ArrayList<IslandModel.NoticeRow>(), false, false).isEmpty());
    }

    @Test public void staleAlertTimerIgnoredAfterUserInteraction() {
        m.setNotices(rows(row("a", false), row("b", false)), true, false);
        IslandModel.Timer t = m.drainTimers().get(0);
        m.noticeFocus("b");
        assertTrue("用户在看：自动收起作废", m.timerDue(t.name, t.seq, null).isEmpty());
        assertEquals(IslandModel.Kind.EXP_NOTICE, m.view().kind);
    }
}
