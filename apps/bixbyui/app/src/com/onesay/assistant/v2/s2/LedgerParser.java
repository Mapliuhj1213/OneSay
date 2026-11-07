package com.onesay.assistant.v2.s2;

import com.onesay.assistant.v2.router.NumberParser;
import com.onesay.assistant.v2.vision.screenref.DateTimeNormalizer;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [s2] 闪记记账（#20）的句子解析（纯 Java）：「午饭花了35」「打车 28 块」「昨天买菜花了六十二块五」。
 *
 * <ul>
 *   <li><b>金额</b>：阿拉伯数字（可带小数、元/块/块钱）、中文数字（复用 {@link NumberParser}，补上口语「一百二」=120、
 *       「两千五」=2500）、「X块Y(毛)」=X.Y、「五毛」=0.5。单位：人民币，按分存。
 *       「3 斤」「10 月」「8 点」「2 个人」这类数字不当金额。</li>
 *   <li><b>分类</b>：关键词表（午饭→餐饮、打车→交通、买菜→买菜…），没命中记「其他」。</li>
 *   <li><b>日期</b>：今天/昨天/前天/大前天（本类）、「10月5日/5号」（复用识屏 {@link DateTimeNormalizer} 解析月日；
 *       解析出的日子在未来则视为去年/上月），缺省 = 今天。</li>
 * </ul>
 */
public final class LedgerParser {

    private LedgerParser() {}

    /** 一笔账。 */
    public static final class Item {
        public long amountFen;
        public String category = "其他";
        public String desc = "";
        public String day = "";
        public long dayMs;

        @Override public String toString() {
            return "fen=" + amountFen + " cat=" + category + " desc=" + desc + " day=" + day;
        }
    }

    // ------------------------------------------------------------------ 分类

    /** {分类, 关键词…}；顺序有意义：先具体后笼统。 */
    static final String[][] CATEGORIES = {
            {"餐饮", "早饭", "早餐", "午饭", "午餐", "中饭", "晚饭", "晚餐", "夜宵", "宵夜", "吃饭", "外卖", "奶茶", "咖啡", "饮料", "零食", "下午茶", "聚餐", "火锅", "饭钱", "饭"},
            {"交通", "打车", "打的", "出租车", "滴滴", "地铁", "公交", "加油", "油费", "停车费", "停车", "高铁", "火车", "机票", "车费", "过路费", "高速费", "共享单车", "单车"},
            {"买菜", "买菜", "菜钱", "超市", "水果", "生鲜", "菜市场"},
            {"居家", "房租", "水费", "电费", "燃气", "物业", "话费", "网费", "宽带", "充值"},
            {"医疗", "买药", "药", "医院", "看病", "挂号", "体检"},
            {"娱乐", "电影", "游戏", "唱歌", "ktv", "门票", "演唱会"},
            {"购物", "衣服", "鞋", "淘宝", "京东", "网购", "拼多多", "日用品", "买了"},
    };

    /** 查询里的分类说法 → 分类（「吃饭花了多少」→ 餐饮）。 */
    static final String[][] QUERY_CATEGORY = {
            {"餐饮", "吃饭", "吃的", "餐饮", "伙食", "饭", "外卖", "早饭", "午饭", "晚饭", "奶茶", "咖啡"},
            {"交通", "交通", "打车", "出行", "地铁", "公交", "加油"},
            {"买菜", "买菜", "菜", "超市"},
            {"居家", "房租", "水电", "居家", "话费"},
            {"医疗", "医疗", "看病", "买药"},
            {"娱乐", "娱乐", "电影", "游戏"},
            {"购物", "购物", "网购", "衣服"},
    };

    public static String categoryOf(String text) {
        String k = S2Text.key(text);
        for (String[] row : CATEGORIES) {
            for (int i = 1; i < row.length; i++) if (k.contains(row[i])) return row[0];
        }
        return "其他";
    }

    /** 命中的分类关键词（作为描述用），没有返回 null。 */
    static String categoryWord(String k) {
        String best = null;
        for (String[] row : CATEGORIES) {
            for (int i = 1; i < row.length; i++) {
                if (k.contains(row[i]) && (best == null || row[i].length() > best.length())) best = row[i];
            }
        }
        return best;
    }

    public static String queryCategory(String k) {
        for (String[] row : QUERY_CATEGORY) {
            for (int i = 1; i < row.length; i++) if (k.contains(row[i])) return row[0];
        }
        return null;
    }

    // ------------------------------------------------------------------ 金额

    private static final String CN = "零〇一二两三四五六七八九十百千万";
    private static final Pattern AMOUNT = Pattern.compile(
            "(\\d+(?:\\.\\d{1,2})?|[" + CN + "]+)(块钱|块|元钱|元|圆|rmb|¥)?([一二三四五六七八九\\d])?(毛钱|毛|角)?");
    private static final Pattern YEN_PREFIX = Pattern.compile("[¥￥](\\d+(?:\\.\\d{1,2})?)");
    private static final Pattern MAO_ONLY = Pattern.compile("([一二三四五六七八九\\d])(毛钱|毛|角)");
    /** 金额后面跟着这些字 = 不是金额（数量/日期/时刻）。 */
    private static final String NOT_MONEY_AFTER = "斤两克个只件瓶盒袋份人次天日号月年点分秒岁层楼排米公里度张本";
    static final Pattern SPEND = Pattern.compile("花了|花掉|花费了?|付了|付款|支付了?|消费了?|用了|充了|交了|掏了|共计|一共|总共|合计|花");

    /** 金额候选。 */
    static final class Amount {
        long fen;
        int start, end;
        boolean unit;
    }

    static List<Amount> amounts(String k) {
        List<Amount> out = new ArrayList<Amount>();
        Matcher y = YEN_PREFIX.matcher(k);
        while (y.find()) {
            Amount a = new Amount();
            a.fen = Math.round(Double.parseDouble(y.group(1)) * 100);
            a.start = y.start();
            a.end = y.end();
            a.unit = true;
            out.add(a);
        }
        Matcher m = AMOUNT.matcher(k);
        while (m.find()) {
            String num = m.group(1);
            String unit = m.group(2);
            String sub = m.group(3);
            String mao = m.group(4);
            if (num == null || num.length() == 0) continue;
            // 纯「十/百/千/万」单字不是金额（「十分」「百度」），「两/零」单字也不是
            if (num.length() == 1 && "十百千万两零〇".indexOf(num.charAt(0)) >= 0 && unit == null) continue;
            int end = m.end();
            if (unit == null && sub == null && mao == null && end < k.length()
                    && NOT_MONEY_AFTER.indexOf(k.charAt(end)) >= 0) continue;
            // 「一下」「一点」「一些」之类的「一」
            if (unit == null && num.equals("一")) continue;
            Long base;
            double val;
            if (Character.isDigit(num.charAt(0))) {
                val = Double.parseDouble(num);
            } else {
                base = cnNumber(num);
                if (base == null) continue;
                val = base.doubleValue();
            }
            long fen = Math.round(val * 100);
            if (sub != null && unit != null) {
                // 「六十二块五」= 62.5；「三块5毛」
                Long d = Character.isDigit(sub.charAt(0)) ? Long.valueOf(sub) : NumberParser.parse(sub);
                if (d != null) fen += d.longValue() * 10;
            } else if (sub != null && unit == null) {
                // 「3 5 毛」之类不规整：放弃 sub
                if (mao != null) continue;
            } else if (unit == null && mao != null) {
                fen = Math.round(val * 10);   // 「五毛」
            }
            if (fen <= 0 || fen > 100000000L) continue;   // 上限 100 万元
            Amount a = new Amount();
            a.fen = fen;
            a.start = m.start();
            a.end = end;
            a.unit = unit != null || mao != null;
            out.add(a);
        }
        Matcher mo = MAO_ONLY.matcher(k);
        while (mo.find()) {
            boolean covered = false;
            for (Amount a : out) if (mo.start() >= a.start && mo.start() < a.end) covered = true;
            if (covered) continue;
            Long d = Character.isDigit(mo.group(1).charAt(0)) ? Long.valueOf(mo.group(1)) : NumberParser.parse(mo.group(1));
            if (d == null) continue;
            Amount a = new Amount();
            a.fen = d.longValue() * 10;
            a.start = mo.start();
            a.end = mo.end();
            a.unit = true;
            out.add(a);
        }
        return out;
    }

    /** 中文数字（含口语「一百二」=120、「两千五」=2500、「三万二」=32000）。 */
    static Long cnNumber(String s) {
        if (s == null || s.length() == 0) return null;
        Matcher m = Pattern.compile("^(.*?)([百千万])([一二两三四五六七八九])$").matcher(s);
        if (m.matches() && m.group(1).length() > 0) {
            Long head = NumberParser.parse(m.group(1) + m.group(2));
            Long tail = NumberParser.parse(m.group(3));
            if (head != null && tail != null) {
                long unit = "百".equals(m.group(2)) ? 100 : "千".equals(m.group(2)) ? 1000 : 10000;
                return Long.valueOf(head.longValue() + tail.longValue() * unit / 10);
            }
        }
        return NumberParser.parse(s);
    }

    // ------------------------------------------------------------------ 识别

    private static final Pattern QUESTION = Pattern.compile("多少|几块|几元|几毛|吗$|呢$|么$|啥|什么|哪");
    private static final Pattern LEDGER_PREFIX = Pattern.compile("^(?:帮我)?(?:记一笔账|记一笔|记笔账|记个账|记账|记一下账|记下账|记一下|记下|记个|记住)[:：]?");

    /** 是否像「一笔账」（有金额 + 花钱语境），并解析；不像返回 null。 */
    public static Item parse(String utterance, long nowMs, TimeZone tz) {
        String k = S2Text.key(utterance);
        if (k.length() == 0 || QUESTION.matcher(k).find()) return null;
        boolean prefixed = false;
        Matcher pm = LEDGER_PREFIX.matcher(k);
        if (pm.find()) {
            prefixed = pm.group().contains("账") || pm.group().contains("笔");
            k = k.substring(pm.end());
        }
        List<Amount> as = amounts(k);
        if (as.isEmpty()) return null;
        Matcher sp = SPEND.matcher(k);
        boolean spend = sp.find();
        String catWord = categoryWord(k);
        Amount pick = null;
        if (spend) {
            for (Amount a : as) if (a.start >= sp.start()) { pick = a; break; }
        }
        if (pick == null) {
            for (Amount a : as) if (a.unit) { pick = a; break; }
        }
        if (pick == null && (prefixed || catWord != null)) {
            Amount last = as.get(as.size() - 1);
            // 「打车28」：分类词 + 句末数字，且剩余没有别的内容
            if (last.end == k.length()) pick = last;
        }
        if (pick == null) return null;
        // 必须有花钱语境：花钱动词 / 记账前缀 / 分类词；只有「28块」不算
        if (!spend && !prefixed && catWord == null) return null;

        Item it = new Item();
        it.amountFen = pick.fen;
        it.category = categoryOf(k);
        it.dayMs = parseDay(k, nowMs, tz);
        it.day = S2Text.day(it.dayMs, tz);
        // 描述：去掉日期词、金额、花钱动词与语气
        String desc = (k.substring(0, pick.start) + k.substring(pick.end))
                .replaceAll(DAY_WORDS, "")
                .replaceAll("\\d{1,2}月\\d{1,2}[日号]?|\\d{1,2}号", "")
                .replaceAll("花了|花掉|花费了?|付了|支付了?|消费了?|用了|充了|交了|掏了|共计|一共|总共|合计|花", "")
                .replaceAll("^(我|今儿|今个)", "")
                .replaceAll("(了|啦|哦|呀|吧|钱)+$", "")
                .trim();
        if (desc.length() == 0) desc = catWord != null ? catWord : it.category;
        it.desc = S2Text.brief(desc, 16);
        return it;
    }

    static final String DAY_WORDS = "大前天|前天|昨天|昨晚|昨日|昨儿|今天|今早|今晚|今日|今儿|早上|中午|晚上|下午|上午";

    /** 事件日期：今天/昨天/前天/大前天/「10月5日」「5号」；缺省今天。 */
    static long parseDay(String k, long nowMs, TimeZone tz) {
        int back = 0;
        if (k.contains("大前天")) back = 3;
        else if (k.contains("前天")) back = 2;
        else if (k.contains("昨天") || k.contains("昨晚") || k.contains("昨日") || k.contains("昨儿")) back = 1;
        if (back > 0) return nowMs - back * 86400000L;
        Matcher md = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]?").matcher(k);
        if (md.find()) {
            try {
                List<DateTimeNormalizer.Span> spans = DateTimeNormalizer.findAll(md.group(1) + "月" + md.group(2) + "日", nowMs, tz);
                if (!spans.isEmpty() && spans.get(0).hasDate) {
                    long ms = spans.get(0).beginMs;
                    if (ms > nowMs + 86400000L) {
                        Calendar c = S2Text.cal(ms, tz);
                        c.add(Calendar.YEAR, -1);
                        ms = c.getTimeInMillis();
                    }
                    return ms;
                }
            } catch (Throwable ignored) {
            }
        }
        Matcher dd = Pattern.compile("(?<![\\d月])(\\d{1,2})号").matcher(k);
        if (dd.find()) {
            int d = Integer.parseInt(dd.group(1));
            Calendar c = S2Text.cal(nowMs, tz);
            if (d >= 1 && d <= 31) {
                if (d > c.get(Calendar.DAY_OF_MONTH)) c.add(Calendar.MONTH, -1);
                c.set(Calendar.DAY_OF_MONTH, Math.min(d, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
                return c.getTimeInMillis();
            }
        }
        return nowMs;
    }
}
