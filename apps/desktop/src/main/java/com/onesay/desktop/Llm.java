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

    /** 模型服务不支持工具调用（返回 400 / 422 之类，且不是别的原因）时抛这个，调用方可以退回纯聊天。 */
    public static final class ToolsUnsupported extends Exception {
        public ToolsUnsupported(String m) { super(m); }
    }

    /** 可以稍后重试的失败：超时、连不上、429、5xx、空回复。 */
    public static final class Transient extends Exception {
        public Transient(String m) { super(m); }
    }

    /** 400 的原因分类（看响应体）。 */
    enum Bad400 { REASONING, CONTEXT, TOOLS, OTHER }

    static Bad400 classify400(String body) {
        String b = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        // DeepSeek 思考模式：带 tools 的请求要回传之前轮次的 reasoning_content，否则 400 —— 这不是「不支持工具」
        if (b.contains("reasoning_content") || b.contains("reasoning content") || b.contains("thinking")) return Bad400.REASONING;
        if (b.contains("context length") || b.contains("context_length") || b.contains("maximum context") || b.contains("too many tokens") || b.contains("max_tokens")) return Bad400.CONTEXT;
        if (b.contains("tool") || b.contains("function")) return Bad400.TOOLS;
        return Bad400.OTHER;
    }

    /** 请求体（单独拿出来便于测试）：messages 原样带上（含 assistant 的 reasoning_content / tool_calls）；noThinking 时显式关思考。 */
    static Map<String, Object> requestBody(String model, List<Map<String, Object>> messages, List<Map<String, Object>> tools, double temperature, boolean noThinking) {
        Map<String, Object> body = new TreeMap<>();
        body.put("model", model);
        body.put("messages", new ArrayList<Object>(messages));
        body.put("temperature", temperature);
        body.put("stream", false);
        if (tools != null && !tools.isEmpty()) body.put("tools", new ArrayList<Object>(tools));
        if (noThinking) body.put("thinking", new TreeMap<>(Map.of("type", "disabled")));
        return body;
    }

    /**
     * [chat-agent-1118] 带工具发一轮对话（tools 为 null = 纯聊天）。失败抛出带中文说明的异常：
     * 超时 / 连不上 / 429 / 5xx / 空回复抛 {@link Transient}（可重试）；带工具时 400 先看响应体 ——
     * 要回传 reasoning_content（思考模式）的，关掉思考重发一次，仍不行按普通错误报，<b>不</b>当成不支持工具；
     * 明确是工具 / 函数相关的 400、或 404 / 422，才抛 {@link ToolsUnsupported}。
     */
    public Turn turn(List<Map<String, Object>> messages, List<Map<String, Object>> tools, double temperature, int timeoutSec) throws Exception {
        return turn(messages, tools, temperature, timeoutSec, false);
    }

    private Turn turn(List<Map<String, Object>> messages, List<Map<String, Object>> tools, double temperature, int timeoutSec, boolean noThinking) throws Exception {
        if (!ready()) throw new IllegalStateException("还没填大模型 Key");
        boolean withTools = tools != null && !tools.isEmpty();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(requestBody(model, messages, tools, temperature, noThinking)), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r;
        try {
            r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new Transient("大模型超时");
        } catch (java.io.IOException e) {
            throw new Transient("连不上大模型服务");
        }
        int sc = r.statusCode();
        if (sc == 401 || sc == 403) throw new Exception("大模型 Key 无效或没权限（" + sc + "）");
        if (sc == 402) throw new Exception("大模型账户余额不足");
        if (sc == 429) throw new Transient("大模型请求太频繁，稍后再试");
        if (sc >= 500) throw new Transient("大模型服务暂时出错 " + sc);
        if (withTools && sc == 400) {
            Bad400 why = classify400(r.body());
            if (why == Bad400.REASONING) {
                if (!noThinking) return turn(messages, tools, temperature, timeoutSec, true);
                throw new Exception("大模型要求回传思考内容（400），关掉思考重发也没过");
            }
            if (why == Bad400.CONTEXT) throw new Exception("对话太长，超出了大模型的上下文（400）");
            if (why == Bad400.TOOLS) throw new ToolsUnsupported("大模型服务不支持工具调用（400）");
            throw new Exception("大模型返回错误 400");
        }
        if (withTools && (sc == 404 || sc == 422)) throw new ToolsUnsupported("大模型服务不支持工具调用（" + sc + "）");
        if (sc / 100 != 2) throw new Exception("大模型返回错误 " + sc);
        try {
            return parseTurn(Json.obj(r.body()));
        } catch (RuntimeException e) {
            throw new Transient("大模型返回格式不认识");
        }
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
        if ((content == null || content.isBlank()) && calls.isEmpty()) throw new Transient("大模型返回了空回复");
        // 原样带回的 assistant 消息：整条保留（DeepSeek 思考模式的 reasoning_content 等字段也要在后续带工具的请求里回传）
        Map<String, Object> echo = new TreeMap<>(msg);
        echo.put("role", "assistant");
        if (echo.get("content") == null) echo.put("content", "");
        if (calls.isEmpty()) echo.remove("tool_calls");
        return new Turn(content, calls, echo);
    }
}
