package com.onesay.assistant.v2.imgsave;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/** [imgsave] I-C 缓存层纯逻辑：目录列表解析、图片头宽高、按宽高比挑原图（保守：歧义退回截图）。 */
public class CacheMatcherTest {

    static final long NOW = 1791457200L;

    @Test public void parseListingFiltersWindowAndUnsafePaths() {
        String out = "__NOW " + NOW + "\n"
                + (NOW - 3) + " 524288 /data/data/com.xingin.xhs/cache/image_manager_disk_cache/ab12.0\n"
                + (NOW - 10) + " 300000 /data/data/com.xingin.xhs/cache/fresco/v2.ols100.1/7/xyz.cnt\n"
                + (NOW - 400) + " 600000 /data/data/com.xingin.xhs/cache/old.0\n"          // 窗口外
                + (NOW - 2) + " 4000 /data/data/com.xingin.xhs/cache/tiny.0\n"             // 太小
                + (NOW - 1) + " 500000 /data/data/com.xingin.xhs/cache/a b.0\n"            // 带空格：不碰
                + (NOW - 1) + " 500000 /data/data/com.xingin.xhs/cache/x';rm -rf.0\n"      // 带引号：不碰
                + "garbage line\n";
        List<CacheMatcher.Candidate> c = CacheMatcher.parseListing(out, NOW, 30, 8 * 1024, 40L * 1024 * 1024);
        assertEquals(c.toString(), 2, c.size());
        assertTrue("新的在前", c.get(0).path.endsWith("ab12.0"));
        assertEquals(NOW, CacheMatcher.parseNow(out));
        assertEquals(-1, CacheMatcher.parseNow("no clock"));
        assertFalse(CacheMatcher.safePath("/data/data/x/../../etc/passwd"));
        assertTrue(CacheMatcher.safePath("/sdcard/Android/data/com.ss.android.ugc.aweme/cache/picture/fresco_cache/a.cnt"));
    }

    static CacheMatcher.Candidate c(String p, long ago, long size, int w, int h) {
        return new CacheMatcher.Candidate(p, NOW - ago, size).dims(w, h, "image/jpeg");
    }

    @Test public void pickByAspectAndResolution() {
        List<CacheMatcher.Candidate> cands = new ArrayList<CacheMatcher.Candidate>();
        cands.add(c("/a/thumb", 1, 20000, 270, 360));      // 3:4 但只是缩略图
        cands.add(c("/a/orig", 4, 900000, 1242, 1656));     // 3:4 原图
        cands.add(c("/a/wide", 2, 500000, 1920, 1080));     // 16:9
        cands.add(new CacheMatcher.Candidate("/a/unknown", NOW - 1, 100000));   // 读不出宽高
        CacheMatcher.Decision d = CacheMatcher.pick(cands, 1080, 1440, null);
        assertNotNull(d.toString(), d.chosen);
        assertEquals("/a/orig", d.chosen.path);
        // 16:9 的目标
        assertEquals("/a/wide", CacheMatcher.pick(cands, 1080, 608, null).chosen.path);
        // 方图：没有对得上的 → 截图
        CacheMatcher.Decision sq = CacheMatcher.pick(cands, 1000, 1000, null);
        assertNull(sq.chosen);
        assertTrue(sq.reason, sq.reason.contains("对不上"));
        // 已被别的目标用掉
        Set<String> used = new HashSet<String>();
        used.add("/a/orig");
        assertNull(CacheMatcher.pick(cands, 1080, 1440, used).chosen);
        assertNull(CacheMatcher.pick(new ArrayList<CacheMatcher.Candidate>(), 1080, 1440, null).chosen);
        assertNull(CacheMatcher.pick(cands, 0, 1440, null).chosen);
    }

    @Test public void ambiguousFallsBackToScreenshot() {
        List<CacheMatcher.Candidate> cands = new ArrayList<CacheMatcher.Candidate>();
        cands.add(c("/a/p1", 3, 800000, 1080, 1440));
        cands.add(c("/a/p2", 3, 820000, 1200, 1600));   // 同为 3:4、同一秒写入、不是同一张
        CacheMatcher.Decision d = CacheMatcher.pick(cands, 1080, 1440, null);
        assertNull(d.toString(), d.chosen);
        assertTrue(d.reason.contains("歧义"));
        // 同一张图的两份拷贝（Glide DATA + 同尺寸）不算歧义
        List<CacheMatcher.Candidate> same = new ArrayList<CacheMatcher.Candidate>();
        same.add(c("/a/q1", 3, 800000, 1080, 1440));
        same.add(c("/a/q2", 3, 800000, 1080, 1440));
        assertNotNull(CacheMatcher.pick(same, 1080, 1440, null).chosen);
        // 最新那个明显更晚 → 用它
        List<CacheMatcher.Candidate> gap = new ArrayList<CacheMatcher.Candidate>();
        gap.add(c("/a/new", 1, 800000, 1080, 1440));
        gap.add(c("/a/old", 9, 820000, 1200, 1600));
        assertEquals("/a/new", CacheMatcher.pick(gap, 1080, 1440, null).chosen.path);
    }

    @Test public void headerDimensions() {
        String[] mime = new String[1];
        byte[] png = new byte[32];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0x04, (byte) 0xd8, 0, 0, 0x06, 0x78};
        System.arraycopy(sig, 0, png, 0, sig.length);
        assertArrayEquals(new int[] {1240, 1656}, CacheMatcher.dimensions(png, png.length, mime));
        assertEquals("image/png", mime[0]);
        // JPEG：SOI + APP0(16 字节) + SOF0
        byte[] jpg = new byte[64];
        int i = 0;
        jpg[i++] = (byte) 0xFF; jpg[i++] = (byte) 0xD8;
        jpg[i++] = (byte) 0xFF; jpg[i++] = (byte) 0xE0; jpg[i++] = 0; jpg[i++] = 16;
        i += 14;
        jpg[i++] = (byte) 0xFF; jpg[i++] = (byte) 0xC0; jpg[i++] = 0; jpg[i++] = 17; jpg[i++] = 8;
        jpg[i++] = 0x06; jpg[i++] = 0x78;   // h = 1656
        jpg[i++] = 0x04; jpg[i++] = (byte) 0xd8;   // w = 1240
        assertArrayEquals(new int[] {1240, 1656}, CacheMatcher.dimensions(jpg, jpg.length, mime));
        assertEquals("image/jpeg", mime[0]);
        // GIF
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', (byte) 0x40, 0x01, (byte) 0xf0, 0x00, 0, 0, 0, 0, 0, 0};
        assertArrayEquals(new int[] {320, 240}, CacheMatcher.dimensions(gif, gif.length, mime));
        // WebP VP8X：宽高各减一存 24 位
        byte[] webp = new byte[32];
        byte[] h = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', 'X'};
        System.arraycopy(h, 0, webp, 0, h.length);
        webp[24] = (byte) 0x37; webp[25] = 0x04; webp[26] = 0;   // 1080-1 = 0x437
        webp[27] = (byte) 0x9f; webp[28] = 0x05; webp[29] = 0;   // 1440-1 = 0x59f
        assertArrayEquals(new int[] {1080, 1440}, CacheMatcher.dimensions(webp, webp.length, mime));
        assertEquals("image/webp", mime[0]);
        assertNull("不是图片", CacheMatcher.dimensions("SQLite format 3\0\0\0\0".getBytes(), 19, mime));
        assertEquals("jpg", CacheMatcher.extOf("image/jpeg"));
        assertEquals("webp", CacheMatcher.extOf("image/webp"));
    }

    @Test public void listCommandIsReadOnlyAndValidatesPackage() {
        String cmd = CacheMatcher.listCommand("com.xingin.xhs");
        assertNotNull(cmd);
        assertTrue(cmd.contains("/data/data/com.xingin.xhs/cache"));
        assertTrue(cmd.contains("stat -c '%Y %s %n'"));
        for (String bad : new String[] {"rm ", "mv ", "> /", "dmesg", "kmsg", "chmod", "cp "}) assertFalse(bad, cmd.contains(bad));
        assertNull(CacheMatcher.listCommand("com.x; rm -rf /"));
        assertNull(CacheMatcher.listCommand("../etc"));
        assertNull(CacheMatcher.listCommand(null));
    }
}
