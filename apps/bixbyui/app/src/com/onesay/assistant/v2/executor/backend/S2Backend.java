package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.s2.S2Capabilities;
import com.onesay.assistant.v2.s2.S2Hooks;

/**
 * [s2] 记忆库 / 习惯默认值 / 记账（executor="s2"）：App 内读写本机 SQLite，结果即最终结果（不做 shell 回读）。
 * 门禁（含「忘掉所有记录」的红卡令牌）已在 UnifiedExecutor 第 1 步完成。
 */
public final class S2Backend implements ExecutorBackend {

    @Override
    public String name() {
        return "s2";
    }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        return S2Capabilities.isLocal(cap);
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        return BackendOutcome.terminal(S2Hooks.executeLocal(req.context, req.cap, req.args));
    }
}
