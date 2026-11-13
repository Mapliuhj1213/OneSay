package com.onesay.assistant.v2.entry.keywake;

import android.content.Context;
import android.content.SharedPreferences;

/** 按键唤醒设置（prefs {@link KeyWake#PREFS}）。入口开关本身在 Entries（{@code entry.key_wake}，默认关）。 */
public final class KeyWakeSettings {
    private KeyWakeSettings() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(KeyWake.PREFS, Context.MODE_PRIVATE);
    }

    public static KeyWake.Trigger trigger(Context c) {
        return KeyWake.parseTrigger(sp(c).getString(KeyWake.K_TRIGGER, KeyWake.Trigger.POWER_DOUBLE.key));
    }

    public static KeyWake.Action action(Context c) {
        return KeyWake.parseAction(sp(c).getString(KeyWake.K_ACTION, KeyWake.Action.VOICE.key));
    }

    public static int windowMs(Context c) {
        return KeyWake.clampWindow(sp(c).getInt(KeyWake.K_WINDOW, KeyWake.DEFAULT_WINDOW_MS));
    }

    public static void setTrigger(Context c, KeyWake.Trigger t) {
        sp(c).edit().putString(KeyWake.K_TRIGGER, t.key).apply();
    }

    public static void setAction(Context c, KeyWake.Action a) {
        sp(c).edit().putString(KeyWake.K_ACTION, a.key).apply();
    }

    public static void setWindowMs(Context c, int ms) {
        sp(c).edit().putInt(KeyWake.K_WINDOW, KeyWake.clampWindow(ms)).apply();
    }

    /** 其他通用入口（快捷开关 / 小部件 / 快捷方式）点一下做什么：语音（默认）/ 识屏 / 文字面板。 */
    public static KeyWake.Action entryAction(Context c, String entryId) {
        return KeyWake.parseAction(sp(c).getString("action." + entryId, KeyWake.Action.VOICE.key));
    }

    public static void setEntryAction(Context c, String entryId, KeyWake.Action a) {
        sp(c).edit().putString("action." + entryId, a.key).apply();
    }
}
