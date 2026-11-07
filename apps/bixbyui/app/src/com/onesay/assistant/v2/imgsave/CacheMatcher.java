package com.onesay.assistant.v2.imgsave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * [imgsave] I-C 缓存层的纯逻辑：解析 root 列目录的输出、读图片头拿宽高、按宽高比挑「原图」。纯 Java，JVM 可测。
 *
 * <h3>为什么保守</h3>
 * 缓存目录里同一时刻往往有多张图（信息流预加载、头像、缩略图），挑错了就是「存了一张别的图」——比截图裁剪更糟。所以：
 * <ul>
 *   <li>只看抓图时刻之前 {@link ImageSaveSettings#CACHE_WINDOW_S} 秒内写入的文件；</li>
 *   <li>必须能从文件头读出宽高（JPEG / PNG / WebP / GIF），否则不算；</li>
 *   <li>宽高比与目标差 ≤ {@link #ASPECT_TOL}（按对数比较，横竖对称）；</li>
 *   <li>分辨率不低于屏幕上的显示尺寸的 {@link #MIN_SCALE} 倍（缩略图没意义，截图更清楚）；</li>
 *   <li>多个文件都对得上、且不是同一张（大小不同）→ 只有最新那个比第二新的晚 ≥ {@link #AMBIGUOUS_GAP_S} 秒才用它，否则判「有歧义」退回截图。</li>
 * </ul>
 */
public final class CacheMatcher {

    private CacheMatcher() {}

    public static final double ASPECT_TOL = 0.03;
    public static final double MIN_SCALE = 0.9;
    public static final int AMBIGUOUS_GAP_S = 2;
    public static final int MAX_COPY = 12;

    /** 一个缓存文件。 */
    public static final class Candidate {
        public final String path;
        public final long mtimeSec;
        public final long size;
        public int width;
        public int height;
        public String mime;

        public Candidate(String path, long mtimeSec, long size) {
            this.path = path;
            this.mtimeSec = mtimeSec;
            this.size = size;
        }

        public Candidate dims(int w, int h, String mime) {
            this.width = w;
            this.height = h;
            this.mime = mime;
            return this;
        }

        @Override public String toString() {
            return path + " " + size + "B mtime=" + mtimeSec + (width > 0 ? " " + width + "x" + height + " " + mime : "");
        }
    }

    /** 比对结果。 */
    public static final class Decision {
        public final Candidate chosen;
        public final String reason;

        Decision(Candidate c, String reason) {
            this.chosen = c;
            this.reason = reason;
        }

        @Override public String toString() {
            return (chosen != null ? "HIT " + chosen : "MISS") + " (" + reason + ")";
        }
    }

    private static final Pattern STAT_LINE = Pattern.compile("^(\\d{9,11})\\s+(\\d+)\\s+(/.+)$");
    /** 只接受这些字符组成的路径（缓存文件名都是哈希；带引号/空格/$ 的一律不碰，root 拷贝命令无需转义）。 */
    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._/+=@,~-]+$");

    public static boolean safePath(String p) {
        return p != null && p.length() < 400 && SAFE_PATH.matcher(p).matches() && !p.contains("/../");
    }

    /**
     * 解析 {@code stat -c '%Y %s %n'} 的输出，按窗口过滤、按时间倒序，最多 {@link #MAX_COPY} 个。
     * @param captureSec 抓图时刻（设备时钟，秒）
     */
    public static List<Candidate> parseListing(String out, long captureSec, int windowS, long minBytes, long maxBytes) {
        List<Candidate> list = new ArrayList<Candidate>();
        if (out == null) return list;
        for (String line : out.split("\n")) {
            java.util.regex.Matcher m = STAT_LINE.matcher(line.trim());
            if (!m.matches()) continue;
            long mt, sz;
            try {
                mt = Long.parseLong(m.group(1));
                sz = Long.parseLong(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            String p = m.group(3).trim();
            if (!safePath(p)) continue;
            if (sz < minBytes || sz > maxBytes) continue;
            if (mt < captureSec - windowS || mt > captureSec + 5) continue;
            boolean dup = false;
            for (Candidate c : list) if (c.path.equals(p)) dup = true;
            if (!dup) list.add(new Candidate(p, mt, sz));
        }
        Collections.sort(list, new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) {
                return a.mtimeSec != b.mtimeSec ? (a.mtimeSec > b.mtimeSec ? -1 : 1) : a.path.compareTo(b.path);
            }
        });
        if (list.size() > MAX_COPY) list = new ArrayList<Candidate>(list.subList(0, MAX_COPY));
        return list;
    }

    static double aspectDiff(int w1, int h1, int w2, int h2) {
        return Math.abs(Math.log(((double) w1 / h1) / ((double) w2 / h2)));
    }

    /**
     * 为一个目标（屏幕上的显示宽高）挑缓存文件。
     * @param used 已被别的目标选走的路径（同一批存多张时不重复用）
     */
    public static Decision pick(List<Candidate> cands, int targetW, int targetH, java.util.Set<String> used) {
        if (targetW <= 0 || targetH <= 0) return new Decision(null, "目标尺寸无效");
        if (cands == null || cands.isEmpty()) return new Decision(null, "窗口内没有缓存文件");
        List<Candidate> ok = new ArrayList<Candidate>();
        int decodable = 0;
        for (Candidate c : cands) {
            if (used != null && used.contains(c.path)) continue;
            if (c.width <= 0 || c.height <= 0) continue;
            decodable++;
            if (aspectDiff(c.width, c.height, targetW, targetH) > ASPECT_TOL) continue;
            if (c.width < targetW * MIN_SCALE || c.height < targetH * MIN_SCALE) continue;
            ok.add(c);
        }
        if (ok.isEmpty()) return new Decision(null, decodable == 0 ? "窗口内没有可读宽高的图片文件" : "宽高比/分辨率都对不上（可读 " + decodable + " 个）");
        Collections.sort(ok, new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) {
                if (a.mtimeSec != b.mtimeSec) return a.mtimeSec > b.mtimeSec ? -1 : 1;
                long pa = (long) a.width * a.height, pb = (long) b.width * b.height;
                return pa != pb ? (pa > pb ? -1 : 1) : a.path.compareTo(b.path);
            }
        });
        Candidate best = ok.get(0);
        for (int i = 1; i < ok.size(); i++) {
            Candidate o = ok.get(i);
            boolean sameImage = o.size == best.size && o.width == best.width && o.height == best.height;
            if (sameImage) continue;
            if (best.mtimeSec - o.mtimeSec < AMBIGUOUS_GAP_S) {
                return new Decision(null, "有歧义：" + ok.size() + " 个文件宽高比都对得上（" + best.width + "x" + best.height + " / "
                        + o.width + "x" + o.height + "），写入时间相差 < " + AMBIGUOUS_GAP_S + " s");
            }
            break;
        }
        return new Decision(best, String.format(Locale.ROOT, "宽高比差 %.3f，%dx%d ≥ 显示 %dx%d", aspectDiff(best.width, best.height, targetW, targetH),
                best.width, best.height, targetW, targetH));
    }

    // ------------------------------------------------------------------ 图片头

    /**
     * 从文件开头的字节读宽高。
     * @return {width, height}，mime 写进 mimeOut[0]；认不出返回 null
     */
    public static int[] dimensions(byte[] b, int len, String[] mimeOut) {
        if (b == null || len < 16) return null;
        // PNG
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && len >= 24) {
            set(mimeOut, "image/png");
            return new int[] {be32(b, 16), be32(b, 20)};
        }
        // GIF
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            set(mimeOut, "image/gif");
            return new int[] {(b[6] & 0xff) | (b[7] & 0xff) << 8, (b[8] & 0xff) | (b[9] & 0xff) << 8};
        }
        // WebP
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P' && len >= 30) {
            set(mimeOut, "image/webp");
            if (b[12] == 'V' && b[13] == 'P' && b[14] == '8' && b[15] == ' ') {
                return new int[] {((b[26] & 0xff) | (b[27] & 0xff) << 8) & 0x3fff, ((b[28] & 0xff) | (b[29] & 0xff) << 8) & 0x3fff};
            }
            if (b[12] == 'V' && b[13] == 'P' && b[14] == '8' && b[15] == 'L') {
                int b0 = b[21] & 0xff, b1 = b[22] & 0xff, b2 = b[23] & 0xff, b3 = b[24] & 0xff;
                int w = 1 + (((b1 & 0x3f) << 8) | b0);
                int h = 1 + (((b3 & 0xf) << 10) | (b2 << 2) | ((b1 & 0xc0) >> 6));
                return new int[] {w, h};
            }
            if (b[12] == 'V' && b[13] == 'P' && b[14] == '8' && b[15] == 'X') {
                int w = 1 + ((b[24] & 0xff) | (b[25] & 0xff) << 8 | (b[26] & 0xff) << 16);
                int h = 1 + ((b[27] & 0xff) | (b[28] & 0xff) << 8 | (b[29] & 0xff) << 16);
                return new int[] {w, h};
            }
            return null;
        }
        // JPEG：找 SOFn
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8) {
            int i = 2;
            while (i + 9 < len) {
                if ((b[i] & 0xff) != 0xFF) { i++; continue; }
                int marker = b[i + 1] & 0xff;
                if (marker == 0xFF) { i++; continue; }
                if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { i += 2; continue; }
                int segLen = ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
                boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
                if (sof) {
                    set(mimeOut, "image/jpeg");
                    int h = ((b[i + 5] & 0xff) << 8) | (b[i + 6] & 0xff);
                    int w = ((b[i + 7] & 0xff) << 8) | (b[i + 8] & 0xff);
                    return new int[] {w, h};
                }
                if (marker == 0xDA || segLen < 2) break;
                i += 2 + segLen;
            }
            return null;
        }
        return null;
    }

    private static void set(String[] out, String v) {
        if (out != null && out.length > 0) out[0] = v;
    }

    private static int be32(byte[] b, int o) {
        return (b[o] & 0xff) << 24 | (b[o + 1] & 0xff) << 16 | (b[o + 2] & 0xff) << 8 | (b[o + 3] & 0xff);
    }

    /** mime → 扩展名。 */
    public static String extOf(String mime) {
        if ("image/png".equals(mime)) return "png";
        if ("image/gif".equals(mime)) return "gif";
        if ("image/webp".equals(mime)) return "webp";
        return "jpg";
    }

    // ------------------------------------------------------------------ root 命令（只读）

    /**
     * 列前台 App 图片缓存目录里最近写入的文件（只读：find + stat）。包名先校验，不合法返回 null。
     * 第一行输出 {@code __NOW <设备秒>}。
     */
    public static String listCommand(String pkg) {
        if (pkg == null || !pkg.matches("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")) return null;
        return "echo \"__NOW $(date +%s)\"; "
                + "for d in /data/data/" + pkg + "/cache /data/data/" + pkg + "/app_* /data/data/" + pkg + "/files "
                + "/sdcard/Android/data/" + pkg + "/cache; do "
                + "[ -d \"$d\" ] && find \"$d\" -maxdepth 7 -type f -mmin -3 -size +8k 2>/dev/null; done "
                + "| head -400 | while read f; do stat -c '%Y %s %n' \"$f\" 2>/dev/null; done; true";
    }

    /** 解析 {@code __NOW <秒>}；没有返回 -1。 */
    public static long parseNow(String out) {
        if (out == null) return -1;
        java.util.regex.Matcher m = Pattern.compile("__NOW (\\d{9,11})").matcher(out);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }
}
