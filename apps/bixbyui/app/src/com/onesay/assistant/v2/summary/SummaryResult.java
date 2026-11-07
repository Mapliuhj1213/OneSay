package com.onesay.assistant.v2.summary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [summary] 解析模型输出 → 一句话 + 3–5 条要点；格式不对时兜底（纯 Java，JVM 可测）。
 *
 * <ul>
 *   <li>{@code OK}：按格式拿到一句话和 ≥1 条要点；</li>
 *   <li>{@code FALLBACK}：模型没守格式（整段话 / 只有要点没有一句话）→ 按句切成要点、取第一句作一句话；</li>
 *   <li>{@code SENSITIVE} / {@code EMPTY}：模型报告是密码验证码界面 / 看不出主题；</li>
 *   <li>{@code FAIL}：空输出。</li>
 * </ul>
 * 每条要点 ≤{@link #BULLET_MAX} 字、最多 {@link #MAX_BULLETS} 条，一句话 ≤{@link #GIST_MAX} 字；输出再过一遍 {@link SummaryGuard#scrub}。
 */
public final class SummaryResult {

    public enum Kind { OK, FALLBACK, SENSITIVE, EMPTY, FAIL }

    public static final int MAX_BULLETS = 5;
    public static final int BULLET_MAX = 40;
    public static final int GIST_MAX = 50;

    public final Kind kind;
    public final String gist;
    public final List<String> bullets;
    /** 输出里被擦掉的疑似敏感行数。 */
    public final int scrubbed;

    /** 敏感页拒绝（看图路径用：输出里被擦掉了验证码 / 卡号）。 */
    public static SummaryResult sensitive() {
        return new SummaryResult(Kind.SENSITIVE, null, null, 0);
    }

    SummaryResult(Kind kind, String gist, List<String> bullets, int scrubbed) {
        this.kind = kind;
        this.gist = gist == null ? "" : gist;
        this.bullets = bullets == null ? Collections.<String>emptyList() : Collections.unmodifiableList(bullets);
        this.scrubbed = scrubbed;
    }

    public boolean usable() {
        return (kind == Kind.OK || kind == Kind.FALLBACK) && (gist.length() > 0 || !bullets.isEmpty());
    }

    private static final Pattern GIST_LINE = Pattern.compile(
            "^(?:一句话(?:总结|概括)?|主旨|一句话主旨|总结|概括|核心|tl;?dr)\\s*[:：]\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET_LINE = Pattern.compile("^(?:[-*•·●▪◦]|\\d{1,2}[.、)）]|[（(]\\d{1,2}[)）])\\s*(.+)$");
    private static final Pattern HEADER_LINE = Pattern.compile("^(?:要点|重点|主要内容|关键点)\\s*[:：]?\\s*$");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？；!?;])|\\n");

    public static SummaryResult parse(String raw) {
        if (raw == null) return new SummaryResult(Kind.FAIL, null, null, 0);
        String s = raw.replace("\r", "").replace("**", "").replace("`", "").trim();
        if (s.length() == 0) return new SummaryResult(Kind.FAIL, null, null, 0);
        String head = s.replaceAll("[\\s。.]", "");
        if (head.equalsIgnoreCase(SummaryPrompt.TOKEN_SENSITIVE) || (head.toUpperCase().startsWith(SummaryPrompt.TOKEN_SENSITIVE) && head.length() < 24)) {
            return new SummaryResult(Kind.SENSITIVE, null, null, 0);
        }
        if (head.equalsIgnoreCase(SummaryPrompt.TOKEN_EMPTY) || (head.toUpperCase().startsWith(SummaryPrompt.TOKEN_EMPTY) && head.length() < 24)) {
            return new SummaryResult(Kind.EMPTY, null, null, 0);
        }
        String gist = null;
        List<String> bullets = new ArrayList<String>();
        List<String> loose = new ArrayList<String>();
        for (String line0 : s.split("\n")) {
            String line = line0.replaceAll("^#+\\s*", "").trim();
            if (line.length() == 0 || HEADER_LINE.matcher(line).matches()) continue;
            Matcher g = GIST_LINE.matcher(line);
            if (g.matches() && gist == null) {
                gist = g.group(1).trim();
                continue;
            }
            Matcher b = BULLET_LINE.matcher(line);
            if (b.matches()) {
                bullets.add(b.group(1).trim());
                continue;
            }
            loose.add(line);
        }
        Kind kind = Kind.OK;
        if (bullets.isEmpty()) {
            // 没守格式：整段话 → 按句切
            kind = Kind.FALLBACK;
            StringBuilder all = new StringBuilder();
            for (String l : loose) all.append(l).append('\n');
            for (String sen : SENTENCE_SPLIT.split(all.toString())) {
                String t = sen.trim();
                if (t.length() >= 4) bullets.add(t);
            }
            if (gist == null && !bullets.isEmpty()) gist = bullets.remove(0);
        } else if (gist == null) {
            kind = Kind.FALLBACK;
            gist = loose.isEmpty() ? bullets.get(0) : loose.get(0);
        }
        int[] removed = new int[1];
        List<String> clean = new ArrayList<String>();
        for (String b : SummaryGuard.scrub(bullets, removed)) {
            String t = clip(stripEnd(b), BULLET_MAX);
            if (t.length() > 0 && !clean.contains(t) && clean.size() < MAX_BULLETS) clean.add(t);
        }
        int scrubbed = removed[0];
        if (gist != null && SummaryGuard.outputLineSensitive(gist)) {
            gist = null;
            scrubbed++;
        }
        gist = gist == null ? "" : clip(stripEnd(gist), GIST_MAX);
        if (gist.length() == 0 && clean.isEmpty()) {
            return new SummaryResult(scrubbed > 0 ? Kind.SENSITIVE : Kind.FAIL, null, null, scrubbed);
        }
        return new SummaryResult(kind, gist, clean, scrubbed);
    }

    /** 面板上的文字。 */
    public String format() {
        StringBuilder sb = new StringBuilder();
        if (gist.length() > 0) sb.append("一句话：").append(gist);
        for (String b : bullets) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("• ").append(b);
        }
        if (scrubbed > 0) sb.append("\n（已略去 ").append(scrubbed).append(" 条疑似敏感信息）");
        return sb.toString();
    }

    /** 「复制摘要」/「记下来」用的纯文本（一行，要点用「；」连接）。 */
    public String plain(int maxChars) {
        StringBuilder sb = new StringBuilder(gist);
        boolean first = true;
        for (String b : bullets) {
            if (sb.length() > 0) sb.append(first && gist.length() > 0 ? "：" : "；");
            sb.append(b);
            first = false;
        }
        return clip(sb.toString(), maxChars);
    }

    static String stripEnd(String s) {
        return s.replaceAll("[。；;，,]+$", "").trim();
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    @Override public String toString() {
        return kind + " gist=" + gist.length() + "字 bullets=" + bullets.size() + (scrubbed > 0 ? " scrubbed=" + scrubbed : "");
    }
}
