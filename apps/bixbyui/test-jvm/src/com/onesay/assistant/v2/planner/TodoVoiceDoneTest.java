package com.onesay.assistant.v2.planner;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** ★ borrow-1112 ⑤：说「发了 / 做完了 / 搞定 X」勾正在提醒 / 最近提醒的待办；歧义问一句；没有最近提醒的不接。 */
public class TodoVoiceDoneTest {

    @After public void reset() {
        TodoVoiceFlow.PENDING.clear();
        RecentReminds.clear();
    }

    @Test public void parsesDonePhrases() {
        for (String q : new String[]{"发了", "做完了", "搞定了", "我已经做完了。", "完成了啊", "交了"}) {
            TodoVoiceDone.Intent in = TodoVoiceDone.parse(q);
            assertNotNull(q, in);
            assertEquals(q, "", in.hint);
        }
        assertEquals("周报", TodoVoiceDone.parse("搞定周报").hint);
        assertEquals("周报", TodoVoiceDone.parse("搞定 周报 了").hint);
        assertEquals("周报", TodoVoiceDone.parse("周报写完了").hint);
        assertEquals("电费", TodoVoiceDone.parse("电费交了").hint);
        for (String q : new String[]{"还没做完", "做完了吗", "发了没", "没发", "好了", "今天天气怎么样", "", null, "别发了"}) {
            assertNull(q, TodoVoiceDone.parse(q));
        }
    }

    static TodoVoiceDone.Cand c(long id, String title, String state) {
        return new TodoVoiceDone.Cand(id, title, state, 1000, "planner");
    }

    @Test public void resolveRules() {
        TodoVoiceDone.Intent bare = TodoVoiceDone.parse("做完了");
        assertEquals(TodoVoiceDone.Kind.NONE, TodoVoiceDone.resolve(bare, new ArrayList<TodoVoiceDone.Cand>()).kind);
        TodoVoiceDone.Decision one = TodoVoiceDone.resolve(bare, Arrays.asList(c(1, "交周报", "notified")));
        assertEquals(TodoVoiceDone.Kind.DONE, one.kind);
        assertEquals("好，「交周报」标成完成了。", one.say);
        TodoVoiceDone.Decision ring = TodoVoiceDone.resolve(bare, Arrays.asList(c(1, "交周报", "notified"), c(2, "吃药", "ringing")));
        assertEquals("只有一条正在响 → 就是它", TodoVoiceDone.Kind.DONE, ring.kind);
        assertEquals(2, ring.picks.get(0).id);
        TodoVoiceDone.Decision two = TodoVoiceDone.resolve(bare, Arrays.asList(c(1, "交周报", "notified"), c(2, "吃药", "snoozed")));
        assertEquals(TodoVoiceDone.Kind.ASK, two.kind);
        assertEquals("你说的是哪一条：「交周报」，还是「吃药」？", two.say);
        TodoVoiceDone.Decision hint = TodoVoiceDone.resolve(TodoVoiceDone.parse("周报写完了"), Arrays.asList(c(1, "交周报", "notified"), c(2, "吃药", "ringing")));
        assertEquals(TodoVoiceDone.Kind.DONE, hint.kind);
        assertEquals(1, hint.picks.get(0).id);
        assertEquals("说的那件不在最近提醒里 → 不接", TodoVoiceDone.Kind.NONE,
                TodoVoiceDone.resolve(TodoVoiceDone.parse("搞定报销"), Arrays.asList(c(1, "交周报", "notified"))).kind);
    }

    @Test public void answersAfterAsk() {
        List<TodoVoiceDone.Cand> o = Arrays.asList(c(1, "交周报", ""), c(2, "吃药", ""));
        assertEquals(1, TodoVoiceDone.answer("第一个", o).get(0).id);
        assertEquals(2, TodoVoiceDone.answer("第2条", o).get(0).id);
        assertEquals(2, TodoVoiceDone.answer("吃药那个", o).get(0).id);
        assertEquals(2, TodoVoiceDone.answer("都完成了", o).size());
        assertTrue(TodoVoiceDone.answer("算了", o).isEmpty());
        assertNull(TodoVoiceDone.answer("明天下雨吗", o));
        assertNull(TodoVoiceDone.answer("第三个", o));
    }

    static final class FakeBackend implements TodoVoiceFlow.Backend {
        List<TodoVoiceDone.Cand> planner;
        final List<String> done = new ArrayList<String>();
        String plannerFail;

        @Override public List<TodoVoiceDone.Cand> planner() {
            return planner;
        }

        @Override public List<TodoVoiceDone.Cand> local(long now) {
            return RecentReminds.recent(now);
        }

        @Override public String completePlanner(long id) {
            if (plannerFail != null) return plannerFail;
            done.add("planner#" + id);
            return null;
        }

        @Override public String completeLocal(long id) {
            done.add("local#" + id);
            return null;
        }
    }

    @Test public void flowAsksOnceThenCompletesViaPlanner() {
        FakeBackend b = new FakeBackend();
        b.planner = new ArrayList<TodoVoiceDone.Cand>(Arrays.asList(c(11, "交周报", "notified"), c(12, "吃药", "notified")));
        long now = 5_000_000L;
        assertTrue(TodoVoiceFlow.wouldHandle("做完了", b, now));
        assertEquals("你说的是哪一条：「交周报」，还是「吃药」？", TodoVoiceFlow.handle("做完了", b, now));
        assertTrue(b.done.isEmpty());
        assertTrue(TodoVoiceFlow.wouldHandle("第二个", b, now + 1000));
        assertEquals("好，「吃药」标成完成了。", TodoVoiceFlow.handle("第二个", b, now + 1000));
        assertEquals(Arrays.asList("planner#12"), b.done);
        assertNull("问过的已经用掉", TodoVoiceFlow.handle("第一个", b, now + 2000));
    }

    @Test public void pendingExpires() {
        FakeBackend b = new FakeBackend();
        b.planner = new ArrayList<TodoVoiceDone.Cand>(Arrays.asList(c(11, "交周报", ""), c(12, "吃药", "")));
        TodoVoiceFlow.handle("做完了", b, 0);
        assertNull(TodoVoiceFlow.handle("第一个", b, TodoVoiceDone.ASK_TTL_MS + 1));
    }

    @Test public void localRemindersWhenPlannerAbsentAndNothingRecentMeansNotOurs() {
        FakeBackend b = new FakeBackend();
        b.planner = null;   // 计划 App 不在 / 没有接口
        long now = 10_000_000L;
        assertNull("没有最近提醒 → 不接", TodoVoiceFlow.handle("发了", b, now));
        assertFalse(TodoVoiceFlow.wouldHandle("发了", b, now));
        RecentReminds.note(7, "待办：给妈妈发消息", now - 60_000L);
        RecentReminds.note(8, "待办：旧的", now - (TodoVoiceDone.WINDOW_MIN + 5) * 60_000L);
        assertEquals("好，「给妈妈发消息」标成完成了。", TodoVoiceFlow.handle("发了", b, now));
        assertEquals(Arrays.asList("local#7"), b.done);
        assertTrue("勾完就不算最近提醒了", RecentReminds.recent(now).isEmpty());
    }

    @Test public void plannerFailureIsReportedHonestly() {
        FakeBackend b = new FakeBackend();
        b.planner = new ArrayList<TodoVoiceDone.Cand>(Arrays.asList(c(11, "交周报", "ringing")));
        b.plannerFail = "这条待办已经不在最近提醒里了";
        String r = TodoVoiceFlow.handle("搞定了", b, 1);
        assertEquals("「交周报」没勾上：这条待办已经不在最近提醒里了。", r);
    }

    @Test public void dedupesPlannerAndLocalById() {
        FakeBackend b = new FakeBackend();
        long now = 20_000_000L;
        b.planner = new ArrayList<TodoVoiceDone.Cand>(Arrays.asList(new TodoVoiceDone.Cand(5, "交周报", "ringing", now, "planner")));
        RecentReminds.note(5, "待办：交周报", now);
        assertEquals("好，「交周报」标成完成了。", TodoVoiceFlow.handle("做完了", b, now));
        assertEquals(Arrays.asList("planner#5"), b.done);
    }

    @Test public void parsesPlannerListJson() {
        List<TodoVoiceDone.Cand> l = TodoDoneLink.parse("[{\"id\":3,\"title\":\"交周报\",\"state\":\"ringing\",\"reminded_at\":99},{\"id\":0},{\"title\":\"没 id\"}]");
        assertEquals(1, l.size());
        assertEquals(3, l.get(0).id);
        assertTrue(l.get(0).ringing());
        assertEquals("planner", l.get(0).source);
        assertTrue(TodoDoneLink.parse("坏的").isEmpty());
        assertTrue(TodoDoneLink.parse(null).isEmpty());
    }

    @Test public void methodNamesMatchPlannerSide() {
        assertEquals("listRemindedTasks", TodoDoneLink.M_LIST);
        assertEquals("completeRemindedTask", TodoDoneLink.M_COMPLETE);
        assertEquals(TodoVoiceDone.WINDOW_MIN, 30);
        // 与计划 App core/RemindLink + LinkContract 一致：字段名与结果码
        assertEquals("id", TodoDoneLink.X_ID);
        assertEquals("window_min", TodoDoneLink.X_WINDOW);
        assertEquals("already_done", TodoDoneLink.X_ALREADY_DONE);
        assertEquals("code", TodoDoneLink.K_CODE);
        assertEquals("data", TodoDoneLink.K_DATA);
        assertEquals("error", TodoDoneLink.K_ERROR);
        assertEquals(1, TodoDoneLink.CODE_OK);
        assertEquals(2, TodoDoneLink.CODE_BAD_ARGS);
        assertEquals(3, TodoDoneLink.CODE_NOT_FOUND);
        assertEquals(4, TodoDoneLink.CODE_UNKNOWN);
    }

    @Test public void completeResultCodes() {
        assertNull(TodoDoneLink.failReason(1, null));
        assertEquals("这条待办已经不在最近提醒里了", TodoDoneLink.failReason(3, "没有这条待办"));
        assertTrue(TodoDoneLink.failReason(2, "缺 id").contains("缺 id"));
        assertTrue(TodoDoneLink.failReason(4, null).contains("还不支持"));
        assertTrue(TodoDoneLink.failReason(0, null).contains("code=0"));
    }

    @Test public void parsesFullPlannerRowShape() {
        List<TodoVoiceDone.Cand> l = TodoDoneLink.parse("[{\"id\":7,\"title\":\"倒垃圾\",\"due_at\":1,\"state\":\"snoozed\",\"reminded_at\":5,\"first_at\":2,\"strong\":true,\"snooze_until\":9}]");
        assertEquals(1, l.size());
        assertEquals("snoozed", l.get(0).state);
        assertEquals(5, l.get(0).remindedAt);
        assertFalse(l.get(0).ringing());
    }
}
