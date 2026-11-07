package com.onesay.assistant.v2.conn.mail;

import java.util.List;

/**
 * [conn/mail] IMAP / SMTP 的最小操作面（真机 = {@link JavaMailImapOps}，单测用假实现）。抛异常 = 失败，由 {@link ImapAccount} 分类。
 *
 * <p>草稿定位：本 App 写进草稿箱的每封草稿都带两个头 {@code X-OneSay-Draft: <键>} / {@code X-OneSay-Rev: <版本>}。
 * IMAP 里的邮件不可修改，「改草稿」= APPEND 新版本 + 删旧版本。
 */
public interface ImapOps {

    String H_KEY = "X-OneSay-Draft";
    String H_REV = "X-OneSay-Rev";

    /** 收件箱最新的未读邮件（新的在前，最多 scanMax 封；important 已按加星 / X-Priority / Importance 头算好）。 */
    List<MailSummary> unread(int scanMax) throws Exception;

    List<MailSummary> search(String query, int max) throws Exception;

    /** APPEND 到草稿箱（带 \Draft 标记和上面两个头）。 */
    void appendDraft(MailDraft d, String key, int rev) throws Exception;

    /** 读回草稿；找不到返回 null。 */
    MailDraft readDraft(String key, int rev) throws Exception;

    /** 经 SMTP 发出（发件人 = 配置的邮箱账号；不带 X-OneSay-* 头）。 */
    void smtpSend(MailDraft d) throws Exception;

    /** 删除草稿（\Deleted + expunge）。@return 找到并删除了 */
    boolean deleteDraft(String key, int rev) throws Exception;

    /** 删不掉时退一步：给草稿打上已读 + 关键字 BixbySent，避免被误当成没发的草稿。@return 成功 */
    boolean markDraftSent(String key, int rev) throws Exception;
}
