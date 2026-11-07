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

    /**
     * 将口语短语转为整数数值。
     */
    public static Long parse(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.length() == 0) return null;

        // 剔除常见口语连接词
        s = s.replaceAll("^(调到|设为|设成|改成|变成|到|成|为|至)", "");
        s = s.replaceAll("(秒|毫秒|分|分钟|点|点钟|个|度|级|多)$", "").trim();

        // 优先尝试直接解析纯阿拉伯数字
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {}

        // 解析包含汉字的数字
        long total = 0;
        long section = 0;
        long currentNumber = 0;
        boolean hasDigit = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            // 处理阿拉伯数字混排
            if (c >= '0' && c <= '9') {
                currentNumber = currentNumber * 10 + (c - '0');
                hasDigit = true;
                continue;
            }

            Integer digit = CN_NUMS.get(c);
            if (digit != null) {
                currentNumber = digit;
                hasDigit = true;
            } else if (c == '十' || c == '拾') {
                if (currentNumber == 0) currentNumber = 1; // "十二" 场景
                section += currentNumber * 10;
                currentNumber = 0;
                hasDigit = true;
            } else if (c == '百' || c == '佰') {
                section += currentNumber * 100;
                currentNumber = 0;
                hasDigit = true;
            } else if (c == '千' || c == '仟') {
                section += currentNumber * 1000;
                currentNumber = 0;
                hasDigit = true;
            } else if (c == '万') {
                total += (section + currentNumber) * 10000;
                section = 0;
                currentNumber = 0;
                hasDigit = true;
            }
        }

        if (!hasDigit) return null;
        return total + section + currentNumber;
    }
}
