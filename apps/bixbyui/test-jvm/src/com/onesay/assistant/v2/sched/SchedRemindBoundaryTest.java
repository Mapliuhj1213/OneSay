package com.onesay.assistant.v2.sched;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.router.RouteLayerModel;
import com.onesay.assistant.v2.s3.S3Parser;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * JVM 复现了 —— 修前 SchedEngine.plan（SchedulePlugin TAKEOVER 290，在 S3 300 之前）把
 * 「取消每天8点的提醒」建成日程「取消」、「把吃药的提醒改到9点」建成「把吃药的改到」、「今天有什么提醒」追问「「什么」几点提醒」；
 * 「今晚8点提醒我给妈妈打电话」掉进 AgentLoop。修后：管理 / 查看已有提醒交 S3；带钟点 + 任务动词的提醒本地建。
 */
public class SchedRemindBoundaryTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static TimeZone saved;
    static RouteLayerModel model;

    @BeforeClass public static void setUp() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TZ);
        model = new RouteLayerModel(true);
    }

    @AfterClass public static void tearDown() { TimeZone.setDefault(saved); }

    static long at(int h, int m) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 4, h, m, 0);
        return c.getTimeInMillis();
    }

    static final long NOW = at(10, 0);

    static SchedEngine.Plan plan(String q) { return new SchedEngine(new SchedStore.Memory(), TZ).plan(q, NOW); }

    @Test public void managingRemindersIsNotSchedule() {
        String[] q = {"取消每天8点的提醒", "把吃药的提醒改到9点", "今天有什么提醒", "删掉明天的提醒", "明天的提醒取消", "关掉所有提醒", "看看明天的提醒"};
        for (String s : q) assertNull(s + " 不该被日程接", plan(s));
    }

    @Test public void routedToS3OrAgentNeverSchedCreate() {
        assertEquals("plugin:6t-s3-cancel", model.predict("取消每天8点的提醒").sign);
        assertEquals("plugin:6t-s3-cancel", model.predict("明天的提醒取消").sign);
        assertEquals("plugin:5t-s3-list", model.predict("今天有什么提醒").sign);
        assertTrue(!"plugin:6d-sched".equals(model.predict("把吃药的提醒改到9点").sign));
        assertNull("改已有提醒：S3 不当新建", S3Parser.parse("把吃药的提醒改到9点", NOW, TZ));
        S3Parser.Parsed c = S3Parser.parse("明天的提醒取消", NOW, TZ);
        assertEquals(S3Parser.Type.CANCEL, c.type);
    }

    @Test public void timedTaskReminderCreatedLocally() {
        SchedEngine e = new SchedEngine(new SchedStore.Memory(), TZ);
        SchedEngine.Reply r = e.handle("今晚8点提醒我给妈妈打电话", NOW);
        assertEquals(SchedEngine.Kind.CREATED, r.kind);
        assertTrue(r.text, r.text.contains("给妈妈打电话") && r.text.contains("晚上8点") && r.text.contains("准点"));
    }

    @Test public void designedSplitUnchanged() {
        // 设计内的分工不变：一次性带钟点的提醒 = 日程 + 准点提醒；相对时长 = 计时器；读邮件类定时任务 / 每晚提醒 = S3
        assertEquals("plugin:6d-sched", model.predict("明天下午三点提醒我开会").sign);
        assertEquals("clock.timer.set", model.predict("10分钟后提醒我").sign);
        assertEquals("plugin:6t-s3-create", model.predict("每天早上8点读一下QQ邮箱未读").sign);
        assertEquals("plugin:5t-s3-list", model.predict("我有哪些提醒").sign);
        assertEquals("plugin:6t-s3-cancel", model.predict("取消吃药的提醒").sign);
    }
}
