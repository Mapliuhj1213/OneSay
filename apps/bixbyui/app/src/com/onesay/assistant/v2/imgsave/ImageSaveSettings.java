package com.onesay.assistant.v2.imgsave;

import android.content.Context;
import android.content.SharedPreferences;


/**
 * [imgsave] 语音存图的开关 + kill-switch（写法与 DiagSettings / S1Settings 一致）。
 *
 * <h3>设置项（SharedPreferences {@code bixby_imgsave}）</h3>
 * <table>
 *   <tr><th>键</th><th>功能</th><th>默认</th></tr>
 *   <tr><td>{@code enabled}</td><td>语音存图总开关（「保存图片」→ 截图 + 非压缩 dump → 裁剪存 PNG）</td><td>开</td></tr>
 *   <tr><td>{@code cache}</td><td>I-C 缓存层（文档里写作 {@code imgsave.cache}）：root 列前台 App 图片磁盘缓存里刚写入的文件，宽高比对上就原样拷出「原图」</td><td>开</td></tr>
 * </table>
 *
 * <h3>Kill-switch（文件存在即关，删除即恢复）</h3>
 * <ul>
 *   <li>整个功能：{@code /sdcard/Android/data/com.onesay.assistant/files/imgsave.off} 或 {@code /data/local/tmp/bixby_imgsave.off}。
 *       关闭后 L0 不再产出 {@code image.save}、分诊不再判「存图」，这些句子回到原判法。</li>
 *   <li>只关缓存层：{@code …/files/imgsave.cache.off}（只走截图裁剪）。</li>
 * </ul>
 */
public final class ImageSaveSettings {

    private ImageSaveSettings() {}

    public static final String PREF = "bixby_imgsave";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_CACHE = "cache";
    public static final String KILL = "imgsave.off";
    public static final String KILL_TMP = com.onesay.assistant.v2.BuildVariant.KILL_TMP_ROOT + "/bixby_imgsave.off";
    public static final String KILL_CACHE = "imgsave.cache.off";

    /** 缓存层：只看抓图时刻之前这么多秒内写入的文件（D0 探针确认后可调）。 */
    public static final int CACHE_WINDOW_S = 15;
    /** 待选状态（多图编号芯片）保留多久。 */
    public static final long PENDING_TTL_MS = 60000L;

    /** 自测/调试覆盖（null = 按设置走）。 */
    public static volatile Boolean sOverride;
    public static volatile Boolean sCacheOverride;

    /**
     * 纯函数裁决（JVM 单测入口）：覆盖值优先；任一 kill-switch 存在即关；否则看设置值。
     */
    public static boolean decide(Boolean override, boolean killed, boolean pref) {
        if (override != null) return override.booleanValue();
        if (killed) return false;
        return pref;
    }

    /** 缓存层裁决：总开关关 → 关；否则同 {@link #decide}。 */
    public static boolean decideCache(boolean featureOn, Boolean override, boolean killed, boolean pref) {
        if (!featureOn) return false;
        return decide(override, killed, pref);
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static boolean killFile(Context ctx, String name) {
        // 审计 R-2：kill 文件口径统一到 FeatureSwitch（外部 files 目录 或 /data/local/tmp/bixby_<名>）
        return com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, name);
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

    public static boolean cacheEnabled(Context ctx) {
        if (!enabled(ctx)) return false;
        if (ctx == null) return sCacheOverride != null && sCacheOverride.booleanValue();
        try {
            return decideCache(true, sCacheOverride, killFile(ctx, KILL_CACHE), sp(ctx).getBoolean(KEY_CACHE, true));
        } catch (Throwable t) {
            return false;
        }
    }

    /** ★ R12 控制台：设置值本身（不看 kill 文件 / 覆盖值）。缺省都是开。 */
    public static boolean pref(Context ctx, String key) {
        try {
            return sp(ctx).getBoolean(key, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** ★ R12 控制台：缓存层的 kill 文件（imgsave.cache.off）。 */
    public static boolean cacheKilled(Context ctx) {
        return killFile(ctx, KILL_CACHE);
    }

    public static void set(Context ctx, String key, boolean on) {
        sp(ctx).edit().putBoolean(key, on).apply();
    }

    public static String dump(Context ctx) {
        return "enabled=" + enabled(ctx) + " cache=" + cacheEnabled(ctx) + " killed=" + killed(ctx)
                + " cacheKilled=" + killFile(ctx, KILL_CACHE);
    }
}
