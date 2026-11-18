package com.onesay.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * phone-mirror M1：手机投屏用的官方 scrcpy Windows 发行版（Apache-2.0，Genymobile / Romain Vimont），
 * <b>不随安装包附带</b>，用户在「手机投屏」页点「安装」时才从 github.com/Genymobile/scrcpy 官方发布页下载。
 *
 * <p>版本<b>锁定</b>（{@link #VERSION}），SHA-256 双重核对：代码里写死的官方值（2026-10-08 发布页 SHA256SUMS.txt 抄下）
 * + 下载时再取发布页的 SHA256SUMS.txt，两者和下载内容三方一致才解压。解压到 tools\scrcpy\，每个文件的 SHA-256 记进 scrcpy.json；
 * 每次启动 adb / scrcpy 前按记录核对（文件大小和修改时间没变时用缓存，不每 2 秒算一遍 26 MB）。
 *
 * <p>官方包里带 adb.exe / AdbWinApi.dll / AdbWinUsbApi.dll（Android SDK Platform-Tools）。
 * TODO(adb-redistribution)：platform-tools 的再分发条款和 Apache-2.0 不是一回事，<b>发布前要核实</b>；在核实前不要把 adb 打进安装包 / release 配置，
 * 只走现在这条「用户点安装 → 从 scrcpy 官方发布页下载」的路。
 *
 * <p>非 Windows（开发 / 测试）不自动安装；可用 -Donesay.scrcpy=/path/scrcpy 和 -Donesay.adb=/path/adb 指向本机自己装的（不校验，标「开发用」）。
 */
public final class MirrorTools {

    public static final String VERSION = "v5.0.1";
    public static final String RELEASES = "https://github.com/Genymobile/scrcpy/releases";
    public static final String LICENSE = "Apache-2.0";
    /** 官方 SHA256SUMS.txt（v5.0.1）里的值。 */
    static final Map<String, String> PINNED = Map.of(
            "scrcpy-win64-v5.0.1.zip", "b12a2c4ee8be317422451fc7dcf8ee20a71b5ea7ef9ad73ddd825a25316227a5",
            "scrcpy-winarm64-v5.0.1.zip", "16c3fc2068df64946f670e3504b4752073ebae47a416a45b46e79a5ab19c689b");
    static final long MAX_ZIP = 64L * 1024 * 1024;

    public static String asset(String arch) {
        String a = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
        return (a.contains("aarch64") || a.contains("arm64") ? "scrcpy-winarm64-" : "scrcpy-win64-") + VERSION + ".zip";
    }

    final Path dir;
    final boolean windows;
    final String asset;
    final String releases;
    /** 开发用覆盖（非 Windows）：本机自己装的 scrcpy / adb。 */
    final String devScrcpy, devAdb;
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public MirrorTools(Path toolsDir, boolean windows, String arch, String releases, String devScrcpy, String devAdb) {
        this.dir = toolsDir.resolve("scrcpy");
        this.windows = windows;
        this.asset = asset(arch);
        this.releases = releases.replaceAll("/+$", "");
        this.devScrcpy = devScrcpy == null ? "" : devScrcpy;
        this.devAdb = devAdb == null ? "" : devAdb;
    }

    public static MirrorTools forThisPc() {
        MediaTools mt = MediaTools.forThisPc(); // 同一个工具目录（%LOCALAPPDATA%\OneSay\tools，规则见 UserDirs）
        String os = System.getProperty("os.name", "");
        return new MirrorTools(mt.dir(), os.toLowerCase(Locale.ROOT).contains("win"), System.getProperty("os.arch", ""), RELEASES,
                System.getProperty("onesay.scrcpy", ""), System.getProperty("onesay.adb", ""));
    }

    public Path dir() { return dir; }

    boolean dev() { return !windows && !devScrcpy.isEmpty() && !devAdb.isEmpty(); }

    public Path scrcpy() { return dev() ? Paths.get(devScrcpy) : dir.resolve(windows ? "scrcpy.exe" : "scrcpy"); }

    public Path adb() { return dev() ? Paths.get(devAdb) : dir.resolve(windows ? "adb.exe" : "adb"); }

    Path manifestFile() { return dir.resolve("scrcpy.json"); }

    @SuppressWarnings("unchecked")
    synchronized Map<String, Object> manifest() {
        try {
            if (!Files.isRegularFile(manifestFile())) return null;
            return Json.obj(Files.readString(manifestFile(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean installed() { return dev() || manifest() != null; }

    private volatile String okStamp = "";

    /** 运行前核对：null = 可以用；否则原因（拒绝运行）。 */
    @SuppressWarnings("unchecked")
    public String verify() {
        if (dev()) return Files.isRegularFile(scrcpy()) && Files.isRegularFile(adb()) ? null : "开发用 scrcpy / adb 路径不对";
        Map<String, Object> m = manifest();
        if (m == null || !(m.get("files") instanceof Map)) return "还没安装投屏组件";
        Map<String, Object> files = (Map<String, Object>) m.get("files");
        if (!files.containsKey(scrcpy().getFileName().toString()) || !files.containsKey(adb().getFileName().toString())) return "投屏组件记录不全，请重新安装";
        StringBuilder stamp = new StringBuilder();
        try {
            for (String f : files.keySet()) {
                Path p = dir.resolve(f).normalize();
                if (!p.startsWith(dir.normalize()) || !Files.isRegularFile(p)) return "投屏组件的文件不在了：" + f + "（请重新安装）";
                stamp.append(f).append(Files.size(p)).append(Files.getLastModifiedTime(p).toMillis()).append(';');
            }
        } catch (IOException e) {
            return "读不了投屏组件";
        }
        if (stamp.toString().equals(okStamp)) return null;
        try {
            for (Map.Entry<String, Object> f : files.entrySet()) {
                if (!MediaTools.sha256(dir.resolve(f.getKey())).equalsIgnoreCase(String.valueOf(f.getValue())))
                    return "投屏组件 " + f.getKey() + " 的 SHA-256 和安装时记录的不一样（文件可能被改过），拒绝运行，请重新安装";
            }
        } catch (IOException e) {
            return "读不了投屏组件";
        }
        okStamp = stamp.toString();
        return null;
    }

    public Map<String, Object> view() {
        Map<String, Object> v = new TreeMap<>();
        Map<String, Object> m = manifest();
        v.put("auto", windows);
        v.put("dev", dev());
        v.put("installed", installed());
        v.put("version", dev() ? "开发用（本机自装）" : m == null ? "" : String.valueOf(m.getOrDefault("version", "")));
        v.put("pinned", VERSION);
        v.put("license", "scrcpy " + LICENSE + "（Genymobile / Romain Vimont）");
        String why = installed() ? verify() : null;
        v.put("ok", installed() && why == null);
        v.put("why", why == null ? "" : why);
        v.put("dir", dir.toString());
        return v;
    }

    /** 从发布页的 SHA256SUMS.txt 里找本机要的包。 */
    static String sumFor(String sums, String asset) {
        if (sums == null) return null;
        for (String line : sums.split("\\r?\\n")) {
            Matcher m = Pattern.compile("^([0-9a-fA-F]{64})\\s+\\*?(\\S+)$").matcher(line.trim());
            if (m.find() && m.group(2).equals(asset)) return m.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** 安装 / 重装：只在 Windows 上；三方 SHA-256 一致才解压。 */
    public synchronized String install(MediaTools.Progress p) throws IOException, InterruptedException {
        if (!windows) throw new IOException("只有 Windows 上能自动安装投屏组件（其他系统请自己装 scrcpy 和 adb）");
        String pinned = PINNED.get(asset);
        if (pinned == null) throw new IOException("没有这台电脑能用的投屏组件");
        String base = releases + "/download/" + VERSION + "/";
        String sums = text(base + "SHA256SUMS.txt", 64 * 1024);
        String official = sumFor(sums, asset);
        if (official == null) throw new IOException("发布页的校验和文件里没有 " + asset);
        if (!official.equalsIgnoreCase(pinned)) throw new IOException("发布页的 SHA-256 和 OneSay 记录的官方值不一样，不安装");
        Files.createDirectories(dir);
        Path part = dir.resolve(asset + ".part");
        try {
            fetch(base + asset, part, pinned, p);
            Path stage = dir.resolve("stage");
            deleteTree(stage);
            Files.createDirectories(stage);
            Map<String, Path> got = unzip(part, stage);
            if (!got.containsKey("scrcpy.exe") || !got.containsKey("adb.exe") || !got.containsKey("scrcpy-server"))
                throw new IOException("压缩包里缺 scrcpy.exe / adb.exe / scrcpy-server");
            Map<String, Object> files = new TreeMap<>();
            for (Map.Entry<String, Path> e : got.entrySet()) {
                Path dst = dir.resolve(e.getKey());
                Files.move(e.getValue(), dst, StandardCopyOption.REPLACE_EXISTING);
                files.put(e.getKey(), MediaTools.sha256(dst));
            }
            deleteTree(stage);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", VERSION);
            m.put("asset", asset);
            m.put("sha256", pinned);
            m.put("license", LICENSE);
            m.put("source", releases);
            m.put("installed_at", System.currentTimeMillis());
            m.put("files", files);
            Path tmp = dir.resolve("scrcpy.json.tmp");
            Files.writeString(tmp, Json.stringify(m), StandardCharsets.UTF_8);
            Files.move(tmp, manifestFile(), StandardCopyOption.REPLACE_EXISTING);
            okStamp = "";
            return "已安装 scrcpy " + VERSION + "（已核对官方 SHA-256）";
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** 只解顶层目录下的文件（scrcpy-win64-v5.0.1/xxx）；取最后一段文件名，拒绝 .. 和更深的目录。 */
    static Map<String, Path> unzip(Path zip, Path out) throws IOException {
        Map<String, Path> got = new TreeMap<>();
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName().replace('\\', '/');
                String[] parts = n.split("/");
                if (parts.length > 2 || n.contains("..")) continue;
                String leaf = parts[parts.length - 1];
                if (!leaf.matches("[A-Za-z0-9._-]{1,64}") || leaf.equals("open_a_terminal_here.bat") || leaf.endsWith(".vbs")) continue;
                Path dst = out.resolve(leaf);
                try (OutputStream o = Files.newOutputStream(dst)) {
                    byte[] b = new byte[1 << 16];
                    int k;
                    while ((k = z.read(b)) > 0) {
                        total += k;
                        if (total > 4 * MAX_ZIP) throw new IOException("压缩包内容太大");
                        o.write(b, 0, k);
                    }
                }
                got.put(leaf, dst);
            }
        }
        return got;
    }

    void fetch(String url, Path part, String want, MediaTools.Progress p) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(MediaTools.req(url).GET().timeout(Duration.ofMinutes(20)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() / 100 != 2) { r.body().close(); throw new IOException("scrcpy 下载失败（HTTP " + r.statusCode() + "）"); }
        long total = r.headers().firstValueAsLong("content-length").orElse(0);
        MessageDigest md = MediaTools.sha();
        long done = 0, tick = 0;
        try (InputStream in = r.body(); OutputStream o = Files.newOutputStream(part)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) {
                done += n;
                if (done > MAX_ZIP) throw new IOException("scrcpy 包比预期大得多，已停止");
                o.write(b, 0, n);
                md.update(b, 0, n);
                if (p != null && done - tick >= 512 * 1024) { p.on("scrcpy", done, total); tick = done; }
            }
        }
        if (!MediaTools.hex(md.digest()).equalsIgnoreCase(want)) {
            Files.deleteIfExists(part);
            throw new IOException("scrcpy 包的 SHA-256 和官方值不一样，已删掉、不安装");
        }
    }

    String text(String url, int max) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(MediaTools.req(url).GET().timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            if (r.statusCode() / 100 != 2) throw new IOException("取校验和失败（HTTP " + r.statusCode() + "）");
            byte[] b = in.readNBytes(max + 1);
            if (b.length > max) throw new IOException("校验和文件太大");
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            for (Path x : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(x);
        }
    }
}
