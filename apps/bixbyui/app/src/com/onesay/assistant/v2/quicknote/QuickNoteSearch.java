package com.onesay.assistant.v2.quicknote;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [quicknote] R10 按内容回忆（纯 Java，零网络）：标题 / 标签 &gt; 摘要 &gt; 备注 &gt; 全文 &gt; 来源 App；中文 2-gram 重叠 + 整串加分；
 * 时间词（今天 / 昨天 / 前天 / 本周 / 上周 / 本月 / 上个月）过滤；类别词（取件码 / 订单 / 文章 …）直接过滤；同分新的在前。
 */
public final class QuickNoteSearch {

    private QuickNoteSearch() {}

    public static final class Hit {
        public final QuickNoteEntry e;
        public final double score;

        Hit(QuickNoteEntry e, double score) {
            this.e = e;
            this.score = score;
        }

        @Override public String toString() {
            return e.id + ":" + String.format(java.util.Locale.ROOT, "%.2f", score);
        }
    }

    /** 解析后的查询。 */
    public static final class Query {
        public String category;      // null = 不限
        public long from, to;        // 0 = 不限
        public String keywords = ""; // 去掉虚词、类别词、时间词之后剩下的

        @Override public String toString() {
            return "cat=" + category + " kw=" + keywords + (from > 0 ? " from=" + from : "");
        }
    }

    static final String[][] CATS = {
            {"取件码", "取件码", "取货码", "提货码", "快递码"},
            {"取餐码", "取餐码", "取餐号", "餐号"},
            {"券码", "券码", "核销码", "兑换码", "优惠券", "券"},
            {"入园码", "入园码"},
            {"快递", "快递单号", "快递", "运单", "单号"},
            {"订单", "订单", "账单", "小票", "消费", "付款记录", "账"},
            {"日程", "日程", "安排", "活动时间", "开售", "抢票"},
            {"地址", "地址", "位置"},
            {"聊天", "聊天记录", "聊天"},
            {"文章", "文章", "攻略", "帖子", "笔记"},
            {"联系人", "联系人", "名片", "电话号码", "手机号"},
            {"随口记", "随口记", "灵感", "语音备忘"},
            {"码", "码"},
    };

    private static final Pattern FILLER = Pattern.compile(
            "记一记里(面)?|记一记|随口记的|我(之前|上次|刚才|刚刚|刚)?(记过|记下|记了|记)的?|帮我(找|查|翻)(一下|找|下)?|(找|查|翻)(一下|找|下)|有没有|关于|有关|那个|那条|这条|那些|这个|"
                    + "是多少|是什么|是啥|是哪个|多少号|在哪(里|儿)?|哪(里|儿)?了|哪了|哪儿|哪里|叫什么|什么|号码是|内容|"
                    + "里的|里|的|呢|吗|啊|呀|吧|我|你|了|过|一下|都|有|哪些|几条|给我|说说|念一下|看看|告诉我|现在");
    private static final Pattern TIME = Pattern.compile("今天|昨天|前天|本周|这周|这个星期|上周|上个星期|本月|这个月|上个月|最近");

    public static Query parse(String q, long now, TimeZone tz) {
        Query out = new Query();
        String k = q == null ? "" : q.replaceAll("[。！!？?，,、\\s]", "");
        Matcher tm = TIME.matcher(k);
        if (tm.find()) {
            long[] r = range(tm.group(), now, tz);
            out.from = r[0];
            out.to = r[1];
            k = k.replace(tm.group(), "");
        }
        out.category = categoryWord(k);
        if (out.category != null) {
            for (String[] row : CATS) {
                if (!row[0].equals(out.category)) continue;
                for (int i = 1; i < row.length; i++) k = k.replace(row[i], "");
            }
        }
        String prev;
        do {
            prev = k;
            k = FILLER.matcher(k).replaceAll("");
        } while (!k.equals(prev));
        out.keywords = k;
        return out;
    }

    /** 句子里的类别词（先长后短）。 */
    public static String categoryWord(String q) {
        if (q == null) return null;
        String best = null;
        int bestLen = 0;
        for (String[] row : CATS) {
            for (int i = 1; i < row.length; i++) {
                if (q.contains(row[i]) && row[i].length() > bestLen) {
                    best = row[0];
                    bestLen = row[i].length();
                }
            }
        }
        return best;
    }

    public static boolean inCategory(QuickNoteEntry e, String cat) {
        if (cat == null) return true;
        if ("码".equals(cat)) return e.hasCode();
        if ("订单".equals(cat)) return "订单".equals(e.category) || e.tags.contains("账单");
        if ("日程".equals(cat)) return "日程".equals(e.category) || e.tags.contains("开售") || e.eventAt > 0 && "随口记".equals(e.category);
        if ("其他".equals(cat)) return "其他".equals(e.group());
        return cat.equals(e.category) || e.tags.contains(cat) || cat.equals(e.codeKind);
    }

    static long[] range(String w, long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long day0 = c.getTimeInMillis();
        long D = 24L * 3600L * 1000L;
        if ("今天".equals(w)) return new long[]{day0, day0 + D};
        if ("昨天".equals(w)) return new long[]{day0 - D, day0};
        if ("前天".equals(w)) return new long[]{day0 - 2 * D, day0 - D};
        if ("最近".equals(w)) return new long[]{now - 7 * D, now + D};
        if (w.startsWith("本月") || w.startsWith("这个月") || w.startsWith("上个月")) {
            c.set(Calendar.DAY_OF_MONTH, 1);
            long m0 = c.getTimeInMillis();
            if (w.startsWith("上个月")) {
                c.add(Calendar.MONTH, -1);
                return new long[]{c.getTimeInMillis(), m0};
            }
            c.add(Calendar.MONTH, 1);
            return new long[]{m0, c.getTimeInMillis()};
        }
        int dow = c.get(Calendar.DAY_OF_WEEK);   // 周一为一周开始
        int back = (dow + 5) % 7;
        long w0 = day0 - back * D;
        if (w.startsWith("上")) return new long[]{w0 - 7 * D, w0};
        return new long[]{w0, w0 + 7 * D};
    }

    /** 检索：最多 limit 条；没有关键词时按类别 / 时间过滤后取最新（码类在岛上的优先）。 */
    public static List<Hit> search(List<QuickNoteEntry> all, String query, long now, TimeZone tz, int limit) {
        Query q = parse(query, now, tz);
        return search(all, q, limit);
    }

    public static List<Hit> search(List<QuickNoteEntry> all, Query q, int limit) {
        List<Hit> hits = new ArrayList<Hit>();
        Set<String> grams = grams(q.keywords);
        for (QuickNoteEntry e : all) {
            if (e.deleted) continue;
            if (!inCategory(e, q.category)) continue;
            if (q.from > 0 && (e.createdAt < q.from || e.createdAt >= q.to)) continue;
            double s;
            if (q.keywords.length() == 0) {
                if (q.category == null && q.from == 0) continue;   // 什么条件都没有：不乱答
                s = 1.0 + (e.codeState == QuickNoteEntry.CODE_ON_ISLAND ? 0.5 : 0);
            } else {
                s = 3.0 * field(e.title, q.keywords, grams)
                        + 2.5 * field(join(e.tags), q.keywords, grams)
                        + 2.0 * field(e.summary, q.keywords, grams)
                        + 1.5 * field(e.notes, q.keywords, grams)
                        + 1.0 * field(e.body, q.keywords, grams)
                        + 1.0 * field(e.appLabel, q.keywords, grams);
                if (s < 0.9) continue;
            }
            hits.add(new Hit(e, s));
        }
        Collections.sort(hits, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                if (Math.abs(a.score - b.score) > 1e-6) return a.score > b.score ? -1 : 1;
                return a.e.createdAt > b.e.createdAt ? -1 : (a.e.createdAt == b.e.createdAt ? 0 : 1);
            }
        });
        return hits.size() > limit ? new ArrayList<Hit>(hits.subList(0, limit)) : hits;
    }

    /** 一个字段的得分 0..2：2-gram 覆盖率 + 整串命中加 1。 */
    static double field(String text, String kw, Set<String> grams) {
        if (text == null || text.length() == 0 || kw.length() == 0) return 0;
        String t = text.toLowerCase();
        String k = kw.toLowerCase();
        double s = 0;
        if (t.contains(k)) s += 1.0;
        if (grams.isEmpty()) return s;
        int n = 0;
        for (String g : grams) if (t.contains(g)) n++;
        return s + (double) n / grams.size();
    }

    static Set<String> grams(String kw) {
        Set<String> g = new HashSet<String>();
        String k = kw == null ? "" : kw.toLowerCase();
        if (k.length() == 1) {
            g.add(k);
            return g;
        }
        for (int i = 0; i + 2 <= k.length(); i++) g.add(k.substring(i, i + 2));
        return g;
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s).append(' ');
        return sb.toString();
    }
}
