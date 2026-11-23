package com.onesay.desktop;

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** [rel-1123-b] 电脑端：只有常连算在线 + 离线原因、免扫码配对的纯逻辑、手机回传目标结果、对话里等结果。 */
public class Pair1123Test {

    // ------------------------------------------------------------------ 免扫码配对（纯逻辑）

    @Test public void spakeAgreesOnlyWithSameCode() {
        // [rel-1123 合并] 电脑弹框 + 核对码 换成固定配对码 + SPAKE2；细的测试在 Merge1123Test
        byte[] salt = Spake2.salt();
        Spake2.Ctx c = new Spake2.Ctx("phone-1", "pc-1", "AA:BB", "cc:dd", salt, Spake2.ITER_MIN);
        Spake2.Party a = new Spake2.Party(true, Spake2.w("4827", salt, Spake2.ITER_MIN)), b = new Spake2.Party(false, Spake2.w("4827", salt, Spake2.ITER_MIN));
        Spake2.Keys ka = a.finish(b.message(), c), kb = b.finish(a.message(), c);
        assertTrue(Spake2.same(ka.cA, kb.cA));
        assertTrue(Spake2.same(ka.cB, kb.cB));
        Spake2.Party wrong = new Spake2.Party(false, Spake2.w("4828", salt, Spake2.ITER_MIN));
        Spake2.Party a2 = new Spake2.Party(true, Spake2.w("4827", salt, Spake2.ITER_MIN));
        assertFalse(Spake2.same(a2.finish(wrong.message(), c).cA, wrong.finish(a2.message(), c).cA));
    }

    @Test public void onlyPrivateAndTailscaleAddresses() throws Exception {
        for (String ok : new String[]{"192.168.1.5", "10.0.0.2", "172.16.0.9", "172.31.255.1", "100.64.0.1", "100.127.1.1", "127.0.0.1", "169.254.1.1", "fd7a:115c:a1e0::5", "fe80::1", "::1", "::ffff:192.168.1.9"})
            assertTrue(ok, LanPair.privateAddr(InetAddress.getByName(ok)));
        for (String bad : new String[]{"8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "11.0.0.1", "2001:db8::1", "::ffff:8.8.8.8"})
            assertFalse(bad, LanPair.privateAddr(InetAddress.getByName(bad)));
        assertFalse(LanPair.privateAddr(null));
    }

    @Test public void inviteHasNoIdentityAndOnlyWhenOpen() throws Exception {
        InetAddress lan = InetAddress.getByName("192.168.1.7"), pub = InetAddress.getByName("8.8.8.8");
        String inv = LanPair.answerPairProbe(true, true, 47920, "DESKTOP-O1", "{\"t\":\"pprobe\",\"v\":2}", lan);
        assertNotNull(inv);
        Map<String, Object> m = Json.obj(inv);
        assertEquals("pinv", m.get("t"));
        assertEquals(47920L, ((Number) m.get("port")).longValue());
        assertEquals("[rel-1123 合并] 邀请带电脑名字（用户同意同网可见）", "DESKTOP-O1", m.get("name"));
        assertEquals(Boolean.TRUE, m.get("code"));
        assertEquals("邀请只有类型 / 版本 / 端口 / 名字 / 设没设码，不带 id、证书：" + m, 5, m.size());
        assertEquals(Boolean.FALSE, Json.obj(LanPair.answerPairProbe(true, false, 47920, "x", "{\"t\":\"pprobe\"}", lan)).get("code"));
        assertNull("关着配对不回", LanPair.answerPairProbe(false, true, 47920, "x", "{\"t\":\"pprobe\"}", lan));
        assertNull("公网来的不回", LanPair.answerPairProbe(true, true, 47920, "x", "{\"t\":\"pprobe\"}", pub));
        assertNull("别的探测不回", LanPair.answerPairProbe(true, true, 47920, "x", "{\"t\":\"probe\"}", lan));
        assertNull(LanPair.answerPairProbe(true, true, 47920, "x", "x".repeat(600), lan));
    }

    @Test public void limiterOnePendingRateAndBlock() {
        LanPair.Limiter l = new LanPair.Limiter();
        long t = 1_000_000;
        assertNull(l.admit("192.168.1.2", t));
        assertEquals("同时只一个", LanPair.Limiter.BUSY, l.admit("192.168.1.3", t + 1));
        l.release();
        assertEquals("同一地址 10 秒一次", LanPair.Limiter.TOO_FAST, l.admit("192.168.1.2", t + 5_000));
        assertNull(l.admit("192.168.1.2", t + 10_000));
        l.release();
        // 一分钟最多 6 次（不同地址）
        long t2 = t + 20_000;
        for (int i = 0; i < 4; i++) { assertNull(l.admit("10.0.0." + i, t2 + i)); l.release(); }
        assertEquals(LanPair.Limiter.TOO_FAST, l.admit("10.0.0.9", t2 + 10));
    }

    @Test public void phoneNameIsCleaned() {
        assertEquals("小米 14", LanPair.cleanName("小米 14"));
        assertEquals("一台手机", LanPair.cleanName(null));
        assertEquals("一台手机", LanPair.cleanName("  \n\t "));
        assertEquals("ab", LanPair.cleanName("a\u202Eb<>\n"));
        assertEquals(32, LanPair.cleanName("x".repeat(80)).length());
        for (String w : new String[]{"code", "locked", "nocode", "old", "closed", "busy", "rate", "addr", "tls", "bad", "?"}) {
            String z = LanPair.whyZh(w);
            assertFalse(z.isEmpty());
            assertFalse(z, z.contains("啥") || z.contains("咋") || z.contains("呗") || z.contains("Bixby"));
        }
    }

    // ------------------------------------------------------------------ 离线原因

    @Test public void offlineWhyPicksMostRecentCause() {
        long now = 10_000_000;
        LanServer.Seen s = new LanServer.Seen();
        assertEquals(OfflineWhy.LAN_OFF, OfflineWhy.of(false, "电脑上的局域网端口 47920 开不了", false, "", s, now).code);
        assertEquals(OfflineWhy.PC_NO_LAN, OfflineWhy.of(true, "", true, "phone-1", s, now).code);
        assertEquals(OfflineWhy.NEVER, OfflineWhy.of(true, "", false, "", s, now).code);
        assertEquals(OfflineWhy.UNREACHABLE, OfflineWhy.of(true, "", false, "phone-1", s, now).code);
        s.oneShot("phone-1", now - 5_000);
        OfflineWhy.Why w = OfflineWhy.of(true, "", false, "phone-1", s, now);
        assertEquals(OfflineWhy.RX_OFF, w.code);
        assertTrue(w.text.contains("接受电脑指令"));
        assertEquals("太久以前的一次性连接不算", OfflineWhy.UNREACHABLE, OfflineWhy.of(true, "", false, "phone-1", s, now + OfflineWhy.ONE_SHOT_MS + 6_000).code);
        s.refused(4001, now - 1_000);
        assertEquals(OfflineWhy.PAIR_BAD, OfflineWhy.of(true, "", false, "phone-1", s, now).code);
        s.refused(4003, now - 500);
        assertEquals(OfflineWhy.PIN, OfflineWhy.of(true, "", false, "phone-1", s, now).code);
        s.refused(4002, now - 400);
        assertEquals(OfflineWhy.PLAIN, OfflineWhy.of(true, "", false, "phone-1", s, now).code);
        s.dropped("phone-1", now - 100);
        OfflineWhy.Why d = OfflineWhy.of(true, "", false, "phone-1", s, now);
        assertEquals(OfflineWhy.DROPPED, d.code);
        assertTrue(d.text, d.text.matches(".*\\d{2}:\\d{2} 断开.*"));
        s.paired("phone-2", now - 50);
        assertEquals(OfflineWhy.JUST_PAIRED, OfflineWhy.of(true, "", false, "phone-1", s, now).code);
        s.held("phone-1");
        assertEquals("连上过就不再说「刚断开」", OfflineWhy.JUST_PAIRED, OfflineWhy.of(true, "", false, "phone-1", s, now).code);
        for (String c : new String[]{"rx_off", "pair_bad", "pin", "plain", "dropped", "just_paired", "never", "unreachable", "pc_no_lan", "lan_off"}) assertFalse(c.isEmpty());
    }

    // ------------------------------------------------------------------ 真连接：只有常连算在线

    @Test public void oneShotIsNotOnlineHeldIs() throws Exception {
        try (LinkV2Test.Server s = new LinkV2Test.Server()) {
            Config ph = LinkV2Test.phoneCfg(s.cfg, "phone-one");
            LinkV2Test.Conn c = new LinkV2Test.Conn(ph, false, null);
            assertTrue(c.why.get(), c.up);
            assertNotNull(c.request(ph, "ping", new TreeMap<>()));
            assertFalse("一次性连接不算在线", s.agent.peers().containsKey("phone-one"));
            OfflineWhy.Why w = s.agent.offlineWhy();
            assertNotNull(w);
            assertEquals(OfflineWhy.RX_OFF, w.code);
            c.lc.stop();
            Thread.sleep(200);
            synchronized (s.log) { assertFalse("一次性连接不刷上线 / 离线：" + s.log, s.log.toString().contains("phone-one 上线") || s.log.toString().contains("phone-one 离线")); }
            LinkV2Test.Conn h = new LinkV2Test.Conn(ph, true, null);
            assertTrue(h.up);
            assertEquals("局域网·加密", s.agent.peers().get("phone-one"));
            assertNull(s.agent.offlineWhy());
            h.lc.stop();
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end && s.agent.peers().containsKey("phone-one")) Thread.sleep(50);
            assertFalse(s.agent.peers().containsKey("phone-one"));
            w = s.agent.offlineWhy();
            assertEquals(OfflineWhy.DROPPED, w.code);
            synchronized (s.log) {
                assertTrue(s.log.toString(), s.log.toString().contains("设备 phone-one 上线"));
                assertTrue(s.log.toString(), s.log.toString().contains("设备 phone-one 离线"));
            }
        }
    }

    // ------------------------------------------------------------------ 手机回传目标结果

    static Map<String, Object> goalBody(Config pc, String from, String gid, String state, String text, long now) {
        Map<String, Object> b = new TreeMap<>();
        b.put("kind", "goal_result");
        b.put("gid", gid);
        b.put("state", state);
        b.put("ok", "done".equals(state));
        b.put("text", text);
        b.put("steps", List.of("打开设置", "看版本"));
        b.put("goal", "打开设置看看安卓版本");
        String id = Protocol.newId();
        Map<String, Object> f = new TreeMap<>();
        f.put("type", "msg");
        f.put("from", from);
        f.put("id", id);
        f.put("body", Protocol.seal(pc.pairKey, id, from, pc.deviceId, b, now));
        return f;
    }

    @Test public void goalResultIsVerifiedAndCompletesWaiter() throws Exception {
        Config pc = LanTest.pc(LanTest.freePortPair());
        pc.lanPort = 0; // 不开局域网：直接喂帧
        Agent a = new Agent(pc, new Main.TestActions(), x -> { });
        java.util.List<Object[]> done = new java.util.concurrent.CopyOnWriteArrayList<>();
        a.onGoalDone = (g, waited) -> done.add(new Object[]{g, waited});
        a.goalStarted("phone-1", "g0123456789", "打开设置看看安卓版本");
        CompletableFuture<Agent.Goal> f = a.goalFuture("g0123456789");
        // 另一台手机冒充：不认
        a.handle(goalBody(pc, "phone-2", "g0123456789", "done", "安卓 8.0", System.currentTimeMillis()), null);
        assertFalse(f.isDone());
        // 签名不对：不认
        Map<String, Object> bad = goalBody(pc, "phone-1", "g0123456789", "done", "x", System.currentTimeMillis());
        @SuppressWarnings("unchecked") Map<String, Object> bb = (Map<String, Object>) bad.get("body");
        bb.put("text", "篡改过");
        a.handle(bad, null);
        assertFalse(f.isDone());
        // 正常
        a.handle(goalBody(pc, "phone-1", "g0123456789", "done", "安卓版本是 8.0.0", System.currentTimeMillis()), null);
        Agent.Goal g = f.get(2, TimeUnit.SECONDS);
        assertEquals("done", g.state);
        assertEquals("安卓版本是 8.0.0", g.text);
        assertEquals(2, g.steps.size());
        assertEquals(Boolean.TRUE, done.get(0)[1]);
        assertSame(g, a.lastGoal());
        // 重发同一条：不重复
        a.handle(goalBody(pc, "phone-1", "g0123456789", "failed", "y", System.currentTimeMillis()), null);
        assertEquals("done", a.lastGoal().state);
        assertEquals(1, done.size());
        // 没人在等的（电脑重启过 / 已经不等了）：照样记下、通知
        a.handle(goalBody(pc, "phone-1", "gabcdef0123", "denied", "你在手机上拒绝了", System.currentTimeMillis()), null);
        assertEquals("denied", a.lastGoal().state);
        assertEquals(Boolean.FALSE, done.get(1)[1]);
        // 不认识的状态按没做成
        a.handle(goalBody(pc, "phone-1", "gzzzz", "weird", "?", System.currentTimeMillis()), null);
        assertEquals("failed", a.lastGoal().state);
    }

    @Test public void chatWaitsForPhoneResult() throws Exception {
        Store st = new Store(Files.createTempDirectory("c1123").resolve("state.json"));
        Config c = new Config(Files.createTempFile("c1123", ".properties"));
        Chat ch = new Chat(c, st, new Main.TestActions(), List::of, x -> { });
        ch.phones = () -> Map.of("phone-1", "局域网·加密");
        Map<String, CompletableFuture<Agent.Goal>> fut = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.List<String> unwait = new java.util.concurrent.CopyOnWriteArrayList<>();
        ch.phoneGoal = (gid, goal) -> fut.computeIfAbsent(gid, k -> new CompletableFuture<>());
        ch.phoneGoalUnwait = unwait::add;
        Map<String, Object> d = new TreeMap<>();
        d.put("goal_id", "g1");
        ch.phone = (cmd, args) -> Actions.Result.ok("已交给手机上的 OneSay 去做", d);
        // 手机 1 秒后回传「做完了」
        new Thread(() -> {
            try { Thread.sleep(800); } catch (InterruptedException ignored) { }
            Agent a = new Agent(c, new Main.TestActions(), x -> { });
            Agent.Goal g = a.goalStarted("phone-1", "g1", "打开设置看看安卓版本");
            g.text = "安卓版本是 8.0.0";
            g.state = "done";
            fut.computeIfAbsent("g1", k -> new CompletableFuture<>()).complete(g);
        }).start();
        Actions.Result r = ch.phoneTool("phone_operate", Map.of("goal", "打开设置看看安卓版本"));
        assertTrue(r.text, r.ok);
        assertEquals("手机做完了：安卓版本是 8.0.0", r.text);
        assertEquals("done", r.data.get("goal_state"));
        assertEquals(List.of("g1"), unwait);
        // 没做成
        d.put("goal_id", "g2");
        Agent a2 = new Agent(c, new Main.TestActions(), x -> { });
        Agent.Goal g2 = a2.goalStarted("phone-1", "g2", "转账");
        g2.state = "denied";
        g2.text = "你在手机上拒绝了";
        fut.put("g2", CompletableFuture.completedFuture(g2));
        Actions.Result r2 = ch.phoneTool("phone_operate", Map.of("goal", "转账"));
        assertFalse(r2.ok);
        assertEquals("手机没做（你在手机上拒绝了）", r2.text);
        // 等太久：先不等，说结果会发通知
        long old = Chat.goalWaitMs;
        Chat.goalWaitMs = 600;
        try {
            d.put("goal_id", "g3");
            Actions.Result r3 = ch.phoneTool("phone_operate", Map.of("goal", "很久的事"));
            assertFalse(r3.ok);
            assertTrue(r3.text, r3.text.contains("还没做完") && r3.text.contains("通知"));
            assertEquals("running", r3.data.get("goal_state"));
        } finally {
            Chat.goalWaitMs = old;
        }
        // 旧版手机（不回 goal_id）：照旧只说交过去了
        d.remove("goal_id");
        Actions.Result r4 = ch.phoneTool("phone_operate", Map.of("goal", "打开设置"));
        assertTrue(r4.ok);
        assertTrue(r4.text.contains("已交给手机"));
        // 已装 App 工具：只读预设下也能用
        ch.phone = (cmd, args) -> "app_list".equals(cmd) ? Actions.Result.ok("手机上名字带「地图」的 App 有 1 个：高德地图", Map.of()) : Actions.Result.fail("x");
        c.chatPreset = "read_only";
        Actions.Result apps = ch.phoneTool("phone_apps", Map.of("query", "地图"));
        assertTrue(apps.text, apps.ok);
        assertTrue(apps.text.contains("高德地图"));
        assertFalse("只读预设下动作类照旧不行", ch.phoneTool("phone_operate", Map.of("goal", "打开设置")).ok);
    }

    @Test public void appListRegisteredOnDesktop() {
        assertTrue(PhoneCmds.ALL.contains("app_list"));
        assertNull(PhoneCmds.check("app_list", Map.of()));
        assertNotNull(PhoneCmds.check("app_list", Map.of("query", "x".repeat(41))));
        assertEquals(LinkPolicy.Cat.READ_PRIVATE, LinkPolicy.phoneCat("app_list"));
        assertEquals("看手机上装了哪些 App", Humanize.phoneCap("app_list"));
        assertEquals("「看手机上装了哪些 App」", Humanize.text("phone_apps"));
        assertEquals("做完了", Humanize.goalState("done"));
        assertEquals("没做成", Humanize.goalState("bogus"));
        assertEquals("手机没做成（手机没说原因）", Humanize.goalLine("failed", ""));
        assertEquals("手机上被新指令打断了：已打开设置", Humanize.goalLine("interrupted", "已打开设置"));
        assertTrue(Humanize.goalLine("unknown", "看手机屏幕").startsWith("手机没回结果"));
        assertTrue(Agent.GOAL_STATES.contains("unknown"));
        Config c = new Config(null);
        assertFalse("[rel-1123 合并] 免扫码配对默认关（先设配对码才能开）", c.pairInvite);
    }

    @Test public void pairInviteSettingPersists() throws Exception {
        Config c = new Config(Files.createTempFile("pi", ".properties"));
        c.pairInvite = false;
        c.save();
        assertFalse(Config.load(c.file).pairInvite);
        c.pairInvite = true;
        c.save();
        assertTrue(Config.load(c.file).pairInvite);
    }
}
