package com.onesay.assistant.v2.llm;

import com.onesay.assistant.v2.net.LlmResponse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** priv-llm：三种协议适配器的请求组装 / 回复解析 / 流式翻译（样本见 test-jvm/fixtures/llm，不联网）。 */
public class ProviderAdaptersTest {

    static String fx(String name) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), "test-jvm/fixtures/llm/" + name).toPath()), "UTF-8");
    }

    /** App 里真实会出现的 OpenAI 格式请求：两条 system、带图 user、助手工具调用、工具结果、再一条 user。 */
    static JSONObject richBody() throws Exception {
        JSONArray m = new JSONArray();
        m.put(new JSONObject().put("role", "system").put("content", "你是助手。"));
        m.put(new JSONObject().put("role", "system").put("content", "今天是周六。"));
        m.put(new JSONObject().put("role", "user").put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "看看这张图"))
                .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "data:image/png;base64,iVBORw0KGgo=")))));
        JSONObject call = new JSONObject().put("id", "call_1").put("type", "function")
                .put("function", new JSONObject().put("name", "battery__level").put("arguments", "{\"detail\":true}"));
        m.put(new JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", new JSONArray().put(call)));
        m.put(new JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "{\"level\":80}"));
        m.put(new JSONObject().put("role", "user").put("content", "那够用吗"));
        JSONObject params = new JSONObject().put("type", "object").put("additionalProperties", false)
                .put("$schema", "http://json-schema.org/draft-07/schema#")
                .put("properties", new JSONObject().put("detail", new JSONObject().put("type", "boolean").put("additionalProperties", false)));
        JSONArray tools = new JSONArray()
                .put(new JSONObject().put("type", "function").put("function", new JSONObject().put("name", "battery__level")
                        .put("description", "电量").put("parameters", params)))
                .put(new JSONObject().put("type", "function").put("function", new JSONObject().put("name", "noop")
                        .put("description", "无参数").put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()))));
        return new JSONObject().put("model", "deepseek-flash").put("temperature", 1.3).put("max_tokens", 900)
                .put("thinking", new JSONObject().put("type", "disabled")).put("messages", m).put("tools", tools).put("tool_choice", "auto");
    }

    // ---------------------------------------------------------------- OpenAI 兼容

    @Test public void openAiCompatSwapsModelAndDropsDeepseekOnlyField() throws Exception {
        WireRequest w = new OpenAiCompatAdapter().chat("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", richBody(), false);
        assertEquals("POST", w.method);
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", w.url);
        assertEquals("Authorization", w.keyHeader);
        assertEquals("Bearer ", w.keyPrefix);
        JSONObject b = new JSONObject(w.body);
        assertEquals("qwen-plus", b.getString("model"));
        assertFalse("DeepSeek 私有的 thinking 字段不发给别家", b.has("thinking"));
        assertFalse(b.has("stream"));
        assertEquals(6, b.getJSONArray("messages").length());
        WireRequest d = new OpenAiCompatAdapter().chat("https://api.deepseek.com", "deepseek-flash", richBody(), true);
        assertTrue("DeepSeek V4 系保留关思考", new JSONObject(d.body).has("thinking"));
        assertTrue(new JSONObject(d.body).getBoolean("stream"));
        assertEquals("text/event-stream", d.headers.get("Accept"));
    }

    @Test public void openAiCompatCheckUsesModelList() throws Exception {
        OpenAiCompatAdapter a = new OpenAiCompatAdapter();
        WireRequest w = a.check("https://api.deepseek.com", "deepseek-flash");
        assertEquals("GET", w.method);
        assertEquals("https://api.deepseek.com/models", w.url);
        assertNull(w.body);
        ProviderErrors.Verdict v = a.checkVerdict(200, fx("openai_models.json"), "deepseek-flash");
        assertTrue(v.ok());
        assertTrue(v.message, v.message.contains("可用"));
        v = a.checkVerdict(200, fx("openai_models.json"), "qwen-max");
        assertTrue("列表里没有也算 Key 有效，只提示", v.ok());
        assertTrue(v.message, v.message.contains("没有「qwen-max」"));
        v = a.checkVerdict(401, fx("openai_error_401.json"), "deepseek-flash");
        assertEquals(ProviderErrors.Kind.AUTH, v.kind);
    }

    // ---------------------------------------------------------------- Anthropic

    @Test public void anthropicRequestShape() throws Exception {
        WireRequest w = new AnthropicAdapter().chat("https://api.anthropic.com", "claude-sonnet-4-5", richBody(), false);
        assertEquals("https://api.anthropic.com/v1/messages", w.url);
        assertEquals("x-api-key", w.keyHeader);
        assertEquals("", w.keyPrefix);
        assertEquals(AnthropicAdapter.VERSION, w.headers.get("anthropic-version"));
        JSONObject b = new JSONObject(w.body);
        assertEquals("claude-sonnet-4-5", b.getString("model"));
        assertEquals(900, b.getInt("max_tokens"));
        assertEquals("温度夹到 Anthropic 的 0..1", 1.0, b.getDouble("temperature"), 1e-9);
        assertEquals("你是助手。\n\n今天是周六。", b.getString("system"));
        assertFalse(b.has("thinking"));
        JSONArray msgs = b.getJSONArray("messages");
        assertEquals(3, msgs.length());
        assertEquals("user", msgs.getJSONObject(0).getString("role"));
        JSONArray c0 = msgs.getJSONObject(0).getJSONArray("content");
        assertEquals("text", c0.getJSONObject(0).getString("type"));
        JSONObject img = c0.getJSONObject(1);
        assertEquals("image", img.getString("type"));
        assertEquals("base64", img.getJSONObject("source").getString("type"));
        assertEquals("image/png", img.getJSONObject("source").getString("media_type"));
        assertEquals("iVBORw0KGgo=", img.getJSONObject("source").getString("data"));
        JSONObject a = msgs.getJSONObject(1);
        assertEquals("assistant", a.getString("role"));
        JSONObject tu = a.getJSONArray("content").getJSONObject(0);
        assertEquals("tool_use", tu.getString("type"));
        assertEquals("call_1", tu.getString("id"));
        assertTrue(tu.getJSONObject("input").getBoolean("detail"));
        JSONArray u2 = msgs.getJSONObject(2).getJSONArray("content");
        assertEquals("tool 结果和后一条 user 合并，且 tool_result 在前", "tool_result", u2.getJSONObject(0).getString("type"));
        assertEquals("call_1", u2.getJSONObject(0).getString("tool_use_id"));
        assertEquals("那够用吗", u2.getJSONObject(1).getString("text"));
        JSONArray tools = b.getJSONArray("tools");
        assertEquals("battery__level", tools.getJSONObject(0).getString("name"));
        assertTrue(tools.getJSONObject(0).has("input_schema"));
        assertEquals("auto", b.getJSONObject("tool_choice").getString("type"));
    }

    @Test public void anthropicDefaultsAndLeadingAssistant() throws Exception {
        JSONObject in = new JSONObject().put("model", "x").put("messages", new JSONArray()
                .put(new JSONObject().put("role", "assistant").put("content", "上次说到…"))
                .put(new JSONObject().put("role", "user").put("content", ""))
                .put(new JSONObject().put("role", "user").put("content", "继续")));
        JSONObject b = new JSONObject(new AnthropicAdapter().chat("https://api.anthropic.com", "claude-haiku-4-5", in, true).body);
        assertEquals(AnthropicAdapter.DEFAULT_MAX_TOKENS, b.getInt("max_tokens"));
        assertTrue(b.getBoolean("stream"));
        assertFalse("没有 system 就不带 system 字段", b.has("system"));
        JSONArray msgs = b.getJSONArray("messages");
        assertEquals("首条必须是 user：补占位", "user", msgs.getJSONObject(0).getString("role"));
        assertEquals("assistant", msgs.getJSONObject(1).getString("role"));
        JSONArray last = msgs.getJSONObject(2).getJSONArray("content");
        assertEquals("空文本块不发", 1, last.length());
        assertEquals("继续", last.getJSONObject(0).getString("text"));
    }

    @Test public void anthropicResponseToOpenAiShape() throws Exception {
        JSONObject o = new AnthropicAdapter().toOpenAi(fx("anthropic_messages.json"), "claude-sonnet-4-5");
        LlmResponse r = LlmResponse.parse(200, o.toString());
        assertTrue(r.ok());
        assertEquals("我先看一下电量。", r.content());
        assertEquals(1, r.toolCalls().length());
        JSONObject tc = r.toolCalls().getJSONObject(0);
        assertEquals("toolu_01Fake", tc.getString("id"));
        assertEquals("battery__level", tc.getJSONObject("function").getString("name"));
        assertTrue(new JSONObject(tc.getJSONObject("function").getString("arguments")).getBoolean("detail"));
        JSONObject c0 = o.getJSONArray("choices").getJSONObject(0);
        assertEquals("tool_calls", c0.getString("finish_reason"));
        assertEquals("先查电量。", c0.getJSONObject("message").getString("reasoning_content"));
        assertEquals("用量统一成总输入口径", 150, o.getJSONObject("usage").getInt("prompt_tokens"));
        assertEquals(42, o.getJSONObject("usage").getInt("completion_tokens"));
    }

    static List<String> translate(ProviderAdapter.StreamTranslator t, String sse) throws Exception {
        List<String> out = new ArrayList<String>();
        boolean doneSeen = false;
        for (String line : sse.split("\n", -1)) {
            for (String l : t.onLine(line)) { out.add(l); if (LlmResponse.isDoneLine(l)) doneSeen = true; }
            if (doneSeen || t.done()) return out;
        }
        out.addAll(t.finish());
        return out;
    }

    static String text(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String l : lines) { String d = LlmResponse.deltaOf(l); if (d != null) b.append(d); }
        return b.toString();
    }

    @Test public void anthropicStreamTranslatesTextToolCallsAndDone() throws Exception {
        List<String> lines = translate(new AnthropicAdapter().newStream("m"), fx("anthropic_stream.sse"));
        assertEquals("明天上海有小雨。", text(lines));
        assertTrue(LlmResponse.isDoneLine(lines.get(lines.size() - 1)));
        StringBuilder args = new StringBuilder();
        String name = null, id = null, finish = null;
        for (String l : lines) {
            if (!l.startsWith("data: {")) continue;
            JSONObject c0 = new JSONObject(l.substring(6)).getJSONArray("choices").getJSONObject(0);
            if (!c0.isNull("finish_reason")) finish = c0.getString("finish_reason");
            JSONArray tcs = c0.getJSONObject("delta").optJSONArray("tool_calls");
            if (tcs == null) continue;
            JSONObject tc = tcs.getJSONObject(0);
            assertEquals(0, tc.getInt("index"));
            if (tc.has("id")) id = tc.getString("id");
            JSONObject fn = tc.getJSONObject("function");
            if (fn.has("name")) name = fn.getString("name");
            args.append(fn.optString("arguments", ""));
        }
        assertEquals("toolu_02Fake", id);
        assertEquals("alarm__set", name);
        assertEquals("07:30", new JSONObject(args.toString()).getString("time"));
        assertEquals("tool_calls", finish);
    }

    @Test public void anthropicCutStreamDoesNotPretendDone() throws Exception {
        List<String> lines = translate(new AnthropicAdapter().newStream("m"), fx("anthropic_stream_cut.sse"));
        assertEquals("说到一半", text(lines));
        for (String l : lines) assertFalse("中途断开不补 [DONE]", LlmResponse.isDoneLine(l));
    }

    @Test(expected = Exception.class)
    public void anthropicStreamErrorEventThrows() throws Exception {
        new AnthropicAdapter().newStream("m").onLine("data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");
    }

    @Test public void anthropicCheck() throws Exception {
        AnthropicAdapter a = new AnthropicAdapter();
        WireRequest w = a.check("https://api.anthropic.com", "claude-sonnet-4-5");
        assertEquals("https://api.anthropic.com/v1/models", w.url);
        assertEquals("GET", w.method);
        assertEquals(AnthropicAdapter.VERSION, w.headers.get("anthropic-version"));
        assertTrue(a.checkVerdict(200, fx("anthropic_models.json"), "claude-sonnet-4-5").message.contains("可用"));
        ProviderErrors.Verdict v = a.checkVerdict(529, fx("anthropic_error_529.json"), "m");
        assertEquals(ProviderErrors.Kind.SERVER, v.kind);
        assertEquals(529, v.status);
    }

    // ---------------------------------------------------------------- Gemini

    @Test public void geminiRequestShape() throws Exception {
        WireRequest w = new GeminiAdapter().chat("https://generativelanguage.googleapis.com", "gemini-2.5-flash", richBody(), false);
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", w.url);
        assertEquals("x-goog-api-key", w.keyHeader);
        assertFalse("Key 绝不放进 URL", w.url.contains("key="));
        JSONObject b = new JSONObject(w.body);
        assertEquals("你是助手。\n\n今天是周六。", b.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"));
        JSONArray contents = b.getJSONArray("contents");
        assertEquals(3, contents.length());
        assertEquals("user", contents.getJSONObject(0).getString("role"));
        assertEquals("image/png", contents.getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData").getString("mimeType"));
        JSONObject model = contents.getJSONObject(1);
        assertEquals("model", model.getString("role"));
        JSONObject fcPart = model.getJSONArray("parts").getJSONObject(0);
        assertEquals("重放的调用带跳过签名占位", GeminiAdapter.SKIP_SIGNATURE, fcPart.getString("thoughtSignature"));
        assertEquals("battery__level", fcPart.getJSONObject("functionCall").getString("name"));
        JSONArray u2 = contents.getJSONObject(2).getJSONArray("parts");
        JSONObject fr = u2.getJSONObject(0).getJSONObject("functionResponse");
        assertEquals("按 tool_call_id 找回函数名", "battery__level", fr.getString("name"));
        assertEquals("{\"level\":80}", fr.getJSONObject("response").getString("content"));
        assertEquals("那够用吗", u2.getJSONObject(1).getString("text"));
        JSONArray decl = b.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations");
        JSONObject p = decl.getJSONObject(0).getJSONObject("parameters");
        assertFalse(p.has("additionalProperties"));
        assertFalse(p.has("$schema"));
        assertFalse("嵌套的也去掉", p.getJSONObject("properties").getJSONObject("detail").has("additionalProperties"));
        assertFalse("无参数工具省略 parameters", decl.getJSONObject(1).has("parameters"));
        JSONObject gc = b.getJSONObject("generationConfig");
        assertEquals(900, gc.getInt("maxOutputTokens"));
        assertEquals(1.3, gc.getDouble("temperature"), 1e-9);
        WireRequest s = new GeminiAdapter().chat("https://generativelanguage.googleapis.com", "models/gemini-2.5-flash", richBody(), true);
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse", s.url);
    }

    @Test public void geminiResponseToOpenAiShape() throws Exception {
        JSONObject o = new GeminiAdapter().toOpenAi(fx("gemini_generate.json"), "gemini-2.5-flash");
        LlmResponse r = LlmResponse.parse(200, o.toString());
        assertTrue(r.ok());
        assertEquals("好的，帮你打开客厅灯。", r.content());
        JSONObject tc = r.toolCalls().getJSONObject(0);
        assertEquals("没有 id 时合成", "gemini-0-light__on", tc.getString("id"));
        assertEquals("客厅", new JSONObject(tc.getJSONObject("function").getString("arguments")).getString("room"));
        JSONObject c0 = o.getJSONArray("choices").getJSONObject(0);
        assertEquals("tool_calls", c0.getString("finish_reason"));
        assertEquals("想一想要不要开灯", c0.getJSONObject("message").getString("reasoning_content"));
        assertEquals(26, o.getJSONObject("usage").getInt("completion_tokens"));
    }

    @Test(expected = Exception.class)
    public void geminiBlockedPromptThrows() throws Exception {
        new GeminiAdapter().toOpenAi(fx("gemini_blocked.json"), "m");
    }

    @Test public void geminiStream() throws Exception {
        List<String> lines = translate(new GeminiAdapter().newStream("m"), fx("gemini_stream.sse"));
        assertEquals("今天天气晴。", text(lines));
        assertTrue("见过 finishReason → EOF 补 [DONE]", LlmResponse.isDoneLine(lines.get(lines.size() - 1)));
        List<String> cut = translate(new GeminiAdapter().newStream("m"), "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"半句\"}]}}]}\n");
        for (String l : cut) assertFalse(LlmResponse.isDoneLine(l));
    }

    @Test public void geminiCheck() throws Exception {
        GeminiAdapter g = new GeminiAdapter();
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash", g.check("https://generativelanguage.googleapis.com", "gemini-2.5-flash").url);
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models", g.check("https://generativelanguage.googleapis.com", "").url);
        assertTrue(g.checkVerdict(200, fx("gemini_model.json"), "gemini-2.5-flash").ok());
        assertEquals(ProviderErrors.Kind.AUTH, g.checkVerdict(400, fx("gemini_error_400_key.json"), "m").kind);
        assertEquals(ProviderErrors.Kind.MODEL_NOT_FOUND, g.checkVerdict(404, "{}", "gemini-x").kind);
    }

    // ---------------------------------------------------------------- 杂项

    @Test public void urlNormalization() {
        assertEquals("https://api.deepseek.com", ProviderUrls.normalize(ProviderKind.OPENAI_COMPAT, " https://api.deepseek.com/chat/completions/ "));
        assertEquals("https://api.anthropic.com", ProviderUrls.normalize(ProviderKind.ANTHROPIC, "https://api.anthropic.com/v1/messages"));
        assertEquals("https://api.anthropic.com", ProviderUrls.normalize(ProviderKind.ANTHROPIC, "https://api.anthropic.com/v1"));
        assertEquals("https://generativelanguage.googleapis.com",
                ProviderUrls.normalize(ProviderKind.GEMINI, "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"));
        assertNull("只收 https", ProviderUrls.normalize(ProviderKind.OPENAI_COMPAT, "http://api.example.com/v1"));
        assertNull("不带查询串", ProviderUrls.normalize(ProviderKind.GEMINI, "https://x.example.com?key=abc"));
        assertNull("不带账号", ProviderUrls.normalize(ProviderKind.OPENAI_COMPAT, "https://u:p@x.example.com/v1"));
        assertTrue(ProviderUrls.validModel("anthropic/claude-sonnet-4.5"));
        assertTrue(ProviderUrls.validModel("qwen3:8b"));
        assertFalse(ProviderUrls.validModel("a b"));
        assertFalse(ProviderUrls.validModel("x?y"));
    }

    @Test public void errorMapping() throws Exception {
        assertEquals(ProviderErrors.Kind.QUOTA, ProviderErrors.of(ProviderKind.OPENAI_COMPAT, 402, "{}", "m").kind);
        assertEquals(ProviderErrors.Kind.RATE_LIMITED, ProviderErrors.of(ProviderKind.OPENAI_COMPAT, 429, "{}", "m").kind);
        assertEquals(ProviderErrors.Kind.CONTEXT_TOO_LONG, ProviderErrors.of(ProviderKind.OPENAI_COMPAT, 400,
                "{\"error\":{\"message\":\"This model's maximum context length is 65536 tokens\"}}", "m").kind);
        assertEquals(ProviderErrors.Kind.QUOTA, ProviderErrors.of(ProviderKind.ANTHROPIC, 400,
                "{\"error\":{\"message\":\"Your credit balance is too low\"}}", "m").kind);
        assertEquals(ProviderErrors.Kind.RATE_LIMITED, ProviderErrors.of(ProviderKind.GEMINI, 429,
                "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}", "m").kind);
        ProviderErrors.Verdict v = ProviderErrors.of(ProviderKind.OPENAI_COMPAT, 418, "{\"error\":{\"message\":\"teapot\"}}", "m");
        assertEquals(ProviderErrors.Kind.BAD_RESPONSE, v.kind);
        assertTrue(v.message.contains("teapot"));
    }

    @Test public void presetsStableAndKnown() {
        assertEquals(9, ProviderPreset.all().size());
        assertEquals(ProviderKind.ANTHROPIC, ProviderPreset.get("anthropic").kind);
        assertEquals(ProviderKind.GEMINI, ProviderPreset.get("gemini").kind);
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", ProviderPreset.get("dashscope").defaultBase);
        for (ProviderPreset p : ProviderPreset.all()) {
            if (p.defaultBase.length() > 0) assertEquals(p.id, p.defaultBase, ProviderUrls.normalize(p.kind, p.defaultBase));
        }
        assertNull(ProviderPreset.get("nope"));
    }
}
