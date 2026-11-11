package com.onesay.assistant.v2.ui.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ ui-K1 对话窗回答的简单 Markdown 简析（纯 Java，JVM 单测）：粗体（**x** / __x__）、圆点列表（- / * / • / 1.）、
 * 标题行（# → 粗体）、行内代码（去反引号）、多余空行压成一行空行。流式中途没配对的 ** 不显示星号。
 *
 * <p>输出纯文本 + 区间（[start, end)），由 Android 侧挂 StyleSpan / BulletSpan；这里不碰 android.text。
 */
public final class ChatMarkdown {

    private ChatMarkdown() {}

    public static final class Doc {
        public final String text;
        /** 粗体区间（含标题行）。 */
        public final List<int[]> bold;
        /** 圆点列表项（每项一行的区间，不含行尾换行）。 */
        public final List<int[]> bullets;
        /** 标题行区间（Android 侧可稍放大）。 */
        public final List<int[]> headings;

        /** ★ feat/api-first：可点追问短语区间（含 ↵）与对应要发送的文字（同下标）。 */
        public final List<int[]> followups;
        public final List<String> followupText;

        Doc(String text, List<int[]> bold, List<int[]> bullets, List<int[]> headings) {
            this(text, bold, bullets, headings, new ArrayList<int[]>(), new ArrayList<String>());
        }

        Doc(String text, List<int[]> bold, List<int[]> bullets, List<int[]> headings, List<int[]> followups, List<String> followupText) {
            this.text = text;
            this.bold = bold;
            this.bullets = bullets;
            this.headings = headings;
            this.followups = followups;
            this.followupText = followupText;
        }
    }

    public static Doc parse(String md) {
        List<int[]> bold = new ArrayList<int[]>();
        List<int[]> bullets = new ArrayList<int[]>();
        List<int[]> heads = new ArrayList<int[]>();
        StringBuilder out = new StringBuilder();
        if (md == null) return new Doc("", bold, bullets, heads);
        String src = md.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = src.split("\n", -1);
        boolean lastBlank = true;   // 开头的空行全丢
        for (String raw : lines) {
            String line = rtrim(raw);
            String t = line.trim();
            if (t.length() == 0) {
                if (!lastBlank) {
                    out.append('\n');
                    lastBlank = true;
                }
                continue;
            }
            if (t.matches("[-*_]{3,}")) continue;   // 分隔线
            boolean bullet = false, heading = false;
            String body = t;
            if (t.matches("^#{1,6}\\s+.*")) {
                heading = true;
                body = t.replaceFirst("^#{1,6}\\s+", "");
            } else if (t.matches("^[-*•·]\\s+.*")) {
                bullet = true;
                body = t.replaceFirst("^[-*•·]\\s+", "");
            } else if (t.matches("^\\d{1,3}[.、)]\\s*.+")) {
                bullet = false;   // 编号列表保留编号（「1. 」本身就是序号），不再加圆点
                body = t;
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
            int lineStart = out.length();
            appendInline(body, out, bold);
            int lineEnd = out.length();
            if (bullet) bullets.add(new int[]{lineStart, lineEnd});
            if (heading) {
                heads.add(new int[]{lineStart, lineEnd});
                bold.add(new int[]{lineStart, lineEnd});
            }
            out.append('\n');
            lastBlank = false;
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.setLength(out.length() - 1);
        int n = out.length();
        clip(bold, n);
        clip(bullets, n);
        clip(heads, n);
        String text = out.toString();
        if (!com.onesay.assistant.v2.util.FollowupMarks.has(text)) return new Doc(text, bold, bullets, heads);
        // ★ feat/api-first：[[追问短语]] → 「短语↵」（可点），已算好的区间跟着挪
        com.onesay.assistant.v2.util.FollowupMarks.Rewrite rw = com.onesay.assistant.v2.util.FollowupMarks.rewrite(text);
        remap(bold, rw.map);
        remap(bullets, rw.map);
        remap(heads, rw.map);
        return new Doc(rw.text, bold, bullets, heads, rw.ranges, rw.phrases);
    }

    private static void remap(List<int[]> l, int[] map) {
        for (int k = l.size() - 1; k >= 0; k--) {
            int[] r = l.get(k);
            r[0] = map[Math.min(r[0], map.length - 1)];
            r[1] = map[Math.min(r[1], map.length - 1)];
            if (r[0] >= r[1]) l.remove(k);
        }
    }

    /** 行内：**粗体** / __粗体__ / `代码`；没配对的标记（流式中途）不显示。 */
    static void appendInline(String s, StringBuilder out, List<int[]> bold) {
        int i = 0, n = s.length();
        while (i < n) {
            if (i + 1 < n && ((s.charAt(i) == '*' && s.charAt(i + 1) == '*') || (s.charAt(i) == '_' && s.charAt(i + 1) == '_'))) {
                String mark = s.substring(i, i + 2);
                int close = s.indexOf(mark, i + 2);
                if (close > i + 2) {
                    int b0 = out.length();
                    appendCode(s.substring(i + 2, close), out);
                    bold.add(new int[]{b0, out.length()});
                    i = close + 2;
                    continue;
                }
                if (close < 0) {   // 没配对：流式中途 → 星号不显示，后面照常
                    i += 2;
                    continue;
                }
                i += 2;   // 「****」空粗体
                continue;
            }
            char c = s.charAt(i);
            if (c == '`') {
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
    }

    private static void appendCode(String s, StringBuilder out) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '`') out.append(c);
        }
    }

    private static String rtrim(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
        return s.substring(0, e);
    }

    private static void clip(List<int[]> l, int n) {
        for (int k = l.size() - 1; k >= 0; k--) {
            int[] r = l.get(k);
            if (r[1] > n) r[1] = n;
            if (r[0] >= r[1]) l.remove(k);
        }
    }

    /** 纯文字（复制 / 分享 / 朗读用）：Markdown 标记去掉、保留换行与圆点。 */
    public static String plain(String md) {
        Doc d = parse(com.onesay.assistant.v2.util.FollowupMarks.plain(md));   // ★ feat/api-first：复制 / 朗读不带 [[ ]] 和 ↵
        if (d.bullets.isEmpty()) return d.text;
        StringBuilder sb = new StringBuilder(d.text);
        for (int k = d.bullets.size() - 1; k >= 0; k--) sb.insert(d.bullets.get(k)[0], "• ");
        return sb.toString();
    }
}
