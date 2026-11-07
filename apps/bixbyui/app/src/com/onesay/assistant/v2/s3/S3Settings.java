package com.onesay.assistant.v2.s3;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.v2.s1.S1Settings;


/**
 * [s3] 开关 + kill-switch（写法同 S2Settings，裁决复用 {@link S1Settings#decide}）。
 *
 * <table>
 *   <tr><th>SharedPreferences</th><th>键</th><th>功能</th><th>默认</th></tr>
 *   <tr><td>{@code bixby_s3}</td><td>{@code enabled}</td><td>定时任务：语音新建 / 列表 / 取消；到点提醒或无人值守跑一遍</td><td>开</td></tr>
 *   <tr><td>{@code bixby_todo}</td><td>{@code enabled}</td><td>待办：记个待办 / 我的待办 / X做完了；岛上长按看列表</td><td>开</td></tr>
 *   <tr><td>{@code bixby_todo}</td><td>{@code chip}</td><td>空闲时岛上显示「今天到期 / 已过期的待办数」（为 0 不显示）</td><td>开</td></tr>
 * </table>
 * Kill-switch：{@code /sdcard/Android/data/com.onesay.assistant/files/s3.off}（或 /data/local/tmp/bixby_s3.off）、{@code todo.off}（或 bixby_todo.off）。
 * 关闭定时任务后<b>已排的闹钟照样到点</b>，但到点时检查开关，关着就只记日志不执行（不丢数据，打开后照常）。
 */
public final class S3Settings {

    private S3Settings() {}

    public static final String PREF_S3 = "bixby_s3";
    public static final String PREF_TODO = "bixby_todo";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_CHIP = "chip";
    public static final String KILL_S3 = "s3.off";
    public static final String KILL_TODO = "todo.off";

    public static volatile Boolean sOverride;

    private static SharedPreferences sp(Context ctx, String pref) {
        return ctx.getApplicationContext().getSharedPreferences(pref, Context.MODE_PRIVATE);
    }

    static boolean killed(Context ctx, String name) {
        // 审计 R-2：kill 文件口径统一到 FeatureSwitch（外部 files 目录 或 /data/local/tmp/bixby_<名>）
        return com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, name);
    }

    public static boolean s3Enabled(Context ctx) {
        if (ctx == null) return false;
        try {
            return S1Settings.decide(sOverride, false, killed(ctx, KILL_S3), sp(ctx, PREF_S3).getBoolean(KEY_ENABLED, true));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean todoEnabled(Context ctx) {
        if (ctx == null) return false;
        try {
            return S1Settings.decide(sOverride, false, killed(ctx, KILL_TODO), sp(ctx, PREF_TODO).getBoolean(KEY_ENABLED, true));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean chipEnabled(Context ctx) {
        return todoEnabled(ctx) && sp(ctx, PREF_TODO).getBoolean(KEY_CHIP, true);
    }

    public static S3Engine.Flags flags(Context ctx) {
        return new S3Engine.Flags(s3Enabled(ctx), todoEnabled(ctx));
    }

    public static boolean pref(Context ctx, String pref, String key) {
        return sp(ctx, pref).getBoolean(key, true);
    }

    public static void set(Context ctx, String pref, String key, boolean on) {
        sp(ctx, pref).edit().putBoolean(key, on).apply();
    }

    public static String dump(Context ctx) {
        return "s3=" + s3Enabled(ctx) + " todo=" + todoEnabled(ctx) + " chip=" + chipEnabled(ctx) + " override=" + sOverride;
    }
}
