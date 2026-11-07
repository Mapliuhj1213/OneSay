package com.onesay.assistant.v2.ui.wake;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * [R13 唤醒界面 v2] 新 / 旧唤醒界面的开关 + kill-switch（项目惯例 bixby_xxx + xxx.off）。
 *
 * <h3>设置项（SharedPreferences {@code bixby_wake_ui}）</h3>
 * <table>
 *   <tr><th>键</th><th>功能</th><th>默认</th></tr>
 *   <tr><td>{@code style}</td><td>{@code new} = 边缘光 + 悬浮小卡 + 卡↔岛形变（R13）；{@code classic} = R12 及以前的底部面板 + 水光</td><td>new（D6=A）</td></tr>
 *   <tr><td>{@code morph}</td><td>长任务收进岛时做黏连形变（关 = 卡片直接摘下、岛照旧从小胶囊长出来）</td><td>开</td></tr>
 *   <tr><td>{@code window}</td><td>★ lane C：{@code full} = 固定全屏卡片窗（回答拉满、窗口尺寸不变）；{@code wrap} = R14 贴底 WRAP_CONTENT 窗</td><td>full</td></tr>
 *   <tr><td>{@code glow_tint}</td><td>{@code compose} = 预烘焙 ALPHA_8 + ComposeShader 上色；{@code baked} = 3 张预着色位图交叉淡化（真机若 Shader 不生效时的兜底）</td><td>compose</td></tr>
 * </table>
 *
 * <h3>Kill-switch（文件存在即回旧界面，删掉即恢复，无需重装）</h3>
 * {@code /sdcard/Android/data/com.onesay.assistant/files/wakeui.off} 或 {@code /data/local/tmp/bixby_wakeui.off}。
 * 每次唤醒现查（不缓存），所以放文件后的<b>下一次</b>唤醒就是旧界面。
 */
public final class WakeUiSettings {

    private WakeUiSettings() {}

    public static final String PREF = "bixby_wake_ui";
    public static final String KEY_STYLE = "style";
    public static final String KEY_MORPH = "morph";
    public static final String KEY_GLOW_TINT = "glow_tint";
    public static final String STYLE_NEW = "new";
    public static final String STYLE_CLASSIC = "classic";
    public static final String KILL = "wakeui.off";

    /** 调试覆盖（null = 按设置走；验收脚本 / 调试广播用）。 */
    public static volatile Boolean sOverride;

    /**
     * 纯函数裁决（JVM 单测入口）：覆盖值优先 → kill-switch 存在即旧界面 → 设置值（只有明确写 classic 才是旧界面；
     * 缺省 / 写错 / 空串都按 new，D6=A「新界面默认开」）。
     */
    public static boolean decideNew(Boolean override, boolean killed, String style) {
        if (override != null) return override.booleanValue();
        if (killed) return false;
        return style == null || !STYLE_CLASSIC.equalsIgnoreCase(style.trim());
    }

    /** 纯函数：形变是否可做（新界面 + 开关开 + 系统动画没被关掉）。 */
    public static boolean decideMorph(boolean newUi, boolean morphPref, boolean animatorsEnabled) {
        return newUi && morphPref && animatorsEnabled;
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean killed(Context ctx) {
        return com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, KILL);
    }

    /** 这一次唤醒用新界面吗（含 kill-switch 与调试覆盖）。任何异常 → 旧界面（旧界面是兜底）。 */
    public static boolean useNew(Context ctx) {
        if (ctx == null) return false;
        try {
            return decideNew(sOverride, killed(ctx), sp(ctx).getString(KEY_STYLE, STYLE_NEW));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean morphPref(Context ctx) {
        try {
            return sp(ctx).getBoolean(KEY_MORPH, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** ★ lane C：卡片窗形态（{@code full} 默认 = 固定全屏窗；{@code wrap} = R14 的 WRAP_CONTENT 贴底窗，上机对比 / 回退用）。 */
    public static String windowPref(Context ctx) {
        try {
            return sp(ctx).getString(WindowFlowPolicy.KEY_WINDOW, WindowFlowPolicy.WINDOW_FULL);
        } catch (Throwable t) {
            return WindowFlowPolicy.WINDOW_FULL;
        }
    }

    public static boolean bakedTint(Context ctx) {
        try {
            return "baked".equalsIgnoreCase(sp(ctx).getString(KEY_GLOW_TINT, "compose"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 系统「动画时长缩放」= 0（开发者选项关动画 / 省电）→ 一切动效瞬时完成（与 ConsoleNav 同一口径）。 */
    public static boolean animatorsEnabled(Context ctx) {
        try {
            return android.provider.Settings.Global.getFloat(ctx.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setStyle(Context ctx, String style) {
        sp(ctx).edit().putString(KEY_STYLE, style).apply();
    }

    public static String dump(Context ctx) {
        String style;
        try {
            style = sp(ctx).getString(KEY_STYLE, STYLE_NEW);
        } catch (Throwable t) {
            style = "?";
        }
        return "useNew=" + useNew(ctx) + " style=" + style + " killed=" + killed(ctx) + " override=" + sOverride
                + " morph=" + morphPref(ctx) + " tint=" + (bakedTint(ctx) ? "baked" : "compose")
                + " animators=" + animatorsEnabled(ctx) + " window=" + windowPref(ctx);
    }
}
