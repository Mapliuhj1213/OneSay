package com.onesay.clock.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

/**
 * [borrow-1112] 错过的闹钟（纯 Java）。思路来源：Wake Me（WeckMich，GPL-3.0）——只读思路，代码自写。
 *
 * <p>每次排程把「排上的那一次」记下（{@link Mark}）；响铃时记「响过的那一次」。开机 / 改时间 / 改时区 / 升级后，
 * 先拿上次排上的那一次和「响过的」比：到点了却没响过，就是错过了。再把那次之后、现在之前，其它闹钟本该响的也找出来，
 * 合成一条通知「错过了 X 点的闹钟」。<b>只补通知，不补响。</b>
 * <ul>
 *   <li>贪睡：错过 {@link SnoozeBook#LATE_MS} 以内的会被排程立刻补响（原有行为），所以只在更晚时才算错过；</li>
 *   <li>调试 / 示例闹钟（id ≥ 900）不算；</li>
 *   <li>往回最多看 {@link #MAX_BACK_MS}（关机一周回来也只说最近 3 天的），最多列 {@link #MAX_ITEMS} 个。</li>
 * </ul>
 * 第一次装上这一版时没有记录 → 不报（向后兼容：旧版本不写记录）。
 */
public final class Missed {
    private Missed() { }

    public static final long MAX_BACK_MS = 3L * 86400_000L;
    public static final int MAX_ITEMS = 10;

    /** 一次排程 / 一次响铃的记录：「id,at,snooze」。 */
    public static final class Mark {
        public final int id;
        public final long at;
        public final boolean snooze;

        public Mark(int id, long at, boolean snooze) { this.id = id; this.at = at; this.snooze = snooze; }

        public String serialize() { return id + "," + at + "," + (snooze ? 1 : 0); }

        public static Mark parse(String s) {
            if (s == null || s.length() == 0) return null;
            String[] p = s.split(",");
            if (p.length < 2) return null;
            try {
                return new Mark(Integer.parseInt(p[0].trim()), Long.parseLong(p[1].trim()), p.length > 2 && "1".equals(p[2].trim()));
            } catch (NumberFormatException e) { return null; }
        }

        public boolean same(Mark o) { return o != null && o.id == id && o.at == at && o.snooze == snooze; }

        public String toString() { return serialize(); }
    }

    /** 错过的一次。 */
    public static final class Item {
        public final int alarmId;
        public final long at;
        public final boolean snooze;
        public final String label;

        Item(int alarmId, long at, boolean snooze, String label) { this.alarmId = alarmId; this.at = at; this.snooze = snooze; this.label = label; }

        public String toString() { return (snooze ? "snooze#" : "alarm#") + alarmId + "@" + at; }
    }

    /**
     * 找出错过的闹钟。
     * @param scheduled 上次排上的那一次（null = 没有记录 → 不报）
     * @param fired     最近一次真的响了的那一次（可 null）
     * @param alarms    现在的闹钟表（用来找那次之后本该响的其它闹钟，和拿名称）
     */
    public static List<Item> find(Mark scheduled, Mark fired, List<Alarm> alarms, long now, TimeZone tz) {
        List<Item> out = new ArrayList<Item>();
        if (scheduled == null || scheduled.at <= 0 || scheduled.at > now) return out;
        if (scheduled.same(fired)) return out;
        if (AlarmMath.isDebugId(scheduled.id)) return out;
        if (scheduled.snooze && scheduled.at > now - SnoozeBook.LATE_MS) return out;   // 排程会立刻补响
        long from = Math.max(scheduled.at, now - MAX_BACK_MS);
        if (scheduled.at >= from) out.add(new Item(scheduled.id, scheduled.at, scheduled.snooze, labelOf(alarms, scheduled.id)));
        if (alarms != null) {
            for (Alarm a : alarms) {
                if (a == null || !a.on || AlarmMath.isDebugId(a.id)) continue;
                for (long t : occurrencesBetween(a, from, now, tz)) {
                    if (!scheduled.snooze && a.id == scheduled.id && t == scheduled.at) continue;   // 就是上面那一次
                    if (fired != null && !fired.snooze && fired.id == a.id && fired.at == t) continue;
                    out.add(new Item(a.id, t, false, a.label));
                }
            }
        }
        Collections.sort(out, new Comparator<Item>() {
            public int compare(Item x, Item y) { return x.at < y.at ? -1 : x.at > y.at ? 1 : x.alarmId - y.alarmId; }
        });
        while (out.size() > MAX_ITEMS) out.remove(out.size() - 1);
        return out;
    }

    /** 闹钟 a 在 [from, to] 之间本该响的时刻（按规则与暂停；单次闹钟就看它那一刻）。 */
    static List<Long> occurrencesBetween(Alarm a, long from, long to, TimeZone tz) {
        List<Long> l = new ArrayList<Long>();
        for (RepeatRule.Occ o : RepeatRule.list(a, from - 1, 64, false, tz)) {
            if (o.at > to) break;
            l.add(o.at);
            if (!a.repeats()) break;
        }
        return l;
    }

    static String labelOf(List<Alarm> alarms, int id) {
        if (alarms != null) for (Alarm a : alarms) if (a != null && a.id == id) return a.label;
        return "";
    }

    static String hm(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        return h + ":" + (m < 10 ? "0" : "") + m;
    }

    /** 通知标题：「错过了 7:00 的闹钟」/「错过了 7:00、7:30 的闹钟」/「错过了 7:00 等 4 个闹钟」。 */
    public static String title(List<Item> items, long now, TimeZone tz) {
        if (items == null || items.isEmpty()) return "";
        int today = AlarmMath.dayKey(now, tz);
        StringBuilder sb = new StringBuilder("错过了 ");
        int shown = Math.min(items.size(), items.size() <= 2 ? 2 : 1);
        for (int i = 0; i < shown; i++) {
            Item it = items.get(i);
            if (i > 0) sb.append("、");
            if (AlarmMath.dayKey(it.at, tz) != today) sb.append(RepeatRule.dayText(AlarmMath.dayKey(it.at, tz))).append(' ');
            sb.append(hm(it.at, tz));
        }
        if (items.size() > shown) sb.append(" 等 ").append(items.size()).append(" 个闹钟");
        else sb.append(" 的闹钟");
        return sb.toString();
    }

    /** 通知正文：说明为什么错过、没有补响。 */
    public static String text(List<Item> items, String why) {
        StringBuilder sb = new StringBuilder();
        if (items != null && items.size() == 1 && items.get(0).label.length() > 0) sb.append("「").append(items.get(0).label).append("」");
        if (items != null && items.size() == 1 && items.get(0).snooze) sb.append(sb.length() > 0 ? "的" : "").append("稍后提醒");
        if (sb.length() > 0) sb.append("。");
        sb.append(why == null || why.length() == 0 ? "手机关机或时间变了，" : why).append("当时没有响铃，现在也不会补响。");
        return sb.toString();
    }

    /** 系统广播 → 通知里的原因。 */
    public static String reason(String action) {
        if (action == null) return "";
        if (action.endsWith("BOOT_COMPLETED")) return "手机关机或重启了，";
        if (action.endsWith("TIMEZONE_CHANGED")) return "时区变了，";
        if (action.endsWith("TIME_SET")) return "手机时间被改了，";
        if (action.endsWith("MY_PACKAGE_REPLACED")) return "时钟刚刚更新，";
        return "";
    }
}
