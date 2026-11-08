package com.onesay.assistant.v2.pclink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * [pclink v2] 电脑 → 手机：手机保持一条到电脑的「常连通道」（hello 带 hold=true；配对带指纹时走 TLS），电脑从这条连接下签名指令，
 * 手机验签 + 时间窗 + 一次性编号 + 本机开关后执行，回签名结果。纯 Java（Android 动作由 {@link Handler} 提供），JVM 可测。
 *
 * <p>为什么是手机连电脑、而不是手机开端口：Windows 防火墙已经为电脑端放行了 47820；手机不用监听任何端口，
 * 换 Wi-Fi / 电脑重启后由手机重连。电脑不在时手机退避等待，期间听组播 / 广播里电脑的 announce（{@link #waitAnnounce}），
 * 一听到就立刻重连 —— 这是参考 LocalSend（Apache-2.0）协议里「组播 announce → 对方立刻回应」的做法，代码自己写的。
 */
public final class PcRx {

    private PcRx() {}

    /** 执行端（Android 侧实现真正的响铃 / 打开 App 等）。 */
    public interface Handler {
        /** 这一项在手机上开着吗（总开关另由 {@link Env#wanted} 管）。 */
        boolean enabled(String cmd);

        /** 执行（参数已检查过）。在常连线程上调用，必须很快返回（响铃等放到别的线程）。 */
        PcLanClient.Result run(String cmd, Map<String, Object> args);

        /** 记一笔（控制台「最近电脑让手机做的事」），只记指令名和结果，不记内容。 */
        void record(String cmd, boolean ok, String text);
    }

    /** 常连循环需要的环境（Android：开关、Wi-Fi、组播锁、配对信息）。 */
    public interface Env {
        boolean wanted();

        boolean onLan();

        PcLanClient.Peer peer();

        List<InetAddress> extraBroadcasts();

        /** 等 announce 时拿 / 放组播锁（Android WifiManager.MulticastLock）。 */
        void multicast(boolean hold);

        void state(String state, String detail);

        void remember(InetSocketAddress a);

        Handler handler();
    }

    public static final String S_OFF = "off", S_NO_WIFI = "no_wifi", S_SEARCH = "search", S_UP = "up", S_WAIT = "wait", S_ERROR = "error";

    // ================================================================== 单条指令

    /** 处理一条已验签的指令：ping / caps 总是可用；其他看开关和参数。 */
    public static PcLanClient.Result handle(Handler h, String cmd, Map<String, Object> args) {
        if ("ping".equals(cmd)) return new PcLanClient.Result(true, "在线", null, "");
        if ("caps".equals(cmd)) {
            List<Object> on = new ArrayList<Object>();
            on.add("ping");
            on.add("caps");
            for (String c : PcRxPolicy.CMDS) if (h.enabled(c)) on.add(c);
            Map<String, Object> d = new TreeMap<String, Object>();
            d.put("caps", on);
            return new PcLanClient.Result(true, "能力清单", d, "");
        }
        if (!PcRxPolicy.known(cmd)) return new PcLanClient.Result(false, "手机不支持「" + cmd + "」", null, "");
        if (!h.enabled(cmd)) return new PcLanClient.Result(false, "手机上没开「" + PcRxPolicy.label(cmd) + "」（控制台 › 电脑联动 › 电脑可以让手机做的事）", null, "");
        String bad = PcRxPolicy.check(cmd, args);
        if (bad != null) return new PcLanClient.Result(false, bad, null, "");
        try {
            PcLanClient.Result r = h.run(cmd, args == null ? new TreeMap<String, Object>() : args);
            return r == null ? new PcLanClient.Result(false, "没有结果", null, "") : r;
        } catch (Throwable t) {
            return new PcLanClient.Result(false, "手机上出错：" + t.getClass().getSimpleName(), null, "");
        }
    }

    // ================================================================== 常连会话

    /** 收到一帧：是电脑来的指令就验签、执行、回结果。返回要发回的文本（不是指令返回 null）。 */
    @SuppressWarnings("unchecked")
    static String onFrame(PcLanClient.Peer p, PcProtocol.ReplayGuard guard, Handler h, String text, long nowMs) {
        Map<String, Object> f;
        try {
            f = PcJson.obj(text);
        } catch (Exception e) {
            return null;
        }
        if (!"msg".equals(f.get("type")) || !(f.get("body") instanceof Map)) return null;
        Map<String, Object> body = (Map<String, Object>) f.get("body");
        if (!"cmd".equals(body.get("kind"))) return null;
        String from = String.valueOf(f.get("from")), id = String.valueOf(f.get("id"));
        if (!p.peer.equals(from)) return null; // 只听配对的那台电脑
        String cmd = String.valueOf(body.get("cmd"));
        PcProtocol.Check c = PcProtocol.verify(p.key, id, from, p.me, body, nowMs, guard);
        PcLanClient.Result r;
        if (c != PcProtocol.Check.OK) {
            r = new PcLanClient.Result(false, "拒绝：" + c, null, "");
        } else {
            Map<String, Object> args = body.get("args") instanceof Map ? (Map<String, Object>) body.get("args") : new TreeMap<String, Object>();
            r = handle(h, cmd, args);
        }
        if (!"ping".equals(cmd) && !"caps".equals(cmd)) {
            try {
                h.record(PcRxPolicy.known(cmd) ? cmd : "?", r.ok, c == PcProtocol.Check.OK ? r.text : "拒绝：" + c);
            } catch (Throwable ignored) {
            }
        }
        String rid = PcProtocol.newId();
        Map<String, Object> env = new TreeMap<String, Object>();
        env.put("type", "msg");
        env.put("to", p.peer);
        env.put("id", rid);
        env.put("body", PcProtocol.result(p.key, rid, p.me, p.peer, id, r.ok, r.text, r.data, nowMs));
        return PcJson.stringify(env);
    }

    /** 阻塞服务一条常连会话直到断开 / stop。每 25 秒发一次 ping（电脑端 95 秒没动静会断）。返回断开原因。 */
    public static String serve(PcLanClient.Session s, Handler h, Stop stop) {
        PcWs ws = s.ws();
        PcLanClient.Peer p = s.peer();
        PcProtocol.ReplayGuard guard = new PcProtocol.ReplayGuard();
        long lastPing = System.currentTimeMillis();
        try {
            while (!stop.stopped()) {
                ws.setReadTimeout(1000);
                String m;
                try {
                    m = ws.readText();
                } catch (SocketTimeoutException te) {
                    if (System.currentTimeMillis() - lastPing > 25000) {
                        ws.sendText("ping");
                        lastPing = System.currentTimeMillis();
                    }
                    continue;
                }
                if (m == null) return "closed " + ws.closeCode;
                if ("pong".equals(m)) continue;
                String reply = onFrame(p, guard, h, m, System.currentTimeMillis());
                if (reply != null) ws.sendText(reply);
            }
            return "stopped";
        } catch (IOException e) {
            return "io " + e.getClass().getSimpleName();
        } finally {
            s.close();
        }
    }

    public interface Stop {
        boolean stopped();
    }

    // ================================================================== 常连循环（带退避与 announce）

    /** 一条后台线程跑的循环：找电脑 → 连（hold）→ 服务 → 断了退避，退避期间听 announce。 */
    public static final class Loop implements Runnable, Stop {
        private final Env env;
        private volatile boolean stopped;
        private volatile boolean kick;
        private final Object lock = new Object();
        /** 测试可调小。 */
        long minBackoff = 2000, maxBackoff = 60000, idleMs = 30000;

        public Loop(Env env) {
            this.env = env;
        }

        @Override public boolean stopped() {
            return stopped;
        }

        public void stop() {
            stopped = true;
            wake();
        }

        /** 开关 / 配对 / 网络变了：立刻再试。 */
        public void wake() {
            kick = true;
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        private void sleep(long ms) {
            long end = System.currentTimeMillis() + ms;
            synchronized (lock) {
                while (!stopped && !kick && System.currentTimeMillis() < end) {
                    try {
                        lock.wait(Math.max(1, end - System.currentTimeMillis()));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            kick = false;
        }

        @Override public void run() {
            long backoff = minBackoff;
            while (!stopped) {
                if (!env.wanted()) {
                    env.state(S_OFF, "");
                    sleep(idleMs);
                    backoff = minBackoff;
                    continue;
                }
                if (!env.onLan()) {
                    env.state(S_NO_WIFI, "");
                    sleep(idleMs);
                    continue;
                }
                PcLanClient.Peer p = env.peer();
                if (p == null) {
                    env.state(S_ERROR, "配对信息解不开");
                    sleep(idleMs);
                    continue;
                }
                env.state(S_SEARCH, "");
                InetSocketAddress a = PcLanClient.locate(p, 2500, env.extraBroadcasts());
                if (a != null && !stopped) {
                    PcLanClient.Session s = null;
                    try {
                        s = PcLanClient.Session.open(p, a, 6000, true);
                    } catch (PcLanClient.AuthException e) {
                        env.state(S_ERROR, "配对密钥不对，被电脑拒绝（请重新扫码）");
                        backoff = maxBackoff * 5;
                    } catch (PcLanClient.PinException e) {
                        env.state(S_ERROR, PcLanClient.pinText(e.code));
                        backoff = maxBackoff * 5;
                    } catch (PcWs.TlsException e) {
                        env.state(S_ERROR, "电脑证书和配对时的不一致，请重新扫码");
                        backoff = maxBackoff * 5;
                    } catch (IOException e) {
                        // 连不上：退避
                    }
                    if (s != null) {
                        env.remember(a);
                        env.state(S_UP, a.getAddress().getHostAddress() + (s.tls() ? " · 加密" : " · 未加密（旧版配对）"));
                        backoff = minBackoff;
                        serve(s, env.handler(), this);
                        if (stopped) break;
                        env.state(S_WAIT, "连接断开");
                        sleep(500);
                        continue;
                    }
                }
                if (stopped) break;
                env.state(S_WAIT, "");
                long waited = waitAnnounce(p, backoff);
                if (waited < 0) backoff = minBackoff; // 听到电脑上线：马上重连
                else backoff = Math.min(maxBackoff, backoff * 2);
            }
            env.state(S_OFF, "");
        }

        /** 等 ms 毫秒；期间听电脑的 announce（组播 / 广播，端口 = 局域网端口 + 1）。听到返回 -1，被 wake 返回 0，超时返回 ms。 */
        long waitAnnounce(PcLanClient.Peer p, long ms) {
            MulticastSocket ms2 = null;
            long end = System.currentTimeMillis() + ms;
            try {
                env.multicast(true);
                try {
                    ms2 = new MulticastSocket(null);
                    ms2.setReuseAddress(true);
                    ms2.bind(new InetSocketAddress(p.lanPort + 1));
                    joinGroup(ms2);
                    ms2.setSoTimeout(500);
                } catch (IOException e) {
                    if (ms2 != null) ms2.close();
                    ms2 = null;
                }
                byte[] buf = new byte[1024];
                while (!stopped && System.currentTimeMillis() < end) {
                    if (kick) {
                        kick = false;
                        return 0;
                    }
                    if (ms2 == null) {
                        sleep(Math.min(1000, Math.max(1, end - System.currentTimeMillis())));
                        continue;
                    }
                    try {
                        DatagramPacket pk = new DatagramPacket(buf, buf.length);
                        ms2.receive(pk);
                        if (PcLanClient.announcePort(p, new String(pk.getData(), pk.getOffset(), pk.getLength(), PcProtocol.UTF8)) > 0) return -1;
                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException e) {
                        sleep(500);
                    }
                }
                return ms;
            } finally {
                if (ms2 != null) ms2.close();
                env.multicast(false);
            }
        }
    }

    static void joinGroup(MulticastSocket ms) {
        InetAddress g;
        try {
            g = InetAddress.getByName(PcLanClient.GROUP);
        } catch (Exception e) {
            return;
        }
        boolean any = false;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                    ms.joinGroup(new InetSocketAddress(g, 0), ni);
                    any = true;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        if (!any) {
            try {
                ms.joinGroup(g);
            } catch (Exception ignored) {
            }
        }
    }
}
