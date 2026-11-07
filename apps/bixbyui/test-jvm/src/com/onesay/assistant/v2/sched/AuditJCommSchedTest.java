package com.onesay.assistant.v2.sched;

import com.onesay.assistant.v2.router.TriageDecider;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ audit-J 1（Gemini 真机 SCHED-04）：「给老王发微信说明天下午三点开会」不被日程 6d-sched / SchedulePlugin 290 吃掉。 */
public class AuditJCommSchedTest {

    private static TriageDecider.Decision tri(String q) {
        boolean old = TriageDecider.sSchedOn;
        TriageDecider.sSchedOn = true;
        try {
            return TriageDecider.decide(q, new TriageDecider.Signals(null, false, false));
        } finally {
            TriageDecider.sSchedOn = old;
        }
    }

    @Test public void commVerbBeforeTimeIsNotSchedule() {
        String[] comm = {
                "给老王发微信说明天下午三点开会", "发微信给老王说明天下午三点开会", "给老王发消息明天下午三点开会",
                "发短信告诉老婆明天晚上七点吃饭", "微信告诉老王明天下午三点开会", "打电话给老王说明天下午三点开会",
                "跟老王说明天下午三点开会", "告诉小李明天上午十点面试", "通知大家明天下午三点开会", "回复老王明天下午三点开会",
                "给张总发个信息说下周一上午九点开会"
        };
        for (String q : comm) {
            assertFalse(q, SchedEngine.looksLikeSchedule(q));
            assertNotEquals(q, "6d-sched", tri(q).rule);
        }
    }

    @Test public void plainScheduleUnchanged() {
        String[] sched = { "明天下午三点开会", "提醒我明天下午三点开会", "明天下午三点和客户开会", "明天上午十点面试" };
        for (String q : sched) {
            assertTrue(q, SchedEngine.looksLikeSchedule(q));
            assertEquals(q, "6d-sched", tri(q).rule);
        }
    }

    @Test public void commBeforeTimePure() {
        assertTrue(SchedEngine.commBeforeTime("给老王发微信说明天下午三点开会"));
        assertFalse(SchedEngine.commBeforeTime("明天下午三点给老王发微信"));
        assertFalse(SchedEngine.commBeforeTime("告诉我明天的日程"));
        assertFalse(SchedEngine.commBeforeTime("明天下午三点开会"));
    }
}
