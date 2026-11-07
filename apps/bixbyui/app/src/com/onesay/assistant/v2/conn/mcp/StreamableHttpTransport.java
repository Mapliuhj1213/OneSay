package com.onesay.assistant.v2.conn.mcp;

import com.onesay.assistant.v2.conn.ConnErrors;
import com.onesay.assistant.v2.conn.ConnHttp;
import com.onesay.assistant.v2.conn.ConnResult;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [conn/mcp] Streamable HTTP 传输（MCP 2025-03-26 / 2025-06-18）。
 *
 * <ul>
 *   <li>每条消息一个 POST 到同一端点，头 {@code Accept: application/json, text/event-stream}；</li>
 *   <li>响应要么是 {@code application/json}（一条 JSON-RPC），要么是 {@code text/event-stream}（若干 message 事件，
 *       其中一条是对本请求 id 的响应，读到就断开）；</li>
 *   <li>initialize 的响应头里有 {@code Mcp-Session-Id} 就记下，之后每个请求带上；带着会话收到 404 = 会话过期；</li>
 *   <li>initialize 之后每个请求带 {@code MCP-Protocol-Version}；</li>
 *   <li>通知期望 202 Accepted；</li>
 *   <li>不开服务器推送用的 GET 长连接（本机只做「请求-响应」型工具调用，不需要）。</li>
 * </ul>
 */
public final class StreamableHttpTransport implements McpTransport {

    private final ConnHttp mHttp;
    private final String mUrl;
    private final Map<String, String> mAuth;
    private final AtomicLong mIds = new AtomicLong(1);
    private volatile String mSession;
    private volatile String mProtocol;

    /** @param authHeaders 鉴权头（如 Authorization: Bearer …），可为空 */
    public StreamableHttpTransport(ConnHttp http, String url, Map<String, String> authHeaders) {
        mHttp = http;
        mUrl = url;
        mAuth = authHeaders;
    }

    @Override public String kind() { return "streamable-http"; }

    public String sessionId() { return mSession; }

    @Override public void setProtocolVersion(String v) { mProtocol = v; }

    @Override public void resetSession() { mSession = null; }

    private ConnHttp.Req base(String method, int timeoutMs) {
        ConnHttp.Req r = new ConnHttp.Req(method, mUrl).timeouts(8000, Math.max(5000, timeoutMs));
        r.header("Accept", "application/json, text/event-stream");
        if (mAuth != null) for (Map.Entry<String, String> e : mAuth.entrySet()) r.header(e.getKey(), e.getValue());
        if (mSession != null) r.header("Mcp-Session-Id", mSession);
        if (mProtocol != null) r.header("MCP-Protocol-Version", mProtocol);
        return r;
    }

    @Override
    public JSONObject request(String method, JSONObject params, int timeoutMs) throws McpException {
        final long id = mIds.getAndIncrement();
        ConnHttp.Req req = base("POST", timeoutMs).body(JsonRpc.request(id, method, params).toString(), "application/json");
        final List<String> raw = new ArrayList<String>();
        final SseParser parser = new SseParser();
        final JSONObject[] hit = new JSONObject[1];
        ConnHttp.Resp resp = mHttp.stream(req, new ConnHttp.LineSink() {
            @Override public boolean onLine(String line) {
                raw.add(line);
                SseParser.Event e = parser.feed(line);
                if (e != null && consider(e, id, hit)) return false;
                return raw.size() < 20000;
            }
        });
        if (hit[0] == null) {
            SseParser.Event tail = parser.flush();
            if (tail != null) consider(tail, id, hit);
        }
        if (resp.networkError()) throw new McpException(ConnErrors.classify(resp.error), "MCP 连接失败：" + com.onesay.assistant.v2.conn.Redact.t(resp.error));
        if (resp.code == 404 && mSession != null) {
            throw new McpException(ConnResult.Code.REMOTE_ERROR, "MCP 会话已过期", true, 0);
        }
        if (resp.code >= 400) {
            throw new McpException(ConnErrors.classifyHttp(resp.code), "MCP 服务器返回 HTTP " + resp.code + " " + join(raw, 200),
                    false, 0, resp.code);
        }
        String sid = resp.header("mcp-session-id");
        if (sid != null && sid.length() > 0 && "initialize".equals(method)) mSession = sid;
        if (hit[0] != null) return hit[0];
        String ct = resp.header("content-type");
        if (ct == null || !ct.toLowerCase(java.util.Locale.ROOT).contains("event-stream")) {
            String body = join(raw, Integer.MAX_VALUE).trim();
            try {
                JSONObject o = new JSONObject(body);
                if (JsonRpc.isResponseTo(o, id)) return o;
            } catch (Exception ignored) {
            }
            throw new McpException(ConnResult.Code.REMOTE_ERROR, "MCP 响应不是对本请求的 JSON-RPC 响应");
        }
        throw new McpException(ConnResult.Code.TIMEOUT, "MCP 事件流结束前没收到响应");
    }

    static boolean consider(SseParser.Event e, long id, JSONObject[] hit) {
        if (!"message".equals(e.event)) return false;
        try {
            JSONObject o = new JSONObject(e.data);
            if (JsonRpc.isResponseTo(o, id)) {
                hit[0] = o;
                return true;
            }
        } catch (Exception ignored) {
            // 服务器推的通知 / 请求（如 ping、日志）：本机不处理
        }
        return false;
    }

    @Override
    public void notify(String method, JSONObject params) throws McpException {
        ConnHttp.Req req = base("POST", 8000).body(JsonRpc.notification(method, params).toString(), "application/json");
        ConnHttp.Resp resp = mHttp.send(req);
        if (resp.networkError()) throw new McpException(ConnErrors.classify(resp.error), "MCP 通知发送失败");
        if (resp.code >= 400) throw new McpException(ConnErrors.classifyHttp(resp.code), "MCP 通知被拒 HTTP " + resp.code);
    }

    @Override
    public void close() {
        if (mSession == null) return;
        try {
            mHttp.send(base("DELETE", 3000));
        } catch (Throwable ignored) {
        }
        mSession = null;
    }

    private static String join(List<String> lines, int max) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
            if (sb.length() > max) break;
        }
        return sb.length() > max ? sb.substring(0, max) : sb.toString();
    }
}
