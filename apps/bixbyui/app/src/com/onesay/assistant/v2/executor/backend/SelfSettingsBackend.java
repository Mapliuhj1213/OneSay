package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.selfset.SelfSettingsCaps;
import com.onesay.assistant.v2.selfset.SelfSettingsHooks;

/**
 * [settings-diag-1124] OneSay 自身设置（executor="selfset"）：App 内读清单 / 改开关 / 打开所在页，改完回读核对，结果即最终结果。
 * 门禁（敏感项的确认卡令牌）已在 UnifiedExecutor 第 1 步完成；后端自己再挡一次「敏感项没走 set_guarded」。
 */
public final class SelfSettingsBackend implements ExecutorBackend {

    @Override
    public String name() {
        return "selfset";
    }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        return SelfSettingsCaps.isLocal(cap);
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        return BackendOutcome.terminal(SelfSettingsHooks.executeLocal(req.context, req.cap, req.args));
    }
}
