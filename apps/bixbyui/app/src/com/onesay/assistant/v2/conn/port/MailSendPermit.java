package com.onesay.assistant.v2.conn.port;

/**
 * 发送许可（lane-a 侧，语义与 B 线 {@code conn.mail.SendPermit} 一一对应）：
 * 「确认卡令牌已被消费」的凭证。<b>构造器包私有</b>，只有 {@link MailConfirmTokens#consume} 能造——
 * 它要求用户在确认卡上点了「发送」，且卡片显示的那一版草稿（草稿号 + 内容指纹）和现在的草稿完全一致。
 *
 * <p>一张许可只能用一次（{@link #use}），且只对它绑定的草稿号与内容指纹有效。
 */
public final class MailSendPermit {

    final String draftId;
    final String fingerprint;
    final String tokenId;
    private boolean mUsed;

    MailSendPermit(String draftId, String fingerprint, String tokenId) {
        this.draftId = draftId;
        this.fingerprint = fingerprint;
        this.tokenId = tokenId;
    }

    /** 核对并作废（实现方在真正发送前调用）。@return null = 可以发；否则原因。 */
    public synchronized String use(String draftId, String fingerprint) {
        if (mUsed) return "这张发送许可已经用过";
        if (this.draftId == null || !this.draftId.equals(draftId)) return "许可不是给这封草稿的";
        if (this.fingerprint == null || !this.fingerprint.equals(fingerprint)) return "草稿在确认之后被改过，需要重新确认";
        mUsed = true;
        return null;
    }

    public String draftId() {
        return draftId;
    }

    public String fingerprint() {
        return fingerprint;
    }

    @Override public String toString() {
        return "MailSendPermit{" + draftId + "}";
    }
}
