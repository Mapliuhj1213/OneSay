package com.onesay.assistant.v2.a11ylabel;

import android.content.Context;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.phone.FastScreenDriver;
import com.onesay.assistant.v2.phone.UiTree;
import com.onesay.assistant.v2.util.SafeLog;

import org.json.JSONObject;

/** ★ a11y-labels：快捷指令内部动作 {@code ui.tap} 的宿主侧（后台线程调用）：拿屏幕租约 → 读屏贴标签 → {@link LabelTap#decide} → 点。 */
public final class LabelTapHooks {

    private LabelTapHooks() {
    }

    static final String TAG = "LabelTap";

    public static ExecutionResult run(Context ctx, Capability cap, JSONObject args) {
        Context app = ctx.getApplicationContext();
        ScreenLease.Lease lease = ScreenLease.get().tryAcquire("快捷指令点屏幕", ScreenLease.SHORT_TTL_MS);
        if (lease == null) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, ScreenLease.get().busySay(), "screen lease busy", cap, args);
        try {
            FastScreenDriver drv = ButtonNumbersHooks.driver(app);
            if (drv == null) return ExecutionResult.failed(ErrorCode.NOT_CONFIGURED, "读屏助手不可用，没法点屏幕", "no screen backend", cap, args);
            UiTree u = drv.observe(new UiTree.Ids());
            if (u != null) A11yLabeler.attach(u, null, System.currentTimeMillis());
            LabelTap.Plan p = LabelTap.decide(args, u, ButtonNumbers.DEFAULT_GUARD);
            if (p.userMustTap) return ExecutionResult.failed(ErrorCode.USER_REJECTED, p.error, "user must tap", cap, args);
            if (!p.ok()) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, p.error, "label not found", cap, args);
            SafeLog.i(TAG, "[LABEL] 点 " + LabelRender.logLine(u, p.target) + (p.candidates > 1 ? " 候选 " + p.candidates : "") + (p.migrated != null ? " 旧坐标已命中控件" : ""));
            boolean ok = drv.tap(p.target);
            if (!ok) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "没点上「" + ButtonNumbers.clip(p.target.label) + "」", "tap failed", cap, args);
            return ExecutionResult.success(cap, args, "已点「" + ButtonNumbers.clip(p.target.label) + "」", false, "ui.tap");
        } finally {
            ScreenLease.get().release(lease);
        }
    }
}
