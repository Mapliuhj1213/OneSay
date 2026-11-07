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
 * 回答 Markdown 分块（CommonMark 的常用子集，按手机助手回答的实际形态取舍）：
 * 围栏代码块（``` / ~~~，含 svg / mermaid / chart 图）、ATX 标题、无序 / 有序列表（缩进算下一级）、引用、
 * GFM 表格、分隔线、段落。行内语法留给 {@link InlineMd}。
 *
 * <p>流式友好：围栏没合上时照样产出一个 {@code open=true} 的代码 / 图块（宿主据此显示「生成中」，不急着渲染图）。
 * 纯函数、无状态、线程安全；输入多长都是线性时间（没有回溯型正则跑在整段正文上）。
 */
public final class MarkdownBlocks {

    private MarkdownBlocks() {}

    /** 单个回答最多切出这么多块（防病态输入把面板撑爆）；超出的并进最后一个段落。 */
    public static final int MAX_BLOCKS = 400;

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})\\s*([^`\\s]*)[^`]*$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:\\s+(.*?))?\\s*#*\\s*$");
    private static final Pattern RULE = Pattern.compile("^ {0,3}(?:(?:-\\s*){3,}|(?:\\*\\s*){3,}|(?:_\\s*){3,})$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+•])\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d{1,9})(?:[.)]\\s+|、\\s*)(.*)$");
    private static final Pattern TABLE_DELIM = Pattern.compile("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$");

    /** 图的语言标签归一：svg / mermaid / chart；其它返回 null（当普通代码）。 */
    public static String diagramKind(String lang) {
        if (lang == null) return null;
        String l = lang.toLowerCase(Locale.ROOT);
        if (l.equals("svg")) return "svg";
        if (l.equals("mermaid") || l.equals("mmd")) return "mermaid";
        if (l.equals("chart") || l.equals("chartjs") || l.equals("chart.js") || l.equals("chart-js")) return "chart";
        return null;
    }

    /** 代码语言标签归一（小写、常见别名）。 */
    public static String normLang(String lang) {
        if (lang == null) return "";
        String l = lang.trim().toLowerCase(Locale.ROOT);
        if (l.startsWith("{") || l.startsWith(".")) l = l.replaceAll("[{}.]", "");
        if (l.equals("js") || l.equals("javascript") || l.equals("node") || l.equals("jsx")) return "javascript";
        if (l.equals("ts") || l.equals("tsx") || l.equals("typescript")) return "typescript";
        if (l.equals("py") || l.equals("python3")) return "python";
        if (l.equals("sh") || l.equals("shell") || l.equals("zsh") || l.equals("console") || l.equals("shellscript")) return "bash";
        if (l.equals("c++") || l.equals("cc") || l.equals("hpp") || l.equals("cxx")) return "cpp";
        if (l.equals("h")) return "c";
        if (l.equals("cs") || l.equals("c#")) return "csharp";
        if (l.equals("kt") || l.equals("kts")) return "kotlin";
        if (l.equals("rs")) return "rust";
        if (l.equals("golang")) return "go";
        if (l.equals("yml")) return "yaml";
        if (l.equals("htm") || l.equals("xhtml")) return "html";
        if (l.equals("md")) return "markdown";
        return l;
    }

    public static List<RichBlock> parse(String text) {
        List<RichBlock> out = new ArrayList<RichBlock>();
        if (text == null) return out;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        int n = lines.length;
        StringBuilder para = new StringBuilder();
        while (i < n) {
            String line = lines[i];
            if (out.size() >= MAX_BLOCKS) {
                // 病态输入：剩下的原样并成一个段落
                StringBuilder rest = new StringBuilder();
                for (int k = i; k < n; k++) {
                    if (rest.length() > 0) rest.append('\n');
                    rest.append(lines[k]);
                }
                flushPara(out, para);
                if (rest.toString().trim().length() > 0) out.add(RichBlock.paragraph(rest.toString().trim()));
                break;
            }
            Matcher fm = FENCE.matcher(line);
            if (fm.matches()) {
                flushPara(out, para);
                String fence = fm.group(1);
                char fc = fence.charAt(0);
                String lang = fm.group(2);
                int indent = leadingSpaces(line);
                StringBuilder code = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < n) {
                    String l2 = lines[j];
                    if (isClosingFence(l2, fc, fence.length())) {
                        closed = true;
                        break;
                    }
                    if (code.length() > 0 || j > i + 1) code.append('\n');
                    code.append(stripIndent(l2, indent));
                    j++;
                }
                String body = code.toString();
                String dk = diagramKind(lang);
                if (dk != null) out.add(RichBlock.diagram(dk, body, !closed));
                else out.add(RichBlock.code(normLang(lang), body, !closed));
                i = closed ? j + 1 : n;
                continue;
            }
            if (line.trim().length() == 0) {
                flushPara(out, para);
                i++;
                continue;
            }
            Matcher hm = HEADING.matcher(line);
            if (hm.matches()) {
                flushPara(out, para);
                String t = hm.group(2) == null ? "" : hm.group(2).trim();
                if (t.length() > 0) out.add(RichBlock.heading(hm.group(1).length(), t));
                i++;
                continue;
            }
            if (RULE.matcher(line).matches()) {
                flushPara(out, para);
                out.add(RichBlock.rule());
                i++;
                continue;
            }
            // 表格：当前行有 |，下一行是分隔行
            if (line.indexOf('|') >= 0 && i + 1 < n && TABLE_DELIM.matcher(lines[i + 1]).matches() && lines[i + 1].indexOf('-') >= 0) {
                List<String> header = splitRow(line);
                List<String> delim = splitRow(lines[i + 1]);
                if (header.size() >= 1 && (header.size() == delim.size() || line.indexOf('|') != line.lastIndexOf('|') || header.size() > 1)) {
                    flushPara(out, para);
                    List<Character> aligns = new ArrayList<Character>();
                    for (int c = 0; c < header.size(); c++) {
                        String d = c < delim.size() ? delim.get(c).trim() : "-";
                        boolean l = d.startsWith(":"), r = d.endsWith(":");
                        aligns.add(l && r ? 'C' : r ? 'R' : 'L');
                    }
                    List<List<String>> rows = new ArrayList<List<String>>();
                    int j = i + 2;
                    while (j < n && lines[j].trim().length() > 0 && lines[j].indexOf('|') >= 0) {
                        List<String> row = splitRow(lines[j]);
                        while (row.size() < header.size()) row.add("");
                        while (row.size() > header.size()) row.remove(row.size() - 1);
                        rows.add(row);
                        j++;
                    }
                    out.add(RichBlock.table(header, rows, aligns));
                    i = j;
                    continue;
                }
            }
            if (line.trim().startsWith(">")) {
                flushPara(out, para);
                StringBuilder q = new StringBuilder();
                int j = i;
                while (j < n && lines[j].trim().startsWith(">")) {
                    String s = lines[j].trim().substring(1);
                    if (s.startsWith(" ")) s = s.substring(1);
                    if (q.length() > 0) q.append('\n');
                    q.append(s);
                    j++;
                }
                out.add(RichBlock.quote(q.toString().trim()));
                i = j;
                continue;
            }
            Matcher bm = BULLET.matcher(line);
            Matcher om = ORDERED.matcher(line);
            boolean isBullet = bm.matches() && !RULE.matcher(line).matches();
            boolean isOrdered = !isBullet && om.matches();
            if (isBullet || isOrdered) {
                flushPara(out, para);
                boolean ordered = isOrdered;
                int start = 1;
                if (ordered) {
                    try {
                        start = Integer.parseInt(om.group(2));
                    } catch (NumberFormatException e) {
                        start = 1;
                    }
                }
                int baseIndent = leadingSpaces(line);
                List<String> items = new ArrayList<String>();
                int j = i;
                while (j < n) {
                    String l2 = lines[j];
                    if (l2.trim().length() == 0) {
                        // 空行后紧跟同类列表项 → 同一个列表（宽松列表）；否则列表结束
                        if (j + 1 < n && sameListItem(lines[j + 1], ordered)) {
                            j++;
                            continue;
                        }
                        break;
                    }
                    if (FENCE.matcher(l2).matches() && leadingSpaces(l2) <= baseIndent) break;
                    Matcher b2 = BULLET.matcher(l2);
                    Matcher o2 = ORDERED.matcher(l2);
                    boolean b2m = b2.matches() && !RULE.matcher(l2).matches();
                    boolean o2m = !b2m && o2.matches();
                    if (b2m || o2m) {
                        int ind = leadingSpaces(l2);
                        int depth = Math.max(0, Math.min(3, (ind - baseIndent) / 2));
                        if (ind <= baseIndent && (o2m != ordered)) break;   // 同级换了列表类型 → 新列表
                        String body = b2m ? b2.group(3) : o2.group(3);
                        String prefix = repeat('\t', depth);
                        if (depth > 0 && o2m) prefix += o2.group(2) + ". ";
                        items.add(prefix + body.trim());
                        j++;
                        continue;
                    }
                    if (leadingSpaces(l2) > baseIndent && !items.isEmpty()) {
                        // 续行
                        int last = items.size() - 1;
                        items.set(last, items.get(last) + "\n" + l2.trim());
                        j++;
                        continue;
                    }
                    if (startsBlock(l2, lines, j)) break;
                    if (!items.isEmpty()) {
                        // 懒续行（不缩进的正文紧跟列表项）
                        int last = items.size() - 1;
                        items.set(last, items.get(last) + "\n" + l2.trim());
                        j++;
                        continue;
                    }
                    break;
                }
                out.add(RichBlock.list(ordered, start, items));
                i = j;
                continue;
            }
            if (para.length() > 0) para.append('\n');
            para.append(line.trim());
            i++;
        }
        flushPara(out, para);
        return out;
    }

    private static boolean sameListItem(String l, boolean ordered) {
        if (ordered) return ORDERED.matcher(l).matches();
        return BULLET.matcher(l).matches() && !RULE.matcher(l).matches();
    }

    private static boolean startsBlock(String l, String[] lines, int idx) {
        if (FENCE.matcher(l).matches() || HEADING.matcher(l).matches() || RULE.matcher(l).matches()) return true;
        if (l.trim().startsWith(">")) return true;
        return l.indexOf('|') >= 0 && idx + 1 < lines.length && TABLE_DELIM.matcher(lines[idx + 1]).matches();
    }

    private static void flushPara(List<RichBlock> out, StringBuilder para) {
        if (para.length() == 0) return;
        String t = para.toString().trim();
        para.setLength(0);
        if (t.length() > 0) out.add(RichBlock.paragraph(t));
    }

    private static boolean isClosingFence(String l, char fc, int len) {
        String t = l.trim();
        if (leadingSpaces(l) > 3 || t.length() < len) return false;
        for (int k = 0; k < t.length(); k++) if (t.charAt(k) != fc) return false;
        return true;
    }

    static int leadingSpaces(String s) {
        int k = 0;
        int col = 0;
        while (k < s.length()) {
            char c = s.charAt(k);
            if (c == ' ') col++;
            else if (c == '\t') col += 4 - (col % 4);
            else break;
            k++;
        }
        return col;
    }

    private static String stripIndent(String l, int indent) {
        int k = 0;
        while (k < l.length() && k < indent && l.charAt(k) == ' ') k++;
        return l.substring(k);
    }

    private static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < n; k++) b.append(c);
        return b.toString();
    }

    /** 拆表格行：去首尾 |，按未转义、不在 `code` 里的 | 切。 */
    static List<String> splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length() - 1);
        List<String> cells = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inCode = false;
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == '\\' && k + 1 < t.length() && t.charAt(k + 1) == '|') {
                cur.append('|');
                k++;
                continue;
            }
            if (c == '`') inCode = !inCode;
            if (c == '|' && !inCode) {
                cells.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        cells.add(cur.toString().trim());
        return cells;
    }
}
