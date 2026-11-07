package com.onesay.assistant.v2.imgsave;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

/** [imgsave] 全黑检测（FLAG_SECURE）、纯色边裁剪、纹理检测找图、raw 截图头。 */
public class PixelHeuristicsTest {

    static int[] fill(int w, int h, int c) {
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, c);
        return px;
    }

    static void noise(int[] px, int w, int l, int t, int r, int b, long seed) {
        Random rnd = new Random(seed);
        for (int y = t; y < b; y++)
            for (int x = l; x < r; x++) px[y * w + x] = 0xff000000 | rnd.nextInt(0xffffff);
    }

    @Test public void allBlack() {
        int w = 180, h = 370;
        assertTrue(PixelHeuristics.isAllBlack(fill(w, h, 0xff000000), w, h));
        int[] almost = fill(w, h, 0xff030303);
        assertTrue("近黑也算", PixelHeuristics.isAllBlack(almost, w, h));
        int[] withStatus = fill(w, h, 0xff000000);
        noise(withStatus, w, 0, 0, w, 20, 1);   // 顶部状态栏有内容 → 不是全黑
        assertFalse(PixelHeuristics.isAllBlack(withStatus, w, h));
        assertFalse(PixelHeuristics.isAllBlack(fill(w, h, 0xffffffff), w, h));
    }

    @Test public void trimLetterbox() {
        int w = 200, h = 400;
        int[] px = fill(w, h, 0xff000000);
        noise(px, w, 0, 100, 200, 250, 2);   // 黑边中间一张 200x150 的图
        assertArrayEquals(new int[] {0, 100, 200, 250}, PixelHeuristics.trimUniformBorders(px, w, h, 0, 0, w, h, 6));
        // 白底留白
        int[] white = fill(w, h, 0xffffffff);
        noise(white, w, 20, 30, 180, 330, 3);
        assertArrayEquals(new int[] {20, 30, 180, 330}, PixelHeuristics.trimUniformBorders(white, w, h, 0, 0, w, h, 6));
        // 整块纯色 → 原样返回
        assertArrayEquals(new int[] {0, 0, w, h}, PixelHeuristics.trimUniformBorders(fill(w, h, 0xff336699), w, h, 0, 0, w, h, 6));
        // 无边 → 原样
        int[] full = fill(w, h, 0);
        noise(full, w, 0, 0, w, h, 4);
        assertArrayEquals(new int[] {0, 0, w, h}, PixelHeuristics.trimUniformBorders(full, w, h, 0, 0, w, h, 6));
    }

    @Test public void textureFindsPhotoNotText() {
        int w = 180, h = 370;
        int[] px = fill(w, h, 0xffffffff);
        // 文字行：黑白两色条纹（颜色少）
        for (int y = 10; y < 60; y += 6)
            for (int x = 10; x < 170; x++) if ((x / 3) % 2 == 0) px[y * w + x] = 0xff000000;
        noise(px, w, 12, 90, 168, 250, 5);   // 照片
        PixelHeuristics.Region r = PixelHeuristics.findImageRegion(px, w, h, 6);
        assertNotNull(r);
        assertTrue(r.toString(), r.confidence >= PixelHeuristics.MIN_CONFIDENCE);
        assertTrue(r.toString(), Math.abs(r.left - 12) <= 6 && Math.abs(r.top - 90) <= 6 && Math.abs(r.right - 168) <= 6 && Math.abs(r.bottom - 250) <= 6);
        // 只有文字 → 没有照片区域或置信度低
        int[] textOnly = fill(w, h, 0xffffffff);
        for (int y = 10; y < 360; y += 6)
            for (int x = 10; x < 170; x++) if ((x / 3) % 2 == 0) textOnly[y * w + x] = 0xff000000;
        PixelHeuristics.Region t = PixelHeuristics.findImageRegion(textOnly, w, h, 6);
        assertTrue(String.valueOf(t), t == null || t.confidence < PixelHeuristics.MIN_CONFIDENCE);
        // 小图（不到 4%）→ 低置信 → 交给手动框选
        int[] small = fill(w, h, 0xffffffff);
        noise(small, w, 60, 60, 90, 90, 6);
        PixelHeuristics.Region s = PixelHeuristics.findImageRegion(small, w, h, 6);
        assertTrue(String.valueOf(s), s == null || s.confidence < PixelHeuristics.MIN_CONFIDENCE);
        int[] mapped = PixelHeuristics.scaleRect(r, 6.0, 6.0, 1080, 2220);
        assertTrue(mapped[2] <= 1080 && mapped[3] <= 2220 && mapped[0] >= 0);
    }

    @Test public void rawHeader() {
        assertEquals(12, PixelHeuristics.rawHeaderLength(12 + 1080L * 2220 * 4, 1080, 2220));
        assertEquals(16, PixelHeuristics.rawHeaderLength(16 + 1080L * 2220 * 4, 1080, 2220));
        assertEquals(-1, PixelHeuristics.rawHeaderLength(1080L * 2220 * 4 + 3, 1080, 2220));
        assertEquals(-1, PixelHeuristics.rawHeaderLength(100, 0, 10));
        byte[] b = {(byte) 0x38, 0x04, 0, 0, (byte) 0xac, 0x08, 0, 0, 1, 0, 0, 0};
        assertEquals(1080, PixelHeuristics.le32(b, 0));
        assertEquals(2220, PixelHeuristics.le32(b, 4));
        assertEquals(1, PixelHeuristics.le32(b, 8));
    }
}
