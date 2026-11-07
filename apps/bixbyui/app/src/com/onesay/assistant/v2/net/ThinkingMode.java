package com.onesay.assistant.v2.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * ★ int/1102：用户可见的「思考模式」三档单选（设置 › 语音 › 思考模式；默认「关」）。
 *
 * <h3>档位</h3>
 * <ul>
 *   <li>{@link Level#OFF}「关」：所有请求照旧显式 {@code thinking: disabled}（与 9cb4dca 行为逐字节一致）；</li>
 *   <li>{@link Level#AGENT}「仅多步任务」：只有 AgentLoop（多步任务）开思考；</li>
 *   <li>{@link Level#ALL}「全部」：AgentLoop + 自由问答（流式 / 非流式）开思考。</li>
 * </ul>
 * 分诊分类、IntentGate、摘要、诊断、soul flush 等内部短调用<b>任何档位都不开</b>（它们只要一个标签 / 一小段 JSON，开思考只会多等）。
 *
 * <h3>官方 API（api-docs.deepseek.com，2026-11-02 核对）</h3>
 * <ul>
 *   <li>开关：OpenAI 格式请求体 {@code "thinking": {"type": "enabled" | "disabled"}}（Thinking Mode 页）；不给时默认开、effort=high；</li>
 *   <li>思考模式下 {@code temperature} / {@code presence_penalty} / {@code frequency_penalty} 不生效（不报错）；deepseek-flash 的 {@code top_p}
 *       只在思考模式生效且 &lt;0.95 按 0.95 —— 这里统一剔掉，用官方默认；</li>
 *   <li>{@code tool_choice} 为 {@code required} 或指定函数在思考模式下返回 400（Create Chat Completion 页）→ 改成 {@code auto}；</li>
 *   <li>{@code max_tokens} = 本次「生成」上限，思维链也算生成（usage.completion_tokens_details.reasoning_tokens；不设时思考模式默认 64K、
 *       非思考 8K）→ 开思考时在原预算上加 {@link #THINK_EXTRA_TOKENS} 给思维链，免得答案被截；</li>
 *   <li>思维链在 {@code reasoning_content}（与 {@code content} 同级；流式在 {@code delta.reasoning_content}）。本应用只取 {@code content}
 *       上屏 / 朗读 / 进会话历史（{@link LlmResponse#deltaOf}、{@link LlmResponse#contentOf} 本来就只读 content）；</li>
 *   <li>带 tools 的请求：此前各轮 assistant 的 {@code reasoning_content} 要原样回传，否则 400 —— AgentLoop 用 {@link #carryReasoning}
 *       把本任务内各轮的思维链带回；会话历史里的旧轮（非思考生成、没有思维链）在 {@link #apply} 里补空串。</li>
 * </ul>
 */
public final class ThinkingMode {

    private ThinkingMode() {}

    public enum Level { OFF, AGENT, ALL }

    /** 请求用途。 */
    public enum Purpose { ASK, AGENT, INTERNAL }

    /** 开思考时给思维链额外留的生成预算（token）。 */
    public static final int THINK_EXTRA_TOKENS = 8192;
    /** 官方 max_tokens 上限 384K。 */
    public static final int MAX_TOKENS_CAP = 393216;

    private static volatile Level sLevel = Level.OFF;

    public static Level level() {
        return sLevel;
    }

    /** off / agent / all（以及中文「关 / 仅多步任务 / 全部」）；别的值 = 关。@return 生效的档位 */
    public static Level setLevel(String v) {
        Level l = parse(v);
        sLevel = l;
        return l;
    }

    public static Level parse(String v) {
        String s = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        if (s.equals("agent") || s.equals("仅多步任务") || s.equals("多步") || s.equals("loop")) return Level.AGENT;
        if (s.equals("all") || s.equals("全部") || s.equals("on")) return Level.ALL;
        return Level.OFF;
    }

    public static String key(Level l) {
        return l == Level.AGENT ? "agent" : l == Level.ALL ? "all" : "off";
    }

    public static String label(Level l) {
        return l == Level.AGENT ? "仅多步任务" : l == Level.ALL ? "全部" : "关";
    }

    /** 这档这个用途开不开思考。 */
    public static boolean enabledFor(Level l, Purpose p) {
        if (l == null || p == null || p == Purpose.INTERNAL) return false;
        if (l == Level.ALL) return true;
        return l == Level.AGENT && p == Purpose.AGENT;
    }

    public static boolean enabledFor(Purpose p) {
        return enabledFor(sLevel, p);
    }

    /** 按当前档位整理请求体（在 {@link LlmModels#prepare} 之前调）。关 = 原样返回（之后 prepare 补 disabled）。 */
    public static JSONObject apply(JSONObject req, Purpose p) {
        return apply(req, p, sLevel);
    }

    public static JSONObject apply(JSONObject req, Purpose p, Level l) {
        if (req == null || !enabledFor(l, p)) return req;
        try {
            String model = LlmModels.resolve(req.optString("model", ""));
            if (!LlmModels.thinkingByDefault(model)) return req;   // 非 DeepSeek V4 系（qwen 等）不动
            JSONObject t = new JSONObject();
            t.put("type", "enabled");
            req.put("thinking", t);
            req.remove("temperature");
            req.remove("top_p");
            req.remove("presence_penalty");
            req.remove("frequency_penalty");
            Object tc = req.opt("tool_choice");
            if (tc instanceof JSONObject || "required".equals(tc)) req.put("tool_choice", "auto");
            int mt = req.optInt("max_tokens", 0);
            if (mt > 0) req.put("max_tokens", Math.min(MAX_TOKENS_CAP, mt + THINK_EXTRA_TOKENS));
            // 带 tools：各轮 assistant 都要有 reasoning_content（没有的旧轮补空串）
            JSONArray msgs = req.optJSONArray("messages");
            if (req.has("tools") && msgs != null) {
                for (int i = 0; i < msgs.length(); i++) {
                    JSONObject m = msgs.optJSONObject(i);
                    if (m != null && "assistant".equals(m.optString("role")) && !m.has("reasoning_content")) m.put("reasoning_content", "");
                }
            }
        } catch (Exception ignored) {
        }
        return req;
    }

    /** AgentLoop：把模型这一轮回的思维链带进回填的 assistant 消息（思考模式 + tools 必须回传；没有就不加）。 */
    public static void carryReasoning(JSONObject from, JSONObject toAssistant) {
        if (from == null || toAssistant == null) return;
        try {
            Object rc = from.opt("reasoning_content");
            if (rc instanceof String) toAssistant.put("reasoning_content", rc);
        } catch (Exception ignored) {
        }
    }

    /** 流式一行里的思维链增量（只用于「思考中」状态，不上屏）；没有返回 null。 */
    public static String reasoningDeltaOf(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.startsWith("data:")) s = s.substring(5).trim();
        if (!s.startsWith("{") || s.indexOf("reasoning_content") < 0) return null;
        try {
            JSONObject o = new JSONObject(s);
            JSONArray ch = o.optJSONArray("choices");
            if (ch == null || ch.length() == 0) return null;
            JSONObject c0 = ch.optJSONObject(0);
            JSONObject d = c0 == null ? null : c0.optJSONObject("delta");
            if (d == null) return null;
            Object rc = d.opt("reasoning_content");
            return rc instanceof String ? (String) rc : null;
        } catch (Exception e) {
            return null;
        }
    }
}
