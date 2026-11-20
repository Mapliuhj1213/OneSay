package com.onesay.desktop;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * [workflow-1120] OAuth 通用框架：两种桌面端常用的授权方式。
 * <ul>
 *   <li><b>设备码（device flow，RFC 8628）</b>：界面显示一串验证码和网址，用户在浏览器里输入后，OneSay 轮询拿令牌。GitHub 预设默认用它。</li>
 *   <li><b>本机回环跳转（RFC 8252）</b>：在 127.0.0.1 随机端口起一个一次性的接收页，带 PKCE（S256）和 state，浏览器授权后跳回来，OneSay 换令牌。</li>
 * </ul>
 * 令牌只放 {@link SecretStore}；界面、日志、导出里只出现「已连接 / 账号名」。client_id 由用户填自己在服务商那里注册的应用（OneSay 不内置任何应用的口令）。
 * 这一版不会替用户真的去授权任何账号：测试全部用本机模拟服务器。
 */
public final class OAuthHub {
    public static final class Provider {
        public String id = "", name = "", flow = "device", authorizeUrl = "", tokenUrl = "", deviceUrl = "", scopes = "", clientId = "", apiBase = "", userPath = "";

        Map<String, Object> view(boolean connected) {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("flow", flow);
            m.put("flow_zh", flow.equals("device") ? "设备码（在浏览器里输入验证码）" : "本机回环跳转（浏览器授权后跳回 OneSay）");
            m.put("scopes", scopes);
            m.put("client_id_set", !clientId.isEmpty());
            m.put("connected", connected);
            return m;
        }
    }

    /** 预设 GitHub（设备码）。端点可被测试替换成模拟服务器。 */
    public static Provider github() {
        Provider p = new Provider();
        p.id = "github";
        p.name = "GitHub";
        p.flow = "device";
        p.deviceUrl = "https://github.com/login/device/code";
        p.tokenUrl = "https://github.com/login/oauth/access_token";
        p.authorizeUrl = "https://github.com/login/oauth/authorize";
        p.scopes = "read:user";
        p.apiBase = "https://api.github.com";
        p.userPath = "/user";
        return p;
    }

    /** 一次进行中的授权。 */
    public static final class Pending {
        public final String id, provider;
        public volatile String state = "waiting"; // waiting / done / failed / expired
        public volatile String userCode = "", verifyUrl = "", error = "", authUrl = "";
        volatile String deviceCode = "";
        volatile long interval = 5, expiresAt;
        HttpServer loop;

        Pending(String id, String provider) { this.id = id; this.provider = provider; }

        public Map<String, Object> view() {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", id);
            m.put("provider", provider);
            m.put("state", state);
            m.put("user_code", userCode);
            m.put("verify_url", verifyUrl);
            m.put("auth_url", authUrl);
            m.put("error", error);
            return m;
        }
    }

    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new TreeMap<>();
    private final SecretStore secrets;
    private final Consumer<String> log;
    /** 打开浏览器（App 接上；测试里记下地址）。 */
    public volatile Consumer<String> opener = u -> { };
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final SecureRandom rnd = new SecureRandom();

    public OAuthHub(SecretStore secrets, Consumer<String> log) {
        this.secrets = secrets;
        this.log = log == null ? s -> { } : log;
        Provider g = github();
        providers.put(g.id, g);
    }

    public synchronized void put(Provider p) { providers.put(p.id, p); }

    public synchronized Provider provider(String id) { return providers.get(id); }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Provider p : providers.values()) out.add(p.view(connected(p.id)));
        return out;
    }

    /** 连没连（凭据库每读一次要起一个 PowerShell，这里缓存「有没有」，不缓存令牌本身）。 */
    private final Map<String, Boolean> connCache = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean connected(String id) { return connCache.computeIfAbsent(id, k -> secrets.get(SecretStore.name("oauth", k)) != null); }

    /** 令牌（给能力调用用；不进界面 / 日志）。 */
    String token(String id) { return secrets.get(SecretStore.name("oauth", id)); }

    public synchronized Map<String, Object> setClientId(String id, String clientId) {
        Provider p = providers.get(id);
        if (p == null) return fail("没有这个服务");
        if (!clientId.matches("[A-Za-z0-9._-]{4,100}")) return fail("client_id 写法不对");
        p.clientId = clientId;
        return ok("已记下 client_id");
    }

    public Map<String, Object> disconnect(String id) {
        secrets.delete(SecretStore.name("oauth", id));
        connCache.put(id, false);
        return ok("已断开，令牌已从凭据库删除");
    }

    String rand(int n) {
        byte[] b = new byte[n];
        rnd.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** 开始授权：设备码返回验证码；回环跳转返回授权地址（并调 opener 打开浏览器）。 */
    public Map<String, Object> start(String id) {
        Provider p = provider(id);
        if (p == null) return fail("没有这个服务");
        if (p.clientId.isEmpty()) return fail("先填你在 " + p.name + " 注册的 OAuth 应用的 client_id");
        Pending pd = new Pending("oa-" + rand(6), id);
        try {
            if (p.flow.equals("device")) {
                Map<String, Object> r = postForm(p.deviceUrl, Map.of("client_id", p.clientId, "scope", p.scopes));
                pd.deviceCode = s(r.get("device_code"));
                pd.userCode = s(r.get("user_code"));
                pd.verifyUrl = s(r.get("verification_uri"));
                pd.interval = r.get("interval") instanceof Number ? Math.max(1, ((Number) r.get("interval")).longValue()) : 5;
                long exp = r.get("expires_in") instanceof Number ? ((Number) r.get("expires_in")).longValue() : 900;
                pd.expiresAt = System.currentTimeMillis() + exp * 1000;
                if (pd.deviceCode.isEmpty() || pd.userCode.isEmpty()) return fail("服务没给验证码：" + s(r.getOrDefault("error_description", r.get("error"))));
                synchronized (this) { pending.put(pd.id, pd); }
                Thread t = new Thread(() -> pollDevice(p, pd), "onesay-oauth");
                t.setDaemon(true);
                t.start();
            } else {
                startLoopback(p, pd);
                synchronized (this) { pending.put(pd.id, pd); }
                try { opener.accept(pd.authUrl); } catch (RuntimeException ignored) { }
            }
        } catch (Exception e) {
            return fail("开始授权失败：" + e.getClass().getSimpleName());
        }
        Map<String, Object> m = ok(p.flow.equals("device") ? "在浏览器打开 " + pd.verifyUrl + " ，输入验证码 " + pd.userCode : "已在浏览器打开授权页，授权后会自动回到 OneSay");
        m.put("pending", pd.view());
        return m;
    }

    public synchronized Map<String, Object> poll(String pid) {
        Pending pd = pending.get(pid);
        if (pd == null) return fail("没有这次授权");
        Map<String, Object> m = ok(pd.state.equals("done") ? "已连接" : pd.state.equals("waiting") ? "等你在浏览器里授权" : pd.error);
        m.put("pending", pd.view());
        return m;
    }

    private void pollDevice(Provider p, Pending pd) {
        while (pd.state.equals("waiting")) {
            if (System.currentTimeMillis() > pd.expiresAt) { pd.state = "expired"; pd.error = "验证码过期了，请重新连接"; return; }
            try { TimeUnit.SECONDS.sleep(pd.interval); } catch (InterruptedException e) { pd.state = "failed"; pd.error = "被取消"; return; }
            try {
                Map<String, Object> r = postForm(p.tokenUrl, Map.of("client_id", p.clientId, "device_code", pd.deviceCode, "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
                String err = s(r.get("error"));
                if (err.equals("authorization_pending")) continue;
                if (err.equals("slow_down")) { pd.interval += 5; continue; }
                if (!err.isEmpty()) { pd.state = "failed"; pd.error = err.equals("access_denied") ? "你在浏览器里拒绝了" : err.equals("expired_token") ? "验证码过期了" : "授权失败（" + err + "）"; return; }
                finish(p, pd, r);
                return;
            } catch (Exception e) {
                pd.state = "failed";
                pd.error = "授权失败：" + e.getClass().getSimpleName();
                return;
            }
        }
    }

    private void finish(Provider p, Pending pd, Map<String, Object> r) {
        String tok = s(r.get("access_token"));
        if (tok.isEmpty()) { pd.state = "failed"; pd.error = "服务没给令牌"; return; }
        if (!secrets.put(SecretStore.name("oauth", p.id), tok)) { pd.state = "failed"; pd.error = "令牌没存进凭据库"; return; }
        connCache.put(p.id, true);
        pd.state = "done";
        log.accept("OAuth 已连接：" + p.name);
    }

    private void startLoopback(Provider p, Pending pd) throws IOException {
        String verifier = rand(32);
        String challenge;
        try { challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); } catch (Exception e) { throw new IOException(e); }
        String state = rand(16);
        HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4);
        int port = srv.getAddress().getPort();
        String redirect = "http://127.0.0.1:" + port + "/callback";
        CompletableFuture<Void> once = new CompletableFuture<>();
        srv.createContext("/callback", ex -> {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String msg;
            if (once.isDone()) msg = "这个授权页已经用过了。";
            else if (!state.equals(q.get("state"))) { msg = "授权失败：state 对不上（可能是别的网页伪造的跳转）。"; }
            else if (q.get("error") != null) { pd.state = "failed"; pd.error = "你在浏览器里拒绝了"; msg = "已取消授权，可以关掉这个页面。"; once.complete(null); }
            else {
                once.complete(null);
                try {
                    Map<String, String> f = new LinkedHashMap<>();
                    f.put("client_id", p.clientId);
                    f.put("code", q.getOrDefault("code", ""));
                    f.put("redirect_uri", redirect);
                    f.put("grant_type", "authorization_code");
                    f.put("code_verifier", verifier);
                    finish(p, pd, postForm(p.tokenUrl, f));
                } catch (Exception e) { pd.state = "failed"; pd.error = "换令牌失败：" + e.getClass().getSimpleName(); }
                msg = pd.state.equals("done") ? "已连接，可以关掉这个页面回到 OneSay。" : "授权失败：" + pd.error;
            }
            byte[] b = ("<!doctype html><meta charset=utf-8><title>OneSay</title><p style=\"font:16px sans-serif;margin:3em\">" + msg + "</p>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
            if (once.isDone()) new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) { } srv.stop(0); }).start();
        });
        srv.start();
        pd.loop = srv;
        pd.expiresAt = System.currentTimeMillis() + 10 * 60_000;
        Thread guard = new Thread(() -> { try { Thread.sleep(10 * 60_000); } catch (InterruptedException ignored) { } if (pd.state.equals("waiting")) { pd.state = "expired"; pd.error = "授权超时"; } srv.stop(0); });
        guard.setDaemon(true);
        guard.start();
        pd.authUrl = p.authorizeUrl + (p.authorizeUrl.contains("?") ? "&" : "?") + "response_type=code&client_id=" + enc(p.clientId) + "&redirect_uri=" + enc(redirect)
                + "&scope=" + enc(p.scopes) + "&state=" + enc(state) + "&code_challenge=" + enc(challenge) + "&code_challenge_method=S256";
    }

    Map<String, Object> postForm(String url, Map<String, String> form) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) { if (sb.length() > 0) sb.append('&'); sb.append(enc(e.getKey())).append('=').append(enc(e.getValue())); }
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(sb.toString())).build(), HttpResponse.BodyHandlers.ofString());
        String body = r.body() == null ? "" : r.body().trim();
        if (body.startsWith("{")) return Json.obj(body);
        Map<String, Object> m = new TreeMap<>();
        for (Map.Entry<String, String> e : query(body).entrySet()) m.put(e.getKey(), e.getValue());
        return m;
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new TreeMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            try { m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8)); } catch (IllegalArgumentException ignored) { }
        }
        return m;
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    static Map<String, Object> ok(String t) { Map<String, Object> m = new TreeMap<>(); m.put("ok", true); m.put("text", t); return m; }

    static Map<String, Object> fail(String t) { Map<String, Object> m = ok(t); m.put("ok", false); return m; }

    /** 能力列表里的 OAuth 账号条目。 */
    public synchronized List<Map<String, Object>> capabilities() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Provider p : providers.values()) {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", "oauth:" + p.id);
            m.put("name", p.name + " 账号");
            m.put("kind", "oauth");
            m.put("kind_zh", "OAuth");
            m.put("description", "授权范围：" + p.scopes);
            m.put("risk", "read");
            m.put("credential", connected(p.id) ? "令牌在" + secrets.where() : "没连接");
            m.put("ready", connected(p.id));
            m.put("schema", Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string", "title", "接口路径"))));
            out.add(m);
        }
        return out;
    }
}
