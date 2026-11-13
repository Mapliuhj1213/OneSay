package com.onesay.clock.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 新闻多源合并（纯函数）：清洗（不改写）→ 源内去重 → 选源。
 *   首选能凑够 5 条的最高优先级源（腾讯 > 头条 > B站），引导句念该源；
 *   都凑不够 → 按优先级轮流合并、跨源近似去重（字符二元组 Jaccard ≥ 0.5），引导句念「网络」。
 * 结果是候选池（最多 12 条，按顺序）：合成时某条失败就顺延下一条。
 */
public final class NewsMerge {
    private NewsMerge() { }

    public static final int POOL = 12, MAX_LEN = 48;

    public static final class Result {
        public final List<String> titles = new ArrayList<String>();
        public String sourceKey = "";
    }

    /** @param bySource 有序：源键 → 原始标题（可空） */
    public static Result pick(Map<String, List<String>> bySource, int want) {
        Result r = new Result();
        Map<String, List<String>> clean = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> e : bySource.entrySet()) clean.put(e.getKey(), dedupe(e.getValue()));
        for (Map.Entry<String, List<String>> e : clean.entrySet()) {
            if (e.getValue().size() >= want) {
                r.sourceKey = e.getKey();
                for (String t : e.getValue()) { if (r.titles.size() >= POOL) break; r.titles.add(t); }
                return r;
            }
        }
        List<List<String>> lists = new ArrayList<List<String>>(clean.values());
        for (int i = 0; r.titles.size() < POOL; i++) {
            boolean any = false;
            for (List<String> l : lists) {
                if (i >= l.size()) continue;
                any = true;
                String t = l.get(i);
                if (!similarToAny(t, r.titles)) r.titles.add(t);
                if (r.titles.size() >= POOL) break;
            }
            if (!any) break;
        }
        if (!r.titles.isEmpty()) r.sourceKey = "net";
        return r;
    }

    static List<String> dedupe(List<String> raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        for (String s : raw) {
            String t = clean(s);
            if (t.length() < 4 || similarToAny(t, out)) continue;
            out.add(t);
        }
        return out;
    }

    static boolean similarToAny(String t, List<String> list) {
        for (String x : list) if (x.equals(t) || jaccard(x, t) >= 0.5) return true;
        return false;
    }

    static Set<String> bigrams(String s) {
        Set<String> b = new HashSet<String>();
        String t = s.replaceAll("[\\s，,。.！!？?：:、]", "");
        for (int i = 0; i + 1 < t.length(); i++) b.add(t.substring(i, i + 2));
        return b;
    }

    public static double jaccard(String a, String b) {
        Set<String> x = bigrams(a), y = bigrams(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        int inter = 0;
        for (String s : x) if (y.contains(s)) inter++;
        return inter / (double) (x.size() + y.size() - inter);
    }

    /**
     * 标题清洗（只为能念，不改写）：去网址、开头【栏目】、「栏目丨」前缀、括号与符号、emoji，
     * 「丨|｜」换逗号，合并空白，去首尾标点；超过 MAX_LEN 截到最后一个逗号前（避免念半句）。
     */
    public static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("https?://\\S+", " ").trim();
        t = t.replaceAll("^\\s*[【\\[［][^】\\]］]{0,10}[】\\]］]\\s*", "");
        int k = indexOfSep(t);
        if (k > 0 && k <= 8 && k < t.length() - 4) t = t.substring(k + 1);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) continue;
            if (c >= 0x2600 && c <= 0x27BF) continue;
            if (c == 0xFE0F || c == 0x200D) continue;
            if ("丨|｜".indexOf(c) >= 0) { sb.append('，'); continue; }
            if ("【】〔〕［］[]（）(){}《》〈〉「」『』“”\"#*_~^`<>\\".indexOf(c) >= 0) continue;
            if (c < 0x20 || c == '\u3000' || c == '\t') { sb.append(' '); continue; }
            sb.append(c);
        }
        String r = sb.toString().replaceAll("\\s+", " ").replaceAll("[，,]{2,}", "，").trim();
        if (r.length() > MAX_LEN) {
            int cut = Math.max(r.lastIndexOf('，', MAX_LEN), r.lastIndexOf(',', MAX_LEN));
            r = r.substring(0, cut > 10 ? cut : MAX_LEN);
        }
        r = trimPunct(r);
        return r;
    }

    static String trimPunct(String r) {
        while (r.length() > 0 && "。！？!?.；;，,、 ：:".indexOf(r.charAt(r.length() - 1)) >= 0) r = r.substring(0, r.length() - 1);
        while (r.length() > 0 && "，,、：: ".indexOf(r.charAt(0)) >= 0) r = r.substring(1);
        return r;
    }

    private static int indexOfSep(String t) {
        for (int i = 0; i < t.length(); i++) if ("丨|｜".indexOf(t.charAt(i)) >= 0) return i;
        return -1;
    }
}
