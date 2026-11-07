package com.onesay.assistant.v2.notice;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 2026-10-23 BUG-I3：关掉再打开总开关 / 某个来源，关之前在排的提醒回来（从库里恢复没过期的），关闭期间已经过期的不补发；
 * 用户处理过的、因「来源不可用」撤下的不因开关回来。
 */
public class NoticeReenableTest {

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

    static IslandNotice mail(String key, long showAt, long expireAt) {
        IslandNotice n = new IslandNotice(IslandNotice.Source.MAIL, key, IslandNotice.Priority.NORMAL, "定时邮件 " + key, "");
        n.sourceTag = ScheduledMailNotices.TAG;
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

    /** 上机复现：3 条在排的定时邮件提醒 + 1 条在岛上的回调；总开关关 → 全撤；马上开 → 全回来。 */
    @Test public void masterOffThenOnBringsQueuedBack() {
        c.post(mail("mail:s3:5@1", now + 5 * MIN, now + 10 * MIN), env, null);
        c.post(mail("mail:s3:6@1", now + 5 * MIN, now + 10 * MIN), env, null);
        c.post(mail("mail:s3:7@1", now + 5 * MIN, now + 10 * MIN), env, null);
        c.post(cb("cb:1"), env, null);
        assertEquals(Arrays.asList("cb:1"), keys(c.active()));

        env.master = false;
        NoticeCenter.Tick off = c.tick(env);
        assertTrue(c.active().isEmpty());
        assertEquals("岛上那条撤下", 1, off.gone.size());
        assertEquals(IslandNotice.State.WITHDRAWN, c.get("mail:s3:5@1").state);
        assertEquals(NoticeCenter.NOTE_SWITCHED_OFF, c.get("mail:s3:5@1").note);
        assertEquals("关着时不叫醒", -1L, c.nextWakeAt());

        now += MIN;
        env.master = true;
        NoticeCenter.Tick on = c.tick(env);
        assertEquals("在岛上的那条立即回来", Arrays.asList("cb:1"), keys(c.active()));
        assertEquals(Arrays.asList("cb:1"), keys(on.surfaced));
        assertFalse("出现过的回来不再自动展开", on.alert);
        assertEquals("还没到点的回到等待", IslandNotice.State.PENDING, c.get("mail:s3:6@1").state);
        assertEquals("显示时刻不变", now + 4 * MIN, c.get("mail:s3:6@1").showAt);
        assertEquals("闹钟按原显示时刻", now + 4 * MIN, c.nextWakeAt());

        now += 4 * MIN;
        NoticeCenter.Tick due = c.tick(env);
        assertEquals(Arrays.asList("cb:1", "mail:s3:5@1", "mail:s3:6@1", "mail:s3:7@1"), keys(c.active()));
        assertTrue("没出现过的到点照常提醒", due.alert);
    }

    /** 开着时同一来源的重新扫描拿到同键 → 只更新（不会因为 WITHDRAWN 而重复 / 卡住）。 */
    @Test public void rescanAfterReenableIsDupUpdated() {
        c.post(mail("mail:s3:5@1", now, now + 10 * MIN), env, null);
        env.disabled.add(IslandNotice.Source.MAIL);
        c.tick(env);
        env.disabled.clear();
        c.tick(env);
        NoticeCenter.Tick t = c.sync(IslandNotice.Source.MAIL, ScheduledMailNotices.TAG,
                Arrays.asList(mail("mail:s3:5@1", now, now + 10 * MIN)), env);
        assertEquals(1, c.active().size());
        assertTrue(t.surfaced.isEmpty());
        assertFalse(t.alert);
    }

    /** 只关某个来源：这个来源撤下、别的不动；重开回来。 */
    @Test public void sourceSwitchOnlyAffectsThatSource() {
        c.post(mail("mail:s3:5@1", now, now + 30 * MIN), env, null);
        c.post(cb("cb:1"), env, null);
        env.disabled.add(IslandNotice.Source.MAIL);
        c.tick(env);
        assertEquals(Arrays.asList("cb:1"), keys(c.active()));
        env.disabled.clear();
        c.tick(env);
        assertEquals(Arrays.asList("cb:1", "mail:s3:5@1"), keys(c.active()));
        // 扩展来源按 tag 关
        IslandNotice ext = new IslandNotice(IslandNotice.Source.EXTERNAL, "test:1", IslandNotice.Priority.NORMAL, "扩展", "");
        ext.sourceTag = "test";
        c.post(ext, env, null);
        env.disabledTags.add("test");
        c.tick(env);
        assertNull("扩展来源关了", c.get("test:1").state == IslandNotice.State.ACTIVE ? "x" : null);
        env.disabledTags.clear();
        c.tick(env);
        assertEquals(IslandNotice.State.ACTIVE, c.get("test:1").state);
    }

    /** 关闭期间已经过期的不补发；之后按保留期清掉。 */
    @Test public void expiredWhileOffNotReplayed() {
        c.post(mail("mail:s3:5@1", now, now + 10 * MIN), env, null);
        c.post(cb("cb:1"), env, null);
        env.master = false;
        c.tick(env);
        now += 15 * MIN;
        env.master = true;
        NoticeCenter.Tick t = c.tick(env);
        assertEquals("过期的不回来", Arrays.asList("cb:1"), keys(c.active()));
        assertFalse(c.get("mail:s3:5@1").isLive());
        assertEquals(1, t.surfaced.size());
        now += NoticeCenter.KEEP_DONE_MS + MIN;
        c.tick(env);
        assertNull("保留期后删掉", c.get("mail:s3:5@1"));
        assertNull(store.get("mail:s3:5@1"));
    }

    /** 用户忽略过的、来源不可用撤下的，不因开关回来。 */
    @Test public void handledOrLostStayDown() {
        c.post(cb("cb:1"), env, null);
        c.dismiss("cb:1", null);
        IslandNotice cal = new IslandNotice(IslandNotice.Source.CALENDAR, "cal:a:1", IslandNotice.Priority.NORMAL, "会", "");
        cal.sourceTag = CalendarNotices.TAG;
        c.post(cal, env, null);
        c.withdrawSource(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, null);
        env.master = false;
        c.tick(env);
        env.master = true;
        c.tick(env);
        assertTrue(c.active().isEmpty());
        assertEquals(IslandNotice.State.DISMISSED, c.get("cb:1").state);
        assertEquals("来源不可用的要等来源恢复（poll）才回来", NoticeCenter.NOTE_SOURCE_LOST, c.get("cal:a:1").note);
    }

    /** 关着的时候 App 被杀：重启（仍关着）不恢复；之后打开 → 从库里回来。 */
    @Test public void restartWhileOffThenOn() {
        c.post(cb("cb:1"), env, null);
        env.master = false;
        c.tick(env);
        c.dropMemory();
        c.restore(env);
        assertTrue(c.active().isEmpty());
        env.master = true;
        NoticeCenter.Tick t = c.tick(env);
        assertEquals(Arrays.asList("cb:1"), keys(c.active()));
        assertFalse(t.alert);
        assertEquals(IslandNotice.State.ACTIVE, store.get("cb:1").state);
    }

    /** 每日上限：回来的那条不重复计数。 */
    @Test public void reenableDoesNotDoubleCount() {
        env.caps.put(IslandNotice.Source.CALLBACK, 1);
        c.post(cb("cb:1"), env, null);
        env.master = false;
        c.tick(env);
        env.master = true;
        c.tick(env);
        assertEquals(IslandNotice.State.ACTIVE, c.get("cb:1").state);
        assertEquals("名额已被 cb:1 用掉，新的照常受限", NoticeCenter.PostResult.DROP_CAP, c.post(cb("cb:2"), env, null));
    }

    /** Android：开关 / kill-switch 改了 → repoll（tick 先放回、再重拉）；内核装配时的首次启用不触发。 */
    @Test public void wiring() throws Exception {
        String hooks = NoticeSourcesTest.read("app/src/com/onesay/assistant/v2/notice/NoticeHooks.java");
        String re = hooks.substring(hooks.indexOf("public static void repoll"));
        re = re.substring(0, re.indexOf("\n    }\n"));
        assertTrue(re.indexOf(".tick(env(app))") > 0 && re.indexOf("pollAsync(app, true, \"repoll\")") > re.indexOf(".tick(env(app))"));
        String pl = NoticeSourcesTest.read("app/src/com/onesay/assistant/v2/kernel/plugins/NoticePlugin.java");
        assertTrue(pl.contains("@Override public void onEnable(Context ctx) {") && pl.contains("if (ctx == null || !NoticeHooks.started()) return;"));
        assertTrue("控制台开关都走 refresh → repoll", pl.contains("refresh(act);") && pl.contains("NoticeHooks.repoll(app)"));
        String drv = NoticeSourcesTest.read("app/src/com/onesay/assistant/v2/test/NoticeTestDriver.java");
        assertTrue("debug set 同样 repoll", drv.contains("NoticeHooks.repoll(app);"));
    }
}
