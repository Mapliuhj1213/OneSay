package com.onesay.clock.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 播报里的降雨提醒句（0.1.6，用户 2026-11-07 定；纯 Java，JVM 全测）。位置：「今天是X天气。」之后、新闻之前；固定句式，不过 LLM。
 *
 * <ol>
 *   <li>和风分钟级降水（/v7/minutely/5m，未来 2 小时每 5 分钟）：现在正在下 →「现在正在下雨，出门记得带伞。」；
 *       2 小时内开始 →「大约 N 分钟后有小雨/中雨/大雨，出门记得带伞。」（N 向上取到 5 的倍数，最少 5）；雪同理（小雪/中雪/大雪）。</li>
 *   <li>两小时内不下、但逐小时预报（/v7/weather/24h，或新版 /weather/v1/hourly）今天（本地日）晚些时候有雨 →
 *       「今天下午可能有雨，出门记得带伞。」（取第一个有雨的时段：凌晨/上午/中午/下午/傍晚/晚上）。</li>
 *   <li>否则不说。分钟级数据拿不到（没 key、网络、超时、解析失败）→ 整句不说（不拿逐小时单独猜）。</li>
 * </ol>
 * 分钟级解析口径照搬 OneSay feat/rain-minutely（RainForecast / RainAnalysis）：precip &gt; 0 算有降水；第 i 点覆盖 [fxTime, fxTime+5min)；
 * 强度 = 那一阵的最大 5 分钟量 ×12 折小时雨强，&lt;2.5 小雨 / &lt;8 中雨 / 以上大雨（雪 &lt;1 / &lt;3 / 以上）。
 */
public final class RainLine {
    private RainLine() { }

    public static final long STEP_MS = 5 * 60_000L, HOUR_MS = 3600_000L, HORIZON_MS = 2 * HOUR_MS;
    /** 句尾（朗读表 rain.tail）。 */
    public static String tail() { return Bank.rain("tail"); }

    /** 5 分钟一点。 */
    public static final class Pt {
        public final long at;
        public final double precip;
        public final boolean snow;

        public Pt(long at, double precip, boolean snow) { this.at = at; this.precip = precip < 0 ? 0 : precip; this.snow = snow; }

        public boolean wet() { return precip > 0; }
    }

    /** 逐小时一点（覆盖 [at, at+1h)）。 */
    public static final class Hr {
        public final long at;
        public final boolean wet, snow;

        public Hr(long at, boolean wet, boolean snow) { this.at = at; this.wet = wet; this.snow = snow; }
    }

    /** 判定结果（kind 用于日志与测试）。 */
    public static final class Result {
        public final String kind;   // NOW / SOON / LATER / NONE
        public final String line;   // 要说的话；NONE 时为 null

        Result(String kind, String line) { this.kind = kind; this.line = line; }

        public String toString() { return kind + (line == null ? "" : ":" + line); }
    }

    static final Result NONE = new Result("NONE", null);

    // ------------------------------------------------------------------ 解析

    /** 和风 /v7/minutely/5m 响应 → 点列（code 不是 200 / 没有点 → null）。 */
    public static List<Pt> parseMinutely(String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "" : json.trim());
            if (!"200".equals(o.optString("code", ""))) return null;
            JSONArray a = o.optJSONArray("minutely");
            if (a == null || a.length() == 0) return null;
            List<Pt> r = new ArrayList<Pt>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                if (m == null) continue;
                long at = parseTime(m.optString("fxTime", ""));
                if (at <= 0) continue;
                r.add(new Pt(at, num(m.optString("precip", "0")), "snow".equalsIgnoreCase(m.optString("type", "rain").trim())));
            }
            if (r.isEmpty()) return null;
            Collections.sort(r, new Comparator<Pt>() { public int compare(Pt x, Pt y) { return x.at < y.at ? -1 : (x.at == y.at ? 0 : 1); } });
            return r;
        } catch (Exception e) { return null; }
    }

    /**
     * 逐小时预报 → 点列；两种格式都认：
     * v7（/v7/weather/24h：code="200"、hourly[].fxTime / icon / precip）和 v1（/weather/v1/hourly/{lat}/{lon}：hours[].forecastTime /
     * condition.code / precipitation.amount.value / precipitation.type）。看不懂 → null。
     */
    public static List<Hr> parseHourly(String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "" : json.trim());
            List<Hr> r = new ArrayList<Hr>();
            JSONArray a = o.optJSONArray("hourly");
            if (a != null) {
                if (!"200".equals(o.optString("code", ""))) return null;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject h = a.optJSONObject(i);
                    if (h == null) continue;
                    long at = parseTime(h.optString("fxTime", ""));
                    if (at <= 0) continue;
                    int icon = (int) num(h.optString("icon", "-1"));
                    double p = num(h.optString("precip", "0"));
                    r.add(new Hr(at, wetIcon(icon) || p >= WET_MM, snowIcon(icon)));
                }
            } else {
                a = o.optJSONArray("hours");
                if (a == null) return null;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject h = a.optJSONObject(i);
                    if (h == null) continue;
                    long at = parseTime(h.optString("forecastTime", ""));
                    if (at <= 0) continue;
                    JSONObject cond = h.optJSONObject("condition");
                    int icon = cond == null ? -1 : (int) num(cond.optString("code", "-1"));
                    JSONObject pr = h.optJSONObject("precipitation");
                    double p = 0;
                    String type = "";
                    if (pr != null) {
                        JSONObject amt = pr.optJSONObject("amount");
                        if (amt != null) p = amt.optDouble("value", 0);
                        type = pr.optString("type", "");
                    }
                    boolean snow = snowIcon(icon) || "snow".equalsIgnoreCase(type);
                    r.add(new Hr(at, wetIcon(icon) || p >= WET_MM, snow));
                }
            }
            if (r.isEmpty()) return null;
            Collections.sort(r, new Comparator<Hr>() { public int compare(Hr x, Hr y) { return x.at < y.at ? -1 : (x.at == y.at ? 0 : 1); } });
            return r;
        } catch (Exception e) { return null; }
    }

    /** 逐小时「有雨」门槛：天气现象是雨/雪类（和风图标 3xx/4xx），或该小时降水量 ≥ 0.1 mm。 */
    public static final double WET_MM = 0.1;

    static boolean wetIcon(int icon) { return icon >= 300 && icon < 500; }

    static boolean snowIcon(int icon) { return icon >= 400 && icon < 500; }

    static double num(String s) {
        try { return Double.parseDouble(s == null ? "" : s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    static final Pattern ISO = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?(Z|([+-])(\\d{2}):?(\\d{2}))?");

    /** 「2026-11-07T07:05+08:00」/「2024-05-31T03:00Z」→ epoch ms；看不懂 → 0；没带时区按 +08:00（和风分钟级只覆盖中国）。 */
    public static long parseTime(String s) {
        if (s == null) return 0;
        Matcher m = ISO.matcher(s.trim());
        if (!m.matches()) return 0;
        int off = 8 * 60;
        if (m.group(7) != null) {
            if ("Z".equals(m.group(7))) off = 0;
            else {
                off = Integer.parseInt(m.group(9)) * 60 + Integer.parseInt(m.group(10));
                if ("-".equals(m.group(8))) off = -off;
            }
        }
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
        return c.getTimeInMillis() - off * 60_000L;
    }

    // ------------------------------------------------------------------ 判定

    /**
     * @param minutely 分钟级点列；null = 拿不到 → 整句不说
     * @param hourly   逐小时点列；null = 拿不到 → 只做第 1 步
     * @param now      判定时刻（= 闹钟时刻；提前生成时用闹钟时刻，不用生成时刻）
     * @param tz       本地时区（「今天」与时段按它算）
     */
    public static Result decide(List<Pt> minutely, List<Hr> hourly, long now, TimeZone tz) {
        if (minutely == null) return NONE;
        List<Pt> live = new ArrayList<Pt>();
        for (Pt p : minutely) if (p.at + STEP_MS > now && p.at < now + HORIZON_MS) live.add(p);
        if (live.isEmpty()) return NONE;                       // 全过期 = 数据太旧，不猜
        int first = -1;
        for (int i = 0; i < live.size(); i++) if (live.get(i).wet()) { first = i; break; }
        if (first >= 0) {
            Pt f = live.get(first);
            String what = Bank.rain(f.snow ? "what.snow" : "what.rain");
            if (f.at <= now) return new Result("NOW", Bank.fill(Bank.rain("now"), "what", what, "tail", tail()));
            double peak = 0;
            for (int i = first; i < live.size() && live.get(i).wet(); i++) peak = Math.max(peak, live.get(i).precip);
            return new Result("SOON", Bank.fill(Bank.rain("soon"), "minutes", String.valueOf(roundMin(f.at - now)),
                    "intensity", intensity(peak, f.snow), "tail", tail()));
        }
        if (hourly == null) return NONE;
        long horizonEnd = now + HORIZON_MS;
        int today = dayKey(now, tz);
        for (Hr h : hourly) {
            if (h.at + HOUR_MS <= horizonEnd) continue;         // 两小时内已由分钟级覆盖（说了不下）
            if (dayKey(h.at, tz) != today) break;               // 只到今天（本地）结束
            if (!h.wet) continue;
            return new Result("LATER", Bank.fill(Bank.rain("later"), "partOfDay", partOfDay(hourOf(h.at, tz)),
                    "what", Bank.rain(h.snow ? "what.snow" : "what.rain"), "tail", tail()));
        }
        return NONE;
    }

    /** 分钟数向上取到 5 的倍数（最少 5）。 */
    public static int roundMin(long ms) {
        long m = (ms + 59_999L) / 60_000L;
        long r = ((m + 4) / 5) * 5;
        return (int) Math.max(5, r);
    }

    /** 那一阵最大 5 分钟量 → 小/中/大雨（暴雨也说大雨）；雪：小/中/大雪。 */
    public static String intensity(double peak5min, boolean snow) {
        double rate = peak5min * 12.0;
        if (snow) return Bank.rainIntensity(rate < 1.0 ? 0 : (rate < 3.0 ? 1 : 2), true);
        return Bank.rainIntensity(rate < 2.5 ? 0 : (rate < 8.0 ? 1 : 2), false);
    }

    /** 0–5 凌晨 / 6–10 上午 / 11–12 中午 / 13–16 下午 / 17–18 傍晚 / 19–23 晚上。 */
    public static String partOfDay(int h) {
        if (h < 6) return Bank.partOfDay(0);
        if (h < 11) return Bank.partOfDay(1);
        if (h < 13) return Bank.partOfDay(2);
        if (h < 17) return Bank.partOfDay(3);
        if (h < 19) return Bank.partOfDay(4);
        return Bank.partOfDay(5);
    }

    static int hourOf(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    static int dayKey(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    // ------------------------------------------------------------------ 调试注入（只有 debug 包用）

    /** 注入的分钟级样本整体平移，让第一个点落在 start（= 闹钟时刻向下取整到 5 分钟）。 */
    public static List<Pt> rebase(List<Pt> pts, long start) {
        if (pts == null || pts.isEmpty()) return pts;
        long d = (start / STEP_MS) * STEP_MS - pts.get(0).at;
        List<Pt> r = new ArrayList<Pt>();
        for (Pt p : pts) r.add(new Pt(p.at + d, p.precip, p.snow));
        return r;
    }

    /** 注入的逐小时样本按整天平移到「今天」（保留一天里的钟点），让「下午 / 晚上」的样本对任何日期都成立。 */
    public static List<Hr> shiftToDay(List<Hr> hrs, long now, TimeZone tz) {
        if (hrs == null || hrs.isEmpty()) return hrs;
        Calendar a = Calendar.getInstance(tz), b = Calendar.getInstance(tz);
        a.setTimeInMillis(hrs.get(0).at);
        b.setTimeInMillis(now);
        int days = 0;
        while (dayKey(a.getTimeInMillis(), tz) < dayKey(now, tz) && days < 4000) { a.add(Calendar.DAY_OF_MONTH, 1); days++; }
        while (dayKey(a.getTimeInMillis(), tz) > dayKey(now, tz) && days > -4000) { a.add(Calendar.DAY_OF_MONTH, -1); days--; }
        long d = a.getTimeInMillis() - hrs.get(0).at;
        List<Hr> r = new ArrayList<Hr>();
        for (Hr h : hrs) r.add(new Hr(h.at + d, h.wet, h.snow));
        return r;
    }

    /** 「api.xxx.qweatherapi.com」：去掉 https:// 和路径；不像域名 → ""。 */
    public static String cleanHost(String v) {
        if (v == null) return "";
        String h = v.trim().replaceFirst("^(?i)https?://", "");
        int s = h.indexOf('/');
        if (s >= 0) h = h.substring(0, s);
        h = h.trim().toLowerCase(java.util.Locale.US);
        return h.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:\\d{1,5})?") ? h : "";
    }

    /** 功能不可用的原因（设置页显示）；null = 可用。 */
    public static String unavailableReason(boolean switchOn, boolean hasKey, boolean hasHost) {
        if (!hasKey) return "未配置和风天气 API Key，不播报降雨提醒";
        if (!hasHost) return "未配置和风天气 API Host，不播报降雨提醒";
        if (!switchOn) return "已关闭";
        return null;
    }

    /** 经纬度保留两位小数（和风要求最多两位，约 1 km）→「lon,lat」。 */
    public static String locParam(double lat, double lon) {
        return String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
    }
}
