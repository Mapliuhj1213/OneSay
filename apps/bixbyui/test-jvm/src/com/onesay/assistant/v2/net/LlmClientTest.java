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

/** P1-1：共用 LlmClient / LlmResponse —— 解析与 HTTP 行为（本地桩服务，不出网）。 */
public class LlmClientTest {
    static { LlmEndpoints.sTestAllowLoopback = true; }   // 假服务器在 127.0.0.1（http）

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "";
    private volatile String lastAuth;
    private volatile String lastReq;

    @Before public void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", new HttpHandler() {
            @Override public void handle(HttpExchange ex) {
                try {
                    lastAuth = ex.getRequestHeaders().getFirst("Authorization");
                    lastReq = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
                    byte[] b = body.getBytes("UTF-8");
                    ex.sendResponseHeaders(status, b.length);
                    OutputStream os = ex.getResponseBody();
                    os.write(b);
                    os.close();
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        server.start();
    }

    @After public void down() { server.stop(0); }

    private LlmClient client() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
        return new LlmClient(url, new LlmClient.KeyProvider() {
            @Override public String apiKey() { return "k-test"; }
        }, 2000, 2000);
    }

    @Test public void parsesDeepSeekStyle() {
        body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好\"}}]}";
        LlmResponse r = client().post("{\"x\":1}");
        assertTrue(r.ok());
        assertEquals("你好", r.content());
        assertEquals("Bearer k-test", lastAuth);
        assertEquals("{\"x\":1}", lastReq);
    }

    @Test public void parsesToolCalls() {
        body = "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"battery__level\",\"arguments\":\"{}\"}}]}}]}";
        LlmResponse r = client().post("{}");
        assertTrue(r.ok());
        assertNull("content=null 不能变成字符串 null", r.content());
        assertEquals(1, r.toolCalls().length());
    }

    @Test public void parsesDashScopeArrayContent() {
        LlmResponse r = LlmResponse.parse(200,
                "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"text\":\"打开\"},{\"text\":\"蓝牙\"}]}}]}}");
        assertTrue(r.ok());
        assertEquals("打开蓝牙", r.content());
    }

    @Test public void httpErrorIsFailureWithBody() {
        status = 401; body = "{\"error\":\"bad key\"}";
        LlmResponse r = client().post("{}");
        assertFalse(r.ok());
        assertEquals(401, r.httpCode);
        assertTrue(r.rawBody.contains("bad key"));
    }

    @Test public void garbageIsFailure() {
        assertFalse(LlmResponse.parse(200, "not json").ok());
        assertFalse(LlmResponse.parse(200, "{\"choices\":[]}").ok());
        assertFalse(LlmResponse.parse(200, "").ok());
    }

    @Test public void connectionRefusedIsFailureNotException() {
        LlmClient c = new LlmClient("http://127.0.0.1:1/x", null, 500, 500);
        LlmResponse r = c.post("{}");
        assertFalse(r.ok());
        assertEquals(-1, r.httpCode);
    }

    @Test public void sseDeltaParsing() {
        assertEquals("你好", LlmResponse.deltaOf("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}"));
        assertEquals("世界", LlmResponse.deltaOf("{\"choices\":[{\"delta\":{\"content\":\"世界\"}}]}"));
        assertNull(LlmResponse.deltaOf("data: {\"choices\":[{\"delta\":{\"content\":null}}]}"));
        assertNull(LlmResponse.deltaOf(": heartbeat"));
        assertNull(LlmResponse.deltaOf("data: [DONE]"));
        assertTrue(LlmResponse.isDoneLine("data: [DONE]"));
    }

    @Test public void streamReadsLinesUntilDone() {
        body = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n"
             + "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\n\n"
             + "data: [DONE]\n\n"
             + "data: {\"choices\":[{\"delta\":{\"content\":\"NO\"}}]}\n";
        final List<String> got = new ArrayList<String>();
        LlmClient.StreamStatus st = client().stream("{}", new LlmClient.StreamSink() {
            @Override public boolean onLine(String line) {
                String d = LlmResponse.deltaOf(line);
                if (d != null) got.add(d);
                return true;
            }
        });
        assertNull(st.error);
        assertEquals(200, st.httpCode);
        assertEquals("[a, b]", got.toString());
    }
}
