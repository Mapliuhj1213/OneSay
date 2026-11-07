package com.onesay.assistant.v2.conn.google;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * [conn/google] 时间换算（纯函数，时区显式传入，JVM 可测）。
 * 模型给的时间可能是 RFC 3339（带偏移）、{@code yyyy-MM-dd HH:mm}（按手机时区理解）或 {@code yyyy-MM-dd}（全天）。
 */
public final class GTime {

    private GTime() {}

    /** 解析结果：要么是带偏移的时刻（dateTime），要么是全天日期（date）。 */
    public static final class When {
        public final String dateTime;
        public final String date;
        public final long millis;

        When(String dateTime, String date, long millis) {
            this.dateTime = dateTime;
            this.date = date;
            this.millis = millis;
        }

        public boolean allDay() {
            return date != null;
        }
    }

    /** @return null = 看不懂 */
    public static When parse(String s, TimeZone tz) {
        if (s == null) return null;
        String v = s.trim().replace('/', '-');
        if (v.length() == 0) return null;
        try {
            if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
                Date d = fmt("yyyy-MM-dd", tz).parse(v);
                return new When(null, v, d.getTime());
            }
            if (v.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{1,2}:\\d{2}(:\\d{2})?")) {
                String norm = v.replace('T', ' ');
                if (norm.length() == 16 || norm.matches(".* \\d:\\d{2}")) norm = norm + ":00";
                if (norm.matches("\\d{4}-\\d{2}-\\d{2} \\d:\\d{2}:\\d{2}")) norm = norm.substring(0, 11) + "0" + norm.substring(11);
                Date d = fmt("yyyy-MM-dd HH:mm:ss", tz).parse(norm);
                return new When(rfc3339(d.getTime(), tz), null, d.getTime());
            }
            if (v.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})")) {
                long ms = parseRfc3339(v);
                return new When(rfc3339(ms, tz), null, ms);
            }
        } catch (ParseException e) {
            return null;
        }
        return null;
    }

    static long parseRfc3339(String v) throws ParseException {
        String s = v;
        int dot = s.indexOf('.');
        if (dot > 0) {
            int end = dot + 1;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            s = s.substring(0, dot) + s.substring(end);
        }
        if (s.endsWith("Z")) s = s.substring(0, s.length() - 1) + "+0000";
        else {
            int p = Math.max(s.lastIndexOf('+'), s.lastIndexOf('-'));
            if (p > 10 && s.charAt(p + 3) == ':') s = s.substring(0, p + 3) + s.substring(p + 4);
        }
        if (s.matches(".*T\\d{2}:\\d{2}[+-]\\d{4}")) s = s.substring(0, 16) + ":00" + s.substring(16);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        return f.parse(s).getTime();
    }

    public static String rfc3339(long ms, TimeZone tz) {
        SimpleDateFormat f = fmt("yyyy-MM-dd'T'HH:mm:ss", tz);
        int off = tz.getOffset(ms) / 60000;
        String sign = off < 0 ? "-" : "+";
        off = Math.abs(off);
        return f.format(new Date(ms)) + sign + String.format(Locale.US, "%02d:%02d", off / 60, off % 60);
    }

    /** 某天 0 点（tz）。 */
    public static long startOfDay(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Tasks API 的 due 只认日期：RFC 3339 的当天 00:00Z。 */
    public static String taskDue(When w, TimeZone tz) {
        String day = w.allDay() ? w.date : fmt("yyyy-MM-dd", tz).format(new Date(w.millis));
        return day + "T00:00:00.000Z";
    }

    /** 给人看的短时间：MM-dd HH:mm / MM-dd（全天）。 */
    public static String human(String dateTime, String date, TimeZone tz) {
        try {
            if (dateTime != null && dateTime.length() > 0) return fmt("MM-dd HH:mm", tz).format(new Date(parseRfc3339(dateTime)));
        } catch (Exception ignored) {
        }
        if (date != null && date.length() >= 10) return date.substring(5, 10) + "（全天）";
        return "";
    }

    static SimpleDateFormat fmt(String p, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
        f.setTimeZone(tz);
        f.setLenient(false);
        return f;
    }
}
