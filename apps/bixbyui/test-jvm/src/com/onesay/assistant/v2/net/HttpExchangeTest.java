package com.onesay.assistant.v2.net;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.Test;

/** 审计 B-5：识屏视觉请求的流 / 连接在异常路径也必须关闭；errorStream 为 null 不得 NPE。 */
public class HttpExchangeTest {

    static final class FakeConn extends HttpURLConnection {
        int code = 200;
        boolean throwOnCode, throwOnRead, nullError, disconnected;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean outClosed, inClosed;
        String resp = "{\"ok\":1}";

        FakeConn() throws Exception { super(new URL("http://example.invalid/")); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int b) { sent.write(b); }
                @Override public void close() { outClosed = true; }
            };
        }
        @Override public int getResponseCode() throws IOException {
            if (throwOnCode) throw new java.net.SocketTimeoutException("read timed out");
            return code;
        }
        private InputStream body() {
            return new ByteArrayInputStream(resp.getBytes()) {
                @Override public synchronized int read(byte[] b, int off, int len) {
                    if (throwOnRead) throw new RuntimeException(new IOException("reset"));
                    return super.read(b, off, len);
                }
                @Override public void close() { inClosed = true; }
            };
        }
        @Override public InputStream getInputStream() { return body(); }
        @Override public InputStream getErrorStream() { return nullError ? null : body(); }
    }

    @Test public void okPathReadsBodyAndClosesEverything() throws Exception {
        FakeConn c = new FakeConn();
        HttpClient.Response r = HttpClient.exchange(c, "abc".getBytes());
        assertEquals(200, r.code);
        assertEquals("{\"ok\":1}", r.body);
        assertEquals("abc", c.sent.toString());
        assertTrue(c.outClosed);
        assertTrue(c.inClosed);
        // ★ R14：响应体读完并关流 = 连接回连接池；成功路径不再 disconnect（Android OkHttp 的 disconnect 会关 socket、废掉 keep-alive）
        assertFalse(c.disconnected);
    }

    @Test public void errorStreamNullGivesEmptyBodyNotNpe() throws Exception {
        FakeConn c = new FakeConn();
        c.code = 500; c.nullError = true;
        HttpClient.Response r = HttpClient.exchange(c, "x".getBytes());
        assertEquals(500, r.code);
        assertEquals("", r.body);
        assertFalse("拿到了完整响应（空体）也算干净结束，不 disconnect（R14）", c.disconnected);
    }

    @Test public void errorStreamReadOnNon2xx() throws Exception {
        FakeConn c = new FakeConn();
        c.code = 401; c.resp = "denied";
        HttpClient.Response r = HttpClient.exchange(c, null);
        assertEquals("denied", r.body);
        assertTrue(c.inClosed);
    }

    @Test public void exceptionOnResponseCodeStillClosesAndDisconnects() throws Exception {
        FakeConn c = new FakeConn();
        c.throwOnCode = true;
        try {
            HttpClient.exchange(c, "abc".getBytes());
            fail("应抛出");
        } catch (java.net.SocketTimeoutException expected) { }
        assertTrue(c.outClosed);
        assertTrue(c.disconnected);
    }

    @Test public void exceptionWhileReadingStillClosesStream() throws Exception {
        FakeConn c = new FakeConn();
        c.throwOnRead = true;
        try {
            HttpClient.exchange(c, "abc".getBytes());
            fail("应抛出");
        } catch (RuntimeException expected) { }
        assertTrue(c.inClosed);
        assertTrue(c.disconnected);
    }

    @Test public void visionEngineUsesExchange() {
        String v = com.onesay.assistant.v2.kernel.KernelWiring.src("app/src/com/onesay/assistant/v2/vision/ScreenVisionEngine.java");
        // 再进一步改走共享 HttpClient.execute（doExecute 的 finally 同样关流 + disconnect），见 VisionHttpClientTest
        assertTrue(v.contains("HttpClient.execute("));
        assertFalse(v.contains("conn.getErrorStream()"));
    }
}
