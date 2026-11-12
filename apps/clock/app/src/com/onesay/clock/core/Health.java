package com.onesay.clock.core;

import java.util.ArrayList;
import java.util.List;

/**
 * [borrow-1112] 闹钟可靠性自检（纯 Java 判定；读系统状态与跳转设置在 Android 层 ui/CheckActivity）。
 * 思路来源：Wake Me（WeckMich，GPL-3.0）——只读思路，代码自写。
 *
 * <p>五项：精确闹钟权限（Android 12+）、电池优化白名单、闹钟音量为 0、勿扰会不会挡闹钟、通知权限。
 * 系统状态读不到的项（null）不报问题，只在详情里注明「读不到」。
 */
public final class Health {
    private Health() { }

    public static final String EXACT = "exact", BATTERY = "battery", VOLUME = "volume", DND = "dnd", NOTIF = "notif";
    /** 严重：会让闹钟直接不响；提醒：可能晚响 / 看不到。 */
    public static final int SEVERE = 2, WARN = 1;

    /** 勿扰模式（与 NotificationManager.INTERRUPTION_FILTER_* 数值一致）。 */
    public static final int ZEN_UNKNOWN = 0, ZEN_OFF = 1, ZEN_PRIORITY = 2, ZEN_NONE = 3, ZEN_ALARMS = 4;

    /** Android 层读好的系统状态；null = 读不到。 */
    public static final class Input {
        public int sdk = 28;
        public Boolean exactAllowed;
        public Boolean ignoringBattery;
        public Integer alarmVolume, alarmVolumeMax;
        public int zen = ZEN_UNKNOWN;
        /** 优先模式下是否放行闹钟（Android 9+ 的 PRIORITY_CATEGORY_ALARMS；更早的系统优先模式总是放行闹钟）。 */
        public Boolean zenAllowsAlarms;
        public Boolean notificationsEnabled;
    }

    /** 一个问题。 */
    public static final class Issue {
        public final String code;
        public final int level;
        public final String title, detail, fix;

        Issue(String code, int level, String title, String detail, String fix) {
            this.code = code; this.level = level; this.title = title; this.detail = detail; this.fix = fix;
        }

        public String toString() { return code + "/" + level; }
    }

    public static List<Issue> evaluate(Input in) {
        List<Issue> l = new ArrayList<Issue>();
        if (in == null) return l;
        if (in.sdk >= 31 && Boolean.FALSE.equals(in.exactAllowed))
            l.add(new Issue(EXACT, SEVERE, "没有「闹钟和提醒」权限", "系统不让本 App 排精确闹钟，闹钟可能完全不响。", "去开启"));
        if (Boolean.FALSE.equals(in.ignoringBattery))
            l.add(new Issue(BATTERY, WARN, "没有加入电池优化白名单", "省电策略可能推迟准备播报，个别机型还会推迟响铃。", "去设置"));
        if (in.alarmVolume != null && in.alarmVolume <= 0)
            l.add(new Issue(VOLUME, SEVERE, "闹钟音量是 0", "到点会响铃界面，但听不到声音。", "调到一半"));
        if (in.zen == ZEN_NONE)
            l.add(new Issue(DND, SEVERE, "勿扰处于「完全静音」", "这种勿扰会挡掉闹钟声音。", "去勿扰设置"));
        else if (in.zen == ZEN_PRIORITY && Boolean.FALSE.equals(in.zenAllowsAlarms))
            l.add(new Issue(DND, SEVERE, "勿扰没有放行闹钟", "当前勿扰的例外里没勾「闹钟」，闹钟会被静音。", "去勿扰设置"));
        if (Boolean.FALSE.equals(in.notificationsEnabled))
            l.add(new Issue(NOTIF, WARN, "通知被关了", "响铃时的通知和「错过的闹钟」提醒都看不到。", "去开启"));
        return l;
    }

    /** 最严重的级别；没有问题返回 0。 */
    public static int worst(List<Issue> l) {
        int w = 0;
        for (Issue i : l) w = Math.max(w, i.level);
        return w;
    }

    /** 列表顶部横幅一句话；没问题返回 ""。 */
    public static String banner(List<Issue> l) {
        if (l == null || l.isEmpty()) return "";
        return "有 " + l.size() + " 项可能影响闹钟，点这里检查";
    }

    /** 闹钟音量「调到一半」的目标值（至少 1）。 */
    public static int halfVolume(int max) { return Math.max(1, (max + 1) / 2); }
}
