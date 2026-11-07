/*
 * [富内容输出插件 · core] OneSay 自写。只依赖 java.*。
 */
package com.onesay.assistant.plug.richout.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SVG 净化：<b>白名单重建</b>，不是黑名单删除。自己写的宽容分词器把输入拆成标签 / 文本，只把白名单里的元素和属性
 * 重新序列化出来（属性值统一转义），其余一律丢掉。所以无论原文怎么构造（大小写、编码、实体、CDATA、畸形标签），
 * 输出里都不可能出现脚本、事件、外链。
 *
 * <ul>
 *   <li>整棵丢掉（连同子孙）：script、foreignObject、iframe、object、embed、audio、video、canvas、image（外链图）、
 *       animate / set / animateMotion / animateTransform（能把 href 改成 javascript:）、handler、listener、style 以外的未知元素的内容保留但元素本身去掉；</li>
 *   <li>属性：on* 一律丢；href / xlink:href 只留 {@code #片段}；任何值里出现 javascript: / vbscript: / data: / 外部 url(…) 的整条丢；
 *       style 属性和 style 元素过一遍 CSS 净化（去 @import、expression、behavior、-moz-binding、非 # 的 url()）；</li>
 *   <li>DOCTYPE / ENTITY / 处理指令 / 注释一律丢（XXE、实体膨胀都没了入口）；文本里只认 5 个标准实体和数字实体；</li>
 *   <li>输入超过 {@link #MAX_BYTES} 直接拒绝；元素超过 {@link #MAX_ELEMENTS} 截断；嵌套超过 {@link #MAX_DEPTH} 截断；</li>
 *   <li>根必须是 svg（前面允许有 xml 声明 / 注释 / 空白），否则拒绝。</li>
 * </ul>
 */
public final class SvgSanitizer {

    private SvgSanitizer() {}

    public static final int MAX_BYTES = 200 * 1024;
    public static final int MAX_ELEMENTS = 5000;
    public static final int MAX_DEPTH = 64;

    public static final class Result {
        /** 净化后的 SVG；拒绝时为 null。 */
        public final String svg;
        /** 丢掉的元素 / 属性计数（日志 / 单测）。 */
        public final int removed;
        public final String reason;

        Result(String svg, int removed, String reason) {
            this.svg = svg;
            this.removed = removed;
            this.reason = reason;
        }

        public boolean ok() {
            return svg != null;
        }

        @Override public String toString() {
            return ok() ? "ok(" + svg.length() + "B, removed=" + removed + ")" : "rejected(" + reason + ")";
        }
    }

    static final Set<String> ELEMENTS = new HashSet<String>(Arrays.asList(
            "svg", "g", "defs", "symbol", "use", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
            "text", "tspan", "textpath", "title", "desc", "lineargradient", "radialgradient", "stop", "clippath", "mask",
            "pattern", "marker", "filter", "style", "switch", "metadata",
            "feblend", "fecolormatrix", "fecomponenttransfer", "fecomposite", "feconvolvematrix", "fediffuselighting",
            "fedisplacementmap", "fedistantlight", "fedropshadow", "feflood", "fefunca", "fefuncb", "fefuncg", "fefuncr",
            "fegaussianblur", "femerge", "femergenode", "femorphology", "feoffset", "fepointlight", "fespecularlighting",
            "fespotlight", "fetile", "feturbulence"));

    /** 整棵丢掉的元素（内容也不要）。 */
    static final Set<String> DROP_SUBTREE = new HashSet<String>(Arrays.asList(
            "script", "foreignobject", "iframe", "object", "embed", "audio", "video", "canvas", "image", "img",
            "animate", "set", "animatemotion", "animatetransform", "animatecolor", "handler", "listener", "a", "html",
            "body", "head", "link", "meta", "base", "form", "input", "button", "textarea", "frame", "frameset", "applet",
            "discard", "feimage", "cursor", "font", "font-face"));

    static final Set<String> ATTRS = new HashSet<String>(Arrays.asList(
            "id", "class", "style", "transform", "viewbox", "width", "height", "x", "y", "x1", "y1", "x2", "y2", "cx", "cy",
            "r", "rx", "ry", "d", "points", "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity",
            "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "stroke-dashoffset", "stroke-miterlimit", "opacity",
            "font-family", "font-size", "font-weight", "font-style", "text-anchor", "dominant-baseline", "alignment-baseline",
            "letter-spacing", "word-spacing", "text-decoration", "dx", "dy", "rotate", "textlength", "lengthadjust",
            "offset", "stop-color", "stop-opacity", "gradientunits", "gradienttransform", "spreadmethod", "fx", "fy", "fr",
            "patternunits", "patterncontentunits", "patterntransform", "clip-path", "clip-rule", "clippathunits", "mask",
            "maskunits", "maskcontentunits", "marker-start", "marker-mid", "marker-end", "markerwidth", "markerheight",
            "markerunits", "refx", "refy", "orient", "preserveaspectratio", "filter", "filterunits", "primitiveunits",
            "in", "in2", "result", "stddeviation", "mode", "operator", "k1", "k2", "k3", "k4", "values", "type",
            "tablevalues", "slope", "intercept", "amplitude", "exponent", "dx", "dy", "flood-color", "flood-opacity",
            "lighting-color", "basefrequency", "numoctaves", "seed", "stitchtiles", "scale", "xchannelselector",
            "ychannelselector", "kernelmatrix", "order", "radius", "surfacescale", "diffuseconstant", "specularconstant",
            "specularexponent", "azimuth", "elevation", "pointsatx", "pointsaty", "pointsatz", "limitingconeangle",
            "visibility", "display", "overflow", "color", "vector-effect", "shape-rendering", "text-rendering",
            "image-rendering", "paint-order", "pathlength", "startoffset", "method", "spacing", "side",
            "xmlns", "xmlns:xlink", "version", "href", "xlink:href", "xml:space", "role", "aria-label", "aria-hidden",
            "focusable", "data-name", "baseprofile", "enable-background", "mix-blend-mode", "isolation"));

    /** 属性值统一按小写、去空白 / 控制字符 / 实体后检查这些危险片段。 */
    private static final String[] BAD_VALUE = {"javascript:", "vbscript:", "data:", "livescript:", "expression(", "-moz-binding",
            "behavior:", "@import", "&#", "\\0"};

    public static Result sanitize(String input) {
        if (input == null) return new Result(null, 0, "空");
        if (utf8Len(input) > MAX_BYTES) return new Result(null, 0, "超过 " + (MAX_BYTES / 1024) + " KB");
        Out o = new Out();
        int i = 0;
        int n = input.length();
        List<String> stack = new ArrayList<String>();
        List<String> rawStack = new ArrayList<String>();   // 与 stack 平行：输出用的原始大小写名（丢弃 / 占位项为 null）
        int dropDepth = 0;            // >0 = 正在丢弃的子树深度
        boolean rootSeen = false;
        boolean rootClosed = false;
        int elements = 0;
        boolean inStyle = false;
        StringBuilder styleBuf = new StringBuilder();
        while (i < n && !rootClosed) {
            char c = input.charAt(i);
            if (c == '<') {
                if (input.startsWith("<!--", i)) {
                    int e = input.indexOf("-->", i + 4);
                    i = e < 0 ? n : e + 3;
                    continue;
                }
                if (input.startsWith("<![CDATA[", i)) {
                    int e = input.indexOf("]]>", i + 9);
                    String data = input.substring(i + 9, e < 0 ? n : e);
                    i = e < 0 ? n : e + 3;
                    if (dropDepth == 0 && rootSeen) {
                        if (inStyle) styleBuf.append(data);
                        else o.sb.append(escText(data));
                    }
                    continue;
                }
                if (input.startsWith("<!", i) || input.startsWith("<?", i)) {
                    // DOCTYPE / ENTITY / 处理指令：整段跳过（含内部子集 [...]）
                    int e = skipDecl(input, i);
                    if (input.regionMatches(true, i, "<!doctype", 0, 9) || input.regionMatches(true, i, "<!entity", 0, 8)) o.removed++;
                    i = e;
                    continue;
                }
                Tag t = readTag(input, i);
                if (t == null) {
                    // 不是合法标签的 <，当文本
                    if (dropDepth == 0 && rootSeen) {
                        if (inStyle) styleBuf.append('<');
                        else o.sb.append("&lt;");
                    }
                    i++;
                    continue;
                }
                i = t.end;
                String name = t.name;
                if (t.closing) {
                    if (dropDepth > 0) {
                        if (!stack.isEmpty() && stack.get(stack.size() - 1).equals(name)) {
                            stack.remove(stack.size() - 1);
                            rawStack.remove(rawStack.size() - 1);
                            dropDepth--;
                        }
                        continue;
                    }
                    // 找到最近的同名开标签并依次闭合
                    int at = stack.lastIndexOf(name);
                    if (at < 0) continue;
                    while (stack.size() > at) {
                        String top = stack.remove(stack.size() - 1);
                        String rawTop = rawStack.remove(rawStack.size() - 1);
                        if (top.equals("style") && inStyle) {
                            o.sb.append(escText(cleanCss(styleBuf.toString())));
                            styleBuf.setLength(0);
                            inStyle = false;
                        }
                        if (rawTop != null) o.sb.append("</").append(rawTop).append('>');
                    }
                    if (stack.isEmpty() && rootSeen) rootClosed = true;
                    continue;
                }
                if (dropDepth > 0) {
                    if (!t.selfClosing) {
                        stack.add(name);
                        rawStack.add(null);
                        dropDepth++;
                    }
                    continue;
                }
                if (!rootSeen) {
                    if (!name.equals("svg")) return new Result(null, o.removed, "根元素不是 svg：" + name);
                    rootSeen = true;
                }
                if (stack.size() >= MAX_DEPTH || elements >= MAX_ELEMENTS) {
                    o.removed++;
                    if (!t.selfClosing) {
                        stack.add(name);
                        rawStack.add(null);
                        dropDepth = 1;
                    }
                    continue;
                }
                if (inStyle) {
                    // style 里不该有子元素：丢
                    o.removed++;
                    continue;
                }
                if (DROP_SUBTREE.contains(name) || !ELEMENTS.contains(name)) {
                    o.removed++;
                    if (DROP_SUBTREE.contains(name) || name.indexOf(':') >= 0) {
                        if (!t.selfClosing) {
                            stack.add(name);
                            rawStack.add(null);
                            dropDepth = 1;
                        }
                    } else if (!t.selfClosing) {
                        // 未知但无害的容器：元素去掉，内容保留（用 ~ 前缀占位，闭合时不输出）
                        stack.add("~" + name);
                        rawStack.add(null);
                    }
                    continue;
                }
                elements++;
                String outName = safeName(t.raw, name);
                o.sb.append('<').append(outName);
                for (String[] a : t.attrs) writeAttr(o, name, a[0], a[1], a[2]);
                if (name.equals("svg") && stack.isEmpty() && !hasAttr(t.attrs, "xmlns")) o.sb.append(" xmlns=\"http://www.w3.org/2000/svg\"");
                if (t.selfClosing) {
                    o.sb.append("/>");
                    if (stack.isEmpty()) rootClosed = true;
                } else {
                    o.sb.append('>');
                    stack.add(name);
                    rawStack.add(outName);
                    if (name.equals("style")) inStyle = true;
                }
                continue;
            }
            // 文本
            int e = input.indexOf('<', i);
            if (e < 0) e = n;
            if (dropDepth == 0 && rootSeen) {
                String txt = decodeEntities(input.substring(i, e));
                if (inStyle) styleBuf.append(txt);
                else o.sb.append(escText(txt));
            }
            i = e;
        }
        if (!rootSeen) return new Result(null, o.removed, "没有 svg 元素");
        if (inStyle) {
            o.sb.append(escText(cleanCss(styleBuf.toString())));
        }
        for (int k = stack.size() - 1; k >= 0; k--) {
            String top = stack.get(k);
            if (dropDepth > 0) {
                dropDepth--;
                continue;
            }
            if (rawStack.get(k) != null) o.sb.append("</").append(rawStack.get(k)).append('>');
        }
        return new Result(o.sb.toString(), o.removed, null);
    }

    private static final class Out {
        final StringBuilder sb = new StringBuilder();
        int removed;
    }

    private static final class Tag {
        String name;
        String raw;
        boolean closing;
        boolean selfClosing;
        final List<String[]> attrs = new ArrayList<String[]>();
        int end;
    }

    /** 输出用原始大小写（viewBox、linearGradient）；名字里只允许字母数字和 : - _，否则用小写白名单名。 */
    private static String safeName(String raw, String lower) {
        if (raw == null || !raw.toLowerCase(Locale.ROOT).equals(lower)) return lower;
        for (int k = 0; k < raw.length(); k++) {
            char c = raw.charAt(k);
            if (!(Character.isLetterOrDigit(c) && c < 0x80) && c != ':' && c != '-' && c != '_') return lower;
        }
        return raw;
    }

    private static boolean hasAttr(List<String[]> attrs, String n) {
        for (String[] a : attrs) if (a[0].equals(n)) return true;
        return false;
    }

    private static void writeAttr(Out o, String el, String name, String rawValue, String rawName) {
        if (name.startsWith("on") || !ATTRS.contains(name)) {
            o.removed++;
            return;
        }
        String v = decodeEntities(rawValue == null ? "" : rawValue);
        String probe = squash(v);
        if (name.equals("href") || name.equals("xlink:href")) {
            if (!probe.startsWith("#") || !el.equals("use") && !el.equals("textpath") && !el.equals("lineargradient")
                    && !el.equals("radialgradient") && !el.equals("pattern") && !el.equals("filter")) {
                o.removed++;
                return;
            }
        }
        if (name.equals("xmlns") && !v.equals("http://www.w3.org/2000/svg")) {
            o.removed++;
            return;
        }
        if (name.equals("xmlns:xlink") && !v.equals("http://www.w3.org/1999/xlink")) {
            o.removed++;
            return;
        }
        for (String b : BAD_VALUE) {
            if (probe.contains(b)) {
                o.removed++;
                return;
            }
        }
        if (probe.contains("url(") && !onlyLocalUrls(probe)) {
            o.removed++;
            return;
        }
        if (name.equals("style")) v = cleanCss(v);
        if (v.length() > 20000) {
            o.removed++;
            return;
        }
        o.sb.append(' ').append(safeName(rawName, name)).append("=\"").append(escAttr(v)).append('"');
    }

    /** CSS 净化：去注释、@import / @font-face（外链字体）、expression、behavior、-moz-binding、非 # 的 url()。 */
    static String cleanCss(String css) {
        if (css == null) return "";
        String s = css.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder out = new StringBuilder();
        // 逐条声明 / 规则过滤（按 ; { } 切开，危险片段整条丢）
        int start = 0;
        for (int k = 0; k <= s.length(); k++) {
            char c = k < s.length() ? s.charAt(k) : ';';
            if (c == ';' || c == '{' || c == '}' || k == s.length()) {
                String piece = s.substring(start, Math.min(k, s.length()));
                String probe = squash(piece);
                boolean bad = false;
                for (String b : BAD_VALUE) if (probe.contains(b)) bad = true;
                if (probe.contains("@font-face") || probe.contains("@namespace") || probe.contains("@charset")) bad = true;
                if (probe.contains("url(") && !onlyLocalUrls(probe)) bad = true;
                if (probe.indexOf('\\') >= 0) bad = true;   // CSS 转义能拼出任何东西：整条丢
                if (!bad) out.append(piece);
                if (k < s.length()) out.append(c);
                start = k + 1;
            }
        }
        return out.toString();
    }

    private static boolean onlyLocalUrls(String probe) {
        int k = 0;
        while (true) {
            int u = probe.indexOf("url(", k);
            if (u < 0) return true;
            int a = u + 4;
            while (a < probe.length() && (probe.charAt(a) == '\'' || probe.charAt(a) == '"')) a++;
            if (a >= probe.length() || probe.charAt(a) != '#') return false;
            k = a;
        }
    }

    /** 小写 + 去掉空白 / 控制字符 / 零宽字符（"java\tscript:"、"jav&#x09;ascript:" 解码后也能识别）。 */
    static String squash(String v) {
        StringBuilder b = new StringBuilder(v.length());
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            if (c <= 0x20 || c == 0x7f || (c >= 0x200b && c <= 0x200f) || c == 0xfeff || c == 0x00ad) continue;
            b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    private static Tag readTag(String s, int i) {
        int n = s.length();
        int k = i + 1;
        Tag t = new Tag();
        if (k < n && s.charAt(k) == '/') {
            t.closing = true;
            k++;
        }
        int ns = k;
        while (k < n && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == ':' || s.charAt(k) == '-' || s.charAt(k) == '_' || s.charAt(k) == '.')) k++;
        if (k == ns || !Character.isLetter(s.charAt(ns))) return null;
        t.raw = s.substring(ns, k);
        t.name = t.raw.toLowerCase(Locale.ROOT);
        while (k < n) {
            char c = s.charAt(k);
            if (c == '>') {
                t.end = k + 1;
                return t;
            }
            if (c == '/' && k + 1 < n && s.charAt(k + 1) == '>') {
                t.selfClosing = true;
                t.end = k + 2;
                return t;
            }
            if (Character.isWhitespace(c) || c == '/') {
                k++;
                continue;
            }
            int as = k;
            while (k < n && !Character.isWhitespace(s.charAt(k)) && s.charAt(k) != '=' && s.charAt(k) != '>' && s.charAt(k) != '/') k++;
            if (k == as) {
                k++;
                continue;
            }
            String an = s.substring(as, k);
            while (k < n && Character.isWhitespace(s.charAt(k))) k++;
            String av = "";
            if (k < n && s.charAt(k) == '=') {
                k++;
                while (k < n && Character.isWhitespace(s.charAt(k))) k++;
                if (k < n && (s.charAt(k) == '"' || s.charAt(k) == '\'')) {
                    char q = s.charAt(k);
                    int e = s.indexOf(q, k + 1);
                    if (e < 0) return null;
                    av = s.substring(k + 1, e);
                    k = e + 1;
                } else {
                    int vs = k;
                    while (k < n && !Character.isWhitespace(s.charAt(k)) && s.charAt(k) != '>') k++;
                    av = s.substring(vs, k);
                }
            }
            if (t.closing) continue;
            t.attrs.add(new String[] {an.toLowerCase(Locale.ROOT), av, an});
        }
        return null;
    }

    private static int skipDecl(String s, int i) {
        int depth = 0;
        for (int k = i + 2; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == '>' && depth <= 0) return k + 1;
        }
        return s.length();
    }

    /** 只认 &lt; &gt; &amp; &quot; &apos; 和数字实体；其它 &xxx; 原样当文本（再转义成 &amp;xxx;）。 */
    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c != '&') {
                b.append(c);
                continue;
            }
            int semi = s.indexOf(';', k);
            if (semi < 0 || semi - k > 10) {
                b.append(c);
                continue;
            }
            String ent = s.substring(k + 1, semi);
            String rep = null;
            if (ent.equals("lt")) rep = "<";
            else if (ent.equals("gt")) rep = ">";
            else if (ent.equals("amp")) rep = "&";
            else if (ent.equals("quot")) rep = "\"";
            else if (ent.equals("apos")) rep = "'";
            else if (ent.startsWith("#")) {
                try {
                    int cp = ent.startsWith("#x") || ent.startsWith("#X") ? Integer.parseInt(ent.substring(2), 16) : Integer.parseInt(ent.substring(1));
                    if (cp > 0 && cp <= 0x10FFFF && (cp >= 0x20 || cp == 9 || cp == 10 || cp == 13)) rep = new String(Character.toChars(cp));
                    else rep = "";
                } catch (Throwable e) {
                    rep = null;
                }
            }
            if (rep == null) {
                b.append(c);
                continue;
            }
            b.append(rep);
            k = semi;
        }
        return b.toString();
    }

    static String escText(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '<') b.append("&lt;");
            else if (c == '>') b.append("&gt;");
            else if (c == '&') b.append("&amp;");
            else if (c < 0x20 && c != '\n' && c != '\t' && c != '\r') continue;
            else b.append(c);
        }
        return b.toString();
    }

    static String escAttr(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '<') b.append("&lt;");
            else if (c == '>') b.append("&gt;");
            else if (c == '&') b.append("&amp;");
            else if (c == '"') b.append("&quot;");
            else if (c == '\'') b.append("&#39;");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    private static int utf8Len(String s) {
        int n = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            n += c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isHighSurrogate(c) ? 2 : 3;
        }
        return n;
    }
}
