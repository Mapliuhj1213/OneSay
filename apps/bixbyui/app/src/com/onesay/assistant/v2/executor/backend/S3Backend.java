package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.s3.S3Capabilities;
import com.onesay.assistant.v2.s3.S3Hooks;

/**
 * [s3] 定时任务 / 待办（executor="s3"）：App 内读写 s3_tasks.db 并重排闹钟，结果即最终结果。
 * 门禁（含「含发送/支付/删除的定时任务」的创建确认）已在 UnifiedExecutor 第 1 步完成。
 */
public final class S3Backend implements ExecutorBackend {

    @Override
    public String name() {
        return "s3";
    }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        return S3Capabilities.isLocal(cap);
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        return BackendOutcome.terminal(S3Hooks.executeLocal(req.context, req.cap, req.args));
    }
}
