package com.onesay.plan.platform;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import com.onesay.plan.core.Capabilities;
import com.onesay.plan.remind.Remind;

/**
 * 探测系统能力（Android 版本、通知权限 / 类别、精确闹钟权限、省电白名单、厂商），交给 {@link Capabilities} 判定。
 * 只读系统状态，不申请权限、不改设置；任何一项探测失败都记 null（= 按可用处理），绝不抛出。
 * 编译用 android-28，API 31 的 canScheduleExactAlarms 用反射调用。
 */
public final class CapabilityProbe {
    private CapabilityProbe() {}

    public static Capabilities.Env probe(Context ctx) {
        Context c = ctx.getApplicationContext();
        Capabilities.Env e = new Capabilities.Env();
        e.sdkInt = Build.VERSION.SDK_INT;
        e.manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                e.notificationsEnabled = nm.areNotificationsEnabled();
                if (Build.VERSION.SDK_INT >= 26) {
                    NotificationChannel ch = nm.getNotificationChannel(Remind.CH);
                    if (ch != null) e.channelEnabled = ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                Object r = AlarmManager.class.getMethod("canScheduleExactAlarms").invoke(am);
                if (r instanceof Boolean) e.exactAlarmAllowed = (Boolean) r;
            }
        } catch (Throwable ignored) {
        }
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) e.ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.content.pm.ShortcutManager sm = (android.content.pm.ShortcutManager) c.getSystemService(android.content.pm.ShortcutManager.class);
                if (sm != null) e.pinShortcutSupported = sm.isRequestPinShortcutSupported();
            }
        } catch (Throwable ignored) {
        }
        StringBuilder off = new StringBuilder();
        for (Capabilities.Status st : Capabilities.all(e)) if (!st.available) off.append(' ').append(st.feature.name());
        Log.i("BixbyPlan", "[CAPS] sdk=" + e.sdkInt + " oem=" + e.manufacturer + (off.length() > 0 ? " 不可用:" + off : " 全部可用"));
        return e;
    }
}
