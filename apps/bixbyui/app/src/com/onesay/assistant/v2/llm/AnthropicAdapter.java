/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/model/AnthropicModel.kt: request/buildMessages/userBlocks/
 * assistantBlocks/parse/AnthropicStream) to Java 7 + org.json. Input is an OpenAI-format request body instead of
 * Mika's Message list; output is translated back to OpenAI chat.completion / chunk shape. Thinking-signature
 * replay and prompt-cache marks are not carried over. Upstream: 8d61573.
 */
package com.onesay.assistant.v2.llm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API。
 * <ul>
 *   <li>system 消息并入顶层 {@code system}（Anthropic 没有 system 角色消息）；</li>
 *   <li>相邻同角色合并（要求 user / assistant 交替）；首条不是 user 时补一条占位 user；</li>
 *   <li>{@code tool} 消息变成 user 消息里的 {@code tool_result}，且排在同一条 user 消息其它块之前；</li>
 *   <li>助手的 {@code tool_calls} 变成 {@code tool_use} 块；{@code max_tokens} 必填，缺省 {@link #DEFAULT_MAX_TOKENS}。</li>
 * </ul>
 */
public final class AnthropicAdapter implements ProviderAdapter {

    public static final String VERSION = "2023-06-01";
    static final int DEFAULT_MAX_TOKENS = 4096;

    @Override public ProviderKind kind() {
        return ProviderKind.ANTHROPIC;
    }

    @Override public WireRequest chat(String base, String model, JSONObject in, boolean stream) throws Exception {
        JSONObject out = new JSONObject();
        out.put("model", model != null && model.length() > 0 ? model : in.optString("model", ""));
        int max = in.optInt("max_tokens", in.optInt("max_completion_tokens", DEFAULT_MAX_TOKENS));
        out.put("max_tokens", max > 0 ? max : DEFAULT_MAX_TOKENS);
        if (stream) out.put("stream", true);
        JSONArray msgs = in.optJSONArray("messages");
        StringBuilder sys = new StringBuilder();
        List<Object[]> merged = new ArrayList<Object[]>();   // {role, List<JSONObject> blocks}
        if (msgs != null) {
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role", "user");
                if ("system".equals(role) || "developer".equals(role)) {
                    String t = OpenAiShapes.textOf(m.opt("content"));
                    if (t.trim().length() > 0) { if (sys.length() > 0) sys.append("\n\n"); sys.append(t); }
                    continue;
                }
                String r;
                List<JSONObject> blocks;
                if ("assistant".equals(role)) { r = "assistant"; blocks = assistantBlocks(m); }
                else if ("tool".equals(role)) { r = "user"; blocks = Collections.singletonList(toolResult(m)); }
                else { r = "user"; blocks = userBlocks(m.opt("content")); }
                if (blocks.isEmpty()) continue;
                Object[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && last[0].equals(r)) {
                    @SuppressWarnings("unchecked") List<JSONObject> lb = (List<JSONObject>) last[1];
                    lb.addAll(blocks);
                } else {
                    merged.add(new Object[]{r, new ArrayList<JSONObject>(blocks)});
                }
            }
        }
        if (merged.isEmpty() || !"user".equals(merged.get(0)[0])) {
            List<JSONObject> b = new ArrayList<JSONObject>();
            b.add(text("（继续）"));
            merged.add(0, new Object[]{"user", b});
        }
        JSONArray wire = new JSONArray();
        for (Object[] e : merged) {
            @SuppressWarnings("unchecked") List<JSONObject> blocks = (List<JSONObject>) e[1];
            if ("user".equals(e[0])) blocks = toolResultsFirst(blocks);
            JSONArray content = new JSONArray();
            for (JSONObject b : blocks) content.put(b);
            wire.put(new JSONObject().put("role", e[0]).put("content", content));
        }
        if (sys.length() > 0) out.put("system", sys.toString());
        out.put("messages", wire);
        if (in.has("temperature")) out.put("temperature", Math.max(0d, Math.min(1d, in.optDouble("temperature", 1d))));
        if (in.has("top_p")) out.put("top_p", in.optDouble("top_p"));
        Object stop = in.opt("stop");
        if (stop instanceof String) out.put("stop_sequences", new JSONArray().put(stop));
        else if (stop instanceof JSONArray) out.put("stop_sequences", stop);
        JSONArray tools = in.optJSONArray("tools");
        Object choice = in.opt("tool_choice");
        if (tools != null && tools.length() > 0 && !"none".equals(choice)) {
            JSONArray t = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject fn = tools.optJSONObject(i) == null ? null : tools.optJSONObject(i).optJSONObject("function");
                if (fn == null) continue;
                JSONObject schema = fn.optJSONObject("parameters");
                t.put(new JSONObject().put("name", fn.optString("name")).put("description", fn.optString("description", ""))
                        .put("input_schema", schema != null ? schema : new JSONObject().put("type", "object")));
            }
            out.put("tools", t);
            if ("required".equals(choice)) out.put("tool_choice", new JSONObject().put("type", "any"));
            else if (choice instanceof JSONObject && ((JSONObject) choice).optJSONObject("function") != null)
                out.put("tool_choice", new JSONObject().put("type", "tool").put("name", ((JSONObject) choice).optJSONObject("function").optString("name")));
            else out.put("tool_choice", new JSONObject().put("type", "auto"));
        }
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Content-Type", "application/json; charset=UTF-8");
        h.put("Accept", stream ? "text/event-stream" : "application/json");
        h.put("anthropic-version", VERSION);
        return new WireRequest("POST", base + "/v1/messages", h, "x-api-key", "", out.toString(), stream);
    }

    private static JSONObject text(String s) throws Exception {
        return new JSONObject().put("type", "text").put("text", s);
    }

    private static List<JSONObject> userBlocks(Object content) throws Exception {
        List<JSONObject> out = new ArrayList<JSONObject>();
        if (content instanceof JSONArray) {
            JSONArray a = (JSONArray) content;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p == null) continue;
                String type = p.optString("type");
                if ("text".equals(type)) {
                    String t = p.optString("text", "");
                    if (t.length() > 0) out.add(text(t));   // Anthropic 拒绝空文本块
                } else if ("image_url".equals(type)) {
                    JSONObject iu = p.optJSONObject("image_url");
                    String url = iu != null ? iu.optString("url", "") : p.optString("image_url", "");
                    String[] d = OpenAiShapes.dataUrl(url);
                    JSONObject src = d != null
                            ? new JSONObject().put("type", "base64").put("media_type", d[0]).put("data", d[1])
                            : new JSONObject().put("type", "url").put("url", url);
                    out.add(new JSONObject().put("type", "image").put("source", src));
                }
            }
        } else {
            String t = OpenAiShapes.textOf(content);
            if (t.length() > 0) out.add(text(t));
        }
        return out;
    }

    private static List<JSONObject> assistantBlocks(JSONObject m) throws Exception {
        List<JSONObject> out = new ArrayList<JSONObject>();
        String t = OpenAiShapes.textOf(m.opt("content"));
        if (t.length() > 0) out.add(text(t));
        JSONArray calls = m.optJSONArray("tool_calls");
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject c = calls.optJSONObject(i);
                if (c == null) continue;
                JSONObject fn = c.optJSONObject("function");
                if (fn == null) continue;
                JSONObject input;
                try { input = new JSONObject(fn.optString("arguments", "{}")); } catch (Exception e) { input = new JSONObject(); }
                out.add(new JSONObject().put("type", "tool_use").put("id", c.optString("id")).put("name", fn.optString("name"))
                        .put("input", input));
            }
        }
        return out;
    }

    private static JSONObject toolResult(JSONObject m) throws Exception {
        return new JSONObject().put("type", "tool_result").put("tool_use_id", m.optString("tool_call_id", ""))
                .put("content", OpenAiShapes.textOf(m.opt("content")));
    }

    /** tool_result 必须排在同一条 user 消息的其它块之前。 */
    private static List<JSONObject> toolResultsFirst(List<JSONObject> blocks) {
        List<JSONObject> res = new ArrayList<JSONObject>(), rest = new ArrayList<JSONObject>();
        for (JSONObject b : blocks) ("tool_result".equals(b.optString("type")) ? res : rest).add(b);
        res.addAll(rest);
        return res;
    }

    static String finishOf(String stopReason) {
        if ("tool_use".equals(stopReason)) return "tool_calls";
        if ("max_tokens".equals(stopReason)) return "length";
        if ("refusal".equals(stopReason)) return "content_filter";
        return "stop";
    }

    @Override public JSONObject toOpenAi(String body, String model) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray content = root.optJSONArray("content");
        if (content == null) throw new Exception("Anthropic 回复缺少 content");
        StringBuilder text = new StringBuilder(), thinking = new StringBuilder();
        JSONArray calls = new JSONArray();
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b == null) continue;
            String type = b.optString("type");
            if ("text".equals(type)) text.append(b.optString("text", ""));
            else if ("thinking".equals(type)) thinking.append(b.optString("thinking", ""));
            else if ("tool_use".equals(type)) {
                JSONObject input = b.optJSONObject("input");
                calls.put(OpenAiShapes.toolCall(b.optString("id"), b.optString("name"), input == null ? "{}" : input.toString()));
            }
        }
        JSONObject u = root.optJSONObject("usage");
        int in = -1, outT = 0;
        if (u != null && u.has("input_tokens")) {
            in = u.optInt("input_tokens") + u.optInt("cache_read_input_tokens") + u.optInt("cache_creation_input_tokens");
            outT = u.optInt("output_tokens");
        }
        return OpenAiShapes.completion(root.optString("model", model), text.toString(), thinking.toString(), calls,
                finishOf(root.optString("stop_reason", "")), in, outT);
    }

    @Override public StreamTranslator newStream(String model) {
        return new Stream();
    }

    /** Anthropic SSE（event: / data: 成对）→ OpenAI 流式行。 */
    static final class Stream implements StreamTranslator {
        private final Map<Integer, Integer> toolIndex = new HashMap<Integer, Integer>();
        private boolean done, finished;

        @Override public List<String> onLine(String line) throws Exception {
            List<String> out = new ArrayList<String>();
            if (line == null) return out;
            String s = line.trim();
            if (!s.startsWith("data:")) return out;   // event: / 空行 / 注释
            s = s.substring(5).trim();
            if (s.length() == 0) return out;
            JSONObject root = new JSONObject(s);
            String type = root.optString("type");
            if ("content_block_start".equals(type)) {
                int idx = root.optInt("index", -1);
                JSONObject b = root.optJSONObject("content_block");
                if (b == null) return out;
                if ("text".equals(b.optString("type")) && b.optString("text", "").length() > 0) add(out, OpenAiShapes.textDelta(b.optString("text")));
                else if ("tool_use".equals(b.optString("type"))) {
                    int ti = toolIndex.size();
                    toolIndex.put(idx, ti);
                    JSONObject fn = new JSONObject().put("name", b.optString("name")).put("arguments", "");
                    JSONObject tc = new JSONObject().put("index", ti).put("id", b.optString("id")).put("type", "function").put("function", fn);
                    add(out, OpenAiShapes.deltaLine(new JSONObject().put("tool_calls", new JSONArray().put(tc)), null));
                }
            } else if ("content_block_delta".equals(type)) {
                JSONObject d = root.optJSONObject("delta");
                if (d == null) return out;
                String dt = d.optString("type");
                if ("text_delta".equals(dt)) add(out, OpenAiShapes.textDelta(d.optString("text", "")));
                else if ("thinking_delta".equals(dt)) add(out, OpenAiShapes.reasoningDelta(d.optString("thinking", "")));
                else if ("input_json_delta".equals(dt)) {
                    Integer ti = toolIndex.get(root.optInt("index", -1));
                    if (ti != null) {
                        JSONObject tc = new JSONObject().put("index", ti.intValue())
                                .put("function", new JSONObject().put("arguments", d.optString("partial_json", "")));
                        add(out, OpenAiShapes.deltaLine(new JSONObject().put("tool_calls", new JSONArray().put(tc)), null));
                    }
                }
            } else if ("message_delta".equals(type)) {
                JSONObject d = root.optJSONObject("delta");
                if (d != null && d.has("stop_reason") && !d.isNull("stop_reason")) {
                    finished = true;
                    add(out, OpenAiShapes.deltaLine(new JSONObject(), finishOf(d.optString("stop_reason"))));
                }
            } else if ("message_stop".equals(type)) {
                done = true;
                out.add(OpenAiShapes.DONE);
            } else if ("error".equals(type)) {
                JSONObject e = root.optJSONObject("error");
                throw new Exception("Anthropic 流式报错：" + (e == null ? "" : e.optString("type") + " " + e.optString("message")));
            }
            return out;
        }

        @Override public List<String> finish() {
            if (done) return Collections.emptyList();
            if (!finished) return Collections.emptyList();   // 既没有 stop_reason 也没有 message_stop = 中途断开，不假装说完
            return Collections.singletonList(OpenAiShapes.DONE);
        }

        @Override public boolean done() {
            return done;
        }

        private static void add(List<String> out, String l) {
            if (l != null) out.add(l);
        }
    }

    @Override public WireRequest check(String base, String model) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/json");
        h.put("anthropic-version", VERSION);
        return new WireRequest("GET", base + "/v1/models", h, "x-api-key", "", null, false);
    }

    @Override public ProviderErrors.Verdict checkVerdict(int status, String body, String model) {
        if (status < 200 || status > 299) return ProviderErrors.of(ProviderKind.ANTHROPIC, status, body, model);
        return OpenAiCompatAdapter.verdictForList(status, OpenAiCompatAdapter.modelIds(body), model);
    }
}
