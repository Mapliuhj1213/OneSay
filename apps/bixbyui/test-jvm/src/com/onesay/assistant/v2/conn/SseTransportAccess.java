package com.onesay.assistant.v2.conn;

/** 让 conn 包测试访问 SseTransport.resolve（包私有）。 */
final class SseTransportAccess {
    static String resolve(String base, String ep) {
        return com.onesay.assistant.v2.conn.mcp.SseTransportTestHook.resolve(base, ep);
    }
}
