/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/model/OpenAiCompatModel.kt request()/headers) to Java 7 +
 * org.json. The app already speaks OpenAI format, so the adapter only fixes endpoint, headers and model name;
 * reasoning replay and cache marks are not carried over. Upstream: 8d61573.
 */
package com.onesay.assistant.v2.llm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** OpenAI Chat Completions 兼容端点（DeepSeek、百炼兼容模式、Kimi、智谱、硅基流动、OpenRouter、自建…）。 */
public final class OpenAiCompatAdapter implements ProviderAdapter {

    @Override public ProviderKind kind() {
        return ProviderKind.OPENAI_COMPAT;
    }

    @Override public WireRequest chat(String base, String model, JSONObject in, boolean stream) throws Exception {
        JSONObject b = new JSONObject(in.toString());
        if (model != null && model.length() > 0) b.put("model", model);
        // DeepSeek V4 系的「thinking: disabled」是它家私有字段；换成别家模型时去掉，免得严格的兼容端点报 400
        if (b.has("thinking") && !isDeepseekThinkingModel(b.optString("model", ""))) b.remove("thinking");
        if (stream) b.put("stream", true);
        else b.remove("stream");
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Content-Type", "application/json; charset=UTF-8");
        h.put("Accept", stream ? "text/event-stream" : "application/json");
        return new WireRequest("POST", base + "/chat/completions", h, "Authorization", "Bearer ", b.toString(), stream);
    }

    static boolean isDeepseekThinkingModel(String m) {
        String s = m == null ? "" : m.toLowerCase(Locale.ROOT);
        return s.equals("deepseek-flash") || s.startsWith("deepseek-v4");
    }

    @Override public JSONObject toOpenAi(String body, String model) throws Exception {
        return new JSONObject(body);
    }

    @Override public StreamTranslator newStream(String model) {
        return new StreamTranslator() {
            boolean done;

            @Override public List<String> onLine(String line) {
                if (line != null) {
                    String s = line.trim();
                    if (s.startsWith("data:") && "[DONE]".equals(s.substring(5).trim())) done = true;
                }
                return Collections.singletonList(line);
            }

            @Override public List<String> finish() {
                return done ? Collections.<String>emptyList() : Collections.singletonList(OpenAiShapes.DONE);
            }

            @Override public boolean done() {
                return done;
            }
        };
    }

    @Override public WireRequest check(String base, String model) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/json");
        return new WireRequest("GET", base + "/models", h, "Authorization", "Bearer ", null, false);
    }

    @Override public ProviderErrors.Verdict checkVerdict(int status, String body, String model) {
        if (status < 200 || status > 299) return ProviderErrors.of(ProviderKind.OPENAI_COMPAT, status, body, model);
        List<String> ids = modelIds(body);
        return verdictForList(status, ids, model);
    }

    static ProviderErrors.Verdict verdictForList(int status, List<String> ids, String model) {
        if (ids.isEmpty()) return ProviderErrors.ok(status, "Key 有效");
        if (model == null || model.length() == 0) return ProviderErrors.ok(status, "Key 有效，可用模型 " + ids.size() + " 个（还没填模型名）");
        for (String id : ids) if (id.equalsIgnoreCase(model)) return ProviderErrors.ok(status, "Key 有效，模型「" + model + "」可用");
        return ProviderErrors.ok(status, "Key 有效，但模型列表里没有「" + model + "」（有的服务不全列，可先试着用）");
    }

    /** OpenAI 格式模型列表 {@code {"data":[{"id":…}]}} 的 id（也认 {@code models:[…]}）。 */
    static List<String> modelIds(String body) {
        List<String> out = new ArrayList<String>();
        try {
            JSONObject o = new JSONObject(body);
            JSONArray a = o.optJSONArray("data");
            if (a == null) a = o.optJSONArray("models");
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                String id = m == null ? a.optString(i, "") : m.optString("id", m.optString("name", ""));
                if (id.startsWith("models/")) id = id.substring(7);
                if (id.length() > 0) out.add(id);
            }
        } catch (Exception ignored) { }
        return out;
    }
}
