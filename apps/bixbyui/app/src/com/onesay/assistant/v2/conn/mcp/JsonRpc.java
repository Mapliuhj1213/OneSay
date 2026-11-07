package com.onesay.assistant.v2.conn.mcp;

import com.onesay.assistant.v2.conn.ConnResult;

import org.json.JSONObject;

/** [conn/mcp] JSON-RPC 2.0 报文（纯函数）。 */
public final class JsonRpc {

    private JsonRpc() {}

    public static JSONObject request(long id, String method, JSONObject params) {
        try {
            JSONObject o = new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method);
            if (params != null) o.put("params", params);
            return o;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JSONObject notification(String method, JSONObject params) {
        try {
            JSONObject o = new JSONObject().put("jsonrpc", "2.0").put("method", method);
            if (params != null) o.put("params", params);
            return o;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 是不是对 id 的响应（有 result 或 error）。 */
    public static boolean isResponseTo(JSONObject msg, long id) {
        if (msg == null || !msg.has("id")) return false;
        if (!(msg.has("result") || msg.has("error"))) return false;
        Object v = msg.opt("id");
        if (v instanceof Number) return ((Number) v).longValue() == id;
        return String.valueOf(id).equals(String.valueOf(v));
    }

    /** 取 result；error → 抛 McpException。 */
    public static JSONObject resultOf(JSONObject msg) throws McpException {
        if (msg == null) throw new McpException(ConnResult.Code.REMOTE_ERROR, "空响应");
        JSONObject err = msg.optJSONObject("error");
        if (err != null) {
            int c = err.optInt("code", 0);
            ConnResult.Code code = (c == -32602 || c == -32600) ? ConnResult.Code.BAD_ARGS : ConnResult.Code.REMOTE_ERROR;
            throw new McpException(code, "JSON-RPC 错误 " + c + "：" + err.optString("message"), false, c);
        }
        JSONObject r = msg.optJSONObject("result");
        return r != null ? r : new JSONObject();
    }
}
