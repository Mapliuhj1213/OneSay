package com.onesay.assistant.v2.vision;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import com.onesay.assistant.v2.asr.AliyunAsrConfig;

import java.util.List;

/**
 * ★ 11-04 相机对话：一轮「最新一帧 + 这句话 + 前几轮文字」→ 百炼 qwen3-vl（与识屏同一模型 / 同一 key / 同一 HttpClient，
 * 可用 bixby_asr_cfg/vision_model 覆盖）。阻塞调用，在工作线程用。
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

    public static Result ask(Context ctx, List<String[]> history, byte[] jpeg, String question) {
        long t0 = SystemClock.elapsedRealtime();
        String key;
        try {
            key = new AliyunAsrConfig(ctx).getDashScopeKey();
        } catch (Throwable t) {
            key = null;
        }
        if (key == null || key.trim().length() == 0) {
            return new Result(false, "还没有配置百炼（DashScope）Key，相机对话用不了");
        }
        String model = ScreenVisionEngine.effectiveModel(ctx);
        byte[] body;
        try {
            String b64 = jpeg == null ? null : Base64.encodeToString(jpeg, Base64.NO_WRAP);
            body = LiveVisionPrompt.payload(model, history, b64, question).toString().getBytes("UTF-8");
            b64 = null;
        } catch (Throwable t) {
            return new Result(false, "请求组装失败");
        }
        com.onesay.assistant.v2.net.HttpClient.Response resp = com.onesay.assistant.v2.net.HttpClient.execute(
                ScreenVisionEngine.visionRequest(com.onesay.assistant.v2.net.LlmEndpoints.dashscopeChatUrl(), key.trim(), body));
        java.util.Arrays.fill(body, (byte) 0);
        long ms = SystemClock.elapsedRealtime() - t0;
        if (resp.error != null) {
            Log.w(TAG, "[LIVE-VISION] 网络异常 " + resp.error.getClass().getSimpleName() + " " + ms + "ms");
            return new Result(false, "网络不太好，没连上视觉模型");
        }
        if (resp.code != 200) {
            String why = ScreenVisionEngine.cloudErrorSummary(resp.body);
            Log.w(TAG, "[LIVE-VISION] HTTP " + resp.code + " model=" + model + " " + ms + "ms " + why);
            return new Result(false, "视觉模型出错了（HTTP " + resp.code + "）");
        }
        String a = LiveVisionPrompt.answerOf(resp.body);
        Log.i(TAG, "[LIVE-VISION] HTTP 200 model=" + model + " 帧=" + (jpeg == null ? 0 : jpeg.length) + "B 历史="
                + (history == null ? 0 : history.size()) + "轮 回答=" + (a == null ? 0 : a.length()) + "字 " + ms + "ms");
        if (a == null) return new Result(false, "视觉模型没给出回答");
        return new Result(true, LiveVisionPrompt.spoken(a));
    }
}
