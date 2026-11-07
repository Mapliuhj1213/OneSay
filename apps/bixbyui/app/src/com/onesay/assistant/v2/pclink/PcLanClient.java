package com.onesay.assistant.v2.pclink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * [pclink] 局域网下令端（手机 → 电脑），照电脑端 {@code LanClient} / {@code Cli}（0.4.1）：
 * <ol>
 *   <li>{@link #locate}：UDP 探测 {@code {"t":"probe","from","to","n","tag"}} 发往「配对时记下的地址:端口+1」+ 各网卡广播 + 255.255.255.255:端口+1，
 *       只认带正确标签的 {@code here} 应答；探测不到再直接 TCP 试连记下的地址（路由器挡广播时）；</li>
 *   <li>{@link Session#open}：WebSocket {@code /v1/ws?device=<本机>}，首帧发签名的 hello（from = 本机，to = 电脑），
 *       等电脑回 {@code devices}（密钥不对回 {@code error auth} 并以 4001 关闭）；</li>
 *   <li>{@link Session#request}：发签名指令，等 {@code re} 对得上的结果，按 from = 电脑、to = 本机验签 + 时间窗 + nonce。</li>
 * </ol>
 * 找不到 = 离线，不排队。每次用完即关，不常驻连接（不耗电）。纯 Java，JVM 可测（互通测试直接连电脑端 jar）。
 */
public final class PcLanClient {

    private PcLanClient() {}

    /** 一次下令需要的身份信息（密钥只在内存里）。 */
    public static final class Peer {
        public final String me;
        public final String peer;
        final byte[] key;
        public final String lanHosts;
        public final int lanPort;
        /** 上次连上的地址（ip:port），优先试。 */
        public final String lanLast;

        public Peer(String me, String peer, byte[] key, String lanHosts, int lanPort, String lanLast) {
            this.me = me;
            this.peer = peer;
            this.key = key;
            this.lanHosts = lanHosts == null ? "" : lanHosts;
            this.lanPort = lanPort;
            this.lanLast = lanLast == null ? "" : lanLast;
        }
    }

    /** 一条指令的结果。 */
    public static final class Result {
        public final boolean ok;
        public final String text;
        public final Map<String, Object> data;
        /** 失败类别：""（电脑执行了、ok 看结果）/ offline / auth / timeout / badsig / error。 */
        public final String fail;

        Result(boolean ok, String text, Map<String, Object> data, String fail) {
            this.ok = ok;
            this.text = text == null ? "" : text;
            this.data = data == null ? new TreeMap<String, Object>() : data;
            this.fail = fail == null ? "" : fail;
        }

        static Result failure(String fail, String text) {
            return new Result(false, text, null, fail);
        }

        public boolean reached() {
            return fail.length() == 0;
        }

        @Override public String toString() {
            return (ok ? "ok" : "fail" + (fail.length() > 0 ? "(" + fail + ")" : "")) + " " + text;
        }
    }

    // ================================================================== 发现

    /** 找电脑：返回它的 WebSocket 地址，找不到返回 null。extraBroadcasts 可为 null（Android 侧可补 Wi-Fi 的广播地址）。 */
    public static InetSocketAddress locate(Peer p, long budgetMs, List<InetAddress> extraBroadcasts) {
        long end = System.currentTimeMillis() + budgetMs;
        List<InetSocketAddress> hints = hints(p);
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            ds.setBroadcast(true);
            ds.setSoTimeout(150);
            String n = PcProtocol.nonce();
            byte[] probe = probe(p, n).getBytes(PcProtocol.UTF8);
            Set<InetSocketAddress> targets = new LinkedHashSet<InetSocketAddress>();
            for (InetSocketAddress h : hints) targets.add(new InetSocketAddress(h.getAddress(), h.getPort() + 1));
            int dport = p.lanPort + 1;
            List<InetAddress> bc = broadcasts();
            if (extraBroadcasts != null) bc.addAll(extraBroadcasts);
            for (InetAddress b : bc) targets.add(new InetSocketAddress(b, dport));
            try {
                targets.add(new InetSocketAddress(InetAddress.getByName("255.255.255.255"), dport));
            } catch (Exception ignored) {
            }
            byte[] buf = new byte[2048];
            long nextSend = 0;
            int rounds = 0;
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend && rounds < 6) {
                    for (InetSocketAddress t : targets) {
                        try {
                            ds.send(new DatagramPacket(probe, probe.length, t));
                        } catch (Exception ignored) {
                            // 某个目标发不出去（无路由）不影响别的
                        }
                    }
                    rounds++;
                    nextSend = System.currentTimeMillis() + 300;
                }
                try {
                    DatagramPacket pk = new DatagramPacket(buf, buf.length);
                    ds.receive(pk);
                    int port = answerPort(p, n, new String(pk.getData(), pk.getOffset(), pk.getLength(), PcProtocol.UTF8));
                    if (port > 0) return new InetSocketAddress(pk.getAddress(), port);
                } catch (SocketTimeoutException ignored) {
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            // 没网 / 建不了 UDP：下面直接试连
        } finally {
            if (ds != null) ds.close();
        }
        for (InetSocketAddress h : hints) {
            Socket s = new Socket();
            try {
                s.connect(h, 700);
                return h;
            } catch (Exception ignored) {
            } finally {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
        return null;
    }

    static String probe(Peer p, String n) {
        Map<String, Object> m = new TreeMap<String, Object>();
        m.put("t", "probe");
        m.put("from", p.me);
        m.put("to", p.peer);
        m.put("n", n);
        m.put("tag", PcProtocol.tag(p.key, "probe\n" + p.me + "\n" + p.peer + "\n" + n));
        return PcJson.stringify(m);
    }

    /** here 应答验过返回端口，否则 -1。 */
    static int answerPort(Peer p, String n, String text) {
        try {
            Map<String, Object> r = PcJson.obj(text);
            Object port = r.get("port");
            if ("here".equals(r.get("t")) && p.peer.equals(r.get("id")) && n.equals(r.get("n")) && port instanceof Number
                    && PcProtocol.tagOk(p.key, "here\n" + p.peer + "\n" + ((Number) port).intValue() + "\n" + n, r.get("tag"))) {
                int v = ((Number) port).intValue();
                return v > 0 && v < 65536 ? v : -1;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    static List<InetSocketAddress> hints(Peer p) {
        List<InetSocketAddress> out = new ArrayList<InetSocketAddress>();
        for (String part : (p.lanLast + "," + p.lanHosts).split(",")) {
            part = part.trim();
            int c = part.lastIndexOf(':');
            if (c <= 0) continue;
            try {
                InetSocketAddress a = new InetSocketAddress(InetAddress.getByName(part.substring(0, c)), Integer.parseInt(part.substring(c + 1)));
                if (!out.contains(a)) out.add(a);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    static List<InetAddress> broadcasts() {
        List<InetAddress> out = new ArrayList<InetAddress>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ================================================================== 会话

    public static final class Session {
        private final Peer p;
        private final PcWs ws;
        private final PcProtocol.ReplayGuard guard = new PcProtocol.ReplayGuard();
        public final InetSocketAddress addr;

        private Session(Peer p, PcWs ws, InetSocketAddress addr) {
            this.p = p;
            this.ws = ws;
            this.addr = addr;
        }

        /** 连上 + hello。密钥不对抛 {@link AuthException}；其他连不上抛 IOException。 */
        public static Session open(Peer p, InetSocketAddress a, int timeoutMs) throws IOException {
            PcWs ws = PcWs.connect(a, p.me, Math.min(timeoutMs, 3000), timeoutMs);
            boolean ok = false;
            try {
                String id = PcProtocol.newId();
                Map<String, Object> hello = new TreeMap<String, Object>();
                hello.put("type", "hello");
                hello.put("from", p.me);
                hello.put("id", id);
                hello.put("body", PcProtocol.hello(p.key, id, p.me, p.peer, System.currentTimeMillis()));
                ws.sendText(PcJson.stringify(hello));
                long end = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < end) {
                    String m = ws.readText();
                    if (m == null) {
                        if (ws.closeCode == 4001) throw new AuthException();
                        throw new IOException("closed " + ws.closeCode);
                    }
                    Map<String, Object> f;
                    try {
                        f = PcJson.obj(m);
                    } catch (Exception e) {
                        continue;
                    }
                    Object type = f.get("type");
                    if ("devices".equals(type)) {
                        ok = true;
                        return new Session(p, ws, a);
                    }
                    if ("error".equals(type) && "auth".equals(f.get("code"))) throw new AuthException();
                }
                throw new SocketTimeoutException("hello");
            } finally {
                if (!ok) ws.close();
            }
        }

        /** 发一条指令并等结果（同一会话可多次调用，串行）。 */
        @SuppressWarnings("unchecked")
        public synchronized Result request(String cmd, Map<String, Object> args, int timeoutMs) {
            String id = PcProtocol.newId();
            try {
                Map<String, Object> env = new TreeMap<String, Object>();
                env.put("type", "msg");
                env.put("to", p.peer);
                env.put("id", id);
                env.put("body", PcProtocol.command(p.key, id, p.me, p.peer, cmd, args, System.currentTimeMillis()));
                ws.sendText(PcJson.stringify(env));
                long end = System.currentTimeMillis() + timeoutMs;
                while (true) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) return Result.failure("timeout", "电脑没有回应（超时）");
                    ws.setReadTimeout((int) Math.max(50, left));
                    String m;
                    try {
                        m = ws.readText();
                    } catch (SocketTimeoutException te) {
                        return Result.failure("timeout", "电脑没有回应（超时）");
                    }
                    if (m == null) return Result.failure("offline", "连接断开");
                    if (m.equals("pong")) continue;
                    Map<String, Object> f;
                    try {
                        f = PcJson.obj(m);
                    } catch (Exception e) {
                        continue;
                    }
                    String type = String.valueOf(f.get("type"));
                    if (type.equals("error") && id.equals(f.get("id"))) {
                        return Result.failure("offline", "offline".equals(f.get("code")) ? "电脑离线" : "通道报错：" + f.get("code"));
                    }
                    if (!type.equals("msg") || !(f.get("body") instanceof Map) || !p.peer.equals(f.get("from"))) continue;
                    Map<String, Object> b = (Map<String, Object>) f.get("body");
                    if (!id.equals(b.get("re"))) continue;
                    PcProtocol.Check c = PcProtocol.verify(p.key, String.valueOf(f.get("id")), p.peer, p.me, b, System.currentTimeMillis(), guard);
                    if (c != PcProtocol.Check.OK) {
                        Object t = b.get("text");
                        return Result.failure("badsig", "电脑的回复没通过校验（" + c + "）" + (t == null ? "" : "：" + t));
                    }
                    Map<String, Object> data = b.get("data") instanceof Map ? (Map<String, Object>) b.get("data") : null;
                    return new Result(Boolean.TRUE.equals(b.get("ok")), b.get("text") == null ? "" : String.valueOf(b.get("text")), data, "");
                }
            } catch (IOException e) {
                return Result.failure("offline", "连接断开：" + e.getClass().getSimpleName());
            }
        }

        public void close() {
            ws.close();
        }
    }

    public static final class AuthException extends IOException {
        public AuthException() {
            super("配对密钥不对，被电脑拒绝");
        }
    }

    // ================================================================== 一次性下令

    /** 找 → 连 → 下令 → 关。找不到 = 离线（不排队）。 */
    public static Result once(Peer p, String cmd, Map<String, Object> args, long findMs, int timeoutMs, List<InetAddress> extraBroadcasts,
                              InetSocketAddress[] foundOut) {
        InetSocketAddress a = locate(p, findMs, extraBroadcasts);
        if (a == null) return Result.failure("offline", "电脑离线（局域网里没找到）");
        if (foundOut != null && foundOut.length > 0) foundOut[0] = a;
        Session s;
        try {
            s = Session.open(p, a, Math.min(timeoutMs, 5000));
        } catch (AuthException e) {
            return Result.failure("auth", "配对密钥不对，被电脑拒绝（电脑端可能换过钥匙，请重新扫码）");
        } catch (IOException e) {
            return Result.failure("offline", "找到了电脑但连不上：" + e.getClass().getSimpleName());
        }
        try {
            return s.request(cmd, args, timeoutMs);
        } finally {
            s.close();
        }
    }
}
