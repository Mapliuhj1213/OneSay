package com.onesay.assistant.v2.asr;

/**
 * 语音识别结果值对象。
 *
 * 设计要点：**离线与云端共用同一个结果类型**，这样上层（WakeService / 路由）
 * 不需要关心文本是从哪儿来的，只按 text 走既有链路。
 * source / latencyMs / confidence 仅用于日志、调试与后续策略调优。
 */
public final class AsrResult {

    /** 识别来源 */
    public enum Source {
        /** 设备本地（vosk / sherpa） */
        OFFLINE,
        /** 云端（阿里云一句话识别，将来可扩展别的厂商） */
        CLOUD,
        /** 无结果（未识别 / 未启用 / 失败） */
        NONE
    }

    /** 识别文本；失败或空结果时为 ""，永不为 null —— 调用方可直接判 length() */
    public final String text;
    /** 文本来源 */
    public final Source source;
    /** 本次识别耗时（毫秒），含网络往返；离线为纯推断耗时 */
    public final long latencyMs;
    /** 是否拿到可用文本 */
    public final boolean ok;
    /** 失败原因（成功时为 ""），仅用于日志，不面向用户 */
    public final String error;

    private AsrResult(String text, Source source, long latencyMs, boolean ok, String error) {
        this.text = (text == null) ? "" : text;
        this.source = source;
        this.latencyMs = latencyMs;
        this.ok = ok;
        this.error = (error == null) ? "" : error;
    }

    /** 成功结果 */
    public static AsrResult ok(String text, Source source, long latencyMs) {
        return new AsrResult(text, source, latencyMs, true, "");
    }

    /** 失败结果（text 为空） */
    public static AsrResult fail(String error, Source source, long latencyMs) {
        return new AsrResult("", source, latencyMs, false, error);
    }

    /** 空结果（未启用 / 无音频） */
    public static AsrResult none(String reason) {
        return new AsrResult("", Source.NONE, 0L, false, reason);
    }

    @Override
    public String toString() {
        return "AsrResult{source=" + source + ", ok=" + ok + ", latency=" + latencyMs + "ms"
                + ", text=\"" + text + "\"" + (ok ? "" : ", error=" + error) + "}";
    }
}
