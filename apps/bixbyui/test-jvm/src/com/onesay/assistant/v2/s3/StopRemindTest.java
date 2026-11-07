package com.onesay.assistant.v2.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.router.RouteLayerModel;
import com.onesay.assistant.v2.router.TriageDecider;

import org.junit.Test;

/**
 * ★ AL-07（2026-11-04）：「不要提醒我了」「别提醒我了」「取消提醒」= S3 本地取消直达（6t-s3-cancel），不进 AgentLoop（原约 2.1 s）。
 * 与 J1 否定句规则不冲突：「别提醒我开会」「别打开蓝牙」照旧 0n-negated → ASK。
 */
public class StopRemindTest {

    static final long NOW = S3ScheduleTest.at(10, 9, 10, 0);
    static final S3Engine.Flags ON = new S3Engine.Flags(true, true);
    static final String[] STOP = {"不要提醒我了", "别提醒我了", "别提醒我", "不用提醒我了", "别再提醒了", "不要再提醒我了", "停止提醒", "关掉提醒", "取消提醒", "别提醒我了！"};

    @Test public void parserAndTriage() {
        RouteLayerModel m = new RouteLayerModel(true);
        for (String q : STOP) {
            assertTrue(q, S3Parser.isStopRemind(q));
            S3Parser.Parsed p = S3Parser.parse(q, NOW, S3ScheduleTest.SH);
            assertNotNull(q, p);
            assertEquals(q, S3Parser.Type.CANCEL, p.type);
            assertEquals(q, "6t-s3-cancel", TriageDecider.decide(q, TriageDecider.Signals.offline()).rule);
            assertEquals(q, "plugin:6t-s3-cancel", m.predict(q).sign);
        }
    }

    @Test public void negatedCommandsUntouched() {
        String[] neg = {"别提醒我开会", "别打开蓝牙", "不要关闭wifi", "不要发消息给老王"};
        for (String q : neg) {
            assertFalse(q, S3Parser.isStopRemind(q));
            assertEquals(q, "0n-negated", TriageDecider.decide(q, TriageDecider.Signals.offline()).rule);
        }
    }

    @Test public void cancelsTheOnlyRunningReminder() {
        S3Engine e = new S3Engine(new S3Store.Mem(), S3ScheduleTest.SH);
        assertTrue(S3EngineTest.run(e, "每周一9点提醒我交周报", NOW).ok);
        S3Engine.Result r = e.handle("别提醒我了", NOW, ON);
        assertEquals(S3Capabilities.SIGN_TASK_CANCEL, r.sign);
        assertTrue(e.execute(r.sign, r.args, NOW).ok);
        assertEquals(0, e.activeTasks().size());
    }

    @Test public void noneOrSeveral() {
        S3Engine e = new S3Engine(new S3Store.Mem(), S3ScheduleTest.SH);
        S3Engine.Result none = e.handle("不要提醒我了", NOW, ON);
        assertTrue(none.isLocalReply());
        assertTrue(none.reply, none.reply.contains("没有"));
        assertTrue(S3EngineTest.run(e, "每周一9点提醒我交周报", NOW).ok);
        assertTrue(S3EngineTest.run(e, "每天晚上10点提醒我吃药", NOW).ok);
        S3Engine.Result many = e.handle("别提醒我了", NOW, ON);
        assertTrue(many.isLocalReply());
        assertTrue(many.reply, many.reply.startsWith("要取消哪一个？"));
    }
}
