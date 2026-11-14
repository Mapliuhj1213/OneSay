package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** 协议 v2（0.5.0）：证书 / 指纹、配对串 v2 与 v1 兼容、TLS 钉指纹、防降级、电脑 → 手机常连通道、电脑端手机口令。 */
public class LinkV2Test {

    // ------------------------------------------------------------------ 证书与指纹

    @Test public void selfSignedCertIsValidAndRoundTrips() throws Exception {
        Tls t = Tls.generate("onesay-pc-test");
        X509Certificate c = t.cert;
        c.verify(c.getPublicKey()); // 自签名验得过
        c.checkValidity();
        assertEquals(3, c.getVersion());
        assertEquals("SHA256withECDSA", c.getSigAlgName());
        assertTrue(c.getSubjectX500Principal().getName().contains("onesay-pc-test"));
        assertTrue(Tls.validFp(t.fp));
        assertEquals(43, t.fp.length());
        assertEquals(Tls.fingerprint(c.getEncoded()), t.fp);
        Tls back = Tls.load(t.keyB64(), t.certB64());
        assertNotNull(back);
        assertEquals(t.fp, back.fp);
        assertNull("坏数据还原不了", Tls.load("AAAA", t.certB64()));
        assertNotEquals("每台设备各自生成", t.fp, Tls.generate("x").fp);
        assertTrue(Tls.shortFp(t.fp).matches("[0-9A-F]{4}-[0-9A-F]{4}"));
        assertFalse(Tls.validFp("abc"));
        assertFalse(Tls.validFp(t.fp + "A"));
    }

    @Test public void certSurvivesConfigSaveLoad() throws Exception {
        Config c = new Config(Files.createTempFile("pc", ".properties"));
        c.deviceId = "pc-a";
        c.pairKey = Protocol.newKey();
        String fp = c.tls().fp;
        c.save();
        assertEquals("重启后证书不变（手机钉的是它）", fp, Config.load(c.file).tls().fp);
    }

    // ------------------------------------------------------------------ 配对串

    @Test public void pairingV2CarriesFingerprintAndV1StillImports() throws Exception {
        Config pc = LanTest.pc(47820);
        String s = pc.pairingString();
        assertTrue(s, s.startsWith("onesay://pair?key="));
        assertTrue(s.contains("&fp=" + pc.tls().fp));
        assertTrue(s.endsWith("&v=2"));
        Config b = new Config(null);
        assertEquals("pc-lan", b.importPairing(s));
        assertEquals(pc.tls().fp, b.peerFp);

        // v1 串（0.4.x 电脑生成的）照样能导入，走明文
        String v1 = "onesay://pair?key=" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(pc.pairKey) + "&peer=pc-old&lan=192.168.1.5%3A47820";
        Config c = new Config(null);
        assertEquals("pc-old", c.importPairing(v1));
        assertEquals("", c.peerFp);
        assertEquals("192.168.1.5:47820", c.lanHosts);

        try { new Config(null).importPairing(s.replace("&v=2", "&v=3")); fail("更新的版本要提示升级"); } catch (IllegalArgumentException e) { assertTrue(e.getMessage().contains("升级")); }
        try { new Config(null).importPairing(s.replace(pc.tls().fp, "short")); fail("坏指纹"); } catch (IllegalArgumentException expected) { }
        try { new Config(null).importPairing(s.replace("&fp=" + pc.tls().fp, "")); fail("v2 缺指纹"); } catch (IllegalArgumentException expected) { }
    }

    // ------------------------------------------------------------------ TLS 真连接

    interface Phone {
        void onCmd(LanClient self, Map<String, Object> frame);
    }

    static final class Conn {
        final LanClient lc;
        final AtomicReference<String> why = new AtomicReference<>();
        final BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();
        volatile boolean up;

        Conn(Config cfg, boolean hold, Phone phone) throws Exception {
            CountDownLatch l = new CountDownLatch(1);
            LanClient[] self = new LanClient[1];
            lc = new LanClient(cfg, "pc-lan", new Link.Listener() {
                @Override public void onFrame(Map<String, Object> f) {
                    if (phone != null && "msg".equals(f.get("type")) && f.get("body") instanceof Map && "cmd".equals(((Map<?, ?>) f.get("body")).get("kind"))) phone.onCmd(self[0], f);
                    else frames.add(f);
                }
                @Override public void onState(boolean c, String w) { up = c; why.set(w); l.countDown(); }
            }, 2500);
            self[0] = lc;
            lc.hold = hold;
            lc.start();
            assertTrue("连接超时", l.await(10, TimeUnit.SECONDS));
            Thread.sleep(150); // 等电脑端处理完 hello / 记指纹（拒绝时等关闭帧）
        }

        Map<String, Object> request(Config cfg, String cmd, Map<String, Object> args) throws Exception {
            String id = Protocol.newId();
            Map<String, Object> body = Protocol.command(cfg.pairKey, id, cfg.deviceId, "pc-lan", cmd, args, System.currentTimeMillis());
            lc.send(Protocol.envelope("pc-lan", id, body)).get(3, TimeUnit.SECONDS);
            return awaitResult(id);
        }

        Map<String, Object> awaitResult(String id) throws Exception {
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end) {
                Map<String, Object> f = frames.poll(200, TimeUnit.MILLISECONDS);
                if (f != null && f.get("body") instanceof Map && id.equals(((Map<?, ?>) f.get("body")).get("re"))) {
                    @SuppressWarnings("unchecked") Map<String, Object> b = (Map<String, Object>) f.get("body");
                    return b;
                }
            }
            return null;
        }
    }

    static Config phoneCfg(Config pc, String id) throws Exception {
        Config c = new Config(Files.createTempFile("ph", ".properties"));
        c.importPairing(pc.pairingString());
        c.lanHosts = "127.0.0.1:" + pc.lanPort;
        c.deviceId = id;
        c.kind = "phone";
        return c;
    }

    static final class Server implements AutoCloseable {
        final Config cfg;
        final Agent agent;
        final StringBuilder log = new StringBuilder();

        Server() throws Exception {
            cfg = LanTest.pc(LanTest.freePortPair());
            agent = new Agent(cfg, new Main.TestActions(), s -> { synchronized (log) { log.append(s).append('\n'); } });
            agent.start();
            Thread.sleep(250);
        }

        @Override public void close() { agent.stop(); }
    }

    @Test public void tlsWithPinnedFingerprintWorksAndPinsClient() throws Exception {
        try (Server s = new Server()) {
            Config ph = phoneCfg(s.cfg, "phone-aaa");
            Conn c = new Conn(ph, false, null);
            assertTrue(c.why.get(), c.up);
            assertTrue("配对串带指纹 → 必走 TLS", c.lc.tls);
            Map<String, Object> r = c.request(ph, "ping", new TreeMap<>());
            assertNotNull(r);
            assertEquals(Boolean.TRUE, r.get("ok"));
            assertEquals("电脑记住了这台手机的证书", ph.tls().fp, Config.load(s.cfg.file).pinOf("phone-aaa"));
            assertEquals("局域网·加密", s.agent.peers().get("phone-aaa"));
            c.lc.stop();
        }
    }

    @Test public void wrongServerFingerprintIsRejected() throws Exception {
        try (Server s = new Server()) {
            Config ph = phoneCfg(s.cfg, "phone-bbb");
            ph.peerFp = Tls.generate("mitm").fp; // 配对串里的指纹不是这台电脑
            Conn c = new Conn(ph, false, null);
            assertFalse("指纹不对必须连不上", c.up);
            assertTrue(c.why.get(), c.why.get().startsWith(LanClient.OFFLINE));
            assertNull("被拒的设备不会被记住", s.cfg.pinOf("phone-bbb"));
        }
    }

    @Test public void downgradeAndPinMismatchAreRejected() throws Exception {
        try (Server s = new Server()) {
            Config ph = phoneCfg(s.cfg, "phone-ccc");
            Conn ok = new Conn(ph, false, null);
            assertTrue(ok.up);
            ok.lc.stop();
            Thread.sleep(150);

            // 同一个设备 id 改走明文（降级）→ 4002
            Config plain = phoneCfg(s.cfg, "phone-ccc");
            plain.peerFp = "";
            Conn d = new Conn(plain, false, null);
            Thread.sleep(300);
            assertFalse("用 TLS 连过的设备不许退回明文", d.up && d.request(plain, "ping", new TreeMap<>()) != null);
            assertTrue(d.why.get(), d.why.get().contains("要求加密连接"));

            // 同一个设备 id、另一张证书 → 4003
            Config other = phoneCfg(s.cfg, "phone-ccc");
            assertNotEquals(ph.tls().fp, other.tls().fp);
            Conn m = new Conn(other, false, null);
            Thread.sleep(300);
            assertFalse("证书和记住的不一致必须拒", m.up && m.request(other, "ping", new TreeMap<>()) != null);
            assertTrue(m.why.get(), m.why.get().contains("旧证书"));

            // 没见过的旧版手机仍可明文（兼容 v1）
            Config legacy = phoneCfg(s.cfg, "phone-legacy");
            legacy.peerFp = "";
            Conn lg = new Conn(legacy, false, null);
            assertTrue(lg.why.get(), lg.up);
            assertFalse(lg.lc.tls);
            Map<String, Object> r = lg.request(legacy, "ping", new TreeMap<>());
            assertNotNull(r);
            assertEquals(Boolean.TRUE, r.get("ok"));
            lg.lc.stop();

            // 关掉明文兼容 → 旧版也拒
            s.cfg.lanPlain = false;
            Config legacy2 = phoneCfg(s.cfg, "phone-legacy2");
            legacy2.peerFp = "";
            Conn lg2 = new Conn(legacy2, false, null);
            Thread.sleep(300);
            assertFalse(lg2.up && lg2.request(legacy2, "ping", new TreeMap<>()) != null);
            assertTrue(lg2.why.get(), lg2.why.get().contains("要求加密连接"));
        }
    }

    @Test public void replayedCommandIsRejected() throws Exception {
        try (Server s = new Server()) {
            Config ph = phoneCfg(s.cfg, "phone-ddd");
            Conn c = new Conn(ph, false, null);
            assertTrue(c.up);
            String id = Protocol.newId();
            Map<String, Object> body = Protocol.command(ph.pairKey, id, ph.deviceId, "pc-lan", "ping", new TreeMap<>(), System.currentTimeMillis());
            String env = Protocol.envelope("pc-lan", id, body);
            c.lc.send(env).get(3, TimeUnit.SECONDS);
            Map<String, Object> r1 = c.awaitResult(id);
            assertEquals(Boolean.TRUE, r1.get("ok"));
            c.lc.send(env).get(3, TimeUnit.SECONDS); // 原样重放
            Map<String, Object> r2 = c.awaitResult(id);
            assertNotNull(r2);
            assertEquals(Boolean.FALSE, r2.get("ok"));
            assertTrue(String.valueOf(r2.get("text")), String.valueOf(r2.get("text")).contains("REPLAY"));
            // 过期的
            String id3 = Protocol.newId();
            Map<String, Object> old = Protocol.command(ph.pairKey, id3, ph.deviceId, "pc-lan", "ping", new TreeMap<>(), System.currentTimeMillis() - 10 * 60_000);
            c.lc.send(Protocol.envelope("pc-lan", id3, old)).get(3, TimeUnit.SECONDS);
            assertTrue(String.valueOf(c.awaitResult(id3).get("text")).contains("STALE"));
            c.lc.stop();
        }
    }

    // ------------------------------------------------------------------ 电脑 → 手机

    /** 假手机：验电脑来的签名指令，回签名结果。 */
    static Phone fakePhone(Config ph, List<String> seen, boolean forgeSig) {
        Protocol.ReplayGuard g = new Protocol.ReplayGuard();
        return (self, f) -> {
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) f.get("body");
            String id = String.valueOf(f.get("id"));
            Protocol.Check c = Protocol.verify(ph.pairKey, id, String.valueOf(f.get("from")), ph.deviceId, body, System.currentTimeMillis(), g);
            String cmd = String.valueOf(body.get("cmd"));
            seen.add(cmd + ":" + c);
            Map<String, Object> data = new TreeMap<>();
            data.put("battery", 87L);
            data.put("charging", true);
            String rid = Protocol.newId();
            Map<String, Object> res = Protocol.result(forgeSig ? Protocol.newKey() : ph.pairKey, rid, ph.deviceId, "pc-lan", id, c == Protocol.Check.OK, c == Protocol.Check.OK ? "好" : "拒绝：" + c, data, System.currentTimeMillis());
            self.send(Protocol.envelope("pc-lan", rid, res));
        };
    }

    @Test public void pcCommandsPhoneOverHeldConnection() throws Exception {
        try (Server s = new Server()) {
            Config ph = phoneCfg(s.cfg, "phone-eee");
            List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
            Conn held = new Conn(ph, true, fakePhone(ph, seen, false));
            assertTrue(held.why.get(), held.up);
            assertEquals("phone-eee", s.agent.phoneTarget(""));
            assertEquals("phone-eee", Config.load(s.cfg.file).phoneLast);

            Map<String, Object> args = new TreeMap<>();
            args.put("action", "start");
            Actions.Result r = s.agent.callPeer("phone-eee", "find_phone", args, 1000, 5000).get(8, TimeUnit.SECONDS);
            assertTrue(r.text, r.ok);
            assertEquals(87L, r.data.get("battery"));
            assertEquals(List.of("find_phone:OK"), seen);

            // 同一台手机再开一条一次性连接下令：不顶掉常连，回复回到发起的连接
            Conn once = new Conn(ph, false, null);
            assertTrue(once.up);
            Map<String, Object> pr = once.request(ph, "ping", new TreeMap<>());
            assertNotNull("一次性连接收到自己的回复", pr);
            assertEquals(Boolean.TRUE, pr.get("ok"));
            once.lc.stop();
            Thread.sleep(200);
            Actions.Result r2 = s.agent.callPeer("phone-eee", "status", new TreeMap<>(), 1000, 5000).get(8, TimeUnit.SECONDS);
            assertTrue("常连还在：" + r2.text, r2.ok);
            assertEquals("手机电量 87%，在充电", Chat.statusLine(r2));
            held.lc.stop();
        }
    }

    @Test public void phoneReplyWithBadSignatureIsRejected() throws Exception {
        try (Server s = new Server()) {
            Config ph = phoneCfg(s.cfg, "phone-fff");
            List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
            Conn held = new Conn(ph, true, fakePhone(ph, seen, true));
            assertTrue(held.up);
            Actions.Result r = s.agent.callPeer("phone-fff", "status", new TreeMap<>(), 1000, 4000).get(8, TimeUnit.SECONDS);
            assertFalse(r.ok);
            assertTrue(r.text, r.text.contains("BAD_SIG"));
            held.lc.stop();
        }
    }

    @Test public void offlinePhoneFailsFastNotQueued() throws Exception {
        try (Server s = new Server()) {
            long t0 = System.currentTimeMillis();
            Actions.Result r = s.agent.callPeer("phone-nowhere", "find_phone", new TreeMap<>(), 800, 3000).get(8, TimeUnit.SECONDS);
            assertFalse(r.ok);
            assertTrue(r.text, r.text.contains("离线"));
            assertTrue(System.currentTimeMillis() - t0 < 3000);
            assertFalse(s.agent.callPeer(null, "ping", null, 100, 100).get(2, TimeUnit.SECONDS).ok);
        }
    }

    @Test public void announceAndProbeV2Fields() throws Exception {
        Config pc = LanTest.pc(47820);
        Map<String, Object> a = Json.obj(LanServer.announceText(pc));
        assertEquals("ann", a.get("t"));
        assertNull("announce 不带本机 id", a.get("id"));
        assertTrue(Protocol.tagOk(pc.pairKey, "ann\npc-lan\n47820\n" + a.get("n"), a.get("tag")));
        assertFalse(Protocol.tagOk(Protocol.newKey(), "ann\npc-lan\n47820\n" + a.get("n"), a.get("tag")));
        String n = "abcdefghij";
        Map<String, Object> m = new TreeMap<>();
        m.put("t", "probe"); m.put("from", "phone-x"); m.put("to", "pc-lan"); m.put("n", n);
        m.put("tag", Protocol.tag(pc.pairKey, "probe\nphone-x\npc-lan\n" + n));
        Map<String, Object> here = Json.obj(LanServer.answerProbe(pc, Json.stringify(m)));
        assertEquals(2L, here.get("v"));
        assertEquals(Boolean.TRUE, here.get("tls"));
        assertTrue("v1 标签不变（旧手机照样认）", Protocol.tagOk(pc.pairKey, "here\npc-lan\n47820\n" + n, here.get("tag")));
        assertEquals("224.0.0.167", LanServer.GROUP);
    }

    // ------------------------------------------------------------------ 电脑端口令

    @Test public void phoneVoiceRules() {
        Intent i = Intent.parse("让手机响一下", Map.of(), List.of());
        assertNull("不在电脑上执行", i.cmd);
        assertEquals("find_phone", i.phoneCmd);
        assertEquals("start", i.args.get("action"));
        assertEquals("stop", Intent.parse("手机别响了", Map.of(), List.of()).args.get("action"));
        assertEquals("find_phone", Intent.parse("找手机", Map.of(), List.of()).phoneCmd);
        assertEquals("screen_on", Intent.parse("手机亮屏", Map.of(), List.of()).phoneCmd);
        assertEquals("status", Intent.parse("手机还有多少电？", Map.of(), List.of()).phoneCmd);
        Intent v = Intent.parse("把手机音量调到 30", Map.of(), List.of());
        assertEquals("volume_set", v.phoneCmd);
        assertEquals(30L, v.args.get("percent"));
        Intent u = Intent.parse("手机打开B站", Map.of(), List.of());
        assertEquals("open_url", u.phoneCmd);
        assertEquals("https://www.bilibili.com", u.args.get("url"));
        Intent w = Intent.parse("手机打开微信", Map.of(), List.of());
        assertEquals("open_app", w.phoneCmd);
        assertEquals("微信", w.args.get("name"));
        assertEquals("com.tencent.mm", Intent.parse("手机打开 com.tencent.mm", Map.of(), List.of()).args.get("package"));
        assertEquals("https://example.com/a", Intent.parse("手机打开 https://example.com/a", Map.of(), List.of()).args.get("url"));
        assertEquals("clipboard_set", Intent.parse("把剪贴板发到手机", Map.of(), List.of()).phoneCmd);
        Intent n = Intent.parse("在手机上提醒我：取快递", Map.of(), List.of());
        assertEquals("notify", n.phoneCmd);
        assertEquals("取快递", n.args.get("text"));
        assertNull("电脑口令不受影响", Intent.parse("锁屏", Map.of(), List.of()).phoneCmd);
        assertNull(Intent.parse("手机", Map.of(), List.of()).phoneCmd);
    }

    @Test public void phoneCmdArgumentChecks() {
        assertNull(PhoneCmds.check("open_url", Map.of("url", "https://a.b")));
        assertNotNull(PhoneCmds.check("open_url", Map.of("url", "file:///etc/passwd")));
        assertNotNull(PhoneCmds.check("open_url", Map.of("url", "intent://x")));
        assertNotNull(PhoneCmds.check("volume_set", Map.of("percent", 101L)));
        assertNull(PhoneCmds.check("volume_set", Map.of("percent", 0L)));
        assertNotNull(PhoneCmds.check("rm_rf", Map.of()));
        assertNotNull(PhoneCmds.check("clipboard_set", Map.of("text", "")));
        assertNotNull(PhoneCmds.check("find_phone", Map.of("action", "explode")));
    }

    @Test public void chatSendsPhoneCommandsThroughHook() throws Exception {
        Config cfg = new Config(Files.createTempFile("c", ".properties"));
        Store st = new Store(Files.createTempDirectory("st").resolve("state.json"));
        Chat chat = new Chat(cfg, st, new Main.TestActions(), List::of, x -> { });
        List<String> sent = new java.util.ArrayList<>();
        chat.phone = (cmd, args) -> { sent.add(cmd + args); return Actions.Result.ok("好", new TreeMap<>(Map.of("battery", 50L))); };
        assertEquals("手机正在响（最大音量，静音也响），找到后说「手机别响了」", chat.send("让手机响一下", "text").get("text"));
        assertEquals("手机电量 50%", chat.send("手机电量多少", "text").get("text"));
        assertEquals(List.of("find_phone{action=start, seconds=30}", "status{}"), sent);
    }
}
