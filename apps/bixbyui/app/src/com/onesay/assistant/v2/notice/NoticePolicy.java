package com.onesay.assistant.v2.notice;

import com.onesay.assistant.v2.callback.DeliveryPolicy;

import java.util.Calendar;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * 提醒的确定性规则（纯函数，JVM 可测）。免打扰 / 通话中 / 每日上限都沿用 A 线：
 * <ul>
 *   <li><b>免打扰时段</b>（A 线同一组设置 bixby_callback/quiet_on、quiet_start、quiet_end，默认 23:00–07:00）：提醒先压着（PENDING），
 *       时段结束再放出来；没等到就过期的静默丢掉。<b>例外</b>：高优先级的日程（{@link #quietExempt}，开关 {@link Env#quietHighCalendar}，默认开）。</li>
 *   <li><b>通话中 / 勿扰模式</b>：照样排进岛上队列，但不自动展开（「静音」）——提醒本身从不出声，TTS 仍只归回调中枢（DeliveryPolicy）。</li>
 *   <li><b>每日上限</b>：按来源、按本机时区自然日计「出现在岛上」的次数（与 A 线事件任务 dailyCap 同一口径），超了这条记 SUPPRESSED。</li>
 * </ul>
 */
public final class NoticePolicy {

    private NoticePolicy() {}

    public static final Map<IslandNotice.Source, Integer> DEFAULT_CAPS = new EnumMap<IslandNotice.Source, Integer>(IslandNotice.Source.class);
    static {
        DEFAULT_CAPS.put(IslandNotice.Source.CALLBACK, 30);
        DEFAULT_CAPS.put(IslandNotice.Source.CALENDAR, 30);
        DEFAULT_CAPS.put(IslandNotice.Source.MAIL, 20);
        // 扩展来源沿用 A 线事件任务的默认每天上限（EventTask.DEFAULT_DAILY_CAP = 5）
        DEFAULT_CAPS.put(IslandNotice.Source.EXTERNAL, 5);
        // 新来源：状态型（麦克风 / 相机、通话）次数多但每次都是真实发生的事，上限放宽；系统状态最容易刷屏，收紧
        DEFAULT_CAPS.put(IslandNotice.Source.PRIVACY, 60);
        DEFAULT_CAPS.put(IslandNotice.Source.CALL, 40);
        DEFAULT_CAPS.put(IslandNotice.Source.NOTIFY, 40);
        DEFAULT_CAPS.put(IslandNotice.Source.SYSTEM, 20);
        DEFAULT_CAPS.put(IslandNotice.Source.ERRAND, 30);
        // ★ feat/rain-minutely：每一阵雨的「开始 / 停」各算一条；下下停停的日子也够用
        DEFAULT_CAPS.put(IslandNotice.Source.WEATHER, 16);
    }

    /** 各来源默认过期（提醒自己没给 expireAt 时）。 */
    public static long defaultTtl(IslandNotice.Source s) {
        switch (s) {
            case CALENDAR: return 30L * 60000L;
            case MAIL: return 60L * 60000L;
            // 状态型：状态结束时由来源撤下；这里只是兜底（来源丢了「结束」事件也不会永远挂在岛上）
            case PRIVACY: return 12L * 3600000L;
            case CALL: return 12L * 3600000L;
            case NOTIFY: return 30L * 60000L;
            case SYSTEM: return 15L * 1000L;
            case ERRAND: return 3L * 3600000L;
            case WEATHER: return 70L * 60000L;
            default: return 2L * 3600000L;
        }
    }

    /** 送达时刻的环境。 */
    public static final class Env {
        public boolean master = true;
        public Set<IslandNotice.Source> disabled = EnumSet.noneOf(IslandNotice.Source.class);
        public Set<String> disabledTags = new java.util.HashSet<String>();
        public boolean inCall, dnd;
        public boolean quietEnabled = true;
        public int quietStartMin = DeliveryPolicy.QUIET_START_DEFAULT;
        public int quietEndMin = DeliveryPolicy.QUIET_END_DEFAULT;
        /** 高优先级日程不受免打扰时段限制（可配置，默认开）。 */
        public boolean quietHighCalendar = true;
        public Map<IslandNotice.Source, Integer> caps = new EnumMap<IslandNotice.Source, Integer>(DEFAULT_CAPS);
        public TimeZone tz = TimeZone.getDefault();
        /**
         *：免打扰时段照常显示的来源（可配置；默认「麦克风 / 相机在用」和「通话」——一个是安全提示，一个是用户自己在打电话，压到早上没有意义）。
         */
        public Set<IslandNotice.Source> quietExemptSources = EnumSet.of(IslandNotice.Source.PRIVACY, IslandNotice.Source.CALL);
        /** 调试注入：强制当作在免打扰时段里 / 外（null = 按时钟）。 */
        public Boolean quietOverride;
    }

    public static boolean sourceOn(Env env, IslandNotice n) {
        if (env == null) return true;
        if (!env.master) return false;
        if (env.disabled.contains(n.source)) return false;
        return n.sourceTag == null || !env.disabledTags.contains(n.sourceTag);
    }

    /** 某个来源（kind + 扩展 id）此刻开着没有（总开关 + 来源开关）。 */
    public static boolean sourceOn(Env env, IslandNotice.Source kind, String tag) {
        if (env == null) return true;
        if (!env.master) return false;
        if (env.disabled.contains(kind)) return false;
        return tag == null || !env.disabledTags.contains(tag);
    }

    public static boolean inQuiet(Env env, long now) {
        if (env == null || !env.quietEnabled) return false;
        if (env.quietOverride != null) return env.quietOverride.booleanValue();
        return DeliveryPolicy.inQuietHours(DeliveryPolicy.minuteOfDay(now, env.tz), env.quietStartMin, env.quietEndMin);
    }

    /** 高优先级日程在免打扰时段照常出现（开关关掉则也压着）。 */
    public static boolean quietExempt(Env env, IslandNotice n) {
        if (env == null) return false;
        // 未接来电不算「正在打电话」：跟普通提醒一样压到免打扰结束
        if (env.quietExemptSources != null && env.quietExemptSources.contains(n.source) && !n.dedupKey.startsWith("call:miss:")) return true;
        return env.quietHighCalendar && n.source == IslandNotice.Source.CALENDAR && n.priority == IslandNotice.Priority.HIGH;
    }

    /** 免打扰时段在 now 之后的结束时刻。 */
    public static long quietEndAt(Env env, long now) {
        if (env != null && env.quietOverride != null) return env.quietOverride ? now + 10L * 60000L : now;
        TimeZone tz = env == null ? TimeZone.getDefault() : env.tz;
        int end = env == null ? DeliveryPolicy.QUIET_END_DEFAULT : env.quietEndMin;
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, end / 60);
        c.set(Calendar.MINUTE, end % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** 静音（通话中 / 勿扰）：照样排队，不自动展开。 */
    public static boolean silent(Env env) {
        return env != null && (env.inCall || env.dnd);
    }

    public static int cap(Env env, IslandNotice.Source s) {
        Integer v = env == null ? DEFAULT_CAPS.get(s) : env.caps.get(s);
        return v == null ? 30 : v;
    }

    public static long dayStart(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 排序：优先级高的在前；同级先来的在前（头部稳定，不会被后来的同级挤掉）。 */
    public static final java.util.Comparator<IslandNotice> ORDER = new java.util.Comparator<IslandNotice>() {
        @Override public int compare(IslandNotice a, IslandNotice b) {
            if (a.priority != b.priority) return a.priority.ordinal() > b.priority.ordinal() ? -1 : 1;
            long ta = a.shownAt > 0 ? a.shownAt : a.showAt, tb = b.shownAt > 0 ? b.shownAt : b.showAt;
            if (ta != tb) return ta < tb ? -1 : 1;
            return a.dedupKey.compareTo(b.dedupKey);
        }
    };

    /**
     * BUG-I1（2026-10-23 收窄）：连接器的哪种失败算「来源不可用」→ 撤下日程提醒。
     * 只有没配置 / 没登录（NOT_CONFIGURED）和授权失效（AUTH_REVOKED）；网络不可达、超时、远端错误、限流等都是「这次读不到」，
     * 保留已有提醒、2 分钟后照常重试。
     */
    public static boolean connectorLost(com.onesay.assistant.v2.conn.ConnResult.Code code) {
        return code == com.onesay.assistant.v2.conn.ConnResult.Code.NOT_CONFIGURED || code == com.onesay.assistant.v2.conn.ConnResult.Code.AUTH_REVOKED;
    }
}
