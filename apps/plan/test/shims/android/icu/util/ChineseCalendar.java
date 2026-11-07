package android.icu.util;

/**
 * JVM 单测垫片：android.jar 里的 android.icu.util.ChineseCalendar 是只会抛 "Stub!" 的桩，这里转给 icu4j（同一套 ICU 算法与字段常量）。
 * 只实现 sched/Lunar 用到的几个方法（字段常量在编译期已内联，与 icu4j 数值相同）。
 */
public class ChineseCalendar {

    private final com.ibm.icu.util.ChineseCalendar c = new com.ibm.icu.util.ChineseCalendar();

    public ChineseCalendar() {}

    public final void clear() {
        c.clear();
    }

    public final void set(int field, int value) {
        c.set(field, value);
    }

    public final int get(int field) {
        return c.get(field);
    }

    public long getTimeInMillis() {
        return c.getTimeInMillis();
    }

    public void setTimeInMillis(long ms) {
        c.setTimeInMillis(ms);
    }
}
