package com.onesay.assistant.v2.conn;

import com.onesay.assistant.v2.net.HttpClient;
import com.onesay.assistant.v2.search.SearchOutcome;
import com.onesay.assistant.v2.search.SearchProvider;
import com.onesay.assistant.v2.search.SearchResult;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InterruptedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import static org.junit.Assert.*;

/** r2/fixes 第 1 项：网络错误分类（连接 / 读超时、取消、TLS、Tavily 无结果）。 */
public class R2NetClassifyTest {

    @Test public void connectPhaseTimeoutIsUnreachable() {
        assertEquals(ConnResult.Code.UNREACHABLE,
                ConnErrors.classify(new ConnectPhaseException(new SocketTimeoutException("connect timed out"))));
        assertEquals("读阶段超时仍是 TIMEOUT（结果未知）", ConnResult.Code.TIMEOUT,
                ConnErrors.classify(new SocketTimeoutException("Read timed out")));
        assertEquals("连接阶段任何 IO 失败都算没发出", ConnResult.Code.UNREACHABLE,
                ConnErrors.classify(new ConnectPhaseException(new javax.net.ssl.SSLException("Connection closed by peer"))));
    }

    @Test public void cancelIsNotTimeout() {
        assertEquals(ConnResult.Code.CANCELLED, ConnErrors.classify(new InterruptedIOException("thread interrupted")));
        assertEquals(ConnResult.Code.CANCELLED, ConnErrors.classify(new java.nio.channels.ClosedByInterruptException()));
        assertEquals(ConnResult.Code.CANCELLED, ConnErrors.classify(new ConnectPhaseException(new InterruptedIOException())));
        assertFalse(ConnResult.fail(ConnResult.Code.CANCELLED, "x").shouldFallback());
        assertFalse(ConnResult.fail(ConnResult.Code.CANCELLED, "x").retryable());
        assertEquals(HttpClient.Kind.CANCELLED, HttpClient.classify(-1, new InterruptedIOException()));
        assertEquals(HttpClient.Kind.TIMEOUT, HttpClient.classify(-1, new SocketTimeoutException()));
        assertEquals(HttpClient.Kind.NETWORK, HttpClient.classify(-1, new java.net.ConnectException()));
    }

    @Test public void tlsBySemantics() {
        SSLHandshakeException cert = new SSLHandshakeException("Chain validation failed");
        cert.initCause(new java.security.cert.CertificateException("Trust anchor not found"));
        assertEquals("证书校验失败 → TLS_ERROR", ConnResult.Code.TLS_ERROR, ConnErrors.classify(cert));
        assertEquals(ConnResult.Code.TLS_ERROR, ConnErrors.classify(new ConnectPhaseException(cert)));
        assertEquals("主机名不符 → TLS_ERROR", ConnResult.Code.TLS_ERROR, ConnErrors.classify(new SSLPeerUnverifiedException("Hostname not verified")));
        SSLHandshakeException reset = new SSLHandshakeException("Handshake failed");
        reset.initCause(new java.net.SocketException("Connection reset"));
        assertEquals("握手中途被掐（墙内常见）→ UNREACHABLE", ConnResult.Code.UNREACHABLE, ConnErrors.classify(reset));
        assertTrue(ConnResult.fail(ConnResult.Code.TLS_ERROR, "x").shouldFallback());
        assertFalse("证书错不重试", ConnResult.fail(ConnResult.Code.TLS_ERROR, "x").retryable());
        assertTrue(ConnErrors.human(ConnResult.Code.TLS_ERROR).contains("证书"));
    }

    @Test public void urlConnHttpReadTimeoutIsTimeoutAndRefusedIsUnreachable() throws Exception {
        final ServerSocket ss = new ServerSocket(0);
        final List<Socket> held = new ArrayList<Socket>();
        Thread acc = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (true) held.add(ss.accept());   // 接受连接但永远不回包 → 读超时
                } catch (Exception ignored) {
                }
            }
        });
        acc.setDaemon(true);
        acc.start();
        try {
            ConnHttp.Resp r = new UrlConnHttp().send(new ConnHttp.Req("GET", "http://127.0.0.1:" + ss.getLocalPort() + "/x").timeouts(2000, 300));
            assertTrue(r.networkError());
            assertEquals("连上了、读超时 → TIMEOUT", ConnResult.Code.TIMEOUT, ConnErrors.classify(r.error));
        } finally {
            ss.close();
            for (Socket s : held) s.close();
        }
        int port;
        ServerSocket tmp = new ServerSocket(0);
        port = tmp.getLocalPort();
        tmp.close();
        ConnHttp.Resp r2 = new UrlConnHttp().send(new ConnHttp.Req("GET", "http://127.0.0.1:" + port + "/x").timeouts(500, 500));
        assertTrue(r2.networkError());
        assertTrue("连接阶段失败带标记", r2.error instanceof ConnectPhaseException);
        assertEquals(ConnResult.Code.UNREACHABLE, ConnErrors.classify(r2.error));
    }

    static final class Outcomes implements SearchProvider, SearchOutcome {
        boolean failed;
        @Override public String name() { return "tavily"; }
        @Override public boolean isReady() { return true; }
        @Override public List<SearchResult> search(String q, int n) { return new ArrayList<SearchResult>(); }
        @Override public boolean lastSearchFailed() { return failed; }
    }

    @Test public void tavilyNoResultsIsNotUnreachable() throws Exception {
        Outcomes p = new Outcomes();
        TavilyConnector t = new TavilyConnector(p);
        ConnResult r = t.call("search", new JSONObject().put("query", "很冷门的词"));
        assertTrue(r.text, r.ok());
        assertEquals("[]", r.data);
        p.failed = true;
        assertEquals(ConnResult.Code.UNREACHABLE, t.call("search", new JSONObject().put("query", "x")).code);
        SearchProvider plain = new SearchProvider() {
            @Override public String name() { return "x"; }
            @Override public boolean isReady() { return true; }
            @Override public List<SearchResult> search(String q, int n) { return new ArrayList<SearchResult>(); }
        };
        assertEquals("分不清时不说网络不可达", ConnResult.Code.REMOTE_ERROR,
                new TavilyConnector(plain).call("search", new JSONObject().put("query", "x")).code);
    }

    @Test public void connCapabilitiesMapsNewCodes() {
        assertEquals(com.onesay.assistant.v2.executor.ErrorCode.CANCELLED,
                ConnCapabilities.toResult(ConnResult.fail(ConnResult.Code.CANCELLED, "x"), null, new JSONObject()).code);
        assertEquals(com.onesay.assistant.v2.executor.ErrorCode.TLS_ERROR,
                ConnCapabilities.toResult(ConnResult.fail(ConnResult.Code.TLS_ERROR, "x"), null, new JSONObject()).code);
    }
}
