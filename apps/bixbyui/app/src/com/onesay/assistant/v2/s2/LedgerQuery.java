package com.onesay.assistant.v2.s2;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [s2] 账本查询（纯 Java）：「这个月花了多少」「本周吃饭花了多少」「昨天花了多少钱」「最近记的账」。
 * 按<b>消费日期</b>（{@link MemoryEntry#day}）落区间求和，周一为一周第一天，金额人民币。
 */
public final class LedgerQuery {

    private LedgerQuery() {}

    public static final class Query {
        /** [startDay, endDay) yyyy-MM-dd，字典序可比。 */
        public String startDay;
        public String endDay;
        /** 「这个月」「本周」…（回答用）。 */
        public String label;
        /** 分类过滤（null = 全部）。 */
        public String category;
        /** 「最近记的账」：列最近几笔。 */
        public boolean listRecent;

        @Override public String toString() {
            return "[" + startDay + "," + endDay + ") " + label + (category != null ? " cat=" + category : "") + (listRecent ? " list" : "");
        }
    }

    private static final Pattern SPEND_WORD = Pattern.compile("花了|花掉|花费|花销|花钱|消费|开销|支出|开支|用了多少钱");
    private static final Pattern AGG_WORD = Pattern.compile("多少|几块|几百|几千|总共|一共|合计|统计|汇总|算一下|算算");
    private static final Pattern LIST_WORD = Pattern.compile("最近记的账|最近的账|最近几笔|最近记账|记的账|账单|账本|我的账|记过的账|看看账|查账|流水");
    private static final Pattern RECENT_N = Pattern.compile("(?:最近|近|过去)([0-9一二两三四五六七八九十]+)天");

    /** 不像账本查询返回 null。 */
    public static Query parse(String utterance, long nowMs, TimeZone tz) {
        String k = S2Text.key(utterance);
        if (k.length() == 0) return null;
        boolean list = LIST_WORD.matcher(k).find();
        boolean agg = SPEND_WORD.matcher(k).find() && AGG_WORD.matcher(k).find();
        if (!list && !agg) return null;
        // 「多少钱」+ 世界信息（「比特币多少钱」「油价多少」）不是记账：必须有花钱动词或账本词
        Query q = new Query();
        q.category = LedgerParser.queryCategory(k.replaceAll("花了|花费|花销|花钱|花", ""));
        if (q.category != null && "餐饮".equals(q.category) && !k.matches(".*(吃|饭|餐|外卖|奶茶|咖啡).*")) q.category = null;
        long dayStart = S2Text.dayStart(nowMs, tz);
        Calendar c = S2Text.cal(dayStart, tz);
        long start;
        long end = dayStart + 86400000L;
        Matcher rn = RECENT_N.matcher(k);
        if (k.contains("今天") || k.contains("今日") || k.contains("今儿")) {
            start = dayStart;
            q.label = "今天";
        } else if (k.contains("大前天")) {
            start = dayStart - 3 * 86400000L;
            end = start + 86400000L;
            q.label = "大前天";
        } else if (k.contains("前天")) {
            start = dayStart - 2 * 86400000L;
            end = start + 86400000L;
            q.label = "前天";
        } else if (k.contains("昨天") || k.contains("昨日")) {
            start = dayStart - 86400000L;
            end = dayStart;
            q.label = "昨天";
        } else if (k.contains("上周") || k.contains("上个星期") || k.contains("上星期") || k.contains("上礼拜")) {
            long mon = mondayOf(c);
            start = mon - 7 * 86400000L;
            end = mon;
            q.label = "上周";
        } else if (k.contains("本周") || k.contains("这周") || k.contains("这个星期") || k.contains("这星期") || k.contains("这礼拜")
                || k.contains("本星期") || k.contains("这一周")) {
            start = mondayOf(c);
            q.label = "本周";
        } else if (k.contains("上个月") || k.contains("上月")) {
            Calendar m = S2Text.cal(dayStart, tz);
            m.set(Calendar.DAY_OF_MONTH, 1);
            end = m.getTimeInMillis();
            m.add(Calendar.MONTH, -1);
            start = m.getTimeInMillis();
            q.label = "上个月";
        } else if (k.contains("今年") || k.contains("本年") || k.contains("这一年")) {
            Calendar m = S2Text.cal(dayStart, tz);
            m.set(Calendar.DAY_OF_YEAR, 1);
            start = m.getTimeInMillis();
            q.label = "今年";
        } else if (rn.find()) {
            Long n = LedgerParser.cnNumber(rn.group(1));
            int days = n == null ? 7 : (int) Math.max(1, Math.min(366, n.longValue()));
            start = dayStart - (days - 1) * 86400000L;
            q.label = "最近 " + days + " 天";
        } else if (list && !agg) {
            start = 0;
            q.listRecent = true;
            q.label = "最近";
        } else {
            Calendar m = S2Text.cal(dayStart, tz);
            m.set(Calendar.DAY_OF_MONTH, 1);
            start = m.getTimeInMillis();
            q.label = k.contains("这个月") || k.contains("本月") || k.contains("这月") ? "这个月" : "这个月";
        }
        q.startDay = start <= 0 ? "0000-00-00" : S2Text.day(start, tz);
        q.endDay = S2Text.day(end, tz);
        return q;
    }

    private static long mondayOf(Calendar dayStartCal) {
        Calendar m = (Calendar) dayStartCal.clone();
        int dow = m.get(Calendar.DAY_OF_WEEK);   // 周日=1
        int back = dow == Calendar.SUNDAY ? 6 : dow - Calendar.MONDAY;
        m.add(Calendar.DAY_OF_MONTH, -back);
        return m.getTimeInMillis();
    }

    /** 汇总结果。 */
    public static final class Sum {
        public long totalFen;
        public int count;
        /** 分类 → 分（按金额降序插入）。 */
        public final Map<String, Long> byCategory = new LinkedHashMap<String, Long>();
        public final List<MemoryEntry> items = new ArrayList<MemoryEntry>();
    }

    /** 在账目里按区间/分类求和（entries 新的在前）。 */
    public static Sum sum(List<MemoryEntry> entries, Query q) {
        Sum s = new Sum();
        Map<String, Long> raw = new LinkedHashMap<String, Long>();
        for (MemoryEntry e : entries) {
            if (!e.isLedger()) continue;
            if (!q.listRecent && (e.day.compareTo(q.startDay) < 0 || e.day.compareTo(q.endDay) >= 0)) continue;
            if (q.category != null && !q.category.equals(e.category)) continue;
            s.totalFen += e.amountFen;
            s.count++;
            s.items.add(e);
            Long v = raw.get(e.category);
            raw.put(e.category, Long.valueOf((v == null ? 0L : v.longValue()) + e.amountFen));
        }
        List<Map.Entry<String, Long>> l = new ArrayList<Map.Entry<String, Long>>(raw.entrySet());
        java.util.Collections.sort(l, new java.util.Comparator<Map.Entry<String, Long>>() {
            @Override public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        for (Map.Entry<String, Long> en : l) s.byCategory.put(en.getKey(), en.getValue());
        return s;
    }
}
