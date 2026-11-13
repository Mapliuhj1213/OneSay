package com.onesay.assistant.v2.net;

import org.json.JSONObject;

import java.util.Locale;

/**
 * ★ int/1102：对话模型名的<b>唯一</b>出处（原先 13 处各写一遍 {@code "deepseek-chat"}）。
 *
 * <h3>为什么迁移</h3>
 * DeepSeek 官方更新日志（api-docs.deepseek.com/updates，2026-04-24 条）：旧模型名 {@code deepseek-chat} / {@code deepseek-reasoner}
 * 于 2026-07-24 停用；现行模型与价格页（2026-11-02 核对）只列 {@code deepseek-flash}（DeepSeek-V4.1-Flash-1017）与
 * {@code deepseek-v4-pro}，二者 1M 上下文、<b>默认开思考模式</b>。旧 {@code deepseek-chat} 指的是非思考模式，所以迁移时必须
 * 显式带 {@code "thinking": {"type": "disabled"}}（官方 Thinking Mode 页的 OpenAI 格式开关），否则延迟和输出 token 都会涨。
 *
 * <h3>可配</h3>
 * {@link #chat()} = 当前对话模型（缺省 {@link #DEEPSEEK_DEFAULT}）；prefs {@code bixby_conv/chat_model} 或 debug 广播
 * {@code --es chat_model <名>} 改（{@code ContextPrefs}）。调用方继续传 {@link #DEEPSEEK_DEFAULT}（或旧名），
 * {@link LlmClient#body} 经 {@link #resolve} 换成当前配置并补思考开关；直接拼请求体的地方调 {@link #prepare}。
 */
public final class LlmModels {

    private LlmModels() {}

    /** 官方现行对话模型（非思考模式使用）。 */
    public static final String DEEPSEEK_DEFAULT = "deepseek-flash";
    /** 已停用的旧名（只用于识别 / 兼容老配置，不再发出去）。 */
    public static final String LEGACY_CHAT = "deepseek-chat";
    public static final String LEGACY_REASONER = "deepseek-reasoner";

    private static volatile String sChat = DEEPSEEK_DEFAULT;

    /** 当前对话模型名。 */
    public static String chat() {
        return sChat;
    }

    /** 设对话模型（非法 / 空 / 旧名 → 回到缺省）。@return 生效的模型名 */
    public static String setChat(String model) {
        String raw = model == null ? "" : model.trim();
        String m = raw.toLowerCase(Locale.ROOT);
        if (m.equals(LEGACY_CHAT) || m.equals(LEGACY_REASONER)) m = DEEPSEEK_DEFAULT;
        else if (m.matches("[a-z0-9][a-z0-9.\\-]{2,63}")) { /* DeepSeek 式名字：统一小写（原规则） */ }
        // ★ console-align：主模型地址可改成别家 OpenAI 兼容接口后，模型名可能带大写 / 斜杠（如 Qwen/Qwen2.5-7B-Instruct），原样保留
        else if (raw.length() >= 2 && MainModelEndpoint.checkModel(raw) == null) m = raw;
        else m = DEEPSEEK_DEFAULT;
        sChat = m;
        return m;
    }

    /** 调用方传入的模型名 → 实际发送的名：缺省名 / 旧名一律换成当前配置；别的（qwen 等）原样。 */
    public static String resolve(String requested) {
        if (requested == null || requested.length() == 0) return sChat;
        String r = requested.trim().toLowerCase(Locale.ROOT);
        if (r.equals(DEEPSEEK_DEFAULT) || r.equals(LEGACY_CHAT)) return sChat;
        return requested;
    }

    /** 这个模型默认开思考、需要显式关掉（DeepSeek V4 / V4.1 系）。 */
    public static boolean thinkingByDefault(String model) {
        if (model == null) return false;
        String m = model.toLowerCase(Locale.ROOT);
        return m.equals("deepseek-flash") || m.startsWith("deepseek-v4");
    }

    /** 字符串版（{@link LlmClient} 建连前统一过一遍；不是 JSON 对象就原样返回）。 */
    public static String prepareJson(String body) {
        if (body == null) return null;
        String t = body.trim();
        if (!t.startsWith("{") || t.indexOf("\"model\"") < 0) return body;
        try {
            JSONObject o = new JSONObject(t);
            String before = o.optString("model", "");
            boolean had = o.has("thinking");
            prepare(o);
            if (before.equals(o.optString("model", "")) && had == o.has("thinking")) return body;   // 没变就不重排序列化
            return o.toString();
        } catch (Exception e) {
            return body;
        }
    }

    /** 把请求体整理成可发送的样子：模型名经 {@link #resolve}，DeepSeek V4 系补 {@code thinking: disabled}（已显式给出的不动）。 */
    public static JSONObject prepare(JSONObject req) {
        if (req == null) return null;
        try {
            String m = resolve(req.optString("model", ""));
            req.put("model", m);
            if (thinkingByDefault(m) && !req.has("thinking")) {
                JSONObject t = new JSONObject();
                t.put("type", "disabled");
                req.put("thinking", t);
            }
        } catch (Exception ignored) {
        }
        return req;
    }
}
