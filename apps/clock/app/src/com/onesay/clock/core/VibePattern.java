package com.onesay.clock.core;

/**
 * 响铃震动波形：对齐三星时钟（Note8 / Android 8）。
 * 三星时钟闹钟震动跟随「振动模式」设置：Settings.System default_vibration_pattern 指向 PersonalVibrationProvider 的一行，
 * 行里 vibration_pattern 是 effect id（默认 Basic call = 50035）；id → 框架资源 config_vibeCommon + ('A' + id - 50025)，
 * Basic call = config_vibeCommonK = [0, 1500, 2000]（v1 RingVibe 2026-09-29 实测：三星设置预览时 dumpsys vibrator 记下同一值）。
 * 三星闹钟：从下标 0 无限重复，直到停止/贪睡；幅度用系统默认。运行时读不到系统设置就用 Basic call。
 */
public final class VibePattern {
    private VibePattern() { }

    public static final int ID_BASE = 50025, ID_MIN = 50025, ID_MAX = 50045;
    public static final int BASIC_CALL_ID = 50035;
    /** 三星「Basic call」（config_vibeCommonK）。 */
    public static final long[] BASIC_CALL = {0L, 1500L, 2000L};
    /** 重复起点：整段循环。 */
    public static final int REPEAT = 0;

    /** effect id → 框架 int-array 资源名；超出范围 → null。 */
    public static String resName(int id) {
        if (id < ID_MIN || id > ID_MAX) return null;
        return "config_vibeCommon" + (char) ('A' + (id - ID_BASE));
    }

    /** 资源读出来的数组是否可用（偶数个以上、非负、总时长 > 0）。 */
    public static boolean valid(long[] p) {
        if (p == null || p.length < 2) return false;
        long sum = 0;
        for (long v : p) { if (v < 0) return false; sum += v; }
        return sum > 0;
    }

    /** 最终波形：系统读到的有效就用它，否则三星 Basic call。返回副本。 */
    public static long[] choose(long[] fromSystem) {
        return (valid(fromSystem) ? fromSystem : BASIC_CALL).clone();
    }
}
