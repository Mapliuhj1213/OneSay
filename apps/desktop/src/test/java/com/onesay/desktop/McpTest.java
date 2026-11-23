package com.onesay.desktop;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/** [preview-1120] MCP 连接器：识别、stdio / Streamable HTTP / SSE、注册给模型、审批分类、密钥不外露。 */
public class McpTest {

    /** 自写的 stdio mock 服务器（Python，不依赖第三方包）：echo（只读）、write_note（有副作用）、会往 stdout 打一行日志、会反过来 ping 客户端。 */
    static final String MOCK = String.join("\n",
            "import sys, json, os",
            "print('mock server starting (log line on stdout)', flush=True)",
            "def send(m): sys.stdout.write(json.dumps(m) + '\\n'); sys.stdout.flush()",
            "for line in sys.stdin:",
            "    line = line.strip()",
            "    if not line: continue",
            "    m = json.loads(line)",
            "    if 'id' not in m and 'method' in m: continue",
            "    if 'method' not in m: continue",
            "    mid, meth = m['id'], m['method']",
            "    if meth == 'initialize':",
            "        send({'jsonrpc':'2.0','id':'srv-ping','method':'ping'})",
            "        send({'jsonrpc':'2.0','id':mid,'result':{'protocolVersion':'2025-06-18','capabilities':{'tools':{}},'serverInfo':{'name':'mock','version':'1.0'}}})",
            "    elif meth == 'tools/list':",
            "        if m.get('params',{}).get('cursor') == 'p2':",
            "            send({'jsonrpc':'2.0','id':mid,'result':{'tools':[{'name':'write_note','description':'writes a note','inputSchema':{'type':'object','properties':{'text':{'type':'string'}}}}]}})",
            "        else:",
            "            send({'jsonrpc':'2.0','id':mid,'result':{'nextCursor':'p2','tools':[{'name':'echo','description':'echo back','inputSchema':{'type':'object','properties':{'message':{'type':'string'}}},'annotations':{'readOnlyHint':True}}]}})",
            "    elif meth == 'tools/call':",
            "        a = m['params'].get('arguments', {})",
            "        if m['params']['name'] == 'echo':",
            "            send({'jsonrpc':'2.0','id':mid,'result':{'content':[{'type':'text','text':'Echo: ' + a.get('message','') + ' key=' + ('set' if os.environ.get('MOCK_KEY') else 'unset')}]}})",
            "        else:",
            "            send({'jsonrpc':'2.0','id':mid,'result':{'isError':True,'content':[{'type':'text','text':'nope'}]}})",
            "    else:",
            "        send({'jsonrpc':'2.0','id':mid,'error':{'code':-32601,'message':'no'}})",
            "");

    static final String SECRET = "sk-test-SECRET-1234567890abcdef";

    final List<McpHub> hubs = new ArrayList<>();
    final List<HttpServer> https = new ArrayList<>();

    @After public void tearDown() {
        for (McpHub h : hubs) h.shutdown();
        for (HttpServer h : https) h.stop(0);
        PcGuard.MCP = null;
    }

    static String python() {
        String p = Mcp.which("python3", System.getenv());
        return p != null ? p : Mcp.which("python", System.getenv());
    }

    Path mockDir() throws Exception {
        Path d = Files.createTempDirectory("mcp-mock");
        Files.writeString(d.resolve("mock_server.py"), MOCK);
        return d;
    }

    static McpHub.Server waitReady(McpHub h, String name) throws Exception {
        for (int i = 0; i < 100; i++) {
            for (McpHub.Server s : h.servers.values()) if (s.spec.name.equals(name) && !"starting".equals(s.status)) return s;
            Thread.sleep(100);
        }
        fail("没等到 " + name);
        return null;
    }

    // ------------------------------------------------------------------ 识别

    @Test public void detectsCommonConfigFiles() throws Exception {
        Path d = Files.createTempDirectory("mcp-cfg");
        Files.writeString(d.resolve("claude_desktop_config.json"), "{\"mcpServers\":{\"fs\":{\"command\":\"npx\",\"args\":[\"-y\",\"@modelcontextprotocol/server-filesystem\",\"${workspaceFolder}\"],\"env\":{\"TOKEN\":\"${env:MY_TOKEN}\"}},"
                + "\"remote\":{\"url\":\"https://example.com/mcp\",\"headers\":{\"Authorization\":\"Bearer ${MY_TOKEN}\"}},\"old\":{\"type\":\"sse\",\"url\":\"http://127.0.0.1:9/sse\"}}}");
        Map<String, String> env = new TreeMap<>(Map.of("MY_TOKEN", SECRET, "PATH", ""));
        Mcp.Detected r = Mcp.detect(d, env);
        assertEquals(3, r.specs.size());
        Mcp.Spec fs = r.specs.stream().filter(s -> s.name.equals("fs")).findFirst().get();
        assertEquals("stdio", fs.transport);
        assertEquals(d.toString(), fs.args.get(2));
        assertEquals(SECRET, fs.env.get("TOKEN"));
        assertFalse("给人看的启动方式不带秘密", fs.shown().contains(SECRET));
        Mcp.Spec remote = r.specs.stream().filter(s -> s.name.equals("remote")).findFirst().get();
        assertEquals("auto", remote.transport);
        assertEquals("Bearer " + SECRET, remote.headers.get("Authorization"));
        assertEquals("sse", r.specs.stream().filter(s -> s.name.equals("old")).findFirst().get().transport);

        // VS Code 的 .vscode/mcp.json：servers 段、注释、结尾逗号、${input:} 标问题
        Path v = Files.createTempDirectory("mcp-vsc");
        Files.createDirectories(v.resolve(".vscode"));
        Files.writeString(v.resolve(".vscode/mcp.json"), "{\n // 注释\n \"servers\": {\n  \"gh\": {\"type\": \"http\", \"url\": \"https://api.example.com/mcp/\", \"headers\": {\"Authorization\": \"Bearer ${input:token}\"}},\n  \"py\": {\"command\": \"uvx\", \"args\": [\"mcp-server-time\"],},\n }\n}");
        Mcp.Detected rv = Mcp.detect(v, env);
        assertEquals(2, rv.specs.size());
        Mcp.Spec gh = rv.specs.stream().filter(s -> s.name.equals("gh")).findFirst().get();
        assertEquals("http", gh.transport);
        assertTrue(gh.problem, gh.problem.contains("input:token"));
        // 直接选 .mcp.json 文件
        Path f = Files.createTempDirectory("mcp-f").resolve(".mcp.json");
        Files.writeString(f, "{\"mcpServers\":{\"x\":{\"command\":\"node\",\"args\":[\"a.js\"]}}}");
        assertEquals(1, Mcp.detect(f, env).specs.size());
        // 坏 JSON
        Path bad = Files.createTempDirectory("mcp-bad");
        Files.writeString(bad.resolve("mcp.json"), "{oops");
        Mcp.Detected rb = Mcp.detect(bad, env);
        assertTrue(rb.specs.isEmpty());
        assertTrue(rb.notes.get(0).contains("不是合法的 JSON"));
    }

    @Test public void detectsServerProjects() throws Exception {
        Path root = Files.createTempDirectory("mcp-proj");
        // Node：bin 存在、依赖装好
        Path node = Files.createDirectories(root.resolve("node-srv"));
        Files.writeString(node.resolve("package.json"), "{\"name\":\"@acme/mcp-weather\",\"bin\":{\"mcp-weather\":\"dist/index.js\"},\"dependencies\":{\"@modelcontextprotocol/sdk\":\"^1\"}}");
        Files.createDirectories(node.resolve("dist"));
        Files.writeString(node.resolve("dist/index.js"), "");
        Files.createDirectories(node.resolve("node_modules"));
        // Node：没构建
        Path nb = Files.createDirectories(root.resolve("node-unbuilt"));
        Files.writeString(nb.resolve("package.json"), "{\"name\":\"x-mcp\",\"bin\":\"build/index.js\",\"dependencies\":{\"@modelcontextprotocol/sdk\":\"^1\"}}");
        // Python：pyproject + scripts
        Path py = Files.createDirectories(root.resolve("py-srv"));
        Files.writeString(py.resolve("pyproject.toml"), "[project]\nname = \"mcp-server-time\"\ndependencies = [\"mcp>=1.0\"]\n[project.scripts]\nmcp-server-time = \"mcp_server_time:main\"\n");
        // 不是 MCP 的 Node 项目
        Path other = Files.createDirectories(root.resolve("website"));
        Files.writeString(other.resolve("package.json"), "{\"name\":\"site\",\"dependencies\":{\"react\":\"18\"}}");
        // Dockerfile
        Path dk = Files.createDirectories(root.resolve("docker-srv"));
        Files.writeString(dk.resolve("Dockerfile"), "FROM scratch");
        Map<String, String> env = new TreeMap<>();
        Path bin = Files.createTempDirectory("bin");
        for (String p : new String[] {"node", "uv"}) { Path x = bin.resolve(p); Files.writeString(x, ""); x.toFile().setExecutable(true); }
        env.put("PATH", bin.toString());
        Mcp.Detected r = Mcp.detect(root, env);
        Map<String, Mcp.Spec> by = new TreeMap<>();
        for (Mcp.Spec s : r.specs) by.put(s.name, s);
        assertTrue(by.keySet().toString(), by.containsKey("mcp-weather"));
        Mcp.Spec w = by.get("mcp-weather");
        assertEquals("node", w.command);
        assertTrue(w.args.get(0).endsWith("index.js"));
        assertEquals("", w.problem);
        assertTrue(by.get("x-mcp").problem.contains("npm"));
        Mcp.Spec t = by.get("mcp-server-time");
        assertEquals("uv", t.command);
        assertEquals(List.of("run", "--directory", py.toString(), "mcp-server-time"), t.args);
        assertFalse("普通网站项目不算", by.containsKey("site"));
        assertTrue(String.join("|", r.notes), r.notes.stream().anyMatch(n -> n.contains("Dockerfile")));
        // 空目录
        Mcp.Detected e = Mcp.detect(Files.createTempDirectory("empty"), env);
        assertTrue(e.specs.isEmpty());
        assertFalse(e.notes.isEmpty());
    }

    // ------------------------------------------------------------------ stdio 端到端 + 注册 + 审批 + 密钥

    @Test public void stdioServerLoadsRegistersAndCalls() throws Exception {
        String py = python();
        Assume.assumeNotNull(py);
        Path d = mockDir();
        Files.writeString(d.resolve("mcp.json"), "{\"mcpServers\":{\"mock\":{\"command\":\"" + py.replace("\\", "\\\\") + "\",\"args\":[\"mock_server.py\"],\"env\":{\"MOCK_KEY\":\"" + SECRET + "\"}}}}");
        Path state = Files.createTempDirectory("mcp-state").resolve("mcp.json");
        List<String> logs = new ArrayList<>();
        McpHub h = new McpHub(state, logs::add);
        hubs.add(h);
        Map<String, Object> add = h.addSource(d.toString());
        assertEquals(add.toString(), true, add.get("ok"));
        McpHub.Server s = waitReady(h, "mock");
        assertEquals(s.error, "ready", s.status);
        assertEquals("两页工具都拿到了", 2, s.tools.size());
        assertEquals("mock 1.0", s.serverName);
        // 交给模型的工具
        List<Map<String, Object>> defs = h.toolDefs();
        assertEquals(2, defs.size());
        String echoFn = "mcp__mock__echo", writeFn = "mcp__mock__write_note";
        assertTrue(Json.stringify(defs).contains(echoFn));
        PcGuard.MCP = h;
        ChatAgent.extraTools = h::toolDefs;
        try {
            assertTrue(ChatAgent.toolNames().contains(echoFn));
            Object[] m = ChatAgent.map(new Llm.ToolCall("c1", echoFn, "{\"message\":\"hi\",\"description\":\"试一下\"}"));
            assertEquals("mcp_call", m[0]);
            @SuppressWarnings("unchecked") Map<String, Object> ma = (Map<String, Object>) m[1];
            assertEquals(echoFn, ma.get("fn"));
            // 审批分类：只读的不问，有副作用的要批准；只读沙箱拒
            assertEquals(LinkPolicy.Cat.READ_PRIVATE, PcGuard.category("mcp_call", ma, null));
            Map<String, Object> wa = new TreeMap<>(Map.of("fn", writeFn, "arguments", Map.of("text", "x")));
            assertEquals(LinkPolicy.Cat.MCP_TOOL, PcGuard.category("mcp_call", wa, null));
            assertEquals(LinkPolicy.Verdict.CONFIRM, LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.MCP_TOOL, new LinkPolicy.Facts()).verdict);
            LinkPolicy.Mode ro = new LinkPolicy.Mode(LinkPolicy.Sandbox.READ_ONLY, LinkPolicy.Approval.UNTRUSTED);
            assertEquals(LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PC, ro, LinkPolicy.Cat.MCP_TOOL, new LinkPolicy.Facts()).verdict);
            LinkPolicy.Mode never = new LinkPolicy.Mode(LinkPolicy.Sandbox.WORKSPACE_WRITE, LinkPolicy.Approval.NEVER);
            assertNotEquals(LinkPolicy.Verdict.ALLOW, LinkPolicy.decide(LinkPolicy.Target.PC, never, LinkPolicy.Cat.MCP_TOOL, new LinkPolicy.Facts()).verdict);
            // 认不出的函数名也算有副作用
            assertEquals(LinkPolicy.Cat.MCP_TOOL, PcGuard.category("mcp_call", Map.of("fn", "mcp__ghost__x"), null));
            // 执行（审批通过之后走 builtin）
            PcGuard g = new PcGuard(new Config(Files.createTempDirectory("cfg").resolve("c.properties")), null);
            Actions.Result r = g.builtin("mcp_call", ma);
            assertTrue(r.text, r.ok);
            assertEquals("Echo: hi key=set", r.data.get("output"));
            Actions.Result bad = g.builtin("mcp_call", wa);
            assertFalse("isError 的结果如实回失败", bad.ok);
            assertTrue(ChatAgent.describe("mcp_call", ma).contains("mcp__mock__echo") || ChatAgent.describe("mcp_call", ma).contains("MCP"));
        } finally {
            ChatAgent.extraTools = List::of;
        }
        // 密钥不进界面数据、状态文件、日志
        String view = Json.stringify(h.view());
        assertFalse(view.contains(SECRET));
        assertTrue("只给环境变量名", view.contains("MOCK_KEY"));
        assertFalse(Files.readString(state).contains(SECRET));
        for (String l : logs) assertFalse(l, l.contains(SECRET));
        // 停用 → 工具不再给模型；再启用 → 回来；移除 → 记住
        assertEquals(true, h.setEnabled(s.id, false).get("ok"));
        assertTrue(h.toolDefs().isEmpty());
        assertTrue(Files.readString(state).contains("disabled"));
        h.setEnabled(s.id, true);
        waitReady(h, "mock");
        for (int i = 0; i < 50 && h.toolDefs().isEmpty(); i++) Thread.sleep(100);
        assertEquals(2, h.toolDefs().size());
        String id = s.id;
        h.remove(id);
        assertTrue(h.view().get("servers").toString(), ((List<?>) h.view().get("servers")).isEmpty());
        // 重启后按状态文件恢复（被移除的不回来）
        McpHub h2 = new McpHub(state, null);
        hubs.add(h2);
        h2.load();
        h2.startAll();
        assertTrue(((List<?>) h2.view().get("servers")).isEmpty());
    }

    @Test public void startupErrorsAreShownWithoutSecrets() throws Exception {
        String py = python();
        Assume.assumeNotNull(py);
        Path d = Files.createTempDirectory("mcp-crash");
        Files.writeString(d.resolve("crash.py"), "import sys, os\nsys.stderr.write('fatal: bad key ' + os.environ['MOCK_KEY'] + '\\n')\nsys.exit(3)\n");
        Files.writeString(d.resolve("mcp.json"), "{\"mcpServers\":{\"crash\":{\"command\":\"" + py.replace("\\", "\\\\") + "\",\"args\":[\"crash.py\"],\"env\":{\"MOCK_KEY\":\"" + SECRET + "\"}},"
                + "\"missing\":{\"command\":\"definitely-not-a-program-xyz\"}}}");
        McpHub h = new McpHub(Files.createTempDirectory("st").resolve("mcp.json"), null);
        hubs.add(h);
        h.addSource(d.toString());
        McpHub.Server c = waitReady(h, "crash");
        assertEquals("error", c.status);
        assertFalse(c.error, c.error.contains(SECRET));
        assertTrue(c.error, c.error.contains("***") || c.error.contains("退出"));
        McpHub.Server m = waitReady(h, "missing");
        assertEquals("error", m.status);
        assertFalse(m.error.isEmpty());
    }

    // ------------------------------------------------------------------ HTTP / SSE（测试里自己起的 mock）

    static Map<String, Object> answer(Map<String, Object> req) {
        Map<String, Object> r = new TreeMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", req.get("id"));
        String m = String.valueOf(req.get("method"));
        if (m.equals("initialize")) r.put("result", Json.obj("{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"httpmock\",\"version\":\"2\"}}"));
        else if (m.equals("tools/list")) r.put("result", Json.obj("{\"tools\":[{\"name\":\"get_time\",\"inputSchema\":{\"type\":\"object\"},\"annotations\":{\"readOnlyHint\":true}}]}"));
        else if (m.equals("tools/call")) r.put("result", Json.obj("{\"content\":[{\"type\":\"text\",\"text\":\"12:00\"}]}"));
        else r.put("result", new TreeMap<>());
        return r;
    }

    int streamable(boolean sseReply, String needAuth) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.add(s);
        s.createContext("/mcp", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            if (needAuth != null && !needAuth.equals(ex.getRequestHeaders().getFirst("Authorization"))) { ex.sendResponseHeaders(401, -1); ex.close(); return; }
            if (!ex.getRequestMethod().equals("POST")) { ex.sendResponseHeaders(405, -1); ex.close(); return; }
            Map<String, Object> req = Json.obj(new String(in, StandardCharsets.UTF_8));
            if (!req.containsKey("id")) { ex.sendResponseHeaders(202, -1); ex.close(); return; }
            if (!"initialize".equals(req.get("method")) && !"sess-1".equals(ex.getRequestHeaders().getFirst("Mcp-Session-Id"))) { ex.sendResponseHeaders(400, -1); ex.close(); return; }
            ex.getResponseHeaders().add("Mcp-Session-Id", "sess-1");
            byte[] out;
            if (sseReply) {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                out = ("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}\n\nevent: message\ndata: " + Json.stringify(answer(req)) + "\n\n").getBytes(StandardCharsets.UTF_8);
            } else {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                out = Json.stringify(answer(req)).getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(out); }
        });
        s.start();
        return s.getAddress().getPort();
    }

    int legacySse() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.add(s);
        s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        java.util.concurrent.BlockingQueue<String> q = new java.util.concurrent.LinkedBlockingQueue<>();
        s.createContext("/sse", ex -> {
            if (ex.getRequestMethod().equals("POST")) { ex.sendResponseHeaders(405, -1); ex.close(); return; }
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream o = ex.getResponseBody();
            o.write("event: endpoint\ndata: /messages?session=abc\n\n".getBytes());
            o.flush();
            try {
                while (true) {
                    String m = q.poll(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (m == null) break;
                    o.write(("event: message\ndata: " + m + "\n\n").getBytes(StandardCharsets.UTF_8));
                    o.flush();
                }
            } catch (Exception ignored) { }
            ex.close();
        });
        s.createContext("/messages", ex -> {
            Map<String, Object> req = Json.obj(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(202, -1);
            ex.close();
            if (req.containsKey("id") && req.containsKey("method")) q.add(Json.stringify(answer(req)));
        });
        s.start();
        return s.getAddress().getPort();
    }

    Mcp.Spec urlSpec(String url, String transport) {
        Mcp.Spec s = new Mcp.Spec();
        s.name = "h";
        s.url = url;
        s.transport = transport;
        return s;
    }

    @Test public void streamableHttpJsonAndSseRepliesAndLegacySseFallback() throws Exception {
        for (boolean sse : new boolean[] {false, true}) {
            int port = streamable(sse, null);
            Object[] c = Mcp.connect(urlSpec("http://127.0.0.1:" + port + "/mcp", "auto"), 5000);
            Mcp.Client cl = (Mcp.Client) c[0];
            assertEquals("http", c[2]);
            List<Map<String, Object>> tools = Mcp.listTools(cl, 5000);
            assertEquals("get_time", tools.get(0).get("name"));
            Map<String, Object> p = new TreeMap<>();
            p.put("name", "get_time");
            p.put("arguments", new TreeMap<>());
            assertEquals("12:00", Mcp.resultText(cl.request("tools/call", p, 5000)));
            cl.close();
        }
        // 老 SSE：auto 先试 POST 拿到 405，退到 SSE
        int sp = legacySse();
        Object[] c = Mcp.connect(urlSpec("http://127.0.0.1:" + sp + "/sse", "auto"), 5000);
        assertEquals("sse", c[2]);
        Mcp.Client cl = (Mcp.Client) c[0];
        assertEquals(1, Mcp.listTools(cl, 5000).size());
        cl.close();
        // 明写 sse（mock 只有一个消息队列，换一个新的）
        int sp2 = legacySse();
        Object[] c2 = Mcp.connect(urlSpec("http://127.0.0.1:" + sp2 + "/sse", "sse"), 5000);
        ((Mcp.Client) c2[0]).close();
        // 要令牌：没给 → 明说；给对 → 连上；错误里不带令牌
        int ap = streamable(false, "Bearer " + SECRET);
        try {
            Mcp.connect(urlSpec("http://127.0.0.1:" + ap + "/mcp", "http"), 5000);
            fail("应该被拒");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("401"));
        }
        Mcp.Spec ok = urlSpec("http://127.0.0.1:" + ap + "/mcp", "http");
        ok.headers.put("Authorization", "Bearer " + SECRET);
        Object[] c3 = Mcp.connect(ok, 5000);
        ((Mcp.Client) c3[0]).close();
        // 连不上
        try {
            Mcp.connect(urlSpec("http://127.0.0.1:9/mcp?token=" + SECRET, "http"), 3000);
            fail();
        } catch (java.io.IOException e) {
            assertFalse(e.getMessage(), e.getMessage().contains(SECRET));
        }
    }

    @Test public void cleanRedactsSecretsAndToolNamesAreSafe() {
        Mcp.Spec s = new Mcp.Spec();
        s.env.put("K", SECRET);
        assertEquals("bad ***", Mcp.clean("bad " + SECRET, s));
        assertFalse(Mcp.clean("Authorization: Bearer abcdefghijklmnop", null).contains("abcdefghijklmnop"));
        assertFalse(Mcp.clean("token ghp_abcdefghijklmnopqrstu", null).contains("ghp_abcdefghijklmnopqrstu"));
        assertEquals("https://x.com/mcp?token=***", Mcp.redactUrl("https://x.com/mcp?token=abc"));
        assertTrue(Mcp.safe("my server/中文", 20).matches("[A-Za-z0-9_-]+"));
        assertTrue(Mcp.readOnly(Json.obj("{\"annotations\":{\"readOnlyHint\":true}}")));
        assertFalse(Mcp.readOnly(Json.obj("{\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":true}}")));
        assertFalse(Mcp.readOnly(Json.obj("{}")));
    }

    /** 真的开源示例服务器（modelcontextprotocol/servers 的 everything）：设了 ONESAY_MCP_EVERYTHING=<目录> 才跑。 */
    @Test public void officialEverythingServerIfAvailable() throws Exception {
        String dir = System.getenv("ONESAY_MCP_EVERYTHING");
        Assume.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)));
        McpHub h = new McpHub(Files.createTempDirectory("ev").resolve("mcp.json"), null);
        hubs.add(h);
        Map<String, Object> r = h.addSource(dir);
        assertEquals(r.toString(), true, r.get("ok"));
        McpHub.Server s = waitReady(h, "server-everything");
        assertEquals(s.error, "ready", s.status);
        assertTrue(s.tools.size() >= 5);
        assertTrue(h.readOnlyFn("mcp__" + s.id + "__echo"));
        Actions.Result e = h.call("mcp__" + s.id + "__echo", new TreeMap<>(Map.of("message", "你好 OneSay")));
        assertTrue(e.text, e.ok);
        assertEquals("Echo: 你好 OneSay", e.data.get("output"));
    }
}
