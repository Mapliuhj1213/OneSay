package com.onesay.desktop;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [devtools-1118] 本机对话「看屏幕 / 点安卓界面」用的几样东西：
 * <ul>
 *   <li>截屏：这台电脑（AWT Robot）、安卓设备（adb exec-out screencap -p；MuMu 模拟器 127.0.0.1:16384、用无线调试连着的手机都行）；</li>
 *   <li>看图：配了能看图的模型（{@link Vision}）就把图交给它；没配就用 Windows 自带的 Windows.Media.Ocr 读出文字，并标明「OCR」；</li>
 *   <li>安卓界面：uiautomator dump（按 UTF-8 读，不经 PowerShell 的 GBK 解码）→ 解析每个节点的 text / content-desc / bounds / checkable / checked →
 *   按文字点（点 bounds 中心）、按文字勾选（先找同一行 / 同一容器里的复选框，已勾上就不动）。</li>
 * </ul>
 * adb 直接由 Java 起进程（参数逐个传，不拼 shell 串），输出按字节读、按 UTF-8 解码，每次有超时，不会挂住任务。
 */
public final class DeviceTools {
    private DeviceTools() {}

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    /** MuMu 模拟器 12 的默认 adb 端口。 */
    public static final String MUMU_SERIAL = "127.0.0.1:16384";

    // ------------------------------------------------------------------ adb

    /** 找 adb：设置里的路径 → PATH → MuMu / Android SDK 的常见位置。找不到返回 null。 */
    public static String findAdb(String configured, Map<String, String> env) {
        if (configured != null && !configured.isBlank() && new File(configured.trim()).isFile()) return configured.trim();
        String exe = WINDOWS ? "adb.exe" : "adb";
        String path = env == null ? null : env.getOrDefault("PATH", env.get("Path"));
        if (path != null) for (String d : path.split(File.pathSeparator)) {
            if (d.isBlank()) continue;
            File f = new File(d.trim(), exe);
            if (f.isFile()) return f.getPath();
        }
        for (String c : adbCandidates(env)) if (new File(c).isFile()) return c;
        return null;
    }

    static List<String> adbCandidates(Map<String, String> env) {
        List<String> out = new ArrayList<>();
        List<String> roots = new ArrayList<>();
        for (String k : new String[] {"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) {
            String v = env == null ? null : env.get(k);
            if (v != null && !v.isBlank() && !roots.contains(v)) roots.add(v);
        }
        for (String d : new String[] {"C:\\Program Files", "D:\\Program Files", "E:\\Program Files"}) if (!roots.contains(d)) roots.add(d);
        for (String r : roots) {
            for (String sub : new String[] {"MuMu\\emulator", "Netease", "MuMu"}) {
                File base = new File(r, sub);
                File[] kids = base.listFiles();
                if (kids == null) continue;
                for (File k : kids) {
                    out.add(new File(new File(k, "shell"), "adb.exe").getPath());
                    out.add(new File(new File(k, "nx_main"), "adb.exe").getPath());
                }
            }
        }
        String la = env == null ? null : env.get("LOCALAPPDATA");
        if (la != null) out.add(la + "\\Android\\Sdk\\platform-tools\\adb.exe");
        return out;
    }

    /** 一次进程调用的结果：退出码、原始输出字节（stdout+stderr）。 */
    public static final class Run {
        public final int exit;
        public final byte[] out;
        public final boolean timedOut;

        Run(int exit, byte[] out, boolean timedOut) {
            this.exit = exit;
            this.out = out;
            this.timedOut = timedOut;
        }

        public String text() { return new String(out, StandardCharsets.UTF_8); }
    }

    /** 起进程、按字节读完输出、超时就杀掉（不会挂住）。mergeErr=false 时 stderr 丢弃（screencap 的 PNG 不能混进错误文字）。 */
    public static Run exec(List<String> cmd, long timeoutMs, boolean mergeErr) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (mergeErr) pb.redirectErrorStream(true);
        else pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = SafeExec.start(pb);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Thread rd = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) {
                    synchronized (bo) {
                        if (bo.size() < 64 * 1024 * 1024) bo.write(b, 0, n);
                    }
                }
            } catch (Exception ignored) { }
        }, "onesay-devtools-read");
        rd.setDaemon(true);
        rd.start();
        boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!done) {
            p.destroyForcibly();
            rd.join(1000);
            synchronized (bo) { return new Run(-1, bo.toByteArray(), true); }
        }
        rd.join(5000);
        synchronized (bo) { return new Run(p.exitValue(), bo.toByteArray(), false); }
    }

    static List<String> adbCmd(String adb, String serial, String... args) {
        List<String> c = new ArrayList<>();
        c.add(adb);
        if (serial != null && !serial.isBlank()) { c.add("-s"); c.add(serial.trim()); }
        for (String a : args) c.add(a);
        return c;
    }

    /** adb devices 的输出 → 序列号 → 状态（device / offline / unauthorized）。 */
    public static Map<String, String> parseDevices(String out) {
        Map<String, String> m = new TreeMap<>();
        if (out == null) return m;
        for (String line : out.split("\\R")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("List of devices") || l.startsWith("*")) continue;
            String[] p = l.split("\\s+");
            if (p.length >= 2 && p[0].matches("[A-Za-z0-9._:\\-]+")) m.put(p[0], p[1]);
        }
        return m;
    }

    public static Map<String, String> devices(String adb) throws Exception {
        Run r = exec(adbCmd(adb, null, "devices"), 15_000, true);
        return parseDevices(r.text());
    }

    /** 设备没在列表里时：是本机模拟器那种 ip:port 就先 adb connect 一次。返回最终状态（null = 没有这台）。 */
    public static String ensureDevice(String adb, String serial) throws Exception {
        Map<String, String> d = devices(adb);
        if (d.containsKey(serial)) return d.get(serial);
        if (serial.matches("(127\\.0\\.0\\.1|localhost|\\d{1,3}(\\.\\d{1,3}){3}):\\d{2,5}")) {
            exec(adbCmd(adb, null, "connect", serial), 15_000, true);
            d = devices(adb);
            return d.get(serial);
        }
        return null;
    }

    /** 没给序列号时挑一台：只有一台在线就用它；MuMu 在线优先。 */
    public static String pickSerial(Map<String, String> devs, String wanted) {
        if (wanted != null && !wanted.isBlank() && !wanted.trim().equalsIgnoreCase("phone") && !wanted.trim().equalsIgnoreCase("android")) return wanted.trim();
        List<String> on = new ArrayList<>();
        for (Map.Entry<String, String> e : devs.entrySet()) if ("device".equals(e.getValue())) on.add(e.getKey());
        if (on.size() == 1) return on.get(0);
        if (on.contains(MUMU_SERIAL)) return MUMU_SERIAL;
        return null;
    }

    public static byte[] screencap(String adb, String serial) throws Exception {
        Run r = exec(adbCmd(adb, serial, "exec-out", "screencap", "-p"), 30_000, false);
        if (r.timedOut) throw new Exception("adb 截屏超时");
        byte[] b = r.out;
        if (b.length < 8 || (b[0] & 0xff) != 0x89 || b[1] != 'P') throw new Exception("adb 截屏没拿到图片（设备没连上或没授权调试）");
        return b;
    }

    /** uiautomator dump → 读回 XML（字节按 UTF-8 解码，中文不会变成「璁剧疆」）。 */
    public static String dumpUi(String adb, String serial) throws Exception {
        String remote = "/sdcard/onesay_ui.xml";
        Run d = exec(adbCmd(adb, serial, "shell", "uiautomator", "dump", remote), 30_000, true);
        if (d.timedOut) throw new Exception("uiautomator dump 超时");
        Run c = exec(adbCmd(adb, serial, "exec-out", "cat", remote), 15_000, false);
        String xml = c.text();
        if (!xml.contains("<hierarchy")) {
            String why = d.text().trim();
            throw new Exception("读不到界面结构" + (why.isEmpty() ? "" : "：" + LinkPolicy.oneLine(why)));
        }
        return xml;
    }

    public static Run tap(String adb, String serial, int x, int y) throws Exception {
        return exec(adbCmd(adb, serial, "shell", "input", "tap", String.valueOf(x), String.valueOf(y)), 15_000, true);
    }

    // ------------------------------------------------------------------ 界面结构

    /** 一个界面节点。 */
    public static final class Node {
        public final int index, parent, depth;
        public final String text, desc, id, cls;
        public final int x1, y1, x2, y2;
        public final boolean clickable, checkable, checked, enabled;

        Node(int index, int parent, int depth, Map<String, String> a) {
            this.index = index;
            this.parent = parent;
            this.depth = depth;
            this.text = a.getOrDefault("text", "");
            this.desc = a.getOrDefault("content-desc", "");
            this.id = a.getOrDefault("resource-id", "");
            this.cls = a.getOrDefault("class", "");
            int[] b = bounds(a.getOrDefault("bounds", ""));
            x1 = b[0]; y1 = b[1]; x2 = b[2]; y2 = b[3];
            clickable = "true".equals(a.get("clickable"));
            checkable = "true".equals(a.get("checkable"));
            checked = "true".equals(a.get("checked"));
            enabled = !"false".equals(a.get("enabled"));
        }

        public int cx() { return (x1 + x2) / 2; }
        public int cy() { return (y1 + y2) / 2; }
        public boolean hasBox() { return x2 > x1 && y2 > y1; }
        public String label() { return !text.isEmpty() ? text : desc; }

        @Override public String toString() {
            StringBuilder b = new StringBuilder();
            b.append('「').append(label()).append('」');
            String shortCls = cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls;
            if (!shortCls.isEmpty()) b.append(' ').append(shortCls);
            if (checkable) b.append(checked ? " 已勾选" : " 未勾选");
            if (clickable) b.append(" 可点");
            if (!enabled) b.append(" 不可用");
            b.append(" [").append(x1).append(',').append(y1).append("][").append(x2).append(',').append(y2).append(']');
            return b.toString();
        }
    }

    static int[] bounds(String s) {
        Matcher m = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matcher(s == null ? "" : s);
        if (!m.find()) return new int[] {0, 0, 0, 0};
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
    }

    static final Pattern TAG = Pattern.compile("<(/?)node\\b([^>]*?)(/?)>");
    static final Pattern ATTR = Pattern.compile("([A-Za-z_:\\-]+)=\"([^\"]*)\"");

    /** 解析 uiautomator 的 XML（带父子关系）。坏的 XML 尽量解析，认不出的跳过。 */
    public static List<Node> parseUi(String xml) {
        List<Node> out = new ArrayList<>();
        if (xml == null) return out;
        List<Integer> stack = new ArrayList<>();
        Matcher m = TAG.matcher(xml);
        while (m.find()) {
            if (!m.group(1).isEmpty()) { if (!stack.isEmpty()) stack.remove(stack.size() - 1); continue; }
            Map<String, String> a = new TreeMap<>();
            Matcher am = ATTR.matcher(m.group(2));
            while (am.find()) a.put(am.group(1), unescape(am.group(2)));
            int parent = stack.isEmpty() ? -1 : stack.get(stack.size() - 1);
            Node n = new Node(out.size(), parent, stack.size(), a);
            out.add(n);
            boolean selfClose = !m.group(3).isEmpty() || m.group(2).trim().endsWith("/");
            if (!selfClose) stack.add(n.index);
        }
        return out;
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#10;", "\n").replace("&#13;", "").replace("&amp;", "&");
    }

    static String norm(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\u00a0\\u200b]+", "").toLowerCase(Locale.ROOT);
    }

    /** 按文字找节点：先完全相同，再包含；同等时可点的、在屏幕上有框的、可用的优先。找不到返回 null。 */
    public static Node findByText(List<Node> nodes, String want) {
        String w = norm(want);
        if (w.isEmpty()) return null;
        Node best = null;
        int bestScore = -1;
        for (Node n : nodes) {
            if (!n.hasBox()) continue;
            String t = norm(n.text), d = norm(n.desc);
            int s;
            if (t.equals(w) || d.equals(w)) s = 100;
            else if ((!t.isEmpty() && t.contains(w)) || (!d.isEmpty() && d.contains(w))) s = 50 - Math.min(40, Math.max(t.length(), d.length()) - w.length());
            else continue;
            if (n.clickable) s += 5;
            if (n.enabled) s += 3;
            if (s > bestScore) { bestScore = s; best = n; }
        }
        return best;
    }

    /** 文字对应的可点区域：节点自己可点就是它；否则往上找可点的祖先（按钮里的文字）；都没有就用节点自己。 */
    public static Node tapTarget(List<Node> nodes, Node label) {
        for (Node n = label; n != null; n = n.parent >= 0 ? nodes.get(n.parent) : null) {
            if (n.clickable && n.hasBox() && area(n) <= area(label) * 40L + 200_000L) return n;
        }
        return label;
    }

    static long area(Node n) { return (long) Math.max(0, n.x2 - n.x1) * Math.max(0, n.y2 - n.y1); }

    /**
     * 文字对应的复选框：自己可勾选 → 自己；祖先可勾选（整行是 CheckBox）→ 祖先；同一父节点下可勾选的兄弟 → 它；
     * 再不行，找和文字在同一行（竖直方向重叠）、水平距离最近的可勾选节点。找不到返回 null。
     */
    public static Node checkboxFor(List<Node> nodes, Node label) {
        if (label == null) return null;
        if (label.checkable) return label;
        for (int p = label.parent; p >= 0; p = nodes.get(p).parent) {
            Node a = nodes.get(p);
            if (a.checkable) return a;
            if (label.depth - a.depth > 3) break;
        }
        for (int up = label.parent, hops = 0; up >= 0 && hops < 2; up = nodes.get(up).parent, hops++) {
            Node sib = null;
            for (Node n : nodes) if (n.checkable && n.hasBox() && isUnder(nodes, n, up)) { sib = sib == null || dist(n, label) < dist(sib, label) ? n : sib; }
            if (sib != null) return sib;
        }
        Node best = null;
        for (Node n : nodes) {
            if (!n.checkable || !n.hasBox()) continue;
            boolean sameRow = n.y1 < label.y2 && n.y2 > label.y1;
            if (!sameRow) continue;
            if (best == null || dist(n, label) < dist(best, label)) best = n;
        }
        return best;
    }

    static boolean isUnder(List<Node> nodes, Node n, int ancestor) {
        for (int p = n.parent; p >= 0; p = nodes.get(p).parent) if (p == ancestor) return true;
        return false;
    }

    static long dist(Node a, Node b) {
        long dx = a.cx() - b.cx(), dy = a.cy() - b.cy();
        return dx * dx + dy * dy;
    }

    /** 给模型看的界面摘要：有文字 / 描述、或可点 / 可勾选的节点，每个一行，最多 maxLines 行。 */
    public static String summarize(List<Node> nodes, int maxLines) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Node x : nodes) {
            if (!x.hasBox()) continue;
            if (x.label().isBlank() && !x.checkable) continue;
            if (n++ >= maxLines) { b.append("…（还有更多节点没列出）\n"); break; }
            b.append(x).append('\n');
        }
        return n == 0 ? "（界面上没有带文字的节点）" : b.toString();
    }

    /** 不替用户点的按钮：付款 / 转账 / 删除 / 卸载 / 恢复出厂这类，最后一下要用户自己点。 */
    static final Pattern NO_TAP = Pattern.compile("(确认)?(支付|付款|转账|提现|购买|下单|充值)|删除|卸载|恢复出厂|格式化|清除数据|抹掉");

    public static boolean userMustTap(String label) {
        return label != null && NO_TAP.matcher(label).find();
    }

    // ------------------------------------------------------------------ 截屏 / 存图

    /** 截这台电脑的全部屏幕（多屏拼起来）。没有图形界面抛错。 */
    public static BufferedImage capturePc() throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new Exception("没有图形界面，没法截电脑屏幕");
        Rectangle all = new Rectangle();
        for (var gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) all = all.union(gd.getDefaultConfiguration().getBounds());
        return new Robot().createScreenCapture(all);
    }

    public static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }

    /** 缩到长边 maxEdge 以内的 JPEG（给看图模型）。 */
    public static byte[] jpegScaled(byte[] imageBytes, int maxEdge) throws Exception {
        BufferedImage src = javax.imageio.ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (src == null) throw new Exception("图片读不出来");
        int w = src.getWidth(), h = src.getHeight();
        double s = Math.min(1.0, (double) maxEdge / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * s)), th = Math.max(1, (int) Math.round(h * s));
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(out, "jpg", bo);
        return bo.toByteArray();
    }

    /** 存到工作区 shots/ 下，返回完整路径。 */
    public static Path saveShot(Path workspace, String who, byte[] png) throws Exception {
        Path dir = workspace.resolve("shots");
        Files.createDirectories(dir);
        String safe = who.replaceAll("[^A-Za-z0-9._-]", "_");
        Path f = dir.resolve(safe + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".png");
        Files.write(f, png);
        return f;
    }

    // ------------------------------------------------------------------ OCR（Windows.Media.Ocr）

    /** 用 Windows 自带的 OCR 读图里的文字（Windows 10+；按用户的语言包，中文系统认中文）。 */
    static String ocrScript(String path) {
        String p = path.replace("'", "''");
        return "$ErrorActionPreference='Stop';[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                + "Add-Type -AssemblyName System.Runtime.WindowsRuntime;"
                + "$null=[Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime];"
                + "$null=[Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime];"
                + "$null=[Windows.Graphics.Imaging.BitmapDecoder,Windows.Graphics,ContentType=WindowsRuntime];"
                + "$at=([System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0];"
                + "function W($o,$t){$k=$at.MakeGenericMethod($t).Invoke($null,@($o));$null=$k.Wait(-1);$k.Result};"
                + "$f=W ([Windows.Storage.StorageFile]::GetFileFromPathAsync('" + p + "')) ([Windows.Storage.StorageFile]);"
                + "$s=W ($f.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream]);"
                + "$d=W ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($s)) ([Windows.Graphics.Imaging.BitmapDecoder]);"
                + "$b=W ($d.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap]);"
                + "$e=[Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages();"
                + "if($e -eq $null){throw 'no-ocr-language'};"
                + "$r=W ($e.RecognizeAsync($b)) ([Windows.Media.Ocr.OcrResult]);"
                + "$r.Lines|%{$_.Text}";
    }

    public static String ocr(Path image) throws Exception {
        if (!WINDOWS) throw new Exception("只有 Windows 上能用系统 OCR");
        Run r = exec(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", PcGuard.encodePs(ocrScript(image.toAbsolutePath().toString()))), 60_000, true);
        if (r.timedOut) throw new Exception("OCR 超时");
        String t = r.text();
        if (r.exit != 0) throw new Exception("系统 OCR 不可用：" + clip(LinkPolicy.oneLine(t), 160));
        return tidyOcr(t);
    }

    /** Windows OCR 认中文时字和字之间会插空格：去掉汉字之间的空格。 */
    static String tidyOcr(String s) {
        if (s == null) return "";
        String x = s.replace("\r", "");
        String prev;
        do {
            prev = x;
            x = x.replaceAll("([\\u3000-\\u9fff\\uff00-\\uffef])[ \\t]+([\\u3000-\\u9fff\\uff00-\\uffef])", "$1$2");
        } while (!x.equals(prev));
        return x.trim();
    }

    static String clip(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…"; }

    // ------------------------------------------------------------------ 看图模型

    /**
     * 能看图的模型（OpenAI 兼容 chat/completions，消息里带 image_url）。来源按顺序：
     * 设置里单独填的 vision_key（+ vision_base / vision_model）；大模型选的是百炼时用同一个 Key；填了百炼语音识别 Key 时也用它
     * （百炼的 compatible-mode 接口，默认 qwen-vl-max）。都没有返回 null（调用方改用 OCR）。
     */
    public static final class Vision {
        public static final String DASHSCOPE = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        public static final String DASHSCOPE_INTL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1";
        public static final String DEFAULT_MODEL = "qwen-vl-max";
        public final String base, key, model, source;

        Vision(String base, String key, String model, String source) {
            this.base = base.replaceAll("/+$", "");
            this.key = key;
            this.model = model;
            this.source = source;
        }

        public static Vision from(Config c) {
            String model = c.visionModel == null || c.visionModel.isBlank() ? DEFAULT_MODEL : c.visionModel.trim();
            if (c.visionKey != null && !c.visionKey.isBlank()) {
                String b = c.visionBase == null || c.visionBase.isBlank() ? DASHSCOPE : c.visionBase.trim();
                return b.startsWith("http") ? new Vision(b, c.visionKey.trim(), model, "单独设置的看图模型") : null;
            }
            if ("bailian".equals(c.llmProvider) && c.llmKey != null && !c.llmKey.isBlank()) return new Vision(DASHSCOPE, c.llmKey.trim(), model, "百炼（大模型同一个 Key）");
            if ("custom".equals(c.llmProvider) && c.visionModel != null && !c.visionModel.isBlank() && c.llmBase != null && c.llmBase.startsWith("http") && !c.llmKey.isBlank())
                return new Vision(c.llmBase.trim(), c.llmKey.trim(), c.visionModel.trim(), "自定义接口");
            if (c.asrKey != null && !c.asrKey.isBlank()) {
                boolean intl = c.asrBase != null && c.asrBase.contains("intl");
                return new Vision(intl ? DASHSCOPE_INTL : DASHSCOPE, c.asrKey.trim(), model, "百炼（语音识别同一个 Key）");
            }
            return null;
        }

        /** 请求体（单独拿出来便于测试）。 */
        public Map<String, Object> body(byte[] jpeg, String question) {
            List<Object> parts = new ArrayList<>();
            Map<String, Object> img = new TreeMap<>();
            img.put("type", "image_url");
            img.put("image_url", Map.of("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)));
            parts.add(img);
            Map<String, Object> tx = new TreeMap<>();
            tx.put("type", "text");
            tx.put("text", question);
            parts.add(tx);
            Map<String, Object> msg = new TreeMap<>();
            msg.put("role", "user");
            msg.put("content", parts);
            Map<String, Object> b = new TreeMap<>();
            b.put("model", model);
            b.put("messages", List.of(msg));
            b.put("temperature", 0.1);
            return b;
        }

        public String look(byte[] image, String question) throws Exception {
            byte[] jpg = jpegScaled(image, 1600);
            String q = (question == null || question.isBlank() ? "" : "要回答的问题：" + question.trim() + "\n")
                    + "请如实描述这张截图：在哪个界面 / 程序，屏幕上所有能看清的文字（按位置从上到下），有没有弹窗、按钮、复选框及其状态。看不清的就说看不清，不要猜。";
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/chat/completions"))
                    .timeout(java.time.Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(Json.stringify(body(jpg, q)), StandardCharsets.UTF_8))
                    .build();
            java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 != 2) throw new Exception("看图模型返回 " + r.statusCode());
            Llm.Turn t = Llm.parseTurn(Json.obj(r.body()));
            if (t.content == null || t.content.isBlank()) throw new Exception("看图模型没有回答");
            return t.content.trim();
        }
    }
}
