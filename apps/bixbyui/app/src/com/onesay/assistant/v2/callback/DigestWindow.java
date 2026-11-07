package com.onesay.assistant.v2.callback;

import com.onesay.assistant.v2.jobs.JobStore;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * 汇总队列 + 送达窗口（lane-a 第 5 项）。纯 Java，JVM 可测。
 *
 * <ul>
 *   <li>不急的结果（LOW，或 NORMAL 但此刻不方便：通话中 / 媒体在播）不马上打扰，推迟到下一个送达窗口（默认 08:00 / 12:30 / 19:00）；</li>
 *   <li>同一个窗口里到点的多条合并成一条：「3 件事：…；…；…」（一张卡、一段播报、一条通知）；</li>
 *   <li>免打扰时段里的窗口顺延到时段结束后（08:00 在默认 23–7 之外，不受影响）。</li>
 * </ul>
 */
public final class DigestWindow {

    private DigestWindow() {}

    public static final int[] DEFAULT_WINDOWS = {8 * 60, 12 * 60 + 30, 19 * 60};

    /** now 之后（含 now 所在分钟之后）的下一个窗口时刻。 */
    public static long nextWindow(long now, int[] windows, TimeZone tz) {
        int[] w = windows == null || windows.length == 0 ? DEFAULT_WINDOWS : windows;
        Calendar c = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        long best = Long.MAX_VALUE;
        for (int day = 0; day <= 1; day++) {
            for (int m : w) {
                c.setTimeInMillis(now);
                c.add(Calendar.DAY_OF_MONTH, day);
                c.set(Calendar.HOUR_OF_DAY, m / 60);
                c.set(Calendar.MINUTE, m % 60);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                long t = c.getTimeInMillis();
                if (t > now && t < best) best = t;
            }
        }
        return best;
    }

    /** 这条要不要推迟到窗口：LOW 一律推迟；NORMAL 在通话 / 媒体播放时推迟；HIGH / USER_ASKED 立即。 */
    public static boolean shouldDefer(DeliveryPolicy.Urgency u, DeliveryPolicy.Env env) {
        if (u == DeliveryPolicy.Urgency.LOW) return true;
        if (u == DeliveryPolicy.Urgency.NORMAL) return env != null && (env.inCall || env.mediaPlaying);
        return false;
    }

    /** 合并后的一条（合并了几条、标题、正文、紧急度取最高、是否要播取或）。 */
    public static final class Merged {
        public final List<JobStore.Outbox> parts;
        public final String title;
        public final String text;
        public final DeliveryPolicy.Urgency urgency;
        public final boolean wantSpeak;

        Merged(List<JobStore.Outbox> parts, String title, String text, DeliveryPolicy.Urgency urgency, boolean wantSpeak) {
            this.parts = parts;
            this.title = title;
            this.text = text;
            this.urgency = urgency;
            this.wantSpeak = wantSpeak;
        }
    }

    /** 到点的若干条 → 一条（只有一条时原样）。 */
    public static Merged merge(List<JobStore.Outbox> due) {
        List<JobStore.Outbox> parts = new ArrayList<JobStore.Outbox>(due);
        if (parts.isEmpty()) return null;
        DeliveryPolicy.Urgency top = DeliveryPolicy.Urgency.LOW;
        boolean speak = false;
        for (JobStore.Outbox o : parts) {
            DeliveryPolicy.Urgency u = urgency(o);
            if (WorthTelling.rank(u) > WorthTelling.rank(top)) top = u;
            speak |= o.wantSpeak;
        }
        if (parts.size() == 1) {
            JobStore.Outbox o = parts.get(0);
            return new Merged(parts, o.title, o.text, top, speak);
        }
        StringBuilder sb = new StringBuilder().append(parts.size()).append(" 件事：");
        for (int i = 0; i < parts.size(); i++) {
            JobStore.Outbox o = parts.get(i);
            if (i > 0) sb.append("；");
            String line = o.text.length() > 50 ? o.text.substring(0, 49) + "…" : o.text;
            sb.append(o.title.length() > 0 ? o.title + "：" : "").append(line);
        }
        sb.append("。");
        return new Merged(parts, "汇总 · " + parts.size() + " 件事", sb.toString(), top, speak);
    }

    static DeliveryPolicy.Urgency urgency(JobStore.Outbox o) {
        try {
            return DeliveryPolicy.Urgency.valueOf(o.urgency);
        } catch (Throwable t) {
            return DeliveryPolicy.Urgency.NORMAL;
        }
    }
}
