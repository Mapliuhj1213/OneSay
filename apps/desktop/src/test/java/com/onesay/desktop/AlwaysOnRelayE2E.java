package com.onesay.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * [always-on-1120] 端到端（不在单测列表里，要真中转）：电脑端 AlwaysOnService + 真 RelayClient ↔ 本地 wrangler dev 跑的 onesay-relay（常驻分工分支）
 * ↔ 模拟手机（RelayClient，kind=phone，用配对密钥解密封内容）↔ 唤醒代理（WakeAgent，发包换成记录）。
 * 用法见 test/e2e-alwayson.sh。退出码 0 = 全过。
 */
public final class AlwaysOnRelayE2E {
    static int passed;

    static void ok(boolean c, String name) {
        if (!c) { System.out.println("FAIL " + name); System.exit(1); }
        passed++;
        System.out.println("ok   " + name);
    }

    static boolean waitFor(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.getAsBoolean()) return true; Thread.sleep(50); }
        return c.getAsBoolean();
    }

    static Config cfg(String url, String token, String id, String kind, byte[] key) {
        Config c = new Config(Path.of("unused-" + id + ".properties"));
        c.relayUrl = url; c.relayToken = token; c.deviceId = id; c.kind = kind; c.pairKey = key; c.lanPort = 0;
        return c;
    }

    static final class Pc {
        final AlwaysOnService s;
        final RelayClient rc;
        final List<String> runs;
        volatile boolean up;
        Pc(Config c, Path file, List<String> runs, List<String> notes) {
            this.runs = runs;
            s = new AlwaysOnService(file, System::currentTimeMillis, x -> { });
            s.load();
            s.pcId = c.deviceId; s.pairKey = c.pairKey; s.nodeConfigured = true;
            s.wolEnabled = true; s.wolAuto = true; s.wolMac = "3C:7C:3F:12:34:56"; s.wolBcast = "192.168.1.255";
            s.notifier = (t, x, f) -> notes.add(t);
            s.runner = (j, due, late) -> { runs.add(j.id + (late ? "/补跑" : "")); return new Object[] {AlwaysOn.St.DONE, "好了"}; };
            rc = new RelayClient(c, new Link.Listener() {
                @Override public void onFrame(Map<String, Object> f) { if (String.valueOf(f.get("type")).startsWith("aon_")) s.onFrame(f); }
                @Override public void onState(boolean c2, String why) { boolean was = up; up = c2; if (c2 && !was) s.onNodeUp(); }
            });
            s.port = new AlwaysOnService.Port() {
                @Override public boolean up() { return rc.isConnected(); }
                @Override public void send(Map<String, Object> f) { rc.send(Json.stringify(f)); }
            };
        }
    }

    public static void main(String[] a) throws Exception {
        String url = a[0], token = System.getenv("RELAY_TOKEN");
        byte[] key = Protocol.newKey();
        Path dir = Files.createTempDirectory("aon-e2e");
        Path file = dir.resolve("always-on.json");
        List<String> runs = new CopyOnWriteArrayList<>(), notes = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> phoneIn = new CopyOnWriteArrayList<>();
        List<String> sent = new CopyOnWriteArrayList<>();

        // 手机（模拟）：收常驻节点的帧
        RelayClient phone = new RelayClient(cfg(url, token, "phone-e2e", "phone", key), new Link.Listener() {
            @Override public void onFrame(Map<String, Object> f) { phoneIn.add(f); }
        });
        phone.start();
        // 唤醒代理：只许这台电脑的 MAC；发包换成记录
        WakeAgent wa = new WakeAgent("3C:7C:3F:12:34:56", System::currentTimeMillis);
        wa.sender = (mac, bc, port) -> { sent.add(WakeOnLan.fmtMac(mac) + "@" + bc + ":" + port); return 3; };
        RelayClient[] agent = new RelayClient[1];
        agent[0] = new RelayClient(cfg(url, token, "node-e2e", "other", new byte[0]), new Link.Listener() {
            @Override public void onFrame(Map<String, Object> f) { Map<String, Object> r = wa.handle(f); if (r != null) agent[0].send(Json.stringify(r)); }
            @Override public void onState(boolean c, String why) { if (c) agent[0].send(Json.stringify(WakeAgent.hello())); }
        });
        agent[0].start();

        Config pcCfg = cfg(url, token, "pc-e2e", "pc", key);
        Pc pc = new Pc(pcCfg, file, runs, notes);
        pc.rc.start();
        ok(waitFor(() -> pc.rc.isConnected() && phone.isConnected() && agent[0].isConnected(), 20000), "电脑、手机、唤醒代理都连上中转");
        Thread.sleep(500);

        long due = System.currentTimeMillis() + 4000;
        AlwaysOn.Job bk = new AlwaysOn.Job();
        bk.id = "bk"; bk.title = "备份照片（端到端）"; bk.action = AlwaysOn.Action.SHORTCUT; bk.target = "备份照片"; bk.where = AlwaysOn.Where.NODE; bk.at = due; bk.wake = true;
        ok(pc.s.add(bk) == null, "电脑加一条需电脑的常驻任务（允许自动唤醒）");
        AlwaysOn.Job rm = new AlwaysOn.Job();
        rm.id = "rm"; rm.title = "交房租"; rm.text = "今天 15 号"; rm.action = AlwaysOn.Action.NOTIFY; rm.where = AlwaysOn.Where.NODE; rm.at = due;
        ok(pc.s.add(rm) == null, "再加一条放常驻节点的提醒");
        Thread.sleep(800);

        // 关机：断开中转、停掉服务
        pc.s.stop();
        pc.rc.stop();
        ok(waitFor(() -> !pc.rc.isConnected(), 3000), "电脑下线");

        ok(waitFor(() -> phoneIn.stream().anyMatch(f -> "aon_fire".equals(f.get("type")) && "rm".equals(f.get("id"))), 12000), "电脑关着：提醒到点送到手机");
        Map<String, Object> fire = phoneIn.stream().filter(f -> "aon_fire".equals(f.get("type")) && "rm".equals(f.get("id"))).findFirst().get();
        Map<String, Object> open = AlwaysOn.open(key, "pc-e2e", "rm", String.valueOf(fire.get("sealed")));
        ok(open != null && "交房租".equals(open.get("title")) && "今天 15 号".equals(open.get("text")), "手机用配对密钥解开密封内容（中转只转了密文）");
        ok(waitFor(() -> !sent.isEmpty(), 8000), "需电脑的任务到点电脑离线 → 唤醒代理发了魔术包");
        ok(sent.get(0).equals("3C:7C:3F:12:34:56@192.168.1.255:9"), "魔术包发给登记的 MAC 和广播地址");
        ok(waitFor(() -> phoneIn.stream().anyMatch(f -> "aon_notice".equals(f.get("type")) && "waking".equals(f.get("code"))), 5000), "手机收到「已唤醒」");
        ok(waitFor(() -> phoneIn.stream().anyMatch(f -> "aon_notice".equals(f.get("type")) && "wake_sent".equals(f.get("code"))), 5000), "手机收到唤醒代理的回执");
        ok(runs.isEmpty(), "关机期间电脑什么也没跑");

        // 开机：新服务读同一个文件，先本机补一轮，再连中转收排队
        Thread.sleep(1500);
        List<String> notes2 = new ArrayList<>();
        Pc pc2 = new Pc(pcCfg, file, runs, notes2);
        pc2.s.tick();
        pc2.rc.start();
        ok(waitFor(() -> phoneIn.stream().anyMatch(f -> "aon_notice".equals(f.get("type")) && "bk".equals(f.get("id")) && "done".equals(f.get("code"))), 15000), "电脑上线补跑后 ack，手机收到「完成」");
        ok(runs.stream().filter(r -> r.startsWith("bk")).count() == 1, "需电脑的任务只跑一次（本机计时和节点排队去重）：" + runs);
        // 提醒：电脑在准时窗口（90 秒）内开机会在电脑上也提醒一次（和电脑在线时一样两边都提醒），晚于窗口则只留手机那次（单测 nodeReminderNotRepeatedByPcAfterBoot）
        ok(runs.stream().filter(r -> r.startsWith("rm")).count() <= 1, "提醒在电脑上最多一次");
        System.out.println("\n全部通过：" + passed + " 项");
        System.exit(0);
    }
}
