package com.onesay.assistant.v2.errand.compare;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix/shop-research（用户 11-03 15:53「用比价网取价」）：商品标题 → 版本标签（纯 Java，JVM 可测）。
 * <p>同一系列不同版本价差很大（巴塞利斯蛇 V3 有线 ≈180、X 极速版 ≈245、专业版 35K ≈440），比价必须按版本分组再比。
 * 规则只看标题里的版本词，不看价格：</p>
 * <ul>
 *   <li>代际：标题里的型号 V3 / V4 …（{@link #generation}）；和用户问的代际不一样的不进同一组（由调用方丢弃或单列「新一代」）；</li>
 *   <li>「X」+ 极速版 / HyperSpeed（或单独的「X 极速版」）→ {@code X 极速版}（例：「V3 X专业极速版」仍是 X 极速版，X 优先）；</li>
 *   <li>专业版 / Pro：带 35K / 35000 → {@code 专业版 35K}；带 30K / 30000 → {@code 专业版 30K}；都没写 → {@code 专业版}（30K 或 35K 未标）；</li>
 *   <li>不是专业版但带 35K → {@code 35K 有线}；写了有线 / 26K / 26000 → {@code 有线}；</li>
 *   <li>别的都没写 → 空串（「未标版本」，调用方单列，不和任何一组混）。</li>
 * </ul>
 * 另外两个标记：{@link #noAccessory}（「无配件」裸机版）、{@link #used}（二手 / 翻新 / 拆封）——单独标出，不和全新标配比。
 */
public final class ShopVariant {

    private ShopVariant() {}

    public static final String X_SPEED = "X 极速版";
    public static final String PRO_35K = "专业版 35K";
    public static final String PRO_30K = "专业版 30K";
    public static final String PRO = "专业版";
    public static final String WIRED_35K = "35K 有线";
    public static final String WIRED = "有线";

    private static final Pattern GEN = Pattern.compile("(?<![a-z0-9])v\\s*(\\d{1,2})(?![0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern X = Pattern.compile("(?<![a-z0-9])x(?![a-z0-9])\\s*(?:专业)?\\s*(?:极速|超速|hyper\\s*speed)|x\\s*极速|v\\d\\s*x(?![a-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRO_W = Pattern.compile("专业版|(?<![a-z])pro(?![a-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern K35 = Pattern.compile("35\\s*k(?![a-z])|35000|35,000", Pattern.CASE_INSENSITIVE);
    private static final Pattern K30 = Pattern.compile("30\\s*k(?![a-z])|30000|30,000", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIRED_W = Pattern.compile("有线|26\\s*k(?![a-z])|26000|26,000|wired", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_ACC = Pattern.compile("无配件|裸机|不含底座");
    private static final Pattern USED = Pattern.compile("二手|翻新|拆封|官翻|展示机|成色");

    /** 版本标签；看不出为空串。 */
    public static String of(String title) {
        if (title == null) return "";
        String t = title.toLowerCase(Locale.ROOT);
        if (X.matcher(t).find()) return X_SPEED;
        boolean pro = PRO_W.matcher(t).find();
        boolean k35 = K35.matcher(t).find();
        if (pro) {
            if (k35) return PRO_35K;
            if (K30.matcher(t).find()) return PRO_30K;
            return PRO;
        }
        if (k35) return WIRED_35K;
        if (WIRED_W.matcher(t).find()) return WIRED;
        return "";
    }

    /** 标题里的代际数字（V3 → 3）；没写为 -1。 */
    public static int generation(String title) {
        if (title == null) return -1;
        Matcher m = GEN.matcher(title);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    public static boolean noAccessory(String title) {
        return title != null && NO_ACC.matcher(title).find();
    }

    public static boolean used(String title) {
        return title != null && USED.matcher(title).find();
    }

    /** 两个标签算不算同一组：「专业版」（未标 30K/35K）只和自己一组，不并进 30K 或 35K。 */
    public static boolean same(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    /** 显示用：空串 → 「未标版本」。 */
    public static String label(String v) {
        return v == null || v.length() == 0 ? "未标版本" : v;
    }

    /** 分组排序：专业版 35K → 专业版 30K → 专业版 → X 极速版 → 35K 有线 → 有线 → 未标。 */
    public static int order(String v) {
        if (PRO_35K.equals(v)) return 0;
        if (PRO_30K.equals(v)) return 1;
        if (PRO.equals(v)) return 2;
        if (X_SPEED.equals(v)) return 3;
        if (WIRED_35K.equals(v)) return 4;
        if (WIRED.equals(v)) return 5;
        return 6;
    }

    /** 无线版本（推荐时「要无线」用）。 */
    public static boolean wireless(String v) {
        return PRO_35K.equals(v) || PRO_30K.equals(v) || PRO.equals(v) || X_SPEED.equals(v);
    }
}
