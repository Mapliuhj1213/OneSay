package com.onesay.assistant.v2.diag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * [diag] 未知症状的 LLM 兜底（纯函数部分：提示词 + 结果解析）。
 *
 * <p>LLM <b>只能</b>从可读白名单里挑要读的键（最多 {@link #MAX_KEYS} 个）；返回里不在白名单的 id 一律丢弃。
 * 它拿不到任何写能力，也不给工具；修复芯片仍只来自知识表规则，点了也照常过 ExecutionGate。
 */
public final class DiagLlm {

    private DiagLlm() {}

    public static final int MAX_KEYS = 6;

    public static String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是手机设置诊断助手。用户描述了一个手机问题。请从下面的【只读设置清单】里挑出最可能相关的 1 到 ")
                .append(MAX_KEYS).append(" 项，只输出 JSON，不要解释：{\"keys\":[\"id1\",\"id2\"]}\n")
                .append("只能用清单里的 id；和设置无关的问题输出 {\"keys\":[]}。\n【只读设置清单】\n");
        for (DiagKnowledge.Key k : DiagKnowledge.KEYS.values()) {
            sb.append(k.id).append(" ｜ ").append(k.label).append('\n');
        }
        return sb.toString();
    }

    /** 解析 LLM 的选择：只保留白名单 id，去重，最多 MAX_KEYS 个；不合法返回空列表。 */
    public static List<String> parseKeys(String content) {
        List<String> out = new ArrayList<String>();
        if (content == null) return out;
        int a = content.indexOf('{');
        int b = content.lastIndexOf('}');
        if (a < 0 || b <= a) return out;
        try {
            JSONObject o = new JSONObject(content.substring(a, b + 1));
            JSONArray arr = o.optJSONArray("keys");
            if (arr == null) return out;
            Set<String> seen = new LinkedHashSet<String>();
            for (int i = 0; i < arr.length() && seen.size() < MAX_KEYS; i++) {
                String id = arr.optString(i, "").trim();
                if (DiagKnowledge.isReadable(id)) seen.add(id);
            }
            out.addAll(seen);
        } catch (Throwable t) {
            return new ArrayList<String>();
        }
        return out;
    }
}
