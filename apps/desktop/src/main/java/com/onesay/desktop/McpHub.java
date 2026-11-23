package com.onesay.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * [preview-1120] MCP 连接器的「插座」：记住用户选过的目录，按目录识别出服务器、启动、列工具，给对话的模型一份工具清单，转发工具调用。
 *
 * <p>状态文件（配置目录 mcp.json）只存：选过的目录、哪些服务器停用 / 移除了。env / headers 不存——每次启动从用户自己的配置文件重新读。
 */
public final class McpHub {
    public static final long INIT_MS = 30_000, CALL_MS = 120_000;
    public static final int MAX_TOOLS = 80;

    /** 一个服务器的现状。 */
    public static final class Server {
        public final String id, source;
        public final Mcp.Spec spec;
        public volatile String status = "stopped"; // stopped / starting / ready / error / disabled / unsupported
        public volatile String error = "";
        public volatile String serverName = "", transportUsed = "";
        public volatile List<Map<String, Object>> tools = new ArrayList<>();
        volatile Mcp.Client client;
        public volatile long since;

        Server(String id, String source, Mcp.Spec spec) {
            this.id = id;
            this.source = source;
            this.spec = spec;
        }

        public Map<String, Object> view() {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", id);
            m.put("name", spec.name);
            m.put("source", source);
            m.put("origin", spec.origin);
            m.put("transport", transportUsed.isEmpty() ? spec.transport : transportUsed);
            m.put("launch", spec.shown());
            m.put("status", status);
            m.put("status_zh", statusZh(status));
            m.put("error", error);
            m.put("server_name", serverName);
            m.put("tool_count", (long) tools.size());
            List<Object> ts = new ArrayList<>();
            for (Map<String, Object> t : tools) {
                Map<String, Object> x = new TreeMap<>();
                x.put("name", Mcp.str(t.get("name")));
                x.put("title", Mcp.str(t.get("title")));
                x.put("description", ChatAgent.clip(Mcp.str(t.get("description")), 160));
                x.put("read_only", Mcp.readOnly(t));
                if (Mcp.readOnly(t)) x.put("schema", formSchema(t.get("inputSchema"))); // [fix-1123-a] 界面「试一个只读工具」按参数说明生成表单
                ts.add(x);
            }
            m.put("tools", ts);
            m.put("env_keys", new ArrayList<Object>(spec.env.keySet())); // 只给名字，不给值
            return m;
        }
    }

    /** [fix-1123-a] 参数说明 → 界面表单要的那点：每个参数的类型 / 说明 / 可选值 / 默认值、哪些必填（最多 20 个参数，说明截到 160 字）。 */
    static Map<String, Object> formSchema(Object raw) {
        Map<String, Object> out = new TreeMap<>();
        Map<String, Object> props = new LinkedHashMap<>();
        List<Object> req = new ArrayList<>();
        if (raw instanceof Map) {
            Object ps = ((Map<?, ?>) raw).get("properties");
            if (ps instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) ps).entrySet()) {
                if (props.size() >= 20) break;
                String k = String.valueOf(e.getKey());
                if (k.isEmpty() || k.length() > 64) continue;
                Map<String, Object> f = new TreeMap<>();
                Map<?, ?> v = e.getValue() instanceof Map ? (Map<?, ?>) e.getValue() : Map.of();
                String type = Mcp.str(v.get("type"));
                if (v.get("type") instanceof List && !((List<?>) v.get("type")).isEmpty()) type = String.valueOf(((List<?>) v.get("type")).get(0));
                f.put("type", List.of("string", "number", "integer", "boolean", "array", "object").contains(type) ? type : "string");
                f.put("description", ChatAgent.clip(Mcp.str(v.get("description")), 160));
                if (v.get("enum") instanceof List) {
                    List<Object> en = new ArrayList<>();
                    for (Object o : (List<?>) v.get("enum")) if (en.size() < 30 && o != null) en.add(String.valueOf(o));
                    if (!en.isEmpty()) f.put("enum", en);
                }
                Object d = v.get("default");
                if (d instanceof String || d instanceof Number || d instanceof Boolean) f.put("default", d);
                props.put(k, f);
            }
            Object rq = ((Map<?, ?>) raw).get("required");
            if (rq instanceof List) for (Object o : (List<?>) rq) if (o != null && props.containsKey(String.valueOf(o))) req.add(String.valueOf(o));
        }
        out.put("properties", props);
        out.put("required", req);
        return out;
    }

    static String statusZh(String s) {
        switch (s) {
            case "ready": return "已连接";
            case "starting": return "启动中";
            case "error": return "出错";
            case "disabled": return "已停用";
            case "unsupported": return "用不了";
            default: return "没启动";
        }
    }

    final Path stateFile;
    final Consumer<String> log;
    final Map<String, Server> servers = new LinkedHashMap<>();
    /** 选过的目录 → {disabled: [...名字], removed: [...名字]} */
    final Map<String, Map<String, List<String>>> sources = new LinkedHashMap<>();
    final List<String> notes = new ArrayList<>();
    final ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "onesay-mcp"); t.setDaemon(true); return t; });

    public McpHub(Path stateFile, Consumer<String> log) {
        this.stateFile = stateFile;
        this.log = log == null ? s -> { } : log;
    }

    // ------------------------------------------------------------------ 状态文件

    @SuppressWarnings("unchecked")
    public synchronized void load() {
        sources.clear();
        if (stateFile == null || !Files.isRegularFile(stateFile)) return;
        try {
            Map<String, Object> m = Json.obj(Files.readString(stateFile, StandardCharsets.UTF_8));
            if (m.get("sources") instanceof List) for (Object o : (List<Object>) m.get("sources")) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> x = (Map<String, Object>) o;
                String p = Mcp.str(x.get("path"));
                if (p.isEmpty()) continue;
                Map<String, List<String>> st = new TreeMap<>();
                st.put("disabled", strs(x.get("disabled")));
                st.put("removed", strs(x.get("removed")));
                sources.put(p, st);
            }
        } catch (Exception e) {
            log.accept("MCP 设置文件读不出来，按空的来");
        }
    }

    static List<String> strs(Object o) {
        List<String> l = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<?>) o) if (x != null) l.add(String.valueOf(x));
        return l;
    }

    synchronized void save() {
        if (stateFile == null) return;
        List<Object> l = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<String>>> e : sources.entrySet()) {
            Map<String, Object> x = new TreeMap<>();
            x.put("path", e.getKey());
            x.put("disabled", new ArrayList<Object>(e.getValue().getOrDefault("disabled", List.of())));
            x.put("removed", new ArrayList<Object>(e.getValue().getOrDefault("removed", List.of())));
            l.add(x);
        }
        Map<String, Object> root = new TreeMap<>();
        root.put("sources", l);
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, Json.stringify(root), StandardCharsets.UTF_8);
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.accept("MCP 设置存不下：" + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ 加 / 停 / 移除

    /** 选了一个目录（或配置文件）：识别，加进来并启动认出的服务器。返回 {ok, text, added, notes}。 */
    public Map<String, Object> addSource(String path) {
        Map<String, Object> out = new TreeMap<>();
        Path p;
        try { p = Paths.get(path == null ? "" : path.trim().replaceAll("^\"|\"$", "")).toAbsolutePath().normalize(); } catch (Exception e) { return res(false, "路径不对"); }
        if (!Files.exists(p)) return res(false, "找不到「" + p + "」");
        // [fix-1123-a] 选的是项目清单（package.json …）= 选了它所在的文件夹：同一个来源，不再各算一条
        if (Files.isRegularFile(p) && p.getParent() != null && Mcp.PROJECT_FILES.contains(p.getFileName().toString().toLowerCase(java.util.Locale.ROOT))) p = p.getParent();
        final Path src = p;
        Mcp.Detected d = Mcp.detect(p);
        if (d.specs.isEmpty()) {
            out.put("ok", false);
            out.put("text", d.notes.isEmpty() ? "这里没认出 MCP 服务器" : String.join("；", d.notes));
            out.put("notes", new ArrayList<Object>(d.notes));
            return out;
        }
        List<Server> added;
        synchronized (this) {
            Map<String, List<String>> st = sources.computeIfAbsent(p.toString(), k -> { Map<String, List<String>> m = new TreeMap<>(); m.put("disabled", new ArrayList<>()); m.put("removed", new ArrayList<>()); return m; });
            st.get("removed").clear(); // 重新选这个目录 = 之前移除的也回来
            save();
            added = rebuild(src.toString(), d);
            if (added.isEmpty()) { sources.remove(src.toString()); save(); } // 全是已经加过的：这个来源不留
            notes.removeIf(n -> n.startsWith(src + "："));
            for (String n : d.notes) notes.add(src + "：" + n);
        }
        if (added.isEmpty()) {
            out.put("ok", false);
            out.put("text", d.notes.isEmpty() ? "这里的服务器都已经加过了" : String.join("；", d.notes));
            out.put("notes", new ArrayList<Object>(d.notes));
            return out;
        }
        for (Server s : added) startAsync(s);
        out.put("ok", true);
        List<Object> names = new ArrayList<>();
        for (Server s : added) names.add(s.spec.name);
        out.put("added", names);
        out.put("notes", new ArrayList<Object>(d.notes));
        out.put("text", "认出 " + added.size() + " 个 MCP 服务器：" + String.join("、", names.stream().map(String::valueOf).toArray(String[]::new)) + (d.notes.isEmpty() ? "" : "（另有提示：" + String.join("；", d.notes) + "）"));
        log.accept("MCP：加了目录，认出 " + added.size() + " 个服务器");
        return out;
    }

    /** 这个目录的服务器按识别结果重建（旧的先关）。调用方持锁。 */
    List<Server> rebuild(String source, Mcp.Detected d) {
        List<String> drop = new ArrayList<>();
        for (Server s : servers.values()) if (s.source.equals(source)) drop.add(s.id);
        for (String id : drop) { Server s = servers.remove(id); if (s != null) stopQuiet(s); }
        Map<String, List<String>> st = sources.get(source);
        List<Server> out = new ArrayList<>();
        for (Mcp.Spec sp : d.specs) {
            if (st != null && st.get("removed").contains(sp.name)) continue;
            // [fix-1123-a] 同一个服务器（地址一样，或程序 + 参数 + 工作目录一样）已经从别的来源加过：不再加一条
            Server same = null;
            for (Server o : servers.values()) if (!o.source.equals(source) && o.spec.identity().equals(sp.identity())) { same = o; break; }
            boolean twice = false;
            for (Server o : out) if (o.spec.identity().equals(sp.identity())) twice = true;
            if (same != null || twice) { d.notes.add("「" + sp.name + "」已经加过了" + (same == null ? "" : "（来自 " + same.source + "）") + "，没再加一条"); continue; }
            String base = Mcp.safe(sp.name, 20), id = base;
            for (int i = 2; servers.containsKey(id); i++) id = Mcp.safe(base, 17) + "_" + i;
            Server s = new Server(id, source, sp);
            if (!sp.problem.isEmpty()) { s.status = "unsupported"; s.error = sp.problem; }
            else if (st != null && st.get("disabled").contains(sp.name)) s.status = "disabled";
            servers.put(id, s);
            out.add(s);
        }
        return out;
    }

    /** 程序启动时：按记住的目录重新识别并启动（异步，不挡启动）。 */
    public void startAll() {
        List<Server> go = new ArrayList<>();
        synchronized (this) {
            for (String src : new ArrayList<>(sources.keySet())) {
                Path p = Paths.get(src);
                Mcp.Detected d = Mcp.detect(p);
                if (!Files.exists(p)) d.notes.add("目录不在了");
                notes.removeIf(n -> n.startsWith(src + "："));
                for (String n : d.notes) notes.add(src + "：" + n);
                go.addAll(rebuild(src, d));
            }
        }
        for (Server s : go) startAsync(s);
    }

    void startAsync(Server s) {
        if (!"stopped".equals(s.status) && !"error".equals(s.status)) return;
        s.status = "starting";
        s.error = "";
        s.since = System.currentTimeMillis();
        pool.submit(() -> start(s));
    }

    /** 启动 + initialize + tools/list（同步）。 */
    void start(Server s) {
        try {
            Object[] c = Mcp.connect(s.spec, INIT_MS);
            Mcp.Client cl = (Mcp.Client) c[0];
            @SuppressWarnings("unchecked") Map<String, Object> init = (Map<String, Object>) c[1];
            List<Map<String, Object>> tools = Mcp.listTools(cl, INIT_MS);
            s.client = cl;
            s.tools = tools;
            s.transportUsed = String.valueOf(c[2]);
            Object si = init.get("serverInfo");
            s.serverName = si instanceof Map ? Mcp.str(((Map<?, ?>) si).get("name")) + (((Map<?, ?>) si).get("version") != null ? " " + ((Map<?, ?>) si).get("version") : "") : "";
            s.status = "ready";
            s.error = tools.isEmpty() ? "服务器没有提供工具" : "";
            log.accept("MCP「" + s.spec.name + "」已连接，" + tools.size() + " 个工具");
        } catch (Exception e) {
            s.status = "error";
            s.error = Mcp.clean(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), s.spec);
            s.client = null;
            log.accept("MCP「" + s.spec.name + "」没连上：" + s.error);
        }
    }

    void stopQuiet(Server s) {
        Mcp.Client c = s.client;
        s.client = null;
        s.tools = new ArrayList<>();
        if (c != null) try { c.close(); } catch (Exception ignored) { }
    }

    public synchronized Map<String, Object> setEnabled(String id, boolean on) {
        Server s = servers.get(id);
        if (s == null) return res(false, "没有这个服务器");
        Map<String, List<String>> st = sources.get(s.source);
        if (st != null) { st.get("disabled").remove(s.spec.name); if (!on) st.get("disabled").add(s.spec.name); save(); }
        if (!on) {
            stopQuiet(s);
            if (!"unsupported".equals(s.status)) s.status = "disabled";
            s.error = "";
            return res(true, "已停用「" + s.spec.name + "」，它的工具不再交给模型");
        }
        if ("unsupported".equals(s.status)) return res(false, "「" + s.spec.name + "」用不了：" + s.error);
        if (!"ready".equals(s.status)) { s.status = "stopped"; startAsync(s); }
        return res(true, "正在启动「" + s.spec.name + "」");
    }

    public synchronized Map<String, Object> restart(String id) {
        Server s = servers.get(id);
        if (s == null) return res(false, "没有这个服务器");
        if ("unsupported".equals(s.status) || "disabled".equals(s.status)) return res(false, "「" + s.spec.name + "」现在是" + statusZh(s.status) + "，先启用");
        stopQuiet(s);
        s.status = "stopped";
        startAsync(s);
        return res(true, "正在重新连接「" + s.spec.name + "」");
    }

    public synchronized Map<String, Object> remove(String id) {
        Server s = servers.remove(id);
        if (s == null) return res(false, "没有这个服务器");
        stopQuiet(s);
        Map<String, List<String>> st = sources.get(s.source);
        if (st != null) {
            st.get("removed").add(s.spec.name);
            boolean any = false;
            for (Server o : servers.values()) if (o.source.equals(s.source)) any = true;
            if (!any) sources.remove(s.source);
            save();
        }
        return res(true, "已移除「" + s.spec.name + "」（不会删你的文件；重新选这个目录可以加回来）");
    }

    /** 再扫一遍某个目录（改过配置 / 装好依赖之后）。 */
    public Map<String, Object> rescan(String source) {
        if (!sources.containsKey(source)) return res(false, "没加过这个目录");
        return addSource(source);
    }

    public void shutdown() {
        List<Server> all;
        synchronized (this) { all = new ArrayList<>(servers.values()); }
        for (Server s : all) stopQuiet(s);
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ 给界面 / 模型

    public synchronized Map<String, Object> view() {
        Map<String, Object> m = new TreeMap<>();
        List<Object> l = new ArrayList<>();
        for (Server s : servers.values()) l.add(s.view());
        m.put("servers", l);
        m.put("sources", new ArrayList<Object>(sources.keySet()));
        m.put("notes", new ArrayList<Object>(notes));
        long n = 0;
        for (Server s : servers.values()) if ("ready".equals(s.status)) n += s.tools.size();
        m.put("tool_total", n);
        return m;
    }

    /** 模型函数名 → (服务器, 工具)。 */
    public synchronized Object[] resolve(String fn) {
        for (Server s : servers.values()) {
            if (!"ready".equals(s.status)) continue;
            for (Map<String, Object> t : s.tools) if (fnName(s, t).equals(fn)) return new Object[] {s, t};
        }
        return null;
    }

    static String fnName(Server s, Map<String, Object> t) {
        String n = "mcp__" + s.id + "__" + Mcp.safe(Mcp.str(t.get("name")), 64);
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    /** 交给模型的工具定义（OpenAI function 格式）。最多 {@link #MAX_TOOLS} 个。 */
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> toolDefs() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Server s : servers.values()) {
            if (!"ready".equals(s.status)) continue;
            for (Map<String, Object> t : s.tools) {
                if (l.size() >= MAX_TOOLS) return l;
                Map<String, Object> schema = t.get("inputSchema") instanceof Map ? new TreeMap<>((Map<String, Object>) t.get("inputSchema")) : new TreeMap<>();
                schema.put("type", "object");
                if (!(schema.get("properties") instanceof Map)) schema.put("properties", new TreeMap<String, Object>());
                schema.remove("$schema");
                Map<String, Object> f = new TreeMap<>();
                f.put("name", fnName(s, t));
                f.put("description", "[MCP 工具 · " + s.spec.name + "] " + ChatAgent.clip(Mcp.str(t.get("description")), 400) + (Mcp.readOnly(t) ? "（只读）" : "（可能有副作用，调用前会让用户批准）"));
                f.put("parameters", schema);
                Map<String, Object> x = new TreeMap<>();
                x.put("type", "function");
                x.put("function", f);
                l.add(x);
            }
        }
        return l;
    }

    /** 这个函数名的工具是不是只读（审批用；认不出算否）。 */
    public boolean readOnlyFn(String fn) {
        Object[] st = resolve(fn);
        return st != null && Mcp.readOnly((Map<String, Object>) cast(st[1]));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object o) { return (Map<String, Object>) o; }

    /** 给人看的一句：「MCP 工具 server · tool」。 */
    public String describe(String fn) {
        Object[] st = resolve(fn);
        if (st == null) return "MCP 工具（" + fn + "）";
        return "MCP 工具「" + ((Server) st[0]).spec.name + " · " + Mcp.str(cast(st[1]).get("name")) + "」";
    }

    /** 调用（审批已经过了）。 */
    public Actions.Result call(String fn, Map<String, Object> arguments) {
        Object[] st = resolve(fn);
        if (st == null) return Actions.Result.fail("这个 MCP 工具现在用不了（服务器停了或没连上）：" + fn);
        Server s = (Server) st[0];
        Map<String, Object> t = cast(st[1]);
        Mcp.Client c = s.client;
        if (c == null) return Actions.Result.fail("MCP 服务器「" + s.spec.name + "」没连着");
        Map<String, Object> p = new TreeMap<>();
        p.put("name", Mcp.str(t.get("name")));
        p.put("arguments", arguments == null ? new TreeMap<String, Object>() : arguments);
        try {
            Map<String, Object> r = c.request("tools/call", p, CALL_MS);
            String text = Mcp.resultText(r);
            Map<String, Object> d = new TreeMap<>();
            d.put("output", ChatAgent.clip(text, 16000));
            d.put("server", s.spec.name);
            d.put("tool", Mcp.str(t.get("name")));
            if (Boolean.TRUE.equals(r.get("isError"))) return new Actions.Result(false, "MCP 工具报错：" + ChatAgent.clip(LinkPolicy.oneLine(text), 200), d);
            return Actions.Result.ok("MCP 工具「" + Mcp.str(t.get("name")) + "」完成", d);
        } catch (IOException e) {
            String why = Mcp.clean(e.getMessage(), s.spec);
            if (s.client != null && c instanceof Mcp.Stdio && ((Mcp.Stdio) c).dead) { s.status = "error"; s.error = why; s.client = null; }
            return Actions.Result.fail("MCP 工具「" + Mcp.str(t.get("name")) + "」没成功：" + why);
        }
    }

    static Map<String, Object> res(boolean ok, String text) {
        Map<String, Object> m = new TreeMap<>();
        m.put("ok", ok);
        m.put("text", text);
        return m;
    }
}
