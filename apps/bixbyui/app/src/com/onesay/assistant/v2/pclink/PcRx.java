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

    /**
     * [link-v2 · 执行级别] 执行端如果同时实现了它，指令先过 {@link LinkPolicy}（级别 × 类别 × 信任检查）再执行；
     * 没实现的（旧测试桩）照旧只看逐项开关。Android 侧 {@code PcRxAndroid} 实现了它。
     */
    public interface Gate {
        /** 手机上用户选的执行级别。 */
        LinkPolicy.Level level();

        /** 工作区根目录（手机：getExternalFilesDir("workspace")）；拿不到返回 null（写文件一律拒）。 */
        java.io.File workspace();

        /** 补全信任检查要的事实：信任域名、App 是否禁止类 / 系统 App（可以往 args 里填解析出的 package）。 */
        LinkPolicy.Facts facts(String cmd, Map<String, Object> args, LinkPolicy.Cat cat);

        /** 在手机上弹确认（允许一次 / 拒绝），阻塞最多 timeoutMs。返回 {@link #CONFIRM_ALLOW} / {@link #CONFIRM_DENY} / {@link #CONFIRM_TIMEOUT}。 */
        int confirm(String cmd, String title, String detail, long timeoutMs);

        /** 生成脚本写进工作区 scripts/ 并通知用户「已生成脚本，需你自己执行」；返回相对路径，失败 null。 */
        String script(String cmd, Map<String, Object> args, String why);

        /** 完全访问下不确认、但给用户提个醒（通知，不阻塞）。 */
        void headsUp(String cmd, String text);
    }

    public static final int CONFIRM_ALLOW = 1, CONFIRM_DENY = 0, CONFIRM_TIMEOUT = -1;
    public static final long CONFIRM_TIMEOUT_MS = 30000;

    /** 等确认的小盒子（纯 Java，JVM 可测）：open 拿编号 → 通知按钮 resolve(编号, 允许?) → await 超时即算拒绝（CONFIRM_TIMEOUT）。 */
    public static final class ConfirmBox {
        private final Map<Integer, Object[]> pending = new java.util.concurrent.ConcurrentHashMap<Integer, Object[]>();
        private final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger(1);

        public int open() {
            int id = seq.getAndIncrement();
            pending.put(id, new Object[]{new java.util.concurrent.CountDownLatch(1), new int[]{CONFIRM_TIMEOUT}});
            return id;
        }

        /** 按钮回调；编号不存在（已超时 / 已处理）返回 false。只认第一次。 */
        public boolean resolve(int id, boolean allow) {
            Object[] p = pending.remove(id);
            if (p == null) return false;
            ((int[]) p[1])[0] = allow ? CONFIRM_ALLOW : CONFIRM_DENY;
            ((java.util.concurrent.CountDownLatch) p[0]).countDown();
            return true;
        }

        public int await(int id, long timeoutMs) {
            Object[] p = pending.get(id);
            if (p == null) return CONFIRM_TIMEOUT;
            try {
                ((java.util.concurrent.CountDownLatch) p[0]).await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pending.remove(id); // 超时后再点按钮也不算
            return ((int[]) p[1])[0];
        }

        public int size() {
            return pending.size();
        }
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

    /** 处理一条已验签的指令：ping / caps 总是可用；其他看开关和参数（执行端实现了 {@link Gate} 时再过执行级别）。 */
    public static PcLanClient.Result handle(Handler h, String cmd, Map<String, Object> args) {
        Gate g = h instanceof Gate ? (Gate) h : null;
        if ("ping".equals(cmd)) return new PcLanClient.Result(true, "在线", null, "");
        if ("caps".equals(cmd)) {
            List<Object> on = new ArrayList<Object>();
            on.add("ping");
            on.add("caps");
            for (String c : PcRxPolicy.CMDS) if (h.enabled(c)) on.add(c);
            if (g != null) for (String c : PcRxPolicy.EXTRA) on.add(c);
            Map<String, Object> d = new TreeMap<String, Object>();
            d.put("caps", on);
            if (g != null) putLevel(d, g);
            return new PcLanClient.Result(true, "能力清单", d, "");
        }
        if (!PcRxPolicy.known(cmd) || (g == null && PcRxPolicy.extra(cmd))) return new PcLanClient.Result(false, "手机不支持「" + cmd + "」", null, "");
        if (!PcRxPolicy.extra(cmd) && !h.enabled(cmd)) return new PcLanClient.Result(false, "手机上没开「" + PcRxPolicy.label(cmd) + "」（控制台 › 电脑联动 › 电脑可以让手机做的事）", null, "");
        String bad = PcRxPolicy.check(cmd, args);
        if (bad != null) return new PcLanClient.Result(false, bad, null, "");
        if (g != null) return gated(h, g, cmd, args == null ? new TreeMap<String, Object>() : args);
        try {
            PcLanClient.Result r = h.run(cmd, args == null ? new TreeMap<String, Object>() : args);
            return r == null ? new PcLanClient.Result(false, "没有结果", null, "") : r;
        } catch (Throwable t) {
            return new PcLanClient.Result(false, "手机上出错：" + t.getClass().getSimpleName(), null, "");
        }
    }

    // ================================================================== 执行级别

    static void putLevel(Map<String, Object> d, Gate g) {
        LinkPolicy.Level l = g.level();
        d.put("level", l.id);
        d.put("level_zh", l.zh);
    }

    static PcLanClient.Result withReason(Gate g, boolean ok, String text, String reason, Map<String, Object> extra) {
        Map<String, Object> d = new TreeMap<String, Object>();
        if (extra != null) d.putAll(extra);
        if (reason != null && reason.length() > 0) {
            d.put("reason", reason);
            d.put("reason_zh", LinkPolicy.reasonZh(reason));
        }
        putLevel(d, g);
        return new PcLanClient.Result(ok, text, d, "");
    }

    /** 指令 → 类别：setting_set 看 name；sys_exec 看命令本身；file_write 看路径在不在工作区里。 */
    public static LinkPolicy.Cat category(Gate g, String cmd, Map<String, Object> a) {
        if (PcRxPolicy.SETTING.equals(cmd)) return LinkPolicy.phoneSettingCat(PcRxPolicy.str(a, "name"));
        if (PcRxPolicy.SYS_EXEC.equals(cmd)) return LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, PcRxPolicy.str(a, "command"));
        if (PcRxPolicy.FILE_WRITE.equals(cmd)) {
            java.io.File ws = g.workspace();
            return ws != null && LinkPolicy.resolveInside(ws, PcRxPolicy.str(a, "path")) != null ? LinkPolicy.Cat.WORKSPACE_WRITE : LinkPolicy.Cat.OUTSIDE_WRITE;
        }
        return LinkPolicy.phoneCat(cmd);
    }

    /** 过执行级别：拒绝 / 生成脚本 / 确认后执行 / 直接执行。结果 data 带 level 和（不成功时）reason 原因码。 */
    static PcLanClient.Result gated(Handler h, Gate g, String cmd, Map<String, Object> a0) {
        Map<String, Object> a = new TreeMap<String, Object>();
        for (Map.Entry<String, Object> e : a0.entrySet()) if (!e.getKey().startsWith("_")) a.put(e.getKey(), e.getValue()); // 下划线开头的是手机内部标记，电脑不能带
        LinkPolicy.Cat cat = category(g, cmd, a);
        LinkPolicy.Facts f = g.facts(cmd, a, cat);
        LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PHONE, g.level(), cat, f);
        if (d.verdict == LinkPolicy.Verdict.DENY) return withReason(g, false, d.text, d.reason, null);
        if (d.verdict == LinkPolicy.Verdict.SCRIPT) {
            String path = g.script(cmd, a, d.text);
            if (path == null) return withReason(g, false, "要生成脚本，但写不进手机工作区", LinkPolicy.R_SCRIPT_GENERATED, null);
            Map<String, Object> x = new TreeMap<String, Object>();
            x.put("path", path);
            return withReason(g, false, "已生成脚本，需你自己执行：" + path, LinkPolicy.R_SCRIPT_GENERATED, x);
        }
        if (d.verdict == LinkPolicy.Verdict.CONFIRM) {
            int c = g.confirm(cmd, PcRxPolicy.label(cmd), d.text, CONFIRM_TIMEOUT_MS);
            if (c == CONFIRM_TIMEOUT) return withReason(g, false, "手机上 30 秒没人确认，已拒绝", LinkPolicy.R_CONFIRM_TIMEOUT, null);
            if (c != CONFIRM_ALLOW) return withReason(g, false, "手机上拒绝了", LinkPolicy.R_CONFIRM_DENIED, null);
            a.put("_confirmed", Boolean.TRUE);
        } else if (d.headsUp) {
            try {
                g.headsUp(cmd, d.text);
            } catch (Throwable ignored) {
            }
        }
        if (PcRxPolicy.CLIPBOARD.equals(cmd) && f != null && f.clip != null && f.clip.verdict == LinkPolicy.ClipCheck.SENSITIVE) a.put("_sensitive", Boolean.TRUE);
        if (cat == LinkPolicy.Cat.OUTSIDE_WRITE) a.put("_outside", Boolean.TRUE);
        if (f != null && f.url != null && f.url.ok) a.put("_url_host", f.url.host);
        PcLanClient.Result r;
        try {
            r = h.run(cmd, a);
        } catch (Throwable t) {
            r = new PcLanClient.Result(false, "手机上出错：" + t.getClass().getSimpleName(), null, "");
        }
        if (r == null) r = new PcLanClient.Result(false, "没有结果", null, "");
        Map<String, Object> data = new TreeMap<String, Object>(r.data);
        putLevel(data, g);
        return new PcLanClient.Result(r.ok, r.text, data, r.fail);
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
                if (h instanceof Gate && inflight.get() < 4 && maybeCmd(m)) {
                    // 可能要在手机上确认（最多 30 秒）：放到单独线程，常连线程继续收 ping / 别的指令。回复各带 re，乱序没关系。
                    inflight.incrementAndGet();
                    final String frame = m;
                    final PcWs fws = ws;
                    final PcLanClient.Peer fp = p;
                    final PcProtocol.ReplayGuard fg = guard;
                    final Handler fh = h;
                    com.onesay.assistant.v2.util.Threads.start("pclink-rx-cmd", new Runnable() {
                        @Override public void run() {
                            try {
                                String reply = onFrame(fp, fg, fh, frame, System.currentTimeMillis());
                                if (reply != null) fws.sendText(reply);
                            } catch (Throwable ignored) {
                            } finally {
                                inflight.decrementAndGet();
                            }
                        }
                    }); // 最多等 30 秒确认就结束
                    continue;
                }
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

    private static final java.util.concurrent.atomic.AtomicInteger inflight = new java.util.concurrent.atomic.AtomicInteger();

    /** 粗看是不是一条 cmd 帧（真正的解析 / 验签在 onFrame 里）。 */
    static boolean maybeCmd(String m) {
        return m.indexOf("\"cmd\"") >= 0 && m.indexOf("\"msg\"") >= 0;
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
