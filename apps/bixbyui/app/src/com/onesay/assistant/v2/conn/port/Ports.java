package com.onesay.assistant.v2.conn.port;

import java.util.Collections;
import java.util.List;

/**
 * lane-a 与 B 线之间的接口注册点（桩 + 可替换实现）。真实实现由 {@link LaneBMailPorts#ensure}（CallbackPlugin.onInit /
 * MailConfirmActivity.onCreate）注册；桩只在注册前和单测里生效。
 */
public final class Ports {

    private Ports() {}

    /** 桩：邮件未接入。 */
    public static final MailReadPort STUB_READ = new MailReadPort() {
        @Override public boolean available() { return false; }
        @Override public List<String> unreadImportantTitles(int max) { return Collections.emptyList(); }
        @Override public List<String[]> correspondents(int max) { return Collections.emptyList(); }
    };

    private static volatile MailReadPort sRead = STUB_READ;

    /** 桩：草稿只存在内存里，发送一律失败（「邮件通道还没接入」）。 */
    public static final MailDraftPort STUB_DRAFT = new MailDraftPort() {
        private final java.util.Map<String, Draft> mDrafts = new java.util.concurrent.ConcurrentHashMap<String, Draft>();
        private final java.util.concurrent.atomic.AtomicLong mSeq = new java.util.concurrent.atomic.AtomicLong();

        @Override public boolean available() { return false; }

        @Override public Result createDraft(Draft d) {
            String id = "stub-" + mSeq.incrementAndGet();
            mDrafts.put(id, d);
            return Result.ok(id);
        }

        @Override public Result updateDraft(String draftId, Draft d) {
            if (draftId == null || !mDrafts.containsKey(draftId)) return Result.fail("草稿不存在");
            mDrafts.put(draftId, d);
            return Result.ok(draftId);
        }

        @Override public Result sendDraft(String draftId, MailSendPermit permit) {
            if (permit == null) return Result.fail("没有发送许可");
            Draft d = draftId == null ? null : mDrafts.get(draftId);
            if (d == null) return Result.fail("草稿不存在");
            String bad = permit.use(draftId, d.fingerprint());
            if (bad != null) return Result.fail(bad);
            return Result.fail("邮件发送通道还没接入（等 B 线连接器）");
        }

        @Override public Result deleteDraft(String draftId) {
            mDrafts.remove(draftId);
            return Result.ok(draftId);
        }
    };

    private static volatile MailDraftPort sDraft = STUB_DRAFT;

    public static MailDraftPort mailDraft() {
        return sDraft;
    }

    public static void setMailDraft(MailDraftPort p) {
        sDraft = p == null ? STUB_DRAFT : p;
    }

    public static MailReadPort mailRead() {
        return sRead;
    }

    public static void setMailRead(MailReadPort p) {
        sRead = p == null ? STUB_READ : p;
    }
}
