package com.onesay.assistant.v2.notice;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-10-23 BUG-I1：日程来源拉取失败 / 连接器缺失时，撤下这个来源所有没处理的提醒（没展示的 + 已在岛上的），
 * 别的来源不动；来源恢复后同键回来（没过期的才回来，不重复计数、不再自动展开）。
 */
public class NoticeWithdrawTest {

    static final long MIN = 60000L;

    long now;
    NoticeStore.Mem store;
    NoticeCenter c;
    NoticePolicy.Env env;

    @Before public void setUp() {
        now = NoticeCenterTest.at(10, 0);
        store = new NoticeStore.Mem();
        c = new NoticeCenter(store, new NoticeCenter.Clock() {
            @Override public long now() { return now; }
        });
        env = new NoticePolicy.Env();
        env.tz = NoticeCenterTest.TZ;
    }

    static IslandNotice cal(String key, long showAt, long expireAt) {
        IslandNotice n = new IslandNotice(IslandNotice.Source.CALENDAR, key, IslandNotice.Priority.NORMAL, "会 " + key, "");
        n.sourceTag = CalendarNotices.TAG;
        n.showAt = showAt;
        n.expireAt = expireAt;
        return n;
    }

    static IslandNotice cb(String key) {
        IslandNotice n = new IslandNotice(IslandNotice.Source.CALLBACK, key, IslandNotice.Priority.NORMAL, "回调 " + key, "");
        n.sourceTag = "callback";
        return n;
    }

    static List<String> keys(List<IslandNotice> l) {
        List<String> k = new ArrayList<String>();
        for (IslandNotice x : l) k.add(x.dedupKey);
        java.util.Collections.sort(k);
        return k;
    }

    /** 已展示的、还没展示的日程提醒都撤；回调、别的扩展来源、用户处理过的不动。 */
    @Test public void withdrawClearsShownAndPendingOfThatSourceOnly() {
        IslandNotice shown = cal("cal:a:1", now, now + 30 * MIN);
        IslandNotice later = cal("cal:b:1", now + 20 * MIN, now + 60 * MIN);
        IslandNotice done = cal("cal:c:1", now, now + 30 * MIN);
        c.sync(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, Arrays.asList(shown, later, done), env);
        c.dismiss("cal:c:1", null);
        c.post(cb("cb:1"), env, null);
        IslandNotice other = new IslandNotice(IslandNotice.Source.EXTERNAL, "ext:1", IslandNotice.Priority.NORMAL, "快递", "");
        other.sourceTag = "parcel";
        c.post(other, env, null);
        assertEquals(Arrays.asList("cal:a:1", "cb:1", "ext:1"), keys(c.active()));
        assertEquals(2, c.liveCount(IslandNotice.Source.CALENDAR, CalendarNotices.TAG));

        NoticeCenter.Tick t = c.withdrawSource(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, NoticeCenter.NOTE_SOURCE_LOST);
        assertEquals("岛上那条撤下（交给岛刷新）", Arrays.asList("cal:a:1"), keys(t.gone));
        assertEquals("别的来源不动", Arrays.asList("cb:1", "ext:1"), keys(c.active()));
        assertEquals(IslandNotice.State.WITHDRAWN, c.get("cal:a:1").state);
        assertEquals("没展示的也撤", IslandNotice.State.WITHDRAWN, c.get("cal:b:1").state);
        assertEquals(NoticeCenter.NOTE_SOURCE_LOST, c.get("cal:b:1").note);
        assertEquals("用户处理过的保持原状态", IslandNotice.State.DISMISSED, c.get("cal:c:1").state);
        assertEquals(0, c.liveCount(IslandNotice.Source.CALENDAR, CalendarNotices.TAG));
        assertEquals("撤下的不叫醒（只剩别的来源的过期）", Math.min(c.get("cb:1").expireAt, c.get("ext:1").expireAt), c.nextWakeAt());
        assertEquals("持久化", IslandNotice.State.WITHDRAWN, store.get("cal:b:1").state);
        assertFalse(t.alert);
    }

    /** tag 不同的同类来源不受影响（扩展来源也能用这个口子）。 */
    @Test public void withdrawRespectsTag() {
        IslandNotice a = cal("cal:a:1", now, now + 30 * MIN);
        IslandNotice x = cal("cal:x:1", now, now + 30 * MIN);
        x.sourceTag = "work-cal";
        c.post(a, env, null);
        c.post(x, env, null);
        c.withdrawSource(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, null);
        assertEquals(Arrays.asList("cal:x:1"), keys(c.active()));
    }

    /** 来源恢复（下一次 poll 成功）：没过期的同键回到岛上；首次出现时刻保留（每日上限不重复计），不再自动展开。 */
    @Test public void sourceBackRevivesWithoutAlertOrDoubleCount() {
        env.caps.put(IslandNotice.Source.CALENDAR, 1);
        IslandNotice a = cal("cal:a:1", now, now + 30 * MIN);
        NoticeCenter.Tick first = c.sync(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, Arrays.asList(a), env);
        assertTrue(first.alert);
        long shownAt = c.get("cal:a:1").shownAt;
        c.withdrawSource(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, null);
        now += 5 * MIN;
        NoticeCenter.Tick back = c.sync(IslandNotice.Source.CALENDAR, CalendarNotices.TAG,
                Arrays.asList(cal("cal:a:1", now - 5 * MIN, now + 25 * MIN)), env);
        assertEquals(IslandNotice.State.ACTIVE, c.get("cal:a:1").state);
        assertEquals("首次出现时刻不变", shownAt, c.get("cal:a:1").shownAt);
        assertEquals(Arrays.asList("cal:a:1"), keys(back.surfaced));
        assertFalse("回来不再自动展开", back.alert);
        assertFalse(back.high);
        // 上限 1：回来的那条不占新名额（否则会被记 SUPPRESSED）
        assertFalse(IslandNotice.State.SUPPRESSED == c.get("cal:a:1").state);
    }

    /** 撤下期间已经过期的不补发；用户忽略过的同键仍然不出现。 */
    @Test public void expiredOrDismissedStayGone() {
        IslandNotice a = cal("cal:a:1", now, now + 10 * MIN);
        IslandNotice d = cal("cal:d:1", now, now + 60 * MIN);
        c.sync(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, Arrays.asList(a, d), env);
        c.dismiss("cal:d:1", null);
        c.withdrawSource(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, null);
        now += 15 * MIN;
        assertEquals(NoticeCenter.PostResult.DROP_EXPIRED, c.post(cal("cal:a:1", now - 15 * MIN, now - 5 * MIN), env, null));
        assertEquals(NoticeCenter.PostResult.DUP_IGNORED, c.post(cal("cal:d:1", now - 15 * MIN, now + 45 * MIN), env, null));
        assertTrue(c.active().isEmpty());
    }

    /** 撤下的状态能落库 / 读回（重启后不会被当成在排的恢复出来）。 */
    @Test public void withdrawnSurvivesRestoreButNotAsLive() {
        c.post(cal("cal:a:1", now, now + 30 * MIN), env, null);
        c.withdrawSource(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, null);
        IslandNotice back = IslandNotice.fromJson(store.get("cal:a:1").toJson());
        assertEquals(IslandNotice.State.WITHDRAWN, back.state);
        c.dropMemory();
        NoticeCenter.Tick t = c.restore(env);
        assertTrue(c.active().isEmpty());
        assertTrue(t.surfaced.isEmpty());
        assertEquals(IslandNotice.State.WITHDRAWN, c.get("cal:a:1").state);
    }

    /** Android 胶水：日程来源打了标记；只有 Unavailable（连接器缺失 / 未登录 / 授权失效）→ withdrawUnavailable；开关关着时不归它管；连接器后来掉了也会被叫醒去撤。 */
    @Test public void hooksWireCalendarWithdraw() throws Exception {
        String src = NoticeSourcesTest.read("app/src/com/onesay/assistant/v2/notice/NoticeHooks.java");
        assertTrue(src.contains("class CalendarSource implements NoticeSource, NoticeSource.WithdrawWhenUnavailable"));
        assertFalse("定时邮件来源不撤（本地扫描失败 = 库读不到，保持原约定）",
                src.contains("class MailSource implements NoticeSource, NoticeSource.WithdrawWhenUnavailable"));
        String sync = src.substring(src.indexOf("static void syncSource"), src.indexOf("static void withdrawUnavailable"));
        assertTrue(sync.contains("catch (NoticeSource.Unavailable u) {") && sync.contains("withdrawUnavailable(app, s, u.getMessage());"));
        assertEquals("withdrawUnavailable 只在 Unavailable 分支调用", 1, sync.split("withdrawUnavailable\\(", -1).length - 1);
        String w = src.substring(src.indexOf("static void withdrawUnavailable"), src.indexOf("// ================================================================== 上岛"));
        assertTrue(w.contains("instanceof NoticeSource.WithdrawWhenUnavailable"));
        assertTrue("来源开关关着 → 交给 tick", w.contains("if (!NoticePolicy.sourceOn(env, s.kind(), s.id())) return;"));
        assertTrue(w.contains("withdrawSource(s.kind(), s.id(), NoticeCenter.NOTE_SOURCE_LOST)") && w.contains("present(app, t);"));
        assertFalse("不弹错误 / 通知", w.contains("notify(") || w.contains("Toast"));
        String cal = src.substring(src.indexOf("class CalendarSource"), src.indexOf("class MailSource"));
        assertTrue("失败后 2 分钟重试", cal.contains("mLastFailed ? RETRY_MS : POLL_MS"));
        assertTrue("calclear 走同一条路", src.substring(src.indexOf("public static void clearFakeEvents")).contains("syncSource(app, sCal);"));
    }

    @Test public void policySourceOnByKind() {
        assertTrue(NoticePolicy.sourceOn(env, IslandNotice.Source.CALENDAR, CalendarNotices.TAG));
        env.disabled.add(IslandNotice.Source.CALENDAR);
        assertFalse(NoticePolicy.sourceOn(env, IslandNotice.Source.CALENDAR, CalendarNotices.TAG));
        env.disabled.clear();
        env.disabledTags.add(CalendarNotices.TAG);
        assertFalse(NoticePolicy.sourceOn(env, IslandNotice.Source.CALENDAR, CalendarNotices.TAG));
        env.disabledTags.clear();
        env.master = false;
        assertFalse(NoticePolicy.sourceOn(env, IslandNotice.Source.CALLBACK, null));
    }
}
