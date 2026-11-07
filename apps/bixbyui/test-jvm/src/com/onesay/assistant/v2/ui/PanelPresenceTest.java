package com.onesay.assistant.v2.ui;

import com.onesay.assistant.v2.island.IslandModel;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-10-23 BUG-I2b：面板每条关闭路径都会且只会通知岛一次 panel(false)。
 *
 * <p>这里用 {@link PanelPresence}（服务里唯一的通知出口）+ 真的 {@link IslandModel}，按 FloatingSheetService 里各条关闭路径
 * 实际会走到的 addView / removeView 次序（PanelPresenceWiringTest 从源码证明每条路径都汇到 removeSheetWindow）逐条重放：
 * 收起动画回调 + 超时兜底双双触发、onDestroy 再收一次等「同一次关闭经过多次 removeView」的情况都只报一次。
 */
public class PanelPresenceTest {

    /** 手动跑的主线程队列。 */
    static final class FakeMain implements PanelPresence.Poster {
        final List<Runnable> q = new ArrayList<Runnable>();

        @Override public void post(Runnable r) {
            q.add(r);
        }

        @Override public void cancel(Runnable r) {
            while (q.remove(r)) { /* 删干净 */ }
        }

        void run() {
            while (!q.isEmpty()) q.remove(0).run();
        }
    }

    FakeMain main;
    IslandModel island;
    List<String> calls;
    PanelPresence p;

    @Before public void setUp() {
        main = new FakeMain();
        island = new IslandModel();
        island.setMode(IslandModel.Mode.ISLAND);
        island.drainTimers();
        calls = new ArrayList<String>();
        p = new PanelPresence(new PanelPresence.Sink() {
            @Override public void onPanelVisibility(boolean on, String why) {
                calls.add("panel(" + on + ")");
                island.panelShown(on);   // S1Hooks.onPanelVisibility → IslandController.panelVisibility → model
            }
        }, main);
    }

    int falses() {
        int n = 0;
        for (String c : calls) if ("panel(false)".equals(c)) n++;
        return n;
    }

    /** 提醒自动展开中唤醒面板：面板挂上 → 提醒让位。 */
    void wakeOverExpandedNotice() {
        island.setNotices(Arrays.asList(new IslandModel.NoticeRow("n1", "会议", "10:00 开会", "日程", false, Arrays.asList("知道了"))), true, false);
        island.drainTimers();
        assertEquals(IslandModel.Kind.EXP_NOTICE, island.view().kind);
        p.attached("showSheet");
        main.run();
        assertEquals(Arrays.asList("panel(true)"), calls);
        assertEquals("提醒让位", IslandModel.Kind.COMPACT_NOTICE, island.view().kind);
    }

    void assertClosedOnceAndRestored() {
        assertEquals("只报一次 panel(false)：" + calls, 1, falses());
        assertEquals(Arrays.asList("panel(true)", "panel(false)"), calls);
        assertFalse(p.onScreen());
        assertEquals("岛空闲 → 提醒重新展开", IslandModel.Kind.EXP_NOTICE, island.view().kind);
        List<IslandModel.Timer> t = island.drainTimers();
        assertEquals(IslandModel.NOTICE_ALERT_MS, t.get(t.size() - 1).delayMs);
    }

    // ------------------------------------------------------------------ 各条关闭路径

    /** HIDE_SHEET：onStartCommand → hideSheet(true) → detachFromWindowImmediate → removeSheetWindow（上机 FAIL 的那条）。 */
    @Test public void hideSheetImmediate() {
        wakeOverExpandedNotice();
        p.removed("detach");
        main.run();
        assertClosedOnceAndRestored();
    }

    /** 点 ✕（旧界面）：onDismiss → dismissByUser → hideSheet(true) → detach；之后 HIDE 迟到再来一次 → 已离屏，不再报。 */
    @Test public void closeButtonLegacyThenLateHide() {
        wakeOverExpandedNotice();
        p.removed("detach");
        main.run();
        p.removed("detach");   // 迟到的 HIDE_SHEET：detach 里 getParent()==null && !mIsAttached 不会再 removeView；就算再调也不报
        main.run();
        assertClosedOnceAndRestored();
    }

    /** 点 ✕ / 返回键（新界面）：hideSheet(false) → v2DismissAnimated → 动画结束 detach；900 ms 兜底也触发 → 同一次关闭只报一次。 */
    @Test public void closeButtonV2AnimationPlusFallback() {
        wakeOverExpandedNotice();
        // 退场动画进行中：卡片窗还挂着 → 还不算关
        main.run();
        assertTrue(p.onScreen());
        assertEquals(0, falses());
        p.removed("detach");          // animateHide 回调
        p.removed("detach");          // mV2DismissFallback 同一轮里也到了
        main.run();
        assertClosedOnceAndRestored();
    }

    /** 自动收起（旧界面）：mAutoHideRunnable → hideSheet(false) → animateHide 回调 detach + 220 ms 兜底 detach，分两轮到。 */
    @Test public void autoHideAnimationThenFallbackNextTick() {
        wakeOverExpandedNotice();
        p.removed("detach");
        main.run();
        p.removed("detach");
        main.run();
        assertClosedOnceAndRestored();
    }

    /** 返回键：FloatingSheetView.dispatchKeyEvent(KEYCODE_BACK) → onDismiss，与 ✕ 同一条（这里按旧界面立即销毁重放）。 */
    @Test public void backKey() {
        wakeOverExpandedNotice();
        p.removed("detach");
        main.run();
        assertClosedOnceAndRestored();
    }

    /** 服务销毁：onDestroy → hideSheet(true) → detach，再 flushNow 同步报掉（不等 post；岛随后才拆）。 */
    @Test public void serviceDestroyReportsSynchronously() {
        wakeOverExpandedNotice();
        p.removed("detach");
        p.flushNow();
        assertEquals("flushNow 当场就报了", Arrays.asList("panel(true)", "panel(false)"), calls);
        assertTrue("post 的那次已撤掉", main.q.isEmpty());
        main.run();
        assertClosedOnceAndRestored();
    }

    /** 服务销毁时面板早就关了：onDestroy 的 hideSheet(true) + flushNow 不重复报。 */
    @Test public void serviceDestroyAfterCloseNoDuplicate() {
        wakeOverExpandedNotice();
        p.removed("detach");
        main.run();
        p.flushNow();                 // onDestroy：detach 不会 removeView（已离屏），flushNow 无事可报
        main.run();
        assertClosedOnceAndRestored();
    }

    /**
     * 崩溃：进程连同岛一起没了；START_STICKY 拉起后是新的 PanelPresence + 新的 IslandModel，起点都是「面板不在屏」——
     * 不会留着「面板在屏」的旧状态把提醒一直压在队列里。
     */
    @Test public void crashRestartStartsWithPanelOff() {
        wakeOverExpandedNotice();
        // 崩溃：什么回调都没有。重启：
        setUp();
        assertFalse(p.onScreen());
        assertFalse(p.reported());
        assertFalse("新模型：面板不在屏 → 岛空闲", island.noticeBusy());
        island.setNotices(Arrays.asList(new IslandModel.NoticeRow("n1", "会议", "10:00 开会", "日程", false, Arrays.asList("知道了"))), true, false);
        assertEquals("从库恢复的提醒照常展开", IslandModel.Kind.EXP_NOTICE, island.view().kind);
        p.flushNow();
        assertTrue("没挂过面板就不报", calls.isEmpty());
    }

    /** LC-5：岛上任务时新请求接管卡片 → 全屏回答（岛判成全屏）→ 关闭；关面板时还在全屏，稍后退出全屏 → 补展开。 */
    @Test public void lc5FullscreenAnswerThenClose() {
        wakeOverExpandedNotice();
        island.setFullscreen(true);   // fullscreen(true) → HIDDEN
        p.removed("detach");          // HIDE_SHEET
        main.run();
        assertEquals(1, falses());
        assertFalse("还没退出全屏：先不弹", IslandModel.Kind.EXP_NOTICE == island.view().kind);
        assertTrue(island.noticeYieldWaitingFullscreen());
        island.setFullscreen(false);  // fullscreen(false)
        assertClosedOnceAndRestored();
    }

    /** 任务进岛（形变）：卡片窗在舞台第一帧后才 removeView → 那时才报；岛不空闲 → 提醒留在队列（规则不变）。 */
    @Test public void taskIntoIslandReportsOnceButStaysQueued() {
        wakeOverExpandedNotice();
        island.taskStarted(0L, "打车去公司");
        island.requestCollapse(IslandModel.CollapseReason.AUTO);
        island.drainTimers();
        main.run();
        assertTrue("v2MorphToIsland 只改 mIsAttached，窗还挂着", p.onScreen());
        p.removed("morph:形变落定后");
        main.run();
        assertEquals(1, falses());
        assertEquals(IslandModel.Kind.MINIMAL_DUAL, island.view().kind);
        assertEquals(IslandModel.Act.TASK, island.view().act);
        assertFalse(island.noticeYieldPending());
    }

    // ------------------------------------------------------------------ 合并 / 去重

    @Test public void removeThenReattachSameTickIsSilent() {
        wakeOverExpandedNotice();
        p.removed("detach");          // 收起动画中途来了新唤醒：showSheet 先 detach 再 addView
        p.attached("showSheet");
        main.run();
        assertEquals("最终状态没变 → 不报（岛上不抖）", Arrays.asList("panel(true)"), calls);
        assertEquals(IslandModel.Kind.COMPACT_NOTICE, island.view().kind);
    }

    @Test public void onePostPerBurst() {
        p.attached("a");
        p.removed("b");
        p.attached("c");
        assertEquals("一轮只 post 一次", 1, main.q.size());
        main.run();
        assertEquals(Arrays.asList("panel(true)"), calls);
        assertEquals(1, p.reports());
    }

    @Test public void repeatedOpenCloseReportsEachTransition() {
        for (int i = 0; i < 3; i++) {
            p.attached("show");
            main.run();
            p.removed("detach");
            main.run();
        }
        assertEquals(6, calls.size());
        assertEquals(3, falses());
    }
}
