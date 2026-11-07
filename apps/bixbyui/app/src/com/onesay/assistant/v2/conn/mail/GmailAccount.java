package com.onesay.assistant.v2.conn.mail;

import com.onesay.assistant.v2.conn.Codec;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.google.GoogleApi;
import com.onesay.assistant.v2.conn.google.GoogleScopes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [conn/mail] Gmail 后端（Gmail REST API v1）。读需要 gmail.readonly；草稿建 / 改 / 删 / 发用 drafts API，只要 gmail.compose。
 *
 * <ul>
 *   <li>未读重要：{@code messages.list q="is:unread is:important"} + 每封 {@code messages.get format=metadata}（发件人 / 主题 / 日期 + snippet）；</li>
 *   <li>搜索：{@code q} 直接用 Gmail 搜索语法；</li>
 *   <li>草稿：{@code drafts.create / update / send / delete}；rev = draft.message.id（Gmail 每改一次草稿就换一个 message id）。
 *       发送前 {@code drafts.get format=minimal} 核对 rev，在网页上被改过就拒绝；drafts.send 成功后 Gmail 自动删掉草稿。</li>
 * </ul>
 */
public final class GmailAccount implements MailAccount {

    static final String BASE = "https://gmail.googleapis.com/gmail/v1/users/me";
    static final int MAX_LIST = 10;

    private final GoogleApi mApi;

    public GmailAccount(GoogleApi api) {
        mApi = api;
    }

    @Override public String kind() { return GMAIL; }

    @Override
    public boolean isReady() {
        return mApi != null && mApi.auth().configured() && mApi.auth().signedIn()
                && (mApi.auth().hasScope(GoogleScopes.GMAIL_READONLY) || mApi.auth().hasScope(GoogleScopes.GMAIL_COMPOSE));
    }

    boolean canRead() {
        return mApi.auth().hasScope(GoogleScopes.GMAIL_READONLY);
    }

    boolean canCompose() {
        return mApi.auth().hasScope(GoogleScopes.GMAIL_COMPOSE);
    }

    @Override
    public Listing unreadImportant(int max) {
        Listing l = list("is:unread is:important", max);
        if (l.result.ok() && l.items.isEmpty()) {
            return new Listing(ConnResult.ok("Gmail 里没有未读的重要邮件。", "[]"), l.items);
        }
        if (l.result.ok()) return new Listing(ConnResult.ok(MailSummary.render("Gmail 未读重要邮件 " + l.items.size() + " 封：", l.items),
                MailSummary.json(l.items)), l.items);
        return l;
    }

    @Override
    public Listing search(String query, int max) {
        if (query == null || query.trim().length() == 0) return Listing.fail(ConnResult.fail(ConnResult.Code.BAD_ARGS, "缺搜索词"));
        Listing l = list(query.trim(), max);
        if (!l.result.ok()) return l;
        String head = l.items.isEmpty() ? "Gmail 里没搜到「" + query.trim() + "」相关邮件。" : "Gmail 搜到 " + l.items.size() + " 封：";
        return new Listing(ConnResult.ok(MailSummary.render(head, l.items), MailSummary.json(l.items)), l.items);
    }

    private Listing list(String q, int max) {
        if (!canRead()) return Listing.fail(ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "Google 授权里没有 Gmail 读取权限，需要重新登录授权"));
        int n = Math.max(1, Math.min(MAX_LIST, max <= 0 ? 5 : max));
        ConnResult r = mApi.get(BASE + "/messages?maxResults=" + n + "&q=" + Codec.urlEncode(q));
        if (!r.ok()) return Listing.fail(r);
        List<MailSummary> out = new ArrayList<MailSummary>();
        try {
            JSONArray arr = new JSONObject(r.data).optJSONArray("messages");
            for (int i = 0; arr != null && i < arr.length() && i < n; i++) {
                String id = arr.getJSONObject(i).optString("id");
                ConnResult m = mApi.get(BASE + "/messages/" + Codec.urlEncode(id)
                        + "?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date");
                if (!m.ok()) {
                    if (i == 0) return Listing.fail(m);
                    break;
                }
                out.add(summary(new JSONObject(m.data)));
            }
        } catch (Exception e) {
            return Listing.fail(ConnResult.fail(ConnResult.Code.REMOTE_ERROR, "Gmail 响应解析失败"));
        }
        return new Listing(ConnResult.ok(""), out);
    }

    static MailSummary summary(JSONObject msg) {
        String from = "", subject = "", date = "";
        JSONObject payload = msg.optJSONObject("payload");
        JSONArray headers = payload != null ? payload.optJSONArray("headers") : null;
        for (int i = 0; headers != null && i < headers.length(); i++) {
            JSONObject h = headers.optJSONObject(i);
            if (h == null) continue;
            String n = h.optString("name");
            if ("From".equalsIgnoreCase(n)) from = h.optString("value");
            else if ("Subject".equalsIgnoreCase(n)) subject = h.optString("value");
            else if ("Date".equalsIgnoreCase(n)) date = h.optString("value");
        }
        boolean unread = false, important = false;
        JSONArray labels = msg.optJSONArray("labelIds");
        for (int i = 0; labels != null && i < labels.length(); i++) {
            String l = labels.optString(i);
            if ("UNREAD".equals(l)) unread = true;
            if ("IMPORTANT".equals(l)) important = true;
        }
        return new MailSummary(msg.optString("id"), from, subject, date, unescape(msg.optString("snippet")), unread, important);
    }

    /** snippet 里是 HTML 实体。 */
    static String unescape(String s) {
        return s == null ? "" : s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    @Override
    public Remote createDraft(MailDraft d) {
        if (!canCompose()) return Remote.fail(ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "Google 授权里没有 gmail.compose（草稿）权限，需要重新登录授权"));
        try {
            JSONObject body = new JSONObject().put("message", new JSONObject().put("raw", MimeText.raw(d)));
            return remote(mApi.post(BASE + "/drafts", body));
        } catch (Exception e) {
            return Remote.fail(ConnResult.fail(ConnResult.Code.BAD_ARGS, "草稿报文生成失败"));
        }
    }

    @Override
    public Remote updateDraft(String remoteId, String rev, MailDraft d) {
        if (!canCompose()) return Remote.fail(ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "缺 gmail.compose 权限"));
        ConnResult chk = verify(remoteId, rev);
        if (!chk.ok()) return Remote.fail(chk);
        try {
            JSONObject body = new JSONObject().put("id", remoteId).put("message", new JSONObject().put("raw", MimeText.raw(d)));
            return remote(mApi.put(BASE + "/drafts/" + Codec.urlEncode(remoteId), body));
        } catch (Exception e) {
            return Remote.fail(ConnResult.fail(ConnResult.Code.BAD_ARGS, "草稿报文生成失败"));
        }
    }

    private static Remote remote(ConnResult r) {
        if (!r.ok()) return Remote.fail(r);
        try {
            JSONObject o = new JSONObject(r.data);
            String id = o.optString("id", "");
            JSONObject m = o.optJSONObject("message");
            String rev = m != null ? m.optString("id", "") : "";
            if (id.length() == 0) return Remote.fail(ConnResult.fail(ConnResult.Code.REMOTE_ERROR, "Gmail 没返回草稿 id"));
            return new Remote(ConnResult.ok("Gmail 草稿已保存"), id, rev);
        } catch (Exception e) {
            return Remote.fail(ConnResult.fail(ConnResult.Code.REMOTE_ERROR, "Gmail 响应解析失败"));
        }
    }

    /** 草稿还在、且没在别处被改过（message id 没变）。 */
    ConnResult verify(String remoteId, String rev) {
        ConnResult r = mApi.get(BASE + "/drafts/" + Codec.urlEncode(remoteId) + "?format=minimal");
        if (!r.ok()) {
            if (r.code == ConnResult.Code.BAD_ARGS) return ConnResult.fail(ConnResult.Code.DENIED, "草稿已不存在（可能已发送或被删除）");
            return r;
        }
        try {
            JSONObject m = new JSONObject(r.data).optJSONObject("message");
            String cur = m != null ? m.optString("id", "") : "";
            if (rev != null && rev.length() > 0 && !rev.equals(cur)) {
                return ConnResult.fail(ConnResult.Code.DENIED, "草稿在 Gmail 里被改过，需要重新起草并确认");
            }
            return ConnResult.ok("");
        } catch (Exception e) {
            return ConnResult.fail(ConnResult.Code.REMOTE_ERROR, "Gmail 响应解析失败");
        }
    }

    @Override
    public ConnResult sendDraft(String remoteId, String rev, SendPermit permit) {
        if (permit == null) return ConnResult.fail(ConnResult.Code.DENIED, "没有发送许可");
        if (!canCompose()) return ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "缺 gmail.compose 权限");
        ConnResult chk = verify(remoteId, rev);
        if (!chk.ok()) return chk;
        try {
            ConnResult r = mApi.post(BASE + "/drafts/send", new JSONObject().put("id", remoteId));
            if (!r.ok()) return r;
            return ConnResult.ok("已通过 Gmail 发出", r.data);
        } catch (Exception e) {
            return ConnResult.fail(ConnResult.Code.REMOTE_ERROR, "发送请求生成失败");
        }
    }

    @Override
    public ConnResult deleteDraft(String remoteId, String rev) {
        ConnResult r = mApi.delete(BASE + "/drafts/" + Codec.urlEncode(remoteId));
        if (!r.ok() && r.code == ConnResult.Code.BAD_ARGS) return ConnResult.ok("草稿本来就不在了");
        return r.ok() ? ConnResult.ok("Gmail 草稿已删除") : r;
    }
}
