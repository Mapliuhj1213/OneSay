package com.onesay.assistant.v2.phone;


import com.onesay.assistant.v2.util.SafeLog;
import com.onesay.assistant.v2.net.LlmClient;
import com.onesay.assistant.v2.net.LlmResponse;

/** ★ r2/fast-screen：App 操作代理的模型调用（DeepSeek，与网页跑腿同一个 LlmClient）。 */
public final class LlmPhonePlanner implements PhoneAgent.VisionPlanner {

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

    /**
     * [exec-ground-1124] 截图模式：截图本身跟着这一轮发给主模型（OpenAI 兼容的图片块 data:image/jpeg;base64）。
     * 模型名经 LlmModels.prepare：带图的请求自动换成能看图的型号（主模型规矩见 VisionRoute）。
     */
    @Override public String nextWithImage(String system, String user, byte[] jpeg) {
        if (jpeg == null) return next(system, user);
        if (!ready()) return null;
        long t0 = System.currentTimeMillis();
        try {
            LlmClient c = mClient.withReadTimeout(PhonePrompt.READ_TIMEOUT_MS);
            String body = LlmClient.body(PhonePrompt.MODEL, PhonePrompt.TEMPERATURE, PhonePrompt.MAX_TOKENS,
                    imageMessages(system, user, jpeg), null, false).toString();
            LlmResponse r = c.post(body);
            long ms = System.currentTimeMillis() - t0;
            callMs.add(ms);
            SafeLog.i(TAG, "[PHONE] 模型（带截图 " + (jpeg.length / 1024) + "KB）" + ms + "ms 提示词 " + user.length() + " 字 ok=" + r.ok());
            return r.ok() ? r.content() : null;
        } catch (Throwable t) {
            SafeLog.w(TAG, "[PHONE] 模型调用异常: " + t);
            return null;
        }
    }

    /** system + user（文字块 + 图片块）。纯 Java，JVM 可测。 */
    static org.json.JSONArray imageMessages(String system, String user, byte[] jpeg) throws org.json.JSONException {
        org.json.JSONArray msgs = new org.json.JSONArray();
        msgs.put(new org.json.JSONObject().put("role", "system").put("content", system));
        org.json.JSONArray parts = new org.json.JSONArray();
        parts.put(new org.json.JSONObject().put("type", "text").put("text", user));
        String b64 = b64(jpeg);
        parts.put(new org.json.JSONObject().put("type", "image_url")
                .put("image_url", new org.json.JSONObject().put("url", "data:image/jpeg;base64," + b64)));
        msgs.put(new org.json.JSONObject().put("role", "user").put("content", parts));
        return msgs;
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** 标准 Base64（不换行）。自己写一份：java.util.Base64 要 API 26，Android 7 没有；android.util.Base64 在 JVM 单测里是桩。 */
    static String b64(byte[] d) {
        StringBuilder b = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int n = (d[i] & 0xff) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xff : 0);
            b.append(B64[n >> 18 & 63]).append(B64[n >> 12 & 63]);
            b.append(i + 1 < d.length ? B64[n >> 6 & 63] : '=');
            b.append(i + 2 < d.length ? B64[n & 63] : '=');
        }
        return b.toString();
    }
}
