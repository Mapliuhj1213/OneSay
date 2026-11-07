package com.onesay.assistant.v2.island;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.onesay.assistant.v2.island.IslandModel.CollapseReason;
import com.onesay.assistant.v2.island.IslandModel.Effect;
import com.onesay.assistant.v2.island.IslandModel.Kind;
import com.onesay.assistant.v2.island.IslandModel.Mode;
import com.onesay.assistant.v2.island.IslandModel.Sheet;
import com.onesay.assistant.v2.island.IslandModel.Status;

/**
 * ★ arch/island-progress（C 路）：灵动岛不再显示「xx 步」——
 * ① 何时上岛（单次接口调用不上岛；GUI 操作 / 子目标 ≥ 2 / 第 2 次工具调用才上）；
 * ② 进度文案（只在 GUI 或子目标 ≥ 2 时显示「第 i/n 项」/ 进度点，失败有对应状态）；
 * ③ ask_user 芯片（2–4 个、点了回填、60 s 超时收起、不能随手收起、全屏也显示）。
 */
public class IslandProgressTest {

    static IslandModel model() {
        IslandModel x = new IslandModel();
        x.setMode(Mode.ISLAND);
        x.configure(true, true);
        x.drainTimers();
        return x;
    }

    static IslandModel.Timer timer(List<IslandModel.Timer> got, String name) {
        for (IslandModel.Timer t : got) if (t.name.equals(name)) return t;
        throw new AssertionError("没有排 " + name + "：" + got);
    }

    static boolean has(List<IslandModel.Timer> got, String name) {
        for (IslandModel.Timer t : got) if (t.name.equals(name)) return true;
        return false;
    }

    static List<Effect> due(IslandModel m, IslandModel.Timer t) {
        return m.timerDue(t.name, t.seq, t.target);
    }

    /** 立即上岛（分诊判多步）的任务，已收进岛。 */
    static IslandModel collapsedEager() {
        IslandModel m = model();
        m.taskStarted(1000L, "查天气然后订闹钟再告诉妈妈", true);
        due(m, timer(m.drainTimers(), "autoCollapse"));
        m.drainTimers();
        return m;
    }

    static List<Subgoal> goals(String... states) {
        String[] titles = {"查明天北京天气", "订明早七点的闹钟", "把行程发给妈妈", "叫车去机场", "买咖啡", "回邮件", "关灯"};
        List<Subgoal> l = new ArrayList<Subgoal>();
        for (int i = 0; i < states.length; i++) l.add(new Subgoal("g" + i, titles[i % titles.length], states[i]));
        return l;
    }

    /** 快照里所有会画出来的进度相关文字都不许出现「步」。 */
    static void assertNoStepCount(IslandModel.Snapshot s) {
        String[] texts = {IslandModel.progressLine(s), IslandModel.pulseLine(s), IslandModel.failLine(s),
                IslandModel.compactActionWord(s), IslandModel.compactFailRight(s), s.progressText == null ? "" : s.progressText};
        for (String t : texts) assertFalse("岛上不再出现「第 N 步」：" + t, t.matches(".*第\\s*\\d+\\s*步.*"));
    }

    // ================================================================== ① 何时上岛

    @Test public void singleCallTaskNeverGoesOnIsland() {
        IslandModel m = model();
        m.taskStarted(1000L, "打开蓝牙", false);
        assertFalse(m.isIslandWorthy());
        IslandModel.Timer ac = timer(m.drainTimers(), "autoCollapse");
        assertTrue("懒上岛：自动收起时间到了也不收", due(m, ac).isEmpty());
        assertEquals(Sheet.EXPANDED, m.sheet());
        m.step("正在：打开蓝牙");
        assertFalse("一次工具调用 = 单次接口调用，不上岛", m.isIslandWorthy());
        assertFalse(has(m.drainTimers(), "autoCollapse"));
        assertEquals(Kind.HIDDEN, m.view().kind);
        assertTrue("没收起 → 收工不碰面板", m.taskEnded(false, false, true, true, null, 5000L).isEmpty());
        assertEquals(Sheet.IDLE, m.sheet());
        assertEquals(Kind.HIDDEN, m.view().kind);
    }

    @Test public void secondToolCallPromotesAndOwedCollapseIsRescheduled() {
        IslandModel m = model();
        m.configure(true, true, 150L);
        m.taskStarted(1000L, "下载微信", false);
        due(m, timer(m.drainTimers(), "autoCollapse"));
        m.step("正在：搜索应用商店");
        assertFalse(has(m.drainTimers(), "autoCollapse"));
        m.step("正在：打开详情页");
        assertTrue(m.isIslandWorthy());
        IslandModel.Timer ac = timer(m.drainTimers(), "autoCollapse");
        assertEquals("补排用同样的收起延时（新界面 150 ms）", 150L, ac.delayMs);
        assertEquals(Arrays.asList(Effect.DETACH_SHEET, Effect.RENDER), due(m, ac));
        assertEquals(Kind.COMPACT_TASK, m.view().kind);
        assertFalse("多步但没有 GUI、没有账本 → 不显示进度", m.view().progressVisible);
    }

    @Test public void promotionBeforeTimerJustLetsTheTimerCollapse() {
        IslandModel m = model();
        m.taskStarted(1000L, "下载微信", false);
        IslandModel.Timer ac = timer(m.drainTimers(), "autoCollapse");
        m.step("a");
        m.step("b");
        assertFalse("还没欠收起 → 不重复排", has(m.drainTimers(), "autoCollapse"));
        assertEquals(Arrays.asList(Effect.DETACH_SHEET, Effect.RENDER), due(m, ac));
    }

    @Test public void subgoalsTwoOrMorePromoteOneDoesNot() {
        IslandModel m = model();
        m.taskStarted(1000L, "订个闹钟", false);
        due(m, timer(m.drainTimers(), "autoCollapse"));
        m.subgoals(goals("doing"));
        assertFalse("1 个子目标不算多步", m.isIslandWorthy());
        m.subgoals(goals("doing", "pending"));
        assertTrue(m.isIslandWorthy());
        assertTrue(has(m.drainTimers(), "autoCollapse"));
    }

    @Test public void guiExecPromotesForegroundTask() {
        IslandModel m = model();
        m.taskStarted(1000L, "在设置里关掉自动亮度", false);
        due(m, timer(m.drainTimers(), "autoCollapse"));
        m.step("正在：操作手机");
        m.execStarted("exec-1", "关掉自动亮度", "com.android.settings", 2000L);
        assertTrue("GUI 操作开始 → 上岛", m.isIslandWorthy());
        assertTrue(has(m.drainTimers(), "autoCollapse"));
        assertTrue(m.anyGuiRunning());
    }

    @Test public void eagerTaskCollapsesAsBefore() {
        IslandModel m = model();
        m.taskStarted(1000L, "打开wifi然后打开蓝牙", true);
        assertEquals(Arrays.asList(Effect.DETACH_SHEET, Effect.RENDER), due(m, timer(m.drainTimers(), "autoCollapse")));
        IslandModel m2 = model();
        m2.taskStarted(1000L, "旧调用（两参）= 立即上岛");
        assertTrue(m2.isIslandWorthy());
    }

    @Test public void userCanStillTuckLazyTaskIntoIslandByTappingOutside() {
        IslandModel m = model();
        m.taskStarted(1000L, "打开蓝牙", false);
        m.drainTimers();
        assertEquals("点面板外：照旧收成岛（否则宿主会把任务取消）", Arrays.asList(Effect.DETACH_SHEET, Effect.RENDER),
                m.requestCollapse(CollapseReason.OUTSIDE_TAP));
        assertFalse(m.view().progressVisible);
    }

    @Test public void newTaskResetsLazyStateAndSubgoals() {
        IslandModel m = collapsedEager();
        m.subgoals(goals("done", "doing"));
        m.taskEnded(true, false, false, false, null, 3000L);
        m.taskStarted(4000L, "打开蓝牙", false);
        assertFalse(m.isIslandWorthy());
        assertTrue(m.subgoals().isEmpty());
        assertTrue("没有在跑的任务时账本被忽略", new IslandModel().subgoals(goals("doing", "pending")).isEmpty());
    }

    // ================================================================== ② 进度文案

    @Test public void noProgressWithoutGuiOrSubgoals() {
        IslandModel m = collapsedEager();
        m.step("正在：查询天气");
        m.step("正在：设置闹钟");
        m.step("正在：发送短信");
        IslandModel.Snapshot s = m.view();
        assertEquals(3, s.stepNo);
        assertFalse(s.progressVisible);
        assertNull(s.progressText);
        assertEquals("", IslandModel.progressLine(s));
        assertEquals("轻提醒只有动作短句", "发送短信", IslandModel.pulseLine(s));
        assertNoStepCount(s);
    }

    @Test public void subgoalProgressShowsItemOfTotalAndCurrentTitle() {
        IslandModel m = collapsedEager();
        m.step("正在：查询天气");
        List<Effect> e = m.subgoals(goals("done", "doing", "pending"));
        assertEquals(Arrays.asList(Effect.RENDER), e);
        assertTrue("当前项变了 → 轻提醒", has(m.drainTimers(), "pulse"));
        IslandModel.Snapshot s = m.view();
        assertEquals(Kind.PULSE_STEP, s.kind);
        assertTrue(s.progressVisible);
        assertEquals(2, s.progressIndex);
        assertEquals(3, s.progressTotal);
        assertEquals("第 2/3 项", s.progressText);
        assertEquals("订明早七点的闹钟", s.progressCurrent);
        assertEquals("第 2/3 项", IslandModel.progressLine(s));
        assertEquals("第 2/3 项 · 订明早七点的闹钟", IslandModel.pulseLine(s));
        assertEquals("紧凑态动作词 = 当前子目标（≤6 字）", "订明早七点的", IslandModel.compactActionWord(s));
        assertEquals(3, s.subgoals.size());
        assertNoStepCount(s);
        // 同一个账本再发一次：不重复提醒
        m.subgoals(goals("done", "doing", "pending"));
        assertFalse(has(m.drainTimers(), "pulse"));
        // 工具调用不改进度
        m.step("正在：设置闹钟");
        assertEquals("第 2/3 项", m.view().progressText);
    }

    @Test public void failedSubgoalHasFailState() {
        IslandModel m = collapsedEager();
        m.subgoals(goals("done", "failed", "doing"));
        IslandModel.Snapshot s = m.view();
        assertEquals("doing 优先于失败项", "第 3/3 项", s.progressText);
        m.subgoals(goals("done", "failed", "pending"));
        assertEquals("第 3/3 项", m.view().progressText);
        m.subgoals(goals("done", "failed", "done"));
        s = m.view();
        assertEquals("都结束了 → 指向失败项", 2, s.progressIndex);
        assertTrue(s.progressFailed);
        m.taskEnded(false, false, false, false, "微信没登录", 9000L);
        s = m.view();
        assertEquals(Kind.EXP_FAIL, s.kind);
        assertEquals("第 2/3 项没完成 · 微信没登录", IslandModel.failLine(s));
        assertEquals("2/3", IslandModel.compactFailRight(s));
        assertNoStepCount(s);
    }

    @Test public void failWithoutLedgerShowsOnlyReason() {
        IslandModel m = collapsedEager();
        m.step("a");
        m.step("b");
        m.taskEnded(false, false, false, false, "网络断了", 9000L);
        IslandModel.Snapshot s = m.view();
        assertEquals("网络断了", IslandModel.failLine(s));
        assertEquals("", IslandModel.compactFailRight(s));
        assertNoStepCount(s);
    }

    @Test public void guiOnlyShowsIndeterminateProgress() {
        IslandModel m = collapsedEager();
        m.execStarted("exec-1", "关掉自动亮度", "com.android.settings", 2000L);
        m.bgStep("exec-1", "点「显示」");
        m.bgStep("exec-1", "关「自动亮度」");
        IslandModel.Snapshot exec = null;
        // 双活动：前台任务 + 执行模式；展开执行模式看它自己的快照
        m.longPress("exec-1");
        exec = m.view();
        assertTrue(exec.exec);
        assertTrue("GUI 操作中 → 显示进度", exec.progressVisible);
        assertNull("没有账本 → 不写「第 i/n 项」", exec.progressText);
        assertEquals("正在操作", IslandModel.progressLine(exec));
        assertEquals(2, exec.stepNo);
        assertNoStepCount(exec);
        // GUI + 账本 → 执行模式卡也显示子目标进度
        m.subgoals(goals("done", "doing"));
        exec = m.view();
        assertEquals("第 2/2 项", IslandModel.progressLine(exec));
        // 前台任务的快照：GUI 在跑也显示进度
        m.collapseExpanded(CollapseReason.SWIPE_UP);
        m.longPress(IslandModel.K_TASK);
        IslandModel.Snapshot task = m.view();
        assertEquals(Kind.EXP_RUN, task.kind);
        assertTrue(task.progressVisible);
        assertEquals("第 2/2 项", task.progressText);
    }

    @Test public void subgoalProgressPureFunction() {
        assertFalse(Subgoal.progress(Collections.<Subgoal>emptyList(), false).visible);
        assertFalse("1 个子目标、无 GUI → 不显示", Subgoal.progress(goals("doing"), false).visible);
        assertTrue("1 个子目标、GUI → 显示（不带数字）", Subgoal.progress(goals("doing"), true).visible);
        assertNull(Subgoal.progress(goals("doing"), true).text);
        assertEquals("第 1/2 项", Subgoal.progress(goals("pending", "pending"), false).text);
        assertEquals("全部完成 → n/n", "第 4/4 项", Subgoal.progress(goals("done", "done", "done", "done"), false).text);
        assertEquals(3, Subgoal.doneCount(goals("done", "done", "doing", "done")));
        assertEquals("第 1/3 项", Subgoal.itemText(0, 3));
        assertEquals("第 3/3 项", Subgoal.itemText(9, 3));
        assertEquals(Subgoal.State.DOING, Subgoal.State.fromWire("Doing"));
        assertEquals(Subgoal.State.DONE, Subgoal.State.fromWire("done"));
        assertEquals(Subgoal.State.FAILED, Subgoal.State.fromWire("failed"));
        assertEquals(Subgoal.State.PENDING, Subgoal.State.fromWire("pending"));
        assertEquals(Subgoal.State.PENDING, Subgoal.State.fromWire("???"));
        assertEquals("pending", Subgoal.State.PENDING.wire());
        List<Subgoal> raw = new ArrayList<Subgoal>(Arrays.asList(new Subgoal(null, " a ", "doing"), null, new Subgoal("x", "b", "done")));
        List<Subgoal> s = Subgoal.sanitize(raw);
        assertEquals(2, s.size());
        assertEquals("#0", s.get(0).id);
        assertEquals("a", s.get(0).title);
        List<Subgoal> many = new ArrayList<Subgoal>();
        for (int i = 0; i < 30; i++) many.add(new Subgoal("g" + i, "t" + i, "pending"));
        assertEquals(Subgoal.MAX, Subgoal.sanitize(many).size());
    }

    @Test public void subgoalWindowFollowsCurrent() {
        assertEquals(0, IslandModel.subgoalWindowStart(3, 3));
        assertEquals(0, IslandModel.subgoalWindowStart(7, 1));
        assertEquals(0, IslandModel.subgoalWindowStart(7, 2));
        assertEquals(1, IslandModel.subgoalWindowStart(7, 3));
        assertEquals("当前项前留一项", 3, IslandModel.subgoalWindowStart(7, 5));
        assertEquals("贴底", 3, IslandModel.subgoalWindowStart(7, 7));
    }

    @Test public void stepCountNeverRenderedAnywhere() {
        // 所有前台任务状态都过一遍：运行 / 轻提醒 / 确认 / 失败，带或不带账本
        IslandModel m = collapsedEager();
        for (int i = 0; i < 7; i++) m.step("正在：动作" + i);
        assertNoStepCount(m.view());
        m.confirmRequired("发短信给妈妈", "我到了");
        assertEquals(Kind.EXP_CONFIRM, m.view().kind);
        assertNoStepCount(m.view());
        m.confirmResolved();
        m.subgoals(goals("done", "doing", "pending", "pending"));
        assertNoStepCount(m.view());
        m.taskEnded(false, false, false, false, "超时", 10000L);
        assertNoStepCount(m.view());
    }

    // ================================================================== ③ ask_user 芯片

    @Test public void askShowsChipsOnIslandAndAnswerFillsBack() {
        IslandModel m = collapsedEager();
        m.subgoals(goals("done", "doing", "pending"));
        m.drainTimers();
        List<Effect> e = m.askUser("q1", "订哪一家？", Arrays.asList("海底捞", " 西贝 ", "海底捞", "", "都不要", "随便", "第五个"), 0L);
        assertEquals(Arrays.asList(Effect.RENDER), e);
        IslandModel.Snapshot s = m.view();
        assertEquals(Kind.EXP_ASK, s.kind);
        assertEquals(Status.WAIT, s.status);
        assertEquals("q1", s.askId);
        assertEquals("订哪一家？", s.askQuestion);
        assertEquals("去空白、去重、最多 4 个", Arrays.asList("海底捞", "西贝", "都不要", "随便"), s.askOptions);
        assertEquals("超时默认 60 s", IslandModel.ASK_TIMEOUT_MS, s.askTimeoutMs);
        assertEquals("ask 卡右上角也是子目标进度", "第 2/3 项", s.progressText);
        assertTrue(s.isExpanded());
        IslandModel.Timer t = timer(m.drainTimers(), "askTimeout");
        assertEquals(60000L, t.delayMs);
        assertEquals("q1", t.target);
        assertTrue(m.isAskPending());

        List<Effect> a = m.askAnswer(1);
        assertEquals(Arrays.asList(Effect.ASK_ANSWER, Effect.RENDER), a);
        assertEquals("q1", m.lastTarget());
        assertEquals("西贝", m.lastAskChoice());
        assertFalse(m.isAskPending());
        assertEquals(Status.RUN, m.status());
        assertFalse(m.view().kind == Kind.EXP_ASK);
        assertTrue("答过了：旧的超时作废", due(m, t).isEmpty());
        assertTrue("越界 / 重复点无效", m.askAnswer(0).isEmpty());
    }

    @Test public void askTimesOutAfterSixtySecondsAndCollapses() {
        IslandModel m = collapsedEager();
        m.askUser("q2", "用哪个账号？", Arrays.asList("工作", "个人"), 60000L);
        IslandModel.Timer t = timer(m.drainTimers(), "askTimeout");
        assertEquals(Arrays.asList(Effect.ASK_TIMEOUT, Effect.RENDER), due(m, t));
        assertEquals("q2", m.lastTarget());
        assertFalse(m.isAskPending());
        assertEquals(Status.RUN, m.status());
        assertEquals("超时收起 → 回到紧凑态", Kind.COMPACT_TASK, m.view().kind);
        assertTrue("再到一次也无效", due(m, t).isEmpty());
    }

    @Test public void askTimeoutClampsAndHintText() {
        assertEquals(IslandModel.ASK_TIMEOUT_MS, IslandModel.normalizeAskTimeoutMs(-1L));
        assertEquals(5000L, IslandModel.normalizeAskTimeoutMs(100L));
        assertEquals(600000L, IslandModel.normalizeAskTimeoutMs(9999999L));
        assertEquals("1 分钟内没选会自动收起", IslandModel.askHint(60000L));
        assertEquals("30 秒内没选会自动收起", IslandModel.askHint(30000L));
    }

    @Test public void askCannotBeDismissedButShowsInFullscreen() {
        IslandModel m = collapsedEager();
        m.askUser("q3", "要继续吗？", Arrays.asList("继续", "算了"), 60000L);
        assertTrue("上滑收不起", m.collapseExpanded(CollapseReason.SWIPE_UP).isEmpty());
        assertTrue("点外部收不起", m.collapseExpanded(CollapseReason.OUTSIDE_TAP).isEmpty());
        assertTrue("横滑暂避被拒", m.tuck().isEmpty());
        m.setFullscreen(true);
        assertEquals("全屏也放行", Kind.EXP_ASK, m.view().kind);
        assertTrue("别的活动不能把它顶掉", m.longPress(IslandModel.K_PICKUP).isEmpty());
    }

    @Test public void askWhileSheetExpandedTucksTaskIntoIsland() {
        IslandModel m = model();
        m.taskStarted(1000L, "打开蓝牙", false);
        m.drainTimers();
        List<Effect> e = m.askUser("q4", "开哪个设备？", Arrays.asList("耳机", "手表"), 60000L);
        assertEquals("芯片只画在岛上 → 面板收起", Arrays.asList(Effect.DETACH_SHEET, Effect.RENDER), e);
        assertTrue(m.isIslandWorthy());
        assertEquals(Kind.EXP_ASK, m.view().kind);
    }

    @Test public void askRejectedWhenIslandCannotTakeIt() {
        IslandModel none = new IslandModel();
        none.configure(true, true);
        none.taskStarted(1000L, "x", true);
        assertFalse("不画浮层", none.canAskUser(Arrays.asList("a", "b")));
        assertTrue(none.askUser("q", "?", Arrays.asList("a", "b"), 0L).isEmpty());
        IslandModel idle = model();
        assertFalse("没有在跑的任务", idle.canAskUser(Arrays.asList("a", "b")));
        IslandModel m = collapsedEager();
        assertFalse("选项 < 2", m.canAskUser(Arrays.asList("a", " ", "a")));
        assertFalse(m.canAskUser(null));
        m.confirmRequired("发短信", "x");
        assertFalse("确认卡未决", m.canAskUser(Arrays.asList("a", "b")));
        assertTrue(m.askUser("q", "?", Arrays.asList("a", "b"), 0L).isEmpty());
        assertTrue(collapsedEager().askUser(null, "?", Arrays.asList("a", "b"), 0L).isEmpty());
    }

    @Test public void askResolvedElsewhereOrTaskEndClearsChips() {
        IslandModel m = collapsedEager();
        m.askUser("q5", "?", Arrays.asList("a", "b"), 0L);
        assertTrue("别的 id 不动", m.askResolved("other").isEmpty());
        assertEquals(Arrays.asList(Effect.RENDER), m.askResolved("q5"));
        assertFalse(m.isAskPending());
        m.askUser("q6", "?", Arrays.asList("a", "b"), 0L);
        m.taskEnded(false, false, true, false, null, 5000L);
        assertFalse("收工 → 问题作废", m.isAskPending());
        IslandModel m2 = collapsedEager();
        m2.askUser("q7", "?", Arrays.asList("a", "b"), 0L);
        assertTrue(m2.stop().contains(Effect.CANCEL_TASK));
        assertFalse(m2.isAskPending());
    }

    @Test public void confirmResolvedDoesNotEraseAsk() {
        IslandModel m = collapsedEager();
        m.askUser("q8", "?", Arrays.asList("a", "b"), 0L);
        m.confirmResolved();
        assertEquals("确认卡收尾不能把等你选择的状态冲掉", Status.WAIT, m.status());
        assertEquals(Kind.EXP_ASK, m.view().kind);
    }

    @Test public void normalizeOptionsPure() {
        assertEquals(Collections.<String>emptyList(), IslandModel.normalizeOptions(null));
        assertEquals(Arrays.asList("x", "y"), IslandModel.normalizeOptions(Arrays.asList(null, " x", "y ", "x")));
    }
}
