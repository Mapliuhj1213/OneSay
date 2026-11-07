package com.onesay.assistant.v2.sched;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.TimeZone;

/** [sched] 语音 / 文字引擎：建（不确认、可撤销）、查、改 / 删（先确认）、追问、不抢别的功能的话。现在 = 2026-10-24（周六）10:00。 */
public class SchedEngineTest {

    static final TimeZone TZ = CnTimeTest.TZ;
    static final long NOW = CnTimeTest.NOW;
    private TimeZone saved;
    private SchedStore.Memory store;
    private SchedEngine e;
    private long t;

    @Before public void setUp() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TZ);
        store = new SchedStore.Memory();
        e = new SchedEngine(store, TZ);
        t = NOW;
    }

    @After public void tearDown() {
        TimeZone.setDefault(saved);
    }

    static long at(int y, int mo, int d, int h, int mi) {
        return CnTimeTest.at(y, mo, d, h, mi);
    }

    /** 每句话间隔 10 秒（避开 5 秒去重）。 */
    SchedEngine.Reply say(String s) {
        t += 10000L;
        return e.handle(s, t);
    }

    SchedEvent only() {
        List<SchedEvent> l = store.all(false);
        assertEquals("库里应该只有 1 条：" + l, 1, l.size());
        return l.get(0);
    }

    // ------------------------------------------------------------------ 新建

    @Test public void createStatement() {
        SchedEngine.Reply r = say("明天下午三点开会");
        assertNotNull(r);
        assertEquals(SchedEngine.Kind.CREATED, r.kind);
        assertTrue(r.text, r.text.contains("明天下午3点") && r.text.contains("开会") && r.text.contains("撤销"));
        assertTrue(r.text, r.text.contains("提前10分钟提醒"));
        SchedEvent ev = only();
        assertEquals("开会", ev.title);
        assertEquals(at(2026, 10, 25, 15, 0), ev.startAt);
        assertFalse(ev.allDay);
    }

    @Test public void createExplicitForms() {
        assertEquals(SchedEngine.Kind.CREATED, say("添加日程明天下午三点开会").kind);
        assertEquals(SchedEngine.Kind.CREATED, say("日程：周五交周报").kind);
        assertEquals(SchedEngine.Kind.CREATED, say("把下周一上午十点的面试加到日程里").kind);
        assertEquals(SchedEngine.Kind.CREATED, say("帮我安排后天晚上七点和老王吃饭").kind);
        assertEquals(SchedEngine.Kind.CREATED, say("新建日程：11月11号晚上8点看演出").kind);
        List<SchedEvent> l = store.all(false);
        assertEquals(5, l.size());
        boolean zhou = false, mian = false, chifan = false;
        for (SchedEvent x : l) {
            if (x.title.equals("交周报")) {
                zhou = true;
                assertTrue(x.allDay);
                assertEquals(CnTimeTest.day(2026, 10, 30), x.startAt);
            }
            if (x.title.contains("面试")) {
                mian = true;
                assertEquals(at(2026, 10, 26, 10, 0), x.startAt);
            }
            if (x.title.contains("吃饭")) {
                chifan = true;
                assertTrue(x.title, x.title.contains("老王"));
            }
        }
        assertTrue(zhou && mian && chifan);
    }

    @Test public void createWithLeadAndRepeat() {
        SchedEngine.Reply r = say("明天下午三点开会提前半小时提醒我");
        assertEquals(SchedEngine.Kind.CREATED, r.kind);
        assertTrue(r.text, r.text.contains("提前半小时提醒"));
        assertEquals(30, only().leadMin);
        store.purgeDeleted(Long.MAX_VALUE);
        SchedEngine.Reply w = say("每周三下午三点开例会");
        assertEquals(SchedEngine.Kind.CREATED, w.kind);
        assertTrue(w.text, w.text.contains("每周三") && w.text.contains("下一次"));
        SchedEngine.Reply n = say("后天上午考试不用提醒");
        assertEquals(SchedEngine.Kind.CREATED, n.kind);
        assertTrue(n.text, n.text.contains("不提醒"));
    }

    @Test public void undoCreate() {
        say("明天下午三点开会");
        SchedEngine.Reply u = say("撤销");
        assertEquals(SchedEngine.Kind.UNDONE, u.kind);
        assertTrue(store.all(false).isEmpty());
        assertNull("撤销过了：「撤销」不再归日程管", say("撤销"));
    }

    @Test public void undoExpires() {
        say("明天下午三点开会");
        t += SchedEngine.UNDO_WINDOW_MS + 1;
        assertNull(say("撤销"));
        assertEquals(1, store.all(false).size());
    }

    @Test public void duplicateUtteranceOnce() {
        e.handle("明天下午三点开会", NOW);
        e.handle("明天下午三点开会", NOW + 1000);
        assertEquals("路由层重试同一句话不重复建", 1, store.all(false).size());
        assertTrue(e.recentlyHandled("明天下午三点开会。", NOW + 2000));
    }

    @Test public void rolledAndGuessedNotes() {
        SchedEngine.Reply r = say("早上8点跑步");
        assertTrue(r.text, r.text.contains("记在了明天"));
        store.purgeDeleted(Long.MAX_VALUE);
        SchedEngine.Reply g = say("明天上午开会");
        assertTrue(g.text, g.text.contains("没说几点"));
    }

    // ------------------------------------------------------------------ 追问

    @Test public void askAmPm() {
        SchedEngine.Reply r = say("明天三点赶飞机");
        assertEquals(SchedEngine.Kind.ASK, r.kind);
        assertTrue(r.text, r.text.contains("下午3点") && r.text.contains("凌晨3点"));
        assertTrue(store.all(false).isEmpty());
        assertEquals("ASK_OPTION", e.pendingType(t));
        SchedEngine.Reply a = say("下午");
        assertEquals(SchedEngine.Kind.CREATED, a.kind);
        assertEquals(at(2026, 10, 25, 15, 0), only().startAt);
    }

    @Test public void askAmPmAnswerSmallHours() {
        say("明天三点赶飞机");
        SchedEngine.Reply a = say("凌晨");
        assertEquals(SchedEngine.Kind.CREATED, a.kind);
        assertEquals(at(2026, 10, 25, 3, 0), only().startAt);
    }

    @Test public void askAnswerByIndexAndNewTime() {
        say("明天三点赶飞机");
        assertEquals(SchedEngine.Kind.CREATED, say("第二个").kind);
        assertEquals(at(2026, 10, 25, 3, 0), only().startAt);
        store.purgeDeleted(Long.MAX_VALUE);
        e.undoNow(t);
        store.purgeDeleted(Long.MAX_VALUE);
        say("明天三点赶飞机");
        assertEquals(SchedEngine.Kind.CREATED, say("下午四点").kind);
        assertEquals(at(2026, 10, 25, 16, 0), only().startAt);
    }

    @Test public void askWeekdayToday() {
        SchedEngine.Reply r = say("周六下午三点聚餐");
        assertEquals(SchedEngine.Kind.ASK, r.kind);
        assertEquals(SchedEngine.Kind.CREATED, say("下周六").kind);
        assertEquals(at(2026, 10, 31, 15, 0), only().startAt);
    }

    @Test public void askPastToday() {
        SchedEngine.Reply r = say("今天上午9点开会");
        assertEquals(SchedEngine.Kind.ASK, r.kind);
        assertEquals(SchedEngine.Kind.CREATED, say("明天").kind);
        assertEquals(at(2026, 10, 25, 9, 0), only().startAt);
    }

    @Test public void askTimeThenTitle() {
        SchedEngine.Reply r = say("添加日程开会");
        assertEquals(SchedEngine.Kind.ASK, r.kind);
        assertTrue(r.text, r.text.contains("什么时候"));
        assertEquals(SchedEngine.Kind.CREATED, say("明天下午三点").kind);
        assertEquals(at(2026, 10, 25, 15, 0), only().startAt);
        assertEquals("开会", only().title);

        e.undoNow(t);
        store.purgeDeleted(Long.MAX_VALUE);
        SchedEngine.Reply r2 = say("添加日程明天下午三点");
        assertEquals(SchedEngine.Kind.ASK, r2.kind);
        assertTrue(r2.text, r2.text.contains("要记什么事"));
        assertEquals(SchedEngine.Kind.CREATED, say("产品评审").kind);
        assertEquals("产品评审", only().title);
    }

    @Test public void askWeekOnly() {
        SchedEngine.Reply r = say("下周下午三点开会");
        assertEquals(SchedEngine.Kind.ASK, r.kind);
        assertEquals(SchedEngine.Kind.CREATED, say("周三").kind);
        assertEquals(at(2026, 10, 28, 15, 0), only().startAt);
    }

    @Test public void pendingExpires() {
        say("明天三点赶飞机");
        t += SchedEngine.PENDING_MS + 1;
        SchedEngine.Reply a = say("下午");
        assertTrue("过期后「下午」不再当回答", a == null || a.kind != SchedEngine.Kind.CREATED);
        assertTrue(store.all(false).isEmpty());
    }

    @Test public void askCancel() {
        say("明天三点赶飞机");
        assertEquals(SchedEngine.Kind.CANCELLED, say("算了").kind);
        assertTrue(store.all(false).isEmpty());
        assertNull(e.pendingType(t));
    }

    // ------------------------------------------------------------------ 不抢别人的话

    @Test public void notOurs() {
        String[] others = {
                "明天天气怎么样", "打开日历", "打开日程", "明天早上7点叫醒我", "设个明天7点的闹钟",
                "记个待办明天交报告", "用谷歌日历加个日程明天三点开会", "在Google日历上建个明天三点的会", "帮我打开微信",
                "三点了", "现在几点", "10分钟后提醒我喝水", "明天去北京", "今天会下雨吗", "把亮度调到50", "删除照片",
                "取消明天的提醒", "取消那个定时任务", "播放周杰伦的歌", "我明天三点要不要带伞", "导航到公司", "下午三点的高铁票还有吗",
                "删掉这条短信", "取消静音", "定时任务有哪些", "撤销",
        };
        for (String s : others) assertNull(s, e.plan(s, t));
        assertTrue(store.all(false).isEmpty());
    }

    /** ★ int/1102 常识：日程 = 日历 + 提醒；「三点开会」不问凌晨；记忆式前缀不进标题；事实句不是日程；光说「加个日程」问一句。 */
    @Test public void commonSenseInt1102() {
        SchedEngine.Reply r = say("明天三点开会");
        assertEquals(SchedEngine.Kind.CREATED, r.kind);
        assertEquals(at(2026, 10, 25, 15, 0), only().startAt);
        assertTrue(r.text, r.text.contains("按下午3点记") && r.text.contains("- 事项：开会") && r.text.contains("- 提醒：提前10分钟"));
        assertTrue(r.text, r.text.contains("- 地点：") && r.text.contains("- 重复：不重复"));
        e.undoNow(t);
        store.purgeDeleted(Long.MAX_VALUE);
        assertEquals(SchedEngine.Kind.CREATED, say("提醒我明天下午三点开会").kind);
        assertEquals("「提醒我X点」= 准点提醒", 0, only().leadMin);
        e.undoNow(t);
        store.purgeDeleted(Long.MAX_VALUE);
        for (String q : new String[]{"记住明天下午三点开会", "别忘了明天下午三点开会", "帮我记一下明天下午三点开会"}) {
            assertEquals(q, SchedEngine.Kind.CREATED, say(q).kind);
            assertEquals(q, "开会", only().title);
            e.undoNow(t);
            store.purgeDeleted(Long.MAX_VALUE);
        }
        assertEquals(SchedEngine.Kind.CREATED, say("我约了周日晚上七点吃饭").kind);
        assertEquals("吃饭", only().title);
        e.undoNow(t);
        store.purgeDeleted(Long.MAX_VALUE);
        assertNull("事实不是日程", e.plan("我女儿生日是5月3号", t));
        assertNull("相对时长归 S3", e.plan("10分钟后提醒我开会", t));
        assertNull("叫我归闹钟", e.plan("明天三点叫我开会", t));
        SchedEngine.Reply a = say("加个日程");
        assertEquals(SchedEngine.Kind.ASK, a.kind);
        assertEquals(SchedEngine.Kind.CREATED, say("明天下午三点开会").kind);
        assertEquals(SchedEngine.Kind.LISTED, say("今天的日程").kind);
        assertEquals(SchedEngine.Kind.LISTED, say("明天有什么安排").kind);
        assertTrue(say("明天的安排").text.contains("- 下午3点 开会"));
        e.settings.tableList = true;
        assertTrue(say("明天日程").text.contains("| 下午3点 | 开会 |"));
        assertTrue(SchedEngine.looksLikeSchedule("记一下明天三点开会"));
        assertFalse(SchedEngine.looksLikeSchedule("我女儿生日是5月3号"));
        assertFalse(SchedEngine.looksLikeSchedule("记一下车停在B2-17"));
        assertEquals(java.util.Arrays.asList("撤销", "今天有什么安排"), SchedHooks.chipsFor("CREATED"));
    }

    // ------------------------------------------------------------------ 查询

    void seed() {
        say("明天下午三点开会");
        say("明天晚上七点半和老王吃饭");
        say("下周三上午十点面试");
        say("每周一上午9点例会");
    }

    @Test public void queries() {
        seed();
        assertEquals(4, store.all(false).size());
        SchedEngine.Reply r = say("明天有什么安排");
        assertEquals(SchedEngine.Kind.LISTED, r.kind);
        assertTrue(r.text, r.text.startsWith("明天有 2 个日程") && r.text.contains("下午3点 开会") && r.text.contains("晚上7点半"));
        assertEquals("今天没有日程。", say("今天有什么日程").text);
        SchedEngine.Reply meet = say("明天有会吗");
        assertTrue(meet.text, meet.text.contains("开会") && !meet.text.contains("吃饭"));
        SchedEngine.Reply when = say("明天几点开会");
        assertTrue(when.text, when.text.contains("下午3点"));
        SchedEngine.Reply next = say("下一个日程是什么");
        assertTrue(next.text, next.text.contains("明天下午3点") && next.text.contains("开会"));
        SchedEngine.Reply week = say("下周有什么安排");
        assertTrue(week.text, week.text.contains("面试") && week.text.contains("例会"));
        SchedEngine.Reply mine = say("我的日程");
        assertTrue(mine.text, mine.text.contains("开会") && mine.text.contains("面试"));
        SchedEngine.Reply busy = say("我后天忙不忙");
        assertTrue(busy.text, busy.text.contains("后天") && busy.text.contains("例会"));
        SchedEngine.Reply eve = say("明天晚上有什么安排");
        assertTrue(eve.text, eve.text.contains("吃饭") && !eve.text.contains("开会"));
    }

    // ------------------------------------------------------------------ 修改（确认）

    @Test public void updateTimeConfirmAndUndo() {
        say("明天下午三点开会");
        SchedEngine.Reply c = say("把明天的会改到后天下午四点");
        assertEquals(SchedEngine.Kind.CONFIRM, c.kind);
        assertTrue(c.text, c.text.contains("确认") && c.text.contains("后天下午4点"));
        assertEquals("没确认之前不动", at(2026, 10, 25, 15, 0), only().startAt);
        SchedEngine.Reply ok = say("确认");
        assertEquals(SchedEngine.Kind.UPDATED, ok.kind);
        assertEquals(at(2026, 10, 26, 16, 0), only().startAt);
        assertEquals(SchedEngine.Kind.UNDONE, say("撤销").kind);
        assertEquals(at(2026, 10, 25, 15, 0), only().startAt);
    }

    @Test public void updateClockOnlyKeepsDay() {
        say("明天下午三点开会");
        say("开会改到下午五点");
        say("好的");
        assertEquals(at(2026, 10, 25, 17, 0), only().startAt);
    }

    @Test public void updateDayOnlyKeepsClock() {
        say("明天下午三点开会");
        say("把开会改到周一");
        say("是的");
        assertEquals(at(2026, 10, 26, 15, 0), only().startAt);
    }

    @Test public void shiftRenameRelocLead() {
        say("明天下午三点开会");
        assertEquals(SchedEngine.Kind.CONFIRM, say("开会推迟半小时").kind);
        say("确定");
        assertEquals(at(2026, 10, 25, 15, 30), only().startAt);
        say("把开会提前一小时");
        say("确定");
        assertEquals(at(2026, 10, 25, 14, 30), only().startAt);
        say("把开会改名为产品周会");
        say("确认");
        assertEquals("产品周会", only().title);
        say("产品周会的地点改成三楼会议室");
        say("确认");
        assertEquals("三楼会议室", only().location);
        say("把产品周会改成提前一小时提醒");
        say("确认");
        assertEquals(60, only().leadMin);
    }

    @Test public void updateDeclined() {
        say("明天下午三点开会");
        say("把明天的会改到后天下午四点");
        assertEquals(SchedEngine.Kind.CANCELLED, say("不用了").kind);
        assertEquals(at(2026, 10, 25, 15, 0), only().startAt);
    }

    @Test public void updateRepeatingSeries() {
        say("每周三下午三点开例会");
        SchedEngine.Reply c = say("把例会改到下午四点");
        assertTrue(c.text, c.text.contains("以后每次都改"));
        say("确认");
        SchedEvent ev = only();
        assertEquals(at(2026, 10, 28, 16, 0), SchedRecur.nextStart(ev, t, TZ));
    }

    // ------------------------------------------------------------------ 删除（确认）

    @Test public void deleteConfirmCancelUndo() {
        say("明天下午三点开会");
        SchedEngine.Reply c = say("删除明天的会");
        assertEquals(SchedEngine.Kind.CONFIRM, c.kind);
        assertTrue(c.text, c.text.contains("开会") && c.text.contains("吗"));
        assertEquals(SchedEngine.Kind.CANCELLED, say("算了").kind);
        assertEquals(1, store.all(false).size());
        say("取消明天下午的会");
        assertEquals(SchedEngine.Kind.DELETED, say("确认").kind);
        assertTrue(store.all(false).isEmpty());
        assertEquals(1, store.all(true).size());
        SchedEngine.Reply u = say("撤销");
        assertEquals(SchedEngine.Kind.UNDONE, u.kind);
        assertTrue(u.text, u.text.contains("已恢复"));
        assertEquals(1, store.all(false).size());
    }

    @Test public void deleteForms() {
        say("后天上午十点面试");
        assertEquals(SchedEngine.Kind.CONFIRM, say("面试取消了").kind);
        assertEquals(SchedEngine.Kind.DELETED, say("删吧").kind);
        say("明天下午三点开会");
        assertEquals(SchedEngine.Kind.CONFIRM, say("删掉刚才那个").kind);
        say("确认");
        assertTrue(store.all(false).isEmpty());
    }

    @Test public void deleteNotFound() {
        SchedEngine.Reply r = say("删除后天的面试");
        assertEquals(SchedEngine.Kind.NOT_FOUND, r.kind);
        assertTrue(r.text, r.text.contains("没找到"));
        SchedEngine.Reply r2 = say("删除明天的日程");
        assertEquals(SchedEngine.Kind.NOT_FOUND, r2.kind);
    }

    @Test public void deletePickOne() {
        say("明天上午十点开周会");
        say("明天下午三点开评审会");
        SchedEngine.Reply r = say("删除明天的会");
        assertEquals(SchedEngine.Kind.ASK, r.kind);
        assertTrue(r.text, r.text.contains("1.") && r.text.contains("2."));
        SchedEngine.Reply c = say("第二个");
        assertEquals(SchedEngine.Kind.CONFIRM, c.kind);
        assertTrue(c.text, c.text.contains("评审"));
        say("确认");
        List<SchedEvent> l = store.all(false);
        assertEquals(1, l.size());
        assertTrue(l.get(0).title.contains("周会"));
    }

    @Test public void clearDay() {
        say("明天上午十点开周会");
        say("明天下午三点开评审会");
        say("每周日上午10点打羽毛球");
        SchedEngine.Reply r = say("清空明天的日程");
        assertEquals(SchedEngine.Kind.CONFIRM, r.kind);
        assertTrue(r.text, r.text.contains("2 个"));
        say("确认");
        assertEquals("重复系列不动", 1, store.all(false).size());
    }

    @Test public void deleteRepeating() {
        say("每周一上午9点例会");
        SchedEngine.Reply c = say("删除例会");
        assertTrue(c.text, c.text.contains("整个重复系列"));
        say("确认");
        assertTrue(store.all(false).isEmpty());
    }

    // ------------------------------------------------------------------ 杂项

    @Test public void titleMatching() {
        assertTrue(SchedEngine.titleMatches("产品评审会", "评审"));
        assertTrue(SchedEngine.titleMatches("开会", "会"));
        assertTrue(SchedEngine.titleMatches("和老王吃饭", "吃饭"));
        assertFalse(SchedEngine.titleMatches("面试", "吃饭"));
        assertEquals("开会", SchedEngine.stripTitle("我要去开会"));
        assertEquals("交周报", SchedEngine.stripTitle("交周报的事"));
    }

    @Test public void effectiveLead() {
        SchedEvent ev = new SchedEvent();
        e.settings.defaultLead = 15;
        assertEquals(15, e.effectiveLead(ev));
        ev.leadMin = 0;
        assertEquals(0, e.effectiveLead(ev));
        ev.leadMin = CnTime.LEAD_NONE;
        assertEquals(CnTime.LEAD_NONE, e.effectiveLead(ev));
    }

    @Test public void consoleApis() {
        SchedEvent d = new SchedEvent();
        d.title = "体检";
        d.startAt = at(2026, 10, 30, 8, 0);
        long id = e.createNow(d, t);
        assertNotNull(e.undoLabel(t));
        SchedEvent nw = store.get(id);
        nw.startAt = at(2026, 10, 30, 9, 0);
        assertTrue(e.updateNow(nw, t));
        assertEquals(at(2026, 10, 30, 9, 0), store.get(id).startAt);
        e.undoNow(t);
        assertEquals(at(2026, 10, 30, 8, 0), store.get(id).startAt);
        assertTrue(e.deleteNow(id, t));
        assertTrue(store.all(false).isEmpty());
        e.undoNow(t);
        assertEquals(1, store.all(false).size());
        assertNull(e.undoLabel(t + SchedEngine.UNDO_WINDOW_MS + 1));
    }
}
