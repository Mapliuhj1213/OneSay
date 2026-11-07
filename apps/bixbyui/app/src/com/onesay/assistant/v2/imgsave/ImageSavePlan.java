package com.onesay.assistant.v2.imgsave;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [imgsave] 存图的「决定」与「文案」（纯 Java，JVM 可测）：
 * 触发命中 + 识别到的图片数 → 直接存哪几张 / 出编号芯片 / 退回手动框选；存完的回执文字；输出路径与文件名。
 */
public final class ImageSavePlan {

    private ImageSavePlan() {}

    public enum Kind { SAVE, CHOOSE, MANUAL, OUT_OF_RANGE }

    public static final class Step {
        public final Kind kind;
        /** SAVE 时要存的序号（1 起）。 */
        public final List<Integer> picks;

        Step(Kind k, List<Integer> picks) {
            this.kind = k;
            this.picks = picks;
        }

        @Override public String toString() {
            return kind + (picks.isEmpty() ? "" : " " + picks);
        }
    }

    /**
     * @param hit   触发命中（all / ordinal）
     * @param count 识别到的图片数（节点或像素检测）
     */
    public static Step decide(ImageSaveIntent.Hit hit, int count) {
        List<Integer> picks = new ArrayList<Integer>();
        if (count <= 0) return new Step(Kind.MANUAL, picks);
        if (hit != null && hit.ordinal > 0) {
            if (hit.ordinal > count) return new Step(Kind.OUT_OF_RANGE, picks);
            picks.add(hit.ordinal);
            return new Step(Kind.SAVE, picks);
        }
        if (count == 1 || (hit != null && hit.all)) {
            for (int i = 1; i <= count; i++) picks.add(i);
            return new Step(Kind.SAVE, picks);
        }
        return new Step(Kind.CHOOSE, picks);
    }

    /** 每张图的来源层级。 */
    public enum Tier { ORIGINAL, CROP }

    /** 回执：「已保存 1 张（原图）」「已保存 2 张（截图裁剪）」「已保存 3 张（1 张原图、2 张截图裁剪）」。 */
    public static String summary(List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) return "没有保存任何图片";
        int orig = 0, crop = 0;
        for (Tier t : tiers) {
            if (t == Tier.ORIGINAL) orig++;
            else crop++;
        }
        String detail;
        if (crop == 0) detail = "原图";
        else if (orig == 0) detail = "截图裁剪";
        else detail = orig + " 张原图、" + crop + " 张截图裁剪";
        return "已保存 " + tiers.size() + " 张（" + detail + "）";
    }

    /** 多图时面板上的提问。 */
    public static String chooseQuestion(int n) {
        return "屏幕上有 " + n + " 张图。点编号或说「第几张」，说「全部」都存。";
    }

    /** 包名里只留安全字符（目录名）。 */
    public static String safeDir(String pkg) {
        if (pkg == null || pkg.length() == 0) return "unknown";
        String s = pkg.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    /** 相对 Pictures 的目录：{@code OneSay/<包名>}。 */
    public static String relativeDir(String pkg) {
        return com.onesay.assistant.v2.BuildVariant.PUBLIC_DIR + "/" + safeDir(pkg);
    }

    /** 文件名：{@code OneSay_20261008_190512_1.png}；原图带 {@code _orig} 且保留原扩展名。 */
    public static String fileName(String stamp, int index, Tier tier, String ext) {
        return String.format(Locale.ROOT, "OneSay_%s_%d%s.%s", stamp, index, tier == Tier.ORIGINAL ? "_orig" : "",
                ext == null || ext.length() == 0 ? "png" : ext);
    }
}
