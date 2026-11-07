package com.onesay.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 命令行下令：send <目标设备> <指令> [键=值 ...]
 * 先走局域网（约 1.5 秒找不到就放弃），再走中转（若配置了）。两条都不通 = 离线，不排队。
 */
public final class Cli {
    private Cli() {}

    /** 一次连接尝试的结果。 */
    static final class Session {
        final Link link;
        final CompletableFuture<Boolean> ready = new CompletableFuture<>();
        final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        String why = "";
        Session(java.util.function.Function<Link.Listener, Link> make, Config cfg, String to, String[] idHolder) {
            Protocol.ReplayGuard guard = new Protocol.ReplayGuard();
            link = make.apply(new Link.Listener() {
                @Override
                @SuppressWarnings("unchecked")
                public void onFrame(Map<String, Object> f) {
                    String type = String.valueOf(f.get("type"));
                    String id = idHolder[0];
                    if (type.equals("devices")) {
                        boolean online = false;
                        for (Object o : (List<Object>) f.get("list")) if (to.equals(((Map<String, Object>) o).get("device"))) online = true;
                        ready.complete(online);
                    } else if (type.equals("error") && id != null && id.equals(f.get("id"))) {
                        result.complete(fail("offline".equals(f.get("code")) ? to + " 离线" : "通道报错：" + f.get("code")));
                    } else if (type.equals("error") && "auth".equals(f.get("code"))) {
                        why = "配对密钥不对，被 " + to + " 拒绝";
                        ready.complete(false);
                    } else if (type.equals("msg") && to.equals(f.get("from")) && f.get("body") instanceof Map) {
                        Map<String, Object> b = (Map<String, Object>) f.get("body");
                        if (id == null || !id.equals(b.get("re"))) return;
                        Protocol.Check c = Protocol.verify(cfg.pairKey, String.valueOf(f.get("id")), to, cfg.deviceId, b, System.currentTimeMillis(), guard);
                        result.complete(c == Protocol.Check.OK ? b : fail("结果验签失败（" + c + "）：" + b.get("text")));
                    }
                }

                @Override
                public void onState(boolean connected, String w) {
                    if (!connected) {
                        if (why.isEmpty()) why = w;
                        if (!ready.isDone()) ready.complete(false);
                        if (!result.isDone()) result.complete(fail("连接断开：" + w));
                    }
                }
            });
        }
    }

    static Map<String, Object> fail(String text) {
        Map<String, Object> m = new TreeMap<>();
        m.put("ok", false);
        m.put("text", text);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static int send(Config cfg, String to, String cmd, Map<String, Object> args, Path outDir, long timeoutSec) throws Exception {
        String id = Protocol.newId();
        String[] idHolder = new String[1];
        Session s = null;
        StringBuilder reasons = new StringBuilder();
        boolean relayDown = false;

        if (cfg.lanEnabled()) {
            LanClient[] lc = new LanClient[1];
            Session t = new Session(l -> lc[0] = new LanClient(cfg, to, l, 1500), cfg, to, idHolder);
            t.link.start();
            boolean on = waitReady(t, 8);
            if (on) {
                s = t;
                if (lc[0].found != null) {
                    String last = lc[0].found.getAddress().getHostAddress() + ":" + lc[0].found.getPort();
                    if (!last.equals(cfg.lanLast) && cfg.file != null) { cfg.lanLast = last; try { cfg.save(); } catch (Exception ignored) { } }
                }
            } else {
                t.link.stop();
                reasons.append(strip(t.why));
            }
        }
        if (s == null && cfg.relayEnabled()) {
            Session t = new Session(l -> new RelayClient(cfg, l), cfg, to, idHolder);
            t.link.start();
            boolean on = waitReady(t, 25);
            if (on) s = t;
            else {
                t.link.stop();
                if (!t.why.isEmpty()) { relayDown = true; reasons.append(reasons.length() == 0 ? "" : "；").append("中转连不上：").append(t.why); }
                else reasons.append(reasons.length() == 0 ? "" : "；").append("中转上也不在线");
            }
        }
        if (s == null) {
            System.out.println(to + " 离线" + (reasons.length() == 0 ? "" : "（" + reasons + "）"));
            return relayDown && !cfg.lanEnabled() ? 3 : 2;
        }
        try {
            idHolder[0] = id;
            Map<String, Object> body = Protocol.command(cfg.pairKey, id, cfg.deviceId, to, cmd, args, System.currentTimeMillis());
            s.link.send(Protocol.envelope(to, id, body));
            Map<String, Object> r;
            try { r = s.result.get(timeoutSec, TimeUnit.SECONDS); } catch (Exception e) { System.out.println("等待结果超时"); return 4; }
            boolean ok = Boolean.TRUE.equals(r.get("ok"));
            System.out.println((ok ? "成功：" : "失败：") + r.get("text"));
            Object data = r.get("data");
            if (data instanceof Map) {
                Map<String, Object> d = new TreeMap<>((Map<String, Object>) data);
                Object b64 = d.remove("b64");
                if (b64 instanceof String) {
                    String name = d.containsKey("name") ? Paths.get(String.valueOf(d.get("name"))).getFileName().toString()
                            : "onesay-" + to + "-" + System.currentTimeMillis() + ("image/jpeg".equals(d.get("mime")) ? ".jpg" : ".bin");
                    Files.createDirectories(outDir);
                    Path p = outDir.resolve(name);
                    Files.write(p, Base64.getDecoder().decode((String) b64));
                    System.out.println("已保存：" + p.toAbsolutePath());
                }
                if (!d.isEmpty()) System.out.println(Json.stringify(d));
            }
            return ok ? 0 : 1;
        } finally {
            s.link.stop();
        }
    }

    static boolean waitReady(Session s, long sec) {
        try { return s.ready.get(sec, TimeUnit.SECONDS); } catch (Exception e) { if (s.why.isEmpty()) s.why = "超时"; return false; }
    }

    static String strip(String w) { return w.startsWith(LanClient.OFFLINE) ? w.substring(LanClient.OFFLINE.length()) : w; }
}
