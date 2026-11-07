package com.onesay.assistant.v2.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 用 JDK 自带 httpserver 起本地桩服务测 200 / 401 / 500 / 超时 / 断网 / 流式。 */
public class HttpClientTest {

    private HttpServer server;
    private String base;
    private volatile String lastBody;
    private volatile String lastAuth;

    @Before public void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws java.io.IOException {
                lastBody = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
                lastAuth = ex.getRequestHeaders().getFirst("Authorization");
                reply(ex, 200, "{\"ok\":true,\"msg\":\"中文\"}");
            }
        });
        server.createContext("/auth", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws java.io.IOException {
                reply(ex, 401, "{\"error\":\"invalid key\"}");
            }
        });
        server.createContext("/boom", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws java.io.IOException {
                reply(ex, 500, "oops");
            }
        });
        server.createContext("/slow", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws java.io.IOException {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                reply(ex, 200, "late");
            }
        });
        server.createContext("/sse", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws java.io.IOException {
                reply(ex, 200, "data: a\n\ndata: b\n\ndata: [DONE]\n\ndata: never\n");
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After public void down() {
        server.stop(0);
    }

    private static void reply(HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes("UTF-8");
        ex.sendResponseHeaders(code, b.length);
        OutputStream os = ex.getResponseBody();
        os.write(b);
        os.close();
    }

    @Test public void postJsonOk() {
        HttpClient.Response r = HttpClient.postJson(base + "/echo", HttpClient.bearer("sk-test"),
                "{\"q\":\"天气\"}", 2000, 2000);
        assertEquals(200, r.code);
        assertEquals(HttpClient.Kind.OK, r.kind);
        assertTrue(r.isOk());
        assertTrue(r.body.contains("中文"));
        assertEquals("{\"q\":\"天气\"}", lastBody);
        assertEquals("Bearer sk-test", lastAuth);
    }

    @Test public void authAndServerErrorsReadErrorStream() {
        HttpClient.Response a = HttpClient.get(base + "/auth", null, 2000, 2000);
        assertEquals(401, a.code);
        assertEquals(HttpClient.Kind.AUTH, a.kind);
        assertTrue("401 时应读到 errorStream", a.body.contains("invalid key"));
        HttpClient.Response b = HttpClient.get(base + "/boom", null, 2000, 2000);
        assertEquals(HttpClient.Kind.HTTP_ERROR, b.kind);
        assertEquals("oops", b.body);
    }

    @Test public void timeoutIsClassified() {
        HttpClient.Response r = HttpClient.get(base + "/slow", null, 2000, 300);
        assertEquals(-1, r.code);
        assertEquals(HttpClient.Kind.TIMEOUT, r.kind);
        assertNotNull(r.error);
        assertTrue(r.errorText().startsWith("SocketTimeoutException"));
    }

    @Test public void networkErrorNeverThrows() {
        server.stop(0);
        HttpClient.Response r = HttpClient.get(base + "/echo", null, 500, 500);
        assertEquals(-1, r.code);
        assertEquals(HttpClient.Kind.NETWORK, r.kind);
        HttpClient.Response bad = HttpClient.get("not a url", null, 500, 500);
        assertEquals(HttpClient.Kind.NETWORK, bad.kind);
    }

    @Test public void streamStopsWhenListenerReturnsFalse() {
        final List<String> got = new ArrayList<String>();
        HttpClient.Response r = HttpClient.stream(new HttpClient.Request(base + "/sse"),
                new HttpClient.LineListener() {
                    @Override public boolean onLine(String line) {
                        if (!line.startsWith("data: ")) return true;
                        String d = line.substring(6);
                        if ("[DONE]".equals(d)) return false;
                        got.add(d);
                        return true;
                    }
                });
        assertTrue(r.isOk());
        assertEquals(2, got.size());
        assertEquals("b", got.get(1));
    }
}
