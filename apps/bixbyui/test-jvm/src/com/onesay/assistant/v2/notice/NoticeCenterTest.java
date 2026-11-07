package com.onesay.assistant.v2.notice;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 提醒中枢：队列 / 优先级、去重、过期、免打扰 / 通话中、每日上限、重启恢复、动作。 */
public class NoticeCenterTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static final long MIN = 60000L;

    long now;
    NoticeStore.Mem store;
    NoticeCenter c;
    NoticePolicy.Env env;

    static long at(int h, int m) {
        Calendar cal = Calendar.getInstance(TZ);
        cal.clear();
        cal.set(2026, Calendar.OCTOBER, 23, h, m, 0);
        return cal.getTimeInMillis();
    }

    @Before public void setUp() {
        now = at(10, 0);
        store = new NoticeStore.Mem();
        c = new NoticeCenter(store, new NoticeCenter.Clock() {
            @Override public long now() { return now; }
        });
        env = new NoticePolicy.Env();
        env.tz = TZ;
    }

    static IslandNotice n(String key, IslandNotice.Priority p) {
        IslandNotice x = new IslandNotice(IslandNotice.Source.CALLBACK, key, p, "标题 " + key, "摘要");
        x.action(new NoticeAction("知道了", NoticeAction.Kind.DISMISS, ""));
        return x;
    }

    static List<String> keys(List<IslandNotice> l) {
        List<String> k = new ArrayList<String>();
        for (IslandNotice x : l) k.add(x.dedupKey);
        return k;
    }

    // ------------------------------------------------------------------ 队列和优先级

    @Test public void queueOrderedByPriorityThenArrival() {
        NoticeCenter.Tick t = new NoticeCenter.Tick();
        assertEquals(NoticeCenter.PostResult.SHOWN, c.post(n("a", IslandNotice.Priority.NORMAL), env, t));
        now += MIN;
        c.post(n("b", IslandNotice.Priority.LOW), env, t);
        now += MIN;
        c.post(n("c", IslandNotice.Priority.HIGH), env, t);
        now += MIN;
        c.post(n("d", IslandNotice.Priority.NORMAL), env, t);
        assertEquals("高优先级在前；同级先来的在前（队首稳定）", Arrays.asList("c", "a", "d", "b"), keys(c.active()));
        assertTrue(t.alert);
        assertTrue("新出现的里有高优先级", t.high);
    }

    @Test public void futureShowAtWaitsUntilDue() {
        IslandNotice x = n("later", IslandNotice.Priority.NORMAL);
        x.showAt = now + 10 * MIN;
        assertEquals(NoticeCenter.PostResult.SCHEDULED, c.post(x, env, null));
        assertTrue(c.active().isEmpty());
        assertEquals("闹钟按 showAt 叫醒", now + 10 * MIN, c.nextWakeAt());
        now += 10 * MIN;
        NoticeCenter.Tick t = c.tick(env);
        assertEquals(1, t.surfaced.size());
        assertTrue(t.alert);
        assertEquals(Arrays.asList("later"), keys(c.active()));
    }

    // ------------------------------------------------------------------ 去重

    @Test public void dedupKeepsOneAndNeverReAlerts() {
        c.post(n("k", IslandNotice.Priority.NORMAL), env, null);
        IslandNotice again = n("k", IslandNotice.Priority.NORMAL);
        again.title = "新标题";
        NoticeCenter.Tick t = new NoticeCenter.Tick();
        assertEquals(NoticeCenter.PostResult.DUP_UPDATED, c.post(again, env, t));
        assertEquals("同一个去重键只有一条", 1, c.active().size());
        assertEquals("内容按新的", "新标题", c.active().get(0).title);
        assertFalse("去重更新不再提醒一次", t.alert);
        assertTrue(c.dismiss("k", null));
        assertEquals("忽略过的同一件事不再出现", NoticeCenter.PostResult.DUP_IGNORED, c.post(n("k", IslandNotice.Priority.NORMAL), env, null));
        assertTrue(c.active().isEmpty());
    }

    @Test public void dedupAfterExpiryIsNewNotice() {
        IslandNotice x = n("k", IslandNotice.Priority.NORMAL);
        x.expireAt = now + 5 * MIN;
        c.post(x, env, null);
        c.dismiss("k", null);
        now += 6 * MIN;
        IslandNotice y = n("k", IslandNotice.Priority.NORMAL);
        assertEquals("旧的已过期：同键当新的一条", NoticeCenter.PostResult.SHOWN, c.post(y, env, null));
    }

    // ------------------------------------------------------------------ 过期

    @Test public void expiryRemovesFromIsland() {
        IslandNotice x = n("e", IslandNotice.Priority.NORMAL);
        x.expireAt = now + 3 * MIN;
        c.post(x, env, null);
        assertEquals(now + 3 * MIN, c.nextWakeAt());
        now += 3 * MIN;
        NoticeCenter.Tick t = c.tick(env);
        assertEquals(1, t.gone.size());
        assertTrue(c.active().isEmpty());
        assertEquals(IslandNotice.State.EXPIRED, c.get("e").state);
        IslandNotice late = n("late", IslandNotice.Priority.NORMAL);
        late.expireAt = now - 1;
        assertEquals("已过期的不入队", NoticeCenter.PostResult.DROP_EXPIRED, c.post(late, env, null));
    }

    @Test public void defaultTtlFilledPerSource() {
        IslandNotice x = n("t", IslandNotice.Priority.NORMAL);
        c.post(x, env, null);
        assertEquals(now + NoticePolicy.defaultTtl(IslandNotice.Source.CALLBACK), c.get("t").expireAt);
    }

    // ------------------------------------------------------------------ 免打扰和通话中

    @Test public void quietHoursHoldUntilEnd() {
        now = at(23, 30);
        IslandNotice x = n("q", IslandNotice.Priority.HIGH);
        x.expireAt = at(9, 0) + 24 * 60 * MIN;
        assertEquals("免打扰 23–7：压着", NoticeCenter.PostResult.HELD_QUIET, c.post(x, env, null));
        assertTrue(c.active().isEmpty());
        long end = at(7, 0) + 24 * 60 * MIN;
        assertEquals("时段结束叫醒", end, c.nextWakeAt());
        now = at(6, 59) + 24 * 60 * MIN;
        assertTrue(c.tick(env).surfaced.isEmpty());
        now = end;
        NoticeCenter.Tick t = c.tick(env);
        assertEquals("7:00 放出来", 1, t.surfaced.size());
    }

    @Test public void quietHeldButExpiredIsDroppedSilently() {
        now = at(23, 30);
        IslandNotice x = n("q", IslandNotice.Priority.NORMAL);
        x.expireAt = at(23, 50);
        c.post(x, env, null);
        now = at(23, 50);
        NoticeCenter.Tick t = c.tick(env);
        assertTrue("没出现过就过期：不打扰也不补", t.surfaced.isEmpty() && t.gone.isEmpty());
        assertEquals(IslandNotice.State.EXPIRED, c.get("q").state);
    }

    @Test public void highCalendarExemptFromQuietConfigurable() {
        now = at(1, 0);
        IslandNotice cal = new IslandNotice(IslandNotice.Source.CALENDAR, "cal:x:1", IslandNotice.Priority.HIGH, "航班", "");
        assertEquals("高优先级日程不受免打扰限制", NoticeCenter.PostResult.SHOWN, c.post(cal, env, null));
        IslandNotice normal = new IslandNotice(IslandNotice.Source.CALENDAR, "cal:y:1", IslandNotice.Priority.NORMAL, "普通日程", "");
        assertEquals(NoticeCenter.PostResult.HELD_QUIET, c.post(normal, env, null));
        IslandNotice cbHigh = new IslandNotice(IslandNotice.Source.CALLBACK, "cb:z", IslandNotice.Priority.HIGH, "回调", "");
        assertEquals("例外只给日程", NoticeCenter.PostResult.HELD_QUIET, c.post(cbHigh, env, null));
        env.quietHighCalendar = false;
        IslandNotice cal2 = new IslandNotice(IslandNotice.Source.CALENDAR, "cal:w:1", IslandNotice.Priority.HIGH, "高铁", "");
        assertEquals("开关关掉：高优先级日程也压着", NoticeCenter.PostResult.HELD_QUIET, c.post(cal2, env, null));
        env.quietEnabled = false;
        assertEquals("免打扰总开关关：压着的都放出来", 3, c.tick(env).surfaced.size());
    }

    @Test public void inCallQueuesSilentlyNoAlert() {
        env.inCall = true;
        NoticeCenter.Tick t = new NoticeCenter.Tick();
        assertEquals(NoticeCenter.PostResult.SHOWN, c.post(n("call", IslandNotice.Priority.HIGH), env, t));
        assertEquals("通话中照样排队", 1, c.active().size());
        assertFalse("通话中静音：不自动展开", t.alert);
        env.inCall = false;
        env.dnd = true;
        NoticeCenter.Tick t2 = new NoticeCenter.Tick();
        c.post(n("dnd", IslandNotice.Priority.NORMAL), env, t2);
        assertFalse("勿扰模式同样静音", t2.alert);
    }

    // ------------------------------------------------------------------ 每日上限 / 开关

    @Test public void dailyCapPerSourcePerLocalDay() {
        env.caps.put(IslandNotice.Source.EXTERNAL, 2);
        for (int i = 0; i < 2; i++) {
            IslandNotice x = new IslandNotice(IslandNotice.Source.EXTERNAL, "x" + i, IslandNotice.Priority.NORMAL, "t", "");
            assertEquals(NoticeCenter.PostResult.SHOWN, c.post(x, env, null));
        }
        IslandNotice third = new IslandNotice(IslandNotice.Source.EXTERNAL, "x2", IslandNotice.Priority.NORMAL, "t", "");
        assertEquals("今天次数已满", NoticeCenter.PostResult.DROP_CAP, c.post(third, env, null));
        assertEquals("别的来源不受影响", NoticeCenter.PostResult.SHOWN, c.post(n("cb", IslandNotice.Priority.NORMAL), env, null));
        now = at(8, 0) + 24 * 60 * MIN;
        IslandNotice tomorrow = new IslandNotice(IslandNotice.Source.EXTERNAL, "x3", IslandNotice.Priority.NORMAL, "t", "");
        assertEquals("第二天重新计数", NoticeCenter.PostResult.SHOWN, c.post(tomorrow, env, null));
    }

    @Test public void disabledSourceAndMasterDrop() {
        env.disabled.add(IslandNotice.Source.CALLBACK);
        assertEquals(NoticeCenter.PostResult.DROP_DISABLED, c.post(n("a", IslandNotice.Priority.NORMAL), env, null));
        env.disabled.clear();
        c.post(n("b", IslandNotice.Priority.NORMAL), env, null);
        env.master = false;
        c.tick(env);
        assertTrue("总开关关掉：在排的撤下", c.active().isEmpty());
    }

    // ------------------------------------------------------------------ 重启恢复

    @Test public void restartRestoresUnexpiredWithoutAlert() {
        IslandNotice a = n("a", IslandNotice.Priority.NORMAL);
        a.expireAt = now + 60 * MIN;
        IslandNotice b = n("b", IslandNotice.Priority.NORMAL);
        b.expireAt = now + 5 * MIN;
        IslandNotice later = n("later", IslandNotice.Priority.NORMAL);
        later.showAt = now + 30 * MIN;
        c.post(a, env, null);
        c.post(b, env, null);
        c.post(later, env, null);
        c.post(n("gone", IslandNotice.Priority.NORMAL), env, null);
        c.dismiss("gone", null);
        IslandNotice draft = ScheduledMailNotices.draftPending("s1", "我", "主题", now);
        c.post(draft, env, null);
        // 进程被杀：新的中心，同一个库
        now += 10 * MIN;
        NoticeCenter c2 = new NoticeCenter(store, new NoticeCenter.Clock() {
            @Override public long now() { return now; }
        });
        NoticeCenter.Tick t = c2.restore(env);
        assertEquals("没过期的回来；过期的撤下；忽略过的不回来；不落库的会话提醒不回来", Arrays.asList("a"), keys(c2.active()));
        assertFalse("恢复不自动展开", t.alert);
        assertEquals(IslandNotice.State.PENDING, c2.get("later").state);
        assertNull(c2.get("mail:draft:s1"));
        assertEquals("忽略记忆也恢复：同键不再出现", NoticeCenter.PostResult.DUP_IGNORED, c2.post(n("gone", IslandNotice.Priority.NORMAL), env, null));
        now += 20 * MIN;
        assertEquals("恢复后待显示的照常到点出现", 1, c2.tick(env).surfaced.size());
    }

    // ------------------------------------------------------------------ 动作 / 同步

    @Test public void snoozeReShowsLater() {
        IslandNotice x = n("s", IslandNotice.Priority.NORMAL);
        x.actions.clear();
        x.action(new NoticeAction("稍后", NoticeAction.Kind.SNOOZE, "10"));
        c.post(x, env, null);
        NoticeCenter.Outcome o = c.act("s", 0, null);
        assertEquals(NoticeAction.Kind.SNOOZE, o.kind);
        assertTrue(c.active().isEmpty());
        now += 10 * MIN;
        NoticeCenter.Tick t = c.tick(env);
        assertEquals(1, t.surfaced.size());
        assertEquals("稍后再提醒不重复计入每日上限", 1, countShown(c));
    }

    int countShown(NoticeCenter cc) {
        int n = 0;
        for (IslandNotice x : cc.all()) if (x.shownAt > 0) n++;
        return n;
    }

    @Test public void invalidActionsDoNothing() {
        IslandNotice x = n("u", IslandNotice.Priority.NORMAL);
        x.actions.clear();
        x.action(new NoticeAction("加入", NoticeAction.Kind.OPEN_URL, "intent://evil#Intent;end"));
        c.post(x, env, null);
        assertNull("非 http(s) 链接不打开", c.act("u", 0, null).kind);
        assertNull("越界", c.act("u", 5, null).kind);
        assertNull("没有这条", c.act("nope", 0, null).kind);
        assertEquals(1, c.active().size());
    }

    @Test public void syncRemovesStaleKeepsOthers() {
        IslandNotice e1 = new IslandNotice(IslandNotice.Source.CALENDAR, "cal:1:100", IslandNotice.Priority.NORMAL, "会1", "");
        IslandNotice e2 = new IslandNotice(IslandNotice.Source.CALENDAR, "cal:2:100", IslandNotice.Priority.NORMAL, "会2", "");
        c.sync(IslandNotice.Source.CALENDAR, "calendar", Arrays.asList(e1, e2), env);
        c.post(n("cb", IslandNotice.Priority.NORMAL), env, null);
        NoticeCenter.Tick t = c.sync(IslandNotice.Source.CALENDAR, "calendar", Arrays.asList(e1), env);
        assertEquals("日程删掉 → 撤下；别的来源不动", Arrays.asList("cb", "cal:1:100"), sortedKeys(c.active()));
        assertEquals(1, t.gone.size());
        assertFalse("同步里没有新出现的 → 不提醒", t.alert);
        NoticeCenter.Tick none = c.sync(IslandNotice.Source.CALENDAR, "calendar", null, env);
        assertFalse("拿不到数据（离线 / 没配置）→ 什么都不动", none.changed());
        assertEquals(2, c.active().size());
    }

    static List<String> sortedKeys(List<IslandNotice> l) {
        List<String> k = keys(l);
        java.util.Collections.sort(k, java.util.Collections.reverseOrder());
        return k;
    }

    @Test public void jsonRoundTrip() {
        IslandNotice x = n("j", IslandNotice.Priority.HIGH);
        x.showAt = 5;
        x.expireAt = 9;
        x.action(new NoticeAction("加入", NoticeAction.Kind.OPEN_URL, "https://meet.google.com/a"));
        IslandNotice y = IslandNotice.fromJson(x.toJson());
        assertEquals(x.toJson(), y.toJson());
        assertNull(IslandNotice.fromJson("garbage"));
    }

    @Test public void titleSanitizedAndClipped() {
        IslandNotice x = new IslandNotice(IslandNotice.Source.EXTERNAL, "k", null, "a\u202Eb\nc" + new String(new char[80]).replace('\0', '长'), null);
        assertFalse(x.title.contains("\u202E") || x.title.contains("\n"));
        assertTrue(x.title.length() <= IslandNotice.MAX_TITLE);
        assertEquals(IslandNotice.Priority.NORMAL, x.priority);
    }

    @Test public void queueCapTrimsLowest() {
        for (int i = 0; i < NoticeCenter.MAX_ACTIVE; i++) {
            now += 1000;
            c.post(n("n" + i, IslandNotice.Priority.NORMAL), env, null);
        }
        now += 1000;
        c.post(n("high", IslandNotice.Priority.HIGH), env, null);
        assertEquals(NoticeCenter.MAX_ACTIVE, c.active().size());
        assertEquals("high", c.active().get(0).dedupKey);
        assertEquals(IslandNotice.State.EXPIRED, c.get("n" + (NoticeCenter.MAX_ACTIVE - 1)).state);
    }
}
