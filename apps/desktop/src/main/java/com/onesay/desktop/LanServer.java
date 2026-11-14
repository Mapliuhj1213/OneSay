package com.onesay.desktop;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * 局域网直连（开源版默认）：电脑在家用路由器里开一个小 WebSocket 服务（默认 TCP 47820），
 * 同时在 UDP 47821 应答配对设备的发现探测。不需要中转、账号或公网。
 *
 * 握手：客户端连上后 10 秒内必须发 {"type":"hello","from":..,"id":..,"body":<签名的 {kind:"hello"}>}，
 * 用配对密钥验过签才算自己人；否则断开。之后的帧与中转格式相同，指令照样逐条验签。
 * 发现：只回应带正确标签的探测，没配对的人在局域网里扫不到这台电脑。
 *
 * <p>协议 v2（0.5.0，端口不变）：
 * <ul>
 *   <li>同一个 TCP 端口上嗅探首字节：0x16 = TLS（{@link Tls}，双向证书），否则按 v1 明文 WebSocket 处理（旧手机照常用）；</li>
 *   <li>TLS 里的 hello 带 cfp（对方证书指纹，在签名里），与 TLS 实际看到的证书一致才收，并按设备 id 记住；之后指纹不一致拒（4003），
 *       用 TLS 连过的设备再用明文来也拒（4002，防降级）；</li>
 *   <li>UDP 端口 +1 同时加入组播组 {@link #GROUP}（LocalSend 的做法：224.0.0.0/24，部分安卓只收这一段），
 *       收组播 / 广播 / 单播三种探测；开机和每分钟、以及要给手机下令而手机不在时，往组播 + 广播发一条带标签的 announce，
 *       常连的手机收到就立刻连回来；</li>
 *   <li>hello 带 hold=true 的连接是手机的常连通道：电脑往手机下的指令（{@link Agent#callPeer}）从这里发；
 *       同一台手机的一次性连接（按钮下令）不会顶掉常连，回复按 re 送回发起的那条连接。</li>
 * </ul>
 */
public final class LanServer implements Link {
    public static final int DEFAULT_PORT = 47820;
    /** 发现用的组播组（端口 = 局域网端口 + 1）。 */
    public static final String GROUP = "224.0.0.167";

    private final Config cfg;
    private final Link.Listener listener;
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();   // 每台设备的主连接（常连优先）
    private final Set<Conn> extra = ConcurrentHashMap.newKeySet();       // 主连接之外的一次性连接
    private final Map<String, Conn> routes = Collections.synchronizedMap(new LinkedHashMap<String, Conn>(64, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Conn> e) { return size() > 2000; }
    });
    private volatile SSLContext tlsCtx;
    private final Protocol.ReplayGuard helloGuard = new Protocol.ReplayGuard();
    private volatile boolean running;
    private ServerSocket ss;
    private DatagramSocket ds;

    public LanServer(Config cfg, Link.Listener listener) {
        this.cfg = cfg;
        this.listener = listener;
    }

    @Override public String name() { return "局域网"; }
    @Override public boolean isConnected() { return running; }
    public int port() { return ss == null ? -1 : ss.getLocalPort(); }
    public List<String> clients() { return List.copyOf(conns.keySet()); }

    @Override
    public void start() {
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(cfg.lanPort), 16);
            MulticastSocket ms = new MulticastSocket(null);
            ms.setReuseAddress(true);
            ms.setBroadcast(true);
            ms.bind(new InetSocketAddress(cfg.lanPort + 1));
            ds = ms;
            joinGroup(ms);
        } catch (IOException e) {
            close(ss);
            if (ds != null) ds.close();
            listener.onState(false, "局域网端口 " + cfg.lanPort + " 开不了：" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()));
            return;
        }
        try { tlsCtx = cfg.tls().serverContext(); } catch (RuntimeException e) { tlsCtx = null; listener.onState(false, "证书出错，只能明文：" + e.getMessage()); }
        running = true;
        daemon("onesay-lan-accept", this::acceptLoop);
        daemon("onesay-lan-discovery", this::discoveryLoop);
        daemon("onesay-lan-announce", () -> {
            while (running) {
                announce();
                try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
            }
        });
        listener.onState(true, "局域网端口 " + cfg.lanPort);
    }

    @Override
    public void stop() {
        running = false;
        close(ss);
        if (ds != null) ds.close();
        for (Conn c : conns.values()) c.close(1001, "bye");
        for (Conn c : extra) c.close(1001, "bye");
        conns.clear();
        extra.clear();
    }

    /** Agent 的回复：{type:"msg", to, id, body} → 送到对应连接，并补上 from。 */
    @Override
    public CompletableFuture<?> send(String text) {
        try {
            Map<String, Object> env = Json.obj(text);
            Conn c = null;
            if (env.get("body") instanceof Map) {
                Object re = ((Map<?, ?>) env.get("body")).get("re");
                if (re != null) c = routes.remove(String.valueOf(re)); // 回复：送回发起的那条连接
            }
            if (c == null || c.closed) c = conns.get(String.valueOf(env.get("to")));
            if (c == null) return CompletableFuture.failedFuture(new IllegalStateException("offline"));
            Map<String, Object> out = new TreeMap<>();
            out.put("type", "msg");
            out.put("from", cfg.deviceId);
            out.put("id", env.get("id"));
            out.put("body", env.get("body"));
            c.sendText(Json.stringify(out));
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = ss.accept();
                daemon("onesay-lan-conn", () -> handle(s));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    // ---- 发现 ----

    private void discoveryLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ds.receive(p);
                String reply = answerProbe(cfg, new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8));
                if (reply != null) {
                    byte[] r = reply.getBytes(StandardCharsets.UTF_8);
                    ds.send(new DatagramPacket(r, r.length, p.getSocketAddress()));
                }
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private static void joinGroup(MulticastSocket ms) {
        InetSocketAddress g = new InetSocketAddress(groupAddr(), 0);
        boolean any = false;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                    ms.joinGroup(g, ni);
                    any = true;
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        if (!any) try { ms.joinGroup(g, null); } catch (Exception ignored) { }
    }

    static InetAddress groupAddr() {
        try { return InetAddress.getByName(GROUP); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** announce：{"t":"ann","v":2,"port","n","tag"}，标签 = tag(key, "ann\n" + 本机id + "\n" + port + "\n" + n)。不带本机 id（没配对的人看不出是谁）。 */
    static String announceText(Config cfg) {
        String n = Protocol.nonce();
        Map<String, Object> m = new TreeMap<>();
        m.put("t", "ann");
        m.put("v", 2L);
        m.put("port", (long) cfg.lanPort);
        m.put("n", n);
        m.put("tag", Protocol.tag(cfg.pairKey, "ann\n" + cfg.deviceId + "\n" + cfg.lanPort + "\n" + n));
        return Json.stringify(m);
    }

    /** 往组播组 + 各网卡广播发一条 announce（常连的手机收到就立刻连回来）。 */
    public void announce() {
        DatagramSocket d = ds;
        if (!running || d == null || cfg.pairKey.length < 32) return;
        byte[] b = announceText(cfg).getBytes(StandardCharsets.UTF_8);
        int dport = cfg.lanPort + 1;
        List<InetAddress> to = new java.util.ArrayList<>();
        to.add(groupAddr());
        to.addAll(LanClient.broadcasts());
        for (InetAddress a : to) {
            try { d.send(new DatagramPacket(b, b.length, a, dport)); } catch (Exception ignored) { }
        }
    }

    /** 探测 {"t":"probe","from","to","n","tag"} → 应答 {"t":"here","id","port","n","tag"}（v2 另带 "v":2、"tls":true 提示，不在标签里）；不是自己人返回 null。 */
    static String answerProbe(Config cfg, String text) {
        Map<String, Object> m;
        try { m = Json.obj(text); } catch (Exception e) { return null; }
        if (!"probe".equals(m.get("t")) || !cfg.deviceId.equals(m.get("to"))) return null;
        String from = String.valueOf(m.get("from")), n = String.valueOf(m.get("n"));
        if (n.length() < 8 || n.length() > 64) return null;
        if (!Protocol.tagOk(cfg.pairKey, "probe\n" + from + "\n" + cfg.deviceId + "\n" + n, m.get("tag"))) return null;
        if (!cfg.peerAllowed(from)) return null;
        Map<String, Object> r = new TreeMap<>();
        r.put("t", "here");
        r.put("id", cfg.deviceId);
        r.put("port", (long) cfg.lanPort);
        r.put("n", n);
        r.put("tag", Protocol.tag(cfg.pairKey, "here\n" + cfg.deviceId + "\n" + cfg.lanPort + "\n" + n));
        r.put("v", 2L);
        r.put("tls", true);
        return Json.stringify(r);
    }

    // ---- 连接 ----

    /** 握手结果：通过 = who 非空；否则 code/why 说明拒绝原因。 */
    static final class Hello {
        String who;
        boolean hold;
        int code = 4001;
        String why = "auth";
    }

    private void handle(Socket raw) {
        Conn c = null;
        Socket s = raw;
        try {
            raw.setTcpNoDelay(true);
            raw.setSoTimeout(10_000);
            // 嗅探首字节：0x16 = TLS ClientHello；其他按 v1 明文 HTTP
            InputStream rin = raw.getInputStream();
            int first = rin.read();
            if (first < 0) { close(raw); return; }
            String tlsFp = null;
            InputStream in;
            OutputStream out;
            if (first == 0x16) {
                SSLContext ctx = tlsCtx;
                if (ctx == null) { close(raw); return; }
                SSLSocket ss2 = (SSLSocket) ctx.getSocketFactory().createSocket(raw, new java.io.ByteArrayInputStream(new byte[]{0x16}), true);
                ss2.setUseClientMode(false);
                ss2.setNeedClientAuth(true);
                ss2.setEnabledProtocols(protocols(ss2.getSupportedProtocols()));
                ss2.startHandshake();
                java.security.cert.Certificate[] pc = ss2.getSession().getPeerCertificates();
                try { tlsFp = Tls.fingerprint(pc[0].getEncoded()); } catch (java.security.cert.CertificateEncodingException e) { throw new IOException(e); }
                s = ss2;
                in = new BufferedInputStream(ss2.getInputStream());
                out = new BufferedOutputStream(ss2.getOutputStream());
            } else {
                in = new BufferedInputStream(new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(new byte[]{(byte) first}), rin));
                out = new BufferedOutputStream(raw.getOutputStream());
            }
            Map<String, String> h = Ws.readHttpHead(in);
            String line = h.get("_line");
            String key = h.get("sec-websocket-key");
            if (!line.startsWith("GET /v1/ws") || key == null || !"websocket".equalsIgnoreCase(h.getOrDefault("upgrade", ""))) {
                if (line.startsWith("GET /healthz")) out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));
                else out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                s.close();
                return;
            }
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                    + Ws.acceptKey(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            c = new Conn(s, out);
            c.tls = tlsFp != null;
            Hello hello = checkHello(readMessage(in, c), tlsFp);
            if (hello.who == null) {
                c.sendText("{\"code\":" + Json.stringify(hello.why) + ",\"type\":\"error\"}");
                c.close(hello.code, hello.why);
                return;
            }
            String who = hello.who;
            c.device = who;
            c.held = hello.hold;
            Conn old = conns.get(who);
            boolean primary;
            if (old == null || old.closed || c.held || !old.held) {
                conns.put(who, c);
                primary = true;
                if (old != null && old != c) old.close(4000, "replaced");
            } else {
                extra.add(c); // 已有常连：一次性连接不顶掉它
                primary = false;
            }
            if (c.held && !who.equals(cfg.phoneLast)) {
                cfg.phoneLast = who;
                try { cfg.save(); } catch (IOException ignored) { }
            }
            s.setSoTimeout(95_000); // 客户端每 25 秒 ping
            c.sendText("{\"list\":[{\"device\":" + Json.stringify(cfg.deviceId) + ",\"kind\":\"pc\",\"online\":true}],\"type\":\"devices\"}");
            if (primary) listener.onFrame(presence(who, true));
            while (running) {
                String msg = readMessage(in, c);
                if (msg == null) break;
                if (msg.equals("ping")) { c.sendText("pong"); continue; }
                Map<String, Object> f;
                try { f = Json.obj(msg); } catch (Exception e) { continue; }
                if (!"msg".equals(f.get("type"))) continue;
                if (!cfg.deviceId.equals(f.get("to"))) {
                    Map<String, Object> err = new TreeMap<>();
                    err.put("type", "error");
                    err.put("code", "offline");
                    err.put("id", f.get("id"));
                    c.sendText(Json.stringify(err));
                    continue;
                }
                f.remove("to");
                f.put("from", who); // 来源以握手验过的身份为准
                if (f.get("id") != null) routes.put(String.valueOf(f.get("id")), c);
                listener.onFrame(f);
            }
        } catch (IOException e) {
            // 断开（含 TLS 握手失败：指纹 / 证书不对）
        } finally {
            if (c != null) {
                extra.remove(c);
                if (c.device != null && conns.remove(c.device, c)) listener.onFrame(presence(c.device, false));
                c.close(1000, "");
            } else close(s);
        }
    }

    static String[] protocols(String[] supported) {
        List<String> out = new java.util.ArrayList<>();
        for (String p : supported) if (p.equals("TLSv1.3") || p.equals("TLSv1.2")) out.add(p);
        return out.toArray(new String[0]);
    }

    /** 当前主连接是不是常连通道（电脑往手机下令走它）。 */
    public boolean held(String device) {
        Conn c = conns.get(device);
        return c != null && c.held && !c.closed;
    }

    /** 主连接是不是 TLS。 */
    public boolean tls(String device) {
        Conn c = conns.get(device);
        return c != null && c.tls;
    }

    Hello checkHello(String first, String tlsFp) {
        Hello r = new Hello();
        if (first == null) return r;
        try {
            Map<String, Object> f = Json.obj(first);
            if (!"hello".equals(f.get("type")) || !(f.get("body") instanceof Map)) return r;
            String from = String.valueOf(f.get("from")), id = String.valueOf(f.get("id"));
            if (!from.matches("[A-Za-z0-9._-]{1,64}") || from.equals(cfg.deviceId)) return r;
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) f.get("body");
            if (!"hello".equals(body.get("kind"))) return r;
            if (Protocol.verify(cfg.pairKey, id, from, cfg.deviceId, body, System.currentTimeMillis(), helloGuard) != Protocol.Check.OK) return r;
            if (!cfg.peerAllowed(from)) return r;
            String pinned = cfg.pinOf(from);
            if (tlsFp == null) {
                // v1 明文：用 TLS 连过的设备不许退回明文；关了明文兼容也拒
                if (pinned != null || !cfg.lanPlain) { r.code = 4002; r.why = "tls_required"; return r; }
            } else {
                Object cfp = body.get("cfp");
                if (!(cfp instanceof String) || !Tls.fpEquals((String) cfp, tlsFp)) return r; // 签名里的指纹必须就是 TLS 上这张证书
                if (pinned == null) {
                    cfg.pin(from, tlsFp);
                    try { cfg.save(); } catch (IOException ignored) { }
                } else if (!Tls.fpEquals(pinned, tlsFp)) {
                    r.code = 4003; r.why = "pin"; return r;
                }
            }
            r.who = from;
            r.hold = Boolean.TRUE.equals(body.get("hold"));
            return r;
        } catch (Exception e) {
            return r;
        }
    }

    /** 读一条完整消息（拼接分片、应答 ping）。对方关闭返回 null。 */
    private static String readMessage(InputStream in, Conn c) throws IOException {
        java.io.ByteArrayOutputStream acc = null;
        while (true) {
            Ws.Frame fr = Ws.read(in, true);
            switch (fr.op) {
                case Ws.OP_PING: c.sendRaw(Ws.OP_PONG, fr.payload); continue;
                case Ws.OP_PONG: continue;
                case Ws.OP_CLOSE: return null;
                case Ws.OP_TEXT:
                case Ws.OP_BIN:
                    if (fr.fin) return new String(fr.payload, StandardCharsets.UTF_8);
                    acc = new java.io.ByteArrayOutputStream();
                    acc.write(fr.payload);
                    continue;
                case Ws.OP_CONT:
                    if (acc == null) throw new IOException("stray continuation");
                    acc.write(fr.payload);
                    if (acc.size() > Ws.MAX_MESSAGE) throw new IOException("message too large");
                    if (fr.fin) return acc.toString(StandardCharsets.UTF_8);
                    continue;
                default: throw new IOException("bad opcode");
            }
        }
    }

    private static Map<String, Object> presence(String device, boolean online) {
        Map<String, Object> m = new TreeMap<>();
        m.put("type", "presence");
        m.put("device", device);
        m.put("online", online);
        return m;
    }

    private static final class Conn {
        final Socket s;
        final OutputStream out;
        volatile String device;
        volatile boolean held, tls, closed;

        Conn(Socket s, OutputStream out) { this.s = s; this.out = out; }

        synchronized void sendRaw(int op, byte[] p) throws IOException { Ws.write(out, op, p); }

        void sendText(String t) throws IOException { sendRaw(Ws.OP_TEXT, t.getBytes(StandardCharsets.UTF_8)); }

        void close(int code, String reason) {
            try {
                byte[] r = reason.getBytes(StandardCharsets.UTF_8);
                byte[] p = new byte[2 + r.length];
                p[0] = (byte) (code >>> 8);
                p[1] = (byte) code;
                System.arraycopy(r, 0, p, 2, r.length);
                sendRaw(Ws.OP_CLOSE, p);
            } catch (IOException ignored) { }
            closed = true;
            LanServer.close(s);
        }
    }

    static void close(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (IOException ignored) { }
    }

    static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }


}
