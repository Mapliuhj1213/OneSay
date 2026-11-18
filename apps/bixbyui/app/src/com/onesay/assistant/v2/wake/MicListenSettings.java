package com.onesay.assistant.v2.wake;

import android.content.Context;
import android.content.SharedPreferences;

/** {@link MicListenPolicy} 的设置读写（prefs onesay_mic_listen）。WakeService 监听这份 prefs，改了立即重判。 */
public final class MicListenSettings {
    public static final String PREFS = "onesay_mic_listen";
    public static final String KEY_MODE = "mode";
    public static final String KEY_MINUTES = "minutes";
    public static final String KEY_QUICK_OFF = "quick_off";

    private MicListenSettings() {}

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String mode(Context c) {
        try { return MicListenPolicy.normalizeMode(sp(c).getString(KEY_MODE, MicListenPolicy.DEFAULT_MODE)); }
        catch (Throwable t) { return MicListenPolicy.DEFAULT_MODE; }
    }

    public static int minutes(Context c) {
        try { return MicListenPolicy.clampMinutes(sp(c).getInt(KEY_MINUTES, MicListenPolicy.DEFAULT_MINUTES)); }
        catch (Throwable t) { return MicListenPolicy.DEFAULT_MINUTES; }
    }

    public static boolean quickOff(Context c) {
        try { return sp(c).getBoolean(KEY_QUICK_OFF, false); } catch (Throwable t) { return false; }
    }

    public static void setMode(Context c, String mode) {
        sp(c).edit().putString(KEY_MODE, MicListenPolicy.normalizeMode(mode)).commit();
    }

    public static void setMinutes(Context c, int m) {
        sp(c).edit().putInt(KEY_MINUTES, MicListenPolicy.clampMinutes(m)).commit();
    }

    /** commit（同步）：快捷开关一点就要生效。 */
    public static void setQuickOff(Context c, boolean off) {
        sp(c).edit().putBoolean(KEY_QUICK_OFF, off).commit();
    }

    /** 当前时刻的环境之外的那部分（模式 / 分钟 / 快捷开关）合成判定。 */
    public static MicListenPolicy.Decision decide(Context c, MicListenPolicy.State s) {
        return MicListenPolicy.decide(mode(c), minutes(c), quickOff(c), s);
    }
}
