/*
 * [富内容输出插件 · core] OneSay 自写。只依赖 java.*，JVM 单测直接跑；宿主（Android）把块画成 View。
 */
package com.onesay.assistant.plug.richout.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 回答里的一个块（Markdown 分块结果）。不可变。
 *
 * <ul>
 *   <li>{@link Kind#PARAGRAPH} / {@link Kind#HEADING} / {@link Kind#QUOTE}：{@link #text} 是带行内 Markdown 的原文（宿主用 {@link InlineMd} 解析）；</li>
 *   <li>{@link Kind#LIST}：{@link #items} 每项一条（行内 Markdown 原文），{@link #ordered}、{@link #start}；</li>
 *   <li>{@link Kind#TABLE}：{@link #header}（列名）、{@link #rows}（每行单元格）、{@link #aligns}（L / C / R）；</li>
 *   <li>{@link Kind#CODE}：{@link #lang}（小写，可空串）、{@link #text}（代码原文，不含围栏），{@link #open} = 围栏还没合上（流式中）；</li>
 *   <li>{@link Kind#DIAGRAM}：{@link #lang} 是 svg / mermaid / chart，{@link #text} 是源码；</li>
 *   <li>{@link Kind#RULE}：分隔线。</li>
 * </ul>
 */
public final class RichBlock {

    public enum Kind { PARAGRAPH, HEADING, LIST, QUOTE, TABLE, CODE, DIAGRAM, RULE }

    public final Kind kind;
    public final String text;
    public final int level;
    public final String lang;
    public final boolean open;
    public final boolean ordered;
    public final int start;
    public final List<String> items;
    public final List<String> header;
    public final List<List<String>> rows;
    public final List<Character> aligns;

    private RichBlock(Kind kind, String text, int level, String lang, boolean open, boolean ordered, int start,
                      List<String> items, List<String> header, List<List<String>> rows, List<Character> aligns) {
        this.kind = kind;
        this.text = text == null ? "" : text;
        this.level = level;
        this.lang = lang == null ? "" : lang;
        this.open = open;
        this.ordered = ordered;
        this.start = start;
        this.items = items == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(items));
        this.header = header == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(header));
        List<List<String>> rr = new ArrayList<List<String>>();
        if (rows != null) for (List<String> r : rows) rr.add(Collections.unmodifiableList(new ArrayList<String>(r)));
        this.rows = Collections.unmodifiableList(rr);
        this.aligns = aligns == null ? Collections.<Character>emptyList() : Collections.unmodifiableList(new ArrayList<Character>(aligns));
    }

    public static RichBlock paragraph(String t) {
        return new RichBlock(Kind.PARAGRAPH, t, 0, null, false, false, 0, null, null, null, null);
    }

    public static RichBlock heading(int level, String t) {
        return new RichBlock(Kind.HEADING, t, Math.max(1, Math.min(6, level)), null, false, false, 0, null, null, null, null);
    }

    public static RichBlock quote(String t) {
        return new RichBlock(Kind.QUOTE, t, 0, null, false, false, 0, null, null, null, null);
    }

    public static RichBlock list(boolean ordered, int start, List<String> items) {
        return new RichBlock(Kind.LIST, null, 0, null, false, ordered, start, items, null, null, null);
    }

    public static RichBlock table(List<String> header, List<List<String>> rows, List<Character> aligns) {
        return new RichBlock(Kind.TABLE, null, 0, null, false, false, 0, null, header, rows, aligns);
    }

    public static RichBlock code(String lang, String code, boolean open) {
        return new RichBlock(Kind.CODE, code, 0, lang, open, false, 0, null, null, null, null);
    }

    public static RichBlock diagram(String kind, String src, boolean open) {
        return new RichBlock(Kind.DIAGRAM, src, 0, kind, open, false, 0, null, null, null, null);
    }

    public static RichBlock rule() {
        return new RichBlock(Kind.RULE, null, 0, null, false, false, 0, null, null, null, null);
    }

    /** 代码或图（朗读要跳过、渲染要开卡片的块）。 */
    public boolean isVisual() {
        return kind == Kind.CODE || kind == Kind.DIAGRAM || kind == Kind.TABLE;
    }

    @Override public String toString() {
        switch (kind) {
            case HEADING: return "H" + level + "(" + text + ")";
            case LIST: return (ordered ? "OL" : "UL") + items;
            case TABLE: return "TABLE" + header + rows;
            case CODE: return "CODE[" + lang + (open ? ",open" : "") + "](" + text.length() + ")";
            case DIAGRAM: return "DIAGRAM[" + lang + (open ? ",open" : "") + "](" + text.length() + ")";
            case RULE: return "HR";
            case QUOTE: return "QUOTE(" + text + ")";
            default: return "P(" + text + ")";
        }
    }
}
