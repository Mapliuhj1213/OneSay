package com.onesay.assistant.v2.ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * 1 复刻三星闹钟）列表卡的样子：视觉常量 + 纯格式化（JVM 单测）。
 *
 * <p>数值全部照抄 OneSay Clock 源码（apps/clock，com.onesay.clock）：
 * <ul>
 *   <li>{@code ui/Ui.java}：卡片白底 #FFFFFF、圆角 26dp、卡间距 12dp；文字 T1 #111111 / T2 #8A8A8E / 关闭 #B9B9BD；
 *       One UI 蓝 #3E7BF2；开关 thumb 蓝 / #FAFAFA，track 半透明蓝 / #42000000；水波纹 #1F000000。</li>
 *   <li>{@code ui/MainActivity.item()}：行内边距 24/16/16/16；上排标签行（名字 13.5sp + 「智能播报」小胶囊 11sp medium，
 *       胶囊底 = 色 &amp; 0x1FFFFFFF、圆角 8dp、内边距 7/2）；时间行底对齐：「上午 / 下午」15sp regular（右 5dp、下 5dp）+
 *       时间 40sp sans-serif-light；右侧星期七个字 13sp（重复日蓝、没选的 #B9B9BD；单次显示「今天 / 明天」），右边距 10dp；最右开关。
 *       关闭的闹钟时间整体变 #B9B9BD。</li>
 *   <li>{@code core/AlarmMath.untilLabel()}：列表大标题下那行「X 小时 Y 分钟后响铃」/「所有闹钟均已关闭」。</li>
 * </ul>
 * 只改样式与展示：开关 / 点卡片的回调与定闹钟逻辑不变（仍是晨间播报 dump 协议 {id,h,m,d,on,r,next}）。
 */
public final class AlarmCardStyle {

    private AlarmCardStyle() { }

    // ---- 颜色（Bixby 时钟 Ui.java 原值） ----
    public static final int CARD = 0xFFFFFFFF;
    public static final int T1 = 0xFF111111;
    public static final int T2 = 0xFF8A8A8E;
    public static final int T_OFF = 0xFFB9B9BD;
    public static final int ACCENT = 0xFF3E7BF2;
    public static final int RIPPLE = 0x1F000000;
    public static final int THUMB_ON = ACCENT;
    public static final int THUMB_OFF = 0xFFFAFAFA;
    public static final int TRACK_ON = ACCENT & 0x80FFFFFF | 0x80000000;
    public static final int TRACK_OFF = 0x42000000;

    // ---- 尺寸（dp / sp，MainActivity.item / Ui.card / Ui.cardLp 原值） ----
    public static final float CARD_RADIUS_DP = 26f;
    public static final float CARD_GAP_DP = 12f;
    public static final float PAD_LEFT_DP = 24f, PAD_TOP_DP = 16f, PAD_RIGHT_DP = 16f, PAD_BOTTOM_DP = 16f;
    public static final float LABEL_SP = 13.5f;
    public static final float CHIP_SP = 11f;
    public static final float CHIP_RADIUS_DP = 8f, CHIP_PAD_H_DP = 7f, CHIP_PAD_V_DP = 2f;
    public static final float TAGS_BOTTOM_DP = 6f;
    public static final float AMPM_SP = 15f, AMPM_RIGHT_DP = 5f, AMPM_BOTTOM_DP = 5f;
    public static final float TIME_SP = 40f;
    public static final float DAYS_SP = 13f, DAYS_RIGHT_DP = 10f;
    /** 列表上方那行（Bixby 时钟大标题下的副标题 14sp T2）。 */
    public static final float SUMMARY_SP = 14f;

    /** 「智能播报」胶囊在这里叫「智能播报」（闹钟仍在晨间播报里，铃声类型 r=smart）。 */
    public static final String CHIP_SMART = "智能播报";
    public static final String ALL_OFF = "所有闹钟均已关闭";
    static final String[] DAYS = {"日", "一", "二", "三", "四", "五", "六"};

    /** 一张卡的展示模型（文本在这里格式化好，View 只画）。 */
    public static final class Card {
        public final int id;
        public final int hour, minute;
        /** bit0=周日 … bit6=周六；0 = 只响一次。 */
        public final int days;
        public final boolean on;
        /** 铃声是智能播报（显示「智能播报」胶囊）。 */
        public final boolean smart;
        /** 「上午 / 下午」；24 小时制为空串。 */
        public final String ampm;
        /** 「7:00」/「07:00」。 */
        public final String time;
        /** 单次闹钟：「今天 / 明天」；重复闹钟：空串（画七个字）。 */
        public final String oneShot;
        /** 无障碍朗读用：「明天 上午 7:00，已开启，智能播报」。 */
        public final String spoken;

        Card(int id, int hour, int minute, int days, boolean on, boolean smart, String ampm, String time, String oneShot, String spoken) {
            this.id = id;
            this.hour = hour;
            this.minute = minute;
            this.days = days;
            this.on = on;
            this.smart = smart;
            this.ampm = ampm;
            this.time = time;
            this.oneShot = oneShot;
            this.spoken = spoken;
        }

        public boolean repeats() {
            return days != 0;
        }
    }

    // ------------------------------------------------------------------ 格式化

    /** 「7:00」或「07:00」（MainActivity.item 的写法：24 小时制补零，12 小时制 12 点叫 12）。 */
    public static String time(boolean is24, int h24, int minute) {
        int hh = is24 ? h24 : (h24 % 12 == 0 ? 12 : h24 % 12);
        return (is24 && hh < 10 ? "0" : "") + hh + ":" + (minute < 10 ? "0" : "") + minute;
    }

    public static String ampm(boolean is24, int h24) {
        return is24 ? "" : (h24 < 12 ? "上午" : "下午");
    }

    /** 关闭的闹钟时间整体变浅（#B9B9BD）。 */
    public static int timeColor(boolean on) {
        return on ? T1 : T_OFF;
    }

    public static int labelColor(boolean on) {
        return on ? T2 : T_OFF;
    }

    public static int chipColor(boolean on) {
        return on ? ACCENT : T_OFF;
    }

    /** 胶囊底色：前景色 &amp; 0x1FFFFFFF（约 12% 透明度）。 */
    public static int chipBg(boolean on) {
        return chipColor(on) & 0x1FFFFFFF;
    }

    /** 星期七个字（中间空格）：「日 一 二 三 四 五 六」。 */
    public static String daysText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            sb.append(DAYS[i]);
            if (i < 6) sb.append(' ');
        }
        return sb.toString();
    }

    /** 第 i 个字（0=日）在 {@link #daysText()} 里的下标。 */
    public static int dayCharIndex(int i) {
        return i * 2;
    }

    /** 星期第 i 个字的颜色：选中 = 开着蓝 / 关着灰 T2；没选 = #B9B9BD（MainActivity.daysSpan）。 */
    public static int dayColor(int days, int i, boolean on) {
        boolean sel = (days & (1 << i)) != 0;
        return sel ? (on ? ACCENT : T2) : T_OFF;
    }

    /** 单次闹钟右侧那两个字：下次响在今天 → 「今天」，否则「明天」（next 缺失就按时刻算下一次）。 */
    public static String oneShot(long next, int hour, int minute, long now, TimeZone tz) {
        long at = next > 0 ? next : nextAt(hour, minute, now, tz);
        return dayKey(at, tz) == dayKey(now, tz) ? "今天" : "明天";
    }

    /** 列表上方那行：最早一个开着的闹钟还有多久（AlarmMath.untilLabel 原样）。 */
    public static String untilLabel(long at, long now) {
        if (at <= 0) return ALL_OFF;
        long min = Math.max(0, (at - now + 59999L) / 60000L);
        long d = min / 1440, h = (min % 1440) / 60, m = min % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append(" 天 ");
        if (h > 0) sb.append(h).append(" 小时 ");
        if (m > 0 || sb.length() == 0) sb.append(m).append(" 分钟");
        return sb.toString().trim() + "后响铃";
    }

    /** dump 里最早一个开着、还没过的闹钟时刻；没有 → 0。 */
    public static long earliestOn(JSONObject dump, long now) {
        JSONArray arr = dump == null ? null : dump.optJSONArray("alarms");
        long best = 0;
        if (arr == null) return 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !o.optBoolean("on", false)) continue;
            long nx = o.optLong("next", 0L);
            if (nx > now && (best == 0 || nx < best)) best = nx;
        }
        return best;
    }

    /** dump → 卡片（按时刻升序，与 Bixby 时钟 / 晨间播报列表一致）。 */
    public static List<Card> fromDump(JSONObject dump, boolean is24, long now, TimeZone tz) {
        List<Card> out = new ArrayList<Card>();
        JSONArray arr = dump == null ? null : dump.optJSONArray("alarms");
        if (arr == null) return out;
        List<JSONObject> objs = new ArrayList<JSONObject>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) objs.add(o);
        }
        for (int i = 1; i < objs.size(); i++) {   // 条数很少，插入排序（稳定）
            JSONObject cur = objs.get(i);
            int key = cur.optInt("h", 0) * 60 + cur.optInt("m", 0);
            int j = i - 1;
            while (j >= 0 && objs.get(j).optInt("h", 0) * 60 + objs.get(j).optInt("m", 0) > key) {
                objs.set(j + 1, objs.get(j));
                j--;
            }
            objs.set(j + 1, cur);
        }
        for (JSONObject o : objs) {
            int h = o.optInt("h", 7), m = o.optInt("m", 0), d = o.optInt("d", 0) & 0x7F;
            boolean on = o.optBoolean("on", false);
            boolean smart = "smart".equals(o.optString("r", "smart"));
            String ap = ampm(is24, h), t = time(is24, h, m);
            String one = d == 0 ? oneShot(o.optLong("next", 0L), h, m, now, tz) : "";
            String spoken = (d == 0 ? one : repeatSpoken(d)) + " " + (ap.length() > 0 ? ap + " " : "") + t
                    + "，" + (on ? "已开启" : "已关闭") + (smart ? "，" + CHIP_SMART : "");
            out.add(new Card(o.optInt("id", 0), h, m, d, on, smart, ap, t, one, spoken));
        }
        return out;
    }

    static String repeatSpoken(int days) {
        if (days == 0x7F) return "每天";
        if (days == 0x3E) return "工作日";
        if (days == 0x41) return "周末";
        StringBuilder b = new StringBuilder("每周");
        for (int i = 1; i <= 7; i++) if ((days & (1 << (i % 7))) != 0) b.append(DAYS[i % 7]);
        return b.toString();
    }

    // ------------------------------------------------------------------ 日期小工具

    static int dayKey(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    static long nextAt(int hour, int minute, long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }
}
