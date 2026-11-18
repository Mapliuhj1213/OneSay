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
    public String chat(List<Map<String, Object>> messages, double temperature, int timeoutSec) throws Exception {
        Turn t = turn(messages, null, temperature, timeoutSec);
        if (t.content == null) throw new Exception("大模型返回格式不认识");
        return t.content.trim();
    }

    /** 模型要调用的一个工具（OpenAI 兼容的 tool_calls）。 */
    public static final class ToolCall {
        public final String id, name, arguments;

        public ToolCall(String id, String name, String arguments) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
        }
    }

    /** 模型的一轮回复：文字（可能为 null）+ 要调用的工具（可能为空）+ 原样的 assistant 消息（下一轮要原样带回去）。 */
    public static final class Turn {
        public final String content;
        public final List<ToolCall> calls;
        public final Map<String, Object> message;

        public Turn(String content, List<ToolCall> calls, Map<String, Object> message) {
            this.content = content;
            this.calls = calls == null ? List.of() : calls;
            this.message = message;
        }
    }

    /** 模型服务不支持工具调用（返回 400 / 422 之类）时抛这个，调用方可以退回纯聊天。 */
    public static final class ToolsUnsupported extends Exception {
        public ToolsUnsupported(String m) { super(m); }
    }

    /**
     * [chat-agent-1118] 带工具发一轮对话（tools 为 null = 纯聊天）。失败抛出带中文说明的异常；
     * 带工具时服务端回 400 / 404 / 422 抛 {@link ToolsUnsupported}。
     */
    @SuppressWarnings("unchecked")
    public Turn turn(List<Map<String, Object>> messages, List<Map<String, Object>> tools, double temperature, int timeoutSec) throws Exception {
        if (!ready()) throw new IllegalStateException("还没填大模型 Key");
        Map<String, Object> body = new TreeMap<>();
        body.put("model", model);
        body.put("messages", new ArrayList<Object>(messages));
        body.put("temperature", temperature);
        body.put("stream", false);
        if (tools != null && !tools.isEmpty()) body.put("tools", new ArrayList<Object>(tools));
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
        if (tools != null && !tools.isEmpty() && (r.statusCode() == 400 || r.statusCode() == 404 || r.statusCode() == 422))
            throw new ToolsUnsupported("大模型服务不支持工具调用（" + r.statusCode() + "）");
        if (r.statusCode() / 100 != 2) throw new Exception("大模型返回错误 " + r.statusCode());
        return parseTurn(Json.obj(r.body()));
    }

    /** 解析 chat/completions 的回复（单独拿出来便于测试）。 */
    @SuppressWarnings("unchecked")
    static Turn parseTurn(Map<String, Object> o) throws Exception {
        Object ch = o == null ? null : o.get("choices");
        if (!(ch instanceof List) || ((List<?>) ch).isEmpty() || !(((List<?>) ch).get(0) instanceof Map)) throw new Exception("大模型返回格式不认识");
        Object m = ((Map<String, Object>) ((List<?>) ch).get(0)).get("message");
        if (!(m instanceof Map)) throw new Exception("大模型返回格式不认识");
        Map<String, Object> msg = (Map<String, Object>) m;
        String content = msg.get("content") == null ? null : String.valueOf(msg.get("content"));
        List<ToolCall> calls = new ArrayList<>();
        Object tc = msg.get("tool_calls");
        if (tc instanceof List) for (Object x : (List<?>) tc) {
            if (!(x instanceof Map)) continue;
            Map<String, Object> c = (Map<String, Object>) x;
            Object fn = c.get("function");
            if (!(fn instanceof Map)) continue;
            Map<String, Object> f = (Map<String, Object>) fn;
            calls.add(new ToolCall(c.get("id") == null ? null : String.valueOf(c.get("id")),
                    f.get("name") == null ? null : String.valueOf(f.get("name")),
                    f.get("arguments") == null ? null : String.valueOf(f.get("arguments"))));
        }
        if (content == null && calls.isEmpty()) throw new Exception("大模型返回格式不认识");
        // 原样带回的 assistant 消息：只留协议需要的字段
        Map<String, Object> echo = new TreeMap<>();
        echo.put("role", "assistant");
        echo.put("content", content == null ? "" : content);
        if (!calls.isEmpty()) echo.put("tool_calls", tc);
        return new Turn(content, calls, echo);
    }
}
