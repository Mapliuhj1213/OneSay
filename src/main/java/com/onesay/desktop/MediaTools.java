package com.onesay.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 链接下载要用的两个外部工具：yt-dlp（Unlicense）和 FFmpeg（选 LGPL 构建）。不随安装包附带，第一次用时按需下载，
 * 存在 %LOCALAPPDATA%\OneSay\tools（用户目录里有中文等非 ASCII 字符时退到 %ProgramData%\OneSay\tools，和工作区同一条规则）。
 *
 * <p>校验：下载前先从同一个官方发布页取校验和文件（yt-dlp 的 SHA2-256SUMS、FFmpeg-Builds 的 checksums.sha256），下完边算 SHA-256 边比，
 * 对不上删掉、不装。装好后把每个可执行文件的 SHA-256 记进 tools.json；每次运行前再算一遍，和记录对不上就拒绝运行（{@link #verify}）。
 *
 * <p>FFmpeg 只认文件名里带 -lgpl- 的静态构建（不要 shared、不要 gpl）；找不到 LGPL 构建就不自动装，界面提示你自己装（作为你本机单独下载的外部工具）。
 * 自动安装 FFmpeg 只在 Windows 上做（官方构建是 zip）；其他系统只自动装 yt-dlp。思路参考 omniget「引擎按需下载」，代码自写。
 */
public final class MediaTools {

    public static final String YTDLP = "yt-dlp", FFMPEG = "ffmpeg";
    public static final String YTDLP_RELEASES = "https://github.com/yt-dlp/yt-dlp/releases";
    public static final String FFMPEG_RELEASES = "https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases";

    /** 一个工具从哪下：发布页、固定 tag（null = 跟 latest 跳到的 tag）、校验和文件名、挑哪个文件、是不是 zip（zip 里解出哪些）。 */
    public static final class Source {
        public final String name, releases, fixedTag, sums, license;
        public final Pattern asset;
        public final boolean zip;
        public final List<String> extract;

        public Source(String name, String releases, String fixedTag, String sums, Pattern asset, boolean zip, List<String> extract, String license) {
            this.name = name;
            this.releases = releases.replaceAll("/+$", "");
            this.fixedTag = fixedTag;
            this.sums = sums;
            this.asset = asset;
            this.zip = zip;
            this.extract = extract == null ? List.of() : List.copyOf(extract);
            this.license = license;
        }
    }

    /** 当前系统用的 yt-dlp 文件名（官方发布页上的名字）。 */
    public static String ytdlpAsset(String os, String arch) {
        String o = os.toLowerCase(Locale.ROOT), a = arch.toLowerCase(Locale.ROOT);
        boolean arm = a.contains("aarch64") || a.contains("arm64");
        if (o.contains("win")) return arm ? "yt-dlp_arm64.exe" : "yt-dlp.exe";
        if (o.contains("mac")) return "yt-dlp_macos";
        return arm ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
    }

    /** 本机上 yt-dlp 存成什么名字。 */
    public static String ytdlpExe(boolean windows) { return windows ? "yt-dlp.exe" : "yt-dlp"; }

    public static Source ytdlpSource(String releases, String os, String arch) {
        return new Source(YTDLP, releases, null, "SHA2-256SUMS", Pattern.compile(Pattern.quote(ytdlpAsset(os, arch))), false, null, "Unlicense");
    }

    /** FFmpeg：最新的正式分支 LGPL 静态构建，例如 ffmpeg-n8.1-latest-win64-lgpl-8.1.zip（取版本号最大的）。 */
    public static Source ffmpegSource(String releases, String arch) {
        String plat = arch.toLowerCase(Locale.ROOT).contains("aarch64") || arch.toLowerCase(Locale.ROOT).contains("arm64") ? "winarm64" : "win64";
        return new Source(FFMPEG, releases, "latest", "checksums.sha256",
                Pattern.compile("ffmpeg-n(\\d+)\\.(\\d+)-latest-" + plat + "-lgpl-\\1\\.\\2\\.zip"), true,
                List.of("bin/ffmpeg.exe", "bin/ffprobe.exe", "LICENSE.txt"), "LGPL-3.0（FFmpeg-Builds 的 LGPL 构建）");
    }

    /**
     * 工具目录：Windows %LOCALAPPDATA%\OneSay\tools；路径里有非 ASCII（中文用户名）时退到 %ProgramData%\OneSay\tools；
     * 没有 LOCALAPPDATA（非 Windows）时 ~/.local/share/onesay/tools。只算路径，不建目录。
     */
    public static String toolsDir(String localAppData, String programData, String userHome) {
        String la = localAppData == null ? "" : localAppData.trim();
        if (la.isEmpty()) return Paths.get(userHome == null ? "." : userHome, ".local", "share", "onesay", "tools").toString();
        boolean ascii = true;
        for (int i = 0; i < la.length(); i++) if (la.charAt(i) > 0x7e || la.charAt(i) < 0x20) { ascii = false; break; }
        String base = ascii ? la : (programData == null || programData.trim().isEmpty() ? "C:\\ProgramData" : programData.trim());
        while (base.endsWith("\\") || base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "\\OneSay\\tools";
    }

    /** 下载限制：防止发布页被换成超大文件。 */
    static final long MAX_TOOL = 400L * 1024 * 1024;

    public interface Progress { void on(String what, long done, long total); }

    final Path dir;
    final boolean windows;
    final Source ytdlp, ffmpeg;
    /** 可换（测试用本机假发布页）。 */
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
    HttpClient noRedirect = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();

    public MediaTools(Path dir, boolean windows, Source ytdlp, Source ffmpeg) {
        this.dir = dir;
        this.windows = windows;
        this.ytdlp = ytdlp;
        this.ffmpeg = ffmpeg;
    }

    /** 这台电脑的默认设置。 */
    public static MediaTools forThisPc() {
        String os = System.getProperty("os.name", ""), arch = System.getProperty("os.arch", "");
        boolean win = os.toLowerCase(Locale.ROOT).contains("win");
        String over = System.getProperty("onesay.tools.dir", "");
        Path d = !over.isEmpty() ? Paths.get(over) : Paths.get(toolsDir(win ? System.getenv("LOCALAPPDATA") : null, System.getenv("ProgramData"), System.getProperty("user.home")));
        return new MediaTools(d, win, ytdlpSource(YTDLP_RELEASES, os, arch), win ? ffmpegSource(FFMPEG_RELEASES, arch) : null);
    }

    public Path dir() { return dir; }

    public Path ytdlpPath() { return dir.resolve(ytdlpExe(windows)); }

    /** FFmpeg 所在目录（给 --ffmpeg-location）。 */
    public Path ffmpegDir() { return dir.resolve("ffmpeg"); }

    Path manifestFile() { return dir.resolve("tools.json"); }

    // ------------------------------------------------------------------ 记录（tools.json）

    @SuppressWarnings("unchecked")
    synchronized Map<String, Object> manifest() {
        try {
            if (!Files.isRegularFile(manifestFile())) return new TreeMap<>();
            Map<String, Object> m = Json.obj(Files.readString(manifestFile(), StandardCharsets.UTF_8));
            return m == null ? new TreeMap<>() : new TreeMap<>(m);
        } catch (Exception e) {
            return new TreeMap<>(); // 记录坏了 = 当没装（会要求重新安装），绝不当作校验通过
        }
    }

    synchronized void saveManifest(Map<String, Object> m) throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve("tools.json.tmp");
        Files.writeString(tmp, Json.stringify(m), StandardCharsets.UTF_8);
        Files.move(tmp, manifestFile(), StandardCopyOption.REPLACE_EXISTING);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> entry(String tool) {
        Object e = manifest().get(tool);
        return e instanceof Map ? (Map<String, Object>) e : null;
    }

    public boolean installed(String tool) {
        Map<String, Object> e = entry(tool);
        return e != null && e.get("files") instanceof Map && !((Map<?, ?>) e.get("files")).isEmpty();
    }

    /** 工具缺不缺：yt-dlp 必须有；Windows 上 FFmpeg 也必须有（合并音视频 / 转音频要它）。 */
    public boolean missing() {
        return !installed(YTDLP) || (ffmpeg != null && !installed(FFMPEG));
    }

    /**
     * 运行前校验：记录里的每个文件都在、SHA-256 和记录一样。没问题返回 null，否则返回原因（调用方拒绝运行）。
     */
    @SuppressWarnings("unchecked")
    public String verify(String tool) {
        Map<String, Object> e = entry(tool);
        if (e == null || !(e.get("files") instanceof Map)) return tool + " 还没安装";
        Path base = YTDLP.equals(tool) ? dir : ffmpegDir();
        for (Map.Entry<String, Object> f : ((Map<String, Object>) e.get("files")).entrySet()) {
            Path p = base.resolve(f.getKey()).normalize();
            if (!p.startsWith(base.normalize())) return tool + " 的记录不对";
            try {
                if (!Files.isRegularFile(p)) return tool + " 的文件不在了：" + f.getKey() + "（请在设置里重新安装）";
                if (!sha256(p).equalsIgnoreCase(String.valueOf(f.getValue()))) return tool + " 的 SHA-256 和安装时记录的不一样（文件可能被改过），拒绝运行，请在设置里重新安装";
            } catch (IOException x) {
                return tool + " 校验时读不了文件";
            }
        }
        return null;
    }

    /** 设置页用：每个工具 {installed, version, ok(校验), why, license, asset}。 */
    public Map<String, Object> view() {
        Map<String, Object> out = new TreeMap<>();
        for (String t : List.of(YTDLP, FFMPEG)) {
            Map<String, Object> v = new TreeMap<>();
            Map<String, Object> e = entry(t);
            boolean auto = YTDLP.equals(t) || ffmpeg != null;
            v.put("auto", auto);
            v.put("installed", e != null);
            v.put("version", e == null ? "" : String.valueOf(e.getOrDefault("version", "")));
            v.put("asset", e == null ? "" : String.valueOf(e.getOrDefault("asset", "")));
            v.put("license", YTDLP.equals(t) ? "Unlicense" : ffmpeg != null ? ffmpeg.license : "LGPL 构建，需你自己安装");
            String why = e == null ? "" : verify(t);
            v.put("ok", e != null && why == null);
            v.put("why", why == null ? "" : why);
            out.put(t, v);
        }
        out.put("dir", dir.toString());
        out.put("missing", missing());
        return out;
    }

    // ------------------------------------------------------------------ 安装 / 更新

    /** 发布页上找到的一个文件：tag、文件名、官方 SHA-256。 */
    public static final class Release {
        public final String tag, asset, sha256;

        Release(String tag, String asset, String sha256) {
            this.tag = tag;
            this.asset = asset;
            this.sha256 = sha256;
        }

        public String version() {
            return tag.equals("latest") ? asset.replaceAll("^ffmpeg-(n\\d+\\.\\d+).*$", "$1") + "（" + sha256.substring(0, 8) + "）" : tag;
        }
    }

    /** 看发布页：latest 跳到哪个 tag，校验和文件里有没有要的文件。 */
    public Release latest(Source s) throws IOException, InterruptedException {
        String tag = s.fixedTag;
        if (tag == null) {
            HttpResponse<Void> r = noRedirect.send(req(s.releases + "/latest").method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            String loc = r.headers().firstValue("location").orElse("");
            Matcher m = Pattern.compile("/releases/tag/([A-Za-z0-9._-]{1,64})$").matcher(loc);
            if (!m.find()) throw new IOException(s.name + " 发布页没给出版本（HTTP " + r.statusCode() + "）");
            tag = m.group(1);
        }
        String sums = text(s.releases + "/download/" + tag + "/" + s.sums, 512 * 1024);
        return pick(s, tag, sums);
    }

    /** 从校验和文件里挑文件：行格式「64 位十六进制  文件名」（文件名前可有 *）；多个匹配取版本号最大的。 */
    static Release pick(Source s, String tag, String sums) throws IOException {
        Release best = null;
        int bestVer = -1;
        for (String line : sums.split("\\r?\\n")) {
            Matcher m = Pattern.compile("^([0-9a-fA-F]{64})\\s+\\*?(\\S+)$").matcher(line.trim());
            if (!m.find()) continue;
            String name = m.group(2);
            Matcher am = s.asset.matcher(name);
            if (!am.matches()) continue;
            if (FFMPEG.equals(s.name) && (!name.contains("-lgpl-") || name.contains("shared"))) continue; // 只要 LGPL 静态构建
            int ver = am.groupCount() >= 2 ? Integer.parseInt(am.group(1)) * 1000 + Integer.parseInt(am.group(2)) : 0;
            if (ver > bestVer) {
                bestVer = ver;
                best = new Release(tag, name, m.group(1).toLowerCase(Locale.ROOT));
            }
        }
        if (best == null) throw new IOException(FFMPEG.equals(s.name) ? "发布页上没找到 FFmpeg 的 LGPL 构建，不自动安装（可自己安装 FFmpeg）" : s.name + " 的校验和文件里没有本机要的文件");
        return best;
    }

    /** 检查更新：返回 {tool → {installed, latest, update}}；查不到的带 error。 */
    public Map<String, Object> checkUpdates() {
        Map<String, Object> out = new TreeMap<>();
        for (Source s : sources()) {
            Map<String, Object> v = new TreeMap<>();
            Map<String, Object> e = entry(s.name);
            v.put("installed", e == null ? "" : String.valueOf(e.getOrDefault("version", "")));
            try {
                Release r = latest(s);
                v.put("latest", r.version());
                v.put("update", e == null || !r.sha256.equalsIgnoreCase(String.valueOf(e.get("sha256"))));
            } catch (Exception x) {
                v.put("error", msg(x));
            }
            out.put(s.name, v);
        }
        return out;
    }

    List<Source> sources() {
        List<Source> l = new ArrayList<>();
        l.add(ytdlp);
        if (ffmpeg != null) l.add(ffmpeg);
        return l;
    }

    /** 装缺的（force = 全部重新下载，用于更新）。单线程：同一时间只装一次。返回装了哪些。 */
    public synchronized List<String> install(boolean force, Progress p) throws IOException, InterruptedException {
        List<String> done = new ArrayList<>();
        for (Source s : sources()) {
            if (!force && installed(s.name) && verify(s.name) == null) continue;
            install(s, p);
            done.add(s.name);
        }
        return done;
    }

    /** 装一个：取官方校验和 → 下载到 .part（边下边算）→ 对上才放到位、记进 tools.json。对不上删掉，抛错。 */
    synchronized void install(Source s, Progress p) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Release r = latest(s);
        Path part = dir.resolve(r.asset + ".part");
        try {
            fetch(s.releases + "/download/" + r.tag + "/" + r.asset, part, r.sha256, s.name, p);
            Map<String, Object> files = new TreeMap<>();
            if (!s.zip) {
                Path exe = ytdlpPath();
                Files.move(part, exe, StandardCopyOption.REPLACE_EXISTING);
                exe.toFile().setExecutable(true, true);
                files.put(exe.getFileName().toString(), sha256(exe));
            } else {
                Path out = ffmpegDir();
                Files.createDirectories(out);
                for (Map.Entry<String, Path> e : unzip(part, s.extract, out).entrySet()) files.put(e.getKey(), sha256(e.getValue()));
                if (!files.containsKey("ffmpeg.exe")) throw new IOException("FFmpeg 压缩包里没有 ffmpeg.exe");
            }
            Map<String, Object> m = manifest();
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("version", r.version());
            e.put("tag", r.tag);
            e.put("asset", r.asset);
            e.put("sha256", r.sha256);
            e.put("license", s.license);
            e.put("source", s.releases);
            e.put("installed_at", System.currentTimeMillis());
            e.put("files", files);
            m.put(s.name, e);
            saveManifest(m);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** zip 里任意顶层目录下的 bin/ffmpeg.exe 这类（任意顶层目录下）解到 out（只要文件名，不建子目录）。防目录穿越：只取最后一段文件名。 */
    static Map<String, Path> unzip(Path zip, List<String> wanted, Path out) throws IOException {
        Map<String, Path> got = new TreeMap<>();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName().replace('\\', '/');
                for (String w : wanted) {
                    if (!(n.equals(w) || n.endsWith("/" + w))) continue;
                    String leaf = w.substring(w.lastIndexOf('/') + 1);
                    if (leaf.isEmpty() || leaf.contains("..")) continue;
                    Path dst = out.resolve(leaf);
                    Path tmp = out.resolve(leaf + ".part");
                    try (OutputStream o = Files.newOutputStream(tmp)) {
                        byte[] b = new byte[1 << 16];
                        long total = 0;
                        int k;
                        while ((k = z.read(b)) > 0) {
                            total += k;
                            if (total > MAX_TOOL) throw new IOException("压缩包里的文件太大");
                            o.write(b, 0, k);
                        }
                    }
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
                    dst.toFile().setExecutable(true, true);
                    got.put(leaf, dst);
                }
            }
        }
        return got;
    }

    void fetch(String url, Path part, String want, String what, Progress p) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(req(url).GET().timeout(Duration.ofMinutes(30)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() / 100 != 2) { r.body().close(); throw new IOException(what + " 下载失败（HTTP " + r.statusCode() + "）"); }
        long total = r.headers().firstValueAsLong("content-length").orElse(0);
        MessageDigest md = sha();
        long done = 0, tick = 0;
        try (InputStream in = r.body(); OutputStream o = Files.newOutputStream(part)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) {
                done += n;
                if (done > MAX_TOOL) throw new IOException(what + " 比预期大得多，已停止");
                o.write(b, 0, n);
                md.update(b, 0, n);
                if (p != null && done - tick >= 512 * 1024) { p.on(what, done, total); tick = done; }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            }
        }
        String got = hex(md.digest());
        if (!got.equalsIgnoreCase(want)) {
            Files.deleteIfExists(part);
            throw new IOException(what + " 的 SHA-256 和官方发布页给的不一样，已删掉、不安装");
        }
        if (p != null) p.on(what, done, total);
    }

    String text(String url, int max) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(req(url).GET().timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            if (r.statusCode() / 100 != 2) throw new IOException("取校验和失败（HTTP " + r.statusCode() + "）");
            byte[] b = in.readNBytes(max + 1);
            if (b.length > max) throw new IOException("校验和文件太大");
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    static HttpRequest.Builder req(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "OneSay-desktop");
    }

    public static String sha256(Path f) throws IOException {
        MessageDigest md = sha();
        try (InputStream in = Files.newInputStream(f)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return hex(md.digest());
    }

    static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    static String msg(Throwable t) { return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }

    /**
     * 只给测试 / 非 Windows 的端到端验证用：登记一个已经在别处按官方校验和核对过的文件（比如 Linux 上手动解出的 LGPL FFmpeg）。
     * 不是界面功能。
     */
    synchronized void register(String tool, String version, String asset, String officialSha, Map<String, Path> files) throws IOException {
        Map<String, Object> f = new TreeMap<>();
        for (Map.Entry<String, Path> e : files.entrySet()) f.put(e.getKey(), sha256(e.getValue()));
        Map<String, Object> m = manifest();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("version", version);
        e.put("asset", asset);
        e.put("sha256", officialSha);
        e.put("files", f);
        m.put(tool, e);
        saveManifest(m);
    }
}
