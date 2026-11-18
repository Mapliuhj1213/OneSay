package com.onesay.assistant.v2.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.router.NegationGuard;
import com.onesay.assistant.v2.router.TriageDecider;
import com.onesay.assistant.v2.sched.SchedEngine;
import com.onesay.assistant.v2.sched.SchedStore;

import org.junit.Test;

/**
 * ★ fix-1118（OneSay 真机 c20 / 清理用例）：
 * <ul>
 *   <li>「不许7点叫我」以前经 S3 接管建了标题「不许」的提醒，确认卡写 07:00、实际 19:00 响 —— 否定句永不新建；</li>
 *   <li>没说上下午的钟点顺延后，预览 / 钟点跟实际时刻一致；</li>
 *   <li>「取消今天7点的任务」以前被日程建成「取消的任务」—— 取消句不新建，S3 按钟点找到那条再走取消确认。</li>
 * </ul>
 */
public class Fix1118NegCancelTest {

    static final long NOW = S3ScheduleTest.at(10, 9, 10, 30);   // 10:30，「7点」顺延到 19:00
    static final S3Engine.Flags ON = new S3Engine.Flags(true, true);

    @Test public void negatedAlarmNeverCreates() {
        String[] neg = {"不许7点叫我", "别7点提醒我开会", "不要明天8点提醒我", "不准晚上9点叫我"};
        for (String q : neg) {
            assertNotNull(q, NegationGuard.reason(q));
            S3Parser.Parsed p = S3Parser.parse(q, NOW, S3ScheduleTest.SH);
            assertTrue(q + " → " + p, p == null || p.type != S3Parser.Type.CREATE);
            assertFalse(q, "6t-s3-create".equals(TriageDecider.decide(q, TriageDecider.Signals.offline()).rule));
            S3Engine e = new S3Engine(new S3Store.Mem(), S3ScheduleTest.SH);
            S3Engine.Result r = e.handle(q, NOW, ON);
            assertTrue(q, r == null || !S3Capabilities.SIGN_TASK_ADD.equals(r.sign));
        }
        // 正常的照旧
        S3Parser.Parsed ok = S3Parser.parse("7点提醒我吃药", NOW, S3ScheduleTest.SH);
        assertNotNull(ok);
        assertEquals(S3Parser.Type.CREATE, ok.type);
    }

    @Test public void previewMatchesActualTime() throws Exception {
        S3Engine e = new S3Engine(new S3Store.Mem(), S3ScheduleTest.SH);
        S3Engine.Result r = e.handle("7点提醒我吃药", NOW, ON);
        assertNotNull(r);
        assertEquals(19, r.args.getInt("hour"));
        String pv = r.args.getString("preview");
        assertTrue(pv, pv.contains("19:00"));
        assertFalse(pv, pv.contains("07:00"));
    }

    @Test public void cancelByClockTimeNeverCreatesEvent() throws Exception {
        String q = "取消今天7点的任务";
        SchedEngine se = new SchedEngine(new SchedStore.Memory(), S3ScheduleTest.SH);
        assertFalse(q, SchedEngine.looksLikeSchedule(q));
        SchedEngine.Reply sr = se.handle(q, NOW);
        assertTrue(q, sr == null || sr.kind != SchedEngine.Kind.CREATED);
        assertNotEquals("6d-sched", TriageDecider.decide(q, TriageDecider.Signals.offline()).rule);

        S3Engine e = new S3Engine(new S3Store.Mem(), S3ScheduleTest.SH);
        // 一条都没有：本地直说，不新建
        S3Engine.Result miss = e.handle(q, NOW, ON);
        assertNotNull(miss);
        assertTrue(miss.isLocalReply());
        assertNull(miss.sign);
        // 有一条 19:00 的：按钟点认出来，走取消（确认卡）
        assertTrue(S3EngineTest.run(e, "7点提醒我吃药", NOW).ok);
        assertTrue(S3EngineTest.run(e, "每天9点提醒我喝水", NOW).ok);
        S3Engine.Result r = e.handle(q, NOW, ON);
        assertEquals(S3Capabilities.SIGN_TASK_CANCEL, r.sign);
        assertTrue(r.args.getString("preview"), r.args.getString("preview").contains("吃药"));
    }

    @Test public void deleteMissingEventDoesNotCreate() {
        SchedEngine se = new SchedEngine(new SchedStore.Memory(), S3ScheduleTest.SH);
        for (String q : new String[]{"取消明天下午3点的会", "删掉今天7点的任务", "撤掉今晚8点的任务"}) {
            SchedEngine.Reply r = se.handle(q, NOW);
            assertTrue(q, r == null || r.kind != SchedEngine.Kind.CREATED);
        }
        SchedEngine.Reply ok = se.handle("明天下午3点开会", NOW);
        assertEquals(SchedEngine.Kind.CREATED, ok.kind);
    }
}
