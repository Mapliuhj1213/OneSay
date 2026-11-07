package com.onesay.assistant.v2.conn;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.backend.BackendOutcome;
import com.onesay.assistant.v2.executor.backend.ExecRequest;
import com.onesay.assistant.v2.executor.backend.ExecutorBackend;

/**
 * [conn] 执行后端 executor="conn"：UnifiedExecutor 已经过门禁（需要确认的已校验并消费令牌）之后才会走到这里。
 * 邮件草稿发送另有更严的一道（{@link com.onesay.assistant.v2.conn.mail.MailSendGuard}：必须拿到本次令牌的消费回执）。
 */
public final class ConnBackend implements ExecutorBackend {

    public interface Target {
        /** 认领并执行；不是自己的返回 null。 */
        com.onesay.assistant.v2.executor.ExecutionResult run(ExecRequest req);
    }

    /** 现取注册表与附加目标（后端可能在注册表装配之前就被内核收集，所以不能在构造时固定）。 */
    public interface Provider {
        ConnectorRegistry registry();

        Target[] targets();
    }

    private final Provider mP;

    public ConnBackend(Provider p) {
        mP = p;
    }

    /** 单测 / 固定注册表。 */
    public static ConnBackend fixed(final ConnectorRegistry reg, final Target... extra) {
        return new ConnBackend(new Provider() {
            @Override public ConnectorRegistry registry() { return reg; }
            @Override public Target[] targets() { return extra == null ? new Target[0] : extra; }
        });
    }

    @Override public String name() { return "conn"; }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        return cap != null && ConnCapabilities.EXECUTOR.equals(cap.executorType);
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        for (Target t : mP.targets()) {
            com.onesay.assistant.v2.executor.ExecutionResult r = t.run(req);
            if (r != null) return BackendOutcome.terminal(r);
        }
        ConnectorRegistry reg = mP.registry();
        if (reg == null) {
            return BackendOutcome.terminal(com.onesay.assistant.v2.executor.ExecutionResult.failed(com.onesay.assistant.v2.executor.ErrorCode.NOT_CONFIGURED,
                    "连接器还没就绪", "no registry", req.cap, req.args));
        }
        return BackendOutcome.terminal(reg.executeGated(req.cap, req.args));
    }
}
