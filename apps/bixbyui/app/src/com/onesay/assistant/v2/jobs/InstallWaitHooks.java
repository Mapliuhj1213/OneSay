package com.onesay.assistant.v2.jobs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ★ install-wait（PLAN-install-wait-resume）Android 侧：持久化（SharedPreferences）、WakeService 动态 PACKAGE_ADDED（Android 8 Manifest 收不到）、
 * 5 s 轮询兜底（等待开始后 30 min 内；之后 60 s）、15 min 提醒一次、2 h 过期、装好后岛上 3 s 未取消即接续原任务、商店无网络时岛上给选项。
 * 读屏看门狗本轮只做「无网络」检测（ConnectivityManager），下载进度 60 s 不动 / 商店要账号 暂未做。
 */
public final class InstallWaitHooks {
    private static final String TAG = "InstallWait";
    private static final String PREFS = "install_wait";
    private static final String KEY = "jobs";
    private static final long FAST_WINDOW_MS = 30 * 60 * 1000L;
    private static final long SLOW_POLL_MS = 60 * 1000L;

    private static Context sApp;
    private static BroadcastReceiver sRx;
    private static final Handler sH = new Handler(Looper.getMainLooper());
    private static boolean sTicking;

    private InstallWaitHooks() {}

    /** WakeService.onCreate（含开机 / 进程重建）：恢复等待任务、挂接收器、先查一次是否已装好。 */
    public static synchronized void attach(Context ctx) {
        try {
            if (ctx == null) return;
            sApp = ctx.getApplicationContext();
            if (sRx == null) {
                InstallWait.setLive(InstallWait.fromJson(prefs().getString(KEY, "")));
                com.onesay.assistant.v2.s3.S3Engine.sWaitChanged = new Runnable() {
                    @Override public void run() { save(); }
                };
                sRx = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent it) {
                        String pkg = it.getData() == null ? null : it.getData().getSchemeSpecificPart();
                        boolean rep = it.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                        Log.i(TAG, "[INSTALL-WAIT] PACKAGE_ADDED " + pkg + " replacing=" + rep);
                        for (InstallWait.Job j : InstallWait.live().onPackageAdded(pkg, rep, System.currentTimeMillis())) onInstalled(j);
                        save();
                    }
                };
                IntentFilter f = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
                f.addDataScheme("package");
                sApp.registerReceiver(sRx, f);
                Log.i(TAG, "[INSTALL-WAIT] attach，恢复等待任务 " + InstallWait.live().active().size() + " 个");
            }
            pollNow();
            ensureTicking();
        } catch (Throwable t) {
            Log.w(TAG, "attach: " + t.getMessage());
        }
    }

    /** 应用网格点了未安装的格子（下载）：登记等待任务（点的那个排第一，其余未装候选随后，最多 4 个）。 */
    public static void register(Context ctx, String query, String goal, String checkpoint, List<String> pkgs, List<String> labels) {
        try {
            attach(ctx);
            InstallWait.Job j = InstallWait.live().register(query, goal, checkpoint, pkgs, labels, System.currentTimeMillis());
            if (j == null) return;
            save();
            Log.i(TAG, "[INSTALL-WAIT] 登记 " + j.id + " pkgs=" + j.pkgs + " 截止 2h");
            com.onesay.assistant.v2.s1.S1Hooks.onTaskNote(j.waitingText());
            ensureTicking();
            checkStoreNetwork(j);
        } catch (Throwable t) {
            Log.w(TAG, "register: " + t.getMessage());
        }
    }

    /** ★ backlog #3：安装卡点了「不用了」/ 用户说「不用了」—— 取消这张卡对应的等装登记并存盘。 */
    public static int cancelFor(Context ctx, List<String> pkgs) {
        try {
            if (ctx != null && sApp == null) sApp = ctx.getApplicationContext();
            int n = InstallWait.live().cancelPkgs(pkgs);
            if (n > 0) {
                InstallWait.live().prune();
                save();
                Log.i(TAG, "[INSTALL-WAIT] 安装卡「不用了」取消等装 " + n + " 个 pkgs=" + pkgs);
            }
            return n;
        } catch (Throwable t) {
            Log.w(TAG, "cancelFor: " + t.getMessage());
            return 0;
        }
    }

    /** OneSay 回到前台时也查一次。 */
    public static void pollNow() {
        try {
            if (sApp == null) return;
            final PackageManager pm = sApp.getPackageManager();
            List<InstallWait.Job> hit = InstallWait.live().poll(new InstallWait.Installed() {
                @Override public boolean isInstalled(String p) {
                    try { pm.getPackageInfo(p, 0); return true; } catch (Throwable t) { return false; }
                }
            }, System.currentTimeMillis());
            for (InstallWait.Job j : hit) onInstalled(j);
            if (!hit.isEmpty()) save();
        } catch (Throwable t) {
            Log.w(TAG, "poll: " + t.getMessage());
        }
    }

    private static void ensureTicking() {
        if (sTicking || InstallWait.live().active().isEmpty()) return;
        sTicking = true;
        sH.postDelayed(sTick, InstallWait.POLL_MS);
    }

    private static final Runnable sTick = new Runnable() {
        @Override
        public void run() {
            sTicking = false;
            try {
                pollNow();
                long now = System.currentTimeMillis();
                long next = SLOW_POLL_MS;
                for (InstallWait.Job j : InstallWait.live().active()) {
                    if (now - j.createdAt < FAST_WINDOW_MS) next = InstallWait.POLL_MS;
                    InstallWait.Tick t = InstallWait.live().tick(j, now);
                    if (t == InstallWait.Tick.REMIND) remind(j);
                    else if (t == InstallWait.Tick.EXPIRE) Log.i(TAG, "[INSTALL-WAIT] " + j.id + " 2h 过期");
                }
                InstallWait.live().prune();
                save();
                if (!InstallWait.live().active().isEmpty()) {
                    sTicking = true;
                    sH.postDelayed(this, next);
                }
            } catch (Throwable t) {
                Log.w(TAG, "tick: " + t.getMessage());
            }
        }
    };

    /** 装好：岛上「X 装好了，接着帮你…」，3 s 内没点取消 → 从 checkpoint 接续原任务。 */
    static void onInstalled(final InstallWait.Job j) {
        Log.i(TAG, "[INSTALL-WAIT] 装好 " + j.installedPkg + " → 3s 后接续 " + j.id);
        com.onesay.assistant.v2.util.Threads.start("install-wait-resume", new Runnable() {
            @Override
            public void run() {
                String id = "iw_" + j.id;
                String a = null;
                if (com.onesay.assistant.v2.s1.S1Hooks.onAskUser(id, j.resumeText(), Arrays.asList("取消"), 3)) {
                    a = com.onesay.assistant.v2.s1.S1Hooks.awaitAskUser(id, InstallWait.RESUME_GRACE_MS + 500);
                } else {
                    try { Thread.sleep(InstallWait.RESUME_GRACE_MS); } catch (InterruptedException ignored) {}
                }
                if ("取消".equals(a)) {
                    InstallWait.live().cancel(j.id);
                    Log.i(TAG, "[INSTALL-WAIT] 用户取消接续 " + j.id);
                } else {
                    InstallWait.live().done(j.id);
                    Log.i(TAG, "[INSTALL-WAIT] 接续 " + j.id);
                    com.onesay.assistant.v2.ui.FloatingSheetService.show(sApp, j.resumeQuery());
                }
                InstallWait.live().prune();
                save();
            }
        });
    }

    private static void remind(final InstallWait.Job j) {
        com.onesay.assistant.v2.util.Threads.start("install-wait-remind", new Runnable() {
            @Override
            public void run() {
                String id = "iwr_" + j.id;
                if (!com.onesay.assistant.v2.s1.S1Hooks.onAskUser(id, "还要装" + j.firstLabel() + "吗？", Arrays.asList(InstallWait.REMIND_OPTIONS), 60)) return;
                if ("不用了".equals(com.onesay.assistant.v2.s1.S1Hooks.awaitAskUser(id, 62000L))) {
                    InstallWait.live().cancel(j.id);
                    InstallWait.live().prune();
                    save();
                }
            }
        });
    }

    /** 看门狗（本轮只做无网络）：商店打开时没网 → 岛上 重试 / 换商店 / 取消。 */
    static void checkStoreNetwork(final InstallWait.Job j) {
        if (online()) return;
        Log.i(TAG, "[INSTALL-WAIT] 商店无网络 → 给选项");
        com.onesay.assistant.v2.util.Threads.start("install-wait-net", new Runnable() {
            @Override
            public void run() {
                String id = "iwn_" + j.id;
                if (!com.onesay.assistant.v2.s1.S1Hooks.onAskUser(id, "应用商店连不上网络，" + j.firstLabel() + "下不了", Arrays.asList(InstallWait.STORE_FAIL_OPTIONS), 60)) return;
                String a = com.onesay.assistant.v2.s1.S1Hooks.awaitAskUser(id, 62000L);
                final com.onesay.assistant.v2.ui.FloatingSheetService svc = com.onesay.assistant.v2.ui.FloatingSheetService.getInstance();
                if ("取消".equals(a)) {
                    InstallWait.live().cancel(j.id);
                    InstallWait.live().prune();
                    save();
                } else if (("重试".equals(a) || "换商店".equals(a)) && svc != null) {
                    final boolean web = "换商店".equals(a);
                    sH.post(new Runnable() {
                        @Override public void run() {
                            com.onesay.assistant.v2.roles.RoleHooks.reopenInstall(svc, j.pkgs.get(0), j.firstLabel(), web);
                        }
                    });
                    if (!web) checkStoreNetwork(j);
                }
            }
        });
    }

    static boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) sApp.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    static synchronized void save() {
        try {
            if (sApp != null) prefs().edit().putString(KEY, InstallWait.live().toJson()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences prefs() {
        return sApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 网格的未装候选（点的那个排第一）。 */
    public static List<String>[] candidates(com.onesay.assistant.v2.roles.AppGridModel model, com.onesay.assistant.v2.roles.AppGridModel.Cell first) {
        List<String> p = new ArrayList<String>();
        List<String> l = new ArrayList<String>();
        p.add(first.pkg);
        l.add(first.label);
        for (com.onesay.assistant.v2.roles.AppGridModel.Cell c : model.cells) {
            if (!c.installed && !p.contains(c.pkg)) {
                p.add(c.pkg);
                l.add(c.label);
            }
        }
        @SuppressWarnings("unchecked") List<String>[] r = new List[]{p, l};
        return r;
    }
}
