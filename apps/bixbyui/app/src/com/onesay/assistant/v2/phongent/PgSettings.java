package com.onesay.assistant.v2.phongent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.onesay.assistant.v2.kernel.FeatureSwitch;

import java.util.UUID;

/**
 * [phongent 适配层] OneSay 侧的设置。<b>总开关默认关</b>；关着时 OneSay 不起 {@link PhongentService}、
 * 不监听端口、不注册 mDNS、通知钩子立即返回（零副作用）。
 */
public final class PgSettings {

    public static final String PREF = "bixby_phongent";
    public static final FeatureSwitch SWITCH = new FeatureSwitch(PREF, "enabled", false, "phongent.off");
    public static final String KEY_TS_ONLY = "tailscale_only";
    public static final String KEY_RELAY = "relay_notifications";
    public static final String KEY_SCREEN = "screen_sharing";
    public static final String KEY_PORT = "port";
    public static final String KEY_NAME = "device_name";
    public static final String KEY_ID = "lan_id";
    public static final int DEFAULT_PORT = 18790;
    /** 转发通知预览的 App 白名单（和上游默认一致的两家；不在里面的连标题都不读）。 */
    public static final String[] RELAY_APPS = {"com.tencent.mm", "com.tencent.mobileqq"};

    private PgSettings() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return SWITCH.on(c);
    }

    public static boolean get(Context c, String key) {
        return sp(c).getBoolean(key, false);
    }

    public static void set(Context c, String key, boolean on) {
        sp(c).edit().putBoolean(key, on).apply();
    }

    public static int port(Context c) {
        int p = sp(c).getInt(KEY_PORT, DEFAULT_PORT);
        return p > 1024 && p < 65536 ? p : DEFAULT_PORT;
    }

    public static String deviceName(Context c) {
        String n = sp(c).getString(KEY_NAME, null);
        return n != null && n.length() > 0 ? n : Build.MODEL;
    }

    /** 上游 DeviceIdentity.rename 口径：1–40 字、无控制字符。 */
    public static String rename(Context c, String raw) {
        String n = raw == null ? "" : raw.trim();
        boolean ok = n.length() > 0 && n.length() <= 40;
        for (int i = 0; ok && i < n.length(); i++) if (Character.isISOControl(n.charAt(i))) ok = false;
        if (!ok) throw new IllegalArgumentException("invalid_device_name");
        if (!sp(c).edit().putString(KEY_NAME, n).commit()) throw new IllegalStateException("name_save_failed");
        return n;
    }

    /** mDNS TXT 里的 id（iOS 主控要求是 UUID）。 */
    public static synchronized String lanId(Context c) {
        String id = sp(c).getString(KEY_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            sp(c).edit().putString(KEY_ID, id).commit();
        }
        return id;
    }

    static boolean relayApp(String pkg) {
        for (String p : RELAY_APPS) if (p.equals(pkg)) return true;
        return false;
    }
}
