package com.onesay.assistant.v2.sched;

import android.content.Context;

import com.onesay.assistant.v2.conn.google.AndroidLocalCalendar;
import com.onesay.assistant.v2.conn.google.LocalCalendar;

import java.util.List;

/**
 * [sched] 可选：从系统日历（CalendarContract.Instances）<b>只读</b>导入未来 60 天的日程，存成本地副本（source = import）。
 * <ul>
 *   <li>不写系统日历、不要写日历权限；只要 READ_CALENDAR（清单里已有，控制台点「导入」时运行时申请）；</li>
 *   <li>副本默认「不提醒」（系统日历自己会提醒，避免同一件事提醒两次），但会出现在列表、语音查询和晨间播报里；</li>
 *   <li>按「开始时刻 + 标题」去重：重复导入不会多出来；副本在 OneSay 里只能删，不能改。</li>
 * </ul>
 */
public final class SchedImport {

    private SchedImport() {}

    static final long WINDOW_MS = 60L * 86400000L;

    /** 后台线程。返回给人看的结果。 */
    public static String run(Context ctx, SchedEngine e, long now) {
        List<LocalCalendar.Event> l;
        try {
            l = new AndroidLocalCalendar(ctx).events(CnTime.dayStart(now, e.tz()), now + WINDOW_MS);
        } catch (SecurityException se) {
            return "没有读取日历的权限，导入不了";
        } catch (Throwable t) {
            return "系统日历暂时读不到";
        }
        int added = 0, same = 0;
        for (LocalCalendar.Event x : l) {
            String ext = extId(x.begin, x.title);
            if (e.store().byExtId(ext) != null) {
                same++;
                continue;
            }
            e.store().insert(copyOf(x, ext, now, e));
            added++;
        }
        return l.isEmpty() ? "系统日历里未来 60 天没有日程" : "导入了 " + added + " 个日程" + (same > 0 ? "（" + same + " 个之前导入过）" : "");
    }

    static String extId(long begin, String title) {
        return "cal:" + begin + ":" + Integer.toHexString((title == null ? "" : title).hashCode());
    }

    static SchedEvent copyOf(LocalCalendar.Event x, String ext, long now, SchedEngine e) {
        SchedEvent d = new SchedEvent();
        d.title = SchedEngine.clip(x.title, 40);
        d.allDay = x.allDay;
        d.startAt = x.begin;
        if (x.allDay) {
            // 系统日历的全天日程以 UTC 0 点存：换成本机那天的 0 点
            java.util.Calendar u = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            u.setTimeInMillis(x.begin);
            d.startAt = CnTime.ymd(u.get(java.util.Calendar.YEAR), u.get(java.util.Calendar.MONTH) + 1, u.get(java.util.Calendar.DAY_OF_MONTH), e.tz());
        }
        d.endAt = x.allDay || x.end <= x.begin ? 0 : x.end;
        d.location = SchedEngine.clip(x.location, 40);
        d.leadMin = CnTime.LEAD_NONE;
        d.source = "import";
        d.extId = ext;
        d.createdAt = now;
        d.updatedAt = now;
        return d;
    }
}
