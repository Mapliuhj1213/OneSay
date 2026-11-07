package com.onesay.assistant.v2.kernel;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * [kernel] 统一的功能开关：SharedPreferences 键 + kill-switch 文件（项目约定 bixby_xxx + xxx.off）。
 *
 * <p>和既有各 *Settings 类同一口径：
 * <ol>
 *   <li>调试覆盖值（非 null）优先；</li>
 *   <li>任一 kill-switch 文件存在即关：{@code <外部 files 目录>/<name>} 或 {@code /data/local/tmp/bixby_<name>}；</li>
 *   <li>否则看 prefs 里的布尔值（缺省用 {@code def}）。</li>
 * </ol>
 * 文件每次现查，删掉即恢复，无需重启（与既有行为一致）。新插件直接用它，不必再复制一份 Settings 样板。
 */
public final class FeatureSwitch {

    public final String prefs;
    public final String key;
    public final boolean def;
    public final String[] killFiles;

    /** 调试覆盖（null = 按设置走）。 */
    public volatile Boolean override;

    public FeatureSwitch(String prefs, String key, boolean def, String... killFiles) {
        this.prefs = prefs;
        this.key = key;
        this.def = def;
        this.killFiles = killFiles == null ? new String[0] : killFiles;
    }

    /** 纯函数裁决（JVM 单测入口）。 */
    public static boolean decide(Boolean override, boolean killed, boolean pref) {
        if (override != null) return override.booleanValue();
        if (killed) return false;
        return pref;
    }

    /** /data/local/tmp：adb shell 用户（不需要 root）也能写的位置。 */
    public static final String TMP_ROOT = com.onesay.assistant.v2.BuildVariant.KILL_TMP_ROOT;   // release "/data/local/tmp"；debug 自己的子目录

    /**
     * 某个 kill-switch 文件是否存在（两个位置任一）。审计 R-2 之后 8 个旧 *Settings 也全部走这里，
     * 所以<b>每个</b> .off 文件（含 S1 / S2 / 存图的分项）都同时认外部目录与 /data/local/tmp/bixby_&lt;名&gt;。
     */
    public static boolean killFilePresent(Context ctx, String name) {
        File ext = null;
        try {
            ext = ctx == null ? null : ctx.getExternalFilesDir(null);
        } catch (Throwable ignored) {
            // 拿不到外部目录：只看 /data/local/tmp
        }
        return killFilePresent(ext, TMP_ROOT, name);
    }

    /** 纯函数版（JVM 单测入口）：{@code <extDir>/<name>} 或 {@code <tmpRoot>/bixby_<name>} 任一存在。 */
    public static boolean killFilePresent(File extDir, String tmpRoot, String name) {
        if (name == null || name.length() == 0) return false;
        try {
            if (tmpRoot != null && new File(tmpRoot, "bixby_" + name).exists()) return true;
        } catch (Throwable ignored) {
        }
        try {
            return extDir != null && new File(extDir, name).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean killed(Context ctx) {
        for (String k : killFiles) {
            if (killFilePresent(ctx, k)) return true;
        }
        return false;
    }

    public boolean on(Context ctx) {
        Boolean o = override;
        if (o != null) return o.booleanValue();
        if (ctx == null) return false;
        try {
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(prefs, Context.MODE_PRIVATE);
            return decide(null, killed(ctx), sp.getBoolean(key, def));
        } catch (Throwable t) {
            return false;
        }
    }

    public void set(Context ctx, boolean on) {
        ctx.getApplicationContext().getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().putBoolean(key, on).apply();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(prefs).append('.').append(key).append(" def=").append(def).append(" kill=");
        for (int i = 0; i < killFiles.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(killFiles[i]);
        }
        return sb.toString();
    }
}
