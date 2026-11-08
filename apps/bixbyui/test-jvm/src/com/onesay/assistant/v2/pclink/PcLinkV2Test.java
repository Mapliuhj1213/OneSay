package com.onesay.assistant.v2.pclink;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * [pclink v2] 证书 / 指纹、配对串 v2 与 v1 兼容、hello 带 cfp/hold、电脑 → 手机指令的验签 / 防重放 / 开关 / 参数检查、announce、接线。
 * 与电脑端真连接（含 TLS）的互通在 {@link PcLinkV2InteropTest}。
 */
public class PcLinkV2Test {

    static final byte[] KEY = new byte[32];
    static {
        for (int i = 0; i < 32; i++) KEY[i] = (byte) (i * 7 + 3);
    }
    static final String PC = "pc-home", ME = "phone-abc123";

    // ------------------------------------------------------------------ 证书

    @Test public void certGeneratedLoadsAndFingerprints() throws Exception {
        PcTls t = PcTls.generate("onesay-phone-abc123");
        X509Certificate c = t.cert;
        c.verify(c.getPublicKey());
        c.checkValidity();
        assertEquals(3, c.getVersion());
        assertEquals(43, t.fp.length());
        assertTrue(PcTls.validFp(t.fp));
        assertEquals(PcTls.fingerprint(c.getEncoded()), t.fp);
        PcTls back = PcTls.load(t.keyB64(), t.certB64());
        assertNotNull(back);
        assertEquals(t.fp, back.fp);
        assertNull(PcTls.load("", t.certB64()));
        assertNull(PcTls.load("AAAA", t.certB64()));
        assertNotEquals(t.fp, PcTls.generate("x").fp);
        assertTrue(PcTls.shortFp(t.fp).matches("[0-9A-F]{4}-[0-9A-F]{4}"));
        assertFalse(PcTls.validFp(null));
        assertFalse(PcTls.validFp(t.fp.substring(1)));
        assertFalse(PcTls.validFp(t.fp.replace(t.fp.charAt(0), '+')));
        // 证书有效期不超过 2049 年底（UTCTime）
        assertTrue(c.getNotAfter().getTime() <= 2524607999000L);
    }

    @Test public void pinnedContextRefusesOtherCert() throws Exception {
        PcTls a = PcTls.generate("a"), b = PcTls.generate("b");
        PcTls.Pin pin = new PcTls.Pin(a.fp);
        pin.checkServerTrusted(new X509Certificate[]{a.cert}, "ECDHE_ECDSA");
        try {
            pin.checkServerTrusted(new X509Certificate[]{b.cert}, "ECDHE_ECDSA");
            fail("指纹不对必须拒");
        } catch (java.security.cert.CertificateException expected) {
        }
        try {
            pin.checkServerTrusted(new X509Certificate[0], "ECDHE_ECDSA");
            fail("没证书必须拒");
        } catch (java.security.cert.CertificateException expected) {
        }
        new PcTls.Pin(null).checkClientTrusted(new X509Certificate[]{b.cert}, "EC"); // 服务端收任意客户端证书（身份由签名的 hello 决定）
        try {
            a.clientContext("not-a-fp");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    // ------------------------------------------------------------------ 配对串

    static String v1() throws Exception {
        return PcPairing.PREFIX + "key=" + PcB64.encodeUrl(KEY) + "&peer=" + PC + "&lan=" + java.net.URLEncoder.encode("192.168.31.20:47820", "UTF-8");
    }

    @Test public void pairingV2CarriesFingerprintAndV1StillParses() throws Exception {
        String fp = PcTls.generate("pc").fp;
        PcPairing p2 = PcPairing.parse(v1() + "&fp=" + fp + "&v=2");
        assertEquals(fp, p2.fp);
        assertEquals(2, p2.version);
        assertEquals(PC, p2.peer);
        assertArrayEquals(KEY, p2.key);
        assertTrue(p2.redacted().contains("tls=pinned"));
        assertFalse("日志不带指纹 / 钥匙", p2.redacted().contains(fp) || p2.redacted().contains(PcB64.encodeUrl(KEY)));

        PcPairing p1 = PcPairing.parse(v1());
        assertEquals("v1 串照样能配对（走明文）", "", p1.fp);
        assertEquals(1, p1.version);
        assertEquals("192.168.31.20:47820", p1.lanHosts);

        try { PcPairing.parse(v1() + "&fp=" + fp + "&v=3"); fail("更新版本要提示升级"); } catch (IllegalArgumentException e) { assertTrue(e.getMessage().contains("更新")); }
        try { PcPairing.parse(v1() + "&fp=abc&v=2"); fail("坏指纹"); } catch (IllegalArgumentException expected) { }
        try { PcPairing.parse(v1() + "&v=2"); fail("v2 缺指纹"); } catch (IllegalArgumentException expected) { }
        try { PcPairing.parse(v1() + "&v=x"); fail("版本不是数字"); } catch (IllegalArgumentException expected) { }
    }

    // ------------------------------------------------------------------ hello

    @Test public void helloCarriesSignedCfpAndHold() {
        long now = System.currentTimeMillis();
        Map<String, Object> h = PcProtocol.hello(KEY, "m1", ME, PC, now, "FP", true);
        assertEquals("FP", h.get("cfp"));
        assertEquals(Boolean.TRUE, h.get("hold"));
        assertEquals(PcProtocol.Check.OK, PcProtocol.verify(KEY, "m1", ME, PC, h, now, null));
        Map<String, Object> tampered = new TreeMap<String, Object>(h);
        tampered.put("cfp", "OTHER");
        assertEquals("cfp 在签名里，改了就验不过", PcProtocol.Check.BAD_SIG, PcProtocol.verify(KEY, "m1", ME, PC, tampered, now, null));
        Map<String, Object> v1 = PcProtocol.hello(KEY, "m2", ME, PC, now);
        assertFalse("v1 hello 不多字段（旧电脑端照收）", v1.containsKey("cfp") || v1.containsKey("hold"));
        assertEquals(new java.util.TreeSet<String>(java.util.Arrays.asList("kind", "nonce", "sig", "ts", "v")), v1.keySet());
    }

    // ------------------------------------------------------------------ 电脑 → 手机

    static final class Stub implements PcRx.Handler {
        final List<String> ran = new ArrayList<String>();
        final List<String> rec = new ArrayList<String>();
        final java.util.Set<String> off = new java.util.HashSet<String>();

        @Override public boolean enabled(String cmd) { return !off.contains(cmd); }

        @Override public PcLanClient.Result run(String cmd, Map<String, Object> args) {
            ran.add(cmd + args);
            Map<String, Object> d = new TreeMap<String, Object>();
            d.put("battery", 66L);
            return new PcLanClient.Result(true, "好了", d, "");
        }

        @Override public void record(String cmd, boolean ok, String text) { rec.add(cmd + (ok ? ":ok" : ":fail")); }
    }

    static String cmdFrame(byte[] key, String id, String from, String cmd, Map<String, Object> args, long ts) {
        Map<String, Object> env = new TreeMap<String, Object>();
        env.put("type", "msg");
        env.put("from", from);
        env.put("id", id);
        env.put("body", PcProtocol.command(key, id, from, ME, cmd, args, ts));
        return PcJson.stringify(env);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> replyBody(String reply) {
        return (Map<String, Object>) PcJson.obj(reply).get("body");
    }

    @Test public void signedCommandRunsAndReplySignedBack() {
        PcLanClient.Peer p = new PcLanClient.Peer(ME, PC, KEY, "", 47820, "");
        Stub h = new Stub();
        PcProtocol.ReplayGuard g = new PcProtocol.ReplayGuard();
        long now = System.currentTimeMillis();
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("action", "start");
        String reply = PcRx.onFrame(p, g, h, cmdFrame(KEY, "c1", PC, "find_phone", a, now), now);
        assertNotNull(reply);
        Map<String, Object> env = PcJson.obj(reply);
        assertEquals("msg", env.get("type"));
        assertEquals(PC, env.get("to"));
        Map<String, Object> b = replyBody(reply);
        assertEquals("c1", b.get("re"));
        assertEquals(Boolean.TRUE, b.get("ok"));
        assertEquals("电脑能验过手机的回复", PcProtocol.Check.OK, PcProtocol.verify(KEY, String.valueOf(env.get("id")), ME, PC, b, now, null));
        assertEquals(1, h.ran.size());
        assertEquals(java.util.Arrays.asList("find_phone:ok"), h.rec);
    }

    @Test public void replayStaleForgedAndForeignCommandsAreRejected() {
        PcLanClient.Peer p = new PcLanClient.Peer(ME, PC, KEY, "", 47820, "");
        Stub h = new Stub();
        PcProtocol.ReplayGuard g = new PcProtocol.ReplayGuard();
        long now = System.currentTimeMillis();
        String f = cmdFrame(KEY, "c1", PC, "screen_on", null, now);
        assertEquals(Boolean.TRUE, replyBody(PcRx.onFrame(p, g, h, f, now)).get("ok"));
        Map<String, Object> again = replyBody(PcRx.onFrame(p, g, h, f, now + 10));
        assertEquals("重放", Boolean.FALSE, again.get("ok"));
        assertEquals("拒绝：REPLAY", again.get("text"));
        Map<String, Object> stale = replyBody(PcRx.onFrame(p, g, h, cmdFrame(KEY, "c2", PC, "screen_on", null, now - 10 * 60000L), now));
        assertEquals("拒绝：STALE", stale.get("text"));
        byte[] evil = KEY.clone();
        evil[0] ^= 1;
        Map<String, Object> forged = replyBody(PcRx.onFrame(p, g, h, cmdFrame(evil, "c3", PC, "screen_on", null, now), now));
        assertEquals("拒绝：BAD_SIG", forged.get("text"));
        assertNull("不是配对的那台电脑：不理", PcRx.onFrame(p, g, h, cmdFrame(KEY, "c4", "pc-other", "screen_on", null, now), now));
        assertNull("不是指令：不理", PcRx.onFrame(p, g, h, "{\"type\":\"devices\",\"list\":[]}", now));
        assertNull(PcRx.onFrame(p, g, h, "garbage", now));
        assertEquals("只有第一条真执行了", 1, h.ran.size());
    }

    @Test public void switchesAndArgumentChecks() {
        Stub h = new Stub();
        h.off.add(PcRxPolicy.OPEN_APP);
        assertTrue(PcRx.handle(h, "ping", null).ok);
        PcLanClient.Result caps = PcRx.handle(h, "caps", null);
        List<?> list = (List<?>) caps.data.get("caps");
        assertTrue(list.contains("find_phone") && !list.contains("open_app"));
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("name", "微信");
        PcLanClient.Result r = PcRx.handle(h, "open_app", a);
        assertFalse(r.ok);
        assertTrue(r.text, r.text.contains("没开"));
        assertFalse(PcRx.handle(h, "rm_rf", null).ok);
        Map<String, Object> u = new TreeMap<String, Object>();
        u.put("url", "javascript:alert(1)");
        assertFalse(PcRx.handle(h, "open_url", u).ok);
        u.put("url", "intent://scan/#Intent;scheme=zxing;end");
        assertFalse(PcRx.handle(h, "open_url", u).ok);
        u.put("url", "https://example.com/x");
        assertTrue(PcRx.handle(h, "open_url", u).ok);
        Map<String, Object> v = new TreeMap<String, Object>();
        v.put("percent", 150L);
        assertFalse(PcRx.handle(h, "volume_set", v).ok);
        v.put("percent", 40L);
        assertTrue(PcRx.handle(h, "volume_set", v).ok);
        Map<String, Object> pk = new TreeMap<String, Object>();
        pk.put("package", "com.tencent.mm;rm");
        h.off.clear();
        assertFalse(PcRx.handle(h, "open_app", pk).ok);
        Map<String, Object> c = new TreeMap<String, Object>();
        c.put("text", "");
        assertFalse(PcRx.handle(h, "clipboard_set", c).ok);
        assertEquals(30, PcRxPolicy.ringSeconds(new TreeMap<String, Object>()));
        Map<String, Object> s = new TreeMap<String, Object>();
        s.put("seconds", 9999L);
        assertEquals(120, PcRxPolicy.ringSeconds(s));
    }

    @Test public void ossDefaultsAreConservative() {
        assertFalse("开源版：接受电脑指令默认关（不常连）", PcRxPolicy.MASTER_DEFAULT);
        for (String c : PcRxPolicy.CMDS) assertEquals(c, !PcRxPolicy.dangerous(c), PcRxPolicy.defaultOn(c));
        assertFalse(PcRxPolicy.defaultOn("open_url") || PcRxPolicy.defaultOn("open_app") || PcRxPolicy.defaultOn("clipboard_set"));
        assertTrue(PcRxPolicy.defaultOn("find_phone") && PcRxPolicy.defaultOn("status") && PcRxPolicy.defaultOn("notify"));
        assertTrue(PcRxPolicy.dangerous("open_url") && PcRxPolicy.dangerous("open_app") && PcRxPolicy.dangerous("clipboard_set"));
        assertFalse(PcRxPolicy.dangerous("find_phone"));
        assertEquals(8, PcRxPolicy.CMDS.length);
    }

    /** [第二轮] 加回来的 8 项：开源版总开关仍默认关，这 8 项也都默认关；控制台里全部 16 项各一个开关。 */
    @Test public void ossExtraDefaultsOff() {
        assertFalse(PcRxPolicy.MASTER_DEFAULT);
        assertEquals(8, PcRxPolicy.EXTRA.length);
        assertEquals(16, PcRxPolicy.ALL.length);
        for (String c : PcRxPolicy.EXTRA) {
            assertTrue(c, PcRxPolicy.known(c) && PcRxPolicy.extra(c));
            assertFalse("开源版默认关：" + c, PcRxPolicy.defaultOn(c));
            assertFalse(c, PcRxPolicy.label(c).equals(c));
            assertFalse(c, PcRxPolicy.sub(c).isEmpty());
        }
        // 原来的 8 项默认值不变
        assertTrue(PcRxPolicy.defaultOn("find_phone") && PcRxPolicy.defaultOn("status") && !PcRxPolicy.defaultOn("open_url"));
    }

    @Test public void announceVerifiedWithPairKeyOnly() {
        PcLanClient.Peer p = new PcLanClient.Peer(ME, PC, KEY, "", 47820, "");
        String n = "nonce12345";
        Map<String, Object> m = new TreeMap<String, Object>();
        m.put("t", "ann");
        m.put("v", 2L);
        m.put("port", 47820L);
        m.put("n", n);
        m.put("tag", PcProtocol.tag(KEY, "ann\n" + PC + "\n47820\n" + n));
        assertEquals(47820, PcLanClient.announcePort(p, PcJson.stringify(m)));
        m.put("tag", PcProtocol.tag(KEY, "ann\npc-other\n47820\n" + n));
        assertEquals("别的电脑的 announce 不认", -1, PcLanClient.announcePort(p, PcJson.stringify(m)));
        assertEquals(-1, PcLanClient.announcePort(p, "{}"));
        assertEquals("224.0.0.167", PcLanClient.GROUP);
    }

    // ------------------------------------------------------------------ 接线（读源码）

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    @Test public void wiring() throws Exception {
        String pl = src("app/src/com/onesay/assistant/v2/kernel/plugins/PcLinkPlugin.java");
        int init = pl.indexOf("public void onInit(");
        String body = pl.substring(init, pl.indexOf("@Override public int priority", init));
        assertTrue("常连在后台线程起（不占主线程）", body.contains("Threads.start(\"pclink-rx-init\"") && body.contains("PcRxAndroid.ensure(app)"));
        assertTrue(src("app/AndroidManifest.xml").contains("android.permission.CHANGE_WIFI_MULTICAST_STATE"));
        assertTrue(src("app/src/com/onesay/assistant/v2/security/SecretPrefs.java").contains("{\"bixby_pclink\", \"tls_key\"}"));
        String link = src("app/src/com/onesay/assistant/v2/pclink/PcLink.java");
        assertTrue("私钥加密保存", link.contains("SecretPrefs.put(e, K_TLS_KEY,") && link.contains("SecretPrefs.get(s, K_TLS_KEY)"));
        String rx = src("app/src/com/onesay/assistant/v2/pclink/PcRxAndroid.java");
        // 日志不打网址 / 剪贴板 / 通知内容
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Log\\.[iwde]\\(TAG, ([^;]*)\\);").matcher(rx);
        while (m.find()) {
            String arg = m.group(1);
            assertFalse(arg, arg.contains("u)") || arg.contains(" + u ") || arg.contains(" + t ") || arg.contains("title") || arg.contains("text"));
        }
        String ui = src("app/src/com/onesay/assistant/v2/pclink/PcLinkUi.java");
        assertTrue(ui.contains("buildRx(host, root, act);"));
        for (String f : new String[]{"pclink/PcRxAndroid.java", "pclink/PcRxPolicy.java", "pclink/PcRx.java", "pclink/PcTls.java"}) {
            String s = src("app/src/com/onesay/assistant/v2/" + f);
            java.util.regex.Matcher lm = java.util.regex.Pattern.compile("\"([^\"\\\\]|\\\\.)*\"").matcher(s);
            while (lm.find()) assertFalse(f + " " + lm.group(), lm.group().contains("Bixby") || lm.group().contains("三星") || lm.group().contains("Samsung"));
        }
    }
}
