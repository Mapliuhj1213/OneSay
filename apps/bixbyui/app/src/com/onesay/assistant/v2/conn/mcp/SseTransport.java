package com.onesay.assistant.v2.conn.mcp;

import com.onesay.assistant.v2.conn.ConnErrors;
import com.onesay.assistant.v2.conn.ConnHttp;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.Redact;

import org.json.JSONObject;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [conn/mcp] 旧版 HTTP+SSE 传输（MCP 2024-11-05）。
 *
 * <ol>
 *   <li>GET sseUrl（Accept: text/event-stream）建长连接，等第一条 {@code event: endpoint}，data 是 POST 地址（可为相对路径）；</li>
 *   <li>每条请求 POST 到该地址（202/200 即可），响应以 {@code event: message} 从长连接回来，按 id 配对；</li>
 *   <li>长连接断开 = 传输失效，挂起的请求全部失败；下一次请求自动重连。</li>
 * </ol>
 * 读线程是后台线程；{@link #close()} 让它在下一行到来时退出（读超时兜底）。
 */
public final class SseTransport implements McpTransport {

    private final ConnHttp mHttp;
    private final String mSseUrl;
    private final Map<String, String> mAuth;
    private final AtomicLong mIds = new AtomicLong(1);
    private final Map<Long, Slot> mPending = new HashMap<Long, Slot>();
    private final Object mLock = new Object();
    private volatile String mEndpoint;
    private volatile boolean mClosed;
    private volatile Thread mReader;
    private volatile CountDownLatch mEndpointLatch;
    private volatile String mLastError;
    /** 读超时：长连接没有任何数据超过这个时间就断开重连（服务器一般 15–30 s 发一次 ping 注释）。 */
    static int sReadTimeoutMs = 120000;

    private static final class Slot {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile JSONObject msg;
    }

    public SseTransport(ConnHttp http, String sseUrl, Map<String, String> authHeaders) {
        mHttp = http;
        mSseUrl = sseUrl;
        mAuth = authHeaders;
    }

    @Override public String kind() { return "sse"; }

    @Override public void setProtocolVersion(String v) { }

    @Override public void resetSession() { close(); mClosed = false; }

    public String endpoint() { return mEndpoint; }

    private void ensureConnected(int timeoutMs) throws McpException {
        if (mEndpoint != null && mReader != null && mReader.isAlive()) return;
        synchronized (mLock) {
            if (mEndpoint != null && mReader != null && mReader.isAlive()) return;
            mClosed = false;
            mEndpoint = null;
            mLastError = null;
            final CountDownLatch latch = new CountDownLatch(1);
            mEndpointLatch = latch;
            mReader = com.onesay.assistant.v2.util.Threads.start("mcp-sse", new Runnable() {
                @Override public void run() {
                    readLoop(latch);
                }
            });
        }
        try {
            mEndpointLatch.await(Math.max(3000, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (mEndpoint == null) {
            String why = mLastError;
            close();
            if (why != null && why.startsWith("HTTP ")) {
                int code = 0;
                try { code = Integer.parseInt(why.substring(5, 8)); } catch (Exception ignored) { }
                throw new McpException(ConnErrors.classifyHttp(code), "SSE 连接被拒：" + why);
            }
            throw new McpException(why != null ? ConnResult.Code.UNREACHABLE : ConnResult.Code.TIMEOUT,
                    "SSE 没收到 endpoint 事件" + (why != null ? "：" + why : ""));
        }
    }

    private void readLoop(final CountDownLatch endpointLatch) {
        ConnHttp.Req req = new ConnHttp.Req("GET", mSseUrl).timeouts(8000, sReadTimeoutMs).header("Accept", "text/event-stream");
        if (mAuth != null) for (Map.Entry<String, String> e : mAuth.entrySet()) req.header(e.getKey(), e.getValue());
        final SseParser parser = new SseParser();
        ConnHttp.Resp r = mHttp.stream(req, new ConnHttp.LineSink() {
            @Override public boolean onLine(String line) {
                if (mClosed) return false;
                SseParser.Event e = parser.feed(line);
                if (e != null) onEvent(e, endpointLatch);
                return !mClosed;
            }
        });
        if (r.networkError()) mLastError = Redact.t(r.error);
        else if (r.code >= 400) mLastError = "HTTP " + r.code;
        else mLastError = "SSE 长连接已断开";
        mEndpoint = null;
        endpointLatch.countDown();
        synchronized (mPending) {
            for (Slot s : mPending.values()) s.latch.countDown();   // 挂起的请求全部失败（msg 为 null）
            mPending.clear();
        }
    }

    private void onEvent(SseParser.Event e, CountDownLatch endpointLatch) {
        if ("endpoint".equals(e.event)) {
            mEndpoint = resolve(mSseUrl, e.data.trim());
            endpointLatch.countDown();
            return;
        }
        if (!"message".equals(e.event)) return;
        try {
            JSONObject o = new JSONObject(e.data);
            if (!o.has("id") || !(o.has("result") || o.has("error"))) return;
            long id = o.optLong("id", -1);
            Slot s;
            synchronized (mPending) {
                s = mPending.remove(id);
            }
            if (s != null) {
                s.msg = o;
                s.latch.countDown();
            }
        } catch (Exception ignored) {
        }
    }

    /** endpoint 可能是相对路径（/messages?sessionId=…）：按 SSE 地址解析；只允许同源（防止把请求发去别的主机）。 */
    static String resolve(String base, String ep) {
        try {
            URI b = new URI(base);
            URI r = b.resolve(ep);
            if (!eq(b.getScheme(), r.getScheme()) || !eq(b.getHost(), r.getHost()) || b.getPort() != r.getPort()) return null;
            return r.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    @Override
    public JSONObject request(String method, JSONObject params, int timeoutMs) throws McpException {
        ensureConnected(timeoutMs);
        long id = mIds.getAndIncrement();
        Slot slot = new Slot();
        synchronized (mPending) {
            mPending.put(id, slot);
        }
        post(JsonRpc.request(id, method, params));
        try {
            if (!slot.latch.await(Math.max(3000, timeoutMs), TimeUnit.MILLISECONDS)) {
                synchronized (mPending) {
                    mPending.remove(id);
                }
                throw new McpException(ConnResult.Code.TIMEOUT, "MCP（SSE）等待响应超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(ConnResult.Code.CANCELLED, "被中断（已取消）");
        }
        if (slot.msg == null) throw new McpException(ConnResult.Code.UNREACHABLE, "SSE 长连接断开：" + mLastError);
        return slot.msg;
    }

    @Override
    public void notify(String method, JSONObject params) throws McpException {
        ensureConnected(8000);
        post(JsonRpc.notification(method, params));
    }

    private void post(JSONObject msg) throws McpException {
        String ep = mEndpoint;
        if (ep == null) throw new McpException(ConnResult.Code.UNREACHABLE, "SSE endpoint 不可用");
        ConnHttp.Req req = new ConnHttp.Req("POST", ep).timeouts(8000, 15000).body(msg.toString(), "application/json");
        if (mAuth != null) for (Map.Entry<String, String> e : mAuth.entrySet()) req.header(e.getKey(), e.getValue());
        ConnHttp.Resp r = mHttp.send(req);
        if (r.networkError()) throw new McpException(ConnErrors.classify(r.error), "MCP（SSE）POST 失败");
        if (r.code >= 400) throw new McpException(ConnErrors.classifyHttp(r.code), "MCP（SSE）POST 被拒 HTTP " + r.code);
    }

    @Override
    public void close() {
        mClosed = true;
        mEndpoint = null;
    }
}
