package com.onesay.assistant.v2.sched;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [sched] 中文日程时间解析（纯 Java，JVM 大量单测：CnTimeTest）。
 *
 * <h3>覆盖</h3>
 * <ul>
 *   <li>相对：半小时后、一个半小时后、10 分钟后、两个钟头后、过 20 分钟、3 天后、两周后、一个月后；</li>
 *   <li>日子：今天 / 明天 / 后天 / 大后天 / 今晚 / 明早；周三 / 这周五 / 下周一 / 下下周二 / 周末；10 月 25 号 / 25 号 / 下个月 3 号 / 2027 年 1 月 1 日；
 *       月底 / 下个月底 / 月初 / 年底；农历（可选，ICU）：农历八月十五 / 正月初一 / 腊月廿三 / 除夕 / 中秋 / 端午…；</li>
 *   <li>钟点：下午三点、晚上 8 点半、三点一刻、3 点 45、15:30、十点整、中午 12 点；只说时段（明天上午）按默认钟点并标 {@link Result#timeGuessed}；
 *       区间：下午三点到五点；</li>
 *   <li>重复：每天 / 每个工作日 / 每周三 / 每周一三五 / 每个周末 / 每月 15 号 / 每月底 / 每年 3 月 8 日 / 每年农历八月十五；</li>
 *   <li>提前量：提前半小时提醒、提前 1 天、不用提醒。</li>
 * </ul>
 *
 * <h3>歧义 → 追问（{@link Result#question} + {@link Result#options}）</h3>
 * <ul>
 *   <li>不带时段的 1–6 点（「明天三点」）：凌晨还是下午 / 早上还是晚上？—— 但「今天」且上午那个点已过、下午那个点还没到时直接取下午（小爱的做法）；</li>
 *   <li>「周三」恰好说在周三：今天还是下周三？</li>
 *   <li>「这周一」已经过去：这周一已经过了，是下周一吗？</li>
 *   <li>明确说了「今天」但钟点已过：改到明天吗？</li>
 *   <li>「下周」没说哪天、要建带钟点的日程：下周哪天？（查询不追问，直接给一周的范围）</li>
 * </ul>
 * 没说日子、钟点已过 → 顺延到明天（{@link Result#rolled}），不追问。说了年份以外的月日但已过 → 明年。
 */
public final class CnTime {

    private CnTime() {}

    /** 追问的一个选项。 */
    public static final class Option {
        public final String label;
        public final long start;
        public final long end;

        public Option(String label, long start, long end) {
            this.label = label;
            this.start = start;
            this.end = end;
        }

        @Override public String toString() {
            return label + "@" + start;
        }
    }

    public static final class Result {
        /** 句子里有没有任何时间表达。 */
        public boolean found;
        /** 有日子（今天以外的显式日子，或「今天」本身）。 */
        public boolean hasDate;
        /** 有钟点（含时段默认钟点、相对分钟）。 */
        public boolean hasTime;
        public long start;
        public long end;
        public boolean allDay;
        /** 只说了时段（「明天上午」），钟点是默认值。 */
        public boolean timeGuessed;
        /** 没说日子、钟点已过 → 顺延到了明天。 */
        public boolean rolled;
        public int repeat = SchedEvent.REPEAT_NONE;
        public int weekMask;
        public int monthDay;
        public boolean lunar;
        public int lunarMonth, lunarDay;
        /** 提前量（分钟）；{@link SchedEvent#LEAD_DEFAULT} = 没说；{@link #LEAD_NONE} = 不用提醒。 */
        public int leadMin = SchedEvent.LEAD_DEFAULT;
        /** 只说了「下周 / 这周」没说哪天：这一周的周一 0 点（查询用 [weekStart, +7 天)）。 */
        public long weekStart = -1L;
        /** 「最近 / 接下来 / 这几天」。 */
        public boolean soon;
        /** 「周末」（查询用周六周日两天）。 */
        public boolean weekend;
        /** 非 null = 需要追问。 */
        public String question;
        public final List<Option> options = new ArrayList<Option>();
        /** 解析失败的说明（例如「2 月 30 号不存在」「农历暂时算不了」）。 */
        public String error;
        /** 去掉时间词以后剩下的话（标题从这里来）。 */
        public String rest = "";

        public boolean ambiguous() {
            return question != null;
        }

        @Override public String toString() {
            return "found=" + found + " start=" + start + " end=" + end + " allDay=" + allDay + " guessed=" + timeGuessed + " rolled=" + rolled
                    + " rep=" + repeat + " mask=" + weekMask + " md=" + monthDay + " lunar=" + lunar + " lead=" + leadMin
                    + " q=" + question + " opts=" + options + " err=" + error + " rest=" + rest;
        }
    }

    public static final int LEAD_NONE = -2;

    // ------------------------------------------------------------------ 正则

    static final String N = "[0-9零〇一二两三四五六七八九十廿]{1,4}";
    static final String PERIOD = "凌晨|清晨|一大早|大清早|早上|早晨|上午|中午|午后|下午|傍晚|晚上|夜里|夜间|半夜|深夜|今早|明早|今晚|明晚|今夜|明夜";
    static final char MARK = '\u0001';

    static final Pattern LEAD = Pattern.compile("提前(" + N + "|半)(个)?(分钟|小时|钟头|天)(左右)?(提醒(我|一下)?|通知(我)?|叫我)?");
    static final Pattern LEAD_HALF = Pattern.compile("提前(" + N + ")个半(小时|钟头)(提醒(我|一下)?|通知(我)?|叫我)?");
    static final Pattern NO_REMIND = Pattern.compile("(不用|不要|别|无需|不需要)(提醒|通知)(我)?");

    static final Pattern REL_HALF = Pattern.compile("(?:过)?(" + N + ")?个?半(个)?(小时|钟头)(以?后|之后|后)");
    static final Pattern REL_HM = Pattern.compile("(?:过)?(" + N + ")个?(小时|钟头)(零)?(" + N + ")分钟?(以?后|之后|后)");
    static final Pattern REL_H = Pattern.compile("(?:过)?(" + N + ")个?(小时|钟头)(以?后|之后|后)");
    static final Pattern REL_M = Pattern.compile("(?:过)?(" + N + ")分钟(以?后|之后|后)");
    static final Pattern REL_PASS = Pattern.compile("过(" + N + "|半)个?(分钟|小时|钟头)");
    static final Pattern REL_DAY = Pattern.compile("(" + N + ")(天|日)(以?后|之后|后)");
    static final Pattern REL_WEEK = Pattern.compile("(" + N + ")个?(周|星期|礼拜)(以?后|之后|后)");
    static final Pattern REL_MONTH = Pattern.compile("(" + N + "|半)个月(以?后|之后|后)");

    static final Pattern REP_DAILY = Pattern.compile("每天|天天|每日|每晚|每早|每个?(?=早上|早晨|上午|中午|下午|晚上)");
    static final Pattern REP_WORKDAY = Pattern.compile("每个?工作日|工作日每天|周一到周五每天|每周一到周五|每个?周一至周五");
    static final Pattern REP_WEEKEND = Pattern.compile("每个?周末|每个?(星期|礼拜)六日|每周六日|每周六和周日|每周六周日");
    static final Pattern REP_WEEK = Pattern.compile("每(?:个)?(?:周|星期|礼拜)((?:[一二三四五六日天1-7](?:[、和,，及与]|周|星期|礼拜)*)+)");
    static final Pattern REP_MONTH_END = Pattern.compile("每(?:个)?月(?:的)?(底|末|最后一天)");
    static final Pattern REP_MONTH = Pattern.compile("每(?:个)?月(?:的)?(" + N + ")(号|日)");
    static final Pattern REP_YEAR_LUNAR = Pattern.compile("每年(?:的)?(?:农历|阴历)(闰)?(正|十一|十二|一|二|三|四|五|六|七|八|九|十|冬|腊)月(初" + N + "|" + N + ")");
    static final Pattern REP_YEAR = Pattern.compile("每年(?:的)?(" + N + ")月(" + N + ")(号|日)?");

    static final Pattern FESTIVAL = Pattern.compile("(大年三十|除夕|大年初一|春节|元宵节?|端午节?|七夕节?|中秋节?|重阳节?|腊八节?|小年)");
    static final Pattern LUNAR_DATE = Pattern.compile("(?:(今年|明年)的?)?(农历|阴历)?(闰)?(正|十一|十二|一|二|三|四|五|六|七|八|九|十|冬|腊)月(初" + N + "|廿" + N + "?|二十" + N + "?|三十|十" + N + "?|" + N + ")(日|号)?");

    static final Pattern ABS_YMD = Pattern.compile("(?:(" + N + ")年|(今年|明年|后年)的?)(" + N + ")月(" + N + ")(日|号)?");
    static final Pattern ABS_MD = Pattern.compile("(" + N + ")月(" + N + ")(日|号)");
    static final Pattern ABS_MD_LOOSE = Pattern.compile("(" + N + ")月(" + N + ")(?![0-9零〇一二两三四五六七八九十点时:分])");
    static final Pattern MONTH_DAY = Pattern.compile("(?:(下下个?月|下个?月|这个?月|本月)的?|(?<![月年0-9零〇一二两三四五六七八九十廿]))(" + N + ")(号|日)(?!子|常|本|元|记|报)");
    static final Pattern MONTH_EDGE = Pattern.compile("(下下个?月|下个?月|这个?月|本月|(" + N + ")月)?(?:的)?(月底|月末|底|月初|初)(?!" + N + ")");
    static final Pattern YEAR_END = Pattern.compile("(今年|明年)?年(底|末)");
    static final Pattern DAY_WORD = Pattern.compile("大后天|后天|明天|今天|今日|明日|明儿|今儿|今晚|明晚|今早|明早|今夜|明夜");
    static final Pattern WEEKDAY = Pattern.compile("(下下个?|下个?|这个?|本|上个?)?(?:周|星期|礼拜)([一二三四五六日天1-7])");
    static final Pattern WEEK_ONLY = Pattern.compile("(下下个?|下个?|这个?|本)(?:周|星期|礼拜)(?![一二三四五六日天1-7])");
    static final Pattern WEEKEND = Pattern.compile("(下个?|这个?|本)?(?:周末)");
    static final Pattern SOON = Pattern.compile("最近|接下来|这几天|近几天|未来几天|近期|之后几天");

    static final Pattern CLOCK = Pattern.compile("(?<![0-9])([01]?[0-9]|2[0-3])[:：]([0-5][0-9])(?![0-9])");
    static final Pattern HOUR = Pattern.compile("(" + PERIOD + ")?(" + N + ")(点钟|点|时)(整|半|一刻|三刻|(零)?(" + N + ")(分)?)?");
    static final Pattern PERIOD_ONLY = Pattern.compile("(" + PERIOD + ")");
    static final Pattern RANGE_SEP = Pattern.compile("^(到|至|-|~|—|－|～)");

    // ------------------------------------------------------------------ 入口

    /** 只看句子有没有时间表达（分诊用，不算具体时刻）。 */
    public static boolean mentionsTime(String text) {
        Result r = parse(text, System.currentTimeMillis(), TimeZone.getDefault());
        return r.found;
    }

    public static Result parse(String text, long now, TimeZone tz) {
        if (tz == null) tz = TimeZone.getDefault();
        Result r = new Result();
        StringBuilder s = new StringBuilder(normalize(text));
        Ctx c = new Ctx(now, tz);

        // 1) 提前量
        Matcher m = LEAD_HALF.matcher(s);
        if (m.find()) {
            int n = CnNum.parse(m.group(1));
            if (n >= 0) r.leadMin = n * 60 + 30;
            mark(s, m);
        }
        m = LEAD.matcher(s);
        if (m.find()) {
            String a = m.group(1);
            int n = "半".equals(a) ? -1 : CnNum.parse(a);
            String u = m.group(3);
            if ("半".equals(a)) r.leadMin = "天".equals(u) ? 12 * 60 : ("分钟".equals(u) ? 1 : 30);
            else if (n >= 0) r.leadMin = "分钟".equals(u) ? n : ("天".equals(u) ? n * 1440 : n * 60);
            mark(s, m);
        }
        m = NO_REMIND.matcher(s);
        if (m.find()) {
            r.leadMin = LEAD_NONE;
            mark(s, m);
        }

        // 2) 相对分钟 / 小时（精确时刻，其他日期词不再看）
        long rel = relative(s);
        if (rel > 0) {
            r.found = true;
            r.hasTime = true;
            r.hasDate = true;
            r.start = roundMinute(now + rel);
            r.rest = rest(s);
            return r;
        }

        // 3) 重复
        repeat(s, c, r);

        // 4) 日子
        dates(s, c, r);
        if (r.error != null) {
            r.found = true;
            r.rest = rest(s);
            return r;
        }

        // 5) 钟点
        times(s, c);

        r.rest = rest(s);
        compose(c, r);
        return r;
    }

    // ------------------------------------------------------------------ 内部状态

    static final class Ctx {
        final long now;
        final TimeZone tz;
        /** 日子：0 点（tz）；-1 = 没说。 */
        long day = -1L;
        /** 日子是怎么来的：IMPLICIT（没说）/ TODAY（说了今天）/ WEEKDAY_BARE（「周三」）/ THIS_WEEK（「这周三」）/ MD（只说月日）/ EXACT。 */
        String dayKind = "IMPLICIT";
        /** WEEKDAY_BARE 说在当天：下周同一天的 0 点（追问的另一个选项）。 */
        long altDay = -1L;
        String dayLabelHint;
        int hour = -1, minute;
        String period;
        boolean periodOnly;
        int endHour = -1, endMinute;
        String endPeriod;
        boolean anyTime;

        Ctx(long now, TimeZone tz) {
            this.now = now;
            this.tz = tz;
        }

        Calendar cal() {
            Calendar k = Calendar.getInstance(tz);
            k.setTimeInMillis(now);
            return k;
        }

        long today() {
            return dayStart(now, tz);
        }
    }

    // ------------------------------------------------------------------ 相对

    static long relative(StringBuilder s) {
        Matcher m = REL_HALF.matcher(s);
        if (m.find()) {
            int n = m.group(1) == null ? 0 : CnNum.parse(m.group(1));
            if (n >= 0) {
                mark(s, m);
                return n * 3600000L + 1800000L;
            }
        }
        m = REL_HM.matcher(s);
        if (m.find()) {
            int h = CnNum.parse(m.group(1)), mi = CnNum.parse(m.group(4));
            if (h >= 0 && mi >= 0) {
                mark(s, m);
                return h * 3600000L + mi * 60000L;
            }
        }
        m = REL_H.matcher(s);
        if (m.find()) {
            int h = CnNum.parse(m.group(1));
            if (h > 0 && h <= 72) {
                mark(s, m);
                return h * 3600000L;
            }
        }
        m = REL_M.matcher(s);
        if (m.find()) {
            int mi = CnNum.parse(m.group(1));
            if (mi > 0 && mi <= 24 * 60) {
                mark(s, m);
                return mi * 60000L;
            }
        }
        m = REL_PASS.matcher(s);
        if (m.find()) {
            String a = m.group(1);
            boolean half = "半".equals(a);
            int n = half ? 0 : CnNum.parse(a);
            if (n >= 0) {
                boolean min = "分钟".equals(m.group(2));   // 先读组再 mark（Matcher 读的是同一个 StringBuilder）
                mark(s, m);
                if (half) return min ? 30000L : 1800000L;
                return min ? n * 60000L : n * 3600000L;
            }
        }
        return 0L;
    }

    // ------------------------------------------------------------------ 重复

    static void repeat(StringBuilder s, Ctx c, Result r) {
        Matcher m = REP_WORKDAY.matcher(s);
        if (m.find()) {
            r.repeat = SchedEvent.REPEAT_WORKDAY;
            mark(s, m);
            return;
        }
        m = REP_WEEKEND.matcher(s);
        if (m.find()) {
            r.repeat = SchedEvent.REPEAT_WEEKLY;
            r.weekMask = bit(Calendar.SATURDAY) | bit(Calendar.SUNDAY);
            mark(s, m);
            return;
        }
        m = REP_WEEK.matcher(s);
        if (m.find()) {
            int mask = 0;
            String g = m.group(1);
            int end = m.end();
            // 「每周三三点」：最后一个字其实是钟点的数字，还给钟点
            if (g.length() >= 2 && end < s.length() && "点时:".indexOf(s.charAt(end)) >= 0 && dowOf(g.charAt(g.length() - 1)) > 0) {
                g = g.substring(0, g.length() - 1);
                end--;
            }
            for (int i = 0; i < g.length(); i++) {
                int dow = dowOf(g.charAt(i));
                if (dow > 0) mask |= bit(dow);
            }
            if (mask != 0) {
                r.repeat = SchedEvent.REPEAT_WEEKLY;
                r.weekMask = mask;
                markRange(s, m.start(), end);
                return;
            }
        }
        m = REP_MONTH_END.matcher(s);
        if (m.find()) {
            r.repeat = SchedEvent.REPEAT_MONTHLY;
            r.monthDay = -1;
            mark(s, m);
            return;
        }
        m = REP_MONTH.matcher(s);
        if (m.find()) {
            int d = CnNum.parse(m.group(1));
            if (d >= 1 && d <= 31) {
                r.repeat = SchedEvent.REPEAT_MONTHLY;
                r.monthDay = d;
                mark(s, m);
                return;
            }
        }
        m = REP_YEAR_LUNAR.matcher(s);
        if (m.find()) {
            int mo = lunarMonth(m.group(2)), d = lunarDayNum(m.group(3));
            if (mo > 0 && d > 0 && d <= 30) {
                r.repeat = SchedEvent.REPEAT_YEARLY;
                r.lunar = true;
                r.lunarMonth = mo;
                r.lunarDay = d;
                mark(s, m);
                return;
            }
        }
        m = REP_YEAR.matcher(s);
        if (m.find()) {
            int mo = CnNum.parse(m.group(1)), d = CnNum.parse(m.group(2));
            if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31) {
                r.repeat = SchedEvent.REPEAT_YEARLY;
                Calendar k = c.cal();
                long first = ymd(k.get(Calendar.YEAR), mo, d, c.tz);
                if (first < 0) {
                    r.error = mo + "月" + d + "号不存在";
                    return;
                }
                c.day = first;   // compose 再按「下一次」调整
                c.dayKind = "YEARLY";
                mark(s, m);
                return;
            }
        }
        m = REP_DAILY.matcher(s);
        if (m.find()) {
            r.repeat = SchedEvent.REPEAT_DAILY;
            String g = m.group();
            // 「每晚」「每早」自带时段
            if (g.endsWith("晚")) c.period = "晚上";
            if (g.endsWith("早")) c.period = "早上";
            mark(s, m);
        }
    }

    // ------------------------------------------------------------------ 日子

    static void dates(StringBuilder s, Ctx c, Result r) {
        Matcher m;
        long today = c.today();
        Calendar k = c.cal();
        int y = k.get(Calendar.YEAR);

        // 相对天 / 周 / 月（先于「N 日」，免得「十日后」被当成 10 号）
        relativeDays(s, c, r, today);

        // 农历（节日名 / 农历日期）
        m = FESTIVAL.matcher(s);
        if (c.day < 0 && m.find()) {
            String f = m.group(1);
            int mo, d;
            if (f.startsWith("除夕") || f.startsWith("大年三十")) { mo = 12; d = 30; }
            else if (f.startsWith("大年初一") || f.startsWith("春节")) { mo = 1; d = 1; }
            else if (f.startsWith("元宵")) { mo = 1; d = 15; }
            else if (f.startsWith("端午")) { mo = 5; d = 5; }
            else if (f.startsWith("七夕")) { mo = 7; d = 7; }
            else if (f.startsWith("中秋")) { mo = 8; d = 15; }
            else if (f.startsWith("重阳")) { mo = 9; d = 9; }
            else if (f.startsWith("腊八")) { mo = 12; d = 8; }
            else { mo = 12; d = 23; }   // 小年（北方）
            if (!Lunar.available()) {
                r.error = "农历日期暂时算不了";
                mark(s, m);
                return;
            }
            long at = Lunar.nextOccurrence(mo, d, today, c.tz);   // 除夕：没有三十的年份自动退到廿九
            if (at < 0) {
                r.error = "农历日期暂时算不了";
                mark(s, m);
                return;
            }
            c.day = at;
            c.dayKind = "EXACT";
            r.hasDate = true;
            mark(s, m);
        }
        m = LUNAR_DATE.matcher(s);
        while (c.day < 0 && !r.lunar && m.find()) {
            boolean explicit = m.group(2) != null;
            String mw = m.group(4), dw = m.group(5);
            boolean lunarish = explicit || "正".equals(mw) || "冬".equals(mw) || "腊".equals(mw) || dw.startsWith("初") || dw.startsWith("廿")
                    || (dw.startsWith("十") && dw.length() > 1 && m.group(6) == null && !explicit && false);
            if (!lunarish) continue;
            int mo = lunarMonth(mw), d = lunarDayNum(dw);
            if (mo <= 0 || d <= 0 || d > 30) continue;
            if (!Lunar.available()) {
                r.error = "农历日期暂时算不了";
                mark(s, m);
                return;
            }
            boolean leap = m.group(3) != null;
            long at;
            if (m.group(1) != null) {
                int ly = "明年".equals(m.group(1)) ? y + 1 : y;
                int[] sol = Lunar.toSolar(ly, mo, leap, d, c.tz);
                at = sol == null ? -1L : ymd(sol[0], sol[1], sol[2], c.tz);
            } else if (leap) {
                at = -1L;
                for (int ly = y - 1; ly <= y + 3 && at < 0; ly++) {
                    int[] sol = Lunar.toSolar(ly, mo, true, d, c.tz);
                    if (sol != null) {
                        long t = ymd(sol[0], sol[1], sol[2], c.tz);
                        if (t >= today) at = t;
                    }
                }
            } else {
                at = Lunar.nextOccurrence(mo, d, today, c.tz);
            }
            if (at < 0) {
                r.error = "农历" + (leap ? "闰" : "") + Lunar.label(mo, d) + "这个日子不存在";
                mark(s, m);
                return;
            }
            c.day = at;
            c.dayKind = "EXACT";
            r.hasDate = true;
            mark(s, m);
        }

        // 公历绝对日期
        m = ABS_YMD.matcher(s);
        if (c.day < 0 && m.find()) {
            String yw = m.group(1) != null ? m.group(1) : m.group(2);
            int yy = "今年".equals(yw) ? y : "明年".equals(yw) ? y + 1 : "后年".equals(yw) ? y + 2 : CnNum.parse(yw);
            if (yy >= 0 && yy < 100) yy += 2000;
            int mo = CnNum.parse(m.group(3)), d = CnNum.parse(m.group(4));
            long at = ymd(yy, mo, d, c.tz);
            if (at < 0) {
                r.error = mo + "月" + d + "号不存在";
                mark(s, m);
                return;
            }
            c.day = at;
            c.dayKind = "EXACT";
            r.hasDate = true;
            mark(s, m);
        }
        m = ABS_MD.matcher(s);
        if (c.day < 0 && m.find()) {
            absMd(s, m, c, r, y, today);
            if (r.error != null) return;
        }
        m = ABS_MD_LOOSE.matcher(s);
        if (c.day < 0 && m.find()) {
            absMd(s, m, c, r, y, today);
            if (r.error != null) return;
        }
        m = YEAR_END.matcher(s);
        if (c.day < 0 && m.find()) {
            int yy = "明年".equals(m.group(1)) ? y + 1 : y;
            c.day = ymd(yy, 12, 31, c.tz);
            c.dayKind = "EXACT";
            r.hasDate = true;
            mark(s, m);
        }
        m = MONTH_EDGE.matcher(s);
        if (c.day < 0 && m.find()) {
            String pre = m.group(1), edge = m.group(3);
            boolean bareEdge = pre == null && (edge.equals("底") || edge.equals("初"));
            if (!bareEdge) {
                Calendar t = c.cal();
                int mo = t.get(Calendar.MONTH) + 1, yy = y;
                boolean explicitMonth = false;
                if (pre != null) {
                    if (pre.startsWith("下下")) mo += 2;
                    else if (pre.startsWith("下")) mo += 1;
                    else if (m.group(2) != null) {
                        mo = CnNum.parse(m.group(2));
                        explicitMonth = true;
                    }
                }
                while (mo > 12) {
                    mo -= 12;
                    yy++;
                }
                boolean start = edge.endsWith("初");
                if (start && pre == null) {
                    // 「月初」：这个月 1 号已经过了 → 下个月 1 号
                    if (t.get(Calendar.DAY_OF_MONTH) > 1) {
                        mo++;
                        if (mo > 12) { mo = 1; yy++; }
                    }
                }
                if (explicitMonth && mo >= 1 && mo <= 12 && mo < t.get(Calendar.MONTH) + 1) yy++;
                if (mo >= 1 && mo <= 12) {
                    c.day = start ? ymd(yy, mo, 1, c.tz) : ymd(yy, mo, daysIn(yy, mo), c.tz);
                    c.dayKind = "EXACT";
                    r.hasDate = true;
                    mark(s, m);
                }
            }
        }
        m = MONTH_DAY.matcher(s);
        if (c.day < 0 && m.find()) {
            String pre = m.group(1);
            int d = CnNum.parse(m.group(2));
            if (d >= 1 && d <= 31) {
                Calendar t = c.cal();
                int mo = t.get(Calendar.MONTH) + 1, yy = y;
                if (pre != null && pre.startsWith("下下")) mo += 2;
                else if (pre != null && pre.startsWith("下")) mo += 1;
                if (mo > 12) {
                    mo -= 12;
                    yy++;
                }
                long at = ymd(yy, mo, d, c.tz);
                if (pre == null && (at < 0 || at < today)) {
                    // 「25 号」已经过了 → 下个月（下个月没有 31 号就再往后）
                    for (int i = 1; i <= 3; i++) {
                        int mm = mo + i, y2 = yy;
                        while (mm > 12) { mm -= 12; y2++; }
                        long t2 = ymd(y2, mm, d, c.tz);
                        if (t2 >= 0) { at = t2; break; }
                    }
                }
                if (at < 0) {
                    r.error = mo + "月没有" + d + "号";
                    mark(s, m);
                    return;
                }
                c.day = at;
                c.dayKind = pre != null && !pre.startsWith("下") && at < today ? "PAST" : "EXACT";
                r.hasDate = true;
                mark(s, m);
            }
        }

        // 今天 / 明天…
        dayWords(s, c, r, today, k);
    }

    private static void relativeDays(StringBuilder s, Ctx c, Result r, long today) {
        Matcher m = REL_DAY.matcher(s);
        if (c.day < 0 && m.find()) {
            int n = CnNum.parse(m.group(1));
            if (n > 0 && n < 400) {
                c.day = addDays(today, n, c.tz);
                c.dayKind = "EXACT";
                r.hasDate = true;
                mark(s, m);
            }
        }
        m = REL_WEEK.matcher(s);
        if (c.day < 0 && m.find()) {
            int n = CnNum.parse(m.group(1));
            if (n > 0 && n < 60) {
                c.day = addDays(today, 7 * n, c.tz);
                c.dayKind = "EXACT";
                r.hasDate = true;
                mark(s, m);
            }
        }
        m = REL_MONTH.matcher(s);
        if (c.day < 0 && m.find()) {
            if ("半".equals(m.group(1))) c.day = addDays(today, 15, c.tz);
            else {
                int n = CnNum.parse(m.group(1));
                Calendar t = c.cal();
                t.setTimeInMillis(today);
                t.add(Calendar.MONTH, Math.max(1, Math.min(24, n)));
                c.day = t.getTimeInMillis();
            }
            c.dayKind = "EXACT";
            r.hasDate = true;
            mark(s, m);
        }
    }

    private static void dayWords(StringBuilder s, Ctx c, Result r, long today, Calendar k) {
        Matcher m = DAY_WORD.matcher(s);
        if (m.find()) {
            String w = m.group();
            int off = w.startsWith("大后") ? 3 : w.startsWith("后") ? 2 : w.startsWith("明") ? 1 : 0;
            if (w.endsWith("晚") || w.endsWith("夜")) c.period = "晚上";
            if (w.endsWith("早")) c.period = "早上";
            if (c.day < 0) {
                c.day = addDays(today, off, c.tz);
                c.dayKind = off == 0 ? "TODAY" : "EXACT";
                r.hasDate = true;
            }
            mark(s, m);
        }

        // 星期
        m = WEEKDAY.matcher(s);
        if (c.day < 0 && m.find()) {
            String pre = m.group(1);
            int dow = dowOf(m.group(2).charAt(0));
            int idx = (dow + 5) % 7;   // 周一 = 0 … 周日 = 6
            int todayIdx = (k.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            long monday = addDays(today, -todayIdx, c.tz);
            if (pre == null) {
                if (idx > todayIdx) {
                    c.day = addDays(monday, idx, c.tz);
                    c.dayKind = "EXACT";
                } else if (idx < todayIdx) {
                    c.day = addDays(monday, 7 + idx, c.tz);
                    c.dayKind = "EXACT";
                } else {
                    c.day = today;
                    c.altDay = addDays(today, 7, c.tz);
                    c.dayKind = "WEEKDAY_BARE";
                    c.dayLabelHint = "周" + "一二三四五六日".charAt(idx);
                }
            } else if (pre.startsWith("下下")) {
                c.day = addDays(monday, 14 + idx, c.tz);
                c.dayKind = "EXACT";
            } else if (pre.startsWith("下")) {
                c.day = addDays(monday, 7 + idx, c.tz);
                c.dayKind = "EXACT";
            } else if (pre.startsWith("上")) {
                c.day = addDays(monday, idx - 7, c.tz);
                c.dayKind = "PAST";
            } else {
                c.day = addDays(monday, idx, c.tz);
                c.dayKind = c.day < today ? "PAST" : (c.day == today ? "TODAY" : "EXACT");
                if ("PAST".equals(c.dayKind)) {
                    c.altDay = addDays(c.day, 7, c.tz);
                    c.dayLabelHint = "周" + "一二三四五六日".charAt(idx);
                }
            }
            r.hasDate = true;
            mark(s, m);
        }
        m = WEEKEND.matcher(s);
        if (c.day < 0 && m.find()) {
            String pre = m.group(1);
            int todayIdx = (k.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            long monday = addDays(today, -todayIdx, c.tz);
            long sat = addDays(monday, (pre != null && pre.startsWith("下") ? 7 : 0) + 5, c.tz);
            if (pre == null && todayIdx == 6) sat = addDays(today, -1, c.tz);   // 周日说「周末」= 这个周末（今天）
            c.day = sat < today ? today : sat;
            c.dayKind = "EXACT";
            r.weekend = true;
            r.hasDate = true;
            mark(s, m);
        }
        m = WEEK_ONLY.matcher(s);
        if (c.day < 0 && m.find()) {
            String pre = m.group(1);
            int todayIdx = (k.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            long monday = addDays(today, -todayIdx, c.tz);
            r.weekStart = pre.startsWith("下下") ? addDays(monday, 14, c.tz) : pre.startsWith("下") ? addDays(monday, 7, c.tz) : monday;
            r.found = true;
            mark(s, m);
        }
        m = SOON.matcher(s);
        if (m.find()) {
            r.soon = true;
            r.found = true;
            mark(s, m);
        }
    }

    private static void absMd(StringBuilder s, Matcher m, Ctx c, Result r, int y, long today) {
        int mo = CnNum.parse(m.group(1)), d = CnNum.parse(m.group(2));
        if (mo < 1 || mo > 12 || d < 1 || d > 31) return;
        long at = ymd(y, mo, d, c.tz);
        if (at < 0) {
            r.error = mo + "月没有" + d + "号";
            mark(s, m);
            return;
        }
        if (at < today) at = ymd(y + 1, mo, d, c.tz);   // 已经过了 → 明年
        if (at < 0) {
            r.error = (y + 1) + "年" + mo + "月没有" + d + "号";
            mark(s, m);
            return;
        }
        c.day = at;
        c.dayKind = "EXACT";
        r.hasDate = true;
        mark(s, m);
    }

    // ------------------------------------------------------------------ 钟点

    static void times(StringBuilder s, Ctx c) {
        Matcher m = CLOCK.matcher(s);
        int endAt = -1;
        if (m.find()) {
            c.hour = Integer.parseInt(m.group(1));
            c.minute = Integer.parseInt(m.group(2));
            String before = s.substring(Math.max(0, m.start() - 4), m.start());
            Matcher pm = PERIOD_ONLY.matcher(before);
            String per = null;
            while (pm.find()) per = pm.group();
            if (per != null) {
                c.period = per;
                int ps = s.lastIndexOf(per, m.start());
                if (ps >= 0) markRange(s, ps, ps + per.length());
            }
            c.anyTime = true;
            endAt = m.end();
            markRange(s, m.start(), m.end());
        } else {
            m = HOUR.matcher(s);
            while (m.find()) {
                if (!plausibleHour(s, m)) continue;
                int h = CnNum.parse(m.group(2));
                if (h < 0 || h > 24) continue;
                int mi = minuteOf(m.group(4), m.group(6));
                if (mi < 0 || mi > 59) continue;
                c.hour = h;
                c.minute = mi;
                if (m.group(1) != null) c.period = m.group(1);
                c.anyTime = true;
                endAt = m.end();
                markRange(s, m.start(), m.end());
                break;
            }
        }
        if (c.anyTime && endAt >= 0) {
            // 区间：「三点到五点」「15:00-16:30」
            String tail = s.substring(endAt);
            Matcher sep = RANGE_SEP.matcher(tail);
            if (sep.find()) {
                String after = tail.substring(sep.end());
                Matcher m2 = CLOCK.matcher(after);
                boolean ok = false;
                if (m2.lookingAt()) {
                    c.endHour = Integer.parseInt(m2.group(1));
                    c.endMinute = Integer.parseInt(m2.group(2));
                    ok = true;
                } else {
                    m2 = HOUR.matcher(after);
                    if (m2.lookingAt()) {
                        int h = CnNum.parse(m2.group(2));
                        int mi = minuteOf(m2.group(4), m2.group(6));
                        if (h >= 0 && h <= 24 && mi >= 0 && mi <= 59) {
                            c.endHour = h;
                            c.endMinute = mi;
                            c.endPeriod = m2.group(1);
                            ok = true;
                        }
                    }
                }
                if (ok) markRange(s, endAt, endAt + sep.end() + m2.end());
            }
        }
        if (!c.anyTime) {
            m = PERIOD_ONLY.matcher(s);
            if (m.find()) {
                c.period = m.group();
                c.periodOnly = true;
                markRange(s, m.start(), m.end());
            } else if (c.period != null) {
                c.periodOnly = true;   // 「今晚」「明早」「每晚」
            }
        } else {
            // 钟点前面单独出现的时段（「下午 三点」中间被别的词隔开时）
            if (c.period == null) {
                m = PERIOD_ONLY.matcher(s);
                if (m.find()) {
                    c.period = m.group();
                    markRange(s, m.start(), m.end());
                }
            }
        }
    }

    /** 「早一点」「快一点」「有点」不是钟点。 */
    static boolean plausibleHour(CharSequence s, Matcher m) {
        if (m.group(1) != null) return true;
        String num = m.group(2);
        if (!"点".equals(m.group(3)) && !"点钟".equals(m.group(3))) {
            // 「时」：要求是数字 + 时（「15时」），避免「小时」「到时」
            return num.matches("[0-9]{1,2}") || m.group(4) != null;
        }
        int st = m.start();
        char prev = st > 0 ? s.charAt(st - 1) : ' ';
        if ("一".equals(num) || "两".equals(num)) {
            if ("早晚快慢多少好大小有差些再稍提前".indexOf(prev) >= 0) return false;
            int e = m.end();
            if (e < s.length() && "儿".indexOf(s.charAt(e)) >= 0) return false;
        }
        if (prev == '第' || prev == '几') return false;
        return true;
    }

    static int minuteOf(String tail, String num) {
        if (tail == null || tail.length() == 0 || "整".equals(tail)) return 0;
        if ("半".equals(tail)) return 30;
        if ("一刻".equals(tail)) return 15;
        if ("三刻".equals(tail)) return 45;
        int mi = CnNum.parse(num);
        // 「三点五」口语 = 3:05？更常见是「三点五分」；没有「分」字、个位数 → 按 X 点 0Y 分
        return mi;
    }

    // ------------------------------------------------------------------ 组合

    static void compose(Ctx c, Result r) {
        long today = c.today();
        boolean hasClock = c.anyTime || c.periodOnly;
        if (c.day >= 0 || hasClock || r.repeat != SchedEvent.REPEAT_NONE || r.weekStart >= 0 || r.soon) r.found = true;
        if (!r.found) return;
        if (r.weekStart >= 0 && c.day < 0) {
            // 只说了「下周」：查询给范围；建日程（带钟点）要追问哪天
            r.start = r.weekStart;
            r.allDay = true;
            if (hasClock) {
                // 钟点先记在那周周一（回答「周三」后换日子、保留钟点）
                int[] w;
                if (c.anyTime) {
                    if (c.period != null) w = withPeriod(c.hour, c.minute, c.period);
                    else if (c.hour >= 1 && c.hour <= 6) w = new int[]{c.hour + 12, c.minute, 0};
                    else w = new int[]{c.hour == 24 ? 0 : c.hour, c.minute, 0};
                } else {
                    w = defaultFor(c.period);
                    r.timeGuessed = true;
                }
                r.start = at(r.weekStart, w, c.tz);
                r.allDay = false;
                r.hasTime = true;
                r.question = r.weekStart > today ? "下周哪一天？" : "这周哪一天？";
            }
            return;
        }
        if (r.soon && c.day < 0 && !hasClock) {
            r.start = today;
            r.allDay = true;
            return;
        }

        // 钟点 → 24 小时制
        int[] hm = null;          // 确定的 {时, 分, 加几天}
        int[][] alts = null;      // 歧义候选
        if (c.anyTime) {
            int h = c.hour;
            if (c.period != null) {
                hm = withPeriod(h, c.minute, c.period);
            } else if (h == 0 || h >= 12) {
                hm = h == 24 ? new int[]{0, c.minute, 1} : new int[]{h, c.minute, 0};
            } else if (h >= 7) {
                hm = new int[]{h, c.minute, 0};
                alts = new int[][]{{h, c.minute, 0}, {h + 12, c.minute, 0}};
            } else {
                alts = new int[][]{{h + 12, c.minute, 0}, {h, c.minute, 0}};
            }
        } else if (c.periodOnly) {
            hm = defaultFor(c.period);
            r.timeGuessed = true;
        }
        r.hasTime = hm != null || alts != null;

        // 重复：起点 = 下一次
        if (r.repeat != SchedEvent.REPEAT_NONE) {
            r.monthDay = r.repeat == SchedEvent.REPEAT_MONTHLY ? r.monthDay : 0;
            int[] use = hm != null ? hm : (alts != null ? alts[0] : null);
            if (alts != null && (alts[0][0] < 12 && alts[0][0] >= 7)) use = alts[0];
            if (alts != null && use == alts[0] && alts[0][0] >= 12 && c.hour >= 1 && c.hour <= 6) {
                // 「每周三三点」：不追问，按下午（凌晨开会的重复日程极少）
                use = alts[0];
            }
            if (use == null) {
                r.allDay = true;
                use = new int[]{0, 0, 0};
            }
            SchedEvent tmp = new SchedEvent();
            tmp.repeat = r.repeat;
            tmp.weekMask = r.weekMask;
            tmp.monthDay = r.monthDay;
            tmp.lunar = r.lunar;
            tmp.lunarMonth = r.lunarMonth;
            tmp.lunarDay = r.lunarDay;
            tmp.allDay = r.allDay;
            long base = c.day >= 0 ? c.day : today;
            tmp.startAt = at(base, use, c.tz);
            if (r.repeat == SchedEvent.REPEAT_YEARLY && !r.lunar && c.day >= 0) {
                Calendar k = Calendar.getInstance(c.tz);
                k.setTimeInMillis(c.day);
                tmp.monthDay = 0;
            }
            if (r.repeat == SchedEvent.REPEAT_YEARLY && r.lunar && !Lunar.available()) {
                r.error = "农历日期暂时算不了";
                return;
            }
            long first = SchedRecur.nextStart(tmp, c.now - 60000L, c.tz);
            if (first < 0) {
                r.error = "算不出下一次的日子";
                return;
            }
            r.start = first;
            if (c.endHour >= 0) r.end = endFor(first, use, c);
            return;
        }

        boolean implicit = c.day < 0;
        long day = implicit ? today : c.day;

        if (hm == null && alts == null) {
            r.allDay = true;
            r.start = day;
            if ("PAST".equals(c.dayKind)) pastQuestion(c, r, null);
            else if ("WEEKDAY_BARE".equals(c.dayKind)) weekdayQuestion(c, r, null);
            return;
        }

        if (alts != null) {
            long a0 = at(day, alts[0], c.tz), a1 = at(day, alts[1], c.tz);
            boolean bare1to6 = c.hour >= 1 && c.hour <= 6;
            if (implicit || "TODAY".equals(c.dayKind)) {
                boolean f0 = a0 >= c.now, f1 = a1 >= c.now;
                // 今天：只有一个还没过 → 取那个（小爱的做法：十点说「三点开会」= 下午三点）
                if (f0 && !f1) hm = alts[0];
                else if (f1 && !f0) hm = alts[1];
                else if (!f0 && !f1) {
                    if (implicit) {
                        day = addDays(today, 1, c.tz);
                        r.rolled = true;
                        if (!bare1to6) hm = alts[0].length > 0 && alts[0][0] < 12 ? alts[0] : alts[1];
                    }
                    // 说了「今天」但两个都过了 → 下面按过去处理
                    if (hm == null && !implicit) hm = bare1to6 ? alts[0] : (alts[0][0] < 12 ? alts[0] : alts[1]);
                } else if (!bare1to6) {
                    hm = alts[0][0] < 12 ? alts[0] : alts[1];
                }
            } else if (!bare1to6) {
                hm = alts[0][0] < 12 ? alts[0] : alts[1];
            }
            if (hm == null) {
                // 追问：下午 / 凌晨（6 点：晚上 / 早上）
                int h = c.hour;
                String pmLabel = h == 6 ? "晚上" + h + "点" : "下午" + h + "点";
                String amLabel = h == 6 ? "早上" + h + "点" : "凌晨" + h + "点";
                r.question = "是" + pmLabel + "还是" + amLabel + "？";
                long s0 = at(day, alts[0], c.tz), s1 = at(day, alts[1], c.tz);
                r.options.add(new Option(pmLabel, s0, endOr0(s0, alts[0], c)));
                r.options.add(new Option(amLabel, s1, endOr0(s1, alts[1], c)));
                r.start = s0;
                r.end = endOr0(s0, alts[0], c);
                return;
            }
        }

        long start = at(day, hm, c.tz);
        if (implicit && start < c.now - 60000L) {
            start = at(addDays(today, 1, c.tz), hm, c.tz);
            r.rolled = true;
        }
        r.start = start;
        r.end = endOr0(start, hm, c);
        if ("PAST".equals(c.dayKind)) {
            pastQuestion(c, r, hm);
        } else if ("WEEKDAY_BARE".equals(c.dayKind)) {
            if (start < c.now - 60000L) {
                // 今天这个点已过 → 下周同一天，不追问
                r.start = at(c.altDay, hm, c.tz);
                r.end = endOr0(r.start, hm, c);
            } else {
                weekdayQuestion(c, r, hm);
            }
        } else if ("TODAY".equals(c.dayKind) && start < c.now - 60000L) {
            r.question = "今天" + hhmm(start, c.tz) + "已经过了，改到明天吗？";
            long t = at(addDays(today, 1, c.tz), hm, c.tz);
            r.options.add(new Option("明天" + hhmm(t, c.tz), t, endOr0(t, hm, c)));
            r.options.add(new Option("就今天", start, r.end));
        }
    }

    private static void pastQuestion(Ctx c, Result r, int[] hm) {
        if (c.altDay < 0) return;
        long t = hm == null ? c.altDay : at(c.altDay, hm, c.tz);
        r.question = "这" + c.dayLabelHint + "已经过了，是下" + c.dayLabelHint + "吗？";
        r.options.add(new Option("下" + c.dayLabelHint, t, hm == null ? 0 : endOr0(t, hm, c)));
        r.options.add(new Option("就这" + c.dayLabelHint, r.start, r.end));
    }

    private static void weekdayQuestion(Ctx c, Result r, int[] hm) {
        long t = hm == null ? c.altDay : at(c.altDay, hm, c.tz);
        r.question = "是今天，还是下" + c.dayLabelHint + "？";
        r.options.add(new Option("今天", r.start, r.end));
        r.options.add(new Option("下" + c.dayLabelHint, t, hm == null ? 0 : endOr0(t, hm, c)));
    }

    private static long endOr0(long start, int[] hm, Ctx c) {
        if (c.endHour < 0) return 0L;
        return endFor(start, hm, c);
    }

    static long endFor(long start, int[] startHm, Ctx c) {
        int eh = c.endHour, em = c.endMinute;
        int[] e;
        String p = c.endPeriod != null ? c.endPeriod : c.period;
        if (c.endPeriod != null) e = withPeriod(eh, em, c.endPeriod);
        else if (eh < 12 && startHm[0] >= 12 && eh + 12 > startHm[0]) e = new int[]{eh + 12, em, 0};
        else if (p != null && c.endPeriod == null && eh < 12 && startHm[0] >= 12) e = new int[]{eh + 12, em, 0};
        else e = new int[]{eh == 24 ? 0 : eh, em, eh == 24 ? 1 : 0};
        long day = dayStart(start, c.tz);
        long end = at(day, e, c.tz);
        if (end <= start) end = at(addDays(day, 1, c.tz), new int[]{e[0], e[1], 0}, c.tz);
        if (end - start > 24L * 3600000L) return 0L;
        return end;
    }

    /** {时, 分, 加几天}。 */
    static int[] withPeriod(int h, int mi, String p) {
        if (p.equals("凌晨") || p.equals("清晨")) {
            return new int[]{h == 12 ? 0 : h, mi, 0};
        }
        if (p.equals("一大早") || p.equals("大清早") || p.equals("早上") || p.equals("早晨") || p.equals("上午") || p.equals("今早") || p.equals("明早")) {
            return new int[]{h, mi, 0};
        }
        if (p.equals("中午")) {
            return new int[]{h >= 1 && h <= 4 ? h + 12 : h, mi, 0};
        }
        if (p.equals("午后") || p.equals("下午")) {
            return new int[]{h < 12 ? h + 12 : h, mi, 0};
        }
        if (p.equals("半夜") || p.equals("深夜")) {
            if (h == 12 || h == 0 || h == 24) return new int[]{0, mi, 1};
            if (h <= 5) return new int[]{h, mi, 1};
            return new int[]{h < 12 ? h + 12 : h, mi, 0};
        }
        // 傍晚 / 晚上 / 夜里 / 今晚 / 明晚 / 今夜
        if (h == 12 || h == 24) return new int[]{0, mi, 1};
        if (h >= 1 && h <= 4) return new int[]{h, mi, 1};
        return new int[]{h < 12 ? h + 12 : h, mi, 0};
    }

    static int[] defaultFor(String p) {
        if (p == null) return new int[]{9, 0, 0};
        if (p.equals("凌晨")) return new int[]{6, 0, 0};
        if (p.contains("早") || p.equals("清晨")) return new int[]{8, 0, 0};
        if (p.equals("上午")) return new int[]{9, 0, 0};
        if (p.equals("中午")) return new int[]{12, 0, 0};
        if (p.equals("午后") || p.equals("下午")) return new int[]{15, 0, 0};
        if (p.equals("傍晚")) return new int[]{18, 0, 0};
        if (p.equals("半夜") || p.equals("深夜")) return new int[]{23, 0, 0};
        return new int[]{20, 0, 0};
    }

    // ------------------------------------------------------------------ 工具

    static String normalize(String t) {
        if (t == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch >= '０' && ch <= '９') ch = (char) ('0' + (ch - '０'));
            if (ch == '：') ch = ':';
            if (ch == '點') ch = '点';
            if (ch == '禮') ch = '礼';
            if (Character.isWhitespace(ch)) continue;
            b.append(ch);
        }
        return b.toString();
    }

    static void mark(StringBuilder s, Matcher m) {
        markRange(s, m.start(), m.end());
    }

    static void markRange(StringBuilder s, int a, int b) {
        for (int i = Math.max(0, a); i < Math.min(s.length(), b); i++) s.setCharAt(i, MARK);
    }

    /** 去掉时间词后剩下的话（时间词处留一个空格，方便标题清洗时断开）。 */
    static String rest(StringBuilder s) {
        StringBuilder b = new StringBuilder();
        boolean gap = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == MARK) {
                gap = true;
                continue;
            }
            if (gap && b.length() > 0) b.append(' ');
            gap = false;
            b.append(ch);
        }
        return b.toString().trim();
    }

    static int bit(int dow) {
        return 1 << (dow - 1);
    }

    static int dowOf(char ch) {
        switch (ch) {
            case '一': case '1': return Calendar.MONDAY;
            case '二': case '2': return Calendar.TUESDAY;
            case '三': case '3': return Calendar.WEDNESDAY;
            case '四': case '4': return Calendar.THURSDAY;
            case '五': case '5': return Calendar.FRIDAY;
            case '六': case '6': return Calendar.SATURDAY;
            case '日': case '天': case '7': return Calendar.SUNDAY;
            default: return -1;
        }
    }

    static int lunarMonth(String w) {
        if (w == null) return -1;
        if (w.equals("正")) return 1;
        if (w.equals("冬")) return 11;
        if (w.equals("腊")) return 12;
        int n = CnNum.parse(w);
        return n >= 1 && n <= 12 ? n : -1;
    }

    static int lunarDayNum(String w) {
        if (w == null) return -1;
        if (w.startsWith("初")) return CnNum.parse(w.substring(1));
        return CnNum.parse(w);
    }

    public static long dayStart(long t, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(t);
        k.set(Calendar.HOUR_OF_DAY, 0);
        k.set(Calendar.MINUTE, 0);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    public static long addDays(long day, int n, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(day);
        k.add(Calendar.DAY_OF_MONTH, n);
        return k.getTimeInMillis();
    }

    static long at(long day, int[] hm, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(day);
        if (hm.length > 2 && hm[2] != 0) k.add(Calendar.DAY_OF_MONTH, hm[2]);
        k.set(Calendar.HOUR_OF_DAY, hm[0]);
        k.set(Calendar.MINUTE, hm[1]);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    /** y-m-d 的 0 点；日子不存在返回 -1。 */
    public static long ymd(int y, int m, int d, TimeZone tz) {
        if (m < 1 || m > 12 || d < 1 || d > daysIn(y, m)) return -1L;
        Calendar k = Calendar.getInstance(tz);
        k.clear();
        k.set(y, m - 1, d, 0, 0, 0);
        return k.getTimeInMillis();
    }

    static int daysIn(int y, int m) {
        Calendar k = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        k.clear();
        k.set(y, m - 1, 1);
        return k.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    static long roundMinute(long t) {
        return (t + 30000L) / 60000L * 60000L;
    }

    static String hhmm(long t, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(t);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
    }
}
