package com.onesay.assistant.v2.conn.mail;

import com.onesay.assistant.v2.conn.Codec;
import com.onesay.assistant.v2.conn.ConnHttp;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.ConnTool;
import com.onesay.assistant.v2.conn.FakeHttp;
import com.onesay.assistant.v2.conn.MemSecrets;
import com.onesay.assistant.v2.conn.ToolEffect;
import com.onesay.assistant.v2.conn.google.GoogleApi;
import com.onesay.assistant.v2.conn.google.GoogleAuth;
import com.onesay.assistant.v2.conn.google.GoogleScopes;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

/** [conn/mail] 统一接口：选路 / 回退、草稿台账、发送许可核对、草稿校验、Gmail 报文与 API、IMAP 后端。 */
public class MailConnectorTest {

    @Test public void draftValidationAndHash() {
        MailDraft d = MailTestKit.draft();
        assertNull(d.validate());
        assertEquals("boss@example.com, a@b.cn", new MailDraft("boss@example.com；a@b.cn", "", "s", "x").to);
        assertNotNull("没收件人", new MailDraft("", "", "主题", "正文正文正文正文正文正文").validate());
        assertNotNull("地址不合法", new MailDraft("boss", "", "主题", "正文正文正文正文正文正文").validate());
        assertTrue("一句话不算起草", new MailDraft("a@b.com", "", "请假", "帮我请个假").validate().contains("起草"));
        assertNotNull("缺主题", new MailDraft("a@b.com", "", "", "正文正文正文正文正文正文").validate());
        assertEquals(d.contentHash(), MailTestKit.draft().contentHash());
        assertNotEquals(d.contentHash(), new MailDraft(d.to, "", d.subject, d.body + "!").contentHash());
        assertNotEquals(d.contentHash(), new MailDraft("x@example.com", "", d.subject, d.body).contentHash());
        assertEquals("主题去换行（防头注入）", "a b", new MailDraft("a@b.com", "", "a\r\nb", "x").subject.replace("  ", " "));
    }

    @Test public void toolsAndEffects() {
        MailTestKit.FakeAccount imap = new MailTestKit.FakeAccount(MailAccount.IMAP);
        MailConnector m = new MailConnector(null, imap, new DraftLedger(null), null);
        List<String> names = new ArrayList<String>();
        for (ConnTool t : m.tools()) {
            names.add(t.sign + "=" + t.effect);
            assertEquals("object", t.inputSchema.optString("type"));
        }
        assertEquals(Arrays.asList("conn.mail.unread_important=READ", "conn.mail.search=READ", "conn.mail.create_draft=DRAFT",
                "conn.mail.update_draft=DRAFT", "conn.mail.delete_draft=DRAFT", "conn.mail.send_draft=COMMS"), names);
        assertTrue("发送是唯一要确认的", ToolEffect.COMMS.confirm() && !ToolEffect.DRAFT.confirm());
        assertTrue("草稿工具描述要求按意图起草", m.tools().get(2).description.contains("不要照抄"));
        imap.ready = false;
        assertTrue("没邮箱就不给工具", m.tools().isEmpty());
        assertEquals("远程白名单不能声明免确认 DRAFT", ToolEffect.WRITE, ToolEffect.parse("draft"));
    }

    @Test public void autoRouteFallsBackFromGmailToImap() {
        MailTestKit.FakeAccount gmail = new MailTestKit.FakeAccount(MailAccount.GMAIL);
        MailTestKit.FakeAccount imap = new MailTestKit.FakeAccount(MailAccount.IMAP);
        final List<ConnResult.Code> reported = new ArrayList<ConnResult.Code>();
        MailConnector m = new MailConnector(gmail, imap, new DraftLedger(null), new MailConnector.NetPolicy() {
            @Override public boolean tryGoogle() { return true; }
            @Override public void reportGoogle(ConnResult r) { reported.add(r.code); }
            @Override public String notice() { return ""; }
        });
        ConnResult r = m.unreadImportant(5, null);
        assertTrue(r.text.startsWith("gmail"));
        gmail.failWith = ConnResult.Code.UNREACHABLE;
        r = m.unreadImportant(5, null);
        assertTrue(r.text, r.ok());
        assertTrue(r.text, r.text.contains("已改用 IMAP") && r.text.contains("imap 未读"));
        assertEquals(Arrays.asList(ConnResult.Code.OK, ConnResult.Code.UNREACHABLE), reported);
        // 指定了 gmail 就不回退
        assertEquals(ConnResult.Code.UNREACHABLE, m.unreadImportant(5, "gmail").code);
        // 远端错误（不是网络 / 授权）不回退
        gmail.failWith = ConnResult.Code.BAD_ARGS;
        assertEquals(ConnResult.Code.BAD_ARGS, m.search("x", 5, null).code);
        // 新建草稿也回退，草稿记在 IMAP 名下
        gmail.failWith = ConnResult.Code.AUTH_REVOKED;
        ConnResult c = m.createDraft(MailTestKit.draft(), null);
        assertTrue(c.text, c.ok());
        assertEquals(1, imap.drafts.size());
        String id = c.data.contains("draft_id") ? new JSONObjectish(c.data).get("draft_id") : null;
        assertEquals(MailAccount.IMAP, m.ledger().get(id).account);
        // 两个都没有
        MailConnector none = new MailConnector(null, null, null, null);
        assertEquals(ConnResult.Code.NOT_CONFIGURED, none.unreadImportant(3, null).code);
        assertFalse(none.isReady());
    }

    /** 极简取值（避免引入解析细节）。 */
    static final class JSONObjectish {
        final org.json.JSONObject o;
        JSONObjectish(String s) {
            try { o = new org.json.JSONObject(s); } catch (Exception e) { throw new RuntimeException(e); }
        }
        String get(String k) { return o.optString(k); }
    }

    @Test public void draftLifecycleAndPermitChecks() throws Exception {
        MailTestKit.FakeAccount gmail = new MailTestKit.FakeAccount(MailAccount.GMAIL);
        MailConnector m = new MailConnector(gmail, null, new DraftLedger(null), null);
        ConnResult c = m.createDraft(MailTestKit.draft(), null);
        assertTrue(c.text, c.ok());
        assertTrue("结果里有草稿内容给用户看", c.text.contains("周五请假申请") && c.text.contains("send_draft"));
        String id = new JSONObjectish(c.data).get("draft_id");
        assertTrue(id.startsWith("d-"));

        // 通用 call() 发不出去
        assertEquals(ConnResult.Code.DENIED, m.call(MailConnector.T_SEND, new JSONObject().put("draft_id", id)).code);
        assertEquals(ConnResult.Code.DENIED, m.sendDraft(id, null).code);
        assertTrue(gmail.sent.isEmpty());

        // 预检：写入 content_hash 与预览，覆盖模型自己填的
        JSONObject args = new JSONObject().put("draft_id", id).put("content_hash", "模型乱填").put("to", "evil@x.com");
        assertNull(m.prepareSendArgs(args));
        String hash = MailTestKit.draft().contentHash();
        assertEquals(hash, args.getString("content_hash"));
        assertEquals("boss@example.com", args.getString("to"));
        assertTrue(args.getString("body_preview").startsWith("王总您好"));
        assertNotNull("不存在的草稿", m.prepareSendArgs(new JSONObject().put("draft_id", "d-nope")));

        // 许可绑定草稿号与指纹
        assertEquals(ConnResult.Code.DENIED, m.sendDraft(id, MailTestKit.forgedPermit("d-other", hash)).code);
        assertEquals(ConnResult.Code.DENIED, m.sendDraft(id, MailTestKit.forgedPermit(id, "oldhash")).code);
        assertTrue(gmail.sent.isEmpty());

        // 改草稿 → 指纹变 → 旧许可作废
        MailDraft d2 = new MailDraft("boss@example.com", "", "周五请假申请（更正）", "王总您好：\n\n更正一下，我是下周五请假。\n\n谢谢！\n张");
        assertTrue(m.updateDraft(id, d2).ok());
        assertEquals(ConnResult.Code.DENIED, m.sendDraft(id, MailTestKit.forgedPermit(id, hash)).code);

        // 在网页上被改过（rev 变）→ 后端拒绝
        String remote = m.ledger().get(id).remoteId;
        gmail.editElsewhere(remote);
        assertEquals(ConnResult.Code.DENIED, m.sendDraft(id, MailTestKit.forgedPermit(id, d2.contentHash())).code);
        gmail.revs.put(remote, m.ledger().get(id).rev);

        SendPermit ok = MailTestKit.forgedPermit(id, d2.contentHash());
        ConnResult s = m.sendDraft(id, ok);
        assertTrue(s.text, s.ok());
        assertEquals(1, gmail.sent.size());
        assertEquals("发完出台账", null, m.ledger().get(id));
        assertEquals("许可一次性", ConnResult.Code.DENIED, m.sendDraft(id, ok).code);

        // 只能删本 App 建的
        assertEquals(ConnResult.Code.DENIED, m.deleteDraft("d-xxxx").code);
    }

    @Test public void ledgerPersistsAndExpires() {
        final String[] store = {"[]"};
        DraftLedger.Store st = new DraftLedger.Store() {
            @Override public String load() { return store[0]; }
            @Override public void save(String json) { store[0] = json; }
        };
        final long[] now = {1000L};
        MailSendGuard.Clock clock = new MailSendGuard.Clock() {
            @Override public long now() { return now[0]; }
        };
        DraftLedger l = new DraftLedger(st, clock);
        String id = l.newId();
        StringBuilder longBody = new StringBuilder("开头");
        for (int i = 0; i < 60; i++) longBody.append("正文内容");
        longBody.append("结尾的秘密段落");
        l.put(id, "imap", "k", "1", new MailDraft("a@b.com", "", "主题", longBody.toString()));
        assertTrue(store[0].contains("开头"));
        assertFalse("台账不存整封正文", store[0].contains("结尾的秘密段落"));
        DraftLedger l2 = new DraftLedger(st, clock);
        assertEquals("k", l2.get(id).remoteId);
        now[0] += DraftLedger.TTL_MS + 1;
        assertNull("7 天过期", l2.get(id));
    }

    @Test public void mimeIsParsableByJavaMail() throws Exception {
        MailDraft d = new MailDraft("a@example.com", "b@example.com", "中文主题 ✓", "第一行\n第二行 English");
        String raw = MimeText.build(d);
        assertFalse("头里没有裸中文", raw.substring(0, raw.indexOf("\r\n\r\n")).matches("(?s).*[\\u4e00-\\u9fff].*"));
        javax.mail.internet.MimeMessage mm = new javax.mail.internet.MimeMessage(javax.mail.Session.getInstance(new Properties()),
                new ByteArrayInputStream(Codec.unb64(MimeText.raw(d))));
        assertEquals("中文主题 ✓", mm.getSubject());
        assertEquals("a@example.com", mm.getRecipients(javax.mail.Message.RecipientType.TO)[0].toString());
        assertEquals("b@example.com", mm.getRecipients(javax.mail.Message.RecipientType.CC)[0].toString());
        // android-mail 的 getContent() 在桌面 JVM 上跑不了（DataFlavor 依赖），这里按原始流 + base64 自己解
        assertEquals("base64", mm.getEncoding());
        java.io.InputStream rawIn = mm.getRawInputStream();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int n;
        while ((n = rawIn.read(buf)) > 0) bo.write(buf, 0, n);
        assertEquals("第一行\r\n第二行 English", Codec.str(Codec.unb64(bo.toString("US-ASCII"))));
        assertTrue(mm.getContentType().toLowerCase().contains("charset=utf-8"));
        assertEquals("Subject 注入被压平", -1, MimeText.build(new MailDraft("a@b.com", "", "x\r\nBcc: evil@x.com", "body body body")).indexOf("\r\nBcc:"));
    }

    @Test public void gmailAccountOverRest() throws Exception {
        MemSecrets sec = new MemSecrets();
        sec.put("google.refresh_token", "1//r");
        sec.put("google.access_token", "ya29.t");
        sec.put("google.access_exp", String.valueOf(System.currentTimeMillis() + 3600000L));
        sec.put("google.scopes", GoogleScopes.GMAIL_READONLY + " " + GoogleScopes.GMAIL_COMPOSE);
        final List<String> calls = new ArrayList<String>();
        final String[] draftMsgId = {"m1"};
        FakeHttp http = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                String u = req.url.replace(GmailAccount.BASE, "");
                calls.add(req.method + " " + u);
                try {
                    if (u.startsWith("/messages?")) {
                        assertTrue(u, Codec.urlDecode(u).contains("is:unread is:important"));
                        return FakeHttp.json(200, "{\"messages\":[{\"id\":\"a1\"}]}");
                    }
                    if (u.startsWith("/messages/a1")) {
                        return FakeHttp.json(200, "{\"id\":\"a1\",\"labelIds\":[\"UNREAD\",\"IMPORTANT\"],\"snippet\":\"明天 3 点开会 &amp; 带电脑\","
                                + "\"payload\":{\"headers\":[{\"name\":\"From\",\"value\":\"张三 <z@x.com>\"},{\"name\":\"Subject\",\"value\":\"会议\"}]}}");
                    }
                    if (u.equals("/drafts") && "POST".equals(req.method)) {
                        String raw = new JSONObject(body).getJSONObject("message").getString("raw");
                        assertTrue(Codec.str(Codec.unb64(raw)).contains("To: boss@example.com"));
                        return FakeHttp.json(200, "{\"id\":\"r-1\",\"message\":{\"id\":\"m1\"}}");
                    }
                    if (u.startsWith("/drafts/r-1?format=minimal")) return FakeHttp.json(200, "{\"id\":\"r-1\",\"message\":{\"id\":\"" + draftMsgId[0] + "\"}}");
                    if (u.equals("/drafts/send")) {
                        assertEquals("r-1", new JSONObject(body).getString("id"));
                        return FakeHttp.json(200, "{\"id\":\"sent1\",\"labelIds\":[\"SENT\"]}");
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return FakeHttp.json(404, "{\"error\":{\"message\":\"nf\"}}");
            }
        });
        GmailAccount g = new GmailAccount(new GoogleApi(http, new GoogleAuth(http, sec, new GoogleAuth.Config() {
            @Override public String clientId() { return "1-a.apps.googleusercontent.com"; }
        })));
        assertTrue(g.isReady());
        MailAccount.Listing l = g.unreadImportant(3);
        assertTrue(l.result.text, l.result.ok());
        assertEquals(1, l.items.size());
        assertTrue(l.items.get(0).important && l.items.get(0).unread);
        assertEquals("明天 3 点开会 & 带电脑", l.items.get(0).snippet);
        MailAccount.Remote r = g.createDraft(MailTestKit.draft());
        assertEquals("r-1", r.remoteId);
        assertEquals("m1", r.rev);
        assertEquals(ConnResult.Code.DENIED, g.sendDraft("r-1", "m1", null).code);
        draftMsgId[0] = "m2";   // 网页上改过
        assertEquals(ConnResult.Code.DENIED, g.sendDraft("r-1", "m1", MailTestKit.forgedPermit("d", "h")).code);
        draftMsgId[0] = "m1";
        assertTrue(g.sendDraft("r-1", "m1", MailTestKit.forgedPermit("d", "h")).ok());
        assertTrue(calls.contains("POST /drafts/send"));
        // 没有 compose 权限
        sec.put("google.scopes", GoogleScopes.GMAIL_READONLY);
        assertEquals(ConnResult.Code.AUTH_REVOKED, g.createDraft(MailTestKit.draft()).result.code);
    }

    /** IMAP 后端：重要筛选、回退说明、发送前读回核对、删不掉就标记。 */
    static final class FakeOps implements ImapOps {
        final List<MailSummary> unread = new ArrayList<MailSummary>();
        final java.util.Map<String, MailDraft> box = new java.util.LinkedHashMap<String, MailDraft>();
        final List<MailDraft> smtp = new ArrayList<MailDraft>();
        boolean deleteFails;
        boolean marked;
        Exception boom;

        @Override public List<MailSummary> unread(int scanMax) throws Exception { if (boom != null) throw boom; return unread; }
        @Override public List<MailSummary> search(String q, int max) throws Exception { return new ArrayList<MailSummary>(); }
        @Override public void appendDraft(MailDraft d, String key, int rev) throws Exception { if (boom != null) throw boom; box.put(key + "#" + rev, d); }
        @Override public MailDraft readDraft(String key, int rev) throws Exception { return box.get(key + "#" + rev); }
        @Override public void smtpSend(MailDraft d) throws Exception { smtp.add(d); }
        @Override public boolean deleteDraft(String key, int rev) throws Exception {
            if (deleteFails) throw new javax.mail.MessagingException("NO expunge");
            return box.remove(key + "#" + rev) != null;
        }
        @Override public boolean markDraftSent(String key, int rev) throws Exception { marked = true; return true; }
    }

    @Test public void imapAccount() throws Exception {
        FakeOps ops = new FakeOps();
        ImapAccount a = new ImapAccount(ops, null);
        ops.unread.add(new MailSummary("1", "a", "普通", "", "", true, false));
        MailAccount.Listing l = a.unreadImportant(5);
        assertTrue(l.result.text, l.result.text.contains("没有「重要」标记"));
        ops.unread.add(new MailSummary("2", "b", "加星的", "", "", true, true));
        l = a.unreadImportant(5);
        assertEquals(1, l.items.size());
        assertEquals("加星的", l.items.get(0).subject);

        MailAccount.Remote r = a.createDraft(MailTestKit.draft());
        assertEquals("1", r.rev);
        MailDraft d2 = new MailDraft("boss@example.com", "", "改过的主题", "改过的正文改过的正文改过的正文");
        MailAccount.Remote r2 = a.updateDraft(r.remoteId, r.rev, d2);
        assertEquals("2", r2.rev);
        assertEquals("旧版本删了", 1, ops.box.size());
        // 许可指纹与服务器上的不一致 → 不发
        assertEquals(ConnResult.Code.DENIED, a.sendDraft(r.remoteId, "2", MailTestKit.forgedPermit("d", MailTestKit.draft().contentHash())).code);
        assertTrue(ops.smtp.isEmpty());
        ops.deleteFails = true;
        ConnResult s = a.sendDraft(r.remoteId, "2", MailTestKit.forgedPermit("d", d2.contentHash()));
        assertTrue(s.text, s.ok() && s.text.contains("标记"));
        assertTrue(ops.marked);
        assertEquals(1, ops.smtp.size());
        // 认证失败不当网络问题
        ops.boom = new javax.mail.AuthenticationFailedException("[AUTHENTICATIONFAILED] Invalid credentials");
        assertEquals(ConnResult.Code.AUTH_REVOKED, a.unreadImportant(3).result.code);
        ops.boom = new javax.mail.MessagingException("x", new java.net.UnknownHostException("imap.qq.com"));
        assertEquals(ConnResult.Code.UNREACHABLE, a.unreadImportant(3).result.code);
    }

    @Test public void smtpPresets() {
        assertArrayEquals(new String[]{"smtp.qq.com", "465", "ssl"}, JavaMailImapOps.Config.smtpFor("imap.qq.com"));
        assertArrayEquals(new String[]{"smtp.gmail.com", "465", "ssl"}, JavaMailImapOps.Config.smtpFor("imap.gmail.com"));
        assertArrayEquals(new String[]{"smtp.office365.com", "587", "starttls"}, JavaMailImapOps.Config.smtpFor("outlook.office365.com"));
        assertArrayEquals(new String[]{"smtp.exmail.qq.com", "465", "ssl"}, JavaMailImapOps.Config.smtpFor("imap.exmail.qq.com"));
        JavaMailImapOps ops = new JavaMailImapOps(new JavaMailImapOps.Config("imap.qq.com", 993, "smtp.office365.com", 587, false, "u@qq.com", "p", null, 0));
        assertEquals("true", ops.smtpProps().getProperty("mail.smtp.starttls.required"));
        assertEquals("true", ops.imapProps().getProperty("mail.imaps.ssl.checkserveridentity"));
        assertNull("不信任任意证书", ops.imapProps().getProperty("mail.imaps.ssl.trust"));
    }

    static JSONArray unused() { return new JSONArray(); }
}
