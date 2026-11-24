package com.onesay.assistant.v2.selfset;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.onesay.assistant.v2.entry.BuiltinEntries;
import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.platform.Tri;

/**
 * [settings-diag-1124] 设置清单的 Android 侧：每一项怎么读、怎么写（一律调各功能原来的 setter，带原来的副作用），前提怎么查，去处怎么打开。
 * 新加一项：{@link SelfCatalog} 里登记，这里 read / write 各补一个分支（SelfSettingsWiringTest 守住两边对得上）。
 */
public final class SelfSettingsAndroid {

    private SelfSettingsAndroid() {}

    public static SelfStore store(Context ctx) {
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        return new SelfStore() {
            @Override public Object read(String id) {
                return SelfSettingsAndroid.read(app, id);
            }

            @Override public boolean write(String id, Object value) {
                return SelfSettingsAndroid.write(app, id, value);
            }

            @Override public Tri need(String needId) {
                return SelfSettingsAndroid.need(app, needId);
            }

            @Override public boolean killed(String id) {
                return SelfSettingsAndroid.killed(app, id);
            }
        };
    }

    static boolean entryOn(Context app, String id) {
        com.onesay.assistant.v2.entry.Entries.ensureInit(app);
        return com.onesay.plugin.api.entry.EntryRegistry.isEnabled(id);
    }

    static Object read(Context app, String id) {
        try {
            if (id.startsWith("entry.")) return Boolean.valueOf(entryOn(app, id));
            if ("privacy.redact".equals(id)) return Boolean.valueOf(com.onesay.assistant.v2.privacy.PrivacySettings.REDACT.on(app));
            if ("reply.voice".equals(id)) return Boolean.valueOf(com.onesay.assistant.v2.ui.TtsSpeaker.isVoiceTurnReplyEnabled(app));
            if ("reply.full".equals(id)) return Boolean.valueOf(com.onesay.assistant.v2.ui.TtsSpeaker.isFullAnswerEnabled(app));
            if ("wake.type_first".equals(id)) return Boolean.valueOf(com.onesay.assistant.v2.ui.wake.WakeUiSettings.typeFirst(app));
            if ("think.mode".equals(id)) return com.onesay.assistant.v2.net.ThinkingMode.key(com.onesay.assistant.v2.net.ThinkingMode.level());
            if ("read.direct".equals(id)) return Boolean.valueOf(com.onesay.assistant.v2.router.ReadOnlyAnswer.isEnabled(app));
            if ("plug.island".equals(id)) return Boolean.valueOf(com.onesay.assistant.v2.island.IslandSettings.pref(app, com.onesay.assistant.v2.island.IslandSettings.KEY_ENABLED));
            if ("plug.notice".equals(id)) return Boolean.valueOf(com.onesay.assistant.v2.notice.NoticeSettings.SWITCH.on(app));
            if ("plug.quicknote".equals(id)) return Boolean.valueOf(com.onesay.assistant.v2.kernel.plugins.QuickNotePlugin.SWITCH.on(app));
            if ("plug.memory".equals(id)) return Boolean.valueOf(com.onesay.assistant.v2.s2.S2Settings.pref(app, com.onesay.assistant.v2.s2.S2Settings.KEY_MEMORY));
            if ("plug.diag".equals(id)) return Boolean.valueOf(com.onesay.assistant.v2.diag.DiagSettings.pref(app, com.onesay.assistant.v2.diag.DiagSettings.KEY_ENABLED));
            if ("plug.phone".equals(id)) return Boolean.valueOf(app.getSharedPreferences(com.onesay.assistant.v2.phone.PhoneHooks.PREF, Context.MODE_PRIVATE)
                    .getBoolean(com.onesay.assistant.v2.phone.PhoneHooks.KEY_ENABLED, true));
            if ("plug.pclink".equals(id)) return Boolean.valueOf(com.onesay.assistant.v2.pclink.PcLink.SWITCH.on(app));
            if ("exec.shizuku".equals(id)) return Boolean.valueOf(com.onesay.assistant.v2.platform.ExecBackendHost.shizukuEnabled(app));
            if ("assist.on".equals(id)) return Boolean.valueOf(!com.onesay.assistant.v2.wake.AssistantPause.isPaused(app));
            SelfCatalog.Item it = SelfCatalog.get(id);
            if (it != null && it.kind == SelfCatalog.Kind.PERMISSION) {
                Tri t = need(app, it.needId);
                return t == Tri.UNKNOWN ? null : Boolean.valueOf(t == Tri.YES);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean write(Context app, String id, Object v) {
        try {
            if ("think.mode".equals(id)) {
                com.onesay.assistant.v2.router.ctx.ContextPrefs.setThinking(app, String.valueOf(v));
                return true;
            }
            boolean on = Boolean.TRUE.equals(v);
            if (id.startsWith("entry.")) {
                com.onesay.assistant.v2.entry.Entries.setEnabled(app, id, on);
                if (BuiltinEntries.WAKE_WORD.equals(id)) restartWake(app);
                return true;
            }
            if ("privacy.redact".equals(id)) com.onesay.assistant.v2.privacy.PrivacySettings.REDACT.set(app, on);
            else if ("reply.voice".equals(id)) com.onesay.assistant.v2.ui.TtsSpeaker.setVoiceTurnReplyEnabled(app, on);
            else if ("reply.full".equals(id)) com.onesay.assistant.v2.ui.TtsSpeaker.setFullAnswerEnabled(app, on);
            else if ("wake.type_first".equals(id)) com.onesay.assistant.v2.ui.wake.WakeUiSettings.setTypeFirst(app, on);
            else if ("read.direct".equals(id)) com.onesay.assistant.v2.router.ReadOnlyAnswer.setEnabled(app, on);
            else if ("plug.island".equals(id)) {
                com.onesay.assistant.v2.island.IslandSettings.set(app, com.onesay.assistant.v2.island.IslandSettings.KEY_ENABLED, on);
                com.onesay.assistant.v2.s1.S1Hooks.refreshIslandMode("selfset");
            } else if ("plug.notice".equals(id)) {
                com.onesay.assistant.v2.notice.NoticeSettings.SWITCH.set(app, on);
                com.onesay.assistant.v2.kernel.plugins.NoticePlugin.refresh(app);   // 同设置页：写开关 + 后台重新拉取提醒源
            }
            else if ("plug.quicknote".equals(id)) {
                com.onesay.assistant.v2.kernel.plugins.QuickNotePlugin.SWITCH.set(app, on);
                com.onesay.assistant.v2.wake.WakeService.syncGestureFlag(app);
            } else if ("plug.memory".equals(id)) com.onesay.assistant.v2.s2.S2Settings.set(app, com.onesay.assistant.v2.s2.S2Settings.KEY_MEMORY, on);
            else if ("plug.diag".equals(id)) com.onesay.assistant.v2.diag.DiagSettings.set(app, com.onesay.assistant.v2.diag.DiagSettings.KEY_ENABLED, on);
            else if ("plug.phone".equals(id)) app.getSharedPreferences(com.onesay.assistant.v2.phone.PhoneHooks.PREF, Context.MODE_PRIVATE).edit()
                    .putBoolean(com.onesay.assistant.v2.phone.PhoneHooks.KEY_ENABLED, on).commit();
            else if ("plug.pclink".equals(id)) com.onesay.assistant.v2.pclink.PcLink.SWITCH.set(app, on);
            else if ("exec.shizuku".equals(id)) com.onesay.assistant.v2.platform.ExecBackendHost.setShizukuEnabled(app, on);
            else return false;
            try {
                com.onesay.assistant.v2.kernel.FeatureKernel.get().refresh(app);   // 插件开关翻转 → onEnable / onDisable（和设置页拨开关一样）
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            com.onesay.assistant.v2.applog.AppLog.w("SelfSet", "写设置出错 " + id + ": " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 唤醒词开关变了：让唤醒服务重新加载（和设置页「重启唤醒服务后生效」同一件事）。 */
    static void restartWake(Context app) {
        try {
            Intent i = new Intent(app, com.onesay.assistant.v2.wake.WakeService.class);
            app.stopService(i);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Throwable ignored) {
        }
    }

    static boolean killed(Context app, String id) {
        String kill = null;
        if ("plug.island".equals(id)) return com.onesay.assistant.v2.island.IslandSettings.killed(app);
        if ("plug.notice".equals(id)) return com.onesay.assistant.v2.notice.NoticeSettings.SWITCH.killed(app);
        if ("plug.quicknote".equals(id)) return com.onesay.assistant.v2.kernel.plugins.QuickNotePlugin.SWITCH.killed(app);
        if ("plug.pclink".equals(id)) return com.onesay.assistant.v2.pclink.PcLink.SWITCH.killed(app);
        if ("privacy.redact".equals(id)) return com.onesay.assistant.v2.privacy.PrivacySettings.REDACT.killed(app);
        if ("plug.phone".equals(id)) kill = "phone.off";
        else if ("plug.diag".equals(id)) kill = com.onesay.assistant.v2.diag.DiagSettings.KILL;
        return kill != null && FeatureSwitch.killFilePresent(app, kill);
    }

    static Boolean secureContains(Context app, String key, String needle) {
        try {
            String v = Settings.Secure.getString(app.getContentResolver(), key);
            return Boolean.valueOf(v != null && v.contains(needle));
        } catch (Throwable t) {
            return null;
        }
    }

    static Tri need(Context app, String needId) {
        try {
            String pkg = app.getPackageName();
            if (SelfCatalog.N_MIC.equals(needId)) {
                return Tri.of(app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
            }
            if (SelfCatalog.N_OVERLAY.equals(needId)) return Tri.of(Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app));
            if (SelfCatalog.N_A11Y.equals(needId)) return Tri.of(secureContains(app, "enabled_accessibility_services", pkg + "/"));
            if (SelfCatalog.N_NOTIF_LISTENER.equals(needId)) return Tri.of(secureContains(app, "enabled_notification_listeners", pkg + "/"));
            if (SelfCatalog.N_POST_NOTIF.equals(needId)) {
                if (Build.VERSION.SDK_INT < 33) return Tri.YES;
                return Tri.of(app.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED);
            }
            if (SelfCatalog.N_BATTERY.equals(needId)) return Tri.of(com.onesay.assistant.v2.ui.console.StatusProbe.batteryWhitelisted(app));
            if (SelfCatalog.N_ASSIST.equals(needId)) return Tri.of(com.onesay.assistant.v2.assist.AssistRoleHost.isDefault(app));
            if (SelfCatalog.N_EXACT_ALARM.equals(needId)) {
                if (Build.VERSION.SDK_INT < 31) return Tri.YES;
                android.app.AlarmManager am = (android.app.AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
                if (am == null) return Tri.UNKNOWN;
                Object r = am.getClass().getMethod("canScheduleExactAlarms").invoke(am);   // API 31；android-28 SDK 编译只能反射
                return r instanceof Boolean ? Tri.of((Boolean) r) : Tri.UNKNOWN;
            }
            if (SelfCatalog.N_ARM64.equals(needId)) {
                if (Build.VERSION.SDK_INT < 21) return Tri.UNKNOWN;
                for (String abi : Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(abi)) return Tri.YES;
                return Tri.NO;
            }
            if (SelfCatalog.N_WAKE_MODEL.equals(needId)) {
                if (new java.io.File(app.getFilesDir(), "kws/encoder.onnx").isFile()) return Tri.YES;
                try {
                    app.getAssets().open("kws_model.zip").close();
                    return Tri.YES;
                } catch (java.io.IOException e) {
                    return Tri.NO;
                }
            }
            if (SelfCatalog.N_WAKE_WORDS.equals(needId)) {
                String lines = com.onesay.assistant.v2.wake.WakeWordSettings.getLines(app);
                return Tri.of(lines.trim().length() > 0 && com.onesay.assistant.v2.wake.WakeWords.validate(lines, null) == null);
            }
            if (SelfCatalog.N_SHIZUKU.equals(needId)) {
                com.onesay.assistant.v2.platform.ShizukuBridge br = new com.onesay.assistant.v2.platform.ShizukuBridge();
                return Tri.of(br.running() && br.granted());
            }
            if (SelfCatalog.N_SCREEN.equals(needId)) {
                if (Boolean.TRUE.equals(secureContains(app, "enabled_accessibility_services", pkg + "/"))) return Tri.YES;
                if (Boolean.TRUE.equals(com.onesay.assistant.v2.platform.ExecBackendHost.shizukuReady(app))) return Tri.YES;
                com.onesay.assistant.v2.platform.DeviceProfile p = com.onesay.assistant.v2.platform.DeviceCapabilityProbe.cached(app);
                if (p != null && p.get(com.onesay.assistant.v2.platform.DeviceCap.EXEC_DAEMON) == Tri.YES) return Tri.YES;
                return p == null ? Tri.UNKNOWN : Tri.NO;
            }
        } catch (Throwable ignored) {
        }
        return Tri.UNKNOWN;
    }

    // ------------------------------------------------------------------ 打开去处

    /** 打开一项设置的去处（应用内页面 / 系统设置页）。@return 打开了没有 */
    public static boolean open(Context ctx, SelfCatalog.Target t) {
        if (t == null || t == SelfCatalog.Target.NONE) return false;
        String pkg = ctx.getPackageName();
        try {
            if (t.inApp()) {
                Intent it = new Intent(ctx, com.onesay.assistant.v2.ui.MainActivity.class);
                it.putExtra("page", com.onesay.assistant.v2.ui.ConsoleDeepLink.PREFIX + t.page);
                it.putExtra("title", t.zh);
                it.putExtra(com.onesay.assistant.v2.ui.ConsoleDeepLink.EXTRA_TOKEN, com.onesay.assistant.v2.ui.ConsoleDeepLink.token());
                return start(ctx, it);
            }
            switch (t) {
                case SYS_OVERLAY:
                    return start(ctx, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + pkg))) || appDetails(ctx);
                case SYS_A11Y:
                    return start(ctx, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                case SYS_NOTIF_LISTENER:
                    return start(ctx, new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                case SYS_APP_NOTIF:
                    if (Build.VERSION.SDK_INT >= 26) {
                        Intent n = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
                        if (start(ctx, n)) return true;
                    }
                    return appDetails(ctx);
                case SYS_BATTERY:
                    return start(ctx, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || appDetails(ctx);
                case SYS_ASSIST_ROLE:
                    return start(ctx, new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) || start(ctx, new Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")) || appDetails(ctx);
                case SYS_EXACT_ALARM:
                    if (Build.VERSION.SDK_INT >= 31 && start(ctx, new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", Uri.parse("package:" + pkg)))) return true;
                    return appDetails(ctx);
                case SHIZUKU_APP: {
                    Intent li = ctx.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                    return li != null && start(ctx, li);
                }
                case SYS_APP_DETAILS:
                default:
                    return appDetails(ctx);
            }
        } catch (Throwable e) {
            return false;
        }
    }

    static boolean appDetails(Context ctx) {
        return start(ctx, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.getPackageName())));
    }

    static boolean start(Context ctx, Intent it) {
        try {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (it.resolveActivity(ctx.getPackageManager()) == null) return false;
            ctx.startActivity(it);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
