package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

public class CoreTest {
    static final byte[] K = new byte[32];
    static final byte[] K2 = new byte[32];
    static { for (int i = 0; i < 32; i++) { K[i] = (byte) i; K2[i] = (byte) (i + 1); } }

    @Test public void jsonRoundTripSortedKeys() {
        Map<String, Object> m = Json.obj("{\"b\":1,\"a\":[true,null,\"x\\n\\u4e2d\"],\"c\":{\"z\":1.5,\"y\":-2}}");
        assertEquals("{\"a\":[true,null,\"x\\n中\"],\"b\":1,\"c\":{\"y\":-2,\"z\":1.5}}", Json.stringify(m));
        assertEquals(Json.stringify(m), Json.stringify(Json.parse(Json.stringify(m))));
    }

    @Test public void jsonRejectsGarbage() {
        for (String bad : new String[]{"{", "{\"a\":}", "[1,]x", "{\"a\" 1}", "nul", "\"abc", "1 2"}) {
            try { Json.parse(bad); fail("should reject " + bad); } catch (IllegalArgumentException | StringIndexOutOfBoundsException expected) { }
        }
    }

    @Test public void jsonDepthLimit() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 100; i++) s.append('[');
        for (int i = 0; i < 100; i++) s.append(']');
        try { Json.parse(s.toString()); fail(); } catch (IllegalArgumentException expected) { }
    }

    static Map<String, Object> wire(Map<String, Object> body) {
        // 模拟经过网络：序列化再解析
        return Json.obj(Json.stringify(body));
    }

    @Test public void commandVerifies() {
        long now = 1_700_000_000_000L;
        Map<String, Object> args = new TreeMap<>();
        args.put("url", "https://example.com/?q=中文");
        args.put("n", 3L);
        Map<String, Object> b = wire(Protocol.command(K, "m1", "phone", "pc", "open_url", args, now));
        assertEquals(Protocol.Check.OK, Protocol.verify(K, "m1", "phone", "pc", b, now + 1000, new Protocol.ReplayGuard()));
    }

    @Test public void tamperingIsCaught() {
        long now = 1_700_000_000_000L;
        Map<String, Object> b = wire(Protocol.command(K, "m1", "phone", "pc", "screenshot", null, now));
        assertEquals(Protocol.Check.BAD_SIG, Protocol.verify(K2, "m1", "phone", "pc", b, now, null));
        assertEquals(Protocol.Check.BAD_SIG, Protocol.verify(K, "m2", "phone", "pc", b, now, null));
        assertEquals(Protocol.Check.BAD_SIG, Protocol.verify(K, "m1", "evil", "pc", b, now, null));
        assertEquals(Protocol.Check.BAD_SIG, Protocol.verify(K, "m1", "phone", "pc2", b, now, null));
        Map<String, Object> t = new TreeMap<>(b);
        t.put("cmd", "shutdown");
        assertEquals(Protocol.Check.BAD_SIG, Protocol.verify(K, "m1", "phone", "pc", t, now, null));
        t = new TreeMap<>(b);
        t.remove("sig");
        assertEquals(Protocol.Check.NO_SIG, Protocol.verify(K, "m1", "phone", "pc", t, now, null));
    }

    @Test public void staleAndReplay() {
        long now = 1_700_000_000_000L;
        Map<String, Object> b = wire(Protocol.command(K, "m1", "phone", "pc", "ping", null, now));
        assertEquals(Protocol.Check.STALE, Protocol.verify(K, "m1", "phone", "pc", b, now + Protocol.MAX_SKEW_MS + 1, null));
        assertEquals(Protocol.Check.STALE, Protocol.verify(K, "m1", "phone", "pc", b, now - Protocol.MAX_SKEW_MS - 1, null));
        Protocol.ReplayGuard g = new Protocol.ReplayGuard();
        assertEquals(Protocol.Check.OK, Protocol.verify(K, "m1", "phone", "pc", b, now, g));
        assertEquals(Protocol.Check.REPLAY, Protocol.verify(K, "m1", "phone", "pc", b, now + 10, g));
    }

    @Test public void resultVerifies() {
        long now = 1_700_000_000_000L;
        Map<String, Object> d = new TreeMap<>();
        d.put("b64", "AAAA");
        d.put("width", 10L);
        Map<String, Object> r = wire(Protocol.result(K, "r1", "pc", "phone", "m1", true, "截图 10×10", d, now));
        assertEquals(Protocol.Check.OK, Protocol.verify(K, "r1", "pc", "phone", r, now, new Protocol.ReplayGuard()));
        assertEquals("m1", r.get("re"));
    }

    @Test public void pairingRoundTrip() throws Exception {
        Path f = Files.createTempFile("onesay", ".properties");
        Config a = new Config(f);
        a.relayUrl = "https://onesay.example.com";
        a.relayToken = "t".repeat(40) + "&=?";
        a.deviceId = "pc-home";
        a.pairKey = Protocol.newKey();
        a.save();
        Config b = new Config(Files.createTempFile("onesay", ".properties"));
        assertEquals("pc-home", b.importPairing(a.pairingString()));
        assertEquals(a.relayToken, b.relayToken);
        assertArrayEquals(a.pairKey, b.pairKey);
        Config c = Config.load(f);
        assertTrue(c.ready());
        assertArrayEquals(a.pairKey, c.pairKey);
        assertEquals("wss://onesay.example.com/v1/ws?device=pc-home&kind=pc", c.wsUri().toString());
        try { b.importPairing("https://evil"); fail(); } catch (IllegalArgumentException expected) { }
        try { b.importPairing("onesay://pair?relay=x&token=short&key=AAAA&peer=p"); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void peersAndApps() {
        Config c = new Config(null);
        assertTrue(c.peerAllowed("anyone"));
        c.peers = "phone, cli-laptop";
        assertTrue(c.peerAllowed("cli-laptop"));
        assertFalse(c.peerAllowed("phone2"));
        c.apps = "微信=C:\\WeChat\\WeChat.exe; Code = C:\\VS\\Code.exe";
        assertEquals("C:\\VS\\Code.exe", c.appMap().get("code"));
        assertEquals(2, c.appMap().size());
    }

    @Test public void safeChildBlocksTraversal() throws Exception {
        Path dir = Files.createTempDirectory("onesay");
        Files.writeString(dir.resolve("ok.txt"), "hi");
        Files.createDirectory(dir.resolve("sub"));
        assertNotNull(DesktopActions.safeChild(dir.toString(), "ok.txt"));
        for (String bad : new String[]{"../ok.txt", "..", "sub", "sub/ok.txt", "sub\\x", "C:x", "", "missing.txt", "/etc/passwd"}) {
            assertNull(bad, DesktopActions.safeChild(dir.toString(), bad));
        }
        assertNull(DesktopActions.safeChild("", "ok.txt"));
    }

    @Test public void dangerousNeedConfirmAndUnknownRejected() throws Exception {
        Config c = new Config(null);
        DesktopActions d = new DesktopActions(c);
        assertFalse(d.run("shutdown", Map.of()).ok);
        assertFalse(d.run("sleep", Map.of("confirm", "yes")).ok);
        assertFalse(d.run("rm_rf", Map.of()).ok);
        assertFalse(d.run("open_url", Map.of("url", "file:///C:/Windows/System32/calc.exe")).ok);
        assertFalse(d.run("open_url", Map.of("url", "javascript:alert(1)")).ok);
        assertFalse(d.run("open_app", Map.of("name", "cmd")).ok);
        assertFalse(d.run("shortcut_run", Map.of("name", "x")).ok);
        assertFalse(d.run("volume", Map.of("action", "explode")).ok);
        List<String> caps = d.capabilities();
        assertFalse(caps.contains("file_get"));
        assertFalse(caps.contains("shortcut_run"));
    }

    @Test public void screenshotImageIsCompressedAndBounded() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(3840, 2160, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Map<String, Object> d = DesktopActions.imageData(img);
        assertEquals(1600L, d.get("width"));
        assertEquals(900L, d.get("height"));
        assertTrue(((String) d.get("b64")).length() < 2_000_000);
    }
}
