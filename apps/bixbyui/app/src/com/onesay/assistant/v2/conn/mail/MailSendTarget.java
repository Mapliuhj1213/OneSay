package com.onesay.assistant.v2.conn.mail;

import com.onesay.assistant.v2.conn.ConnBackend;
import com.onesay.assistant.v2.conn.ConnCapabilities;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.backend.ExecRequest;

/**
 * [conn/mail] 执行后端里认领 {@code conn.mail.send_draft} 的目标：UnifiedExecutor 已消费令牌 → 这里向 {@link MailSendGuard}
 * 换 {@link SendPermit}（拿不到就拒绝）→ {@link MailService#sendDraft}。这是整个 App 里唯一能把邮件发出去的路径。
 */
public final class MailSendTarget implements ConnBackend.Target {

    public interface Provider {
        MailConnector mail();
    }

    private final Provider mP;

    public MailSendTarget(Provider p) {
        mP = p;
    }

    @Override
    public ExecutionResult run(ExecRequest req) {
        if (req == null || req.cap == null || !MailSendGuard.SIGN.equals(req.cap.sign)) return null;
        StringBuilder why = new StringBuilder();
        SendPermit permit = MailSendGuard.fromReceipt(req.cap, req.args, why);
        if (permit == null) {
            return ConnCapabilities.toResult(ConnResult.fail(ConnResult.Code.DENIED, why.toString()), req.cap, req.args);
        }
        MailConnector m = mP == null ? null : mP.mail();
        if (m == null) return ConnCapabilities.toResult(ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "邮件连接器没就绪"), req.cap, req.args);
        return ConnCapabilities.toResult(m.sendDraft(req.args.optString("draft_id", ""), permit), req.cap, req.args);
    }
}
