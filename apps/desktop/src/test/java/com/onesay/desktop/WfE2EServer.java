package com.onesay.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.sun.net.httpserver.HttpServer;

/**
 * [workflow-1120] 端到端测试用的界面服务：真的 index.html + /workflow.js + /api/wf（真的执行器 / 技能 / 连接器），
 * 别的接口（state 等）用桩数据。key 存内存；大模型是假的（改图时在开始节点后面加一个「AI 加的总结」节点）；审批默认全允许。
 *
 * <p>用法：java ... WfE2EServer 端口 入口钥匙 数据目录 state桩.json [deny]
 * 还会在 端口+1 起一个模拟接口（/v1/models 要 Bearer sk-e2e-good-123456 才 200，/v1/echo 回显请求头）。
 */
public final class WfE2EServer {
    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        String key = a[1];
        Path data = Path.of(a[2]);
        String state = Files.readString(Path.of(a[3]), StandardCharsets.UTF_8);
        boolean deny = a.length > 4 && a[4].equals("deny");
        Files.createDirectories(data);
        Path logFile = data.resolve("onesay.log");
        java.util.function.Consumer<String> log = s -> {
            try { Files.writeString(logFile, s + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException ignored) { }
            System.out.println("[log] " + s);
        };
        WfApi wf = new WfApi(data, new SecretStore.Memory(), log);
        wf.approver((t, d, r) -> { log.accept("审批：" + t + "（" + r + "）→ " + (deny ? "拒绝" : "允许")); return !deny; });
        wf.skillApprover = (t, d) -> { log.accept("技能确认卡：" + d.replace('\n', ' ')); return !deny; };
        wf.llm = (sys, prompt, temp, conn, tmo) -> {
            int i = prompt.indexOf('{');
            int j = prompt.lastIndexOf("\n\n要改的：");
            Map<String, Object> cur = Json.obj(prompt.substring(i, j));
            @SuppressWarnings("unchecked") List<Object> nodes = (List<Object>) cur.get("nodes");
            @SuppressWarnings("unchecked") List<Object> edges = (List<Object>) cur.get("edges");
            Map<String, Object> n = new TreeMap<>();
            n.put("id", "ai_note");
            n.put("type", "llm");
            n.put("meta", Map.of("position", Map.of("x", 260L, "y", 220L)));
            n.put("data", new TreeMap<>(Map.of("title", "AI 加的总结", "prompt", "总结一下")));
            nodes.add(n);
            String start = "start";
            for (Object o : nodes) if ("start".equals(((Map<?, ?>) o).get("type"))) start = String.valueOf(((Map<?, ?>) o).get("id"));
            edges.add(new TreeMap<>(Map.of("sourceNodeID", start, "targetNodeID", "ai_note")));
            log.accept("假模型收到改图请求（" + prompt.length() + " 字）");
            return "好的，改好了：\n" + Json.stringify(cur);
        };
        // 模拟接口
        HttpServer mock = HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port + 1), 8);
        mock.createContext("/", ex -> {
            String auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            String p = ex.getRequestURI().getPath();
            int code = 200;
            String body;
            if (p.endsWith("/models")) { if (auth.equals("Bearer sk-e2e-good-123456")) body = "{\"data\":[{\"id\":\"mock\"}]}"; else { code = 401; body = "{\"error\":\"bad key\"}"; } }
            else if (p.endsWith("/echo")) body = "{\"you_sent\":" + Json.stringify(auth) + "}";
            else body = "{\"ok\":true,\"path\":" + Json.stringify(p) + "}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            try (java.io.OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        mock.start();
        UiServer ui = new UiServer(key);
        ui.on("state", b -> Json.parse(state));
        ui.on("wf", wf::handle);
        for (String p : new String[] {"theme", "confirm", "chat", "notes", "todo", "settings", "mcp", "aon", "habits", "remembered", "chat_rules", "shortcuts", "whitelist", "media", "mirror", "pairing"}) ui.on(p, b -> Map.of("ok", false, "text", "测试桩"));
        if (!ui.start(port)) throw new IOException("端口被占用：" + port);
        System.out.println("READY " + ui.url());
        List<String> keep = new ArrayList<>();
        keep.add("alive");
        Thread.currentThread().join();
    }
}
