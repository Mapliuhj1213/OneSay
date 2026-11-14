package com.onesay.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** 电脑端常驻：局域网（默认）+ 中转（可选插件）收指令，验签、执行，从原通道回签名结果。 */
public final class Agent {
    private final Config cfg;
    private final Actions actions;
    private final List<Link> links = new ArrayList<>();
    private final Map<Link, String> state = new ConcurrentHashMap<>();
    private final Map<Link, Boolean> up = new ConcurrentHashMap<>();
    private final Protocol.ReplayGuard replay = new Protocol.ReplayGuard();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "onesay-exec");
        t.setDaemon(true);
        return t;
    });
    private final Consumer<String> log;
    public volatile String status = "启动中";
    /** 程序内部处理的指令（通知、记忆同步等），优先于 Actions。 */
    public final Map<String, java.util.function.Function<Map<String, Object>, Actions.Result>> local = new ConcurrentHashMap<>();
    private final java.util.Set<String> relayPeers = ConcurrentHashMap.newKeySet();
    /** link-v2：手机发来的指令先过执行级别 / 信任检查 / 工作区沙箱（电脑是严格矩阵）。 */
    public final PcGuard.Confirms confirms = new PcGuard.Confirms();
    public final PcGuard guard;
    /** 手机回复里带来的手机执行级别（只读显示，在手机上改）。 */
    public volatile String phoneLevel = "";

    public Agent(Config cfg, Actions actions, Consumer<String> log) {
        this.cfg = cfg;
        this.actions = actions;
        this.log = log;
        this.guard = new PcGuard(cfg, confirms);
        confirms.phoneOffer = this::offerToPhone;
        confirms.phoneClose = this::closeOnPhone;
        if (cfg.lanEnabled()) add(l -> new LanServer(cfg, l));
        if (cfg.relayEnabled()) add(l -> new RelayClient(cfg, l));
    }

    private void add(java.util.function.Function<Link.Listener, Link> make) {
        Link[] self = new Link[1];
        self[0] = make.apply(new Link.Listener() {
            @Override public void onFrame(Map<String, Object> f) { handle(f, self[0]); }
            @Override public void onState(boolean c, String why) { changed(self[0], c, why); }
        });
        links.add(self[0]);
    }

    public void start() { for (Link l : links) l.start(); }

    public void stop() { for (Link l : links) l.stop(); exec.shutdownNow(); }

    public boolean connected() { return up.containsValue(Boolean.TRUE); }

    public List<Link> links() { return links; }

    /** 当前连着的配对设备：设备 id → 通道名。 */
    public Map<String, String> peers() {
        Map<String, String> m = new TreeMap<>();
        for (String p : relayPeers) m.put(p, "中转");
        for (Link l : links) if (l instanceof LanServer) for (String c : ((LanServer) l).clients()) m.put(c, ((LanServer) l).tls(c) ? "局域网·加密" : "局域网");
        return m;
    }

    private void changed(Link l, boolean c, String why) {
        up.put(l, c);
        state.put(l, (c ? "在线" : "离线") + "（" + why + "）");
        StringBuilder s = new StringBuilder(connected() ? "在线" : "离线");
        for (Link x : links) s.append(" · ").append(x.name()).append(Boolean.TRUE.equals(up.get(x)) ? "✓" : "✗");
        status = s.toString();
        log.accept(l.name() + " " + state.get(l));
    }

    @SuppressWarnings("unchecked")
    void handle(Map<String, Object> f, Link via) {
        String type = String.valueOf(f.get("type"));
        if (type.equals("presence")) {
            boolean on = Boolean.TRUE.equals(f.get("online"));
            if (on) relayPeers.add(String.valueOf(f.get("device"))); else relayPeers.remove(String.valueOf(f.get("device")));
            log.accept("设备 " + f.get("device") + (on ? " 上线" : " 离线") + "（" + via.name() + "）");
            return;
        }
        if (type.equals("devices") || type.equals("error")) {
            if (type.equals("error")) log.accept(via.name() + "报错 " + f.get("code"));
            return;
        }
        if (!type.equals("msg") || !(f.get("body") instanceof Map)) return;
        String from = String.valueOf(f.get("from"));
        String id = String.valueOf(f.get("id"));
        Map<String, Object> body = (Map<String, Object>) f.get("body");
        if ("result".equals(body.get("kind"))) { onResult(from, id, body); return; } // 电脑 → 手机下令的回复
        if ("confirm_answer".equals(body.get("kind"))) { onConfirmAnswer(from, id, body); return; } // 手机屏幕上确认卡的回答
        if (!"cmd".equals(body.get("kind"))) return;

        Protocol.Check c = Protocol.verify(cfg.pairKey, id, from, cfg.deviceId, body, System.currentTimeMillis(), replay);
        if (c != Protocol.Check.OK) {
            log.accept("拒绝来自 " + from + " 的指令：" + c);
            reply(via, from, id, false, "拒绝：" + c, null, c == Protocol.Check.BAD_SIG || c == Protocol.Check.NO_SIG);
            return;
        }
        if (!cfg.peerAllowed(from)) {
            log.accept("拒绝未授权设备 " + from);
            reply(via, from, id, false, "此设备未被允许下令", null, false);
            return;
        }
        String cmd = String.valueOf(body.get("cmd"));
        Map<String, Object> args = body.get("args") instanceof Map ? (Map<String, Object>) body.get("args") : Map.of();
        log.accept("执行 " + cmd + "（来自 " + from + "，经" + via.name() + "）");
        // 要确认的指令最多等 60 秒：放到单独线程，免得挡住后面的指令（找手机 / 状态等仍走顺序执行线程）
        Runnable job = () -> {
            Actions.Result r;
            SafeExec.REMOTE.set(Boolean.TRUE); // 远程指令：子进程永不提权；OneSay 是管理员时要降权，降不了就拒
            try {
                PcGuard.Outcome o = guard.check(from, cmd, args);
                if (o.reply != null) {
                    r = o.reply;
                    log.accept("没执行 " + cmd + "（来自 " + from + "）：" + r.data.get("reason"));
                } else if (o.pass == null || !o.pass.consume(cmd, o.args)) {
                    // 审批在执行层强制：没有判定放行时发的一次性凭证就不执行（思路借鉴 DIYgod/pinky，Apache-2.0；代码自己写的）
                    r = Actions.Result.fail("没有通过审批，不执行");
                } else if (cmd.equals("caps")) {
                    List<Object> caps = new ArrayList<>(actions.capabilities());
                    caps.addAll(new java.util.TreeSet<>(local.keySet()));
                    caps.addAll(List.of("file_write", "script_list", "script_run", "sys_exec", "level"));
                    r = Actions.Result.ok("能力清单", DesktopActions.map("caps", caps));
                } else if (local.containsKey(cmd)) {
                    Map<String, Object> a2 = new TreeMap<>(o.args);
                    a2.put("_from", from);
                    r = local.get(cmd).apply(a2);
                } else {
                    Actions.Result b = guard.builtin(cmd, o.args);
                    r = b != null ? b : actions.run(cmd, o.args);
                }
            } catch (SafeExec.Refused t) {
                Map<String, Object> d = new TreeMap<>();
                d.put("reason", LinkPolicy.R_PLUGIN_BLOCKED);
                d.put("reason_zh", LinkPolicy.reasonZh(LinkPolicy.R_PLUGIN_BLOCKED));
                r = new Actions.Result(false, t.getMessage(), d);
                log.accept("没执行 " + cmd + "（来自 " + from + "）：" + LinkPolicy.R_PLUGIN_BLOCKED);
            } catch (Throwable t) {
                r = Actions.Result.fail(cmd + " 出错：" + t.getClass().getSimpleName());
            } finally {
                SafeExec.REMOTE.set(Boolean.FALSE);
            }
            reply(via, from, id, r.ok, r.text, guard.decorate(r.data), false);
        };
        LinkPolicy.Cat cat = PcGuard.category(cmd, args, null);
        if (cat == LinkPolicy.Cat.QUERY || cat == LinkPolicy.Cat.HARMLESS || cat == LinkPolicy.Cat.READ_PRIVATE || cat == LinkPolicy.Cat.UNKNOWN) exec.execute(job);
        else {
            Thread t = new Thread(job, "onesay-exec-confirm");
            t.setDaemon(true);
            t.start();
        }
    }

    // ------------------------------------------------------------------ 电脑上的确认也发到手机（手机屏幕上能批）

    /**
     * 把一条确认发给发令的那台手机（常连通道 / 中转）：签名的 confirm_offer，带一次性 token。手机界面上真人点了才回 confirm_answer；
     * 手机不在线就不发（照样只在电脑上等）。
     */
    boolean offerToPhone(PcGuard.PhoneOffer o) {
        if (o == null || o.from == null || o.from.isEmpty() || !cfg.peerAllowed(o.from)) return false;
        Link via = route(o.from);
        if (via == null) return false;
        Map<String, Object> b = new TreeMap<>();
        b.put("kind", "confirm_offer");
        b.put("cid", (long) o.id);
        b.put("token", o.token);
        b.put("cmd", o.cmd);
        b.put("detail", o.detail);
        b.put("always", o.always);
        b.put("remember_kind", o.rememberKind);
        b.put("remember_zh", o.rememberKind.isEmpty() ? "" : LinkPolicy.kindZh(o.rememberKind));
        b.put("timeout_ms", Math.max(0, o.deadline - System.currentTimeMillis()));
        String mid = Protocol.newId();
        via.send(Protocol.envelope(o.from, mid, Protocol.seal(cfg.pairKey, mid, cfg.deviceId, o.from, b, System.currentTimeMillis())));
        log.accept("确认也发到了手机 " + o.from + "（手机屏幕上真人点击也能批）");
        return true;
    }

    /** 这条确认结束了：叫手机把卡收起来（尽力而为）。 */
    void closeOnPhone(PcGuard.PhoneOffer o) {
        Link via = o == null ? null : route(o.from);
        if (via == null) return;
        Map<String, Object> b = new TreeMap<>();
        b.put("kind", "confirm_close");
        b.put("cid", (long) o.id);
        String mid = Protocol.newId();
        via.send(Protocol.envelope(o.from, mid, Protocol.seal(cfg.pairKey, mid, cfg.deviceId, o.from, b, System.currentTimeMillis())));
    }

    /** 手机确认卡的回答：验签 + 时间窗 + 一次性编号 + 允许的设备，再交给 {@link PcGuard.Confirms#answerPhone}（规则在 ConfirmGate）。 */
    private void onConfirmAnswer(String from, String id, Map<String, Object> body) {
        Protocol.Check c = Protocol.verify(cfg.pairKey, id, from, cfg.deviceId, body, System.currentTimeMillis(), replay);
        if (c != Protocol.Check.OK || !cfg.peerAllowed(from)) {
            log.accept("手机 " + from + " 的确认回答没通过校验：" + (c != Protocol.Check.OK ? c : "设备未被允许"));
            return;
        }
        Object cid = body.get("cid"), shown = body.get("shown_ms");
        if (!(cid instanceof Number)) return;
        ConfirmGate.Answer a = confirms.answerPhone(from, ((Number) cid).intValue(), String.valueOf(body.get("token")), Boolean.TRUE.equals(body.get("allow")),
                Boolean.TRUE.equals(body.get("always")), Boolean.TRUE.equals(body.get("remember")), String.valueOf(body.get("input")),
                shown instanceof Number ? ((Number) shown).longValue() : 0L);
        boolean allow = Boolean.TRUE.equals(body.get("allow"));
        if (a == ConfirmGate.Answer.ACCEPTED) log.accept("手机 " + from + " 的确认卡上" + (allow ? "允许" : "拒绝") + "了一条请求（真人触摸）");
        else if (a == ConfirmGate.Answer.NOT_LOCAL) log.accept("手机 " + from + " 回的「允许」不是手机界面上的真人触摸 / token 对不上，没算数：" + LinkPolicy.R_VOICE_APPROVAL_BLOCKED);
        else log.accept("手机 " + from + " 的确认回答没算数（" + a + "）");
    }

    // ------------------------------------------------------------------ 电脑 → 手机（协议 v2）

    private final Map<String, java.util.concurrent.CompletableFuture<Actions.Result>> pending = new ConcurrentHashMap<>();
    private final Map<String, String> pendingTo = new ConcurrentHashMap<>();
    private final Protocol.ReplayGuard resultReplay = new Protocol.ReplayGuard();

    @SuppressWarnings("unchecked")
    private void onResult(String from, String id, Map<String, Object> body) {
        String re = String.valueOf(body.get("re"));
        java.util.concurrent.CompletableFuture<Actions.Result> f = pending.get(re);
        if (f == null || !from.equals(pendingTo.get(re))) return; // 不是我们发出去的，或来源不对
        Protocol.Check c = Protocol.verify(cfg.pairKey, id, from, cfg.deviceId, body, System.currentTimeMillis(), resultReplay);
        if (c != Protocol.Check.OK) {
            log.accept("手机 " + from + " 的回复没通过校验：" + c);
            f.complete(Actions.Result.fail("手机的回复没通过校验（" + c + "）"));
            return;
        }
        Map<String, Object> data = body.get("data") instanceof Map ? (Map<String, Object>) body.get("data") : new TreeMap<>();
        if (data.get("level") != null) phoneLevel = String.valueOf(data.get("level"));
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
        f.complete(Boolean.TRUE.equals(body.get("ok")) ? Actions.Result.ok(text, data) : new Actions.Result(false, text, data));
    }

    /** 要下令的手机：指定的 → 已连着常连通道的 → 最近一台常连的。没有返回 null。 */
    public String phoneTarget(String wanted) {
        if (wanted != null && !wanted.isEmpty()) return wanted;
        for (Link l : links) if (l instanceof LanServer) for (String c : ((LanServer) l).clients()) if (((LanServer) l).held(c)) return c;
        return cfg.phoneLast.isEmpty() ? null : cfg.phoneLast;
    }

    /**
     * 电脑给手机下一条签名指令，等签名结果。手机没连着常连通道时先发 announce（组播 + 广播）叫它连回来，最多等 waitMs。
     * 离线不排队：等不到就直接失败。
     */
    public java.util.concurrent.CompletableFuture<Actions.Result> callPeer(String device, String cmd, Map<String, Object> args, long waitMs, long timeoutMs) {
        java.util.concurrent.CompletableFuture<Actions.Result> out = new java.util.concurrent.CompletableFuture<>();
        if (device == null || device.isEmpty()) { out.complete(Actions.Result.fail("还没有手机常连过这台电脑（手机上打开「电脑联动」里的「接受电脑指令」）")); return out; }
        Thread t = new Thread(() -> {
            Link via = route(device);
            if (via == null) {
                for (Link l : links) if (l instanceof LanServer) ((LanServer) l).announce();
                long end = System.currentTimeMillis() + waitMs;
                long nextAnn = System.currentTimeMillis() + 1500;
                while (via == null && System.currentTimeMillis() < end) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    if (System.currentTimeMillis() >= nextAnn) {
                        for (Link l : links) if (l instanceof LanServer) ((LanServer) l).announce();
                        nextAnn = System.currentTimeMillis() + 1500;
                    }
                    via = route(device);
                }
            }
            if (via == null) { out.complete(Actions.Result.fail("手机离线（局域网里没连上 " + device + "），没发出去")); return; }
            String id = Protocol.newId();
            pending.put(id, out);
            pendingTo.put(id, device);
            Map<String, Object> body = Protocol.command(cfg.pairKey, id, cfg.deviceId, device, cmd, args == null ? new TreeMap<>() : args, System.currentTimeMillis());
            log.accept("向手机 " + device + " 下令 " + cmd + "（经" + via.name() + "）");
            via.send(Protocol.envelope(device, id, body)).whenComplete((x, e) -> {
                if (e != null) out.complete(Actions.Result.fail("发不出去：" + e.getClass().getSimpleName()));
            });
            try {
                out.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                out.complete(Actions.Result.fail("手机没有回应（超时）"));
            } finally {
                pending.remove(id);
                pendingTo.remove(id);
            }
        }, "onesay-callpeer");
        t.setDaemon(true);
        t.start();
        return out;
    }

    /** 能到这台设备的通道：局域网常连优先，其次中转。 */
    private Link route(String device) {
        for (Link l : links) if (l instanceof LanServer && ((LanServer) l).held(device)) return l;
        if (relayPeers.contains(device)) for (Link l : links) if (l instanceof RelayClient && l.isConnected()) return l;
        return null;
    }

    private void reply(Link via, String to, String re, boolean ok, String text, Map<String, Object> data, boolean unsigned) {
        String rid = Protocol.newId();
        Map<String, Object> body;
        if (unsigned) {
            body = new TreeMap<>();
            body.put("kind", "result");
            body.put("re", re);
            body.put("ok", false);
            body.put("text", text);
        } else {
            body = Protocol.result(cfg.pairKey, rid, cfg.deviceId, to, re, ok, text, data, System.currentTimeMillis());
        }
        via.send(Protocol.envelope(to, rid, body));
    }
}
