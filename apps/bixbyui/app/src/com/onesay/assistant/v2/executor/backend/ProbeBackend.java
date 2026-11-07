package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.probe.ProbeEngine;

/**
 * 只读探针（executor=probe）：委托 ProbeEngine，输出本身就是给 LLM 的结构化观察，
 * 不做回读校验（UNVERIFIED），直接作为最终结果。
 */
public final class ProbeBackend implements ExecutorBackend {

    @Override
    public String name() {
        return "probe";
    }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        return "probe".equals(cap.executorType);
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        ProbeEngine.ProbeResult pr = ProbeEngine.execute(req.cap.sign, req.args, req.context);
        if (!pr.ok) {
            return BackendOutcome.terminal(ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    pr.error != null ? pr.error : "探针执行失败", pr.error, req.cap, req.args));
        }
        return BackendOutcome.terminal(ExecutionResult.success(req.cap, req.args, pr.output,
                ExecutionResult.Verification.UNVERIFIED, pr.output));
    }
}
