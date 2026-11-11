package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Assume;
import org.junit.Test;

/**
 * 链接下载端到端（要联网，默认跳过；ONESAY_MEDIA_E2E=1 才跑，不在 build.sh 的固定清单里）：
 * 从官方发布页真实安装 yt-dlp（核对官方 SHA2-256SUMS）→ 预览 → 下载 Wikimedia Commons 上 Blender 基金会的
 * 《Big Buck Bunny》预告片（CC BY 3.0）。给了 ONESAY_E2E_FFMPEG_DIR（已按官方 checksums.sha256 核对过的 LGPL 构建）时，再试仅音频 m4a。
 */
public class MediaE2ETest {
    static final String URL = "https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_Trailer_1080p.ogv";

    @Test public void realDownload() throws Exception {
        Assume.assumeTrue("ONESAY_MEDIA_E2E=1 才跑", "1".equals(System.getenv("ONESAY_MEDIA_E2E")));
        Path root = Files.createTempDirectory("mg-e2e");
        String os = System.getProperty("os.name"), arch = System.getProperty("os.arch");
        MediaTools mt = new MediaTools(root.resolve("tools"), false, MediaTools.ytdlpSource(MediaTools.YTDLP_RELEASES, os, arch), null);
        List<String> log = new CopyOnWriteArrayList<>();
        assertEquals(List.of("yt-dlp"), mt.install(false, (w, d, t) -> { }));
        assertNull(mt.verify(MediaTools.YTDLP));
        Map<?, ?> y = (Map<?, ?>) mt.manifest().get("yt-dlp");
        System.out.println("E2E yt-dlp " + y.get("version") + " " + y.get("asset") + " sha256=" + y.get("sha256"));
        String ff = System.getenv("ONESAY_E2E_FFMPEG_DIR");
        if (ff != null && !ff.isEmpty()) {
            mt.register(MediaTools.FFMPEG, System.getenv().getOrDefault("ONESAY_E2E_FFMPEG_VER", "lgpl"), System.getenv().getOrDefault("ONESAY_E2E_FFMPEG_ASSET", ""),
                    System.getenv().getOrDefault("ONESAY_E2E_FFMPEG_SHA", ""), copyFf(Path.of(ff), mt.ffmpegDir()));
            assertNull(mt.verify(MediaTools.FFMPEG));
        }
        Media media = new Media(mt, new MediaCookies(root.resolve("cookies")), root.resolve("Downloads"), log::add);
        Media.Info in = media.preview(URL, "720", "");
        System.out.println("E2E 预览：" + in.title + " · " + MediaArgs.duration(in.duration) + " · " + MediaArgs.mb(in.size));
        assertTrue(in.title.contains("Big Buck Bunny"));
        Media.Task t = media.enqueue(URL, "720", "", "", false, in);
        long end = System.currentTimeMillis() + 110_000;
        while (!t.finished() && System.currentTimeMillis() < end) Thread.sleep(200);
        System.out.println("E2E 视频：" + t.state + " " + t.file + " " + t.error + " · " + t.commands);
        assertEquals(t.error, Media.State.DONE, t.state);
        assertTrue(Files.size(Path.of(t.file)) > 100_000);
        if (ff != null && !ff.isEmpty()) {
            Media.Task a = media.enqueue(URL, "best", "m4a", "", false, null);
            end = System.currentTimeMillis() + 110_000;
            while (!a.finished() && System.currentTimeMillis() < end) Thread.sleep(200);
            System.out.println("E2E 仅音频：" + a.state + " " + a.file + " " + a.error);
            assertEquals(a.error, Media.State.DONE, a.state);
            assertTrue(a.file.endsWith(".m4a"));
        }
        for (String l : log) assertFalse(l, l.contains("--cookies /"));
        media.shutdown();
    }

    static Map<String, Path> copyFf(Path src, Path dst) throws Exception {
        Files.createDirectories(dst);
        Files.copy(src.resolve("ffmpeg"), dst.resolve("ffmpeg"));
        Files.copy(src.resolve("ffprobe"), dst.resolve("ffprobe"));
        dst.resolve("ffmpeg").toFile().setExecutable(true);
        dst.resolve("ffprobe").toFile().setExecutable(true);
        return Map.of("ffmpeg", dst.resolve("ffmpeg"), "ffprobe", dst.resolve("ffprobe"));
    }
}
