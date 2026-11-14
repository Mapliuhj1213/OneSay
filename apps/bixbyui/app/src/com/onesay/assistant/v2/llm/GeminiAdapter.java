/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/model/GeminiModel.kt: request/buildContents/userParts/modelParts/
 * sanitize/toolJson/parse/GeminiStream) to Java 7 + org.json. Input is an OpenAI-format request body; output is
 * translated back to OpenAI chat.completion / chunk shape. Thinking-level control is not carried over. Upstream: 8d61573.
 */
package com.onesay.assistant.v2.llm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini generateContent 原生接口。
 * <ul>
 *   <li>角色只有 user / model，相邻同角色合并；system 消息并入 {@code systemInstruction}；</li>
 *   <li>Gemini 不给调用编号：回复里没 id 时合成 {@code gemini-<序号>-<名称>}；回传 {@code functionResponse} 时按 tool_call_id
 *       到前面助手消息的 tool_calls 里找函数名；</li>
 *   <li>工具 schema 去掉 Gemini 不收的 {@code additionalProperties / $schema / $id / $defs}；无参数工具省略 parameters；</li>
 *   <li>重放不是 Gemini 自己产生的工具调用时，每轮第一个调用带官方的跳过签名占位（{@link #SKIP_SIGNATURE}）；</li>
 *   <li>Key 放 {@code x-goog-api-key} 头，绝不放进 URL。</li>
 * </ul>
 */
public final class GeminiAdapter implements ProviderAdapter {

    static final String SKIP_SIGNATURE = "skip_thought_signature_validator";
    private static final String[] REJECTED_KEYS = {"additionalProperties", "$schema", "$id", "$defs"};

    @Override public ProviderKind kind() {
        return ProviderKind.GEMINI;
    }

    static String modelPath(String model) {
        String m = model == null ? "" : model.trim();
        if (m.startsWith("models/")) m = m.substring(7);
        return m;
    }

    @Override public WireRequest chat(String base, String model, JSONObject in, boolean stream) throws Exception {
        String m = modelPath(model != null && model.length() > 0 ? model : in.optString("model", ""));
        JSONObject out = new JSONObject();
        JSONArray msgs = in.optJSONArray("messages");
        Map<String, String> nameById = new HashMap<String, String>();
        if (msgs != null) {
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject mm = msgs.optJSONObject(i);
                JSONArray calls = mm == null ? null : mm.optJSONArray("tool_calls");
                if (calls == null) continue;
                for (int j = 0; j < calls.length(); j++) {
                    JSONObject c = calls.optJSONObject(j);
                    JSONObject fn = c == null ? null : c.optJSONObject("function");
                    if (fn != null) nameById.put(c.optString("id"), fn.optString("name"));
                }
            }
        }
        StringBuilder sys = new StringBuilder();
        List<Object[]> merged = new ArrayList<Object[]>();
        if (msgs != null) {
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject mm = msgs.optJSONObject(i);
                if (mm == null) continue;
                String role = mm.optString("role", "user");
                if ("system".equals(role) || "developer".equals(role)) {
                    String t = OpenAiShapes.textOf(mm.opt("content"));
                    if (t.trim().length() > 0) { if (sys.length() > 0) sys.append("\n\n"); sys.append(t); }
                    continue;
                }
                String r;
                List<JSONObject> parts;
                if ("assistant".equals(role)) { r = "model"; parts = modelParts(mm); }
                else if ("tool".equals(role)) { r = "user"; parts = Collections.singletonList(functionResponse(mm, nameById)); }
                else { r = "user"; parts = userParts(mm.opt("content")); }
                if (parts.isEmpty()) continue;
                Object[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && last[0].equals(r)) {
                    @SuppressWarnings("unchecked") List<JSONObject> lp = (List<JSONObject>) last[1];
                    lp.addAll(parts);
                } else {
                    merged.add(new Object[]{r, new ArrayList<JSONObject>(parts)});
                }
            }
        }
        if (sys.length() > 0) out.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys.toString()))));
        JSONArray tools = in.optJSONArray("tools");
        if (tools != null && tools.length() > 0 && !"none".equals(in.opt("tool_choice"))) {
            JSONArray decl = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject fn = tools.optJSONObject(i) == null ? null : tools.optJSONObject(i).optJSONObject("function");
                if (fn == null) continue;
                JSONObject d = new JSONObject().put("name", fn.optString("name")).put("description", fn.optString("description", ""));
                JSONObject schema = fn.optJSONObject("parameters");
                if (schema != null) {
                    JSONObject clean = (JSONObject) sanitize(schema);
                    JSONObject props = clean.optJSONObject("properties");
                    if (props != null && props.length() > 0) d.put("parameters", clean);   // Gemini 拒绝没有 properties 的 object schema
                }
                decl.put(d);
            }
            out.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", decl)));
        }
        JSONArray contents = new JSONArray();
        for (Object[] e : merged) {
            @SuppressWarnings("unchecked") List<JSONObject> parts = (List<JSONObject>) e[1];
            if ("user".equals(e[0])) parts = responsesFirst(parts);
            JSONArray pa = new JSONArray();
            for (JSONObject p : parts) pa.put(p);
            contents.put(new JSONObject().put("role", e[0]).put("parts", pa));
        }
        out.put("contents", contents);
        JSONObject gc = new JSONObject();
        if (in.has("temperature")) gc.put("temperature", in.optDouble("temperature"));
        if (in.has("top_p")) gc.put("topP", in.optDouble("top_p"));
        int max = in.optInt("max_tokens", in.optInt("max_completion_tokens", 0));
        if (max > 0) gc.put("maxOutputTokens", max);
        Object stop = in.opt("stop");
        if (stop instanceof String) gc.put("stopSequences", new JSONArray().put(stop));
        else if (stop instanceof JSONArray) gc.put("stopSequences", stop);
        JSONObject rf = in.optJSONObject("response_format");
        if (rf != null && "json_object".equals(rf.optString("type"))) gc.put("responseMimeType", "application/json");
        if (gc.length() > 0) out.put("generationConfig", gc);
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Content-Type", "application/json; charset=UTF-8");
        h.put("Accept", stream ? "text/event-stream" : "application/json");
        String url = base + "/v1beta/models/" + m + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent");
        return new WireRequest("POST", url, h, "x-goog-api-key", "", out.toString(), stream);
    }

    static Object sanitize(Object el) throws Exception {
        if (el instanceof JSONObject) {
            JSONObject o = (JSONObject) el, r = new JSONObject();
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                boolean drop = false;
                for (String rk : REJECTED_KEYS) if (rk.equals(k)) drop = true;
                if (drop) continue;
                Object v = o.get(k);
                if ("properties".equals(k) && v instanceof JSONObject) {   // 名称 → schema 的映射：名称本身不动
                    JSONObject props = (JSONObject) v, np = new JSONObject();
                    Iterator<String> pi = props.keys();
                    while (pi.hasNext()) { String pk = pi.next(); np.put(pk, sanitize(props.get(pk))); }
                    r.put(k, np);
                } else {
                    r.put(k, sanitize(v));
                }
            }
            return r;
        }
        if (el instanceof JSONArray) {
            JSONArray a = (JSONArray) el, r = new JSONArray();
            for (int i = 0; i < a.length(); i++) r.put(sanitize(a.get(i)));
            return r;
        }
        return el;
    }

    private static List<JSONObject> userParts(Object content) throws Exception {
        List<JSONObject> out = new ArrayList<JSONObject>();
        if (content instanceof JSONArray) {
            JSONArray a = (JSONArray) content;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p == null) continue;
                if ("text".equals(p.optString("type"))) {
                    String t = p.optString("text", "");
                    if (t.length() > 0) out.add(new JSONObject().put("text", t));
                } else if ("image_url".equals(p.optString("type"))) {
                    JSONObject iu = p.optJSONObject("image_url");
                    String url = iu != null ? iu.optString("url", "") : p.optString("image_url", "");
                    String[] d = OpenAiShapes.dataUrl(url);
                    if (d != null) out.add(new JSONObject().put("inlineData", new JSONObject().put("mimeType", d[0]).put("data", d[1])));
                    else out.add(new JSONObject().put("text", "[图片链接：" + url + "]"));
                }
            }
        } else {
            String t = OpenAiShapes.textOf(content);
            if (t.length() > 0) out.add(new JSONObject().put("text", t));
        }
        return out;
    }

    private static List<JSONObject> modelParts(JSONObject m) throws Exception {
        List<JSONObject> out = new ArrayList<JSONObject>();
        String t = OpenAiShapes.textOf(m.opt("content"));
        if (t.length() > 0) out.add(new JSONObject().put("text", t));
        JSONArray calls = m.optJSONArray("tool_calls");
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject c = calls.optJSONObject(i);
                JSONObject fn = c == null ? null : c.optJSONObject("function");
                if (fn == null) continue;
                JSONObject args;
                try { args = new JSONObject(fn.optString("arguments", "{}")); } catch (Exception e) { args = new JSONObject(); }
                JSONObject part = new JSONObject();
                // 调用不是本适配器从 Gemini 收到的（没有原签名）：每轮第一个调用带跳过校验的占位签名
                if (i == 0) part.put("thoughtSignature", SKIP_SIGNATURE);
                part.put("functionCall", new JSONObject().put("name", fn.optString("name")).put("args", args));
                out.add(part);
            }
        }
        return out;
    }

    private static JSONObject functionResponse(JSONObject m, Map<String, String> nameById) throws Exception {
        String id = m.optString("tool_call_id", "");
        String name = nameById.containsKey(id) ? nameById.get(id) : (m.optString("name", "").length() > 0 ? m.optString("name") : id);
        JSONObject resp = new JSONObject().put("content", OpenAiShapes.textOf(m.opt("content")));
        return new JSONObject().put("functionResponse", new JSONObject().put("name", name).put("response", resp));
    }

    private static List<JSONObject> responsesFirst(List<JSONObject> parts) {
        List<JSONObject> res = new ArrayList<JSONObject>(), rest = new ArrayList<JSONObject>();
        for (JSONObject p : parts) (p.has("functionResponse") ? res : rest).add(p);
        res.addAll(rest);
        return res;
    }

    static String finishOf(String fr, boolean hasCalls) {
        if (fr == null || fr.length() == 0 || "STOP".equals(fr)) return hasCalls ? "tool_calls" : "stop";
        if ("MAX_TOKENS".equals(fr)) return "length";
        if ("SAFETY".equals(fr) || "RECITATION".equals(fr) || "PROHIBITED_CONTENT".equals(fr) || "BLOCKLIST".equals(fr) || "SPII".equals(fr))
            return "content_filter";
        return hasCalls ? "tool_calls" : "stop";
    }

    /** 一份（或一帧）回复里的文字 / 思考 / 调用。 */
    static final class Chunk {
        final StringBuilder text = new StringBuilder(), thinking = new StringBuilder();
        final List<String[]> calls = new ArrayList<String[]>();   // {id 或 null, name, argsJson}
        String finish;
        int promptTokens = -1, completionTokens;
    }

    static Chunk parseChunk(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject pf = root.optJSONObject("promptFeedback");
        if (pf != null && pf.optString("blockReason", "").length() > 0) throw new Exception("Gemini 拒绝处理该内容（" + pf.optString("blockReason") + "）");
        Chunk ch = new Chunk();
        JSONObject u = root.optJSONObject("usageMetadata");
        if (u != null && u.has("promptTokenCount")) {
            ch.promptTokens = u.optInt("promptTokenCount");
            ch.completionTokens = u.optInt("candidatesTokenCount") + u.optInt("thoughtsTokenCount");
        }
        JSONArray cands = root.optJSONArray("candidates");
        JSONObject cand = cands == null ? null : cands.optJSONObject(0);
        if (cand == null) return ch;
        ch.finish = cand.optString("finishReason", null);
        JSONObject content = cand.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p == null) continue;
                JSONObject fc = p.optJSONObject("functionCall");
                if (fc != null) {
                    JSONObject args = fc.optJSONObject("args");
                    ch.calls.add(new String[]{fc.has("id") ? fc.optString("id") : null, fc.optString("name"), args == null ? "{}" : args.toString()});
                    continue;
                }
                String t = p.optString("text", "");
                if (t.length() == 0) continue;
                if (p.optBoolean("thought", false)) ch.thinking.append(t);
                else ch.text.append(t);
            }
        }
        return ch;
    }

    @Override public JSONObject toOpenAi(String body, String model) throws Exception {
        JSONObject root = new JSONObject(body);
        if (root.optJSONArray("candidates") == null && root.optJSONObject("promptFeedback") == null) throw new Exception("Gemini 回复缺少 candidates");
        Chunk ch = parseChunk(body);
        JSONArray calls = new JSONArray();
        for (int i = 0; i < ch.calls.size(); i++) {
            String[] c = ch.calls.get(i);
            calls.put(OpenAiShapes.toolCall(c[0] != null ? c[0] : "gemini-" + i + "-" + c[1], c[1], c[2]));
        }
        String finish = finishOf(ch.finish, calls.length() > 0);
        if ("content_filter".equals(finish) && ch.text.length() == 0 && calls.length() == 0) throw new Exception("Gemini 拒绝处理该内容（" + ch.finish + "）");
        return OpenAiShapes.completion(root.optString("modelVersion", model), ch.text.toString(), ch.thinking.toString(), calls,
                finish, ch.promptTokens, ch.completionTokens);
    }

    @Override public StreamTranslator newStream(String model) {
        return new Stream();
    }

    /** Gemini SSE（每帧一份 GenerateContentResponse）→ OpenAI 流式行。带 finishReason 的那一帧之后补 [DONE]。 */
    static final class Stream implements StreamTranslator {
        private int callCount;
        private boolean finished;

        @Override public List<String> onLine(String line) throws Exception {
            List<String> out = new ArrayList<String>();
            if (line == null) return out;
            String s = line.trim();
            if (!s.startsWith("data:")) return out;
            s = s.substring(5).trim();
            if (s.length() == 0) return out;
            Chunk ch = parseChunk(s);
            if (ch.thinking.length() > 0) add(out, OpenAiShapes.reasoningDelta(ch.thinking.toString()));
            if (ch.text.length() > 0) add(out, OpenAiShapes.textDelta(ch.text.toString()));
            for (String[] c : ch.calls) {
                int idx = callCount++;
                JSONObject fn = new JSONObject().put("name", c[1]).put("arguments", c[2]);
                JSONObject tc = new JSONObject().put("index", idx).put("id", c[0] != null ? c[0] : "gemini-" + idx + "-" + c[1])
                        .put("type", "function").put("function", fn);
                add(out, OpenAiShapes.deltaLine(new JSONObject().put("tool_calls", new JSONArray().put(tc)), null));
            }
            if (ch.finish != null && ch.finish.length() > 0 && !finished) {
                finished = true;
                add(out, OpenAiShapes.deltaLine(new JSONObject(), finishOf(ch.finish, callCount > 0)));
            }
            return out;
        }

        @Override public List<String> finish() {
            // Gemini 的流没有单独的结束标记：见过 finishReason 才算说完；没见过 = 连接中途断开，不补 [DONE]
            return finished ? Collections.singletonList(OpenAiShapes.DONE) : Collections.<String>emptyList();
        }

        @Override public boolean done() {
            return false;   // finishReason 之后可能还有一帧只带用量，读到 EOF
        }

        private static void add(List<String> out, String l) {
            if (l != null) out.add(l);
        }
    }

    @Override public WireRequest check(String base, String model) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/json");
        String m = modelPath(model);
        return new WireRequest("GET", base + "/v1beta/models" + (m.length() > 0 ? "/" + m : ""), h, "x-goog-api-key", "", null, false);
    }

    @Override public ProviderErrors.Verdict checkVerdict(int status, String body, String model) {
        if (status < 200 || status > 299) return ProviderErrors.of(ProviderKind.GEMINI, status, body, model);
        String m = modelPath(model);
        if (m.length() > 0) return ProviderErrors.ok(status, "Key 有效，模型「" + m + "」可用");
        return OpenAiCompatAdapter.verdictForList(status, OpenAiCompatAdapter.modelIds(body), model);
    }
}
