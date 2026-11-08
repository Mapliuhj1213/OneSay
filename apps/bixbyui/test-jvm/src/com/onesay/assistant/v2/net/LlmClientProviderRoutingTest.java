package com.onesay.assistant.v2.net;

import com.onesay.assistant.v2.llm.ProviderCheckRoutingTest;
import com.onesay.assistant.v2.llm.ProviderKind;
import com.onesay.assistant.v2.llm.ProviderRoute;
import com.onesay.assistant.v2.llm.ProviderRouting;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * priv-llm：主对话改走 Anthropic / Gemini 时，LlmClient 照常对外说 OpenAI 格式（本机假服务器回放样本，不出网）；
 * 没选别家 / 不是主对话地址时完全走原路。
 */
public class LlmClientProviderRoutingTest {

    private HttpServer server;
    private volatile String path, lastReq, xApiKey, goog, auth, version;
    private volatile String reply = "";
    private volatile String contentType = "application/json";

    @Before public void up() throws Exception {
        LlmEndpoints.sTestAllowLoopback = true;
        ProviderCheckRoutingTest.allowLoopback(true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override public void handle(HttpExchange ex) {
                try {
                    path = ex.getRequestURI().toString();
                    xApiKey = ex.getRequestHeaders().getFirst("x-api-key");
                    goog = ex.getRequestHeaders().getFirst("x-goog-api-key");
                    auth = ex.getRequestHeaders().getFirst("Authorization");
                    version = ex.getRequestHeaders().getFirst("anthropic-version");
                    lastReq = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
                    byte[] b = reply.getBytes("UTF-8");
                    ex.getResponseHeaders().add("Content-Type", contentType);
                    ex.sendResponseHeaders(200, b.length);
                    OutputStream os = ex.getResponseBody();
                    os.write(b);
                    os.close();
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        server.start();
    }

    @After public void down() {
        server.stop(0);
        ProviderRouting.set(null);
        ProviderCheckRoutingTest.allowLoopback(false);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String fx(String n) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), "test-jvm/fixtures/llm/" + n).toPath()), "UTF-8");
    }

    private static LlmClient mainClient() {
        return new LlmClient(LlmEndpoints.deepseekChatUrl(), new LlmClient.KeyProvider() {
            @Override public String apiKey() { return "ds-key-should-not-leak"; }
        }, 2000, 3000);
    }

    private void route(ProviderKind k, String model) {
        ProviderRouting.set(new ProviderRoute(k == ProviderKind.ANTHROPIC ? "anthropic" : "gemini", k, base(), model,
                new ProviderRoute.KeySource() { public String key() { return "provider-key"; } }));
    }

    @Test public void anthropicPostRoundTrip() throws Exception {
        route(ProviderKind.ANTHROPIC, "claude-sonnet-4-5");
        reply = fx("anthropic_messages.json");
        LlmClient c = mainClient();
        assertEquals("主对话的 Key 换成服务商的", "provider-key", c.apiKey());
        String body = LlmClient.body(LlmModels.DEEPSEEK_DEFAULT, 0.2, 300, LlmClient.messages("系统", "电量多少"), null, false).toString();
        LlmResponse r = c.post(body);
        assertTrue(r.error, r.ok());
        assertEquals("我先看一下电量。", r.content());
        assertEquals(1, r.toolCalls().length());
        assertEquals("/v1/messages", path);
        assertEquals("provider-key", xApiKey);
        assertNull("不带 Bearer（DeepSeek Key 不外泄）", auth);
        assertEquals("2023-06-01", version);
        JSONObject sent = new JSONObject(lastReq);
        assertEquals("claude-sonnet-4-5", sent.getString("model"));
        assertEquals("系统", sent.getString("system"));
        assertFalse(lastReq.contains("ds-key-should-not-leak"));
    }

    @Test public void geminiStreamRoundTrip() throws Exception {
        route(ProviderKind.GEMINI, "gemini-2.5-flash");
        reply = fx("gemini_stream.sse");
        contentType = "text/event-stream";
        final List<String> seen = new ArrayList<String>();
        LlmClient.StreamStatus st = mainClient().stream(
                LlmClient.body("deepseek-flash", 0.5, 200, LlmClient.messages(null, "天气"), null, true).toString(),
                new LlmClient.StreamSink() {
                    @Override public boolean onLine(String line) { seen.add(line); return true; }
                });
        assertNull(st.error, st.error);
        StringBuilder text = new StringBuilder();
        for (String l : seen) { String d = LlmResponse.deltaOf(l); if (d != null) text.append(d); }
        assertEquals("今天天气晴。", text.toString());
        assertEquals("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse", path);
        assertEquals("provider-key", goog);
        assertNull(auth);
    }

    @Test public void otherUrlsIgnoreRoute() throws Exception {
        route(ProviderKind.ANTHROPIC, "claude-sonnet-4-5");
        reply = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"原路\"}}]}";
        LlmClient c = new LlmClient(base() + "/v1/chat/completions", new LlmClient.KeyProvider() {
            @Override public String apiKey() { return "k-test"; }
        }, 2000, 2000);
        assertEquals("k-test", c.apiKey());
        LlmResponse r = c.post("{\"x\":1}");
        assertTrue(r.ok());
        assertEquals("原路", r.content());
        assertEquals("/v1/chat/completions", path);
        assertEquals("Bearer k-test", auth);
        assertEquals("{\"x\":1}", lastReq);
    }

    @Test public void noRouteMeansLegacyKey() {
        ProviderRouting.set(null);
        assertEquals("ds-key-should-not-leak", mainClient().apiKey());
    }
}
