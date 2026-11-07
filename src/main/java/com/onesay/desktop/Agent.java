package com.onesay.desktop;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** 电脑端常驻：连中转、验签、执行、回签名结果。 */
public final class Agent implements RelayClient.Listener {
    private final Config cfg;
    private final Actions actions;
    private final RelayClient relay;
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
        this.relay = new RelayClient(cfg, this);
    }

    public void start() { relay.start(); }

    public void stop() { relay.stop(); exec.shutdownNow(); }

    public boolean connected() { return relay.isConnected(); }

    @Override
    public void onState(boolean connected, String why) {
        status = connected ? "在线" : "离线（" + why + "）";
        log.accept(status);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onFrame(Map<String, Object> f) {
        String type = String.valueOf(f.get("type"));
        if (type.equals("presence")) {
            log.accept("设备 " + f.get("device") + (Boolean.TRUE.equals(f.get("online")) ? " 上线" : " 离线"));
            return;
        }
        if (type.equals("devices") || type.equals("error")) {
            if (type.equals("error")) log.accept("中转报错 " + f.get("code"));
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
            // 验签失败不回签名结果（对方可能不是自己人）；只回一条无签名的拒绝便于排错
            reply(from, id, false, "拒绝：" + c, null, c == Protocol.Check.BAD_SIG || c == Protocol.Check.NO_SIG);
            return;
        }
        if (!cfg.peerAllowed(from)) {
            log.accept("拒绝未授权设备 " + from);
            reply(from, id, false, "此设备未被允许下令", null, false);
            return;
        }
        String cmd = String.valueOf(body.get("cmd"));
        Map<String, Object> args = body.get("args") instanceof Map ? (Map<String, Object>) body.get("args") : Map.of();
        log.accept("执行 " + cmd + "（来自 " + from + "）");
        exec.execute(() -> {
            Actions.Result r;
            try {
                r = cmd.equals("caps") ? Actions.Result.ok("能力清单", DesktopActions.map("caps", (List<Object>) (List<?>) actions.capabilities())) : actions.run(cmd, args);
            } catch (Throwable t) {
                r = Actions.Result.fail(cmd + " 出错：" + t.getClass().getSimpleName());
            }
            reply(from, id, r.ok, r.text, r.data, false);
        });
    }

    private void reply(String to, String re, boolean ok, String text, Map<String, Object> data, boolean unsigned) {
        String rid = Protocol.newId();
        Map<String, Object> body;
        if (unsigned) {
            body = new java.util.TreeMap<>();
            body.put("kind", "result");
            body.put("re", re);
            body.put("ok", false);
            body.put("text", text);
        } else {
            body = Protocol.result(cfg.pairKey, rid, cfg.deviceId, to, re, ok, text, data, System.currentTimeMillis());
        }
        relay.send(Protocol.envelope(to, rid, body));
    }
}
