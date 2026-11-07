package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;

/**
 * app.launch：App 进程内 startActivity（MAIN/LAUNCHER）。
 * 只接 executor=intent 且 sign=app.launch 且已解析出组件的调用（与旧版判据逐字一致）；
 * 其它 intent 类能力的模板是 {@code am start ...}，仍由 {@link ShellBackend} 经 root 执行。
 */
public final class IntentBackend implements ExecutorBackend {

    @Override
    public String name() {
        return "intent";
    }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        return "intent".equals(cap.executorType) && "app.launch".equals(cap.sign) && req.component != null;
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        boolean launched = req.apps != null && req.apps.launchComponent(req.component);
        if (!launched) {
            return BackendOutcome.terminal(ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    "拉起应用失败", "am start 唤起失败", req.cap, req.args));
        }
        return BackendOutcome.output("Launched " + req.component);
    }
}
