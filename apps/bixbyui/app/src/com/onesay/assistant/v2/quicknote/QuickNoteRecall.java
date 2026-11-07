package com.onesay.assistant.v2.quicknote;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * [quicknote] R10 按内容回忆的回答（纯 Java）：「我的取件码是多少」「车停哪了」「记一记里关于三亚的」「我记过的那个地址」。
 * 第 1 条朗读摘要，另外最多 2 条列在后面。搜不到返回 null（软句式留给 S2）。
 */
public final class QuickNoteRecall {

    private QuickNoteRecall() {}

    private static final Pattern PARK = Pattern.compile("车(子)?停|停车|车位");
    private static final String[] PARK_KEYS = {"车停", "停车", "车位", "停在"};

    public static String answer(QuickNoteBook book, String query, long now, TimeZone tz) {
        List<QuickNoteEntry> all = book.list();
        if (all.isEmpty()) return null;
        List<QuickNoteSearch.Hit> hits;
        String q = query == null ? "" : query;
        if (PARK.matcher(q).find()) {
            hits = new ArrayList<QuickNoteSearch.Hit>();
            for (String k : PARK_KEYS) {
                QuickNoteSearch.Query pq = new QuickNoteSearch.Query();
                pq.keywords = k;
                for (QuickNoteSearch.Hit h : QuickNoteSearch.search(all, pq, 3)) {
                    boolean dup = false;
                    for (QuickNoteSearch.Hit x : hits) if (x.e.id == h.e.id) dup = true;
                    if (!dup) hits.add(h);
                }
            }
            // 车停哪：最新的那条最有用
            java.util.Collections.sort(hits, new java.util.Comparator<QuickNoteSearch.Hit>() {
                @Override public int compare(QuickNoteSearch.Hit a, QuickNoteSearch.Hit b) {
                    return a.e.createdAt > b.e.createdAt ? -1 : (a.e.createdAt == b.e.createdAt ? 0 : 1);
                }
            });
        } else {
            hits = QuickNoteSearch.search(all, q, now, tz, 3);
        }
        if (hits.isEmpty()) return null;
        QuickNoteEntry first = hits.get(0).e;
        StringBuilder sb = new StringBuilder();
        if (first.hasCode()) {
            sb.append(first.codeKind).append(" ").append(first.codeValue);
            String where = QuickNoteHooksPure.where(first);
            if (where.length() > 0) sb.append("（").append(where).append("）");
            sb.append("，").append(book.when(first.createdAt, now).trim()).append("记的").append(QuickNoteBook.codeStateText(first));
        } else {
            sb.append(book.when(first.createdAt, now).trim()).append("记一记的：").append(first.summary);
            if (first.notes != null && first.notes.length() > 0) sb.append("（备注：").append(QuickNoteBook.clip(first.notes, 40)).append("）");
        }
        if (hits.size() > 1) {
            sb.append("\n还有：");
            for (int i = 1; i < hits.size(); i++) {
                QuickNoteEntry e = hits.get(i).e;
                sb.append("\n").append(i + 1).append(". ").append(book.when(e.createdAt, now).trim()).append(" ")
                        .append(QuickNoteBook.clip(e.hasCode() ? e.codeKind + " " + e.codeValue : e.summary, 36));
            }
        }
        return sb.toString();
    }

    public static String missReply(String query) {
        if (query != null && QuickNoteIntent.PARCEL_ASK.matcher(QuickNoteIntent.norm(query)).matches()) {
            return "记一记里还没有快递单号或取件码。把单号告诉我（比如「查快递 SF1234567890」），或在物流页面长按 Bixby 键记下来，我再帮你查；也可以打开菜鸟或淘宝的「我的物流」看。";
        }
        String cat = QuickNoteSearch.categoryWord(query);
        if (query != null && PARK.matcher(query).find()) return "记一记里没有停车位置。停好车可以说「随口记 车停在 B2-17」，或者长按 Bixby 键记下停车场的牌子。";
        if (cat != null) return "记一记里没有" + cat + "。在有" + cat + "的页面长按 Bixby 键就能记下来。";
        return "记一记里没找到相关的。可以说「记一记列表」看看都记了什么。";
    }

    /** 码所在的驿站 / 门店（摘要括号里那段）。 */
    static final class QuickNoteHooksPure {
        static String where(QuickNoteEntry e) {
            String s = e.summary == null ? "" : e.summary;
            int i = s.indexOf('（');
            if (i > 0 && s.endsWith("）")) return s.substring(i + 1, s.length() - 1);
            return e.appLabel == null ? "" : e.appLabel;
        }
    }
}
