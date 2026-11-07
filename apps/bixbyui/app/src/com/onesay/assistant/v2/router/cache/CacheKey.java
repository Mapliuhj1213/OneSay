package com.onesay.assistant.v2.router.cache;

import com.onesay.assistant.v2.router.TriageDecider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 缓存键规范化管线（，写入与查询**共用同一个函数**）。
 *
 * <p>旧 L1 的问题：写入只存 {@code normalize(原句)} 一种形式，查询却展开
 * {@code generateSpokenVariants} 多个变体做精确比较（写/查不对称），且不归一数字、
 * 不剥唤醒词、"三十"≠"30"。这里只产出<b>一个确定的规范形</b>：
 * <ol>
 *   <li>小写、去空白标点、剥唤醒词残留（复用 {@link TriageDecider#normalize}）；</li>
 *   <li>剥礼貌框架（请/帮我/麻烦/能不能… ；…一下/吧/呢/吗），去掉句中"一下"；</li>
 *   <li>中文数字归一为阿拉伯数字（"三十"→30，"百分之五十"→50%；"一点/一下/一个"等量词搭配不动）；</li>
 *   <li>数字抽成槽：{@link #shape} 把每个数字替换为 {n}，{@link #numbers} 记录取值。</li>
 * </ol>
 * 纯 Java，可 JVM 单测。
 */
public final class CacheKey {

    /** 规范化后的字面串（数字为阿拉伯数字）。ASK 组用它做精确键。 */
    public final String literal;
    /** 数字抽槽后的形状串（"亮度调到{n}"）。DO 组用它做键。 */
    public final String shape;
    /** 按出现顺序的数字取值（字符串形式，保留小数）。 */
    public final List<String> numbers;

    private CacheKey(String literal, String shape, List<String> numbers) {
        this.literal = literal;
        this.shape = shape;
        this.numbers = numbers;
    }

    public static final String NUM_SLOT = "{n}";
    private static final Pattern NUM = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");

    private static final String[] PREFIX = {
            "你能不能帮我", "能不能帮我", "可不可以帮我", "可以帮我", "你能帮我", "你可以帮我", "能帮我",
            "麻烦你帮我", "麻烦帮我", "请你帮我", "请帮我",
            "能不能", "可不可以", "能否", "你能", "你可以", "麻烦你", "麻烦", "请你", "请",
            "帮我", "帮忙", "给我", "替我", "快点", "快",
    };
    private static final String[] SUFFIX = {
            "好不好", "可以吗", "行不行", "好吗", "行吗", "一下吧", "一下", "吧", "呗", "呀", "啊", "哦", "呢", "吗", "啦",
    };

    public static CacheKey of(String utterance) {
        String q = TriageDecider.normalize(utterance);
        q = toHalfWidth(q);
        q = stripFrames(q);
        q = q.replace("一下", "");
        q = cnNumbersToArabic(q);
        List<String> nums = new ArrayList<String>();
        Matcher m = NUM.matcher(q);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            nums.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(NUM_SLOT));
        }
        m.appendTail(sb);
        return new CacheKey(q, sb.toString(), nums);
    }

    static String stripFrames(String q) {
        String cur = q;
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 8) {
            changed = false;
            for (String p : PREFIX) {
                if (cur.startsWith(p) && cur.length() > p.length() + 1) {
                    cur = cur.substring(p.length());
                    changed = true;
                    break;
                }
            }
            for (String s : SUFFIX) {
                if (cur.endsWith(s) && cur.length() > s.length() + 1) {
                    cur = cur.substring(0, cur.length() - s.length());
                    changed = true;
                    break;
                }
            }
            if (cur.startsWith("把") && cur.length() > 2) {
                cur = cur.substring(1);
                changed = true;
            }
        }
        return cur;
    }

    private static String toHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\uFF10' && c <= '\uFF19') c = (char) ('0' + (c - '\uFF10'));
            else if (c >= '\uFF41' && c <= '\uFF5A') c = (char) ('a' + (c - '\uFF41'));
            sb.append(c);
        }
        return sb.toString();
    }

    // ---------------- 中文数字 ----------------

    private static final String CN_DIGITS = "零〇一二两三四五六七八九十百千万";

    /** 不当数字处理的搭配（"一点"=一些，"一下"、"一个"…）。 */
    private static final String[] PROTECT_AFTER_YI = {
            "点儿", "下", "个", "些", "会", "首", "张", "封", "部", "起", "样", "直", "般", "共", "切", "边", "半天",
    };

    static String cnNumbersToArabic(String s) {
        s = s.replaceAll("百分之([0-9]+)", "$1%");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (CN_DIGITS.indexOf(c) < 0) {
                out.append(c);
                i++;
                continue;
            }
            int j = i;
            while (j < s.length() && CN_DIGITS.indexOf(s.charAt(j)) >= 0) j++;
            String run = s.substring(i, j);
            String after = s.substring(j);
            String before = out.toString();
            if (shouldConvert(run, before, after)) {
                if (run.startsWith("百") && after.startsWith("分之")) {
                    // "百分之三十" → 先保留"百分之"，后面的数字照常转换，再整体改写成 %
                    out.append(run);
                } else {
                    long v = parseCn(run);
                    if (v >= 0) out.append(v); else out.append(run);
                }
            } else {
                out.append(run);
            }
            i = j;
        }
        String r = out.toString();
        return r.replaceAll("百分之([0-9]+)", "$1%");
    }

    private static boolean shouldConvert(String run, String before, String after) {
        if (run.equals("一") || run.equals("两")) {
            for (String p : PROTECT_AFTER_YI) if (after.startsWith(p)) return false;
            // "一点"：后面是 钟/半/零/数字/分 时才是钟点，否则是"一些"（"两点"一律是钟点）
            if (run.equals("一") && after.startsWith("点")) {
                String a2 = after.length() > 1 ? after.substring(1) : "";
                return a2.startsWith("钟") || a2.startsWith("半") || a2.startsWith("零")
                        || a2.startsWith("分") || (a2.length() > 0 && (Character.isDigit(a2.charAt(0))
                        || CN_DIGITS.indexOf(a2.charAt(0)) >= 0));
            }
            if (before.endsWith("统") || before.endsWith("万") || before.endsWith("唯")
                    || before.endsWith("星期") || before.endsWith("周") || before.endsWith("第")) return false;
        }
        if (run.equals("万") || run.equals("千") || run.equals("百")) {
            return after.startsWith("分之");
        }
        return true;
    }

    /** 解析中文数字串（十/二十五/一百二十/两/三千零五）；失败返回 -1。 */
    static long parseCn(String run) {
        long total = 0, section = 0, number = 0;
        boolean any = false;
        for (int k = 0; k < run.length(); k++) {
            char c = run.charAt(k);
            int d = digit(c);
            if (d >= 0) {
                number = d;
                any = true;
                continue;
            }
            int unit;
            switch (c) {
                case '十': unit = 10; break;
                case '百': unit = 100; break;
                case '千': unit = 1000; break;
                case '万': unit = 10000; break;
                default: return -1;
            }
            if (unit == 10000) {
                section = (section + number) * unit;
                total += section;
                section = 0;
            } else {
                if (number == 0 && unit == 10) number = 1;   // "十五" = 15
                section += number * unit;
            }
            number = 0;
            any = true;
        }
        if (!any) return -1;
        return total + section + number;
    }

    private static int digit(char c) {
        switch (c) {
            case '零': case '〇': return 0;
            case '一': return 1;
            case '二': case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return -1;
        }
    }
}
