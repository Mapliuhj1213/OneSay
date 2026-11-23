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
    public static final int DEFAULT_PORT = Flavor.lanPort(); // [preview-1120] 正式版 47820，预览版 47920
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
    /** [rel-1123 合并] 免扫码配对的配对码（只有 PBKDF2 结果，App 从凭据库读进来）；null = 没设码，不收免扫码配对。 */
    public volatile LanPair.Verifier pairCode;
    /** 输错限次（计数存进配置）。 */
    public final LanPair.Lock pairLock;
    /** 电脑名字（邀请里给同一网络的手机看）。 */
    public volatile String pcName = LanPair.hostName();
    /** 最近一次免扫码配对的进展（给界面看）：{at, name, ip, state, text}；state = running / ok / code / locked / fail。 */
    public volatile Map<String, Object> pairLast;
    /** 配对好了（App 接上：发一条电脑通知）。参数：手机名字、手机 id。 */
    public volatile java.util.function.BiConsumer<String, String> onPaired = (n, id) -> { };
    /** 配对过程写进电脑日志（Agent 接上）。 */
    public volatile java.util.function.Consumer<String> log = x -> { };
    final LanPair.Limiter pairLimit = new LanPair.Limiter();
    /** [rel-1123-b] 给「为什么离线」用的最近见闻（都只在内存里）。 */
    final Seen seen = new Seen();
    private volatile String bindError = "";

    public LanServer(Config cfg, Link.Listener listener) {
        this.cfg = cfg;
        this.pairLock = new LanPair.Lock(cfg.pairFails, cfg.pairLockUntil);
        this.listener = listener;
    }

    @Override public String name() { return "局域网"; }
    @Override public boolean isConnected() { return running; }
    public int port() { return ss == null ? -1 : ss.getLocalPort(); }
    /** [rel-1123-b] 现在在线的设备 = 有常连通道（hold）的；一次性连接（按一下按钮 / 手机页刷新）不算在线。 */
    public List<String> clients() {
        List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Conn> e : conns.entrySet()) if (e.getValue().held && !e.getValue().closed) out.add(e.getKey());
        return out;
    }

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
            bindError = "电脑上的局域网端口 " + cfg.lanPort + " 开不了（被别的程序占着，或被防火墙拦了）";
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
                String text = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
                String reply = answerProbe(cfg, text);
                if (reply == null) reply = LanPair.answerPairProbe(cfg.pairInvite, pairCode != null, cfg.lanPort, pcName, text, p.getAddress()); // [rel-1123-b] 没配对的手机找可配对的电脑（[rel-1123 合并] 带电脑名字和设没设码）
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
        // [rel-1123-b] 允许新手机配对时，顺带发一条不带身份的邀请（{"t":"pinv","v":2,"port","name","code"}；[rel-1123 合并] 要设了配对码才发），没配对的手机打开「找附近的电脑」就能看到
        if (cfg.pairInvite && pairCode != null) {
            byte[] inv = LanPair.inviteText(cfg.lanPort, pcName, true).getBytes(StandardCharsets.UTF_8);
            for (InetAddress a : to) {
                try { d.send(new DatagramPacket(inv, inv.length, a, dport)); } catch (Exception ignored) { }
            }
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
            String firstMsg = readMessage(in, c);
            if (firstMsg != null && firstMsg.startsWith("{") && firstMsg.contains("\"pair_req\"")) { // [rel-1123-b] 免扫码配对请求
                pair(c, s, in, firstMsg, tlsFp, raw.getInetAddress());
                return;
            }
            Hello hello = checkHello(firstMsg, tlsFp);
            if (hello.who == null) {
                if (firstMsg != null && firstMsg.contains("\"hello\"")) seen.refused(hello.code, System.currentTimeMillis());
                c.sendText("{\"code\":" + Json.stringify(hello.why) + ",\"type\":\"error\"}");
                c.close(hello.code, hello.why);
                return;
            }
            String who = hello.who;
            c.device = who;
            c.held = hello.hold;
            Conn old = conns.get(who);
            boolean wasOnline = old != null && old.held && !old.closed;
            if (old == null || old.closed || c.held || !old.held) {
                conns.put(who, c);
                if (old != null && old != c) old.close(4000, "replaced");
            } else {
                extra.add(c); // 已有常连：一次性连接不顶掉它
            }
            if (c.held) seen.held(who); else seen.oneShot(who, System.currentTimeMillis());
            if (c.held && !who.equals(cfg.phoneLast)) {
                cfg.phoneLast = who;
                try { cfg.save(); } catch (IOException ignored) { }
            }
            s.setSoTimeout(95_000); // 客户端每 25 秒 ping
            c.sendText("{\"list\":[{\"device\":" + Json.stringify(cfg.deviceId) + ",\"kind\":\"pc\",\"online\":true}],\"type\":\"devices\"}");
            // [rel-1123-b] 只有常连算上线；一次性连接（手机页每 10 秒刷新、按一下按钮）不再每次报「上线 / 离线」
            if (c.held && !wasOnline) listener.onFrame(presence(who, true));
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
                if (c.device != null && conns.remove(c.device, c) && c.held) {
                    seen.dropped(c.device, System.currentTimeMillis());
                    listener.onFrame(presence(c.device, false));
                }
                c.close(1000, "");
            } else close(s);
        }
    }

    // ---- [rel-1123-b] 免扫码配对 ----

    /** [rel-1123 合并] 免扫码配对：固定配对码 + SPAKE2（流程见 {@link LanPair}）。 */
    private void pair(Conn c, Socket s, InputStream in, String first, String tlsFp, InetAddress remote) throws IOException {
        String ip = remote == null ? "?" : remote.getHostAddress();
        if (!cfg.pairInvite) { pairNo(c, "closed"); return; }
        LanPair.Verifier v = pairCode;
        if (v == null) { pairNo(c, "nocode"); return; }
        if (!LanPair.privateAddr(remote)) { pairNo(c, "addr"); return; }
        if (tlsFp == null) { pairNo(c, "tls"); return; }
        Map<String, Object> f;
        try { f = Json.obj(first); } catch (Exception e) { pairNo(c, "bad"); return; }
        if (!"pair_req".equals(f.get("type"))) { pairNo(c, "bad"); return; }
        if (!(f.get("v") instanceof Number) || ((Number) f.get("v")).intValue() != 2) { pairNo(c, "old"); return; }
        String from = String.valueOf(f.get("from"));
        if (!from.matches("[A-Za-z0-9._-]{1,64}") || from.equals(cfg.deviceId)) { pairNo(c, "bad"); return; }
        String name = LanPair.cleanName(f.get("name"));
        long now = System.currentTimeMillis();
        if (pairLock.locked(now)) { pairLocked(c, now); return; }
        String adm = pairLimit.admit(ip, now);
        if (adm != null) { pairNo(c, adm); return; }
        boolean counted = false, done = false;
        try {
            pairLast(name, ip, "running", "手机「" + name + "」正在用配对码配对…");
            Map<String, Object> salt = new TreeMap<>();
            salt.put("type", "pair_salt");
            salt.put("v", 2L);
            salt.put("salt", b64(v.salt));
            salt.put("iter", (long) v.iter);
            salt.put("id", cfg.deviceId);
            salt.put("name", pcName);
            c.sendText(Json.stringify(salt));
            s.setSoTimeout(LanPair.STEP_MS);
            Map<String, Object> x = obj(readMessage(in, c));
            byte[] xa = x == null || !"pair_x".equals(x.get("type")) ? null : unb64(x.get("x"));
            if (xa == null || xa.length != Spake2.ELEM_BYTES) { pairNo(c, "bad"); pairLast(name, ip, "fail", "手机「" + name + "」的配对请求不完整"); return; }
            Spake2.Party me = new Spake2.Party(false, v.w);
            Spake2.Keys k = me.finish(xa, new Spake2.Ctx(from, cfg.deviceId, tlsFp, cfg.tls().fp, v.salt, v.iter));
            if (k == null) { pairNo(c, "bad"); pairLast(name, ip, "fail", "手机「" + name + "」发来的数据不对"); return; }
            pairFail(System.currentTimeMillis()); // 先记一次错：对方拿到了猜一次的机会（成功再清零）
            counted = true;
            Map<String, Object> y = new TreeMap<>();
            y.put("type", "pair_y");
            y.put("y", b64(me.message()));
            c.sendText(Json.stringify(y));
            Map<String, Object> cf = obj(readMessage(in, c));
            byte[] ca = cf == null || !"pair_confirm".equals(cf.get("type")) ? null : unb64(cf.get("c"));
            if (!Spake2.same(ca, k.cA)) {
                long t = System.currentTimeMillis();
                if (pairLock.locked(t)) {
                    pairLocked(c, t);
                    log.accept("手机「" + name + "」（" + ip + "）配对码输错太多次，免扫码配对锁到 " + java.time.Instant.ofEpochMilli(pairLock.until()));
                } else {
                    try { c.sendText("{\"left\":" + pairLock.left() + ",\"type\":\"pair_no\",\"why\":\"code\"}"); } catch (IOException ignored) { }
                    c.close(4005, "code");
                    pairLast(name, ip, "code", "手机「" + name + "」输的配对码不对（还能试 " + pairLock.left() + " 次）");
                    log.accept("手机「" + name + "」（" + ip + "）输的配对码不对");
                }
                return;
            }
            String before = cfg.pinOf(from);
            cfg.pin(from, tlsFp); // 记住这台手机的证书：之后它的签名 hello 必须是这张证书
            pairLock.ok();
            persistLock();
            done = true;
            Map<String, Object> ok = new TreeMap<>();
            ok.put("type", "pair_ok");
            ok.put("c", b64(k.cB));
            ok.put("pairing", cfg.pairingString());
            try {
                c.sendText(Json.stringify(ok));
            } catch (IOException e) {
                cfg.pin(from, before); // 手机已经走了：不留这张证书
                try { cfg.save(); } catch (IOException ignored) { }
                throw e;
            }
            seen.paired(from, System.currentTimeMillis());
            pairLast(name, ip, "ok", "手机「" + name + "」配对好了");
            log.accept("手机「" + name + "」（" + from + "，" + ip + "）用配对码配对好了");
            try { onPaired.accept(name, from); } catch (RuntimeException ignored) { }
            c.close(1000, "paired");
        } catch (java.net.SocketTimeoutException e) {
            pairNo(c, "bad");
            if (!done) pairLast(name, ip, counted ? "code" : "fail", "手机「" + name + "」没把配对做完（中途停了），" + (counted ? "按输错一次算" : "没配上"));
        } finally {
            pairLimit.release();
        }
    }

    private void pairFail(long now) {
        pairLock.fail(now);
        persistLock();
    }

    private void persistLock() {
        cfg.pairFails = pairLock.fails();
        cfg.pairLockUntil = pairLock.until();
        try { cfg.save(); } catch (IOException ignored) { }
    }

    /** 在电脑上解锁（界面的「解锁」）：清零。 */
    public void pairUnlock() {
        pairLock.reset();
        persistLock();
    }

    private void pairLocked(Conn c, long now) {
        long min = Math.max(1, (pairLock.until() - now + 59_999) / 60_000);
        try { c.sendText("{\"type\":\"pair_no\",\"wait_min\":" + min + ",\"why\":\"locked\"}"); } catch (IOException ignored) { }
        c.close(4005, "locked");
        Map<String, Object> l = pairLast;
        pairLast("", "", "locked", "配对码试错太多次，免扫码配对暂停到 " + java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(pairLock.until()), java.time.ZoneId.systemDefault()).withNano(0).withSecond(0)
                + (l != null && l.get("name") != null && !"".equals(l.get("name")) ? "（最近一次是手机「" + l.get("name") + "」）" : ""));
    }

    private void pairLast(String name, String ip, String state, String text) {
        Map<String, Object> m = new TreeMap<>();
        m.put("at", System.currentTimeMillis());
        m.put("name", name);
        m.put("ip", ip);
        m.put("state", state);
        m.put("text", text);
        pairLast = m;
    }

    private static Map<String, Object> obj(String t) {
        try { return t == null ? null : Json.obj(t); } catch (Exception e) { return null; }
    }

    private static String b64(byte[] b) { return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    private static byte[] unb64(Object o) {
        if (!(o instanceof String) || ((String) o).length() > 1024) return null;
        try { return java.util.Base64.getUrlDecoder().decode((String) o); } catch (IllegalArgumentException e) { return null; }
    }

    private static void pairNo(Conn c, String why) {
        try { c.sendText("{\"type\":\"pair_no\",\"why\":" + Json.stringify(why) + "}"); } catch (IOException ignored) { }
        c.close(4005, why);
    }

    /** [rel-1123-b] 「为什么手机不在线」要用的见闻：哪台设备最近只用一次性连接来过、哪台常连刚断、最近有没有被拒的握手。 */
    static final class Seen {
        final Map<String, Long> oneShot = new ConcurrentHashMap<>(), dropped = new ConcurrentHashMap<>(), paired = new ConcurrentHashMap<>();
        volatile long refusedAt;
        volatile int refusedCode;

        void oneShot(String who, long now) { if (oneShot.size() < 64) oneShot.put(who, now); else { oneShot.clear(); oneShot.put(who, now); } }
        void held(String who) { oneShot.remove(who); dropped.remove(who); }
        void dropped(String who, long now) { dropped.put(who, now); }
        void paired(String who, long now) { paired.put(who, now); }
        void refused(int code, long now) { refusedAt = now; refusedCode = code; }
    }

    /** 现在为什么没有手机在线（有在线的返回 null）。phoneLast = 最近常连过的手机。 */
    /** ★ 1123-dui B1：这台电脑配对过手机没有（常连过 / 记着证书 / 本次运行配对过）。 */
    public boolean pairedBefore(String phoneLast) {
        return OfflineWhy.pairedBefore(phoneLast, cfg.pins, seen);
    }

    public OfflineWhy.Why offlineWhy(String phoneLast, long now) {
        if (!clients().isEmpty()) return null;
        return OfflineWhy.of(running, bindError, LanClient.localAddresses().isEmpty(), phoneLast, OfflineWhy.pairedBefore(phoneLast, cfg.pins, seen), seen, now);   // ★ 1123-dui B1
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
