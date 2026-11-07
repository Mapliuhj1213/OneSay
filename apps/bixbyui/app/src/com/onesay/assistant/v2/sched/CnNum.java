package com.onesay.assistant.v2.sched;

/** [sched] 中文 / 阿拉伯数字 → int（0–9999 够用：钟点、日期、年份、分钟）。认不出返回 -1。纯 Java。 */
public final class CnNum {

    private CnNum() {}

    public static int parse(String s) {
        if (s == null) return -1;
        String t = s.trim();
        if (t.length() == 0) return -1;
        boolean allDigit = true;
        for (int i = 0; i < t.length(); i++) if (!Character.isDigit(t.charAt(i))) allDigit = false;
        if (allDigit) {
            try {
                return t.length() > 6 ? -1 : Integer.parseInt(t);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        // 「二〇二六」「二零二七」这种逐位读的年份
        if (t.length() == 4 && t.indexOf('十') < 0 && t.indexOf('百') < 0 && t.indexOf('千') < 0) {
            int v = 0;
            for (int i = 0; i < 4; i++) {
                int d = digit(t.charAt(i));
                if (d < 0) return -1;
                v = v * 10 + d;
            }
            return v;
        }
        int total = 0, cur = -1;
        boolean any = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            int d = digit(c);
            if (d >= 0) {
                cur = d;
                any = true;
                continue;
            }
            if (c == '十') {
                total += (cur < 0 ? 1 : cur) * 10;
                cur = -1;
                any = true;
            } else if (c == '廿') {
                total += 20;
                cur = -1;
                any = true;
            } else if (c == '卅') {
                total += 30;
                cur = -1;
                any = true;
            } else if (c == '百') {
                total += (cur < 0 ? 1 : cur) * 100;
                cur = -1;
                any = true;
            } else if (c == '千') {
                total += (cur < 0 ? 1 : cur) * 1000;
                cur = -1;
                any = true;
            } else {
                return -1;
            }
        }
        if (!any) return -1;
        if (cur >= 0) total += cur;
        return total;
    }

    static int digit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        switch (c) {
            case '零': case '〇': return 0;
            case '一': case '壹': return 1;
            case '二': case '两': case '贰': return 2;
            case '三': case '叁': return 3;
            case '四': case '肆': return 4;
            case '五': case '伍': return 5;
            case '六': case '陆': return 6;
            case '七': case '柒': return 7;
            case '八': case '捌': return 8;
            case '九': case '玖': return 9;
            default: return -1;
        }
    }
}
