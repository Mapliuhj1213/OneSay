/*
 * [富内容输出插件 · core] OneSay 自写。只依赖 java.*。
 */
package com.onesay.assistant.plug.richout.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阅读模式的兜底正文抽取（Tavily extract 不可用时）：HTML → 标题 + 段落。不执行页面脚本、不带 Cookie（宿主用普通 HTTP 取页）。
 * 规则很朴素：去 script / style / nav / header / footer / aside / form 等；有 article / main 优先用；块级标签断段；
 * 去掉太短的导航碎片。够看新闻、百科、博客正文。
 */
public final class ReaderText {

    private ReaderText() {}

    public static final int MAX_CHARS = 20000;

    public static final class Doc {
        public final String title;
        public final List<String> paragraphs;

        Doc(String title, List<String> paragraphs) {
            this.title = title == null ? "" : title;
            this.paragraphs = paragraphs;
        }

        public int chars() {
            int n = 0;
            for (String p : paragraphs) n += p.length();
            return n;
        }

        public boolean usable() {
            return chars() >= 80;
        }

        public String text() {
            StringBuilder b = new StringBuilder();
            for (String p : paragraphs) {
                if (b.length() > 0) b.append("\n\n");
                b.append(p);
            }
            return b.toString();
        }
    }

    private static final Pattern DROP = Pattern.compile(
            "(?is)<(script|style|noscript|template|svg|nav|header|footer|aside|form|iframe|object|button|select|textarea)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern OG_TITLE = Pattern.compile("(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']*)[\"']");
    private static final Pattern H1 = Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>");
    private static final Pattern BLOCK = Pattern.compile("(?i)</?(p|div|br|li|ul|ol|h[1-6]|tr|table|section|article|blockquote|pre|dd|dt|figcaption)\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");

    /** 已经是纯文本的正文（Tavily extract / 原网页 PageSnapshot）：按行切段、去碎片、截上限。 */
    public static Doc fromText(String title, String text) {
        List<String> paras = new ArrayList<String>();
        int total = 0;
        if (text != null) {
            for (String line : text.replace("\r", "").split("\n")) {
                String t = line.replace('\u00a0', ' ').replaceAll("[ \\t]+", " ").trim();
                if (t.length() < 2) continue;
                if (!paras.isEmpty() && paras.get(paras.size() - 1).equals(t)) continue;
                paras.add(t);
                total += t.length();
                if (total > MAX_CHARS) break;
            }
        }
        return new Doc(title == null ? "" : title.trim(), paras);
    }

    public static Doc extract(String html) {
        List<String> paras = new ArrayList<String>();
        if (html == null || html.length() == 0) return new Doc("", paras);
        String h = html.length() > 3_000_000 ? html.substring(0, 3_000_000) : html;
        String title = firstGroup(OG_TITLE, h);
        if (title == null) title = firstGroup(TITLE, h);
        String h1 = firstGroup(H1, h);
        h = COMMENT.matcher(h).replaceAll(" ");
        h = DROP.matcher(h).replaceAll(" ");
        String body = pickMain(h);
        body = BLOCK.matcher(body).replaceAll("\n");
        body = TAG.matcher(body).replaceAll("");
        body = SvgSanitizer.decodeEntities(decodeNamed(body));
        int total = 0;
        for (String line : body.split("\n")) {
            String t = line.replace('\u00a0', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ").trim();
            if (t.length() < 2) continue;
            if (t.length() < 12 && !endsLikeSentence(t)) continue;   // 导航 / 按钮碎片
            if (!paras.isEmpty() && paras.get(paras.size() - 1).equals(t)) continue;
            paras.add(t);
            total += t.length();
            if (total > MAX_CHARS) break;
        }
        String tt = clean(title != null ? title : h1);
        if (tt.length() == 0 && h1 != null) tt = clean(h1);
        return new Doc(tt, paras);
    }

    private static boolean endsLikeSentence(String t) {
        char c = t.charAt(t.length() - 1);
        return "。！？.!?；;：:".indexOf(c) >= 0;
    }

    private static String pickMain(String h) {
        String best = between(h, "article");
        if (best == null || best.length() < 400) {
            String m = between(h, "main");
            if (m != null && (best == null || m.length() > best.length())) best = m;
        }
        if (best != null && best.length() >= 200) return best;
        String b = between(h, "body");
        return b != null ? b : h;
    }

    private static String between(String h, String tag) {
        Matcher m = Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*)</" + tag + "\\s*>").matcher(h);
        return m.find() ? m.group(1) : null;
    }

    private static String firstGroup(Pattern p, String h) {
        Matcher m = p.matcher(h);
        return m.find() ? m.group(1) : null;
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = SvgSanitizer.decodeEntities(decodeNamed(TAG.matcher(s).replaceAll("")));
        return t.replaceAll("\\s+", " ").trim();
    }

    private static String decodeNamed(String s) {
        if (s.indexOf('&') < 0) return s;
        return s.replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
                .replace("&ldquo;", "“").replace("&rdquo;", "”").replace("&lsquo;", "‘").replace("&rsquo;", "’")
                .replace("&middot;", "·").replace("&copy;", "©").replace("&times;", "×").replace("&yen;", "¥");
    }

    /** 从 Content-Type 头取 charset（小写），没有返回 null。 */
    public static String charsetOf(String contentType) {
        if (contentType == null) return null;
        Matcher m = Pattern.compile("(?i)charset=([\\w-]+)").matcher(contentType);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** 从 HTML 头部 meta 取 charset（只看前 4 KB）。 */
    public static String metaCharset(byte[] head) {
        if (head == null) return null;
        String s = new String(head, 0, Math.min(head.length, 4096), java.nio.charset.Charset.forName("ISO-8859-1"));
        Matcher m = Pattern.compile("(?i)<meta[^>]+charset=[\"']?([\\w-]+)").matcher(s);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }
}
