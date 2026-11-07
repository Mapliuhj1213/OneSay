package com.onesay.assistant.v2.conn.mail;

/**
 * [conn/mail] 发送许可 = 「确认卡令牌已被消费」的凭证。<b>构造器包私有</b>，只有 {@link MailSendGuard#fromReceipt} 能造：
 * 它要求当前线程刚刚由 ExecutionGate 消费了一张<b>用户在确认卡上批准</b>、签名为 {@code conn.mail.send_draft}、
 * 参数（draft_id + content_hash）完全一致的令牌。
 *
 * <p>一张许可只能用一次（{@link #use()}），且只对它绑定的草稿号与内容指纹有效。
 */
public final class SendPermit {

    final String draftId;
    final String contentHash;
    final String tokenId;
    private boolean mUsed;

    SendPermit(String draftId, String contentHash, String tokenId) {
        this.draftId = draftId;
        this.contentHash = contentHash;
        this.tokenId = tokenId;
    }

    /** 核对并作废。@return null = 可以发；否则原因。 */
    synchronized String use(String draftId, String contentHash) {
        if (mUsed) return "这张发送许可已经用过";
        if (this.draftId == null || !this.draftId.equals(draftId)) return "许可不是给这封草稿的";
        if (this.contentHash == null || !this.contentHash.equals(contentHash)) return "草稿在确认之后被改过，需要重新确认";
        mUsed = true;
        return null;
    }

    @Override
    public String toString() {
        return "SendPermit{" + draftId + "}";
    }
}
