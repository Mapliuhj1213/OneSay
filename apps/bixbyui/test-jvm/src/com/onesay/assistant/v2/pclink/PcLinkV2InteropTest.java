package com.onesay.assistant.v2.pclink;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * [pclink v2] 与电脑端 OneSay 0.5.0 真进程互通（真 socket，含 TLS）：
 * <ol>
 *   <li>逐字节：证书指纹算法、配对串 v2 解析与电脑端 {@code Config.importPairing} 一致；</li>
 *   <li>TLS：钉电脑证书指纹连上、下令；指纹不对 → tls；同一手机 id 换证书 → pin（4003）；用 TLS 连过再走明文 → pin（4002，防降级）；
 *       没见过的旧版（v1 明文）手机照样能用；</li>
 *   <li>电脑 → 手机：手机端 {@link PcRx.Loop} 常连（hold），测试经电脑端界面接口 /api/phone 下令：找手机 / 状态 / 剪贴板 /
 *       关着的项被拒；断开后电脑报离线不排队。</li>
 * </ol>
 * 电脑端 jar 同 {@link PcDesktopInteropTest}（缺省 /tmp/osd-build/onesay-desktop.jar，缺 jar 整类跳过）。
 */
public class PcLinkV2InteropTest {

    static File jar;
    static URLClassLoader cl;
    static Process proc;
    static Path dir;
    static int port, uiPort;
    static String token;
    static final byte[] KEY = new byte[32];
    static final String PC = "pc-v2";

    @BeforeClass public static void startDesktop() throws Exception {
        jar = PcDesktopInteropTest.findJar();
        Assume.assumeTrue("电脑端 jar 不在，v2 互通测试跳过", jar != null);
        cl = new URLClassLoader(new URL[]{jar.toURI().toURL()}, ClassLoader.getPlatformClassLoader());
        Assume.assumeTrue("电脑端 jar 不是 0.5.0+（没有 Tls 类），v2 互通测试跳过", hasClass("com.onesay.desktop.Tls"));
        new java.security.SecureRandom().nextBytes(KEY);
        port = PcDesktopInteropTest.freePort();
        dir = Files.createTempDirectory("pclink-v2");
        Path cfg = dir.resolve("desktop.properties").toAbsolutePath();
        String props = "lan_port=" + port + "\ndevice_id=" + PC + "\nkind=pc\nptt=off\npair_key=" + PcB64.encodeUrl(KEY) + "\n";
        Files.write(cfg, props.getBytes(StandardCharsets.UTF_8));
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-jar", jar.getAbsolutePath(), "--config", cfg.toString(), "app", "--tray");
        pb.environment().put("ONESAY_TEST_ACTIONS", "1");
        pb.environment().put("ONESAY_UI_PORT", "0");
        pb.environment().remove("APPDATA");
        pb.redirectErrorStream(true).redirectOutput(dir.resolve("desktop.out").toFile());
        proc = pb.start();
        long end = System.currentTimeMillis() + 20000;
        Pattern ui = Pattern.compile("界面 http://127\\.0\\.0\\.1:(\\d+)/");
        while (System.currentTimeMillis() < end && uiPort == 0) {
            if (!proc.isAlive()) fail("电脑端进程提前退出：" + out());
            Matcher m = ui.matcher(out());
            if (m.find()) uiPort = Integer.parseInt(m.group(1));
            else Thread.sleep(150);
        }
        assertTrue("电脑端界面没起来：" + out(), uiPort > 0);
        Matcher t = Pattern.compile("const TOKEN='([A-Za-z0-9_-]+)'").matcher(http("GET", "/", null, null));
        assertTrue(t.find());
        token = t.group(1);
    }

    static boolean hasClass(String n) {
        try {
            cl.loadClass(n);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    static String out() throws Exception {
        return new String(Files.readAllBytes(dir.resolve("desktop.out")), StandardCharsets.UTF_8);
    }

    @AfterClass public static void stopDesktop() throws Exception {
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

    static String http(String method, String path, String tok, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + uiPort + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(30000);
        if (tok != null) c.setRequestProperty("X-OneSay-Token", tok);
        if (body != null) {
            c.setDoOutput(true);
            OutputStream o = c.getOutputStream();
            o.write(body.getBytes(StandardCharsets.UTF_8));
            o.close();
        }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static Map<String, Object> api(String name, Map<String, Object> body) throws Exception {
        return PcJson.obj(http("POST", "/api/" + name, token, PcJson.stringify(body == null ? new TreeMap<String, Object>() : body)));
    }

    static String pairing() throws Exception {
        return String.valueOf(api("pairing", null).get("pairing"));
    }

    static PcLanClient.Peer peer(String me, PcTls tls, String fp) throws Exception {
        PcPairing p = PcPairing.parse(pairing());
        return new PcLanClient.Peer(me, p.peer, p.key, "127.0.0.1:" + port, p.lanPort, "", fp, tls);
    }

    // ------------------------------------------------------------------ 逐字节

    @Test public void fingerprintAndPairingMatchDesktop() throws Exception {
        String s = pairing();
        PcPairing mine = PcPairing.parse(s);
        assertEquals(2, mine.version);
        assertTrue(PcTls.validFp(mine.fp));
        Class<?> cfgC = cl.loadClass("com.onesay.desktop.Config");
        Object c = cfgC.getConstructor(Path.class).newInstance((Path) null);
        assertEquals(PC, cfgC.getMethod("importPairing", String.class).invoke(c, s));
        assertEquals(cfgC.getField("peerFp").get(c), mine.fp);
        Class<?> tlsC = cl.loadClass("com.onesay.desktop.Tls");
        PcTls t = PcTls.generate("x");
        byte[] der = t.cert.getEncoded();
        assertEquals("指纹算法两端一致", tlsC.getMethod("fingerprint", byte[].class).invoke(null, der), PcTls.fingerprint(der));
        assertEquals(tlsC.getMethod("shortFp", String.class).invoke(null, t.fp), PcTls.shortFp(t.fp));
        // 电脑端生成的证书手机端能解析、指纹一致
        Object dt = tlsC.getMethod("generate", String.class).invoke(null, "pc");
        String dfp = (String) tlsC.getField("fp").get(dt);
        PcTls back = PcTls.load(PcB64.encodeUrl(((java.security.PrivateKey) tlsC.getField("key").get(dt)).getEncoded()),
                PcB64.encodeUrl(((java.security.cert.X509Certificate) tlsC.getField("cert").get(dt)).getEncoded()));
        assertNotNull(back);
        assertEquals(dfp, back.fp);
        // 手机端生成的 hello（带 cfp/hold）电脑端验签通过
        Class<?> proto = cl.loadClass("com.onesay.desktop.Protocol");
        Class<?> guard = cl.loadClass("com.onesay.desktop.Protocol$ReplayGuard");
        Method verify = proto.getMethod("verify", byte[].class, String.class, String.class, String.class, Map.class, long.class, guard);
        long now = System.currentTimeMillis();
        assertEquals("OK", String.valueOf(verify.invoke(null, KEY, "h1", "phone-x", PC, PcProtocol.hello(KEY, "h1", "phone-x", PC, now, t.fp, true), now, null)));
    }

    // ------------------------------------------------------------------ TLS

    @Test public void tlsPinnedWorksAndMismatchesAreRejected() throws Exception {
        PcTls mine = PcTls.generate("phone-tls1");
        PcLanClient.Peer p = peer("phone-tls1", mine, null);
        p = new PcLanClient.Peer(p.me, p.peer, p.key, p.lanHosts, p.lanPort, "", PcPairing.parse(pairing()).fp, mine);
        InetSocketAddress a = PcLanClient.locate(p, 3000, null);
        assertNotNull(a);
        PcLanClient.Session s = PcLanClient.Session.open(p, a, 5000);
        try {
            assertTrue("走的是 TLS", s.tls());
            PcLanClient.Result r = s.request("ping", null, 5000);
            assertTrue(r.toString(), r.ok);
            PcLanClient.Result shot = s.request("screenshot", null, 15000);
            assertTrue("大消息过 TLS", shot.ok && PcB64.decode(String.valueOf(shot.data.get("b64"))).length > 1000);
        } finally {
            s.close();
        }

        // 配对串里的指纹不是这台电脑 → 握手不过
        PcLanClient.Peer wrong = new PcLanClient.Peer("phone-tls2", p.peer, p.key, p.lanHosts, p.lanPort, "", PcTls.generate("mitm").fp, PcTls.generate("p2"));
        PcLanClient.Result w = PcLanClient.once(wrong, "ping", null, 1500, 5000, null, null);
        assertFalse(w.ok);
        assertEquals("tls", w.fail);

        // 同一个手机 id 换了证书（重装 / 冒充）→ 电脑记住的是旧的 → 4003
        PcLanClient.Peer other = new PcLanClient.Peer("phone-tls1", p.peer, p.key, p.lanHosts, p.lanPort, "", p.fp, PcTls.generate("p1-new"));
        PcLanClient.Result o = PcLanClient.once(other, "ping", null, 1500, 5000, null, null);
        assertFalse(o.ok);
        assertEquals("pin", o.fail);

        // 用 TLS 连过的手机退回明文 → 4002（防降级）
        PcLanClient.Peer plain = new PcLanClient.Peer("phone-tls1", p.peer, p.key, p.lanHosts, p.lanPort, "");
        PcLanClient.Result d = PcLanClient.once(plain, "ping", null, 1500, 5000, null, null);
        assertFalse(d.ok);
        assertEquals("pin", d.fail);
        assertTrue(d.text, d.text.contains("加密"));

        // 没见过的旧版手机（v1 明文）照样能用
        PcLanClient.Peer legacy = new PcLanClient.Peer("phone-legacy", p.peer, p.key, p.lanHosts, p.lanPort, "");
        assertTrue(PcLanClient.once(legacy, "ping", null, 1500, 5000, null, null).ok);
    }

    // ------------------------------------------------------------------ 电脑 → 手机

    static final class JvmEnv implements PcRx.Env, PcRx.Handler {
        final PcLanClient.Peer p;
        volatile boolean wanted = true;
        volatile String state = "";
        final List<String> ran = new CopyOnWriteArrayList<String>();
        final List<String> rec = new CopyOnWriteArrayList<String>();

        JvmEnv(PcLanClient.Peer p) { this.p = p; }

        @Override public boolean wanted() { return wanted; }
        @Override public boolean onLan() { return true; }
        @Override public PcLanClient.Peer peer() { return p; }
        @Override public List<InetAddress> extraBroadcasts() { return null; }
        @Override public void multicast(boolean hold) { }
        @Override public void state(String s, String d) { state = s + " " + d; }
        @Override public void remember(InetSocketAddress a) { }
        @Override public PcRx.Handler handler() { return this; }
        @Override public boolean enabled(String cmd) { return !PcRxPolicy.OPEN_APP.equals(cmd); }

        @Override public PcLanClient.Result run(String cmd, Map<String, Object> args) {
            ran.add(cmd + (PcRxPolicy.CLIPBOARD.equals(cmd) ? "(" + String.valueOf(args.get("text")).length() + ")" : String.valueOf(args)));
            Map<String, Object> d = new TreeMap<String, Object>();
            if (PcRxPolicy.STATUS.equals(cmd)) {
                d.put("battery", 42L);
                d.put("charging", Boolean.FALSE);
                d.put("ringer", "静音");
            }
            return new PcLanClient.Result(true, PcRxPolicy.FIND.equals(cmd) ? "手机正在响（30 秒）" : "好", d, "");
        }

        @Override public void record(String cmd, boolean ok, String text) { rec.add(cmd + (ok ? ":ok" : ":fail")); }
    }

    static Map<String, Object> phone(String cmd, Map<String, Object> args) throws Exception {
        Map<String, Object> b = new TreeMap<String, Object>();
        b.put("cmd", cmd);
        b.put("args", args == null ? new TreeMap<String, Object>() : args);
        return api("phone", b);
    }

    @Test public void pcCommandsPhoneOverHeldTlsChannel() throws Exception {
        PcPairing pp = PcPairing.parse(pairing());
        PcLanClient.Peer p = new PcLanClient.Peer("phone-rx1", pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, PcTls.generate("phone-rx1"));
        JvmEnv env = new JvmEnv(p);
        PcRx.Loop loop = new PcRx.Loop(env);
        loop.minBackoff = 300;
        loop.maxBackoff = 1000;
        Thread t = new Thread(loop, "rx-test");
        t.setDaemon(true);
        t.start();
        try {
            long end = System.currentTimeMillis() + 10000;
            while (!env.state.startsWith(PcRx.S_UP) && System.currentTimeMillis() < end) Thread.sleep(100);
            assertTrue("常连没连上：" + env.state, env.state.startsWith(PcRx.S_UP));
            assertTrue(env.state, env.state.contains("加密"));
            Thread.sleep(200);

            Map<String, Object> st = api("state", null);
            assertEquals("电脑端知道这台手机常连着", "phone-rx1", st.get("phone"));
            assertEquals("局域网·加密", ((Map<?, ?>) st.get("peers")).get("phone-rx1"));

            Map<String, Object> a = new TreeMap<String, Object>();
            a.put("action", "start");
            Map<String, Object> r = phone("find_phone", a);
            assertEquals(String.valueOf(r), Boolean.TRUE, r.get("ok"));
            assertEquals("手机正在响（30 秒）", r.get("text"));

            Map<String, Object> s = phone("status", null);
            assertEquals(Boolean.TRUE, s.get("ok"));
            assertTrue(String.valueOf(s.get("text")), String.valueOf(s.get("text")).contains("电量 42%"));
            assertEquals(42L, ((Map<?, ?>) s.get("data")).get("battery"));

            Map<String, Object> c = new TreeMap<String, Object>();
            c.put("text", "从电脑来的文字 abc");
            assertEquals(Boolean.TRUE, phone("clipboard_set", c).get("ok"));

            Map<String, Object> app = new TreeMap<String, Object>();
            app.put("name", "微信");
            Map<String, Object> off = phone("open_app", app);
            assertEquals("手机上关着的项被拒", Boolean.FALSE, off.get("ok"));
            assertTrue(String.valueOf(off.get("text")), String.valueOf(off.get("text")).contains("没开"));

            Map<String, Object> bad = new TreeMap<String, Object>();
            bad.put("url", "file:///etc/passwd");
            assertEquals("电脑端先挡掉非 http(s)", Boolean.FALSE, phone("open_url", bad).get("ok"));

            assertEquals(3, env.ran.size());
            assertTrue(env.ran.toString(), env.ran.get(0).startsWith("find_phone") && env.ran.get(2).equals("clipboard_set(11)"));
            assertTrue(env.rec.contains("open_app:fail"));

            // 手机按钮那边的一次性连接不顶掉常连
            PcLanClient.Result once = PcLanClient.once(p, "ping", null, 1500, 5000, null, null);
            assertTrue(once.ok);
            Thread.sleep(200);
            assertEquals(Boolean.TRUE, phone("status", null).get("ok"));
        } finally {
            env.wanted = false;
            loop.stop();
            t.join(5000);
        }
        Thread.sleep(500);
        long t0 = System.currentTimeMillis();
        Map<String, Object> gone = phone("ping", null);
        assertEquals("手机断开后电脑报离线、不排队", Boolean.FALSE, gone.get("ok"));
        assertTrue(String.valueOf(gone.get("text")), String.valueOf(gone.get("text")).contains("离线"));
        assertTrue(System.currentTimeMillis() - t0 < 9000);
    }

    @Test public void healthzProbeAnswersOnSamePort() {
        assertTrue(PcLanClient.healthz(new InetSocketAddress("127.0.0.1", port), 1500));
        assertFalse(PcLanClient.healthz(new InetSocketAddress("127.0.0.1", 1), 500));
    }

    static List<String> lines(String s) {
        List<String> out = new ArrayList<String>();
        for (String l : s.split("\n")) out.add(l);
        return out;
    }

    static boolean reachable(int p) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", p), 300);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
