package com.onesay.assistant.v2.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ feat/api-first（2026-11-11）：回答里的「可点追问短语」。纯 Java，JVM 可测。
 *
 * <p>约定：模型在回答正文里用 {@code [[短语]]} 标出 2–3 个用户最可能接着说的话（例：{@code [[订18:11那趟]]}），
 * 屏幕上渲染成蓝色可点文字带 ↵，点一下等于用户发送这句；朗读、复制、纯文本场景一律去掉标记只留文字。
 * 思路借鉴魅族 Aicy 的文中追问短语（闭源，只借思路）。
 *
 * <p>短语限制：1–30 个字、不含换行和方括号；超出的不算标记、原样保留。
 */
public final class FollowupMarks {

    private FollowupMarks() {}

    public static final String ENTER = "↵";
    /** 屏上最多几个可点短语（多出的照样去掉标记显示成普通文字）。 */
    public static final int MAX = 3;
    public static final int MAX_LEN = 30;

    static final Pattern MARK = Pattern.compile("\\[\\[([^\\[\\]\\n\\r]{1," + MAX_LEN + "})\\]\\]");
    /** 流式中途还没写完的开头（「[[订18」「[」）。 */
    static final Pattern DANGLING = Pattern.compile("\\[\\[?[^\\[\\]\\n\\r]{0," + MAX_LEN + "}$");

    /** 一段：普通文字或可点短语。 */
    public static final class Seg {
        public final String text;
        public final boolean chip;

        Seg(String text, boolean chip) {
            this.text = text;
            this.chip = chip;
        }

        @Override public String toString() {
            return chip ? "[" + text + "]" : text;
        }
    }

    public static boolean has(String s) {
        return s != null && s.indexOf("[[") >= 0 && MARK.matcher(s).find();
    }

    /** 切段（前 {@link #MAX} 个是可点短语，其余变普通文字）。 */
    public static List<Seg> parse(String s) {
        List<Seg> out = new ArrayList<Seg>();
        if (s == null || s.length() == 0) return out;
        Matcher m = MARK.matcher(s);
        int at = 0, n = 0;
        StringBuilder plain = new StringBuilder();
        while (m.find()) {
            plain.append(s, at, m.start());
            String phrase = m.group(1).trim();
            if (phrase.length() == 0) {
                at = m.end();
                continue;
            }
            if (n < MAX) {
                if (plain.length() > 0) out.add(new Seg(plain.toString(), false));
                plain.setLength(0);
                out.add(new Seg(phrase, true));
                n++;
            } else {
                plain.append(phrase);
            }
            at = m.end();
        }
        plain.append(s.substring(at));
        if (plain.length() > 0) out.add(new Seg(plain.toString(), false));
        return out;
    }

    /** 可点短语（去重，最多 {@link #MAX} 个）。 */
    public static List<String> chips(String s) {
        List<String> out = new ArrayList<String>();
        for (Seg g : parse(s)) if (g.chip && !out.contains(g.text)) out.add(g.text);
        return out;
    }

    /** 纯文本 / 朗读 / 复制：去掉标记只留文字（「[[订18:11那趟]]」→「订18:11那趟」）。 */
    public static String plain(String s) {
        if (s == null || s.indexOf("[[") < 0) return s;
        Matcher m = MARK.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1).trim()));
        m.appendTail(sb);
        return sb.toString();
    }

    /** 屏上显示用（不可点的场景）：短语后面带 ↵。 */
    public static String display(String s) {
        if (s == null || s.indexOf("[[") < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (Seg g : parse(s)) sb.append(g.chip ? g.text + ENTER : g.text);
        return sb.toString();
    }

    /** {@link #rewrite} 的结果：新文字、旧下标 → 新下标、可点短语的区间与文字。 */
    public static final class Rewrite {
        public final String text;
        /** map[i] = 旧文字第 i 个字符在新文字里的位置（长度 = 旧长度 + 1）。 */
        public final int[] map;
        public final List<int[]> ranges;
        public final List<String> phrases;

        Rewrite(String text, int[] map, List<int[]> ranges, List<String> phrases) {
            this.text = text;
            this.map = map;
            this.ranges = ranges;
            this.phrases = phrases;
        }
    }

    /**
     * 把 [[短语]] 改写成「短语↵」（前 {@link #MAX} 个；其余只留文字），同时给出旧 → 新下标映射，
     * 让已经算好的粗体 / 列表区间（ChatMarkdown）跟着挪。区间 [start, end) 含 ↵。
     */
    public static Rewrite rewrite(String s) {
        String src = s == null ? "" : s;
        int[] map = new int[src.length() + 1];
        List<int[]> ranges = new ArrayList<int[]>();
        List<String> phrases = new ArrayList<String>();
        StringBuilder out = new StringBuilder();
        Matcher m = MARK.matcher(src);
        int at = 0, n = 0;
        while (m.find()) {
            for (int i = at; i < m.start(); i++) {
                map[i] = out.length();
                out.append(src.charAt(i));
            }
            String phrase = m.group(1).trim();
            int start = out.length();
            for (int i = m.start(); i < m.end(); i++) map[i] = start;
            if (phrase.length() > 0) {
                out.append(phrase);
                if (n < MAX) {
                    out.append(ENTER);
                    ranges.add(new int[]{start, out.length()});
                    phrases.add(phrase);
                    n++;
                }
            }
            at = m.end();
        }
        for (int i = at; i < src.length(); i++) {
            map[i] = out.length();
            out.append(src.charAt(i));
        }
        map[src.length()] = out.length();
        return new Rewrite(out.toString(), map, ranges, phrases);
    }

    /** 流式中途：去掉末尾还没闭合的「[[…」，免得屏上闪一下方括号。 */
    public static String streaming(String s) {
        if (s == null) return null;
        String t = s;
        Matcher d = DANGLING.matcher(t);
        if (d.find() && d.start() < t.length() && t.charAt(d.start()) == '[') t = t.substring(0, d.start());
        return t;
    }
}
