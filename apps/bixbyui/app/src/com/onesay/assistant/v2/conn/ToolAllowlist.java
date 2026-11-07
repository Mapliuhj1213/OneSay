package com.onesay.assistant.v2.conn;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [conn] 能力表白名单：工具名 → 副作用等级。<b>不在表里的远程工具一律不暴露</b>（默认拒绝）。
 *
 * <p>远端的 annotations（readOnlyHint / destructiveHint）只能<b>升级</b>等级，不能降级：
 * 白名单写 READ 但远端声明 destructiveHint=true → DESTRUCTIVE；readOnlyHint=false → 至少 WRITE。
 * 白名单写 WRITE 而远端说 readOnlyHint=true → 仍按 WRITE（不信远端降级）。
 *
 * <p>JSON 形如 {@code {"search_docs":"read","create_issue":"write","*":"deny"}}；值大小写不敏感。
 */
public final class ToolAllowlist {

    private final Map<String, ToolEffect> mMap = new LinkedHashMap<String, ToolEffect>();

    public ToolAllowlist() {}

    public static ToolAllowlist parse(JSONObject o) {
        ToolAllowlist a = new ToolAllowlist();
        if (o == null) return a;
        Iterator<?> it = o.keys();
        while (it.hasNext()) {
            String k = String.valueOf(it.next());
            ToolEffect e = ToolEffect.parse(o.optString(k));
            if (e != null && k.length() > 0 && !"*".equals(k)) a.mMap.put(k, e);
        }
        return a;
    }

    public ToolAllowlist allow(String tool, ToolEffect e) {
        if (tool != null && e != null) mMap.put(tool, e);
        return this;
    }

    public boolean isEmpty() {
        return mMap.isEmpty();
    }

    public int size() {
        return mMap.size();
    }

    /**
     * @param annotations 远端 tool.annotations（可为 null）
     * @return 生效等级；null = 不放行
     */
    public ToolEffect effectFor(String tool, JSONObject annotations) {
        return effectFor(tool, annotations, null);
    }

    /**
     * 带 inputSchema 的版本 —— 名字（{@link com.onesay.assistant.v2.security.OutboundJudge} 统一判据，
     * 含 dispatch / deliver / emit / relay / submit / enqueue / push、全大写、同形字）或 schema 结构（to / recipient / channel /
     * subject+body …）像对外发送，就至少按 COMMS。
     */
    public ToolEffect effectFor(String tool, JSONObject annotations, JSONObject inputSchema) {
        ToolEffect base = tool == null ? null : mMap.get(tool);
        if (base == null) return null;
        if (com.onesay.assistant.v2.security.OutboundJudge.schemaLooksOutbound(inputSchema)) {
            base = ToolEffect.stricter(base, ToolEffect.COMMS);
        }
        // 2026-10-24 r2/fixes：发信 / 发消息类工具至少按 COMMS（对外发送 → 每次单独确认，不给「始终允许」），
        // 不管白名单里写的是 read / write。名词类（mail / message）只在白名单本身已标有副作用时才升级，免得把「读邮件」变成要确认。
        if (looksLikeSendVerb(tool) || (base != ToolEffect.READ && looksLikeCommsNoun(tool))) {
            base = ToolEffect.stricter(base, ToolEffect.COMMS);
        }
        if (annotations != null) {
            if (annotations.optBoolean("destructiveHint", false)) base = ToolEffect.stricter(base, ToolEffect.DESTRUCTIVE);
            if (annotations.has("readOnlyHint") && !annotations.optBoolean("readOnlyHint", true)) {
                base = ToolEffect.stricter(base, ToolEffect.WRITE);
            }
        }
        return base;
    }

    /** 工具名像「对外发送」动词（send_email、reply、gmail_send、postMessage、dispatch_mail、SEND_EMAIL、ѕend_email…）。LOGIC-5：统一走 OutboundJudge。 */
    static boolean looksLikeSendVerb(String tool) {
        return com.onesay.assistant.v2.security.OutboundJudge.looksLikeSendVerb(tool);
    }

    /** 工具名带通信类名词（mail / message / sms…）。LOGIC-5：统一走 OutboundJudge。 */
    static boolean looksLikeCommsNoun(String tool) {
        return com.onesay.assistant.v2.security.OutboundJudge.looksLikeCommsNoun(tool);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ToolEffect> e : mMap.entrySet()) {
            try {
                o.put(e.getKey(), e.getValue().name().toLowerCase(java.util.Locale.ROOT));
            } catch (Exception ignored) {
            }
        }
        return o;
    }
}
