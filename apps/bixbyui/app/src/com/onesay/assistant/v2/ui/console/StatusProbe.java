package com.onesay.assistant.v2.ui.console;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.UnifiedExecutor;
import com.onesay.assistant.v2.router.L2LLMCompiler;

/**
 * [console R12] 状态探测（方案 §8.1 StatusProbe）。全部后台线程、结果回主线程；先给本机能立即读到的（服务、权限、密钥），
 * 再给要走 root 通道的（Root、按键守护）。超过 {@link #DEADLINE_MS} 还没回来的项保持「未知」（null），绝不猜成正常。
 */
public final class StatusProbe {

    public interface Listener {
        /** 主线程；每有一批结果回来调用一次，最后一次 snapshot.done = true。 */
        void onStatus(ConsoleStatus.Snapshot s);
    }

    public static final long DEADLINE_MS = 10000L;

    private static volatile ConsoleStatus.Snapshot sLast;

    private StatusProbe() {}

    /** 上一次的结果（开页面时先画它，避免闪「检测中」）；没有就是 null。 */
    public static ConsoleStatus.Snapshot last() {
        return sLast;
    }

    /** 首帧用：还没探过时给一个「检测中」快照（见 {@link ConsoleStatus#pending}）。 */
    public static ConsoleStatus.Snapshot seed(Context ctx) {
        return new ConsoleStatus.Snapshot();
    }

    public static void run(final Context ctx, final L2LLMCompiler compiler, final Listener l) {
        final Context app = ctx.getApplicationContext();
        final Handler h = new Handler(Looper.getMainLooper());
        final ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();
        final boolean[] finished = {false};
        final Runnable deadline = new Runnable() {
            @Override public void run() {
                synchronized (finished) {
                    if (finished[0]) return;
                    finished[0] = true;
                }
                s.wakeRunning = com.onesay.assistant.v2.wake.WakeService.isServiceRunning();
                s.micReady = com.onesay.assistant.v2.wake.WakeService.isMicReady();
                s.done = true;
                sLast = copy(s);
                l.onStatus(copy(s));
            }
        };
        h.postDelayed(deadline, DEADLINE_MS);
        com.onesay.assistant.v2.util.Threads.start("console-status", new Runnable() {
            @Override public void run() {
                local(app, compiler, s);
                post(h, l, s, finished);
                ShellExecutor exec = null;
                try {
                    exec = UnifiedExecutor.getInstance(app).getShellExecutor();
                    ShellExecutor.ShellResponse r = exec.execute("echo OK");
                    s.rootOk = r.isSuccess() && "OK".equals(r.output.trim());
                } catch (Throwable t) {
                    s.rootOk = Boolean.FALSE;
                }
                try {   // ★ priv-llm：「Shizuku 特权」（插件关 = null）
                    s.shizukuOk = com.onesay.assistant.v2.platform.ExecBackendHost.shizukuReady(app);
                } catch (Throwable t) {
                    s.shizukuOk = null;
                }
                try {   // ★ design-1118：后台读剪贴板只能经 Shizuku 查（appops get，白名单内只读）
                    s.clipboardAllowed = clipboardAllowed(app);
                } catch (Throwable t) {
                    s.clipboardAllowed = null;
                }
                post(h, l, s, finished);
                // 服务可能刚被 MainActivity 拉起：再读一次
                s.wakeRunning = com.onesay.assistant.v2.wake.WakeService.isServiceRunning();
                s.micReady = com.onesay.assistant.v2.wake.WakeService.isMicReady();
                synchronized (finished) {
                    if (finished[0]) return;
                    finished[0] = true;
                }
                h.removeCallbacks(deadline);
                s.done = true;
                final ConsoleStatus.Snapshot c = copy(s);
                sLast = c;
                h.post(new Runnable() {
                    @Override public void run() {
                        l.onStatus(c);
                    }
                });
                // ★ refactor/oss：状态卡先出，再把 root 通道状态交给设备能力探测（只读，异常吞掉，不影响状态卡）
                try {
                    com.onesay.assistant.v2.platform.DeviceCapabilityProbe.onStatus(app, c.rootOk);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void post(Handler h, final Listener l, ConsoleStatus.Snapshot s, boolean[] finished) {
        synchronized (finished) {
            if (finished[0]) return;
        }
        final ConsoleStatus.Snapshot c = copy(s);
        h.post(new Runnable() {
            @Override public void run() {
                l.onStatus(c);
            }
        });
    }

    /** 本机直接能读到的部分（不走 root）。 */
    static void local(Context app, L2LLMCompiler compiler, ConsoleStatus.Snapshot s) {
        s.paused = com.onesay.assistant.v2.wake.AssistantPause.isPaused(app);
        // 冷启动：MainActivity 刚 startForegroundService，onStartCommand 还没跑 → 最多等 3 秒，别误报「未运行」（真机 R12）
        for (int i = 0; i < 30 && !com.onesay.assistant.v2.wake.WakeService.isServiceRunning(); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        s.wakeRunning = com.onesay.assistant.v2.wake.WakeService.isServiceRunning();
        s.micReady = com.onesay.assistant.v2.wake.WakeService.isMicReady();
        try {
            s.llmConfigured = compiler != null ? compiler.hasApiKey() : new L2LLMCompiler(app).hasApiKey();
        } catch (Throwable t) {
            s.llmConfigured = null;
        }
        try {
            s.visionModel = com.onesay.assistant.v2.vision.ScreenVisionEngine.effectiveModel(app);
        } catch (Throwable ignored) {
        }
        try {
            com.onesay.assistant.v2.asr.AliyunAsrConfig c = new com.onesay.assistant.v2.asr.AliyunAsrConfig(app);
            // r2/p0 P0-2：开关没开也等于用不了（WakeService.asrNotConfigured 同口径）
            s.asrConfigured = c.isEnabled() && (c.isComplete() || c.hasDashScopeKey());
            s.asrKeyInvalid = Boolean.TRUE.equals(s.asrConfigured) ? com.onesay.assistant.v2.asr.AsrHealth.isAuthFailed(app, c) : Boolean.FALSE;
        } catch (Throwable t) {
            s.asrConfigured = null;
            s.asrKeyInvalid = null;
        }
        s.overlayOk = overlayOk(app);
        try {
            com.onesay.assistant.v2.kernel.FeaturePlugin isl = com.onesay.assistant.v2.kernel.FeatureKernel.get().find("island");
            s.islandEnabled = isl == null || isl.enabled(app);
        } catch (Throwable ignored) {
        }
        s.batteryWhitelisted = batteryWhitelisted(app);
        s.micPermission = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        try {
            com.onesay.assistant.v2.asr.SpeakerVerifier sv = com.onesay.assistant.v2.asr.SpeakerVerifier.get(app);
            s.voiceprint = sv.isAvailable() ? Boolean.valueOf(sv.hasName("owner")) : null;
        } catch (Throwable t) {
            s.voiceprint = null;
        }
        perms(app, s);
    }

    /** ★ design-1118（移植 mz21-perm-1118）：「权限配置」页要的本机状态（都只读，不执行命令）。 */
    static void perms(Context app, ConsoleStatus.Snapshot s) {
        try {
            try {
                app.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
                s.shizukuInstalled = Boolean.TRUE;
            } catch (PackageManager.NameNotFoundException e) {
                s.shizukuInstalled = Boolean.FALSE;
            }
            com.onesay.assistant.v2.platform.ShizukuBridge br = new com.onesay.assistant.v2.platform.ShizukuBridge();
            s.shizukuRunning = br.running();
            s.shizukuGranted = Boolean.TRUE.equals(s.shizukuRunning) && br.granted();
            s.shizukuEnabled = com.onesay.assistant.v2.platform.ExecBackendHost.shizukuEnabled(app);
        } catch (Throwable ignored) {
        }
        try {   // 开源版：targetSdk 28 不受包可见性限制；读到的第三方应用很少 = ROM 的「获取应用列表」没给
            java.util.List<android.content.pm.PackageInfo> all = app.getPackageManager().getInstalledPackages(0);
            int third = 0;
            for (android.content.pm.PackageInfo pi : all) {
                if (pi.applicationInfo != null && (pi.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) third++;
            }
            s.appListPermMissing = Boolean.valueOf(third <= 1);
            s.appListLimited = Boolean.valueOf(third > 1 && third < 4);
        } catch (Throwable t) {
            s.appListPermMissing = null;
        }
        String pkg = app.getPackageName();
        try {
            app.getPackageManager().getServiceInfo(new android.content.ComponentName(pkg, A11Y_SERVICE), 0);
            s.a11yAvailable = true;
        } catch (Throwable t) {
            s.a11yAvailable = false;
        }
        s.a11yOn = secureContains(app, "enabled_accessibility_services", pkg + "/");
        s.notifListenerOn = secureContains(app, "enabled_notification_listeners", pkg + "/");
        s.defaultAssistant = secureContains(app, "voice_interaction_service", pkg + "/");
        if (s.clipboardAllowed == null) s.clipboardAllowed = lastClipboard(app);
        try {
            s.cloudTtsOn = new com.onesay.assistant.v2.tts.CloudTtsConfig(app).isEnabled();
            s.cloudTtsKey = new com.onesay.assistant.v2.asr.AliyunAsrConfig(app).hasDashScopeKey();
        } catch (Throwable t) {
            s.cloudTtsOn = null;
            s.cloudTtsKey = null;
        }
    }

    static final String A11Y_SERVICE = "com.onesay.assistant.v2.a11y.OneSayA11yService";
    static final String PREFS = "onesay_console_status";

    static Boolean secureContains(Context app, String key, String needle) {
        try {
            String v = Settings.Secure.getString(app.getContentResolver(), key);
            return Boolean.valueOf(v != null && v.contains(needle));
        } catch (Throwable t) {
            return null;
        }
    }

    static final String K_CLIPBOARD = "clipboard_allowed";

    /** 经 Shizuku 只读查询 appops READ_CLIPBOARD（白名单内）；Shizuku 不可用 → 上次记下的结果（没有就 null）。 */
    static Boolean clipboardAllowed(Context app) {
        com.onesay.plugin.exec.shizuku.ShizukuExecBackend ex = com.onesay.assistant.v2.platform.ExecBackendHost.shizuku();
        if (ex == null || !Boolean.TRUE.equals(com.onesay.assistant.v2.platform.ExecBackendHost.shizukuReady(app))) return lastClipboard(app);
        com.onesay.plugin.api.exec.ExecResult r = ex.run("appops get " + app.getPackageName() + " READ_CLIPBOARD", 4000);
        if (r == null || r.rc != 0) return lastClipboard(app);
        Boolean v = Boolean.valueOf(PermSetup.clipboardAllowed(r.output));
        rememberClipboard(app, v);
        return v;
    }

    static Boolean lastClipboard(Context app) {
        try {
            android.content.SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return p.contains(K_CLIPBOARD) ? Boolean.valueOf(p.getBoolean(K_CLIPBOARD, false)) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static void rememberClipboard(Context app, Boolean v) {
        try {
            if (v != null) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_CLIPBOARD, v).apply();
        } catch (Throwable ignored) {
        }
    }

    public static boolean overlayOk(Context ctx) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx);
    }

    public static Boolean batteryWhitelisted(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm == null ? null : Boolean.valueOf(pm.isIgnoringBatteryOptimizations(ctx.getPackageName()));
        } catch (Throwable t) {
            return null;
        }
    }

    static ConsoleStatus.Snapshot copy(ConsoleStatus.Snapshot a) {
        ConsoleStatus.Snapshot b = new ConsoleStatus.Snapshot();
        b.wakeRunning = a.wakeRunning;
        b.micReady = a.micReady;
        b.paused = a.paused;
        b.rootOk = a.rootOk;
        b.shizukuOk = a.shizukuOk;
        b.llmConfigured = a.llmConfigured;
        b.visionModel = a.visionModel;
        b.asrConfigured = a.asrConfigured;
        b.overlayOk = a.overlayOk;
        b.islandEnabled = a.islandEnabled;
        b.batteryWhitelisted = a.batteryWhitelisted;
        b.voiceprint = a.voiceprint;
        b.micPermission = a.micPermission;
        b.asrKeyInvalid = a.asrKeyInvalid;
        b.shizukuInstalled = a.shizukuInstalled;
        b.shizukuRunning = a.shizukuRunning;
        b.shizukuGranted = a.shizukuGranted;
        b.shizukuEnabled = a.shizukuEnabled;
        b.appListConsent = a.appListConsent;
        b.appListPermMissing = a.appListPermMissing;
        b.appListLimited = a.appListLimited;
        b.a11yAvailable = a.a11yAvailable;
        b.a11yOn = a.a11yOn;
        b.notifListenerOn = a.notifListenerOn;
        b.clipboardAllowed = a.clipboardAllowed;
        b.defaultAssistant = a.defaultAssistant;
        b.cloudTtsOn = a.cloudTtsOn;
        b.cloudTtsKey = a.cloudTtsKey;
        b.done = a.done;
        return b;
    }
}
