package com.onesay.assistant.v2.suggest;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * ★ feat/suggest：前台包名（只用于私密判定 / 副标题，不进日志）。双路径：
 * <ol>
 *   <li>UsageStatsManager.queryEvents 最近 15 s 的最后一次 MOVE_TO_FOREGROUND（要 usage appop；快、无 root）；</li>
 *   <li>取不到且允许 root：守护只读命令 {@code dumpsys activity activities | grep -m2 …}（executePoll，≤1.5 s）。</li>
 * </ol>
 * 都取不到 = null（截图场景按「未知」照常弹、不显示包名）。日志只记用的哪条路。
 */
public final class ForegroundProbe {
    private ForegroundProbe() {
    }

    static final String TAG = "Suggest";
    static final String ACTIVITY_CMD = "dumpsys activity activities | grep -m2 -E 'mResumedActivity|mFocusedActivity'";

    private static volatile String sLastVia = "-";

    public static String lastVia() {
        return sLastVia;
    }

    /** @return {pkg, label}；取不到返回 null。 */
    public static String[] now(Context ctx, boolean allowRoot) {
        String pkg = viaUsage(ctx);
        String via = "usage";
        if (pkg == null && allowRoot) {
            pkg = viaRoot(ctx);
            via = "root";
        }
        if (pkg == null) {
            sLastVia = "none";
            return null;
        }
        if (!via.equals(sLastVia)) Log.i(TAG, "[SUGGEST] 前台包名来源=" + via);
        sLastVia = via;
        return new String[]{pkg, label(ctx, pkg)};
    }

    static String viaUsage(Context ctx) {
        try {
            UsageStatsManager um = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (um == null) return null;
            long now = System.currentTimeMillis();
            UsageEvents ev = um.queryEvents(now - 15000L, now + 1000L);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (ev != null && ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.getPackageName();
            }
            return last;
        } catch (Throwable t) {
            return null;
        }
    }

    static String viaRoot(Context ctx) {
        try {
            com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r = new com.onesay.assistant.v2.executor.ShellExecutor(ctx).executePoll(ACTIVITY_CMD);
            if (r == null || !r.isSuccess()) return null;
            String[] pa = com.onesay.assistant.v2.vision.screenref.UiDumpParser.parseResumedActivity(r.output);
            return pa == null ? null : pa[0];
        } catch (Throwable t) {
            return null;
        }
    }

    public static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            return l == null ? null : l.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
