package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.MailBridge;

/** 邮件域（IMAP）：executor=custom 且 sign 属于 MailBridge。结果即最终结果。 */
public final class MailBackend implements ExecutorBackend {

    @Override
    public String name() {
        return "mail";
    }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        return "custom".equals(cap.executorType) && MailBridge.isMailSign(cap.sign);
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        return BackendOutcome.terminal(MailBridge.execute(req.cap, req.args, req.context));
    }
}
