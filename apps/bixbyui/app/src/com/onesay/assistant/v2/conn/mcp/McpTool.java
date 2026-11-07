package com.onesay.assistant.v2.conn.mcp;

import org.json.JSONObject;

/** [conn/mcp] tools/list 里的一个工具（远端原样，未过白名单）。 */
public final class McpTool {

    public final String name;
    public final String description;
    public final JSONObject inputSchema;
    /** 远端自报的 annotations（readOnlyHint / destructiveHint …），只用来<b>升级</b>风险。 */
    public final JSONObject annotations;

    public McpTool(String name, String description, JSONObject inputSchema, JSONObject annotations) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema;
        this.annotations = annotations;
    }

    public static McpTool fromJson(JSONObject o) {
        if (o == null) return null;
        String n = o.optString("name", "");
        if (n.length() == 0) return null;
        String d = o.optString("description", "");
        if (d.length() == 0) d = o.optString("title", "");
        return new McpTool(n, d, o.optJSONObject("inputSchema"), o.optJSONObject("annotations"));
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name).put("description", description);
            if (inputSchema != null) o.put("inputSchema", inputSchema);
            if (annotations != null) o.put("annotations", annotations);
        } catch (Exception ignored) {
        }
        return o;
    }
}
