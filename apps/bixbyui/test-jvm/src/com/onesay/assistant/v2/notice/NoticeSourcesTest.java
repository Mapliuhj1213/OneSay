package com.onesay.assistant.v2.notice;

import com.onesay.assistant.v2.callback.OutboundMail;
import com.onesay.assistant.v2.conn.port.MailDraftPort;
import com.onesay.assistant.v2.conn.port.MailSendPermit;
import com.onesay.assistant.v2.s3.S3Engine;
import com.onesay.assistant.v2.s3.S3Parser;
import com.onesay.assistant.v2.s3.S3Schedule;
import com.onesay.assistant.v2.s3.S3Store;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 来源：日程（解析 / 会议链接 / 优先级 / 提前量 / 连接器缺失降级）、定时邮件（扫描 / 推迟 / 取消）、动作绕不过确认卡。 */
public class NoticeSourcesTest {

    static final TimeZone TZ = NoticeCenterTest.TZ;
    static final long MIN = 60000L;

    // ------------------------------------------------------------------ 日程

    static final String EVENTS = "{\"items\":["
            + "{\"id\":\"e1\",\"summary\":\"周会\",\"start\":{\"dateTime\":\"2026-10-23T10:30:00+08:00\"},\"end\":{\"dateTime\":\"2026-10-23T11:00:00+08:00\"},"
            + "\"hangoutLink\":\"https://meet.google.com/abc-defg-hij\"},"
            + "{\"id\":\"e2\",\"summary\":\"看牙\",\"location\":\"中山路 1 号\",\"start\":{\"dateTime\":\"2026-10-23T02:40:00Z\"},\"end\":{\"dateTime\":\"2026-10-23T03:10:00Z\"}},"
            + "{\"id\":\"e3\",\"summary\":\"国庆\",\"start\":{\"date\":\"2026-10-23\"},\"end\":{\"date\":\"2026-10-24\"}},"
            + "{\"id\":\"e4\",\"summary\":\"评审\",\"description\":\"入会 https://meeting.tencent.com/dm/xyz 密码 123\",\"start\":{\"dateTime\":\"2026-10-23T12:00:00.000+08:00\"},"
            + "\"attendees\":[{\"email\":\"me@x.com\",\"self\":true}]},"
            + "{\"id\":\"e5\",\"status\":\"cancelled\",\"summary\":\"取消了\",\"start\":{\"dateTime\":\"2026-10-23T12:00:00+08:00\"}},"
            + "{\"id\":\"e6\",\"summary\":\"一对一\",\"start\":{\"dateTime\":\"2026-10-23T13:00:00+08:00\"},\"attendees\":[{\"email\":\"me@x.com\",\"self\":true},{\"email\":\"b@x.com\"}],"
            + "\"conferenceData\":{\"entryPoints\":[{\"entryPointType\":\"phone\",\"uri\":\"tel:+1\"},{\"entryPointType\":\"video\",\"uri\":\"https://zoom.us/j/1\"}]}},"
            + "{\"id\":\"e7\",\"summary\":\"坏链接\",\"location\":\"javascript:alert(1)\",\"start\":{\"dateTime\":\"2026-10-23T14:00:00+08:00\"}},"
            + "{\"id\":\"e8\",\"summary\":\"去机场赶航班\",\"start\":{\"dateTime\":\"2026-10-23T15:00:00+08:00\"}}"
            + "]}";

    @Test public void parseGoogleEventsWithLinks() {
        List<CalendarNotices.Event> l = CalendarNotices.parseGoogle(EVENTS, TZ);
        assertEquals("取消的日程跳过", 7, l.size());
        assertEquals("https://meet.google.com/abc-defg-hij", l.get(0).link);
        assertEquals("UTC 时间换算", NoticeCenterTest.at(10, 40), l.get(1).start);
        assertNull(l.get(1).link);
        assertTrue(l.get(2).allDay);
        assertEquals("备注里的腾讯会议链接", "https://meeting.tencent.com/dm/xyz", l.get(3).link);
        assertEquals("自己不算参会人", 0, l.get(3).attendees);
        assertEquals("conferenceData 只认视频入口", "https://zoom.us/j/1", l.get(4).link);
        assertEquals(1, l.get(4).attendees);
        assertNull("非 http(s) 不当会议链接", l.get(5).link);
        assertTrue(CalendarNotices.parseGoogle(null, TZ).isEmpty());
        assertTrue(CalendarNotices.parseGoogle("<html>代理错误页</html>", TZ).isEmpty());
    }

    @Test public void planLeadPriorityAndJoinAction() {
        long now = NoticeCenterTest.at(10, 0);
        List<IslandNotice> l = CalendarNotices.plan(CalendarNotices.parseGoogle(EVENTS, TZ), CalendarNotices.DEFAULT_LEAD_MIN, now, TZ);
        assertEquals("全天日程不提醒", 6, l.size());
        IslandNotice weekly = l.get(0);
        assertEquals("默认开始前 10 分钟", NoticeCenterTest.at(10, 20), weekly.showAt);
        assertEquals("cal:e1:" + NoticeCenterTest.at(10, 30), weekly.dedupKey);
        assertEquals("有会议链接 → 高优先级", IslandNotice.Priority.HIGH, weekly.priority);
        assertEquals("加入", weekly.actions.get(0).label);
        assertEquals(NoticeAction.Kind.OPEN_URL, weekly.actions.get(0).kind);
        assertEquals("10:30–11:00", weekly.summary);
        IslandNotice dentist = l.get(1);
        assertEquals(IslandNotice.Priority.NORMAL, dentist.priority);
        assertEquals("没有链接：只有「知道了」", Arrays.asList("知道了"), dentist.actionLabels());
        assertTrue(dentist.summary.contains("中山路"));
        assertEquals("有其他参会人 → 高", IslandNotice.Priority.HIGH, l.get(3).priority);
        assertEquals("赶点的标题 → 高", IslandNotice.Priority.HIGH, l.get(5).priority);
        List<IslandNotice> l3 = CalendarNotices.plan(CalendarNotices.parseGoogle(EVENTS, TZ), 3, now, TZ);
        assertEquals("提前量可配置", NoticeCenterTest.at(10, 27), l3.get(0).showAt);
        List<IslandNotice> later = CalendarNotices.plan(CalendarNotices.parseGoogle(EVENTS, TZ), 10, NoticeCenterTest.at(10, 40), TZ);
        assertEquals("已开始超过 5 分钟的不提醒", "看牙", later.get(0).title);
    }

    /** 连接器没配置 / 离线：来源返回 null，不报错、不弹通知；中心的 sync(null) 仍然什么都不动（日程来源的「撤下」走 withdrawSource，见 NoticeWithdrawTest）。 */
    @Test public void calendarConnectorMissingDegradesSilently() throws Exception {
        NoticeCenter c = new NoticeCenter(new NoticeStore.Mem(), new NoticeCenter.Clock() {
            @Override public long now() { return NoticeCenterTest.at(10, 0); }
        });
        NoticePolicy.Env env = new NoticePolicy.Env();
        env.tz = TZ;
        NoticeCenter.Tick t = c.sync(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, null, env);
        assertFalse(t.changed());
        assertTrue(c.all().isEmpty());
        // Android 来源：没配置连接器时不轮询（没有在排的日程提醒 → nextPollAt = -1，不叫醒）、poll 在走网络前就抛 Unavailable（静默撤，BUG-I1）；
        // 读失败分两种：连接器缺失 / 授权失效 → Unavailable；网络 / 超时等 → 只记日志返回 null（保留已有提醒，2026-10-23 收窄）
        String src = read("app/src/com/onesay/assistant/v2/notice/NoticeHooks.java");
        int i = src.indexOf("class CalendarSource");
        String cal = src.substring(i, src.indexOf("class MailSource"));
        assertTrue(cal.contains("if (FAKE.isEmpty() && sFailSim == null && !connectorReady()) {")
                && cal.contains("return center(mApp).liveCount(IslandNotice.Source.CALENDAR, id()) > 0 ? now : -1L;"));
        int miss = cal.indexOf("if (!connectorReady()) throw new NoticeSource.Unavailable(");
        assertTrue(miss > 0 && miss < cal.indexOf("api.get(url)"));
        assertTrue(cal.contains("if (!r.ok()) {") && cal.contains("if (NoticePolicy.connectorLost(r.code)) throw new NoticeSource.Unavailable(")
                && cal.contains("return null;"));
        assertFalse("降级不弹任何提醒 / 通知", cal.contains("notify(") || cal.contains("post("));
        String sync = src.substring(src.indexOf("static void syncSource"), src.indexOf("// ================================================================== 上岛"));
        assertTrue("来源抛异常也静默", sync.contains("catch (NoticeSource.Unavailable u)") && sync.contains("catch (Throwable t)") && sync.contains("if (fresh == null) {"));
        assertFalse("撤下也不弹通知", sync.contains("notify("));
    }

    // ------------------------------------------------------------------ 定时邮件

    @Test public void scheduledMailScan() {
        long now = NoticeCenterTest.at(10, 0);
        List<ScheduledMailNotices.Candidate> cands = Arrays.asList(
                new ScheduledMailNotices.Candidate(1, "给张三发邮件说明天开会", now + 30 * MIN, true),
                new ScheduledMailNotices.Candidate(2, "查一下天气", now + 30 * MIN, true),
                new ScheduledMailNotices.Candidate(3, "给李四发邮件", now + 30 * MIN, false),
                new ScheduledMailNotices.Candidate(4, "给王五发邮件", now - MIN, true),
                new ScheduledMailNotices.Candidate(5, "给赵六发邮件", now + 40 * 3600000L, true));
        List<IslandNotice> l = ScheduledMailNotices.plan(cands, ScheduledMailNotices.DEFAULT_LEAD_MIN, now, TZ);
        assertEquals("只提醒：要发邮件、启用、还没到点、36 小时内的", 1, l.size());
        IslandNotice n = l.get(0);
        assertEquals("默认发送前 5 分钟", now + 25 * MIN, n.showAt);
        assertEquals("到点就过期（到点后由确认卡接手）", now + 30 * MIN, n.expireAt);
        assertEquals(Arrays.asList("立即查看", "推迟", "取消发送"), n.actionLabels());
        assertEquals(EnumSet.of(NoticeAction.Kind.VIEW_TASK, NoticeAction.Kind.POSTPONE_MAIL, NoticeAction.Kind.CANCEL_MAIL), kinds(n));
        assertTrue(n.summary.contains("确认卡"));
        long[] occ = ScheduledMailNotices.parseOccurrence(ScheduledMailNotices.occurrenceOf(n.actions.get(1).arg));
        assertEquals(1L, occ[0]);
        assertEquals(now + 30 * MIN, occ[1]);
        assertEquals(10, ScheduledMailNotices.postponeMinutes(n.actions.get(1).arg));
        assertNull(ScheduledMailNotices.parseOccurrence("draft:abc"));
    }

    static Set<NoticeAction.Kind> kinds(IslandNotice n) {
        Set<NoticeAction.Kind> s = EnumSet.noneOf(NoticeAction.Kind.class);
        for (NoticeAction a : n.actions) s.add(a.kind);
        return s;
    }

    @Test public void postponeAndSkipOnlyTouchThisOccurrence() {
        S3Store.Mem store = new S3Store.Mem();
        S3Engine e = new S3Engine(store, TZ);
        long now = NoticeCenterTest.at(7, 50);
        S3Store.Task t = new S3Store.Task();
        t.kind = S3Parser.KIND_AGENT;
        t.repeat = S3Schedule.DAILY;
        t.hour = 8;
        t.minute = 0;
        t.command = "给张三发邮件说早安";
        t.nextAt = NoticeCenterTest.at(8, 0);
        long id = store.addTask(t);
        assertFalse("计划时刻对不上 → 不改", e.postpone(id, t.nextAt + 1, 10 * MIN, now));
        assertTrue(e.postpone(id, NoticeCenterTest.at(8, 0), 10 * MIN, now));
        S3Store.Task t2 = store.task(id);
        assertEquals(NoticeCenterTest.at(8, 10), t2.nextAt);
        assertEquals("规格不变（hour/minute 不动）→ 授权不失效", 8, t2.hour);
        assertEquals(0, t2.minute);
        assertTrue(e.skipOccurrence(id, NoticeCenterTest.at(8, 10), now));
        assertEquals("重复任务跳过这次 → 明天 8:00", NoticeCenterTest.at(8, 0) + 24 * 60 * MIN, store.task(id).nextAt);
        assertTrue(store.task(id).enabled);
        S3Store.Task once = new S3Store.Task();
        once.kind = S3Parser.KIND_AGENT;
        once.repeat = S3Schedule.ONCE;
        once.onceAt = NoticeCenterTest.at(9, 0);
        once.nextAt = once.onceAt;
        once.command = "给李四发邮件";
        long oid = store.addTask(once);
        assertTrue(e.skipOccurrence(oid, once.onceAt, now));
        assertFalse("一次性任务取消 → 停用、不触发", store.task(oid).enabled);
        assertTrue(e.due(NoticeCenterTest.at(9, 1)).isEmpty());
    }

    // ------------------------------------------------------------------ 提醒动作不能绕过邮件确认卡

    static final class RecordingPort implements MailDraftPort {
        final List<String> calls = new ArrayList<String>();
        @Override public boolean available() { return true; }
        @Override public Result createDraft(Draft d) { calls.add("create"); return Result.ok("d1"); }
        @Override public Result updateDraft(String id, Draft d) { calls.add("update"); return Result.ok(id); }
        @Override public Result sendDraft(String id, MailSendPermit p) { calls.add("send"); return Result.ok(id); }
        @Override public Result deleteDraft(String id) { calls.add("delete:" + id); return Result.ok(id); }
    }

    @Test public void mailNoticeActionsNeverSend() throws Exception {
        long now = NoticeCenterTest.at(10, 0);
        final long[] clock = {now};
        NoticeCenter c = new NoticeCenter(new NoticeStore.Mem(), new NoticeCenter.Clock() {
            @Override public long now() { return clock[0]; }
        });
        NoticePolicy.Env env = new NoticePolicy.Env();
        env.tz = TZ;
        // 一个真实的待确认会话（起草 → 存草稿 → 等卡）
        RecordingPort port = new RecordingPort();
        OutboundMail.Recipient r = OutboundMail.verifyRecipient("给 a@b.com 发邮件", "a@b.com", null, null, null);
        OutboundMail.Session s = OutboundMail.start("明天开会改到三点", r, "scheduled", new OutboundMail.Drafter() {
            @Override public String complete(String sys, String user) {
                return "{\"subject\":\"会议调整\",\"body\":\"您好：明天的会议改到下午三点，请知悉。\",\"summary\":\"改期\"}";
            }
        }, port, now);
        assertEquals(OutboundMail.State.AWAIT_CONFIRM, s.state);
        Set<NoticeAction.Kind> allowed = EnumSet.of(NoticeAction.Kind.OPEN_MAIL_CARD, NoticeAction.Kind.VIEW_TASK, NoticeAction.Kind.SNOOZE,
                NoticeAction.Kind.POSTPONE_MAIL, NoticeAction.Kind.CANCEL_MAIL, NoticeAction.Kind.DISMISS);
        List<IslandNotice> mails = new ArrayList<IslandNotice>();
        mails.add(ScheduledMailNotices.draftPending(s.id, "a@b.com", "会议调整", now));
        mails.addAll(ScheduledMailNotices.plan(Arrays.asList(new ScheduledMailNotices.Candidate(9, "给 a@b.com 发邮件说测试", now + 3 * MIN, true)),
                5, now, TZ));
        for (IslandNotice m : mails) {
            for (int i = 0; i < m.actions.size(); i++) {
                NoticeCenter fresh = new NoticeCenter(new NoticeStore.Mem(), new NoticeCenter.Clock() {
                    @Override public long now() { return clock[0]; }
                });
                fresh.post(m, env, null);
                NoticeCenter.Outcome o = fresh.act(m.dedupKey, i, null);
                assertNotNull(o.kind);
                assertTrue("邮件提醒的动作只有：打开确认卡 / 看任务 / 稍后 / 推迟 / 取消 —— " + o, allowed.contains(o.kind));
            }
        }
        assertFalse("点遍所有动作：sendDraft 一次都没被调用", port.calls.contains("send"));
        assertEquals("会话仍在等确认卡", OutboundMail.State.AWAIT_CONFIRM, OutboundMail.session(s.id).state);
        // 「取消发送」走的是 OutboundMail.cancel（删草稿），不是发送
        OutboundMail.cancel(s.id, port);
        assertEquals(Arrays.asList("create", "delete:d1"), port.calls);
        assertTrue(c.all().isEmpty());
    }

    /** 源码扫描：提醒相关代码里没有任何发送 / 签发令牌 / 批准门禁的调用。 */
    @Test public void noticeCodeHasNoSendPath() throws Exception {
        String[] files = {
                "app/src/com/onesay/assistant/v2/notice/NoticeHooks.java", "app/src/com/onesay/assistant/v2/notice/NoticeCenter.java",
                "app/src/com/onesay/assistant/v2/notice/NoticeAction.java", "app/src/com/onesay/assistant/v2/notice/ScheduledMailNotices.java",
                "app/src/com/onesay/assistant/v2/notice/CalendarNotices.java", "app/src/com/onesay/assistant/v2/notice/NoticePolicy.java",
                "app/src/com/onesay/assistant/v2/notice/IslandNotice.java", "app/src/com/onesay/assistant/v2/notice/SqliteNoticeStore.java",
                "app/src/com/onesay/assistant/v2/notice/NoticeSettings.java", "app/src/com/onesay/assistant/v2/notice/NoticeSource.java",
                "app/src/com/onesay/assistant/v2/notice/NoticeStore.java", "app/src/com/onesay/assistant/v2/kernel/plugins/NoticePlugin.java",
                "app/src/com/onesay/assistant/v2/test/NoticeTestDriver.java"};
        String[] forbidden = {".sendDraft(", "confirmSend(", "cardToken(", "MailConfirmTokens", "new MailSendPermit", "SendPermit",
                "ExecutionGate.approve", "UnifiedExecutor", "MailSendGuard", "OutboundMailHooks.begin(", "divertAgentSend(", "runAgentLoop("};
        for (String f : files) {
            String src = stripComments(read(f));
            for (String bad : forbidden) assertFalse(f + " 不能出现 " + bad, src.contains(bad));
        }
        String hooks = read("app/src/com/onesay/assistant/v2/notice/NoticeHooks.java");
        assertTrue("「立即查看」只打开确认卡", hooks.contains("com.onesay.assistant.v2.callback.OutboundMailHooks.show(app, o.arg);"));
    }

    static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    static String read(String rel) throws Exception {
        File f = new File(System.getProperty("bixby.root", "."), rel);
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }
}
