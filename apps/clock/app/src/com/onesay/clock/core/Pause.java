package com.onesay.clock.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * [borrow-1112] 暂停而不删除（纯 Java）。思路来源：Wake Me（WeckMich，GPL-3.0）——只读思路，代码自写。
 * 两种暂停可以并存：暂停到某日（{@link Alarm#resume}：这天起恢复）与跳过接下来 N 次（写成具体日期放进 {@link Alarm#skips}）。
 * 到期自然恢复（过去的日子不再影响 next），{@link #prune} 只是把过期记录清掉。单次闹钟不支持暂停。
 */
public final class Pause {
    private Pause() { }

    public static final int MAX_SKIPS = 60, MAX_SKIP_N = 30, MAX_DAYS = 366;

    /** 暂停到 resumeDay（这一天起恢复响铃）。返回 null = 成功；否则是不能设的原因。 */
    public static String until(Alarm a, int resumeDay, int today) {
        if (a == null) return "没有这个闹钟";
        if (!a.repeats()) return "只响一次的闹钟不能暂停，直接关掉即可";
        if (!RepeatRule.validDay(resumeDay)) return "日期不对";
        if (resumeDay <= today) return "恢复日期要晚于今天";
        if (RepeatRule.epochDay(resumeDay) - RepeatRule.epochDay(today) > MAX_DAYS) return "最多暂停一年";
        a.resume = resumeDay;
        return null;
    }

    /** 跳过接下来 n 次（从 now 之后、按规则会响且还没被跳过的那几次）。返回被新加进跳过表的日期。 */
    public static List<Integer> skipNext(Alarm a, int n, long now, TimeZone tz) {
        List<Integer> added = new ArrayList<Integer>();
        if (a == null || !a.repeats() || n <= 0) return added;
        n = Math.min(n, MAX_SKIP_N);
        for (RepeatRule.Occ o : RepeatRule.list(a, now, n, false, tz)) {
            if (a.skips.size() >= MAX_SKIPS) break;
            a.skips.add(o.day);
            added.add(o.day);
        }
        Collections.sort(a.skips);
        return added;
    }

    /** 取消全部暂停（立即恢复）。 */
    public static void clear(Alarm a) {
        if (a == null) return;
        a.resume = 0;
        a.skips.clear();
    }

    /** 清掉已经过去的暂停记录（today 之前的跳过日、已到的恢复日）。返回有没有改动。 */
    public static boolean prune(Alarm a, int today) {
        if (a == null) return false;
        boolean ch = false;
        if (a.resume > 0 && a.resume <= today) { a.resume = 0; ch = true; }
        for (int i = a.skips.size() - 1; i >= 0; i--) if (a.skips.get(i) < today) { a.skips.remove(i); ch = true; }
        return ch;
    }

    /** 列表上的暂停描述；没暂停返回 ""。 */
    public static String label(Alarm a, long now, TimeZone tz) {
        if (a == null || !a.repeats() || !a.hasPause()) return "";
        int today = AlarmMath.dayKey(now, tz);
        if (a.resume > today) return "已暂停，" + RepeatRule.dayText(a.resume) + "起恢复";
        int n = RepeatRule.skipped(a, now, MAX_SKIPS, tz).size();
        return n > 0 ? "跳过接下来 " + n + " 次" : "";
    }
}
