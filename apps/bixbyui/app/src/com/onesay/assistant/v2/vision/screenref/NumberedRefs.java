package com.onesay.assistant.v2.vision.screenref;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ lane C3-5：「识屏结果加编号，用户说『第三个』来指代」（纯 Java，JVM 可测）。
 *
 * <ul>
 *   <li>{@link #label}：给列表项加圆圈编号（①②③…），识屏芯片、歧义候选都用它；</li>
 *   <li>{@link #pick}：整句只是在点编号（「第三个」「选第 2 个」「3 号」「就最后一个吧」「打开第三个」）→ 0 起下标；
 *       超出范围 → {@link #OUT_OF_RANGE}；不是这种说法 → {@link #NONE}。带了别的内容（「第三个地址导航过去」「第三季度」）一律不算；</li>
 *   <li>{@link #publish} / {@link #isLatest}：谁最后把编号列表摆到用户面前（识屏芯片 / 跑腿来源），「第三个」就归谁。</li>
 * </ul>
 */
public final class NumberedRefs {

    private NumberedRefs() {}

    public static final int NONE = -1;
    public static final int OUT_OF_RANGE = -2;
    public static final int MAX = 20;

    public static final String OWNER_SCREEN = "screenref";
    public static final String OWNER_ERRAND = "errand";

    private static final String CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳";

    /** 0 起下标 → 「① 文案」。 */
    public static String label(int index, String text) {
        String n = index >= 0 && index < CIRCLED.length() ? String.valueOf(CIRCLED.charAt(index)) : (index + 1) + ".";
        return n + " " + (text == null ? "" : text);
    }

    private static final String NUM = "([一二两三四五六七八九十]{1,3}|\\d{1,2})";
    private static final Pattern PICK = Pattern.compile(
            "^(?:请|麻烦)?(?:帮我)?(?:就|那就|我要|要|选|选择|用|点|点击|点一下|打开|执行|看|看看|就选|就要)?"
                    + "(?:"
                    + "第" + NUM + "(?:个|条|项|处|行|篇|家|张|号|项结果|个结果|个选项|个链接|个来源)?"
                    + "|" + NUM + "号"
                    + "|(最后一?(?:个|条|项)|最后那个|最下面那个)"
                    + ")"
                    + "(?:那个|这个|的)?(?:吧|呢|啊|呀|好了|就行|就好)?$");

    /**
     * @param utterance 用户原话（ASR 文本，标点 / 空格无所谓）
     * @param size      当前编号列表的长度
     * @return 0 起下标；{@link #OUT_OF_RANGE}；{@link #NONE}
     */
    public static int pick(String utterance, int size) {
        if (utterance == null || size <= 0) return NONE;
        String u = utterance.replaceAll("[\\s，,。.！!？?、~～…]+", "");
        if (u.length() == 0 || u.length() > 16) return NONE;
        Matcher m = PICK.matcher(u);
        if (!m.matches()) return NONE;
        if (m.group(3) != null) return size - 1;
        String d = m.group(1) != null ? m.group(1) : m.group(2);
        int n = DateTimeNormalizer.cnOrInt(d);
        if (n <= 0 || n > MAX) return NONE;
        return n <= size ? n - 1 : OUT_OF_RANGE;
    }

    // ------------------------------------------------------------------ 最近一次摆出来的编号列表

    private static String sOwner;
    private static int sCount;
    private static long sAt;
    private static long sTtl;

    /** 有编号列表摆到了用户面前（ttlMs 内有效）。 */
    public static synchronized void publish(String owner, int count, long nowMs, long ttlMs) {
        sOwner = owner;
        sCount = count;
        sAt = nowMs;
        sTtl = ttlMs;
    }

    /** 列表撤下（芯片隐藏且不是被「第 N 个」用掉的）。只撤自己的。 */
    public static synchronized void withdraw(String owner) {
        if (owner != null && owner.equals(sOwner)) {
            sOwner = null;
            sCount = 0;
        }
    }

    public static synchronized boolean isLatest(String owner, long nowMs) {
        return owner != null && owner.equals(sOwner) && sCount > 0 && nowMs - sAt <= sTtl;
    }

    public static synchronized int count() {
        return sCount;
    }

    /** 测试用。 */
    public static synchronized void reset() {
        sOwner = null;
        sCount = 0;
        sAt = 0;
        sTtl = 0;
    }
}
