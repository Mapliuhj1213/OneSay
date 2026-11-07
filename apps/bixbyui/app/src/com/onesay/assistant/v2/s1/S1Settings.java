package com.onesay.assistant.v2.s1;

import android.content.Context;
import android.content.SharedPreferences;


/**
 * [s1] S1「看得见的后台」四个功能的开关 + 紧急关停（kill-switch），写法与 {@code ScreenRefSettings} 一致。
 *
 * <h3>设置项（SharedPreferences {@code bixby_s1}）</h3>
 * <table>
 *   <tr><th>键</th><th>功能</th><th>默认</th><th>理由</th></tr>
 *   <tr><td>{@code capsule}</td><td>#6 后台执行 + 状态胶囊</td><td>开</td><td>用户指定默认开；只改变长任务期间面板的形态，确认卡永远强制展开</td></tr>
 *   <tr><td>{@code auto_collapse}</td><td>长任务开始 {@link #AUTO_COLLAPSE_MS} 后自动收成胶囊</td><td>开</td><td>S1 闭环「说完查邮箱后面板收成胶囊」的前提；关掉后只有点面板外区域才收起</td></tr>
 *   <tr><td>{@code pickup_card}</td><td>#10 取件码/取餐码常驻浮卡</td><td>开</td><td>纯本机正则、不联网；验证码一律不出卡；S1 出口条件之一</td></tr>
 *   <tr><td>{@code circle}</td><td>#8 圈选即问</td><td>开</td><td>只在用户明确说「圈一下/框选」时触发，平时零开销</td></tr>
 *   <tr><td>{@code clip_chip}</td><td>剪贴板地址芯片</td><td>开</td><td>用户明确要求；敏感内容（验证码/密码/卡号/身份证/密钥）一律忽略</td></tr>
 *   <tr><td>{@code clip_deixis}</td><td>「导航到刚复制的地址」从剪贴板取值</td><td>开</td><td>随剪贴板芯片；只在屏幕上没有对应实体时才用（低优先级）</td></tr>
 * </table>
 *
 * <h3>Kill-switch（文件存在即关闭，删除即恢复，无需重装）</h3>
 * <ul>
 *   <li>整体：{@code /sdcard/Android/data/com.onesay.assistant/files/s1.off} 或 {@code /data/local/tmp/bixby_s1.off}；</li>
 *   <li>单项：同目录下 {@code capsule.off} / {@code pickup.off} / {@code circle.off} / {@code clipchip.off}
 *       （审计 R-2 起也认 {@code /data/local/tmp/bixby_capsule.off} 等，口径与其他功能一致）。</li>
 * </ul>
 * 关闭后对应挂钩全部短路，行为与未接入 S1 时一致（胶囊关闭时，点面板外区域仍是原来的「取消并收起」）。
 */
public final class S1Settings {

    private S1Settings() {}

    public static final String PREF = "bixby_s1";
    public static final String KEY_CAPSULE = "capsule";
    public static final String KEY_AUTO_COLLAPSE = "auto_collapse";
    public static final String KEY_PICKUP = "pickup_card";
    public static final String KEY_CIRCLE = "circle";
    public static final String KEY_CLIP_CHIP = "clip_chip";
    public static final String KEY_CLIP_DEIXIS = "clip_deixis";

    public static final String KILL_ALL = "s1.off";
    public static final String KILL_ALL_TMP = com.onesay.assistant.v2.BuildVariant.KILL_TMP_ROOT + "/bixby_s1.off";
    public static final String KILL_CAPSULE = "capsule.off";
    public static final String KILL_PICKUP = "pickup.off";
    public static final String KILL_CIRCLE = "circle.off";
    public static final String KILL_CLIP = "clipchip.off";

    /** 长任务开始后多久自动收成胶囊。 */
    public static final long AUTO_COLLAPSE_MS = com.onesay.assistant.v2.island.IslandModel.AUTO_COLLAPSE_MS;   // ★ R14：与岛一致（6 s）
    /** 剪贴板芯片无人触碰时自动消失。 */
    public static final long CLIP_CHIP_TTL_MS = 6000L;
    /** 取件码浮卡有效期。 */
    public static final long PICKUP_TTL_MS = 12L * 3600L * 1000L;

    /** 自测/调试覆盖（null = 按设置走）。 */
    public static volatile Boolean sOverride;

    /**
     * 纯函数裁决（JVM 单测入口）：覆盖值优先；任一 kill-switch 存在即关；否则看设置值。
     */
    public static boolean decide(Boolean override, boolean killAll, boolean killOne, boolean pref) {
        if (override != null) return override.booleanValue();
        if (killAll || killOne) return false;
        return pref;
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static boolean killFile(Context ctx, String name) {
        // 审计 R-2：kill 文件口径统一到 FeatureSwitch（外部 files 目录 或 /data/local/tmp/bixby_<名>）
        return com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, name);
    }

    public static boolean killAll(Context ctx) {
        // 审计 R-2：kill 文件口径统一到 FeatureSwitch（外部 files 目录 或 /data/local/tmp/bixby_<名>）
        return com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, KILL_ALL);
    }

    private static boolean on(Context ctx, String key, String killName, boolean def) {
        if (ctx == null) return false;
        try {
            return decide(sOverride, killAll(ctx), killName != null && killFile(ctx, killName), sp(ctx).getBoolean(key, def));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean capsuleEnabled(Context ctx) {
        return on(ctx, KEY_CAPSULE, KILL_CAPSULE, true);
    }

    public static boolean autoCollapseEnabled(Context ctx) {
        return capsuleEnabled(ctx) && on(ctx, KEY_AUTO_COLLAPSE, KILL_CAPSULE, true);
    }

    public static boolean pickupEnabled(Context ctx) {
        return on(ctx, KEY_PICKUP, KILL_PICKUP, true);
    }

    public static boolean circleEnabled(Context ctx) {
        return on(ctx, KEY_CIRCLE, KILL_CIRCLE, true);
    }

    public static boolean clipChipEnabled(Context ctx) {
        return on(ctx, KEY_CLIP_CHIP, KILL_CLIP, true);
    }

    public static boolean clipDeixisEnabled(Context ctx) {
        return clipChipEnabled(ctx) && on(ctx, KEY_CLIP_DEIXIS, KILL_CLIP, true);
    }

    /** ★ R12 控制台：设置值本身（不看 kill 文件 / 覆盖值），开关上显示的就是它。缺省都是开。 */
    public static boolean pref(Context ctx, String key) {
        try {
            return sp(ctx).getBoolean(key, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** ★ R12 控制台：某个子功能是否被 kill 文件关掉（s1.off 或该项自己的 .off）。 */
    public static boolean killedFor(Context ctx, String killName) {
        return killAll(ctx) || (killName != null && killFile(ctx, killName));
    }

    public static void set(Context ctx, String key, boolean on) {
        sp(ctx).edit().putBoolean(key, on).apply();
    }

    /** 一行状态（日志/自测用）。 */
    public static String dump(Context ctx) {
        return "capsule=" + capsuleEnabled(ctx) + " auto_collapse=" + autoCollapseEnabled(ctx)
                + " pickup=" + pickupEnabled(ctx) + " circle=" + circleEnabled(ctx)
                + " clip_chip=" + clipChipEnabled(ctx) + " clip_deixis=" + clipDeixisEnabled(ctx)
                + " killAll=" + killAll(ctx);
    }
}
