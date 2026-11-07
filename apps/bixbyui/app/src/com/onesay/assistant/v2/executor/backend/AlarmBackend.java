package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.AlarmBridge;

/** 闹钟（executor=alarmbridge）：OneSay 时钟接口优先，没装时交给系统 AlarmClock；结果即最终结果。 */
public final class AlarmBackend implements ExecutorBackend {

    @Override
    public String name() {
        return AlarmBridge.EXECUTOR;
    }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        return AlarmBridge.EXECUTOR.equals(cap.executorType);
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        return BackendOutcome.terminal(AlarmBridge.execute(req.cap, req.args, req.context));
    }
}
