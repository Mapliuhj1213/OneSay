package com.onesay.assistant.v2.a11y;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ a11y-1118：「打开无障碍」入口。点界面任务因为读屏助手和无障碍读屏服务都用不了而没做成时（{@link A11yState#choose} 给出
 * offerA11ySettings），结果卡上挂一个「打开无障碍」芯片：点了直接进系统无障碍设置（能直达本服务详情页就直达），
 * 有 Shizuku 且是 Android 13+ 时先替用户放开「受限设置」（侧载应用不放开就打不开无障碍开关）。
 */
public final class A11yEntry {
    private A11yEntry() {}

    static final String TAG = "A11yEntry";
    public static final String CHIP = "打开无障碍";
    /** 记号有效期：这么久内收工的结果卡才挂芯片（避免挂到无关的下一次任务上）。 */
    static final long WINDOW_MS = 120_000L;

    private static volatile long sNeedAt;

    /** PhoneHooks / 编号点选开局判完后端调用：need = 要不要给入口。 */
    public static void note(boolean need) {
        sNeedAt = need ? System.currentTimeMillis() : 0L;
    }

    /** 纯函数（JVM 可测）。 */
    static boolean pending(long now, long at) {
        return at > 0 && now >= at && now - at < WINDOW_MS;
    }

    public static boolean pendingNow() {
        return pending(System.currentTimeMillis(), sNeedAt);
    }

    /** 面板上要给用户看的芯片（没有入口时为空）。 */
    public static List<String> chips(boolean pending) {
        List<String> l = new ArrayList<String>();
        if (pending) l.add(CHIP);
        return l;
    }

    /** FloatingSheetService 收工上屏后调（主线程）：有记号就挂「打开无障碍」芯片，记号用掉即清。 */
    public static void onAgentResultShown(final com.onesay.assistant.v2.ui.FloatingSheetService svc) {
        try {
            if (svc == null || !pendingNow()) return;
            sNeedAt = 0L;
            final List<String> labels = chips(true);
            final com.onesay.assistant.v2.ui.ActionChipsView v = chipsView(svc);
            if (v == null) return;
            v.setListener(new com.onesay.assistant.v2.ui.ActionChipsView.Listener() {
                @Override public void onChipClicked(int index) {
                    if (index < 0 || index >= labels.size()) return;
                    v.hideChips();
                    open(svc.getApplicationContext());
                }
            });
            v.showChips(labels);
            Log.i(TAG, "[A11Y] 结果卡挂上「" + CHIP + "」入口");
        } catch (Throwable t) {
            Log.w(TAG, "[A11Y] 入口芯片异常: " + t.getClass().getSimpleName());
        }
    }

    static com.onesay.assistant.v2.ui.ActionChipsView chipsView(com.onesay.assistant.v2.ui.FloatingSheetService svc) {
        com.onesay.assistant.v2.ui.FloatingSheetView sheet = svc.getSheetView();
        if (sheet == null) return null;
        View existing = sheet.findViewWithTag(com.onesay.assistant.v2.ui.ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof com.onesay.assistant.v2.ui.ActionChipsView) return (com.onesay.assistant.v2.ui.ActionChipsView) existing;
        com.onesay.assistant.v2.ui.ActionChipsView v = new com.onesay.assistant.v2.ui.ActionChipsView(svc);
        int idx = Math.min(2, sheet.getChildCount());   // 与诊断 / 识屏 / 快捷指令芯片同一位置
        sheet.addView(v, idx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    /** 进系统无障碍设置（任意线程）。Android 13+ 且 Shizuku 可用时先放开受限设置（仍过 Shizuku 白名单）。 */
    public static void open(final Context ctx) {
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        com.onesay.assistant.v2.util.Threads.start("a11y-entry", new Runnable() {
            @Override public void run() {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && com.onesay.assistant.v2.platform.ExecBackendHost.shizukuTier(app)) {
                        com.onesay.plugin.exec.shizuku.ShizukuExecBackend s = com.onesay.assistant.v2.platform.ExecBackendHost.shizuku();
                        if (s != null) s.run("appops set " + app.getPackageName() + " ACCESS_RESTRICTED_SETTINGS allow", 5000);
                    }
                } catch (Throwable ignored) {
                }
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        startSettings(app);
                    }
                });
            }
        });
    }

    static void startSettings(Context app) {
        Intent detail = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        detail.putExtra("android.intent.extra.COMPONENT_NAME", new ComponentName(app.getPackageName(), A11yState.SERVICE_CLASS).flattenToString());
        detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30 && detail.resolveActivity(app.getPackageManager()) != null) {
                app.startActivity(detail);
                toast(app, "把 OneSay 的「读屏与按键唤醒」打开，然后再说一遍");
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent it = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(it);
            toast(app, "在列表里找到 OneSay 的「读屏与按键唤醒」并打开，然后再说一遍");
        } catch (Throwable t) {
            toast(app, "打不开无障碍设置，请到系统设置 › 无障碍里手动打开 OneSay 读屏服务");
        }
    }

    static void toast(final Context app, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
