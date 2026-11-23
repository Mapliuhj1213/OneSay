package com.onesay.assistant.v2.router;

import java.util.HashMap;
import java.util.Map;

/**
 * 口语数字与中文数值解析器（解决 v1 BUG-025 与 BUG-026）。
 *
 * 支持：
 * 1. 汉字大写与小写数字转换（"八" -> 8, "三十" -> 30, "一百二十" -> 120）；
 * 2. 剥离连接动词与量词（"调到50"、"设成30秒"、"设为80"）；
 * 3. 自动规整百分比数值与离散档位。
 */
public class NumberParser {

    private static final Map<Character, Integer> CN_NUMS = new HashMap<Character, Integer>();
    static {
        CN_NUMS.put('零', 0); CN_NUMS.put('〇', 0);
        CN_NUMS.put('一', 1); CN_NUMS.put('壹', 1);
        CN_NUMS.put('二', 2); CN_NUMS.put('两', 2); CN_NUMS.put('贰', 2);
        CN_NUMS.put('三', 3); CN_NUMS.put('叁', 3);
        CN_NUMS.put('四', 4); CN_NUMS.put('肆', 4);
        CN_NUMS.put('五', 5); CN_NUMS.put('伍', 5);
        CN_NUMS.put('六', 6); CN_NUMS.put('陆', 6);
        CN_NUMS.put('七', 7); CN_NUMS.put('柒', 7);
        CN_NUMS.put('八', 8); CN_NUMS.put('捌', 8);
        CN_NUMS.put('九', 9); CN_NUMS.put('玖', 9);
    }

    /** 上限：超过即视为不合法（防溢出，口语里也不会出现万亿以上的数）。 */
    static final long MAX = 1000000000000L;

    private static final String CN_DIGITS = "零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖";

    /**
     * 将口语短语转为整数数值。
     *
     * <p>★ rel-1118 B07：改成严格解析 —— 只认数字 / 中文数字 / 单位（十百千万亿），夹了别的字（「一半」「1分30」「二十几」）一律 null，
     * 不再逐字累加；「一百二 / 二百五 / 三千五 / 三万五」按口语省略补位；「一亿」「百」「千」「万」；「一二三」逐位读；
     * 「三四」「五六十」这类约数、「十十」这类乱序、小数（「1.5」「一点五」）、超过 {@link #MAX} 都返回 null；
     * 「-5 / 负五」返回负数，由调用方决定拒不拒（音量 / 倒计时都拒）。
     */
    public static Long parse(String text) {
        if (text == null) return null;
        if (text.matches(".*[\\u2460-\\u24ff\\u2150-\\u218f\\u3220-\\u3243\\u3280-\\u32b0].*")) return null;   // ⑤ Ⅴ ㈤ 不是口语数字
        String s = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).replaceAll("[\\u200b-\\u200f\\u2060\\ufeff]", "").trim();
        if (s.length() == 0) return null;

        // 剔除常见口语连接词
        s = s.replaceAll("^(调到|设为|设成|改成|变成|到|成|为|至)", "");
        s = s.replaceAll("(秒|毫秒|分|分钟|点|点钟|个|度|级|多)$", "").trim();
        if (s.length() == 0) return null;

        boolean neg = false;
        if (s.startsWith("-") || s.startsWith("负") || s.startsWith("\u2212")) {
            neg = true;
            s = s.substring(1).trim();
            if (s.length() == 0) return null;
        }
        Long v;
        if (s.matches("[0-9]+")) {
            if (s.length() > 13) return null;
            v = Long.parseLong(s);
        } else if (s.matches("[0-9" + CN_DIGITS + "十拾百佰千仟万亿]+")) {
            v = parseCn(s);
        } else {
            return null;
        }
        if (v == null || v > MAX) return null;
        return neg ? -v : v;
    }

    /** 句子里槽位两边常见的口头填充（「喂，7点」「5哈」「5，现在」「50％」），只在这张表里的才剥。 */
    private static final java.util.regex.Pattern LOOSE_HEAD = java.util.regex.Pattern.compile(
            "^(?:喂|ok|好|那个|唔该|嘿|哎|嗯|现在|快|马上|俺要|我要|我想|劳驾|你给我|给我|麻烦|帮我|请|调到|调成|调为|设成|设为|设置成|设置为|改成|改为|开到|放到|\\p{So}|\\p{P}|\\s)+");
    private static final java.util.regex.Pattern LOOSE_TAIL = java.util.regex.Pattern.compile(
            "(?:现在|快点|马上|立刻|赶紧|哈|咯|嘛|啦|呗|吧|呀|啊|哦|噢|喔|嗯|了|好吗|可以吗|谢谢|%|％|\\p{So}|\\p{P}|\\s)+$");

    /**
     * ★ rel-1118 B07：给 L0 槽位用 —— 先剥掉两边白名单里的口头填充，再走严格的 {@link #parse}。
     * 「跟刚才一样」这类不在白名单里的字不剥，照旧 null。
     */
    public static Long parseLoose(String text) {
        Long v = parse(text);
        if (v != null || text == null) return v;
        String s = text.trim();
        String t = LOOSE_TAIL.matcher(LOOSE_HEAD.matcher(s.toLowerCase(java.util.Locale.ROOT)).replaceFirst("")).replaceFirst("");
        if (t.length() == 0 || t.equals(s)) return null;
        return parse(t);
    }

    private static int unitOf(char c) {
        switch (c) {
            case '十': case '拾': return 10;
            case '百': case '佰': return 100;
            case '千': case '仟': return 1000;
            default: return 0;
        }
    }

    private static int digitOf(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        Integer d = CN_NUMS.get(c);
        return d == null ? -1 : d;
    }

    private static Long parseCn(String s) {
        boolean hasUnit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (unitOf(c) > 0 || c == '万' || c == '亿') { hasUnit = true; break; }
        }
        if (!hasUnit) {
            // 纯数字串：「零五」= 5、「一二三」= 123；两位相邻数（「三四」「五六」）是约数 → null
            if (s.length() == 1) return (long) digitOf(s.charAt(0));
            if (s.length() == 2) {
                int a = digitOf(s.charAt(0)), b = digitOf(s.charAt(1));
                if (a == 0) return (long) b;
                if (Math.abs(a - b) == 1) return null;
            }
            long v = 0;
            for (int i = 0; i < s.length(); i++) {
                v = v * 10 + digitOf(s.charAt(i));
                if (v > MAX) return null;
            }
            return v;
        }
        long yi = -1, wan = -1, section = 0, cur = 0;
        int lastUnit = 0;            // 本段里上一个单位（十百千），必须严格递减
        boolean prevDigit = false, prevArabic = false, zero = false, any = false;
        char prevC = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d = digitOf(c);
            if (d >= 0) {
                boolean arabic = c >= '0' && c <= '9';
                if (prevDigit && !(arabic && prevArabic)) {
                    if (!(prevC == '零' || prevC == '〇')) return null;   // 「五六十」= 约数；「零五」除外
                }
                cur = arabic && prevArabic ? cur * 10 + d : d;
                if (d == 0 && !arabic) zero = true;
                prevDigit = true;
                prevArabic = arabic;
            } else {
                int u = unitOf(c);
                if (u > 0) {
                    if (lastUnit != 0 && u >= lastUnit) return null;          // 「十十」「十百」
                    if (!prevDigit) {
                        if (any && !(prevC == '万' || prevC == '亿')) return null;
                        cur = 1;                                              // 「十 / 百 / 千」开头
                    }
                    section += cur * u;
                    lastUnit = u;
                } else {                                                      // 万 / 亿
                    long sec = section + cur;
                    if (sec == 0 && !any) sec = 1;                            // 「万」「亿」单独出现
                    if (c == '万') {
                        if (wan >= 0) return null;
                        wan = sec * 10000L;
                    } else {
                        if (yi >= 0) return null;
                        long w = (wan < 0 ? 0 : wan) + sec;
                        if (w > MAX / 100000000L) return null;
                        yi = w * 100000000L;
                        wan = -1;
                    }
                    section = 0;
                    lastUnit = 0;
                }
                cur = 0;
                prevDigit = false;
                prevArabic = false;
                zero = false;
            }
            any = true;
            prevC = c;
            if (section > MAX) return null;
        }
        // 口语省略：「一百二」= 120、「三千五」= 3500、「三万五」= 35000（末尾单个数字直接跟在单位后、中间没有「零」）
        if (prevDigit && cur > 0 && cur < 10 && !zero && s.length() >= 2) {
            char beforeLast = s.charAt(s.length() - 2);
            int u = unitOf(beforeLast);
            if (u >= 100) cur *= u / 10;
            else if (beforeLast == '万') cur *= 1000;
            else if (beforeLast == '亿') cur *= 10000000L;
        }
        long total = (yi < 0 ? 0 : yi) + (wan < 0 ? 0 : wan) + section + cur;
        return total > MAX ? null : total;
    }
}
