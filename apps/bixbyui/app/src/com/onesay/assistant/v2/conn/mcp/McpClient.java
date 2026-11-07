package com.onesay.assistant.v2.conn.mcp;

import com.onesay.assistant.v2.conn.ConnResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [conn/mcp] MCP 客户端：initialize → notifications/initialized → tools/list（分页）/ tools/call。
 * 会话过期（Streamable HTTP 404）时自动重新 initialize 一次再重试。只做工具，不声明 sampling / roots 等客户端能力。
 */
public final class McpClient {

    /** 我们优先请求的协议版本；服务器可以回一个它支持的版本。 */
    public static final String PREFERRED_VERSION = "2025-06-18";
    static final String[] KNOWN_VERSIONS = {"2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"};

    static final int INIT_TIMEOUT_MS = 15000;
    static final int CALL_TIMEOUT_MS = 30000;
    static final int MAX_PAGES = 10;
    /** 工具结果给模型的上限（字）。 */
    static final int MAX_RESULT_CHARS = 4000;

    private final McpTransport mT;
    private final String mVersion;
    private volatile boolean mInited;
    private volatile String mServerVersion;
    private volatile String mServerName = "";

    public McpClient(McpTransport t, String appVersion) {
        mT = t;
        mVersion = appVersion == null ? "0" : appVersion;
    }

    public McpTransport transport() { return mT; }
    public String serverVersion() { return mServerVersion; }
    public String serverName() { return mServerName; }
    public boolean initialized() { return mInited; }

    public synchronized void initialize() throws McpException {
        try {
            JSONObject params = new JSONObject()
                    .put("protocolVersion", PREFERRED_VERSION)
                    .put("capabilities", new JSONObject())
                    .put("clientInfo", new JSONObject().put("name", "OneSay").put("version", mVersion));
            JSONObject r = JsonRpc.resultOf(mT.request("initialize", params, INIT_TIMEOUT_MS));
            mServerVersion = r.optString("protocolVersion", PREFERRED_VERSION);
            JSONObject info = r.optJSONObject("serverInfo");
            mServerName = info != null ? info.optString("name", "") : "";
            if (r.optJSONObject("capabilities") != null && !r.optJSONObject("capabilities").has("tools")) {
                throw new McpException(ConnResult.Code.REMOTE_ERROR, "这个 MCP 服务器没有声明 tools 能力");
            }
            mT.setProtocolVersion(mServerVersion);
            mT.notify("notifications/initialized", null);
            mInited = true;
        } catch (McpException e) {
            mInited = false;
            throw e;
        } catch (Exception e) {
            mInited = false;
            throw new McpException(ConnResult.Code.REMOTE_ERROR, "initialize 失败：" + e.getMessage());
        }
    }

    public static boolean isKnownVersion(String v) {
        for (String k : KNOWN_VERSIONS) if (k.equals(v)) return true;
        return false;
    }

    public List<McpTool> listTools() throws McpException {
        List<McpTool> out = new ArrayList<McpTool>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JSONObject params = new JSONObject();
            try {
                if (cursor != null) params.put("cursor", cursor);
            } catch (Exception ignored) {
            }
            JSONObject r = call("tools/list", params, INIT_TIMEOUT_MS);
            JSONArray arr = r.optJSONArray("tools");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    McpTool t = McpTool.fromJson(arr.optJSONObject(i));
                    if (t != null) out.add(t);
                }
            }
            cursor = r.optString("nextCursor", null);
            if (cursor == null || cursor.length() == 0 || "null".equals(cursor)) break;
        }
        return out;
    }

    /** tools/call：返回（文本, isError）。 */
    public ToolOutput callTool(String name, JSONObject args) throws McpException {
        JSONObject params = new JSONObject();
        try {
            params.put("name", name).put("arguments", args != null ? args : new JSONObject());
        } catch (Exception ignored) {
        }
        JSONObject r = call("tools/call", params, CALL_TIMEOUT_MS);
        return ToolOutput.from(r);
    }

    private JSONObject call(String method, JSONObject params, int timeoutMs) throws McpException {
        if (!mInited) initialize();
        try {
            return JsonRpc.resultOf(mT.request(method, params, timeoutMs));
        } catch (McpException e) {
            if (!e.sessionExpired) throw e;
            mT.resetSession();
            mInited = false;
            initialize();
            return JsonRpc.resultOf(mT.request(method, params, timeoutMs));
        }
    }

    public void close() {
        mT.close();
        mInited = false;
    }

    /** tools/call 的结果：文本拼接（非文本内容只给占位），isError 原样。 */
    public static final class ToolOutput {
        public final String text;
        public final boolean isError;

        ToolOutput(String text, boolean isError) {
            this.text = text;
            this.isError = isError;
        }

        static ToolOutput from(JSONObject r) {
            StringBuilder sb = new StringBuilder();
            JSONArray content = r.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject c = content.optJSONObject(i);
                    if (c == null) continue;
                    String type = c.optString("type");
                    if (sb.length() > 0) sb.append('\n');
                    if ("text".equals(type)) sb.append(c.optString("text"));
                    else if ("resource".equals(type) && c.optJSONObject("resource") != null) sb.append(c.optJSONObject("resource").optString("text", "[资源]"));
                    else if ("resource_link".equals(type)) sb.append("[链接] ").append(c.optString("uri"));
                    else sb.append('[').append(type).append(']');
                }
            }
            if (sb.length() == 0 && r.optJSONObject("structuredContent") != null) sb.append(r.optJSONObject("structuredContent").toString());
            String t = sb.toString();
            if (t.length() > MAX_RESULT_CHARS) t = t.substring(0, MAX_RESULT_CHARS) + "…（已截断）";
            return new ToolOutput(t, r.optBoolean("isError", false));
        }
    }
}
