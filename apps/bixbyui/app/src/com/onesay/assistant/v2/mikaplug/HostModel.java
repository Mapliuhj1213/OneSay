package com.onesay.assistant.v2.mikaplug;

import android.content.Context;

import com.onesay.assistant.plug.api.ModelPort;
import com.onesay.assistant.v2.net.LlmClient;
import com.onesay.assistant.v2.net.LlmResponse;

/**
 * ★ mika-plug：插件核心的 {@link ModelPort} 宿主实现（与网页跑腿同一个 LlmClient / DeepSeek）。没有 Key 返回 null，
 * 插件按「模型不可用」回落。阻塞调用，调用方保证不在主线程。
 */
public final class HostModel implements ModelPort {
    public static final String MODEL = com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT;
    private final LlmClient mClient;

    private HostModel(LlmClient c) {
        mClient = c;
    }

    public static ModelPort forContext(Context ctx) {
        if (ctx == null) return null;
        try {
            LlmClient c = com.onesay.assistant.v2.errand.ErrandHooks.llmFor(ctx.getApplicationContext());
            return c != null && c.hasKey() ? new HostModel(c) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public String complete(String system, String user, int maxTokens, int timeoutMs) {
        try {
            LlmClient c = timeoutMs > 0 ? mClient.withReadTimeout(timeoutMs) : mClient;
            LlmResponse r = c.post(LlmClient.body(MODEL, 0.0, Math.max(8, maxTokens), LlmClient.messages(system, user), null, false).toString());
            return r.ok() ? r.content() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
