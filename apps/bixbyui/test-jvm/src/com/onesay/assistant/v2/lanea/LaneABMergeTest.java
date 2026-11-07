package com.onesay.assistant.v2.lanea;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.callback.OutboundMail;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.mail.MailDraft;
import com.onesay.assistant.v2.conn.mail.MailService;
import com.onesay.assistant.v2.conn.port.LaneBMailDraftAdapter;
import com.onesay.assistant.v2.conn.port.LaneBMailReadAdapter;
import com.onesay.assistant.v2.conn.port.MailConfirmTokens;
import com.onesay.assistant.v2.conn.port.MailDraftPort;
import com.onesay.assistant.v2.conn.port.MailSendPermit;
import com.onesay.assistant.v2.jobs.JobMachine;
import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.jobs.JobsEngine;
import com.onesay.assistant.v2.jobs.Perm;
import com.onesay.assistant.v2.jobs.PermissionAnalyzer;
import com.onesay.assistant.v2.jobs.WatchBridge;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * 2026-10-23 A↔B 合并适配：LaneBMailDraftAdapter / LaneBMailReadAdapter / PermissionAnalyzer 映射 / WatchBridge。
 * 重点证明：整个仓库只有「确认卡」这一条路径能发邮件；定时任务 / 队列最多只能存草稿。
 */
public class LaneABMergeTest {

    // ================================================================== 假 B 线

    /** 假 B MailService：草稿台账按 B 的 contentHash；sendDraft 绝不该被直接调（只有闸能发）。 */
    static final class FakeB implements MailService {
        final Map<String, MailDraft> ledger = new HashMap<String, MailDraft>();
        final List<String> calls = new ArrayList<String>();
        int seq;
        boolean ready = true;

        @Override public ConnResult unreadImportant(int max, String account) {
            return ConnResult.ok("x", "[{\"subject\":\"周报\",\"from\":\"a@b.com\"},{\"subject\":\"账单\",\"from\":\"c@d.com\"}]");
        }
        @Override public ConnResult search(String q, int max, String account) {
            calls.add("search:" + q);
            return ConnResult.ok("x", "[{\"from\":\"张三 <ZhangSan@corp.com>\"},{\"from\":\"zhangsan@corp.com\"},{\"from\":\"\\\"李 四\\\" <li4@x.cn>\"},{\"from\":\"乱码\"},{\"from\":\"w@y.org\"}]");
        }
        @Override public ConnResult createDraft(MailDraft d, String account) {
            String id = "d-" + (++seq);
            ledger.put(id, d);
            calls.add("create:" + id);
            return ConnResult.ok("草稿已保存", "{\"draft_id\":\"" + id + "\",\"account\":\"imap\"}");
        }
        @Override public ConnResult updateDraft(String id, MailDraft d) {
            if (!ledger.containsKey(id)) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "没有这个草稿");
            ledger.put(id, d);
            calls.add("update:" + id);
            return ConnResult.ok("ok");
        }
        @Override public ConnResult sendDraft(String id, com.onesay.assistant.v2.conn.mail.SendPermit p) {
            calls.add("DIRECT-SEND:" + id);
            return ConnResult.fail(ConnResult.Code.DENIED, "不该直接调");
        }
        @Override public ConnResult deleteDraft(String id) {
            ledger.remove(id);
            calls.add("delete:" + id);
            return ConnResult.ok("ok");
        }
    }

    /** 假 B 发送闸：prepare 按台账补 content_hash；sendViaGate 记一次「经 ExecutionGate 发出」。 */
    static final class FakeGate implements LaneBMailDraftAdapter.Gate {
        final FakeB b;
        final List<String> sent = new ArrayList<String>();

        FakeGate(FakeB b) {
            this.b = b;
        }

        @Override public String prepare(JSONObject args) {
            MailDraft d = b.ledger.get(args.optString("draft_id"));
            if (d == null) return "{\"ok\":false,\"error\":\"没有这个草稿\"}";
            try {
                args.put("content_hash", d.contentHash());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        }

        @Override public MailDraftPort.Result sendViaGate(JSONObject args) {
            sent.add(args.optString("draft_id") + "@" + args.optString("content_hash"));
            return MailDraftPort.Result.ok(args.optString("draft_id"));
        }
    }

    static LaneBMailDraftAdapter adapter(final FakeB b, FakeGate g) {
        return new LaneBMailDraftAdapter(new LaneBMailDraftAdapter.ServiceProvider() {
            @Override public MailService service() { return b; }
            @Override public boolean ready() { return b.ready; }
        }, g);
    }

    static final List<OutboundMail.Contact> CONTACTS = Arrays.asList(new OutboundMail.Contact("张三", "zhangsan@corp.com"));
    static final String D1 = "{\"subject\":\"会议时间调整\",\"body\":\"张三您好：明天的会议调整到下午三点，请知悉。\",\"summary\":\"会议改到明天三点\"}";
    static final String D2 = "{\"subject\":\"关于明日会议时间的调整\",\"body\":\"张三您好：非常抱歉，明天的会议需调整至下午三点。\",\"summary\":\"会议改期（更客气）\"}";

    static OutboundMail.Drafter llm(final String... out) {
        return new OutboundMail.Drafter() {
            int i;
            @Override public String complete(String system, String user) { return out[Math.min(i++, out.length - 1)]; }
        };
    }

    // ================================================================== 适配器：映射

    @Test public void adapterMapsCreateUpdateDeleteAndFingerprint() {
        FakeB b = new FakeB();
        LaneBMailDraftAdapter a = adapter(b, new FakeGate(b));
        assertTrue(a.available());
        MailDraftPort.Draft d = new MailDraftPort.Draft("z@corp.com", "主题", "正文正文正文正文正文正文");
        MailDraftPort.Result r = a.createDraft(d);
        assertTrue(r.ok);
        assertEquals("A 拿到的是 B 的 data.draft_id", "d-1", r.value);
        assertEquals("A 指纹 ≡ B 台账 contentHash（cc 为空）", d.fingerprint(), b.ledger.get("d-1").contentHash());
        assertTrue(a.updateDraft("d-1", new MailDraftPort.Draft("z@corp.com", "主题2", "正文正文正文正文正文正文")).ok);
        assertFalse("B 台账里没有的草稿 → 失败", a.updateDraft("d-9", d).ok);
        assertTrue(a.deleteDraft("d-1").ok);
        assertEquals(Arrays.asList("create:d-1", "update:d-1", "delete:d-1"), b.calls);
    }

    // ================================================================== 发送：令牌 + 指纹双重核对，只经 B 的闸

    @Test public void sendRequiresCardPermitAndLedgerFingerprint() {
        FakeB b = new FakeB();
        FakeGate g = new FakeGate(b);
        LaneBMailDraftAdapter a = adapter(b, g);
        MailDraftPort.Draft d = new MailDraftPort.Draft("z@corp.com", "主题", "正文正文正文正文正文正文");
        String id = a.createDraft(d).value;

        assertFalse("没有许可 → 不发", a.sendDraft(id, null).ok);
        assertTrue(g.sent.isEmpty());

        // 确认卡令牌 → 许可（绑定草稿号 + 指纹）
        String tok = MailConfirmTokens.issue(id, d.fingerprint(), 1L);
        MailSendPermit p = MailConfirmTokens.consume(tok, id, d.fingerprint(), 2L, new StringBuilder());
        assertNotNull(p);
        // B 台账在确认之后被改（例如模型调了 conn.mail.update_draft）→ 台账指纹 ≠ 许可指纹 → 不发
        b.ledger.put(id, new MailDraft("z@corp.com", "", "主题", "被偷偷改过的正文正文"));
        assertFalse(a.sendDraft(id, p).ok);
        assertTrue("指纹不符连闸都不进", g.sent.isEmpty());
        assertFalse("许可只能用一次（失败核对也不放第二次）", a.sendDraft(id, p).ok || !g.sent.isEmpty());

        // 正常：台账 = 卡片那一版
        b.ledger.put(id, new MailDraft(d.to, "", d.subject, d.body));
        String tok2 = MailConfirmTokens.issue(id, d.fingerprint(), 3L);
        MailSendPermit p2 = MailConfirmTokens.consume(tok2, id, d.fingerprint(), 4L, new StringBuilder());
        MailDraftPort.Result ok = a.sendDraft(id, p2);
        assertTrue(ok.ok);
        assertEquals("经 B 闸发出一次，参数 = 草稿号 + B 台账指纹", Arrays.asList(id + "@" + d.fingerprint()), g.sent);
        assertFalse("同一张许可不能再发", a.sendDraft(id, p2).ok);
        assertEquals(1, g.sent.size());
        // 许可是别的草稿的
        String id2 = a.createDraft(d).value;
        String tok3 = MailConfirmTokens.issue(id, d.fingerprint(), 5L);
        MailSendPermit p3 = MailConfirmTokens.consume(tok3, id, d.fingerprint(), 6L, new StringBuilder());
        assertFalse(a.sendDraft(id2, p3).ok);
        assertEquals(1, g.sent.size());
        assertFalse("适配器自己从不直接调 MailService.sendDraft", b.calls.toString().contains("DIRECT-SEND"));
    }

    @Test public void fullCardFlowThroughAdapter() {
        FakeB b = new FakeB();
        FakeGate g = new FakeGate(b);
        LaneBMailDraftAdapter a = adapter(b, g);
        OutboundMail.Recipient r = OutboundMail.verifyRecipient("给张三发邮件", "张三", CONTACTS, null, null);
        OutboundMail.Session s = OutboundMail.start("明天的会改到三点", r, "voice", llm(D1, D2), a, 1L);
        assertEquals(OutboundMail.State.AWAIT_CONFIRM, s.state);
        String old = OutboundMail.cardToken(s.id, 2L);
        OutboundMail.modify(s.id, "再客气一点", llm(D2), a);
        OutboundMail.confirmSend(s.id, old, a, 3L);
        assertTrue("修改后旧卡令牌作废 → 不发", g.sent.isEmpty());
        OutboundMail.confirmSend(s.id, null, a, 3L);
        assertTrue(g.sent.isEmpty());
        String tok = OutboundMail.cardToken(s.id, 4L);
        OutboundMail.confirmSend(s.id, tok, a, 5L);
        assertEquals(OutboundMail.State.SENT, s.state);
        assertEquals(1, g.sent.size());
        assertEquals(Arrays.asList("create:d-1", "update:d-1"), b.calls);
    }

    @Test public void scheduledAndQueueOnlySaveDraft() {
        for (String origin : new String[]{"scheduled", "queue"}) {
            FakeB b = new FakeB();
            FakeGate g = new FakeGate(b);
            LaneBMailDraftAdapter a = adapter(b, g);
            OutboundMail.Session s = OutboundMail.start("提醒张三交周报", OutboundMail.verifyRecipient("每天九点提醒张三交周报", "张三", CONTACTS, null, null),
                    origin, llm(D1), a, 1L);
            assertFalse(OutboundMail.popCardNow(origin));
            assertEquals(origin + "：最多走到「存草稿 + 等确认」", OutboundMail.State.AWAIT_CONFIRM, s.state);
            assertEquals(Arrays.asList("create:d-1"), b.calls);
            assertTrue(origin + "：没有经过确认卡就没有任何发送", g.sent.isEmpty());
        }
    }

    @Test public void noMailboxNoSendPath() {
        FakeB b = new FakeB();
        b.ready = false;
        FakeGate g = new FakeGate(b);
        LaneBMailDraftAdapter a = adapter(b, g);
        assertFalse(a.available());
        assertFalse("没配置邮箱 → 连草稿都不存", a.createDraft(new MailDraftPort.Draft("z@corp.com", "s", "正文正文正文正文正文")).ok);
        OutboundMail.Session s = OutboundMail.start("明天的会改到三点", OutboundMail.verifyRecipient("给张三发邮件", "张三", CONTACTS, null, null),
                "voice", llm(D1), a, 1L);
        assertNotEquals(OutboundMail.State.AWAIT_CONFIRM, s.state);
        assertTrue(b.calls.isEmpty() && g.sent.isEmpty());
        String hooks = KernelWiring.src("app/src/com/onesay/assistant/v2/callback/OutboundMailHooks.java");
        int beg = hooks.indexOf("public static void begin(");
        assertTrue("begin 一进来先看邮箱有没有配置（没有 → 不起草、不弹卡）", beg > 0 && hooks.indexOf("if (!mailReady(app))", beg) > beg
                && hooks.indexOf("if (!mailReady(app))", beg) < hooks.indexOf("OutboundMail.start(", beg));
        int div = hooks.indexOf("public static String divertAgentSend(");
        assertTrue(hooks.indexOf("if (!mailReady(ctx))", div) > div);
        String cb = KernelWiring.plugin("CallbackPlugin");
        assertTrue("语音「发邮件」没配置邮箱时不起草", cb.contains("if (ready) fx.beginMail(") && cb.contains("OutboundMailHooks.begin(ctx, raw, who, intent, \"voice\")") && cb.contains("OutboundMailHooks.NO_MAILBOX"));
        assertEquals("STUB 端口不可用", false, com.onesay.assistant.v2.conn.port.Ports.STUB_DRAFT.available());
    }

    // ================================================================== 源码扫描：唯一的发送路径

    static void scan(File f, Map<String, String> out) throws Exception {
        if (f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File x : k) scan(x, out);
            return;
        }
        if (!f.getName().endsWith(".java")) return;
        String p = f.getPath().replace('\\', '/');
        out.put(p.substring(p.indexOf("com/onesay/assistant/")), new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
    }

    @Test public void onlyConfirmCardCanSendMail() throws Exception {
        Map<String, String> src = new HashMap<String, String>();
        scan(new File(System.getProperty("bixby.root", "."), "app/src"), src);
        TreeSet<String> send = new TreeSet<String>(), permitB = new TreeSet<String>(), permitA = new TreeSet<String>(), approveMail = new TreeSet<String>();
        for (Map.Entry<String, String> e : src.entrySet()) {
            String s = e.getValue();
            if (s.contains(".sendDraft(")) send.add(e.getKey());
            if (s.contains("new SendPermit(")) permitB.add(e.getKey());
            if (s.contains("new MailSendPermit(")) permitA.add(e.getKey());
            if (!e.getKey().endsWith("executor/ExecutionGate.java")   // 定义处（注释里提到 MailSendGuard）
                    && s.contains("ExecutionGate.approve(") && (s.contains("MailSendGuard") || s.contains("send_draft"))) approveMail.add(e.getKey());
        }
        assertEquals("调 .sendDraft( 的只有：A 确认卡 confirmSend、B 连接器内部（账户分派）、B 唯一执行目标 MailSendTarget",
                new TreeSet<String>(Arrays.asList("com/onesay/assistant/v2/callback/OutboundMail.java", "com/onesay/assistant/v2/conn/mail/MailConnector.java",
                        "com/onesay/assistant/v2/conn/mail/MailSendTarget.java")), send);
        assertEquals("B 的 SendPermit 只由 MailSendGuard（令牌消费回执）造", new TreeSet<String>(Arrays.asList("com/onesay/assistant/v2/conn/mail/MailSendGuard.java")), permitB);
        assertEquals("A 的 MailSendPermit 只由 MailConfirmTokens 造", new TreeSet<String>(Arrays.asList("com/onesay/assistant/v2/conn/port/MailConfirmTokens.java")), permitA);
        assertEquals("替用户批准「发送邮件」令牌的只有 A↔B 桥（且在 A 许可核对之后）",
                new TreeSet<String>(Arrays.asList("com/onesay/assistant/v2/conn/port/LaneBMailPorts.java")), approveMail);

        // 适配器：先 A 许可核对（草稿号 + B 台账指纹），再进 B 闸；自己不调 MailService.sendDraft
        String ad = src.get("com/onesay/assistant/v2/conn/port/LaneBMailDraftAdapter.java");
        int sd = ad.indexOf("public Result sendDraft(String draftId, MailSendPermit permit)");
        assertTrue(sd > 0);
        int use = ad.indexOf("permit.use(draftId, ledgerHash)", sd), via = ad.indexOf("mGate.sendViaGate(args)", sd), prep = ad.indexOf("mGate.prepare(args)", sd);
        assertTrue("prepare → permit.use → sendViaGate", prep > sd && use > prep && via > use);
        assertTrue(ad.indexOf("if (permit == null) return Result.fail", sd) > sd && ad.indexOf("if (bad != null) return Result.fail(bad);", use) > use);
        // 桥：只在 sendViaGate 里批准，且门禁不要求确认时失败关闭
        String br = src.get("com/onesay/assistant/v2/conn/port/LaneBMailPorts.java");
        int sv = br.indexOf("public MailDraftPort.Result sendViaGate(");
        assertTrue(sv > 0 && br.indexOf("ExecutionGate.approve(") > sv && br.indexOf("if (!dec.needsConfirm() || dec.token == null)", sv) < br.indexOf("ExecutionGate.approve(", sv));
        assertTrue("A 端口实现只有这一个适配器", br.contains("Ports.setMailDraft(new LaneBMailDraftAdapter("));
        // AgentLoop：回调中枢（400）先于连接器（500）拦下发送类工具；回调中枢关了连接器也直接拒绝；面板路由拿不到 send_draft
        String cb = KernelWiring.plugin("CallbackPlugin");
        assertTrue(cb.contains("hook == com.onesay.assistant.v2.kernel.FeatureHook.AGENT_PRECHECK) return 400"));
        assertTrue(KernelWiring.plugin("ConnectorPlugin").contains("case AGENT_PRECHECK: return 500"));
        String ch = src.get("com/onesay/assistant/v2/conn/ConnHooks.java");
        int ap = ch.indexOf("public static String agentPrecheck(");
        assertTrue(ch.indexOf("MailSendGuard.SIGN.equals(name)", ap) > ap && ch.indexOf("MailSendGuard.SIGN.equals(name)", ap) < ch.indexOf("MailHooks.precheck(name, args)", ap));
        assertTrue(KernelWiring.plugin("ConnectorPlugin").contains("if (com.onesay.assistant.v2.conn.mail.MailSendGuard.SIGN.equals(sign)) return null;"));
        // 定时任务 / 队列 / 事件 / 监控的执行代码里没有任何签发 / 发送入口
        for (String f : new String[]{"s3/S3Runner.java", "jobs/JobsHooks.java", "jobs/JobsEngine.java", "jobs/JobQueue.java", "jobs/WatchBridge.java",
                "jobs/WatchBridgeHooks.java", "events/EventEngine.java", "events/EventHooks.java", "callback/CallbackHub.java", "callback/OutboundMailHooks.java",
                "kernel/plugins/CallbackPlugin.java", "router/AgentLoop.java"}) {
            String s = src.get("com/onesay/assistant/v2/" + f);
            assertNotNull(f, s);
            assertFalse(f + " 不能签发 / 消费确认卡令牌或直接走发送闸", s.contains("cardToken(") || s.contains("confirmSend(") || s.contains("MailConfirmTokens.")
                    || s.contains("sendViaGate(") || s.contains(".sendDraft(") || s.contains("MailSendGuard.fromReceipt"));
        }
    }

    // ================================================================== 读端口

    @Test public void readAdapter() throws Exception {
        FakeB b = new FakeB();
        LaneBMailReadAdapter r = new LaneBMailReadAdapter(new LaneBMailDraftAdapter.ServiceProvider() {
            @Override public MailService service() { return b; }
            @Override public boolean ready() { return b.ready; }
        });
        assertEquals(Arrays.asList("周报"), r.unreadImportantTitles(1));
        List<String[]> c = r.correspondents(50);
        assertEquals("从 B 搜索结果的发件人里取；地址去重（大小写不敏感）；认不出的丢掉", 3, c.size());
        assertArrayEquals(new String[]{"张三", "ZhangSan@corp.com"}, c.get(0));
        assertArrayEquals(new String[]{"李 四", "li4@x.cn"}, c.get(1));
        assertArrayEquals(new String[]{"", "w@y.org"}, c.get(2));
        assertEquals("search:" + LaneBMailReadAdapter.QUERY, b.calls.get(0));
        b.ready = false;
        assertFalse(r.available());
        try {
            r.correspondents(5);
            fail("没配置邮箱应抛异常（调用方当作读不到）");
        } catch (IllegalStateException expected) {
        }
    }

    // ================================================================== 权限映射

    @Test public void permissionMapping() {
        assertEquals(Perm.READ_TODO, PermissionAnalyzer.forTool("conn.google.tasks_list", null));
        assertEquals(Perm.WRITE_TODO, PermissionAnalyzer.forTool("conn.google.tasks_create", null));
        assertEquals(Perm.WRITE_TODO, PermissionAnalyzer.forTool("conn.google.tasks_update", null));
        assertEquals("删本 App 建的草稿 = 草稿权限，不是红卡", Perm.MAIL_DRAFT, PermissionAnalyzer.forTool("conn.mail.delete_draft", null));
        assertEquals(Perm.MAIL_DRAFT, PermissionAnalyzer.forTool("conn.mail.create_draft", null));
        assertEquals(Perm.MAIL_DRAFT, PermissionAnalyzer.forTool("conn.mail.update_draft", null));
        assertEquals("发送永远不可预授权", Perm.MAIL_SEND, PermissionAnalyzer.forTool("conn.mail.send_draft", null));
        assertFalse(Perm.MAIL_SEND.preGrantable);
        assertEquals(Perm.READ_MAIL, PermissionAnalyzer.forTool("conn.mail.search", null));
        assertEquals(Perm.READ_CALENDAR, PermissionAnalyzer.forTool("conn.google.calendar_list", null));
        assertEquals(Perm.WRITE_CALENDAR, PermissionAnalyzer.forTool("conn.google.calendar_create", null));
        assertEquals(Perm.WEB_SEARCH, PermissionAnalyzer.forTool("conn.tavily.search", null));
        assertEquals("其他 delete 仍是红卡", Perm.RED_ACTION, PermissionAnalyzer.forTool("conn.mcp.files_delete", null));
        assertEquals(EnumSet.of(Perm.WEB_SEARCH), PermissionAnalyzer.forTemplate("watch.w1#删除 小米 新品"));
        assertEquals(EnumSet.of(Perm.WRITE_TODO), PermissionAnalyzer.analyze("帮我在待办里加一条明天交周报", null, null));
        assertEquals(EnumSet.of(Perm.READ_TODO), PermissionAnalyzer.analyze("看看今天的待办", null, null));
        assertEquals("旧授权串照样解析（按名字）", Perm.READ_NOTIFICATION, Perm.parse("READ_NOTIFICATION"));
    }

    // ================================================================== WatchPorts → jobs.db

    @Test public void watchBridgeSchedulesInJobsDb() {
        JobStore st = new JobStore.Mem();
        JobsEngine e = new JobsEngine(st, TimeZone.getTimeZone("Asia/Shanghai"));
        WatchBridge b = new WatchBridge(e);
        long now = 1000000L;
        JobStore.Run r = b.schedule("w1", "小米新品", now + 3600000L, now);
        assertEquals("watch:w1", r.owner);
        assertEquals(1, r.maxAttempts);
        assertEquals("command 永远为空（查询词不进静态分析）", "", r.command);
        assertEquals("earliestNext 按 plannedAt，不是「立即」", now + 3600000L, e.earliestNext(now));
        assertTrue(b.due(now).isEmpty());
        b.schedule("w1", "小米新品", now + 60000L, now);
        assertEquals("同 id 再 schedule = 改期，不新建", 1, st.runs().size());
        assertEquals(now + 60000L, e.earliestNext(now));
        assertEquals(1, b.due(now + 60000L).size());
        assertEquals(1, b.cancel("w1", now));
        assertEquals(JobMachine.State.CANCELLED, st.runs().get(0).state);
        assertEquals(-1L, e.earliestNext(now));
        // 规格 / 授权
        JobStore.Job j = WatchBridge.asJob("w2", "显卡", "删除 显卡 降价");
        assertEquals("", j.command);
        assertEquals(EnumSet.of(Perm.WEB_SEARCH), JobsEngine.analyze(j));
        // 结果送达：outbox 幂等、不朗读、按监控合并
        JobStore.Outbox o1 = b.onChange("w2", "显卡", Arrays.asList("https://a/1", "https://b/2"), "显卡：2 条新结果（a、b）", now);
        JobStore.Outbox o2 = b.onChange("w2", "显卡", Arrays.asList("https://a/1", "https://b/2"), "显卡：2 条新结果（a、b）", now + 1);
        assertEquals("同一组结果只写一次", o1.id, o2.id);
        assertFalse(o1.wantSpeak);
        assertEquals("watch:w2", o1.digestKey);
        JobStore.Outbox k1 = b.onBroken("w2", "显卡", "TIMEOUT 搜不到", "20261023", now);
        JobStore.Outbox k2 = b.onBroken("w2", "显卡", "TIMEOUT 搜不到", "20261023", now + 5);
        assertEquals("同一监控一天最多一条「查不了了」", k1.id, k2.id);
        assertEquals("LOW", k1.urgency);
        assertEquals(2, st.outbox().size());
        // 执行结束只改状态，不另写结果 outbox
        JobStore.Run r3 = b.schedule("w3", "x", now, now);
        e.apply(r3, JobMachine.Event.START, now);
        b.finish(r3, true, "", now);
        assertEquals(JobMachine.State.DONE, st.run(r3.id).state);
        assertEquals(2, st.outbox().size());
    }

    @Test public void watchWiring() {
        String s3 = KernelWiring.src("app/src/com/onesay/assistant/v2/s3/S3Runner.java");
        assertTrue(s3.contains("com.onesay.assistant.v2.jobs.WatchBridgeHooks.runDue(mCtx)"));
        String jh = KernelWiring.src("app/src/com/onesay/assistant/v2/jobs/JobsHooks.java");
        assertTrue(jh.contains("owner.startsWith(WatchBridge.OWNER_PREFIX)) return WatchBridgeHooks.describe(app, owner)"));
        String cb = KernelWiring.plugin("CallbackPlugin");
        assertTrue("启动时注册邮件适配器 + 监控桥", cb.contains("public void onInit(Context ctx)") && cb.contains("LaneBMailPorts.ensure(ctx)")
                && cb.contains("WatchBridgeHooks.install(ctx)"));
        String wh = KernelWiring.src("app/src/com/onesay/assistant/v2/jobs/WatchBridgeHooks.java");
        assertTrue("到点先查授权，没有 → 等确认", wh.indexOf("je.begin(run") > 0 && wh.indexOf("je.begin(run") < wh.indexOf("runOne("));
        assertTrue(wh.contains("WatchPorts.setTrigger(") && wh.contains("WatchPorts.setSink("));
    }
}
