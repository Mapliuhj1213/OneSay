/*
 * [富内容输出插件 · core] OneSay 自写。只依赖 java.*。
 */
package com.onesay.assistant.plug.richout.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 富内容回答的文字层规则（纯函数）：要不要走富渲染、终稿怎么裁长度、朗读念什么、灵动岛显示什么。
 */
public final class RichText {

    private RichText() {}

    /** 有没有任何 Markdown / 链接特征。没有 → 宿主走原来的纯文本排版（一点不变、一样快）。 */
    private static final Pattern RICH_HINT = Pattern.compile(
            "(?s).*(```|~~~|\\*\\*|__|~~|`|\\]\\(|https?://|(^|\\n)\\s{0,3}(#{1,6}\\s|>|[-*+•]\\s|\\d{1,3}[.)]\\s|\\|)).*");

    public static boolean needsRich(String text) {
        if (text == null || text.length() == 0) return false;
        // 快速预筛：一个特征字符都没有的直接返回（绝大多数短答案走这里）
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '`' || c == '*' || c == '_' || c == '~' || c == '#' || c == '>' || c == '|' || c == '[' || c == ':' || c == '-' || c == '+' || c == '•'
                    || (c == '.' || c == ')') && i > 0 && Character.isDigit(text.charAt(i - 1))) {
                any = true;
                break;
            }
        }
        return any && RICH_HINT.matcher(text).matches();
    }

    // ------------------------------------------------------------------ 终稿裁剪

    /**
     * 终稿裁剪：正文（围栏外）按 maxProse 在句末截断；代码 / 图块整块保留（不从中间劈开），所有内容合计不超过 maxTotal，
     * 超了的代码块按行截断并注明；没合上的围栏补上。
     */
    public static String polish(String raw, int maxProse, int maxTotal) {
        if (raw == null) return "";
        String t = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (t.length() == 0) return "";
        List<String[]> segs = segments(t);   // {"P"|"F", text}
        StringBuilder out = new StringBuilder();
        int prose = 0;
        for (String[] sg : segs) {
            if (out.length() >= maxTotal) break;
            if (sg[0].equals("P")) {
                String p = sg[1];
                int room = Math.min(maxProse - prose, maxTotal - out.length());
                if (room <= 0) break;
                if (p.length() > room) {
                    out.append(cutAtSentence(p, room));
                    prose = maxProse;
                    break;
                }
                out.append(p);
                prose += p.length();
            } else {
                String f = sg[1];
                int room = maxTotal - out.length();
                if (f.length() > room) {
                    f = truncateFence(f, room);
                    if (f == null) break;
                    out.append(f);
                    break;
                }
                out.append(f);
            }
        }
        return closeOpenFence(out.toString().trim());
    }

    /** 把文本切成「正文 / 围栏块」交替段（围栏块含首尾 ``` 行）。 */
    static List<String[]> segments(String t) {
        List<String[]> out = new ArrayList<String[]>();
        String[] lines = t.split("\n", -1);
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String l = lines[i];
            String tl = l.trim();
            if (tl.startsWith("```") || tl.startsWith("~~~")) {
                if (cur.length() > 0) {
                    out.add(new String[] {"P", cur.toString()});
                    cur.setLength(0);
                }
                char fc = tl.charAt(0);
                int flen = 0;
                while (flen < tl.length() && tl.charAt(flen) == fc) flen++;
                StringBuilder f = new StringBuilder(l);
                int j = i + 1;
                boolean closed = false;
                while (j < lines.length) {
                    f.append('\n').append(lines[j]);
                    String tj = lines[j].trim();
                    if (tj.length() >= flen && onlyChar(tj, fc)) {
                        closed = true;
                        j++;
                        break;
                    }
                    j++;
                }
                f.append('\n');
                out.add(new String[] {"F", closed ? f.toString() : f.toString()});
                i = j;
                continue;
            }
            cur.append(l).append('\n');
            i++;
        }
        if (cur.length() > 0) out.add(new String[] {"P", cur.toString()});
        return out;
    }

    private static boolean onlyChar(String s, char c) {
        for (int k = 0; k < s.length(); k++) if (s.charAt(k) != c) return false;
        return true;
    }

    static String cutAtSentence(String p, int room) {
        int from = (int) (room * 0.6);
        for (int i = Math.min(room, p.length()) - 1; i >= from; i--) {
            char c = p.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') return p.substring(0, i + 1);
        }
        return p.substring(0, Math.max(0, room - 2)) + "…";
    }

    private static String truncateFence(String f, int room) {
        String[] lines = f.split("\n", -1);
        if (lines.length < 2 || room < 40) return null;
        String open = lines[0];
        StringBuilder b = new StringBuilder(open).append('\n');
        String note = "// …（太长，已截断）\n";
        for (int k = 1; k < lines.length; k++) {
            String l = lines[k];
            if (l.trim().startsWith("```") || l.trim().startsWith("~~~")) break;
            if (b.length() + l.length() + 1 + note.length() + 4 > room) {
                b.append(note);
                break;
            }
            b.append(l).append('\n');
        }
        return b.toString();
    }

    /** 没合上的围栏补上（流式半截 / 被截断）。 */
    public static String closeOpenFence(String t) {
        String[] lines = t.split("\n", -1);
        String open = null;
        for (String l : lines) {
            String tl = l.trim();
            if (open == null) {
                if (tl.startsWith("```") || tl.startsWith("~~~")) {
                    int k = 0;
                    while (k < tl.length() && tl.charAt(k) == tl.charAt(0)) k++;
                    open = tl.substring(0, k);
                }
            } else if (tl.length() >= open.length() && onlyChar(tl, open.charAt(0))) {
                open = null;
            }
        }
        if (open == null) return t;
        return t + (t.endsWith("\n") ? "" : "\n") + open;
    }

    // ------------------------------------------------------------------ 朗读

    public static final String CUE_CODE = "代码在屏上了，你看一眼";
    public static final String CUE_DIAGRAM = "图画好了，在屏上";
    public static final String CUE_TABLE = "表格在屏上了，你看一眼";
    public static final String CUE_MIXED = "代码和图都在屏上了";
    public static final String CUE_LINK = "链接在屏上，点开就能看";

    public static final class Speech {
        /** 朗读整句（正文去 Markdown，代码 / 图 / 表格换成一句提示，链接只念文字）。 */
        public final String spoken;
        /** 只念一句时用的提示（没有代码 / 图 / 表格时为 null）。 */
        public final String cue;
        public final boolean hasCode;
        public final boolean hasDiagram;
        public final boolean hasTable;
        public final int links;

        Speech(String spoken, String cue, boolean code, boolean dia, boolean table, int links) {
            this.spoken = spoken;
            this.cue = cue;
            this.hasCode = code;
            this.hasDiagram = dia;
            this.hasTable = table;
            this.links = links;
        }

        public boolean hasVisual() {
            return hasCode || hasDiagram || hasTable;
        }
    }

    public static Speech speech(String text) {
        List<RichBlock> blocks = MarkdownBlocks.parse(text);
        boolean code = false, dia = false, table = false;
        int links = 0;
        StringBuilder sb = new StringBuilder();
        for (RichBlock b : blocks) {
            switch (b.kind) {
                case CODE:
                    code = true;
                    break;
                case DIAGRAM:
                    dia = true;
                    break;
                case TABLE:
                    table = true;
                    break;
                case RULE:
                    break;
                case LIST:
                    for (String it : b.items) {
                        InlineMd.Result r = InlineMd.parse(it.replace("\t", ""));
                        links += r.links().size();
                        appendSentence(sb, r.text);
                    }
                    break;
                default: {
                    InlineMd.Result r = InlineMd.parse(b.text);
                    links += r.links().size();
                    appendSentence(sb, speakableLinks(r));
                    break;
                }
            }
        }
        String cue = code && dia ? CUE_MIXED : code ? CUE_CODE : dia ? CUE_DIAGRAM : table ? CUE_TABLE : null;
        if (cue != null) appendSentence(sb, cue);
        return new Speech(sb.toString().trim(), cue, code, dia, table, links);
    }

    /** 裸网址不念（念出来是一串字母）；带文字的链接只念文字。 */
    private static String speakableLinks(InlineMd.Result r) {
        String t = r.text;
        List<InlineMd.Span> ls = r.of(InlineMd.Type.LINK);
        if (ls.isEmpty()) return t;
        StringBuilder b = new StringBuilder();
        int pos = 0;
        for (InlineMd.Span s : ls) {
            if (s.start < pos) continue;
            b.append(t, pos, s.start);
            String label = t.substring(s.start, s.end);
            if (!label.equals(s.url) && !label.startsWith("http")) b.append(label);
            pos = s.end;
        }
        b.append(t.substring(pos));
        return b.toString();
    }

    private static void appendSentence(StringBuilder sb, String s) {
        if (s == null) return;
        String t = s.replace('\n', ' ').trim();
        if (t.length() == 0) return;
        if (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if ("。！？；，.!?;,:：".indexOf(last) < 0) sb.append('。');
        }
        sb.append(t);
    }

    // ------------------------------------------------------------------ 灵动岛 / 一行摘要

    /** 灵动岛 / 通知 / 日志用的一行纯文字：不出现代码和图，换成〔代码〕〔图〕〔表格〕。 */
    public static String plainLine(String text, int max) {
        if (text == null) return "";
        if (!needsRich(text)) {
            String t = text.replace('\n', ' ').replaceAll("\\s{2,}", " ").trim();
            return t.length() <= max ? t : t.substring(0, Math.max(0, max - 1)) + "…";
        }
        StringBuilder sb = new StringBuilder();
        for (RichBlock b : MarkdownBlocks.parse(text)) {
            String piece;
            switch (b.kind) {
                case CODE: piece = "〔代码〕"; break;
                case DIAGRAM: piece = "〔图〕"; break;
                case TABLE: piece = "〔表格〕"; break;
                case RULE: piece = ""; break;
                case LIST: {
                    StringBuilder l = new StringBuilder();
                    for (String it : b.items) {
                        if (l.length() > 0) l.append("；");
                        l.append(InlineMd.plain(it.replace("\t", "")));
                    }
                    piece = l.toString();
                    break;
                }
                default: piece = InlineMd.plain(b.text);
            }
            if (piece.length() == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(piece.replace('\n', ' '));
            if (sb.length() > max * 2) break;
        }
        String t = sb.toString().replaceAll("\\s{2,}", " ").trim();
        return t.length() <= max ? t : t.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** 关掉插件时的回落：去 Markdown 符号但保留代码正文（不像旧 cleanMarkdown 那样把代码里的符号也删光）。 */
    public static String plainFallback(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (RichBlock b : MarkdownBlocks.parse(text)) {
            if (sb.length() > 0) sb.append("\n\n");
            switch (b.kind) {
                case CODE:
                case DIAGRAM:
                    sb.append(b.text);
                    break;
                case RULE:
                    sb.append("——");
                    break;
                case LIST: {
                    int k = b.start;
                    for (int i = 0; i < b.items.size(); i++) {
                        if (i > 0) sb.append('\n');
                        sb.append(b.ordered ? (k++) + ". " : "· ").append(InlineMd.plain(b.items.get(i).replace("\t", "  ")));
                    }
                    break;
                }
                case TABLE: {
                    sb.append(join(b.header, " / "));
                    for (List<String> r : b.rows) sb.append('\n').append(join(r, " / "));
                    break;
                }
                default:
                    sb.append(InlineMd.plain(b.text));
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ 只放行表格（富内容关、用户明确要表格）

    private static final Pattern TAG = Pattern.compile("(?s)<[^>]{0,400}>");
    /** script / style 连内容一起去掉（只当文字也不留）。 */
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?is)<\\s*(script|style)\\b[^>]*>.*?(<\\s*/\\s*\\1\\s*>|$)");

    /** 单元格 / 正文消毒：去 Markdown 行内符号、去 HTML 标签、去 | 和反引号、压空白、限长。结果是纯文本。 */
    static String plainCell(String s, int max) {
        if (s == null) return "";
        String t = InlineMd.plain(SCRIPT_BLOCK.matcher(s).replaceAll(""));
        t = TAG.matcher(SCRIPT_BLOCK.matcher(t).replaceAll("")).replaceAll("");
        t = t.replace('|', '/').replace("`", "").replace('<', '‹').replace('>', '›');
        t = t.replace('\n', ' ').replaceAll("\\s{2,}", " ").trim();
        return t.length() <= max ? t : t.substring(0, Math.max(0, max - 1)) + "…";
    }

    /**
     * 富内容关、用户这句明确要表格时的终稿：只留表格（单元格消毒成纯文本、限行列），表格外的块一律压成纯文本句子，
     * 代码 / 图 / 链接 / HTML 全部不放行。没有表格 → 返回 null（调用方照原来的纯文本口径处理）。
     */
    public static String tableOnly(String raw, int maxRows, int maxCols, int maxCell, int maxTotal) {
        if (raw == null) return null;
        String t = SCRIPT_BLOCK.matcher(raw.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("").trim();
        if (t.indexOf('|') < 0) return null;
        List<RichBlock> blocks = MarkdownBlocks.parse(t);
        StringBuilder out = new StringBuilder();
        boolean table = false;
        int prose = 0;
        for (RichBlock b : blocks) {
            String piece;
            switch (b.kind) {
                case TABLE: {
                    if (table) continue;   // 只放一个表
                    int cols = Math.min(maxCols, b.header.size());
                    if (cols < 1) continue;
                    StringBuilder tb = new StringBuilder();
                    tb.append('|');
                    for (int c = 0; c < cols; c++) tb.append(' ').append(cellOrDash(b.header.get(c), maxCell)).append(" |");
                    tb.append("\n|");
                    for (int c = 0; c < cols; c++) tb.append("---|");
                    int rows = Math.min(maxRows, b.rows.size());
                    for (int r = 0; r < rows; r++) {
                        tb.append("\n|");
                        List<String> row = b.rows.get(r);
                        for (int c = 0; c < cols; c++) tb.append(' ').append(cellOrDash(c < row.size() ? row.get(c) : "", maxCell)).append(" |");
                    }
                    piece = tb.toString();
                    table = true;
                    break;
                }
                case CODE:
                case DIAGRAM:
                case RULE:
                    continue;
                case LIST: {
                    StringBuilder l = new StringBuilder();
                    for (String it : b.items) {
                        if (l.length() > 0) l.append('；');
                        l.append(plainCell(it.replace("\t", ""), 200));
                    }
                    piece = l.toString();
                    break;
                }
                default:
                    piece = plainCell(b.text, 300);
            }
            if (piece.length() == 0) continue;
            if (b.kind != RichBlock.Kind.TABLE) {
                if (prose >= 240) continue;   // 表格外最多一两句
                prose += piece.length();
            }
            if (out.length() + piece.length() + 2 > maxTotal) break;
            if (out.length() > 0) out.append("\n\n");
            out.append(piece);
        }
        return table ? out.toString().trim() : null;
    }

    private static String cellOrDash(String s, int max) {
        String c = plainCell(s, max);
        return c.length() == 0 ? "-" : c;
    }

    /** 表格回答：有表格、没有代码 / 图 / 链接 / HTML（即 {@link #tableOnly} 的产物形态）。 */
    public static boolean isTableAnswer(String text) {
        if (text == null || text.indexOf('|') < 0) return false;
        if (text.contains("```") || text.contains("~~~") || text.contains("](") || TAG.matcher(text).find()) return false;
        boolean table = false;
        for (RichBlock b : MarkdownBlocks.parse(text)) {
            if (b.kind == RichBlock.Kind.CODE || b.kind == RichBlock.Kind.DIAGRAM) return false;
            if (b.kind == RichBlock.Kind.TABLE) table = true;
        }
        return table;
    }

    /** 第一张表的表头签名（宿主据此认「这是放行过的表格回答」）；没有表 → null。 */
    public static String tableSignature(String text) {
        if (text == null || text.indexOf('|') < 0) return null;
        for (RichBlock b : MarkdownBlocks.parse(text)) {
            if (b.kind == RichBlock.Kind.TABLE) return join(b.header, "|");
        }
        return null;
    }

    private static String join(List<String> cells, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(InlineMd.plain(cells.get(i)));
        }
        return b.toString();
    }
}
