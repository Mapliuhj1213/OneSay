package com.onesay.assistant.v2.conn.mcp;

public final class SseTransportTestHook {
    public static String resolve(String base, String ep) {
        return SseTransport.resolve(base, ep);
    }
}
