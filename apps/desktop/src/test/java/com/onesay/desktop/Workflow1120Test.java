package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** [workflow-1120] 工作流：格式校验、执行器、代码沙箱、技能、API 连接器、OAuth、导出、AI 改图预览、快捷指令。 */
public class Workflow1120Test {
    Path tmp;
    HttpServer mock;
    final List<String> seen = new ArrayList<>();
    final AtomicReference<String> lastAuth = new AtomicReference<>();

    @Before
    public void setUp() throws Exception {
        tmp = Files.createTempDirectory("wf1120");
        mock = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        mock.createContext("/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            lastAuth.set(auth);
            String q = ex.getRequestURI().getRawQuery();
            synchronized (seen) { seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + (q == null ? "" : "?" + q)); }
            String path = ex.getRequestURI().getPath();
            int code = 200;
            String body;
            if (path.endsWith("/echo")) body = "{\"auth\":" + Json.stringify(String.valueOf(auth)) + ",\"q\":" + Json.stringify(String.valueOf(q)) + "}";
            else if (path.endsWith("/models")) body = (auth != null && auth.equals("Bearer sk-good-123456789")) ? "{\"data\":[]}" : "{\"error\":\"bad key\"}";
            else if (path.endsWith("/v1/missing")) { code = 404; body = "{}"; }
            else body = "{\"ok\":true,\"method\":\"" + ex.getRequestMethod() + "\"}";
            if (path.endsWith("/models") && !body.contains("data")) code = 401;
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        mock.start();
    }

    @After
    public void tearDown() {
        mock.stop(0);
        SkillRegistry.deleteTree(tmp);
    }

    String base() { return "http://127.0.0.1:" + mock.getAddress().getPort(); }

    // ------------------------------------------------------------------ 构图小工具

    static Map<String, Object> node(String id, String type, Object... kv) {
        Map<String, Object> n = new TreeMap<>();
        n.put("id", id);
        n.put("type", type);
        Map<String, Object> pos = new TreeMap<>();
        pos.put("x", 0L);
        pos.put("y", 0L);
        n.put("meta", new TreeMap<>(Map.of("position", pos)));
        Map<String, Object> d = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) d.put(String.valueOf(kv[i]), kv[i + 1]);
        n.put("data", d);
        return n;
    }

    static Map<String, Object> edge(String a, String b, String port) {
        Map<String, Object> e = new TreeMap<>();
        e.put("sourceNodeID", a);
        e.put("targetNodeID", b);
        if (port != null) e.put("sourcePortID", port);
        return e;
    }

    static Map<String, Object> wf(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> w = new TreeMap<>();
        w.put("schema", WfSchema.SCHEMA);
        w.put("name", "测试");
        w.put("nodes", new ArrayList<Object>(nodes));
        w.put("edges", new ArrayList<Object>(edges));
        return w;
    }

    /** 条件 + 循环 + 代码：列表里每个数平方；总数 > 2 走「多」分支。 */
    static Map<String, Object> demo() {
        return wf(List.of(
                node("start", "start", "inputs", Json.parse("{\"type\":\"object\",\"properties\":{\"nums\":{\"type\":\"array\",\"items\":{\"type\":\"number\"}}},\"required\":[\"nums\"]}")),
                node("loop1", "loop", "items", "{{input.nums}}"),
                node("sq", "code", "code", "function main(input){ return { v: input.x * input.x }; }", "inputs", Map.of("x", "{{loop1.item}}")),
                node("sum", "code", "code", "return { total: input.list.reduce((a, b) => a + b.v, 0), n: input.list.length };", "inputs", Map.of("list", "{{loop1.results}}")),
                node("c", "condition", "left", "{{sum.n}}", "op", ">", "right", "2"),
                node("many", "code", "code", "return { label: '多' };"),
                node("few", "code", "code", "return { label: '少' };"),
                node("end", "end", "output", Map.of("total", "{{sum.total}}", "many", "{{many.label}}", "few", "{{few.label}}"))),
                List.of(edge("start", "loop1", null), edge("loop1", "sq", "body"), edge("loop1", "sum", "next"), edge("sum", "c", null),
                        edge("c", "many", "true"), edge("c", "few", "false"), edge("many", "end", null), edge("few", "end", null)));
    }

    WfEngine engine(WfEngine.Approver ap) {
        WfEngine.Hooks h = new WfEngine.Hooks();
        h.approver = ap;
        return new WfEngine(h);
    }

    // ------------------------------------------------------------------ 格式校验

    @Test
    public void schemaAcceptsDemo() {
        List<WfSchema.Issue> is = WfSchema.validate(demo());
        assertTrue(is.toString(), WfSchema.ok(is));
    }

    @Test
    public void schemaRejectsCycleDupAndBadPort() {
        Map<String, Object> w = wf(List.of(node("start", "start"), node("a", "code", "code", "return 1"), node("b", "code", "code", "return 2"), node("end", "end")),
                List.of(edge("start", "a", null), edge("a", "b", null), edge("b", "a", null), edge("b", "end", null)));
        assertTrue(codes(w).contains("cycle"));
        Map<String, Object> d = wf(List.of(node("start", "start"), node("a", "code", "code", "x"), node("a", "code", "code", "y"), node("end", "end")), List.of(edge("start", "end", null)));
        assertTrue(codes(d).contains("dup_id"));
        Map<String, Object> p = wf(List.of(node("start", "start"), node("c", "condition", "op", "=="), node("end", "end")), List.of(edge("start", "c", null), edge("c", "end", null)));
        assertTrue(codes(p).contains("cond_port"));
        Map<String, Object> t = wf(List.of(node("start", "start"), node("x", "shell"), node("end", "end")), List.of(edge("start", "end", null)));
        assertTrue(codes(t).contains("bad_type"));
    }

    @Test
    public void schemaLoopBodyMustBeClosed() {
        Map<String, Object> w = wf(List.of(node("start", "start"), node("l", "loop", "items", "[1]"), node("b", "code", "code", "return 1"), node("end", "end")),
                List.of(edge("start", "l", null), edge("l", "b", "body"), edge("b", "end", null), edge("l", "end", "next")));
        assertTrue(codes(w).contains("body_escape"));
    }

    @Test
    public void schemaRejectsInlineSecretsAndBadRefs() {
        Map<String, Object> w = wf(List.of(node("start", "start"), node("a", "api", "connector", "x", "headers", Map.of("Authorization", "Bearer abcdefgh123")), node("end", "end", "output", Map.of("v", "{{nope.x}}"))),
                List.of(edge("start", "a", null), edge("a", "end", null)));
        List<String> c = codes(w);
        assertTrue(c.contains("secret_inline"));
        assertTrue(c.contains("bad_ref"));
    }

    @Test
    public void schemaWarnsUnreachableButAllows() {
        Map<String, Object> w = wf(List.of(node("start", "start"), node("lonely", "code", "code", "return 1"), node("end", "end")), List.of(edge("start", "end", null)));
        List<WfSchema.Issue> is = WfSchema.validate(w);
        assertTrue(WfSchema.ok(is));
        assertTrue(is.stream().anyMatch(i -> i.code.equals("unreachable") && !i.fatal));
    }

    static List<String> codes(Map<String, Object> w) {
        List<String> l = new ArrayList<>();
        for (WfSchema.Issue i : WfSchema.validate(w)) l.add(i.code);
        return l;
    }

    // ------------------------------------------------------------------ 执行器

    @Test
    public void runsConditionLoopAndCode() {
        WfEngine.Run r = engine((t, d, k) -> true).runSync(demo(), Map.of("nums", List.of(1L, 2L, 3L)), 60_000);
        assertEquals(r.error, "done", r.status);
        Map<?, ?> res = (Map<?, ?>) r.result;
        assertEquals(14L, ((Number) res.get("total")).longValue());
        assertEquals("多", res.get("many"));
        assertEquals("", String.valueOf(res.get("few")).replace("null", ""));
        assertEquals("skipped", r.view().get("nodes") instanceof Map ? ((Map<?, ?>) r.view().get("nodes")).get("few") : null);
        // 两项：走「少」分支
        WfEngine.Run r2 = engine((t, d, k) -> true).runSync(demo(), Map.of("nums", List.of(5L, 1L)), 60_000);
        assertEquals("done", r2.status);
        assertEquals("少", ((Map<?, ?>) r2.result).get("few"));
        assertEquals(26L, ((Number) ((Map<?, ?>) r2.result).get("total")).longValue());
    }

    @Test
    public void startInputsValidated() {
        WfEngine.Run r = engine(null).runSync(demo(), Map.of(), 30_000);
        assertEquals("failed", r.status);
        assertTrue(r.error, r.error.contains("nums 必填"));
    }

    @Test
    public void refsKeepTypes() {
        Map<String, Object> vars = new TreeMap<>();
        vars.put("a", Map.of("list", List.of(1L, 2L), "s", "x"));
        assertEquals(List.of(1L, 2L), WfEngine.resolve("{{a.list}}", vars));
        assertEquals("v=[1,2]/x", WfEngine.resolve("v={{a.list}}/{{a.s}}", vars));
        assertEquals(2L, WfEngine.resolve("{{a.list[1]}}", vars));
        assertEquals(2L, WfEngine.resolve("{{a.list.length}}", vars));
        assertTrue(WfEngine.cond("10", ">", "9"));
        assertTrue(WfEngine.cond(List.of("a"), "contains", "a"));
        assertTrue(WfEngine.cond("", "empty", null));
    }

    @Test
    public void nodeTimeoutInterrupts() {
        Map<String, Object> w = wf(List.of(node("start", "start"), node("slow", "code", "code", "while(true){}", "codeTimeoutMs", 800L), node("end", "end")),
                List.of(edge("start", "slow", null), edge("slow", "end", null)));
        long t0 = System.currentTimeMillis();
        WfEngine.Run r = engine(null).runSync(w, Map.of(), 30_000);
        assertEquals("failed", r.status);
        assertTrue(r.error, r.error.contains("超时"));
        assertTrue(System.currentTimeMillis() - t0 < 15_000);
    }

    @Test
    public void cancelStopsRun() throws Exception {
        Map<String, Object> w = wf(List.of(node("start", "start"), node("slow", "code", "code", "while(true){}", "codeTimeoutMs", 20_000L), node("end", "end")),
                List.of(edge("start", "slow", null), edge("slow", "end", null)));
        WfEngine e = engine(null);
        WfEngine.Run r = e.start(w, Map.of(), 60_000);
        Thread.sleep(1500);
        r.cancel();
        for (int i = 0; i < 100 && !r.finished(); i++) Thread.sleep(100);
        assertEquals("cancelled", r.status);
    }

    @Test
    public void onErrorContinue() {
        Map<String, Object> w = wf(List.of(node("start", "start"), node("bad", "code", "code", "throw new Error('坏了')", "onError", "continue"), node("end", "end", "output", Map.of("e", "{{bad.error}}"))),
                List.of(edge("start", "bad", null), edge("bad", "end", null)));
        WfEngine.Run r = engine(null).runSync(w, Map.of(), 30_000);
        assertEquals("done", r.status);
        assertTrue(String.valueOf(((Map<?, ?>) r.result).get("e")).contains("坏了"));
    }

    @Test
    public void approvalDeniedStopsPostApi() throws Exception {
        WfApi api = new WfApi(tmp, new SecretStore.Memory(), null);
        Map<String, Object> in = new TreeMap<>();
        in.put("name", "本地服务");
        in.put("url", base() + "/v1");
        in.put("type", "backend");
        in.put("note", "本机模拟接口");
        in.put("key", "k-abcdef-123");
        Map<String, Object> s = api.api.save(in);
        assertTrue(String.valueOf(s.get("text")), Boolean.TRUE.equals(s.get("ok")));
        String id = String.valueOf(((Map<?, ?>) s.get("connector")).get("id"));
        assertTrue(Boolean.TRUE.equals(api.api.test(id).get("ok")));
        List<String> asked = new ArrayList<>();
        api.approver((t, d, k) -> { asked.add(k + "|" + d); return false; });
        Map<String, Object> w = wf(List.of(node("start", "start"), node("get", "api", "connector", id, "method", "GET", "path", "/a"), node("post", "api", "connector", id, "method", "POST", "path", "/b", "body", Map.of("x", 1L)), node("end", "end")),
                List.of(edge("start", "get", null), edge("get", "post", null), edge("post", "end", null)));
        WfEngine.Run r = api.engine.runSync(w, Map.of(), 30_000);
        assertEquals("denied", r.status);
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).startsWith("outbound|"));
        assertTrue(seen.contains("GET /v1/a"));
        assertFalse(seen.stream().anyMatch(x -> x.startsWith("POST")));
        // 批准后能发
        api.approver((t, d, k) -> true);
        WfEngine.Run r2 = api.engine.runSync(w, Map.of(), 30_000);
        assertEquals(r2.error, "done", r2.status);
        assertTrue(seen.contains("POST /v1/b"));
        assertFalse(Json.stringify(r2.view()).contains("k-abcdef-123"));
    }

    // ------------------------------------------------------------------ 代码沙箱

    @Test
    public void sandboxBasicsAndNoNetOrFiles() {
        WfCode.Result ok = WfCode.run("function main(input){ console.log('hi'); return {s: input.a + 1}; }", Map.of("a", 1L), 3000, 32, null);
        assertTrue(ok.error, ok.ok);
        assertEquals(2L, ((Number) ((Map<?, ?>) ok.value).get("s")).longValue());
        assertTrue(ok.log.contains("hi"));
        WfCode.Result probe = WfCode.run("return { fetch: typeof fetch, req: typeof require, proc: typeof process, std: typeof std, os: typeof os, xhr: typeof XMLHttpRequest, onesay: typeof onesay, deno: typeof Deno };", Map.of(), 3000, 32, null);
        assertTrue(probe.error, probe.ok);
        for (Object v : ((Map<?, ?>) probe.value).values()) assertEquals("undefined", v);
        WfCode.Result imp = WfCode.run("const fs = require('fs'); return fs.readFileSync('C:/Windows/win.ini', 'utf8');", Map.of(), 3000, 32, null);
        assertFalse(imp.ok);
        WfCode.Result net = WfCode.run("return fetch('http://example.com');", Map.of(), 3000, 32, null);
        assertFalse(net.ok);
        WfCode.Result io = WfCode.run("let r; try { r = typeof Javy !== 'undefined' && Javy.IO ? Javy.IO.readSync(3, new Uint8Array(10)) : 'none'; } catch (e) { r = 'blocked:' + e.message; } return {r: String(r)};", Map.of(), 3000, 32, null);
        if (io.ok) assertFalse(String.valueOf(((Map<?, ?>) io.value).get("r")).matches("\\d+") && !String.valueOf(((Map<?, ?>) io.value).get("r")).equals("0") && !String.valueOf(((Map<?, ?>) io.value).get("r")).equals("-1"));
    }

    @Test
    public void sandboxTimeoutAndMemory() {
        long t0 = System.currentTimeMillis();
        WfCode.Result t = WfCode.run("while(true){}", Map.of(), 700, 32, null);
        assertFalse(t.ok);
        assertEquals("timeout", t.kind);
        assertTrue(System.currentTimeMillis() - t0 < 10_000);
        WfCode.Result m = WfCode.run("let a=[]; while(true){ a.push('x'.repeat(100000)); }", Map.of(), 10_000, 16, null);
        assertFalse(m.ok);
        assertEquals(m.error, "memory", m.kind);
        WfCode.Result as = WfCode.run("async function main(){ return 1 }", Map.of(), 3000, 32, null);
        assertFalse(as.ok);
        assertTrue(as.error, as.error.contains("async"));
    }

    @Test
    public void sandboxHostFunctionsOnlyWhenGiven() {
        WfCode.Result r = WfCode.run("return { r: onesay.ping('x') };", Map.of(), 3000, 32, Map.of("ping", s -> "pong:" + s));
        assertTrue(r.error, r.ok);
        assertEquals("pong:x", ((Map<?, ?>) r.value).get("r"));
    }

    // ------------------------------------------------------------------ 技能

    Path example() throws Exception {
        Path d = tmp.resolve("src/text-stats");
        for (String f : new String[] {"SKILL.md", "skill.json", "scripts/main.js"}) {
            Files.createDirectories(d.resolve(f).getParent());
            Files.write(d.resolve(f), UiServer.resource("onesay/skills/text-stats/" + f));
        }
        return d;
    }

    @Test
    public void skillInstallNeedsApprovalAndListsPermissions() throws Exception {
        SkillRegistry reg = new SkillRegistry(tmp.resolve("skills"), null);
        List<String> cards = new ArrayList<>();
        Map<String, Object> no = reg.install(example().toString(), (t, d) -> { cards.add(d); return false; });
        assertFalse(Boolean.TRUE.equals(no.get("ok")));
        assertTrue(reg.list().isEmpty());
        assertTrue(cards.get(0).contains("什么权限都不要"));
        Map<String, Object> yes = reg.install(example().toString(), (t, d) -> true);
        assertTrue(String.valueOf(yes.get("text")), Boolean.TRUE.equals(yes.get("ok")));
        assertEquals(1, reg.palette().size());
        Map<String, Object> pal = reg.palette().get(0);
        assertEquals("text-stats", pal.get("id"));
        Map<?, ?> nodeData = (Map<?, ?>) pal.get("node");
        assertTrue(((Map<?, ?>) nodeData.get("inputs")).containsKey("text"));
        assertNotNull(((Map<?, ?>) nodeData.get("skill")).get("sha256"));
        Map<String, Object> out = reg.run(reg.get("text-stats").ref(), Map.of("text", "a b a\nc"), 5000, null);
        assertEquals(4L, ((Number) out.get("chars")).longValue());
        assertEquals(2L, ((Number) out.get("lines")).longValue());
        assertEquals("a", ((List<?>) out.get("top")).get(0));
    }

    @Test
    public void skillMissingMismatchAndTamper() throws Exception {
        SkillRegistry reg = new SkillRegistry(tmp.resolve("skills"), null);
        assertTrue(reg.check(Map.of("id", "text-stats")).contains("缺少技能"));
        reg.install(example().toString(), (t, d) -> true);
        assertNull(reg.check(reg.get("text-stats").ref()));
        assertTrue(reg.check(Map.of("id", "text-stats", "sha256", "deadbeef")).contains("不一样"));
        Files.writeString(tmp.resolve("skills/text-stats/scripts/main.js"), "function main(){ return {chars: 0} }");
        try {
            reg.run(reg.get("text-stats").ref(), Map.of("text", "x"), 3000, null);
            throw new AssertionError("改过的技能不该运行");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("被改过"));
        }
    }

    @Test
    public void skillDeclarationsChecked() throws Exception {
        Path d = tmp.resolve("py");
        Files.createDirectories(d.resolve("scripts"));
        Files.writeString(d.resolve("SKILL.md"), "---\nname: py thing\nversion: 1.0\n---\nhi");
        Files.writeString(d.resolve("skill.json"), "{\"entry\":{\"runtime\":\"python\",\"script\":\"scripts/a.py\"}}");
        Files.writeString(d.resolve("scripts/a.py"), "print(1)");
        SkillRegistry.Skill k = SkillRegistry.parse(d);
        assertFalse(k.runnable);
        assertTrue(k.reason.contains("Python"));
        Path m = tmp.resolve("mdonly");
        Files.createDirectories(m);
        Files.writeString(m.resolve("SKILL.md"), "---\nname: notes\n---\n只是说明");
        SkillRegistry.Skill k2 = SkillRegistry.parse(m);
        assertFalse(k2.runnable);
        assertTrue(k2.reason.contains("说明"));
        Path b = tmp.resolve("badperm");
        Files.createDirectories(b.resolve("scripts"));
        Files.writeString(b.resolve("SKILL.md"), "---\nname: bad\n---\n");
        Files.writeString(b.resolve("skill.json"), "{\"permissions\":{\"shell\":true},\"inputs\":{\"type\":\"object\",\"pattern\":\"x\"}}");
        Files.writeString(b.resolve("scripts/main.js"), "function main(){return {}}");
        SkillRegistry.Skill k3 = SkillRegistry.parse(b);
        assertFalse(k3.runnable);
        assertTrue(k3.problems.toString(), k3.problems.stream().anyMatch(p -> p.contains("shell")));
        assertTrue(k3.problems.stream().anyMatch(p -> p.contains("pattern")));
    }

    @Test
    public void skillNetOnlyWhenDeclared() throws Exception {
        SkillRegistry reg = new SkillRegistry(tmp.resolve("skills"), null);
        Map<String, Object> spec = new TreeMap<>();
        spec.put("name", "probe");
        spec.put("id", "probe");
        spec.put("code", "function main(){ return { has: typeof onesay !== 'undefined' && typeof onesay.http_get === 'function' }; }");
        spec.put("outputs", Json.parse("{\"type\":\"object\",\"properties\":{\"has\":{\"type\":\"boolean\"}}}"));
        assertTrue(Boolean.TRUE.equals(reg.create(spec, (t, d) -> true).get("ok")));
        assertEquals(false, reg.run(reg.get("probe").ref(), Map.of(), 3000, u -> "{}").get("has"));
        spec.put("id", "probe-net");
        spec.put("name", "probe net");
        spec.put("permissions", Map.of("net", true));
        List<String> cards = new ArrayList<>();
        assertTrue(Boolean.TRUE.equals(reg.create(spec, (t, d) -> { cards.add(d); return true; }).get("ok")));
        assertTrue(cards.get(0).contains("联网"));
        assertEquals(true, reg.run(reg.get("probe-net").ref(), Map.of(), 3000, u -> "{}").get("has"));
        assertEquals("outbound", reg.get("probe-net").risk());
    }

    @Test
    public void skillZipSlipRejected() throws Exception {
        Path z = tmp.resolve("evil.zip");
        try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(z))) {
            zo.putNextEntry(new ZipEntry("../../evil.txt"));
            zo.write("x".getBytes());
            zo.closeEntry();
        }
        SkillRegistry reg = new SkillRegistry(tmp.resolve("skills"), null);
        Map<String, Object> r = reg.install(z.toString(), (t, d) -> true);
        assertFalse(Boolean.TRUE.equals(r.get("ok")));
        assertTrue(String.valueOf(r.get("text")).contains("不安全"));
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
    }

    @Test
    public void skillNodeRunsInWorkflowAndMissingFlagged() throws Exception {
        WfApi api = new WfApi(tmp, new SecretStore.Memory(), null);
        Map<String, Object> w = wf(List.of(node("start", "start"), node("s", "skill", "skill", Map.of("id", "text-stats"), "inputs", Map.of("text", "{{input.t}}")), node("end", "end", "output", Map.of("n", "{{s.chars}}"))),
                List.of(edge("start", "s", null), edge("s", "end", null)));
        Map<?, ?> st = (Map<?, ?>) api.nodeStatus(w).get("s");
        assertEquals(true, st.get("missing"));
        Map<String, Object> b = new TreeMap<>();
        b.put("op", "run");
        b.put("workflow", w);
        assertFalse(Boolean.TRUE.equals(((Map<?, ?>) api.handle(b)).get("ok")));
        api.skillApprover = (t, d) -> true;
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>) api.handle(Map.of("op", "skill_install_example"))).get("ok")));
        assertEquals(false, ((Map<?, ?>) api.nodeStatus(w).get("s")).get("missing"));
        WfEngine.Run r = api.engine.runSync(w, Map.of("t", "你好 世界"), 30_000);
        assertEquals(r.error, "done", r.status);
        assertEquals(4L, ((Number) ((Map<?, ?>) r.result).get("n")).longValue());
    }

    // ------------------------------------------------------------------ API 连接器

    Map<String, Object> conn(String name, String url, String type, String note, String key) {
        Map<String, Object> in = new TreeMap<>();
        in.put("name", name);
        in.put("url", url);
        in.put("type", type);
        if (note != null) in.put("note", note);
        if (key != null) in.put("key", key);
        return in;
    }

    @Test
    public void coldConnectorNeedsNote() {
        ApiConnectors a = new ApiConnectors(tmp.resolve("c.json"), new SecretStore.Memory(), null);
        Map<String, Object> r = a.save(conn("奇怪服务", "https://api.example-unknown.io/v2", "backend", null, "k123456"));
        assertFalse(Boolean.TRUE.equals(r.get("ok")));
        assertTrue(String.valueOf(r.get("text")).contains("备注"));
        assertTrue(a.list().isEmpty());
        Map<String, Object> r2 = a.save(conn("奇怪服务", "https://api.example-unknown.io/v2", "backend", "某某公司的天气接口", "k123456"));
        assertTrue(Boolean.TRUE.equals(r2.get("ok")));
        Map<String, Object> r3 = a.save(conn("DeepSeek", "https://api.deepseek.com/v1", "llm", null, "sk-x123456"));
        assertTrue(String.valueOf(r3.get("text")), Boolean.TRUE.equals(r3.get("ok")));
        assertEquals("openai", ((Map<?, ?>) r3.get("connector")).get("preset"));
        assertEquals("openai", ApiConnectors.detect("http://localhost:11434/v1", "llm")[0]);
        assertNull(ApiConnectors.detect("http://localhost:11434/v1", "image"));
        assertEquals("anthropic", ApiConnectors.detect("https://api.anthropic.com", "llm")[0]);
    }

    @Test
    public void connectorRulesRejectUnsafeInput() {
        ApiConnectors a = new ApiConnectors(tmp.resolve("c.json"), new SecretStore.Memory(), null);
        assertFalse(Boolean.TRUE.equals(a.save(conn("x", "http://api.openai.com/v1", "llm", null, "k")).get("ok")));
        assertFalse(Boolean.TRUE.equals(a.save(conn("x", "https://api.openai.com/v1?key=abc", "llm", null, "k")).get("ok")));
        assertFalse(Boolean.TRUE.equals(a.save(conn("x", "https://u:p@api.openai.com/v1", "llm", null, "k")).get("ok")));
        Map<String, Object> h = conn("x", "https://api.openai.com/v1", "llm", null, "k");
        h.put("key_mode", "header");
        assertFalse(Boolean.TRUE.equals(a.save(h).get("ok")));
        assertEquals("read", ApiConnectors.risk(null, "GET"));
        assertEquals("outbound", ApiConnectors.risk(null, "POST"));
    }

    @Test
    public void connectorTestKeyStorageAndRedaction() throws Exception {
        SecretStore.Memory mem = new SecretStore.Memory();
        ApiConnectors a = new ApiConnectors(tmp.resolve("c.json"), mem, null);
        Map<String, Object> bad = a.save(conn("本地模型", base() + "/v1", "llm", null, "sk-bad-000000000"));
        String id = String.valueOf(((Map<?, ?>) bad.get("connector")).get("id"));
        Map<String, Object> t1 = a.test(id);
        assertFalse(Boolean.TRUE.equals(t1.get("ok")));
        assertTrue(String.valueOf(t1.get("text")).contains("key 不对"));
        Map<String, Object> upd = conn("本地模型", base() + "/v1", "llm", null, "sk-good-123456789");
        upd.put("id", id);
        a.save(upd);
        Map<String, Object> t2 = a.test(id);
        assertTrue(String.valueOf(t2.get("text")), Boolean.TRUE.equals(t2.get("ok")));
        assertTrue(a.usable(a.get(id)));
        String file = Files.readString(tmp.resolve("c.json"));
        assertFalse(file.contains("sk-good"));
        assertFalse(file.contains("sk-bad"));
        assertEquals("sk-good-123456789", mem.get(SecretStore.name("api", id)));
        // 回显 key 的接口：结果里抹掉
        ApiConnectors.Resp r = a.send(a.get(id), "GET", "/echo", null, null, null, 10);
        assertFalse(r.body, r.body.contains("sk-good-123456789"));
        assertTrue(r.body.contains("••••"));
        assertFalse(Json.stringify(a.list()).contains("sk-good"));
        // 改 key 后要重测
        upd.put("key", "sk-other-55555555");
        a.save(upd);
        assertFalse(a.usable(a.get(id)));
        // 重新加载：仍然没有 key
        ApiConnectors b = new ApiConnectors(tmp.resolve("c.json"), mem, null);
        assertTrue(b.get(id).hasKey);
    }

    @Test
    public void connectorQueryKeyMode() {
        ApiConnectors a = new ApiConnectors(tmp.resolve("c.json"), new SecretStore.Memory(), null);
        Map<String, Object> in = conn("q", base() + "/svc", "backend", "本机", "qk-777777");
        in.put("key_mode", "query");
        in.put("key_name", "key");
        String id = String.valueOf(((Map<?, ?>) a.save(in).get("connector")).get("id"));
        ApiConnectors.Resp r = a.send(a.get(id), "GET", "/echo", Map.of("a", "1"), null, null, 10);
        assertEquals(200, r.status);
        assertTrue(seen.stream().anyMatch(x -> x.contains("key=qk-777777")));
        assertFalse(r.body.contains("qk-777777"));
    }

    @Test
    public void winCredScriptNeverCarriesSecret() {
        String[] cmd = SecretStore.WinCred.command("put", "OneSay-Preview:api:x");
        String all = String.join(" ", cmd);
        assertFalse(all.contains("sk-"));
        String script = SecretStore.WinCred.script("put", "OneSay-Preview:api:x");
        assertTrue(script.contains("[Console]::In.ReadLine()"));
        assertTrue(script.contains("CredWriteW"));
        assertTrue(SecretStore.WinCred.script("get", "a'b").contains("'a''b'"));
    }

    // ------------------------------------------------------------------ 导出 / 导入 / 改图

    @Test
    public void exportCarriesNoCredentials() {
        SecretStore.Memory mem = new SecretStore.Memory();
        WfApi api = new WfApi(tmp, mem, null);
        Map<String, Object> c = api.api.save(conn("本地", base() + "/v1", "backend", "模拟", "secret-key-99999"));
        String id = String.valueOf(((Map<?, ?>) c.get("connector")).get("id"));
        Map<String, Object> w = wf(List.of(node("start", "start"), node("a", "api", "connector", id, "path", "/x"), node("end", "end")), List.of(edge("start", "a", null), edge("a", "end", null)));
        // 有人手写 token 进去（绕过校验直接写的旧文件）
        ((Map<String, Object>) WfSchema.data(WfSchema.nodes(w).get(1))).put("token", "ghp_abcdefghijklmnopqrstuvwxyz");
        ((Map<String, Object>) WfSchema.data(WfSchema.nodes(w).get(1))).put("note", "sk-abcdefghijklmnop12345");
        Map<String, Object> b = new TreeMap<>();
        b.put("op", "export");
        b.put("workflow", w);
        String json = String.valueOf(((Map<?, ?>) api.handle(b)).get("json"));
        assertFalse(json.contains("secret-key-99999"));
        assertFalse(json.contains("ghp_"));
        assertFalse(json.contains("sk-abcdef"));
        assertTrue(json.contains("\"connectors\""));
        assertTrue(json.contains("本地"));
        // 导入到一台没有这个连接的机器：列出缺的连接
        WfApi other = new WfApi(tmp.resolve("other"), new SecretStore.Memory(), null);
        Map<String, Object> chk = WfStore.importCheck(Json.obj(json), other.skills, other.api);
        assertEquals(1, ((List<?>) chk.get("missing_connectors")).size());
        assertTrue(String.valueOf(chk.get("text")).contains("缺 1 个连接"));
    }

    @Test
    public void aiEditMakesProposalNotChange() {
        WfApi api = new WfApi(tmp, new SecretStore.Memory(), null);
        Map<String, Object> w = demo();
        w.put("name", "演示");
        Map<String, Object> saved = api.store.save(w);
        assertTrue(String.valueOf(saved.get("text")), Boolean.TRUE.equals(saved.get("ok")));
        String id = String.valueOf(((Map<?, ?>) saved.get("workflow")).get("id"));
        api.llm = (sys, prompt, t, c, tmo) -> {
            Map<String, Object> after = demo();
            ((List<Object>) after.get("nodes")).add(node("note", "llm", "prompt", "总结：{{sum.total}}"));
            ((List<Object>) after.get("edges")).add(edge("sum", "note", null));
            return "好的：\n" + Json.stringify(after);
        };
        Map<String, Object> b = new TreeMap<>();
        b.put("op", "ai_edit");
        b.put("id", id);
        b.put("instruction", "加一个总结");
        Map<?, ?> r = (Map<?, ?>) api.handle(b);
        assertTrue(String.valueOf(r.get("text")), Boolean.TRUE.equals(r.get("ok")));
        Map<?, ?> diff = (Map<?, ?>) ((Map<?, ?>) r.get("proposal")).get("diff");
        assertEquals(1, ((List<?>) diff.get("added")).size());
        assertEquals(1, ((List<?>) diff.get("edges_added")).size());
        // 文件没变
        assertEquals(8, WfSchema.nodes(api.store.get(id)).size());
        String pid = String.valueOf(((Map<?, ?>) r.get("proposal")).get("id"));
        Map<?, ?> ap = (Map<?, ?>) api.handle(Map.of("op", "proposal_apply", "proposal", pid));
        assertTrue(Boolean.TRUE.equals(ap.get("ok")));
        assertEquals(9, WfSchema.nodes((Map<String, Object>) ap.get("workflow")).size());
        assertFalse(Boolean.TRUE.equals(((Map<?, ?>) api.handle(Map.of("op", "proposal_apply", "proposal", pid))).get("ok")));
        // 改坏的不采用
        api.llm = (sys, prompt, t, c, tmo) -> "{\"nodes\":[{\"id\":\"x\",\"type\":\"start\"}],\"edges\":[]}";
        assertFalse(Boolean.TRUE.equals(((Map<?, ?>) api.handle(b)).get("ok")));
    }

    @Test
    public void saveReloadShortcutAndDraft() throws Exception {
        WfApi api = new WfApi(tmp, new SecretStore.Memory(), null);
        Map<String, Object> w = demo();
        Map<String, Object> saved = api.store.save(w);
        String id = String.valueOf(((Map<?, ?>) saved.get("workflow")).get("id"));
        Map<String, Object> again = new WfStore(tmp.resolve("workflows"), null).get(id);
        assertEquals(Json.stringify(WfSchema.nodes(((Map<String, Object>) saved.get("workflow")))), Json.stringify(WfSchema.nodes(again)));
        assertTrue(Boolean.TRUE.equals(api.store.setShortcut(id, "平方和").get("ok")));
        assertEquals(1, api.store.shortcuts().size());
        Map<String, Object> b = new TreeMap<>();
        b.put("op", "shortcut_run");
        b.put("name", "平方和");
        b.put("input", Map.of("nums", List.of(3L)));
        Map<?, ?> r = (Map<?, ?>) api.handle(b);
        assertTrue(String.valueOf(r.get("text")), Boolean.TRUE.equals(r.get("ok")));
        String rid = String.valueOf(((Map<?, ?>) r.get("run")).get("id"));
        WfEngine.Run run = api.engine.run(rid);
        for (int i = 0; i < 200 && !run.finished(); i++) Thread.sleep(50);
        assertEquals("done", run.status);
        Map<String, Object> d = WfStore.draftFromSteps("查天气", List.of(Map.of("tool", "mcp__weather__get", "args", Map.of("city", "上海", "api_key", "zzz"), "description", "查上海天气"), Map.of("tool", "run_command", "description", "整理结果")));
        assertTrue(WfSchema.ok(WfSchema.validate(d)));
        assertFalse(Json.stringify(d).contains("zzz"));
        assertEquals(4, WfSchema.nodes(d).size());
    }

    @Test
    public void invalidSaveRejected() {
        WfStore st = new WfStore(tmp.resolve("w"), null);
        Map<String, Object> r = st.save(wf(List.of(node("start", "start")), List.of()));
        assertFalse(Boolean.TRUE.equals(r.get("ok")));
        assertTrue(String.valueOf(r.get("text")).contains("缺结束节点"));
    }

    // ------------------------------------------------------------------ OAuth（模拟服务器）

    @Test
    public void oauthDeviceFlowWithMock() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4);
        srv.createContext("/device", ex -> reply(ex, "{\"device_code\":\"dc1\",\"user_code\":\"ABCD-1234\",\"verification_uri\":\"https://example.test/device\",\"interval\":1,\"expires_in\":60}"));
        srv.createContext("/token", ex -> reply(ex, polls.incrementAndGet() < 2 ? "{\"error\":\"authorization_pending\"}" : "{\"access_token\":\"gho_mocktoken1234567890\",\"token_type\":\"bearer\"}"));
        srv.start();
        try {
            SecretStore.Memory mem = new SecretStore.Memory();
            OAuthHub h = new OAuthHub(mem, null);
            OAuthHub.Provider p = h.provider("github");
            String b = "http://127.0.0.1:" + srv.getAddress().getPort();
            p.deviceUrl = b + "/device";
            p.tokenUrl = b + "/token";
            assertFalse(Boolean.TRUE.equals(h.start("github").get("ok"))); // 没填 client_id
            h.setClientId("github", "Iv1.mockclient");
            Map<String, Object> st = h.start("github");
            assertTrue(String.valueOf(st.get("text")), Boolean.TRUE.equals(st.get("ok")));
            assertTrue(String.valueOf(st.get("text")).contains("ABCD-1234"));
            String pid = String.valueOf(((Map<?, ?>) st.get("pending")).get("id"));
            for (int i = 0; i < 60 && !"done".equals(((Map<?, ?>) h.poll(pid).get("pending")).get("state")); i++) Thread.sleep(200);
            assertEquals("done", ((Map<?, ?>) h.poll(pid).get("pending")).get("state"));
            assertEquals("gho_mocktoken1234567890", mem.get(SecretStore.name("oauth", "github")));
            assertFalse(Json.stringify(h.list()).contains("gho_"));
            assertFalse(Json.stringify(h.poll(pid)).contains("gho_"));
            assertTrue(h.connected("github"));
        } finally {
            srv.stop(0);
        }
    }

    @Test
    public void oauthLoopbackFlowWithMockAndStateCheck() throws Exception {
        AtomicReference<String> verifier = new AtomicReference<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4);
        srv.createContext("/token", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            verifier.set(OAuthHub.query(body).get("code_verifier"));
            reply(ex, OAuthHub.query(body).get("code").equals("c0de") ? "{\"access_token\":\"tok-loop-123456\"}" : "{\"error\":\"bad_code\"}");
        });
        srv.start();
        try {
            SecretStore.Memory mem = new SecretStore.Memory();
            OAuthHub h = new OAuthHub(mem, null);
            OAuthHub.Provider p = new OAuthHub.Provider();
            p.id = "mock";
            p.name = "模拟";
            p.flow = "loopback";
            p.authorizeUrl = "https://auth.example.test/authorize";
            p.tokenUrl = "http://127.0.0.1:" + srv.getAddress().getPort() + "/token";
            p.clientId = "client-1";
            h.put(p);
            AtomicReference<String> opened = new AtomicReference<>();
            h.opener = opened::set;
            Map<String, Object> st = h.start("mock");
            assertTrue(Boolean.TRUE.equals(st.get("ok")));
            String auth = opened.get();
            assertTrue(auth.contains("code_challenge_method=S256"));
            Map<String, String> q = OAuthHub.query(URI.create(auth).getRawQuery());
            String redirect = q.get("redirect_uri");
            assertTrue(redirect.startsWith("http://127.0.0.1:"));
            HttpClient c = HttpClient.newHttpClient();
            String forged = c.send(HttpRequest.newBuilder(URI.create(redirect + "?code=c0de&state=wrong")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(forged.contains("state 对不上"));
            assertNull(mem.get(SecretStore.name("oauth", "mock")));
            String okPage = c.send(HttpRequest.newBuilder(URI.create(redirect + "?code=c0de&state=" + OAuthHub.enc(q.get("state")))).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(okPage, okPage.contains("已连接"));
            assertEquals("tok-loop-123456", mem.get(SecretStore.name("oauth", "mock")));
            assertNotNull(verifier.get());
            assertTrue(verifier.get().length() >= 43);
        } finally {
            srv.stop(0);
        }
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    // ------------------------------------------------------------------ 对话工具 / 能力

    @Test
    public void chatToolsMapAndCapabilities() throws Exception {
        Object[] m = ChatAgent.map(new Llm.ToolCall("1", "wf__list", "{\"description\":\"看看工作流\"}"));
        assertEquals("wf_call", m[0]);
        assertEquals("wf__list", ((Map<?, ?>) m[1]).get("fn"));
        assertTrue(WfApi.readOnlyTool("wf__propose"));
        assertFalse(WfApi.readOnlyTool("wf__skill_install"));
        assertTrue(WfApi.toolDefs().size() >= 8);
        WfApi api = new WfApi(tmp, new SecretStore.Memory(), null);
        api.skillApprover = (t, d) -> true;
        api.handle(Map.of("op", "skill_install_example"));
        api.api.save(conn("本地", base() + "/v1", "backend", "模拟", "kkk-123456"));
        List<Map<String, Object>> caps = api.capabilities();
        assertTrue(caps.stream().anyMatch(c -> "skill:text-stats".equals(c.get("id"))));
        assertTrue(caps.stream().anyMatch(c -> String.valueOf(c.get("id")).startsWith("api:")));
        assertTrue(caps.stream().anyMatch(c -> "oauth:github".equals(c.get("id"))));
        assertFalse(Json.stringify(caps).contains("kkk-123456"));
        Actions.Result lr = api.callTool("wf__list", Map.of());
        assertTrue(lr.ok);
        Map<String, Object> saved = api.store.save(demo());
        String id = String.valueOf(((Map<?, ?>) saved.get("workflow")).get("id"));
        Actions.Result ar = api.callTool("wf__add_skill_node", Map.of("workflow_id", id, "skill_id", "text-stats", "after_node", "sum"));
        assertTrue(ar.text, ar.ok);
        assertEquals(8, WfSchema.nodes(api.store.get(id)).size()); // 只是预览
        assertEquals(1, api.store.pending(id).size());
        PcGuard.WF = api;
        try {
            assertEquals(LinkPolicy.Cat.READ_PRIVATE, PcGuard.category("wf_call", Map.of("fn", "wf__list"), tmp.toFile()));
        } finally {
            PcGuard.WF = null;
        }
    }

    @Test
    public void uiServesCanvasBundle() throws Exception {
        UiServer ui = new UiServer();
        assertTrue(ui.start(0));
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(ui.baseUrl() + "workflow.js")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(r.body().length() > 1000);
            assertTrue(r.body().contains("OneSayWorkflow"));
        } finally {
            ui.stop();
        }
    }
}
