package com.onesay.assistant.v2.conn.mail;

import com.onesay.assistant.v2.conn.Codec;
import com.onesay.assistant.v2.conn.ConnErrors;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.Redact;

import java.util.ArrayList;
import java.util.List;

/**
 * [conn/mail] IMAP + SMTP 后端。
 *
 * <ul>
 *   <li>读：IMAP 没有「重要」这个概念 → 未读里先挑加星（\Flagged）/ X-Priority 1-2 / Importance: high 的；一封都没有就给最新未读并说明；</li>
 *   <li>草稿：APPEND 到草稿箱；改 = APPEND 新版本（rev+1）再删旧版本；</li>
 *   <li>发：从草稿箱读回<b>服务器上的那封</b>，核对内容指纹（和确认卡上看到的一致）→ SMTP 发出 → 删除草稿（删不掉就标记 BixbySent）。</li>
 * </ul>
 * remoteId = 草稿键（随机），rev = 版本号（十进制字符串）。
 */
public final class ImapAccount implements MailAccount {

    static final int SCAN = 30;

    public interface ReadyCheck {
        boolean ready();
    }

    private final ImapOps mOps;
    private final ReadyCheck mReady;

    public ImapAccount(ImapOps ops, ReadyCheck ready) {
        mOps = ops;
        mReady = ready;
    }

    @Override public String kind() { return IMAP; }

    @Override public boolean isReady() { return mOps != null && (mReady == null || mReady.ready()); }

    @Override
    public Listing unreadImportant(int max) {
        int n = Math.max(1, Math.min(10, max <= 0 ? 5 : max));
        try {
            List<MailSummary> all = mOps.unread(SCAN);
            List<MailSummary> imp = new ArrayList<MailSummary>();
            for (MailSummary m : all) if (m.important && imp.size() < n) imp.add(m);
            if (!imp.isEmpty()) {
                return new Listing(ConnResult.ok(MailSummary.render("邮箱（IMAP）未读重要邮件 " + imp.size() + " 封：", imp), MailSummary.json(imp)), imp);
            }
            if (all.isEmpty()) return new Listing(ConnResult.ok("邮箱（IMAP）里没有未读邮件。", "[]"), all);
            List<MailSummary> top = all.subList(0, Math.min(n, all.size()));
            return new Listing(ConnResult.ok(MailSummary.render("IMAP 邮箱没有「重要」标记（未读里没有加星 / 高优先级的），按时间列出最新未读 "
                    + top.size() + " 封：", top), MailSummary.json(top)), new ArrayList<MailSummary>(top));
        } catch (Throwable t) {
            return Listing.fail(fail("读收件箱", t));
        }
    }

    @Override
    public Listing search(String query, int max) {
        if (query == null || query.trim().length() == 0) return Listing.fail(ConnResult.fail(ConnResult.Code.BAD_ARGS, "缺搜索词"));
        int n = Math.max(1, Math.min(10, max <= 0 ? 5 : max));
        try {
            List<MailSummary> l = mOps.search(query.trim(), n);
            String head = l.isEmpty() ? "邮箱（IMAP）里没搜到「" + query.trim() + "」相关邮件。" : "邮箱（IMAP）搜到 " + l.size() + " 封：";
            return new Listing(ConnResult.ok(MailSummary.render(head, l), MailSummary.json(l)), l);
        } catch (Throwable t) {
            return Listing.fail(fail("搜索邮件", t));
        }
    }

    @Override
    public Remote createDraft(MailDraft d) {
        String key = Codec.random(12);
        try {
            mOps.appendDraft(d, key, 1);
            return new Remote(ConnResult.ok("草稿已存入邮箱草稿箱"), key, "1");
        } catch (Throwable t) {
            return Remote.fail(fail("存草稿", t));
        }
    }

    @Override
    public Remote updateDraft(String remoteId, String rev, MailDraft d) {
        int r = parseRev(rev);
        try {
            if (mOps.readDraft(remoteId, r) == null) {
                return Remote.fail(ConnResult.fail(ConnResult.Code.DENIED, "原草稿已不在草稿箱（可能被删或被改过），请新建草稿"));
            }
            mOps.appendDraft(d, remoteId, r + 1);
            try {
                mOps.deleteDraft(remoteId, r);
            } catch (Throwable ignored) {
                // 旧版本删不掉不影响新版本；发送时只认新 rev
            }
            return new Remote(ConnResult.ok("草稿已更新"), remoteId, String.valueOf(r + 1));
        } catch (Throwable t) {
            return Remote.fail(fail("更新草稿", t));
        }
    }

    @Override
    public ConnResult sendDraft(String remoteId, String rev, SendPermit permit) {
        if (permit == null) return ConnResult.fail(ConnResult.Code.DENIED, "没有发送许可");
        int r = parseRev(rev);
        MailDraft d;
        try {
            d = mOps.readDraft(remoteId, r);
        } catch (Throwable t) {
            return fail("读取草稿", t);
        }
        if (d == null) return ConnResult.fail(ConnResult.Code.DENIED, "草稿已不在草稿箱（可能被删或被改过），没有发送");
        if (!d.contentHash().equals(permit.contentHash)) {
            return ConnResult.fail(ConnResult.Code.DENIED, "草稿箱里的内容和确认时不一致，没有发送；请重新确认");
        }
        try {
            mOps.smtpSend(d);
        } catch (Throwable t) {
            return fail("SMTP 发送", t);
        }
        String tail;
        try {
            if (mOps.deleteDraft(remoteId, r)) tail = "，草稿已从草稿箱删除";
            else tail = mOps.markDraftSent(remoteId, r) ? "，草稿已标记为已发送" : "，但草稿没能删除（请手动清理）";
        } catch (Throwable t) {
            boolean marked = false;
            try {
                marked = mOps.markDraftSent(remoteId, r);
            } catch (Throwable ignored) {
            }
            tail = marked ? "，草稿已标记为已发送" : "，但草稿没能删除（请手动清理）";
        }
        return ConnResult.ok("已通过 SMTP 发出" + tail);
    }

    @Override
    public ConnResult deleteDraft(String remoteId, String rev) {
        try {
            return mOps.deleteDraft(remoteId, parseRev(rev)) ? ConnResult.ok("草稿已删除") : ConnResult.ok("草稿本来就不在了");
        } catch (Throwable t) {
            return fail("删除草稿", t);
        }
    }

    static int parseRev(String rev) {
        try {
            return Math.max(1, Integer.parseInt(rev));
        } catch (Exception e) {
            return 1;
        }
    }

    /** 异常分类：认证失败 → AUTH_REVOKED（不重试，防风控）；网络 → UNREACHABLE / TIMEOUT。 */
    static ConnResult fail(String what, Throwable t) {
        if (isAuth(t)) return ConnResult.fail(ConnResult.Code.AUTH_REVOKED, what + "失败：邮箱登录被拒（授权码错误或已失效）");
        ConnResult.Code c = ConnErrors.classify(t);
        if (c == ConnResult.Code.REMOTE_ERROR && t != null && String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("connect")) {
            c = ConnResult.Code.UNREACHABLE;
        }
        return ConnResult.fail(c, what + "失败：" + Redact.t(t));
    }

    static boolean isAuth(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getClass().getName().endsWith("AuthenticationFailedException")) return true;
            String m = String.valueOf(c.getMessage());
            if (m.contains("AUTHENTICATIONFAILED") || m.contains("535") && m.toLowerCase(java.util.Locale.ROOT).contains("auth")) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }
}
