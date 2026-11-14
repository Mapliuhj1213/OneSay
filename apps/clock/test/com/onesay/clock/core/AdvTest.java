package com.onesay.clock.core;

import java.util.*;

/**
 * 找茬式对抗测试 · 时钟核心（纯 Java，沿用本目录 CoreTest / BorrowTest 的计数方式：每个 ok() 一条用例，每条一个不同输入）。
 * <ul>
 *   <li>日历：validDay（闰年 / 2000 与 2100 / 月末）、星期与加减天数（对拍 java.time）；</li>
 *   <li>重复规则：每月第 n 个周几（含第 5 个不存在、最后一个）、每隔 N 天、大小周交替；</li>
 *   <li>跨时区 / 夏令时：同一闹钟在 6 个时区的春秋切换日，原路径（AlarmMath）与新规则路径（RepeatRule）结果一致、严格晚于 now、落在当天；</li>
 *   <li>暂停 / 跳过：设置边界、跳过接下来 N 次、到期清理、单次闹钟不能暂停；</li>
 *   <li>错过的闹钟：没有记录不报、响过不报、调试闹钟不报、贪睡宽限、往回最多 3 天、最多 10 条、记录串畸形；</li>
 *   <li>对外接口 AlarmApi：参数越界、days 只取低 7 位、同一每周闹钟复用而大小周 / 每月 / 每隔 N 天不复用、调试闹钟不可改不出现、id 用完、标签清洗。</li>
 * </ul>
 * 标签截断把代理对切开这一条在 AdvClockLabelTest（2026-11-14 已修，已在门禁里）。
 */
public class AdvTest {
    static int pass, fail;
    static final List<String> FAILS = new ArrayList<String>();
    static final Set<String> NAMES = new HashSet<String>();

    static void ok(String name, boolean c) {
        if (!NAMES.add(name)) throw new IllegalStateException("重复用例名 " + name);
        if (c) pass++; else { fail++; FAILS.add(name); System.out.println("  FAIL " + name); }
    }

    static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        if (!c) System.out.println("  want: " + want + "\n  got : " + got);
        ok(name, c);
    }

    static long t(TimeZone tz, int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    static Alarm al(int id, int h, int m, int days) {
        Alarm a = new Alarm(); a.id = id; a.hour = h; a.minute = m; a.days = days; return a;
    }

    static int key(java.time.LocalDate d) { return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth(); }

    public static void main(String[] args) throws Exception {
        calendar(); monthlyNth(); everyN(); biweekly(); dst(); pause(); missed(); api(); until();
        System.out.println("AdvTest " + pass + "/" + (pass + fail) + (fail == 0 ? " ALL PASS" : " FAILED: " + FAILS));
        System.exit(fail == 0 ? 0 : 1);
    }

    static void calendar() {
        int[] ys = {2000, 2024, 2100, 2023, 1999, 2101};
        int[] ms = {2, 4, 12, 13, 1};
        int[] ds = {0, 28, 29, 30, 31};
        for (int y : ys) for (int m : ms) for (int d : ds) {
            int k = y * 10000 + m * 100 + d;
            boolean want;
            try {
                java.time.LocalDate.of(y, m, d);
                want = y >= 2000 && y <= 2100;
            } catch (Exception e) {
                want = false;
            }
            eq("validDay " + k, want, RepeatRule.validDay(k));
        }
        java.time.LocalDate base = java.time.LocalDate.of(2000, 1, 1);
        for (int i = 0; i < 120; i++) {
            java.time.LocalDate d = base.plusDays(i * 307L + (i % 7));
            int k = key(d);
            eq("dow " + k, d.getDayOfWeek().getValue() % 7, RepeatRule.dow(k));
            eq("epochDay " + k, d.toEpochDay(), RepeatRule.epochDay(k));
            int n = (i % 2 == 0 ? 1 : -1) * (i * 13 % 400);
            eq("addDays " + k + " " + n, key(d.plusDays(n)), RepeatRule.addDays(k, n));
        }
    }

    static void monthlyNth() {
        int[] nths = {1, 3, 5, -1};
        int[] dows = {0, 5};
        for (int mo = 1; mo <= 12; mo++) for (int nth : nths) for (int dw : dows) {
            Alarm a = al(1, 7, 30, 0);
            a.rule = RepeatRule.MONTHLY_NTH; a.nth = nth; a.nthDow = dw;
            long now = t(SH, 2024, mo, 1, 0, 0) - 1;
            java.time.DayOfWeek jd = java.time.DayOfWeek.of(dw == 0 ? 7 : dw);
            java.time.LocalDate first = java.time.LocalDate.of(2024, mo, 1).with(java.time.temporal.TemporalAdjusters.firstInMonth(jd));
            java.time.LocalDate want = nth == -1 ? java.time.LocalDate.of(2024, mo, 1).with(java.time.temporal.TemporalAdjusters.lastInMonth(jd)) : first.plusWeeks(nth - 1);
            if (want.getMonthValue() != mo) {
                // 这个月没有第 5 个：下一次一定不在本月，而且落在之后某月的第 5 个周几
                long n = RepeatRule.next(a, now, SH);
                int k = AlarmMath.dayKey(n, SH);
                ok("nth 2024-" + mo + " #" + nth + " dow" + dw + " 本月没有 → 往后找", k / 100 % 100 != mo && (k % 100 - 1) / 7 + 1 == 5 && RepeatRule.dow(k) == dw);
            } else {
                long n = RepeatRule.next(a, now, SH);
                eq("nth 2024-" + mo + " #" + nth + " dow" + dw, key(want) * 10000L + 730, AlarmMath.dayKey(n, SH) * 10000L + 730);
            }
        }
    }

    static void everyN() {
        int[][] cases = {{20241231, 2}, {20240228, 3}, {20240229, 7}, {20251231, 365}, {20240101, 30}, {20240310, 2}};
        for (int[] c : cases) {
            Alarm a = al(1, 6, 0, 0);
            a.rule = RepeatRule.EVERY_N; a.anchor = c[0]; a.everyN = c[1];
            long now = t(SH, c[0] / 10000, c[0] / 100 % 100, c[0] % 100, 0, 0) - 1;
            List<RepeatRule.Occ> l = RepeatRule.preview(a, now, 5, SH);
            eq("everyN " + c[0] + "/" + c[1] + " 首次就是起始日", c[0], l.isEmpty() ? 0 : l.get(0).day);
            boolean spaced = l.size() == (c[1] == 365 ? 3 : 5);   // 每 365 天：SCAN_DAYS=800 天内有第 0、365、730 天三次
            for (int i = 1; i < l.size(); i++) if (RepeatRule.epochDay(l.get(i).day) - RepeatRule.epochDay(l.get(i - 1).day) != c[1]) spaced = false;
            ok("everyN " + c[0] + "/" + c[1] + " 间隔", spaced);
            ok("everyN " + c[0] + "/" + c[1] + " 起始日前一天不响", !RepeatRule.matchesDay(a, RepeatRule.addDays(c[0], -1)));
        }
        Alarm bad = al(1, 6, 0, 0);
        bad.rule = RepeatRule.EVERY_N; bad.anchor = 0; bad.everyN = 2;
        eq("everyN 没有起始日 → 不响", 0L, RepeatRule.next(bad, t(SH, 2024, 1, 1, 0, 0), SH));
        bad.anchor = 20240101; bad.everyN = 0;
        eq("everyN N=0 → 不响", 0L, RepeatRule.next(bad, t(SH, 2024, 1, 1, 0, 0), SH));
    }

    static void biweekly() {
        int[] anchors = {20241111, 20241113, 20241117, 20241231, 20250101};   // 周一 / 周三 / 周日 / 跨年
        for (int an : anchors) {
            Alarm a = al(1, 8, 0, 0);
            a.rule = RepeatRule.BIWEEKLY; a.anchor = an; a.days = 1 << 1; a.daysB = 1 << 6;   // 本周周一、下周周六
            long anchorMon = RepeatRule.epochDay(an) - ((RepeatRule.dow(an) + 6) % 7);
            long now = t(SH, an / 10000, an / 100 % 100, an % 100, 0, 0) - 14L * 86400000L;
            List<RepeatRule.Occ> l = RepeatRule.preview(a, now, 8, SH);
            boolean good = l.size() == 8;
            for (RepeatRule.Occ o : l) {
                long wk = (RepeatRule.epochDay(o.day) - anchorMon) >= 0 ? (RepeatRule.epochDay(o.day) - anchorMon) / 7 : -((anchorMon - RepeatRule.epochDay(o.day) + 6) / 7);
                int want = (wk % 2 == 0) ? 1 : 6;
                if (RepeatRule.dow(o.day) != want) good = false;
            }
            ok("biweekly anchor " + an + " 交替（含起始日之前的周）", good);
            ok("biweekly anchor " + an + " anchorWeekIsCurrent", RepeatRule.anchorWeekIsCurrent(an, an) && !RepeatRule.anchorWeekIsCurrent(an, RepeatRule.addDays(an, 7)));
        }
    }

    /** {时区, 春季切换日, 秋季切换日}：切换日前一天 23:00 当 now。 */
    static void dst() {
        Object[][] zones = {{"America/New_York", 20250309, 20251102}, {"Europe/London", 20250330, 20251026}, {"Australia/Sydney", 20251005, 20250406},
                {"Australia/Lord_Howe", 20251005, 20250406}, {"Pacific/Chatham", 20250928, 20250406}, {"Asia/Shanghai", 20250309, 20251102}};
        int[][] hm = {{1, 30}, {2, 30}, {3, 30}, {0, 0}};
        for (Object[] z : zones) {
            TimeZone tz = TimeZone.getTimeZone((String) z[0]);
            for (int k = 1; k <= 2; k++) {
                int day = (Integer) z[k];
                int prev = RepeatRule.addDays(day, -1);
                long now = t(tz, prev / 10000, prev / 100 % 100, prev % 100, 23, 0);
                for (int[] x : hm) {
                    String n = z[0] + " " + (k == 1 ? "春" : "秋") + day + " " + x[0] + ":" + x[1];
                    Alarm daily = al(1, x[0], x[1], 0x7F);
                    long a1 = AlarmMath.next(daily, now, tz);
                    Alarm viaRule = al(1, x[0], x[1], 0x7F);
                    viaRule.skips.add(20991231);   // 有暂停记录 → 走 RepeatRule 路径（跳过的日子很远，不影响结果）
                    long a2 = AlarmMath.next(viaRule, now, tz);
                    ok(n + " 两条路径一致 / 严格晚于 now / 落在切换日", a1 == a2 && a1 > now && AlarmMath.dayKey(a1, tz) == day);
                }
            }
        }
        // 时区变化：同一墙钟闹钟，换时区后下次响铃按新时区的墙钟
        Alarm a = al(1, 7, 0, 0x7F);
        long now = t(SH, 2025, 6, 1, 12, 0);
        String[] tzs = {"Asia/Tokyo", "Europe/Berlin", "America/Los_Angeles", "Asia/Kolkata", "Pacific/Kiritimati", "Pacific/Pago_Pago"};
        for (String id : tzs) {
            TimeZone tz = TimeZone.getTimeZone(id);
            long n = AlarmMath.next(a, now, tz);
            Calendar c = Calendar.getInstance(tz);
            c.setTimeInMillis(n);
            ok("换到 " + id + " 仍是当地 7:00、24 小时内", c.get(Calendar.HOUR_OF_DAY) == 7 && c.get(Calendar.MINUTE) == 0 && n > now && n - now <= 86400000L);
        }
    }

    static void pause() {
        int today = 20251114;
        Alarm rep = al(1, 7, 0, 0x3E);
        Object[][] until = {{20251115, null}, {20251114, "恢复日期要晚于今天"}, {20251113, "恢复日期要晚于今天"}, {20261115, null}, {20261116, "最多暂停一年"},
                {20251131, "日期不对"}, {20250229, "日期不对"}, {0, "日期不对"}, {21010101, "日期不对"}, {20280229, "最多暂停一年"}};
        for (Object[] u : until) {
            Alarm a = al(1, 7, 0, 0x3E);
            eq("暂停到 " + u[0], u[1], Pause.until(a, (Integer) u[0], today));
            if (u[1] == null) eq("暂停到 " + u[0] + " 已记下", u[0], a.resume);
            else eq("暂停到 " + u[0] + " 不改", 0, a.resume);
        }
        eq("单次闹钟不能暂停", "只响一次的闹钟不能暂停，直接关掉即可", Pause.until(al(2, 7, 0, 0), 20251120, today));
        eq("没有闹钟", "没有这个闹钟", Pause.until(null, 20251120, today));
        long now = t(SH, 2025, 11, 14, 8, 0);   // 周五 8 点（7 点已过）
        Alarm s = al(3, 7, 0, 0x3E);
        List<Integer> added = Pause.skipNext(s, 3, now, SH);
        eq("跳过接下来 3 次（周一至五，从下周一起）", Arrays.asList(20251117, 20251118, 20251119), added);
        eq("跳过后下次是周四", 20251120, AlarmMath.dayKey(AlarmMath.next(s, now, SH), SH));
        eq("再跳 2 次接着往后", Arrays.asList(20251120, 20251121), Pause.skipNext(s, 2, now, SH));
        eq("跳过 N 最多 30", 30, Pause.skipNext(al(4, 7, 0, 0x7F), 999, now, SH).size());
        eq("单次闹钟跳过无效", 0, Pause.skipNext(al(5, 7, 0, 0), 3, now, SH).size());
        eq("跳过 0 次", 0, Pause.skipNext(al(6, 7, 0, 0x7F), 0, now, SH).size());
        Alarm many = al(7, 7, 0, 0x7F);
        for (int i = 0; i < 3; i++) Pause.skipNext(many, 30, now, SH);
        eq("跳过表最多 60 条", 60, many.skips.size());
        Alarm pr = al(8, 7, 0, 0x7F);
        pr.resume = 20251114; pr.skips.add(20251110); pr.skips.add(20251114); pr.skips.add(20251120);
        ok("清理：已到的恢复日与过去的跳过日", Pause.prune(pr, 20251114) && pr.resume == 0 && pr.skips.equals(Arrays.asList(20251114, 20251120)));
        ok("清理：没东西可清", !Pause.prune(pr, 20251114));
        Alarm paused = al(9, 7, 0, 0x7F);
        Pause.until(paused, 20251120, today);
        eq("暂停期间下次响铃是恢复日", 20251120, AlarmMath.dayKey(AlarmMath.next(paused, now, SH), SH));
        eq("暂停期间会被跳过的次数", 5, RepeatRule.skipped(paused, now, 100, SH).size());
        eq("暂停描述", "已暂停，11月20日起恢复", Pause.label(paused, now, SH));
        Pause.clear(paused);
        eq("取消暂停后明天就响", 20251115, AlarmMath.dayKey(AlarmMath.next(paused, now, SH), SH));
        Alarm single = al(10, 7, 0, 0);
        single.resume = 20251120;   // 旧数据里单次闹钟带了暂停：忽略
        eq("单次闹钟忽略暂停记录", 20251115, AlarmMath.dayKey(AlarmMath.next(single, now, SH), SH));
        eq("关着的闹钟不响", 0L, AlarmMath.next(rep(false), now, SH));
    }

    static Alarm rep(boolean on) {
        Alarm a = al(11, 7, 0, 0x7F);
        a.on = on;
        return a;
    }

    static void missed() {
        long now = t(SH, 2025, 11, 14, 9, 0);
        List<Alarm> as = new ArrayList<Alarm>();
        Alarm a7 = al(1, 7, 0, 0x7F); a7.label = "起床";
        Alarm a8 = al(2, 8, 0, 0x7F);
        Alarm dbg = al(990, 7, 30, 0x7F);
        as.add(a7); as.add(a8); as.add(dbg);
        long at7 = t(SH, 2025, 11, 14, 7, 0), at8 = t(SH, 2025, 11, 14, 8, 0);
        eq("没有记录不报", 0, Missed.find(null, null, as, now, SH).size());
        eq("排上的那次还没到不报", 0, Missed.find(new Missed.Mark(1, now + 60000, false), null, as, now, SH).size());
        eq("响过了不报", 0, Missed.find(new Missed.Mark(1, at7, false), new Missed.Mark(1, at7, false), new ArrayList<Alarm>(), now, SH).size());
        List<Missed.Item> l = Missed.find(new Missed.Mark(1, at7, false), null, as, now, SH);
        ok("7 点没响：报 7 点和之后本该响的 8 点，调试闹钟不算", l.size() == 2 && l.get(0).at == at7 && l.get(1).at == at8 && l.get(0).alarmId == 1);
        eq("标题", "错过了 7:00、8:00 的闹钟", Missed.title(l, now, SH));
        eq("调试闹钟排上的不报", 0, Missed.find(new Missed.Mark(990, at7, false), null, as, now, SH).size());
        eq("贪睡 10 分钟内会补响，不算错过", 0, Missed.find(new Missed.Mark(1, now - 60000, true), null, new ArrayList<Alarm>(), now, SH).size());
        eq("贪睡更早：算错过", 1, Missed.find(new Missed.Mark(1, now - SnoozeBook.LATE_MS - 60000, true), null, new ArrayList<Alarm>(), now, SH).size());
        List<Alarm> one = new ArrayList<Alarm>();
        one.add(a7);
        List<Missed.Item> week = Missed.find(new Missed.Mark(1, now - 7L * 86400000L, false), null, one, now, SH);
        ok("关机一周：只说最近 3 天（3 次 7 点）", week.size() == 3 && week.get(0).at >= now - Missed.MAX_BACK_MS);
        List<Alarm> lots = new ArrayList<Alarm>();
        for (int i = 1; i <= 20; i++) lots.add(al(i, i % 24, 0, 0x7F));
        eq("最多列 10 条", 10, Missed.find(new Missed.Mark(1, now - 86400000L, false), null, lots, now, SH).size());
        String[][] marks = {{"1,1700000000000,0", "1,1700000000000,0"}, {"1,1700000000000,1", "1,1700000000000,1"}, {"1,1700000000000", "1,1700000000000,0"},
                {" 2 , 5 , 1 ", "2,5,1"}, {"", null}, {"1", null}, {"x,1,0", null}, {"1,y,0", null}, {"1,99999999999999999999,0", null}, {null, null},
                {"1,5,yes", "1,5,0"}, {"1,5,0,extra", "1,5,0"}};
        for (String[] m : marks) {
            Missed.Mark k = Missed.Mark.parse(m[0]);
            eq("记录串 [" + m[0] + "]", m[1], k == null ? null : k.serialize());
        }
        List<Missed.Item> single = Missed.find(new Missed.Mark(1, at7, false), null, one, now, SH);
        eq("正文：一条带名字", "「起床」。手机关机或重启了，当时没有响铃，现在也不会补响。", Missed.text(single, Missed.reason("android.intent.action.BOOT_COMPLETED")));
        eq("原因：改时区", "时区变了，", Missed.reason("android.intent.action.TIMEZONE_CHANGED"));
        eq("原因：不认识", "", Missed.reason("x.y.Z"));
    }

    static void api() {
        List<Alarm> base = new ArrayList<Alarm>();
        base.add(al(1, 7, 0, 0x3E));
        Alarm bw = al(2, 8, 0, 0x02); bw.rule = RepeatRule.BIWEEKLY; bw.anchor = 20251110; bw.daysB = 0x40;
        base.add(bw);
        Alarm mn = al(3, 9, 0, 0); mn.rule = RepeatRule.MONTHLY_NTH; mn.nth = 1; mn.nthDow = 1;
        base.add(mn);
        base.add(al(990, 6, 0, 0x7F));
        int[][] bad = {{-1, 0}, {24, 0}, {0, -1}, {0, 60}, {99, 99}, {Integer.MIN_VALUE, 0}};
        for (int[] b : bad) {
            AlarmApi.Result r = AlarmApi.apply(base, AlarmApi.A_SET, 0, b[0], b[1], 0, true, "");
            ok("SET " + b[0] + ":" + b[1] + " 参数不对且不改表", r.code == AlarmApi.RC_BAD_ARGS && !r.changed && r.alarms.size() == 4);
        }
        AlarmApi.Result same = AlarmApi.apply(base, AlarmApi.A_SET, 0, 7, 0, 0x3E | 0x80, true, "早起");
        ok("同一时刻同一每周重复：复用 1 号（days 只取低 7 位）", same.code == AlarmApi.RC_OK && same.lastId == 1 && same.alarms.size() == 4 && "早起".equals(same.alarms.get(0).label));
        ok("纯函数：原表不变", base.get(0).label.length() == 0);
        AlarmApi.Result bwNew = AlarmApi.apply(base, AlarmApi.A_SET, 0, 8, 0, 0x02, true, "");
        ok("大小周 8:00 不被当成同一个：新建", bwNew.lastId == 4 && bwNew.alarms.size() == 5);
        AlarmApi.Result mnNew = AlarmApi.apply(base, AlarmApi.A_SET, 0, 9, 0, 0, true, "");
        ok("每月第 n 个 9:00（days=0）不被单次闹钟复用", mnNew.lastId == 4 && mnNew.alarms.size() == 5);
        ok("调试闹钟 6:00 不被复用", AlarmApi.apply(base, AlarmApi.A_SET, 0, 6, 0, 0x7F, true, "").lastId == 4);
        for (int id : new int[]{990, 900, 999, 0, -1}) {
            AlarmApi.Result r = AlarmApi.apply(base, AlarmApi.A_CANCEL, id, -1, -1, 0, false, "");
            ok("CANCEL " + id + " 不允许", r.code == AlarmApi.RC_BAD_ARGS && r.alarms.size() == 4);
            AlarmApi.Result t = AlarmApi.apply(base, AlarmApi.A_TOGGLE, id, -1, -1, 0, false, "");
            ok("TOGGLE " + id + " 不允许", t.code == AlarmApi.RC_BAD_ARGS);
        }
        ok("CANCEL 不存在", AlarmApi.apply(base, AlarmApi.A_CANCEL, 77, -1, -1, 0, false, "").code == AlarmApi.RC_NOT_FOUND);
        ok("TOGGLE 同状态不算改动", !AlarmApi.apply(base, AlarmApi.A_TOGGLE, 1, -1, -1, 0, true, "").changed);
        ok("TOGGLE 关", AlarmApi.apply(base, AlarmApi.A_TOGGLE, 1, -1, -1, 0, false, "").changed);
        ok("CANCEL 2 号（大小周）", AlarmApi.apply(base, AlarmApi.A_CANCEL, 2, -1, -1, 0, false, "").alarms.size() == 3);
        ok("不认识的动作", AlarmApi.apply(base, "com.onesay.clock.action.ALARM_WIPE", 0, -1, -1, 0, false, "").code == AlarmApi.RC_UNKNOWN);
        ok("动作名大小写不同不认", !AlarmApi.known("com.onesay.clock.action.alarm_set") && AlarmApi.known(AlarmApi.A_QUERY));
        List<Alarm> full = new ArrayList<Alarm>();
        full.add(al(899, 7, 0, 0));
        ok("普通 id 用完（899）→ 参数不对", AlarmApi.apply(full, AlarmApi.A_SET, 0, 8, 0, 0, true, "").code == AlarmApi.RC_BAD_ARGS);
        String[][] labels = {{"  起床  ", "起床"}, {"a\u202Eb\u202Dc", "abc"}, {"x\u2066y\u2069", "xy"}, {"line\nbreak\t", "linebreak"}, {"\u0000\u0007ok", "ok"},
                {"一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十多出来", "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十"}, {null, ""}, {"", ""}};
        for (String[] lb : labels) eq("标签清洗 [" + lb[0] + "]", lb[1], AlarmApi.cleanLabel(lb[0]));
        String dump = AlarmApi.dump(base, 1, t(SH, 2025, 11, 14, 9, 0), SH);
        ok("结果里没有调试闹钟", !dump.contains("\"id\":990") && dump.contains("\"count\":3"));
        ok("新规则附带 rule 描述", dump.contains("大小周") && dump.contains("每月第 1 个周一"));
    }

    static void until() {
        long now = 1_763_000_000_000L;
        Object[][] u = {{0L, "所有闹钟均已关闭"}, {now + 1, "1 分钟后响铃"}, {now + 60000, "1 分钟后响铃"}, {now + 60001, "2 分钟后响铃"},
                {now + 3600000, "1 小时后响铃"}, {now + 90 * 60000, "1 小时 30 分钟后响铃"}, {now + 86400000, "1 天后响铃"}, {now - 5000, "0 分钟后响铃"},
                {now + 86400000 + 61 * 60000, "1 天 1 小时 1 分钟后响铃"}};
        for (Object[] x : u) eq("untilLabel " + ((Long) x[0] - now), x[1], AlarmMath.untilLabel((Long) x[0], now));
    }
}
