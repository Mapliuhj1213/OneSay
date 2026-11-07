package com.onesay.assistant.v2.soul;

import java.util.Calendar;
import java.util.TimeZone;

/** [soul] 单测公共：固定时区、时钟工具、内存仓库。 */
abstract class SoulTestBase {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static final long DAY = SoulEngine.DAY;

    static long t(int month, int day, int h, int m) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, month - 1, day, h, m, 0);
        return c.getTimeInMillis();
    }

    static final long T0 = t(10, 30, 9, 0);

    InMemorySoulRepo repo = new InMemorySoulRepo();
    SoulEngine en = new SoulEngine(repo, TZ);

    Claim core(String text, long at) {
        SoulEngine.WriteResult w = en.remember(text, Claim.SRC_USER, text, "s1", false, "", "", "", at);
        if (!w.ok) throw new AssertionError("remember failed: " + w.reason);
        return w.claim;
    }

    int activeCount(String table) {
        return en.active(table).size();
    }
}
