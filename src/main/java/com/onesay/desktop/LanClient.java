package com.onesay.desktop;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * 局域网客户端（下令端）：先 UDP 探测找到电脑（广播 + 配对时记下的地址单播），
 * 找不到再直接试连记下的地址（防路由器挡广播）。连上后发签名的 hello。
 * 找不到时 onState(false, "offline: …")，调用方据此报「离线」。
 */
public final class LanClient implements Link {
    public static final String OFFLINE = "offline: ";

    private final Config cfg;
    private final String peer;
    private final Link.Listener listener;
    private final long findMs;
    private volatile WebSocket ws;
    private volatile boolean connected, stopped;
    private CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
    public volatile InetSocketAddress found;
    /** 常连（hold）：对方（电脑）可以从这条连接往这边下令。 */
    public volatile boolean hold;
    /** 这次连接是否走了 TLS（配对串带指纹时一定走，不退回明文）。 */
    public volatile boolean tls;

    public LanClient(Config cfg, String peer, Link.Listener listener, long findMs) {
        this.cfg = cfg;
        this.peer = peer;
        this.listener = listener;
        this.findMs = findMs;
    }

    @Override public String name() { return "局域网"; }
    @Override public boolean isConnected() { return connected; }

    @Override
    public void start() {
        LanServer.daemon("onesay-lan-client", () -> {
            InetSocketAddress a = locate(cfg, peer, findMs);
            if (a == null) { listener.onState(false, OFFLINE + "局域网里没找到 " + peer); return; }
            found = a;
            try {
                boolean useTls = Tls.validFp(cfg.peerFp);
                HttpClient.Builder hb = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3));
                if (useTls) {
                    hb.sslContext(cfg.tls().clientContext(cfg.peerFp));
                    javax.net.ssl.SSLParameters sp = new javax.net.ssl.SSLParameters();
                    sp.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                    hb.sslParameters(sp);
                }
                HttpClient http = hb.build();
                String host = a.getAddress().getHostAddress();
                URI uri = URI.create((useTls ? "wss://" : "ws://") + host + ":" + a.getPort() + "/v1/ws?device=" + java.net.URLEncoder.encode(cfg.deviceId, StandardCharsets.UTF_8));
                ws = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).buildAsync(uri, new L()).get(5, TimeUnit.SECONDS);
                String id = Protocol.newId();
                Map<String, Object> b = new TreeMap<>();
                b.put("kind", "hello");
                if (useTls) b.put("cfp", cfg.tls().fp);
                if (hold) b.put("hold", true);
                Map<String, Object> hello = new TreeMap<>();
                hello.put("type", "hello");
                hello.put("from", cfg.deviceId);
                hello.put("id", id);
                hello.put("body", Protocol.seal(cfg.pairKey, id, cfg.deviceId, peer, b, System.currentTimeMillis()));
                send(Json.stringify(hello)).get(5, TimeUnit.SECONDS);
                connected = true;
                tls = useTls;
                listener.onState(true, "局域网 " + host + (useTls ? "（加密）" : ""));
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                listener.onState(false, OFFLINE + "找到了 " + peer + " 但连不上：" + c.getClass().getSimpleName());
            }
        });
    }

    @Override
    public void stop() {
        stopped = true;
        WebSocket w = ws;
        if (w != null) try { w.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Exception ignored) { }
    }

    @Override
    public synchronized CompletableFuture<?> send(String text) {
        WebSocket w = ws;
        if (w == null) return CompletableFuture.failedFuture(new IllegalStateException("not connected"));
        chain = chain.handle((a, b) -> null).thenCompose(x -> w.sendText(text, true));
        return chain;
    }

    private final class L implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String s = buf.toString();
                buf.setLength(0);
                if (!s.equals("pong")) {
                    try { listener.onFrame(Json.obj(s)); } catch (Exception ignored) { }
                }
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            connected = false;
            if (!stopped) listener.onState(false, (code == 4001 ? OFFLINE + "配对密钥不对，被 " + peer + " 拒绝"
                    : code == 4003 ? OFFLINE + peer + " 记住的是本机旧证书，请在电脑上「忘掉已配对设备的证书」后重连"
                    : code == 4002 ? OFFLINE + peer + " 要求加密连接，请用新配对串重新配对" : "断开 " + code));
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable error) {
            connected = false;
            if (!stopped) listener.onState(false, "出错：" + error.getClass().getSimpleName());
        }
    }

    // ---- 发现 ----

    /** 找电脑：返回它的 WebSocket 地址，找不到返回 null。 */
    public static InetSocketAddress locate(Config cfg, String peer, long budgetMs) {
        long end = System.currentTimeMillis() + budgetMs;
        List<InetSocketAddress> hints = hints(cfg);
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            ds.setSoTimeout(150);
            String n = Protocol.nonce();
            Map<String, Object> m = new TreeMap<>();
            m.put("t", "probe");
            m.put("from", cfg.deviceId);
            m.put("to", peer);
            m.put("n", n);
            m.put("tag", Protocol.tag(cfg.pairKey, "probe\n" + cfg.deviceId + "\n" + peer + "\n" + n));
            byte[] probe = Json.stringify(m).getBytes(StandardCharsets.UTF_8);
            Set<InetSocketAddress> targets = new LinkedHashSet<>();
            for (InetSocketAddress h : hints) targets.add(new InetSocketAddress(h.getAddress(), h.getPort() + 1));
            int dport = cfg.lanPort + 1;
            targets.add(new InetSocketAddress(LanServer.groupAddr(), dport)); // v2：组播
            for (InetAddress b : broadcasts()) targets.add(new InetSocketAddress(b, dport));
            targets.add(new InetSocketAddress(InetAddress.getByName("255.255.255.255"), dport));
            byte[] buf = new byte[2048];
            long nextSend = 0;
            int rounds = 0;
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend && rounds < 6) {
                    for (InetSocketAddress t : targets) {
                        try { ds.send(new DatagramPacket(probe, probe.length, t)); } catch (Exception ignored) { }
                    }
                    rounds++;
                    nextSend = System.currentTimeMillis() + 300;
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    Map<String, Object> r = Json.obj(new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8));
                    Object port = r.get("port");
                    if ("here".equals(r.get("t")) && peer.equals(r.get("id")) && n.equals(r.get("n")) && port instanceof Number
                            && Protocol.tagOk(cfg.pairKey, "here\n" + peer + "\n" + ((Number) port).intValue() + "\n" + n, r.get("tag")))
                        return new InetSocketAddress(p.getAddress(), ((Number) port).intValue());
                } catch (SocketTimeoutException ignored) {
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        // UDP 可能被挡：直接探记下的地址（v2：GET /healthz 回 ok 才算；不是自己人握手时还会被拒）
        for (InetSocketAddress h : hints) if (healthz(h, 700)) return h;
        return null;
    }

    /** 明文 GET /healthz → "ok"（电脑端 v1 / v2 都回）。 */
    static boolean healthz(InetSocketAddress h, int ms) {
        try (Socket s = new Socket()) {
            s.connect(h, ms);
            s.setSoTimeout(ms);
            s.getOutputStream().write(("GET /healthz HTTP/1.1\r\nHost: " + h.getAddress().getHostAddress() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            byte[] buf = s.getInputStream().readNBytes(256);
            String r = new String(buf, StandardCharsets.ISO_8859_1);
            return r.startsWith("HTTP/1.1 200") && r.endsWith("ok");
        } catch (Exception e) {
            return false;
        }
    }

    static List<InetSocketAddress> hints(Config cfg) {
        List<InetSocketAddress> out = new ArrayList<>();
        for (String part : (cfg.lanLast + "," + cfg.lanHosts).split(",")) {
            part = part.trim();
            int c = part.lastIndexOf(':');
            if (c <= 0) continue;
            try {
                InetSocketAddress a = new InetSocketAddress(InetAddress.getByName(part.substring(0, c)), Integer.parseInt(part.substring(c + 1)));
                if (!out.contains(a)) out.add(a);
            } catch (Exception ignored) { }
        }
        return out;
    }

    static List<InetAddress> broadcasts() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 本机局域网 IPv4（写进配对串当提示地址）。 */
    public static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String n = (ni.getDisplayName() + " " + ni.getName()).toLowerCase();
                if (n.contains("vmware") || n.contains("virtualbox") || n.contains("hyper-v") || n.contains("wsl") || n.contains("docker") || n.contains("tailscale")) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }
}
