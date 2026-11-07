package com.onesay.assistant.v2.conn.mail;

import com.onesay.assistant.v2.conn.ConnResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 邮件测试用的假后端（同包，能造 / 查 SendPermit 字段）。 */
public final class MailTestKit {

    private MailTestKit() {}

    public static MailDraft draft() {
        return new MailDraft("boss@example.com", "", "周五请假申请", "王总您好：\n\n我周五家里有事，想请假一天，工作已交接给小李。\n\n谢谢！\n张");
    }

    /** 内存邮箱：草稿 / 已发 / 可切成断网或授权失效。 */
    public static final class FakeAccount implements MailAccount {
        public final String kind;
        public boolean ready = true;
        public ConnResult.Code failWith;
        public final Map<String, MailDraft> drafts = new LinkedHashMap<String, MailDraft>();
        public final Map<String, String> revs = new LinkedHashMap<String, String>();
        public final List<MailDraft> sent = new ArrayList<MailDraft>();
        public final List<SendPermit> permits = new ArrayList<SendPermit>();
        public final List<MailSummary> inbox = new ArrayList<MailSummary>();
        int seq;

        public FakeAccount(String kind) {
            this.kind = kind;
        }

        @Override public String kind() { return kind; }
        @Override public boolean isReady() { return ready; }

        private ConnResult err() {
            return failWith == null ? null : ConnResult.fail(failWith, kind + " 失败");
        }

        @Override public Listing unreadImportant(int max) {
            if (err() != null) return Listing.fail(err());
            return new Listing(ConnResult.ok(MailSummary.render(kind + " 未读：", inbox), MailSummary.json(inbox)), inbox);
        }

        @Override public Listing search(String q, int max) {
            if (err() != null) return Listing.fail(err());
            return new Listing(ConnResult.ok(kind + " 搜索 " + q, "[]"), new ArrayList<MailSummary>());
        }

        @Override public Remote createDraft(MailDraft d) {
            if (err() != null) return Remote.fail(err());
            String id = kind + "-r" + (++seq);
            drafts.put(id, d);
            revs.put(id, "1");
            return new Remote(ConnResult.ok("ok"), id, "1");
        }

        @Override public Remote updateDraft(String remoteId, String rev, MailDraft d) {
            if (err() != null) return Remote.fail(err());
            if (!rev.equals(revs.get(remoteId))) return Remote.fail(ConnResult.fail(ConnResult.Code.DENIED, "rev"));
            drafts.put(remoteId, d);
            String nr = String.valueOf(Integer.parseInt(rev) + 1);
            revs.put(remoteId, nr);
            return new Remote(ConnResult.ok("ok"), remoteId, nr);
        }

        @Override public ConnResult sendDraft(String remoteId, String rev, SendPermit permit) {
            if (permit == null) return ConnResult.fail(ConnResult.Code.DENIED, "no permit");
            if (err() != null) return err();
            if (!rev.equals(revs.get(remoteId))) return ConnResult.fail(ConnResult.Code.DENIED, "rev changed");
            permits.add(permit);
            sent.add(drafts.remove(remoteId));
            return ConnResult.ok("sent");
        }

        @Override public ConnResult deleteDraft(String remoteId, String rev) {
            drafts.remove(remoteId);
            return ConnResult.ok("deleted");
        }

        /** 模拟在网页上改了草稿（rev 变）。 */
        public void editElsewhere(String remoteId) {
            revs.put(remoteId, "99");
        }
    }

    /** 绕过闸门伪造许可——只在测试里用来证明「后端仍会核对草稿号和内容指纹」。 */
    public static SendPermit forgedPermit(String draftId, String hash) {
        return new SendPermit(draftId, hash, "forged");
    }

    public static void setGuardClock(final long[] now) {
        MailSendGuard.sClock = new MailSendGuard.Clock() {
            @Override public long now() { return now[0]; }
        };
    }

    public static void resetGuardClock() {
        MailSendGuard.sClock = new MailSendGuard.Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        };
    }
}
