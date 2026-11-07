package com.onesay.assistant.v2.conn.mcp;

import com.onesay.assistant.v2.conn.ConnResult;

/** [conn/mcp] MCP 调用失败（已分类、已脱敏）。 */
public final class McpException extends Exception {

    public final ConnResult.Code code;
    /** 会话过期（Streamable HTTP 带着 Mcp-Session-Id 收到 404）→ 客户端重新 initialize 一次。 */
    public final boolean sessionExpired;
    /** JSON-RPC error.code（没有为 0）。 */
    public final int rpcCode;
    /** HTTP 状态（没有为 0）：auto 传输据 400/404/405 判断要不要退回旧版 SSE。 */
    public final int httpStatus;

    public McpException(ConnResult.Code code, String msg) {
        this(code, msg, false, 0);
    }

    public McpException(ConnResult.Code code, String msg, boolean sessionExpired, int rpcCode) {
        this(code, msg, sessionExpired, rpcCode, 0);
    }

    public McpException(ConnResult.Code code, String msg, boolean sessionExpired, int rpcCode, int httpStatus) {
        super(com.onesay.assistant.v2.conn.Redact.s(msg, 300));
        this.code = code;
        this.sessionExpired = sessionExpired;
        this.rpcCode = rpcCode;
        this.httpStatus = httpStatus;
    }
}
