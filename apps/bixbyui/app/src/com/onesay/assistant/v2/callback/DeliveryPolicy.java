package com.onesay.assistant.v2.callback;

import java.util.Calendar;
import java.util.EnumSet;
import java.util.Set;
import java.util.TimeZone;

/**
 * 回调送达规则（确定性规则，不交给大模型；lane-a 第 3 项）。纯 Java，JVM 可测。
 *
 * <h3>渠道</h3> 灵动岛 / TTS / 通知。通知永远留一条（追溯），岛 / TTS 按场景加。
 *
 * <h3>什么时候不出声</h3>
 * 通话中、勿扰模式开着（zen_mode ≠ 0）、免打扰时段（默认 23:00–07:00，可配置，跨午夜）→ <b>去掉 TTS</b>；
 * 媒体正在播 → 不念（改岛）。用户刚亲口要的（{@link Urgency#USER_ASKED}）也遵守通话 / 勿扰，只放宽免打扰时段。
 *
 * <h3>紧急度</h3>
 * <ul>
 *   <li>HIGH（到点提醒、要确认）→ 岛 + 通知，可出声时加 TTS；</li>
 *   <li>NORMAL（定时任务结果、播报模板）→ 岛 + 通知，可出声且任务要求播报时加 TTS；</li>
 *   <li>LOW（无变化 / 一切正常）→ 只通知历史，不打扰。</li>
 * </ul>
 */
public final class DeliveryPolicy {

    private DeliveryPolicy() {}

    public enum Channel { ISLAND, TTS, NOTIFY }

    public enum Urgency { LOW, NORMAL, HIGH, USER_ASKED }

    /** 默认免打扰时段（分钟数，0:00 起算）。 */
    public static final int QUIET_START_DEFAULT = 23 * 60;
    public static final int QUIET_END_DEFAULT = 7 * 60;

    /** 送达时刻的环境。 */
    public static final class Env {
        public boolean inCall;
        public boolean dnd;
        public boolean mediaPlaying;
        public boolean islandReady = true;
        public int quietStartMin = QUIET_START_DEFAULT;
        public int quietEndMin = QUIET_END_DEFAULT;
        public boolean quietEnabled = true;
    }

    /** 免打扰时段判断（start == end 视为关闭；start &gt; end 跨午夜）。 */
    public static boolean inQuietHours(int minuteOfDay, int startMin, int endMin) {
        if (startMin == endMin) return false;
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        if (startMin < endMin) return m >= startMin && m < endMin;
        return m >= startMin || m < endMin;
    }

    public static int minuteOfDay(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        c.setTimeInMillis(now);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** 此刻能否出声（纯函数）。 */
    public static boolean maySpeak(Env env, Urgency u, int minuteOfDay) {
        if (env == null) return false;
        if (env.inCall || env.dnd || env.mediaPlaying) return false;
        if (u == Urgency.USER_ASKED) return true;
        return !(env.quietEnabled && inQuietHours(minuteOfDay, env.quietStartMin, env.quietEndMin));
    }

    /**
     * 选渠道。
     *
     * @param wantSpeak 任务本身要求播报（如「每天播报今日待办」）
     */
    public static Set<Channel> channels(Urgency u, boolean wantSpeak, Env env, int minuteOfDay) {
        Set<Channel> s = EnumSet.of(Channel.NOTIFY);
        if (u == Urgency.LOW) return s;
        if (env == null || env.islandReady) s.add(Channel.ISLAND);
        boolean speak = (wantSpeak || u == Urgency.HIGH || u == Urgency.USER_ASKED) && maySpeak(env, u, minuteOfDay);
        if (speak) s.add(Channel.TTS);
        return s;
    }

    /** 「HH:MM」→ 分钟数；读不出返回 def。 */
    public static int parseHm(String s, int def) {
        if (s == null) return def;
        String t = s.trim().replace('：', ':');
        int i = t.indexOf(':');
        try {
            int h = Integer.parseInt(i < 0 ? t : t.substring(0, i));
            int m = i < 0 ? 0 : Integer.parseInt(t.substring(i + 1));
            if (h < 0 || h > 24 || m < 0 || m > 59 || (h == 24 && m != 0)) return def;
            return (h * 60 + m) % 1440;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static String formatHm(int min) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", (min / 60) % 24, min % 60);
    }
}
