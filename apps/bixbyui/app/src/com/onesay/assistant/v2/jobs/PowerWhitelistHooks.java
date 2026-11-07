package com.onesay.assistant.v2.jobs;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * root 省电白名单的 Android 胶水（lane-a 第 7 项）：debug 包启动时自动加（默认开），控制台一键还原。
 *
 * <p>只在 debug 变体生效：{@code com.onesay.assistant.v2.BuildVariant.IS_DEBUG} 是编译期常量，release 包里 {@link #ensure} / {@link #apply}
 * 直接返回，不探测、不执行任何 root 命令。原状态存 SharedPreferences {@code power_wl.original}；还原成功后清掉记录并关掉自动开关
 * （否则下次启动又会加回去）。
 */
public final class PowerWhitelistHooks {

    private PowerWhitelistHooks() {}

    private static final String TAG = "PowerWL";
    static final String PREFS = "power_wl";
    static final String K_AUTO = "auto";
    static final String K_ORIGINAL = "original";

    /** 测试注入：root 执行，返回输出（null = 守护没起来 / 超时 / 失败）。 */
    interface Root {
        String exec(Context ctx, java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> steps);
    }

    static Root sRoot = new Root() {
        @Override public String exec(Context ctx, java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> steps) {
            // 2026-10-25 exec 守护 v4：拆成单条命令依次执行（ShellScript），不再发复合脚本
            com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r =
                    com.onesay.assistant.v2.executor.ShellScript.exec(new com.onesay.assistant.v2.executor.ShellExecutor(ctx), steps);
            if (r == null || r.isDown || r.isTimeout || r.rc != 0) return null;
            return r.output == null ? "" : r.output;
        }
    };

    static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean variantSupported() {
        return com.onesay.assistant.v2.BuildVariant.IS_DEBUG;
    }

    public static boolean autoOn(Context ctx) {
        return sp(ctx).getBoolean(K_AUTO, true);
    }

    /** 服务启动时调（后台线程）：debug + 自动开 + 还没加过 → 探测原状态 → 存 → 加白名单。 */
    public static void ensure(final Context ctx) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;
        final Context app = ctx.getApplicationContext();
        if (!PowerWhitelist.shouldAutoApply(true, autoOn(app), sp(app).getString(K_ORIGINAL, ""))) return;
        com.onesay.assistant.v2.util.Threads.start("power-wl", new Runnable() {
            @Override public void run() {
                Log.i(TAG, "[POWER-WL] " + apply(app));
            }
        });
    }

    /** 加白名单（控制台「加入」也走这里）。返回给人看的一句话。 */
    public static synchronized String apply(Context ctx) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "release 包不改省电白名单（只在 debug 包生效）";
        Context app = ctx.getApplicationContext();
        String pkg = app.getPackageName();
        int sdk = Build.VERSION.SDK_INT;
        String saved = sp(app).getString(K_ORIGINAL, "");
        PowerWhitelist.Original o = PowerWhitelist.Original.fromJson(saved);
        if (o == null) {
            String out = sRoot.exec(app, PowerWhitelist.probeSteps(pkg, sdk));
            o = PowerWhitelist.parse(out, pkg, sdk, System.currentTimeMillis());
            if (o == null) return "读不到当前状态（root 守护没就绪？），没有改动";
            // 先存原状态再改：改到一半被杀也能还原
            sp(app).edit().putString(K_ORIGINAL, o.toJson()).putBoolean(K_AUTO, true).commit();
        }
        java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> steps = PowerWhitelist.applySteps(o);
        if (steps.isEmpty()) return "已经在省电白名单里，没有改动（原状态已记录）";
        return sRoot.exec(app, steps) != null ? "已加入省电白名单（Doze + 后台运行），原状态已记录" : "加入失败（root 命令没执行成功）";
    }

    /** 一键还原：按记录的原状态撤销；成功后清记录并关自动。 */
    public static synchronized String restore(Context ctx) {
        Context app = ctx.getApplicationContext();
        PowerWhitelist.Original o = PowerWhitelist.Original.fromJson(sp(app).getString(K_ORIGINAL, ""));
        if (o == null) {
            sp(app).edit().putBoolean(K_AUTO, false).commit();
            return "没有改动记录，不需要还原（已关闭自动加入）";
        }
        if (!o.pkg.equals(app.getPackageName())) return "记录的包名不对，没有还原";
        java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> steps = PowerWhitelist.restoreSteps(o);
        if (!steps.isEmpty() && sRoot.exec(app, steps) == null) return "还原失败（root 命令没执行成功），记录保留，可以再试";
        sp(app).edit().remove(K_ORIGINAL).putBoolean(K_AUTO, false).commit();
        return "已还原到加入前的状态（已关闭自动加入）";
    }
}
