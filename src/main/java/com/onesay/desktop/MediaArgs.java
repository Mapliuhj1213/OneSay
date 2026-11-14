package com.onesay.desktop;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 链接下载 · yt-dlp 的参数：只按固定白名单拼，不接受用户自由参数（思路参考 omniget「只把链接交给下载引擎、参数由程序决定」，代码自写）。
 *
 * <ul>
 *   <li>链接只收 http / https，拒绝空格、控制字符和 ; | ` $ &lt; &gt; " ' \ { } ^ 这类会被 shell 解释的字符（我们本来就不经 shell，用 ProcessBuilder
 *       逐个传参，这一层是多加一道）；不能以 - 开头（防被当成选项），参数表里链接前面还有一个 {@code --}。
 *       查询参数里正常的 &amp; = % 放行（2026-11-11 用户同意）：链接在参数数组里永远是单独一个元素，&amp; 不会变成额外参数；
 *       yt-dlp 必须是直接启动的可执行文件（.bat / .cmd 会经 cmd.exe 解释 &amp;，{@link #base} 拒绝）。</li>
 *   <li>清晰度只有 best / 1080 / 720；仅音频只有 m4a / mp3。别的值一律回落默认或拒绝。</li>
 *   <li>yt-dlp 不读任何配置文件（--ignore-config）、不加载插件目录（--no-plugin-dirs），不带 --exec。</li>
 *   <li>记进日志的命令行里 Cookie 文件路径打码（{@link #masked}）。</li>
 * </ul>
 */
public final class MediaArgs {
    private MediaArgs() { }

    public static final String Q_BEST = "best", Q_1080 = "1080", Q_720 = "720";
    public static final String A_NONE = "", A_M4A = "m4a", A_MP3 = "mp3";
    /** 下载完成后 yt-dlp 打印的最终文件路径前缀（--print after_move:…）。 */
    public static final String FILE_TAG = "[onesay-file] ";
    public static final String COOKIE_MASK = "<cookies:已打码>";
    public static final int MAX_URL = 2000;
    /** 输出文件名。[fix-1114 实测 P1-2] 仅音频单独加「 仅音频」：yt-dlp -x 抽完音频会删掉同名的中间文件，和视频同名时会把你之前下好的视频当中间文件删掉。 */
    public static final String OUT_VIDEO = "%(title).80B [%(id)s].%(ext)s", OUT_AUDIO = "%(title).80B [%(id)s] 仅音频.%(ext)s";

    /** 清晰度：不认识的回落「最佳」。 */
    public static String quality(Object q) {
        String s = q == null ? "" : String.valueOf(q).trim().toLowerCase(Locale.ROOT);
        if (s.equals("1080") || s.equals("1080p")) return Q_1080;
        if (s.equals("720") || s.equals("720p")) return Q_720;
        return Q_BEST;
    }

    /** 仅音频：空 = 要视频；不认识的返回 null（调用方拒绝）。 */
    public static String audio(Object a) {
        String s = a == null ? "" : String.valueOf(a).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("false") || s.equals("none") || s.equals("video")) return A_NONE;
        if (s.equals("m4a") || s.equals("true") || s.equals("audio")) return A_M4A;
        if (s.equals("mp3")) return A_MP3;
        return null;
    }

    private static final Pattern BAD = Pattern.compile("[\\s;|`$<>\"'\\\\\\x00-\\x1f\\x7f{}^]");

    /** 链接检查：没问题返回 null，否则返回给人看的原因。 */
    public static String checkUrl(String raw) {
        if (raw == null || raw.isEmpty()) return "没有链接";
        if (raw.length() > MAX_URL) return "链接太长";
        if (raw.startsWith("-")) return "链接不能以 - 开头";
        Matcher m = BAD.matcher(raw);
        if (m.find()) {
            char c = raw.charAt(m.start());
            String what = Character.isWhitespace(c) ? "空格 / 换行" : c < 0x20 || c == 0x7f ? "控制字符" : "「" + c + "」";
            return "链接里有" + what + "，为防注入不接受";
        }
        if (invisible(raw)) return "链接里有看不见的字符或文字方向控制符，为防伪装不接受";
        String low = raw.toLowerCase(Locale.ROOT);
        if (!low.startsWith("http://") && !low.startsWith("https://")) return "只接受 http / https 链接";
        URI u;
        try {
            u = new URI(raw);
        } catch (Exception e) {
            return "链接格式不对";
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) return "链接里没有网站地址";
        if (u.getRawUserInfo() != null) return "链接里不能带账号密码";
        return null;
    }

    /**
     * [sync-1114 ← 手机 943e4c8 P2-5] 看不见 / 改文字方向的字符（零宽、软连字符、BOM、RLO / LRI…）和非 ASCII 空白：确认卡上的链接可能被它们伪装
     * （RLO 把 gnp.4pm 显示成 mp4.png）。和手机 PcMedia.checkUrl、本仓 LinkPolicy.checkUrl 一样拒收。
     */
    static boolean invisible(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int t = Character.getType(c);
            if (t == Character.FORMAT || t == Character.CONTROL || t == Character.SPACE_SEPARATOR || t == Character.LINE_SEPARATOR
                    || t == Character.PARAGRAPH_SEPARATOR || c == '\u115f' || c == '\u1160' || c == '\u3164' || c == '\uffa0') return true;
        }
        return false;
    }

    /**
     * [sync-1114 ← 开源 f46e99f P2-2] 中文分享里包住链接的引号 / 书名号（“” ‘’ 《》 『』 〈〉 「」）、全角冒号、不换行 / 全角空格、零宽字符和
     * 文字方向控制符（RLO、LRI…）都是链接的边界，不带进链接。开源版范围之外多切了 U+2066–206F（方向隔离符等），这些留在链接里 checkUrl 也会拒。
     */
    static final Pattern TIDY_URL = Pattern.compile(
            "(?i)https?://[^\\s\\u00a0\\u2000-\\u200f\\u2028-\\u202f\\u205f-\\u2064\\u2066-\\u206f\\u3000\\ufeff\"'<>`，。！？、；：）】」“”‘’《》『』〈〉「]+");

    /**
     * 分享文字 / 语音 / 输入框里拿到的一段文字 → 第一个 http(s) 链接，去掉 # 片段（只在浏览器里用，不发给网站）和明显的来源跟踪参数
     * （utm_*、spm、spm_id_from、fbclid、gclid、igshid），其余查询参数（如 YouTube 的 &amp;t=10、&amp;list=）原样保留，再过 {@link #checkUrl}。
     * 这是界面上的整理，整理后的链接会显示给你看；下载端照样按 {@link #checkUrl} 严格检查。没有返回 null。
     */
    public static String tidy(String text) {
        if (text == null) return null;
        Matcher m = TIDY_URL.matcher(text);
        if (!m.find()) return null;
        String u = m.group();
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        u = dropTracking(u);
        while (u.length() > 0 && "?&.,…".indexOf(u.charAt(u.length() - 1)) >= 0) u = u.substring(0, u.length() - 1); // [sync-1114 ← 开源] 加了「…」
        return u.isEmpty() ? null : u;
    }

    /** 明显的来源跟踪参数（整理时去掉；名字不分大小写，utm_ 开头的都算）。 */
    static boolean tracking(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("utm_") || n.equals("spm") || n.equals("spm_id_from") || n.equals("fbclid") || n.equals("gclid") || n.equals("igshid");
    }

    /** 去掉查询串里的跟踪参数，其余参数和顺序不变；空参数（&amp;&amp;）顺手去掉。 */
    static String dropTracking(String u) {
        int q = u.indexOf('?');
        if (q < 0) return u;
        StringBuilder b = new StringBuilder();
        for (String kv : u.substring(q + 1).split("&")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            if (tracking(eq < 0 ? kv : kv.substring(0, eq))) continue;
            if (b.length() > 0) b.append('&');
            b.append(kv);
        }
        return b.length() == 0 ? u.substring(0, q) : u.substring(0, q + 1) + b;
    }

    /** yt-dlp 的格式选择（白名单）。没有 FFmpeg 时只选不用合并的单文件。 */
    public static String format(String quality, String audio, boolean ffmpeg) {
        if (audio != null && !audio.isEmpty()) return "ba/b";
        String h = Q_1080.equals(quality) ? "[height<=1080]" : Q_720.equals(quality) ? "[height<=720]" : "";
        if (!ffmpeg) return h.isEmpty() ? "b" : "b" + h + "/b";
        return "bv*" + h + "+ba/b" + h + "/b";
    }

    /** 两种调用都有的固定前缀。 */
    static List<String> base(String ytdlp) {
        String low = ytdlp == null ? "" : ytdlp.toLowerCase(Locale.ROOT);
        // .bat / .cmd 在 Windows 上会经 cmd.exe 启动，链接里的 & 会被当成命令分隔：只认直接启动的可执行文件
        if (low.endsWith(".bat") || low.endsWith(".cmd")) throw new IllegalArgumentException("下载工具必须是可执行文件，不能是 .bat / .cmd");
        List<String> a = new ArrayList<>();
        a.add(ytdlp);
        a.add("--ignore-config");
        a.add("--no-plugin-dirs");
        a.add("--no-playlist");
        a.add("--no-exec");
        a.add("--socket-timeout");
        a.add("30");
        return a;
    }

    /** 预览：只取信息（-J，不下载）。 */
    public static List<String> preview(String ytdlp, String url, String quality, String audio, String ffmpegDir, String cookieFile) {
        requireUrl(url);
        List<String> a = base(ytdlp);
        a.add("-J");
        a.add("-f");
        a.add(format(quality, audio, ffmpegDir != null));
        if (ffmpegDir != null) { a.add("--ffmpeg-location"); a.add(ffmpegDir); }
        if (cookieFile != null) { a.add("--cookies"); a.add(cookieFile); }
        a.add("--");
        a.add(url);
        return a;
    }

    /** 下载：断点续传（--continue）、一行一个进度、完成后打印最终路径。 */
    public static List<String> download(String ytdlp, String url, String quality, String audio, String ffmpegDir, String outDir, String cookieFile) {
        requireUrl(url);
        if (audio == null) throw new IllegalArgumentException("仅音频只能是 m4a / mp3");
        if (!audio.isEmpty() && ffmpegDir == null) throw new IllegalArgumentException("仅音频要 FFmpeg");
        List<String> a = base(ytdlp);
        a.add("--newline");
        a.add("--progress");
        a.add("--continue");
        a.add("--windows-filenames");
        a.add("-f");
        a.add(format(quality, audio, ffmpegDir != null));
        if (!audio.isEmpty()) {
            a.add("-x");
            a.add("--audio-format");
            a.add(audio);
        } else if (ffmpegDir != null) {
            a.add("--merge-output-format");
            a.add("mp4/mkv");
        }
        if (ffmpegDir != null) { a.add("--ffmpeg-location"); a.add(ffmpegDir); }
        if (cookieFile != null) { a.add("--cookies"); a.add(cookieFile); }
        a.add("-P");
        a.add(outDir);
        a.add("-o");
        a.add(audio.isEmpty() ? OUT_VIDEO : OUT_AUDIO);
        a.add("--print");
        a.add("after_move:" + FILE_TAG + "%(filepath)s");
        a.add("--");
        a.add(url);
        return a;
    }

    static void requireUrl(String url) {
        String bad = checkUrl(url);
        if (bad != null) throw new IllegalArgumentException(bad);
    }

    /** 记日志用：Cookie 文件路径打码，其余照原样（参数里有空格的加引号）。 */
    public static String masked(List<String> argv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            String s = argv.get(i);
            if (i > 0 && "--cookies".equals(argv.get(i - 1))) s = COOKIE_MASK;
            if (b.length() > 0) b.append(' ');
            b.append(s.isEmpty() || s.contains(" ") ? "\"" + s + "\"" : s);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ 进度

    /** 一行进度：百分比（0–100）、总大小、速度、剩余时间（看不出来的为空串）。 */
    public static final class Progress {
        public final double percent;
        public final String total, speed, eta;

        Progress(double percent, String total, String speed, String eta) {
            this.percent = percent;
            this.total = total;
            this.speed = speed;
            this.eta = eta;
        }
    }

    private static final Pattern PROG = Pattern.compile(
            "^\\[download\\]\\s+(\\d{1,3}(?:\\.\\d+)?)%(?:\\s+of\\s+~?\\s*(\\S+))?(?:\\s+at\\s+(Unknown B/s|\\S+))?(?:\\s+ETA\\s+(\\S+))?");

    /** yt-dlp 的 "[download]  12.3% of ~ 10.00MiB at 1.23MiB/s ETA 00:05" → 进度；不是进度行返回 null。 */
    public static Progress progress(String line) {
        if (line == null) return null;
        Matcher m = PROG.matcher(line.trim());
        if (!m.find()) return null;
        double p;
        try {
            p = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (p < 0 || p > 100) return null;
        String speed = m.group(3) == null || m.group(3).startsWith("Unknown") ? "" : m.group(3);
        String eta = m.group(4) == null || m.group(4).startsWith("Unknown") ? "" : m.group(4);
        return new Progress(p, m.group(2) == null ? "" : m.group(2), speed, eta);
    }

    /** 这一行说的是不是被限流（HTTP 429 / Too Many Requests）。 */
    public static boolean rateLimited(String line) {
        if (line == null) return false;
        String l = line.toLowerCase(Locale.ROOT);
        return l.contains("http error 429") || l.contains("too many requests") || l.matches(".*\\b429\\b.*rate.*");
    }

    /** 第 n 次重试前等多久（毫秒）：10 秒、30 秒、90 秒。 */
    public static long backoffMs(int attempt, long baseMs) {
        long ms = baseMs;
        for (int i = 1; i < attempt; i++) ms *= 3;
        return ms;
    }

    /** 秒数 → 「3:05」/「1:02:03」。 */
    public static String duration(double sec) {
        if (sec <= 0 || Double.isNaN(sec)) return "";
        long s = Math.round(sec);
        long h = s / 3600, m = (s % 3600) / 60, r = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, r) : String.format(Locale.ROOT, "%d:%02d", m, r);
    }

    public static String mb(long bytes) {
        if (bytes <= 0) return "";
        return bytes >= 1L << 30 ? String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0) : String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }
}
