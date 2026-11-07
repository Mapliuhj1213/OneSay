package com.onesay.clock.core;

import java.util.Calendar;

/**
 * 响铃页的纯逻辑（无 Android 依赖，JVM 可测；移植自 v2 ac0e72b，天气图标改由 WeatherCode.icon 给）—— 数值与规则取自三星时钟 10.1.20.13：
 *   贪睡 −/+：AlarmItem.getCalculatedDur（步进 5 分钟，下限 5、上限 60；到 5 时 − 置灰、到 60 时 + 置灰，图标透明度 0.2）；
 *   文案：values-zh-rCN plurals snooze_in_mins「%d 分钟后提醒」、alarm_alert_date_format「MMM d日, EEE」、clock_24_hours_format「kk:mm」；
 *   天气图标：AlarmWeatherUtil.setWeatherImg 的 level-list（白天 / 夜晚两套，夜里晴、热都换成月亮 clear）；
 *   背景呼吸：sec.color.gradient.view.RadialGradientView（入场 2000 ms、gradient1 延迟 400 ms，之后 4000 ms 1→0→1 往复，EasingSine）；
 *   停止圆环光晕：AlertSlidingTabHandle.makeHandleAnimation（缩放 0.6→1 / 2000 ms SineInOut70，透明度 1→0 / 起点 1000 ms、1000 ms SineInOut33）。
 */
public final class AlertUi {
    private AlertUi() { }

    public static final int SNOOZE_MIN = 5, SNOOZE_MAX = 60, SNOOZE_STEP = 5;

    /** 闹钟设置的贪睡分钟 → 响铃页起始值（原版取自 5/10/15/20/25/30 表；这里夹到 [5, 60]）。 */
    public static int snoozeStart(int min) {
        if (min < SNOOZE_MIN) return SNOOZE_MIN;
        if (min > SNOOZE_MAX) return SNOOZE_MAX;
        return min;
    }

    /** 原版 AlarmItem.getCalculatedDur：inc=true 为「+」。逐行照搬（含 5 只能加、60 只能减的两个分支）。 */
    public static int snoozeStep(int d, boolean inc) {
        if (d > SNOOZE_MIN && d < SNOOZE_MAX) d = inc ? d + SNOOZE_STEP : d - SNOOZE_STEP;
        if (d == SNOOZE_MIN && inc) d += SNOOZE_STEP;
        if (d == SNOOZE_MAX && !inc) d -= SNOOZE_STEP;
        return d;
    }

    public static boolean minusEnabled(int d) { return d != SNOOZE_MIN; }

    public static boolean plusEnabled(int d) { return d != SNOOZE_MAX; }

    public static String snoozeLabel(int d) { return d + " 分钟后提醒"; }

    public static final String[] WEEK = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    /** 「MMM d日, EEE」在 zh-CN 下 → 「6月 14日, 周日」（视频里月与日之间也有空格，逗号为半角加空格）。 */
    public static String dateLabel(Calendar c) {
        return (c.get(Calendar.MONTH) + 1) + "月 " + c.get(Calendar.DAY_OF_MONTH) + "日, " + WEEK[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    /** 24 小时制「kk:mm」（Android DateFormat 的 k 与 H 同为 0–23，两位补零）。 */
    public static String timeLabel(Calendar c) {
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    public static String alarmName(String label) {
        return label == null || label.trim().length() == 0 ? "闹钟" : label.trim();
    }

    public static String cpName(String newsSource) {
        return newsSource == null || newsSource.trim().length() == 0 ? "网络" : newsSource.trim();   // 开源版：缺省不写具体厂商
    }

    // ------------------------------------------------------------------ 动画曲线
    /** 三次贝塞尔缓动（与 android.view.animation.PathInterpolator(x1,y1,x2,y2) 同曲线）。 */
    public static float bezier(float x1, float y1, float x2, float y2, float x) {
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
        float lo = 0f, hi = 1f, t = x;
        for (int i = 0; i < 40; i++) {
            t = (lo + hi) / 2f;
            float bx = cub(x1, x2, t);
            if (bx < x) lo = t; else hi = t;
        }
        return cub(y1, y2, t);
    }

    private static float cub(float p1, float p2, float t) {
        float u = 1f - t;
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t;
    }

    public static float sineInOut70(float x) { return bezier(0.33f, 0f, 0.3f, 1f, x); }

    public static float sineInOut33(float x) { return bezier(0.33f, 0f, 0.67f, 1f, x); }

    /** EasingSineFunc.easeInOut(f, 0, 1, 1)。 */
    public static float easeSine(float f) { return (float) (-0.5 * (Math.cos(f * Math.PI) - 1.0)); }

    /** 停止圆环光晕 {缩放, 透明度}：周期 2000 ms。 */
    public static float[] halo(long t) {
        long p = ((t % 2000L) + 2000L) % 2000L;
        float s = 0.6f + 0.4f * sineInOut70(p / 2000f);
        float a = p < 1000L ? 1f : 1f - sineInOut33((p - 1000L) / 1000f);
        return new float[]{s, a};
    }

    public static final int BG_START_MS = 2000, BG_DELAY1_MS = 400, BG_REPEAT_MS = 4000;

    /**
     * 背景三层光晕在 t 毫秒时的 {gradient1 缩放系数, gradient1 显现度, gradient3 缩放系数, gradient3 显现度}。
     * 缩放系数乘在各层 scale（1.3 / 2.0）上；显现度乘在层透明度上。
     * 入场：系数 0.5 + 0.7·a（a 由 0 缓到 1）；之后往复：0.85 + 0.35·e，e = easeSine(1→0→1，4000 ms)，范围 0.85–1.2。
     */
    public static float[] breath(long t) {
        if (t < 0) t = 0;
        float a3 = easeSine(Math.min(1f, t / (float) BG_START_MS));
        long t1 = t - BG_DELAY1_MS;
        float a1 = easeSine(Math.max(0f, Math.min(1f, t1 / (float) BG_START_MS)));
        if (t1 < BG_START_MS) return new float[]{0.5f + 0.7f * a1, a1, 0.5f + 0.7f * a3, a3};
        long r = (t1 - BG_START_MS) % BG_REPEAT_MS;
        float v = r < BG_REPEAT_MS / 2 ? 1f - r / (BG_REPEAT_MS / 2f) : (r - BG_REPEAT_MS / 2f) / (BG_REPEAT_MS / 2f);
        float k = 0.85f + 0.35f * easeSine(v);
        return new float[]{k, 1f, k, 1f};
    }

    /** 停止：拖动距离 / 圆直径 ≥ 0.95 即解除（AlertSlidingTabHandle.isThresholdReached）。 */
    public static boolean dismissReached(double dist, double diameter) { return dist / diameter >= 0.95; }

    /** 按下判定：距圆心 / 直径 < 1（AlertSlidingTabHandle.isInCircle —— 注意是直径，不是半径）。 */
    public static boolean inHandle(double dist, double diameter) { return dist / diameter < 1.0; }
}
