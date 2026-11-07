package com.onesay.assistant.v2.conn.port;

/**
 * lane-a ↔ B 线：对外发邮件的草稿接口（2026-10-23 lane-a 定义）。A+B 合并后由 {@link LaneBMailPorts#ensure} 在启动时注册
 * {@link LaneBMailDraftAdapter}（接 B 线 MailService）；注册前 / 单测里是 {@link Ports#STUB_DRAFT} 桩（发送一律失败）。
 *
 * <h3>用户的最终规则（只发邮件，不做国产 IM）</h3>
 * <ol>
 *   <li>第一步永远是大模型按意图起草（{@code callback.OutboundMail}），不照抄用户原话；</li>
 *   <li>草稿先存好（Gmail 草稿 / IMAP Drafts 文件夹）= {@link #createDraft}，再弹确认卡（收件人 / 主题 / 摘要，发送 / 修改 / 取消）；</li>
 *   <li>「修改」= 重新起草并<b>覆盖原草稿</b> = {@link #updateDraft}（指纹变，旧确认作废）；</li>
 *   <li>只有确认卡令牌被消费换来的 {@link MailSendPermit} 才能 {@link #sendDraft}；定时任务最多只能存草稿，没有任何不经确认的发送路径。</li>
 * </ol>
 *
 * <h3>与 B 线 {@code conn.mail.MailService} 一一对应</h3>
 * <pre>
 *   A: createDraft(Draft)                    ↔ B: createDraft(MailDraft, account=null) → data.draft_id
 *   A: updateDraft(draftId, Draft)           ↔ B: updateDraft(draftId, MailDraft)
 *   A: sendDraft(draftId, MailSendPermit)    ↔ B: sendDraft(draftId, SendPermit)
 *   A: deleteDraft(draftId)                  ↔ B: deleteDraft(draftId)
 *   A: Draft.fingerprint()                   ≡ B: MailDraft(to, "", subject, body).contentHash()（直接调用 B 的实现，cc 为空）
 * </pre>
 * 适配器见 {@link LaneBMailDraftAdapter}。
 */
public interface MailDraftPort {

    /** 一封草稿的内容（收件人地址已经核实过；A 线不做抄送）。 */
    final class Draft {
        public final String to;
        public final String subject;
        public final String body;

        public Draft(String to, String subject, String body) {
            this.to = to == null ? "" : to;
            this.subject = subject == null ? "" : subject;
            this.body = body == null ? "" : body;
        }

        /**
         * 内容指纹：任何一个字段变了指纹就变。<b>直接用 B 线 {@link com.onesay.assistant.v2.conn.mail.MailDraft#contentHash()}</b>（cc 固定为空），
         * 包括它对收件人 / 主题换行 / 正文 CRLF 的规范化——否则 B 台账里的指纹（规范化后）与这里算的不一致，
         * {@link MailSendPermit#use} 会把正常确认误判成「确认后被改过」而永远发不出去。
         */
        public String fingerprint() {
            return new com.onesay.assistant.v2.conn.mail.MailDraft(to, "", subject, body).contentHash();
        }
    }

    /** 调用结果。 */
    final class Result {
        public final boolean ok;
        /** createDraft 成功时是草稿 id；失败时是原因。 */
        public final String value;

        public Result(boolean ok, String value) {
            this.ok = ok;
            this.value = value == null ? "" : value;
        }

        public static Result ok(String v) {
            return new Result(true, v);
        }

        public static Result fail(String why) {
            return new Result(false, why);
        }
    }

    boolean available();

    /** 存草稿（Gmail drafts.create / IMAP APPEND 到 Drafts）。 */
    Result createDraft(Draft d);

    /** 覆盖已有草稿（「修改」重新起草后）。 */
    Result updateDraft(String draftId, Draft d);

    /**
     * 发出草稿——唯一的发送方法，没有 send(to, subject, body)。
     * 实现方必须先 {@code permit.use(draftId, 当前草稿指纹)}，返回非 null 就拒绝、不发。permit 为 null 一律拒绝。
     */
    Result sendDraft(String draftId, MailSendPermit permit);

    /** 取消时删除草稿（可选：实现方也可以保留在草稿箱）。 */
    Result deleteDraft(String draftId);
}
