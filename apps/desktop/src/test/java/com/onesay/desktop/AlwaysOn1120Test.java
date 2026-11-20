package com.onesay.desktop;

import com.onesay.desktop.AlwaysOn.Action;
import com.onesay.desktop.AlwaysOn.Job;
import com.onesay.desktop.AlwaysOn.Missed;
import com.onesay.desktop.AlwaysOn.St;
import com.onesay.desktop.AlwaysOn.Where;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * [always-on-1120] 常驻分工：本机计时、离线补跑 / 过期、去重、和常驻节点的帧、密封、网络唤醒、唤醒代理、设置页。
 * 「常驻节点」用 {@link SimNode} 在内存里模拟（帧格式和 onesay-relay 的 src/aon.js 一样；真中转另有 test/run_aon.sh 冒烟），
 * 「离线电脑」= 把服务停掉、时钟往前拨、再用同一个文件新建一个服务（像关机后重新开机）。
 */
public class AlwaysOn1120Test {
    static final long MIN = 60_000, H = 3600_000, DAY = 24 * H;
    static final long T0 = 1_795_000_000_000L; // 某天的某个时刻（不依赖真时间）

    static Job job(String id, Action a, Where w, long at, long every) {
        Job j = new Job();
        j.id = id; j.title = "任务" + id; j.action = a; j.where = w; j.at = at; j.every = every; j.target = "备份照片";
        return j;
    }

    // ------------------------------------------------------------------ 纯逻辑

    @Test public void nextDueAndOccurrences() {
        Job j = job("a", Action.NOTIFY, Where.LOCAL, T0, DAY);
        assertEquals(T0, AlwaysOn.nextDue(j, T0 - 1));
        assertEquals(T0 + DAY, AlwaysOn.nextDue(j, T0));
        assertEquals(List.of(T0, T0 + DAY, T0 + 2 * DAY), AlwaysOn.occurrences(j, T0 - 1, T0 + 2 * DAY + 5, 10));
        assertEquals("最多取最晚的 limit 个", List.of(T0 + 2 * DAY), AlwaysOn.occurrences(j, T0 - 1, T0 + 2 * DAY + 5, 1));
        Job once = job("b", Action.NOTIFY, Where.LOCAL, T0, 0);
        assertEquals(-1, AlwaysOn.nextDue(once, T0));
        assertTrue(AlwaysOn.occurrences(once, T0, T0 + DAY, 5).isEmpty());
    }

    @Test public void decideOnTimeCatchUpExpire() {
        Job j = job("a", Action.SHORTCUT, Where.NODE, T0, 0);
        assertEquals(St.RUNNING, AlwaysOn.decide(j, T0, T0 + 30_000));
        assertEquals("宽限 2 小时内补跑", St.RUNNING, AlwaysOn.decide(j, T0, T0 + 90 * MIN));
        assertEquals(St.EXPIRED, AlwaysOn.decide(j, T0, T0 + 3 * H));
        j.missed = Missed.EXPIRE;
        assertEquals("过期不跑：晚了就过期", St.EXPIRED, AlwaysOn.decide(j, T0, T0 + 10 * MIN));
        assertEquals("准时的照跑", St.RUNNING, AlwaysOn.decide(j, T0, T0 + 30_000));
    }

    @Test public void planMergesMissedRepeats() {
        Job j = job("a", Action.SHORTCUT, Where.LOCAL, T0, DAY);
        j.seen = T0 - 1;
        List<Object[]> p = AlwaysOn.plan(j, T0 + 2 * DAY + 30 * MIN); // 关机三天，开机时最近一次晚了半小时
        assertEquals(3, p.size());
        assertEquals(St.EXPIRED, p.get(0)[1]);
        assertEquals(St.EXPIRED, p.get(1)[1]);
        assertEquals("错过多次只补最近一次", St.RUNNING, p.get(2)[1]);
        Job fresh = job("b", Action.NOTIFY, Where.LOCAL, T0 - DAY, DAY);
        assertTrue("新任务不回头补以前的", AlwaysOn.plan(fresh, T0 - 1).isEmpty());
        j.enabled = false;
        assertTrue(AlwaysOn.plan(j, T0 + 3 * DAY).isEmpty());
    }

    @Test public void validateRules() {
        Job w = job("w", Action.WATCH, Where.LOCAL, T0, 0);
        w.url = "https://example.com/t"; w.contains = "有票";
        assertNull(AlwaysOn.validate(w, true));
        assertEquals("查网页强制常驻节点", Where.NODE, w.where);
        assertEquals(AlwaysOn.MIN_WATCH_MS, w.every);
        w.every = 5 * MIN;
        assertNotNull("查网页最短 15 分钟", AlwaysOn.validate(w, true));
        Job w2 = job("w2", Action.WATCH, Where.NODE, T0, 0);
        w2.url = "https://u:p@example.com/";
        assertNotNull("网址不许带账号", AlwaysOn.validate(w2, true));
        Job n = job("n", Action.NOTIFY, Where.NODE, T0, 0);
        assertNotNull("没配中转不能放常驻节点", AlwaysOn.validate(n, false));
        Job l = job("l", Action.SHORTCUT, Where.LOCAL, T0, 0);
        l.wake = true;
        assertNull(AlwaysOn.validate(l, false));
        assertFalse("电脑本地的任务谈不上唤醒", l.wake);
        Job bad = job("x", Action.SHORTCUT, Where.LOCAL, T0, 30_000);
        assertNotNull("重复间隔至少 1 分钟", AlwaysOn.validate(bad, false));
        Job sc = job("y", Action.SHORTCUT, Where.LOCAL, T0, 0);
        sc.target = "..\\evil";
        assertNotNull("快捷指令名不许带路径", AlwaysOn.validate(sc, false));
        Job g = job("g", Action.NOTIFY, Where.LOCAL, T0, 0);
        g.grace = 30 * DAY;
        assertNull(AlwaysOn.validate(g, false));
        assertEquals("宽限封顶 7 天", AlwaysOn.MAX_GRACE_MS, g.grace);
    }

    @Test public void sealHidesContentAndBindsJob() {
        byte[] key = Protocol.newKey();
        Map<String, Object> p = new TreeMap<>();
        p.put("title", "交房租");
        p.put("text", "卡号尾号 1234");
        String s = AlwaysOn.seal(key, "pc-home", "j1", p);
        assertTrue(s.matches("[A-Za-z0-9_-]+"));
        assertFalse(new String(java.util.Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8).contains("交房租"));
        assertEquals("交房租", AlwaysOn.open(key, "pc-home", "j1", s).get("title"));
        assertNull("换了任务解不开", AlwaysOn.open(key, "pc-home", "j2", s));
        assertNull("换了电脑解不开", AlwaysOn.open(key, "pc-other", "j1", s));
        assertNull("钥匙不对解不开", AlwaysOn.open(Protocol.newKey(), "pc-home", "j1", s));
        char[] c = s.toCharArray();
        c[20] = c[20] == 'A' ? 'B' : 'A';
        assertNull("被改过解不开", AlwaysOn.open(key, "pc-home", "j1", new String(c)));
        assertNotEquals("每次随机数不同", s, AlwaysOn.seal(key, "pc-home", "j1", p));

        Job j = job("j1", Action.SHORTCUT, Where.NODE, T0, DAY);
        j.title = "备份家里的照片";
        j.wake = true;
        Map<String, Object> nj = AlwaysOn.nodeJob(j, key, "pc-home");
        String wire = Json.stringify(nj);
        assertFalse("交给节点的没有明文标题", wire.contains("备份家里的照片"));
        assertFalse("也没有快捷指令名", wire.contains("备份照片"));
        assertEquals("pc", nj.get("kind"));
        assertEquals(Boolean.TRUE, nj.get("wake"));
        assertEquals("备份家里的照片", AlwaysOn.open(key, "pc-home", "j1", String.valueOf(nj.get("sealed"))).get("title"));
        j.missed = Missed.EXPIRE;
        assertEquals("过期不跑：节点的宽限只给准时那一会", AlwaysOn.ON_TIME_MS, ((Number) AlwaysOn.nodeJob(j, key, "pc-home").get("grace")).longValue());
    }

    // ------------------------------------------------------------------ 模拟常驻节点 + 离线电脑

    /** 内存里的常驻节点：照 onesay-relay src/aon.js 的规则（只做需要的那部分）。 */
    static final class SimNode {
        final Map<String, Map<String, Object>> jobs = new TreeMap<>();
        final Map<String, Map<String, Object>> queue = new TreeMap<>();
        final List<Map<String, Object>> toPc = new ArrayList<>(), acks = new ArrayList<>(), phone = new ArrayList<>();
        Map<String, Object> wake = new TreeMap<>();
        boolean pcOnline;
        AlwaysOnService pc;

        AlwaysOnService.Port portFor() {
            return new AlwaysOnService.Port() {
                @Override public boolean up() { return pcOnline; }
                @Override @SuppressWarnings("unchecked") public void send(Map<String, Object> f) {
                    switch (String.valueOf(f.get("type"))) {
                        case "aon_sync": jobs.clear(); for (Object o : (List<Object>) f.get("jobs")) { Map<String, Object> m = new TreeMap<>((Map<String, Object>) o); m.put("next", m.get("at")); jobs.put(String.valueOf(m.get("id")), m); } break;
                        case "aon_del": jobs.remove(String.valueOf(f.get("id"))); break;
                        case "aon_ack": acks.add(f); queue.remove(f.get("id") + "@" + ((Number) f.get("due")).longValue()); phone.add(Map.of("code", f.get("result"), "id", f.get("id"))); break;
                        case "aon_wake_cfg": wake = new TreeMap<>(f); break;
                        default: break;
                    }
                }
            };
        }

        /** 时钟走到 now：到点的按规则处理。 */
        void tick(long now) {
            for (Map<String, Object> j : jobs.values()) {
                long next = ((Number) j.get("next")).longValue();
                if (next < 0 || next > now) continue;
                long every = ((Number) j.get("every")).longValue();
                j.put("next", every > 0 ? next + every : -1L);
                String kind = String.valueOf(j.get("kind"));
                Map<String, Object> fire = new TreeMap<>(Map.of("type", "aon_fire", "id", j.get("id"), "due", next, "kind", kind, "sealed", j.get("sealed")));
                if (kind.equals("pc")) {
                    Map<String, Object> it = new TreeMap<>(Map.of("id", j.get("id"), "due", next, "kind", "pc", "sealed", j.get("sealed")));
                    if (pcOnline) { it.put("state", "running"); queue.put(j.get("id") + "@" + next, it); toPc.add(fire); pc.onFrame(fire); }
                    else {
                        boolean w = Boolean.TRUE.equals(j.get("wake")) && Boolean.TRUE.equals(wake.get("auto"));
                        it.put("state", w ? "waking" : "queued");
                        queue.put(j.get("id") + "@" + next, it);
                        phone.add(Map.of("code", w ? "waking" : "queued", "id", j.get("id")));
                    }
                } else {
                    phone.add(Map.of("code", "fire", "id", j.get("id")));
                    if (pcOnline) pc.onFrame(fire);
                }
            }
        }

        /** 电脑连上：推排队的。 */
        void pcConnects(AlwaysOnService s) {
            pc = s;
            pcOnline = true;
            s.onNodeUp();
            if (queue.isEmpty()) return;
            List<Object> items = new ArrayList<>(queue.values());
            Map<String, Object> f = new TreeMap<>();
            f.put("type", "aon_queue");
            f.put("items", items);
            s.onFrame(f);
        }
    }

    static final class Harness {
        final AtomicLong now = new AtomicLong(T0 - 10 * MIN);
        final List<String> runs = new ArrayList<>(), notes = new ArrayList<>();
        final Path file;
        final SimNode node = new SimNode();
        final byte[] key = Protocol.newKey();
        Harness() throws Exception { file = Files.createTempDirectory("aon").resolve("always-on.json"); }

        AlwaysOnService boot() {
            AlwaysOnService s = new AlwaysOnService(file, now::get, x -> { });
            s.load();
            s.pcId = "pc-home";
            s.pairKey = key;
            s.nodeConfigured = true;
            s.port = node.portFor();
            s.notifier = (t, x, f) -> notes.add(t);
            s.runner = (j, due, late) -> { runs.add(j.id + "@" + due + (late ? "/补跑" : "")); return new Object[] {St.DONE, "好了"}; };
            node.pc = s;
            return s;
        }
    }

    @Test public void offlinePcQueuedThenCatchUpOnce() throws Exception {
        Harness h = new Harness();
        AlwaysOnService s = h.boot();
        h.node.pcConnects(s);
        s.wolEnabled = true; s.wolAuto = true; s.wolMac = "3c-7c-3f-12-34-56"; s.wolBcast = "192.168.1.255";
        Job j = job("bk", Action.SHORTCUT, Where.NODE, T0, DAY);
        j.wake = true;
        assertNull(s.add(j));
        assertTrue("登记到节点", h.node.jobs.containsKey(j.id));
        assertEquals("唤醒设置交给节点（MAC 规整）", "3C:7C:3F:12:34:56", h.node.wake.get("mac"));
        assertEquals(Boolean.TRUE, h.node.wake.get("auto"));

        // 关机：节点照常计时，到点电脑不在 → 排队（预先授权了 → 已唤醒）
        s.stop();
        h.node.pcOnline = false;
        h.now.set(T0 + 40 * MIN);
        h.node.tick(h.now.get());
        assertEquals("waking", h.node.queue.get("bk@" + T0).get("state"));
        assertTrue(h.node.phone.contains(Map.of("code", "waking", "id", "bk")));
        assertTrue("关机期间电脑什么也没跑", h.runs.isEmpty());

        // 开机：先本机补一轮（发现错过的），再连上节点收排队 → 只跑一次，回 ack
        AlwaysOnService s2 = h.boot();
        s2.tick();
        assertEquals(List.of("bk@" + T0 + "/补跑"), h.runs);
        h.node.pcConnects(s2);
        assertEquals("节点排队的那次是重复的，不再跑", 1, h.runs.size());
        assertEquals(1, h.node.acks.size());
        assertEquals("done", h.node.acks.get(0).get("result"));
        assertTrue("节点队列清空", h.node.queue.isEmpty());
        assertTrue(h.notes.stream().anyMatch(t -> t.startsWith("补跑完成：")));
        Map<String, Object> v = s2.view();
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) v.get("items");
        assertEquals("done", items.get(0).get("state"));
        assertEquals("已完成", items.get(0).get("state_zh"));
    }

    @Test public void queueArrivesBeforeLocalTickStillOnce() throws Exception {
        Harness h = new Harness();
        AlwaysOnService s = h.boot();
        h.node.pcConnects(s);
        assertNull(s.add(job("bk", Action.SHORTCUT, Where.NODE, T0, 0)));
        s.stop();
        h.node.pcOnline = false;
        h.now.set(T0 + 20 * MIN);
        h.node.tick(h.now.get());
        assertEquals("queued", h.node.queue.get("bk@" + T0).get("state"));
        AlwaysOnService s2 = h.boot();
        h.node.pcConnects(s2);   // 这次节点的队列先到
        s2.tick();               // 本机计时后到
        assertEquals(1, h.runs.size());
        assertEquals(1, h.node.acks.size());
    }

    @Test public void tooLateExpiresWithNotice() throws Exception {
        Harness h = new Harness();
        AlwaysOnService s = h.boot();
        h.node.pcConnects(s);
        assertNull(s.add(job("bk", Action.SHORTCUT, Where.NODE, T0, 0)));
        s.stop();
        h.node.pcOnline = false;
        h.now.set(T0 + 1);
        h.node.tick(h.now.get());
        h.now.set(T0 + 5 * H); // 宽限 2 小时，开机时已经过了
        AlwaysOnService s2 = h.boot();
        h.node.pcConnects(s2);
        s2.tick();
        assertTrue("过期不跑", h.runs.isEmpty());
        assertEquals("expired", h.node.acks.get(0).get("result"));
        assertTrue(h.notes.stream().anyMatch(t -> t.startsWith("已过期：")));
    }

    @Test public void expirePolicySkipsEvenShortlyLate() throws Exception {
        Harness h = new Harness();
        AlwaysOnService s = h.boot();
        Job j = job("lc", Action.SHORTCUT, Where.LOCAL, T0, 0);
        j.missed = Missed.EXPIRE;
        assertNull(s.add(j));
        assertTrue("电脑本地的任务不交给节点", h.node.jobs.isEmpty());
        s.stop();
        h.now.set(T0 + 10 * MIN);
        AlwaysOnService s2 = h.boot();
        s2.tick();
        assertTrue(h.runs.isEmpty());
        assertTrue(h.notes.stream().anyMatch(t -> t.startsWith("已过期：")));
        s2.tick();
        assertEquals("同一次只通知一次", 1, h.notes.stream().filter(t -> t.startsWith("已过期：")).count());
    }

    @Test public void onlineFireAndLocalTickDedupe() throws Exception {
        Harness h = new Harness();
        AlwaysOnService s = h.boot();
        h.node.pcConnects(s);
        assertNull(s.add(job("bk", Action.SHORTCUT, Where.NODE, T0, DAY)));
        h.now.set(T0 + 5_000);
        h.node.tick(h.now.get()); // 节点转来到点（电脑在线）
        s.tick();                 // 本机也到点
        assertEquals("准时、只跑一次", List.of("bk@" + T0), h.runs);
        assertEquals(1, h.node.acks.size());
        assertTrue("准时的不发补跑通知", h.notes.isEmpty());
        h.now.set(T0 + DAY + 5_000);
        s.tick();
        h.node.tick(h.now.get());
        assertEquals("第二天又跑一次", 2, h.runs.size());
    }

    @Test public void nodeReminderNotRepeatedByPcAfterBoot() throws Exception {
        Harness h = new Harness();
        AlwaysOnService s = h.boot();
        h.node.pcConnects(s);
        Job j = job("rent", Action.NOTIFY, Where.NODE, T0, 0);
        j.text = "交房租";
        assertNull(s.add(j));
        s.stop();
        h.node.pcOnline = false;
        h.now.set(T0 + 1);
        h.node.tick(h.now.get());
        assertTrue("电脑关着：节点把提醒送到手机", h.node.phone.contains(Map.of("code", "fire", "id", "rent")));
        h.now.set(T0 + 30 * MIN);
        AlwaysOnService s2 = h.boot();
        s2.tick();
        assertTrue("手机已经提醒过：电脑开机后不再补提醒", h.runs.isEmpty());
    }

    @Test public void localReminderCatchesUpAfterBoot() throws Exception {
        Harness h = new Harness();
        AlwaysOnService s = h.boot();
        assertNull(s.add(job("tea", Action.NOTIFY, Where.LOCAL, T0, 0)));
        s.stop();
        h.now.set(T0 + 30 * MIN);
        AlwaysOnService s2 = h.boot();
        s2.tick();
        assertEquals("电脑本地的提醒：开机后补一次", List.of("tea@" + T0 + "/补跑"), h.runs);
    }

    @Test public void unknownQueuedJobAckedAsDupAndWatchHitNotifies() throws Exception {
        Harness h = new Harness();
        AlwaysOnService s = h.boot();
        h.node.pcOnline = true;
        Map<String, Object> q = new TreeMap<>();
        q.put("type", "aon_queue");
        q.put("items", List.of(Map.of("id", "gone", "due", T0, "state", "queued", "kind", "pc")));
        s.onFrame(q);
        assertEquals("电脑上已经删了的：回 dup，节点清掉", "dup", h.node.acks.get(0).get("result"));

        Job w = job("tk", Action.WATCH, Where.NODE, T0, 0);
        w.url = "https://example.com/t"; w.contains = "有票";
        assertNull(s.add(w));
        Map<String, Object> f = new TreeMap<>(Map.of("type", "aon_fire", "id", "tk", "due", T0 + 15 * MIN, "kind", "watch", "hit", Map.of("matched", true)));
        s.onFrame(f);
        s.onFrame(f);
        assertEquals(1, h.notes.stream().filter(t -> t.equals("网页有变化：任务tk")).count());
        assertTrue("查网页不在电脑上执行", h.runs.isEmpty());
    }

    @Test public void needsUserAndFailureNotified() throws Exception {
        Harness h = new Harness();
        AlwaysOnService s = h.boot();
        s.runner = (j, due, late) -> new Object[] {St.NEEDS_USER, "没人批准"};
        assertNull(s.add(job("sc", Action.SHORTCUT, Where.LOCAL, T0, 0)));
        h.now.set(T0 + 1_000);
        s.tick();
        assertTrue(h.notes.contains("需要你操作：任务sc"));
    }

    @Test public void ledgerSurvivesRestartAndCaps() throws Exception {
        Harness h = new Harness();
        AlwaysOnService s = h.boot();
        assertNull(s.add(job("a", Action.NOTIFY, Where.LOCAL, T0, 0)));
        h.now.set(T0 + 1_000);
        s.tick();
        assertEquals(1, h.runs.size());
        AlwaysOnService s2 = h.boot();
        s2.occur(s2.find("a"), T0, St.RUNNING, "常驻节点", null, false);
        assertEquals("重启后还记得做过", 1, h.runs.size());
        AlwaysOn.Ledger l = new AlwaysOn.Ledger(3);
        for (int i = 0; i < 5; i++) assertTrue(l.claim("k" + i, "done"));
        assertEquals(3, l.size());
        assertFalse(l.has("k0"));
        assertFalse(l.claim("k4", "x"));
    }

    @Test public void removeAndToggleTellNode() throws Exception {
        Harness h = new Harness();
        AlwaysOnService s = h.boot();
        h.node.pcConnects(s);
        assertNull(s.add(job("a", Action.NOTIFY, Where.NODE, T0, DAY)));
        assertNull(s.toggle("a", false));
        assertFalse("暂停的不交给节点", h.node.jobs.containsKey("a"));
        assertNull(s.toggle("a", true));
        assertTrue(h.node.jobs.containsKey("a"));
        assertNull(s.remove("a"));
        assertFalse(h.node.jobs.containsKey("a"));
        assertNotNull(s.remove("a"));
        Job bad = job("n", Action.NOTIFY, Where.NODE, T0, 0);
        s.nodeConfigured = false;
        assertNotNull(s.add(bad));
    }

    @Test public void lanPeersCannotSpeakForTheNode() throws Exception {
        Path dir = Files.createTempDirectory("aon-agent");
        Config cfg = new Config(dir.resolve("c.properties"));
        cfg.lanPort = 0;
        cfg.deviceId = "pc-home";
        cfg.pairKey = Protocol.newKey();
        Agent a = new Agent(cfg, new Main.TestActions(), x -> { });
        List<Map<String, Object>> got = new ArrayList<>();
        a.nodeHook = got::add;
        Link lan = new Link() {
            public void start() { } public void stop() { } public boolean isConnected() { return true; }
            public java.util.concurrent.CompletableFuture<?> send(String t) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
            public String name() { return "局域网"; }
        };
        Map<String, Object> f = new TreeMap<>(Map.of("type", "aon_queue", "items", List.of()));
        a.handle(f, lan);
        assertTrue("局域网对端发的 aon_* 不认", got.isEmpty());
        cfg.relayUrl = "https://relay.example.com";
        cfg.relayToken = "t".repeat(32);
        a.handle(f, new RelayClient(cfg, new Link.Listener() { public void onFrame(Map<String, Object> x) { } }));
        assertEquals(1, got.size());
    }

    // ------------------------------------------------------------------ 网络唤醒

    @Test public void magicPacketAndMacParsing() throws Exception {
        byte[] mac = WakeOnLan.parseMac("3c-7c-3f-12-34-56");
        assertEquals("3C:7C:3F:12:34:56", WakeOnLan.fmtMac(mac));
        assertEquals("3C:7C:3F:12:34:56", WakeOnLan.normMac("3c7c.3f12.3456"));
        assertNull(WakeOnLan.parseMac("00:00:00:00:00:00"));
        assertNull(WakeOnLan.parseMac("FF:FF:FF:FF:FF:FF"));
        assertNull("组播地址不收", WakeOnLan.parseMac("01:00:5E:00:00:01"));
        assertNull(WakeOnLan.parseMac("3C:7C:3F:12:34"));
        byte[] p = WakeOnLan.magicPacket(mac);
        assertEquals(102, p.length);
        for (int i = 0; i < 6; i++) assertEquals((byte) 0xFF, p[i]);
        assertEquals(mac[0], p[6 + 15 * 6]);
        assertTrue(WakeOnLan.isMagicFor(p, p.length, mac));
        assertFalse(WakeOnLan.isMagicFor(p, p.length, WakeOnLan.parseMac("3C:7C:3F:12:34:57")));
        assertEquals("192.168.1.255", WakeOnLan.broadcastOf("192.168.1.20", 24));
        assertEquals("10.0.255.255", WakeOnLan.broadcastOf("10.0.3.4", 16));
        assertTrue(WakeOnLan.ipv4("255.255.255.255"));
        assertFalse(WakeOnLan.ipv4("example.com"));
        assertFalse(WakeOnLan.ipv4("1.2.3.256"));

        try (DatagramSocket rx = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            rx.setSoTimeout(3000);
            int n = WakeOnLan.send(mac, "127.0.0.1", rx.getLocalPort(), 2);
            assertEquals(2, n);
            DatagramPacket d = new DatagramPacket(new byte[200], 200);
            rx.receive(d);
            assertTrue("本机收到的就是魔术包", WakeOnLan.isMagicFor(d.getData(), d.getLength(), mac));
        }
        try { WakeOnLan.send(mac, "nas.local", 9, 1); fail("广播地址只收 IPv4 字面量"); } catch (IllegalArgumentException expected) { }
        try { WakeOnLan.send(mac, "192.168.1.255", 445, 1); fail("端口只用 7 / 9"); } catch (IllegalArgumentException expected) { }
        assertNotNull(WakeOnLan.nics()); // 沙箱里可能一块都没有，只要不出错
    }

    @Test @SuppressWarnings("unchecked") public void windowsCheckVerdicts() {
        String json = "{\"fast_startup\":1,\"nics\":["
                + "{\"name\":\"以太网\",\"desc\":\"Realtek PCIe GbE\",\"mac\":\"3C-7C-3F-12-34-56\",\"media\":\"802.3\",\"status\":\"Up\",\"magic\":\"Enabled\",\"armed\":true,\"adv\":[{\"k\":\"*WakeOnMagicPacket\",\"d\":\"Wake on Magic Packet\",\"v\":\"Enabled\"}]},"
                + "{\"name\":\"WLAN\",\"desc\":\"Intel Wi-Fi 6 AX201\",\"mac\":\"A4-B1-C1-00-11-22\",\"media\":\"Native 802.11\",\"status\":\"Disconnected\",\"magic\":\"Enabled\",\"armed\":true,\"adv\":[]},"
                + "{\"name\":\"以太网 2\",\"desc\":\"USB GbE\",\"mac\":\"00-E0-4C-11-22-33\",\"media\":\"802.3\",\"status\":\"Up\",\"magic\":\"Disabled\",\"armed\":false,\"adv\":[]},"
                + "{\"name\":\"以太网 3\",\"desc\":\"Old NIC\",\"mac\":\"00-11-22-33-44-55\",\"media\":\"802.3\",\"status\":\"Up\",\"magic\":\"Unsupported\",\"armed\":false,\"adv\":[]},"
                + "{\"name\":\"以太网 4\",\"desc\":\"Intel I219\",\"mac\":\"00-11-22-33-44-66\",\"media\":\"802.3\",\"status\":\"Up\",\"magic\":\"Enabled\",\"armed\":true,\"adv\":[{\"k\":\"S5WakeOnLan\",\"d\":\"关机唤醒\",\"v\":\"Disabled\"}]}]}";
        Map<String, Object> v = WakeOnLan.verdict(json, true);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) v.get("nics");
        assertEquals(5, rows.size());
        assertEquals("ok", rows.get(0).get("verdict"));
        assertEquals("3C:7C:3F:12:34:56", rows.get(0).get("mac"));
        assertEquals("无线降级为要改设置", "warn", rows.get(1).get("verdict"));
        assertEquals("warn", rows.get(2).get("verdict"));
        assertEquals("bad", rows.get(3).get("verdict"));
        assertEquals("S5 唤醒关着", "warn", rows.get(4).get("verdict"));
        assertEquals(Boolean.TRUE, v.get("ok"));
        assertEquals(Boolean.TRUE, v.get("fast_startup"));
        assertTrue(String.valueOf(v.get("summary")).contains("快速启动"));
        assertTrue(String.valueOf(v.get("bios")).contains("BIOS"));
        // 单块网卡时 ConvertTo-Json 给的是对象不是数组
        Map<String, Object> one = WakeOnLan.verdict("{\"fast_startup\":0,\"nics\":{\"name\":\"以太网\",\"desc\":\"x\",\"mac\":\"3C-7C-3F-12-34-56\",\"status\":\"Up\",\"magic\":\"Enabled\",\"armed\":true}}", true);
        assertEquals(1, ((List<Object>) one.get("nics")).size());
        assertFalse(String.valueOf(one.get("summary")).contains("快速启动"));
        assertEquals(Boolean.FALSE, WakeOnLan.verdict(null, true).get("ok"));
        assertTrue(String.valueOf(WakeOnLan.verdict(null, false).get("summary")).contains("只在 Windows"));
        // 检查脚本只读：只有 Get-* / powercfg 查询，没有 Set- / Enable- / Disable- / reg add
        String ps = WakeOnLan.CHECK_PS.toLowerCase();
        assertFalse(ps.matches("(?s).*\\b(set|enable|disable|new|remove)-[a-z]+.*"));
        assertFalse(ps.contains("reg add") || ps.contains("/change") || ps.contains("-deviceenablewake"));
    }

    @Test public void wakeAgentLeastPrivilege() throws Exception {
        AtomicLong now = new AtomicLong(T0);
        WakeAgent a = new WakeAgent("3c:7c:3f:12:34:56, bad", now::get);
        List<String> sent = new ArrayList<>();
        a.sender = (mac, bc, port) -> { sent.add(WakeOnLan.fmtMac(mac) + "@" + bc + ":" + port); return 3; };
        assertEquals(1, a.allowed().size());
        assertNull(a.handle(Map.of("type", "msg")));
        Map<String, Object> r = a.handle(Map.of("type", "wake_request", "req", "r1", "mac", "AA:BB:CC:DD:EE:01", "bcast", "192.168.1.255", "port", 9));
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertTrue("没登记的 MAC 一律不发", sent.isEmpty());
        r = a.handle(Map.of("type", "wake_request", "req", "r2", "mac", "3C-7C-3F-12-34-56", "bcast", "192.168.1.255", "port", 445));
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("r2", r.get("req"));
        assertEquals("端口只用 7 / 9；子网广播 + 全局广播各发一次", List.of("3C:7C:3F:12:34:56@192.168.1.255:9", "3C:7C:3F:12:34:56@255.255.255.255:9"), sent);
        for (int i = 0; i < 10; i++) a.handle(Map.of("type", "wake_request", "req", "x" + i, "mac", "3C:7C:3F:12:34:56", "bcast", "host.evil", "port", 9));
        r = a.handle(Map.of("type", "wake_request", "req", "y", "mac", "3C:7C:3F:12:34:56"));
        assertEquals("每小时最多 6 次", Boolean.FALSE, r.get("ok"));
        assertTrue(sent.stream().noneMatch(x -> x.contains("host.evil")));
        now.addAndGet(2 * H);
        assertEquals("过了一小时又能发", Boolean.TRUE, a.handle(Map.of("type", "wake_request", "req", "z", "mac", "3C:7C:3F:12:34:56")).get("ok"));
        assertTrue(new WakeAgent("", now::get).handle(Map.of("type", "wake_request", "req", "e", "mac", "3C:7C:3F:12:34:56")).get("text").toString().contains("wake_allow"));
    }

    @Test public void configRoundTripsWolKeys() throws Exception {
        Path f = Files.createTempDirectory("aon-cfg").resolve("d.properties");
        Config c = new Config(f);
        c.wolEnabled = true; c.wolAuto = true; c.wolMac = "3C:7C:3F:12:34:56"; c.wolBcast = "192.168.1.255";
        c.save();
        Config d = Config.load(f);
        assertTrue(d.wolEnabled && d.wolAuto);
        assertEquals("3C:7C:3F:12:34:56", d.wolMac);
        assertEquals("192.168.1.255", d.wolBcast);
        Files.writeString(f, "wol_mac=nonsense\nwol_bcast=evil.example\n");
        Config e = Config.load(f);
        assertEquals("", e.wolMac);
        assertEquals("", e.wolBcast);
        assertFalse(e.wolEnabled);
    }

    // ------------------------------------------------------------------ 设置页

    static String page() throws Exception {
        try (java.io.InputStream in = AlwaysOn1120Test.class.getResourceAsStream("/onesay/ui/index.html")) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test public void settingsGroupAndWording() throws Exception {
        String s = page();
        assertTrue(s.contains("['set-aon','常驻与唤醒']"));
        int a = s.indexOf("// ---------- [always-on-1120]"), b = s.indexOf("const GROUPS=");
        assertTrue(a > 0 && b > a);
        String block = s.substring(a, b);
        for (String bad : new String[] {"Bixby", "三星", "DeX", "小布", "shortcut_run", "aon_", "wake_request", "undefined"})
            assertFalse("界面文字不出现「" + bad + "」", block.replaceAll("api\\('aon'", "").replaceAll("S\\.aon|aonF|aonOpen|aonSet|aonSel|aonIn|aonAt|aonRep|aonSeg|aonPanel|aonJob|aonItem|aonFormHtml|aonAdd|aonOp|aonDel|AON_[A-Z]+|aon-jobs|aon-items", "").contains(bad));
        assertTrue("状态不只是输入框：有排队 / 已唤醒 / 执行中 / 已完成 / 已过期", block.contains("'排队'") && block.contains("'已唤醒'") && block.contains("'执行中'") && block.contains("'已完成'") && block.contains("'已过期'"));
        assertTrue(block.contains("只读检查"));
        assertTrue("自动任务页不再一律写「不排队」", s.contains("电脑关着时排队、上线补跑"));
    }

    static boolean hasNode() {
        for (String d : String.valueOf(System.getenv("PATH")).split(File.pathSeparator)) if (new File(d, "node").canExecute() || new File(d, "node.exe").canExecute()) return true;
        return false;
    }

    /** 在 node 里真的跑页面上的 aonPanel()（其余页面函数用最小替身），看渲染出来的字。 */
    @Test public void panelRendersInNode() throws Exception {
        Assume.assumeTrue("沙箱里有 node 才跑", hasNode());
        String s = page();
        String block = s.substring(s.indexOf("// ---------- [always-on-1120]"), s.indexOf("const GROUPS="));
        String prelude = String.join("\n",
                "const esc=s=>String(s??'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));",
                "const sec=(c,t,n)=>`<div class=sec>${t} ${n??''}</div>`, kv=(k,p,v)=>`<div class=kv>${k}|${p}|${v}</div>`, sw=(k,on)=>`<sw ${k}=${on}>`, ico=n=>'', ago=()=>'刚刚';",
                "const pad=n=>String(n).padStart(2,'0'), hm=ms=>{const d=new Date(ms);return pad(d.getHours())+':'+pad(d.getMinutes())}, dayName=ms=>'今天 ';",
                "const grp=(id,t,sub)=>`<div id=${id}>${t} ${sub}</div>`; let S={shortcuts:['备份照片']}; const render=()=>{};");
        String data = "S.aon={node:{configured:true,up:false,agents:[],queued:2,err:''},count_local:0,count_node:1,"
                + "jobs:[{id:'j1',title:'备份',action:'shortcut',target:'备份照片',where:'node',missed_zh:'补跑一次',every:86400000,next:1795000000000,needs_pc:true,wake:true,enabled:true}],"
                + "items:[{key:'j1@1',title:'备份',action:'运行快捷指令',due:1795000000000,via:'常驻节点',state:'waking',history:['queued','waking']}],"
                + "wol:{enabled:false,auto:false,mac:'',nics:[],check:{}}};";
        String run = "let out=aonPanel(); aonOpen=true; aonF.action='watch'; out+=aonPanel(); console.log(out);";
        Path js = Files.createTempFile("aon-ui", ".js");
        Files.writeString(js, prelude + "\n" + block + "\n" + data + "\n" + run, StandardCharsets.UTF_8);
        Process p = new ProcessBuilder("node", js.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(out, 0, p.waitFor());
        assertTrue(out, out.contains("连不上"));
        assertTrue("没有在线的唤醒代理", out.contains("现在没有在线的"));
        assertTrue("节点上排队数", out.contains("节点上还有 2 条在排队"));
        assertTrue("已唤醒标签", out.contains("已唤醒"));
        assertTrue("没开网络唤醒时自动唤醒开关不可点", out.contains("先打开网络唤醒"));
        assertTrue("查网页只能放常驻节点", out.contains("查网页只能放常驻节点"));
        assertFalse(out.contains("undefined"));
        assertFalse(out.contains("NaN"));
    }
}
