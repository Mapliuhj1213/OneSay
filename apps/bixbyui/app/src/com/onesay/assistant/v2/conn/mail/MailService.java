package com.onesay.assistant.v2.conn.mail;

import com.onesay.assistant.v2.conn.ConnResult;

/**
 * [conn/mail] 统一邮件接口（IMAP 与 Gmail 两路共用）。B 线对外（AgentLoop 工具、A 线回调 / 确认流程、P3 监控）只暴露这一个面。
 *
 * <h3>发信只有四个接口</h3>
 * <pre>
 *   createDraft(draft, account)          → data.draft_id（本机草稿号 d-xxxx，只认本 App 建的草稿）
 *   updateDraft(draftId, draft)          → 重新起草（内容指纹变，之前的确认作废）
 *   sendDraft(draftId, SendPermit)       → 唯一的发送入口；SendPermit 只能由 {@link MailSendGuard} 从「确认卡令牌的消费回执」生成
 *   deleteDraft(draftId)
 * </pre>
 * 没有任何不带确认的发送方法（没有 send(to, subject, body)）。
 *
 * <p>account：{@code "gmail"} / {@code "imap"} / {@code null}（自动：Gmail 可用且连得上就用 Gmail，否则 IMAP）。
 * 读操作：data 是 JSON 数组（{@link MailSummary#json}）；text 是给模型的编号列表。
 */
public interface MailService {

    ConnResult unreadImportant(int max, String account);

    ConnResult search(String query, int max, String account);

    ConnResult createDraft(MailDraft draft, String account);

    ConnResult updateDraft(String draftId, MailDraft draft);

    ConnResult sendDraft(String draftId, SendPermit confirm);

    ConnResult deleteDraft(String draftId);
}
