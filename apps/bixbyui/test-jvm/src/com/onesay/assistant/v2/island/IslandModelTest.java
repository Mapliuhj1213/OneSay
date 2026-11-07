package com.onesay.assistant.v2.island;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.onesay.assistant.v2.island.IslandModel.Act;
import com.onesay.assistant.v2.island.IslandModel.CollapseReason;
import com.onesay.assistant.v2.island.IslandModel.Effect;
import com.onesay.assistant.v2.island.IslandModel.Kind;
import com.onesay.assistant.v2.island.IslandModel.Mode;
import com.onesay.assistant.v2.island.IslandModel.Sheet;
import com.onesay.assistant.v2.island.IslandModel.Status;
import com.onesay.assistant.v2.s1.PickupCardStore;
import com.onesay.assistant.v2.s1.PickupCodeDetector;

/**
 * [island v3] 岛 / 活动模型：v3 的时长与手势（轻提醒、关键提醒、确认立即展开、完成 3 s、失败 6 s + 5 min、暂避、全屏、8 h），
 * S3 的提醒 / 后台任务 / 待办，多任务优先级，胶囊回落（= 旧行为）。
 */
public class IslandModelTest {

    static IslandModel model(Mode m) {
        IslandModel x = new IslandModel();
        x.setMode(m);
        x.configure(true, true);
        x.drainTimers();
        return x;
    }

    static IslandModel running(Mode m) {
        IslandModel x = model(m);
        x.taskStarted(1000L, "打车去公司，到了告诉妈妈一声");
        return x;
    }

    static IslandModel collapsed(Mode m) {
        IslandModel x = running(m);
        x.autoCollapseDue(x.taskSeq());
        x.drainTimers();
        return x;
    }

    static IslandModel.Pickup pk(String id, String code, long at) {
        return new IslandModel.Pickup(id, "取件码", code, "菜鸟驿站", "短信", at, at + 12L * 3600L * 1000L);
    }

    /** 取出名为 name 的延时请求（没有 → 失败）。 */
    static IslandModel.Timer timer(IslandModel m, List<IslandModel.Timer> got, String name) {
        for (IslandModel.Timer t : got) if (t.name.equals(name)) return t;
        throw new AssertionError("没有排 " + name + "：" + got);
    }

    static List<Effect> due(IslandModel m, IslandModel.Timer t) {
        return m.timerDue(t.name, t.seq, t.target);
    }

    // ------------------------------------------------------------------ 形态选择（v3：不看无障碍）

    @Test public void chooseModeWithoutAccessibility() {
        assertEquals(Mode.ISLAND, IslandModel.chooseMode(true, false, true));
        assertEquals("island.off → 不画浮层（第 6 项：胶囊已删）", Mode.NONE, IslandModel.chooseMode(true, true, true));
        assertEquals("开关关 → 不画浮层", Mode.NONE, IslandModel.chooseMode(false, false, true));
        assertEquals(Mode.NONE, IslandModel.chooseMode(false, true, false));
        assertEquals("只剩两种形态", 2, Mode.values().length);
        assertEquals("默认（未选形态前）不画浮层", Mode.NONE, new IslandModel().mode());
        assertEquals("没有悬浮窗权限 → 只用面板", Mode.NONE, IslandModel.chooseMode(true, false, false));
    }

    @Test public void noOverlayNeverCollapsesAndModeLossRestoresSheet() {
        IslandModel m = running(Mode.NONE);
        assertTrue(m.requestCollapse(CollapseReason.OUTSIDE_TAP).isEmpty());
        IslandModel m2 = collapsed(Mode.ISLAND);
        assertEquals(Arrays.asList(Effect.RENDER, Effect.ATTACH_SHEET), m2.setMode(Mode.NONE));
        assertEquals(Sheet.EXPANDED, m2.sheet());
    }

    // ------------------------------------------------------------------ 收起 / 轻提醒 / 关键提醒

    @Test public void collapseThenStepPulseResetsAndEnds() {
        IslandModel m = running(Mode.ISLAND);
        IslandModel.Timer ac = timer(m, m.drainTimers(), "autoCollapse");
        assertEquals(IslandModel.AUTO_COLLAPSE_MS, ac.delayMs);
        assertEquals(Arrays.asList(Effect.DETACH_SHEET, Effect.RENDER), due(m, ac));
        timer(m, m.drainTimers(), "maxAge");
        assertEquals(Kind.COMPACT_TASK, m.view().kind);
        m.step("正在：打开滴滴出行");
        IslandModel.Timer p1 = timer(m, m.drainTimers(), "pulse");
        assertEquals(IslandModel.STEP_PULSE_MS, p1.delayMs);
        assertEquals(Kind.PULSE_STEP, m.view().kind);
        assertEquals("打开滴滴出行", m.view().pulseText);
        m.step("设置目的地");
        IslandModel.Timer p2 = timer(m, m.drainTimers(), "pulse");
        assertTrue("连续步骤：旧计时作废", due(m, p1).isEmpty());
        assertEquals(Kind.PULSE_STEP, m.view().kind);
        assertEquals(Arrays.asList(Effect.RENDER), due(m, p2));
        assertEquals(Kind.COMPACT_TASK, m.view().kind);
        assertEquals(2, m.view().stepNo);
    }

    @Test public void keyAlertFourSecondsAndRecallsTuck() {
        IslandModel m = collapsed(Mode.ISLAND);
        assertEquals(Arrays.asList(Effect.RENDER), m.tuck());
        IslandModel.Timer tk = timer(m, m.drainTimers(), "tuck");
        assertEquals(IslandModel.TUCK_MS, tk.delayMs);
        assertEquals(Kind.HIDDEN, m.view().kind);
        assertTrue(m.view().tucked);
        m.keyUpdate("司机已接单");
        IslandModel.Timer k = timer(m, m.drainTimers(), "key");
        assertEquals(IslandModel.KEY_ALERT_MS, k.delayMs);
        assertFalse("关键事件召回暂避", m.isTucked());
        assertEquals(Kind.ALERT_KEY, m.view().kind);
        assertEquals("司机已接单", m.view().alertText);
        assertTrue("旧暂避计时作废", due(m, tk).isEmpty());
        due(m, k);
        assertEquals(Kind.COMPACT_TASK, m.view().kind);
    }

    @Test public void tuckExpiresAfter60s() {
        IslandModel m = collapsed(Mode.ISLAND);
        m.tuck();
        IslandModel.Timer tk = timer(m, m.drainTimers(), "tuck");
        assertEquals(Arrays.asList(Effect.RENDER), due(m, tk));
        assertEquals(Kind.COMPACT_TASK, m.view().kind);
    }

    // ------------------------------------------------------------------ 确认（立即展开、不可收起 / 暂避）

    @Test public void confirmExpandsImmediatelyAndCannotBeHidden() {
        assertEquals(0L, IslandModel.CONFIRM_EXPAND_MS);
        IslandModel m = collapsed(Mode.ISLAND);
        m.tuck();
        assertEquals(Arrays.asList(Effect.RENDER), m.confirmRequired("发送短信给 妈妈", "约 28 分钟到"));
        assertEquals("立即展开", Kind.EXP_CONFIRM, m.view().kind);
        assertFalse("召回暂避", m.isTucked());
        assertTrue(m.collapseExpanded(CollapseReason.OUTSIDE_TAP).isEmpty());
        assertTrue(m.collapseExpanded(CollapseReason.SWIPE_UP).isEmpty());
        assertTrue("待确认时不能暂避", m.tuck().isEmpty());
        assertTrue(m.setFullscreen(true).size() == 1);
        assertEquals("全屏也放行确认卡", Kind.EXP_CONFIRM, m.view().kind);
        assertEquals(Arrays.asList(Effect.RESOLVE_CONFIRM_ALLOW), m.islandConfirm(true));
        m.confirmResolved();
        assertEquals("全屏下普通运行态隐藏", Kind.HIDDEN, m.view().kind);
        m.setFullscreen(false);
        assertEquals(Kind.COMPACT_TASK, m.view().kind);
    }

    @Test public void confirmWithoutOverlayStaysOnSheet() {
        // island.off：任务从不收起，确认卡就在面板上（宿主照旧渲染）；岛收起中途关岛 → 挂回面板且确认卡跟着回面板
        IslandModel m = running(Mode.NONE);
        assertTrue(m.confirmRequired("发短信", null).isEmpty());
        assertEquals(Sheet.EXPANDED, m.sheet());
        assertTrue(m.isConfirmPending());
        IslandModel c = collapsed(Mode.ISLAND);
        c.confirmRequired("发短信", null);
        assertEquals(Arrays.asList(Effect.RENDER, Effect.ATTACH_SHEET), c.setMode(Mode.NONE));
        assertEquals(Sheet.EXPANDED, c.sheet());
        assertTrue("确认仍待裁决（在面板上）", c.isConfirmPending());
        IslandModel e = running(Mode.ISLAND);
        assertTrue("面板展开着：面板上就有确认卡", e.confirmRequired().isEmpty());
        assertFalse(e.canCollapse());
    }

    // ------------------------------------------------------------------ 完成 / 失败 / 结果文字 / 8 h

    @Test public void doneShowsThreeSecondsThenHides() {
        IslandModel m = collapsed(Mode.ISLAND);
        assertEquals(Arrays.asList(Effect.RENDER), m.taskEnded(false, false, true, false, null, 5000L));
        IslandModel.Timer t = timer(m, m.drainTimers(), "doneHide");
        assertEquals(3000L, t.delayMs);
        assertEquals(Status.DONE, m.view().status);
        assertTrue("完成态点按不展开", m.longPress().isEmpty());
        due(m, t);
        assertEquals(Kind.HIDDEN, m.view().kind);
    }

    @Test public void failExpandsSixSecondsThenCompactFiveMinutes() {
        IslandModel m = collapsed(Mode.ISLAND);
        m.taskEnded(false, false, false, false, "叫车超时", 5000L);
        List<IslandModel.Timer> ts = m.drainTimers();
        IslandModel.Timer fc = timer(m, ts, "failCollapse"), fl = timer(m, ts, "failLinger");
        assertEquals(6000L, fc.delayMs);
        assertEquals(300000L, fl.delayMs);
        assertEquals(Kind.EXP_FAIL, m.view().kind);
        due(m, fc);
        assertEquals(Kind.COMPACT_TASK, m.view().kind);
        assertEquals(Status.FAIL, m.view().status);
        assertEquals(Arrays.asList(Effect.RENDER, Effect.END_SESSION), due(m, fl));
        assertEquals(Kind.HIDDEN, m.view().kind);
    }

    @Test public void failRetryAndClose() {
        IslandModel m = collapsed(Mode.ISLAND);
        m.taskEnded(false, false, false, false, "x", 5000L);
        assertEquals(Arrays.asList(Effect.RENDER, Effect.RETRY_TASK), m.retry());
        IslandModel m2 = collapsed(Mode.ISLAND);
        m2.taskEnded(false, false, false, false, "x", 5000L);
        assertEquals(Arrays.asList(Effect.RENDER, Effect.END_SESSION), m2.closeFailed());
    }

    @Test public void resultTextReattachesSheetAndCancelHides() {
        IslandModel m = collapsed(Mode.ISLAND);
        assertEquals(Arrays.asList(Effect.ATTACH_SHEET, Effect.RENDER), m.taskEnded(false, false, true, true, null, 5000L));
        IslandModel c = collapsed(Mode.ISLAND);
        assertEquals(Arrays.asList(Effect.RENDER), c.taskEnded(true, false, false, false, null, 5000L));
        assertEquals(Kind.HIDDEN, c.view().kind);
    }

    @Test public void maxActivityEightHoursReturnsToSheet() {
        IslandModel m = running(Mode.ISLAND);
        due(m, timer(m, m.drainTimers(), "autoCollapse"));
        IslandModel.Timer ma = timer(m, m.drainTimers(), "maxAge");
        assertEquals(8L * 3600L * 1000L, ma.delayMs);
        assertEquals(Arrays.asList(Effect.ATTACH_SHEET, Effect.RENDER), due(m, ma));
        assertTrue("任务没被取消", m.isRunning());
        assertEquals(Kind.HIDDEN, m.view().kind);
    }

    // ------------------------------------------------------------------ 手势

    @Test public void tapOpensAppLongPressExpandsSwipeUpCollapses() {
        IslandModel m = collapsed(Mode.ISLAND);
        assertEquals(Arrays.asList(Effect.OPEN_APP_TASK), m.tapMain());
        assertEquals("点按不改变视图", Kind.COMPACT_TASK, m.view().kind);
        assertEquals(Arrays.asList(Effect.RENDER), m.longPress());
        assertEquals(Kind.EXP_RUN, m.view().kind);
        assertEquals(Arrays.asList(Effect.RENDER), m.collapseExpanded(CollapseReason.SWIPE_UP));
        assertEquals(Kind.COMPACT_TASK, m.view().kind);
    }

    @Test public void stopAndNewWakeCancelOnlyForegroundTask() {
        IslandModel m = collapsed(Mode.ISLAND);
        m.bgStarted("a1", "读邮件", 2000L);
        m.remindFired("r1", "交周报", 3000L, 0, 3000L);
        assertEquals(Arrays.asList(Effect.RENDER, Effect.CANCEL_TASK), m.newWake());
        assertTrue("后台任务不受新唤醒影响", m.hasBg("a1"));
        assertTrue("提醒不受新唤醒影响", m.hasBg("r1"));
        IslandModel s = collapsed(Mode.ISLAND);
        assertEquals(Arrays.asList(Effect.RENDER, Effect.CANCEL_TASK), s.stop());
    }

    // ------------------------------------------------------------------ 全屏

    @Test public void fullscreenHidesAllButKeyEvents() {
        IslandModel m = collapsed(Mode.ISLAND);
        m.longPress();
        m.setFullscreen(true);
        assertEquals("进入全屏：普通展开卡先收起并隐藏", Kind.HIDDEN, m.view().kind);
        assertTrue(m.view().fullscreen);
        m.keyUpdate("已接单");
        assertEquals(Kind.ALERT_KEY, m.view().kind);
        m.collapseExpanded(CollapseReason.OUTSIDE_TAP);
        assertEquals(Kind.HIDDEN, m.view().kind);
        m.taskEnded(false, false, false, false, "超时", 5000L);
        assertEquals("失败是关键事件", Kind.EXP_FAIL, m.view().kind);
        m.setFullscreen(false);
        assertEquals(Kind.EXP_FAIL, m.view().kind);
    }

    // ------------------------------------------------------------------ S3：提醒 / 后台任务 / 待办

    @Test public void reminderAlertsThenCompactThenExpires() {
        IslandModel m = model(Mode.ISLAND);
        m.remindFired("r1", "交周报", 1000L, 0, 1000L);
        List<IslandModel.Timer> ts = m.drainTimers();
        IslandModel.Timer ra = timer(m, ts, "remindAlert"), re = timer(m, ts, "remindExpire");
        assertEquals(3600L * 1000L, re.delayMs);
        assertEquals(Kind.EXP_REMIND, m.view().kind);
        assertEquals("交周报", m.view().title);
        due(m, ra);
        assertEquals(Kind.COMPACT_REMIND, m.view().kind);
        due(m, re);
        assertEquals(Kind.HIDDEN, m.view().kind);
    }

    @Test public void reminderDoneSnoozeAndLate() {
        IslandModel m = model(Mode.ISLAND);
        m.remindFired("r1", "取快递", 1000L, 25, 1000L + 25 * 60000L);
        assertEquals(25, m.view().lateMin);
        assertEquals(Arrays.asList(Effect.REMIND_DONE, Effect.RENDER), m.remindDone(null));
        assertEquals("r1", m.lastTarget());
        assertEquals(Kind.HIDDEN, m.view().kind);
        m.remindFired("r2", "喝水", 1000L, 0, 1000L);
        assertEquals(Arrays.asList(Effect.REMIND_SNOOZE, Effect.RENDER), m.remindSnooze(null));
        assertEquals("r2", m.lastTarget());
        assertEquals(0, IslandModel.lateMinutes(1000L, 1000L + 10L * 60000L));
        assertEquals(11, IslandModel.lateMinutes(1000L, 1000L + 11L * 60000L));
    }

    @Test public void bgAgentRunConfirmTimeoutNeverAutoAllows() {
        IslandModel m = model(Mode.ISLAND);
        m.bgStarted("a1", "读一下QQ邮箱未读", 1000L);
        assertEquals(Kind.COMPACT_TASK, m.view().kind);
        assertEquals(Act.AGENT, m.view().act);
        m.bgStep("a1", "正在：读取邮件");
        assertEquals(1, m.view().stepNo);
        m.bgConfirm("a1", "发送短信给 妈妈", "邮件摘要");
        assertEquals(Kind.EXP_CONFIRM, m.view().kind);
        assertTrue(m.collapseExpanded(CollapseReason.OUTSIDE_TAP).isEmpty());
        assertEquals(10L * 60L * 1000L, IslandModel.BG_CONFIRM_TIMEOUT_MS);
        assertEquals(Arrays.asList(Effect.BG_CONFIRM_DENY), m.islandConfirm(false));
        assertEquals("a1", m.lastTarget());
        // 宿主超时 / 拒绝后：回到运行，再以失败收尾（模型里没有任何自动允许的路径）
        m.bgConfirmResolved("a1");
        assertEquals(Status.RUN, m.bgStatus("a1"));
        m.bgEnded("a1", false, "确认超时，已安全中止", 9000L);
        assertEquals(Kind.EXP_FAIL, m.view().kind);
        assertEquals(Arrays.asList(Effect.RENDER), m.closeFailed());
        assertFalse(m.hasBg("a1"));
    }

    @Test public void bgDoneRemovedAfterThreeSeconds() {
        IslandModel m = model(Mode.ISLAND);
        m.bgStarted("a1", "读邮件", 1000L);
        m.bgEnded("a1", true, null, 2000L);
        IslandModel.Timer t = timer(m, m.drainTimers(), "bgDone");
        assertEquals(3000L, t.delayMs);
        assertEquals(Status.DONE, m.view().status);
        due(m, t);
        assertFalse(m.hasBg("a1"));
        assertEquals(Kind.HIDDEN, m.view().kind);
    }

    @Test public void bgStopFromExpandedCard() {
        IslandModel m = model(Mode.ISLAND);
        m.bgStarted("a1", "读邮件", 1000L);
        m.longPress();
        assertEquals(Kind.EXP_RUN, m.view().kind);
        assertEquals(Arrays.asList(Effect.BG_STOP), m.stop());
        assertEquals("a1", m.lastTarget());
    }

    @Test public void todoChipOnlyWhenDueTodayAndLongPressList() {
        IslandModel m = model(Mode.ISLAND);
        List<IslandModel.TodoRow> rows = new ArrayList<IslandModel.TodoRow>();
        for (int i = 0; i < 7; i++) rows.add(new IslandModel.TodoRow("t" + i, "待办" + i, 1000L + i));
        m.setTodos(rows, 0);
        assertEquals("今天没有到期的：空闲时岛保持隐藏", Kind.HIDDEN, m.view().kind);
        m.setTodos(rows, 2);
        assertEquals(Kind.COMPACT_TODO, m.view().kind);
        assertEquals(2, m.view().todoCount);
        assertEquals(Arrays.asList(Effect.OPEN_APP_TODO), m.tapMain());
        assertEquals(Arrays.asList(Effect.RENDER), m.longPress());
        assertEquals(Kind.EXP_TODO, m.view().kind);
        assertEquals("最多 5 条", 5, m.view().todos.size());
        assertEquals(Arrays.asList(Effect.TODO_CHECK), m.todoCheck("t1"));
        assertEquals("t1", m.lastTarget());
        assertTrue(m.todoCheck("t6").isEmpty());
        m.setTodoChipEnabled(false);
        m.collapseExpanded(CollapseReason.OUTSIDE_TAP);
        assertEquals("计数块关掉 → 空闲隐藏", Kind.HIDDEN, m.view().kind);
        // 有活动时，计数块让位
        m.setTodoChipEnabled(true);
        m.bgStarted("a1", "读邮件", 1000L);
        assertEquals(Kind.COMPACT_TASK, m.view().kind);
    }

    // ------------------------------------------------------------------ 多任务优先级

    @Test public void priorityConfirmOverRunOverRemindOverPickup() {
        assertTrue(IslandModel.rank(Act.AGENT, Status.WAIT) < IslandModel.rank(Act.TASK, Status.RUN));
        assertTrue(IslandModel.rank(Act.TASK, Status.RUN) < IslandModel.rank(Act.REMIND, Status.NONE));
        assertTrue(IslandModel.rank(Act.REMIND, Status.NONE) < IslandModel.rank(Act.PICKUP, Status.NONE));
        IslandModel m = model(Mode.ISLAND);
        m.setPickups(Arrays.asList(pk("p1", "5-2-1234", 100L)));
        assertEquals(Kind.COMPACT_PICKUP, m.view().kind);
        m.remindFired("r1", "交周报", 1000L, 0, 1000L);
        m.timerDue("remindAlert", 1, "r1");
        m.collapseExpanded(CollapseReason.OUTSIDE_TAP);
        IslandModel.Snapshot v = m.view();
        assertEquals(Kind.MINIMAL_DUAL, v.kind);
        assertEquals(Act.REMIND, v.act);
        assertEquals(Act.PICKUP, v.bubble);
        assertEquals("码尾", "1234", v.bubbleText);
        m.bgStarted("a1", "读邮件", 1000L);
        v = m.view();
        assertEquals("运行中 > 提醒 > 取件码：取前两个", Act.AGENT, v.act);
        assertEquals(Act.REMIND, v.bubble);
        m.bgConfirm("a2", "x", null);   // 不存在的 id 无效
        m.bgStarted("a2", "发短信", 1100L);
        m.bgConfirm("a2", "发送短信给 妈妈", null);
        assertEquals(Kind.EXP_CONFIRM, m.view().kind);
        m.bgConfirmResolved("a2");
        v = m.view();
        assertEquals(Kind.MINIMAL_DUAL, v.kind);
        assertEquals(2, m.visibleKeys().indexOf("r1"));
    }

    @Test public void foregroundPlusBackgroundDual() {
        IslandModel m = collapsed(Mode.ISLAND);
        m.bgStarted("a1", "读邮件", 1000L);
        IslandModel.Snapshot v = m.view();
        assertEquals(Kind.MINIMAL_DUAL, v.kind);
        assertEquals("同级：前台任务在前", Act.TASK, v.act);
        assertEquals(Act.AGENT, v.bubble);
        assertEquals(Arrays.asList(Effect.OPEN_APP_TASK), m.tap(v.bubbleKey));
        assertEquals("a1", m.lastTarget());
    }

    @Test public void noOverlayShowsNothing() {
        IslandModel m = model(Mode.NONE);
        m.remindFired("r1", "交周报", 1000L, 0, 1000L);
        m.setPickups(Arrays.asList(pk("p1", "5-2-1234", 100L)));
        assertEquals(Kind.HIDDEN, m.view().kind);
        m.drainTimers();
        m.taskStarted(2000L, "查邮件");
        for (IslandModel.Timer t : m.drainTimers()) assertFalse("不画浮层时不排自动收起", "autoCollapse".equals(t.name));
        assertTrue(m.requestCollapse(CollapseReason.AUTO).isEmpty());
        assertEquals(Kind.HIDDEN, m.view().kind);
    }

    @Test public void pickupPagingCopyDismissOnIsland() {
        IslandModel m = model(Mode.ISLAND);
        m.setPickups(Arrays.asList(pk("p1", "5-2-1234", 100L), pk("p2", "83920417", 200L)));
        assertEquals("最新一张", "p2", m.currentPickupId());
        assertEquals(Arrays.asList(Effect.OPEN_APP_PICKUP), m.tapMain());
        m.longPress();
        assertEquals(Kind.EXP_PICKUP, m.view().kind);
        assertEquals(Arrays.asList(Effect.RENDER), m.pagePickup(-1));
        assertEquals("p1", m.currentPickupId());
        assertEquals(Arrays.asList(Effect.COPY_PICKUP), m.copyPickup());
        m.markCopied("p1");
        assertTrue(m.view().copied);
        IslandModel.Timer t = timer(m, m.drainTimers(), "copiedReset");
        due(m, t);
        assertFalse(m.view().copied);
        assertEquals(Arrays.asList(Effect.DISMISS_PICKUP), m.dismissPickup());
    }

    @Test public void pickupExpiryAndDedupeThroughStore() {
        long t0 = 1_000_000L, ttl = 12L * 3600L * 1000L;
        PickupCardStore store = new PickupCardStore();
        assertTrue(store.add(new PickupCodeDetector.Pickup("5-2-1234", "取件码", "菜鸟驿站"), t0, ttl) != null);
        assertNull("同码去重", store.add(new PickupCodeDetector.Pickup("5-2-1234", "取件码", "菜鸟驿站"), t0 + 5, ttl));
        for (int i = 0; i < 6; i++) store.add(new PickupCodeDetector.Pickup("A" + i, "取餐码", "瑞幸"), t0 + 10 + i, ttl);
        assertEquals("最多存 5", PickupCardStore.MAX_CARDS, store.size());
        IslandModel m = model(Mode.ISLAND);
        m.setPickups(toIsland(store.active(t0 + 100)));
        assertEquals(3, m.view().pickupCount);
        // 关闭后同码不再出现
        String id = store.active(t0 + 100).get(4).id;
        store.dismiss(id);
        assertNull(store.add(new PickupCodeDetector.Pickup("A5", "取餐码", "瑞幸"), t0 + 200, ttl));
        m.setPickups(toIsland(store.active(t0 + 200)));
        assertFalse(id.equals(m.currentPickupId()));
        // 12 h 后全部过期 → 岛收回
        assertTrue(store.active(t0 + ttl + 20).isEmpty());
        m.setPickups(toIsland(store.active(t0 + ttl + 20)));
        assertEquals(Kind.HIDDEN, m.view().kind);
        // 验证码短信根本到不了仓库
        assertTrue(PickupCodeDetector.detect("【某银行】您的验证码为482913，5分钟内有效，请勿泄露。", t0,
                java.util.TimeZone.getTimeZone("Asia/Shanghai")).isEmpty());
    }

    static List<IslandModel.Pickup> toIsland(List<PickupCardStore.Card> cards) {
        List<IslandModel.Pickup> r = new ArrayList<IslandModel.Pickup>();
        for (PickupCardStore.Card c : cards) r.add(new IslandModel.Pickup(c.id, c.kind, c.code, c.station, "短信", c.createdAt, c.expiresAt));
        return r;
    }

    // ------------------------------------------------------------------ 其它纯函数

    @Test public void gestureAndTextHelpers() {
        assertTrue(IslandModel.isTap(3f, -4f, 200L, 10f));
        assertFalse(IslandModel.isTap(30f, 0f, 200L, 10f));
        assertFalse("450ms 起算长按", IslandModel.isTap(0f, 0f, 500L, 10f));
        assertTrue(IslandModel.isSwipeSide(70f, 10f, 63f));
        assertFalse(IslandModel.isSwipeSide(70f, 90f, 63f));
        assertTrue(IslandModel.isSwipeUp(5f, -70f, 63f));
        assertEquals("打开滴滴", IslandModel.actionWord("正在：打开滴滴出行", "x"));
        assertEquals("打车去公", IslandModel.actionWord(null, "打车去公司，到了告诉妈妈"));
    }
}
