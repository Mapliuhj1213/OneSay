package com.onesay.assistant.v2.conn;

import org.json.JSONObject;

/**
 * [conn] 连接器暴露给 AgentLoop 的一个工具（已过白名单）。
 * 签名 {@code conn.<连接器id>.<工具名>}；wire 名由 CapabilityRegistry.signToToolName 把点换成双下划线。
 */
public final class ConnTool {

    public final String connectorId;
    /** 连接器内部的工具名（MCP 远端原名 / Google 本地名）。 */
    public final String name;
    public final String sign;
    public final String description;
    /** JSON Schema（object）；可为空对象。 */
    public final JSONObject inputSchema;
    public final ToolEffect effect;

    public ConnTool(String connectorId, String name, String description, JSONObject inputSchema, ToolEffect effect) {
        this.connectorId = connectorId;
        this.name = name;
        this.sign = signOf(connectorId, name);
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema != null ? inputSchema : emptySchema();
        this.effect = effect;
    }

    public static final String PREFIX = "conn.";

    /** 签名：每段只留 [A-Za-z0-9_-]，连续下划线压成一个（双下划线是 wire 上的点号替身），总长 ≤ 60。 */
    public static String signOf(String connectorId, String name) {
        String s = PREFIX + seg(connectorId) + "." + seg(name);
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    static String seg(String s) {
        if (s == null || s.length() == 0) return "x";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            b.append(ok ? c : '_');
        }
        String out = b.toString();
        while (out.contains("__")) out = out.replace("__", "_");
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        return out.length() == 0 ? "x" : out;
    }

    public static JSONObject emptySchema() {
        try {
            return new JSONObject().put("type", "object").put("properties", new JSONObject());
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
