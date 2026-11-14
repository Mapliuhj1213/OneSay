package com.onesay.assistant.v2.pclink;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [pclink] 链接下载 · 手机这一半（纯 Java，JVM 可测）：语音「下载这个视频 / 把这个链接下到电脑」或系统分享菜单把链接交给 OneSay →
 * 经现有电脑联动发 {@code media_get} 给电脑 → 轮询 {@code media_status} 看进度 → 完成后可选 {@code media_fetch} 传回手机。
 * 手机本地不跑 yt-dlp；电脑没连上就只提示「需要连上电脑」。思路参考 omniget（GPL-3.0，只读思路），代码自写。
 *
 * <p>传回手机复用现有「取文件」那条通道（一条签名回复里带 base64，单个 ≤ 5 MB）；大了不传，只告诉你文件在电脑上的路径——不新造传输协议。
 */
public final class PcMedia {

    public static final String GET = "media_get", STATUS = "media_status", FETCH = "media_fetch", CANCEL = "media_cancel";
    public static final String Q_BEST = "best", Q_1080 = "1080", Q_720 = "720";
    /** 传回手机的上限（和电脑端取文件同一个数）。 */
    public static final long FETCH_MAX = 5L * 1024 * 1024;

    private PcMedia() {}

    // ================================================================== 链接

    // 2026-11-14 对抗测试 P2-2：中文分享里包住链接的引号 / 书名号（“” ‘’ 《》 『』 〈〉 「」）和不换行 / 全角空格、零宽字符也是链接的边界
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\\u00a0\\u2000-\\u200f\\u2028-\\u202f\\u205f-\\u2064\\u3000\\ufeff\"'<>`，。！？、；：）】」“”‘’《》『』〈〉「]+");
    private static final Pattern BAD = Pattern.compile("[\\s;|`$<>\"'\\\\\\x00-\\x1f\\x7f{}^]");

    /**
     * 一段文字（分享内容、语音、剪贴板）里的第一个 http(s) 链接，去掉 # 片段（只在浏览器里用，不发给网站）和明显的来源跟踪参数
     * （utm_*、spm、spm_id_from、fbclid、gclid、igshid），其余查询参数（如 YouTube 的 &amp;t=10、&amp;list=）原样保留。没有返回 null。
     * 和电脑端 MediaArgs.tidy 同一套规则（2026-11-11 起不再截掉第一个 &amp; 之后的参数）。
     */
    public static String tidy(String text) {
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        if (!m.find()) return null;
        String u = m.group();
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        u = dropTracking(u);
        while (u.length() > 0 && "?&.,…".indexOf(u.charAt(u.length() - 1)) >= 0) u = u.substring(0, u.length() - 1);
        return u.length() == 0 ? null : u;
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
            if (kv.length() == 0) continue;
            int eq = kv.indexOf('=');
            if (tracking(eq < 0 ? kv : kv.substring(0, eq))) continue;
            if (b.length() > 0) b.append('&');
            b.append(kv);
        }
        return b.length() == 0 ? u.substring(0, q) : u.substring(0, q + 1) + b;
    }

    /** 和电脑端同一套检查（电脑端会再查一遍）：没问题返回 null，否则返回原因。 */
    public static String checkUrl(String raw) {
        if (raw == null || raw.length() == 0) return "没有链接";
        if (raw.length() > 2000) return "链接太长";
        if (raw.startsWith("-")) return "链接不能以 - 开头";
        if (BAD.matcher(raw).find()) return "链接里有空格或特殊字符，为防注入不接受";
        String low = raw.toLowerCase(Locale.ROOT);
        if (!low.startsWith("http://") && !low.startsWith("https://")) return "只接受 http / https 链接";
        try {
            java.net.URI u = new java.net.URI(raw);
            if (u.getHost() == null || u.getHost().length() == 0) return "链接里没有网站地址";
            if (u.getRawUserInfo() != null) return "链接里不能带账号密码";
        } catch (Exception e) {
            return "链接格式不对";
        }
        return null;
    }

    public static Map<String, Object> args(String url, String quality, String audio) {
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("url", url);
        a.put("quality", Q_1080.equals(quality) || Q_720.equals(quality) ? quality : Q_BEST);
        a.put("audio", "m4a".equals(audio) || "mp3".equals(audio) ? audio : "");
        return a;
    }

    // ================================================================== 语音

    /** 语音口令解析结果：url 可为 null（= 用剪贴板里的链接）；audio 空 = 要视频。 */
    public static final class Ask {
        public final String url, audio;

        Ask(String url, String audio) {
            this.url = url;
            this.audio = audio;
        }
    }

    private static final String HEAD = "^(?:请|帮我|麻烦你?|给我)?(?:用电脑|在电脑上|让电脑|叫电脑)?";
    private static final String THIS = "(?:这个|这条|这段|这首|这部|刚才的?那?个?|刚刚的?那?个?)";
    private static final String WHAT = "(?:视频|链接|音频|音乐|歌|片子)";
    private static final String AUDIO = "(?:的?(?:音频|声音|音乐)|(?:成|转成|只要)?(?:音频|mp3|m4a))?";
    private static final Pattern DL = Pattern.compile(HEAD + "(?:下载|下)(?:一下)?" + THIS + "?" + WHAT + "?" + AUDIO + "(?:(?:下载)?(?:到|去|在)(?:我的)?电脑(?:上|里)?)?(?:吧)?$");
    private static final Pattern PUT = Pattern.compile(HEAD + "把" + THIS + "?" + WHAT + AUDIO + "(?:下载|下|存|保存)(?:下来)?(?:到|去|在)(?:我的)?电脑(?:上|里)?(?:吧)?$");

    /** 「下载这个视频」「把这个链接下到电脑」「用电脑下载 https://…」「下载这个视频的音频」→ Ask；不是这类说法返回 null。 */
    public static Ask voice(String q) {
        if (q == null) return null;
        String url = tidy(q);
        String rest = URL.matcher(q).replaceAll("").replaceAll("[，。！？,.!?\\s]+", "").toLowerCase(Locale.ROOT);
        if (rest.length() == 0 || rest.length() > 24) return null;
        if (!DL.matcher(rest).matches() && !PUT.matcher(rest).matches()) return null;
        boolean says = rest.matches(".*" + WHAT + ".*") && (rest.contains("这") || rest.contains("刚") || rest.contains("电脑"));
        if (url == null && !says) return null; // 光说「下载」不接，免得抢别的功能
        String audio = rest.contains("mp3") ? "mp3" : rest.matches(".*(音频|声音|音乐|歌|m4a).*") ? "m4a" : "";
        return new Ask(url, audio);
    }

    // ================================================================== 进度

    static String s(Map<String, Object> d, String k) {
        Object v = d == null ? null : d.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    public static boolean finished(String state) {
        return "done".equals(state) || "failed".equals(state) || "canceled".equals(state);
    }

    /** 一行进度：「下载中 42.5% · 2.00MiB/s · 剩 00:01」。 */
    public static String statusLine(Map<String, Object> d) {
        String st = s(d, "state");
        StringBuilder b = new StringBuilder(s(d, "state_zh").length() > 0 ? s(d, "state_zh") : st);
        if ("downloading".equals(st)) {
            b.append(' ').append(s(d, "percent")).append('%');
            if (s(d, "speed").length() > 0) b.append(" · ").append(s(d, "speed"));
            if (s(d, "eta").length() > 0) b.append(" · 剩 ").append(s(d, "eta"));
        }
        if (s(d, "duration").length() > 0) b.append(" · ").append(s(d, "duration"));
        if (s(d, "size").length() > 0) b.append(" · 约 ").append(s(d, "size"));
        if (s(d, "error").length() > 0) b.append(" · ").append(s(d, "error"));
        return b.toString();
    }

    public static int percent(Map<String, Object> d) {
        Object p = d == null ? null : d.get("percent");
        if (p instanceof Number) return Math.max(0, Math.min(100, (int) Math.round(((Number) p).doubleValue())));
        return 0;
    }

    /** 下令（同步，后台线程）。 */
    public interface Caller {
        PcLanClient.Result call(String cmd, Map<String, Object> args, int timeoutMs);
    }

    /** 进度 / 结果给人看（通知）。 */
    public interface Sink {
        void progress(int id, String title, int percent, String line);

        void done(int id, String title, boolean ok, String text);
    }

    /** 把传回来的文件存到手机上，返回给人看的位置；存不下返回 null。 */
    public interface Saver {
        String save(String name, byte[] data, boolean audio);
    }

    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /**
     * 跟着一个电脑上的下载任务直到结束：每 pollMs 查一次 media_status；连续 5 次连不上电脑就不跟了（下载在电脑上照常进行）。
     * 完成后 fetchBack 时用 media_fetch 取回（≤ 5 MB），否则只报电脑上的路径。返回最后给人看的那句话。
     */
    public static String follow(Caller c, Sink sink, Saver saver, int id, boolean fetchBack, long maxMs, long pollMs, Sleeper sl) {
        Map<String, Object> q = new TreeMap<String, Object>();
        q.put("id", (long) id);
        long end = System.currentTimeMillis() + maxMs;
        int miss = 0;
        String title = "";
        while (true) {
            PcLanClient.Result r = c.call(STATUS, q, 8000);
            if (!r.reached()) {
                if (++miss >= 5) {
                    String t = "和电脑断开了，下载在电脑上照常进行，完成后在电脑的下载文件夹里";
                    sink.done(id, title, false, t);
                    return t;
                }
            } else if (!r.ok) {
                String t = "电脑上找不到这个下载任务了：" + r.text;
                sink.done(id, title, false, t);
                return t;
            } else {
                miss = 0;
                Map<String, Object> d = r.data;
                if (s(d, "title").length() > 0) title = s(d, "title");
                String st = s(d, "state");
                if ("done".equals(st)) {
                    String path = s(d, "path");
                    String t = "已下载到电脑：" + (path.length() > 0 ? path : "下载文件夹");
                    if (fetchBack) t = fetch(c, saver, id, d, t);
                    sink.done(id, title, true, t);
                    return t;
                }
                if ("failed".equals(st)) {
                    String t = "电脑下载失败：" + s(d, "error");
                    sink.done(id, title, false, t);
                    return t;
                }
                if ("canceled".equals(st)) {
                    sink.done(id, title, false, "已取消");
                    return "已取消";
                }
                sink.progress(id, title, percent(d), statusLine(d));
            }
            if (System.currentTimeMillis() > end) {
                String t = "下载还没完，手机先不跟了（电脑上照常下载）";
                sink.done(id, title, false, t);
                return t;
            }
            try {
                sl.sleep(pollMs);
            } catch (InterruptedException e) {
                return "已停止";
            }
        }
    }

    static String fetch(Caller c, Saver saver, int id, Map<String, Object> d, String t) {
        Object bytes = d.get("bytes");
        if (bytes instanceof Number && ((Number) bytes).longValue() > FETCH_MAX)
            return t + "；文件超过 5 MB，现有传文件通道传不了，留在电脑上";
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("id", (long) id);
        PcLanClient.Result f = c.call(FETCH, a, 60000);
        if (!f.ok || f.data.get("b64") == null) return t + "；没传回手机：" + f.text;
        byte[] data;
        try {
            data = PcB64.decode(String.valueOf(f.data.get("b64")));
        } catch (IllegalArgumentException e) {
            return t + "；传回的内容坏了";
        }
        String name = s(f.data, "name");
        String at = saver == null ? null : saver.save(name.length() > 0 ? name : "onesay-" + id, data, s(d, "audio").length() > 0);
        return at == null ? t + "；传回来了但手机上存不下" : t + "；已传回手机：" + at;
    }

    /** 文件名只留安全字符（存手机用）。 */
    public static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n.length() == 0 ? "onesay-download" : n;
    }
}
