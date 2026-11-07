/*
 * [富内容输出插件 · core] OneSay 自写。只依赖 java.*。
 */
package com.onesay.assistant.plug.richout.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 行内 Markdown → 纯文本 + 区间标记（宿主把标记换成 Android Span）。
 *
 * <p>支持：{@code **粗**} / {@code __粗__}、{@code *斜*}（{@code _斜_} 只在两侧不是字母数字时算，免得把 snake_case 拆了）、
 * {@code ~~删~~}、{@code `代码`}、{@code [文字](链接)}、{@code ![替代文字](图)}（只当链接，不加载远程图）、
 * {@code <https://…>}、裸露的 http(s) 网址（遇到中文标点 / 空白截止，去掉末尾的英文标点）、反斜杠转义。
 * 链接只记录原始地址，放不放行由 {@link LinkPolicy} 在点击时裁决。
 */
public final class InlineMd {

    private InlineMd() {}

    public enum Type { BOLD, ITALIC, STRIKE, CODE, LINK }

    public static final class Span {
        public final Type type;
        public final int start;
        public final int end;
        public final String url;

        Span(Type type, int start, int end, String url) {
            this.type = type;
            this.start = start;
            this.end = end;
            this.url = url;
        }

        @Override public String toString() {
            return type + "[" + start + "," + end + ")" + (url == null ? "" : "=" + url);
        }
    }

    public static final class Result {
        public final String text;
        public final List<Span> spans;

        Result(String text, List<Span> spans) {
            this.text = text;
            this.spans = Collections.unmodifiableList(spans);
        }

        public List<Span> of(Type t) {
            List<Span> o = new ArrayList<Span>();
            for (Span s : spans) if (s.type == t) o.add(s);
            return o;
        }

        public List<String> links() {
            List<String> o = new ArrayList<String>();
            for (Span s : spans) if (s.type == Type.LINK) o.add(s.url);
            return o;
        }
    }

    private static final String ESCAPABLE = "\\`*_{}[]()#+-.!|~<>";
    /** 裸网址到这些字符截止（中文标点、全角括号、引号、空白）。 */
    private static final String URL_STOP = " \t\n<>\"'`，。！？；：、）】》」』…“”‘’（【《「『";
    /** 裸网址末尾去掉的英文标点。 */
    private static final String URL_TRAIL = ".,;:!?)]}'\"";
    private static final int MAX_DEPTH = 6;

    public static Result parse(String s) {
        StringBuilder out = new StringBuilder();
        List<Span> spans = new ArrayList<Span>();
        if (s != null) parseInto(s, 0, s.length(), out, spans, 0);
        return new Result(out.toString(), spans);
    }

    /** 只要纯文本（朗读 / 岛 / 复制用）。 */
    public static String plain(String s) {
        return parse(s).text;
    }

    private static void parseInto(String s, int from, int to, StringBuilder out, List<Span> spans, int depth) {
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < to && ESCAPABLE.indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int run = runLen(s, i, to, '`');
                int close = findRun(s, i + run, to, '`', run);
                if (close > 0) {
                    String code = s.substring(i + run, close);
                    if (code.length() >= 2 && code.startsWith(" ") && code.endsWith(" ") && code.trim().length() > 0) code = code.substring(1, code.length() - 1);
                    code = code.replace('\n', ' ');
                    int st = out.length();
                    out.append(code);
                    spans.add(new Span(Type.CODE, st, out.length(), null));
                    i = close + run;
                    continue;
                }
                out.append(s, i, i + run);
                i += run;
                continue;
            }
            if (depth < MAX_DEPTH && (c == '[' || (c == '!' && i + 1 < to && s.charAt(i + 1) == '['))) {
                int lb = c == '!' ? i + 1 : i;
                int rb = matchBracket(s, lb, to);
                if (rb > 0 && rb + 1 < to && s.charAt(rb + 1) == '(') {
                    int rp = matchParen(s, rb + 1, to);
                    if (rp > 0) {
                        String dest = s.substring(rb + 2, rp).trim();
                        // 去掉 "title"
                        int sp = indexOfWs(dest);
                        if (sp > 0) dest = dest.substring(0, sp);
                        if (dest.startsWith("<") && dest.endsWith(">")) dest = dest.substring(1, dest.length() - 1);
                        int st = out.length();
                        if (c == '!') out.append("图：");
                        parseInto(s, lb + 1, rb, out, spans, depth + 1);
                        if (out.length() == st) out.append(dest);
                        if (dest.length() > 0) spans.add(new Span(Type.LINK, st, out.length(), dest));
                        i = rp + 1;
                        continue;
                    }
                }
            }
            if (c == '<') {
                int gt = s.indexOf('>', i + 1);
                if (gt > 0 && gt < to) {
                    String inner = s.substring(i + 1, gt);
                    if ((inner.startsWith("http://") || inner.startsWith("https://")) && indexOfWs(inner) < 0) {
                        int st = out.length();
                        out.append(inner);
                        spans.add(new Span(Type.LINK, st, out.length(), inner));
                        i = gt + 1;
                        continue;
                    }
                }
            }
            if ((c == 'h' || c == 'H') && startsUrl(s, i, to) && (i == from || !isWordChar(s.charAt(i - 1)))) {
                int e = i;
                while (e < to && URL_STOP.indexOf(s.charAt(e)) < 0) e++;
                while (e > i && URL_TRAIL.indexOf(s.charAt(e - 1)) >= 0) {
                    // 末尾的 ) 有配对的 ( 就保留（维基链接）
                    if (s.charAt(e - 1) == ')' && count(s, i, e, '(') >= count(s, i, e, ')')) break;
                    e--;
                }
                String url = s.substring(i, e);
                if (url.length() > 10) {
                    int st = out.length();
                    out.append(url);
                    spans.add(new Span(Type.LINK, st, out.length(), url));
                    i = e;
                    continue;
                }
            }
            if (depth < MAX_DEPTH && (c == '*' || c == '_' || c == '~')) {
                int run = runLen(s, i, to, c);
                if (c == '~' && run >= 2) {
                    int close = findDelim(s, i + 2, to, "~~");
                    if (close > i + 2) {
                        int st = out.length();
                        parseInto(s, i + 2, close, out, spans, depth + 1);
                        spans.add(new Span(Type.STRIKE, st, out.length(), null));
                        i = close + 2;
                        continue;
                    }
                } else if (c != '~' && run >= 2 && leftFlanking(s, i + 2, to) && (c == '*' || i == from || !isWordChar(s.charAt(i - 1)))) {
                    String d = c == '*' ? "**" : "__";
                    int close = findDelim(s, i + 2, to, d);
                    if (close > i + 2 && !Character.isWhitespace(s.charAt(close - 1))
                            && (c == '*' || close + 2 >= to || !isWordChar(s.charAt(close + 2)))) {
                        int st = out.length();
                        parseInto(s, i + 2, close, out, spans, depth + 1);
                        spans.add(new Span(Type.BOLD, st, out.length(), null));
                        i = close + 2;
                        continue;
                    }
                } else if (c != '~' && run == 1 && leftFlanking(s, i + 1, to) && (c == '*' || i == from || !isWordChar(s.charAt(i - 1)))) {
                    int close = findSingle(s, i + 1, to, c);
                    if (close > i + 1 && !Character.isWhitespace(s.charAt(close - 1))
                            && (c == '*' || close + 1 >= to || !isWordChar(s.charAt(close + 1)))) {
                        int st = out.length();
                        parseInto(s, i + 1, close, out, spans, depth + 1);
                        spans.add(new Span(Type.ITALIC, st, out.length(), null));
                        i = close + 1;
                        continue;
                    }
                }
                out.append(s, i, i + run);
                i += run;
                continue;
            }
            out.append(c);
            i++;
        }
    }

    private static boolean startsUrl(String s, int i, int to) {
        return regionIc(s, i, to, "https://") || regionIc(s, i, to, "http://");
    }

    private static boolean regionIc(String s, int i, int to, String p) {
        return i + p.length() <= to && s.regionMatches(true, i, p, 0, p.length());
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean leftFlanking(String s, int i, int to) {
        return i < to && !Character.isWhitespace(s.charAt(i));
    }

    private static int runLen(String s, int i, int to, char c) {
        int k = i;
        while (k < to && s.charAt(k) == c) k++;
        return k - i;
    }

    private static int findRun(String s, int from, int to, char c, int len) {
        int k = from;
        while (k < to) {
            if (s.charAt(k) == c) {
                int r = runLen(s, k, to, c);
                if (r == len) return k;
                k += r;
            } else {
                k++;
            }
        }
        return -1;
    }

    private static int findDelim(String s, int from, int to, String d) {
        int k = from;
        while (k <= to - d.length()) {
            char c = s.charAt(k);
            if (c == '\\') {
                k += 2;
                continue;
            }
            if (c == '`') {
                int run = runLen(s, k, to, '`');
                int close = findRun(s, k + run, to, '`', run);
                k = close > 0 ? close + run : k + run;
                continue;
            }
            if (s.startsWith(d, k)) return k;
            k++;
        }
        return -1;
    }

    private static int findSingle(String s, int from, int to, char c) {
        int k = from;
        while (k < to) {
            char ch = s.charAt(k);
            if (ch == '\\') {
                k += 2;
                continue;
            }
            if (ch == '`') {
                int run = runLen(s, k, to, '`');
                int close = findRun(s, k + run, to, '`', run);
                k = close > 0 ? close + run : k + run;
                continue;
            }
            if (ch == c) {
                int r = runLen(s, k, to, c);
                if (r == 1) return k;
                k += r;
                continue;
            }
            if (ch == '\n' && k + 1 < to && s.charAt(k + 1) == '\n') return -1;
            k++;
        }
        return -1;
    }

    private static int matchBracket(String s, int lb, int to) {
        int d = 0;
        for (int k = lb; k < to; k++) {
            char c = s.charAt(k);
            if (c == '\\') {
                k++;
                continue;
            }
            if (c == '[') d++;
            else if (c == ']') {
                d--;
                if (d == 0) return k;
            } else if (c == '\n' && k + 1 < to && s.charAt(k + 1) == '\n') return -1;
        }
        return -1;
    }

    private static int matchParen(String s, int lp, int to) {
        int d = 0;
        for (int k = lp; k < to; k++) {
            char c = s.charAt(k);
            if (c == '\\') {
                k++;
                continue;
            }
            if (c == '(') d++;
            else if (c == ')') {
                d--;
                if (d == 0) return k;
            } else if (c == '\n') return -1;
        }
        return -1;
    }

    private static int indexOfWs(String s) {
        for (int k = 0; k < s.length(); k++) if (Character.isWhitespace(s.charAt(k))) return k;
        return -1;
    }

    private static int count(String s, int from, int to, char c) {
        int n = 0;
        for (int k = from; k < to; k++) if (s.charAt(k) == c) n++;
        return n;
    }
}
