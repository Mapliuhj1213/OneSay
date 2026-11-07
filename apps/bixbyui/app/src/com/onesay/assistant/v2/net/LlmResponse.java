package com.onesay.assistant.v2.net;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OpenAI 兼容端点（DeepSeek / DashScope 兼容模式）的**统一响应解析**（P1-1）。
 *
 * <p>此前 router 包内有 4 处各写一份 {@code choices[0].message} 解析：
 * {@code L2LLMCompiler.callLlm}、{@code L2LLMCompiler.callWithMessages}、
 * {@code SseStream.parseDelta}，外加各处对 content 为 null/数组的不同处理。
 * 现在全部收敛到这里，行为差异（DashScope 的 content 数组、首帧 content=null）只处理一次。
 *
 * <p>纯 Java（只依赖 org.json），可在 JVM 单测里直接测。
 */
public final class LlmResponse {

    /** HTTP 状态码；网络异常时为 -1。 */
    public final int httpCode;
    /** 原始响应体（失败时可能是错误体或 null）。 */
    public final String rawBody;
    /** {@code choices[0].message}；解析失败为 null。 */
    public final JSONObject message;
    /** 失败原因（成功时为 null）。 */
    public final String error;

    private LlmResponse(int httpCode, String rawBody, JSONObject message, String error) {
        this.httpCode = httpCode;
        this.rawBody = rawBody;
        this.message = message;
        this.error = error;
    }

    /** 是否拿到了一条可用的 message。 */
    public boolean ok() {
        return message != null && error == null;
    }

    /** message.content 的文本（兼容字符串 / DashScope 的 [{"text":..}] 数组）；没有则为 null。 */
    public String content() {
        return contentOf(message);
    }

    /** message.tool_calls；没有则为 null。 */
    public JSONArray toolCalls() {
        return message == null ? null : message.optJSONArray("tool_calls");
    }

    public static LlmResponse failure(int httpCode, String body, String error) {
        return new LlmResponse(httpCode, body, null, error == null ? "unknown" : error);
    }

    /**
     * 解析非流式响应体。
     *
     * @param httpCode 状态码（非 200 一律视为失败，但仍保留原始体便于打日志）
     */
    public static LlmResponse parse(int httpCode, String body) {
        if (httpCode != 200) {
            return failure(httpCode, body, "HTTP " + httpCode);
        }
        if (body == null || body.trim().length() == 0) {
            return failure(httpCode, body, "空响应体");
        }
        try {
            JSONObject root = new JSONObject(body);
            JSONObject msg = firstMessage(root);
            if (msg == null) {
                // DashScope 原生格式：output.choices[0].message
                JSONObject output = root.optJSONObject("output");
                if (output != null) msg = firstMessage(output);
            }
            if (msg == null) return failure(httpCode, body, "响应缺少 choices[0].message");
            return new LlmResponse(httpCode, body, msg, null);
        } catch (Throwable t) {
            return failure(httpCode, body, "响应不是合法 JSON: " + t.getMessage());
        }
    }

    private static JSONObject firstMessage(JSONObject root) {
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return null;
        JSONObject c0 = choices.optJSONObject(0);
        if (c0 == null) return null;
        return c0.optJSONObject("message");
    }

    /**
     * 取 message.content 文本。
     *
     * <p>★ 不能对 content 用 optString：org.json 会把 JSON null 变成字符串 "null"。
     * DashScope 兼容/原生模式里 content 也可能是数组 {@code [{"text":"…"}]}。
     */
    public static String contentOf(JSONObject msg) {
        if (msg == null) return null;
        Object c = msg.opt("content");
        if (c instanceof String) return (String) c;
        if (c instanceof JSONArray) {
            JSONArray arr = (JSONArray) c;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object it = arr.opt(i);
                if (it instanceof JSONObject) {
                    Object t = ((JSONObject) it).opt("text");
                    if (t instanceof String) sb.append((String) t);
                } else if (it instanceof String) {
                    sb.append((String) it);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    // ---------------- SSE（流式） ----------------

    /**
     * 从一行 SSE 文本里抽出增量（原 {@code SseStream.parseDelta}，逐字迁移）。
     *
     * <p>兼容三种行：{@code data: {...}}、裸 {@code {...}}、以及必须忽略的
     * {@code :heartbeat} 注释行与 {@code data: [DONE]} 结束行。
     *
     * @return 增量文本；该行不含内容时返回 <b>null</b>（不是空串，调用方靠它跳过）
     */
    public static String deltaOf(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.length() == 0) return null;

        if (s.startsWith("data:")) {
            s = s.substring(5).trim();
        } else if (s.startsWith(":")) {
            return null;                 // SSE 注释/心跳
        } else if (!s.startsWith("{")) {
            return null;                 // event: / id: / retry: 之类
        }
        if (s.length() == 0 || "[DONE]".equals(s)) return null;

        try {
            JSONObject obj = new JSONObject(s);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject c0 = choices.optJSONObject(0);
            if (c0 == null) return null;

            JSONObject delta = c0.optJSONObject("delta");
            if (delta == null) delta = c0.optJSONObject("message"); // 少数端点把首帧包在 message 里
            if (delta == null) return null;

            Object content = delta.opt("content");
            if (!(content instanceof String)) return null;          // 首帧 content=null
            return (String) content;
        } catch (Throwable ignore) {
            return null;                                            // 半截 JSON：跳过，不中断整条流
        }
    }

    /** 是否是流结束标记行。 */
    public static boolean isDoneLine(String line) {
        if (line == null) return false;
        String s = line.trim();
        if (s.startsWith("data:")) s = s.substring(5).trim();
        return "[DONE]".equals(s);
    }
}
