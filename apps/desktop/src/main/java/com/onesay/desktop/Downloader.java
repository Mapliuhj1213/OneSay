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
    /** 看门狗多久看一次取消 / 超时（毫秒）。 */
    static final long WATCH_MS = 200;

    /** 判定用的等价命令（字面网址和路径，PathScope 认得出「只写下载 / 桌面…」）。 */
    public static String equivalentCommand(String url, String path) {
        return "Invoke-WebRequest -Uri '" + url.replace("'", "''") + "' -OutFile '" + path.replace("'", "''") + "'";
    }

    /** 网络（测试换）：给网址，回 (状态码, 总大小 或 -1, 输入流[, 跳转地址 Location])。不自己跟跳转（[preview-1120 A-03] 跳转由 {@link #download} 逐跳核对）。 */
    public interface Net {
        Object[] open(String url, int timeoutSec) throws Exception;
    }

    public static volatile Net net = Downloader::httpOpen;

    static Object[] httpOpen(String url, int timeoutSec) throws Exception {
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(Math.max(30, timeoutSec))).header("User-Agent", "OneSay-Desktop").GET().build();
        HttpResponse<InputStream> r = c.send(rq, HttpResponse.BodyHandlers.ofInputStream());
        long len = r.headers().firstValueAsLong("content-length").orElse(-1L);
        return new Object[] {r.statusCode(), len, r.body(), r.headers().firstValue("location").orElse(null)};
    }

    /** 最多跟几次跳转。 */
    public static final int MAX_REDIRECTS = 5;

    /** 旧调用：跳转只许留在同一个主机。 */
    public static Actions.Result download(String url, Path dest, int timeoutSec) {
        return download(url, dest, timeoutSec, null);
    }

    static String host(String url) {
        try { String h = URI.create(url).getHost(); return h == null ? "" : h.toLowerCase(java.util.Locale.ROOT); } catch (Exception e) { return ""; }
    }

    /**
     * 下载。hostOk：跳转到别的主机时问它行不行（null = 只许同一个主机）。
     * [preview-1120 AUDIT A-03] 以前 HttpClient 自己跟跳转：审批 / 联网白名单只看了第一个网址，跳到哪都照下。现在逐跳核对，
     * 跳到没批准的主机就停下，如实说跳到了哪（要下得用新网址再走一次审批）。
     * [preview-1120 AUDIT A-02] 以前「取消 / 超时」只在读到数据之后才看：连接卡住不出数据时 read() 一直阻塞，取消和超时都不生效。
     * 现在有个看门狗线程，取消或到时就把连接关掉，阻塞的 read() 立刻返回。
     */
    public static Actions.Result download(String url, Path dest, int timeoutSec, java.util.function.Predicate<String> hostOk) {
        if (url == null || !url.matches("(?i)https?://\\S+")) return Actions.Result.fail("只下载 http / https 网址");
        java.util.concurrent.atomic.AtomicBoolean cancel = Progress.begin();
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Map<String, Object> d = new TreeMap<>();
        String first = host(url);
        try {
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            Object[] o = net.open(url, timeoutSec);
            int code = (Integer) o[0];
            String at = url;
            for (int hop = 0; code / 100 == 3 && o.length > 3 && o[3] != null; hop++) {
                try { ((InputStream) o[2]).close(); } catch (Exception ignored) { }
                if (hop >= MAX_REDIRECTS) return Actions.Result.fail("下载失败：跳转太多次（超过 " + MAX_REDIRECTS + " 次）");
                String next;
                try { next = URI.create(at).resolve(String.valueOf(o[3]).trim()).toString(); } catch (Exception e) { return Actions.Result.fail("下载失败：服务器给的跳转地址不对"); }
                if (!next.matches("(?i)https?://\\S+")) return Actions.Result.fail("下载失败：服务器要跳到不是 http / https 的地址，没跟");
                if (at.regionMatches(true, 0, "https:", 0, 6) && next.regionMatches(true, 0, "http:", 0, 5))
                    return Actions.Result.fail("下载失败：服务器要从 https 跳到不加密的 http，没跟");
                String h = host(next);
                if (!h.equals(first) && (hostOk == null || !hostOk.test(h))) {
                    d.put("reason", "redirect_not_approved");
                    d.put("redirect", next);
                    return new Actions.Result(false, "没下载：「" + first + "」把下载跳转到了「" + h + "」，这个地址没经过你批准。要下的话用新地址（" + LinkPolicy.oneLine(next) + "）再发起一次，会重新确认", d);
                }
                at = next;
                o = net.open(at, timeoutSec);
                code = (Integer) o[0];
            }
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
            InputStream src = (InputStream) o[2];
            java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<String> killed = new java.util.concurrent.atomic.AtomicReference<>();
            Thread dog = new Thread(() -> {
                while (!finished.get()) {
                    String why = cancel.get() ? "cancel" : System.currentTimeMillis() > deadline ? "timeout" : null;
                    if (why != null) { killed.set(why); try { src.close(); } catch (Exception ignored) { } return; }
                    try { Thread.sleep(WATCH_MS); } catch (InterruptedException e) { return; }
                }
            }, "onesay-download-watch");
            dog.setDaemon(true);
            dog.start();
            try (InputStream in = src; OutputStream out = Files.newOutputStream(part)) {
                byte[] b = new byte[64 * 1024];
                int n;
                m.tick(0, true);
                while (true) {
                    try { n = in.read(b); }
                    catch (java.io.IOException e) { if (killed.get() == null) throw e; n = -1; }
                    if (killed.get() != null && !cancel.get() && n <= 0) { out.close(); Files.deleteIfExists(part); return Actions.Result.fail("下载超过 " + timeoutSec + " 秒没下完，已停（下了 " + FileXfer.human(done) + "）"); }
                    if (n <= 0 && !cancel.get()) break;
                    if (cancel.get()) { out.close(); Files.deleteIfExists(part); d.put("reason", "cancelled"); return new Actions.Result(false, "已取消下载（下了 " + FileXfer.human(done) + "）", d); }
                    if (System.currentTimeMillis() > deadline) { out.close(); Files.deleteIfExists(part); return Actions.Result.fail("下载超过 " + timeoutSec + " 秒没下完，已停（下了 " + FileXfer.human(done) + "）"); }
                    out.write(b, 0, n);
                    md.update(b, 0, n);
                    done += n;
                    if (done > MAX) { out.close(); Files.deleteIfExists(part); return Actions.Result.fail("文件太大（超过 4 GB），已停"); }
                    m.tick(done, false);
                }
            } finally {
                finished.set(true);
                dog.interrupt();
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
