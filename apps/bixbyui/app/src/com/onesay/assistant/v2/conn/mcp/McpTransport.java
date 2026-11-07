package com.onesay.assistant.v2.conn.mcp;

import org.json.JSONObject;

/** [conn/mcp] 传输层：Streamable HTTP（2025-03-26 起）或旧版 HTTP+SSE（2024-11-05）。 */
public interface McpTransport {

    /** 发请求、等响应，返回完整 JSON-RPC 响应报文（含 result 或 error）。 */
    JSONObject request(String method, JSONObject params, int timeoutMs) throws McpException;

    /** 发通知（不等响应）。 */
    void notify(String method, JSONObject params) throws McpException;

    /** initialize 成功后告诉传输层协商出的协议版本（Streamable HTTP 之后每个请求带 MCP-Protocol-Version 头）。 */
    void setProtocolVersion(String v);

    /** 丢掉会话（会话过期后重新 initialize 前调用）。 */
    void resetSession();

    void close();

    String kind();
}
