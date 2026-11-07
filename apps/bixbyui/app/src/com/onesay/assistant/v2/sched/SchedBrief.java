package com.onesay.assistant.v2.sched;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * [sched] 晨间播报里的「今日日程」（纯 Java）。OneSay 自己的每日播报（DailyDigest）和发给晨间播报 v2 的「我的事」（MyThingsExport.events）共用。
 * 每条「上午9点 晨会」「全天 交报告」；已经结束的不念；全天的排前面。
 */
public final class SchedBrief {

    private SchedBrief() {}

    public static List<String> todayLines(SchedEngine e, long now) {
        TimeZone tz = e.tz();
        long d = CnTime.dayStart(now, tz);
        List<String> l = new ArrayList<String>();
        for (SchedRecur.Occ o : e.occurrences(d, CnTime.addDays(d, 1, tz))) {
            if (!o.event.allDay && o.end <= now) continue;
            l.add((o.event.allDay ? "全天" : SchedFormat.clockWord(o.start, tz)) + " " + o.event.title);
        }
        return l;
    }
}
