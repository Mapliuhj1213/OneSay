package com.onesay.assistant.v2.summary;

import android.content.Context;
import android.content.SharedPreferences;


/**
 * [summary] #18 长内容摘要的开关 + kill-switch（写法与 ImageSaveSettings / DiagSettings 一致）。
 *
 * <h3>设置项（SharedPreferences {@code bixby_summary}）</h3>
 * <table>
 *   <tr><th>键</th><th>功能</th><th>默认</th></tr>
 *   <tr><td>{@code enabled}</td><td>「总结一下 / 这篇讲了什么 / 太长不看」→ 读屏幕文字 → 一次大模型 → 一句话 + 3–5 条要点</td><td>开</td></tr>
 *   <tr><td>{@code vl}</td><td>屏幕文字太少（图片为主 / 自绘界面）时，用既有截图 + 视觉模型兜底（截图会发到云端）</td><td>开</td></tr>
 * </table>
 *
 * <h3>Kill-switch（文件存在即关，删除即恢复）</h3>
 * {@code /sdcard/Android/data/com.onesay.assistant/files/summary.off} 或 {@code /data/local/tmp/bixby_summary.off}：
 * L0 不再产出 {@code screen.summary}、分诊不再判 5s-screen-summary，这些句子回到原判法（ASK / 兜底）。
 */
public final class SummarySettings {

    private SummarySettings() {}

    public static final String PREF = "bixby_summary";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_VL = "vl";
    public static final String KILL = "summary.off";
    public static final String KILL_TMP = com.onesay.assistant.v2.BuildVariant.KILL_TMP_ROOT + "/bixby_summary.off";

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

    public static boolean enabled(Context ctx) {
        if (sOverride != null) return sOverride.booleanValue();
        if (ctx == null) return false;
        try {
            return decide(null, killed(ctx), sp(ctx).getBoolean(KEY_ENABLED, true));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean vlEnabled(Context ctx) {
        if (!enabled(ctx) || ctx == null) return false;
        try {
            return sp(ctx).getBoolean(KEY_VL, true);
        } catch (Throwable t) {
            return false;
        }
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
        return "enabled=" + enabled(ctx) + " vl=" + vlEnabled(ctx) + " killed=" + killed(ctx);
    }
}
