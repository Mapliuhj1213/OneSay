package com.onesay.assistant.v2.llm;

import org.json.JSONArray;
import org.json.JSONObject;

/** 拼 OpenAI 格式的回复 / 流式行（给 Anthropic、Gemini 适配器把对方回复翻回来用）。 */
final class OpenAiShapes {
    private OpenAiShapes() {}

    static JSONObject completion(String model, String text, String reasoning, JSONArray toolCalls, String finish,
                                 int promptTokens, int completionTokens) throws Exception {
        JSONObject msg = new JSONObject();
        msg.put("role", "assistant");
        msg.put("content", text == null || (text.length() == 0 && toolCalls != null && toolCalls.length() > 0) ? JSONObject.NULL : text);
        if (reasoning != null && reasoning.length() > 0) msg.put("reasoning_content", reasoning);
        if (toolCalls != null && toolCalls.length() > 0) msg.put("tool_calls", toolCalls);
        JSONObject c0 = new JSONObject();
        c0.put("index", 0);
        c0.put("message", msg);
        c0.put("finish_reason", finish);
        JSONObject root = new JSONObject();
        root.put("object", "chat.completion");
        root.put("model", model == null ? "" : model);
        root.put("choices", new JSONArray().put(c0));
        if (promptTokens >= 0) {
            JSONObject u = new JSONObject();
            u.put("prompt_tokens", promptTokens);
            u.put("completion_tokens", Math.max(0, completionTokens));
            u.put("total_tokens", promptTokens + Math.max(0, completionTokens));
            root.put("usage", u);
        }
        return root;
    }

    static JSONObject toolCall(String id, String name, String argsJson) throws Exception {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("arguments", argsJson == null || argsJson.length() == 0 ? "{}" : argsJson);
        JSONObject c = new JSONObject();
        c.put("id", id);
        c.put("type", "function");
        c.put("function", fn);
        return c;
    }

    static String deltaLine(JSONObject delta, String finish) {
        try {
            JSONObject c0 = new JSONObject();
            c0.put("index", 0);
            c0.put("delta", delta);
            c0.put("finish_reason", finish == null ? JSONObject.NULL : finish);
            JSONObject root = new JSONObject();
            root.put("object", "chat.completion.chunk");
            root.put("choices", new JSONArray().put(c0));
            return "data: " + root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static String textDelta(String text) {
        try {
            return deltaLine(new JSONObject().put("content", text), null);
        } catch (Exception e) {
            return null;
        }
    }

    static String reasoningDelta(String text) {
        try {
            return deltaLine(new JSONObject().put("reasoning_content", text), null);
        } catch (Exception e) {
            return null;
        }
    }

    static final String DONE = "data: [DONE]";

    /** OpenAI 消息的 content 可能是字符串、null，或分块数组：取出全部文字。 */
    static String textOf(Object content) {
        if (content == null || content == JSONObject.NULL) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray a = (JSONArray) content;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null && "text".equals(p.optString("type"))) {
                    if (b.length() > 0) b.append('\n');
                    b.append(p.optString("text", ""));
                }
            }
            return b.toString();
        }
        return String.valueOf(content);
    }

    /** data:image/png;base64,XXXX → {mime, data}；不是 data URL 返回 null。 */
    static String[] dataUrl(String url) {
        if (url == null || !url.startsWith("data:")) return null;
        int semi = url.indexOf(';'), comma = url.indexOf(',');
        if (semi < 0 || comma < semi || !url.substring(semi, comma).contains("base64")) return null;
        return new String[]{url.substring(5, semi), url.substring(comma + 1)};
    }
}
