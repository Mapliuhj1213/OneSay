package com.onesay.assistant.v2.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.onesay.assistant.v2.capability.SideEffect;

/** [s3] 引擎：路由结果、写库、取消、待办（含派生提醒）、到点 / 补触发 / 错过、稍后 10 分钟、红卡任务创建要确认。 */
public class S3EngineTest {

    static final long NOW = S3ScheduleTest.at(10, 9, 10, 0);
    static final S3Engine.Flags ON = new S3Engine.Flags(true, true);

    static S3Engine engine() {
        return new S3Engine(new S3Store.Mem(), S3ScheduleTest.SH);
    }

    static S3Engine.Exec run(S3Engine e, String q, long now) {
        S3Engine.Result r = e.handle(q, now, ON);
        assertNotNull(q, r);
        assertFalse(q + " 应是能力调用", r.isLocalReply());
        return e.execute(r.sign, r.args, now);
    }

    @Test public void createListCancel() {
        S3Engine e = engine();
        final int[] changes = {0};
        e.setOnChange(new Runnable() {
            @Override public void run() {
                changes[0]++;
            }
        });
        S3Engine.Exec x = run(e, "每天早上8点读一下QQ邮箱未读", NOW);
        assertTrue(x.message, x.ok);
        assertEquals("好的，每天 08:00 读一下QQ邮箱未读", x.message);
        assertTrue(run(e, "每周一9点提醒我交周报", NOW).ok);
        assertEquals(2, changes[0]);
        S3Engine.Result l = e.handle("我有哪些定时任务", NOW, ON);
        assertTrue(l.isLocalReply());
        assertTrue(l.reply, l.reply.contains("你有 2 个定时任务") && l.reply.contains("每天 08:00 读一下QQ邮箱未读") && l.reply.contains("每周一 09:00 提醒交周报"));
        S3Engine.Result c = e.handle("取消读邮件那个", NOW, ON);
        assertEquals(S3Capabilities.SIGN_TASK_CANCEL, c.sign);
        assertTrue(e.execute(c.sign, c.args, NOW).ok);
        assertEquals(1, e.activeTasks().size());
        S3Engine.Result miss = e.handle("取消跑步的提醒", NOW, ON);
        assertTrue("找不到但说了提醒：本地回答", miss.isLocalReply() && miss.reply.contains("没找到"));
        assertNull("没说提醒 / 定时、也找不到：不接管（让 S2 的「删掉停车那个」照旧）", e.handle("删掉停车那个", NOW, ON));
    }

    @Test public void redActionTaskNeedsConfirmAtCreation() {
        S3Engine e = engine();
        assertEquals(S3Capabilities.SIGN_TASK_ADD_CONFIRM, e.handle("每天晚上9点给妈妈发短信说晚安", NOW, ON).sign);
        assertEquals(S3Capabilities.SIGN_TASK_ADD, e.handle("每天早上8点读一下QQ邮箱未读", NOW, ON).sign);
        assertEquals("提醒不需要", S3Capabilities.SIGN_TASK_ADD, e.handle("每天晚上9点提醒我给妈妈发短信", NOW, ON).sign);
        assertTrue(S3Capabilities.get(S3Capabilities.SIGN_TASK_ADD_CONFIRM).confirm);
        assertTrue(S3Capabilities.get(S3Capabilities.SIGN_TASK_ADD_CONFIRM).risk >= 2);
        assertFalse(S3Capabilities.get(S3Capabilities.SIGN_TASK_ADD).confirm);
    }

    @Test public void unattendedRedClass() {
        assertTrue(S3Safety.isRedClass("sms.send", SideEffect.PUBLIC_COMMS));
        assertTrue(S3Safety.isRedClass("app.uninstall", SideEffect.DESTRUCTIVE));
        assertTrue(S3Safety.isRedClass("mail.send", SideEffect.PERSISTENT_WRITE));
        assertTrue(S3Safety.isRedClass("x.pay", SideEffect.DEVICE_CONTROL));
        assertTrue(S3Safety.isRedClass("phone.call", SideEffect.DEVICE_CONTROL));
        assertFalse(S3Safety.isRedClass("mail.read_unread", SideEffect.PRIVATE_DATA_READ));
        assertFalse(S3Safety.isRedClass("wifi.on", SideEffect.DEVICE_CONTROL));
        assertTrue(S3Safety.commandNeedsConfirm("给张三发邮件说我请假"));
        assertTrue(S3Safety.commandNeedsConfirm("删掉相册里的截图"));
        assertTrue(S3Safety.commandNeedsConfirm("用支付宝付款"));
        assertFalse(S3Safety.commandNeedsConfirm("读一下QQ邮箱未读"));
    }

    @Test public void fireCatchUpAndMissed() {
        S3Engine e = engine();
        run(e, "每天早上8点读一下QQ邮箱未读", NOW);          // next = 明天 8:00
        run(e, "明天下午3点提醒我取快递", NOW);              // once 明天 15:00
        long d8 = S3ScheduleTest.at(10, 10, 8, 0);
        assertEquals(d8, e.earliestNext());
        assertTrue(e.due(d8 - 1000L).isEmpty());
        List<S3Engine.Fire> f = e.due(d8 + 30L * 60000L);   // 晚了 30 分钟（重启 / Doze）
        assertEquals(1, f.size());
        assertEquals(30, f.get(0).lateMin);
        assertFalse(f.get(0).missed);
        e.fired(f.get(0), d8 + 30L * 60000L);
        assertEquals("每天任务排到后天", S3ScheduleTest.at(10, 10, 15, 0), e.earliestNext());
        // 关机到 17:00：一次性 15:00 的提醒已过 2 小时 → 错过，不补
        List<S3Engine.Fire> g = e.due(S3ScheduleTest.at(10, 10, 17, 0));
        assertEquals(1, g.size());
        assertTrue(g.get(0).missed);
        e.fired(g.get(0), S3ScheduleTest.at(10, 10, 17, 0));
        assertEquals("错过的一次性任务不再出现在列表", 1, e.activeTasks().size());
        assertEquals(S3ScheduleTest.at(10, 11, 8, 0), e.earliestNext());
        // 一次性正常触发 → 删除
        run(e, "今晚8点提醒我倒垃圾", NOW);
        long t20 = S3ScheduleTest.at(10, 9, 20, 0);
        List<S3Engine.Fire> h = e.due(t20);
        assertEquals(1, h.size());
        assertEquals(0, h.get(0).lateMin);
        e.fired(h.get(0), t20);
        for (S3Store.Task t : e.store().tasks()) assertFalse(t.title.equals("倒垃圾"));
    }

    @Test public void snoozeAndRepeatWeekly() {
        S3Engine e = engine();
        long id = e.snooze("交周报", 0, NOW);
        S3Store.Task t = e.store().task(id);
        assertEquals(NOW + 10L * 60000L, t.nextAt);
        assertEquals(S3Schedule.ONCE, t.repeat);
    }

    @Test public void todosAddListDoneAndDerivedReminder() {
        S3Engine e = engine();
        assertTrue(run(e, "记个待办 买牛奶", NOW).ok);
        S3Engine.Exec x = run(e, "记个待办 明天下午3点前交材料", NOW);
        assertTrue(x.message, x.ok);
        assertEquals("截止带钟点 → 派生一条提醒（不出现在定时任务列表）", 0, e.activeTasks().size());
        assertEquals(1, e.store().tasks().size());
        List<S3Store.Todo> open = e.store().openTodos();
        assertEquals("按截止从近到远，无截止的排最后", "交材料", open.get(0).text);
        assertEquals("买牛奶", open.get(1).text);
        String list = e.handle("我的待办", NOW, ON).reply;
        assertTrue(list, list.startsWith("还有 2 项待办") && list.contains("交材料（明天 15:00 前）"));
        assertEquals(0, e.todoDueTodayCount(NOW));
        assertEquals(1, e.todoDueTodayCount(S3ScheduleTest.at(10, 10, 9, 0)));
        S3Engine.Result d = e.handle("交材料做完了", NOW, ON);
        assertEquals(S3Capabilities.SIGN_TODO_DONE, d.sign);
        assertTrue(e.execute(d.sign, d.args, NOW).ok);
        assertEquals("勾销同时删掉派生提醒", 0, e.store().tasks().size());
        assertNull("「作业做完了」不是待办", e.handle("作业做完了", NOW, ON));
        assertTrue(e.handle("待办跑步做完了", NOW, ON).isLocalReply());
    }

    @Test public void flagsGate() {
        S3Engine e = engine();
        assertNull(e.handle("记个待办 买牛奶", NOW, new S3Engine.Flags(true, false)));
        assertNull(e.handle("每天早上8点读一下QQ邮箱未读", NOW, new S3Engine.Flags(false, true)));
        assertNull(e.handle("每天早上8点读一下QQ邮箱未读", NOW, new S3Engine.Flags(false, false)));
    }

    @Test public void fullscreenPolicyParse() {
        assertEquals(Boolean.TRUE, com.onesay.assistant.v2.s1.FullscreenPolicy.parse("    mTopIsFullscreen=true mForceStatusBar=false"));
        assertEquals(Boolean.FALSE, com.onesay.assistant.v2.s1.FullscreenPolicy.parse("mTopIsFullscreen=false"));
        assertEquals(Boolean.TRUE, com.onesay.assistant.v2.s1.FullscreenPolicy.parse("mLastSystemUiFlags=0x00000404"));
        assertNull(com.onesay.assistant.v2.s1.FullscreenPolicy.parse("nothing here"));
    }
}
