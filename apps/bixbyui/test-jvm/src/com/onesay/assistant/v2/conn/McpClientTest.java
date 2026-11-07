package com.onesay.assistant.v2.conn;

import com.onesay.assistant.v2.conn.mcp.McpClient;
import com.onesay.assistant.v2.conn.mcp.McpException;
import com.onesay.assistant.v2.conn.mcp.McpTool;
import com.onesay.assistant.v2.conn.mcp.SseParser;
import com.onesay.assistant.v2.conn.mcp.StreamableHttpTransport;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** [conn/mcp] Streamable HTTP 传输 + 客户端：握手 / 会话 / 分页 / SSE 响应 / 会话过期重连 / 错误分类。 */
public class McpClientTest {

    static long idOf(String body) {
        try {
            return new JSONObject(body).optLong("id", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    static String method(String body) {
        try {
            return new JSONObject(body).optString("method");
        } catch (Exception e) {
            return "";
        }
    }

    static final String INIT_RESULT = "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"demo\"}}";

    /** 一个标准服务器：initialize 给会话 id；tools/list 两页；tools/call 回文本。 */
    static FakeHttp server(final boolean sseForCall) {
        return new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                String m = method(body);
                long id = idOf(body);
                if ("initialize".equals(m)) {
                    return FakeHttp.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + INIT_RESULT + "}", "Mcp-Session-Id", "sess-1");
                }
                if ("notifications/initialized".equals(m)) return new ConnHttp.Resp(202, null, "", null);
                if (!"sess-1".equals(req.headers.get("Mcp-Session-Id"))) return new ConnHttp.Resp(400, null, "no session", null);
                if ("tools/list".equals(m)) {
                    boolean second = body.contains("\"cursor\":\"p2\"");
                    String tools = second
                            ? "[{\"name\":\"delete_all\",\"description\":\"删\",\"annotations\":{\"destructiveHint\":true}}]"
                            : "[{\"name\":\"search\",\"description\":\"查文档\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}]";
                    String next = second ? "" : ",\"nextCursor\":\"p2\"";
                    return FakeHttp.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":" + tools + next + "}}");
                }
                if ("tools/call".equals(m)) {
                    String res = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"答案 42\"}]}}";
                    if (sseForCall) {
                        return FakeHttp.sse(": ping\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
                                + "event: message\ndata: " + res + "\n\n");
                    }
                    return FakeHttp.json(200, res);
                }
                return FakeHttp.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"no\"}}");
            }
        });
    }

    @Test public void handshakeSessionPagingAndCall() throws Exception {
        FakeHttp http = server(false);
        Map<String, String> auth = new HashMap<String, String>();
        auth.put("Authorization", "Bearer secret-token");
        StreamableHttpTransport t = new StreamableHttpTransport(http, "https://x.example/mcp", auth);
        McpClient c = new McpClient(t, "2.1.3");
        List<McpTool> tools = c.listTools();
        assertEquals(2, tools.size());
        assertEquals("search", tools.get(0).name);
        assertTrue(tools.get(1).annotations.optBoolean("destructiveHint"));
        assertEquals("sess-1", t.sessionId());
        assertEquals("2025-06-18", c.serverVersion());
        // 头：Accept 两种、鉴权、初始化后带协议版本
        ConnHttp.Req init = http.requests.get(0);
        assertEquals("application/json, text/event-stream", init.headers.get("Accept"));
        assertEquals("Bearer secret-token", init.headers.get("Authorization"));
        assertNull("initialize 时还没有协议版本头", init.headers.get("MCP-Protocol-Version"));
        assertEquals("2025-06-18", http.requests.get(2).headers.get("MCP-Protocol-Version"));
        assertTrue(http.bodies.get(0).contains("\"clientInfo\""));
        assertEquals(1, http.count("notifications/initialized"));
        McpClient.ToolOutput out = c.callTool("search", new JSONObject().put("q", "x"));
        assertEquals("答案 42", out.text);
        assertFalse(out.isError);
        assertEquals("只握手一次", 1, http.count("initialize"));
    }

    @Test public void sseFramedResponseIsMatchedById() throws Exception {
        McpClient c = new McpClient(new StreamableHttpTransport(server(true), "https://x/mcp", null), "1");
        assertEquals("答案 42", c.callTool("search", new JSONObject()).text);
    }

    @Test public void sessionExpiredReinitializesOnce() throws Exception {
        final int[] calls = {0};
        final int[] inits = {0};
        FakeHttp http = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                String m = method(body);
                long id = idOf(body);
                if ("initialize".equals(m)) {
                    inits[0]++;
                    return FakeHttp.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + INIT_RESULT + "}", "Mcp-Session-Id", "s" + inits[0]);
                }
                if (m.startsWith("notifications/")) return new ConnHttp.Resp(202, null, "", null);
                calls[0]++;
                if ("s1".equals(req.headers.get("Mcp-Session-Id"))) return new ConnHttp.Resp(404, null, "gone", null);
                return FakeHttp.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}");
            }
        });
        McpClient c = new McpClient(new StreamableHttpTransport(http, "https://x/mcp", null), "1");
        assertEquals("ok", c.callTool("a", null).text);
        assertEquals(2, inits[0]);
        assertEquals(2, calls[0]);
    }

    @Test public void errorsAreClassified() {
        McpClient c401 = new McpClient(new StreamableHttpTransport(new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                return new ConnHttp.Resp(401, null, "{\"error\":\"invalid_token\"}", null);
            }
        }), "https://x/mcp", null), "1");
        try {
            c401.listTools();
            fail();
        } catch (McpException e) {
            assertEquals(ConnResult.Code.AUTH_REVOKED, e.code);
            assertEquals(401, e.httpStatus);
        }
        McpClient down = new McpClient(new StreamableHttpTransport(new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                return ConnHttp.Resp.fail(new java.net.UnknownHostException("x"));
            }
        }), "https://x/mcp", null), "1");
        try {
            down.listTools();
            fail();
        } catch (McpException e) {
            assertEquals(ConnResult.Code.UNREACHABLE, e.code);
        }
        McpClient slow = new McpClient(new StreamableHttpTransport(new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                return ConnHttp.Resp.fail(new java.net.SocketTimeoutException("x"));
            }
        }), "https://x/mcp", null), "1");
        try {
            slow.listTools();
            fail();
        } catch (McpException e) {
            assertEquals(ConnResult.Code.TIMEOUT, e.code);
        }
    }

    @Test public void rpcErrorAndToolIsError() throws Exception {
        FakeHttp http = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                String m = method(body);
                long id = idOf(body);
                if ("initialize".equals(m)) return FakeHttp.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + INIT_RESULT + "}");
                if (m.startsWith("notifications/")) return new ConnHttp.Resp(202, null, "", null);
                if ("tools/list".equals(m)) return FakeHttp.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32602,\"message\":\"bad\"}}");
                return FakeHttp.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"boom\"}]}}");
            }
        });
        McpClient c = new McpClient(new StreamableHttpTransport(http, "https://x/mcp", null), "1");
        try {
            c.listTools();
            fail();
        } catch (McpException e) {
            assertEquals(ConnResult.Code.BAD_ARGS, e.code);
            assertEquals(-32602, e.rpcCode);
        }
        McpClient.ToolOutput o = c.callTool("x", null);
        assertTrue(o.isError);
        assertEquals("boom", o.text);
    }

    @Test public void serverWithoutToolsCapabilityIsRejected() {
        FakeHttp http = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                return FakeHttp.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(body) + ",\"result\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"prompts\":{}}}}");
            }
        });
        try {
            new McpClient(new StreamableHttpTransport(http, "https://x/mcp", null), "1").initialize();
            fail();
        } catch (McpException e) {
            assertTrue(e.getMessage().contains("tools"));
        }
    }

    @Test public void sseParser() {
        SseParser p = new SseParser();
        assertNull(p.feed(": comment"));
        assertNull(p.feed("event: endpoint"));
        assertNull(p.feed("data: /messages?sessionId=1"));
        SseParser.Event e = p.feed("");
        assertEquals("endpoint", e.event);
        assertEquals("/messages?sessionId=1", e.data);
        p.feed("data:{\"a\":");
        p.feed("data: 1}");
        SseParser.Event e2 = p.feed("");
        assertEquals("message", e2.event);
        assertEquals("{\"a\":\n1}", e2.data);
        assertNull("空行不产生空事件", p.feed(""));
        p.feed("data: tail");
        assertEquals("tail", p.flush().data);
    }

    @Test public void resultTextIsTruncated() throws Exception {
        final StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) big.append('字');
        FakeHttp http = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                String m = method(body);
                long id = idOf(body);
                if ("initialize".equals(m)) return FakeHttp.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + INIT_RESULT + "}");
                if (m.startsWith("notifications/")) return new ConnHttp.Resp(202, null, "", null);
                return FakeHttp.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + big + "\"},{\"type\":\"image\",\"data\":\"x\"}]}}");
            }
        });
        McpClient.ToolOutput o = new McpClient(new StreamableHttpTransport(http, "https://x/mcp", null), "1").callTool("x", null);
        assertTrue(o.text.length() < 4100);
        assertTrue(o.text.endsWith("（已截断）"));
    }
}
