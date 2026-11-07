package com.onesay.assistant.v2.net;

import com.onesay.assistant.v2.util.ConclusionLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 11-03 rel：空响应体 / 首字节超时重试 1 次、连接与读超时分开、总时长上限、请求日志只记数字、结论原文日志。 */
public class LlmClientRetryTest {
    static { LlmEndpoints.sTestAllowLoopback = true; }   // 假服务器在 127.0.0.1（http）

    private static final String OK = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"好\"}}]}";
    private static final String KEY = "sk-secret-xyz-123";   // gitleaks:allow（假 Key）

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<Long> at = Collections.synchronizedList(new ArrayList<Long>());
    /** 第 i 次请求（从 0 起）的行为：响应体（null = 挂起 sleepMs 不回）、状态码。 */
    private volatile String[] bodies = {OK};
    private volatile int status = 200;
    private volatile long sleepMs = 0;
    private final List<String> trace = Collections.synchronizedList(new ArrayList<String>());

    @Before public void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", new HttpHandler() {
            @Override public void handle(HttpExchange ex) {
                try {
                    int i = hits.getAndIncrement();
                    at.add(System.currentTimeMillis());
                    ex.getRequestBody().readAllBytes();
                    String b = bodies[Math.min(i, bodies.length - 1)];
                    if (b == null) {
                        try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
                        ex.close();
                        return;
                    }
                    byte[] bb = b.getBytes("UTF-8");
                    ex.sendResponseHeaders(status, bb.length == 0 ? -1 : bb.length);
                    OutputStream os = ex.getResponseBody();
                    os.write(bb);
                    os.close();
                } catch (Exception e) { /* 客户端先断开 */ }
            }
        });
        server.start();
        LlmClient.setTrace(new LlmClient.Trace() {
            @Override public void line(String s) { trace.add(s); }
        });
    }

    @After public void down() {
        LlmClient.sSleeper = null;
        LlmClient.setTrace(null);
        LlmClient.sTimeoutRetryMinReadMs = LlmClient.TIMEOUT_RETRY_MIN_READ_MS;
        server.stop(0);
    }

    private LlmClient client(int connectMs, int readMs) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
        return new LlmClient(url, new LlmClient.KeyProvider() {
            @Override public String apiKey() { return KEY; }
        }, connectMs, readMs).withTag("t");
    }

    @Test public void defaultsSplitConnectAndFirstByte() {
        assertEquals(10000, LlmClient.DEFAULT_CONNECT_MS);
        assertEquals(20000, LlmClient.DEFAULT_FIRST_BYTE_MS);
        LlmClient c = client(LlmClient.DEFAULT_CONNECT_MS, LlmClient.DEFAULT_FIRST_BYTE_MS);
        assertEquals(10000, c.connectTimeoutMs());
        assertEquals(20000, c.readTimeoutMs());
        assertEquals(LlmClient.MAX_TOTAL_MS, c.totalMs());
        LlmClient t = c.withReadTimeout(1500);
        assertEquals("派生客户端保留连接超时与总上限", 10000, t.connectTimeoutMs());
        assertEquals(1500, t.readTimeoutMs());
        assertEquals(LlmClient.MAX_TOTAL_MS, t.totalMs());
    }

    @Test public void emptyBodyRetriedOnceAfterDelay() {
        // ★ 11-04：不量墙钟（并行跑偶发），改为记录重试前请求的等待时长
        final List<Long> slept = Collections.synchronizedList(new ArrayList<Long>());
        LlmClient.sSleeper = new LlmClient.Sleeper() {
            @Override public void sleep(long ms) { slept.add(ms); }
        };
        bodies = new String[]{"", OK};
        LlmResponse r = client(2000, 2000).post("{}");
        assertTrue(r.ok());
        assertEquals("好", r.content());
        assertEquals(2, hits.get());
        assertEquals("重试前等 300 ms（只等一次）", Collections.singletonList((long) LlmClient.EMPTY_RETRY_DELAY_MS), slept);
        assertTrue(join().contains("空响应体 → 300 ms 后重试 1 次"));
    }

    @Test public void whitespaceOnlyBodyCountsAsEmpty() {
        bodies = new String[]{"\n\n  \n", OK};
        assertTrue(client(2000, 2000).post("{}").ok());
        assertEquals(2, hits.get());
    }

    @Test public void emptyBodyTwiceFallsBackAfterOneRetry() {
        bodies = new String[]{""};
        LlmResponse r = client(2000, 2000).post("{}");
        assertFalse(r.ok());
        assertEquals("空响应体", r.error);
        assertEquals("只重试 1 次", 2, hits.get());
    }

    @Test public void httpErrorNotRetried() {
        status = 500; bodies = new String[]{"{\"error\":\"x\"}"};
        LlmResponse r = client(2000, 2000).post("{}");
        assertFalse(r.ok());
        assertEquals(500, r.httpCode);
        assertEquals(1, hits.get());
    }

    @Test public void firstByteTimeoutRetriedOnceThenSucceeds() {
        LlmClient.sTimeoutRetryMinReadMs = 100;
        bodies = new String[]{null, OK};
        sleepMs = 5000;   // 服务端第一次根本不回：成功只能来自「首字节超时 → 取消重试」，不量墙钟
        LlmResponse r = client(1000, 400).post("{}");
        assertTrue(r.error, r.ok());
        assertEquals(2, hits.get());
        String j = join();
        assertTrue(j, j.contains("首字节超时 → 取消并重试 1 次"));
        assertTrue(j, j.matches("(?s).*\\[LLM\\] t try=1 http=-1 ms=\\d+ len=-1 fail=首字节超时.*"));
    }

    @Test public void firstByteTimeoutTwiceGivesUpWithinTotal() {
        LlmClient.sTimeoutRetryMinReadMs = 100;
        bodies = new String[]{null};
        sleepMs = 5000;
        // 总上限给足（并行负载下第一次超时后剩余也够再试一次），不量墙钟：两次都按首字节超时放弃、没等到总时长看门狗
        LlmResponse r = client(1000, 400).withTotalMs(20000).post("{}");
        assertFalse(r.ok());
        assertEquals("首字节超时", r.error);
        assertEquals("只重试 1 次", 2, hits.get());
        assertFalse(join().contains("总时长到"));
    }

    @Test public void noTimeoutRetryWhenRemainingTooShort() {
        LlmClient.sTimeoutRetryMinReadMs = 100;
        bodies = new String[]{null};
        sleepMs = 3000;
        LlmResponse r = client(1000, 400).withTotalMs(1200).post("{}");   // 剩 ~800 < 连接 1000 + 首字节 400
        assertFalse(r.ok());
        assertEquals(1, hits.get());
    }

    @Test public void fastTriageTimeoutNotRetried() {
        bodies = new String[]{null};
        sleepMs = 1500;
        LlmResponse r = client(1000, 300).post("{}");   // 读超时 < 10 s 的快速兜底：不重试
        assertFalse(r.ok());
        assertEquals(1, hits.get());
    }

    @Test public void attemptCapDisconnectsSlowBody() throws Exception {
        // 首字节很快到、正文迟迟不完：单次尝试上限 = min(剩余总时长, 连接 + 首字节 + 15 s) → 这里由总时长 800 ms 截断
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", new HttpHandler() {
            @Override public void handle(HttpExchange ex) {
                try {
                    hits.incrementAndGet();
                    ex.getRequestBody().readAllBytes();
                    ex.sendResponseHeaders(200, 0);
                    OutputStream os = ex.getResponseBody();
                    for (int i = 0; i < 40; i++) { os.write("\n".getBytes()); os.flush(); Thread.sleep(100); }
                    os.close();
                } catch (Exception ignored) {}
            }
        });
        LlmResponse r = client(1000, 1000).withTotalMs(800).post("{}");
        assertFalse(r.ok());
        // 服务端要 4 s 才写完；不量墙钟：错误必须来自总时长看门狗（而不是读完 / 读超时）
        assertTrue(r.error, r.error.startsWith("总时长到"));
        assertEquals(1, hits.get());
    }

    @Test public void traceHasCodeMsLenButNoSecrets() {
        bodies = new String[]{OK};
        client(2000, 2000).post("{\"messages\":\"用户隐私原话\"}");
        String j = join();
        assertTrue(j, j.matches("(?s).*\\[LLM\\] t try=1 http=200 ms=\\d+ len=" + OK.length() + " ok.*"));
        assertFalse(j.contains(KEY));
        assertFalse(j.contains("Bearer"));
        assertFalse(j.contains("Authorization"));
        assertFalse("不记请求体", j.contains("用户隐私原话"));
        assertFalse("不记响应原文", j.contains("choices"));
    }

    @Test public void attemptLineDropsExceptionMessage() {
        String l = LlmClient.attemptLine("agent", 2, -1, 12, -1, "连接异常(IOException: host sk-abc leaked)");
        assertEquals("[LLM] agent try=2 http=-1 ms=12 len=-1 fail=连接异常(IOException)", l);
        assertEquals("[LLM] triage try=1 http=200 ms=5 len=0 fail=空响应体", LlmClient.attemptLine("triage", 1, 200, 5, 0, "空响应体"));
    }

    @Test public void conclusionLogTruncatesAndChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("巴塞利斯蛇V3 439.12\n");   // 200 × 15 = 3000 字
        List<String> ls = ConclusionLog.lines("errand/COMPARE", sb.toString());
        assertEquals(2, ls.size());
        assertTrue(ls.get(0), ls.get(0).startsWith("[CONCLUSION] errand/COMPARE len=3000 (截前1500) 1/2 巴塞利斯蛇V3 439.12⏎"));
        int body = 0;
        for (String l : ls) { body += l.substring(l.indexOf("/2 ") + 3).length(); assertFalse(l.contains("\n")); }
        assertEquals(1500, body);
        List<String> one = ConclusionLog.lines("agent/MARKET", "上证 3585.20");
        assertEquals(Collections.singletonList("[CONCLUSION] agent/MARKET len=10 1/1 上证 3585.20"), one);
    }

    private String join() {
        StringBuilder sb = new StringBuilder();
        synchronized (trace) { for (String s : trace) sb.append(s).append('\n'); }
        return sb.toString();
    }
}
