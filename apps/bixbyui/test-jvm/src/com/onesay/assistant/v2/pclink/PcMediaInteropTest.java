package com.onesay.assistant.v2.pclink;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * [media-get] 手机 ↔ 电脑链接下载往返互通（真进程、真 socket + TLS）：电脑端用假的 yt-dlp 脚本（输出模拟进度行）。
 * <ol>
 *   <li>坏链接（; 空格 file:// -- 开头）→ 电脑直接拒（untrusted_url），不弹确认卡；带 &amp; 的正常查询参数放行，假 yt-dlp 收到的链接是单独一个参数；</li>
 *   <li>media_get → 电脑上弹确认卡（动作类型 pc.media_download，卡上写着链接）→ 本机鼠标「允许」并勾「以后这类不用问」→ 排队；</li>
 *   <li>手机轮询 media_status 看进度直到完成 → media_fetch 传回手机（≤ 5 MB）；</li>
 *   <li>再发一次：记住了，不再弹卡；电脑设置页能看到记住的类型。</li>
 * </ol>
 * 电脑端 jar 同 {@link PcDesktopInteropTest}（缺 jar 或 jar 里没有链接下载整类跳过）。
 */
public class PcMediaInteropTest {

    static final String URL_OK = "https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_Trailer_1080p.ogv";
    static Process proc;
    static Path dir;
    static int port, uiPort;
    static String token;
    static final byte[] KEY = new byte[32];

    static final String FAKE = "#!/bin/bash\n"
            + "D=\"$(dirname \"$0\")\"\n"
            + "printf '%s\\n' \"$@\" > \"$D/last.args\"\n"
            + "prev=''; out=''\n"
            + "for a in \"$@\"; do case \"$prev\" in -P) out=\"$a\";; esac; prev=\"$a\"; done\n"
            + "for a in \"$@\"; do if [ \"$a\" = -J ]; then echo '{\"title\":\"测试视频\",\"duration\":33.0,\"ext\":\"webm\",\"filesize\":9}'; exit 0; fi; done\n"
            + "echo '[download]   0.0% of ~  1.50MiB at  Unknown B/s ETA Unknown'\n"
            + "sleep 1\n"
            + "echo '[download]  42.5% of ~  1.50MiB at    2.00MiB/s ETA 00:01'\n"
            + "sleep 1\n"
            + "echo '[download] 100% of    1.50MiB in 00:00:01 at 1.50MiB/s'\n"
            + "mkdir -p \"$out\"; printf 'FAKEVIDEO' > \"$out/测试视频 [abc].webm\"\n"
            + "echo \"[onesay-file] $out/测试视频 [abc].webm\"\n";

    static String hex(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format("%02x", x));
        return s.toString();
    }

    @BeforeClass public static void startDesktop() throws Exception {
        File jar = PcDesktopInteropTest.findJar();
        Assume.assumeTrue("电脑端 jar 不在，链接下载互通测试跳过", jar != null);
        URLClassLoader cl = new URLClassLoader(new URL[]{jar.toURI().toURL()}, ClassLoader.getPlatformClassLoader());
        try {
            cl.loadClass("com.onesay.desktop.Media");
        } catch (Throwable e) {
            Assume.assumeTrue("电脑端 jar 还没有链接下载，跳过", false);
        }
        new java.security.SecureRandom().nextBytes(KEY);
        port = PcDesktopInteropTest.freePort();
        dir = Files.createTempDirectory("pclink-media");
        Path tools = dir.resolve("tools");
        Files.createDirectories(tools);
        byte[] fake = FAKE.getBytes(StandardCharsets.UTF_8);
        Files.write(tools.resolve("yt-dlp"), fake);
        tools.resolve("yt-dlp").toFile().setExecutable(true);
        String sha = hex(fake);
        Files.write(tools.resolve("tools.json"), ("{\"yt-dlp\":{\"version\":\"test\",\"asset\":\"yt-dlp_linux\",\"sha256\":\"" + sha + "\",\"files\":{\"yt-dlp\":\"" + sha + "\"}}}").getBytes(StandardCharsets.UTF_8));
        Path cfg = dir.resolve("desktop.properties").toAbsolutePath();
        String props = "lan_port=" + port + "\ndevice_id=pc-media\nkind=pc\nptt=off\npair_key=" + PcB64.encodeUrl(KEY) + "\nmedia_dir=" + dir.resolve("Downloads").toString().replace("\\", "\\\\") + "\n";
        Files.write(cfg, props.getBytes(StandardCharsets.UTF_8));
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-Donesay.tools.dir=" + tools, "-jar", jar.getAbsolutePath(), "--config", cfg.toString(), "app", "--tray");
        pb.environment().put("ONESAY_TEST_ACTIONS", "1");
        pb.environment().put("ONESAY_UI_PORT", "0");
        pb.environment().remove("APPDATA");
        pb.environment().remove("USERPROFILE");
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

    static String out() throws Exception {
        return new String(Files.readAllBytes(dir.resolve("desktop.out")), StandardCharsets.UTF_8);
    }

    @AfterClass public static void stopDesktop() throws Exception {
        if (proc != null) {
            proc.destroy();
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly();
        }
        if (dir != null) del(dir.toFile());
    }

    static void del(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) del(x);
        f.delete();
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

    static Map<String, Object> mm(Object... kv) {
        Map<String, Object> m = new TreeMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static PcLanClient.Peer peer() throws Exception {
        PcPairing p = PcPairing.parse(String.valueOf(api("pairing", null).get("pairing")));
        return new PcLanClient.Peer("phone-media", p.peer, p.key, "127.0.0.1:" + port, p.lanPort, "", p.fp, PcTls.generate("onesay-phone-media"));
    }

    static List<?> confirms() throws Exception {
        return (List<?>) api("state", null).get("confirms");
    }

    @Test public void phoneToPcMediaRoundTrip() throws Exception {
        final PcLanClient.Peer p = peer();
        // 1. 坏链接：直接拒，不弹卡
        for (String bad : new String[]{"https://a.com/x;calc", "https://a.com/a b", "file:///etc/passwd", "--exec=calc"}) {
            PcLanClient.Result r = PcLanClient.once(p, PcMedia.GET, mm("url", bad), 1500, 15000, null, null);
            assertFalse(bad, r.ok);
            assertEquals(bad, LinkPolicy.R_UNTRUSTED_URL, r.data.get("reason"));
        }
        assertTrue("坏链接没弹卡", confirms() == null || confirms().isEmpty());

        // 2. 第一次：电脑上弹卡，本机鼠标允许 + 以后这类不用问
        final PcLanClient.Result[] got = new PcLanClient.Result[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() { got[0] = PcLanClient.once(p, PcMedia.GET, PcMedia.args(URL_OK, "720", ""), 1500, PcLinkUi.MAY_CONFIRM_WAIT_MS, null, null); }
        }, "phone-media-get");
        t.setDaemon(true);
        t.start();
        Map<?, ?> card = null;
        for (int i = 0; i < 150 && card == null; i++) {
            List<?> c = confirms();
            if (c != null && !c.isEmpty()) card = (Map<?, ?>) c.get(0);
            else Thread.sleep(100);
        }
        assertNotNull("电脑上弹了确认卡：" + out(), card);
        assertEquals("pc.media_download", card.get("remember_kind"));
        assertTrue(String.valueOf(card.get("detail")), String.valueOf(card.get("detail")).contains(URL_OK));
        api("confirm", mm("op", "seen", "id", card.get("id"), "focused", Boolean.TRUE));
        Thread.sleep(900);
        Map<String, Object> ans = api("confirm", mm("id", card.get("id"), "allow", Boolean.TRUE, "remember", Boolean.TRUE, "input", "mouse", "trusted", Boolean.TRUE));
        assertEquals(String.valueOf(ans), Boolean.TRUE, ans.get("ok"));
        t.join(20000);
        assertNotNull(got[0]);
        assertTrue(got[0].text, got[0].ok);
        int id = ((Number) got[0].data.get("id")).intValue();

        // 3. 跟进度 → 传回手机
        final List<Integer> pct = new java.util.ArrayList<Integer>();
        final byte[][] saved = new byte[1][];
        String done = PcMedia.follow(new PcMedia.Caller() {
            @Override public PcLanClient.Result call(String cmd, Map<String, Object> args, int timeoutMs) {
                return PcLanClient.once(p, cmd, args, 1500, timeoutMs, null, null);
            }
        }, new PcMedia.Sink() {
            @Override public void progress(int i, String title, int percent, String line) { pct.add(percent); }

            @Override public void done(int i, String title, boolean ok, String text) { }
        }, new PcMedia.Saver() {
            @Override public String save(String name, byte[] data, boolean audio) {
                saved[0] = data;
                return "视频/OneSay/" + name;
            }
        }, id, true, 60000, 300, new PcMedia.Sleeper() {
            @Override public void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
        });
        assertTrue(done, done.contains("已下载到电脑") && done.contains("已传回手机"));
        assertEquals("FAKEVIDEO", new String(saved[0], StandardCharsets.UTF_8));
        assertFalse("看到了进度：" + pct, pct.isEmpty());
        String args = new String(Files.readAllBytes(dir.resolve("tools").resolve("last.args")), StandardCharsets.UTF_8);
        assertTrue(args, args.trim().endsWith("--\n" + URL_OK));
        assertTrue("清晰度 720", args.contains("[height<=720]"));

        // 4. 记住了：再发不弹卡
        PcLanClient.Result again = PcLanClient.once(p, PcMedia.GET, PcMedia.args(URL_OK, "best", ""), 1500, 15000, null, null);
        assertTrue(again.text, again.ok);
        assertTrue(confirms() == null || confirms().isEmpty());
        List<?> rem = (List<?>) api("state", null).get("remembered");
        assertTrue(String.valueOf(rem), String.valueOf(rem).contains("pc.media_download"));
        PcLanClient.Result list = PcLanClient.once(p, PcMedia.STATUS, null, 1500, 8000, null, null);
        assertTrue(list.ok);
        assertTrue(((List<?>) list.data.get("tasks")).size() >= 2);
        // 电脑界面的「下载」区块看得到这两个任务、来自这台手机
        Map<?, ?> media = (Map<?, ?>) api("state", null).get("media");
        assertTrue(String.valueOf(media.get("tasks")), String.valueOf(media.get("tasks")).contains("phone-media"));
        assertFalse("日志里没有 Cookie", out().contains("--cookies /"));

        // 5. 带 & 的链接（2026-11-11 放行）：等前一个任务完，再发；假 yt-dlp 收到的 argv 里链接是单独一个参数、个数不变
        int againId = ((Number) again.data.get("id")).intValue();
        followQuiet(p, againId);
        String before = new String(Files.readAllBytes(dir.resolve("tools").resolve("last.args")), StandardCharsets.UTF_8);
        String amp = URL_OK + "?t=10&--exec=calc&-o=/tmp/pwn";
        PcLanClient.Result r5 = PcLanClient.once(p, PcMedia.GET, PcMedia.args(amp, "best", ""), 1500, 15000, null, null);
        assertTrue(r5.text, r5.ok);
        assertTrue("记住了，不弹卡", confirms() == null || confirms().isEmpty());
        String d5 = followQuiet(p, ((Number) r5.data.get("id")).intValue());
        assertTrue(d5, d5.contains("已下载到电脑"));
        String a5 = new String(Files.readAllBytes(dir.resolve("tools").resolve("last.args")), StandardCharsets.UTF_8);
        assertTrue(a5, a5.trim().endsWith("--\n" + amp));
        assertEquals("参数个数不变", before.trim().split("\n").length, a5.trim().split("\n").length);
        assertFalse(a5, a5.contains("\n--exec") || a5.contains("\n-o=/tmp/pwn"));
    }

    /** 跟完一个任务（不传回手机），返回结果文字。 */
    static String followQuiet(final PcLanClient.Peer p, int id) throws Exception {
        return PcMedia.follow(new PcMedia.Caller() {
            @Override public PcLanClient.Result call(String cmd, Map<String, Object> args, int timeoutMs) {
                return PcLanClient.once(p, cmd, args, 1500, timeoutMs, null, null);
            }
        }, new PcMedia.Sink() {
            @Override public void progress(int i, String title, int percent, String line) { }

            @Override public void done(int i, String title, boolean ok, String text) { }
        }, new PcMedia.Saver() {
            @Override public String save(String name, byte[] data, boolean audio) { return name; }
        }, id, false, 60000, 300, new PcMedia.Sleeper() {
            @Override public void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
        });
    }
}
