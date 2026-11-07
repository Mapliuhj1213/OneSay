package com.onesay.assistant.v2.rain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [rain] 和风天气分钟级降水（/v7/minutely/5m）的解析结果（纯 Java，JVM 全测）。
 *
 * <p>响应：{@code code}（"200" 才算成功）、{@code summary}（「间歇性降雨还将持续90分钟」这类一句话）、
 * {@code minutely[]}（24 个点、每 5 分钟一个、共 2 小时：{@code fxTime} ISO 时间带时区、{@code precip} 这 5 分钟累计毫米、
 * {@code type} rain / snow）。只覆盖中国；国外坐标返回 HTTP 400「Data Not Available」（{@link #parseError}）。
 */
public final class RainForecast {

    public static final class Point {
        /** 这 5 分钟的起点（epoch ms）。 */
        public final long at;
        /** 这 5 分钟累计降水（mm）。 */
        public final double precip;
        public final boolean snow;

        public Point(long at, double precip, boolean snow) {
            this.at = at;
            this.precip = precip < 0 ? 0 : precip;
            this.snow = snow;
        }

        /** 阈值：precip &gt; 0 视为有雨 / 雪（用户定）。 */
        public boolean wet() {
            return precip > 0;
        }
    }

    public final String summary;
    /** 数据更新时间（epoch ms；没有 = 0）。 */
    public final long updateAt;
    public final List<Point> points;

    public RainForecast(String summary, long updateAt, List<Point> points) {
        this.summary = summary == null ? "" : summary.trim();
        this.updateAt = updateAt;
        List<Point> p = new ArrayList<Point>(points == null ? Collections.<Point>emptyList() : points);
        Collections.sort(p, new java.util.Comparator<Point>() {
            @Override public int compare(Point a, Point b) {
                return a.at < b.at ? -1 : (a.at == b.at ? 0 : 1);
            }
        });
        this.points = Collections.unmodifiableList(p);
    }

    /** 解析失败（code 不是 200 / 没有点 / 不是 JSON）。message 只描述原因，不带地址和 key。 */
    public static final class BadResponse extends Exception {
        public final boolean noCoverage;

        public BadResponse(String why, boolean noCoverage) {
            super(why);
            this.noCoverage = noCoverage;
        }
    }

    /** 解析 200 响应体。 */
    public static RainForecast parse(String json) throws BadResponse {
        JSONObject o;
        try {
            o = new JSONObject(json == null ? "" : json.trim());
        } catch (Exception e) {
            throw new BadResponse("不是 JSON", false);
        }
        String code = o.optString("code", "");
        if (!"200".equals(code)) {
            throw new BadResponse("code=" + (code.length() == 0 ? "?" : code), "204".equals(code) || "404".equals(code));
        }
        JSONArray arr = o.optJSONArray("minutely");
        if (arr == null || arr.length() == 0) throw new BadResponse("没有分钟数据", false);
        List<Point> pts = new ArrayList<Point>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            long at = parseTime(m.optString("fxTime", ""));
            if (at <= 0) continue;
            double p;
            try {
                p = Double.parseDouble(m.optString("precip", "0").trim());
            } catch (NumberFormatException e) {
                p = 0;
            }
            pts.add(new Point(at, p, "snow".equalsIgnoreCase(m.optString("type", "rain").trim())));
        }
        if (pts.isEmpty()) throw new BadResponse("分钟数据时间看不懂", false);
        return new RainForecast(o.optString("summary", ""), parseTime(o.optString("updateTime", "")), pts);
    }

    /**
     * 非 200 的响应（HTTP 4xx 也带 JSON）：国外 / 无覆盖 = 400 + error.title「Data Not Available」或 type 含 data-not-available。
     * 返回给人看的原因（不带地址 / key）。
     */
    public static BadResponse parseError(int http, String body) {
        String b = body == null ? "" : body;
        boolean noCov = b.contains("data-not-available") || b.contains("Data Not Available") || b.contains("\"code\":\"204\"");
        if (http == 401 || http == 403) return new BadResponse("key 无效或没开通（HTTP " + http + "）", false);
        if (http == 429) return new BadResponse("请求太多（HTTP 429）", false);
        return new BadResponse(noCov ? "这个地方没有分钟级降水数据（只覆盖中国）" : "HTTP " + http, noCov);
    }

    static final Pattern ISO = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?(Z|([+-])(\\d{2}):?(\\d{2}))?");

    /** 「2026-11-06T19:25+08:00」→ epoch ms；看不懂 → 0。没带时区按 +08:00（和风只覆盖中国）。 */
    public static long parseTime(String s) {
        if (s == null) return 0;
        Matcher m = ISO.matcher(s.trim());
        if (!m.matches()) return 0;
        int offMin = 8 * 60;
        if (m.group(7) != null) {
            if ("Z".equals(m.group(7))) offMin = 0;
            else {
                offMin = Integer.parseInt(m.group(9)) * 60 + Integer.parseInt(m.group(10));
                if ("-".equals(m.group(8))) offMin = -offMin;
            }
        }
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
        return c.getTimeInMillis() - offMin * 60000L;
    }
}
