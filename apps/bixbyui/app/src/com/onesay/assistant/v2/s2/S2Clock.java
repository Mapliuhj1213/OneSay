package com.onesay.assistant.v2.s2;

/**
 * [s2] S2 的时钟（纯 Java）。生产 = 系统时间；debug 包可以加一个偏移量模拟「隔天」
 * （{@code --es s2 clock --es text +1d}），验收“记一下车停在 B2-17 → 隔天问我车停哪了”时不用真的等一天。
 *
 * <p>偏移只影响 S2 自己（写入时间戳、相对日期「昨天/今天」、账本的「本周/这个月」），不影响系统其它部分。
 */
public final class S2Clock {

    private S2Clock() {}

    private static volatile long sOffsetMs = 0L;

    public static long now() {
        return System.currentTimeMillis() + sOffsetMs;
    }

    /** 调试：设置偏移（毫秒，可为负）；0 = 恢复真实时间。 */
    public static void setOffsetMs(long offset) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        sOffsetMs = offset;
    }

    public static long offsetMs() {
        return sOffsetMs;
    }

    /**
     * 解析调试偏移写法：{@code +1d} / {@code -2d} / {@code +3h} / {@code 0} / 纯数字毫秒。解析不了返回 null。
     */
    public static Long parseOffset(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.length() == 0) return null;
        try {
            long unit = 1L;
            if (t.endsWith("d")) { unit = 24L * 3600L * 1000L; t = t.substring(0, t.length() - 1); }
            else if (t.endsWith("h")) { unit = 3600L * 1000L; t = t.substring(0, t.length() - 1); }
            else if (t.endsWith("m")) { unit = 60L * 1000L; t = t.substring(0, t.length() - 1); }
            if (t.startsWith("+")) t = t.substring(1);
            return Long.valueOf(Long.parseLong(t) * unit);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
