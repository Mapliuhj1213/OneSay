package com.onesay.assistant.v2.vision.screenref;

import android.content.Context;
import android.content.SharedPreferences;


/**
 * [screenref] 功能开关（默认开）+ 紧急关停开关。
 *
 * <ul>
 *   <li>设置项：SharedPreferences {@code bixby_screenref}：{@code enabled}（总开关，默认 true）、
 *       {@code chips}（唤醒后动作芯片，默认 true）、{@code vl_fallback}（文字树失败时按需走截图+Qwen-VL，默认 true）。</li>
 *   <li><b>Kill-switch</b>（无需改代码、无需 UI，adb 一条命令即可）：下面任一文件存在即整体关闭：
 *       <br>{@code /sdcard/Android/data/com.onesay.assistant/files/screenref.off}
 *       <br>{@code /data/local/tmp/bixby_screenref.off}
 *       <br>删除文件即恢复。关闭后：不在唤醒时采集、不拦截指令、不给 AgentLoop 加工具/注入、不显示芯片 —— 与未接入本功能完全一致。</li>
 * </ul>
 */
public final class ScreenRefSettings {

    private ScreenRefSettings() {}

    public static final String PREF = "bixby_screenref";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_CHIPS = "chips";
    public static final String KEY_VL = "vl_fallback";
    public static final String KILL_FILE_NAME = "screenref.off";
    public static final String KILL_FILE_TMP = com.onesay.assistant.v2.BuildVariant.KILL_TMP_ROOT + "/bixby_screenref.off";

    /** 会话缓存 TTL：唤醒采集的屏幕上下文 60 秒内有效。 */
    public static final long CONTEXT_TTL_MS = 60000L;
    /** uiautomator dump 的 shell 超时（秒）。 */
    public static final int DUMP_TIMEOUT_S = 4;
    /** 指令到达时等待在途采集的上限。 */
    public static final long AWAIT_CAPTURE_MS = 6000L;
    /** VL 兜底同步等待上限。 */
    public static final long VL_TIMEOUT_MS = 20000L;

    /** 自测/调试覆盖（null = 按设置走）。 */
    public static volatile Boolean sOverride;

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean killSwitchOn(Context ctx) {
        // 审计 R-2：kill 文件口径统一到 FeatureSwitch（外部 files 目录 或 /data/local/tmp/bixby_<名>）
        return com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, KILL_FILE_NAME);
    }

    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return false;
        Boolean o = sOverride;
        if (o != null) return o.booleanValue();
        if (killSwitchOn(ctx)) return false;
        try {
            return sp(ctx).getBoolean(KEY_ENABLED, true);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean chipsEnabled(Context ctx) {
        return isEnabled(ctx) && sp(ctx).getBoolean(KEY_CHIPS, true);
    }

    public static boolean vlFallbackEnabled(Context ctx) {
        return isEnabled(ctx) && sp(ctx).getBoolean(KEY_VL, true);
    }

    /** ★ R12 控制台：设置值本身（不看 kill 文件 / 覆盖值）。缺省都是开。 */
    public static boolean pref(Context ctx, String key) {
        try {
            return sp(ctx).getBoolean(key, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static void setChips(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_CHIPS, on).apply();
    }

    public static void setVlFallback(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_VL, on).apply();
    }
}
