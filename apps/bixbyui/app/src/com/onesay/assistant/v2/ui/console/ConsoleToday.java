package com.onesay.assistant.v2.ui.console;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * ★ feat/console-ui（设计稿 ①「今天」块）：闹钟 / 待办 / 电脑任务三行。纯 Java，JVM 可测。
 *
 * <p>只放真数据：读不到（null）或是零的行不显示；三行都没有 → 整块不显示（{@link #rows} 返回空表）。绝不放示例数据。
 */
public final class ConsoleToday {

    private ConsoleToday() {}

    /** 下一个闹钟多久以内才放进「今天」（超过一天的不算今天的事）。 */
    public static final long ALARM_WINDOW_MS = 24L * 3600_000L;

    public static final class Row {
        public final String label;
        public final String value;

        Row(String label, String value) {
            this.label = label;
            this.value = value;
        }

        @Override public String toString() {
            return label + "=" + value;
        }
    }

    /** 页面在后台线程里攒好的真数据；null = 读不到。 */
    public static final class Input {
        /** 系统里下一个闹钟的响铃时刻（毫秒）；没有 = null。 */
        public Long nextAlarmAt;
        /** 今天要做（或没定日子）还没做完的待办件数。 */
        public Integer openTodos;
        /** 交给电脑、还在做的任务件数。 */
        public Integer pcRunning;
    }

    public static List<Row> rows(Input in, long now, TimeZone tz) {
        List<Row> out = new ArrayList<Row>();
        if (in == null) return out;
        String a = alarm(in.nextAlarmAt, now, tz);
        if (a != null) out.add(new Row("闹钟", a));
        if (in.openTodos != null && in.openTodos > 0) out.add(new Row("待办", in.openTodos + " 件没做"));
        if (in.pcRunning != null && in.pcRunning > 0) out.add(new Row("电脑任务", in.pcRunning + " 件进行中"));
        return out;
    }

    /** 「今天 07:00」/「明天 07:00」；已经过了或一天以后的 → null（不显示）。 */
    static String alarm(Long at, long now, TimeZone tz) {
        if (at == null || at <= now || at - now > ALARM_WINDOW_MS) return null;
        Calendar n = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        n.setTimeInMillis(now);
        Calendar c = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        c.setTimeInMillis(at);
        boolean sameDay = n.get(Calendar.YEAR) == c.get(Calendar.YEAR) && n.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR);
        return (sameDay ? "今天 " : "明天 ") + two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE));
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
