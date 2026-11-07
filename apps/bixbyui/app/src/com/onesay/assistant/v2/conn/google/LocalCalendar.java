package com.onesay.assistant.v2.conn.google;

import java.util.List;

/**
 * [conn/google] 本机日历（连不上 Google 时的退路）。真机实现 {@link AndroidLocalCalendar}：
 * 读 = CalendarContract.Instances（需要 READ_CALENDAR，清单里已有，运行时没授权就如实报错）；
 * 写 = 打开系统日历的「新建日程」页并预填（不需要写日历权限，用户在日历 App 里点保存才落地）。
 */
public interface LocalCalendar {

    final class Event {
        public final String title;
        public final long begin;
        public final long end;
        public final boolean allDay;
        public final String location;

        public Event(String title, long begin, long end, boolean allDay, String location) {
            this.title = title == null || title.length() == 0 ? "(无标题)" : title;
            this.begin = begin;
            this.end = end;
            this.allDay = allDay;
            this.location = location == null ? "" : location;
        }
    }

    /** [from, to) 内的日程（按开始时间）。失败抛异常（SecurityException = 没给日历权限）。 */
    List<Event> events(long from, long to) throws Exception;

    /** 打开系统日历新建页并预填。@return 是否成功拉起 */
    boolean openInsert(String title, long begin, long end, boolean allDay, String location, String description);
}
