package com.onesay.assistant.v2.security;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * <b>唯一一套</b>「对外发送」判据（纯 Java）。
 *
 * <p>原来三处各有一份词表：{@code ToolAllowlist.SEND_VERB/COMMS_NOUN}（MCP 定级）、{@code PermissionAnalyzer.forTool}（无人值守权限）、
 * {@code S3Safety.isRedClass}（无人值守红卡），各有漏网：dispatch_mail / deliver_message / emit_note / relay / submit_letter /
 * enqueue_sms / push_notification、全大写 SEND_EMAIL（旧 splitCamel 拆成 s_e_n_d）、同形字 ѕend_email。现在三处都调本类：
 * <ul>
 *   <li>工具名先 {@link SafetyText#words} 归一化（NFKC、去零宽、同形字→拉丁、全大写不再逐字母拆、camelCase / 下划线 / 连字符切词）；</li>
 *   <li>发送动词：send reply forward fwd publish post tweet broadcast notify <b>dispatch deliver emit relay submit enqueue push transmit share</b> …；</li>
 *   <li>通信名词：mail email gmail message msg sms dm chat comment invite letter notification …；连写（sendmail / gmailsend / pushnotify）也认；</li>
 *   <li>结构识别：inputSchema.properties 里有收件人类字段（to / recipient / cc / bcc / channel / chat_id / phone / email / address …），
 *       或同时有 subject + body/text/message → 视为对外发送。</li>
 * </ul>
 */
public final class OutboundJudge {

    private OutboundJudge() {}

    /** 强发送动词：单独出现就算。 */
    static final Set<String> SEND_VERBS = new HashSet<String>(Arrays.asList(
            "send", "sends", "sent", "sending", "reply", "replies", "forward", "fwd", "publish", "post", "tweet", "retweet",
            "broadcast", "notify", "dispatch", "deliver", "emit", "relay", "submit", "enqueue", "push", "transmit", "announce"));
    /** 弱动词（也常作名词）：要和通信名词同现才算（share_message、queue_sms、text_message、upload_comment …）。 */
    static final Set<String> WEAK_VERBS = new HashSet<String>(Arrays.asList(
            "mail", "email", "text", "sms", "message", "dm", "queue", "upload", "share", "ping", "page", "invite", "create", "write", "add", "new", "compose"));
    static final Set<String> COMMS_NOUNS = new HashSet<String>(Arrays.asList(
            "mail", "mails", "email", "emails", "gmail", "e", "message", "messages", "msg", "msgs", "sms", "mms", "dm", "dms", "chat",
            "chats", "comment", "comments", "invite", "invites", "invitation", "letter", "letters", "notification", "notifications",
            "note", "notes", "tweet", "post", "posts", "reply", "status", "webhook", "slack", "telegram", "wechat", "whatsapp",
            "outgoing", "outbound", "newsletter", "fax"));

    static final String[] STRONG_VERBS_SORTED = {"dispatch", "deliver", "forward", "publish", "broadcast", "transmit", "enqueue",
            "notify", "submit", "reply", "relay", "tweet", "send", "post", "push", "emit", "fwd"};
    static final String[] NOUN_STEMS = {"mail", "message", "msg", "sms", "notification", "notify", "letter", "chat", "dm", "comment",
            "invite", "tweet", "post", "note"};

    /** 收件人类 schema 字段。 */
    static final Set<String> RECIPIENT_FIELDS = new HashSet<String>(Arrays.asList(
            "to", "recipient", "recipients", "cc", "bcc", "channel", "channel_id", "chat_id", "chatid", "phone", "phone_number",
            "number", "email", "email_address", "address", "receiver", "receivers", "target_user", "user_id", "webhook", "webhook_url",
            "destination", "thread_id", "conversation_id", "room", "room_id"));
    static final Set<String> BODY_FIELDS = new HashSet<String>(Arrays.asList("body", "text", "message", "content", "html", "markdown", "msg"));

    /** 工具名像「对外发送」（发送动词）。 */
    public static boolean looksLikeSendVerb(String toolName) {
        String[] toks = tokens(toolName);
        int nouns = 0;
        for (String t : toks) if (isNoun(t)) nouns++;
        for (String t : toks) {
            if (t.length() == 0) continue;
            if (SEND_VERBS.contains(t)) return true;
            if (compoundSend(t)) return true;
            // 弱动词 + 另一个通信名词（create_outgoing_message、text_message、queue_sms）
            if (WEAK_VERBS.contains(t) && nouns - (isNoun(t) ? 1 : 0) > 0) return true;
        }
        return false;
    }

    static boolean isNoun(String t) {
        return COMMS_NOUNS.contains(t) && !"e".equals(t);
    }

    /** 工具名带通信名词。 */
    public static boolean looksLikeCommsNoun(String toolName) {
        for (String t : tokens(toolName)) {
            if (COMMS_NOUNS.contains(t) && !"e".equals(t) && !"status".equals(t) && !"post".equals(t) && !"posts".equals(t)
                    && !"reply".equals(t) && !"note".equals(t) && !"notes".equals(t)) return true;
            if (compoundSend(t)) return true;
            for (String n : new String[]{"gmail", "email", "sms"}) if (t.startsWith(n) || t.endsWith(n)) return true;
        }
        return false;
    }

    /** 连写：sendmail / gmailsend / sendemail / pushnotification / dispatchmsg / senddraft …（动词 + 名词，或名词 + 动词）。 */
    static boolean compoundSend(String t) {
        if (t.length() < 6) return false;
        for (String v : STRONG_VERBS_SORTED) {
            if (t.startsWith(v) && t.length() > v.length()) {
                String rest = t.substring(v.length());
                if ("draft".equals(rest) || "drafts".equals(rest) || "out".equals(rest) || "all".equals(rest)) return true;
                for (String n : NOUN_STEMS) if (rest.startsWith(n) || rest.startsWith("e" + n) || rest.startsWith("g" + n)) return true;
            }
            if (t.endsWith(v) && t.length() > v.length()) {
                String head = t.substring(0, t.length() - v.length());
                for (String n : NOUN_STEMS) if (head.endsWith(n)) return true;
            }
        }
        return false;
    }

    /** 结构识别：inputSchema.properties 有收件人类字段，或 subject + 正文。 */
    public static boolean schemaLooksOutbound(JSONObject inputSchema) {
        if (inputSchema == null) return false;
        JSONObject props = inputSchema.optJSONObject("properties");
        if (props == null) return false;
        boolean subject = false, body = false;
        Iterator<?> it = props.keys();
        while (it.hasNext()) {
            String raw = String.valueOf(it.next());
            String k = SafetyText.words(raw).trim().replace(' ', '_');
            if (RECIPIENT_FIELDS.contains(k)) return true;
            if ("subject".equals(k) || "title".equals(k)) subject = true;
            if (BODY_FIELDS.contains(k)) body = true;
        }
        return subject && body;
    }

    /** 统一判据：名字像发送，或 schema 结构像发送。 */
    public static boolean isOutbound(String toolName, JSONObject inputSchema) {
        return looksLikeSendVerb(toolName) || schemaLooksOutbound(inputSchema);
    }

    /** 能力签名（如 conn.x.SEND_EMAIL / sms.send）最后一段或整串是否对外发送。 */
    public static boolean signLooksOutbound(String sign) {
        if (sign == null) return false;
        return looksLikeSendVerb(sign);
    }

    static String[] tokens(String name) {
        String w = SafetyText.words(name).trim();
        if (w.length() == 0) return new String[0];
        return w.toLowerCase(Locale.ROOT).split(" +");
    }
}
