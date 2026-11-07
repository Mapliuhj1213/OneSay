package com.onesay.assistant.v2.island;

import android.content.Context;
import android.content.SharedPreferences;


/**
 * [island] 灵动岛的开关 + kill-switch（写法与 SummarySettings / ImageSaveSettings 一致）。
 *
 * <h3>设置项（SharedPreferences {@code bixby_island}）</h3>
 * <table>
 *   <tr><th>键</th><th>功能</th><th>默认</th></tr>
 *   <tr><td>{@code enabled}</td><td>后台任务 / 取件码 / 提醒画在状态栏下方居中的岛上（v3：悬浮窗 TYPE_APPLICATION_OVERLAY，<b>不用无障碍</b>）；
 *       关掉 = 不画任何浮层：任务留在面板、S3 提醒走通知（2026-10-10 第 6 项起没有胶囊回落）</td><td>开</td></tr>
 *   <tr><td>{@code fullscreen}</td><td>状态栏隐藏（全屏视频 / 游戏）时岛也隐藏，只有关键事件在屏幕上沿 8dp 展开</td><td>开</td></tr>
 *   <tr><td>{@code tuck}</td><td>横滑岛 = 暂避 60 秒（关键事件会提前叫回）</td><td>开</td></tr>
 * </table>
 *
 * <h3>Kill-switch（文件存在即关，删除即恢复，无需重装）</h3>
 * {@code /sdcard/Android/data/com.onesay.assistant/files/island.off} 或 {@code /data/local/tmp/bixby_island.off}：
 * 不再画岛，也不画任何别的浮层（旧胶囊已删除）：长任务、确认卡、结果都留在面板上。后台收起本身仍受 {@code capsule.off}/{@code s1.off} 管。
 */
public final class IslandSettings {

    private IslandSettings() {}

    public static final String PREF = "bixby_island";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_FULLSCREEN_HIDE = "fullscreen";
    public static final String KEY_TUCK = "tuck";
    public static final String KILL = "island.off";
    public static final String KILL_TMP = com.onesay.assistant.v2.BuildVariant.KILL_TMP_ROOT + "/bixby_island.off";

    /** 自测/调试覆盖（null = 按设置走）。 */
    public static volatile Boolean sOverride;

    /** 纯函数裁决（JVM 单测入口）：覆盖值优先；kill-switch 存在即关；否则看设置值。 */
    public static boolean decide(Boolean override, boolean killed, boolean pref) {
        if (override != null) return override.booleanValue();
        if (killed) return false;
        return pref;
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean killed(Context ctx) {
        // 审计 R-2：kill 文件口径统一到 FeatureSwitch（外部 files 目录 或 /data/local/tmp/bixby_<名>）
        return com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, KILL);
    }

    /** 岛开关（含 island.off 与调试覆盖；不含悬浮窗权限）。 */
    public static boolean enabled(Context ctx) {
        if (ctx == null) return false;
        try {
            return decide(sOverride, killed(ctx), sp(ctx).getBoolean(KEY_ENABLED, true));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean fullscreenHide(Context ctx) {
        return ctx != null && pref(ctx, KEY_FULLSCREEN_HIDE);
    }

    public static boolean tuckEnabled(Context ctx) {
        return ctx != null && pref(ctx, KEY_TUCK);
    }

    public static boolean pref(Context ctx, String key) {
        try {
            return sp(ctx).getBoolean(key, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void set(Context ctx, String key, boolean on) {
        sp(ctx).edit().putBoolean(key, on).apply();
    }

    public static String dump(Context ctx) {
        return "enabled=" + enabled(ctx) + " pref=" + pref(ctx, KEY_ENABLED) + " fullscreen=" + pref(ctx, KEY_FULLSCREEN_HIDE) + " tuck=" + pref(ctx, KEY_TUCK)
                + " killed=" + killed(ctx) + " override=" + sOverride;
    }
}
