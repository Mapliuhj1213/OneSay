package com.onesay.assistant.v2.conn.mail;

import com.onesay.assistant.v2.conn.ConnResult;

import java.util.List;

/**
 * [conn/mail] 一个邮箱后端（Gmail API / IMAP+SMTP）。上层统一入口是 {@link MailService}（{@link MailConnector} 实现），
 * 不直接调这里；发送方法必须带 {@link SendPermit}（只能由确认卡令牌的消费回执生成）。
 *
 * <p>远端草稿用 (remoteId, rev) 定位：Gmail = (draft id, message id)，IMAP = (本 App 生成的草稿键, 版本号)。
 * rev 变了 = 草稿在别处被改过，发送 / 改写一律拒绝。
 */
public interface MailAccount {

    String GMAIL = "gmail";
    String IMAP = "imap";

    String kind();

    boolean isReady();

    /** 未读重要邮件（Gmail：is:unread is:important；IMAP：未读里的已加星 / 高优先级，没有就给最新未读并说明）。 */
    Listing unreadImportant(int max);

    Listing search(String query, int max);

    Remote createDraft(MailDraft d);

    Remote updateDraft(String remoteId, String rev, MailDraft d);

    /** 发送远端草稿（发前核对 rev 与内容指纹；发后 Gmail 自动删草稿，IMAP 删除或标记）。 */
    ConnResult sendDraft(String remoteId, String rev, SendPermit permit);

    ConnResult deleteDraft(String remoteId, String rev);

    /** 列表结果。 */
    final class Listing {
        public final ConnResult result;
        public final List<MailSummary> items;

        public Listing(ConnResult result, List<MailSummary> items) {
            this.result = result;
            this.items = items;
        }

        public static Listing fail(ConnResult r) {
            return new Listing(r, null);
        }
    }

    /** 草稿写操作结果：成功时带远端定位。 */
    final class Remote {
        public final ConnResult result;
        public final String remoteId;
        public final String rev;

        public Remote(ConnResult result, String remoteId, String rev) {
            this.result = result;
            this.remoteId = remoteId;
            this.rev = rev;
        }

        public static Remote fail(ConnResult r) {
            return new Remote(r, null, null);
        }
    }
}
