package com.onesay.desktop;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** 真实电脑能力。Windows 为主；其他系统能用的（截图、打开、剪贴板）也可用。 */
public class DesktopActions implements Actions {
    static final int MAX_EDGE = 1600;          // 截图长边上限
    static final long MAX_FILE = 5L * 1024 * 1024; // 取文件上限（中转单帧 8 MiB，base64 膨胀 4/3）

    protected final Config cfg;
    protected final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    public DesktopActions(Config cfg) { this.cfg = cfg; }

    @Override
    public List<String> capabilities() {
        List<String> c = new ArrayList<>(Arrays.asList("ping", "info", "screenshot", "open_url", "clipboard_get", "clipboard_set"));
        if (!cfg.appMap().isEmpty()) c.add("open_app");
        if (!cfg.filesDir.isEmpty()) { c.add("file_list"); c.add("file_get"); }
        if (!cfg.scriptsDir.isEmpty()) { c.add("shortcut_list"); c.add("shortcut_run"); }
        if (windows) c.addAll(Arrays.asList("lock", "volume", "media", "sleep", "shutdown", "shutdown_abort"));
        return c;
    }

    @Override
    public Result run(String cmd, Map<String, Object> a) throws Exception {
        switch (cmd) {
            case "ping": return Result.ok("在线");
            case "info": return info();
            case "screenshot": return screenshot();
            case "open_url": return openUrl(str(a, "url"));
            case "open_app": return openApp(str(a, "name"));
            case "clipboard_get": return clipboardGet();
            case "clipboard_set": return clipboardSet(str(a, "text"));
            case "file_list": return fileList();
            case "file_get": return fileGet(str(a, "name"));
            case "shortcut_list": return shortcutList();
            case "shortcut_run": return shortcutRun(str(a, "name"), str(a, "_sha256"));
            case "lock": return win("锁屏", "rundll32.exe", "user32.dll,LockWorkStation");
            case "volume": return volume(str(a, "action"), num(a, "steps", 5));
            case "media": return media(str(a, "action"));
            case "sleep": return confirmed(a, "睡眠") ? win("睡眠", "rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0") : needConfirm("睡眠");
            case "shutdown_abort": return win("已取消关机", "shutdown.exe", "/a");
            case "shutdown": return confirmed(a, "关机") ? win("60 秒后关机，可用 shutdown /a 取消", "shutdown.exe", "/s", "/t", "60") : needConfirm("关机");
            default: return Result.fail("不支持的指令：" + cmd);
        }
    }

    // ---------- 参数 ----------
    static String str(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    static int num(Map<String, Object> a, String k, int def) {
        Object v = a == null ? null : a.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    static boolean confirmed(Map<String, Object> a, String what) { return a != null && Boolean.TRUE.equals(a.get("confirm")); }

    static Result needConfirm(String what) { return Result.fail(what + "需要确认：请在手机上确认后再发（confirm=true）"); }

    // ---------- 能力 ----------
    Result info() throws Exception {
        Map<String, Object> d = new TreeMap<>();
        d.put("host", InetAddress.getLocalHost().getHostName());
        d.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        d.put("user", System.getProperty("user.name"));
        d.put("caps", capabilities());
        return Result.ok("电脑 " + d.get("host") + " 在线", d);
    }

    protected Result screenshot() throws Exception {
        if (GraphicsEnvironment.isHeadless()) return Result.fail("没有图形界面，无法截图");
        Rectangle all = new Rectangle();
        for (var gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) all = all.union(gd.getDefaultConfiguration().getBounds());
        BufferedImage img = new Robot().createScreenCapture(all);
        return Result.ok("截图 " + img.getWidth() + "×" + img.getHeight(), imageData(img));
    }

    static Map<String, Object> imageData(BufferedImage src) throws Exception {
        int w = src.getWidth(), h = src.getHeight();
        double s = Math.min(1.0, (double) MAX_EDGE / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * s)), th = Math.max(1, (int) Math.round(h * s));
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter wr = it.next();
        ImageWriteParam p = wr.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.7f);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            wr.setOutput(ios);
            wr.write(null, new IIOImage(out, null, null), p);
        } finally {
            wr.dispose();
        }
        Map<String, Object> d = new TreeMap<>();
        d.put("mime", "image/jpeg");
        d.put("width", (long) tw);
        d.put("height", (long) th);
        d.put("b64", Base64.getEncoder().encodeToString(bos.toByteArray()));
        return d;
    }

    Result openUrl(String url) throws Exception {
        URI u;
        try { u = URI.create(url.trim()); } catch (Exception e) { return Result.fail("网址无效"); }
        String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!sch.equals("http") && !sch.equals("https")) return Result.fail("只打开 http/https 网址");
        if (windows) {
            SafeExec.start(new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", u.toString()));
        } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(u);
        } else return Result.fail("这台电脑不支持打开网址");
        return Result.ok("已打开 " + u.getHost());
    }

    Result openApp(String name) throws Exception {
        String path = cfg.appMap().get(name.trim().toLowerCase(Locale.ROOT));
        if (path == null) return Result.fail("没配置这个程序：" + name + "（在设置的 apps 里加 名字=路径）");
        File f = new File(path);
        if (!f.exists()) return Result.fail("程序路径不存在：" + name);
        SafeExec.start(new ProcessBuilder(f.getAbsolutePath()).directory(f.getParentFile()));
        return Result.ok("已打开 " + name);
    }

    Result clipboardGet() throws Exception {
        if (GraphicsEnvironment.isHeadless()) return Result.fail("没有图形界面");
        var cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        if (!cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) return Result.ok("剪贴板里不是文字", map("text", ""));
        String t = (String) cb.getData(DataFlavor.stringFlavor);
        if (t.length() > 100_000) t = t.substring(0, 100_000);
        return Result.ok("已取电脑剪贴板（" + t.length() + " 字）", map("text", t));
    }

    Result clipboardSet(String text) {
        if (GraphicsEnvironment.isHeadless()) return Result.fail("没有图形界面");
        if (text.length() > 100_000) return Result.fail("内容太长");
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        return Result.ok("已放进电脑剪贴板（" + text.length() + " 字）");
    }

    /** 只允许文件夹内一层的文件名，拒绝路径分隔符与 ..。 */
    static Path safeChild(String dir, String name) {
        if (dir.isEmpty() || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..") || name.contains(":")) return null;
        Path base = Paths.get(dir).toAbsolutePath().normalize();
        Path p = base.resolve(name).normalize();
        return p.getParent() != null && p.getParent().equals(base) && Files.isRegularFile(p) ? p : null;
    }

    Result fileList() throws Exception {
        List<Object> names = new ArrayList<>();
        try (var s = Files.list(Paths.get(cfg.filesDir))) { s.filter(Files::isRegularFile).limit(200).forEach(p -> names.add(p.getFileName().toString())); }
        return Result.ok(names.size() + " 个文件", map("files", names));
    }

    Result fileGet(String name) throws Exception {
        Path p = safeChild(cfg.filesDir, name);
        if (p == null) return Result.fail("找不到这个文件：" + name);
        if (Files.size(p) > MAX_FILE) return Result.fail("文件超过 5 MB，暂不支持");
        Map<String, Object> d = new TreeMap<>();
        d.put("name", p.getFileName().toString());
        d.put("b64", Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
        return Result.ok("已取 " + name, d);
    }

    Result shortcutList() throws Exception {
        List<Object> names = new ArrayList<>();
        try (var s = Files.list(Paths.get(cfg.scriptsDir))) {
            s.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("(?i).+\\.(ps1|bat|cmd)$")).limit(200).forEach(names::add);
        }
        return Result.ok(names.size() + " 个快捷指令", map("shortcuts", names));
    }

    /** 快捷指令名 → 文件夹里的文件（名字可带或不带 .ps1 / .bat / .cmd）；没有返回 null。PcGuard 判定和这里执行用同一个查找。 */
    static Path shortcutPath(String dir, String name) {
        if (dir == null || name == null) return null;
        for (String ext : new String[]{"", ".ps1", ".bat", ".cmd"}) {
            Path p = safeChild(dir, name + ext);
            if (p != null && p.getFileName().toString().matches("(?i).+\\.(ps1|bat|cmd)$")) return p;
        }
        return null;
    }

    /** sha256 非空（手机触发、PcGuard 判过的）：执行前再核对内容，和判定时不一样就不跑。 */
    Result shortcutRun(String name, String sha256) throws Exception {
        Path p = shortcutPath(cfg.scriptsDir, name);
        if (p == null) return Result.fail("没有这个快捷指令：" + name);
        if (sha256 != null && !sha256.isEmpty() && !LinkPolicy.sha256Hex(Files.readAllBytes(p)).equalsIgnoreCase(sha256))
            return Result.fail("快捷指令「" + p.getFileName() + "」内容刚改过，没执行（重新触发会再问你）");
        String fn = p.toString();
        ProcessBuilder pb = fn.toLowerCase(Locale.ROOT).endsWith(".ps1")
                ? new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", fn)
                : new ProcessBuilder("cmd.exe", "/c", fn);
        pb.directory(p.getParent().toFile()).redirectErrorStream(true);
        Process proc = SafeExec.start(pb); // 永不提权：不走 runas；OneSay 是管理员时远程触发的拒绝
        proc.getOutputStream().close();
        byte[] out = proc.getInputStream().readNBytes(4000);
        boolean done = proc.waitFor(60, TimeUnit.SECONDS);
        if (!done) { proc.destroyForcibly(); return Result.ok("正在执行快捷指令（超过 60 秒，已停止等待）"); }
        return Result.ok("正在执行快捷指令", map("exit", (long) proc.exitValue(), "output", new String(out, java.nio.charset.Charset.defaultCharset()).trim()));
    }

    Result volume(String action, int steps) throws Exception {
        int key;
        switch (action) {
            case "up": key = 175; break;
            case "down": key = 174; break;
            case "mute": key = 173; steps = 1; break;
            default: return Result.fail("音量只支持 up / down / mute");
        }
        steps = Math.max(1, Math.min(steps, 50));
        return sendKeys(key, steps, "音量 " + action);
    }

    Result media(String action) throws Exception {
        int key;
        switch (action) {
            case "play_pause": key = 179; break;
            case "next": key = 176; break;
            case "prev": key = 177; break;
            default: return Result.fail("媒体只支持 play_pause / next / prev");
        }
        return sendKeys(key, 1, "媒体 " + action);
    }

    /** 用 PowerShell 发媒体虚拟键（不需要额外依赖）。 */
    Result sendKeys(int vk, int times, String label) throws Exception {
        if (!windows) return Result.fail("仅 Windows 支持");
        String ps = "$w=New-Object -ComObject WScript.Shell; 1.." + times + " | % { $w.SendKeys([char]" + vk + ") }";
        return win(label, "powershell.exe", "-NoProfile", "-Command", ps);
    }

    protected Result win(String label, String... cmd) throws Exception {
        if (!windows) return Result.fail("仅 Windows 支持");
        Process p = SafeExec.start(new ProcessBuilder(cmd).redirectErrorStream(true));
        p.getOutputStream().close();
        if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); return Result.fail(label + " 超时"); }
        return p.exitValue() == 0 ? Result.ok(label + " 完成") : Result.fail(label + " 失败（" + p.exitValue() + "）");
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
