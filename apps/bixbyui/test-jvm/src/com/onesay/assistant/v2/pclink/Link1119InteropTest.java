package com.onesay.assistant.v2.pclink;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * [overnight-1119] 双端四条链路，手机侧代码对真电脑端进程（真 socket + TLS）：
 * <ol>
 *   <li>手机 → 电脑任务：pc_task 走到电脑的任务入口（测试电脑没配大模型 → 明说，不假装开始）、pc_task_status 回话；</li>
 *   <li>文件互传：手机 {@link PcFileRx#send} 分块发到电脑工作区「来自手机」，字节一致；电脑界面「发到手机」经常连通道分块到手机 {@link PcFileRx}，字节一致；</li>
 *   <li>快捷指令：电脑经界面查手机快捷指令 / 让手机运行，手机侧开关管着；</li>
 *   <li>电脑 → 手机任务：operate / operate_status 经常连通道到手机。</li>
 * </ol>
 * 电脑端 jar 同 {@link PcDesktopInteropTest}（ONESAY_DESKTOP_JAR，缺省 /tmp/osd-build/onesay-desktop.jar，缺 jar 整类跳过）。
 */
public class Link1119InteropTest {
    static File jar;
    static Process proc;
    static Path dir;
    static int port, uiPort;
    static String token;
    static final byte[] KEY = new byte[32];
    static final String PC = "pc-1119";

    @BeforeClass public static void startDesktop() throws Exception {
        jar = PcDesktopInteropTest.findJar();
        Assume.assumeTrue("电脑端 jar 不在，互通测试跳过", jar != null);
        new java.security.SecureRandom().nextBytes(KEY);
        port = PcDesktopInteropTest.freePort();
        dir = Files.createTempDirectory("link1119");
        Path ws = dir.resolve("ws");
        Path cfg = dir.resolve("desktop.properties").toAbsolutePath();
        String props = "lan_port=" + port + "\ndevice_id=" + PC + "\nkind=pc\nptt=off\npair_key=" + PcB64.encodeUrl(KEY) + "\nworkspace_dir=" + ws.toString().replace("\\", "/") + "\n";
        Files.write(cfg, props.getBytes(StandardCharsets.UTF_8));
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-jar", jar.getAbsolutePath(), "--config", cfg.toString(), "app", "--tray");
        pb.environment().put("ONESAY_TEST_ACTIONS", "1");
        pb.environment().put("ONESAY_TEST_LAUNCH_KEY", PcLinkV2InteropTest.LAUNCH_KEY);
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
        // 复用 PcLinkV2InteropTest 的 http 小工具（同一个界面协议）
        PcLinkV2InteropTest.uiPort = uiPort;
        Matcher t = Pattern.compile("const TOKEN='([A-Za-z0-9_-]+)'").matcher(PcLinkV2InteropTest.http("GET", "/?k=" + PcLinkV2InteropTest.LAUNCH_KEY, null, null));
        assertTrue(t.find());
        token = t.group(1);
        PcLinkV2InteropTest.token = token;
    }

    static String out() throws Exception {
        return new String(Files.readAllBytes(dir.resolve("desktop.out")), StandardCharsets.UTF_8);
    }

    @AfterClass public static void stopDesktop() throws Exception {
        // 借用了 PcLinkV2InteropTest 的 http 小工具的端口 / 令牌：还原，免得它的 @BeforeClass 以为界面已起来（同一 JVM 里先后跑）
        PcLinkV2InteropTest.uiPort = 0;
        PcLinkV2InteropTest.token = null;
        if (proc != null) {
            proc.destroy();
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly();
        }
        if (dir != null) rm(dir.toFile());
    }

    static void rm(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) rm(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static PcLanClient.Peer peer(String me) throws Exception {
        PcPairing pp = PcPairing.parse(String.valueOf(PcLinkV2InteropTest.api("pairing", null).get("pairing")));
        return new PcLanClient.Peer(me, pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, PcTls.generate(me));
    }

    static File find(File root, String name) {
        File[] fs = root.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isFile() && f.getName().equals(name)) return f;
            if (f.isDirectory()) {
                File x = find(f, name);
                if (x != null) return x;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 手机 → 电脑

    @Test public void phoneToPcFileAndTask() throws Exception {
        final PcLanClient.Peer p = peer("phone-once");
        byte[] data = new byte[PcFileRx.CHUNK + 4321];
        new Random(11).nextBytes(data);
        File src = new File(dir.toFile(), "相册导出.bin");
        FileOutputStream o = new FileOutputStream(src);
        o.write(data);
        o.close();
        PcLanClient.Result r = PcFileRx.send(src, null, new PcFileRx.Caller() {
            @Override public PcLanClient.Result call(String cmd, Map<String, Object> args) {
                return PcLanClient.once(p, cmd, args, 1500, 30000, null, null);
            }
        }, null);
        assertTrue(r + " / " + out(), r.ok);
        File got = new File(String.valueOf(r.data.get("path")));
        if (!got.isFile()) got = find(dir.resolve("ws").toFile(), "相册导出.bin");
        assertNotNull("电脑工作区里没找到：" + r.data, got);
        assertTrue(got.getPath(), got.getParentFile().getName().equals("来自手机"));
        assertArrayEquals(data, Files.readAllBytes(got.toPath()));

        // 手机交任务：测试电脑没配大模型 → 明说，不假装开始
        Map<String, Object> g = new TreeMap<String, Object>();
        g.put("goal", "把来自手机文件夹里的文件列一下");
        PcLanClient.Result t = PcLanClient.once(p, "pc_task", g, 1500, 15000, null, null);
        assertTrue(t.toString(), t.reached());
        assertFalse(t.ok);
        assertTrue(t.text, t.text.contains("没配大模型"));
        assertTrue(PcTaskAndroid.line(t, true).contains("没配大模型"));
        PcLanClient.Result s = PcLanClient.once(p, "pc_task_status", null, 1500, 15000, null, null);
        assertTrue(s.toString(), s.ok);
        assertTrue(s.text, s.text.contains("还没交过任务"));
    }

    // ------------------------------------------------------------------ 电脑 → 手机（常连）

    static final class Env implements PcRx.Env, PcRx.Handler {
        final PcLanClient.Peer p;
        final PcFileRx rx;
        volatile boolean wanted = true;
        volatile String state = "";
        final List<String> ran = new CopyOnWriteArrayList<String>();
        final java.util.Set<String> on = new java.util.HashSet<String>();

        Env(PcLanClient.Peer p, File dest) throws Exception {
            this.p = p;
            rx = new PcFileRx(Files.createTempDirectory("stage").toFile(), PcFileRx.dirSink(dest));
        }

        @Override public boolean wanted() { return wanted; }
        @Override public boolean onLan() { return true; }
        @Override public PcLanClient.Peer peer() { return p; }
        @Override public List<InetAddress> extraBroadcasts() { return null; }
        @Override public void multicast(boolean hold) { }
        @Override public void state(String s, String d) { state = s + " " + d; }
        @Override public void remember(InetSocketAddress a) { }
        @Override public PcRx.Handler handler() { return this; }
        @Override public boolean enabled(String cmd) { return on.contains(cmd); }

        @Override public PcLanClient.Result run(String cmd, Map<String, Object> a) {
            ran.add(cmd);
            if (PcRxPolicy.FILE_PUT.equals(cmd)) return rx.receive(a, PC);
            Map<String, Object> d = new TreeMap<String, Object>();
            if (PcRxPolicy.SHORTCUT_LIST.equals(cmd)) {
                List<Object> l = new ArrayList<Object>();
                Map<String, Object> s = new TreeMap<String, Object>();
                s.put("name", "回家模式");
                s.put("steps", "3 步");
                l.add(s);
                d.put("shortcuts", l);
                return new PcLanClient.Result(true, "手机上有 1 条快捷指令", d, "");
            }
            if (PcRxPolicy.SHORTCUT_RUN.equals(cmd)) return new PcLanClient.Result(true, "手机开始执行快捷指令「" + a.get("name") + "」", null, "");
            if (PcRxPolicy.OPERATE.equals(cmd)) return new PcLanClient.Result(true, "已交给手机上的 OneSay 去做", null, "");
            if (PcRxPolicy.OPERATE_STATUS.equals(cmd)) return new PcLanClient.Result(true, "刚才交给手机的「" + "看安卓版本" + "」已经在手机上开始做", null, "");
            return new PcLanClient.Result(true, "好", d, "");
        }

        @Override public void record(String cmd, boolean ok, String text) { }
    }

    static Map<String, Object> phone(String cmd, Map<String, Object> args) throws Exception {
        Map<String, Object> b = new TreeMap<String, Object>();
        b.put("cmd", cmd);
        b.put("args", args == null ? new TreeMap<String, Object>() : args);
        return PcLinkV2InteropTest.api("phone", b);
    }

    @Test public void pcToPhoneFileShortcutsAndOperate() throws Exception {
        File dest = Files.createTempDirectory("phone-dl").toFile();
        Env env = new Env(peer("phone-held"), dest);
        PcRx.Loop loop = new PcRx.Loop(env);
        loop.minBackoff = 300;
        loop.maxBackoff = 1000;
        Thread t = new Thread(loop, "rx-1119");
        t.setDaemon(true);
        t.start();
        try {
            long end = System.currentTimeMillis() + 10000;
            while (!env.state.startsWith(PcRx.S_UP) && System.currentTimeMillis() < end) Thread.sleep(100);
            assertTrue("常连没连上：" + env.state, env.state.startsWith(PcRx.S_UP));
            Thread.sleep(200);

            // 开关都关着：电脑说清楚是手机上没开
            Map<String, Object> off = phone("shortcut_list", null);
            assertEquals(Boolean.FALSE, off.get("ok"));
            assertTrue(String.valueOf(off.get("text")), String.valueOf(off.get("text")).contains("没开"));
            assertTrue(env.ran.isEmpty());

            env.on.add("shortcut_run");
            env.on.add("operate");
            env.on.add("file_put");
            Map<String, Object> l = phone("shortcut_list", null);
            assertEquals(String.valueOf(l), Boolean.TRUE, l.get("ok"));
            assertTrue(String.valueOf(l.get("data")), String.valueOf(l.get("data")).contains("回家模式"));
            Map<String, Object> n = new TreeMap<String, Object>();
            n.put("name", "回家模式");
            Map<String, Object> run = phone("shortcut_run", n);
            assertEquals(String.valueOf(run), Boolean.TRUE, run.get("ok"));
            Map<String, Object> g = new TreeMap<String, Object>();
            g.put("goal", "打开设置看看安卓版本");
            assertEquals(Boolean.TRUE, phone("operate", g).get("ok"));
            Map<String, Object> os = phone("operate_status", null);
            assertEquals(String.valueOf(os), Boolean.TRUE, os.get("ok"));

            // 电脑界面「发到手机」：分块经常连通道，手机落盘字节一致
            byte[] data = new byte[2 * PcFileRx.CHUNK + 77];
            new Random(5).nextBytes(data);
            File src = new File(dir.toFile(), "给手机.pdf");
            FileOutputStream o = new FileOutputStream(src);
            o.write(data);
            o.close();
            Map<String, Object> sb = new TreeMap<String, Object>();
            sb.put("op", "send_path");
            sb.put("path", src.getAbsolutePath());
            Map<String, Object> started = PcLinkV2InteropTest.api("xfer", sb);
            assertEquals(String.valueOf(started), Boolean.TRUE, started.get("ok"));
            File got = new File(dest, "给手机.pdf");
            end = System.currentTimeMillis() + 30000;
            while (!got.isFile() && System.currentTimeMillis() < end) Thread.sleep(200);
            assertTrue("手机没收到：" + env.ran + " " + out(), got.isFile());
            assertArrayEquals(data, Files.readAllBytes(got.toPath()));
            // 电脑界面的传输列表里看得到它（发 / 完成 / 100%）
            Map<String, Object> view = PcLinkV2InteropTest.api("xfer", null);
            end = System.currentTimeMillis() + 5000;
            while (!String.valueOf(view.get("xfers")).contains("status=done") && System.currentTimeMillis() < end) {
                Thread.sleep(200);
                view = PcLinkV2InteropTest.api("xfer", null);
            }
            String xv = String.valueOf(view.get("xfers"));
            assertTrue(xv, xv.contains("给手机.pdf") && xv.contains("status=done") && xv.contains("percent=100"));
            assertTrue(env.ran.toString(), env.ran.containsAll(java.util.Arrays.asList("shortcut_list", "shortcut_run", "operate", "operate_status", "file_put")));
        } finally {
            env.wanted = false;
            loop.stop();
            t.join(5000);
        }
    }
}
