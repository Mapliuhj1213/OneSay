package com.onesay.assistant.v2.vyrse;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.plug.vyrse.core.VyrsePairing;
import com.onesay.assistant.plug.vyrse.core.VyrseProtocol;
import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.security.SecretPrefs;

/**
 * [vyrse 适配层] VYRSE 兼容的设置与配对信息。<b>总开关默认关</b>（{@code bixby_vyrse.enabled}，kill-switch {@code vyrse.off}）；
 * 关着时不起服务、不联网。手机 key 经 {@link SecretPrefs} 存 Android Keystore 密文（登记在 SecretPrefs.REGISTRY），
 * 日志 / 状态只说「有没有」，不打值。
 */
public final class VyrseSettings {

    public static final String PREF = "bixby_vyrse";
    public static final String KILL = "vyrse.off";
    public static final FeatureSwitch SWITCH = new FeatureSwitch(PREF, "enabled", false, KILL);
    static final String K_TOKEN = "vyrse_token";
    static final String K_BASE = "base", K_PC = "pc_name", K_DEVICE = "device_id", K_AT = "paired_at", K_LINK = "link_on",
            K_REMOVED = "removed_by_pc", K_WARNED = "http_warning_ack";

    private VyrseSettings() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return SWITCH.on(c);
    }

    public static boolean paired(Context c) {
        return VyrseProtocol.validBase(base(c)) && token(c).length() > 0;
    }

    /** 配对过但 key 解不开（还原到另一次安装 / Keystore 被清）。 */
    public static boolean keyLost(Context c) {
        SharedPreferences s = sp(c);
        return s.getString(K_BASE, "").length() > 0 && s.getString(K_TOKEN, "").length() > 0 && token(c).length() == 0;
    }

    static String token(Context c) {
        return SecretPrefs.get(sp(c), K_TOKEN);
    }

    public static String base(Context c) {
        return sp(c).getString(K_BASE, "");
    }

    public static String pcName(Context c) {
        String n = sp(c).getString(K_PC, "");
        return n.length() == 0 ? "电脑" : n;
    }

    public static long pairedAt(Context c) {
        return sp(c).getLong(K_AT, 0L);
    }

    /** 「让电脑联系这台手机」（长轮询服务）开没开；配对成功后默认开。 */
    public static boolean linkOn(Context c) {
        return sp(c).getBoolean(K_LINK, true);
    }

    public static void setLinkOn(Context c, boolean on) {
        sp(c).edit().putBoolean(K_LINK, on).apply();
    }

    /** 电脑那边把这台手机移除了（轮询拿到 401）。 */
    public static boolean removedByPc(Context c) {
        return sp(c).getBoolean(K_REMOVED, false);
    }

    public static boolean warningAcked(Context c) {
        return sp(c).getBoolean(K_WARNED, false);
    }

    public static void ackWarning(Context c) {
        sp(c).edit().putBoolean(K_WARNED, true).apply();
    }

    static void save(Context c, VyrsePairing.Result r) {
        SharedPreferences.Editor e = sp(c).edit();
        SecretPrefs.put(e, K_TOKEN, r.token);
        e.putString(K_BASE, r.base).putString(K_PC, r.pcName == null ? "" : r.pcName).putString(K_DEVICE, r.deviceId == null ? "" : r.deviceId)
                .putLong(K_AT, System.currentTimeMillis()).putBoolean(K_LINK, true).putBoolean(K_REMOVED, false).commit();
    }

    public static void unpair(Context c) {
        sp(c).edit().remove(K_TOKEN).remove(K_BASE).remove(K_PC).remove(K_DEVICE).remove(K_AT).remove(K_REMOVED).commit();
    }

    static void markRemoved(Context c) {
        sp(c).edit().remove(K_TOKEN).putBoolean(K_REMOVED, true).commit();
    }

    /** 调试状态（不含 key）。 */
    public static String status(Context c) {
        return "enabled=" + enabled(c) + " paired=" + paired(c) + (keyLost(c) ? " keyLost" : "") + " link=" + linkOn(c)
                + " removed=" + removedByPc(c) + " running=" + VyrseLinkService.running();
    }
}
