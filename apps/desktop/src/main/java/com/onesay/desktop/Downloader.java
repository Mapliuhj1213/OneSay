package com.onesay.desktop;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * [overnight-1119] 对话里的「下载文件」工具（download）：Java 自己下，边下边把进度写进任务卡（百分比、已下 / 总大小、速度、剩余时间，
 * 总大小不知道就是不定进度），可以取消；先下到 .part，下完再改名，顺手算 SHA-256（给「校验」用）。
 *
 * <p>审批不另起一套：按等价的 {@code Invoke-WebRequest -Uri '网址' -OutFile '路径'} 交给原来的判定（联网域名、按路径放行、本次任务批准都照旧）。
 */
public final class Downloader {
    private Downloader() {}

    public static final long MAX = 4L * 1024 * 1024 * 1024;

    /** 判定用的等价命令（字面网址和路径，PathScope 认得出「只写下载 / 桌面…」）。 */
    public static String equivalentCommand(String url, String path) {
        return "Invoke-WebRequest -Uri '" + url.replace("'", "''") + "' -OutFile '" + path.replace("'", "''") + "'";
    }

    /** 网络（测试换）：给网址，回 (状态码, 总大小 或 -1, 输入流)。 */
    public interface Net {
        Object[] open(String url, int timeoutSec) throws Exception;
    }

    public static volatile Net net = Downloader::httpOpen;

    static Object[] httpOpen(String url, int timeoutSec) throws Exception {
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(Math.max(30, timeoutSec))).header("User-Agent", "OneSay-Desktop").GET().build();
        HttpResponse<InputStream> r = c.send(rq, HttpResponse.BodyHandlers.ofInputStream());
        long len = r.headers().firstValueAsLong("content-length").orElse(-1L);
        return new Object[] {r.statusCode(), len, r.body()};
    }

    public static Actions.Result download(String url, Path dest, int timeoutSec) {
        if (url == null || !url.matches("(?i)https?://\\S+")) return Actions.Result.fail("只下载 http / https 网址");
        Progress.CANCEL.set(false);
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Map<String, Object> d = new TreeMap<>();
        try {
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            Object[] o = net.open(url, timeoutSec);
            int code = (Integer) o[0];
            long total = (Long) o[1];
            if (code / 100 != 2) {
                try { ((InputStream) o[2]).close(); } catch (Exception ignored) { }
                return Actions.Result.fail("下载失败：服务器回 " + code + (code == 404 ? "（地址不对或文件不存在）" : ""));
            }
            if (total > MAX) return Actions.Result.fail("文件太大（超过 4 GB）");
            Progress.Meter m = new Progress.Meter("下载 " + dest.getFileName(), total);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long done = 0;
            long deadline = System.currentTimeMillis() + Math.max(30, timeoutSec) * 1000L;
            try (InputStream in = (InputStream) o[2]; OutputStream out = Files.newOutputStream(part)) {
                byte[] b = new byte[64 * 1024];
                int n;
                m.tick(0, true);
                while ((n = in.read(b)) > 0) {
                    if (Progress.CANCEL.get()) { out.close(); Files.deleteIfExists(part); d.put("reason", "cancelled"); return new Actions.Result(false, "已取消下载（下了 " + FileXfer.human(done) + "）", d); }
                    if (System.currentTimeMillis() > deadline) { out.close(); Files.deleteIfExists(part); return Actions.Result.fail("下载超过 " + timeoutSec + " 秒没下完，已停（下了 " + FileXfer.human(done) + "）"); }
                    out.write(b, 0, n);
                    md.update(b, 0, n);
                    done += n;
                    if (done > MAX) { out.close(); Files.deleteIfExists(part); return Actions.Result.fail("文件太大（超过 4 GB），已停"); }
                    m.tick(done, false);
                }
            }
            m.tick(done, true);
            if (total > 0 && done != total) { Files.deleteIfExists(part); return Actions.Result.fail("下载不完整（" + FileXfer.human(done) + " / " + FileXfer.human(total) + "），连接中途断了"); }
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
            String sha = FileXfer.hex(md.digest());
            d.put("path", dest.toString());
            d.put("size", done);
            d.put("sha256", sha);
            d.put("output", "已下载到 " + dest + "\n大小 " + done + " 字节（" + FileXfer.human(done) + "）\nSHA-256 " + sha);
            return Actions.Result.ok("下载好了：" + dest.getFileName() + "（" + FileXfer.human(done) + "）", d);
        } catch (Exception e) {
            try { Files.deleteIfExists(part); } catch (Exception ignored) { }
            return Actions.Result.fail("下载出错：" + (e.getMessage() == null ? e.getClass().getSimpleName() : LinkPolicy.oneLine(e.getMessage())));
        }
    }
}
