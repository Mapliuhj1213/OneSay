package com.onesay.assistant.v2.platform;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.File;

/**
 * 设备能力探测（Android 适配层，refactor/oss 2026-11-06）。
 *
 * <h3>只读、不打扰</h3>
 * <ul>
 *   <li>高版本（Android 11–16）：包可见性过滤下「查不到」记 UNKNOWN；通知权限（13+）、精确闹钟（12+）低于引入版本视为有；
 *       API 31+ 的方法只用反射调用（仓库按 android-28 编译）；</li>
 *   <li>只查 Build 字段、PackageManager、权限、Settings.Secure 和几个 su 路径是否存在；<b>不</b>执行 su（不会弹 Magisk 授权、不留 su 日志）；</li>
 *   <li>root / 守护状态直接复用控制台状态探测（StatusProbe 经已有的 root 通道探到的结果），通过 {@link #onStatus} 回填；</li>
 *   <li>结果存 prefs {@link #PREFS}，下次先画缓存；探测失败的项记 {@link Tri#UNKNOWN}，从不猜成「没有」。</li>
 * </ul>
 * 触发时机：每次控制台状态探测结束（打开 App / 回到控制台，包括首次安装后第一次打开）以及打开「设备能力与功能」页时。
 */
public final class DeviceCapabilityProbe {

    private DeviceCapabilityProbe() {}

    private static final String TAG = "[PLATFORM]";
    public static final String PREFS = "bixby_device_caps";
    static final String K_PROFILE = "profile";
    static final String K_ROOT = "root_ok", K_KEY_DAEMON = "key_daemon";

    static final String PKG_SAMSUNG_BROWSER = "com.sec.android.app.sbrowser";
    static final String PKG_SAMSUNG_CLOCK = "com.sec.android.app.clockpackage";
    static final String PKG_GALAXY_STORE = "com.sec.android.app.samsungapps";
    static final String PKG_BIXBY_AGENT = "com.samsung.android.bixby.agent";
    static final String PKG_BIXBY_WAKEUP = "com.samsung.android.bixby.wakeup";
    static final String PKG_SHIZUKU = "moe.shizuku.privileged.api";
    /**
     * 仅作「看得到 su」的弱信号。高版本上 Magisk（/debug_ramdisk）、KernelSU / APatch（不放 su 到 PATH，App 侧通常看不到）
     * 都可能探不到——探不到只会让 root 落到「未确认」（不置灰），以 root 执行守护是否在线为准。
     */
    static final String[] SU_PATHS = {"/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su",
            "/system_ext/bin/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"};
    static final String PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";   // API 33，android-28 SDK 里没有常量
    static final String PERM_QUERY_ALL_PACKAGES = "android.permission.QUERY_ALL_PACKAGES";   // API 30

    private static volatile DeviceProfile sLast;

    /** 上次探测结果（内存 → prefs 缓存）；都没有返回 null。 */
    public static DeviceProfile cached(Context ctx) {
        DeviceProfile p = sLast;
        if (p != null || ctx == null) return p;
        try {
            p = DeviceProfile.fromJson(sp(ctx).getString(K_PROFILE, null));
        } catch (Throwable t) {
            p = null;
        }
        sLast = p;
        return p;
    }

    /**
     * 控制台状态探测结束时回填 root 通道 / 按键守护的状态（null = 没探到，保留上次的值），并重新探测一遍。
     * 在后台线程调用（StatusProbe 的工作线程）；任何异常都吞掉，不影响状态探测本身。
     */
    public static void onStatus(Context ctx, Boolean rootOk, Boolean keyDaemonAlive) {
        if (ctx == null) return;
        try {
            Context app = ctx.getApplicationContext();
            SharedPreferences.Editor e = sp(app).edit();
            if (rootOk != null) e.putString(K_ROOT, Tri.of(rootOk).name());
            if (keyDaemonAlive != null) e.putString(K_KEY_DAEMON, Tri.of(keyDaemonAlive).name());
            e.apply();
            probe(app);
        } catch (Throwable t) {
            Log.w(TAG, "onStatus 失败：" + t);
        }
    }

    /** 立即探测并缓存（不执行 su，耗时在几十毫秒内；可在主线程调用，但建议后台）。 */
    public static DeviceProfile probe(Context ctx) {
        Context app = ctx.getApplicationContext();
        PackageManager pm = app.getPackageManager();
        SharedPreferences sp = sp(app);
        String abi = null;
        try {
            abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : null;
        } catch (Throwable ignored) {
        }
        DeviceProfile.Builder b = new DeviceProfile.Builder()
                .manufacturer(Build.MANUFACTURER).brand(Build.BRAND).model(Build.MODEL)
                .sdkInt(Build.VERSION.SDK_INT).abi(abi).probedAtMs(System.currentTimeMillis());

        // root：以执行守护是否在线为准（StatusProbe 经 root 通道探到的结果）。
        //   守护在线 → 有；守护明确不在线且 App 看不到任何 su → 没有；其余（还没探 / 有 su 但守护没起）→ 不确定。
        //   「不确定」不会置灰任何功能，所以第一次打开、状态探测还没回来时 Note 8 上一切照旧。
        Tri daemon = tri(sp.getString(K_ROOT, null));
        b.set(DeviceCap.EXEC_DAEMON, daemon);
        Tri root = rootFrom(daemon, anySuBinary());
        b.set(DeviceCap.ROOT, root);

        Tri keyDaemon = tri(sp.getString(K_KEY_DAEMON, null));
        if (root == Tri.NO) keyDaemon = Tri.NO;
        b.set(DeviceCap.KEY_DAEMON, keyDaemon);

        boolean samsung = "samsung".equalsIgnoreCase(trim(Build.MANUFACTURER)) || "samsung".equalsIgnoreCase(trim(Build.BRAND));
        // Bixby 键：守护在跑 = 有；不是三星 = 没有；三星但守护没探到 = 不确定（有的三星机型没有 Bixby 键）
        b.set(DeviceCap.BIXBY_KEY, keyDaemon == Tri.YES ? Tri.YES : (samsung ? Tri.UNKNOWN : Tri.NO));

        // 包可见性（Android 11+ 且本 App targetSdk ≥ 30）：没在 <queries> 声明的包查不到 ≠ 没装 → 记「未确认」，不置灰
        int sdk = Build.VERSION.SDK_INT;
        int target = 0;
        try {
            target = app.getApplicationInfo().targetSdkVersion;
        } catch (Throwable ignored) {
        }
        boolean queryAll = granted(app, PERM_QUERY_ALL_PACKAGES) == Tri.YES;
        b.set(DeviceCap.SAMSUNG_BROWSER, installed(pm, PKG_SAMSUNG_BROWSER, sdk, target, queryAll));
        b.set(DeviceCap.SAMSUNG_CLOCK, installed(pm, PKG_SAMSUNG_CLOCK, sdk, target, queryAll));
        b.set(DeviceCap.GALAXY_STORE, installed(pm, PKG_GALAXY_STORE, sdk, target, queryAll));
        Tri agent = installed(pm, PKG_BIXBY_AGENT, sdk, target, queryAll), wakeup = installed(pm, PKG_BIXBY_WAKEUP, sdk, target, queryAll);
        b.set(DeviceCap.BIXBY_SYSTEM, agent == Tri.YES || wakeup == Tri.YES ? Tri.YES
                : (agent == Tri.NO && wakeup == Tri.NO ? Tri.NO : Tri.UNKNOWN));
        b.set(DeviceCap.SHIZUKU, installed(pm, PKG_SHIZUKU, sdk, target, queryAll));

        b.set(DeviceCap.MIC, granted(app, Manifest.permission.RECORD_AUDIO));
        // 高版本运行时门槛：低于引入版本时视为「有」（安装即授予 / 无需授权）
        b.set(DeviceCap.POST_NOTIFICATIONS, gate(sdk, 33, sdk >= 33 ? granted(app, PERM_POST_NOTIFICATIONS) : Tri.YES));
        b.set(DeviceCap.EXACT_ALARM, gate(sdk, 31, sdk >= 31 ? exactAlarm(app) : Tri.YES));
        b.set(DeviceCap.OVERLAY, overlay(app));
        String pkg = app.getPackageName();
        b.set(DeviceCap.ACCESSIBILITY, secureContains(app, "enabled_accessibility_services", pkg + "/"));
        b.set(DeviceCap.NOTIFICATION_LISTENER, secureContains(app, "enabled_notification_listeners", pkg + "/"));
        b.set(DeviceCap.DEFAULT_ASSISTANT, secureContains(app, "voice_interaction_service", pkg + "/"));

        DeviceProfile p = b.deriveStatic().build();
        sLast = p;
        try {
            sp.edit().putString(K_PROFILE, p.toJson().toString()).apply();
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "能力探测：" + p.summary());
        return p;
    }

    /** 后台线程探测（打开能力页时用）；done 在后台线程回调，可为 null。 */
    public static void probeAsync(final Context ctx, final Runnable done) {
        final Context app = ctx.getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("bixby-caps-probe", new Runnable() {
            @Override public void run() {
                try {
                    probe(app);
                } catch (Throwable t) {
                    Log.w(TAG, "探测失败：" + t);
                }
                if (done != null) done.run();
            }
        });
    }

    // ------------------------------------------------------------------ 小工具

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** root 判定（纯函数，JVM 测试）。 */
    static Tri rootFrom(Tri daemon, boolean suVisible) {
        if (daemon == Tri.YES) return Tri.YES;
        if (daemon == Tri.NO && !suVisible) return Tri.NO;
        return Tri.UNKNOWN;
    }

    static Tri tri(String s) {
        if (s == null) return Tri.UNKNOWN;
        try {
            return Tri.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Tri.UNKNOWN;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean anySuBinary() {
        for (String p : SU_PATHS) {
            try {
                if (new File(p).exists()) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static Tri installed(PackageManager pm, String pkg, int sdk, int targetSdk, boolean queryAll) {
        try {
            pm.getPackageInfo(pkg, 0);
            return Tri.YES;
        } catch (PackageManager.NameNotFoundException e) {
            return notFound(sdk, targetSdk, queryAll);
        } catch (Throwable t) {
            return Tri.UNKNOWN;
        }
    }

    /**
     * 查包 NameNotFound 时怎么记（纯函数，JVM 测试）：Android 11+ 且 targetSdk ≥ 30、又没有 QUERY_ALL_PACKAGES 时，
     * 包可见性过滤会把「装了但不可见」也报成找不到 → UNKNOWN；其余情况可信 → NO。
     */
    static Tri notFound(int sdk, int targetSdk, boolean queryAll) {
        if (sdk >= 30 && targetSdk >= 30 && !queryAll) return Tri.UNKNOWN;
        return Tri.NO;
    }

    /** 运行时门槛（纯函数）：系统版本低于引入该权限的版本 → YES（不需要授权）；否则用探到的值。 */
    static Tri gate(int sdk, int introducedIn, Tri probed) {
        if (sdk > 0 && sdk < introducedIn) return Tri.YES;
        return probed == null ? Tri.UNKNOWN : probed;
    }

    /** AlarmManager.canScheduleExactAlarms()（API 31；android-28 SDK 编译，只能反射）。 */
    private static Tri exactAlarm(Context ctx) {
        try {
            Object am = ctx.getSystemService(Context.ALARM_SERVICE);
            Object r = am.getClass().getMethod("canScheduleExactAlarms").invoke(am);
            return r instanceof Boolean ? Tri.of((Boolean) r) : Tri.UNKNOWN;
        } catch (Throwable t) {
            return Tri.UNKNOWN;
        }
    }

    private static Tri granted(Context ctx, String perm) {
        try {
            return Tri.of(ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED);
        } catch (Throwable t) {
            return Tri.UNKNOWN;
        }
    }

    private static Tri overlay(Context ctx) {
        try {
            return Tri.of(Settings.canDrawOverlays(ctx));
        } catch (Throwable t) {
            return Tri.UNKNOWN;
        }
    }

    private static Tri secureContains(Context ctx, String key, String needle) {
        try {
            String v = Settings.Secure.getString(ctx.getContentResolver(), key);
            return Tri.of(v != null && v.contains(needle));
        } catch (Throwable t) {
            return Tri.UNKNOWN;
        }
    }
}
