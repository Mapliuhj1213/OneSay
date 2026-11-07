package com.onesay.assistant.v2.sched;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.router.RouteLayerModel;
import com.onesay.assistant.v2.router.TriageDecider;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * ★ NG-07（2026-11-04）：「别忘了交房租」被当记忆写入（rule#6m-memory-write）→ 应走提醒；缺时间按既有追问流程问一次。
 * 「记住 / 记一下 + 事实」与「别忘了 + 事实」照旧是记忆写入。
 */
public class ForgetRemindTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static TimeZone saved;
    static RouteLayerModel model;

    @BeforeClass public static void setUp() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TZ);
        model = new RouteLayerModel(true);
    }

    @AfterClass public static void tearDown() { TimeZone.setDefault(saved); }

    static long at(int month, int day, int h, int m) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, month - 1, day, h, m, 0);
        return c.getTimeInMillis();
    }

    static final long NOW = at(11, 4, 21, 0);

    static SchedEngine sched() { return new SchedEngine(new SchedStore.Memory(), TZ); }

    @Test public void forgetTaskAsksWhenOnce() {
        SchedEngine e = sched();
        SchedEngine.Reply q = e.handle("别忘了交房租", NOW);
        assertEquals(SchedEngine.Kind.ASK, q.kind);
        assertEquals("什么时候提醒你「交房租」？", q.text);
        SchedEngine.Reply a = e.handle("明天上午十点", NOW + 10000L);
        assertEquals(SchedEngine.Kind.CREATED, a.kind);
        assertTrue(a.text, a.text.contains("交房租") && a.text.contains("上午10点"));
        assertTrue(a.text, a.text.contains("准点"));
    }

    @Test public void askOnlyOnceThenDrops() {
        SchedEngine e = sched();
        assertEquals(SchedEngine.Kind.ASK, e.handle("别忘了交房租", NOW).kind);
        // 答非所问：不再缠着问，交回正常路由
        assertEquals(null, e.handle("打开蓝牙", NOW + 5000L));
    }

    @Test public void variantsAreReminders() {
        String[][] cases = {
                {"别忘了交房租", "交房租"}, {"不要忘了给妈妈打电话", "给妈妈打电话"}, {"记得给妈妈打电话", "给妈妈打电话"},
                {"别忘了带钥匙", "带钥匙"}, {"千万别忘了还信用卡", "还信用卡"}, {"提醒我交房租", "交房租"},
                {"别忘了提醒我交房租", "交房租"}, {"别忘了吃药", "吃药"},
        };
        for (String[] c : cases) {
            SchedEngine.Reply r = sched().handle(c[0], NOW);
            assertEquals(c[0], SchedEngine.Kind.ASK, r.kind);
            assertEquals(c[0], "什么时候提醒你「" + c[1] + "」？", r.text);
            assertEquals(c[0], "plugin:6d-sched", model.predict(c[0]).sign);
            assertTrue(c[0], SchedEngine.isForgetRemindTask(c[0]));
        }
    }

    @Test public void withTimeStillCreatesDirectly() {
        SchedEngine.Reply r = sched().handle("别忘了明天交房租", NOW);
        assertEquals(SchedEngine.Kind.CREATED, r.kind);
        assertEquals("plugin:6d-sched", model.predict("记住明天三点开会").sign);
    }

    @Test public void realMemoryWritesUntouched() {
        String[] mem = {"记住我的车停在B2-17", "记一下我的工号是123", "别忘了我的车停在B2", "别忘了我的工号是123", "记住我女儿生日是5月3号"};
        for (String q : mem) {
            assertFalse(q, SchedEngine.isForgetRemindTask(q));
            assertEquals(q, "plugin:6m-memory-write", model.predict(q).sign);
        }
        // 问句不是任务
        assertFalse(SchedEngine.isForgetRemindTask("记得我车停哪吗"));
    }

    @Test public void triageNotMemoryEvenWhenSchedOff() {
        boolean was = TriageDecider.sSchedOn;
        try {
            TriageDecider.sSchedOn = false;
            TriageDecider.Decision d = TriageDecider.decide("别忘了交房租", TriageDecider.Signals.offline(), true, true, false);
            assertNotEquals("6m-memory-write", d.rule);
            assertEquals("6m-memory-write", TriageDecider.decide("记住我的车停在B2-17", TriageDecider.Signals.offline(), true, true, false).rule);
        } finally {
            TriageDecider.sSchedOn = was;
        }
    }
}
