package com.onesay.desktop;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * [always-on-1120] 局域网唤醒代理：放在家里常开的小机器上（旧笔记本、NAS、树莓派…，装 Java 17 即可），
 * 连中转并声明自己能发魔术包；常驻节点要叫醒电脑时把 {@code wake_request} 发给它，它在局域网里广播魔术包，回 {@code wake_result}。
 *
 * <p>最小权限：它不拿配对密钥，只拿中转令牌；只替本机配置 {@code wake_allow} 里列出的 MAC 发包（空 = 一个都不发），
 * 每个 MAC 每小时最多 6 次；广播地址只收 IPv4 字面量，端口只用 7 / 9。它不执行任何别的指令。
 *
 * <p>用法：{@code onesay --config wake.properties wake-agent}，配置里写 relay_url、relay_token、device_id（如 node-nas）、
 * wake_allow=AA:BB:CC:DD:EE:FF。手机端以后也可以当唤醒代理（同一套帧，见 ALWAYS-ON.md 的手机接口）。
 */
public final class WakeAgent {
    public interface Sender { int send(byte[] mac, String bcast, int port) throws Exception; }

    public static final int PER_HOUR = 6;
    private final Set<String> allow = new LinkedHashSet<>();
    private final Map<String, Deque<Long>> hist = new TreeMap<>();
    private final LongSupplier clock;
    public volatile Sender sender = (mac, bc, port) -> WakeOnLan.send(mac, bc, port, 3);

    public WakeAgent(String allowList, LongSupplier clock) {
        for (String s : String.valueOf(allowList).split("[,，;；\\s]+")) { String m = WakeOnLan.normMac(s); if (!m.isEmpty()) allow.add(m); }
        this.clock = clock;
    }

    public Set<String> allowed() { return allow; }

    public static Map<String, Object> hello() {
        Map<String, Object> f = new TreeMap<>();
        f.put("type", "aon_hello");
        f.put("roles", java.util.List.of("wake_agent"));
        return f;
    }

    /** 处理一条中转帧；是 wake_request 就返回要回的 wake_result，否则 null。 */
    public synchronized Map<String, Object> handle(Map<String, Object> f) {
        if (!"wake_request".equals(String.valueOf(f.get("type")))) return null;
        Map<String, Object> r = new TreeMap<>();
        r.put("type", "wake_result");
        r.put("req", String.valueOf(f.get("req")));
        String mac = WakeOnLan.normMac(String.valueOf(f.get("mac")));
        String bc = String.valueOf(f.getOrDefault("bcast", "255.255.255.255"));
        long port = AlwaysOn.num(f.get("port"));
        if (port != 7) port = 9;
        if (mac.isEmpty()) return fail(r, "MAC 不对");
        if (!allow.contains(mac)) return fail(r, "这台唤醒代理没登记允许唤醒 " + mac + "（配置 wake_allow）");
        if (!WakeOnLan.ipv4(bc)) bc = "255.255.255.255";
        long now = clock.getAsLong();
        Deque<Long> h = hist.computeIfAbsent(mac, k -> new ArrayDeque<>());
        while (!h.isEmpty() && now - h.peekFirst() > 3600_000L) h.removeFirst();
        if (h.size() >= PER_HOUR) return fail(r, "一小时内唤醒太多次，先不发");
        try {
            int n = sender.send(WakeOnLan.parseMac(mac), bc, (int) port);
            if (!"255.255.255.255".equals(bc)) n += sender.send(WakeOnLan.parseMac(mac), "255.255.255.255", (int) port);
            h.addLast(now);
            r.put("ok", true);
            r.put("text", "已发魔术包 ×" + n + "（" + bc + ":" + port + "）");
        } catch (Exception e) {
            return fail(r, "发包失败：" + e.getClass().getSimpleName());
        }
        return r;
    }

    private static Map<String, Object> fail(Map<String, Object> r, String why) { r.put("ok", false); r.put("text", why); return r; }

    /** 命令行：连中转，常驻。 */
    public static void run(Config cfg, Consumer<String> log) throws Exception {
        if (!cfg.relayEnabled()) throw new IllegalStateException("唤醒代理要连中转：配置里写 relay_url 和 relay_token");
        if (cfg.deviceId.isEmpty()) throw new IllegalStateException("配置里写 device_id（如 node-nas）");
        cfg.kind = "other";
        WakeAgent a = new WakeAgent(cfg.wakeAllow, System::currentTimeMillis);
        if (a.allowed().isEmpty()) log.accept("提醒：wake_allow 是空的，收到唤醒请求会一律拒绝");
        RelayClient[] rc = new RelayClient[1];
        rc[0] = new RelayClient(cfg, new Link.Listener() {
            @Override public void onFrame(Map<String, Object> f) {
                Map<String, Object> r = a.handle(f);
                if (r != null) { log.accept("唤醒请求 → " + r.get("text")); rc[0].send(Json.stringify(r)); }
            }
            @Override public void onState(boolean c, String why) {
                log.accept((c ? "已连中转" : "中转断开") + "（" + why + "）");
                if (c) rc[0].send(Json.stringify(hello()));
            }
        });
        rc[0].start();
        log.accept("唤醒代理已启动：允许 " + a.allowed());
        Thread.currentThread().join();
    }
}
