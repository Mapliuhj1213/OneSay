package com.onesay.assistant.v2.conn.mail;

import com.onesay.assistant.v2.conn.Codec;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * [conn/mail] 一封草稿的内容（收件人 / 抄送 / 主题 / 正文）。不可变；{@link #contentHash()} 是确认卡与发送时比对用的指纹。
 */
public final class MailDraft {

    /** 正文最少字数：草稿要由大模型起草成完整邮件，一句口令式的原话不算草稿。 */
    public static final int MIN_BODY = 10;
    public static final int MAX_BODY = 20000;
    public static final int MAX_RECIPIENTS = 10;

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    public final String to;
    public final String cc;
    public final String subject;
    public final String body;

    public MailDraft(String to, String cc, String subject, String body) {
        this.to = norm(to);
        this.cc = norm(cc);
        this.subject = subject == null ? "" : subject.trim().replace('\r', ' ').replace('\n', ' ');
        this.body = body == null ? "" : body.replace("\r\n", "\n").trim();
    }

    public static MailDraft fromArgs(JSONObject a) {
        if (a == null) a = new JSONObject();
        return new MailDraft(a.optString("to", ""), a.optString("cc", ""), a.optString("subject", ""), a.optString("body", ""));
    }

    /** 收件人：逗号 / 分号 / 空白分隔 → 规范成 "a@x.com, b@y.com"。 */
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : s.split("[,;，；\\s]+")) {
            String t = p.trim();
            if (t.length() == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(t);
        }
        return sb.toString();
    }

    /** @return null = 合法；否则原因。requireRecipient：发送 / 存草稿都要求有收件人（没有收件人的草稿发不出去，也确认不了）。 */
    public String validate() {
        if (to.length() == 0) return "缺收件人邮箱（to）";
        String bad = badAddress(to);
        if (bad == null && cc.length() > 0) bad = badAddress(cc);
        if (bad != null) return "收件人地址不合法：" + bad;
        if (count(to) + count(cc) > MAX_RECIPIENTS) return "收件人太多（最多 " + MAX_RECIPIENTS + " 个）";
        if (subject.length() == 0) return "缺主题（subject）：请根据用户意图起一个主题";
        if (body.length() < MIN_BODY) return "正文太短：请根据用户意图把邮件完整起草出来（称呼、正文、落款），不要只照抄一句话";
        if (body.length() > MAX_BODY) return "正文太长（上限 " + MAX_BODY + " 字）";
        return null;
    }

    static String badAddress(String list) {
        for (String p : list.split(", ")) {
            String t = p.trim();
            int lt = t.indexOf('<'), gt = t.indexOf('>');
            if (lt >= 0 && gt > lt) t = t.substring(lt + 1, gt);
            if (!EMAIL.matcher(t).matches()) return p;
        }
        return null;
    }

    static int count(String list) {
        return list.length() == 0 ? 0 : list.split(", ").length;
    }

    /** 内容指纹：任何一个字段变了指纹就变。 */
    public String contentHash() {
        return Codec.sha256Hex("to=" + to + "\ncc=" + cc + "\nsubject=" + subject + "\nbody=" + body).substring(0, 32);
    }

    /** 给确认卡 / 模型看的摘要（正文截断）。 */
    public String preview(int bodyChars) {
        String b = body.length() > bodyChars ? body.substring(0, bodyChars) + "…" : body;
        return "收件人：" + to + (cc.length() > 0 ? "\n抄送：" + cc : "") + "\n主题：" + subject + "\n正文：\n" + b;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("to", to).put("cc", cc).put("subject", subject).put("body", body);
        } catch (Exception ignored) {
        }
        return o;
    }
}
