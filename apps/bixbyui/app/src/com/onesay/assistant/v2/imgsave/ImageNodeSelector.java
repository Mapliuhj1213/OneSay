package com.onesay.assistant.v2.imgsave;

import com.onesay.assistant.v2.vision.screenref.UiNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * [imgsave] 从<b>非压缩</b> uiautomator dump 里挑「屏幕上的图片」并编号。纯 Java，JVM 可测。
 *
 * <h3>判定</h3>
 * <ol>
 *   <li>类名像图片：{@code ImageView}、{@code *ImageView}、{@code SimpleDraweeView}、{@code PhotoView}、
 *       {@code SubsamplingScaleImageView}、{@code GifImageView}、WebView 里的 {@code android.widget.Image}；
 *       content-desc 写着「图片/照片/image/photo」的 {@code android.view.View}（Compose / 自绘）也算。</li>
 *   <li>排除：按钮/文字类（{@code Button}、{@code ImageButton}、{@code TextView}、{@code EditText}…）、带 text 的节点、
 *       resource-id（按分词）或 content-desc 像头像/图标/按钮/表情/徽标（avatar、icon、btn、logo、emoji、badge、like…；background 不算 back）。</li>
 *   <li>与可见屏幕求交：可见面积 ≥ 节点面积的一半、可见短边 ≥ 屏宽 {@value #MIN_SIDE_PCT}%、
 *       可见面积 ≥ 屏幕面积的 {@value #MIN_AREA_PERMILLE}‰（头像、小图标、表情被过滤）。</li>
 *   <li>去重：两个候选 IoU ≥ 0.8，或小的有 ≥ 90% 落在大的里 → 只留<b>内层</b>（外层多半是带边距的容器）。</li>
 *   <li>编号：阅读顺序（按行从上到下，同一行从左到右；行容差 = 两者高度较小值的一半），最多 {@value #MAX_TARGETS} 张。</li>
 * </ol>
 */
public final class ImageNodeSelector {

    private ImageNodeSelector() {}

    public static final int MIN_SIDE_PCT = 15;
    public static final int MIN_AREA_PERMILLE = 30;
    public static final int MAX_TARGETS = 9;

    /** 一张候选图片。坐标为屏幕像素（与截图同一坐标系）。 */
    public static final class Target {
        public final int left, top, right, bottom;
        public final String className;
        public final String resourceId;
        /** 来源：NODE（节点）/ PIXEL（像素检测）/ MANUAL（手动框选）。 */
        public final String source;

        public Target(int left, int top, int right, int bottom, String className, String resourceId, String source) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.className = className == null ? "" : className;
            this.resourceId = resourceId == null ? "" : resourceId;
            this.source = source == null ? "NODE" : source;
        }

        public int width() { return Math.max(0, right - left); }
        public int height() { return Math.max(0, bottom - top); }
        public long area() { return (long) width() * height(); }

        public Target withRect(int l, int t, int r, int b) {
            return new Target(l, t, r, b, className, resourceId, source);
        }

        @Override public String toString() {
            return "[" + left + "," + top + "][" + right + "," + bottom + "] " + width() + "x" + height() + " " + source
                    + (className.length() > 0 ? " " + shortClass(className) : "");
        }
    }

    private static final Pattern IMAGE_CLASS = Pattern.compile(
            "(?i)(ImageView$|DraweeView$|PhotoView$|ScaleImageView$|GifImageView$|ImageSwitcher$|^android\\.widget\\.Image$|ZoomImage|PinchImage|LargeImage|BigImage)");
    private static final Pattern NOT_IMAGE_CLASS = Pattern.compile(
            "(?i)(Button|TextView|EditText|CheckBox|Switch|RadioButton|SeekBar|ProgressBar|Spinner|RatingBar|Toolbar|TabWidget)");
    private static final Pattern DESC_IMAGE = Pattern.compile("(?i)图片|照片|相片|配图|大图|image|photo|picture|img");
    /** 头像/图标/按钮类的英文词（按 id 的分词逐个比对，避免 background 里的 back 误伤）。 */
    private static final java.util.Set<String> ICON_TOKENS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "avatar", "avator", "head", "portrait", "icon", "ico", "btn", "button", "logo", "emoji", "emoticon", "sticker",
            "badge", "like", "zan", "share", "comment", "collect", "favor", "favorite", "follow", "more", "menu", "close",
            "back", "arrow", "indicator", "dot", "loading", "placeholder", "vip", "level", "medal", "tag", "flag", "verify",
            "verified", "gift", "play", "mute", "voice", "location", "qrcode"));
    private static final Pattern ICON_ZH = Pattern.compile("头像|图标|按钮|表情|点赞|分享|评论|收藏|关注|更多|返回|关闭|播放|认证|勋章");

    /** id / content-desc 是否像头像、图标、按钮。 */
    static boolean iconish(String s) {
        if (s == null || s.length() == 0) return false;
        if (ICON_ZH.matcher(s).find()) return true;
        String id = s;
        int k = id.indexOf(":id/");
        if (k >= 0) id = id.substring(k + 4);
        for (String tok : id.split("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+")) {
            if (tok.length() == 0) continue;
            String t = tok.toLowerCase(java.util.Locale.ROOT).replaceAll("\\d+$", "");
            if (ICON_TOKENS.contains(t)) return true;
        }
        return false;
    }

    /** 节点是否「像图片」（不看尺寸）。 */
    public static boolean looksLikeImage(UiNode n) {
        if (n == null) return false;
        String cls = n.className;
        if (n.text.trim().length() > 0) return false;
        if (NOT_IMAGE_CLASS.matcher(cls).find()) return false;
        if (iconish(n.resourceId)) return false;
        if (n.contentDesc.length() > 0 && iconish(n.contentDesc) && !DESC_IMAGE.matcher(n.contentDesc).find()) return false;
        if (IMAGE_CLASS.matcher(cls).find()) return true;
        return ("android.view.View".equals(cls) || cls.endsWith(".View")) && DESC_IMAGE.matcher(n.contentDesc).find();
    }

    /**
     * @param nodes 非压缩 dump 解析出的节点（自家面板节点已被 UiDumpParser 丢弃）
     * @param scrW  屏幕宽（截图宽）
     * @param scrH  屏幕高
     */
    public static List<Target> select(List<UiNode> nodes, int scrW, int scrH) {
        List<Target> cands = new ArrayList<Target>();
        if (nodes == null || scrW <= 0 || scrH <= 0) return cands;
        long scrArea = (long) scrW * scrH;
        int minSide = scrW * MIN_SIDE_PCT / 100;
        for (UiNode n : nodes) {
            if (!looksLikeImage(n)) continue;
            long nodeArea = (long) Math.max(0, n.right - n.left) * Math.max(0, n.bottom - n.top);
            if (nodeArea <= 0) continue;
            int l = Math.max(0, n.left), t = Math.max(0, n.top), r = Math.min(scrW, n.right), b = Math.min(scrH, n.bottom);
            if (r <= l || b <= t) continue;
            long vis = (long) (r - l) * (b - t);
            if (vis * 2 < nodeArea) continue;
            if (Math.min(r - l, b - t) < minSide) continue;
            if (vis * 1000 < scrArea * MIN_AREA_PERMILLE) continue;
            cands.add(new Target(l, t, r, b, n.className, n.resourceId, "NODE"));
        }
        // 去重：重叠的只留内层
        List<Target> kept = new ArrayList<Target>();
        for (int i = 0; i < cands.size(); i++) {
            Target a = cands.get(i);
            boolean drop = false;
            for (int j = 0; j < cands.size() && !drop; j++) {
                if (i == j) continue;
                Target b = cands.get(j);
                double inter = intersect(a, b);
                if (inter <= 0) continue;
                double iou = inter / (a.area() + b.area() - inter);
                boolean bInsideA = inter >= 0.9 * b.area();
                // a 是外层（b 几乎全在 a 里且 b 更小）→ 丢 a；完全相同的两个只留下标小的
                if ((bInsideA && b.area() < a.area()) || (iou >= 0.8 && (b.area() < a.area() || (b.area() == a.area() && j < i)))) {
                    drop = true;
                }
            }
            if (!drop) kept.add(a);
        }
        sortReadingOrder(kept);
        if (kept.size() > MAX_TARGETS) kept = new ArrayList<Target>(kept.subList(0, MAX_TARGETS));
        return kept;
    }

    /** 阅读顺序：同一行（中心 y 差 ≤ 较矮者高度一半）按 left，不同行按 top。 */
    public static void sortReadingOrder(List<Target> list) {
        Collections.sort(list, new Comparator<Target>() {
            @Override public int compare(Target a, Target b) {
                int cyA = (a.top + a.bottom) / 2, cyB = (b.top + b.bottom) / 2;
                int tol = Math.min(a.height(), b.height()) / 2;
                if (Math.abs(cyA - cyB) <= tol) return a.left != b.left ? (a.left < b.left ? -1 : 1) : 0;
                return cyA < cyB ? -1 : 1;
            }
        });
    }

    static double intersect(Target a, Target b) {
        int l = Math.max(a.left, b.left), t = Math.max(a.top, b.top), r = Math.min(a.right, b.right), bt = Math.min(a.bottom, b.bottom);
        if (r <= l || bt <= t) return 0;
        return (double) (r - l) * (bt - t);
    }

    static String shortClass(String cls) {
        int i = cls.lastIndexOf('.');
        return i >= 0 ? cls.substring(i + 1) : cls;
    }

    /** 编号芯片的文字：「第1张」…「第N张」，N ≥ 2 时再加「全部」「取消」。 */
    public static List<String> chipLabels(int n) {
        List<String> out = new ArrayList<String>();
        for (int i = 1; i <= n; i++) out.add("第" + i + "张");
        if (n >= 2) out.add("全部");
        out.add("取消");
        return out;
    }
}
