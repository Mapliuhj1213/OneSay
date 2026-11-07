package com.onesay.assistant.v2.conn.mcp;

import com.onesay.assistant.v2.conn.ToolAllowlist;

import org.json.JSONObject;

/**
 * [conn/mcp] 一个 MCP 服务器的配置（<b>不含密钥</b>；令牌在 SecretStore 的 {@link #secretKey()} 下）。
 *
 * <pre>
 * {"id":"docs","title":"文档检索","url":"https://example.com/mcp","transport":"auto",
 *  "auth_header":"Authorization","auth_scheme":"Bearer","enabled":true,
 *  "allow":{"search":"read","create_page":"write"}}
 * </pre>
 * transport：auto（先 Streamable HTTP，收到 400/404/405 再退旧版 SSE，按规范的向后兼容建议）/ http / sse。
 * 只允许 https（例外：127.0.0.1 / localhost，方便本机调试）。
 */
public final class McpServerConfig {

    public static final String T_AUTO = "auto", T_HTTP = "http", T_SSE = "sse";

    public final String id;
    public final String title;
    public final String url;
    public final String transport;
    public final String authHeader;
    public final String authScheme;
    public final boolean enabled;
    public final ToolAllowlist allow;

    public McpServerConfig(String id, String title, String url, String transport, String authHeader, String authScheme,
                           boolean enabled, ToolAllowlist allow) {
        this.id = id;
        this.title = title == null || title.length() == 0 ? id : title;
        this.url = url;
        this.transport = transport == null ? T_AUTO : transport;
        this.authHeader = authHeader == null || authHeader.length() == 0 ? "Authorization" : authHeader;
        this.authScheme = authScheme == null ? "Bearer" : authScheme;
        this.enabled = enabled;
        this.allow = allow != null ? allow : new ToolAllowlist();
    }

    public String secretKey() {
        return "mcp." + id + ".token";
    }

    /** 校验：返回 null = 合法，否则是原因。 */
    public String validate() {
        if (id == null || !id.matches("[a-z][a-z0-9_-]{0,23}")) return "id 只能是小写字母开头的 1–24 位 [a-z0-9_-]";
        if (com.onesay.assistant.v2.executor.ConsentPolicy.isBuiltinConnectorId(id)) return "id 与内置连接器重名";
        if (url == null) return "缺 url";
        String u = url.toLowerCase(java.util.Locale.ROOT);
        boolean local = u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost");
        if (!u.startsWith("https://") && !local) return "只允许 https 地址";
        if (!T_AUTO.equals(transport) && !T_HTTP.equals(transport) && !T_SSE.equals(transport)) return "transport 只能是 auto / http / sse";
        if (authHeader.indexOf('\n') >= 0 || authHeader.indexOf(':') >= 0) return "auth_header 非法";
        return null;
    }

    public static McpServerConfig fromJson(JSONObject o) {
        if (o == null) return null;
        return new McpServerConfig(o.optString("id", ""), o.optString("title", ""), o.optString("url", null),
                o.optString("transport", T_AUTO), o.optString("auth_header", "Authorization"), o.optString("auth_scheme", "Bearer"),
                o.optBoolean("enabled", true), ToolAllowlist.parse(o.optJSONObject("allow")));
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("title", title).put("url", url).put("transport", transport)
                    .put("auth_header", authHeader).put("auth_scheme", authScheme).put("enabled", enabled).put("allow", allow.toJson());
        } catch (Exception ignored) {
        }
        return o;
    }
}
