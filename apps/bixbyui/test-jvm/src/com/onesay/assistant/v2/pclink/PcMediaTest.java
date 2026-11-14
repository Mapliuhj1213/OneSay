package com.onesay.assistant.v2.pclink;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * [media-get] 链接下载 · 手机这一半（纯 Java）：链接整理与检查（注入拦截）、语音口令、进度跟踪（假电脑）、传回手机 ≤ 5 MB、
 * 电脑断开、分享菜单 / 控制台 / 语音的接线（源码检查）。思路参考 omniget，代码自写。
 */
public class PcMediaTest {

    static final String URL = "https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_Trailer_1080p.ogv";

    @Test public void tidyAndCheckUrl() {
        assertEquals("https://www.bilibili.com/video/BV1xx/?share_source=copy&vd_source=abc", PcMedia.tidy("【标题】 https://www.bilibili.com/video/BV1xx/?share_source=copy&vd_source=abc#t=1 复制打开"));
        assertEquals("https://v.douyin.com/abc/", PcMedia.tidy("7.99 复制打开抖音 https://v.douyin.com/abc/ 看看"));
        assertNull(PcMedia.tidy("没有链接"));
        assertNull(PcMedia.tidy(null));
        // 2026-11-11 用户同意：不再截掉第一个 & 之后的参数；YouTube 带 &t=10 完整保留，只去 # 片段和明显的跟踪参数
        String yt = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10";
        assertEquals(yt, PcMedia.tidy("分享视频 " + yt + " 从第 10 秒"));
        assertNull(PcMedia.checkUrl(PcMedia.tidy(yt)));
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10&list=PL1",
                PcMedia.tidy("https://www.youtube.com/watch?utm_source=share&v=dQw4w9WgXcQ&t=10&UTM_Medium=x&&spm=a.b&list=PL1&fbclid=Zz#comments"));
        assertEquals("https://a.com/v", PcMedia.tidy("https://a.com/v?utm_source=x&spm_id_from=1&gclid=2&igshid=3"));
        PcMedia.Ask ask = PcMedia.voice("用电脑下载 " + yt);
        assertNotNull(ask);
        assertEquals("语音里的链接也完整保留", yt, ask.url);
        assertNull(PcMedia.checkUrl("https://a.com/?a=1&b=2"));
        assertNotNull(PcMedia.checkUrl("https://a.com/?a=1& b"));
        assertNull(PcMedia.checkUrl(URL));
        String[] bad = {"https://a.com/x;rm", "https://a.com/x|y", "https://a.com/a b", "file:///sdcard/x", "--exec x", "-o x",
                "javascript:alert(1)", "https://u:p@a.com/", "https:///x", "", "https://a.com/`id`", "https://a.com/$x"};
        for (String b : bad) assertNotNull(b, PcMedia.checkUrl(b));
        Map<String, Object> a = PcMedia.args(URL, "720", "mp3");
        assertEquals(URL, a.get("url"));
        assertEquals("720", a.get("quality"));
        assertEquals("mp3", a.get("audio"));
        a = PcMedia.args(URL, "; rm", "wav");
        assertEquals("best", a.get("quality"));
        assertEquals("", a.get("audio"));
        assertEquals("测 试.mp4", PcMedia.safeName("测 试.mp4"));
        assertEquals("_.._x", PcMedia.safeName("/../x").replace("_", "_"));
        assertFalse(PcMedia.safeName("../../etc/passwd").contains("/"));
    }

    @Test public void voiceCommands() {
        String[] yes = {"下载这个视频", "把这个链接下到电脑", "帮我把这个视频下载到电脑上", "下载这个视频到电脑", "用电脑下载 " + URL, "下载 " + URL,
                "把刚才那个视频下到电脑", "下这个视频", "把这首歌下到电脑"};
        for (String s : yes) {
            assertNotNull(s, PcMedia.voice(s));
            assertEquals(s, PcVoice.Kind.MEDIA, PcVoice.of(s));
        }
        assertEquals(URL, PcMedia.voice("用电脑下载 " + URL + " 吧").url);
        assertNull("没说链接：用剪贴板", PcMedia.voice("下载这个视频").url);
        assertEquals("", PcMedia.voice("下载这个视频").audio);
        assertEquals("m4a", PcMedia.voice("下载这个视频的音频").audio);
        assertEquals("m4a", PcMedia.voice("把这首歌下到电脑").audio);
        assertEquals("mp3", PcMedia.voice("把这个视频转成mp3下到电脑").audio);
        for (String s : new String[]{"下载", "下载微信", "下载抖音", "帮我下载个游戏", "视频怎么下载", "这个视频真好看", "电脑锁屏", "打开 " + URL, ""}) {
            assertNull(s, PcMedia.voice(s));
            assertNotEquals(s, PcVoice.Kind.MEDIA, PcVoice.of(s));
        }
        assertEquals("原来的口令不受影响", PcVoice.Kind.LOCK, PcVoice.of("电脑锁屏"));
    }

    // ------------------------------------------------------------------ 进度跟踪（假电脑）

    static PcLanClient.Result ok(String text, Map<String, Object> d) { return new PcLanClient.Result(true, text, d, ""); }

    static Map<String, Object> st(String state, double pct) {
        Map<String, Object> d = new TreeMap<String, Object>();
        d.put("state", state);
        d.put("state_zh", state);
        d.put("percent", pct);
        d.put("speed", "2.00MiB/s");
        d.put("eta", "00:03");
        d.put("title", "测试视频");
        return d;
    }

    static final class FakePc implements PcMedia.Caller {
        final List<Object> script = new ArrayList<Object>();
        final List<String> calls = new ArrayList<String>();
        int i;

        @SuppressWarnings("unchecked")
        @Override public PcLanClient.Result call(String cmd, Map<String, Object> args, int timeoutMs) {
            calls.add(cmd + " " + args);
            if (cmd.equals(PcMedia.FETCH)) {
                Map<String, Object> d = new TreeMap<String, Object>();
                d.put("name", "测试视频 [abc].mp4");
                d.put("b64", PcB64.encode("FAKEVIDEO".getBytes(StandardCharsets.UTF_8)));
                return ok("已传回", d);
            }
            Object o = script.get(Math.min(i++, script.size() - 1));
            return o instanceof PcLanClient.Result ? (PcLanClient.Result) o : ok("", (Map<String, Object>) o);
        }
    }

    static final class Rec implements PcMedia.Sink, PcMedia.Saver, PcMedia.Sleeper {
        final List<String> lines = new ArrayList<String>();
        final List<Integer> pct = new ArrayList<Integer>();
        String done;
        boolean ok;
        byte[] saved;
        String savedName;

        @Override public void progress(int id, String title, int percent, String line) {
            pct.add(percent);
            lines.add(line);
        }

        @Override public void done(int id, String title, boolean o, String text) {
            done = text;
            ok = o;
        }

        @Override public String save(String name, byte[] data, boolean audio) {
            savedName = name;
            saved = data;
            return "视频/OneSay/" + name;
        }

        @Override public void sleep(long ms) { }
    }

    @Test public void followProgressThenFetchBack() {
        FakePc pc = new FakePc();
        pc.script.add(st("preview", 0));
        pc.script.add(st("downloading", 42.5));
        Map<String, Object> done = st("done", 100);
        done.put("path", "C:\\Users\\me\\OneSay\\Downloads\\测试视频 [abc].mp4");
        done.put("bytes", 9L);
        pc.script.add(done);
        Rec r = new Rec();
        String t = PcMedia.follow(pc, r, r, 7, true, 60000, 1, r);
        assertTrue(t, t.contains("已下载到电脑") && t.contains("已传回手机"));
        assertTrue(r.ok);
        assertEquals("FAKEVIDEO", new String(r.saved, StandardCharsets.UTF_8));
        assertEquals("测试视频 [abc].mp4", r.savedName);
        assertTrue(r.pct.contains(43));
        assertTrue(r.lines.get(1), r.lines.get(1).contains("42.5%") && r.lines.get(1).contains("2.00MiB/s") && r.lines.get(1).contains("剩 00:03"));
        assertTrue(pc.calls.get(0).startsWith("media_status {id=7}"));
        assertTrue(pc.calls.get(pc.calls.size() - 1).startsWith("media_fetch"));
        // 大于 5 MB：不取，只给电脑上的路径
        FakePc big = new FakePc();
        Map<String, Object> d2 = st("done", 100);
        d2.put("path", "C:\\d\\big.mp4");
        d2.put("bytes", PcMedia.FETCH_MAX + 1);
        big.script.add(d2);
        Rec r2 = new Rec();
        String t2 = PcMedia.follow(big, r2, r2, 8, true, 60000, 1, r2);
        assertTrue(t2, t2.contains("C:\\d\\big.mp4") && t2.contains("5 MB"));
        assertNull(r2.saved);
        for (String c : big.calls) assertFalse("没去取", c.startsWith("media_fetch"));
        // 不要传回：只报路径
        FakePc nf = new FakePc();
        nf.script.add(done);
        Rec r3 = new Rec();
        assertTrue(PcMedia.follow(nf, r3, r3, 9, false, 60000, 1, r3).contains("已下载到电脑"));
        assertNull(r3.saved);
    }

    @Test public void followFailureOfflineAndCancel() {
        FakePc f = new FakePc();
        Map<String, Object> fail = st("failed", 0);
        fail.put("error", "被网站限流（HTTP 429）");
        f.script.add(fail);
        Rec r = new Rec();
        assertTrue(PcMedia.follow(f, r, r, 1, true, 60000, 1, r).contains("429"));
        assertFalse(r.ok);
        FakePc off = new FakePc();
        off.script.add(new PcLanClient.Result(false, "电脑离线", null, "offline"));
        Rec r2 = new Rec();
        String t = PcMedia.follow(off, r2, r2, 2, false, 60000, 1, r2);
        assertTrue(t, t.contains("断开") && t.contains("电脑上照常"));
        assertEquals("连不上 5 次才放弃", 5, off.calls.size());
        FakePc c = new FakePc();
        c.script.add(st("canceled", 10));
        Rec r3 = new Rec();
        assertEquals("已取消", PcMedia.follow(c, r3, r3, 3, false, 60000, 1, r3));
        FakePc gone = new FakePc();
        gone.script.add(new PcLanClient.Result(false, "没有这个下载任务", null, ""));
        Rec r4 = new Rec();
        assertTrue(PcMedia.follow(gone, r4, r4, 4, false, 60000, 1, r4).contains("找不到"));
    }

    // ------------------------------------------------------------------ 接线（源码检查）

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    static final String P = "app/src/com/onesay/assistant/v2/";

    @Test public void wiring() throws Exception {
        String m = src("app/AndroidManifest.xml");
        int i = m.indexOf("com.onesay.assistant.v2.pclink.PcShareActivity");
        assertTrue("分享菜单入口", i > 0);
        String decl = m.substring(m.lastIndexOf("<activity", i), m.indexOf("</activity>", i));
        assertTrue(decl.contains("android.intent.action.SEND") && decl.contains("text/plain"));
        assertFalse("不带权限、不自动执行", decl.contains("android:permission"));
        String share = src(P + "pclink/PcShareActivity.java");
        assertTrue("要你点「下载」才发", share.contains("PcMediaAndroid.chooser(") && !share.contains("PcMediaAndroid.start("));
        String plug = src(P + "kernel/plugins/PcLinkPlugin.java");
        assertTrue(plug.contains("PcVoice.Kind.MEDIA") && plug.contains("需要连上电脑"));
        String ui = src(P + "pclink/PcLinkUi.java");
        assertTrue(ui.contains("把链接下到电脑") && ui.contains("cat == LinkPolicy.Cat.MEDIA_DOWNLOAD"));
        String and = src(P + "pclink/PcMediaAndroid.java");
        assertTrue("电脑没连上只提示", and.contains("需要连上电脑"));
        assertTrue("电脑上可能要确认，多等", and.contains("PcLinkUi.MAY_CONFIRM_WAIT_MS"));
        for (String f : new String[]{"pclink/PcMedia.java", "pclink/PcMediaAndroid.java", "pclink/PcShareActivity.java"}) {
            String s = src(P + f);
            assertFalse(f + " 手机本地不跑 yt-dlp", s.contains("ProcessBuilder") || s.contains("Runtime.getRuntime().exec") || s.contains("yt-dlp.exe"));
            java.util.regex.Matcher lm = java.util.regex.Pattern.compile("\"([^\"\\\\]|\\\\.)*\"").matcher(s);
            while (lm.find()) assertFalse(f + " " + lm.group(), lm.group().contains("Bixby") || lm.group().contains("三星"));
        }
        assertTrue(src(P + "pclink/PcMedia.java").contains("思路参考 omniget"));
        assertTrue("LinkPolicy 三份同步：media_get 登记了", src(P + "pclink/LinkPolicy.java").contains("if (\"media_get\".equals(cmd)) return Cat.MEDIA_DOWNLOAD;"));
        assertEquals(LinkPolicy.Cat.MEDIA_DOWNLOAD, LinkPolicy.pcCat("media_get"));
        assertEquals("不属于手机被控端的分档指令", LinkPolicy.Cat.UNKNOWN, LinkPolicy.phoneCat("media_get"));
        assertTrue(LinkPolicy.knownKind("pc.media_download"));
    }
}
