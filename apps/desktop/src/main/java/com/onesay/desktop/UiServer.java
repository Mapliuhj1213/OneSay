package com.onesay.desktop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 界面服务：只监听 127.0.0.1，把 ui/index.html 交给 Edge 应用窗口。
 * 防护：每次启动生成随机口令，注入页面；所有 /api 必须带 X-OneSay-Token；Host 必须是 127.0.0.1:端口（挡 DNS 重绑定）。
 * ★ fix-1118 SEC L2：页面本身（带口令）也不再无鉴权给出 —— GET / 要带本次启动的入口钥匙 ?k=（只在本进程打开窗口 / 托盘时用，不进日志）；
 * 别的本机程序拿不到口令。再次启动时第二个进程只打 /show，由正在运行的这个自己开窗口。
 */
public final class UiServer {
    public static final int DEFAULT_PORT = 47830;
    private final Map<String, Function<Map<String, Object>, Object>> api = new TreeMap<>();
    private final String token;
    /** ★ fix-1118 SEC L2：入口钥匙（GET / 用），与 API 口令分开。 */
    private final String launchKey;
    /** 第二个进程打 /show 时叫出窗口（App 设置）。 */
    public volatile Runnable onShow;
    private HttpServer http;
    private int port;

    public UiServer() { this(null); }

    /** ★ fix-1118 SEC L2：presetLaunchKey 只给互通测试用（App 只在 ONESAY_TEST_ACTIONS=1 时传 ONESAY_TEST_LAUNCH_KEY），正常运行为 null = 随机。 */
    public UiServer(String presetLaunchKey) {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        byte[] k = new byte[18];
        new SecureRandom().nextBytes(k);
        launchKey = presetLaunchKey != null && presetLaunchKey.matches("[A-Za-z0-9_-]{16,64}") ? presetLaunchKey
                : Base64.getUrlEncoder().withoutPadding().encodeToString(k);
    }

    public void on(String path, Function<Map<String, Object>, Object> h) { api.put(path, h); }

    /** 测试用：直接拿某个接口的处理函数（不经 HTTP / 口令）。 */
    Function<Map<String, Object>, Object> handler(String path) { return api.get(path); }

    public int port() { return port; }

    /** 带入口钥匙的窗口地址（不要写进日志，日志用 {@link #baseUrl()}）。 */
    public String url() { return baseUrl() + "?k=" + launchKey; }

    public String baseUrl() { return "http://127.0.0.1:" + port + "/"; }

    static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String kv : rawQuery.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) return kv.substring(eq + 1);
        }
        return null;
    }

    /** 绑定端口；端口被占用返回 false（通常是已经开着一个 OneSay）。 */
    public boolean start(int wanted) throws IOException {
        try {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), wanted), 16);
        } catch (java.net.BindException e) {
            return false;
        }
        port = http.getAddress().getPort();
        http.setExecutor(Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "onesay-ui"); t.setDaemon(true); return t; }));
        http.createContext("/", this::handle);
        http.start();
        return true;
    }

    public void stop() { if (http != null) http.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String host = String.valueOf(ex.getRequestHeaders().getFirst("Host"));
            if (!host.equals("127.0.0.1:" + port) && !host.equals("localhost:" + port)) { send(ex, 403, "text/plain", "forbidden".getBytes()); return; }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                String k = queryParam(ex.getRequestURI().getRawQuery(), "k");
                if (k == null || !MessageDigest.isEqual(k.getBytes(StandardCharsets.UTF_8), launchKey.getBytes(StandardCharsets.UTF_8))) {
                    send(ex, 403, "text/html; charset=utf-8", ("<!doctype html><meta charset=utf-8><title>OneSay</title>"
                            + "<p style=\"font:16px sans-serif;margin:3em\">OneSay 正在运行。请从托盘图标或桌面快捷方式打开窗口。</p>").getBytes(StandardCharsets.UTF_8));
                    return;
                }
                String html = new String(resource("onesay/ui/index.html"), StandardCharsets.UTF_8).replace("__ONESAY_TOKEN__", token);
                ex.getResponseHeaders().add("Cache-Control", "no-store");
                ex.getResponseHeaders().add("Content-Security-Policy", "default-src 'self' 'unsafe-inline' data:; connect-src 'self'");
                send(ex, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/favicon.ico")) { send(ex, 200, "image/x-icon", resource("onesay/ui/favicon.ico")); return; }
            if (path.equals("/qr.js")) { send(ex, 200, "text/javascript; charset=utf-8", resource("onesay/ui/qr.js")); return; }
            if (path.equals("/ping")) { send(ex, 200, "text/plain", "onesay".getBytes()); return; }
            if (path.equals("/show") && ex.getRequestMethod().equals("POST")) {   // ★ fix-1118 SEC L2：再次启动 → 正在运行的进程自己开窗口（不回任何口令）
                Runnable r = onShow;
                if (r != null) r.run();
                send(ex, 200, "text/plain", "ok".getBytes());
                return;
            }
            if (!path.startsWith("/api/")) { send(ex, 404, "text/plain", "not found".getBytes()); return; }
            String t = ex.getRequestHeaders().getFirst("X-OneSay-Token");
            if (t == null || !MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) { send(ex, 401, "text/plain", "no token".getBytes()); return; }
            Function<Map<String, Object>, Object> h = api.get(path.substring(5));
            if (h == null) { send(ex, 404, "text/plain", "no api".getBytes()); return; }
            Map<String, Object> body = new TreeMap<>();
            if (ex.getRequestMethod().equals("POST")) {
                byte[] in = readAll(ex.getRequestBody(), 256 * 1024);
                if (in.length > 0) body = Json.obj(new String(in, StandardCharsets.UTF_8));
            }
            Object out;
            try {
                out = h.apply(body);
            } catch (RuntimeException e) {
                Map<String, Object> err = new TreeMap<>();
                err.put("ok", false);
                err.put("text", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                out = err;
            }
            send(ex, 200, "application/json; charset=utf-8", Json.stringify(out).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            try { send(ex, 500, "text/plain", "error".getBytes()); } catch (IOException ignored) { }
        } finally {
            ex.close();
        }
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream in = UiServer.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing " + name);
            return in.readAllBytes();
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > max) throw new IOException("too large");
        }
        return bo.toByteArray();
    }

    private static void send(HttpExchange ex, int code, String type, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }
}
