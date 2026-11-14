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
     * [link-v2 第二轮] 红线要的手机侧事实和动作（Android 侧 {@code PcRxAndroid} 实现；JVM 测试桩可以不实现，那时只做纯 Java 能做的检查）。
     * 不是执行级别：没有档位、不弹确认，只补「App 是不是系统 / 禁止类」「工作区在哪」「生成脚本」这三样。
     */
    public interface Guard {
        /** 补 App 事实：按名字找到的包名写回 args（判定和执行用同一个 App），是否系统 App、是否禁止类（含 OneSay 自己）。 */
        void appFacts(String cmd, Map<String, Object> args, LinkPolicy.Facts f);

        /** 手机工作区（getExternalFilesDir("workspace")）；拿不到返回 null。 */
        java.io.File workspace();

        /** 给 App 授权的命令：只生成脚本写进工作区 scripts/ 并通知「已生成脚本，需你自己执行」；返回相对路径，失败 null。 */
        String script(String cmd, Map<String, Object> args, String why);
    }

    /**
     * [approve-not-block] 手机上的确认卡和「以后这类不用问」（Android 侧 {@code PcRxAndroid} 实现；没实现时用 {@link PcConfirm} 的界面，
     * 没有界面 = 没人确认 = 拒绝）。Java 7 没有默认方法，所以单独一个接口，不改 {@link Guard}。
     */
    public interface Asker {
        /** 在手机上弹确认卡（阻塞，最多 60 秒）：返回 {@link PcConfirm#ALLOW} / {@link PcConfirm#DENY} / {@link PcConfirm#TIMEOUT} / {@link PcConfirm#REMEMBER}。 */
        int ask(String title, String detail, String rememberKind);

        /** 记住的动作类型（只有 phone.* 的在手机上记）。 */
        java.util.Collection<String> rememberedKinds();

        void remember(String kind);

        /** 敏感内容写进剪贴板后 60 秒清除（可选，默认关）。 */
        boolean clipAutoClear();
    }

    /** 往电脑发一帧（常连通道上的 ws.sendText）。 */
    public interface Out {
        void send(String text) throws IOException;
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

    /**
     * 处理一条已验签的指令：ping / caps 总是可用；其他看开关、参数和规则（没有执行级别）。三条硬规则直接拒 / 只生成脚本；
     * 原来拒的（支付 / 系统 App、带账号或疑似仿冒的网址）在手机上弹确认卡，可能阻塞到 60 秒（{@link #serve} 每条指令单独一个线程）。
     */
    public static PcLanClient.Result handle(Handler h, String cmd, Map<String, Object> args) {
        if ("ping".equals(cmd)) return new PcLanClient.Result(true, "在线", null, "");
        if ("caps".equals(cmd)) {
            List<Object> on = new ArrayList<Object>();
            on.add("ping");
            on.add("caps");
            for (String c : PcRxPolicy.ALL) if (h.enabled(c)) on.add(c);
            List<Object> all = new ArrayList<Object>();
            for (String c : PcRxPolicy.ALL) all.add(c);
            Map<String, Object> d = new TreeMap<String, Object>();
            d.put("caps", on);      // 现在开着的
            d.put("known", all);    // 这台手机支持的全部（关着的在手机控制台里能打开）
            return new PcLanClient.Result(true, "能力清单", d, "");
        }
        if (!PcRxPolicy.known(cmd)) return new PcLanClient.Result(false, "手机不支持「" + cmd + "」", null, "");
        if (!h.enabled(cmd)) return new PcLanClient.Result(false, "手机上没开「" + PcRxPolicy.label(cmd) + "」（控制台 › 电脑联动 › 电脑可以让手机做的事）", null, "");
        String bad = PcRxPolicy.check(cmd, args);
        if (bad != null) return new PcLanClient.Result(false, bad, null, "");
        Map<String, Object> a = new TreeMap<String, Object>();
        if (args != null) for (Map.Entry<String, Object> e : args.entrySet()) if (!e.getKey().startsWith("_")) a.put(e.getKey(), e.getValue()); // 下划线开头的是手机内部标记，电脑不能带
        Guard g = h instanceof Guard ? (Guard) h : null;
        LinkPolicy.Cat cat = category(g, cmd, a);
        LinkPolicy.Facts f = facts(g, cmd, a);
        // 规则（LinkPolicy.phoneLegacy）：不分档；硬规则拒 / 只生成脚本，原来拒的改成手机上确认
        Asker k = h instanceof Asker ? (Asker) h : null;
        LinkPolicy.Decision d = remembered(k, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, cat, f));
        if (d.verdict == LinkPolicy.Verdict.DENY) return withReason(false, d.text, d.reason, null);
        List<LinkPolicy.Decision> asks = new ArrayList<LinkPolicy.Decision>();
        if (d.verdict == LinkPolicy.Verdict.CONFIRM) asks.add(d);
        if (PcRxPolicy.SYS_EXEC.equals(cmd) && d.verdict != LinkPolicy.Verdict.SCRIPT) {
            // 系统命令里用 pm 卸载 / 停用 / 清数据某个 App：OneSay 自己不行（硬规则），系统 App 和支付 / 银行 / 钱包类先问你
            // 先去掉引号 / 反斜杠再认（pm 'uninstall' com.x、pm clear 'com.x' 执行的就是 pm uninstall / pm clear）
            java.util.regex.Matcher pm = PM_APP_OP.matcher(LinkPolicy.unquoteShell(PcRxPolicy.str(a, "command")));
            while (pm.find()) {
                Map<String, Object> pa = new TreeMap<String, Object>();
                pa.put("package", pm.group(3));
                LinkPolicy.Facts pf = facts(g, PcRxPolicy.UNINSTALL, pa);
                LinkPolicy.Decision pd = remembered(k, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.UNINSTALL_APP, pf));
                if (pd.verdict == LinkPolicy.Verdict.DENY) return withReason(false, pd.text, pd.reason, null);
                if (pd.verdict == LinkPolicy.Verdict.CONFIRM) asks.add(pd);
            }
        }
        if (d.verdict == LinkPolicy.Verdict.SCRIPT) {
            String path = g == null ? null : g.script(cmd, a, d.text);
            if (path == null) return withReason(false, "涉及授予权限，只能生成脚本，但写不进手机工作区", LinkPolicy.R_SCRIPT_GENERATED, null);
            Map<String, Object> x = new TreeMap<String, Object>();
            x.put("path", path);
            return withReason(false, "涉及授予权限，已生成脚本，需你自己执行：" + path, LinkPolicy.R_SCRIPT_GENERATED, x);
        }
        if (PcRxPolicy.SCRIPT_RUN.equals(cmd) && g != null) {
            // 工作区脚本：内容里有给 App 授权的命令就不替你运行（和系统命令同一条红线）
            java.io.File ws = g.workspace();
            java.io.File sf = ws == null ? null : LinkPolicy.resolveInside(ws, "scripts/" + PcRxPolicy.str(a, "name"));
            if (sf != null && sf.isFile() && sf.length() > PcRxPolicy.FILE_MAX) return withReason(false, "脚本太大（超过 256 KB），不检查就不跑", "", null);
            if (sf != null && sf.isFile() && LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, readText(sf)) == LinkPolicy.Cat.SELF_GRANT) {
                Map<String, Object> x = new TreeMap<String, Object>();
                x.put("path", "scripts/" + PcRxPolicy.str(a, "name"));
                return withReason(false, "脚本里有给 App 授权的命令，OneSay 不替你运行，需你自己执行：scripts/" + PcRxPolicy.str(a, "name"), LinkPolicy.R_SCRIPT_GENERATED, x);
            }
        }
        for (LinkPolicy.Decision ad : asks) {
            // 在手机上弹确认卡：真人在屏幕上点「允许」才做（60 秒没人点 / 拒绝 / 返回 = 不做）
            String rk = ad.rememberable() ? ad.kind : "";
            int r;
            try {
                r = k != null ? k.ask("电脑请求：" + PcRxPolicy.label(cmd), ad.text, rk) : PcConfirm.ask("电脑请求：" + PcRxPolicy.label(cmd), ad.text, rk, LinkPolicy.CONFIRM_TIMEOUT_MS);
            } catch (Throwable t) {
                r = PcConfirm.DENY; // 确认卡出错 = 拒绝
            }
            if (r == PcConfirm.TIMEOUT) return withReason(false, "手机上没人确认（60 秒到了），没做：" + ad.text, LinkPolicy.R_CONFIRM_TIMEOUT, null);
            if (r != PcConfirm.ALLOW && r != PcConfirm.REMEMBER) return withReason(false, "手机上拒绝了：" + ad.text, LinkPolicy.R_CONFIRM_DENIED, null);
            if (r == PcConfirm.REMEMBER && k != null && rk.length() > 0) k.remember(rk);
        }
        if (PcRxPolicy.CLIPBOARD.equals(cmd) && f.clip != null && f.clip.verdict == LinkPolicy.ClipCheck.SENSITIVE) {
            a.put("_sensitive", Boolean.TRUE);
            if (k != null && k.clipAutoClear()) a.put("_clear", Boolean.TRUE); // 60 秒清除：可选，默认关
        }
        if (cat == LinkPolicy.Cat.OUTSIDE_WRITE) a.put("_outside", Boolean.TRUE);
        try {
            PcLanClient.Result r = h.run(cmd, a);
            return r == null ? new PcLanClient.Result(false, "没有结果", null, "") : r;
        } catch (Throwable t) {
            return new PcLanClient.Result(false, "手机上出错：" + t.getClass().getSimpleName(), null, "");
        }
    }

    /** 「以后这类不用问」：手机上记住过的类型直接做（三条硬规则不是 CONFIRM，不受影响）。 */
    static LinkPolicy.Decision remembered(Asker k, LinkPolicy.Decision d) {
        if (k == null || d.verdict != LinkPolicy.Verdict.CONFIRM) return d;
        try {
            return LinkPolicy.remembered(d, k.rememberedKinds());
        } catch (Throwable t) {
            return d;
        }
    }

    /** 系统命令里的 pm 卸载 / 停用 / 隐藏 / 清数据 / 挂起：第 2 组是包名（跳过 --user 0、-k 之类选项）。 */
    static final java.util.regex.Pattern PM_APP_OP = java.util.regex.Pattern.compile(
            "\\bpm\\s+(uninstall|disable|disable-user|hide|clear|suspend)((?:\\s+--?[A-Za-z-]+(?:\\s+\\d+)?)*)\\s+([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)");

    static PcLanClient.Result withReason(boolean ok, String text, String reason, Map<String, Object> extra) {
        Map<String, Object> d = new TreeMap<String, Object>();
        if (extra != null) d.putAll(extra);
        if (reason != null && reason.length() > 0) {
            d.put("reason", reason);
            d.put("reason_zh", LinkPolicy.reasonZh(reason));
        }
        return new PcLanClient.Result(ok, text, d, "");
    }

    /** 指令 → 类别（只为红线）：setting_set 看 name；sys_exec 看命令本身；file_write 看路径在不在工作区里。 */
    public static LinkPolicy.Cat category(Guard g, String cmd, Map<String, Object> a) {
        if (PcRxPolicy.SETTING.equals(cmd)) return LinkPolicy.phoneSettingCat(PcRxPolicy.str(a, "name"));
        if (PcRxPolicy.SYS_EXEC.equals(cmd)) return LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, PcRxPolicy.str(a, "command"));
        if (PcRxPolicy.FILE_WRITE.equals(cmd)) {
            java.io.File ws = g == null ? null : g.workspace();
            if (ws == null) return LinkPolicy.Cat.WORKSPACE_WRITE; // 测试桩：执行端自己管
            return LinkPolicy.resolveInside(ws, PcRxPolicy.str(a, "path")) != null ? LinkPolicy.Cat.WORKSPACE_WRITE : LinkPolicy.Cat.OUTSIDE_WRITE;
        }
        return LinkPolicy.phoneCat(cmd);
    }

    /** 红线要的事实：网址检查、剪贴板检查（纯 Java）；App 是不是禁止类 / 系统 App（Android 侧补全）。 */
    static LinkPolicy.Facts facts(Guard g, String cmd, Map<String, Object> a) {
        LinkPolicy.Facts f = new LinkPolicy.Facts();
        if (PcRxPolicy.OPEN_URL.equals(cmd)) {
            String raw = PcRxPolicy.str(a, "url");
            String u = PcVoice.normalizeUrl(raw);
            f.url = LinkPolicy.checkUrl(u == null ? raw : u, java.util.Collections.<String>emptyList());
        } else if (PcRxPolicy.SYS_EXEC.equals(cmd)) {
            // 写法可疑（引号拆分、同形字、全角、零宽、变量展开…）：守卫认不准，改成不能记住的确认卡
            f.oddWriting = LinkPolicy.oddShellWriting(PcRxPolicy.str(a, "command"));
        } else if (PcRxPolicy.CLIPBOARD.equals(cmd)) {
            f.clip = LinkPolicy.checkClip(a.get("text") == null ? "" : String.valueOf(a.get("text")));
        } else if (PcRxPolicy.OPEN_APP.equals(cmd) || PcRxPolicy.UNINSTALL.equals(cmd)) {
            f.appBlocked = LinkPolicy.blockedApp(PcRxPolicy.str(a, "package"), PcRxPolicy.str(a, "name"));
            if (g != null) {
                try {
                    g.appFacts(cmd, a, f);
                } catch (Throwable t) {
                    f.appBlocked = true; // 查不了 = 不碰
                }
            }
        }
        return f;
    }

    static String readText(java.io.File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f));
            try {
                in.readFully(b);
            } finally {
                in.close();
            }
            return LinkPolicy.decodeScript(b);
        } catch (Exception e) {
            return "";
        }
    }

    // ================================================================== 常连会话

    /** 收到一帧：是电脑来的指令就验签、执行、回结果。返回要发回的文本（不是指令返回 null）。 */
    static String onFrame(PcLanClient.Peer p, PcProtocol.ReplayGuard guard, Handler h, String text, long nowMs) {
        return onFrame(p, guard, h, text, nowMs, null);
    }

    /** 同上；out 非空时也处理电脑发来的确认卡（confirm_offer / confirm_close），点完经 out 回 confirm_answer。 */
    @SuppressWarnings("unchecked")
    static String onFrame(final PcLanClient.Peer p, PcProtocol.ReplayGuard guard, Handler h, String text, long nowMs, final Out out) {
        Map<String, Object> f;
        try {
            f = PcJson.obj(text);
        } catch (Exception e) {
            return null;
        }
        if (!"msg".equals(f.get("type")) || !(f.get("body") instanceof Map)) return null;
        Map<String, Object> body = (Map<String, Object>) f.get("body");
        String from = String.valueOf(f.get("from")), id = String.valueOf(f.get("id"));
        if (!p.peer.equals(from)) return null; // 只听配对的那台电脑
        Object kind = body.get("kind");
        if ("confirm_offer".equals(kind) || "confirm_close".equals(kind)) {
            if (PcProtocol.verify(p.key, id, from, p.me, body, nowMs, guard) != PcProtocol.Check.OK) return null;
            Object cid = body.get("cid");
            if (!(cid instanceof Number)) return null;
            if ("confirm_close".equals(kind)) {
                PcConfirm.closePc(from, ((Number) cid).longValue());
                return null;
            }
            if (out == null) return null;
            Object to = body.get("timeout_ms");
            PcConfirm.offer(from, ((Number) cid).longValue(), PcRxPolicy.str(body, "token"), PcRxPolicy.str(body, "detail"), PcRxPolicy.str(body, "remember_kind"),
                    Boolean.TRUE.equals(body.get("always")), to instanceof Number ? ((Number) to).longValue() : LinkPolicy.CONFIRM_TIMEOUT_MS, new PcConfirm.Reply() {
                        @Override public void send(PcConfirm.Card c, boolean allow, boolean always, boolean remember, long shownMs) {
                            try {
                                out.send(answerFrame(p, c, allow, always, remember, shownMs, System.currentTimeMillis()));
                            } catch (IOException ignored) {
                                // 回不去：电脑等不到就按拒绝
                            }
                        }
                    });
            return null;
        }
        if (!"cmd".equals(kind)) return null;
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

    /**
     * 手机确认卡上真人点完：签名的 confirm_answer（带电脑给的编号和一次性 token、手机上显示了多久、input = touch）。
     * 只有 {@link PcConfirm#answer} 在真人触摸后才会走到这里；语音 / 机器人 / 远程指令都到不了。
     */
    static String answerFrame(PcLanClient.Peer p, PcConfirm.Card c, boolean allow, boolean always, boolean remember, long shownMs, long nowMs) {
        Map<String, Object> b = new TreeMap<String, Object>();
        b.put("kind", "confirm_answer");
        b.put("cid", Long.valueOf(c.pcId));
        b.put("token", c.pcToken);
        b.put("allow", Boolean.valueOf(allow));
        b.put("always", Boolean.valueOf(always));
        b.put("remember", Boolean.valueOf(remember));
        b.put("input", allow ? "touch" : "card");
        b.put("shown_ms", Long.valueOf(shownMs));
        String rid = PcProtocol.newId();
        Map<String, Object> env = new TreeMap<String, Object>();
        env.put("type", "msg");
        env.put("to", p.peer);
        env.put("id", rid);
        env.put("body", PcProtocol.seal(p.key, rid, p.me, p.peer, b, nowMs));
        return PcJson.stringify(env);
    }

    /** 同时在处理的指令最多几条（要确认的可能等 60 秒，每条一个线程，免得挡住后面的 ping / 找手机）。 */
    static final int MAX_INFLIGHT = 6;

    /** 阻塞服务一条常连会话直到断开 / stop。每 25 秒发一次 ping（电脑端 95 秒没动静会断）。返回断开原因。 */
    public static String serve(PcLanClient.Session s, final Handler h, Stop stop) {
        final PcWs ws = s.ws();
        final PcLanClient.Peer p = s.peer();
        final PcProtocol.ReplayGuard guard = new PcProtocol.ReplayGuard();
        final Out out = new Out() {
            @Override public void send(String text) throws IOException { ws.sendText(text); }
        };
        final java.util.concurrent.atomic.AtomicInteger inflight = new java.util.concurrent.atomic.AtomicInteger();
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
                if (inflight.get() >= MAX_INFLIGHT) {
                    String reply;
                    try {
                        reply = onFrame(p, guard, h, m, System.currentTimeMillis(), out);
                    } catch (RuntimeException e) {
                        reply = null; // 一帧坏数据只丢这一帧，不打断整条常连（和异步分支一样）
                    }
                    if (reply != null) ws.sendText(reply);
                    continue;
                }
                final String frame = m;
                inflight.incrementAndGet();
                com.onesay.assistant.v2.util.Threads.start("pclink-rx-cmd", new Runnable() {
                    @Override public void run() {
                        try {
                            String reply = onFrame(p, guard, h, frame, System.currentTimeMillis(), out);
                            if (reply != null) ws.sendText(reply);
                        } catch (Throwable ignored) {
                            // 连接断了：电脑等不到就按超时
                        } finally {
                            inflight.decrementAndGet();
                        }
                    }
                });
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
