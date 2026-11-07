package com.onesay.plan.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 系统能力 → 功能开关是否可用（纯 Java，JVM 可测）。
 *
 * <p>Android 层（{@code platform/CapabilityProbe}）在启动和打开设置时探测一次系统状态填进 {@link Env}，
 * 这里决定每个功能「可用 / 不可用（置灰 + 原因）/ 可用但有提示」。<b>只影响界面展示</b>：不改任何已保存的设置，
 * 也不改提醒排程（探测不到的项按「可用」处理，和 0.1.4 一样）。
 */
public final class Capabilities {
    private Capabilities() {}

    /** 探测结果。Boolean 为 null 表示「这台系统上没有这个概念或探测失败」，按可用处理。 */
    public static final class Env {
        public int sdkInt;
        public String manufacturer = "";
        /** NotificationManager.areNotificationsEnabled()（Android 13+ 也反映通知权限）。 */
        public Boolean notificationsEnabled;
        /** 「计划提醒」通知类别没被用户关掉（API 26+）。 */
        public Boolean channelEnabled;
        /** AlarmManager.canScheduleExactAlarms()（API 31+）。 */
        public Boolean exactAlarmAllowed;
        /** PowerManager.isIgnoringBatteryOptimizations()（API 23+）。 */
        public Boolean ignoringBatteryOptimizations;
        /** ShortcutManager.isRequestPinShortcutSupported()（API 26+；桌面不支持固定快捷方式时为 false）。 */
        public Boolean pinShortcutSupported;
    }

    /** 受系统能力影响的功能。 */
    public enum Feature {
        /** 到点提醒（通知）。对应设置里的开关。 */
        NOTIFY("到点提醒（通知）"),
        /** 精确到点（setExactAndAllowWhileIdle）。 */
        EXACT_ALARM("精确到点"),
        /** 锁屏显示票提醒与取票码。 */
        LOCKSCREEN_TICKET("锁屏出示票码"),
        /** 后台不被厂商省电策略拦截。 */
        BACKGROUND("后台运行"),
        /** 快捷指令「加到桌面」（固定快捷方式）。 */
        PIN_SHORTCUT("快捷指令加到桌面");

        public final String label;

        Feature(String label) { this.label = label; }
    }

    /** 一个功能在当前系统上的状态。 */
    public static final class Status {
        public final Feature feature;
        /** false = 置灰，{@link #reason} 写明原因。 */
        public final boolean available;
        /** 不可用的原因，或可用时的提示；没有则为空串。 */
        public final String reason;

        Status(Feature f, boolean available, String reason) {
            this.feature = f;
            this.available = available;
            this.reason = reason == null ? "" : reason;
        }

        @Override public String toString() {
            return feature.label + "：" + (available ? "可用" : "不可用") + (reason.length() > 0 ? "（" + reason + "）" : "");
        }
    }

    /** 厂商后台管控较严、常见提醒迟到的品牌（小写，按 Build.MANUFACTURER 匹配）。 */
    static final String[] AGGRESSIVE_OEMS = {"samsung", "xiaomi", "redmi", "huawei", "honor", "oppo", "realme", "oneplus", "vivo", "iqoo", "meizu"};

    /** 厂商是否属于后台管控较严的品牌（只用于提示）。 */
    public static boolean aggressiveOem(String manufacturer) {
        if (manufacturer == null) return false;
        String m = manufacturer.toLowerCase(Locale.ROOT);
        for (String o : AGGRESSIVE_OEMS) if (m.contains(o)) return true;
        return false;
    }

    /** 判定一个功能；{@code e} 里未知（null）的项按可用处理。 */
    public static Status of(Feature f, Env e) {
        switch (f) {
            case NOTIFY:
                if (e.sdkInt > 0 && e.sdkInt < 24) return new Status(f, false, "需要 Android 7.0 及以上");
                if (Boolean.FALSE.equals(e.notificationsEnabled))
                    return new Status(f, false, "系统设置里关闭了计划的通知" + (e.sdkInt >= 33 ? "（Android 13 起还需授予通知权限）" : ""));
                if (Boolean.FALSE.equals(e.channelEnabled)) return new Status(f, false, "「计划提醒」通知类别被关闭");
                return new Status(f, true, "");
            case EXACT_ALARM:
                if (Boolean.FALSE.equals(e.exactAlarmAllowed))
                    return new Status(f, false, "系统没有授予「闹钟和提醒」权限，提醒可能推迟几分钟");
                return new Status(f, true, "");
            case LOCKSCREEN_TICKET: {
                Status n = of(Feature.NOTIFY, e);
                return n.available ? new Status(f, true, "") : new Status(f, false, "通知不可用：" + n.reason);
            }
            case BACKGROUND:
                if (Boolean.FALSE.equals(e.ignoringBatteryOptimizations) && aggressiveOem(e.manufacturer))
                    return new Status(f, true, "这台手机的省电策略可能延迟后台提醒，可在电池设置里把计划设为「不受限制」");
                return new Status(f, true, "");
            case PIN_SHORTCUT:
                if (e.sdkInt > 0 && e.sdkInt < 26) return new Status(f, false, "需要 Android 8.0 及以上");
                if (Boolean.FALSE.equals(e.pinShortcutSupported)) return new Status(f, false, "这个桌面不支持添加快捷方式");
                return new Status(f, true, "");
            default:
                return new Status(f, true, "");
        }
    }

    /** 按 {@link Feature} 声明顺序判定全部功能。 */
    public static List<Status> all(Env e) {
        List<Status> l = new ArrayList<Status>();
        for (Feature f : Feature.values()) l.add(of(f, e));
        return l;
    }
}
