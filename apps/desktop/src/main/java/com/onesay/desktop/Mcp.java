package com.onesay.desktop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [preview-1120] MCP 连接器：选一个目录（或配置文件），自动认出里面是什么 MCP 服务器、怎么启动，连上做 initialize + tools/list，
 * 把工具交给对话里的模型用。不分官方 / 第三方，按目录里的东西判断：
 *
 * <ul>
 *   <li><b>配置文件</b>：claude_desktop_config.json、mcp.json、.mcp.json、.vscode/mcp.json、.cursor/mcp.json、mcp_servers.json；
 *       里面的 mcpServers / servers / 直接的「名字 → 配置」都认。每项 command + args + env（stdio）或 url + headers（HTTP / SSE）。</li>
 *   <li><b>单个服务器项目</b>：package.json（有 bin，或依赖里有 MCP SDK）→ node 跑 bin / main；pyproject.toml / requirements.txt / setup.py
 *       里有 mcp / fastmcp → 有 uv 用 uv run，否则 python（优先项目里的 .venv）；目录里只有一个可执行文件 → 直接跑；Dockerfile → 只提示（不自动构建）。
 *       目录下一两层里有多个服务器项目时都认出来（比如官方 servers 仓库的 src/*）。</li>
 *   <li><b>传输</b>：stdio（一行一个 JSON-RPC）；Streamable HTTP（POST，回 JSON 或 SSE 流，带 Mcp-Session-Id）；老的 HTTP+SSE（GET 拿 endpoint 再 POST）。
 *       配置里写了 url 没写类型时先试 Streamable HTTP，404 / 405 再退到 SSE。</li>
 * </ul>
 *
 * <p>安全：子进程照旧走 {@link SafeExec}（永不提权）；env / headers 里的值只在内存里用来启动 / 连接，不写日志、不进状态文件、
 * 错误信息里出现就换成 ***。工具调用走对话原来的审批（{@link PcGuard#category}：只读提示的工具不问，其余默认要你批准）。
 */
public final class Mcp {
    private Mcp() { }

    public static final String PROTOCOL = "2025-06-18";

    // ================================================================== 识别

    /** 一个服务器怎么启动 / 连接。env、headers 只在内存里。 */
    public static final class Spec {
        public String name = "";
        /** stdio / http / sse / auto（url 没写类型） */
        public String transport = "stdio";
        public String command = "";
        public List<String> args = new ArrayList<>();
        public Map<String, String> env = new TreeMap<>();
        public String cwd = "";
        public String url = "";
        public Map<String, String> headers = new TreeMap<>();
        /** 从哪认出来的（给人看）：「配置文件 mcp.json」「Node 项目」… */
        public String origin = "";
        /** 认出来但用不了的原因（空 = 能用）。 */
        public String problem = "";

        /** 所有要当秘密的值（env / headers 的值），用来把错误信息里的抹掉。 */
        List<String> secrets() {
            List<String> s = new ArrayList<>();
            for (String v : env.values()) if (v != null && v.length() >= 4) s.add(v);
            for (String v : headers.values()) if (v != null && v.length() >= 4) s.add(v);
            for (String a : args) if (a != null && a.matches("(?i).*(token|key|secret|password|bearer)=.{4,}")) s.add(a.substring(a.indexOf('=') + 1));
            return s;
        }

        /** 给人看的启动方式（不带 env 值）。 */
        public String shown() {
            if (!url.isEmpty()) return (transport.equals("sse") ? "SSE " : transport.equals("http") ? "HTTP " : "") + redactUrl(url);
            StringBuilder b = new StringBuilder(command);
            for (String a : args) b.append(' ').append(a.contains(" ") ? "\"" + a + "\"" : a);
            String t = b.toString();
            for (String s : secrets()) t = t.replace(s, "***");
            return t;
        }
    }

    /** 识别结果：认出的服务器 + 看到但不支持的东西。 */
    public static final class Detected {
        public final List<Spec> specs = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
    }

    /** [preview-1120] 项目清单文件：选中它 = 选了它所在的项目。 */
    static final List<String> PROJECT_FILES = List.of("package.json", "pyproject.toml", "requirements.txt", "setup.py");

    static final List<String> CONFIG_NAMES = List.of("claude_desktop_config.json", "mcp.json", ".mcp.json", "mcp_servers.json",
            ".vscode/mcp.json", ".cursor/mcp.json", ".roo/mcp.json", "cline_mcp_settings.json");

    /** 识别一个目录或配置文件。env 用当前进程的（展开 ${env:X} / ${X}）。 */
    public static Detected detect(Path p) { return detect(p, System.getenv()); }

    public static Detected detect(Path p, Map<String, String> sysEnv) {
        Detected d = new Detected();
        if (p == null || !Files.exists(p)) { d.notes.add("找不到这个位置"); return d; }
        if (Files.isRegularFile(p)) {
            String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
            // [preview-1120 真机] 选的是项目清单（package.json / pyproject.toml …）= 选了这个项目：按项目认。以前 package.json 被当成
            // MCP 配置文件，里面的 "repository": {"url": "https://github.com/…"} 被认成一个叫 repository 的 HTTP 服务器还去连了
            if (PROJECT_FILES.contains(fn)) { if (!project(p.getParent(), sysEnv, d) && d.notes.isEmpty()) d.notes.add(fn + " 所在的项目不像 MCP 服务器（没有 MCP 依赖，也没有 bin）"); return d; }
            if (fn.endsWith(".json")) fromConfig(p, p.getParent(), sysEnv, d);
            else d.notes.add("选的是文件：MCP 请选它所在的文件夹，或者选 MCP 配置文件（.json）");
            return d;
        }
        boolean cfg = false;
        for (String n : CONFIG_NAMES) {
            Path f = p.resolve(n);
            if (Files.isRegularFile(f)) { cfg = true; fromConfig(f, p, sysEnv, d); }
        }
        if (cfg) return d;
        // 没有配置文件：当成服务器项目（这一层，或者下一两层的子项目）
        if (project(p, sysEnv, d)) return d;
        List<Path> subs = new ArrayList<>();
        collect(p, 0, subs);
        for (Path s : subs) project(s, sysEnv, d);
        if (d.specs.isEmpty() && d.notes.isEmpty()) d.notes.add("这个文件夹里没认出 MCP 服务器：没有 MCP 配置文件，也没有带 MCP 依赖的 Node / Python 项目或可执行文件");
        return d;
    }

    static void collect(Path dir, int depth, List<Path> out) {
        if (depth >= 2 || out.size() > 40) return;
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            List<Path> kids = new ArrayList<>();
            s.filter(Files::isDirectory).forEach(kids::add);
            Collections.sort(kids);
            for (Path k : kids) {
                String n = k.getFileName().toString();
                if (n.startsWith(".") || n.equals("node_modules") || n.equals("__pycache__") || n.equals("dist") || n.equals("build") || n.equals("venv")) continue;
                if (looksLikeProject(k)) out.add(k); else collect(k, depth + 1, out);
            }
        } catch (IOException ignored) { }
    }

    static boolean looksLikeProject(Path d) {
        return Files.isRegularFile(d.resolve("package.json")) || Files.isRegularFile(d.resolve("pyproject.toml")) || Files.isRegularFile(d.resolve("requirements.txt"))
                || Files.isRegularFile(d.resolve("setup.py")) || Files.isRegularFile(d.resolve("Dockerfile"));
    }

    @SuppressWarnings("unchecked")
    static void fromConfig(Path f, Path base, Map<String, String> sysEnv, Detected d) {
        Object root;
        try {
            String txt = Files.readString(f, StandardCharsets.UTF_8);
            root = Json.parse(stripJsonComments(txt));
        } catch (Exception e) {
            d.notes.add(f.getFileName() + "：不是合法的 JSON（" + e.getClass().getSimpleName() + "）");
            return;
        }
        if (!(root instanceof Map)) { d.notes.add(f.getFileName() + "：内容不是一个 JSON 对象"); return; }
        Map<String, Object> m = (Map<String, Object>) root;
        Object servers = m.containsKey("mcpServers") ? m.get("mcpServers") : m.containsKey("servers") ? m.get("servers")
                : m.get("mcp") instanceof Map && ((Map<String, Object>) m.get("mcp")).get("servers") instanceof Map ? ((Map<String, Object>) m.get("mcp")).get("servers") : m;
        // [preview-1120] 顶层直接「名字 → 配置」的写法：只认长得像服务器的项（有 command，或 url 再带 type / transport / headers），
        // 免得别的 JSON 里的 {"url": …}（仓库地址、主页）被当成 HTTP 服务器去连
        boolean topLevel = servers == m;
        if (!(servers instanceof Map)) { d.notes.add(f.getFileName() + "：没有 mcpServers / servers 段"); return; }
        int before = d.specs.size();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) servers).entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> x = (Map<String, Object>) e.getValue();
            if (!x.containsKey("command") && !x.containsKey("url") && !x.containsKey("serverUrl")) continue;
            if (topLevel && !x.containsKey("command") && !(transportWord(x.get("type")) || transportWord(x.get("transport")) || x.get("headers") instanceof Map)) continue; // "type":"git" 不算
            Spec s = new Spec();
            s.name = e.getKey();
            s.origin = "配置文件 " + base.relativize(f).toString().replace('\\', '/');
            if (Boolean.TRUE.equals(x.get("disabled"))) s.problem = "配置里标了 disabled";
            String type = str(x.get("type")).toLowerCase(Locale.ROOT);
            if (x.containsKey("transport") && x.get("transport") instanceof String) type = str(x.get("transport")).toLowerCase(Locale.ROOT);
            String url = str(x.containsKey("url") ? x.get("url") : x.get("serverUrl"));
            if (!url.isEmpty()) {
                s.url = expand(url, base, sysEnv, s);
                s.transport = type.contains("sse") ? "sse" : (type.contains("http") || type.contains("streamable")) ? "http" : "auto";
                if (!s.url.matches("(?i)https?://\\S+")) s.problem = "url 只支持 http / https";
                if (x.get("headers") instanceof Map) for (Map.Entry<String, Object> h : ((Map<String, Object>) x.get("headers")).entrySet())
                    s.headers.put(h.getKey(), expand(str(h.getValue()), base, sysEnv, s));
            } else {
                s.transport = "stdio";
                s.command = expand(str(x.get("command")), base, sysEnv, s);
                if (x.get("args") instanceof List) for (Object a : (List<Object>) x.get("args")) s.args.add(expand(str(a), base, sysEnv, s));
                if (x.get("env") instanceof Map) for (Map.Entry<String, Object> v : ((Map<String, Object>) x.get("env")).entrySet())
                    s.env.put(v.getKey(), expand(str(v.getValue()), base, sysEnv, s));
                s.cwd = x.get("cwd") != null ? expand(str(x.get("cwd")), base, sysEnv, s) : base.toString();
                if (s.command.isEmpty()) s.problem = "没写 command";
                if (type.equals("docker") || s.command.equalsIgnoreCase("docker")) s.origin += "（Docker）";
            }
            d.specs.add(s);
        }
        if (d.specs.size() == before) d.notes.add(f.getFileName() + "：没有可用的服务器条目");
    }

    static final Pattern VAR = Pattern.compile("\\$\\{(env:)?([A-Za-z_][A-Za-z0-9_]*)\\}|\\$\\{(workspaceFolder|workspaceRoot|userHome|pathSeparator)\\}|\\$\\{input:([^}]+)\\}");

    /** 展开 ${env:X} / ${X} / ${workspaceFolder} / ${userHome}；${input:…}（VS Code 运行时才问的）展开不了，标问题。 */
    static String expand(String v, Path base, Map<String, String> sysEnv, Spec s) {
        if (v == null || v.indexOf("${") < 0) return v == null ? "" : v;
        Matcher m = VAR.matcher(v);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String rep;
            if (m.group(4) != null) {
                rep = m.group(0);
                if (s.problem.isEmpty()) s.problem = "要在编辑器里输入的变量 " + m.group(0) + "，这里没法问你；请在配置里直接写值或用环境变量";
            } else if (m.group(3) != null) {
                String k = m.group(3);
                rep = k.startsWith("workspace") ? base.toString() : k.equals("userHome") ? System.getProperty("user.home") : java.io.File.pathSeparator;
            } else if (m.group(2).matches("workspaceFolder|workspaceRoot|userHome|pathSeparator")) {
                String k = m.group(2);
                rep = k.startsWith("workspace") ? base.toString() : k.equals("userHome") ? System.getProperty("user.home") : java.io.File.pathSeparator;
            } else {
                String val = sysEnv == null ? null : sysEnv.get(m.group(2));
                if (val == null) { rep = ""; if (s.problem.isEmpty()) s.problem = "环境变量 " + m.group(2) + " 没设置"; }
                else rep = val;
            }
            m.appendReplacement(b, Matcher.quoteReplacement(rep));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** 去掉 JSON 里的 // 和 /* *\/ 注释（VS Code 的 mcp.json 允许），字符串里的不动。 */
    static String stripJsonComments(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean str = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (str) {
                b.append(c);
                if (c == '\\' && i + 1 < s.length()) b.append(s.charAt(++i));
                else if (c == '"') str = false;
            } else if (c == '"') { str = true; b.append(c); }
            else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; b.append('\n'); }
            else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') { i += 2; while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++; i++; }
            else b.append(c);
        }
        // 结尾多余的逗号（JSONC 也允许）
        return b.toString().replaceAll(",(\\s*[}\\]])", "$1");
    }

    /** 这一层是不是一个 MCP 服务器项目；是就加进 d。 */
    @SuppressWarnings("unchecked")
    /**
     * [preview-1120 真机] 依赖找得到没有：按 Node 的查找规则，从项目目录往上每一层的 node_modules 里找。
     * 项目自己有 node_modules 照旧算装好；没有时再往上找。以前只看项目自己的：用 npm install 装成依赖的服务器（依赖被提到上层 node_modules）会被误报「依赖还没装」。
     */
    static boolean nodeDepsResolvable(Path dir, java.util.Collection<String> deps) {
        for (String dep : deps) {
            if (dep == null || dep.isBlank() || dep.contains("..")) continue;
            boolean found = false;
            for (Path p = dir.toAbsolutePath().normalize(); p != null && !found; p = p.getParent())
                if (Files.isDirectory(p.resolve("node_modules").resolve(dep))) found = true;
            if (!found) return false;
        }
        return true;
    }

    /** [preview-1120] 像 MCP 传输方式的 type / transport 值（"git"、"module" 这类不算）。 */
    static boolean transportWord(Object v) {
        if (!(v instanceof String)) return false;
        String t = ((String) v).toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return t.equals("http") || t.equals("sse") || t.equals("streamablehttp") || t.equals("stdio") || t.equals("streamable");
    }

    static boolean project(Path dir, Map<String, String> sysEnv, Detected d) {
        String folder = dir.getFileName() == null ? "mcp" : dir.getFileName().toString();
        Path pkg = dir.resolve("package.json");
        if (Files.isRegularFile(pkg)) {
            Map<String, Object> j;
            try { j = Json.obj(Files.readString(pkg, StandardCharsets.UTF_8)); } catch (Exception e) { d.notes.add(folder + "/package.json 读不出来"); return false; }
            String deps = String.valueOf(j.get("dependencies")) + String.valueOf(j.get("devDependencies")) + String.valueOf(j.get("peerDependencies"));
            String name = str(j.get("name"));
            boolean mcp = deps.contains("@modelcontextprotocol/") || deps.contains("fastmcp") || deps.contains("mcp-framework") || name.toLowerCase(Locale.ROOT).contains("mcp");
            if (!mcp) return false;
            Spec s = new Spec();
            s.name = name.isEmpty() ? folder : name.replaceAll("^@[^/]+/", "");
            s.origin = "Node 项目（package.json）";
            s.cwd = dir.toString();
            String entry = "";
            Object bin = j.get("bin");
            if (bin instanceof String) entry = (String) bin;
            else if (bin instanceof Map && !((Map<String, Object>) bin).isEmpty()) entry = str(((Map<String, Object>) bin).values().iterator().next());
            if (entry.isEmpty()) entry = str(j.get("main"));
            if (entry.isEmpty() && Files.isRegularFile(dir.resolve("index.js"))) entry = "index.js";
            if (entry.isEmpty()) { s.problem = "package.json 里没有 bin / main，不知道从哪启动"; s.command = "node"; }
            else {
                Path e = dir.resolve(entry).normalize();
                s.command = "node";
                s.args.add(e.toString());
                if (!e.startsWith(dir.normalize())) s.problem = "启动文件在项目外面，没用";
                else if (!Files.isRegularFile(e)) s.problem = "启动文件 " + entry + " 还没有：先在这个文件夹里运行 npm install 和 npm run build";
                else if (!(j.get("dependencies") instanceof Map) || ((Map<String, Object>) j.get("dependencies")).isEmpty()) { /* 没依赖，直接跑 */ }
                else if (!Files.isDirectory(dir.resolve("node_modules")) && !nodeDepsResolvable(dir, ((Map<String, Object>) j.get("dependencies")).keySet())) s.problem = "依赖还没装：先在这个文件夹里运行 npm install（或 npm install --omit=dev）";
            }
            if (s.problem.isEmpty() && which("node", sysEnv) == null) s.problem = "这台电脑上没找到 Node.js（node）";
            d.specs.add(s);
            return true;
        }
        Path py = dir.resolve("pyproject.toml"), req = dir.resolve("requirements.txt"), setup = dir.resolve("setup.py");
        if (Files.isRegularFile(py) || Files.isRegularFile(req) || Files.isRegularFile(setup)) {
            String all = read(py) + "\n" + read(req) + "\n" + read(setup);
            if (!Pattern.compile("(?im)(^|[\"'\\s,\\[])(mcp|fastmcp)\\s*([<>=~!\\[\"',]|$)|mcp\\[cli\\]|modelcontextprotocol").matcher(all).find()) return false;
            Spec s = new Spec();
            Matcher pn = Pattern.compile("(?m)^\\s*name\\s*=\\s*\"([^\"]+)\"").matcher(read(py));
            s.name = pn.find() ? pn.group(1) : folder;
            s.origin = "Python 项目（" + (Files.isRegularFile(py) ? "pyproject.toml" : Files.isRegularFile(req) ? "requirements.txt" : "setup.py") + "）";
            s.cwd = dir.toString();
            String script = "";
            Matcher sc = Pattern.compile("(?ms)^\\[project\\.scripts\\]\\s*\\n(.*?)(^\\[|\\z)").matcher(read(py));
            if (sc.find()) {
                Matcher first = Pattern.compile("(?m)^\\s*([A-Za-z0-9_.-]+)\\s*=").matcher(sc.group(1));
                if (first.find()) script = first.group(1);
            }
            String file = "";
            for (String c : new String[] {"server.py", "main.py", "app.py", "mcp_server.py"}) if (Files.isRegularFile(dir.resolve(c))) { file = c; break; }
            Path venvPy = SafeExec.WINDOWS ? dir.resolve(".venv").resolve("Scripts").resolve("python.exe") : dir.resolve(".venv").resolve("bin").resolve("python");
            String uv = which("uv", sysEnv);
            if (uv != null && Files.isRegularFile(py)) {
                s.command = "uv";
                s.args.addAll(List.of("run", "--directory", dir.toString()));
                if (!script.isEmpty()) s.args.add(script);
                else if (!file.isEmpty()) { s.args.add("python"); s.args.add(file); }
                else s.problem = "pyproject.toml 里没有 [project.scripts]，也没有 server.py / main.py";
                if (s.problem.isEmpty() && !Files.isDirectory(dir.resolve(".venv"))) s.origin += "（第一次启动时 uv 会下载依赖，要联网）";
            } else if (Files.isRegularFile(venvPy) && !file.isEmpty()) {
                s.command = venvPy.toString();
                s.args.add(file);
            } else if (!file.isEmpty()) {
                String python = which(SafeExec.WINDOWS ? "python" : "python3", sysEnv);
                if (python == null) python = which("python", sysEnv);
                s.command = python == null ? "python" : python;
                s.args.add(file);
                if (python == null) s.problem = "这台电脑上没找到 Python，也没有 uv";
            } else {
                s.command = "python";
                s.problem = uv == null ? "没找到入口（server.py / main.py），也没有 uv 能跑 [project.scripts]" : "没找到入口";
            }
            d.specs.add(s);
            return true;
        }
        // 可执行文件 / Dockerfile
        List<Path> exes = new ArrayList<>();
        try (java.util.stream.Stream<Path> st = Files.list(dir)) {
            st.filter(Files::isRegularFile).forEach(f -> {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (SafeExec.WINDOWS ? n.matches(".*\\.(exe|cmd|bat)$") : (Files.isExecutable(f) && !n.contains("."))) exes.add(f);
            });
        } catch (IOException ignored) { }
        if (exes.size() > 1) exes.removeIf(f -> !f.getFileName().toString().toLowerCase(Locale.ROOT).contains("mcp"));
        if (exes.size() == 1) {
            Spec s = new Spec();
            s.name = exes.get(0).getFileName().toString().replaceAll("\\.(exe|cmd|bat)$", "");
            s.origin = "可执行文件";
            s.command = exes.get(0).toString();
            s.cwd = dir.toString();
            d.specs.add(s);
            return true;
        }
        if (Files.isRegularFile(dir.resolve("Dockerfile"))) {
            d.notes.add(folder + "：只有 Dockerfile。本版不自动构建镜像；装了 Docker 的话，请写一个 mcp.json（command: docker, args: [run, -i, --rm, 镜像名]）再选它");
            return true;
        }
        if (exes.size() > 1) d.notes.add(folder + "：有好几个可执行文件，认不出哪个是 MCP 服务器；请写一个 mcp.json 指定");
        return false;
    }

    static String read(Path p) {
        try { return Files.isRegularFile(p) && Files.size(p) < 512 * 1024 ? Files.readString(p, StandardCharsets.UTF_8) : ""; } catch (Exception e) { return ""; }
    }

    /** 在 PATH 里找程序（Windows 加 .exe / .cmd / .bat）。找不到 null。 */
    static String which(String prog, Map<String, String> env) {
        if (prog == null || prog.isEmpty()) return null;
        Path direct = Paths.get(prog);
        if (direct.isAbsolute()) return Files.isRegularFile(direct) ? direct.toString() : null;
        String path = env == null ? null : (env.get("PATH") != null ? env.get("PATH") : env.get("Path"));
        if (path == null) return null;
        String[] exts = SafeExec.WINDOWS ? new String[] {".exe", ".cmd", ".bat", ""} : new String[] {""};
        for (String d : path.split(java.io.File.pathSeparator)) {
            if (d.isBlank()) continue;
            for (String e : exts) {
                try {
                    Path f = Paths.get(d.trim().replace("\"", ""), prog + e);
                    if (Files.isRegularFile(f)) return f.toString();
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static String redactUrl(String u) {
        return u == null ? "" : u.replaceAll("(?i)([?&](token|key|api[_-]?key|access_token|secret)=)[^&]+", "$1***").replaceAll("//[^/@]+@", "//***@");
    }

    /** 把错误信息里的秘密值换成 ***，压成一行，最长 300 字。 */
    static String clean(String msg, Spec s) {
        String t = msg == null ? "" : msg;
        if (s != null) for (String v : s.secrets()) t = t.replace(v, "***");
        t = t.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}", "$1***").replaceAll("(?i)\\b(sk|ghp|gho|xox[abp]|AKIA)[-_A-Za-z0-9]{12,}", "***");
        t = LinkPolicy.oneLine(t);
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    // ================================================================== 客户端

    /** 一条连接（stdio / HTTP / SSE）。 */
    public interface Client extends AutoCloseable {
        /** 发请求，等结果（result 对象）；出错抛 IOException（带对方的 error.message）。 */
        Map<String, Object> request(String method, Map<String, Object> params, long timeoutMs) throws IOException;

        void notify(String method, Map<String, Object> params) throws IOException;

        @Override void close();

        /** 最近一点 stderr / 连接错误（已抹秘密），给界面看。 */
        default String lastError() { return ""; }
    }

    static Map<String, Object> rpc(long id, String method, Map<String, Object> params) {
        Map<String, Object> m = new TreeMap<>();
        m.put("jsonrpc", "2.0");
        if (id >= 0) m.put("id", id);
        m.put("method", method);
        if (params != null) m.put("params", params);
        return m;
    }

    /** 收到一条消息：是响应就交给等它的；是对方发来的请求就回「不支持」（ping 回空）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> dispatch(Object msg, Map<Long, CompletableFuture<Map<String, Object>>> waiting) {
        if (!(msg instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) msg;
        Object id = m.get("id");
        if (m.containsKey("method")) {
            if (id == null) return null; // 通知：日志、进度、list_changed，不管
            Map<String, Object> r = new TreeMap<>();
            r.put("jsonrpc", "2.0");
            r.put("id", id);
            if ("ping".equals(m.get("method"))) r.put("result", new TreeMap<String, Object>());
            else if ("roots/list".equals(m.get("method"))) { Map<String, Object> res = new TreeMap<>(); res.put("roots", new ArrayList<>()); r.put("result", res); }
            else { Map<String, Object> e = new TreeMap<>(); e.put("code", -32601L); e.put("message", "OneSay 不支持 " + m.get("method")); r.put("error", e); }
            return r;
        }
        if (id instanceof Number) {
            CompletableFuture<Map<String, Object>> f = waiting.remove(((Number) id).longValue());
            if (f != null) f.complete(m);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resultOf(Map<String, Object> resp, String method) throws IOException {
        if (resp.get("error") instanceof Map) {
            Map<String, Object> e = (Map<String, Object>) resp.get("error");
            throw new IOException(method + " 出错：" + str(e.get("message")) + (e.get("code") != null ? "（" + e.get("code") + "）" : ""));
        }
        Object r = resp.get("result");
        return r instanceof Map ? (Map<String, Object>) r : new TreeMap<>();
    }

    /** stdio：子进程，一行一个 JSON-RPC 消息。 */
    public static final class Stdio implements Client {
        final Process p;
        final OutputStream out;
        final Spec spec;
        final Map<Long, CompletableFuture<Map<String, Object>>> waiting = new ConcurrentHashMap<>();
        final AtomicLong ids = new AtomicLong(1);
        final StringBuilder err = new StringBuilder();
        volatile boolean dead;

        public Stdio(Spec s) throws IOException {
            spec = s;
            List<String> cmd = new ArrayList<>();
            String exe = s.command;
            String found = which(exe, mergedEnv(s));
            if (SafeExec.WINDOWS && found != null && found.toLowerCase(Locale.ROOT).matches(".*\\.(cmd|bat)$")) {
                // npx.cmd / uvx.cmd 这类批处理要经 cmd.exe 才能起
                cmd.add("cmd.exe"); cmd.add("/c"); cmd.add(found);
            } else cmd.add(found != null ? found : exe);
            cmd.addAll(s.args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (!s.cwd.isEmpty() && Files.isDirectory(Paths.get(s.cwd))) pb.directory(Paths.get(s.cwd).toFile());
            pb.environment().putAll(s.env);
            p = SafeExec.start(pb);
            out = p.getOutputStream();
            Thread rd = new Thread(this::readLoop, "onesay-mcp-out");
            rd.setDaemon(true);
            rd.start();
            Thread er = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) synchronized (err) { err.append(line).append('\n'); if (err.length() > 4000) err.delete(0, err.length() - 4000); }
                } catch (IOException ignored) { }
            }, "onesay-mcp-err");
            er.setDaemon(true);
            er.start();
        }

        static Map<String, String> mergedEnv(Spec s) {
            Map<String, String> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            m.putAll(System.getenv());
            m.putAll(s.env);
            return m;
        }

        void readLoop() {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.charAt(0) != '{') continue; // 有的服务器往 stdout 打日志
                    Object msg;
                    try { msg = Json.parse(line); } catch (RuntimeException e) { continue; }
                    Map<String, Object> reply = dispatch(msg, waiting);
                    if (reply != null) try { send(reply); } catch (IOException ignored) { }
                }
            } catch (IOException ignored) { }
            dead = true;
            for (CompletableFuture<Map<String, Object>> f : waiting.values()) f.completeExceptionally(new IOException("服务器进程退出了" + tail()));
            waiting.clear();
        }

        String tail() {
            String t;
            synchronized (err) { t = err.toString().trim(); }
            if (t.isEmpty()) return "";
            String[] l = t.split("\\R");
            return "：" + clean(l[l.length - 1], spec);
        }

        synchronized void send(Map<String, Object> m) throws IOException {
            out.write((Json.stringify(m) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override public Map<String, Object> request(String method, Map<String, Object> params, long timeoutMs) throws IOException {
            if (dead || !p.isAlive()) throw new IOException("服务器进程没在跑" + tail());
            long id = ids.getAndIncrement();
            CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
            waiting.put(id, f);
            send(rpc(id, method, params));
            try {
                return resultOf(waitCancellable(f, timeoutMs, method), method);
            } finally {
                waiting.remove(id);
            }
        }

        @Override public void notify(String method, Map<String, Object> params) throws IOException { send(rpc(-1, method, params)); }

        @Override public void close() {
            try { out.close(); } catch (IOException ignored) { }
            try { if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) PcGuard.killTree(p); } catch (InterruptedException e) { PcGuard.killTree(p); }
        }

        @Override public String lastError() { return tail().replaceFirst("^：", ""); }
    }

    /** 等结果：每 100 毫秒看一次这一步的取消开关（对话「停止」）。 */
    static Map<String, Object> waitCancellable(CompletableFuture<Map<String, Object>> f, long timeoutMs, String method) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        java.util.concurrent.atomic.AtomicBoolean tok = Progress.TOKEN.get();
        while (true) {
            try {
                return f.get(100, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                if (tok != null && tok.get()) throw new IOException("你点了停止，" + method + " 没等完");
                if (System.currentTimeMillis() >= end) throw new IOException(method + " 超过 " + (timeoutMs / 1000) + " 秒没回应");
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof IOException ? (IOException) e.getCause() : new IOException(String.valueOf(e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("被打断了");
            }
        }
    }

    /** Streamable HTTP（2025-03-26 起）：每个请求一个 POST，回 JSON 或 SSE。 */
    public static final class Http implements Client {
        final Spec spec;
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        final AtomicLong ids = new AtomicLong(1);
        volatile String session = "";
        volatile String proto = "";
        String last = "";

        public Http(Spec s) { spec = s; }

        HttpRequest.Builder req() {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(spec.url)).timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json");
            for (Map.Entry<String, String> h : spec.headers.entrySet()) b.header(h.getKey(), h.getValue());
            if (!session.isEmpty()) b.header("Mcp-Session-Id", session);
            if (!proto.isEmpty()) b.header("MCP-Protocol-Version", proto);
            return b;
        }

        /** 404 / 405：服务器不是 Streamable HTTP（可能是老的 SSE）。 */
        public static final class NotStreamable extends IOException { NotStreamable(String m) { super(m); } }

        @Override public Map<String, Object> request(String method, Map<String, Object> params, long timeoutMs) throws IOException {
            long id = ids.getAndIncrement();
            HttpResponse<InputStream> r;
            try {
                r = http.send(req().timeout(Duration.ofMillis(Math.max(1000, timeoutMs))).POST(HttpRequest.BodyPublishers.ofString(Json.stringify(rpc(id, method, params)))).build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("被打断了");
            } catch (java.net.http.HttpTimeoutException e) {
                throw new IOException(method + " 超时");
            } catch (IOException e) {
                throw new IOException("连不上 " + redactUrl(spec.url) + "：" + clean(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), spec));
            }
            int code = r.statusCode();
            r.headers().firstValue("mcp-session-id").ifPresent(v -> session = v);
            if (code == 404 || code == 405) { r.body().close(); throw new NotStreamable("HTTP " + code); }
            if (code == 401 || code == 403) { r.body().close(); throw new IOException("服务器拒绝了（HTTP " + code + "）：要登录 / 令牌不对，在配置的 headers 里填"); }
            if (code / 100 != 2) { String b = new String(r.body().readNBytes(400), StandardCharsets.UTF_8); throw new IOException("HTTP " + code + "：" + clean(b, spec)); }
            String type = r.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            Map<String, Object> resp = null;
            if (type.contains("text/event-stream")) {
                Map<Long, CompletableFuture<Map<String, Object>>> w = new ConcurrentHashMap<>();
                CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
                w.put(id, f);
                try (InputStream in = r.body()) {
                    sse(in, (ev, data) -> {
                        try { Object o = Json.parse(data); Map<String, Object> rep = dispatch(o, w); if (rep != null) postQuiet(rep); } catch (RuntimeException ignored) { }
                        return f.isDone();
                    });
                }
                resp = f.getNow(null);
                if (resp == null) throw new IOException(method + "：服务器的事件流结束了也没回结果");
            } else {
                Object o;
                try { o = Json.parse(new String(r.body().readAllBytes(), StandardCharsets.UTF_8)); } catch (RuntimeException e) { throw new IOException(method + "：服务器回的不是 JSON"); }
                if (o instanceof List && !((List<?>) o).isEmpty()) o = ((List<?>) o).get(0);
                if (!(o instanceof Map)) throw new IOException(method + "：服务器回的不是 JSON-RPC");
                @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) o;
                resp = mm;
            }
            return resultOf(resp, method);
        }

        void postQuiet(Map<String, Object> m) {
            try { http.send(req().POST(HttpRequest.BodyPublishers.ofString(Json.stringify(m))).build(), HttpResponse.BodyHandlers.discarding()); } catch (Exception ignored) { }
        }

        @Override public void notify(String method, Map<String, Object> params) throws IOException {
            try { http.send(req().POST(HttpRequest.BodyPublishers.ofString(Json.stringify(rpc(-1, method, params)))).build(), HttpResponse.BodyHandlers.discarding()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        @Override public void close() {
            if (session.isEmpty()) return;
            try { http.send(req().DELETE().build(), HttpResponse.BodyHandlers.discarding()); } catch (Exception ignored) { }
        }
    }

    /** 读 SSE：每个事件回调 (event, data)；回调返回 true 就停。 */
    interface SseSink { boolean on(String event, String data); }

    static void sse(InputStream in, SseSink sink) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String ev = "message";
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    String d = data.toString();
                    data.setLength(0);
                    if (sink.on(ev, d)) return;
                }
                ev = "message";
                continue;
            }
            if (line.startsWith(":")) continue;
            int c = line.indexOf(':');
            String k = c < 0 ? line : line.substring(0, c), v = c < 0 ? "" : line.substring(c + 1);
            if (v.startsWith(" ")) v = v.substring(1);
            if (k.equals("event")) ev = v;
            else if (k.equals("data")) { if (data.length() > 0) data.append('\n'); data.append(v); }
        }
    }

    /** 老的 HTTP+SSE（2024-11-05）：GET 拿 endpoint 事件，之后 POST 到那个地址，结果从事件流回来。 */
    public static final class Sse implements Client {
        final Spec spec;
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        final Map<Long, CompletableFuture<Map<String, Object>>> waiting = new ConcurrentHashMap<>();
        final AtomicLong ids = new AtomicLong(1);
        final CompletableFuture<String> endpoint = new CompletableFuture<>();
        volatile InputStream stream;
        volatile boolean closed;

        public Sse(Spec s) throws IOException {
            spec = s;
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(s.url)).header("Accept", "text/event-stream").GET();
            for (Map.Entry<String, String> h : s.headers.entrySet()) b.header(h.getKey(), h.getValue());
            CompletableFuture<HttpResponse<InputStream>> rf = http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> r;
            try { r = rf.get(15, TimeUnit.SECONDS); }
            catch (Exception e) { rf.cancel(true); throw new IOException("连不上 " + redactUrl(s.url) + "：" + clean(String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), s)); }
            if (r.statusCode() / 100 != 2) { r.body().close(); throw new IOException("SSE 连接被拒（HTTP " + r.statusCode() + "）"); }
            stream = r.body();
            Thread t = new Thread(() -> {
                try {
                    sse(stream, (ev, data) -> {
                        if ("endpoint".equals(ev)) { endpoint.complete(URI.create(spec.url).resolve(data.trim()).toString()); return false; }
                        try { Map<String, Object> rep = dispatch(Json.parse(data), waiting); if (rep != null) post(rep); } catch (Exception ignored) { }
                        return closed;
                    });
                } catch (IOException ignored) { }
                for (CompletableFuture<Map<String, Object>> f : waiting.values()) f.completeExceptionally(new IOException("SSE 连接断了"));
                endpoint.completeExceptionally(new IOException("SSE 连接断了，没收到 endpoint"));
            }, "onesay-mcp-sse");
            t.setDaemon(true);
            t.start();
            try { String ep = endpoint.get(15, TimeUnit.SECONDS); if (!URI.create(ep).getHost().equalsIgnoreCase(URI.create(s.url).getHost())) throw new IOException("服务器给的 endpoint 在别的主机上（" + URI.create(ep).getHost() + "），没用"); }
            catch (IOException e) { close(); throw e; }
            catch (Exception e) { close(); throw new IOException("SSE 服务器 15 秒没给 endpoint"); }
        }

        void post(Map<String, Object> m) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint.get())).header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(m)));
            for (Map.Entry<String, String> h : spec.headers.entrySet()) b.header(h.getKey(), h.getValue());
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IOException("POST 被拒（HTTP " + r.statusCode() + "）");
        }

        @Override public Map<String, Object> request(String method, Map<String, Object> params, long timeoutMs) throws IOException {
            long id = ids.getAndIncrement();
            CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
            waiting.put(id, f);
            try {
                post(rpc(id, method, params));
                return resultOf(waitCancellable(f, timeoutMs, method), method);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(clean(String.valueOf(e.getMessage()), spec));
            } finally {
                waiting.remove(id);
            }
        }

        @Override public void notify(String method, Map<String, Object> params) throws IOException {
            try { post(rpc(-1, method, params)); } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(String.valueOf(e.getMessage())); }
        }

        @Override public void close() {
            closed = true;
            try { if (stream != null) stream.close(); } catch (IOException ignored) { }
        }
    }

    /** 按 spec 连上并 initialize。transport=auto 时先试 Streamable HTTP，不行再试 SSE。返回 {client, serverInfo}。 */
    public static Object[] connect(Spec s, long timeoutMs) throws IOException {
        Client c;
        String used = s.transport;
        if (s.url.isEmpty()) c = new Stdio(s);
        else if ("sse".equals(s.transport)) c = new Sse(s);
        else c = new Http(s);
        Map<String, Object> init;
        try {
            init = initialize(c, timeoutMs);
            if (c instanceof Http) ((Http) c).proto = str(init.get("protocolVersion"));
            if (c instanceof Http) used = "http";
        } catch (Http.NotStreamable e) {
            c.close();
            if (!"auto".equals(s.transport)) throw new IOException("服务器不认 Streamable HTTP（" + e.getMessage() + "）；如果它是老的 SSE 服务器，把 type 写成 sse");
            c = new Sse(s);
            used = "sse";
            try { init = initialize(c, timeoutMs); } catch (IOException e2) { c.close(); throw e2; }
        } catch (IOException e) {
            String extra = c.lastError();
            c.close();
            throw new IOException(clean(e.getMessage() + (extra.isEmpty() || e.getMessage().contains(extra) ? "" : "（" + extra + "）"), s));
        }
        return new Object[] {c, init, used};
    }

    static Map<String, Object> initialize(Client c, long timeoutMs) throws IOException {
        Map<String, Object> p = new TreeMap<>();
        p.put("protocolVersion", PROTOCOL);
        p.put("capabilities", new TreeMap<String, Object>());
        Map<String, Object> ci = new TreeMap<>();
        ci.put("name", "OneSay");
        ci.put("version", Flavor.version());
        p.put("clientInfo", ci);
        Map<String, Object> r = c.request("initialize", p, timeoutMs);
        c.notify("notifications/initialized", null);
        return r;
    }

    /** tools/list（跟着 nextCursor 翻页，最多 500 个）。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listTools(Client c, long timeoutMs) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20 && out.size() < 500; page++) {
            Map<String, Object> p = new TreeMap<>();
            if (cursor != null) p.put("cursor", cursor);
            Map<String, Object> r = c.request("tools/list", p, timeoutMs);
            if (r.get("tools") instanceof List) for (Object t : (List<Object>) r.get("tools")) if (t instanceof Map && ((Map<String, Object>) t).get("name") != null) out.add((Map<String, Object>) t);
            cursor = r.get("nextCursor") == null ? null : str(r.get("nextCursor"));
            if (cursor == null || cursor.isEmpty()) break;
        }
        return out;
    }

    /** 工具结果 → 给模型看的文字（text 内容拼起来；图片 / 资源只说有）。 */
    @SuppressWarnings("unchecked")
    public static String resultText(Map<String, Object> r) {
        StringBuilder b = new StringBuilder();
        if (r.get("content") instanceof List) for (Object o : (List<Object>) r.get("content")) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> c = (Map<String, Object>) o;
            String t = str(c.get("type"));
            if (t.equals("text")) b.append(str(c.get("text"))).append('\n');
            else if (t.equals("image") || t.equals("audio")) b.append("（").append(t.equals("image") ? "图片" : "音频").append("，").append(str(c.get("mimeType"))).append("）\n");
            else if (t.equals("resource")) { Object res = c.get("resource"); b.append(res instanceof Map && ((Map<String, Object>) res).get("text") != null ? str(((Map<String, Object>) res).get("text")) : "（资源 " + (res instanceof Map ? str(((Map<String, Object>) res).get("uri")) : "") + "）").append('\n'); }
            else if (t.equals("resource_link")) b.append("（资源链接 ").append(str(c.get("uri"))).append("）\n");
        }
        if (b.length() == 0 && r.get("structuredContent") != null) b.append(Json.stringify(r.get("structuredContent")));
        return b.toString().trim();
    }

    /** 工具是不是声明了只读（readOnlyHint=true 且没标 destructiveHint=true）。 */
    @SuppressWarnings("unchecked")
    public static boolean readOnly(Map<String, Object> tool) {
        Object a = tool == null ? null : tool.get("annotations");
        if (!(a instanceof Map)) return false;
        Map<String, Object> m = (Map<String, Object>) a;
        return Boolean.TRUE.equals(m.get("readOnlyHint")) && !Boolean.TRUE.equals(m.get("destructiveHint"));
    }

    /** 名字里只留模型函数名能用的字符。 */
    static String safe(String s, int max) {
        String t = (s == null ? "" : s).replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (t.isEmpty()) t = "x";
        return t.length() > max ? t.substring(0, max) : t;
    }

    static final Map<String, String> EMPTY = Collections.unmodifiableMap(new LinkedHashMap<>());
}
