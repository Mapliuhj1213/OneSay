package com.onesay.assistant.v2.pclink;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * [pclink] 与电脑端 OneSay（0.4.1）真机互通：
 * <ol>
 *   <li>把电脑端 jar 载进来，用它的 {@code Json} / {@code Protocol} / {@code Config.importPairing} 与手机端实现逐字节比对；</li>
 *   <li>把电脑端起成真正的局域网服务（{@code --config <绝对路径> app --tray}，{@code ONESAY_TEST_ACTIONS=1}，无图形界面），
 *       手机端协议类走 UDP 发现 → WebSocket hello → 签名指令：ping、caps、notify、memory_sync、screenshot，并验证密钥不对会被拒。</li>
 * </ol>
 * jar 路径：系统属性 {@code onesay.desktop.jar} 或环境变量 {@code ONESAY_DESKTOP_JAR}，缺省 /tmp/osd-build/onesay-desktop.jar；
 * 找不到 jar 时整类跳过（Assume），报告里要单独列出。重建：{@code cd onesay-desktop && BUILD_DIR=/tmp/osd-build sh build.sh}。
 */
public class PcDesktopInteropTest {

    static File jar;
    static URLClassLoader cl;
    static Process proc;
    static Path dir;
    static int port;
    static com.sun.net.httpserver.HttpServer uiStub;
    static final byte[] KEY = new byte[32];
    static final String PC = "pc-interop";
    static final String ME = "phone-test1";

    static File findJar() {
        String p = System.getProperty("onesay.desktop.jar");
        if (p == null || p.isEmpty()) p = System.getenv("ONESAY_DESKTOP_JAR");
        if (p == null || p.isEmpty()) p = "/tmp/osd-build/onesay-desktop.jar";
        File f = new File(p);
        return f.isFile() ? f : null;
    }

    static int freePort() throws Exception {
        for (int i = 0; i < 50; i++) {
            ServerSocket s = new ServerSocket(0);
            int p = s.getLocalPort();
            s.close();
            if (p < 65000) {
                try (java.net.DatagramSocket d = new java.net.DatagramSocket(p + 1)) {
                    return p;
                } catch (Exception ignored) {
                }
            }
        }
        throw new IllegalStateException("no free port");
    }

    @BeforeClass public static void startDesktop() throws Exception {
        jar = findJar();
        Assume.assumeTrue("电脑端 jar 不在（/tmp/osd-build/onesay-desktop.jar），互通测试跳过", jar != null);
        cl = new URLClassLoader(new URL[]{jar.toURI().toURL()}, ClassLoader.getPlatformClassLoader());
        new java.security.SecureRandom().nextBytes(KEY);
        port = freePort();
        dir = Files.createTempDirectory("pclink-interop");
        Path cfg = dir.resolve("desktop.properties").toAbsolutePath();
        String props = "lan_port=" + port + "\n" + "device_id=" + PC + "\n" + "kind=pc\n" + "ptt=off\n"
                + "pair_key=" + PcB64.encodeUrl(KEY) + "\n";
        Files.write(cfg, props.getBytes(StandardCharsets.UTF_8));
        // 回归：机器上已经有一个 OneSay 在 127.0.0.1:47830 答 /ping 时也要能起来（以前整类因此报「进程提前退出」）。
        // 端口空着就自己占上一个假的，让这种情形每次都被测到；被别人占着就是现成的同一情形。
        try {
            uiStub = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 47830), 4);
            uiStub.createContext("/ping", ex -> {
                byte[] b = "onesay".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            uiStub.start();
        } catch (java.io.IOException busy) {
            uiStub = null;
        }
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        // 不用 -jar … app --tray：App.launch 发现 127.0.0.1:47830 上已有 OneSay 实例就直接退出（见 PcDesktopLauncher）
        String testClasses = new File(PcDesktopLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", jar.getAbsolutePath() + File.pathSeparator + testClasses,
                PcDesktopLauncher.class.getName(), cfg.toString());
        pb.environment().put("ONESAY_TEST_ACTIONS", "1");
        pb.environment().remove("APPDATA");
        pb.redirectErrorStream(true).redirectOutput(dir.resolve("desktop.out").toFile());
        proc = pb.start();
        long end = System.currentTimeMillis() + 20000;
        boolean up = false;
        while (!up && System.currentTimeMillis() < end) {
            if (!proc.isAlive()) fail("电脑端进程提前退出：" + new String(Files.readAllBytes(dir.resolve("desktop.out")), StandardCharsets.UTF_8));
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                up = true;
            } catch (Exception e) {
                Thread.sleep(150);
            }
        }
        if (!up) fail("电脑端 20 s 内没在局域网端口 " + port + " 上监听：" + new String(Files.readAllBytes(dir.resolve("desktop.out")), StandardCharsets.UTF_8));
    }

    @AfterClass public static void stopDesktop() throws Exception {
        if (uiStub != null) uiStub.stop(0);
        if (proc != null) {
            proc.destroy();
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly();
        }
        if (dir != null) {
            File[] fs = dir.toFile().listFiles();
            if (fs != null) for (File f : fs) f.delete();
            dir.toFile().delete();
        }
    }

    static PcLanClient.Peer peer(byte[] key, String hosts) {
        return new PcLanClient.Peer(ME, PC, key, hosts, port, "");
    }

    // ------------------------------------------------------------------ 逐字节比对

    @Test public void canonicalJsonMatchesDesktop() throws Exception {
        Method st = cl.loadClass("com.onesay.desktop.Json").getMethod("stringify", Object.class);
        Method parse = cl.loadClass("com.onesay.desktop.Json").getMethod("parse", String.class);
        String[] samples = {
                "{\"z\":1,\"a\":\"x\\\"y\\\\\\n\\t\\u0001\\u001f é 中\",\"m\":[true,null,2.5,3.0,-7,1e3,123456789012345678],\"o\":{\"b\":{},\"B\":[]}}",
                "{\"args\":{\"text\":\"明早 8:00 开会\\r\\n带电脑\"},\"cmd\":\"notify\",\"kind\":\"cmd\",\"ts\":1762560000000,\"v\":1}",
                "[0.1,-0.0,1.0E15,1.5E-7,\"\\b\\f/\"]",
        };
        for (String s : samples) {
            assertEquals(st.invoke(null, parse.invoke(null, s)), PcJson.stringify(PcJson.parse(s)));
            // 交叉：一边解析、另一边输出
            assertEquals(st.invoke(null, parse.invoke(null, s)), st.invoke(null, PcJson.parse(s)));
        }
    }

    @Test @SuppressWarnings("unchecked")
    public void signaturesInteroperateBothWays() throws Exception {
        Class<?> proto = cl.loadClass("com.onesay.desktop.Protocol");
        Class<?> guard = cl.loadClass("com.onesay.desktop.Protocol$ReplayGuard");
        Method verify = proto.getMethod("verify", byte[].class, String.class, String.class, String.class, Map.class, long.class, guard);
        Method result = proto.getMethod("result", byte[].class, String.class, String.class, String.class, String.class, boolean.class, String.class, Map.class, long.class);
        Method tag = proto.getMethod("tag", byte[].class, String.class);
        long now = System.currentTimeMillis();
        Map<String, Object> args = new TreeMap<String, Object>();
        args.put("title", "日程提醒");
        args.put("text", "10:00 组会\n带电脑");
        args.put("n", 3L);
        Map<String, Object> mine = PcProtocol.command(KEY, "m-abc", ME, PC, "notify", args, now);
        assertEquals("OK", String.valueOf(verify.invoke(null, KEY, "m-abc", ME, PC, mine, now, null)));
        assertEquals("BAD_SIG", String.valueOf(verify.invoke(null, KEY, "m-abc", PC, ME, mine, now, null)));
        Map<String, Object> data = new TreeMap<String, Object>();
        data.put("text", "记忆");
        Map<String, Object> theirs = (Map<String, Object>) result.invoke(null, KEY, "r-1", PC, ME, "m-abc", true, "已在电脑上提醒", data, now);
        assertEquals(PcProtocol.Check.OK, PcProtocol.verify(KEY, "r-1", PC, ME, theirs, now, new PcProtocol.ReplayGuard()));
        assertEquals(PcProtocol.Check.BAD_SIG, PcProtocol.verify(KEY, "r-1", ME, PC, theirs, now, null));
        assertEquals(tag.invoke(null, KEY, "here\npc\n47820\nabc"), PcProtocol.tag(KEY, "here\npc\n47820\nabc"));
    }

    @Test public void pairingParseMatchesDesktopImport() throws Exception {
        Class<?> cfgC = cl.loadClass("com.onesay.desktop.Config");
        Object cfg = cfgC.getConstructor(Path.class).newInstance(dir.resolve("x.properties"));
        Method imp = cfgC.getMethod("importPairing", String.class);
        String s = PcPairing.PREFIX + "key=" + PcB64.encodeUrl(KEY) + "&peer=pc-home&lan=" + java.net.URLEncoder.encode("192.168.31.20:47820,0.0.0.0:47820,10.1.2.3:47820", "UTF-8");
        String peerTheirs = (String) imp.invoke(cfg, s);
        PcPairing mine = PcPairing.parse(s);
        assertEquals(peerTheirs, mine.peer);
        assertEquals(cfgC.getField("lanHosts").get(cfg), mine.lanHosts);
        assertEquals(cfgC.getField("lanPort").get(cfg), mine.lanPort);
        assertArrayEquals((byte[]) cfgC.getField("pairKey").get(cfg), mine.key);
        // 电脑端自己生成的配对串，手机端能解析
        Object pc = cfgC.getConstructor(Path.class).newInstance(dir.resolve("y.properties"));
        cfgC.getField("deviceId").set(pc, "pc-gen");
        cfgC.getField("pairKey").set(pc, KEY);
        cfgC.getField("lanPort").set(pc, 47820);
        String gen = (String) cfgC.getMethod("pairingString").invoke(pc);
        PcPairing g = PcPairing.parse(gen);
        assertEquals("pc-gen", g.peer);
        assertEquals(47820, g.lanPort);
        assertArrayEquals(KEY, g.key);
    }

    // ------------------------------------------------------------------ 真服务互通

    @Test public void discoveryFindsDesktopOverUdp() {
        InetSocketAddress a = PcLanClient.locate(peer(KEY, "127.0.0.1:" + port), 3000, null);
        assertNotNull("UDP 发现应找到电脑端", a);
        assertEquals(port, a.getPort());
    }

    @Test public void pingCapsNotifyMemorySyncScreenshot() throws Exception {
        PcLanClient.Peer p = peer(KEY, "127.0.0.1:" + port);
        InetSocketAddress a = PcLanClient.locate(p, 3000, null);
        assertNotNull(a);
        PcLanClient.Session s = PcLanClient.Session.open(p, a, 5000);
        try {
            PcLanClient.Result ping = s.request("ping", null, 5000);
            assertTrue(ping.toString(), ping.ok && ping.reached());
            assertEquals("在线", ping.text);

            PcLanClient.Result caps = s.request("caps", null, 5000);
            assertTrue(caps.toString(), caps.ok);
            List<?> list = (List<?>) caps.data.get("caps");
            assertTrue(String.valueOf(list), list.contains("ping") && list.contains("screenshot") && list.contains("notify") && list.contains("memory_sync"));

            Map<String, Object> n = new TreeMap<String, Object>();
            n.put("kind", "reminder");
            n.put("title", "互通测试提醒");
            n.put("text", "10:00 组会");
            PcLanClient.Result nr = s.request("notify", n, 5000);
            assertTrue(nr.toString(), nr.ok);
            assertEquals("已在电脑上提醒", nr.text);

            long at = System.currentTimeMillis();
            Map<String, Object> m = new TreeMap<String, Object>();
            m.put("text", "用户喜欢简短回答");
            m.put("updated_at", at);
            PcLanClient.Result mr = s.request("memory_sync", m, 5000);
            assertTrue(mr.toString(), mr.ok);
            assertEquals("用户喜欢简短回答", mr.data.get("text"));
            assertEquals(Long.valueOf(at), mr.data.get("updated_at"));
            // 旧的不覆盖新的
            m.put("text", "旧记忆");
            m.put("updated_at", at - 1000);
            PcLanClient.Result mr2 = s.request("memory_sync", m, 5000);
            assertEquals("用户喜欢简短回答", mr2.data.get("text"));

            PcLanClient.Result shot = s.request("screenshot", null, 15000);
            assertTrue(shot.toString(), shot.ok);
            byte[] jpg = PcB64.decode(String.valueOf(shot.data.get("b64")));
            assertTrue(jpg.length > 1000);
            assertEquals((byte) 0xFF, jpg[0]);
            assertEquals((byte) 0xD8, jpg[1]);
            assertEquals("image/jpeg", shot.data.get("mime"));

            PcLanClient.Result bad = s.request("no_such_cmd", null, 5000);
            assertFalse(bad.ok);
            assertTrue(bad.reached());
        } finally {
            s.close();
        }
        // 电脑端确实记下了这条通知（state.json 的 notes）
        String state = new String(Files.readAllBytes(dir.resolve("state.json")), StandardCharsets.UTF_8);
        assertTrue(state.contains("互通测试提醒"));
        assertTrue(state.contains(ME));
    }

    @Test public void oneShotHelperAndTcpFallback() {
        // 提示地址正确 → 一次性下令
        PcLanClient.Result r = PcLanClient.once(peer(KEY, "127.0.0.1:" + port), "ping", null, 2000, 5000, null, null);
        assertTrue(r.toString(), r.ok);
    }

    @Test public void wrongKeyIsRejected() throws Exception {
        byte[] other = KEY.clone();
        other[5] ^= 0x40;
        PcLanClient.Peer p = peer(other, "127.0.0.1:" + port);
        // 发现只回应自己人：密钥不对 → UDP 没应答，退到 TCP 试连提示地址，然后 hello 被拒
        PcLanClient.Result r = PcLanClient.once(p, "ping", null, 1200, 5000, null, null);
        assertFalse(r.ok);
        assertEquals("auth", r.fail);
    }

    @Test public void unreachableIsOfflineNotQueued() {
        PcLanClient.Peer p = new PcLanClient.Peer(ME, "pc-nowhere", KEY, "127.0.0.1:1", 1, "");
        long t0 = System.currentTimeMillis();
        PcLanClient.Result r = PcLanClient.once(p, "notify", null, 800, 3000, null, null);
        assertFalse(r.ok);
        assertEquals("offline", r.fail);
        assertTrue(System.currentTimeMillis() - t0 < 5000);
    }

    /**
     * 二维码这条路：用电脑端 jar 里自带的 qr.js（设置页画二维码的同一份，ECC M）把电脑端自己生成的配对串编成二维码，
     * 按扫码页 PcScanActivity 的解码方式（ZXing MultiFormatReader，只认 QR，UTF-8，PlanarYUVLuminanceSource + HybridBinarizer）解回来，
     * 必须一字不差、被认作配对码并能解析。机器上没有 node 时跳过（Assume）。
     */
    @Test public void qrPayloadFromDesktopScansAndPairs() throws Exception {
        String node = null;
        for (String n : new String[]{"node", "nodejs"}) {
            try {
                Process v = new ProcessBuilder(n, "--version").redirectErrorStream(true).start();
                if (v.waitFor(10, TimeUnit.SECONDS) && v.exitValue() == 0) { node = n; break; }
            } catch (java.io.IOException ignored) {
            }
        }
        Assume.assumeTrue("没有 node，跑不了电脑端的 qr.js", node != null);
        java.io.InputStream in = cl.getResourceAsStream("onesay/ui/qr.js");
        assertNotNull("电脑端 jar 里有 onesay/ui/qr.js", in);
        Path qrjs = dir.resolve("qr.js");
        Files.copy(in, qrjs, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        in.close();

        Class<?> cfgC = cl.loadClass("com.onesay.desktop.Config");
        Object pc = cfgC.getConstructor(Path.class).newInstance(dir.resolve("qr.properties"));
        cfgC.getField("deviceId").set(pc, "pc-qr");
        cfgC.getField("pairKey").set(pc, KEY);
        cfgC.getField("lanPort").set(pc, 47820);
        String gen = (String) cfgC.getMethod("pairingString").invoke(pc);

        Path js = dir.resolve("qr-matrix.js");
        Files.write(js, ("const fs=require('fs'),vm=require('vm');vm.runInThisContext(fs.readFileSync(process.argv[2],'utf8')+';globalThis.__qr=qrcode;');"
                + "const q=__qr(0,'M');q.addData(fs.readFileSync(process.argv[3],'utf8'));q.make();const n=q.getModuleCount();let o='';"
                + "for(let r=0;r<n;r++){for(let c=0;c<n;c++)o+=q.isDark(r,c)?'1':'0';o+='\\n'}process.stdout.write(o);").getBytes(StandardCharsets.UTF_8));
        Path payload = dir.resolve("payload.txt");
        Files.write(payload, gen.getBytes(StandardCharsets.UTF_8));
        Process p = new ProcessBuilder(node, js.toString(), qrjs.toString(), payload.toString()).redirectErrorStream(true).start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertEquals(out, 0, p.exitValue());
        String[] rows = out.trim().split("\n");
        int n = rows.length, scale = 6, quiet = 4, w = (n + 2 * quiet) * scale;
        byte[] lum = new byte[w * w];
        java.util.Arrays.fill(lum, (byte) 0xFF);
        for (int r = 0; r < n; r++) {
            assertEquals(n, rows[r].length());
            for (int c = 0; c < n; c++) {
                if (rows[r].charAt(c) != '1') continue;
                for (int dy = 0; dy < scale; dy++) for (int dx = 0; dx < scale; dx++) lum[((r + quiet) * scale + dy) * w + (c + quiet) * scale + dx] = 0;
            }
        }
        com.google.zxing.MultiFormatReader reader = new com.google.zxing.MultiFormatReader();
        Map<com.google.zxing.DecodeHintType, Object> hints = new java.util.EnumMap<com.google.zxing.DecodeHintType, Object>(com.google.zxing.DecodeHintType.class);
        hints.put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
        hints.put(com.google.zxing.DecodeHintType.CHARACTER_SET, "UTF-8");
        reader.setHints(hints);
        com.google.zxing.PlanarYUVLuminanceSource src = new com.google.zxing.PlanarYUVLuminanceSource(lum, w, w, 0, 0, w, w, false);
        String text = reader.decodeWithState(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(src))).getText();
        assertEquals(gen, text);
        assertTrue(PcPairing.looksLike(text));
        PcPairing got = PcPairing.parse(text);
        assertEquals("pc-qr", got.peer);
        assertEquals(47820, got.lanPort);
        assertArrayEquals(KEY, got.key);
        // 电脑端自己也认这串（同一个串两边都收）
        Object back = cfgC.getConstructor(Path.class).newInstance(dir.resolve("qr2.properties"));
        assertEquals("pc-qr", cfgC.getMethod("importPairing", String.class).invoke(back, text));
    }

    static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int k; (k = in.read(buf)) > 0; ) b.write(buf, 0, k);
        return b.toByteArray();
    }
}
