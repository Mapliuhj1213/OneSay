package com.onesay.desktop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** OpenAI 兼容的对话接口：DeepSeek、百炼兼容模式、或自定义地址。Key 由用户自带。 */
public final class Llm {
    public final String url, key, model;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Llm(String url, String key, String model) { this.url = url; this.key = key; this.model = model; }

    public static Llm from(Config c) {
        String base, model;
        switch (c.llmProvider) {
            case "bailian": base = "https://dashscope.aliyuncs.com/compatible-mode/v1"; model = "qwen-plus"; break;
            case "custom": base = c.llmBase; model = ""; break;
            default: base = "https://api.deepseek.com"; model = "deepseek-chat";
        }
        if (!c.llmModel.isEmpty()) model = c.llmModel;
        return new Llm(base.replaceAll("/+$", "") + "/chat/completions", c.llmKey, model);
    }

    public static String defaultModel(String provider) {
        return provider.equals("bailian") ? "qwen-plus" : provider.equals("custom") ? "" : "deepseek-chat";
    }

    public boolean ready() { return !key.isEmpty() && url.startsWith("http") && !model.isEmpty(); }

    public static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new TreeMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** 发一次对话，返回模型回复的文字。失败抛出带中文说明的异常。 */
    @SuppressWarnings("unchecked")
    public String chat(List<Map<String, Object>> messages, double temperature, int timeoutSec) throws Exception {
        if (!ready()) throw new IllegalStateException("还没填大模型 Key");
        Map<String, Object> body = new TreeMap<>();
        body.put("model", model);
        body.put("messages", new ArrayList<Object>(messages));
        body.put("temperature", temperature);
        body.put("stream", false);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r;
        try {
            r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new Exception("大模型超时");
        } catch (java.io.IOException e) {
            throw new Exception("连不上大模型服务");
        }
        if (r.statusCode() == 401 || r.statusCode() == 403) throw new Exception("大模型 Key 无效或没权限（" + r.statusCode() + "）");
        if (r.statusCode() == 402) throw new Exception("大模型账户余额不足");
        if (r.statusCode() == 429) throw new Exception("大模型请求太频繁，稍后再试");
        if (r.statusCode() / 100 != 2) throw new Exception("大模型返回错误 " + r.statusCode());
        Map<String, Object> o = Json.obj(r.body());
        Object ch = o.get("choices");
        if (ch instanceof List && !((List<?>) ch).isEmpty()) {
            Object m = ((Map<String, Object>) ((List<?>) ch).get(0)).get("message");
            if (m instanceof Map && ((Map<String, Object>) m).get("content") != null) return String.valueOf(((Map<String, Object>) m).get("content")).trim();
        }
        throw new Exception("大模型返回格式不认识");
    }
}
