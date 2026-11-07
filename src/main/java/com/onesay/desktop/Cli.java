package com.onesay.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 命令行下令：java -jar onesay-desktop.jar send <目标设备> <指令> [键=值 ...] */
public final class Cli {
    private Cli() {}

    @SuppressWarnings("unchecked")
    public static int send(Config cfg, String to, String cmd, Map<String, Object> args, Path outDir, long timeoutSec) throws Exception {
        String id = Protocol.newId();
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        Protocol.ReplayGuard guard = new Protocol.ReplayGuard();
        RelayClient[] holder = new RelayClient[1];
        holder[0] = new RelayClient(cfg, new RelayClient.Listener() {
            @Override
            public void onFrame(Map<String, Object> f) {
                String type = String.valueOf(f.get("type"));
                if (type.equals("devices")) {
                    boolean online = false;
                    for (Object o : (java.util.List<Object>) f.get("list")) if (to.equals(((Map<String, Object>) o).get("device"))) online = true;
                    ready.complete(online);
                } else if (type.equals("error") && id.equals(f.get("id"))) {
                    Map<String, Object> m = new TreeMap<>();
                    m.put("ok", false);
                    m.put("text", "offline".equals(f.get("code")) ? to + " 离线" : "中转报错：" + f.get("code"));
                    result.complete(m);
                } else if (type.equals("msg") && to.equals(f.get("from")) && f.get("body") instanceof Map) {
                    Map<String, Object> b = (Map<String, Object>) f.get("body");
                    if (!id.equals(b.get("re"))) return;
                    Protocol.Check c = Protocol.verify(cfg.pairKey, String.valueOf(f.get("id")), to, cfg.deviceId, b, System.currentTimeMillis(), guard);
                    if (c != Protocol.Check.OK) {
                        Map<String, Object> m = new TreeMap<>();
                        m.put("ok", false);
                        m.put("text", "结果验签失败（" + c + "）：" + b.get("text"));
                        result.complete(m);
                    } else result.complete(b);
                }
            }

            @Override
            public void onState(boolean connected, String why) {
                if (!connected && !ready.isDone()) ready.completeExceptionally(new IllegalStateException(why));
            }
        });
        RelayClient rc = holder[0];
        rc.start();
        try {
            boolean online;
            try { online = ready.get(25, TimeUnit.SECONDS); } catch (Exception e) {
                System.out.println("连不上中转：" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                return 3;
            }
            if (!online) { System.out.println(to + " 离线"); return 2; }
            Map<String, Object> body = Protocol.command(cfg.pairKey, id, cfg.deviceId, to, cmd, args, System.currentTimeMillis());
            rc.send(Protocol.envelope(to, id, body));
            Map<String, Object> r;
            try { r = result.get(timeoutSec, TimeUnit.SECONDS); } catch (Exception e) { System.out.println("等待结果超时"); return 4; }
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
            rc.stop();
        }
    }
}
