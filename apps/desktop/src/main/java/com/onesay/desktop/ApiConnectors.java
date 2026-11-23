package com.onesay.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * [workflow-1120] 万能 API 连接器：用户手动填 名称 / 请求地址 / key / key 的放法 / API 类型，OneSay 就能把它当一个「能力」或工作流里的接口节点用。
 *
 * <ul>
 *   <li>key 只进 {@link SecretStore}（Windows 凭据管理器），connectors.json 里只记「存了」；日志、导出、给模型看的说明里都没有 key。</li>
 *   <li>认得出的常见服务（OpenAI 兼容等）按预设走；认不出的算「冷门」，<b>冷门必须写备注才能保存</b>（写清楚这是什么、谁家的）。</li>
 *   <li>「测试」：按预设打一个只读请求（OpenAI 兼容是 GET /models），2xx 才算验通；验通后才能在工作流 / 能力里用。改了地址或 key 要重新测试。</li>
 *   <li>风险档：GET 算只读；POST / PUT / PATCH / DELETE 要审批（标了「会花钱」的算花钱档）。</li>
 * </ul>
 */
public final class ApiConnectors {
    public static final Set<String> TYPES = Set.of("image", "video", "llm", "backend");
    public static final Set<String> KEY_MODES = Set.of("bearer", "header", "query", "none");
    static final int MAX_RESP = 2 * 1024 * 1024;

    /** 预设：host 后缀 → [预设 id, 给人看的名字, 测试路径, 默认 key 放法, 默认头名]。 */
    static final String[][] PRESETS = {
            {"api.openai.com", "openai", "OpenAI 兼容（OpenAI）", "/models", "bearer", ""},
            {"api.deepseek.com", "openai", "OpenAI 兼容（DeepSeek）", "/models", "bearer", ""},
            {"api.moonshot.cn", "openai", "OpenAI 兼容（月之暗面）", "/models", "bearer", ""},
            {"dashscope.aliyuncs.com", "openai", "OpenAI 兼容（阿里云百炼）", "/models", "bearer", ""},
            {"open.bigmodel.cn", "openai", "OpenAI 兼容（智谱）", "/models", "bearer", ""},
            {"api.siliconflow.cn", "openai", "OpenAI 兼容（硅基流动）", "/models", "bearer", ""},
            {"openrouter.ai", "openai", "OpenAI 兼容（OpenRouter）", "/models", "bearer", ""},
            {"ark.cn-beijing.volces.com", "openai", "OpenAI 兼容（火山方舟）", "/models", "bearer", ""},
            {"api.groq.com", "openai", "OpenAI 兼容（Groq）", "/models", "bearer", ""},
            {"api.mistral.ai", "openai", "OpenAI 兼容（Mistral）", "/models", "bearer", ""},
            {"api.anthropic.com", "anthropic", "Anthropic", "/v1/models", "header", "x-api-key"},
            {"generativelanguage.googleapis.com", "gemini", "Google Gemini", "/v1beta/models", "query", "key"},
            {"api.stability.ai", "stability", "Stability AI（图片）", "/v1/user/account", "bearer", ""},
            {"api.replicate.com", "replicate", "Replicate", "/v1/account", "bearer", ""},
            {"fal.run", "fal", "fal.ai（图片 / 视频）", "", "header", "Authorization"},
            {"api.github.com", "github", "GitHub REST", "/user", "bearer", ""},
    };

    /** 一个连接器（不含 key）。 */
    public static final class Conn {
        public String id = "", name = "", url = "", keyMode = "bearer", keyName = "", type = "backend", preset = "", presetName = "", note = "", testPath = "";
        public boolean verified, costs, hasKey;
        public long verifiedAt;
        /** 验通时的地址 + key 指纹（key 的 sha256 前 12 位，不可逆）；变了就要重测。 */
        public String verifiedFp = "";

        Map<String, Object> view() {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("url", url);
            m.put("key_mode", keyMode);
            m.put("key_name", keyName);
            m.put("type", type);
            m.put("type_zh", typeZh(type));
            m.put("preset", preset);
            m.put("preset_name", presetName.isEmpty() ? "冷门（没认出来）" : presetName);
            m.put("cold", preset.isEmpty());
            m.put("note", note);
            m.put("test_path", testPath);
            m.put("verified", verified);
            m.put("verified_at", verifiedAt);
            m.put("costs", costs);
            m.put("has_key", hasKey);
            return m;
        }

        Map<String, Object> save() {
            Map<String, Object> m = view();
            m.remove("type_zh");
            m.remove("preset_name");
            m.remove("cold");
            m.put("preset_name", presetName);
            m.put("verified_fp", verifiedFp);
            return m;
        }
    }

    static String typeZh(String t) {
        switch (t) {
            case "image": return "图片";
            case "video": return "视频";
            case "llm": return "大模型";
            default: return "后台接口";
        }
    }

    private final Path file;
    private final SecretStore secrets;
    private final Consumer<String> log;
    private final Map<String, Conn> conns = new TreeMap<>();
    /** key 缓存（凭据库每次读都要起一个 PowerShell，太慢）；只在内存。 */
    private final Map<String, String> keyCache = new ConcurrentHashMap<>();
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public ApiConnectors(Path file, SecretStore secrets, Consumer<String> log) {
        this.file = file;
        this.secrets = secrets;
        this.log = log == null ? s -> { } : log;
        load();
    }

    @SuppressWarnings("unchecked")
    synchronized void load() {
        conns.clear();
        try {
            if (file == null || !Files.exists(file)) return;
            Map<String, Object> m = Json.obj(Files.readString(file, StandardCharsets.UTF_8));
            Object l = m.get("connectors");
            if (l instanceof List) for (Object o : (List<Object>) l) if (o instanceof Map) {
                Map<String, Object> x = (Map<String, Object>) o;
                Conn c = new Conn();
                c.id = s(x.get("id"));
                c.name = s(x.get("name"));
                c.url = s(x.get("url"));
                c.keyMode = s(x.get("key_mode"));
                c.keyName = s(x.get("key_name"));
                c.type = s(x.get("type"));
                c.preset = s(x.get("preset"));
                c.presetName = s(x.get("preset_name"));
                c.note = s(x.get("note"));
                c.testPath = s(x.get("test_path"));
                c.verified = Boolean.TRUE.equals(x.get("verified"));
                c.costs = Boolean.TRUE.equals(x.get("costs"));
                c.hasKey = Boolean.TRUE.equals(x.get("has_key"));
                c.verifiedAt = x.get("verified_at") instanceof Number ? ((Number) x.get("verified_at")).longValue() : 0;
                c.verifiedFp = s(x.get("verified_fp"));
                if (!c.id.isEmpty()) conns.put(c.id, c);
            }
        } catch (IOException | RuntimeException e) {
            log.accept("连接器配置读不出来：" + e.getClass().getSimpleName());
        }
    }

    private synchronized void persist() {
        if (file == null) return;
        List<Object> l = new ArrayList<>();
        for (Conn c : conns.values()) l.add(c.save());
        Map<String, Object> m = new TreeMap<>();
        m.put("connectors", l);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.stringify(m), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.accept("连接器配置写不进去：" + e.getClass().getSimpleName());
        }
    }

    static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    /** 认预设：返回 [id, 名字, 测试路径, key 放法, 头名]；认不出返回 null（冷门）。 */
    public static String[] detect(String url, String type) {
        URI u;
        try { u = URI.create(url.trim()); } catch (RuntimeException e) { return null; }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        String path = u.getPath() == null ? "" : u.getPath();
        for (String[] p : PRESETS) if (host.equals(p[0]) || host.endsWith("." + p[0])) return new String[] {p[1], p[2], p[3], p[4], p[5]};
        // 本机 Ollama / LM Studio 这类：路径以 /v1 结尾或带 /compatible-mode/v1、且是大模型类型 → OpenAI 兼容（推测）
        if ("llm".equals(type) && (path.matches(".*/v1/?") || path.contains("/compatible-mode/"))) return new String[] {"openai", "OpenAI 兼容（按地址推测）", "/models", "bearer", ""};
        return null;
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Conn c : conns.values()) out.add(c.view());
        return out;
    }

    public synchronized Conn get(String id) { return conns.get(id); }

    /**
     * 新建 / 修改。in：id（改时）、name、url、key（空 = 不改）、key_mode、key_name、type、note、costs、test_path、clear_key。
     * 返回 {ok,text,connector}。冷门没写备注 → ok=false。
     */
    public synchronized Map<String, Object> save(Map<String, Object> in) {
        String name = s(in.get("name")).trim(), url = s(in.get("url")).trim(), type = s(in.get("type")).trim();
        String keyMode = s(in.get("key_mode")).trim(), keyName = s(in.get("key_name")).trim(), note = s(in.get("note")).trim();
        if (name.isEmpty() || name.length() > 40) return fail("名称要写（最多 40 字）");
        if (!url.matches("(?i)https?://[^\\s]+") || url.length() > 500) return fail("请求地址要以 http:// 或 https:// 开头");
        URI u;
        try { u = URI.create(url); } catch (RuntimeException e) { return fail("请求地址写得不对"); }
        if (u.getHost() == null) return fail("请求地址里没有主机名");
        if (u.getUserInfo() != null) return fail("请求地址里不要带用户名 / 密码，key 填在下面的 key 栏");
        if (u.getRawQuery() != null && u.getRawQuery().matches("(?i).*(key|token|secret|sig)=.*")) return fail("请求地址里像是带了 key，请把 key 填在 key 栏、放法选「网址参数」");
        if (url.toLowerCase(Locale.ROOT).startsWith("http://") && !isLoopback(u.getHost())) return fail("非本机地址要用 https://（http 会把 key 明文发出去）");
        if (!TYPES.contains(type)) return fail("API 类型选一个：图片 / 视频 / 大模型 / 后台接口");
        String[] p = detect(url, type);
        if (keyMode.isEmpty()) keyMode = p != null ? p[3] : "bearer";
        if (!KEY_MODES.contains(keyMode)) return fail("key 的放法只能是 Bearer / 自定义请求头 / 网址参数 / 不用 key");
        if (keyMode.equals("header") && keyName.isEmpty() && p != null) keyName = p[4];
        if ((keyMode.equals("header") || keyMode.equals("query")) && !keyName.matches("[A-Za-z0-9_.-]{1,64}")) return fail(keyMode.equals("header") ? "自定义请求头要写头的名字（如 X-API-Key）" : "网址参数要写参数名（如 key）");
        if (p == null && note.isEmpty()) return fail("没认出这是哪家的接口（冷门），请在备注里写清楚这是什么服务、谁家的，才能保存");
        if (note.length() > 200) return fail("备注最多 200 字");
        String id = s(in.get("id")).trim();
        Conn c = id.isEmpty() ? null : conns.get(id);
        if (c == null) {
            c = new Conn();
            c.id = newId(name);
        }
        String key = s(in.get("key"));
        boolean changed = !url.equals(c.url) || !keyMode.equals(c.keyMode) || !keyName.equals(c.keyName);
        c.name = name;
        c.url = url;
        c.type = type;
        c.keyMode = keyMode;
        c.keyName = keyMode.equals("bearer") || keyMode.equals("none") ? "" : keyName;
        c.note = note;
        c.costs = Boolean.TRUE.equals(in.get("costs"));
        c.testPath = s(in.get("test_path")).trim();
        if (!c.testPath.isEmpty() && !c.testPath.startsWith("/")) c.testPath = "/" + c.testPath;
        c.preset = p == null ? "" : p[0];
        c.presetName = p == null ? "" : p[1];
        if (!key.isEmpty()) {
            if (key.length() > 4096 || key.matches(".*[\\r\\n].*")) return fail("key 太长或带了换行");
            if (!secrets.put(SecretStore.name("api", c.id), key.trim())) {
                String why = secrets.lastProblem();
                if (!conns.containsKey(c.id)) secrets.delete(SecretStore.name("api", c.id));   // 新连接没存成：别在凭据库里留半条
                return fail("key 没存进" + secrets.where() + "，没保存" + (why == null ? "" : "：" + why));
            }
            keyCache.put(c.id, key.trim());
            c.hasKey = true;
            changed = true;
        } else if (Boolean.TRUE.equals(in.get("clear_key"))) {
            secrets.delete(SecretStore.name("api", c.id));
            keyCache.remove(c.id);
            c.hasKey = false;
            changed = true;
        }
        if (changed) { c.verified = false; c.verifiedFp = ""; }
        conns.put(c.id, c);
        persist();
        log.accept("连接器已保存：" + c.name + "（" + (c.presetName.isEmpty() ? "冷门" : c.presetName) + "）");
        Map<String, Object> r = ok(changed ? "已保存。点「测试」验通后才能在工作流里用" : "已保存");
        r.put("connector", c.view());
        return r;
    }

    static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }

    private String newId(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty() || base.length() > 24) base = "api";
        String id = base;
        for (int i = 2; conns.containsKey(id); i++) id = base + "-" + i;
        return id;
    }

    public synchronized Map<String, Object> delete(String id) {
        Conn c = conns.remove(id);
        if (c == null) return fail("没有这个连接器");
        secrets.delete(SecretStore.name("api", id));
        keyCache.remove(id);
        persist();
        return ok("已删除连接器「" + c.name + "」，key 也从凭据库删了");
    }

    String key(Conn c) {
        if (!c.hasKey || c.keyMode.equals("none")) return "";
        String k = keyCache.get(c.id);
        if (k == null) {
            k = secrets.get(SecretStore.name("api", c.id));
            if (k != null) keyCache.put(c.id, k);
        }
        return k == null ? "" : k;
    }

    static String fp(String url, String key) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((url + "\n" + key).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    /** 把 key 从一段文字里抹掉（接口回显、报错里带了 key 时）。 */
    public String redact(String text) {
        if (text == null) return "";
        String t = text;
        for (String k : keyCache.values()) if (k != null && k.length() >= 4) t = t.replace(k, "••••");
        return t;
    }

    /** 已知的所有 key（给运行日志统一抹掉用）。 */
    public java.util.Collection<String> knownSecrets() { return new ArrayList<>(keyCache.values()); }

    /** 「测试」：按预设打一个 GET。 */
    public Map<String, Object> test(String id) {
        Conn c;
        synchronized (this) { c = conns.get(id); }
        if (c == null) return fail("没有这个连接器");
        if (!c.keyMode.equals("none") && !c.hasKey) return fail("还没填 key");
        String path = !c.testPath.isEmpty() ? c.testPath : c.preset.isEmpty() ? "" : presetTestPath(c);
        Resp r = send(c, "GET", path, null, null, null, 15);
        Map<String, Object> m;
        if (r.status >= 200 && r.status < 300) {
            synchronized (this) {
                c.verified = true;
                c.verifiedAt = System.currentTimeMillis();
                c.verifiedFp = fp(c.url, key(c));
                persist();
            }
            m = ok("连通了（HTTP " + r.status + "，" + r.ms + " 毫秒）。现在可以在工作流里用了");
        } else if (r.status == 401 || r.status == 403) {
            m = fail("连上了服务器，但 key 不对或没权限（HTTP " + r.status + "）");
        } else if (r.status == 404) {
            m = fail("连上了服务器，但测试地址不存在（HTTP 404）。冷门接口可以在「测试路径」里填一个能 GET 的地址");
        } else if (r.status > 0) {
            m = fail("服务器回了 HTTP " + r.status);
        } else {
            m = fail("连不上：" + r.error);
        }
        m.put("status", (long) r.status);
        m.put("connector", c.view());
        log.accept("连接器测试：" + c.name + " → " + (r.status > 0 ? "HTTP " + r.status : r.error));
        return m;
    }

    static String presetTestPath(Conn c) {
        for (String[] p : PRESETS) if (p[1].equals(c.preset)) return p[3];
        return c.preset.equals("openai") ? "/models" : "";
    }

    /** 能不能直接用（验通过，而且地址 / key 没改过）。 */
    public boolean usable(Conn c) {
        return c != null && c.verified && fp(c.url, key(c)).equals(c.verifiedFp);
    }

    /** 这个请求的风险档：read / outbound / spend。 */
    public static String risk(Conn c, String method) {
        String m = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        if (m.equals("GET")) return "read";
        return c != null && c.costs ? "spend" : "outbound";
    }

    /** 一次请求的结果。body 已经抹掉 key。 */
    public static final class Resp {
        public int status;
        public String body = "", error = "", contentType = "";
        public long ms;

        public Map<String, Object> output() {
            Map<String, Object> m = new TreeMap<>();
            m.put("status", (long) status);
            Object parsed = null;
            if (contentType.contains("json") || body.startsWith("{") || body.startsWith("[")) { try { parsed = Json.parse(body); } catch (RuntimeException ignored) { } }
            m.put("body", parsed != null ? parsed : body);
            if (!error.isEmpty()) m.put("error", error);
            return m;
        }
    }

    /** 发请求（审批由调用方做）。path 拼在地址后面；query / headers 是额外的；body 是对象就发 JSON。 */
    public Resp send(Conn c, String method, String path, Map<String, Object> query, Map<String, Object> headers, Object body, int timeoutSec) {
        Resp r = new Resp();
        long t0 = System.currentTimeMillis();
        try {
            String base = c.url.replaceAll("/+$", "");
            String p = path == null ? "" : path.trim();
            if (!p.isEmpty() && !p.startsWith("/")) p = "/" + p;
            if (p.contains("://") || p.contains("..") || p.contains("@")) { r.error = "路径不合法"; return r; }
            StringBuilder url = new StringBuilder(base + p);
            List<String> qs = new ArrayList<>();
            if (query != null) for (Map.Entry<String, Object> e : query.entrySet()) qs.add(enc(e.getKey()) + "=" + enc(s(e.getValue())));
            String key = key(c);
            if (c.keyMode.equals("query") && !key.isEmpty()) qs.add(enc(c.keyName) + "=" + enc(key));
            if (!qs.isEmpty()) url.append(url.indexOf("?") >= 0 ? "&" : "?").append(String.join("&", qs));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(Math.max(3, Math.min(timeoutSec, 120))));
            if (headers != null) for (Map.Entry<String, Object> e : headers.entrySet()) {
                String hn = e.getKey();
                if (!hn.matches("[A-Za-z0-9-]{1,64}") || hn.equalsIgnoreCase("host") || hn.equalsIgnoreCase("content-length") || hn.equalsIgnoreCase("connection")) continue;
                b.header(hn, s(e.getValue()));
            }
            if (c.keyMode.equals("bearer") && !key.isEmpty()) b.header("Authorization", "Bearer " + key);
            if (c.keyMode.equals("header") && !key.isEmpty()) b.header(c.keyName, c.keyName.equalsIgnoreCase("Authorization") && c.preset.equals("fal") ? "Key " + key : key);
            if (c.preset.equals("anthropic")) b.header("anthropic-version", "2023-06-01");
            String m = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
            if (body == null || m.equals("GET") || m.equals("DELETE") && body == null) b.method(m, HttpRequest.BodyPublishers.noBody());
            else {
                String bs = body instanceof String ? (String) body : Json.stringify(body);
                if (!(body instanceof String)) b.header("Content-Type", "application/json");
                b.method(m, HttpRequest.BodyPublishers.ofString(bs, StandardCharsets.UTF_8));
            }
            HttpResponse<InputStream> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            r.status = resp.statusCode();
            r.contentType = resp.headers().firstValue("content-type").orElse("");
            byte[] raw;
            try (InputStream in = resp.body()) { raw = in.readNBytes(MAX_RESP + 1); }
            String txt = new String(raw, 0, Math.min(raw.length, MAX_RESP), StandardCharsets.UTF_8);
            r.body = redact(txt) + (raw.length > MAX_RESP ? "…（超过 2 MB，后面截掉了）" : "");
        } catch (java.net.http.HttpTimeoutException e) {
            r.error = "超时";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r.error = "被取消";
        } catch (Exception e) {
            r.error = redact(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "：" + e.getMessage()));
        }
        r.ms = System.currentTimeMillis() - t0;
        return r;
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static Map<String, Object> ok(String t) { Map<String, Object> m = new TreeMap<>(); m.put("ok", true); m.put("text", t); return m; }

    static Map<String, Object> fail(String t) { Map<String, Object> m = ok(t); m.put("ok", false); return m; }

    /** 给能力列表 / 模型看的说明（不含 key、不含完整地址的查询串）。 */
    public synchronized List<Map<String, Object>> capabilities() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Conn c : conns.values()) {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", "api:" + c.id);
            m.put("name", c.name);
            m.put("kind", "api");
            m.put("kind_zh", "API 连接器");
            m.put("description", typeZh(c.type) + " · " + (c.presetName.isEmpty() ? "冷门：" + c.note : c.presetName));
            m.put("risk", "read");
            m.put("risk_note", "GET 只读；其他方法要你批准" + (c.costs ? "（会花钱）" : ""));
            m.put("credential", c.keyMode.equals("none") ? "不用 key" : c.hasKey ? "key 在" + secrets.where() : "还没填 key");
            m.put("ready", usable(c));
            Map<String, Object> sch = new TreeMap<>();
            sch.put("type", "object");
            Map<String, Object> props = new TreeMap<>();
            props.put("method", Map.of("type", "string", "enum", List.of("GET", "POST", "PUT", "PATCH", "DELETE")));
            props.put("path", Map.of("type", "string", "title", "路径"));
            props.put("query", Map.of("type", "object", "title", "网址参数"));
            props.put("body", Map.of("type", "object", "title", "请求体"));
            sch.put("properties", props);
            m.put("schema", sch);
            out.add(m);
        }
        return out;
    }
}
