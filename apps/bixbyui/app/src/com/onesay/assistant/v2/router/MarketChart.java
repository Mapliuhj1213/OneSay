package com.onesay.assistant.v2.router;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ fix/shop-research（用户 11-04 00:13「行情类结论要画图展示」）：{@link MarketQuotes} 结构化指数 → 图表模型（纯 Java，JVM 可测）。
 * <ul>
 *   <li>按地区分组：A股 / 港股 / 美股 / 欧洲 / 日本（MarketQuotes 里的「日欧」拆成日本与欧洲）；</li>
 *   <li>每条：指数名、点位、涨跌幅（都是源里的原字符串，不换算）；涨跌幅数值只用来定柱长与颜色；</li>
 *   <li>颜色按 A 股习惯：红涨绿跌、平盘灰；拿不到的指数 {@link Bar#found} = false，显示「未查到」，不画柱；</li>
 *   <li>图下脚注：各地区行情时间与时区（非今日标「最近一个交易日」）+ 来源。</li>
 * </ul>
 * 界面（{@code ui.MarketChartView}，原生 Canvas 自绘，不联网、无新依赖）按结论文字认出这张图：
 * AgentLoop 收工时 {@link #withChart} 把图和这段结论一起放进进程内槽，{@link #match} 按结论文字指纹认领
 * （★ 11-04 用户：不再在正文里加「主要指数涨跌见上图（结构化行情源…）」这类内部说法）。
 */
public final class MarketChart {

    private MarketChart() {}

    /** 模型没写正文时的兜底一句（面向用户）。 */
    public static final String EMPTY_ANSWER = "主要指数涨跌如上。";

    public static final String[] REGIONS = {"A股", "港股", "美股", "欧洲", "日本"};

    /** 红涨绿跌（A 股习惯）、平盘灰。ARGB。 */
    public static final int UP = 0xFFF0454B;
    public static final int DOWN = 0xFF27B26B;
    public static final int FLAT = 0xFF8E8E9A;

    public static final class Bar {
        /** 图上短名（道指 / 纳指 / 恒指…）。 */
        public final String name;
        /** 全名（道琼斯工业指数…）。 */
        public final String fullName;
        public final String region;
        public final boolean found;
        /** 点位原字符串；未查到为空串。 */
        public final String price;
        /** 「+0.57%」「-0.82%」；未查到为空串。 */
        public final String pctText;
        /** 涨跌幅数值（%）；未查到 / 解析不了为 NaN（不画柱）。 */
        public final double pct;
        public final int color;

        Bar(String name, String region, boolean found, String price, String pctText, double pct) {
            this.fullName = name;
            this.name = MarketQuotes.shortName(name);
            this.region = region;
            this.found = found;
            this.price = price == null ? "" : MarketQuotes.fmt2(price);
            this.pctText = pctText == null ? "" : pctText;
            this.pct = pct;
            this.color = colorOf(pct);
        }

        /** 柱右侧的文字：「3608.10  -0.82%」或「未查到」。 */
        public String valueText() {
            return found ? (price + "  " + pctText).trim() : "未查到";
        }

        public boolean drawBar() {
            return found && !Double.isNaN(pct);
        }
    }

    public static final class Group {
        public final String region;
        public final List<Bar> bars = new ArrayList<Bar>();

        Group(String region) {
            this.region = region;
        }
    }

    public static final class Model {
        public final List<Group> groups = new ArrayList<Group>();
        /** 柱长满格对应的涨跌幅绝对值（≥0.5%）。 */
        public double maxAbs;
        /** 脚注行（行情时间与时区、来源）。 */
        public final List<String> footer = new ArrayList<String>();
        public long createdAt;
        /** 和这张图配对的结论文字指纹（{@link #withChart} 填）。 */
        String key;

        public int barCount() {
            int n = 0;
            for (Group g : groups) n += g.bars.size();
            return n;
        }

        /** 日志 / 核对用：每条一段「名=点位,涨跌幅」（未查到写 未查到）。 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (Group g : groups) {
                sb.append(g.region).append(':');
                for (Bar b : g.bars) sb.append(' ').append(b.name).append('=').append(b.found ? b.price + "," + b.pctText : "未查到");
                sb.append(" | ");
            }
            return sb.toString();
        }
    }

    /** 颜色：>0 红、<0 绿、0 / NaN 灰。 */
    public static int colorOf(double pct) {
        if (Double.isNaN(pct) || pct == 0d) return FLAT;
        return pct > 0 ? UP : DOWN;
    }

    /** MarketQuotes 地区 → 图上分组（「日欧」拆开）。 */
    static String regionOf(MarketQuotes.Quote q) {
        if ("日欧".equals(q.region)) return q.name.contains("日经") ? "日本" : "欧洲";
        return q.region;
    }

    static double parsePct(String s) {
        if (s == null) return Double.NaN;
        String t = s.trim().replace("%", "").replace("+", "");
        if (t.length() == 0) return Double.NaN;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** 快照 → 图表模型；null 快照返回 null。nowMillis 判断「最近一个交易日」（北京时间）。 */
    public static Model fromSnapshot(MarketQuotes.Snapshot s, long nowMillis) {
        if (s == null) return null;
        Model m = new Model();
        m.createdAt = nowMillis;
        Map<String, Group> by = new LinkedHashMap<String, Group>();
        for (String r : REGIONS) by.put(r, new Group(r));
        Map<String, String> when = new LinkedHashMap<String, String>();
        String today = MarketQuotes.ymd(nowMillis);
        double max = 0;
        for (MarketQuotes.Quote q : s.quotes) {
            String region = regionOf(q);
            Group g = by.get(region);
            if (g == null) {
                g = new Group(region);
                by.put(region, g);
            }
            Bar b;
            if (q.found()) {
                String pt = MarketQuotes.signed(q.pct);
                double p = parsePct(q.pct);
                b = new Bar(q.name, region, true, q.price, pt.length() > 0 ? pt + "%" : "", p);
                if (!Double.isNaN(p)) max = Math.max(max, Math.abs(p));
                if (q.date != null && !when.containsKey(region)) {
                    StringBuilder w = new StringBuilder(q.date.length() >= 10 ? q.date.substring(5) : q.date);
                    if (q.time != null) w.append(' ').append(q.time);
                    if (q.zone != null) w.append(' ').append(q.zone);
                    if (!q.date.equals(today) && !"美东时间".equals(q.zone)) w.append("（最近一个交易日）");
                    when.put(region, w.toString());
                }
            } else {
                b = new Bar(q.name, region, false, "", "", Double.NaN);
            }
            g.bars.add(b);
        }
        for (Group g : by.values()) if (!g.bars.isEmpty()) m.groups.add(g);
        m.maxAbs = Math.max(0.5, max);
        StringBuilder t = new StringBuilder("行情时间：");
        int k = 0;
        for (Map.Entry<String, String> e : when.entrySet()) t.append(k++ > 0 ? "；" : "").append(e.getKey()).append(' ').append(e.getValue());
        if (k == 0) t.append("未查到");
        m.footer.add(t.toString());
        List<String> src = new ArrayList<String>();
        if (s.usedTencent()) src.add(MarketQuotes.SRC_TENCENT);
        if (s.usedSina()) src.add(MarketQuotes.SRC_SINA);
        StringBuilder f = new StringBuilder("来源：");
        if (src.isEmpty()) f.append("腾讯 / 新浪行情均未取到");
        for (int i = 0; i < src.size(); i++) f.append(i > 0 ? "、" : "").append(src.get(i));
        f.append("；红涨绿跌");
        m.footer.add(f.toString());
        return m;
    }

    // ------------------------------------------------------------------ 结论 → 界面

    /** 槽有效期：结论上屏前后都在这段时间内。 */
    public static final long TTL_MS = 30L * 60 * 1000;
    private static volatile Model sLast;

    /** 收工时放进槽（覆盖上一张）。 */
    public static void publish(Model m) {
        sLast = m;
    }

    /** 结论文字指纹：只取字母 / 数字 / 汉字的前 40 个。 */
    static String keyOf(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && sb.length() < 40; i++) {
            char ch = text.charAt(i);
            if (!Character.isLetterOrDigit(ch)) continue;   // 只留字母数字汉字：界面清洗 Markdown / 标点前后都一致
            sb.append(ch);
        }
        return sb.toString();
    }

    /** 界面：这段结论正是收工时配了图的那段（指纹一致）且槽里的图没过期 → 返回它；否则 null（照旧显示纯文字）。 */
    public static Model match(String text, long nowMillis) {
        Model m = sLast;
        if (m == null || text == null || m.key == null || m.key.length() == 0) return null;
        if (!keyOf(text).equals(m.key)) return null;
        if (nowMillis - m.createdAt > TTL_MS || nowMillis < m.createdAt - 60000) return null;
        return m;
    }

    /** 测试用。 */
    static void clear() {
        sLast = null;
    }

    /**
     * 最终回答 = 模型写的板块与原因（不再逐个复述指数数字，也不加任何图注）；同时把图表模型连同这段文字的指纹放进槽。
     * 快照为 null 时原样返回；模型没写正文时用 {@link #EMPTY_ANSWER}。
     */
    public static String withChart(MarketQuotes.Snapshot s, String answer, long nowMillis) {
        if (s == null) return answer;
        String a = answer == null ? "" : answer.trim();
        if (a.length() == 0) a = EMPTY_ANSWER;
        Model m = fromSnapshot(s, nowMillis);
        m.key = keyOf(a);
        publish(m);
        return a;
    }

    // ------------------------------------------------------------------ ★ 11-04：列宽自适应（任何一行都不出省略号）

    /**
     * 名称列 + 数值列按实测最大宽度排，中间柱区至少 minBar；放不下就整体缩字号。
     * @return 字号缩放系数（1 = 不缩；下限 0.6，到下限仍放不下也不截断，由柱区让位）
     */
    public static float fitScale(float width, float maxNameW, float maxValueW, float gaps, float minBar) {
        float need = maxNameW + maxValueW + gaps + minBar;
        if (need <= width || need <= 0) return 1f;
        float text = maxNameW + maxValueW;
        if (text <= 0) return 1f;
        float sc = (width - gaps - minBar) / text;
        return Math.max(0.6f, Math.min(1f, sc));
    }
}
