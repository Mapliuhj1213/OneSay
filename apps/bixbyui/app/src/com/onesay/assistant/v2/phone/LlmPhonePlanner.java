package com.onesay.assistant.v2.phone;


import com.onesay.assistant.v2.util.SafeLog;
import com.onesay.assistant.v2.net.LlmClient;
import com.onesay.assistant.v2.net.LlmResponse;

/** ★ r2/fast-screen：App 操作代理的模型调用（DeepSeek，与网页跑腿同一个 LlmClient）。 */
public final class LlmPhonePlanner implements PhoneAgent.Planner {

    private static final String TAG = "PhoneAgent";
    private final LlmClient mClient;
    /** 统计：每次模型调用耗时（ms）。 */
    public final java.util.List<Long> callMs = new java.util.ArrayList<Long>();

    public LlmPhonePlanner(LlmClient c) {
        mClient = c;
    }

    public boolean ready() {
        return mClient != null && mClient.hasKey();
    }

    @Override public String next(String system, String user) {
        if (!ready()) return null;
        long t0 = System.currentTimeMillis();
        try {
            LlmClient c = mClient.withReadTimeout(PhonePrompt.READ_TIMEOUT_MS);
            String body = LlmClient.body(PhonePrompt.MODEL, PhonePrompt.TEMPERATURE, PhonePrompt.MAX_TOKENS,
                    LlmClient.messages(system, user), null, false).toString();
            LlmResponse r = c.post(body);
            long ms = System.currentTimeMillis() - t0;
            callMs.add(ms);
            SafeLog.i(TAG, "[PHONE] 模型 " + ms + "ms 提示词 " + user.length() + " 字 ok=" + r.ok());
            return r.ok() ? r.content() : null;
        } catch (Throwable t) {
            SafeLog.w(TAG, "[PHONE] 模型调用异常: " + t);
            return null;
        }
    }
}
