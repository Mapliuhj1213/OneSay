package com.onesay.clock.core;

import java.text.SimpleDateFormat;
import java.util.*;

/** 0.1.6 降雨提醒句：解析（和风 v7 分钟级 / v7 逐小时 / v1 逐小时）、判定、措辞、模板位置、开关与配置。 */
public class RainTest {
    static int pass, fail;
    static final List<String> FAILS = new ArrayList<String>();
    static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    static void ok(String name, boolean c) {
        if (c) pass++; else { fail++; FAILS.add(name); System.out.println("  FAIL " + name); }
    }

    static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        if (!c) System.out.println("  want: " + want + "\n  got : " + got);
        ok(name, c);
    }

    static long t(int h, int mi) {
        Calendar c = Calendar.getInstance(SH);
        c.clear();
        c.set(2026, 10, 7, h, mi, 0);
        return c.getTimeInMillis();
    }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'+08:00'", Locale.US);
        f.setTimeZone(SH);
        return f.format(new Date(ms));
    }

    /** 和风 /v7/minutely/5m 样式：从 start 起 24 点，precip[i]（不足补 0）。 */
    static String minutely(long start, String type, double... precip) {
        StringBuilder sb = new StringBuilder("{\"code\":\"200\",\"updateTime\":\"" + iso(start) + "\",\"fxLink\":\"https://www.qweather.com\",\"summary\":\"\",\"minutely\":[");
        for (int i = 0; i < 24; i++) {
            double p = i < precip.length ? precip[i] : 0;
            if (i > 0) sb.append(',');
            sb.append("{\"fxTime\":\"").append(iso(start + i * 300_000L)).append("\",\"precip\":\"").append(p).append("\",\"type\":\"").append(type).append("\"}");
        }
        return sb.append("]}").toString();
    }

    /** 和风 /v7/weather/24h 样式：从 startHour 起 24 小时；wet 里的钟点给图标 icon（其余 101 多云），precip 1.2。 */
    static String hourlyV7(int startHour, int icon, int... wetHours) {
        StringBuilder sb = new StringBuilder("{\"code\":\"200\",\"updateTime\":\"" + iso(t(startHour, 0)) + "\",\"hourly\":[");
        for (int i = 0; i < 24; i++) {
            long at = t(startHour, 0) + i * 3600_000L;
            Calendar c = Calendar.getInstance(SH);
            c.setTimeInMillis(at);
            boolean wet = false;
            for (int w : wetHours) if (c.get(Calendar.HOUR_OF_DAY) == w && c.get(Calendar.DAY_OF_MONTH) == 7) wet = true;
            if (i > 0) sb.append(',');
            sb.append("{\"fxTime\":\"").append(iso(at)).append("\",\"temp\":\"20\",\"icon\":\"").append(wet ? icon : 101)
                    .append("\",\"text\":\"x\",\"pop\":\"").append(wet ? 80 : 5).append("\",\"precip\":\"").append(wet ? "1.2" : "0.0").append("\"}");
        }
        return sb.append("]}").toString();
    }

    static RainLine.Result run(String min, String hr, long now) {
        return RainLine.decide(RainLine.parseMinutely(min), hr == null ? null : RainLine.parseHourly(hr), now, SH);
    }

    public static void main(String[] a) throws Exception {
        parse(); cases(); wording(); template(); config();
        System.out.println("RainTest " + pass + "/" + (pass + fail) + (fail == 0 ? " ALL PASS" : " FAILED: " + FAILS));
        System.exit(fail == 0 ? 0 : 1);
    }

    static void parse() {
        String doc = "{\"code\":\"200\",\"updateTime\":\"2021-12-16T18:55+08:00\",\"summary\":\"95分钟后雨就停了\",\"minutely\":["
                + "{\"fxTime\":\"2021-12-16T18:55+08:00\",\"precip\":\"0.15\",\"type\":\"rain\"},{\"fxTime\":\"2021-12-16T19:00+08:00\",\"precip\":\"0.23\",\"type\":\"rain\"}]}";
        List<RainLine.Pt> p = RainLine.parseMinutely(doc);
        ok("min 官方示例解析 2 点", p != null && p.size() == 2 && p.get(0).wet() && Math.abs(p.get(1).precip - 0.23) < 1e-9);
        eq("min 时间 +08:00", RainLine.parseTime("2021-12-16T18:55+08:00"), p.get(0).at);
        ok("min code 401 → null", RainLine.parseMinutely("{\"code\":\"401\"}") == null);
        ok("min 坏 JSON → null", RainLine.parseMinutely("<html>") == null && RainLine.parseMinutely(null) == null);
        ok("min 没有点 → null", RainLine.parseMinutely("{\"code\":\"200\",\"minutely\":[]}") == null);
        ok("min 乱序会排好", RainLine.parseMinutely("{\"code\":\"200\",\"minutely\":[{\"fxTime\":\"2026-11-07T07:05+08:00\",\"precip\":\"0\"},{\"fxTime\":\"2026-11-07T07:00+08:00\",\"precip\":\"0\"}]}").get(0).at == t(7, 0));
        eq("time Z", RainLine.parseTime("2024-05-31T03:00Z"), RainLine.parseTime("2024-05-31T11:00+08:00"));
        eq("time 坏 → 0", 0L, RainLine.parseTime("昨天"));
        List<RainLine.Hr> h = RainLine.parseHourly(hourlyV7(7, 305, 14));
        ok("hourly v7 解析 24 点、14 点有雨", h != null && h.size() == 24 && h.get(7).wet && !h.get(6).wet);
        ok("hourly v7 code≠200 → null", RainLine.parseHourly("{\"code\":\"500\",\"hourly\":[]}") == null);
        String v1 = "{\"metadata\":{\"tag\":\"x\"},\"hours\":["
                + "{\"forecastTime\":\"2026-11-07T05:00Z\",\"condition\":{\"text\":\"Cloudy\",\"code\":\"101\"},\"precipitation\":{\"amount\":{\"value\":0.0,\"unit\":\"mm\"},\"probability\":0.1,\"type\":\"none\"}},"
                + "{\"forecastTime\":\"2026-11-07T06:00Z\",\"condition\":{\"text\":\"Light Rain\",\"code\":\"305\"},\"precipitation\":{\"amount\":{\"value\":0.8,\"unit\":\"mm\"},\"probability\":0.7,\"type\":\"rain\"}},"
                + "{\"forecastTime\":\"2026-11-07T07:00Z\",\"condition\":{\"text\":\"Overcast\",\"code\":\"104\"},\"precipitation\":{\"amount\":{\"value\":0.3,\"unit\":\"mm\"},\"probability\":0.4,\"type\":\"snow\"}}]}";
        List<RainLine.Hr> h1 = RainLine.parseHourly(v1);
        ok("hourly v1（官方新版）解析：UTC 时间、code 305 有雨", h1 != null && h1.size() == 3 && !h1.get(0).wet && h1.get(1).wet && h1.get(1).at == t(14, 0));
        ok("hourly v1 precipitation.type=snow + 0.3mm → 有雪", h1.get(2).wet && h1.get(2).snow);
        ok("hourly 坏 → null", RainLine.parseHourly("{}") == null && RainLine.parseHourly("x") == null);
        ok("hourly 0.05mm 不算雨（门槛 0.1）", !RainLine.parseHourly("{\"code\":\"200\",\"hourly\":[{\"fxTime\":\"2026-11-07T10:00+08:00\",\"icon\":\"101\",\"precip\":\"0.05\"}]}").get(0).wet);
    }

    static void cases() {
        long al = t(7, 0);
        // 1. 正在下
        RainLine.Result r = run(minutely(al, "rain", 0.15, 0.2, 0.2), hourlyV7(7, 305, 7), al);
        eq("① 正在下雨", "现在正在下雨，出门记得带伞。", r.line);
        eq("① kind NOW", "NOW", r.kind);
        // 生成在闹钟前 10 分钟：06:50 起的数据，07:00 那点下 → 也是「正在下」
        eq("① 提前 10 分钟生成、闹钟时刻在下", "NOW", run(minutely(t(6, 50), "rain", 0, 0, 0.1, 0.1), null, al).kind);
        // 2. N 分钟后
        eq("② 25 分钟后小雨", "大约25分钟后有小雨，出门记得带伞。", run(minutely(al, "rain", 0, 0, 0, 0, 0, 0.1, 0.15), null, al).line);
        eq("② 闹钟 07:02、雨 07:25 → 23 分钟取到 25", "大约25分钟后有小雨，出门记得带伞。", run(minutely(al, "rain", 0, 0, 0, 0, 0, 0.1), null, t(7, 2)).line);
        eq("② 中雨（0.4mm/5min ≈ 4.8mm/h）", "大约10分钟后有中雨，出门记得带伞。", run(minutely(al, "rain", 0, 0, 0.4, 0.3), null, al).line);
        eq("② 60 分钟后大雨（1.0mm/5min = 12mm/h）", "大约60分钟后有大雨，出门记得带伞。",
                run(minutely(al, "rain", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1.0), null, al).line);
        eq("② 暴雨也说大雨", "大约5分钟后有大雨，出门记得带伞。", run(minutely(al, "rain", 0, 2.0), null, al).line);
        eq("② 强度看那一阵的最大值，不看后面另一阵", "大约5分钟后有小雨，出门记得带伞。",
                run(minutely(al, "rain", 0, 0.1, 0, 0, 2.0), null, al).line);
        eq("② 有雨就不再看逐小时", "SOON", run(minutely(al, "rain", 0, 0, 0.1), hourlyV7(7, 305, 15), al).kind);
        // 3. 两小时干 + 下午有雨
        String dry = minutely(al, "rain");
        eq("③ 两小时不下、下午有雨", "今天下午可能有雨，出门记得带伞。", run(dry, hourlyV7(7, 305, 14, 15), al).line);
        eq("③ kind LATER", "LATER", run(dry, hourlyV7(7, 305, 14), al).kind);
        eq("③ 取第一个有雨的时段（上午先于晚上）", "今天上午可能有雨，出门记得带伞。", run(dry, hourlyV7(7, 305, 10, 20), al).line);
        eq("③ 中午", "今天中午可能有雨，出门记得带伞。", run(dry, hourlyV7(7, 305, 12), al).line);
        eq("③ 傍晚", "今天傍晚可能有雨，出门记得带伞。", run(dry, hourlyV7(7, 305, 17), al).line);
        eq("③ v1 逐小时也行（14:00 CST 小雨）", "今天下午可能有雨，出门记得带伞。",
                run(dry, "{\"hours\":[{\"forecastTime\":\"2026-11-07T06:00Z\",\"condition\":{\"code\":\"305\"},\"precipitation\":{\"amount\":{\"value\":0.8},\"type\":\"rain\"}}]}", al).line);
        // 4. 全天不下
        eq("④ 全天不下 → 不说", null, run(dry, hourlyV7(7, 305), al).line);
        eq("④ kind NONE", "NONE", run(dry, hourlyV7(7, 305), al).kind);
        eq("④ 逐小时拿不到、分钟级干 → 不说", null, run(dry, null, al).line);
        // 5. 只有晚上下（闹钟之后很久）
        eq("⑤ 只有晚上 20 点下", "今天晚上可能有雨，出门记得带伞。", run(dry, hourlyV7(7, 305, 20), al).line);
        eq("⑤ 只有明天下 → 不说（只看今天）", null, run(dry, hourlyV7(7, 305, 5), al).line);
        eq("⑤ 晚上闹钟 21:00、雨在明天凌晨 → 不说", null,
                run(minutely(t(21, 0), "rain"), hourlyV7(21, 305, 2), t(21, 0)).line);
        eq("⑤ 逐小时 8 点有雨但分钟级说两小时不下 → 以分钟级为准，不说", null, run(dry, hourlyV7(7, 305, 8), al).line);
        eq("⑤ 逐小时 8 点 + 9 点有雨 → 9 点那段超出两小时，说上午", "今天上午可能有雨，出门记得带伞。", run(dry, hourlyV7(7, 305, 8, 9), al).line);
        eq("⑤ 闹钟前的逐小时有雨不算（6 点）", null, run(dry, hourlyV7(5, 305, 6), al).line);
        // 6. 失败
        eq("⑥ 分钟级失败（401）→ 不说，哪怕逐小时有雨", null, run("{\"code\":\"401\"}", hourlyV7(7, 305, 14), al).line);
        eq("⑥ 分钟级网络失败（null）→ 不说", null, RainLine.decide(null, RainLine.parseHourly(hourlyV7(7, 305, 14)), al, SH).line);
        eq("⑥ 分钟级数据太旧（全在闹钟前）→ 不说", null, run(minutely(t(4, 0), "rain", 0.5, 0.5), null, al).line);
        // 7. 雪
        eq("⑦ 正在下雪", "现在正在下雪，出门记得带伞。", run(minutely(al, "snow", 0.05), null, al).line);
        eq("⑦ 10 分钟后小雪", "大约10分钟后有小雪，出门记得带伞。", run(minutely(al, "snow", 0, 0, 0.05), null, al).line);
        eq("⑦ 中雪（0.2mm/5min = 2.4mm/h）", "大约5分钟后有中雪，出门记得带伞。", run(minutely(al, "snow", 0, 0.2), null, al).line);
        eq("⑦ 大雪", "大约5分钟后有大雪，出门记得带伞。", run(minutely(al, "snow", 0, 0.5), null, al).line);
        eq("⑦ 逐小时下午有雪", "今天下午可能有雪，出门记得带伞。", run(dry, hourlyV7(7, 400, 15), al).line);
    }

    static void wording() {
        eq("round 1ms → 5", 5, RainLine.roundMin(1));
        eq("round 5:00 → 5", 5, RainLine.roundMin(300_000));
        eq("round 5:01 → 10", 10, RainLine.roundMin(301_000));
        eq("round 23min → 25", 25, RainLine.roundMin(23 * 60_000));
        eq("round 115min → 115", 115, RainLine.roundMin(115 * 60_000));
        eq("强度 0.2→小雨", "小雨", RainLine.intensity(0.2, false));
        eq("强度 0.21→中雨", "中雨", RainLine.intensity(0.21, false));
        eq("强度 0.67→大雨", "大雨", RainLine.intensity(0.67, false));
        eq("强度 0.08 雪→小雪", "小雪", RainLine.intensity(0.08, true));
        String[] want = {"凌晨", "上午", "上午", "中午", "中午", "下午", "下午", "傍晚", "傍晚", "晚上", "晚上"};
        int[] hs = {5, 6, 10, 11, 12, 13, 16, 17, 18, 19, 23};
        boolean all = true;
        for (int i = 0; i < hs.length; i++) if (!want[i].equals(RainLine.partOfDay(hs[i]))) { all = false; System.out.println("  partOfDay " + hs[i] + " → " + RainLine.partOfDay(hs[i])); }
        ok("时段边界 凌晨/上午/中午/下午/傍晚/晚上", all);
        eq("locParam 两位小数 lon,lat", "116.41,39.92", RainLine.locParam(39.919, 116.406));
        // 调试注入平移
        List<RainLine.Pt> p = RainLine.rebase(RainLine.parseMinutely(minutely(RainLine.parseTime("2021-12-16T18:55+08:00"), "rain", 0.2)), t(7, 3));
        ok("注入：分钟级平移到闹钟（向下取整到 5 分钟）", p.get(0).at == t(7, 0) && p.get(1).at == t(7, 5));
        eq("注入：平移后判定=正在下", "NOW", RainLine.decide(p, null, t(7, 3), SH).kind);
        List<RainLine.Hr> h = RainLine.parseHourly(hourlyV7(7, 305, 14));
        List<RainLine.Hr> moved = new ArrayList<RainLine.Hr>();
        for (RainLine.Hr x : h) moved.add(new RainLine.Hr(x.at - 400L * 86400_000L, x.wet, x.snow));
        List<RainLine.Hr> back = RainLine.shiftToDay(moved, t(7, 0), SH);
        ok("注入：逐小时按整天平移回今天、钟点不变", back.get(0).at == t(7, 0) && back.get(7).wet);
    }

    static void template() {
        Template.Wx w = new Template.Wx();
        w.ok = true; w.temp = 20; w.tmin = 18; w.tmax = 25; w.key = "duoyun"; w.rainProb = 80;
        Template.Opts o = new Template.Opts();
        o.rainLine = "今天下午可能有雨，出门记得带伞。";
        o.umbrella = true;
        List<String> news = Arrays.asList("标题一", "标题二", "标题三");
        List<Template.Piece> ps = Template.pieces(7, 0, w, news, "tencent", o);
        int iWx = -1, iRain = -1, iLead = -1, iUmb = -1;
        for (int i = 0; i < ps.size(); i++) {
            Template.Piece x = ps.get(i);
            if (x.isBank() && x.bank.startsWith("wx_")) iWx = i;
            if (!x.isGap() && o.rainLine.equals(x.text)) iRain = i;
            if (x.isBank() && x.bank.startsWith("lead_")) iLead = i;
            if (x.isBank() && x.bank.equals("x_umbrella")) iUmb = i;
        }
        ok("模板：降雨句在天气句之后、新闻引导之前", iWx >= 0 && iRain > iWx && iLead > iRain);
        ok("模板：降雨句是云端变量件（非件库）", iRain >= 0 && !ps.get(iRain).isBank() && "extra".equals(ps.get(iRain).section));
        ok("模板：有降雨句时不再说旧的概率带伞句", iUmb < 0);
        String txt = Template.text(ps);
        ok("模板：整稿文本 天气句后紧跟降雨句（同一行）", txt.contains("天气。今天下午可能有雨，出门记得带伞。"));
        ok("模板：结束语不变", txt.endsWith("目前就是这样了，一天好心情。"));
        o.rainLine = null;
        boolean umb = false;
        for (Template.Piece x : Template.pieces(7, 0, w, news, "tencent", o)) if (x.isBank() && "x_umbrella".equals(x.bank)) umb = true;
        ok("模板：没有降雨句时旧的带伞开关照旧", umb);
        Template.Opts none = new Template.Opts();
        List<Template.Piece> a = Template.pieces(7, 0, w, news, "tencent", none);
        none.rainLine = "";
        ok("模板：空降雨句 = 不说（件序列不变）", a.toString().equals(Template.pieces(7, 0, w, news, "tencent", none).toString()));
        o.rainLine = "现在正在下雨，出门记得带伞。";
        ok("模板：没有新闻也照说降雨句", Template.text(Template.pieces(7, 0, w, null, null, o)).contains("现在正在下雨"));
    }

    static void config() {
        ok("flag 播报降雨提醒 默认开", Flags.X_RAIN_LINE_DEF && Flags.boolDefault(Flags.X_RAIN_LINE));
        ok("flag 降雨提醒在 ALL_BOOL（DUMP 可见）", Arrays.asList(Flags.ALL_BOOL).contains(Flags.X_RAIN_LINE));
        ok("flag 降雨提醒已实现", !Flags.notImplemented(Flags.X_RAIN_LINE));
        eq("没 key → 写原因", "未配置和风天气 API Key，不播报降雨提醒", RainLine.unavailableReason(true, false, true));
        eq("没 host → 写原因", "未配置和风天气 API Host，不播报降雨提醒", RainLine.unavailableReason(true, true, false));
        eq("开关关 → 已关闭", "已关闭", RainLine.unavailableReason(false, true, true));
        eq("齐了 → 可用", null, RainLine.unavailableReason(true, true, true));
        eq("host 去 https:// 和路径", "abc123.re.qweatherapi.com", RainLine.cleanHost(" https://ABC123.re.qweatherapi.com/v7/ "));
        eq("host 不像域名 → 空", "", RainLine.cleanHost("not a host"));
        eq("host null → 空", "", RainLine.cleanHost(null));
        BriefMeta m = new BriefMeta();
        m.rainLine = "大约25分钟后有小雨，出门记得带伞。";
        eq("BriefMeta rainLine 往返", m.rainLine, BriefMeta.fromJson(m.toJson()).rainLine);
        eq("BriefMeta 旧成品没有 rainLine → 空", "", BriefMeta.fromJson("{\"forAlarmAt\":1}").rainLine);
    }
}
