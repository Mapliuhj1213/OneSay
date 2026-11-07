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

    public Agent(Config cfg, Actions actions, Consumer<String> log) {
        this.cfg = cfg;
        this.actions = actions;
        this.log = log;
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
            log.accept("设备 " + f.get("device") + (Boolean.TRUE.equals(f.get("online")) ? " 上线" : " 离线") + "（" + via.name() + "）");
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
        if (!"cmd".equals(body.get("kind"))) return; // 只执行指令；结果类消息忽略

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
        exec.execute(() -> {
            Actions.Result r;
            try {
                r = cmd.equals("caps") ? Actions.Result.ok("能力清单", DesktopActions.map("caps", (List<Object>) (List<?>) actions.capabilities())) : actions.run(cmd, args);
            } catch (Throwable t) {
                r = Actions.Result.fail(cmd + " 出错：" + t.getClass().getSimpleName());
            }
            reply(via, from, id, r.ok, r.text, r.data, false);
        });
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
