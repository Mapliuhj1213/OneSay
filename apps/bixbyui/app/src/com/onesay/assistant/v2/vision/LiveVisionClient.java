package com.onesay.assistant.v2.vision;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;


import java.util.List;

/**
 * ★ 11-04 相机对话：一轮「最新一帧 + 这句话 + 前几轮文字」→ ★ fix/chat-vision-1124 起交给主模型原生视觉（与识屏同一条路）。
 * 阻塞调用，在工作线程用。
 *
 * <p>隐私：帧只以 base64 存在于请求体内存里；日志只记字节数 / 字数 / 耗时 / HTTP 码，不记问题原文、回答原文、图片和 key。
 */
public final class LiveVisionClient {

    private static final String TAG = "LiveVision";

    /** 结果：ok + 文本（失败时是给用户看的原因）。 */
    public static final class Result {
        public final boolean ok;
        public final String text;

        Result(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }
    }

    private LiveVisionClient() { }

    /**
     * ★ fix/chat-vision-1124：相机画面也是图片 → 主模型原生视觉（主模型地址 + 主模型 Key，见 {@link VisionRoute}），
     * 不再用另配的看图模型 Key（没配就整个用不了）。
     */
    public static Result ask(Context ctx, List<String[]> history, byte[] jpeg, String question) {
        long t0 = SystemClock.elapsedRealtime();
        final String key = mainKey(ctx);
        if (!VisionRoute.usable(VisionRoute.pathFor(VisionRoute.Purpose.CAMERA_FRAME), key, null)) {
            return new Result(false, NO_KEY);
        }
        String model = com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT;   // 发送前换成主模型，不能看图的换 deepseek-flash
        String body;
        try {
            String b64 = jpeg == null ? null : Base64.encodeToString(jpeg, Base64.NO_WRAP);
            body = LiveVisionPrompt.payload(model, history, b64, question).toString();
            b64 = null;
        } catch (Throwable t) {
            return new Result(false, "请求组装失败");
        }
        try {
            com.onesay.assistant.v2.net.LlmEndpoints.load(ctx);
        } catch (Throwable ignored) {
        }
        com.onesay.assistant.v2.net.LlmResponse r = new com.onesay.assistant.v2.net.LlmClient(com.onesay.assistant.v2.net.LlmClient.DEEPSEEK_URL,
                new com.onesay.assistant.v2.net.LlmClient.KeyProvider() {
                    @Override public String apiKey() { return key; }
                }, ScreenVisionEngine.NATIVE_CONNECT_MS, ScreenVisionEngine.NATIVE_FIRST_BYTE_MS).withTag("live-vision").post(body);
        body = null;
        long ms = SystemClock.elapsedRealtime() - t0;
        if (r.httpCode < 0) {
            Log.w(TAG, "[LIVE-VISION] 网络异常 " + ms + "ms");
            return new Result(false, "网络不太好，没连上看图的模型");
        }
        if (!r.ok()) {
            String why = ScreenVisionEngine.cloudErrorSummary(r.rawBody);
            Log.w(TAG, "[LIVE-VISION] HTTP " + r.httpCode + " " + ms + "ms " + why);
            return new Result(false, ScreenVisionEngine.userVisionError(r.httpCode));
        }
        String a = r.content();
        Log.i(TAG, "[LIVE-VISION] HTTP 200 主模型看图 帧=" + (jpeg == null ? 0 : jpeg.length) + "B 历史="
                + (history == null ? 0 : history.size()) + "轮 回答=" + (a == null ? 0 : a.length()) + "字 " + ms + "ms");
        if (a == null || a.trim().length() == 0) return new Result(false, "看图的模型没给出回答");
        return new Result(true, LiveVisionPrompt.spoken(a));
    }

    /** 没填主模型 Key 时的话。 */
    public static final String NO_KEY = "还没填主模型的 Key，相机对话看不了画面";

    private static String mainKey(Context ctx) {
        try {
            String k = com.onesay.assistant.v2.router.L2LLMCompiler.mainApiKey(ctx);
            return k == null ? "" : k;
        } catch (Throwable t) {
            return "";
        }
    }
}
