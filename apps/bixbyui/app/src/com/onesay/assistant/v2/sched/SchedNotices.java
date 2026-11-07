package com.onesay.assistant.v2.sched;

import com.onesay.assistant.v2.notice.IslandNotice;
import com.onesay.assistant.v2.notice.NoticeAction;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * [sched] 本地日程 → 灵动岛提醒（纯 Java，JVM 可测）。上岛、排队、免打扰 / 通话中 / 每日上限都交给 NoticeCenter（来源 kind = CALENDAR，
 * 所以控制台「日程」来源开关、免打扰时段、「免打扰时也提醒重要日程」对本地日程同样生效）。
 *
 * <ul>
 *   <li>定时日程：开始前 lead 分钟（每条可单独设，默认跟设置）出现；开始后 15 分钟或结束时（取早的）过期；已开始超过 5 分钟的不再提醒；</li>
 *   <li>全天日程：当天 {@link #ALLDAY_HOUR} 点出现，中午 12 点过期；</li>
 *   <li>「不提醒」的日程不出；</li>
 *   <li>去重键 {@code sched:<id>:<这次开始时刻>}：改了时间就是新提醒，旧的由 NoticeCenter.sync 撤掉；</li>
 *   <li>优先级：标题像赶点的事（航班 / 高铁 / 面试 / 考试 / 医院…）→ HIGH，其余 NORMAL（与 Google 日程同一套词）。</li>
 * </ul>
 */
public final class SchedNotices {

    private SchedNotices() {}

    public static final String TAG = "sched";
    /** 控制台「日程」列表页 id（提醒上的「查看」打开它）。 */
    public static final String PAGE_LIST = "sched.list";
    public static final int ALLDAY_HOUR = 8;
    static final long STARTED_GRACE_MS = 5L * 60000L;
    /** 提前量最大 1 天：拉取窗口要覆盖到它。 */
    public static final long WINDOW_MS = 26L * 3600000L;

    static final Pattern URGENT = Pattern.compile("航班|飞机|登机|高铁|火车|动车|面试|考试|开考|接机|接人|出发|医院|挂号|手术|答辩|签约");

    public static String key(SchedRecur.Occ o) {
        return "sched:" + o.event.id + ":" + o.start;
    }

    public static IslandNotice toNotice(SchedRecur.Occ o, int lead, long now, TimeZone tz) {
        if (o == null || lead == CnTime.LEAD_NONE) return null;
        SchedEvent e = o.event;
        long showAt, expire;
        if (e.allDay) {
            showAt = o.start + ALLDAY_HOUR * 3600000L;
            expire = o.start + 12L * 3600000L;
        } else {
            if (o.start < now - STARTED_GRACE_MS) return null;
            showAt = o.start - Math.max(0, Math.min(1440, lead)) * 60000L;
            long end = o.end > o.start ? o.end : o.start + 30L * 60000L;
            expire = Math.min(end, o.start + 15L * 60000L);
        }
        if (expire <= now) return null;
        String when = e.allDay ? "今天 · 全天" : SchedFormat.hm(o.start, e.endAt > e.startAt ? o.end : 0, false, tz);
        String loc = e.location == null || e.location.length() == 0 ? "" : " · " + e.location;
        IslandNotice.Priority p = URGENT.matcher(e.title).find() ? IslandNotice.Priority.HIGH : IslandNotice.Priority.NORMAL;
        IslandNotice n = new IslandNotice(IslandNotice.Source.CALENDAR, key(o), p, e.title, when + loc);
        n.sourceTag = TAG;
        n.showAt = showAt;
        n.expireAt = expire;
        n.action(new NoticeAction("查看日程", NoticeAction.Kind.OPEN_PAGE, PAGE_LIST));
        n.action(new NoticeAction("知道了", NoticeAction.Kind.DISMISS, ""));
        return n;
    }

    /** 这一批该有的提醒（NoticeCenter 会撤掉批里没有、还没处理的旧提醒）。 */
    public static List<IslandNotice> plan(SchedEngine engine, long now) {
        List<IslandNotice> l = new ArrayList<IslandNotice>();
        TimeZone tz = engine.tz();
        long from = CnTime.dayStart(now, tz);
        for (SchedRecur.Occ o : engine.occurrences(from, now + WINDOW_MS)) {
            IslandNotice n = toNotice(o, engine.effectiveLead(o.event), now, tz);
            if (n != null) l.add(n);
        }
        return l;
    }
}
