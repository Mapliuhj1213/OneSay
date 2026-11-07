package com.onesay.assistant.v2.lanea;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.jobs.JobMachine.State;
import com.onesay.assistant.v2.jobs.JobQueue;
import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.jobs.JobsEngine;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.Test;

import java.util.TimeZone;

/** lane-a 第 4 项：任务队列（排队、插队、一次一个、进度文字），与定时任务共用状态机。 */
public class LaneAQueueTest {

    static JobsEngine eng() {
        return new JobsEngine(new JobStore.Mem(), TimeZone.getTimeZone("Asia/Shanghai"));
    }

    @Test public void fifoOneAtATimeAndJump() {
        JobsEngine e = eng();
        JobQueue q = new JobQueue(e);
        JobStore.Run a = q.enqueue("查明天天气", 1);
        JobStore.Run b = q.enqueue("整理今天的邮件", 2);
        JobStore.Run c = q.enqueue("看看快递到哪了", 3);
        assertEquals(a.id, q.next().id);
        assertNotNull("共用状态机：begin = 待执行 → 执行中", e.begin(q.next(), 4));
        assertEquals(State.RUNNING, e.store().run(a.id).state);
        assertNull("一次只跑一个", q.next());
        assertEquals(0, q.position(a.id));
        assertEquals(1, q.position(b.id));
        assertNotNull("插队", q.jump(c.id, 5));
        assertEquals(1, q.position(c.id));
        assertEquals(2, q.position(b.id));
        assertEquals("正在：查明天天气 · 还有 2 个排队", q.progressText());
        JobStore.Run ra = e.store().run(a.id);
        e.finish(ra, true, "晴", "NORMAL", false, 6);
        assertEquals("插队的先跑", c.id, q.next().id);
        assertNull("正在跑的不能插队", q.jump(a.id, 7));
    }

    @Test public void waitingConfirmHoldsTheSlot() {
        JobsEngine e = eng();
        JobQueue q = new JobQueue(e);
        JobStore.Run a = q.enqueue("给张三发邮件", 1);
        q.enqueue("查天气", 2);
        JobStore.Run r = e.store().run(a.id);
        e.begin(r, 3);
        r.waitReason = JobsEngine.WAIT_CONFIRM;
        e.apply(r, com.onesay.assistant.v2.jobs.JobMachine.Event.NEED_CONFIRM, 4);
        assertNull("等确认也占着执行位", q.next());
        assertTrue(q.progressText().startsWith("等你确认：给张三发邮件"));
        e.apply(e.store().run(a.id), com.onesay.assistant.v2.jobs.JobMachine.Event.DENIED, 5);
        assertEquals(State.CANCELLED, e.store().run(a.id).state);
        assertNotNull(q.next());
    }

    @Test public void failNoSilentRetryAndCancel() {
        JobsEngine e = eng();
        JobQueue q = new JobQueue(e);
        JobStore.Run a = q.enqueue("x", 1);
        JobStore.Run r = e.store().run(a.id);
        e.begin(r, 2);
        e.finish(r, false, "网络错误", null, false, 3);
        assertEquals("当面交代的事失败就失败，不悄悄重试", State.FAILED, e.store().run(a.id).state);
        JobStore.Run b = q.enqueue("y", 4);
        assertTrue(q.cancel(b.id, 5));
        assertNull(q.next());
        assertNull(q.progressText());
        assertEquals("队列是空的。", q.statusReply());
    }

    @Test public void capacity() {
        JobQueue q = new JobQueue(eng());
        for (int i = 0; i < JobQueue.MAX; i++) assertNotNull(q.enqueue("t" + i, i));
        assertNull(q.enqueue("overflow", 99));
        assertNull(q.enqueue("  ", 99));
    }

    @Test public void parse() {
        assertEquals(JobQueue.Verb.ENQUEUE, JobQueue.parse("排队：查明天天气").verb);
        assertEquals("查明天天气", JobQueue.parse("排队：查明天天气").command);
        assertEquals("整理邮件", JobQueue.parse("先排上，整理邮件").command);
        assertEquals(JobQueue.Verb.JUMP, JobQueue.parse("插队 看看快递").verb);
        assertEquals("看看快递", JobQueue.parse("插队 看看快递").command);
        assertEquals(JobQueue.Verb.STATUS, JobQueue.parse("队列").verb);
        assertEquals(JobQueue.Verb.CLEAR, JobQueue.parse("清空队列。").verb);
        assertNull(JobQueue.parse("排队"));
        assertNull(JobQueue.parse("打开排队软件") );
    }

    @Test public void wiring() {
        String run = KernelWiring.src("app/src/com/onesay/assistant/v2/s3/S3Runner.java");
        assertTrue("队列项做完接着跑下一个", run.contains("JobsHooks.pumpQueue(mCtx)"));
        assertTrue("队列项不走预授权，照常门禁", run.contains("grant == com.onesay.assistant.v2.jobs.JobsEngine.ADHOC"));
        assertTrue("确认卡 = 状态机等确认", run.contains("JobMachine.Event.NEED_CONFIRM, System.currentTimeMillis());   // 状态机：执行中 → 等确认"));
        String cb = KernelWiring.plugin("CallbackPlugin");
        assertTrue(cb.contains("JobQueue.parse(raw)") && cb.contains("q.jump(r.id, now)"));
        String hooks = KernelWiring.src("app/src/com/onesay/assistant/v2/jobs/JobsHooks.java");
        assertTrue("岛上显示队列进度", hooks.contains("ch.bgStep(QUEUE_ISLAND_ID, t)"));
    }
}
