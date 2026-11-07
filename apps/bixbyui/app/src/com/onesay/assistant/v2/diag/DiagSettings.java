package com.onesay.assistant.v2.diag;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.v2.s1.S1Settings;


/**
 * [diag] 设置诊断的开关 + kill-switch（写法与 S1Settings / S2Settings 一致，裁决复用 {@link S1Settings#decide}）。
 *
 * <h3>设置项（SharedPreferences {@code bixby_diag}）</h3>
 * <table>
 *   <tr><th>键</th><th>功能</th><th>默认</th></tr>
 *   <tr><td>{@code enabled}</td><td>#9 设置诊断总开关（症状句 → 读设置 → 解释 → 修复芯片；撤销口令）</td><td>开</td></tr>
 *   <tr><td>{@code llm}</td><td>未知症状时让 LLM 从可读白名单里挑要读的键（只读；关 = 按领域词取键）</td><td>开</td></tr>
 * </table>
 *
 * <h3>Kill-switch（文件存在即关，删除即恢复）</h3>
 * {@code /sdcard/Android/data/com.onesay.assistant/files/diag.off} 或 {@code /data/local/tmp/bixby_diag.off}。
 * 关闭后症状句回到 #9 之前的分诊（多数是 ASK / DO_ACT），已有撤销记录仍可用「撤销」恢复（只恢复，不新改）。
 */
public final class DiagSettings {

    private DiagSettings() {}

    public static final String PREF = "bixby_diag";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_LLM = "llm";
    public static final String KILL = "diag.off";
    public static final String KILL_TMP = com.onesay.assistant.v2.BuildVariant.KILL_TMP_ROOT + "/bixby_diag.off";

    /** 自测/调试覆盖（null = 按设置走）。 */
    public static volatile Boolean sOverride;

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean killed(Context ctx) {
        // 审计 R-2：kill 文件口径统一到 FeatureSwitch（外部 files 目录 或 /data/local/tmp/bixby_<名>）
        return com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, KILL);
    }

    /** 默认值：两项都开。 */
    public static boolean defaultOf(String key) {
        return true;
    }

    public static boolean enabled(Context ctx) {
        if (sOverride != null) return sOverride.booleanValue();
        if (ctx == null) return false;
        try {
            return S1Settings.decide(sOverride, killed(ctx), false, sp(ctx).getBoolean(KEY_ENABLED, defaultOf(KEY_ENABLED)));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean llmEnabled(Context ctx) {
        if (!enabled(ctx) || ctx == null) return false;
        try {
            return sp(ctx).getBoolean(KEY_LLM, defaultOf(KEY_LLM));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean pref(Context ctx, String key) {
        return sp(ctx).getBoolean(key, defaultOf(key));
    }

    public static void set(Context ctx, String key, boolean on) {
        sp(ctx).edit().putBoolean(key, on).apply();
    }

    public static String dump(Context ctx) {
        return "enabled=" + enabled(ctx) + " llm=" + llmEnabled(ctx) + " killed=" + killed(ctx);
    }
}
