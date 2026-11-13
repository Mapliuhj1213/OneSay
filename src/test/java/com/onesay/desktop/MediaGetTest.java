package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

/**
 * 链接下载（feat/media-get，思路参考 omniget，代码自写）：参数白名单与注入拦截、进度解析、429 退避、SHA-256 不符拒运行、
 * Cookie 路径打码、审批卡与「以后这类不用问」、队列（用假的 yt-dlp 脚本输出模拟进度行）。
 */
public class MediaGetTest {

    static final LinkPolicy.Sandbox RO = LinkPolicy.Sandbox.READ_ONLY, WS = LinkPolicy.Sandbox.WORKSPACE_WRITE, FA = LinkPolicy.Sandbox.FULL_ACCESS;
    static final LinkPolicy.Approval UN = LinkPolicy.Approval.UNTRUSTED, OR = LinkPolicy.Approval.ON_REQUEST, NV = LinkPolicy.Approval.NEVER;
    static final String URL = "https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_Trailer_1080p.ogv";

    static Map<String, Object> m(Object... kv) { return PcGuardTest.m(kv); }

    // ================================================================== 1. 参数白名单 / 注入拦截

    @Test public void urlWhitelistBlocksInjection() {
        for (String ok : new String[]{URL, "https://www.bilibili.com/video/BV1xx411c7mD/?p=2", "http://example.com/a%20b.mp4", "https://youtu.be/abc_DEF-123",
                "https://a.com/watch?v=1&t=2", "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10&list=PL1"})
            assertNull(ok, MediaArgs.checkUrl(ok));
        String[] bad = {"https://a.com/x;rm -rf /", "https://a.com/x|calc", "https://a.com/x y", "file:///etc/passwd",
                "--exec=calc https://a.com/", "-o /tmp/x", "javascript:alert(1)", "ftp://a.com/x", "https://a.com/x\nrm", "https://a.com/`id`",
                "https://a.com/$(id)", "https://a.com/x>y", "https://user:pw@a.com/x", "https:///nohost", "", "https://a.com/x\"y", "https://a.com/x'y",
                "https://a.com/x\\y", "https://a.com/\u0000"};
        for (String b : bad) assertNotNull("应拒：" + b, MediaArgs.checkUrl(b));
        // 2026-11-11 用户同意：查询参数里的 & 放行（链接在参数数组里永远是单独一个元素，不经 shell）
        assertNull(MediaArgs.checkUrl("https://a.com/watch?v=1&t=2"));
        assertNotNull("& 放行不等于 & 后面能带空格 / 分号", MediaArgs.checkUrl("https://a.com/watch?v=1& calc"));
        assertNotNull(MediaArgs.checkUrl("https://a.com/watch?v=1&;calc"));
        assertTrue(MediaArgs.checkUrl("x".repeat(10) + "https://a.com/" + "y".repeat(2100)).contains("太长"));
        // 整理：分享文字里挑出链接、去掉 # 片段和明显的跟踪参数（utm_* / spm …），其余参数保留（显示给你看），然后照样严格检查
        assertEquals("https://www.bilibili.com/video/BV1xx/?share_source=copy&vd_source=abc", MediaArgs.tidy("【标题】 https://www.bilibili.com/video/BV1xx/?share_source=copy&vd_source=abc#t=1 复制打开"));
        assertEquals("https://v.douyin.com/abc/", MediaArgs.tidy("7.99 复制打开抖音 https://v.douyin.com/abc/ 看看"));
        assertNull(MediaArgs.tidy("没有链接 file:///x"));
        // YouTube 带 &t=10：完整保留，并且过检查
        String yt = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10";
        assertEquals(yt, MediaArgs.tidy("看这个 " + yt + " 从第 10 秒"));
        assertNull(MediaArgs.checkUrl(MediaArgs.tidy(yt)));
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10&list=PL1",
                MediaArgs.tidy("https://www.youtube.com/watch?utm_source=share&v=dQw4w9WgXcQ&t=10&UTM_Medium=x&&spm=a.b&list=PL1&fbclid=Zz#comments"));
        assertEquals("https://a.com/v", MediaArgs.tidy("https://a.com/v?utm_source=x&spm_id_from=1&gclid=2&igshid=3"));
        assertEquals("https://a.com/v?a=1", MediaArgs.tidy("https://a.com/v?a=1&"));
        // yt-dlp 只认直接启动的可执行文件：.bat / .cmd 会经 cmd.exe 解释 &，拒绝
        for (String exe : new String[]{"C:\\t\\yt-dlp.bat", "C:\\t\\YT-DLP.CMD"}) {
            try {
                MediaArgs.download(exe, yt, "best", "", null, "/out", null);
                fail("拒绝 " + exe);
            } catch (IllegalArgumentException expected) { }
        }
        // 参数：白名单拼装，链接永远在最后、前面是 --；不认识的清晰度回落最佳；仅音频只有 m4a / mp3
        assertEquals("best", MediaArgs.quality("; rm -rf"));
        assertEquals("1080", MediaArgs.quality("1080p"));
        assertNull(MediaArgs.audio("wav --exec calc"));
        assertEquals("mp3", MediaArgs.audio("mp3"));
        List<String> d = MediaArgs.download("/t/yt-dlp", URL, MediaArgs.quality("720"), "", "/t/ffmpeg", "/out", null);
        assertEquals(URL, d.get(d.size() - 1));
        assertEquals("--", d.get(d.size() - 2));
        assertEquals(1, d.stream().filter("--"::equals).count());
        for (String must : new String[]{"--ignore-config", "--no-plugin-dirs", "--no-playlist", "--no-exec", "--continue", "--newline"}) assertTrue(must, d.contains(must));
        assertFalse("从不带 --exec", d.contains("--exec"));
        assertTrue(d.contains("bv*[height<=720]+ba/b[height<=720]/b"));
        List<String> au = MediaArgs.download("/t/yt-dlp", URL, "best", "mp3", "/t/ffmpeg", "/out", "/cfg/cookies/c.txt");
        assertTrue(au.contains("-x") && au.contains("mp3") && au.contains("ba/b"));
        assertTrue(MediaArgs.masked(au).contains("--cookies " + MediaArgs.COOKIE_MASK));
        assertFalse("Cookie 路径打码", MediaArgs.masked(au).contains("/cfg/cookies"));
        for (String b : bad) {
            try {
                MediaArgs.download("/t/yt-dlp", b, "best", "", null, "/out", null);
                fail("拼参数前就拒：" + b);
            } catch (IllegalArgumentException expected) { }
            try {
                MediaArgs.preview("/t/yt-dlp", b, "best", "", null, null);
                fail("预览也拒：" + b);
            } catch (IllegalArgumentException expected) { }
        }
        try {
            MediaArgs.download("/t/yt-dlp", URL, "best", "m4a", null, "/out", null);
            fail("仅音频没有 FFmpeg 不拼");
        } catch (IllegalArgumentException expected) { }
        assertTrue("没有 FFmpeg 只选单文件", MediaArgs.download("/t/yt-dlp", URL, "1080", "", null, "/out", null).contains("b[height<=1080]/b"));
    }

    // ================================================================== 2. 进度解析

    @Test public void progressLines() {
        MediaArgs.Progress p = MediaArgs.progress("[download]  42.5% of ~  12.30MiB at    2.00MiB/s ETA 00:05");
        assertEquals(42.5, p.percent, 0.001);
        assertEquals("12.30MiB", p.total);
        assertEquals("2.00MiB/s", p.speed);
        assertEquals("00:05", p.eta);
        p = MediaArgs.progress("[download]   0.0% of    2.80MiB at  Unknown B/s ETA Unknown");
        assertEquals(0.0, p.percent, 0.001);
        assertEquals("", p.speed);
        assertEquals("", p.eta);
        p = MediaArgs.progress("[download] 100% of    2.80MiB in 00:00:00 at 6.89MiB/s");
        assertEquals(100.0, p.percent, 0.001);
        p = MediaArgs.progress("[download]  10.0% of ~ 100.00MiB at  1.00MiB/s ETA 01:30 (frag 3/30)");
        assertEquals("01:30", p.eta);
        assertNull(MediaArgs.progress("[download] Destination: x.mp4"));
        assertNull(MediaArgs.progress("[youtube] abc: Downloading webpage"));
        assertNull(MediaArgs.progress(null));
        assertTrue(MediaArgs.rateLimited("ERROR: [generic] Unable to download webpage: HTTP Error 429: Too Many Requests"));
        assertFalse(MediaArgs.rateLimited("ERROR: Video unavailable"));
        assertEquals(10_000, MediaArgs.backoffMs(1, 10_000));
        assertEquals(30_000, MediaArgs.backoffMs(2, 10_000));
        assertEquals(90_000, MediaArgs.backoffMs(3, 10_000));
        assertEquals("1:05", MediaArgs.duration(65.4));
        assertEquals("1:01:01", MediaArgs.duration(3661));
    }

    // ================================================================== 3. 假 yt-dlp：队列、进度、429 退避、SHA-256

    static final String FAKE = String.join("\n",
            "#!/bin/bash",
            "D=\"$(dirname \"$0\")\"",
            "printf '%s\\n' \"$@\" > \"$D/last.args\"",
            "echo x >> \"$D/runs\"",
            "MODE=$(cat \"$D/mode\" 2>/dev/null || echo ok)",
            "prev=''; out=''; cookie=''",
            "for a in \"$@\"; do case \"$prev\" in -P) out=\"$a\";; --cookies) cookie=\"$a\";; esac; prev=\"$a\"; done",
            "if [ -n \"$cookie\" ] && [ -f \"$cookie\" ]; then cp \"$cookie\" \"$D/cookie.seen\"; fi",
            "n=$(wc -l < \"$D/runs\")",
            "if { [ \"$MODE\" = 429x2 ] && [ \"$n\" -le 2 ]; } || [ \"$MODE\" = 429 ]; then echo 'ERROR: [generic] Unable to download webpage: HTTP Error 429: Too Many Requests'; exit 1; fi",
            "for a in \"$@\"; do if [ \"$a\" = -J ]; then echo 'WARNING: just a test'; echo '{\"title\":\"测试视频\",\"duration\":65.4,\"ext\":\"mp4\",\"requested_formats\":[{\"filesize\":1048576},{\"filesize_approx\":524288}]}'; exit 0; fi; done",
            "echo \"[download] Destination: $out/x.mp4\"",
            "echo '[download]   0.0% of ~  1.50MiB at  Unknown B/s ETA Unknown'",
            "echo '[download]  42.5% of ~  1.50MiB at    2.00MiB/s ETA 00:01'",
            "if [ \"$MODE\" = slow ]; then sleep 20; fi",
            "echo '[download] 100% of    1.50MiB in 00:00:01 at 1.50MiB/s'",
            "mkdir -p \"$out\"; printf 'FAKEVIDEO' > \"$out/测试视频 [abc].mp4\"",
            "echo \"[onesay-file] $out/测试视频 [abc].mp4\"",
            "exit 0", "");

    static final class Rig {
        final Path root, tools, out, cfgDir;
        final MediaTools mt;
        final MediaCookies ck;
        final Media media;
        final List<String> log = new CopyOnWriteArrayList<>();
        final List<Long> slept = new CopyOnWriteArrayList<>();

        Rig() throws Exception {
            root = Files.createTempDirectory("mg");
            tools = root.resolve("tools");
            out = root.resolve("Downloads");
            cfgDir = root.resolve("cfg");
            mt = new MediaTools(tools, false, MediaTools.ytdlpSource("http://127.0.0.1:9/yt", "Linux", "amd64"), null);
            Files.createDirectories(tools);
            Files.writeString(mt.ytdlpPath(), FAKE);
            mt.ytdlpPath().toFile().setExecutable(true);
            mt.register(MediaTools.YTDLP, "test", "yt-dlp_linux", MediaTools.sha256(mt.ytdlpPath()), Map.of("yt-dlp", mt.ytdlpPath()));
            ck = new MediaCookies(cfgDir.resolve("cookies"));
            media = new Media(mt, ck, out, log::add);
            media.sleeper = slept::add;
        }

        void mode(String m) throws Exception { Files.writeString(tools.resolve("mode"), m); }

        int runs() throws Exception { return Files.exists(tools.resolve("runs")) ? Files.readAllLines(tools.resolve("runs")).size() : 0; }

        List<String> args() throws Exception { return Files.readAllLines(tools.resolve("last.args"), StandardCharsets.UTF_8); }
    }

    static void await(Media.Task t, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (!t.finished() && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue("任务没结束：" + t.state, t.finished());
    }

    @Test public void fakeYtDlpPreviewThenDownloadWithProgress() throws Exception {
        Rig r = new Rig();
        Media.Info in = r.media.preview(URL, "1080", "");
        assertEquals("测试视频", in.title);
        assertEquals(65.4, in.duration, 0.01);
        assertEquals(1048576 + 524288, in.size);
        assertTrue("预览用 -J", r.args().contains("-J"));
        assertEquals(URL, r.args().get(r.args().size() - 1));
        Media.Task t = r.media.enqueue(URL, "1080", "", "", false, in);
        await(t, 15000);
        assertEquals(t.error, Media.State.DONE, t.state);
        assertEquals(100.0, t.percent, 0.01);
        assertEquals("2.00MiB/s", t.speed);
        assertTrue(t.file, t.file.endsWith("测试视频 [abc].mp4"));
        assertEquals("FAKEVIDEO", Files.readString(Path.of(t.file)));
        assertEquals("预览过的不再预览：只跑了预览 + 下载两次", 2, r.runs());
        List<String> a = r.args();
        assertEquals("--", a.get(a.size() - 2));
        assertEquals(URL, a.get(a.size() - 1));
        assertTrue(a.contains("--continue"));
        assertTrue(a.contains(r.out.toString()));
        assertEquals(1, t.commands.size());
        assertTrue("记下实际命令", t.commands.get(0).contains("--continue") && t.commands.get(0).endsWith("-- " + URL));
        assertTrue(r.log.stream().anyMatch(l -> l.contains("执行：") && l.contains(URL)));
        Map<String, Object> v = r.media.view(t);
        assertEquals("done", v.get("state"));
        assertEquals(Boolean.TRUE, v.get("fetchable"));
        // 手机取回（≤ 5 MB）：整文件 base64
        Actions.Result f = r.media.remoteFetch(m("id", (long) t.id));
        assertTrue(f.text, f.ok);
        assertEquals("FAKEVIDEO", new String(Base64.getDecoder().decode(String.valueOf(f.data.get("b64"))), StandardCharsets.UTF_8));
        assertFalse(r.media.remoteFetch(m("id", 999L)).ok);
        // 大于 5 MB：不传，只给电脑上的路径
        Files.write(Path.of(t.file), new byte[(int) Media.FETCH_MAX + 1]);
        Actions.Result big = r.media.remoteFetch(m("id", (long) t.id));
        assertFalse(big.ok);
        assertNull(big.data.get("b64"));
        assertEquals(t.file, Path.of(String.valueOf(big.data.get("path"))).toString());
        assertTrue(big.text, big.text.contains("5 MB"));
        r.media.shutdown();
    }

    @Test public void rateLimitedBacksOffAndRetriesAtMostThreeTimes() throws Exception {
        Rig r = new Rig();
        r.mode("429x2");
        Media.Task t = r.media.enqueue(URL, "best", "", "", false, info());
        await(t, 15000);
        assertEquals(t.error, Media.State.DONE, t.state);
        assertEquals(2, t.retries);
        assertEquals(List.of(10_000L, 30_000L), r.slept);
        assertEquals("续传同一条命令", 3, t.commands.size());
        assertTrue(r.args().contains("--continue"));
        // 一直 429：重试 3 次后失败（一共跑 4 次）
        Rig r2 = new Rig();
        r2.mode("429");
        Media.Task t2 = r2.media.enqueue(URL, "best", "", "", false, info());
        await(t2, 15000);
        assertEquals(Media.State.FAILED, t2.state);
        assertEquals(3, t2.retries);
        assertEquals(List.of(10_000L, 30_000L, 90_000L), r2.slept);
        assertEquals(4, r2.runs());
        assertTrue(t2.error, t2.error.contains("429"));
        // 预览遇 429 也退避
        Rig r3 = new Rig();
        r3.mode("429x2");
        assertEquals("测试视频", r3.media.preview(URL, "best", "").title);
        assertEquals(List.of(10_000L, 30_000L), r3.slept);
        r.media.shutdown();
        r2.media.shutdown();
        r3.media.shutdown();
    }

    @Test public void ampersandUrlStaysOneArgv() throws Exception {
        // 2026-11-11：& 放行后，假 yt-dlp 收到的 argv 里链接必须是单独一个参数，& 后面像选项的东西也只是链接的一部分
        String plain = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        String[] amps = {plain + "&t=10", plain + "&t=10&--exec=calc&-o=/tmp/pwn&--cookies=/etc/passwd", plain + "&a=%26b%3D1&x="};
        Rig r = new Rig();
        r.media.preview(plain, "best", "");
        int previewArgc = r.args().size();
        Media.Task t0 = r.media.enqueue(plain, "best", "", "", false, info());
        await(t0, 15000);
        int downloadArgc = r.args().size();
        for (String u : amps) {
            assertNull(u, MediaArgs.checkUrl(u));
            r.media.preview(u, "best", "");
            List<String> a = r.args();
            assertEquals("预览参数个数不变：" + a, previewArgc, a.size());
            assertEquals(u, a.get(a.size() - 1));
            assertEquals("--", a.get(a.size() - 2));
            Media.Task t = r.media.enqueue(u, "best", "", "", false, info());
            await(t, 15000);
            assertEquals(t.error, Media.State.DONE, t.state);
            a = r.args();
            assertEquals("下载参数个数不变：" + a, downloadArgc, a.size());
            assertEquals(u, a.get(a.size() - 1));
            assertEquals("--", a.get(a.size() - 2));
            assertEquals(1, a.stream().filter("--"::equals).count());
            assertEquals("链接只出现一次", 1, a.stream().filter(x -> x.contains("&")).count());
            assertFalse(a.stream().anyMatch(x -> x.startsWith("--exec") || x.startsWith("-o=") || x.startsWith("--cookies=")));
            assertFalse("没有带 Cookie", a.contains("--cookies"));
        }
        r.media.shutdown();
    }

    static Media.Info info() {
        Media.Info i = new Media.Info();
        i.title = "测试视频";
        return i;
    }

    @Test public void shaMismatchRefusesToRun() throws Exception {
        Rig r = new Rig();
        assertNull(r.mt.verify(MediaTools.YTDLP));
        Files.writeString(r.mt.ytdlpPath(), FAKE + "\n# 被改过\n");
        assertNotNull(r.mt.verify(MediaTools.YTDLP));
        assertTrue(r.mt.verify(MediaTools.YTDLP).contains("SHA-256"));
        try {
            r.media.preview(URL, "best", "");
            fail("校验不过不预览");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("SHA-256"));
        }
        Media.Task t = r.media.enqueue(URL, "best", "", "", false, info());
        await(t, 10000);
        assertEquals(Media.State.FAILED, t.state);
        assertTrue(t.error, t.error.contains("拒绝运行"));
        assertEquals("一次都没跑", 0, r.runs());
        // 文件没了 / 记录坏了：当没装，同样不跑
        Files.writeString(r.tools.resolve("tools.json"), "{坏的");
        assertTrue(r.mt.missing());
        assertNotNull(r.mt.verify(MediaTools.YTDLP));
        r.media.shutdown();
    }

    // ================================================================== 4. 工具安装：官方校验和，对不上不装；FFmpeg 只要 LGPL

    static byte[] zip(String... nameContent) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (int i = 0; i < nameContent.length; i += 2) {
                z.putNextEntry(new ZipEntry(nameContent[i]));
                z.write(nameContent[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return b.toByteArray();
    }

    static String sha(byte[] b) throws Exception {
        Path p = Files.createTempFile("sha", ".bin");
        Files.write(p, b);
        return MediaTools.sha256(p);
    }

    @Test public void installVerifiesOfficialChecksumsAndPicksLgpl() throws Exception {
        byte[] exe = "#!/bin/sh\necho fake\n".getBytes(StandardCharsets.UTF_8);
        byte[] zip = zip("ffmpeg-n9.0-latest-win64-lgpl-9.0/bin/ffmpeg.exe", "FF", "ffmpeg-n9.0-latest-win64-lgpl-9.0/bin/ffprobe.exe", "FP",
                "ffmpeg-n9.0-latest-win64-lgpl-9.0/LICENSE.txt", "LGPL", "../../evil.exe", "X");
        String[] ytSha = {sha(exe)};
        String[] ffSha = {sha(zip)};
        HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        srv.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            byte[] body = null;
            if (p.equals("/yt/releases/latest")) {
                ex.getResponseHeaders().add("Location", "https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            if (p.equals("/yt/releases/download/2026.10.26/SHA2-256SUMS")) body = (ytSha[0] + "  yt-dlp.exe\n" + "0".repeat(64) + "  yt-dlp_linux\n").getBytes();
            if (p.equals("/yt/releases/download/2026.10.26/yt-dlp.exe")) body = exe;
            if (p.equals("/ff/releases/download/latest/checksums.sha256"))
                body = ("1".repeat(64) + "  ffmpeg-n9.0-latest-win64-gpl-9.0.zip\n" + "2".repeat(64) + "  ffmpeg-n9.0-latest-win64-lgpl-shared-9.0.zip\n"
                        + "3".repeat(64) + "  ffmpeg-n8.1-latest-win64-lgpl-8.1.zip\n" + ffSha[0] + "  ffmpeg-n9.0-latest-win64-lgpl-9.0.zip\n"
                        + "4".repeat(64) + "  ffmpeg-master-latest-win64-lgpl.zip\n").getBytes();
            if (p.equals("/ff/releases/download/latest/ffmpeg-n9.0-latest-win64-lgpl-9.0.zip")) body = zip;
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, ex.getRequestMethod().equals("HEAD") ? -1 : body.length);
            if (!ex.getRequestMethod().equals("HEAD")) try (OutputStream o = ex.getResponseBody()) { o.write(body); }
            ex.close();
        });
        srv.start();
        try {
            String base = "http://127.0.0.1:" + srv.getAddress().getPort();
            Path dir = Files.createTempDirectory("mgtools");
            MediaTools mt = new MediaTools(dir, true, MediaTools.ytdlpSource(base + "/yt/releases", "Windows 11", "amd64"), MediaTools.ffmpegSource(base + "/ff/releases", "amd64"));
            assertTrue(mt.missing());
            // yt-dlp 校验和对不上：不装、不留文件
            ytSha[0] = "f".repeat(64);
            try {
                mt.install(false, null);
                fail("校验和不对要拒");
            } catch (java.io.IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("SHA-256"));
            }
            assertFalse(Files.exists(mt.ytdlpPath()));
            assertFalse(mt.installed(MediaTools.YTDLP));
            assertTrue(Files.list(dir).noneMatch(x -> x.toString().endsWith(".part")));
            ytSha[0] = sha(exe);
            List<String> got = mt.install(false, null);
            assertEquals(List.of("yt-dlp", "ffmpeg"), got);
            assertFalse(mt.missing());
            assertNull(mt.verify(MediaTools.YTDLP));
            assertNull(mt.verify(MediaTools.FFMPEG));
            assertTrue(Files.exists(mt.ffmpegDir().resolve("ffmpeg.exe")));
            assertTrue(Files.exists(mt.ffmpegDir().resolve("LICENSE.txt")));
            assertFalse("zip 里的 ../ 不解", Files.exists(dir.getParent().resolve("evil.exe")) || Files.exists(mt.ffmpegDir().resolve("evil.exe")));
            Map<String, Object> man = mt.manifest();
            Map<?, ?> ff = (Map<?, ?>) man.get("ffmpeg");
            assertEquals("挑最新正式分支的 LGPL 静态构建", "ffmpeg-n9.0-latest-win64-lgpl-9.0.zip", ff.get("asset"));
            assertTrue(String.valueOf(ff.get("license")).contains("LGPL"));
            assertEquals("2026.10.26", ((Map<?, ?>) man.get("yt-dlp")).get("version"));
            assertEquals("没缺的不重装", List.of(), mt.install(false, null));
            Map<?, ?> up = (Map<?, ?>) mt.checkUpdates().get("ffmpeg");
            assertEquals(Boolean.FALSE, up.get("update"));
            ffSha[0] = sha(zip("x/bin/ffmpeg.exe", "NEW"));
            assertEquals("发布页换了构建 = 有更新", Boolean.TRUE, ((Map<?, ?>) mt.checkUpdates().get("ffmpeg")).get("update"));
            // 装好后被改：拒绝运行
            Files.writeString(mt.ffmpegDir().resolve("ffprobe.exe"), "tampered");
            assertTrue(mt.verify(MediaTools.FFMPEG).contains("SHA-256"));
            assertEquals(Boolean.FALSE, ((Map<?, ?>) mt.view().get("ffmpeg")).get("ok"));
        } finally {
            srv.stop(0);
        }
        // 只有 GPL / shared：不自动装
        try {
            MediaTools.pick(MediaTools.ffmpegSource("https://x/releases", "amd64"), "latest",
                    "1".repeat(64) + "  ffmpeg-n9.0-latest-win64-gpl-9.0.zip\n" + "2".repeat(64) + "  ffmpeg-n9.0-latest-win64-lgpl-shared-9.0.zip\n");
            fail("没有 LGPL 静态构建不装");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("LGPL"));
        }
        assertEquals("yt-dlp.exe", MediaTools.ytdlpAsset("Windows 11", "amd64"));
        assertEquals("yt-dlp_linux_aarch64", MediaTools.ytdlpAsset("Linux", "aarch64"));
    }

    @Test public void toolsDirFollowsChineseUsernameFallback() {
        assertEquals("C:\\Users\\me\\AppData\\Local\\OneSay\\tools", MediaTools.toolsDir("C:\\Users\\me\\AppData\\Local", "C:\\ProgramData", "/h"));
        assertEquals("中文用户名退到 ProgramData", "C:\\ProgramData\\OneSay\\tools", MediaTools.toolsDir("C:\\Users\\张三\\AppData\\Local", "C:\\ProgramData", "/h"));
        assertEquals("D:\\PD\\OneSay\\tools", MediaTools.toolsDir("C:\\Users\\张三\\AppData\\Local\\", "D:\\PD\\", "/h"));
        assertEquals("C:\\ProgramData\\OneSay\\tools", MediaTools.toolsDir("C:\\Users\\张三\\AppData\\Local", null, "/h"));
        assertTrue(MediaTools.toolsDir(null, null, "/home/u").endsWith("onesay/tools"));
    }

    // ================================================================== 5. Cookie：只留这个站点、只给本账户、路径打码

    static final String COOKIES = "# Netscape HTTP Cookie File\n"
            + ".bilibili.com\tTRUE\t/\tFALSE\t1999999999\tSESSDATA\tsecretvalue123\n"
            + "#HttpOnly_.bilibili.com\tTRUE\t/\tTRUE\t1999999999\tbili_jct\tjct456\n"
            + ".evil.com\tTRUE\t/\tFALSE\t1999999999\tother\tnope\n";

    @Test public void cookiesPerSiteOwnerOnlyAndMaskedInLogs() throws Exception {
        Rig r = new Rig();
        assertNotNull("不是 Netscape 格式", r.ck.save("bilibili.com", "{\"cookies\":[]}"));
        assertNotNull("没有这个站点的", r.ck.save("bilibili.com", ".evil.com\tTRUE\t/\tFALSE\t0\ta\tb\n"));
        assertNotNull("站点不像域名", r.ck.save("../../etc", COOKIES));
        assertNull(r.ck.save("https://www.bilibili.com/", COOKIES));
        Path f = r.cfgDir.resolve("cookies").resolve("bilibili.com.txt");
        String saved = Files.readString(f);
        assertTrue(saved.contains("SESSDATA") && saved.contains("#HttpOnly_.bilibili.com"));
        assertFalse("别的站点的丢掉", saved.contains("evil.com\t"));
        assertEquals("只给本账户", "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.getParent())));
        assertEquals(1, r.ck.list().size());
        assertEquals(2L, ((Map<?, ?>) r.ck.list().get(0)).get("count"));
        assertNull("别的网站不用", r.ck.forUrl(URL));
        assertEquals(f, r.ck.forUrl("https://m.bilibili.com/video/BV1"));
        assertNull("仿冒域名不用", r.ck.forUrl("https://bilibili.com.evil.cn/x"));

        String bili = "https://www.bilibili.com/video/BV1xx411c7mD";
        Media.Task t = r.media.enqueue(bili, "best", "", "", false, info());
        await(t, 15000);
        assertEquals(t.error, Media.State.DONE, t.state);
        List<String> a = r.args();
        int i = a.indexOf("--cookies");
        assertTrue("带了 Cookie", i > 0);
        assertNotEquals(f.toString(), a.get(i + 1));
        assertTrue("运行时用的是临时副本，内容一样", Files.readString(r.tools.resolve("cookie.seen")).contains("secretvalue123"));
        assertFalse("用完删掉", Files.exists(Path.of(a.get(i + 1))));
        for (String l : r.log) {
            assertFalse("日志里没有 Cookie 内容：" + l, l.contains("secretvalue123") || l.contains("SESSDATA"));
            assertFalse("日志里 Cookie 路径打码：" + l, l.contains(a.get(i + 1)) || l.contains("cookies/bilibili"));
        }
        assertTrue(t.commands.get(0).contains(MediaArgs.COOKIE_MASK));
        assertTrue(r.ck.delete("bilibili.com"));
        assertFalse(Files.exists(f));
        r.media.shutdown();
    }

    static void assertNotEquals(Object a, Object b) { assertFalse(a + " == " + b, String.valueOf(a).equals(String.valueOf(b))); }

    // ================================================================== 6. 审批：media_download「能批就不拦」

    @Test public void approvalCardRememberAndModes() throws Exception {
        assertEquals(LinkPolicy.Cat.MEDIA_DOWNLOAD, LinkPolicy.pcCat("media_get"));
        assertEquals(LinkPolicy.Cat.QUERY, LinkPolicy.pcCat("media_status"));
        assertEquals(LinkPolicy.Cat.READ_PRIVATE, LinkPolicy.pcCat("media_fetch"));
        assertEquals(LinkPolicy.Cat.HARMLESS, LinkPolicy.pcCat("media_cancel"));
        assertEquals("手机作为被控端不认这条", LinkPolicy.Cat.UNKNOWN, LinkPolicy.phoneCat("media_get"));
        assertTrue(LinkPolicy.knownKind("pc.media_download"));
        assertTrue(LinkPolicy.knownKind("pc.media_tool_install"));

        // 默认（工作区可写 + 分类），工具已装：弹卡，类型 pc.media_download，可勾「以后这类不用问」
        Config c = PcPluginTest.cfg(WS, UN);
        ApproveNotBlockTest.KindStub st = new ApproveNotBlockTest.KindStub();
        PcGuard g = new PcGuard(c, st);
        g.mediaToolsMissing = () -> false;
        st.answer = PcGuard.TIMEOUT;
        assertEquals(LinkPolicy.R_CONFIRM_TIMEOUT, PcGuardTest.reason(g.check("phone", "media_get", m("url", URL))));
        assertEquals(List.of("media_get|pc.media_download"), st.asked);
        st.answer = PcGuard.DENY;
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, PcGuardTest.reason(g.check("phone", "media_get", m("url", URL))));
        st.answer = PcGuard.REMEMBER;
        PcGuard.Outcome o = g.check("phone", "media_get", m("url", URL, "quality", "720"));
        assertNull(o.reply);
        assertTrue(o.pass.consume("media_get", o.args));
        assertTrue("记住了", g.rememberedKinds().contains("pc.media_download"));
        int n = st.asked.size();
        o = g.check("phone", "media_get", m("url", URL));
        assertNull("以后这类不用问", o.reply);
        assertEquals("remembered", "pc.media_download", o.rememberedKind);
        assertEquals("没再问", n, st.asked.size());
        // 工具没装：卡片换成「先装工具」（另一类，记住了下载也照样问）；批了打内部标记 _media_install_ok
        g.mediaToolsMissing = () -> true;
        st.answer = PcGuard.ALLOW;
        o = g.check("phone", "media_get", m("url", URL, "_media_install_ok", Boolean.TRUE));
        assertNull(o.reply);
        assertEquals("media_get|pc.media_tool_install", st.asked.get(st.asked.size() - 1));
        assertEquals(Boolean.TRUE, o.args.get("_media_install_ok"));
        g.mediaToolsMissing = () -> false;
        o = g.check("phone", "media_get", m("url", URL, "_media_install_ok", Boolean.TRUE));
        assertNull("记住过下载，直接放行", o.reply);
        assertNull("手机自己带的 _media_install_ok 不算（判定时丢掉）", o.args.get("_media_install_ok"));
        // 链接不对：直接拒，不弹卡
        g.mediaToolsMissing = () -> false;
        n = st.asked.size();
        for (String bad : new String[]{"https://a.com/x;calc", "file:///c:/x", "--exec calc", "https://a.com/a b"}) {
            PcGuard.Outcome b = g.check("phone", "media_get", m("url", bad));
            assertEquals(bad, LinkPolicy.R_UNTRUSTED_URL, PcGuardTest.reason(b));
        }
        assertEquals(LinkPolicy.R_UNTRUSTED_URL, PcGuardTest.reason(g.check("phone", "media_get", m("url", URL, "audio", "wav"))));
        assertEquals("拒的没弹卡", n, st.asked.size());
        // 确认卡上写着链接
        assertTrue(PcGuard.detail("media_get", m("url", URL, "audio", "mp3"), "下载").contains(URL));
        assertTrue(PcGuard.detail("media_get", m("url", URL, "audio", "mp3"), "下载").contains("仅音频 mp3"));

        // 完全访问 + 从不询问：直接跑（缺工具也直接装）
        ApproveNotBlockTest.KindStub st2 = new ApproveNotBlockTest.KindStub();
        PcGuard gf = new PcGuard(PcPluginTest.cfg(FA, NV), st2);
        gf.mediaToolsMissing = () -> true;
        o = gf.check("phone", "media_get", m("url", URL));
        assertNull(o.reply);
        assertEquals(Boolean.TRUE, o.args.get("_media_install_ok"));
        assertEquals(0, st2.asked.size());
        // 工作区可写 + 从不询问：拒；只读：拒；按需：问
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, PcGuardTest.reason(new PcGuard(PcPluginTest.cfg(WS, NV), st2).check("phone", "media_get", m("url", URL))));
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, PcGuardTest.reason(new PcGuard(PcPluginTest.cfg(RO, UN), st2).check("phone", "media_get", m("url", URL))));
        assertEquals(0, st2.asked.size());
        st2.answer = PcGuard.ALLOW;
        assertNull(new PcGuard(PcPluginTest.cfg(FA, OR), st2).check("phone", "media_get", m("url", URL)).reply);
        assertEquals(1, st2.asked.size());
        // 查进度 / 取文件 / 取消 不弹卡
        PcGuard gq = new PcGuard(PcPluginTest.cfg(WS, UN), st2);
        assertNull(gq.check("phone", "media_status", m()).reply);
        assertNull(gq.check("phone", "media_fetch", m("id", 1L)).reply);
        assertNull(gq.check("phone", "media_cancel", m("id", 1L)).reply);
        assertEquals(1, st2.asked.size());
    }

    // ================================================================== 7. 远程指令处理 + 界面接口

    @Test public void remoteHandlersAndUiApi() throws Exception {
        Rig r = new Rig();
        Actions.Result g = r.media.remoteGet(m("url", "https://a.com/x;calc", "_from", "phone-1"));
        assertFalse(g.ok);
        assertEquals(LinkPolicy.R_UNTRUSTED_URL, g.data.get("reason"));
        g = r.media.remoteGet(m("url", URL, "quality", "720", "audio", "", "_from", "phone-1"));
        assertTrue(g.text, g.ok);
        int id = ((Number) g.data.get("id")).intValue();
        Media.Task t = r.media.task(id);
        await(t, 15000);
        assertEquals(t.error, Media.State.DONE, t.state);
        assertEquals("phone-1", t.from);
        assertEquals("测试视频", t.title);
        Actions.Result s = r.media.remoteStatus(m("id", (long) id));
        assertEquals("done", s.data.get("state"));
        assertEquals(1, ((List<?>) r.media.remoteStatus(m()).data.get("tasks")).size());
        // 工具缺、没批过安装：不排队
        Files.delete(r.mt.ytdlpPath());
        Files.delete(r.tools.resolve("tools.json"));
        assertFalse(r.media.remoteGet(m("url", URL)).ok);
        r.media.shutdown();

        // 界面接口：预览（会先整理分享文字）→ 下载 → 取消 → Cookie 导入 / 删除
        Rig u = new Rig();
        Map<String, Object> pv = App.mediaApi(u.media, m("op", "preview", "url", "看这个 " + URL + "#t=3 好看", "quality", "1080"), x -> { }, null);
        assertEquals(Boolean.TRUE, pv.get("ok"));
        assertEquals(URL, pv.get("url"));
        assertEquals("测试视频", pv.get("title"));
        assertEquals("1:05", pv.get("duration"));
        assertEquals(Boolean.FALSE, App.mediaApi(u.media, m("op", "preview", "url", "没有链接"), x -> { }, null).get("ok"));
        u.mode("slow");
        Map<String, Object> dl = App.mediaApi(u.media, m("op", "download", "url", URL, "quality", "1080", "title", "测试视频", "seconds", 65.4, "bytes", 10L), x -> { }, null);
        assertEquals(Boolean.TRUE, dl.get("ok"));
        int did = ((Number) ((Map<?, ?>) dl.get("task")).get("id")).intValue();
        Media.Task dt = u.media.task(did);
        long end = System.currentTimeMillis() + 10000;
        while (dt.percent < 42 && System.currentTimeMillis() < end) Thread.sleep(50);
        assertEquals("进度解析到一半", 42.5, dt.percent, 0.01);
        assertEquals("00:01", dt.eta);
        assertEquals(Boolean.TRUE, App.mediaApi(u.media, m("op", "cancel", "id", (long) did), x -> { }, null).get("ok"));
        await(dt, 10000);
        assertEquals(Media.State.CANCELED, dt.state);
        assertEquals(Boolean.TRUE, App.mediaApi(u.media, m("op", "cookie_import", "site", "bilibili.com", "text", COOKIES), x -> { }, null).get("ok"));
        Map<String, Object> st = App.mediaApi(u.media, m("op", "state"), x -> { }, null);
        assertEquals(1, ((List<?>) st.get("cookies")).size());
        assertFalse("状态里不带 Cookie 内容", String.valueOf(st).contains("secretvalue123"));
        assertEquals(Boolean.TRUE, App.mediaApi(u.media, m("op", "cookie_delete", "site", "bilibili.com"), x -> { }, null).get("ok"));
        assertEquals(Boolean.FALSE, App.mediaApi(u.media, m("op", "nope"), x -> { }, null).get("ok"));
        u.media.shutdown();
    }

    @Test public void configUiAndNotice() throws Exception {
        Config c = new Config(Files.createTempFile("mgc", ".properties"));
        c.mediaQuality = "720";
        c.save();
        assertEquals("720", Config.load(c.file).mediaQuality);
        Files.writeString(c.file, "media_quality=; calc\n");
        assertEquals("best", Config.load(c.file).mediaQuality);
        String html = new String(UiServer.resource("onesay/ui/index.html"), StandardCharsets.UTF_8);
        for (String must : new String[]{"mdPanel()", "sec('var(--accent)','下载'", "mdInstall(false)", "mdInstall(true)", "mdCheck()", "seg('media_quality'", "mdCookie()", "mdOpen()", "op:'preview'", "op:'download'"})
            assertTrue(must, html.contains(must));
        assertFalse(html.contains("Bixby"));
        assertFalse(html.contains("三星"));
        for (Class<?> k : new Class<?>[]{Media.class, MediaArgs.class, MediaTools.class, MediaCookies.class}) {
            String src = Files.readString(Path.of("src/main/java/com/onesay/desktop/" + k.getSimpleName() + ".java"));
            assertFalse(k + " 用户可见文字", src.contains("Bixby") || src.contains("三星"));
        }
        assertTrue("注明思路来源", Files.readString(Path.of("src/main/java/com/onesay/desktop/Media.java")).contains("思路参考 omniget"));
        String notice = Files.readString(Path.of("NOTICE"));
        assertTrue(notice.contains("omniget") && notice.contains("yt-dlp") && notice.contains("FFmpeg"));
    }
}
