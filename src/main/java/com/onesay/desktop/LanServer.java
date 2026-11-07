package com.onesay.desktop;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 局域网直连（开源版默认）：电脑在家用路由器里开一个小 WebSocket 服务（默认 TCP 47820），
 * 同时在 UDP 47821 应答配对设备的发现探测。不需要中转、账号或公网。
 *
 * 握手：客户端连上后 10 秒内必须发 {"type":"hello","from":..,"id":..,"body":<签名的 {kind:"hello"}>}，
 * 用配对密钥验过签才算自己人；否则断开。之后的帧与中转格式相同，指令照样逐条验签。
 * 发现：只回应带正确标签的探测，没配对的人在局域网里扫不到这台电脑。
 */
public final class LanServer implements Link {
    public static final int DEFAULT_PORT = 47820;

    private final Config cfg;
    private final Link.Listener listener;
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();
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
            ds = new DatagramSocket(null);
            ds.setReuseAddress(true);
            ds.setBroadcast(true);
            ds.bind(new InetSocketAddress(cfg.lanPort + 1));
        } catch (IOException e) {
            close(ss);
            if (ds != null) ds.close();
            listener.onState(false, "局域网端口 " + cfg.lanPort + " 开不了：" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()));
            return;
        }
        running = true;
        daemon("onesay-lan-accept", this::acceptLoop);
        daemon("onesay-lan-discovery", this::discoveryLoop);
        listener.onState(true, "局域网端口 " + cfg.lanPort);
    }

    @Override
    public void stop() {
        running = false;
        close(ss);
        if (ds != null) ds.close();
        for (Conn c : conns.values()) c.close(1001, "bye");
        conns.clear();
    }

    /** Agent 的回复：{type:"msg", to, id, body} → 送到对应连接，并补上 from。 */
    @Override
    public CompletableFuture<?> send(String text) {
        try {
            Map<String, Object> env = Json.obj(text);
            Conn c = conns.get(String.valueOf(env.get("to")));
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

    /** 探测 {"t":"probe","from","to","n","tag"} → 应答 {"t":"here","id","port","n","tag"}；不是自己人返回 null。 */
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
        return Json.stringify(r);
    }

    // ---- 连接 ----

    private void handle(Socket s) {
        Conn c = null;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
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
            String first = readMessage(in, c);
            String who = checkHello(first);
            if (who == null) {
                c.sendText("{\"code\":\"auth\",\"type\":\"error\"}");
                c.close(4001, "auth");
                return;
            }
            c.device = who;
            Conn old = conns.put(who, c);
            if (old != null) old.close(4000, "replaced");
            s.setSoTimeout(95_000); // 客户端每 25 秒 ping
            c.sendText("{\"list\":[{\"device\":" + Json.stringify(cfg.deviceId) + ",\"kind\":\"pc\",\"online\":true}],\"type\":\"devices\"}");
            listener.onFrame(presence(who, true));
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
                listener.onFrame(f);
            }
        } catch (IOException e) {
            // 断开
        } finally {
            if (c != null) {
                if (c.device != null && conns.remove(c.device, c)) listener.onFrame(presence(c.device, false));
                c.close(1000, "");
            } else close(s);
        }
    }

    private String checkHello(String first) {
        if (first == null) return null;
        try {
            Map<String, Object> f = Json.obj(first);
            if (!"hello".equals(f.get("type")) || !(f.get("body") instanceof Map)) return null;
            String from = String.valueOf(f.get("from")), id = String.valueOf(f.get("id"));
            if (!from.matches("[A-Za-z0-9._-]{1,64}") || from.equals(cfg.deviceId)) return null;
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) f.get("body");
            if (!"hello".equals(body.get("kind"))) return null;
            if (Protocol.verify(cfg.pairKey, id, from, cfg.deviceId, body, System.currentTimeMillis(), helloGuard) != Protocol.Check.OK) return null;
            return cfg.peerAllowed(from) ? from : null;
        } catch (Exception e) {
            return null;
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
