package com.onesay.assistant.v2.conn.mail;

import com.onesay.assistant.v2.conn.Codec;

/**
 * [conn/mail] 生成纯文本 RFC 5322 报文（Gmail drafts API 的 raw 字段用）。UTF-8 + base64，主题按 RFC 2047 编码。
 * 不写 From：Gmail 用登录账号的默认发件地址填。
 */
public final class MimeText {

    private MimeText() {}

    public static String build(MailDraft d) {
        StringBuilder sb = new StringBuilder();
        sb.append("To: ").append(header(d.to)).append("\r\n");
        if (d.cc.length() > 0) sb.append("Cc: ").append(header(d.cc)).append("\r\n");
        sb.append("Subject: ").append(encodeWord(d.subject)).append("\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n");
        sb.append("X-Mailer: OneSay\r\n");
        sb.append("\r\n");
        String b64 = Codec.b64(Codec.utf8(d.body.replace("\n", "\r\n")));
        for (int i = 0; i < b64.length(); i += 76) {
            sb.append(b64, i, Math.min(b64.length(), i + 76)).append("\r\n");
        }
        return sb.toString();
    }

    /** Gmail API 的 raw：整封报文 base64url。 */
    public static String raw(MailDraft d) {
        return Codec.b64url(Codec.utf8(build(d)));
    }

    /** 头里不允许出现换行（防头注入）。 */
    static String header(String v) {
        return v == null ? "" : v.replace("\r", " ").replace("\n", " ");
    }

    /** 纯 ASCII 原样；否则 =?UTF-8?B?…?=。 */
    static String encodeWord(String s) {
        String v = header(s);
        boolean ascii = true;
        for (int i = 0; i < v.length(); i++) if (v.charAt(i) > 126 || v.charAt(i) < 32) { ascii = false; break; }
        if (ascii) return v;
        return "=?UTF-8?B?" + Codec.b64(Codec.utf8(v)) + "?=";
    }
}
