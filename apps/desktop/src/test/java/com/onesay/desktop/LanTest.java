package com.onesay.desktop;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

public class LanTest {
    static int freePortPair() throws Exception {
        for (int i = 0; i < 50; i++) {
            int p;
            try (ServerSocket s = new ServerSocket(0)) { p = s.getLocalPort(); }
            if (p < 65000) {
                try (java.net.DatagramSocket d = new java.net.DatagramSocket(p + 1)) { return p; } catch (Exception ignored) { }
            }
        }
        throw new IllegalStateException("no port");
    }

    static Config pc(int port) throws Exception {
        Config c = new Config(Files.createTempFile("pc", ".properties"));
        c.deviceId = "pc-lan";
        c.lanPort = port;
        c.pairKey = Protocol.newKey();
        c.save();
        return c;
    }

    static Config client(Config pc, String hostHint) throws Exception {
        Config c = new Config(Files.createTempFile("cli", ".properties"));
        assertEquals("pc-lan", c.importPairing(pc.pairingString()));
        if (hostHint != null) c.lanHosts = hostHint;
        c.deviceId = "cli-lan";
        c.kind = "other";
        return c;
    }

    @Test public void lanOnlyPairingNeedsNoRelay() throws Exception {
        Config a = pc(47820);
        assertTrue(a.ready());
        assertFalse(a.relayEnabled());
        String ps = a.pairingString();
        assertFalse(ps.contains("relay="));
        assertTrue(ps.contains("lan="));
        Config b = client(a, null);
        assertEquals(47820, b.lanPort);
        assertFalse(b.relayEnabled());
        assertArrayEquals(a.pairKey, b.pairKey);
        Config again = Config.load(a.file);
        assertEquals(47820, again.lanPort);
        try { b.importPairing("onesay://pair?key=" + "A".repeat(43) + "&peer=p"); fail("既无局域网也无中转"); } catch (IllegalArgumentException expected) { }
        try { b.importPairing("onesay://pair?key=" + "A".repeat(43) + "&peer=../x&lan=1.2.3.4:5"); fail("非法 peer"); } catch (IllegalArgumentException expected) { }
    }

    @Test public void oldRelayOnlyPairingStillImports() throws Exception {
        Config b = new Config(null);
        String key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Protocol.newKey());
        assertEquals("pc-x", b.importPairing("onesay://pair?relay=https%3A%2F%2Fr.example&token=" + "t".repeat(30) + "&key=" + key + "&peer=pc-x"));
        assertFalse(b.lanEnabled());
        assertTrue(b.relayEnabled());
    }

    @Test public void probeAnsweredOnlyForPairedDevices() throws Exception {
        Config pc = pc(47820);
        String n = "abcdefghij";
        Map<String, Object> m = new TreeMap<>();
        m.put("t", "probe"); m.put("from", "cli"); m.put("to", "pc-lan"); m.put("n", n);
        m.put("tag", Protocol.tag(pc.pairKey, "probe\ncli\npc-lan\n" + n));
        String r = LanServer.answerProbe(pc, Json.stringify(m));
        assertNotNull(r);
        Map<String, Object> rr = Json.obj(r);
        assertTrue(Protocol.tagOk(pc.pairKey, "here\npc-lan\n47820\n" + n, rr.get("tag")));
        m.put("tag", Protocol.tag(Protocol.newKey(), "probe\ncli\npc-lan\n" + n));
        assertNull("错钥匙的探测不回", LanServer.answerProbe(pc, Json.stringify(m)));
        m.put("tag", Protocol.tag(pc.pairKey, "probe\ncli\npc-lan\n" + n));
        m.put("to", "pc-other");
        assertNull(LanServer.answerProbe(pc, Json.stringify(m)));
        assertNull(LanServer.answerProbe(pc, "garbage"));
        pc.peers = "phone";
        m.put("to", "pc-lan");
        assertNull("不在允许名单", LanServer.answerProbe(pc, Json.stringify(m)));
    }

    @Test public void wsFramesRoundTrip() throws Exception {
        byte[] mask = {1, 2, 3, 4};
        for (int len : new int[]{0, 1, 125, 126, 127, 65535, 65536, 200_000}) {
            byte[] p = new byte[len];
            for (int i = 0; i < len; i++) p[i] = (byte) (i * 31);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            Ws.write(o, Ws.OP_TEXT, p, mask);
            Ws.Frame f = Ws.read(new ByteArrayInputStream(o.toByteArray()), true);
            assertEquals(Ws.OP_TEXT, f.op);
            assertTrue(f.fin);
            assertArrayEquals("len " + len, p, f.payload);
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Ws.write(o, Ws.OP_TEXT, "x".getBytes());
        try { Ws.read(new ByteArrayInputStream(o.toByteArray()), true); fail("服务端必须拒收无掩码帧"); } catch (java.io.IOException expected) { }
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", Ws.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    /** 不给任何地址提示，只靠广播 + 配对串里的本机地址找电脑（沙箱网络允许时）。 */
    @Test public void discoveryWithoutManualHint() throws Exception {
        int port = freePortPair();
        Config pcCfg = pc(port);
        Agent agent = new Agent(pcCfg, new Main.TestActions(), s -> { });
        agent.start();
        try {
            Config cli = client(pcCfg, null);
            assertEquals("", cli.lanHosts.contains("127.0.0.1") ? "loopback leaked" : "");
            java.net.InetSocketAddress a = LanClient.locate(cli, "pc-lan", 2000);
            org.junit.Assume.assumeTrue("此网络找不到本机局域网地址也不放行广播，跳过", a != null || !LanClient.localAddresses().isEmpty());
            assertNotNull("有局域网地址时应能找到", a);
            assertEquals(port, a.getPort());
        } finally {
            agent.stop();
        }
    }

    @Test public void lanEndToEnd() throws Exception {
        int port = freePortPair();
        Config pcCfg = pc(port);
        StringBuilder log = new StringBuilder();
        Agent agent = new Agent(pcCfg, new Main.TestActions(), s -> { synchronized (log) { log.append(s).append('\n'); } });
        agent.start();
        Thread.sleep(300);
        assertTrue(agent.connected());
        Path out = Files.createTempDirectory("shots");
        try {
            Config cli = client(pcCfg, "127.0.0.1:" + port);
            assertEquals(0, Cli.send(cli, "pc-lan", "ping", new TreeMap<>(), out, 10));
            assertEquals("记住上次地址", "127.0.0.1:" + port, Config.load(cli.file).lanLast);
            assertEquals(0, Cli.send(cli, "pc-lan", "screenshot", new TreeMap<>(), out, 10));
            assertEquals(1, Files.list(out).count());
            Map<String, Object> args = new TreeMap<>();
            args.put("text", "你好");
            assertEquals(0, Cli.send(cli, "pc-lan", "echo", args, out, 10));
            assertEquals(1, Cli.send(cli, "pc-lan", "format_disk", new TreeMap<>(), out, 10));

            // 错钥匙：握手被拒，指令根本到不了执行
            Config evil = client(pcCfg, "127.0.0.1:" + port);
            evil.pairKey = Protocol.newKey();
            evil.deviceId = "evil";
            assertEquals(2, Cli.send(evil, "pc-lan", "screenshot", new TreeMap<>(), out, 10));
            assertFalse(log.toString().contains("来自 evil"));
            assertEquals(1, Files.list(out).count());
        } finally {
            agent.stop();
        }
        Thread.sleep(200);
        Config cli = client(pcCfg, "127.0.0.1:" + port);
        long t0 = System.currentTimeMillis();
        assertEquals("电脑关了 = 离线", 2, Cli.send(cli, "pc-lan", "ping", new TreeMap<>(), out, 10));
        assertTrue("离线要快", System.currentTimeMillis() - t0 < 6000);
    }
}
