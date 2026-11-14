package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collection;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 链接下载（MediaArgs 类说明）：链接只收 http / https，拒绝空白 / 控制字符 / ; | ` $ &lt; &gt; " ' \ { } ^、- 开头、账号密码、没有主机；
 * 查询参数里正常的 &amp; = % 放行，并且原样作为参数数组里单独一个元素（&amp;t=10 不丢、不拆）；整理分享文字时只去 # 片段和 utm_* / spm /
 * spm_id_from / fbclid / gclid / igshid；参数表永远 --ignore-config --no-plugin-dirs --no-exec，链接前有 --；yt-dlp 不能是 .bat / .cmd；
 * Cookie 路径进日志打码；PcGuard 里链接不对直接拒、工具没装问「先装工具」。
 */
@RunWith(Parameterized.class)
public class AdvMediaTest {

    static final String[] GOOD = {"https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10", "https://youtu.be/abc?t=10s", "https://www.bilibili.com/video/BV1xx411c7mD?p=2&t=30",
            "https://b23.tv/AbCdEf", "HTTPS://WWW.YOUTUBE.COM/watch?v=x", "http://example.com/v.mp4", "https://x.com/a/status/123?s=20",
            "https://example.com/a%20b.mp4", "https://example.com/a=b&c=d%26e", "https://www.youtube.com/playlist?list=PL123&index=2", "https://example.com/中文.mp4",
            "https://example.com:8443/v", "https://[::1]/v", "https://example.com/v#t=10", "https://example.com/~user/v"};
    static final String[][] BAD = {{"", "没有链接"}, {"ftp://x/v", "只接受 http / https"}, {"file:///C:/v.mp4", "只接受 http / https"},
            {"javascript:alert(1)", "只接受 http / https"}, {"-https://x", "不能以 - 开头"}, {"--exec=calc", "不能以 - 开头"}, {"https://x.com/a b", "空格 / 换行"},
            {"https://x.com/a\tb", "空格 / 换行"}, {"https://x.com/a\nb", "空格 / 换行"}, {"https://x.com/a;calc", "「;」"}, {"https://x.com/a|calc", "「|」"},
            {"https://x.com/`calc`", "「`」"}, {"https://x.com/$(calc)", "「$」"}, {"https://x.com/a<b", "「<」"}, {"https://x.com/a>b", "「>」"},
            {"https://x.com/a\"b", "「\"」"}, {"https://x.com/a'b", "「'」"}, {"https://x.com\\a", "「\\」"}, {"https://x.com/{a}", "「{」"}, {"https://x.com/a^b", "「^」"},
            {"https://x.com/\u0001", "控制字符"}, {"https://x.com/\u007f", "控制字符"}, {"https:///v", "没有网站地址"}, {"https://u:p@x.com/v", "账号密码"},
            {"https://u@x.com/v", "账号密码"}, {"x.com/v", "只接受 http / https"}, {"https:x.com/v", "只接受 http / https"}, {"https://x.com/%zz", "格式不对"},
            {"https://x.com/" + "a".repeat(MediaArgs.MAX_URL), "太长"}};
    static final String[][] TIDY = {{"看这个 https://youtu.be/abc?t=10，很好", "https://youtu.be/abc?t=10"},
            {"https://www.youtube.com/watch?v=x&t=10&utm_source=share", "https://www.youtube.com/watch?v=x&t=10"},
            {"https://www.youtube.com/watch?utm_source=a&v=x&t=10", "https://www.youtube.com/watch?v=x&t=10"}, {"https://x.com/v?UTM_Medium=1&a=1", "https://x.com/v?a=1"},
            {"https://x.com/v?utm=1", "https://x.com/v?utm=1"}, {"https://www.bilibili.com/video/BV1?spm_id_from=333.1&p=2", "https://www.bilibili.com/video/BV1?p=2"},
            {"https://x.com/v?spm=a.b&fbclid=1&gclid=2&igshid=3", "https://x.com/v"}, {"https://x.com/v?a=1&&b=2", "https://x.com/v?a=1&b=2"},
            {"https://x.com/v#t=30", "https://x.com/v"}, {"https://x.com/v?a=1#frag", "https://x.com/v?a=1"}, {"https://x.com/v.", "https://x.com/v"},
            {"https://x.com/v?a=1&", "https://x.com/v?a=1"}, {"https://x.com/v?", "https://x.com/v"}, {"no link here", null}, {"", null},
            {"【标题】https://b23.tv/XyZ】", "https://b23.tv/XyZ"}, {"复制打开 https://v.douyin.com/abc/ 看看", "https://v.douyin.com/abc/"},
            {"two https://a.com/1 https://b.com/2", "https://a.com/1"}, {"HTTPS://YOUTU.BE/X?T=10", "HTTPS://YOUTU.BE/X?T=10"},
            {"https://x.com/v?si=abc&t=5", "https://x.com/v?si=abc&t=5"}, {"https://x.com/v?list=PL1&index=3&utm_campaign=z", "https://x.com/v?list=PL1&index=3"},
            {"\"https://x.com/v?t=1\"", "https://x.com/v?t=1"}, {"https://x.com/v,", "https://x.com/v"}, {"https://x.com/v?t=10。", "https://x.com/v?t=10"},
            {"https://x.com/v?t=10）后面", "https://x.com/v?t=10"}, {"https://x.com/v?t=1&GCLID=9&t2=2", "https://x.com/v?t=1&t2=2"},
            {"ftp://x.com/v", null}, {"https://x.com/v?fbclid=1#x&t=3", "https://x.com/v"}};

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("media");
        for (String g : GOOD) r.add("url ok " + g, "url", g, null);
        for (String[] b : BAD) r.add("url bad " + b[0], "url", b[0], b[1]);
        r.add("url bad null", "url", null, "没有链接");
        for (String[] t : TIDY) r.add("tidy " + t[0], "tidy", t[0], t[1]);
        r.add("tidy null", "tidy", null, null);
        String[] dl = {"https://www.youtube.com/watch?v=x&t=10", "https://www.bilibili.com/video/BV1?p=2&t=30", "https://example.com/a=b&c=d%26e", "https://b23.tv/x"};
        for (String u : dl)
            for (String q : new String[]{"best", "1080", "720"})
                for (String a : new String[]{"", "m4a", "mp3"})
                    for (boolean ff : new boolean[]{true, false})
                        r.add("download " + u + " q=" + q + " a=" + a + " ff=" + ff, "download", u, q + "|" + a + "|" + ff);
        for (String u : dl)
            for (boolean ff : new boolean[]{true, false})
                for (boolean ck : new boolean[]{true, false})
                    r.add("preview " + u + " ff=" + ff + " cookies=" + ck, "preview", u, ff + "|" + ck);
        for (String y : new String[]{"yt-dlp.bat", "C:\\t\\YT-DLP.CMD", "x.Bat", "yt-dlp.exe", "/usr/bin/yt-dlp", "C:\\tools\\yt-dlp.exe"})
            r.add("base " + y, "base", y, null);
        Object[][] qs = {{"1080", "1080"}, {"1080p", "1080"}, {"1080P", "1080"}, {" 720 ", "720"}, {"720p", "720"}, {"best", "best"}, {"4k", "best"},
                {"", "best"}, {"2160", "best"}, {1080L, "1080"}, {720, "720"}};
        for (Object[] q : qs) r.add("quality [" + q[0] + "] " + q[0].getClass().getSimpleName(), "quality", q[0], q[1]);
        r.add("quality [null]", "quality", null, "best");
        Object[][] as = {{"", ""}, {"false", ""}, {"none", ""}, {"video", ""}, {"m4a", "m4a"}, {"M4A", "m4a"}, {"true", "m4a"}, {"audio", "m4a"}, {"mp3", "mp3"},
                {" MP3 ", "mp3"}, {"flac", null}, {"wav", null}, {Boolean.TRUE, "m4a"}, {Boolean.FALSE, ""}, {"mp4", null}};
        for (Object[] a : as) r.add("audio [" + a[0] + "] " + a[0].getClass().getSimpleName(), "audio", a[0], a[1]);
        r.add("audio [null]", "audio", null, "");
        // PcGuard：{档位, url, audio, 工具缺不缺, 期望}
        String[][] g = {{"workspace_write/untrusted", "https://youtu.be/x?t=10", "", "no", "ask:pc.media_download"},
                {"workspace_write/on_request", "https://youtu.be/x?t=10", "mp3", "no", "ask:pc.media_download"},
                {"workspace_write/untrusted", "https://youtu.be/x?t=10", "", "yes", "ask:pc.media_tool_install"},
                {"workspace_write/untrusted", "https://youtu.be/x?t=10", "", "throw", "ask:pc.media_tool_install"},
                {"workspace_write/untrusted", "https://x.com/a;calc", "", "no", "deny:" + LinkPolicy.R_UNTRUSTED_URL},
                {"full_access/never", "https://x.com/a|calc", "", "no", "deny:" + LinkPolicy.R_UNTRUSTED_URL},
                {"workspace_write/untrusted", "https://youtu.be/x", "flac", "no", "deny:" + LinkPolicy.R_UNTRUSTED_URL},
                {"read_only/untrusted", "https://youtu.be/x", "", "no", "deny:" + LinkPolicy.R_DENIED_BY_LEVEL},
                {"workspace_write/never", "https://youtu.be/x", "", "no", "deny:" + LinkPolicy.R_DENIED_BY_LEVEL},
                {"full_access/never", "https://youtu.be/x?t=10", "", "no", "allow"}, {"full_access/never", "https://youtu.be/x?t=10", "", "yes", "allow-install"},
                {"full_access/on_request", "https://youtu.be/x?t=10", "m4a", "no", "ask:pc.media_download"},
                {"workspace_write/untrusted", "-https://youtu.be/x", "", "no", "deny:" + LinkPolicy.R_UNTRUSTED_URL},
                {"workspace_write/untrusted", "https://u:p@youtu.be/x", "", "no", "deny:" + LinkPolicy.R_UNTRUSTED_URL}};
        for (String[] x : g) r.add("guard " + String.join(" ", x), "guard", x[1], x);
        // 进度行 / 限流
        String[][] pr = {{"[download]  12.3% of ~ 10.00MiB at 1.23MiB/s ETA 00:05", "12.3|10.00MiB|1.23MiB/s|00:05"}, {"[download] 100% of 5.00MiB", "100.0|5.00MiB||"},
                {"[download]   0.0% of 1GiB at Unknown B/s ETA Unknown", "0.0|1GiB||"}, {"[download] 101% of 1MiB", null}, {"[info] 12%", null}, {"", null},
                {"  [download]  50% at 2MiB/s", "50.0||2MiB/s|"}};
        for (String[] p : pr) r.add("progress [" + p[0] + "]", "progress", p[0], p[1]);
        String[][] rl = {{"ERROR: HTTP Error 429: Too Many Requests", "true"}, {"too many requests", "true"}, {"HTTP Error 403: Forbidden", "false"},
                {"[download] 42.9% of 429MiB", "false"}, {"code 429 rate limited", "true"}};
        for (String[] x : rl) r.add("ratelimit [" + x[0] + "]", "rate", x[0], x[1]);
        return r.get();
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String kind;
    @Parameterized.Parameter(2) public Object in;
    @Parameterized.Parameter(3) public Object want;

    @Test public void media() throws Exception {
        switch (kind) {
            case "url": {
                String bad = MediaArgs.checkUrl((String) in);
                if (want == null) assertNull(name + "：" + bad, bad);
                else {
                    assertNotNull(name, bad);
                    assertTrue(name + " → " + bad, bad.contains((String) want));
                }
                break;
            }
            case "tidy": {
                String t = MediaArgs.tidy((String) in);
                assertEquals(name, want, t);
                if (t != null) assertNull("整理后的链接下载端也收：" + t, MediaArgs.checkUrl(t));
                break;
            }
            case "download": {
                String[] p = ((String) want).split("\\|", -1);
                String u = (String) in;
                boolean ff = Boolean.parseBoolean(p[2]);
                List<String> a;
                try {
                    a = MediaArgs.download("yt-dlp.exe", u, p[0], p[1], ff ? "C:\\ff" : null, "C:\\Users\\x\\OneSay\\Downloads", "C:\\c.txt");
                } catch (IllegalArgumentException e) {
                    assertTrue(name + " 只有仅音频且没 FFmpeg 才不行", !p[1].isEmpty() && !ff);
                    assertTrue(e.getMessage(), e.getMessage().contains("FFmpeg"));
                    return;
                }
                assertFalse(name, !p[1].isEmpty() && !ff);
                fixed(a, u);
                assertEquals(MediaArgs.format(p[0], p[1], ff), a.get(a.indexOf("-f") + 1));
                assertEquals(!p[1].isEmpty(), a.contains("-x"));
                if (!p[1].isEmpty()) assertEquals(p[1], a.get(a.indexOf("--audio-format") + 1));
                assertEquals(ff, a.contains("--ffmpeg-location"));
                assertTrue(a.contains("--continue"));
                assertEquals("C:\\Users\\x\\OneSay\\Downloads", a.get(a.indexOf("-P") + 1));
                String log = MediaArgs.masked(a);
                assertFalse("Cookie 路径不进日志", log.contains("C:\\c.txt"));
                assertTrue(log.contains(MediaArgs.COOKIE_MASK));
                break;
            }
            case "preview": {
                String[] p = ((String) want).split("\\|");
                boolean ff = Boolean.parseBoolean(p[0]), ck = Boolean.parseBoolean(p[1]);
                List<String> a = MediaArgs.preview("/usr/bin/yt-dlp", (String) in, "best", "", ff ? "/ff" : null, ck ? "/c.txt" : null);
                fixed(a, (String) in);
                assertTrue(a.contains("-J"));
                assertEquals(ck, a.contains("--cookies"));
                assertEquals(ck, MediaArgs.masked(a).contains(MediaArgs.COOKIE_MASK));
                break;
            }
            case "base": {
                String y = (String) in;
                boolean script = y.toLowerCase().endsWith(".bat") || y.toLowerCase().endsWith(".cmd");
                try {
                    MediaArgs.preview(y, "https://youtu.be/x?t=1&a=2", "best", "", null, null);
                    assertFalse(name + " .bat / .cmd 应拒绝", script);
                } catch (IllegalArgumentException e) {
                    assertTrue(name + "：" + e.getMessage(), script);
                }
                break;
            }
            case "quality": assertEquals(name, want, MediaArgs.quality(in)); break;
            case "audio": assertEquals(name, want, MediaArgs.audio(in)); break;
            case "guard": guard((String[]) want); break;
            case "progress": {
                MediaArgs.Progress p = MediaArgs.progress((String) in);
                if (want == null) assertNull(name, p);
                else {
                    String[] w = ((String) want).split("\\|", -1);
                    assertNotNull(name, p);
                    assertEquals(Double.parseDouble(w[0]), p.percent, 1e-9);
                    assertEquals(w[1], p.total);
                    assertEquals(w[2], p.speed);
                    assertEquals(w[3], p.eta);
                }
                break;
            }
            case "rate": assertEquals(name, Boolean.parseBoolean((String) want), MediaArgs.rateLimited((String) in)); break;
            default: throw new AssertionError(kind);
        }
    }

    /** 固定前缀、没有 --exec、链接在 -- 后面、作为最后一个完整元素（& = % 都不拆）。 */
    static void fixed(List<String> a, String url) {
        assertEquals("--ignore-config", a.get(1));
        assertEquals("--no-plugin-dirs", a.get(2));
        assertTrue(a.contains("--no-playlist"));
        assertTrue(a.contains("--no-exec"));
        assertFalse(a.contains("--exec"));
        assertEquals("--", a.get(a.size() - 2));
        assertEquals("链接原样、单独一个元素", url, a.get(a.size() - 1));
        int n = 0;
        for (String s : a) if (s.contains(url)) n++;
        assertEquals("链接只出现一次", 1, n);
    }

    void guard(String[] x) throws Exception {
        String[] m = x[0].split("/");
        Config c = AdvSupport.cfg(LinkPolicy.Mode.of(m[0], m[1]));
        PcGuard g = new PcGuard(c, null);
        if (x[3].equals("yes")) g.mediaToolsMissing = () -> true;
        if (x[3].equals("throw")) g.mediaToolsMissing = () -> { throw new IllegalStateException("查不了"); };
        AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.DENY);
        g.confirmer = st;
        PcGuard.Outcome o = g.check("phone-a", "media_get", AdvSupport.m("url", x[1], "audio", x[2]));
        String w = x[4];
        if (w.startsWith("ask:")) {
            assertEquals(name, 1, st.asked.size());
            assertEquals(w.substring(4), st.kinds.get(0));
            assertTrue("确认框里带链接（含 &t=10 这类参数）", st.asked.get(0).contains(LinkPolicy.oneLine(x[1])));
        } else if (w.startsWith("deny:")) {
            assertEquals(name, w.substring(5), AdvSupport.reason(o));
            assertEquals(0, st.asked.size());
        } else {
            assertNull(name, o.reply);
            assertEquals(0, st.asked.size());
            assertEquals(w.equals("allow-install"), Boolean.TRUE.equals(o.args.get("_media_install_ok")));
            assertEquals("参数原样传给执行端", x[1], o.args.get("url"));
        }
        if (o.pass == null && o.reply == null) fail("既没放行也没回复");
    }
}
