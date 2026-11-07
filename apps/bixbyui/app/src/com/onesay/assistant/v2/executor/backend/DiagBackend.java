package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.diag.DiagCapabilities;
import com.onesay.assistant.v2.diag.DiagHooks;

/**
 * [diag] 设置诊断的修复 / 撤销（executor="diag"）：先读旧值、再写、再回读，结果即最终结果。
 * 门禁（黄卡令牌）已在 UnifiedExecutor 第 1 步完成；这里只接受知识表内的修复 id（白名单守卫在 DiagEngine）。
 */
public final class DiagBackend implements ExecutorBackend {

    @Override
    public String name() {
        return "diag";
    }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        return DiagCapabilities.isLocal(cap);
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        return BackendOutcome.terminal(DiagHooks.executeLocal(req.context, req.shell, req.cap, req.args));
    }
}
