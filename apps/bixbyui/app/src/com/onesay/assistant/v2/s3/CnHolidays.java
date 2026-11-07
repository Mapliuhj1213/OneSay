package com.onesay.assistant.v2.s3;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * [s3] 中国法定节假日与调休表（用户 2026-10-09 14:25 拍板 D-1d = B）。纯 Java，JVM 可测。
 *
 * <p>「工作日」定时任务 = 表里的上班日：调休上班的周六 / 周日<b>要</b>触发，放假（含调休放假）的周一至周五<b>不</b>触发。
 * 表外年份（还没录入的年份）退回「周一到周五」，并打一条 {@code [S3] 节假日表未收录 YYYY 年} 日志（每年只打一次）。
 *
 * <h3>每年怎么更新（写进）</h3>
 * 国务院办公厅每年 11 月前后发《关于 YYYY 年部分节假日安排的通知》（中国政府网 gov.cn）。照通知把
 * 「放假」日期加进 {@link #OFF}、「上班」日期加进 {@link #ON}，把年份加进 {@link #YEARS}，跑 {@code CnHolidaysTest} 即可。
 *
 * <p>2026 年数据来源：国办发明电〔2025〕7 号（2025-11-04，https://www.gov.cn/zhengce/content/202511/content_7047090.htm）。
 * 2027 年的通知截至 2026-10-09 尚未发布，所以没有收录。
 */
public final class CnHolidays {

    private CnHolidays() {}

    /** 已收录的年份。 */
    static final int[] YEARS = {2026};

    /** 放假日（含调休放假），yyyyMMdd。 */
    static final int[] OFF = {
            // 元旦 1/1–1/3
            20260101, 20260102, 20260103,
            // 春节 2/15–2/23
            20260215, 20260216, 20260217, 20260218, 20260219, 20260220, 20260221, 20260222, 20260223,
            // 清明 4/4–4/6
            20260404, 20260405, 20260406,
            // 劳动节 5/1–5/5
            20260501, 20260502, 20260503, 20260504, 20260505,
            // 端午 6/19–6/21
            20260619, 20260620, 20260621,
            // 中秋 9/25–9/27
            20260925, 20260926, 20260927,
            // 国庆 10/1–10/7
            20261001, 20261002, 20261003, 20261004, 20261005, 20261006, 20261007,
    };

    /** 调休上班日（周末上班），yyyyMMdd。 */
    static final int[] ON = {
            20260104,              // 元旦：1/4 周日上班
            20260214, 20260228,    // 春节：2/14 周六、2/28 周六上班
            20260509,              // 劳动节：5/9 周六上班
            20260920, 20261010,    // 国庆：9/20 周日、10/10 周六上班
    };

    private static final Set<Integer> OFF_SET = toSet(OFF);
    private static final Set<Integer> ON_SET = toSet(ON);
    private static final Set<Integer> YEAR_SET = toSet(YEARS);
    private static final Set<Integer> sWarned = new HashSet<Integer>();

    private static Set<Integer> toSet(int[] a) {
        Set<Integer> s = new HashSet<Integer>();
        for (int x : a) s.add(x);
        return s;
    }

    /** 这一年在表里吗。 */
    public static boolean covers(int year) {
        return YEAR_SET.contains(year);
    }

    /** {@code c} 所在日期是不是工作日（按表；表外年份 = 周一到周五）。 */
    public static boolean isWorkday(Calendar c) {
        int y = c.get(Calendar.YEAR);
        int ymd = y * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        boolean weekday = dow != Calendar.SATURDAY && dow != Calendar.SUNDAY;
        if (!covers(y)) {
            warnOnce(y);
            return weekday;
        }
        if (ON_SET.contains(ymd)) return true;
        if (OFF_SET.contains(ymd)) return false;
        return weekday;
    }

    private static void warnOnce(int year) {
        synchronized (sWarned) {
            if (!sWarned.add(year)) return;
        }
        try {
            android.util.Log.w("S3", "[S3] 节假日表未收录 " + year + " 年：「工作日」按周一到周五算（更新方法）");
        } catch (Throwable ignored) {
        }
    }
}
