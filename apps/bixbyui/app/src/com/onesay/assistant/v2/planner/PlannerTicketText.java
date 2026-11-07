package com.onesay.assistant.v2.planner;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从短信 / 订单页文字里认票（纯 Java）：火车（12306 短信、订单页）、航班、电影、演出。认不出返回 null，绝不猜。
 * 结果是一行 tickets 表数据（没有 _id）。这是计划 App core/TicketText.java 的副本（PlannerContractTest 比对两边正则一致），用于「把这张票存起来」。
 */
public final class PlannerTicketText {
    private PlannerTicketText() {}

    static final Pattern TRAIN_NO = Pattern.compile("(?<![A-Za-z0-9])([GDCZTKYLS]\\d{1,4})(?:次|列车|\\s|，|,|$)");
    static final Pattern FLIGHT_NO = Pattern.compile("(?<![A-Za-z0-9])((?:[A-Z]{2}|[A-Z]\\d|\\d[A-Z])\\d{3,4})(?![0-9])");
    static final Pattern DATE_MD = Pattern.compile("(?:(20\\d{2})[年\\-/.])?(\\d{1,2})[月\\-/.](\\d{1,2})日?");
    static final Pattern TIME = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?!\\d)");
    static final Pattern TRAIN_TIME = Pattern.compile("([01]?\\d|2[0-3])[:：]([0-5]\\d)\\s*(?:开|发车|出发)");
    static final Pattern SEAT_TRAIN = Pattern.compile("(\\d{1,2}车(?:厢)?\\s*\\d{1,3}[A-F]?(?:号|座)?(?:[上中下]铺)?)");
    static final Pattern SEAT_GENERIC = Pattern.compile("(?:座位|座位号|座)[:：]?\\s*([0-9A-Za-z排号座\\-]{2,12})");
    static final Pattern GATE_TRAIN = Pattern.compile("检票口[:：]?\\s*([0-9A-Za-z\\-]{1,6}(?:号)?)");
    static final Pattern GATE_FLIGHT = Pattern.compile("登机口[:：]?\\s*([0-9A-Za-z\\-]{1,6})");
    static final Pattern ORDER = Pattern.compile("(?:订单号|订单|取票号|取票码)[:：]?\\s*([A-Za-z0-9]{5,20})");
    static final Pattern ROUTE = Pattern.compile("([\\u4e00-\\u9fa5]{2,6}?)(?:站)?\\s*(?:-|—|→|至|到|开往)\\s*([\\u4e00-\\u9fa5]{2,6}?)(?:站|\\s|，|,|$)");
    static final Pattern STATION_FROM = Pattern.compile("([\\u4e00-\\u9fa5]{2,6}站)");
    static final Pattern CINEMA = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9]{2,16}(?:影城|影院|电影院))");
    static final Pattern HALL = Pattern.compile("(\\d{1,2}\\s?号厅|[A-Z0-9]{1,3}厅|IMAX厅|激光厅)");
    /** 2026-11-05 票根边界测试：选座 / 待支付页不是票（没付钱、没出票），认出来也不建。 */
    static final Pattern UNPAID = Pattern.compile("待支付|去支付|确认支付|立即支付|完成支付|锁座|未支付|支付剩余");
    static final Pattern PAID = Pattern.compile("支付成功|购票成功|出票成功|已出票|已支付|交易成功|取票码");
    /** 取票码优先于订单号；允许中间带空格（「TEST 8888 0001」），存时去掉空格。 */
    static final Pattern PICKUP_CODE = Pattern.compile("(?:取票码|取票号|兑换码)[:：]?\\s*([A-Za-z0-9](?:[A-Za-z0-9]|\\s(?=[A-Za-z0-9])){4,24})");
    static final Pattern MOVIE = Pattern.compile("《([^》]{1,30})》");
    static final Pattern VENUE = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9]{2,16}(?:体育馆|体育场|剧院|剧场|大剧院|音乐厅|livehouse|LiveHouse|艺术中心|中心))");

    public static PRow parse(String text, long now, TimeZone tz) {
        if (text == null) return null;
        String t = text.replace('\u00a0', ' ');
        boolean trainCtx = t.contains("12306") || t.contains("车次") || t.contains("检票") || t.contains("铁路") || TRAIN_TIME.matcher(t).find()
                || SEAT_TRAIN.matcher(t).find();
        boolean flightCtx = t.contains("航班") || t.contains("登机") || t.contains("起飞") || t.contains("值机");
        boolean movieCtx = CINEMA.matcher(t).find() || t.contains("取票码") && t.contains("影");
        boolean showCtx = VENUE.matcher(t).find() && (t.contains("演出") || t.contains("演唱会") || t.contains("话剧") || t.contains("音乐会") || t.contains("票"));
        if (UNPAID.matcher(t).find() && !PAID.matcher(t).find()) return null;
        PRow r = new PRow().with("source", "parse");
        if (trainCtx && !flightCtx) {
            Matcher m = TRAIN_NO.matcher(t);
            if (!m.find()) return null;
            r.with("kind", "train").with("number", m.group(1));
            Matcher tm = TRAIN_TIME.matcher(t);
            long at = when(t, tm.find() ? tm : null, now, tz);
            if (at <= 0) return null;
            r.with("depart_at", at);
            route(t, r);
            Matcher s = SEAT_TRAIN.matcher(t);
            if (s.find()) r.with("seat", s.group(1).replaceAll("\\s+", ""));
            Matcher g = GATE_TRAIN.matcher(t);
            if (g.find()) r.with("gate", g.group(1));
            order(t, r);
            r.with("title", "火车 " + r.s("number") + (r.s("from_place").length() > 0 ? " " + r.s("from_place") + "→" + r.s("to_place") : ""));
            return r;
        }
        if (flightCtx) {
            Matcher m = FLIGHT_NO.matcher(t);
            if (!m.find()) return null;
            r.with("kind", "flight").with("number", m.group(1));
            long at = when(t, null, now, tz);
            if (at <= 0) return null;
            r.with("depart_at", at);
            route(t, r);
            Matcher g = GATE_FLIGHT.matcher(t);
            if (g.find()) r.with("gate", g.group(1));
            Matcher s = SEAT_GENERIC.matcher(t);
            if (s.find()) r.with("seat", s.group(1));
            order(t, r);
            r.with("title", "航班 " + r.s("number") + (r.s("from_place").length() > 0 ? " " + r.s("from_place") + "→" + r.s("to_place") : ""));
            return r;
        }
        if (movieCtx) {
            long at = when(t, null, now, tz);
            if (at <= 0) return null;
            Matcher c = CINEMA.matcher(t);
            c.find();
            Matcher mv = MOVIE.matcher(t);
            String name = mv.find() ? mv.group(1) : "电影";
            Matcher h = HALL.matcher(t);
            r.with("kind", "movie").with("depart_at", at).with("venue", c.group(1) + (h.find() ? " " + h.group(1).replace(" ", "") : "")).with("title", name);
            Matcher s = SEAT_GENERIC.matcher(t);
            if (s.find()) r.with("seat", s.group(1));
            Matcher seatRow = Pattern.compile("(\\d{1,2}排\\d{1,2}(?:号|座))").matcher(t);
            if (seatRow.find()) r.with("seat", seatRow.group(1));
            order(t, r);
            return r;
        }
        if (showCtx) {
            long at = when(t, null, now, tz);
            if (at <= 0) return null;
            Matcher v = VENUE.matcher(t);
            v.find();
            Matcher mv = MOVIE.matcher(t);
            r.with("kind", "show").with("depart_at", at).with("venue", v.group(1)).with("title", mv.find() ? mv.group(1) : "演出");
            Matcher seatRow = Pattern.compile("([0-9A-Za-z]{1,3}区\\s*\\d{1,3}排\\s*\\d{1,3}(?:号|座))").matcher(t);
            if (seatRow.find()) r.with("seat", seatRow.group(1).replaceAll("\\s+", ""));
            order(t, r);
            return r;
        }
        return null;
    }

    static void route(String t, PRow r) {
        Matcher m = ROUTE.matcher(t);
        while (m.find()) {
            String a = m.group(1), b = m.group(2);
            if (a.endsWith("站")) a = a.substring(0, a.length() - 1);
            if (a.contains("订单") || a.contains("车次") || b.contains("订单")) continue;
            r.with("from_place", a).with("to_place", b);
            return;
        }
        Matcher s = STATION_FROM.matcher(t);
        if (s.find()) r.with("from_place", s.group(1).substring(0, s.group(1).length() - 1));
    }

    static void order(String t, PRow r) {
        Matcher pc = PICKUP_CODE.matcher(t);
        if (pc.find()) {
            r.with("order_no", pc.group(1).replaceAll("\\s+", ""));
            return;
        }
        Matcher o = ORDER.matcher(t);
        if (o.find()) r.with("order_no", o.group(1));
    }

    /** 日期 + 时刻 → 毫秒。没有日期：时刻还没过就是今天，过了就是明天。日期比现在早 30 天以上 → 当成明年。 */
    static long when(String t, Matcher timeHint, long now, TimeZone tz) {
        int hh, mm;
        if (timeHint != null) {
            hh = Integer.parseInt(timeHint.group(1));
            mm = Integer.parseInt(timeHint.group(2));
        } else {
            Matcher m = TIME.matcher(t);
            if (!m.find()) return -1;
            hh = Integer.parseInt(m.group(1));
            mm = Integer.parseInt(m.group(2));
        }
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(now);
        Matcher d = DATE_MD.matcher(t);
        boolean hasDate = false;
        while (d.find()) {
            int mo = Integer.parseInt(d.group(2)), da = Integer.parseInt(d.group(3));
            if (mo < 1 || mo > 12 || da < 1 || da > 31) continue;
            if (d.group(1) != null) k.set(Calendar.YEAR, Integer.parseInt(d.group(1)));
            k.set(Calendar.MONTH, mo - 1);
            k.set(Calendar.DAY_OF_MONTH, da);
            hasDate = true;
            if (d.group(1) == null) {
                k.set(Calendar.HOUR_OF_DAY, hh);
                k.set(Calendar.MINUTE, mm);
                if (k.getTimeInMillis() < now - 30L * 86400000L) k.add(Calendar.YEAR, 1);
            }
            break;
        }
        k.set(Calendar.HOUR_OF_DAY, hh);
        k.set(Calendar.MINUTE, mm);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        if (!hasDate && k.getTimeInMillis() < now) k.add(Calendar.DAY_OF_MONTH, 1);
        return k.getTimeInMillis();
    }
}
