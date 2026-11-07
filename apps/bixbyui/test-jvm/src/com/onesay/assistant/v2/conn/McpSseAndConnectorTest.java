package com.onesay.assistant.v2.conn;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.conn.mcp.McpClient;
import com.onesay.assistant.v2.conn.mcp.McpConnector;
import com.onesay.assistant.v2.conn.mcp.McpServerConfig;
import com.onesay.assistant.v2.conn.mcp.SseTransport;
import com.onesay.assistant.v2.executor.ExecutionResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** [conn/mcp] 旧版 SSE 传输、auto 回退、McpConnector 白名单 / 缓存 / 令牌、注册表导出与执行分工。 */
public class McpSseAndConnectorTest {

    /** 旧版 SSE 服务器：GET 长连接先发 endpoint，POST 的请求从长连接回 message。 */
    static class LegacySseServer extends FakeHttp {
        final BlockingQueue<String> out = new LinkedBlockingQueue<String>();
        volatile boolean rejectPostStreamable = true;
        int gets;

        LegacySseServer() {
            super(null);
            handler = new Handler() {
                @Override public Resp handle(Req req, String body) {
                    if (req.url.endsWith("/mcp") && "POST".equals(req.method) && rejectPostStreamable) return new Resp(405, null, "", null);
                    if (req.url.contains("/messages")) {
                        try {
                            JSONObject o = new JSONObject(body);
                            String m = o.optString("method");
                            if (o.has("id")) {
                                long id = o.getLong("id");
                                String res;
                                if ("initialize".equals(m)) res = McpClientTest.INIT_RESULT;
                                else if ("tools/list".equals(m)) res = "{\"tools\":[{\"name\":\"echo\",\"description\":\"回声\"},{\"name\":\"send_mail\",\"description\":\"发信\"},{\"name\":\"secret\"}]}";
                                else res = "{\"content\":[{\"type\":\"text\",\"text\":" + JSONObject.quote("echo:" + o.getJSONObject("params").optJSONObject("arguments")) + "}]}";
                                out.put("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + res + "}\n\n");
                            }
                        } catch (Exception e) {
                            return new Resp(500, null, "", null);
                        }
                        return new Resp(202, null, "", null);
                    }
                    return new Resp(404, null, "", null);
                }
            };
        }

        @Override public Resp stream(Req req, LineSink sink) {
            if (!"GET".equals(req.method)) return super.stream(req, sink);
            synchronized (this) {
                gets++;
            }
            if (!sink.onLine("event: endpoint") || !sink.onLine("data: /messages?sessionId=abc") || !sink.onLine("")) return new Resp(200, null, "", null);
            try {
                while (true) {
                    String chunk = out.poll(3, TimeUnit.SECONDS);
                    if (chunk == null) {
                        if (!sink.onLine(": keepalive")) break;
                        continue;
                    }
                    boolean go = true;
                    for (String l : chunk.split("\n", -1)) if (!(go = sink.onLine(l))) break;
                    if (!go) break;
                }
            } catch (InterruptedException ignored) {
            }
            return new Resp(200, null, "", null);
        }
    }

    @Test public void legacySseTransportRoundTrip() throws Exception {
        LegacySseServer s = new LegacySseServer();
        SseTransport t = new SseTransport(s, "https://x.example/sse", null);
        McpClient c = new McpClient(t, "1");
        assertEquals(3, c.listTools().size());
        assertEquals("https://x.example/messages?sessionId=abc", t.endpoint());
        assertTrue(c.callTool("echo", new JSONObject().put("a", 1)).text.startsWith("echo:"));
        c.close();
    }

    @Test public void sseEndpointMustBeSameOrigin() {
        assertNull(SseTransportAccess.resolve("https://a.example/sse", "https://evil.example/messages"));
        assertEquals("https://a.example/m?x=1", SseTransportAccess.resolve("https://a.example/sse", "/m?x=1"));
    }

    static McpServerConfig cfg(String transport) throws Exception {
        return McpServerConfig.fromJson(new JSONObject().put("id", "demo").put("title", "演示").put("url", "https://x.example/mcp")
                .put("transport", transport)
                .put("allow", new JSONObject().put("echo", "read").put("send_mail", "comms").put("delete_all", "read")));
    }

    @Test public void connectorAutoFallsBackToSseAndAppliesAllowlist() throws Exception {
        LegacySseServer s = new LegacySseServer();
        MemSecrets sec = new MemSecrets();
        sec.put("mcp.demo.token", "tok-123");
        final String[] saved = new String[1];
        McpConnector m = new McpConnector(cfg("auto"), sec, s, "1", new McpConnector.CacheSink() {
            @Override public void save(String id, String json) { saved[0] = json; }
        });
        ConnResult r = m.refreshTools();
        assertTrue(r.toString(), r.ok());
        assertEquals("sse", m.transportKind());
        assertNotNull("工具清单持久化", saved[0]);
        assertFalse("缓存里不能有令牌", saved[0].contains("tok-123"));
        // 远端 3 个：echo（read）、send_mail（comms）、secret（不在白名单 → 不暴露）
        assertEquals(2, m.tools().size());
        assertEquals(ToolEffect.READ, m.tools().get(0).effect);
        assertEquals(ToolEffect.COMMS, m.tools().get(1).effect);
        assertEquals(ConnResult.Code.DENIED, m.call("secret", new JSONObject()).code);
        assertTrue(m.call("echo", new JSONObject().put("q", 1)).ok());
        // 鉴权头带上了
        boolean sawAuth = false;
        for (ConnHttp.Req q : s.requests) if ("Bearer tok-123".equals(q.headers.get("Authorization"))) sawAuth = true;
        assertTrue(sawAuth);
        assertFalse("状态里不出现令牌", m.status().contains("tok-123"));
    }

    @Test public void cacheRestoresWithoutNetwork() throws Exception {
        McpConnector m = new McpConnector(cfg("http"), new MemSecrets(), new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                throw new AssertionError("不应触网");
            }
        }), "1", null);
        m.loadCache(new JSONArray().put(new JSONObject().put("name", "echo")).put(new JSONObject().put("name", "delete_all")
                .put("annotations", new JSONObject().put("destructiveHint", true))).toString(), 1L);
        assertEquals(2, m.tools().size());
        assertEquals("远端声明 destructive → 白名单的 read 被升级", ToolEffect.DESTRUCTIVE, m.tools().get(1).effect);
    }

    @Test public void allowlistDefaultDenyAndOnlyEscalates() throws Exception {
        ToolAllowlist a = ToolAllowlist.parse(new JSONObject().put("r", "read").put("w", "write").put("*", "read").put("bad", "maybe"));
        assertEquals(2, a.size());
        assertNull("不在表里 = 拒绝（* 不生效）", a.effectFor("other", null));
        assertNull(a.effectFor("bad", null));
        assertEquals(ToolEffect.WRITE, a.effectFor("r", new JSONObject().put("readOnlyHint", false)));
        assertEquals("远端说只读也不降级", ToolEffect.WRITE, a.effectFor("w", new JSONObject().put("readOnlyHint", true)));
        assertEquals(ToolEffect.READ, a.effectFor("r", new JSONObject().put("readOnlyHint", true)));
    }

    @Test public void configValidation() throws Exception {
        assertNull(cfg("auto").validate());
        assertNotNull(McpServerConfig.fromJson(new JSONObject().put("id", "x").put("url", "http://evil.example/mcp")).validate());
        assertNull(McpServerConfig.fromJson(new JSONObject().put("id", "x").put("url", "http://127.0.0.1:8080/mcp")).validate());
        assertNotNull(McpServerConfig.fromJson(new JSONObject().put("id", "Bad ID").put("url", "https://a/b")).validate());
        assertNotNull("不能占用内置 id", McpServerConfig.fromJson(new JSONObject().put("id", "google").put("url", "https://a/b")).validate());
    }

    @Test public void registryExportsAndSplitsReadFromWrite() throws Exception {
        LegacySseServer s = new LegacySseServer();
        McpConnector m = new McpConnector(cfg("sse"), new MemSecrets(), s, "1", null);
        assertTrue(m.refreshTools().ok());
        ConnectorRegistry reg = new ConnectorRegistry();
        reg.register(m);
        JSONArray tools = new JSONArray();
        assertEquals(2, reg.appendTools(tools));
        assertEquals("重复追加不重复", 0, reg.appendTools(tools));
        String wire = tools.getJSONObject(0).getJSONObject("function").getString("name");
        assertEquals("conn__demo__echo", wire);
        assertTrue(wire.matches("^[a-zA-Z0-9_-]+$"));
        assertEquals("conn.demo.echo", CapabilityRegistry.toolNameToSign(wire));
        assertTrue(tools.getJSONObject(1).getJSONObject("function").getString("description").contains("确认卡"));

        Capability read = reg.describe("conn.demo.echo");
        assertEquals(SideEffect.PRIVATE_DATA_READ, read.sideEffect);
        assertFalse(read.confirm);
        Capability send = reg.describe("conn.demo.send_mail");
        assertEquals(SideEffect.PUBLIC_COMMS, send.sideEffect);
        assertTrue(send.confirm);
        assertEquals(2, send.risk);
        assertNull("白名单外的签名查不到", reg.describe("conn.demo.secret"));

        ExecutionResult rr = reg.executeRead(read, new JSONObject().put("x", 1));
        assertNotNull(rr);
        assertTrue(rr.ok);
        assertNull("有副作用的工具不在 App 内直接执行（交回令牌路径）", reg.executeRead(send, new JSONObject()));
        assertTrue("读远端内容 = 不可信 → 污染", com.onesay.assistant.v2.router.AgentLoop.readsUntrustedContent(read));
    }

    @Test public void tavilyAdapterKeepsBehaviour() throws Exception {
        final String[] got = new String[1];
        com.onesay.assistant.v2.search.SearchProvider p = new com.onesay.assistant.v2.search.SearchProvider() {
            @Override public String name() { return "tavily"; }
            @Override public boolean isReady() { return true; }
            @Override public java.util.List<com.onesay.assistant.v2.search.SearchResult> search(String q, int n) {
                got[0] = q + "/" + n;
                java.util.List<com.onesay.assistant.v2.search.SearchResult> l = new java.util.ArrayList<com.onesay.assistant.v2.search.SearchResult>();
                l.add(new com.onesay.assistant.v2.search.SearchResult("标题", "https://a", "摘要 99 元"));
                return l;
            }
        };
        TavilyConnector t = new TavilyConnector(p);
        assertFalse("不往 AgentLoop 加工具：现有搜索链路不变", t.exposeToAgent());
        ConnectorRegistry reg = new ConnectorRegistry();
        reg.register(t);
        assertEquals(0, reg.agentTools().size());
        assertNotNull(reg.findAny("conn.tavily.search"));
        ConnResult r = t.call("search", new JSONObject().put("query", "iPhone 价格").put("max_results", 3));
        assertTrue(r.ok());
        assertEquals("iPhone 价格/3", got[0]);
        assertTrue(r.text.contains("摘要 99 元"));
        assertEquals(1, new JSONArray(r.data).length());
        assertEquals(ConnResult.Code.BAD_ARGS, t.call("search", new JSONObject()).code);
    }

    @Test public void redactHidesSecrets() {
        String s = Redact.s("Authorization: Bearer ya29.abcDEF access_token=xyz&code=4/abc refresh_token\":\"1//0gXYZ\" tvly-dev-123 sk-abcdef123456 GOCSPX-xyz");
        assertFalse(s, s.contains("abcDEF"));
        assertFalse(s, s.contains("xyz&"));
        assertFalse(s, s.contains("0gXYZ"));
        assertFalse(s, s.contains("dev-123"));
        assertFalse(s, s.contains("abcdef123456"));
        assertFalse(s, s.contains("GOCSPX-xyz"));
    }
}
