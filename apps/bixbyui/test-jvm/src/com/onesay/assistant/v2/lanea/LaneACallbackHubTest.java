package com.onesay.assistant.v2.lanea;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.callback.CallbackHistory;
import com.onesay.assistant.v2.callback.DeliveryPolicy;
import com.onesay.assistant.v2.callback.DeliveryPolicy.Urgency;
import com.onesay.assistant.v2.callback.DigestWindow;
import com.onesay.assistant.v2.callback.OutboundMail;
import com.onesay.assistant.v2.callback.WorthTelling;
import com.onesay.assistant.v2.conn.port.MailConfirmTokens;
import com.onesay.assistant.v2.conn.port.MailDraftPort;
import com.onesay.assistant.v2.conn.port.MailSendPermit;
import com.onesay.assistant.v2.conn.port.Ports;
import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** lane-a 第 5 项：worth_telling、汇总队列 + 送达窗口、回调历史、对外发邮件（起草 → 草稿 → 确认卡 → 发送）。 */
public class LaneACallbackHubTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");

    static long at(int h, int m) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, 9, 23, h, m, 0);
        return c.getTimeInMillis();
    }

    // ================================================================== worth_telling

    @Test public void worthTellingOnlyLowers() {
        WorthTelling.Verdict v = WorthTelling.judge("快递已到驿站。\n{\"worth_telling\": true, \"urgency\": \"high\", \"one_line\": \"快递到了\"}", Urgency.NORMAL);
        assertTrue(v.parsed && v.worth);
        assertEquals("模型说 high 也只能按任务的 NORMAL", Urgency.NORMAL, v.urgency);
        assertEquals("快递已到驿站。", v.body);
        assertEquals("快递到了", v.oneLine);
        WorthTelling.Verdict low = WorthTelling.judge("股价没大动。{\"worth_telling\": true, \"urgency\": \"low\"}", Urgency.NORMAL);
        assertEquals(Urgency.LOW, low.urgency);
        WorthTelling.Verdict no = WorthTelling.judge("和上次一样。{\"worth_telling\": false, \"urgency\": \"normal\"}", Urgency.NORMAL);
        assertFalse(no.worth);
        assertEquals(Urgency.LOW, no.urgency);
        WorthTelling.Verdict bare = WorthTelling.judge("没有变化", Urgency.NORMAL);
        assertFalse("监控类「没有变化」不报", bare.worth);
        WorthTelling.Verdict broken = WorthTelling.judge("今天有雨。{\"worth_telling\": tru", Urgency.NORMAL);
        assertTrue("判不出 → 照常送达，不漏报", broken.worth && !broken.parsed && broken.urgency == Urgency.NORMAL);
        assertTrue(WorthTelling.PROMPT_SUFFIX.contains("worth_telling"));
    }

    // ================================================================== 汇总 / 窗口

    @Test public void windowsAndMerge() {
        assertEquals(at(8, 0), DigestWindow.nextWindow(at(6, 0), null, TZ));
        assertEquals(at(12, 30), DigestWindow.nextWindow(at(8, 0), null, TZ));
        assertEquals(at(19, 0), DigestWindow.nextWindow(at(13, 0), null, TZ));
        assertEquals(at(8, 0) + 86400000L, DigestWindow.nextWindow(at(20, 0), null, TZ));
        DeliveryPolicy.Env env = new DeliveryPolicy.Env();
        assertTrue(DigestWindow.shouldDefer(Urgency.LOW, env));
        assertFalse(DigestWindow.shouldDefer(Urgency.NORMAL, env));
        env.inCall = true;
        assertTrue("通话中普通结果等窗口", DigestWindow.shouldDefer(Urgency.NORMAL, env));
        assertFalse(DigestWindow.shouldDefer(Urgency.HIGH, env));
        List<JobStore.Outbox> l = new ArrayList<JobStore.Outbox>();
        l.add(ob("快递", "到驿站了", "LOW", false));
        l.add(ob("股价", "涨了 3%", "NORMAL", true));
        DigestWindow.Merged m = DigestWindow.merge(l);
        assertEquals("2 件事：快递：到驿站了；股价：涨了 3%。", m.text);
        assertEquals(Urgency.NORMAL, m.urgency);
        assertTrue(m.wantSpeak);
        assertEquals("到驿站了", DigestWindow.merge(l.subList(0, 1)).text);
    }

    static JobStore.Outbox ob(String title, String text, String u, boolean speak) {
        JobStore.Outbox o = new JobStore.Outbox();
        o.title = title;
        o.text = text;
        o.urgency = u;
        o.wantSpeak = speak;
        return o;
    }

    @Test public void historyText() {
        JobStore.Outbox o = ob("早报", "早上好。", "NORMAL", true);
        o.status = JobStore.Outbox.DELIVERED;
        o.deliveredVia = "merged island tts notify";
        assertEquals("已送达", CallbackHistory.status(o)[0]);
        assertEquals("[合并 岛 播报 通知] 早上好。", CallbackHistory.line(o));
        o.status = JobStore.Outbox.EXPIRED;
        assertEquals("过期未播", CallbackHistory.status(o)[0]);
    }

    // ================================================================== 对外发邮件

    static final List<OutboundMail.Contact> CONTACTS = Arrays.asList(
            new OutboundMail.Contact("张三", "zhangsan@corp.com"),
            new OutboundMail.Contact("李四", "lisi@a.com"),
            new OutboundMail.Contact("李四", "lisi@b.com"),
            new OutboundMail.Contact("王五", "not-an-email"));

    @Test public void recipientMustBeSaidOrVerified() {
        OutboundMail.Recipient r = OutboundMail.verifyRecipient("给张三发邮件", "张三", CONTACTS, null, null);
        assertEquals(OutboundMail.RecipientStatus.VERIFIED, r.status);
        assertEquals("zhangsan@corp.com", r.address);
        assertEquals("同名两个邮箱 → 不猜", OutboundMail.RecipientStatus.AMBIGUOUS,
                OutboundMail.verifyRecipient("给李四发邮件", "李四", CONTACTS, null, null).status);
        assertEquals(OutboundMail.RecipientStatus.UNVERIFIED, OutboundMail.verifyRecipient("给赵六发邮件", "赵六", CONTACTS, null, null).status);
        assertEquals("通讯录里地址不合法不算", OutboundMail.RecipientStatus.UNVERIFIED,
                OutboundMail.verifyRecipient("给王五发邮件", "王五", CONTACTS, null, null).status);
        OutboundMail.Recipient said = OutboundMail.verifyRecipient("发邮件给 boss@x.org 说我请假", "boss@x.org", null, null, null);
        assertEquals("用户亲口说出的地址", OutboundMail.RecipientStatus.VERIFIED, said.status);
        assertEquals("模型给的地址不算用户说的：要在往来邮件里核实", OutboundMail.RecipientStatus.UNVERIFIED,
                OutboundMail.verifyRecipient("", "evil@x.org", CONTACTS, null, null).status);
        assertEquals(OutboundMail.RecipientStatus.VERIFIED, OutboundMail.verifyRecipient("", "boss@x.org", null,
                Arrays.asList(new OutboundMail.Contact("Boss", "boss@x.org")), null).status);
        assertEquals("me@x.org", OutboundMail.verifyRecipient("发给我自己", "我自己", null, null, "me@x.org").address);
    }

    @Test public void parseCommands() {
        assertArrayEquals(new String[]{"张三", "明天的会改到三点"}, OutboundMail.parseCommand("给张三发邮件，说明天的会改到三点"));
        assertArrayEquals(new String[]{"李四", "周报晚一天交"}, OutboundMail.parseCommand("发封邮件给李四：周报晚一天交。"));
        assertEquals("boss@x.org", OutboundMail.parseCommand("发邮件给boss@x.org 我今天请假")[0]);
        assertNull(OutboundMail.parseCommand("读一下邮件"));
        assertNull(OutboundMail.parseCommand("给张三发微信"));
    }

    /** 假大模型：按顺序返回。 */
    static final class FakeLlm implements OutboundMail.Drafter {
        final List<String> out;
        final List<String> users = new ArrayList<String>();
        int i;

        FakeLlm(String... out) {
            this.out = Arrays.asList(out);
        }

        @Override public String complete(String system, String user) {
            users.add(user);
            return out.get(Math.min(i++, out.size() - 1));
        }
    }

    /** 假草稿接口：记调用。 */
    static final class FakePort implements MailDraftPort {
        final List<String> calls = new ArrayList<String>();
        boolean sendOk = true;

        @Override public boolean available() { return true; }
        @Override public Result createDraft(Draft d) { calls.add("create:" + d.to + ":" + d.subject); String id = "d" + (++seq); drafts.put(id, d); return Result.ok(id); }
        @Override public Result updateDraft(String id, Draft d) { calls.add("update:" + id + ":" + d.subject); drafts.put(id, d); return Result.ok(id); }
        final java.util.Map<String, Draft> drafts = new java.util.HashMap<String, Draft>();
        int seq;
        @Override public Result sendDraft(String id, MailSendPermit permit) {
            String bad = permit == null ? "no permit" : permit.use(id, drafts.get(id).fingerprint());
            calls.add("send:" + id + ":" + (bad == null));
            if (bad != null) return Result.fail(bad);
            return sendOk ? Result.ok(id) : Result.fail("x");
        }
        @Override public Result deleteDraft(String id) { calls.add("delete:" + id); return Result.ok(id); }
    }

    static final String D1 = "{\"subject\":\"会议时间调整\",\"body\":\"张三您好：明天的会议调整到下午三点，请知悉。\",\"summary\":\"会议改到明天三点\"}";
    static final String D2 = "{\"subject\":\"关于明日会议时间的调整\",\"body\":\"张三您好：非常抱歉，明天的会议需调整至下午三点。\",\"summary\":\"会议改期（更客气）\"}";

    @Test public void draftConfirmModifySend() {
        OutboundMail.Recipient r = OutboundMail.verifyRecipient("给张三发邮件", "张三", CONTACTS, null, null);
        FakeLlm llm = new FakeLlm(D1, D2);
        FakePort port = new FakePort();
        OutboundMail.Session s = OutboundMail.start("明天的会改到三点", r, "voice", llm, port, 1L);
        assertEquals("起草 + 存草稿后停在等确认", OutboundMail.State.AWAIT_CONFIRM, s.state);
        assertEquals(Arrays.asList("create:zhangsan@corp.com:会议时间调整"), port.calls);
        assertTrue(llm.users.get(0).contains("<意图>明天的会改到三点</意图>"));
        OutboundMail.modify(s.id, "再客气一点", llm, port);
        assertEquals("修改 = 重新起草并覆盖原草稿", "update:d1:关于明日会议时间的调整", port.calls.get(1));
        assertTrue(llm.users.get(1).contains("<修改意见>再客气一点</修改意见>") && llm.users.get(1).contains("<上一版主题>会议时间调整"));
        assertEquals(1, s.revisions);
        OutboundMail.confirmSend(s.id, null, port, 2L);
        assertEquals("没有卡片令牌 → 不发，连 sendDraft 都不调", 2, port.calls.size());
        OutboundMail.confirmSend(s.id, "forged-token", port, 2L);
        assertEquals("伪造令牌 → 不发", 2, port.calls.size());
        String tok = OutboundMail.cardToken(s.id, 3L);
        assertNotNull(tok);
        OutboundMail.confirmSend(s.id, tok, port, 4L);
        assertEquals("卡片「发送」消费令牌换许可，许可绑定草稿号 + 指纹", "send:d1:true", port.calls.get(2));
        assertEquals(OutboundMail.State.SENT, s.state);
        assertNull("发完会话作废，不能重发", OutboundMail.session(s.id));
    }

    @Test public void copyRejectedAndUnverifiedNeverDrafted() {
        String intent = "明天上午十点的项目评审会推迟到下午三点请大家准时参加";
        String copy = "{\"subject\":\"通知\",\"body\":\"明天上午十点的项目评审会推迟到下午三点，请大家准时参加。\",\"summary\":\"x\"}";
        FakeLlm llm = new FakeLlm(copy, copy);
        FakePort port = new FakePort();
        OutboundMail.Recipient r = OutboundMail.verifyRecipient("给张三发邮件", "张三", CONTACTS, null, null);
        OutboundMail.Session s = OutboundMail.start(intent, r, "voice", llm, port, 1L);
        assertEquals("两次都照抄原话 → 不存草稿", OutboundMail.State.FAILED, s.state);
        assertTrue(port.calls.isEmpty());
        assertEquals(2, llm.users.size());
        FakePort p2 = new FakePort();
        OutboundMail.Session u = OutboundMail.start("x", OutboundMail.verifyRecipient("", "赵六", CONTACTS, null, null), "scheduled",
                new FakeLlm(D1), p2, 1L);
        assertEquals(OutboundMail.State.FAILED, u.state);
        assertTrue("核实不了收件人 → 连草稿都不建", p2.calls.isEmpty());
        assertTrue(u.error.contains("邮箱地址"));
    }

    @Test public void cancelDeletesAndStubNeverSends() {
        FakePort port = new FakePort();
        OutboundMail.Session s = OutboundMail.start("会改期", OutboundMail.verifyRecipient("给张三发邮件", "张三", CONTACTS, null, null),
                "voice", new FakeLlm(D1), port, 1L);
        OutboundMail.cancel(s.id, port);
        assertEquals(OutboundMail.State.CANCELLED, s.state);
        assertEquals("delete:d1", port.calls.get(1));
        OutboundMail.Session st = OutboundMail.start("会改期", OutboundMail.verifyRecipient("给张三发邮件", "张三", CONTACTS, null, null),
                "voice", new FakeLlm(D1), Ports.STUB_DRAFT, 1L);
        assertEquals(OutboundMail.State.AWAIT_CONFIRM, st.state);
        OutboundMail.confirmSend(st.id, OutboundMail.cardToken(st.id, 2L), Ports.STUB_DRAFT, 3L);
        assertEquals("桩：B 线没接入前发送一律失败（草稿留着）", OutboundMail.State.AWAIT_CONFIRM, st.state);
        assertTrue(st.error, st.error.contains("还没接入"));
        assertFalse(Ports.STUB_DRAFT.sendDraft("x", null).ok);
    }

    // ================================================================== 确认卡令牌 / 发送许可

    @Test public void modifyInvalidatesOldCardAndTokenSingleUse() {
        FakePort port = new FakePort();
        OutboundMail.Session s = OutboundMail.start("明天的会改到三点", OutboundMail.verifyRecipient("给张三发邮件", "张三", CONTACTS, null, null),
                "voice", new FakeLlm(D1, D2), port, 1L);
        String oldCard = OutboundMail.cardToken(s.id, 2L);
        OutboundMail.modify(s.id, "再客气一点", new FakeLlm(D2), port);
        OutboundMail.confirmSend(s.id, oldCard, port, 3L);
        assertEquals("修改后旧卡片的令牌作废，不能把新版本发出去", OutboundMail.State.AWAIT_CONFIRM, s.state);
        assertFalse(port.calls.toString().contains("send:"));
        assertTrue(s.error, s.error.contains("确认卡"));
        String newCard = OutboundMail.cardToken(s.id, 4L);
        port.sendOk = false;
        OutboundMail.confirmSend(s.id, newCard, port, 5L);
        assertEquals("发送失败草稿留着", OutboundMail.State.AWAIT_CONFIRM, s.state);
        int sends = port.calls.size();
        OutboundMail.confirmSend(s.id, newCard, port, 6L);
        assertEquals("令牌只能用一次", sends, port.calls.size());
        port.sendOk = true;
        String late = OutboundMail.cardToken(s.id, 7L);
        OutboundMail.confirmSend(s.id, late, port, 7L + MailConfirmTokens.TTL_MS + 1);
        assertEquals("卡片放太久令牌过期", sends, port.calls.size());
        OutboundMail.confirmSend(s.id, OutboundMail.cardToken(s.id, 8L), port, 9L);
        assertEquals(OutboundMail.State.SENT, s.state);
    }

    @Test public void permitBoundToDraftAndFingerprint() {
        MailDraftPort.Draft d = new MailDraftPort.Draft("a@b.com", "主题", "正文");
        StringBuilder why = new StringBuilder();
        String t = MailConfirmTokens.issue("d9", d.fingerprint(), 1L);
        assertNull("草稿号不对", MailConfirmTokens.consume(t, "d8", d.fingerprint(), 2L, why));
        assertNull("消费失败也作废", MailConfirmTokens.consume(t, "d9", d.fingerprint(), 2L, new StringBuilder()));
        t = MailConfirmTokens.issue("d9", d.fingerprint(), 1L);
        MailSendPermit p = MailConfirmTokens.consume(t, "d9", d.fingerprint(), 2L, why);
        assertNotNull(p);
        assertNotNull("许可对别的指纹无效", p.use("d9", new MailDraftPort.Draft("a@b.com", "主题", "正文改").fingerprint()));
        assertNull(p.use("d9", d.fingerprint()));
        assertNotNull("许可只能用一次", p.use("d9", d.fingerprint()));
        assertNull("空草稿号不签发", MailConfirmTokens.issue("", "x", 1L));
    }

    @Test public void fingerprintMatchesLaneB() throws Exception {
        // B 线 MailDraft.contentHash() = sha256Hex("to=..\ncc=..\nsubject=..\nbody=..").substring(0, 32)；A 线 cc 固定为空
        byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest("to=a@b.com\ncc=\nsubject=s\nbody=b".getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : h) sb.append(String.format("%02x", x & 0xff));
        assertEquals(sb.substring(0, 32), new MailDraftPort.Draft("a@b.com", "s", "b").fingerprint());
    }

    @Test public void fingerprintEqualsLaneBLedgerAfterNormalization() {
        // 审计 g4：B 的 MailDraft 会规范化（正文 CRLF→LF、主题换行→空格、收件人分隔符），台账指纹按规范化后的内容算；
        // A 的指纹必须是同一个值，否则 LaneBMailDraftAdapter 里 permit.use(草稿号, 台账指纹) 会把正常确认误判成「被改过」
        String[][] cases = {
                {"a@b.com", "s", "第一行\r\n第二行"},
                {"a@b.com", "周会\n改期", "b"},
                {" a@b.com ", "s", "b"},
        };
        for (String[] c : cases) {
            assertEquals(new com.onesay.assistant.v2.conn.mail.MailDraft(c[0], "", c[1], c[2]).contentHash(),
                    new MailDraftPort.Draft(c[0], c[1], c[2]).fingerprint());
        }
    }

    @Test public void scheduledOnlySavesDraft() {
        assertTrue(OutboundMail.popCardNow("voice"));
        assertTrue(OutboundMail.popCardNow("agent"));
        assertFalse("定时任务不弹卡，只存草稿 + 通知", OutboundMail.popCardNow("scheduled"));
        assertFalse(OutboundMail.popCardNow("queue"));
        FakePort port = new FakePort();
        OutboundMail.Session s = OutboundMail.start("提醒张三交周报", OutboundMail.verifyRecipient("每天九点提醒张三交周报", "张三", CONTACTS, null, null),
                "scheduled", new FakeLlm(D1), port, 1L);
        assertEquals("定时任务最多走到「存草稿 + 等确认」", OutboundMail.State.AWAIT_CONFIRM, s.state);
        assertEquals(Arrays.asList("create:zhangsan@corp.com:会议时间调整"), port.calls);
    }

    // ================================================================== 接线：没有不经确认的发送路径

    @Test public void onlyOneSendPath() throws Exception {
        List<String> hits = new ArrayList<String>();
        List<String> mints = new ArrayList<String>();
        scan(new File(System.getProperty("bixby.root", "."), "app/src"), hits, mints);
        // conn/ 下只允许端口实现（合并时的 B 线适配器）调用；业务代码里只有确认卡路径
        List<String> biz = new ArrayList<String>();
        for (String h : hits) if (!h.startsWith("com/onesay/assistant/v2/conn/")) biz.add(h);
        assertEquals("业务代码里只有 OutboundMail.confirmSend 调 sendDraft", Arrays.asList("com/onesay/assistant/v2/callback/OutboundMail.java"), biz);
        assertEquals("MailSendPermit 只在 MailConfirmTokens.consume 里造", Arrays.asList("com/onesay/assistant/v2/conn/port/MailConfirmTokens.java"), mints);
        String permit = KernelWiring.src("app/src/com/onesay/assistant/v2/conn/port/MailSendPermit.java");
        assertTrue("许可构造器包私有", permit.contains("\n    MailSendPermit(String draftId") && !permit.contains("public MailSendPermit("));
        String port = KernelWiring.src("app/src/com/onesay/assistant/v2/conn/port/MailDraftPort.java");
        assertTrue("端口只有 sendDraft(draftId, MailSendPermit)，没有直接发送的方法",
                port.contains("Result sendDraft(String draftId, MailSendPermit permit);") && !port.contains("Result send("));
        String om = KernelWiring.src("app/src/com/onesay/assistant/v2/callback/OutboundMail.java");
        int cs = om.indexOf("public static Session confirmSend(String sessionId, String cardToken, MailDraftPort port, long now)");
        assertTrue(cs > 0 && om.indexOf("MailConfirmTokens.consume(", cs) > cs && om.indexOf("port.sendDraft(", cs) > om.indexOf("MailConfirmTokens.consume(", cs));
        String act = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/MailConfirmActivity.java");
        assertTrue("令牌只在确认卡渲染时签发、按钮里消费", act.contains("OutboundMail.cardToken(mId") && act.contains("OutboundMail.confirmSend(id, token, Ports.mailDraft()"));
        // 除了确认卡，没有别的地方签发令牌或调 confirmSend
        for (String f : new String[]{"callback/OutboundMailHooks.java", "kernel/plugins/CallbackPlugin.java", "s3/S3Runner.java", "router/AgentLoop.java", "jobs/JobsEngine.java"}) {
            String src = KernelWiring.src("app/src/com/onesay/assistant/v2/" + f);
            assertFalse(f + " 不能签发令牌 / 发送", src.contains("cardToken(") || src.contains("confirmSend(") || src.contains("MailConfirmTokens."));
        }
        String cb = KernelWiring.plugin("CallbackPlugin");
        assertTrue("AgentLoop / 定时任务 / 队列里的发送类工具一律拦下改为起草 + 卡片",
                cb.contains("agentPrecheck(Context ctx, String name") && cb.contains("Perm.MAIL_SEND") && cb.contains("divertAgentSend("));
        String run = KernelWiring.src("app/src/com/onesay/assistant/v2/s3/S3Runner.java");
        assertTrue(run.contains("WorthTelling.PROMPT_SUFFIX") && run.contains("OutboundMailHooks.UNATTENDED.set("));
        String hooks = KernelWiring.src("app/src/com/onesay/assistant/v2/callback/OutboundMailHooks.java");
        assertTrue("定时任务 / 队列只存草稿 + 通知开卡", hooks.contains("OutboundMail.popCardNow(origin)") && hooks.contains("notifyCard(app, s)"));
        String hub = KernelWiring.src("app/src/com/onesay/assistant/v2/callback/CallbackHub.java");
        assertTrue(hub.contains("DigestWindow.merge(ready)") && hub.contains("JobStore.Outbox.SUPPRESSED"));
        String man = KernelWiring.src("app/AndroidManifest.xml");
        int i = man.indexOf("com.onesay.assistant.v2.ui.MailConfirmActivity");
        assertTrue(i > 0 && man.indexOf("android:exported=\"false\"", i) - i < 120);
        assertFalse("不做国产 IM", hooks.contains("wechat"));
    }

    static void scan(File f, List<String> hits, List<String> mints) throws Exception {
        if (f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File x : k) scan(x, hits, mints);
            return;
        }
        if (!f.getName().endsWith(".java")) return;
        String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        String p = f.getPath().replace('\\', '/');
        p = p.substring(p.indexOf("com/onesay/assistant/"));
        if (s.contains(".sendDraft(")) hits.add(p);
        if (s.contains("new MailSendPermit(")) mints.add(p);
    }

}
