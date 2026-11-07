package com.onesay.assistant.v2.imgsave;

/**
 * [imgsave] 截图像素层面的三件事（纯 Java，输入是 ARGB int[]，JVM 可测）：
 * <ol>
 *   <li>{@link #isAllBlack}：FLAG_SECURE 界面 root screencap 出来是全黑 → 告诉用户「这个页面禁止截图」。</li>
 *   <li>{@link #trimUniformBorders}：大图浏览页的 ImageView 往往铺满全屏、图片上下留黑/白边 → 裁掉纯色边，
 *       得到图片真正的显示区域（裁剪更干净，缓存层按它的宽高比去比对）。</li>
 *   <li>{@link #findImageRegion}：dump 里一个图片节点都没有（自绘 / Compose / 游戏）时，按「纹理」找最大的一块像图片的矩形：
 *       把缩略图切成小格，每格算亮度标准差和量化颜色数 —— 照片格子两者都高；文字格子亮度差大但颜色少；纯色背景两者都低。
 *       在「照片格」二值矩阵里找最大全 1 矩形（直方图法），置信度 = 面积占比与边缘清晰度的组合；低于阈值交给手动框选。</li>
 * </ol>
 */
public final class PixelHeuristics {

    private PixelHeuristics() {}

    static int lum(int c) {
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    /** 采样后 ≥ 99.5% 的像素亮度 &lt; 10 → 全黑。 */
    public static boolean isAllBlack(int[] px, int w, int h) {
        if (px == null || w <= 0 || h <= 0 || px.length < w * h) return false;
        int step = Math.max(1, (int) Math.sqrt((double) w * h / 40000.0));
        long total = 0, dark = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                total++;
                if (lum(px[y * w + x]) < 10) dark++;
            }
        }
        return total > 0 && dark * 1000 >= total * 995;
    }

    static boolean near(int a, int b, int tol) {
        return Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff)) <= tol
                && Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff)) <= tol
                && Math.abs((a & 0xff) - (b & 0xff)) <= tol;
    }

    private static boolean rowUniform(int[] px, int w, int y, int x0, int x1, int ref, int tol) {
        int step = Math.max(1, (x1 - x0) / 64);
        for (int x = x0; x < x1; x += step) if (!near(px[y * w + x], ref, tol)) return false;
        return true;
    }

    private static boolean colUniform(int[] px, int w, int x, int y0, int y1, int ref, int tol) {
        int step = Math.max(1, (y1 - y0) / 64);
        for (int y = y0; y < y1; y += step) if (!near(px[y * w + x], ref, tol)) return false;
        return true;
    }

    /**
     * 在 rect（l,t,r,b，像素坐标）内裁掉四周与角落同色的纯色边。裁完的面积不足原来的 20% 视为整块纯色，返回原矩形。
     * @return {l,t,r,b}
     */
    public static int[] trimUniformBorders(int[] px, int w, int h, int l, int t, int r, int b, int tol) {
        int[] orig = {l, t, r, b};
        if (px == null || px.length < w * h) return orig;
        l = Math.max(0, l); t = Math.max(0, t); r = Math.min(w, r); b = Math.min(h, b);
        if (r - l < 8 || b - t < 8) return orig;
        int ref = px[t * w + l];
        int nl = l, nt = t, nr = r, nb = b;
        while (nt < nb - 1 && rowUniform(px, w, nt, nl, nr, ref, tol)) nt++;
        while (nb - 1 > nt && rowUniform(px, w, nb - 1, nl, nr, ref, tol)) nb--;
        while (nl < nr - 1 && colUniform(px, w, nl, nt, nb, ref, tol)) nl++;
        while (nr - 1 > nl && colUniform(px, w, nr - 1, nt, nb, ref, tol)) nr--;
        long before = (long) (r - l) * (b - t), after = (long) (nr - nl) * (nb - nt);
        if (after * 5 < before) return new int[] {l, t, r, b};
        return new int[] {nl, nt, nr, nb};
    }

    /** 纹理检测结果。坐标是输入图（通常是缩略图）的像素坐标。 */
    public static final class Region {
        public final int left, top, right, bottom;
        public final double confidence;

        Region(int l, int t, int r, int b, double c) {
            left = l; top = t; right = r; bottom = b; confidence = c;
        }

        @Override public String toString() {
            return "[" + left + "," + top + "][" + right + "," + bottom + "] conf=" + String.format(java.util.Locale.ROOT, "%.2f", confidence);
        }
    }

    /** 置信度阈值：低于它就交给手动框选（圈一下）。 */
    public static final double MIN_CONFIDENCE = 0.6;

    /**
     * @param px   缩略图像素（建议宽 ~180）
     * @param cell 格子边长（像素）
     * @return 最大的「照片格」矩形；找不到返回 null
     */
    public static Region findImageRegion(int[] px, int w, int h, int cell) {
        if (px == null || w <= 0 || h <= 0 || px.length < w * h || cell <= 1) return null;
        int gw = w / cell, gh = h / cell;
        if (gw < 3 || gh < 3) return null;
        boolean[][] photo = new boolean[gh][gw];
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                photo[gy][gx] = isPhotoCell(px, w, gx * cell, gy * cell, cell);
            }
        }
        // 最大全 1 矩形（直方图 + 单调栈）
        int[] hist = new int[gw];
        int bestArea = 0, bl = 0, bt = 0, br = 0, bb = 0;
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) hist[gx] = photo[gy][gx] ? hist[gx] + 1 : 0;
            int[] stack = new int[gw + 1];
            int sp = 0;
            for (int gx = 0; gx <= gw; gx++) {
                int cur = gx == gw ? 0 : hist[gx];
                while (sp > 0 && hist[stack[sp - 1]] >= cur) {
                    int height = hist[stack[--sp]];
                    int left = sp == 0 ? 0 : stack[sp - 1] + 1;
                    int area = height * (gx - left);
                    if (area > bestArea) {
                        bestArea = area;
                        bl = left; br = gx; bb = gy + 1; bt = gy + 1 - height;
                    }
                }
                stack[sp++] = gx;
            }
        }
        if (bestArea == 0) return null;
        double areaFrac = (double) bestArea / (gw * gh);
        // 边缘清晰度：矩形外一圈格子里「非照片格」的比例（照片和背景分界清楚 → 高）
        int ring = 0, ringNon = 0;
        for (int gy = bt - 1; gy <= bb; gy++) {
            for (int gx = bl - 1; gx <= br; gx++) {
                if (gy < 0 || gx < 0 || gy >= gh || gx >= gw) continue;
                boolean inside = gy >= bt && gy < bb && gx >= bl && gx < br;
                if (inside) continue;
                ring++;
                if (!photo[gy][gx]) ringNon++;
            }
        }
        double edge = ring == 0 ? 1.0 : (double) ringNon / ring;
        // 面积 8% 起步给 0.5，30% 以上满分；再乘边缘清晰度（0.5~1）
        double areaScore = areaFrac < 0.04 ? 0 : Math.min(1.0, 0.5 + (areaFrac - 0.08) / 0.44);
        double conf = Math.max(0, areaScore) * (0.5 + 0.5 * edge);
        return new Region(bl * cell, bt * cell, br * cell, bb * cell, conf);
    }

    /** 一个格子像不像照片：亮度标准差 ≥ 12 且 4-bit 量化颜色数 ≥ 12，或颜色数 ≥ 24（平滑渐变的照片）。 */
    static boolean isPhotoCell(int[] px, int w, int x0, int y0, int cell) {
        long sum = 0, sq = 0;
        int n = 0;
        java.util.HashSet<Integer> colors = new java.util.HashSet<Integer>();
        for (int y = y0; y < y0 + cell; y++) {
            for (int x = x0; x < x0 + cell; x++) {
                int c = px[y * w + x];
                int l = lum(c);
                sum += l;
                sq += (long) l * l;
                n++;
                if (colors.size() < 64) colors.add(((c >> 20) & 0xf) << 8 | ((c >> 12) & 0xf) << 4 | ((c >> 4) & 0xf));
            }
        }
        double mean = (double) sum / n;
        double sd = Math.sqrt(Math.max(0, (double) sq / n - mean * mean));
        return (sd >= 12 && colors.size() >= 12) || colors.size() >= 24;
    }

    /** 把缩略图坐标映射回原图并夹到屏幕内。 */
    public static int[] scaleRect(Region r, double sx, double sy, int scrW, int scrH) {
        int l = (int) Math.floor(r.left * sx), t = (int) Math.floor(r.top * sy);
        int rr = (int) Math.ceil(r.right * sx), b = (int) Math.ceil(r.bottom * sy);
        return new int[] {Math.max(0, l), Math.max(0, t), Math.min(scrW, rr), Math.min(scrH, b)};
    }

    /**
     * root {@code screencap}（不带 -p）原始输出的头长度：Android 8 是 12 字节（w,h,format），Android 9+ 多一个 colorspace（16 字节）。
     * @return 头长度；对不上返回 -1
     */
    public static int rawHeaderLength(long fileSize, int w, int h) {
        if (w <= 0 || h <= 0) return -1;
        long body = (long) w * h * 4;
        if (fileSize == body + 12) return 12;
        if (fileSize == body + 16) return 16;
        return -1;
    }

    /** 读小端 int（raw 截图头）。 */
    public static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }
}
