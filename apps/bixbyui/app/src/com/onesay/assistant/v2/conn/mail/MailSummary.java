package com.onesay.assistant.v2.conn.mail;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** [conn/mail] 邮件列表里的一条（读 / 搜索结果）。正文只给摘要片段。 */
public final class MailSummary {

    public final String id;
    public final String from;
    public final String subject;
    public final String date;
    public final String snippet;
    public final boolean unread;
    public final boolean important;

    public MailSummary(String id, String from, String subject, String date, String snippet, boolean unread, boolean important) {
        this.id = id == null ? "" : id;
        this.from = from == null ? "" : from;
        this.subject = subject == null || subject.length() == 0 ? "(无主题)" : subject;
        this.date = date == null ? "" : date;
        String s = snippet == null ? "" : snippet.replace('\r', ' ').replace('\n', ' ').trim();
        this.snippet = s.length() > 160 ? s.substring(0, 160) + "…" : s;
        this.unread = unread;
        this.important = important;
    }

    /** 给模型的文本（编号列表）。 */
    public static String render(String head, List<MailSummary> l) {
        StringBuilder sb = new StringBuilder(head);
        for (int i = 0; i < l.size(); i++) {
            MailSummary m = l.get(i);
            sb.append('\n').append(i + 1).append(". ").append(m.important ? "[重要] " : "").append(m.unread ? "[未读] " : "")
                    .append(m.subject).append(" — ").append(m.from);
            if (m.date.length() > 0) sb.append("（").append(m.date).append("）");
            if (m.snippet.length() > 0) sb.append("\n   ").append(m.snippet);
        }
        return sb.toString();
    }

    public static String json(List<MailSummary> l) {
        JSONArray arr = new JSONArray();
        for (MailSummary m : l) {
            try {
                arr.put(new JSONObject().put("id", m.id).put("from", m.from).put("subject", m.subject).put("date", m.date)
                        .put("snippet", m.snippet).put("unread", m.unread).put("important", m.important));
            } catch (Exception ignored) {
            }
        }
        return arr.toString();
    }
}
