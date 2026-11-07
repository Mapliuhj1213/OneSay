package com.onesay.assistant.v2.lanea;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.callback.DeliveryPolicy;
import com.onesay.assistant.v2.callback.DeliveryPolicy.Channel;
import com.onesay.assistant.v2.callback.DeliveryPolicy.Urgency;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.jobs.DailyDigest;
import com.onesay.assistant.v2.jobs.JobMachine;
import com.onesay.assistant.v2.jobs.JobMachine.Event;
import com.onesay.assistant.v2.jobs.JobMachine.State;
import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.jobs.JobsEngine;
import com.onesay.assistant.v2.jobs.Perm;
import com.onesay.assistant.v2.jobs.PermissionAnalyzer;
import com.onesay.assistant.v2.jobs.PermissionGuard;
import com.onesay.assistant.v2.jobs.PermissionGuard.Verdict;
import com.onesay.assistant.v2.jobs.ReliabilityCheck;
import com.onesay.assistant.v2.jobs.TaskGrant;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/** lane-a 第 3 项：任务状态机、权限卡（静态分析 / 授权 / 运行时闸 / 重新授权）、预生成、outbox、送达规则、每日播报、可靠性自检。 */
public class LaneAJobsTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    // ================================================================== 状态机

    @Test public void stateMachineHappyPathAndConfirm() {
        assertEquals(State.RUNNING, JobMachine.next(State.PENDING, Event.START, 0, 3));
        assertEquals(State.WAITING_CONFIRM, JobMachine.next(State.RUNNING, Event.NEED_CONFIRM, 1, 3));
        assertEquals(State.RUNNING, JobMachine.next(State.WAITING_CONFIRM, Event.CONFIRMED, 1, 3));
        assertEquals(State.DONE, JobMachine.next(State.RUNNING, Event.SUCCEED, 1, 3));
        assertEquals("拒绝 = 取消，不是自动批准", State.CANCELLED, JobMachine.next(State.WAITING_CONFIRM, Event.DENIED, 1, 3));
        assertNull("等确认不能直接完成", JobMachine.next(State.WAITING_CONFIRM, Event.SUCCEED, 1, 3));
        assertNull("终态不再迁移", JobMachine.next(State.DONE, Event.START, 1, 3));
        assertNull(JobMachine.next(State.DONE, Event.CANCEL, 1, 3));
    }

    @Test public void stateMachineRetryAndRecover() {
        assertEquals(State.RETRY_WAIT, JobMachine.next(State.RUNNING, Event.FAIL, 1, 3));
        assertEquals(State.FAILED, JobMachine.next(State.RUNNING, Event.FAIL, 3, 3));
        assertEquals(State.PENDING, JobMachine.next(State.RETRY_WAIT, Event.RETRY_DUE, 1, 3));
        assertEquals("被杀后恢复：有余量回待执行", State.PENDING, JobMachine.next(State.RUNNING, Event.RECOVER, 1, 3));
        assertEquals(State.FAILED, JobMachine.next(State.RUNNING, Event.RECOVER, 3, 3));
        assertEquals("等确认跨重启保留", State.WAITING_CONFIRM, JobMachine.next(State.WAITING_CONFIRM, Event.RECOVER, 1, 3));
        assertEquals(60000L, JobMachine.retryDelay(1));
        assertEquals(120000L, JobMachine.retryDelay(2));
        assertEquals(JobMachine.RETRY_CAP_MS, JobMachine.retryDelay(20));
    }

    // ================================================================== 权限静态分析

    @Test public void analyzeCommands() {
        assertEquals(EnumSet.of(Perm.READ_CALENDAR, Perm.WEB_SEARCH), PermissionAnalyzer.analyze("看看今天的日程，再查一下天气", null, null));
        Set<Perm> mail = PermissionAnalyzer.analyze("把今天的会议纪要发邮件给张三", null, null);
        assertTrue(mail.contains(Perm.MAIL_SEND) && mail.contains(Perm.MAIL_DRAFT) && mail.contains(Perm.READ_CALENDAR));
        assertTrue(PermissionAnalyzer.analyze("帮我起草一封邮件", null, null).contains(Perm.MAIL_DRAFT));
        assertFalse(PermissionAnalyzer.analyze("帮我起草一封邮件", null, null).contains(Perm.MAIL_SEND));
        assertTrue(PermissionAnalyzer.analyze("把明天下午的会加到日历", null, null).contains(Perm.WRITE_CALENDAR));
        assertTrue(PermissionAnalyzer.analyze("晚上十点把亮度调低", null, null).contains(Perm.SYSTEM_SETTINGS));
        assertTrue(PermissionAnalyzer.analyze("每天检查一下 root 命令", null, null).contains(Perm.SHELL));
        assertTrue(PermissionAnalyzer.analyze("到点帮我付款", null, null).contains(Perm.PAYMENT));
        assertTrue(PermissionAnalyzer.analyze("删掉旧照片", null, null).contains(Perm.RED_ACTION));
        assertEquals(EnumSet.of(Perm.READ_TODO, Perm.READ_MAIL, Perm.WEB_SEARCH), PermissionAnalyzer.analyze(null, DailyDigest.TEMPLATE_ID, null));
        assertEquals(EnumSet.of(Perm.READ_MAIL, Perm.MAIL_SEND), PermissionAnalyzer.analyze(null, null, Arrays.asList("mail.inbox.latest", "mail.send")));
    }

    @Test public void toolMapping() {
        assertEquals(Perm.MAIL_SEND, PermissionAnalyzer.forTool("mail.send", SideEffect.PUBLIC_COMMS));
        assertEquals(Perm.MAIL_SEND, PermissionAnalyzer.forTool("conn.mail.sendDraft", null));
        assertEquals(Perm.MAIL_DRAFT, PermissionAnalyzer.forTool("mail.draft.new", SideEffect.PERSISTENT_WRITE));
        assertEquals(Perm.READ_MAIL, PermissionAnalyzer.forTool("mail.inbox.latest", SideEffect.PRIVATE_DATA_READ));
        assertEquals(Perm.SYSTEM_SETTINGS, PermissionAnalyzer.forTool("settings.brightness.set", SideEffect.DEVICE_CONTROL));
        assertEquals(Perm.READ_DEVICE, PermissionAnalyzer.forTool("settings.brightness.get", SideEffect.NONE));
        assertEquals(Perm.RED_ACTION, PermissionAnalyzer.forTool("app.uninstall", SideEffect.DESTRUCTIVE));
        assertEquals(Perm.RED_ACTION, PermissionAnalyzer.forTool("unknown.x", SideEffect.PUBLIC_COMMS));
        assertEquals(Perm.SHELL, PermissionAnalyzer.forTool("unknown.y", SideEffect.ROOT));
        assertEquals(Perm.PAYMENT, PermissionAnalyzer.forTool("shop.pay", null));
        assertNull(PermissionAnalyzer.forTool("ask.reply", SideEffect.NONE));
    }

    // ================================================================== 授权

    @Test public void grantStripsNonPregrantable() {
        Set<Perm> req = EnumSet.of(Perm.READ_MAIL, Perm.MAIL_DRAFT, Perm.MAIL_SEND, Perm.PAYMENT, Perm.RED_ACTION);
        TaskGrant g = TaskGrant.grant("spec", req, EnumSet.allOf(Perm.class), 1L);
        assertEquals("发邮件 / 付款 / 红卡不能预授权；清单外的也不授", EnumSet.of(Perm.READ_MAIL, Perm.MAIL_DRAFT), g.granted);
        assertEquals(EnumSet.of(Perm.MAIL_SEND, Perm.PAYMENT, Perm.RED_ACTION), TaskGrant.perRun(req));
        TaskGrant tampered = TaskGrant.restore("spec", "READ_MAIL,MAIL_SEND", "READ_MAIL,MAIL_SEND,PAYMENT", g.fingerprint, 1L);
        assertEquals("库被篡改也授不出发邮件", EnumSet.of(Perm.READ_MAIL), tampered.granted);
        TaskGrant partial = TaskGrant.grant("spec", req, EnumSet.of(Perm.READ_MAIL), 1L);
        assertEquals(EnumSet.of(Perm.MAIL_DRAFT), partial.missing());
    }

    @Test public void reauthWhenSpecOrListChanges() {
        Set<Perm> req = EnumSet.of(Perm.WEB_SEARCH);
        TaskGrant g = TaskGrant.grant("cmd=查天气", req, req, 1L);
        assertFalse(g.needsReauth("cmd=查天气", req));
        assertTrue("指令改了", g.needsReauth("cmd=查天气并发邮件", req));
        assertTrue("清单变了", g.needsReauth("cmd=查天气", EnumSet.of(Perm.WEB_SEARCH, Perm.MAIL_SEND)));
        assertEquals("READ_MAIL,WEB_SEARCH", TaskGrant.encode(EnumSet.of(Perm.WEB_SEARCH, Perm.READ_MAIL)));
        assertEquals(EnumSet.of(Perm.WEB_SEARCH, Perm.READ_MAIL), TaskGrant.decode("WEB_SEARCH,READ_MAIL,BOGUS"));
    }

    @Test public void runtimeGuard() {
        TaskGrant g = TaskGrant.grant("s", EnumSet.of(Perm.READ_MAIL, Perm.WEB_SEARCH, Perm.MAIL_SEND), EnumSet.of(Perm.READ_MAIL, Perm.MAIL_SEND), 1L);
        assertEquals(Verdict.ALLOW, PermissionGuard.check("mail.inbox.latest", SideEffect.PRIVATE_DATA_READ, g, false).verdict);
        assertEquals("清单里有但没勾 → 越权", Verdict.DENY_UNGRANTED, PermissionGuard.check("web.search", null, g, false).verdict);
        assertEquals("清单外 → 越权", Verdict.DENY_UNGRANTED, PermissionGuard.check("settings.brightness.set", SideEffect.DEVICE_CONTROL, g, false).verdict);
        assertEquals("发邮件哪怕勾了也每次确认", Verdict.CONFIRM_EACH_TIME, PermissionGuard.check("mail.send", SideEffect.PUBLIC_COMMS, g, false).verdict);
        assertEquals("本次确认过才放行", Verdict.ALLOW, PermissionGuard.check("mail.send", SideEffect.PUBLIC_COMMS, g, true).verdict);
        assertEquals(Verdict.CONFIRM_EACH_TIME, PermissionGuard.check("shop.pay", null, g, false).verdict);
        assertEquals(Verdict.CONFIRM_EACH_TIME, PermissionGuard.check("app.uninstall", SideEffect.DESTRUCTIVE, g, false).verdict);
        assertEquals("没有授权记录", Verdict.DENY_NO_GRANT, PermissionGuard.check("web.search", null, null, false).verdict);
        assertEquals("纯回答不需要权限", Verdict.ALLOW, PermissionGuard.check("ask.reply", SideEffect.NONE, null, false).verdict);
        assertTrue(PermissionGuard.hint(PermissionGuard.check("web.search", null, g, false), "早报").contains("联网搜索"));
    }

    // ================================================================== 引擎：入库要授权、预生成、过期、恢复

    @Test public void createOnlyAfterConfirm() {
        JobStore.Mem st = new JobStore.Mem();
        JobsEngine e = new JobsEngine(st, TZ);
        long now = at(2026, 10, 23, 6, 0);
        JobsEngine.Pending p = e.prepare(DailyDigest.newJob(7, 30));
        assertTrue("prepare 不写库", st.jobs().isEmpty());
        assertEquals(EnumSet.of(Perm.READ_TODO, Perm.READ_MAIL, Perm.WEB_SEARCH), p.grantable());
        long id = e.confirmCreate(p, EnumSet.of(Perm.READ_TODO, Perm.WEB_SEARCH), now);
        assertTrue(id > 0);
        TaskGrant g = e.validGrant("job:" + id);
        assertNotNull("授权随任务入库", g);
        assertEquals(EnumSet.of(Perm.READ_TODO, Perm.WEB_SEARCH), g.granted);
        assertEquals(at(2026, 10, 23, 7, 30), st.job(id).nextDeliverAt);
    }

    @Test public void editRequiresReauthAndBlocksRuns() {
        JobStore.Mem st = new JobStore.Mem();
        JobsEngine e = new JobsEngine(st, TZ);
        long now = at(2026, 10, 23, 6, 0);
        long id = e.confirmCreate(e.prepare(DailyDigest.newJob(7, 30)), EnumSet.allOf(Perm.class), now);
        JobStore.Job j = st.job(id);
        j.hour = 8;
        j.minute = 0;
        assertTrue("改时间 → 规格变 → 要重新授权", e.edit(j, now));
        assertNull(e.validGrant(j.grantKey()));
        List<JobStore.Run> runs = e.tick(at(2026, 10, 23, 7, 56));
        assertEquals(1, runs.size());
        JobStore.Run r = runs.get(0);
        assertNull("开始执行先查授权：不符 → 等确认", e.begin(r, at(2026, 10, 23, 7, 56)));
        assertEquals(State.WAITING_CONFIRM, st.run(r.id).state);
        assertTrue(st.run(r.id).waitReason.startsWith(JobsEngine.WAIT_REAUTH));
        e.reauthorize(id, EnumSet.of(Perm.READ_TODO), at(2026, 10, 23, 7, 57));
        assertEquals("重新授权后放回待执行", State.PENDING, st.run(r.id).state);
        assertNotNull(e.begin(st.run(r.id), at(2026, 10, 23, 7, 57)));
    }

    @Test public void preGenerateDeliverOnTimeExpireStale() {
        JobStore.Mem st = new JobStore.Mem();
        JobsEngine e = new JobsEngine(st, TZ);
        long id = e.confirmCreate(e.prepare(DailyDigest.newJob(7, 30)), EnumSet.allOf(Perm.class), at(2026, 10, 23, 6, 0));
        long deliver = at(2026, 10, 23, 7, 30);
        assertEquals("下次醒来 = 送达 − 提前量", deliver - JobsEngine.DEFAULT_LEAD_MS, e.earliestNext(at(2026, 10, 23, 6, 0)));
        assertTrue(e.tick(at(2026, 10, 23, 7, 24)).isEmpty());
        List<JobStore.Run> runs = e.tick(at(2026, 10, 23, 7, 25));
        assertEquals("7:25 提前生成", 1, runs.size());
        assertTrue("同一时刻不重复建", e.tick(at(2026, 10, 23, 7, 26)).isEmpty());
        assertEquals("任务排到明天", at(2026, 10, 24, 7, 30), st.job(id).nextDeliverAt);
        JobStore.Run r = runs.get(0);
        assertNotNull(e.begin(r, at(2026, 10, 23, 7, 25)));
        JobStore.Outbox o = e.finish(r, true, "早上好", "NORMAL", true, at(2026, 10, 23, 7, 26));
        assertEquals(State.DONE, st.run(r.id).state);
        assertEquals("没到送达时刻不送", 0, e.dueOutbox(at(2026, 10, 23, 7, 29)).size());
        assertEquals("下一次醒来 = 送达时刻", deliver, e.earliestNext(at(2026, 10, 23, 7, 27)));
        assertEquals("准时送达", 1, e.dueOutbox(deliver).size());
        assertEquals("幂等键：重复写返回原条", o.id, st.addOutbox(dup(o)));
        assertEquals("过期不播", 0, e.dueOutbox(deliver + JobsEngine.STALE_MS + 1).size());
        assertEquals(JobStore.Outbox.EXPIRED, st.outbox().get(0).status);
        int[] rel = e.reliability(deliver + JobsEngine.STALE_MS + 1, 7);
        assertEquals(1, rel[2]);
    }

    static JobStore.Outbox dup(JobStore.Outbox o) {
        JobStore.Outbox d = o.copy();
        d.id = 0;
        return d;
    }

    @Test public void missedAndRecovered() {
        JobStore.Mem st = new JobStore.Mem();
        JobsEngine e = new JobsEngine(st, TZ);
        e.confirmCreate(e.prepare(DailyDigest.newJob(7, 30)), EnumSet.allOf(Perm.class), at(2026, 10, 23, 6, 0));
        List<JobStore.Run> runs = e.tick(at(2026, 10, 23, 9, 0));
        assertTrue("晚了一个半小时 → 错过，不生成", runs.isEmpty());
        assertEquals(State.FAILED, st.runs().get(0).state);
        assertTrue(st.runs().get(0).error.startsWith("错过"));
        // 被杀恢复
        JobStore.Run r = new JobStore.Run();
        r.owner = "q:x";
        r.state = State.RUNNING;
        r.attempts = 1;
        st.addRun(r);
        assertEquals(1, e.recover(at(2026, 10, 23, 9, 1)));
        assertEquals(State.PENDING, st.run(r.id).state);
    }

    @Test public void failRetriesThenFails() {
        JobStore.Mem st = new JobStore.Mem();
        JobsEngine e = new JobsEngine(st, TZ);
        JobStore.Run r = new JobStore.Run();
        r.owner = "q:retry";
        st.addRun(r);
        long t = 1000L;
        for (int i = 1; i <= 3; i++) {
            r = st.run(r.id);
            assertNotNull(e.begin(r, t));
            e.finish(r, false, "网络错误", null, false, t);
            r = st.run(r.id);
            if (i < 3) {
                assertEquals(State.RETRY_WAIT, r.state);
                t = r.nextRetryAt;
                e.tick(t);
                assertEquals(State.PENDING, st.run(r.id).state);
            }
        }
        assertEquals(State.FAILED, st.run(r.id).state);
    }

    @Test public void runtimeBlockedGoesWaitingConfirm() {
        JobStore.Mem st = new JobStore.Mem();
        JobsEngine e = new JobsEngine(st, TZ);
        JobStore.Run r = new JobStore.Run();
        r.owner = "q:b";
        st.addRun(r);
        e.begin(r, 1L);
        e.blocked(r, PermissionGuard.check("web.search", null, TaskGrant.grant("s", EnumSet.of(Perm.WEB_SEARCH), null, 1L), false), 2L);
        assertEquals(State.WAITING_CONFIRM, st.run(r.id).state);
        assertTrue(st.run(r.id).waitReason.startsWith(JobsEngine.WAIT_UNGRANTED));
    }

    // ================================================================== 送达规则

    @Test public void quietHoursAndSilence() {
        assertTrue(DeliveryPolicy.inQuietHours(23 * 60, 23 * 60, 7 * 60));
        assertTrue(DeliveryPolicy.inQuietHours(2 * 60, 23 * 60, 7 * 60));
        assertFalse(DeliveryPolicy.inQuietHours(7 * 60, 23 * 60, 7 * 60));
        assertFalse(DeliveryPolicy.inQuietHours(12 * 60, 23 * 60, 7 * 60));
        assertTrue(DeliveryPolicy.inQuietHours(13 * 60, 12 * 60, 14 * 60));
        assertFalse("相等 = 关闭", DeliveryPolicy.inQuietHours(13 * 60, 600, 600));
        DeliveryPolicy.Env env = new DeliveryPolicy.Env();
        assertEquals(EnumSet.of(Channel.NOTIFY, Channel.ISLAND, Channel.TTS), DeliveryPolicy.channels(Urgency.NORMAL, true, env, 8 * 60));
        assertEquals("免打扰时段不出声", EnumSet.of(Channel.NOTIFY, Channel.ISLAND), DeliveryPolicy.channels(Urgency.NORMAL, true, env, 23 * 60 + 30));
        assertEquals("不要求播报的普通结果不出声", EnumSet.of(Channel.NOTIFY, Channel.ISLAND), DeliveryPolicy.channels(Urgency.NORMAL, false, env, 8 * 60));
        assertEquals(EnumSet.of(Channel.NOTIFY), DeliveryPolicy.channels(Urgency.LOW, true, env, 8 * 60));
        env.inCall = true;
        assertFalse("通话中不出声（哪怕高优先级）", DeliveryPolicy.channels(Urgency.HIGH, true, env, 8 * 60).contains(Channel.TTS));
        env.inCall = false;
        env.dnd = true;
        assertFalse("勿扰不出声", DeliveryPolicy.channels(Urgency.USER_ASKED, true, env, 8 * 60).contains(Channel.TTS));
        env.dnd = false;
        assertTrue("用户亲口要的不受免打扰时段限制", DeliveryPolicy.channels(Urgency.USER_ASKED, false, env, 23 * 60 + 30).contains(Channel.TTS));
        env.quietStartMin = DeliveryPolicy.parseHm("22:30", 0);
        assertEquals(22 * 60 + 30, env.quietStartMin);
        assertFalse("可配置", DeliveryPolicy.maySpeak(env, Urgency.NORMAL, 22 * 60 + 45));
        assertEquals(-1, DeliveryPolicy.parseHm("25:00", -1));
        assertEquals("07:05", DeliveryPolicy.formatHm(DeliveryPolicy.parseHm("7：05", 0)));
        env.islandReady = false;
        assertFalse(DeliveryPolicy.channels(Urgency.NORMAL, false, env, 8 * 60).contains(Channel.ISLAND));
    }

    // ================================================================== 每日播报 / 自检

    @Test public void dailyDigestCompose() {
        String s = DailyDigest.compose(DailyDigest.Part.of(Arrays.asList("交房租", "买菜")),
                DailyDigest.Part.of(Arrays.asList("合同确认\u202e", "周报")), "晴，18 到 26 度");
        assertEquals("早上好。今天有 2 件待办：交房租、买菜。未读重要邮件 2 封：合同确认；周报。天气：晴，18 到 26 度。", s);
        String n = DailyDigest.compose(DailyDigest.Part.note("没授权读取"), DailyDigest.Part.note("还没接入"), "");
        assertEquals("早上好。待办没授权读取。邮件还没接入。天气暂时没查到。", n);
        assertTrue(DailyDigest.compose(DailyDigest.Part.of(Arrays.<String>asList()), DailyDigest.Part.of(Arrays.<String>asList()), "雨").contains("今天没有待办"));
    }

    @Test public void reliabilityParsing() {
        String pkg = "com.onesay.assistant.debug";
        String out = "system-excidle,com.android.shell,2000\nuser,com.onesay.assistant.debug,10234\n" + ReliabilityCheck.SEP + "\nRUN_IN_BACKGROUND: allow; time=+1d\n";
        List<ReliabilityCheck.Item> l = ReliabilityCheck.evaluate(out, pkg, true, 2000L, 1000L, new int[]{5, 0, 0});
        for (ReliabilityCheck.Item it : l) assertEquals(it.title, 1, it.ok);
        assertEquals(0, ReliabilityCheck.dozeWhitelisted("user,com.other,1", pkg));
        assertEquals(0, ReliabilityCheck.runInBackground("RUN_IN_BACKGROUND: ignore"));
        assertEquals(-1, ReliabilityCheck.runInBackground(null));
        // 第 7 项：加入 / 还原改由 PowerWhitelist 按记录的原状态生成（LaneAEventsTest 详测）
        com.onesay.assistant.v2.jobs.PowerWhitelist.Original o = com.onesay.assistant.v2.jobs.PowerWhitelist.parse(
                "\n" + com.onesay.assistant.v2.jobs.PowerWhitelist.SEP + "\nNo operations.\n", "com.onesay.assistant", 27, 1L);
        assertEquals("dumpsys deviceidle whitelist +com.onesay.assistant; (cmd appops set com.onesay.assistant RUN_IN_BACKGROUND allow 2>/dev/null"
                + " || appops set com.onesay.assistant RUN_IN_BACKGROUND allow)", com.onesay.assistant.v2.jobs.PowerWhitelist.applyCmd(o));
        assertEquals("dumpsys deviceidle whitelist -com.onesay.assistant; (cmd appops set com.onesay.assistant RUN_IN_BACKGROUND default 2>/dev/null"
                + " || appops set com.onesay.assistant RUN_IN_BACKGROUND default)", com.onesay.assistant.v2.jobs.PowerWhitelist.restoreCmd(o));
        try {
            ReliabilityCheck.probeCmd("com.x; reboot");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    // ================================================================== 接线

    @Test public void wiring() {
        String run = KernelWiring.src("app/src/com/onesay/assistant/v2/s3/S3Runner.java");
        int g = run.indexOf("PermissionGuard.check(cap.sign, cap.sideEffect, grant, token != null)");
        int exec = run.indexOf("UnifiedExecutor.getInstance(mCtx).execute(cap, args, token, tainted)");
        assertTrue("每一步执行前过权限闸", g > 0 && g < exec);
        assertTrue("越权 → 等确认 + 岛上提示", run.contains("JobsHooks.engine(mCtx).blocked(run, pd") && run.contains("JobsHooks.hint(mCtx, run, hint)"));
        assertTrue("执行前先查授权", run.contains("je.begin(run, System.currentTimeMillis())"));
        assertTrue("被杀后恢复", run.contains("JobsHooks.recover(app)"));
        String hooks = KernelWiring.src("app/src/com/onesay/assistant/v2/s3/S3Hooks.java");
        int card = hooks.indexOf("PermissionCards.forNewS3(");
        int add = hooks.indexOf("engine(ctx).execute(cap.sign, args");
        assertTrue("AGENT 任务先弹权限卡，确认后才入库", card > 0 && card < add && hooks.contains("S3Parser.KIND_AGENT"));
        String sched = KernelWiring.src("app/src/com/onesay/assistant/v2/s3/S3Scheduler.java");
        assertTrue(sched.contains("JobsHooks.earliestNext(ctx)"));
        String man = KernelWiring.src("app/AndroidManifest.xml");
        int i = man.indexOf("com.onesay.assistant.v2.ui.PermissionCardActivity");
        assertTrue(i > 0 && man.indexOf("android:exported=\"false\"", i) - i < 120);
        assertTrue(KernelWiring.src("app/src/com/onesay/assistant/v2/kernel/BuiltinPlugins.java").contains("CallbackPlugin()"));
    }
}
