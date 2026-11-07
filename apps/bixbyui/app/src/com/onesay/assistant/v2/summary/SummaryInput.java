package com.onesay.assistant.v2.summary;

import com.onesay.assistant.v2.vision.screenref.UiNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [summary] 把识屏文字树（既有 {@code uiautomator dump --compressed} 的节点）整理成给大模型的输入，带<b>严格字数预算</b>。纯 Java，JVM 可测。
 *
 * <h3>整理</h3>
 * <ol>
 *   <li>按 dump 顺序（≈ 阅读顺序；WebView 是 DOM 顺序）取每个节点的可读文字（text，退而 content-desc）；</li>
 *   <li>去掉界面零件：可点击的 ≤4 字短标签（返回 / 分享 / 关注 / 点赞）、纯计数（「1.2万」「99+」）、完全重复的行；</li>
 *   <li>单行超过 {@link #LINE_MAX} 字：保留头 + 尾，中间标「…」。</li>
 * </ol>
 * <h3>截断（总字数超过预算时）</h3>
 * 头部 35% + 尾部 20% + 剩下的给<b>当前屏幕上可见的段落</b>（节点与屏幕可见区相交、且 ≥{@link #PARA_MIN} 字的优先），
 * 再按原顺序拼回去，被跳过的地方插入「……（中间省略 N 字）……」。WebView 会把屏幕外的整篇正文也吐进 dump，
 * 所以「头 + 尾 + 眼前这一屏」比单纯截前 N 字更接近用户想要的「这篇讲了什么」。
 */
public final class SummaryInput {

    private SummaryInput() {}

    /** 给模型的屏幕文字上限（字符）。约 2.5k token，DeepSeek 一次调用 1–3 秒。 */
    public static final int BUDGET = 3000;
    /** 少于这么多字 → 文字树不够用，走截图 + 视觉模型兜底。 */
    public static final int MIN_CHARS = 60;
    public static final int LINE_MAX = 500;
    public static final int PARA_MIN = 15;

    /** 浏览器地址栏 / 纯网址行：不是页面内容，不计入字数（否则三星浏览器只读到地址栏也会凑够 60 字进文字路径）。 */
    static final Pattern URL_LINE = Pattern.compile("^(?:https?|file|content|about|chrome)://\\S*$|^www\\.\\S+$|^(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?::\\d+)?/\\S*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNTER = Pattern.compile("^[\\d.,，]+[万千kKwW+]?\\+?$|^\\d{1,2}:\\d{2}$");

    /** 整理结果。 */
    public static final class Built {
        public final String text;
        /** 整理后（截断前）的总字数。 */
        public final int totalChars;
        /** 实际交给模型的字数（不含省略标记）。 */
        public final int keptChars;
        public final int lines;
        public final int keptLines;
        public final boolean truncated;
        /** 被省略的字数。 */
        public final int omittedChars;

        Built(String text, int totalChars, int keptChars, int lines, int keptLines, boolean truncated, int omittedChars) {
            this.text = text;
            this.totalChars = totalChars;
            this.keptChars = keptChars;
            this.lines = lines;
            this.keptLines = keptLines;
            this.truncated = truncated;
            this.omittedChars = omittedChars;
        }

        public boolean enough() {
            return totalChars >= MIN_CHARS;
        }

        @Override public String toString() {
            return "chars=" + totalChars + " kept=" + keptChars + " lines=" + keptLines + "/" + lines
                    + (truncated ? " truncated omitted=" + omittedChars : "");
        }
    }

    private static final class Line {
        final String text;
        final boolean visible;

        Line(String text, boolean visible) {
            this.text = text;
            this.visible = visible;
        }
    }

    /**
     * @param nodes   dump 节点（已由 UiDumpParser 去掉自家面板、密码节点，并给卡号/身份证打码）
     * @param screenH 屏幕高度像素（≤0 = 不知道，全部当可见）
     * @param budget  字数预算
     */
    public static Built fromNodes(List<UiNode> nodes, int screenH, int budget) {
        List<Line> lines = new ArrayList<Line>();
        Set<String> seen = new LinkedHashSet<String>();
        if (nodes != null) {
            for (UiNode n : nodes) {
                String t = clean(n.label());
                if (t.length() == 0) continue;
                if (n.clickable && t.length() <= 4 && !hasSentencePunct(t)) continue;   // 按钮 / 标签页
                if (COUNTER.matcher(t).matches()) continue;
                if (URL_LINE.matcher(t).matches()) continue;
                if (!seen.add(t)) continue;
                boolean hasBounds = n.right > n.left && n.bottom > n.top;
                boolean visible = screenH <= 0 || !hasBounds || (n.bottom > 0 && n.top < screenH);
                lines.add(new Line(capLine(t), visible));
            }
        }
        return assemble(lines, budget);
    }

    /** 纯文字行（测试 / 其它来源）：全部视为可见。 */
    public static Built fromLines(List<String> texts, int budget) {
        List<Line> lines = new ArrayList<Line>();
        Set<String> seen = new LinkedHashSet<String>();
        if (texts != null) {
            for (String s : texts) {
                String t = clean(s);
                if (t.length() == 0 || !seen.add(t)) continue;
                lines.add(new Line(capLine(t), true));
            }
        }
        return assemble(lines, budget);
    }

    private static Built assemble(List<Line> lines, int budget) {
        int total = 0;
        for (Line l : lines) total += l.text.length();
        if (budget <= 0) budget = BUDGET;
        if (total <= budget) {
            StringBuilder sb = new StringBuilder();
            for (Line l : lines) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(l.text);
            }
            return new Built(sb.toString(), total, total, lines.size(), lines.size(), false, 0);
        }
        boolean[] keep = new boolean[lines.size()];
        int headBudget = budget * 35 / 100;
        int tailBudget = budget * 20 / 100;
        int used = 0;
        // 头
        int h = 0;
        for (int i = 0; i < lines.size(); i++) {
            int len = lines.get(i).text.length();
            if (h + len > headBudget && h > 0) break;
            keep[i] = true;
            h += len;
        }
        used += h;
        // 尾
        int t = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (keep[i]) break;
            int len = lines.get(i).text.length();
            if (t + len > tailBudget) break;
            keep[i] = true;
            t += len;
        }
        used += t;
        // 可见段落：先长段落，再其余可见行
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < lines.size(); i++) {
                if (keep[i]) continue;
                Line l = lines.get(i);
                if (!l.visible) continue;
                if (pass == 0 && l.text.length() < PARA_MIN) continue;
                if (used + l.text.length() > budget) continue;
                keep[i] = true;
                used += l.text.length();
            }
        }
        StringBuilder sb = new StringBuilder();
        int kept = 0, keptLines = 0, gap = 0;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).text;
            if (!keep[i]) {
                gap += s.length();
                continue;
            }
            if (gap > 0) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("……（中间省略 ").append(gap).append(" 字）……");
                gap = 0;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
            kept += s.length();
            keptLines++;
        }
        if (gap > 0) sb.append("\n……（后面省略 ").append(gap).append(" 字）……");
        return new Built(sb.toString(), total, kept, lines.size(), keptLines, true, total - kept);
    }

    static String clean(String s) {
        if (s == null) return "";
        return s.replace('\u00a0', ' ').replaceAll("[\\u200b\\u200c\\u200d\\ufeff]", "").replaceAll("\\s+", " ").trim();
    }

    static String capLine(String t) {
        if (t.length() <= LINE_MAX) return t;
        return t.substring(0, LINE_MAX * 7 / 10) + "…" + t.substring(t.length() - LINE_MAX * 3 / 10 + 1);
    }

    private static boolean hasSentencePunct(String t) {
        return t.matches(".*[，。！？,.!?：:].*");
    }
}
