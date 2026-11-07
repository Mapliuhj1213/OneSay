package com.onesay.assistant.v2.vision.screenref;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [screenref] 中文日期/时间片段 → 绝对时刻（纯 Java，JVM 可测）。
 *
 * <p>支持：
 * <ul>
 *   <li>日期：{@code 2026-10-09 / 2026/10/9 / 2026.10.09 / 2026年10月9日 / 10月9日(号) / 今天 明天 后天 大后天 今晚 明晚 明早
 *       / 周五 星期三 礼拜天 下周一 下下周二 本周五 这周日}</li>
 *   <li>钟点：{@code 14:30 / 下午3点 / 3点半 / 晚上八点一刻 / 9点15分 / 3:30pm / 中午12点}</li>
 *   <li>区间：{@code 14:00-16:00 / 下午2点到4点 / 9:00~11:30}</li>
 * </ul>
 * 只有钟点没有日期时默认"今天"，置信度低（聊天列表里的时间戳到处都是）。
 */
public final class DateTimeNormalizer {

    private DateTimeNormalizer() {}

    /** 一个被识别出的时间片段。 */
    public static final class Span {
        public final int start, end;
        public final String raw;
        public final long beginMs;
        public final long endMs;
        public final boolean hasDate;
        public final boolean hasTime;

        Span(int start, int end, String raw, long beginMs, long endMs, boolean hasDate, boolean hasTime) {
            this.start = start;
            this.end = end;
            this.raw = raw;
            this.beginMs = beginMs;
            this.endMs = endMs;
            this.hasDate = hasDate;
            this.hasTime = hasTime;
        }

        public boolean allDay() {
            return hasDate && !hasTime;
        }

        /** 规则置信度：日期+钟点 0.9，日期 0.65，仅钟点 0.3。 */
        public double confidence() {
            if (hasDate && hasTime) return 0.9;
            if (hasDate) return 0.65;
            return 0.3;
        }
    }

    private static final String CN = "[零〇一二两三四五六七八九十]";
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})(?:\\s*[日号]|(?!\\d))"     // 1,2,3
            + "|(?<!\\d)(\\d{1,2})\\s*月\\s*(\\d{1,2})(?:\\s*[日号]|(?!\\d))"                               // 4,5
            + "|(大后天|今天|明天|后天|今晚|明晚|明早|今早|今日|明日)"                                         // 6
            + "|((?:下下|下个|下|这个|这|本)?(?:周|星期|礼拜)([一二三四五六日天1-7]))");                     // 7,8
    private static final String PERIOD = "(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜间|晚)?";
    private static final Pattern TIME = Pattern.compile(
            PERIOD + "\\s*(?:(?<![\\d:：.])(\\d{1,2})\\s*[:：]\\s*(\\d{2})(?![\\d:：])"                    // 1 period, 2,3
            + "|(?<![\\d.])(\\d{1,2}|" + CN + "{1,3})\\s*点(?:钟)?(?:(半)|(一刻)|(三刻)|(\\d{1,2})\\s*分?|(" + CN + "{1,3})分?)?)" // 4..9
            + "(?:\\s*([AaPp][Mm]))?");                                                                   // 10
    private static final Pattern RANGE_GAP = Pattern.compile("^\\s*(?:-|~|～|—|–|至|到)\\s*$");
    private static final Pattern JOIN_GAP = Pattern.compile("^[\\s,，的]{0,3}$");

    private static final class Tok {
        int start, end;
        // date
        int y = -1, mo = -1, d = -1;
        String rel;     // 今天/明天…
        String weekPrefix;
        int weekday = -1;
        boolean evening;   // 今晚/明晚
        // time
        int h = -1, mi = 0;
        String period;
        String ampm;
        boolean weak;   // 仅"N点"：无时段、无分钟
    }

    /** 在一行文字里找出所有时间片段（按出现顺序）。 */
    public static List<Span> findAll(String line, long nowMs, TimeZone tz) {
        List<Span> out = new ArrayList<Span>();
        if (line == null || line.length() == 0) return out;
        List<Tok> dates = new ArrayList<Tok>();
        Matcher dm = DATE.matcher(line);
        while (dm.find()) {
            Tok t = new Tok();
            t.start = dm.start();
            t.end = dm.end();
            if (dm.group(1) != null) {
                t.y = parseInt(dm.group(1));
                t.mo = parseInt(dm.group(2));
                t.d = parseInt(dm.group(3));
            } else if (dm.group(4) != null) {
                t.mo = parseInt(dm.group(4));
                t.d = parseInt(dm.group(5));
            } else if (dm.group(6) != null) {
                t.rel = dm.group(6);
                t.evening = t.rel.contains("晚");
            } else if (dm.group(7) != null) {
                String w = dm.group(7);
                String wd = dm.group(8);
                int idx = Math.max(w.indexOf("周"), Math.max(w.indexOf("星期"), w.indexOf("礼拜")));
                t.weekPrefix = idx > 0 ? w.substring(0, idx) : "";
                t.weekday = weekdayOf(wd);
            }
            if (t.mo != -1 && (t.mo < 1 || t.mo > 12 || t.d < 1 || t.d > 31)) continue;
            dates.add(t);
        }
        List<Tok> times = new ArrayList<Tok>();
        Matcher tm = TIME.matcher(line);
        while (tm.find()) {
            if (tm.end() == tm.start()) continue;
            Tok t = buildTime(tm);
            if (t == null) continue;
            // 时间 token 不能落在日期 token 里面
            Tok overlapped = null;
            for (Tok d : dates) {
                if (t.start < d.end && t.end > d.start) { overlapped = d; break; }
            }
            if (overlapped != null) {
                // 典型："明晚8点" —— TIME 的时段组吞了"晚"。从日期末尾重新试一次。
                if (t.end > overlapped.end) {
                    Matcher m2 = TIME.matcher(line);
                    m2.region(overlapped.end, line.length());
                    if (m2.lookingAt() && m2.end() > m2.start()) {
                        Tok t2 = buildTime(m2);
                        if (t2 != null) times.add(t2);
                    }
                }
                continue;
            }
            times.add(t);
        }

        boolean[] usedTime = new boolean[times.size()];
        for (Tok d : dates) {
            Tok tStart = null, tEnd = null;
            int ti = -1;
            for (int i = 0; i < times.size(); i++) {
                if (usedTime[i]) continue;
                Tok t = times.get(i);
                if (t.start >= d.end && JOIN_GAP.matcher(line.substring(d.end, t.start)).matches()) {
                    tStart = t;
                    ti = i;
                    break;
                }
            }
            int end = d.end;
            if (tStart != null) {
                usedTime[ti] = true;
                end = tStart.end;
                if (ti + 1 < times.size() && !usedTime[ti + 1]) {
                    Tok t2 = times.get(ti + 1);
                    if (t2.start >= tStart.end && RANGE_GAP.matcher(line.substring(tStart.end, t2.start)).matches()) {
                        tEnd = t2;
                        usedTime[ti + 1] = true;
                        end = t2.end;
                    }
                }
            }
            Calendar day = resolveDate(d, nowMs, tz);
            if (day == null) continue;
            long begin, endMs = 0;
            boolean hasTime = tStart != null;
            if (hasTime) {
                begin = at(day, hourOf(tStart, d.evening), tStart.mi);
                if (tEnd != null) {
                    String p = tEnd.period != null ? tEnd.period : tStart.period;
                    Tok e2 = tEnd;
                    if (e2.period == null) e2.period = p;
                    endMs = at(day, hourOf(e2, d.evening), e2.mi);
                    if (endMs <= begin) endMs += 12L * 3600 * 1000;   // "下午2点到4点"
                }
            } else {
                begin = at(day, 0, 0);
            }
            out.add(new Span(d.start, end, line.substring(d.start, end), begin, endMs, true, hasTime));
        }
        // 剩下孤立的钟点（默认今天）
        for (int i = 0; i < times.size(); i++) {
            if (usedTime[i]) continue;
            Tok t = times.get(i);
            // 孤立的"N点"（无分钟、无时段）太容易误伤（"3点建议"…），只收 hh:mm 或带时段的
            if (t.weak) continue;
            int end = t.end;
            Tok tEnd = null;
            if (i + 1 < times.size() && !usedTime[i + 1]) {
                Tok t2 = times.get(i + 1);
                if (RANGE_GAP.matcher(line.substring(t.end, t2.start)).matches()) {
                    tEnd = t2;
                    usedTime[i + 1] = true;
                    end = t2.end;
                }
            }
            Calendar day = Calendar.getInstance(tz);
            day.setTimeInMillis(nowMs);
            long begin = at(day, hourOf(t, false), t.mi);
            long endMs = 0;
            if (tEnd != null) {
                if (tEnd.period == null) tEnd.period = t.period;
                endMs = at(day, hourOf(tEnd, false), tEnd.mi);
                if (endMs <= begin) endMs += 12L * 3600 * 1000;
            }
            out.add(new Span(t.start, end, line.substring(t.start, end), begin, endMs, false, true));
        }
        // 按出现顺序
        java.util.Collections.sort(out, new java.util.Comparator<Span>() {
            @Override
            public int compare(Span a, Span b) {
                return a.start - b.start;
            }
        });
        return out;
    }

    private static Tok buildTime(Matcher tm) {
        Tok t = new Tok();
        t.start = tm.start();
        t.end = tm.end();
        t.period = tm.group(1);
        t.ampm = tm.group(10);
        if (tm.group(2) != null) {
            t.h = parseInt(tm.group(2));
            t.mi = parseInt(tm.group(3));
        } else if (tm.group(4) != null) {
            t.h = cnOrInt(tm.group(4));
            if (tm.group(5) != null) t.mi = 30;
            else if (tm.group(6) != null) t.mi = 15;
            else if (tm.group(7) != null) t.mi = 45;
            else if (tm.group(8) != null) t.mi = parseInt(tm.group(8));
            else if (tm.group(9) != null) t.mi = cnOrInt(tm.group(9));
            else t.weak = t.period == null && t.ampm == null;
        } else {
            return null;
        }
        if (t.h < 0 || t.h > 24 || t.mi < 0 || t.mi > 59) return null;
        return t;
    }

    private static int hourOf(Tok t, boolean eveningHint) {
        int h = t.h;
        String p = t.period;
        if (t.ampm != null) {
            boolean pm = t.ampm.toLowerCase().startsWith("p");
            if (pm && h < 12) h += 12;
            if (!pm && h == 12) h = 0;
            return h;
        }
        if (p != null) {
            if ((p.equals("下午") || p.equals("傍晚") || p.equals("晚上") || p.equals("晚") || p.equals("夜里") || p.equals("夜间")) && h < 12) h += 12;
            else if (p.equals("中午") && h < 6) h += 12;
        } else if (eveningHint && h < 12) {
            h += 12;
        }
        if (h == 24) h = 0;
        return h;
    }

    private static Calendar resolveDate(Tok d, long nowMs, TimeZone tz) {
        Calendar now = Calendar.getInstance(tz);
        now.setTimeInMillis(nowMs);
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(nowMs);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (d.mo != -1) {
            int y = d.y != -1 ? d.y : now.get(Calendar.YEAR);
            c.set(Calendar.YEAR, y);
            c.set(Calendar.MONTH, d.mo - 1);
            c.set(Calendar.DAY_OF_MONTH, d.d);
            if (d.y == -1) {
                // 未写年份：若比今天早半年以上，视为明年（"1月3日"在 12 月出现）
                long diffDays = (c.getTimeInMillis() - nowMs) / 86400000L;
                if (diffDays < -180) c.add(Calendar.YEAR, 1);
            }
            return c;
        }
        if (d.rel != null) {
            String r = d.rel;
            int add = 0;
            if (r.startsWith("明")) add = 1;
            else if (r.equals("后天")) add = 2;
            else if (r.equals("大后天")) add = 3;
            c.add(Calendar.DAY_OF_MONTH, add);
            return c;
        }
        if (d.weekday != -1) {
            int today = isoWeekday(now.get(Calendar.DAY_OF_WEEK));
            int target = d.weekday;
            String pre = d.weekPrefix == null ? "" : d.weekPrefix;
            int delta;
            if (pre.startsWith("下下")) {
                delta = (7 - today) + 7 + target;
            } else if (pre.startsWith("下")) {
                delta = (7 - today) + target;
            } else if (pre.startsWith("这") || pre.startsWith("本")) {
                delta = target - today;
            } else {
                delta = target - today;
                if (delta < 0) delta += 7;
            }
            c.add(Calendar.DAY_OF_MONTH, delta);
            return c;
        }
        return null;
    }

    private static long at(Calendar day, int h, int mi) {
        Calendar c = (Calendar) day.clone();
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, mi);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Calendar.DAY_OF_WEEK → 1(周一)..7(周日) */
    private static int isoWeekday(int calDow) {
        return calDow == Calendar.SUNDAY ? 7 : calDow - 1;
    }

    private static int weekdayOf(String s) {
        if (s == null) return -1;
        String m = "一二三四五六";
        int i = m.indexOf(s);
        if (i >= 0) return i + 1;
        if ("日".equals(s) || "天".equals(s) || "7".equals(s)) return 7;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 阿拉伯数字或 ≤99 的中文数字。 */
    static int cnOrInt(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.matches("\\d+")) return parseInt(s);
        String digits = "零一二三四五六七八九";
        s = s.replace('两', '二').replace('〇', '零');
        int ten = s.indexOf('十');
        if (ten < 0) {
            if (s.length() != 1) return -1;
            return digits.indexOf(s.charAt(0));
        }
        int hi = ten == 0 ? 1 : digits.indexOf(s.charAt(0));
        int lo = ten == s.length() - 1 ? 0 : digits.indexOf(s.charAt(ten + 1));
        if (hi < 0 || lo < 0) return -1;
        return hi * 10 + lo;
    }
}
