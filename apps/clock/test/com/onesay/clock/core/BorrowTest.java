package com.onesay.clock.core;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;

/**
 * [borrow-1112] 社区借鉴（思路来源 Wake Me / WeckMich，GPL-3.0，只读思路）：重复规则 + 预览、暂停而不删除、
 * 错过的闹钟、可靠性自检，以及旧闹钟数据向后兼容。
 */
public class BorrowTest {
    static int pass, fail;
    static final List<String> FAILS = new ArrayList<String>();

    static void ok(String name, boolean c) {
        if (c) pass++; else { fail++; FAILS.add(name); System.out.println("  FAIL " + name); }
    }

    static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        if (!c) System.out.println("  want: " + want + "\n  got : " + got);
        ok(name, c);
    }

    static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    static long t(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(SH);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    static Alarm al(int id, int h, int m, int days) {
        Alarm a = new Alarm(); a.id = id; a.hour = h; a.minute = m; a.days = days; return a;
    }

    static List<Integer> days(List<RepeatRule.Occ> l) {
        List<Integer> r = new ArrayList<Integer>();
        for (RepeatRule.Occ o : l) r.add(o.day);
        return r;
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("clock.root", "."), rel).toPath()), Charset.forName("UTF-8"));
    }

    public static void main(String[] args) throws Exception {
        compat(); calendarMath(); biweekly(); monthly(); everyN(); preview(); pause(); missed(); health(); wiring(); alarmApi();
        System.out.println("BorrowTest " + pass + "/" + (pass + fail) + (fail == 0 ? " ALL PASS" : " FAILED: " + FAILS));
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 旧数据：读进来存回去逐字节一致；旧的每周几算法与新引擎在两年里逐分钟对拍一致。 */
    static void compat() throws Exception {
        String[] legacy = {
                "{\"id\":1,\"h\":7,\"m\":0,\"days\":62,\"on\":true,\"label\":\"上班\",\"brief\":true,\"snz\":true,\"snzMin\":5,\"vib\":true,\"sound\":\"\"}",
                "{\"id\":2,\"h\":23,\"m\":59,\"days\":0,\"on\":false,\"label\":\"\",\"brief\":false,\"snz\":false,\"snzMin\":10,\"vib\":false,\"sound\":\"content://x\"}",
                "{\"id\":3}",
        };
        for (String s : legacy) {
            Alarm a = Alarm.fromJson(new JSONObject(s));
            Alarm ref = Alarm.fromJson(new JSONObject(s));
            eq("旧闹钟 rule 缺省 = WEEKLY " + s.length(), RepeatRule.WEEKLY, a.rule);
            ok("旧闹钟没有暂停 " + s.length(), !a.hasPause());
            JSONObject back = a.toJson();
            ok("旧闹钟存回去不多写新键 " + s.length(), !back.has("rule") && !back.has("resume") && !back.has("skips") && !back.has("daysB"));
            eq("旧闹钟往返逐字节一致 " + s.length(), ref.toJson().toString(), Alarm.fromJson(new JSONObject(back.toString())).toJson().toString());
        }
        // 0.1.7 的 toJson 字段集原样
        Alarm a = Alarm.fromJson(new JSONObject(legacy[0]));
        eq("旧闹钟 toJson 键集合不变", new TreeSet<String>(Arrays.asList("id", "h", "m", "days", "on", "label", "brief", "snz", "snzMin", "vib", "sound")),
                new TreeSet<String>(keys(a.toJson())));
        ok("旧的工作日闹钟仍是重复", a.repeats());
        ok("旧的单次闹钟仍是单次", !Alarm.fromJson(new JSONObject(legacy[1])).repeats());
        // 对拍：旧路径（AlarmMath.next，WEEKLY 无暂停）与 RepeatRule.next 在 730 天、各种位图上一致
        Random r = new Random(1112);
        int mismatch = 0, n = 0;
        for (int i = 0; i < 3000; i++) {
            Alarm x = al(1, r.nextInt(24), r.nextInt(60), r.nextInt(128));
            long now = t(2026, 1, 1, 0, 0) + (long) (r.nextDouble() * 730 * 86400_000L);
            long oldWay = AlarmMath.next(x, now, SH), newWay = RepeatRule.next(x, now, SH);
            n++;
            if (oldWay != newWay) { mismatch++; if (mismatch < 3) System.out.println("  mismatch " + x.days + " " + now + " " + oldWay + " " + newWay); }
        }
        eq("每周几：旧算法与新引擎对拍 " + n + " 次 0 差异", 0, mismatch);
        // 坏数据：不认识的规则号退回 WEEKLY；大小周缺起始日退回 WEEKLY；坏日期丢掉
        Alarm bad = Alarm.fromJson(new JSONObject("{\"id\":5,\"days\":2,\"rule\":99}"));
        eq("不认识的规则号 → WEEKLY", RepeatRule.WEEKLY, bad.rule);
        Alarm noAnchor = Alarm.fromJson(new JSONObject("{\"id\":5,\"days\":2,\"rule\":1}"));
        eq("大小周缺起始日 → WEEKLY（不让闹钟消失）", RepeatRule.WEEKLY, noAnchor.rule);
        Alarm badSkip = Alarm.fromJson(new JSONObject("{\"id\":5,\"days\":2,\"skips\":[20261131,20261120,20261118,20261120],\"resume\":20261340}"));
        eq("坏的跳过日期丢掉、去重、升序", Arrays.asList(20261118, 20261120), badSkip.skips);
        eq("坏的恢复日期丢掉", 0, badSkip.resume);
        // 新规则往返
        Alarm b = al(9, 6, 30, 0x7E); b.rule = RepeatRule.BIWEEKLY; b.daysB = 0x3E; b.anchor = 20261109; b.resume = 20261201; b.skips.add(20261120);
        Alarm b2 = Alarm.fromJson(new JSONObject(b.toJson().toString()));
        ok("大小周往返", b2.rule == RepeatRule.BIWEEKLY && b2.daysB == 0x3E && b2.anchor == 20261109 && b2.resume == 20261201 && b2.skips.equals(Arrays.asList(20261120)));
        Alarm m = al(9, 8, 0, 0); m.rule = RepeatRule.MONTHLY_NTH; m.nth = -1; m.nthDow = 5;
        Alarm m2 = Alarm.fromJson(new JSONObject(m.toJson().toString()));
        ok("每月最后一个周五往返", m2.rule == RepeatRule.MONTHLY_NTH && m2.nth == -1 && m2.nthDow == 5 && m2.repeats());
    }

    static Set<String> keys(JSONObject o) {
        Set<String> s = new HashSet<String>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) s.add(it.next());
        return s;
    }

    static void calendarMath() {
        eq("epochDay 1970-01-01", 0L, RepeatRule.epochDay(19700101));
        eq("dow 2026-11-12 周四", 4, RepeatRule.dow(20261112));
        eq("dow 2027-01-01 周五", 5, RepeatRule.dow(20270101));
        eq("addDays 跨年", 20270102, RepeatRule.addDays(20261231, 2));
        eq("addDays 闰年 2 月", 20280229, RepeatRule.addDays(20280228, 1));
        eq("addDays 往回", 20261031, RepeatRule.addDays(20261101, -1));
        ok("validDay 2 月 30 不合法", !RepeatRule.validDay(20270230));
        ok("validDay 闰年 2 月 29 合法", RepeatRule.validDay(20280229));
        // 两年逐日：addDays 与 epochDay 互逆
        int k = 20260101; boolean okAll = true;
        for (int i = 0; i < 800; i++) { int nx = RepeatRule.addDays(k, 1); okAll &= RepeatRule.epochDay(nx) == RepeatRule.epochDay(k) + 1 && RepeatRule.validDay(nx); k = nx; }
        ok("addDays/epochDay 逐日 800 天一致", okAll);
    }

    static Alarm big() {   // 大小周：11-09 那周（大周）一至六，下周（小周）一至五
        Alarm a = al(1, 7, 0, 0x7E); a.rule = RepeatRule.BIWEEKLY; a.daysB = 0x3E; a.anchor = 20261109; return a;
    }

    static void biweekly() {
        Alarm a = big();
        ok("大周周六响", RepeatRule.matchesDay(a, 20261114));
        ok("小周周六不响", !RepeatRule.matchesDay(a, 20261121));
        ok("再下周（大周）周六响", RepeatRule.matchesDay(a, 20261128));
        ok("小周周五响", RepeatRule.matchesDay(a, 20261120));
        ok("周日都不响", !RepeatRule.matchesDay(a, 20261115) && !RepeatRule.matchesDay(a, 20261122));
        ok("起始日之前的周也按奇偶交替（11-07 周六是小周）", !RepeatRule.matchesDay(a, 20261107) && RepeatRule.matchesDay(a, 20261031));
        Alarm w = big(); w.anchor = 20261111;   // 起始日是周三：仍以那周周一为界
        ok("起始日在周中：同一周的周一也算大周", RepeatRule.matchesDay(w, 20261109) && RepeatRule.matchesDay(w, 20261114));
        eq("大小周 next：周五 8:00 之后 → 周六 7:00", t(2026, 11, 14, 7, 0), AlarmMath.next(a, t(2026, 11, 13, 8, 0), SH));
        eq("大小周 next：小周周五 8:00 之后 → 下周一", t(2026, 11, 23, 7, 0), AlarmMath.next(a, t(2026, 11, 20, 8, 0), SH));
        eq("大小周 文案", "大小周 · 本周周一至六，下周周一至五", RepeatRule.label(a));
        ok("anchorWeekIsCurrent", RepeatRule.anchorWeekIsCurrent(20261109, 20261112) && !RepeatRule.anchorWeekIsCurrent(20261109, 20261119));
    }

    static void monthly() {
        Alarm a = al(1, 9, 0, 0); a.rule = RepeatRule.MONTHLY_NTH; a.nth = -1; a.nthDow = 5;
        List<Integer> l = days(RepeatRule.preview(a, t(2026, 11, 12, 10, 0), 4, SH));
        eq("每月最后一个周五", Arrays.asList(20261127, 20261225, 20270129, 20270226), l);
        Alarm b = al(1, 9, 0, 0); b.rule = RepeatRule.MONTHLY_NTH; b.nth = 2; b.nthDow = 3;
        eq("每月第 2 个周三", Arrays.asList(20261209, 20270113, 20270210), days(RepeatRule.preview(b, t(2026, 11, 12, 10, 0), 3, SH)));
        Alarm c = al(1, 9, 0, 0); c.rule = RepeatRule.MONTHLY_NTH; c.nth = 5; c.nthDow = 1;
        eq("每月第 5 个周一（没有的月份跳过）", Arrays.asList(20261130, 20270329, 20270531), days(RepeatRule.preview(c, t(2026, 11, 12, 10, 0), 3, SH)));
        eq("每月 文案", "每月最后一个周五", RepeatRule.label(a));
        eq("每月 文案 2", "每月第 2 个周三", RepeatRule.label(b));
        ok("每月规则算重复", a.repeats());
    }

    static void everyN() {
        Alarm a = al(1, 20, 0, 0); a.rule = RepeatRule.EVERY_N; a.everyN = 3; a.anchor = 20261112;
        eq("每隔 3 天（含起始日当天）", Arrays.asList(20261112, 20261115, 20261118, 20261121), days(RepeatRule.preview(a, t(2026, 11, 12, 10, 0), 4, SH)));
        eq("起始日当天已过 → 第三天", 20261115, RepeatRule.preview(a, t(2026, 11, 12, 21, 0), 1, SH).get(0).day);
        ok("起始日之前不响", !RepeatRule.matchesDay(a, 20261109));
        eq("每隔 文案", "每隔 3 天", RepeatRule.label(a));
    }

    static void preview() {
        Alarm a = al(1, 7, 0, 0x3E);
        List<RepeatRule.Occ> p = RepeatRule.preview(a, t(2026, 11, 12, 10, 0), 5, SH);
        eq("预览 5 次（工作日）", Arrays.asList(20261113, 20261116, 20261117, 20261118, 20261119), days(p));
        eq("预览文案", "11月13日 周五 07:00", RepeatRule.occText(p.get(0), 7, 0));
        Alarm once = al(1, 7, 0, 0);
        eq("单次只有一条", 1, RepeatRule.preview(once, t(2026, 11, 12, 10, 0), 5, SH).size());
        Alarm none = al(1, 7, 0, 0); none.rule = RepeatRule.BIWEEKLY; none.anchor = 20261109; none.days = 0; none.daysB = 0;
        eq("大小周两周都没勾 → 没有响铃", 0, RepeatRule.preview(none, t(2026, 11, 12, 10, 0), 5, SH).size());
        eq("没有响铃 → next = 0（不排）", 0L, AlarmMath.next(none, t(2026, 11, 12, 10, 0), SH));
    }

    static void pause() {
        long now = t(2026, 11, 12, 10, 0);   // 周四
        int today = 20261112;
        Alarm a = al(1, 7, 0, 0x7F);
        eq("暂停到 11-20", null, Pause.until(a, 20261120, today));
        eq("暂停中 next = 恢复日 7:00", t(2026, 11, 20, 7, 0), AlarmMath.next(a, now, SH));
        eq("会被跳过的日子", Arrays.asList(20261113, 20261114, 20261115, 20261116, 20261117, 20261118, 20261119), days(RepeatRule.skipped(a, now, 30, SH)));
        eq("暂停文案", "已暂停，11月20日起恢复", Pause.label(a, now, SH));
        ok("恢复日不能是今天或以前", Pause.until(a, today, today) != null);
        ok("最多暂停一年", Pause.until(a, 20280101, today) != null);
        Pause.clear(a);
        eq("取消暂停 → 明天照常", t(2026, 11, 13, 7, 0), AlarmMath.next(a, now, SH));

        Alarm w = al(2, 7, 0, 0x3E);
        List<Integer> added = Pause.skipNext(w, 3, now, SH);
        eq("跳过接下来 3 次（工作日）", Arrays.asList(20261113, 20261116, 20261117), added);
        eq("跳过后 next", t(2026, 11, 18, 7, 0), AlarmMath.next(w, now, SH));
        eq("跳过列表", added, days(RepeatRule.skipped(w, now, 30, SH)));
        eq("跳过文案", "跳过接下来 3 次", Pause.label(w, now, SH));
        eq("再跳 1 次接着往后", Arrays.asList(20261118), Pause.skipNext(w, 1, now, SH));
        ok("到期自动恢复：11-18 之后 next 回到正常", AlarmMath.next(w, t(2026, 11, 18, 8, 0), SH) == t(2026, 11, 19, 7, 0));
        ok("prune 清掉过去的跳过日", Pause.prune(w, 20261118) && w.skips.equals(Arrays.asList(20261118)));
        ok("prune 没东西可清 → false", !Pause.prune(w, 20261118));
        Alarm r = al(3, 7, 0, 0x7F); r.resume = 20261113;
        ok("prune 清掉已到的恢复日", Pause.prune(r, 20261113) && r.resume == 0);

        Alarm once = al(4, 7, 0, 0);
        ok("单次闹钟不能暂停", Pause.until(once, 20261120, today) != null);
        eq("单次闹钟不能跳过", 0, Pause.skipNext(once, 2, now, SH).size());
        once.resume = 20261120;   // 就算数据里有，也不影响单次闹钟
        eq("单次闹钟忽略暂停字段（原行为）", t(2026, 11, 13, 7, 0), AlarmMath.next(once, now, SH));

        Alarm big = big();
        Pause.skipNext(big, 2, t(2026, 11, 13, 8, 0), SH);
        eq("大小周跳过 2 次：周六、周一", Arrays.asList(20261114, 20261116), big.skips);
        Alarm big2 = Alarm.fromJson(new JSONObject(safeJson(big)));
        eq("跳过表存盘往返", big.skips, big2.skips);
        AlarmMath.Event e = AlarmMath.earliest(Arrays.asList(big2, al(5, 6, 0, 0x7F)), null, t(2026, 11, 13, 8, 0), SH);
        ok("earliest 混合规则：每天 6:00 先到", e != null && e.alarmId == 5 && e.at == t(2026, 11, 14, 6, 0));
    }

    static String safeJson(Alarm a) { try { return a.toJson().toString(); } catch (Exception e) { return "{}"; } }

    static void missed() {
        Alarm a = al(1, 7, 0, 0x7F); a.label = "起床";
        Alarm b = al(2, 7, 30, 0x7F);
        List<Alarm> as = Arrays.asList(a, b);
        long boot = t(2026, 11, 13, 8, 0);
        Missed.Mark sched = new Missed.Mark(1, t(2026, 11, 13, 7, 0), false);
        List<Missed.Item> l = Missed.find(sched, null, as, boot, SH);
        eq("关机期间错过 7:00 和 7:30", 2, l.size());
        ok("按时间排序", l.get(0).at == t(2026, 11, 13, 7, 0) && l.get(1).alarmId == 2);
        eq("标题", "错过了 7:00、7:30 的闹钟", Missed.title(l, boot, SH));
        eq("只错过一个的文案", "错过了 7:00 的闹钟", Missed.title(l.subList(0, 1), boot, SH));
        eq("正文带名称、说明不补响", "「起床」。手机关机或重启了，当时没有响铃，现在也不会补响。",
                Missed.text(l.subList(0, 1), Missed.reason("android.intent.action.BOOT_COMPLETED")));
        eq("响过了 → 不报", 0, Missed.find(sched, sched, Arrays.asList(a), boot, SH).size());
        eq("还没到点 → 不报", 0, Missed.find(sched, null, as, t(2026, 11, 13, 6, 59), SH).size());
        eq("没有记录（刚升级上来）→ 不报", 0, Missed.find(null, null, as, boot, SH).size());
        eq("调试闹钟不报", 0, Missed.find(new Missed.Mark(990, t(2026, 11, 13, 7, 0), false), null, new ArrayList<Alarm>(), boot, SH).size());
        Missed.Mark snz = new Missed.Mark(1, t(2026, 11, 13, 7, 55), true);
        eq("贪睡错过 10 分钟内 → 排程会补响，不报", 0, Missed.find(snz, null, new ArrayList<Alarm>(), boot, SH).size());
        Missed.Mark snzOld = new Missed.Mark(1, t(2026, 11, 13, 7, 40), true);
        List<Missed.Item> s = Missed.find(snzOld, null, new ArrayList<Alarm>(), boot, SH);
        ok("贪睡错过超过 10 分钟 → 报", s.size() == 1 && s.get(0).snooze);
        // 关机两天：只回看 3 天、最多 10 个；跨天带日期
        long later = t(2026, 11, 15, 9, 0);
        List<Missed.Item> two = Missed.find(sched, null, as, later, SH);
        eq("关机两天：每天两个 × 3 天", 6, two.size());
        eq("跨天标题带日期", "错过了 11月13日 7:00 等 6 个闹钟", Missed.title(two, later, SH));
        long week = t(2026, 11, 20, 9, 0);
        ok("关机一周：只说最近 3 天", Missed.find(sched, null, as, week, SH).size() <= 6);
        Alarm paused = al(3, 7, 15, 0x7F); paused.resume = 20261201;
        eq("暂停中的闹钟不算错过", 2, Missed.find(sched, null, Arrays.asList(a, b, paused), boot, SH).size());
        Alarm off = al(4, 7, 20, 0x7F); off.on = false;
        eq("关着的闹钟不算错过", 2, Missed.find(sched, null, Arrays.asList(a, b, off), boot, SH).size());
        eq("Mark 往返", "1," + t(2026, 11, 13, 7, 0) + ",0", Missed.Mark.parse(sched.serialize()).serialize());
        eq("Mark 坏串", null, Missed.Mark.parse("x,y"));
        eq("原因：时区", "时区变了，", Missed.reason("android.intent.action.TIMEZONE_CHANGED"));
    }

    static void health() {
        Health.Input in = new Health.Input();
        eq("全读不到 → 不报", 0, Health.evaluate(in).size());
        in.sdk = 31; in.exactAllowed = false; in.ignoringBattery = false; in.alarmVolume = 0; in.alarmVolumeMax = 7;
        in.zen = Health.ZEN_NONE; in.notificationsEnabled = false;
        List<Health.Issue> l = Health.evaluate(in);
        List<String> codes = new ArrayList<String>();
        for (Health.Issue i : l) codes.add(i.code);
        eq("五项都报", Arrays.asList(Health.EXACT, Health.BATTERY, Health.VOLUME, Health.DND, Health.NOTIF), codes);
        eq("最严重", Health.SEVERE, Health.worst(l));
        eq("横幅", "有 5 项可能影响闹钟，点这里检查", Health.banner(l));
        Health.Input old = new Health.Input();
        old.sdk = 28; old.exactAllowed = false;
        eq("Android 12 以前不查精确闹钟权限", 0, Health.evaluate(old).size());
        Health.Input pri = new Health.Input();
        pri.zen = Health.ZEN_PRIORITY; pri.zenAllowsAlarms = true;
        eq("优先模式放行闹钟 → 不报", 0, Health.evaluate(pri).size());
        pri.zenAllowsAlarms = false;
        eq("优先模式不放行闹钟 → 报", Health.DND, Health.evaluate(pri).get(0).code);
        Health.Input alarmsOnly = new Health.Input();
        alarmsOnly.zen = Health.ZEN_ALARMS;
        eq("仅闹钟模式不挡闹钟", 0, Health.evaluate(alarmsOnly).size());
        eq("调到一半（7 档）", 4, Health.halfVolume(7));
        eq("调到一半（至少 1）", 1, Health.halfVolume(0));
        eq("没问题横幅为空", "", Health.banner(new ArrayList<Health.Issue>()));
    }

    /** 接线：开机 / 时区先查错过再重排；排程记下排上的、响铃记下响过的；自检页登记且不导出；新界面文字不出现品牌字样。 */
    static void wiring() throws Exception {
        String boot = src("app/src/com/onesay/clock/sched/BootReceiver.java"), sched = src("app/src/com/onesay/clock/sched/Sched.java");
        ok("BootReceiver 先查错过再重排", boot.indexOf("Sched.checkMissed(") >= 0 && boot.indexOf("Sched.checkMissed(") < boot.indexOf("Sched.rescheduleNow("));
        ok("排程记下排上的那一次", sched.contains("setSchedMark("));
        ok("响铃记账记下响过的那一次", sched.indexOf("setFiredMark(", sched.indexOf("public static boolean onFired(")) > 0);
        ok("错过只发通知，不起响铃服务", !between(sched, "public static void checkMissed(", "\n    }\n").contains("RingService"));
        String man = src("app/AndroidManifest.xml");
        ok("自检页登记、不导出", man.contains("android:name=\".ui.CheckActivity\"") && man.matches("(?s).*CheckActivity\"[^>]*android:exported=\"false\".*"));
        String app = src("app/src/com/onesay/clock/App.java");
        ok("错过的闹钟有自己的通知渠道", app.contains("CH_MISSED"));
        for (String f : new String[]{"app/src/com/onesay/clock/ui/CheckActivity.java", "app/src/com/onesay/clock/core/RepeatRule.java", "app/src/com/onesay/clock/core/Pause.java",
                "app/src/com/onesay/clock/core/Missed.java", "app/src/com/onesay/clock/core/Health.java", "app/src/com/onesay/clock/ui/RuleUi.java"}) {
            String s = strings(src(f));
            ok("新界面文字不含品牌字样 " + f, !s.contains("Bixby") && !s.contains("三星"));
        }
    }

    /** 开源版：对外闹钟接口（AlarmApi）不把新规则的闹钟当成同一个「每周几」闹钟；dump 只在新规则时附带 rule。 */
    static void alarmApi() throws Exception {
        Alarm big = big(); big.id = 3; big.days = 0x3E;   // 大小周，本周一至五
        List<Alarm> as = new ArrayList<Alarm>(Arrays.asList(big));
        AlarmApi.Result r = AlarmApi.apply(as, AlarmApi.A_SET, 0, 7, 0, 0x3E, true, "");
        eq("ALARM_SET 同时刻同 days 不复用大小周闹钟（另建一个）", 2, r.alarms.size());
        Alarm wk = al(4, 7, 0, 0x3E);
        AlarmApi.Result r2 = AlarmApi.apply(Arrays.asList(wk), AlarmApi.A_SET, 0, 7, 0, 0x3E, true, "");
        eq("每周几的旧行为不变：复用", 1, r2.alarms.size());
        JSONObject d = new JSONObject(AlarmApi.dump(r.alarms, -1, t(2026, 11, 12, 10, 0), SH));
        org.json.JSONArray arr = d.getJSONArray("alarms");
        ok("dump：新规则附带 rule", arr.getJSONObject(0).optString("rule").startsWith("大小周"));
        ok("dump：每周几的不多出 rule 键（格式不变）", !arr.getJSONObject(1).has("rule"));
    }

    static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return "";
        int j = s.indexOf(b, i);
        return j < 0 ? s.substring(i) : s.substring(i, j);
    }

    /** 源码里双引号字符串的拼接（只看用户可见文字，不看注释）。 */
    static String strings(String code) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(code);
        while (m.find()) sb.append(m.group(1)).append('\n');
        return sb.toString();
    }
}
