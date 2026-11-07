package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.conn.ConnBackend;
import com.onesay.assistant.v2.conn.ConnectorRegistry;
import com.onesay.assistant.v2.conn.mail.DraftLedger;
import com.onesay.assistant.v2.conn.mail.MailAccount;
import com.onesay.assistant.v2.conn.mail.MailConnector;
import com.onesay.assistant.v2.conn.mail.MailSendTarget;
import com.onesay.assistant.v2.conn.mail.MailTestKit;
import com.onesay.assistant.v2.executor.backend.ExecutorBackend;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * [conn/mail] 端到端：草稿（不弹卡）→ send_draft 预检补指纹 → 黄卡令牌 → UnifiedExecutor 消费 → MailSendTarget 换许可 → 发出。
 * 以及所有绕过路径：没令牌、旧布尔、「始终允许」、改参数、改草稿、令牌复用，都发不出去。
 */
public class MailSendGateTest {

    MailTestKit.FakeAccount gmail;
    MailConnector mail;
    ConnectorRegistry reg;
    UnifiedExecutor ex;

    void setUp() {
        gmail = new MailTestKit.FakeAccount(MailAccount.GMAIL);
        mail = new MailConnector(gmail, null, new DraftLedger(null), null);
        reg = new ConnectorRegistry();
        reg.register(mail);
        List<ExecutorBackend> bes = new ArrayList<ExecutorBackend>();
        bes.add(ConnBackend.fixed(reg, new MailSendTarget(new MailSendTarget.Provider() {
            @Override public MailConnector mail() { return mail; }
        })));
        ex = new UnifiedExecutor(null, new UnifiedExecutorTest.FakeShell(), new UnifiedExecutorTest.FakeApps(), bes);
    }

    @After public void tearDown() {
        ExecutionGate.setStrictConfirm(true);   // r2/fixes 11：恢复生产默认（严格）
        ConsentPolicy.revoke("conn.mail.send_draft");
    }

    String createDraftViaExecutor() throws Exception {
        Capability create = reg.describe("conn.mail.create_draft");
        JSONObject a = MailTestKit.draft().toJson();
        ExecutionGate.Decision d = ExecutionGate.evaluate(create, a, false);
        assertTrue("建草稿不弹卡", d.allowed());
        ExecutionResult r = ex.execute(create, a, null, false);
        assertTrue(r.userMessage, r.ok);
        assertTrue("被污染的任务里建草稿要确认", ExecutionGate.evaluate(create, a, true).needsConfirm());
        return mail.ledger().all().get(0).id;
    }

    @Test public void happyPathNeedsYellowCard() throws Exception {
        setUp();
        String id = createDraftViaExecutor();
        Capability send = reg.describe("conn.mail.send_draft");
        assertNotNull(send);
        JSONObject args = new JSONObject().put("draft_id", id);
        assertNull(mail.prepareSendArgs(args));
        ExecutionGate.Decision d = ExecutionGate.evaluate(send, args, false);
        assertTrue(d.needsConfirm());
        assertEquals("可信来源：黄卡", ConsentPolicy.TIER_YELLOW, d.tier);

        ExecutionResult noTok = ex.execute(send, args, null, false);
        assertEquals(ErrorCode.RISK_GATED, noTok.code);
        assertTrue(gmail.sent.isEmpty());

        assertTrue(ExecutionGate.approve(d.token));
        ExecutionResult ok = ex.execute(send, args, d.token, false);
        assertTrue(ok.userMessage, ok.ok);
        assertEquals(1, gmail.sent.size());
        assertTrue(ok.userMessage.contains("boss@example.com"));
        assertFalse("令牌一次性", ex.execute(send, args, d.token, false).ok);
        assertEquals(1, gmail.sent.size());
    }

    @Test public void legacyBooleanAndAlwaysAllowCannotSend() throws Exception {
        setUp();
        String id = createDraftViaExecutor();
        Capability send = reg.describe("conn.mail.send_draft");
        JSONObject args = new JSONObject().put("draft_id", id);
        mail.prepareSendArgs(args);

        // 非严格模式下旧布尔能过 ExecutionGate，但拿不到消费回执 → MailSendGuard 拒绝
        ExecutionGate.setStrictConfirm(false);
        ExecutionResult legacy = ex.executeInternal(send, args, true, false);
        assertFalse(legacy.ok);
        assertEquals(ErrorCode.GATE_DENIED, legacy.code);
        assertTrue(legacy.userMessage, legacy.userMessage.contains("确认卡"));
        assertTrue(gmail.sent.isEmpty());

        // 「始终允许」不适用于对外发送
        ConsentPolicy.allowAlways("conn.mail.send_draft");
        assertTrue("仍要确认", ExecutionGate.evaluate(send, args, false).needsConfirm());
        assertFalse(ex.execute(send, args, null, false).ok);
        assertTrue(gmail.sent.isEmpty());
    }

    @Test public void tamperingIsRejected() throws Exception {
        setUp();
        String id = createDraftViaExecutor();
        Capability send = reg.describe("conn.mail.send_draft");
        JSONObject args = new JSONObject().put("draft_id", id);
        mail.prepareSendArgs(args);
        ExecutionGate.Decision d = ExecutionGate.evaluate(send, args, false);
        ExecutionGate.approve(d.token);

        // 确认后改参数
        JSONObject other = new JSONObject(args.toString()).put("draft_id", "d-other");
        assertFalse(ex.execute(send, other, d.token, false).ok);
        assertTrue(gmail.sent.isEmpty());

        // 确认后重新起草：参数里的旧指纹和台账不一致 → 拒绝
        ExecutionGate.Decision d2 = ExecutionGate.evaluate(send, args, false);
        ExecutionGate.approve(d2.token);
        com.onesay.assistant.v2.conn.mail.MailDraft nd = new com.onesay.assistant.v2.conn.mail.MailDraft("boss@example.com", "", "新主题",
                "完全重写的一封邮件正文，内容已经变了。");
        assertTrue(mail.updateDraft(id, nd).ok());
        ExecutionResult r = ex.execute(send, args, d2.token, false);
        assertFalse(r.ok);
        assertTrue(r.userMessage, r.userMessage.contains("重新确认"));
        assertTrue(gmail.sent.isEmpty());

        // 被污染的任务：红卡
        assertEquals(ConsentPolicy.TIER_RED, ExecutionGate.evaluate(send, args, true).tier);
    }

    @Test public void receiptIsThreadLocalAndOneShot() throws Exception {
        setUp();
        assertNull(ExecutionGate.takeConsumedReceipt());
        String id = createDraftViaExecutor();
        assertNull("不需要确认的执行不留回执", ExecutionGate.takeConsumedReceipt());
        Capability send = reg.describe("conn.mail.send_draft");
        JSONObject args = new JSONObject().put("draft_id", id);
        mail.prepareSendArgs(args);
        ExecutionGate.Decision d = ExecutionGate.evaluate(send, args, false);
        ExecutionGate.approve(d.token);
        assertTrue(ex.execute(send, args, d.token, false).ok);
        assertNull("回执已被发送闸取走", ExecutionGate.takeConsumedReceipt());
    }
}
