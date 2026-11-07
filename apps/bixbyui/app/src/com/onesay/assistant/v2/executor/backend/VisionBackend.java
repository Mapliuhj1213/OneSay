package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.vision.ScreenVisionEngine;

/**
 * 识屏（executor=custom，sign=vision.screen.understand）：同步截屏 + 多模态识别。
 * 识屏是"没有专用接口时唯一能读到第三方应用内部文字"的手段，循环里必须真跑。
 */
public final class VisionBackend implements ExecutorBackend {

    public static final String SIGN = "vision.screen.understand";

    @Override
    public String name() {
        return "vision";
    }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        return "custom".equals(cap.executorType) && SIGN.equals(cap.sign);
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        String ask = req.args != null ? req.args.optString("content", null) : null;
        ScreenVisionEngine.VisionResult vr = ScreenVisionEngine.getInstance(req.context)
                .recognizeScreenSync(ask, ScreenVisionEngine.SYNC_TIMEOUT_MS);
        if (vr == null || !vr.ok) {
            return BackendOutcome.terminal(ExecutionResult.failed(ErrorCode.EXEC_FAILED,
                    "识屏失败，没能读到屏幕内容", vr == null ? "识屏无返回" : vr.text, req.cap, req.args));
        }
        // 识屏结果是"看到的文字"，既是给用户的答复也是给模型的观察值（detail）
        return BackendOutcome.terminal(ExecutionResult.success(req.cap, req.args, vr.text,
                ExecutionResult.Verification.UNVERIFIED, vr.text));
    }
}
