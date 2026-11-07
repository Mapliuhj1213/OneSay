package com.onesay.assistant.v2.seclogic;

import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.conn.ToolAllowlist;
import com.onesay.assistant.v2.conn.ToolEffect;
import com.onesay.assistant.v2.jobs.Perm;
import com.onesay.assistant.v2.jobs.PermissionAnalyzer;
import com.onesay.assistant.v2.jobs.PermissionGuard;
import com.onesay.assistant.v2.s3.S3Safety;
import com.onesay.assistant.v2.security.OutboundJudge;
import com.onesay.assistant.v2.security.SafetyText;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

/**
 * r2/sec-logic LOGIC-5（MCP 词表统一）+ LOGIC-7c（创建期红词归一化）。用例取自 poc/logic/Poc.java §5、Poc3.java、Poc.java §2/5。
 */
public class SecLogicOutboundTest {

    @Test public void safety_text_normalizes_bypass_tricks() {
        assertEquals("提交订单", SafetyText.squash("提交\u200b訂單"));
        assertEquals("paynow", SafetyText.squash("ＰＡＹ　ＮＯＷ"));
        assertEquals("paynow", SafetyText.squash("\u0420\u0430\u0443 now"));
        assertEquals("立即支付", SafetyText.squash("立即 · 支付"));
        assertEquals("每天发邮件给老板", SafetyText.fold("每天發郵件給老闆"));
        assertEquals(" send email ", SafetyText.words("SEND_EMAIL"));
        assertEquals(" send mail ", SafetyText.words("SendMail"));
        assertEquals(" post message ", SafetyText.words("postMessage"));
        assertEquals(" send email ", SafetyText.words("\u0455end_email"));
    }

    static final String[] SEND_NAMES = {"send_email", "gmail_send", "postMessage", "dispatch_mail", "deliver_message", "emit_note",
            "create_outgoing", "relay", "submit_letter", "enqueue_sms", "push_notification", "SEND_EMAIL", "SENDMAIL", "SendEmail",
            "\u0455end_email", "send-email", "sendmail", "Send", "reply_all", "forwardMessage", "notify_user", "broadcast"};

    @Test public void outbound_judge_catches_all_poc_names() {
        for (String n : SEND_NAMES) assertTrue("应识别为对外发送：" + n, OutboundJudge.looksLikeSendVerb(n));
        String[] reads = {"search_docs", "list_messages", "get_email", "read_inbox", "fetch_weather", "list_channels", "query_db"};
        for (String n : reads) assertFalse("只读工具不该当发送：" + n, OutboundJudge.looksLikeSendVerb(n));
    }

    @Test public void schema_structure_is_recognized() throws Exception {
        JSONObject s1 = new JSONObject("{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}}}");
        JSONObject s2 = new JSONObject("{\"type\":\"object\",\"properties\":{\"channel\":{\"type\":\"string\"},\"text\":{}}}");
        JSONObject s3 = new JSONObject("{\"type\":\"object\",\"properties\":{\"Subject\":{},\"Body\":{}}}");
        JSONObject s4 = new JSONObject("{\"type\":\"object\",\"properties\":{\"query\":{},\"limit\":{}}}");
        assertTrue(OutboundJudge.schemaLooksOutbound(s1));
        assertTrue(OutboundJudge.schemaLooksOutbound(s2));
        assertTrue(OutboundJudge.schemaLooksOutbound(s3));
        assertFalse(OutboundJudge.schemaLooksOutbound(s4));
        // 名字无害（do_task）+ 用户误标 read，但 schema 有收件人 → 至少 COMMS
        ToolAllowlist a = new ToolAllowlist().allow("do_task", ToolEffect.READ);
        assertEquals(ToolEffect.COMMS, a.effectFor("do_task", null, s1));
        assertEquals(ToolEffect.READ, a.effectFor("do_task", null, s4));
    }

    @Test public void allowlist_upgrades_disguised_send_tools_to_comms() {
        for (String n : SEND_NAMES) {
            ToolAllowlist a = new ToolAllowlist().allow(n, ToolEffect.WRITE);
            assertEquals("标 write 也要升到 COMMS：" + n, ToolEffect.COMMS, a.effectFor(n, null));
        }
        assertEquals(ToolEffect.READ, new ToolAllowlist().allow("list_messages", ToolEffect.READ).effectFor("list_messages", null));
    }

    @Test public void remote_conn_tools_are_never_pregrantable() {
        String[] remote = {"conn.x.send_email", "conn.x.dispatch_mail", "conn.x.SEND_EMAIL", "conn.x.search_docs", "conn.x.notify_user"};
        for (String s : remote) {
            Perm p = PermissionAnalyzer.forTool(s, SideEffect.PERSISTENT_WRITE);
            assertEquals(s, Perm.REMOTE_TOOL, p);
            assertFalse(p.preGrantable);
            assertEquals("无人值守每次确认：" + s, PermissionGuard.Verdict.CONFIRM_EACH_TIME, PermissionGuard.check(s, SideEffect.PERSISTENT_WRITE, null, false).verdict);
            assertTrue("isRedClass 同一判据：" + s, S3Safety.isRedClass(s, SideEffect.PERSISTENT_WRITE));
        }
        // 内置连接器不受影响
        assertEquals(Perm.MAIL_SEND, PermissionAnalyzer.forTool("conn.mail.send_draft", null));
        assertEquals(Perm.MAIL_DRAFT, PermissionAnalyzer.forTool("conn.mail.delete_draft", null));
        assertEquals(Perm.WRITE_TODO, PermissionAnalyzer.forTool("conn.google.tasks_create", null));
        assertNull(PermissionAnalyzer.forTool("ask.reply", null));
        assertFalse(S3Safety.isRedClass("ask.reply", SideEffect.NONE));
    }

    @Test public void local_outbound_signs_unified() {
        assertEquals(Perm.RED_ACTION, PermissionAnalyzer.forTool("note.dispatch", SideEffect.PERSISTENT_WRITE));
        assertTrue(S3Safety.isRedClass("note.dispatch", SideEffect.PERSISTENT_WRITE));
        assertTrue(S3Safety.isRedClass("x.\u0455end", SideEffect.PERSISTENT_WRITE));
        assertEquals(Perm.SYSTEM_SETTINGS, PermissionAnalyzer.forTool("settings.brightness.up", SideEffect.DEVICE_CONTROL));
        assertFalse(S3Safety.isRedClass("settings.brightness.up", SideEffect.DEVICE_CONTROL));
        assertFalse(S3Safety.isRedClass("browser.intent_dispatcher_33", SideEffect.DEVICE_CONTROL));
    }

    @Test public void s3_red_words_normalized_and_multilingual() {
        String[] red = {"每天早上发邮件给老板汇报", "到点帮我付款", "every morning send email to boss", "pay the bill at 9am",
                "每天發郵件給老闆", "到点把报告寄出去", "每天 发 邮件", "Delete old photos every night", "每天轉帳給媽媽", "每天\u200b付款"};
        for (String c : red) assertTrue("应需确认：" + c, S3Safety.commandNeedsConfirm(c));
        String[] ok = {"每天早上八点提醒我喝水", "每周一总结一下我的待办", "every morning tell me the weather", "下雨就提醒我带伞"};
        for (String c : ok) assertFalse("不该要确认：" + c, S3Safety.commandNeedsConfirm(c));
    }

    @Test public void permission_card_lists_send_and_pay_for_english_and_traditional() {
        Set<Perm> a = PermissionAnalyzer.analyze("every morning send email to boss", null, null);
        assertTrue(a.toString(), a.contains(Perm.MAIL_SEND));
        Set<Perm> b = PermissionAnalyzer.analyze("每天發郵件給老闆", null, null);
        assertTrue(b.toString(), b.contains(Perm.MAIL_SEND));
        Set<Perm> c = PermissionAnalyzer.analyze("pay the bill at 9am", null, null);
        assertTrue(c.toString(), c.contains(Perm.PAYMENT));
        Set<Perm> d = PermissionAnalyzer.analyze("每天早上提醒我喝水", null, null);
        assertFalse(d.contains(Perm.MAIL_SEND));
        assertFalse(d.contains(Perm.PAYMENT));
    }
}
