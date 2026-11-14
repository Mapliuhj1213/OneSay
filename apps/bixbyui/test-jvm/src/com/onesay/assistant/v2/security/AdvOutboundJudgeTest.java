package com.onesay.assistant.v2.security;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 找茬 · 「对外发送」统一判据（OutboundJudge）：远程工具名 / 能力签名的各种写法。
 * 生成器：发送动词 × 通信名词 × 写法（snake / camel / Pascal / 全大写 / kebab / 点分 / 连写 / 带服务前缀）×
 * 干扰（原样 / 西里尔同形字 / 全角 / 零宽 / 软连字符）→ 都必须认成发送（注释点名的 dispatch_mail、deliver_message、emit_note、relay、
 * submit_letter、enqueue_sms、push_notification、SEND_EMAIL、ѕend_email）；只读 / 查询类（list / get / search / read …）不算；
 * schema 有收件人类字段或 subject + 正文 → 算。
 */
@RunWith(Parameterized.class)
public class AdvOutboundJudgeTest {

    static final String[] VERBS = {"send", "dispatch", "deliver", "emit", "relay", "submit", "enqueue", "push", "forward", "publish", "post", "reply", "notify", "broadcast", "transmit"};
    static final String[] NOUNS = {"email", "mail", "message", "sms", "notification", "letter", "note"};

    static String style(String v, String n, int s) {
        switch (s) {
            case 0: return v + "_" + n;
            case 1: return v + Character.toUpperCase(n.charAt(0)) + n.substring(1);
            case 2: return (v + "_" + n).toUpperCase();
            case 3: return v + "-" + n;
            case 4: return "conn.server." + v + "_" + n;
            default: return Character.toUpperCase(v.charAt(0)) + v.substring(1) + Character.toUpperCase(n.charAt(0)) + n.substring(1);
        }
    }

    static String noise(String s, int k) {
        switch (k) {
            case 0: return s;
            case 1: return s.replaceFirst("s", "ѕ").replaceFirst("e", "е").replaceFirst("o", "о");   // 西里尔
            case 2: {   // 全角
                StringBuilder b = new StringBuilder();
                for (char c : s.toCharArray()) b.append(c >= '!' && c <= '~' ? (char) (c + 0xFEE0) : c);
                return b.toString();
            }
            case 3: return s.length() > 2 ? s.charAt(0) + "\u200b" + s.substring(1, 2) + "\u200d" + s.substring(2) : s;
            default: return s.length() > 2 ? s.substring(0, 2) + "\u00ad" + s.substring(2) : s;
        }
    }

    @Parameterized.Parameters(name = "{index}: {0} [{1}]")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        // 动词 × 名词 × 写法：每个动词取 2 个名词、每组 6 种写法；干扰按轮次轮换，避免同一输入只换皮重复
        int round = 0;
        for (int vi = 0; vi < VERBS.length; vi++)
            for (int ni = 0; ni < 2; ni++) {
                String n = NOUNS[(vi + ni * 3) % NOUNS.length];
                for (int s = 0; s < 6; s++) add(out, seen, "send", noise(style(VERBS[vi], n, s), (round++) % 5));
            }
        // 注释点名的漏网写法 + 连写
        String[] named = {"dispatch_mail", "deliver_message", "emit_note", "relay", "submit_letter", "enqueue_sms", "push_notification", "SEND_EMAIL", "ѕend_email",
                "sendmail", "gmailsend", "sendemail", "pushnotification", "dispatchmsg", "senddraft", "SendDraft", "postMessage", "chat.postMessage",
                "create_outgoing_message", "text_message", "queue_sms", "share_message", "compose_email", "write_comment", "new_message", "upload_comment",
                "sms.send", "mail.send", "conn.mail.send_email", "messages.send", "tweet", "retweet", "announce", "fwd_mail", "reply_all", "SendSMS"};
        for (String s : named) if (!seen.contains("send\u0001" + s)) add(out, seen, "send", s);   // 生成器已覆盖的不重复加
        String[] notSend = {"list_messages", "get_email", "search_mail", "read_mail", "list_notifications", "get_message", "fetch_inbox", "count_unread",
                "mark_read", "archive_email", "delete_draft", "get_status", "list_posts", "read_note", "calendar_list", "get_weather", "translate_text",
                "search", "summarize", "lookup_contact", "describe_letter_format", "postal_code_lookup", "sender_info", "email_validate", "status",
                "getMessage", "ListEmails", "READ_SMS", "files.read", "drive.download", "sendable_check"};
        for (String s : notSend) add(out, seen, "notSend", s);
        // schema
        String[] schemaYes = {"{\"to\":{}}", "{\"recipient\":{}}", "{\"cc\":{}}", "{\"channel_id\":{}}", "{\"chatId\":{}}", "{\"phoneNumber\":{}}",
                "{\"webhook_url\":{}}", "{\"subject\":{},\"body\":{}}", "{\"title\":{},\"text\":{}}", "{\"Subject\":{},\"Message\":{}}", "{\"roomId\":{}}",
                "{\"email_address\":{}}", "{\"ＴＯ\":{}}", "{\"thread_id\":{},\"content\":{}}"};
        String[] schemaNo = {"{}", "{\"query\":{}}", "{\"subject\":{}}", "{\"body\":{}}", "{\"limit\":{},\"offset\":{}}", "{\"id\":{}}", "{\"folder\":{}}",
                "{\"title\":{}}", "{\"path\":{},\"content\":{}}"};
        for (String s : schemaYes) add(out, seen, "schemaYes", s);
        for (String s : schemaNo) add(out, seen, "schemaNo", s);
        return out;
    }

    static void add(List<Object[]> out, Set<String> seen, String kind, String in) {
        if (!seen.add(kind + "\u0001" + in)) throw new IllegalStateException("dup " + in);
        out.add(new Object[]{kind, in});
    }

    private final String kind, in;

    public AdvOutboundJudgeTest(String kind, String in) {
        this.kind = kind;
        this.in = in;
    }

    @Test public void judge() throws Exception {
        if (kind.equals("send")) {
            assertTrue("[" + in + "] words=" + SafetyText.words(in), OutboundJudge.looksLikeSendVerb(in));
            assertTrue(OutboundJudge.isOutbound(in, null));
            assertTrue(OutboundJudge.signLooksOutbound(in));
        } else if (kind.equals("notSend")) {
            assertFalse("[" + in + "] words=" + SafetyText.words(in), OutboundJudge.looksLikeSendVerb(in));
            assertFalse(OutboundJudge.isOutbound(in, new JSONObject().put("type", "object").put("properties", new JSONObject().put("id", new JSONObject()))));
        } else {
            JSONObject schema = new JSONObject().put("type", "object").put("properties", new JSONObject(in));
            assertEquals(in, kind.equals("schemaYes"), OutboundJudge.schemaLooksOutbound(schema));
            assertEquals(in, kind.equals("schemaYes"), OutboundJudge.isOutbound("do_thing", schema));
        }
    }
}
