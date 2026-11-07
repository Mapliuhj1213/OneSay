package com.onesay.assistant.v2.entry;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.onesay.assistant.v2.platform.DeviceCap;
import com.onesay.assistant.v2.platform.DeviceCapabilityProbe;
import com.onesay.assistant.v2.platform.DeviceProfile;
import com.onesay.assistant.v2.platform.Tri;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.wake.WakeService;
import com.onesay.assistant.v2.wake.WakeWordSettings;
import com.onesay.assistant.vendor.samsung.BixbyKeyEntry;
import com.onesay.plugin.api.entry.EntryEnv;
import com.onesay.plugin.api.entry.EntryRegistry;
import com.onesay.plugin.api.entry.EntryTrigger;
import com.onesay.plugin.api.entry.SummonHost;

import java.util.Arrays;

/**
 * 宿主侧：把 {@link EntryRegistry} 接到 Android。
 *
 * <ul>
 *   <li>开关存在 prefs {@code onesay_entries}（本地唤醒词的开关仍是 {@link WakeWordSettings}，两边是同一个值）；</li>
 *   <li>SYSTEM 入口关掉时停用对应 Manifest 组件（快捷开关 / 小部件 / 公开 Intent），快捷方式用 ShortcutManager 停用；</li>
 *   <li>唤起：语音 → 拉起 WakeService 并 {@code requestWake(source)}；文字 → 只打开悬浮面板。</li>
 * </ul>
 * 新入口插件：实现 {@link EntryTrigger}，在 {@link #ensureInit} 里 {@code EntryRegistry.register(...)} 一行。
 */
public final class Entries {
    private Entries() {}

    private static final String TAG = "Entries";
    public static final String PREFS = "onesay_entries";

    static final String JAVA_PKG = "com.onesay.assistant";
    static final String C_TILE = "com.onesay.assistant.v2.entry.SummonTileService";
    static final String C_WIDGET = "com.onesay.assistant.v2.entry.SummonWidgetProvider";
    static final String C_INTENT = "com.onesay.assistant.v2.entry.SummonActivity";
    static final String SHORTCUT_ID = "onesay_voice";

    private static volatile boolean sInit;

    public static void ensureInit(Context ctx) {
        if (sInit || ctx == null) return;
        synchronized (Entries.class) {
            if (sInit) return;
            final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            try {
                EntryRegistry.setStore(new PrefsStore(app));
                EntryRegistry.setEnv(new AndroidEnv(app));
                EntryRegistry.setHost(new SummonHost() {
                    @Override public void summon(String source, EntryTrigger.Mode mode) { doSummon(app, source, mode); }
                });
                BuiltinEntries.registerAll(new BuiltinEntries.ComponentSwitch() {
                    @Override public void setComponentEnabled(String id, boolean on) { switchComponent(app, id, on); }
                });
                // 厂商入口插件（默认关）
                EntryRegistry.register(new BixbyKeyEntry());
                sInit = true;
                EntryRegistry.apply();
            } catch (Throwable t) {
                Log.w(TAG, "[ENTRY] init 失败: " + t);
            }
        }
    }

    public static boolean fire(Context ctx, String id, EntryTrigger.Mode mode) {
        ensureInit(ctx);
        boolean ok = EntryRegistry.fire(id, mode);
        Log.i(TAG, "[ENTRY] " + id + " mode=" + mode + (ok ? " → 唤起" : " → 已关闭或不可用，丢弃"));
        return ok;
    }

    public static boolean canFire(Context ctx, String id) {
        ensureInit(ctx);
        return EntryRegistry.canFire(id);
    }

    public static void setEnabled(Context ctx, String id, boolean on) {
        ensureInit(ctx);
        EntryRegistry.setEnabled(id, on);
        EntryRegistry.apply();
    }

    static void doSummon(Context app, String source, EntryTrigger.Mode mode) {
        try {
            if (mode == EntryTrigger.Mode.TEXT) {
                FloatingSheetService.show(app, null);
                return;
            }
            Intent i = new Intent(app, WakeService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
            WakeService.requestWake(source, false);
        } catch (Throwable t) {
            Log.w(TAG, "[ENTRY] 唤起失败 src=" + source + ": " + t);
        }
    }

    static void switchComponent(Context app, String id, boolean on) {
        try {
            if (BuiltinEntries.SHORTCUT.equals(id)) {
                if (Build.VERSION.SDK_INT >= 25) {
                    android.content.pm.ShortcutManager sm = app.getSystemService(android.content.pm.ShortcutManager.class);
                    if (sm != null) {
                        if (on) sm.enableShortcuts(Arrays.asList(SHORTCUT_ID));
                        else sm.disableShortcuts(Arrays.asList(SHORTCUT_ID), "已在 OneSay 设置里关闭");
                    }
                }
                return;
            }
            String cls = BuiltinEntries.QS_TILE.equals(id) ? C_TILE : BuiltinEntries.WIDGET.equals(id) ? C_WIDGET
                    : BuiltinEntries.INTENT.equals(id) ? C_INTENT : null;
            if (cls == null) return;
            app.getPackageManager().setComponentEnabledSetting(new ComponentName(app, cls),
                    on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            Log.w(TAG, "[ENTRY] 切换组件失败 " + id + ": " + t);
        }
    }

    /** prefs 存储；本地唤醒词映射到 {@link WakeWordSettings}（原开关，默认关）。 */
    static final class PrefsStore implements EntryRegistry.Store {
        final Context app;
        PrefsStore(Context app) { this.app = app; }

        SharedPreferences sp() { return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

        @Override public Boolean get(String id) {
            if (BuiltinEntries.WAKE_WORD.equals(id)) return WakeWordSettings.isEnabled(app);
            SharedPreferences p = sp();
            return p.contains(id) ? p.getBoolean(id, false) : null;
        }

        @Override public void put(String id, boolean on) {
            if (BuiltinEntries.WAKE_WORD.equals(id)) { WakeWordSettings.setEnabled(app, on); return; }
            sp().edit().putBoolean(id, on).apply();
        }
    }

    static final class AndroidEnv implements EntryEnv {
        final Context app;
        AndroidEnv(Context app) { this.app = app; }

        @Override public Object androidContext() { return app; }
        @Override public int sdkInt() { return Build.VERSION.SDK_INT; }

        @Override public EntryTrigger.Tri cap(String name) {
            try {
                if ("MIC".equals(name)) {
                    return app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            ? EntryTrigger.Tri.YES : EntryTrigger.Tri.NO;
                }
                DeviceProfile p = DeviceCapabilityProbe.cached(app);
                if (p == null) return EntryTrigger.Tri.UNKNOWN;
                Tri t = p.get(DeviceCap.valueOf(name));
                return t == Tri.YES ? EntryTrigger.Tri.YES : t == Tri.NO ? EntryTrigger.Tri.NO : EntryTrigger.Tri.UNKNOWN;
            } catch (Throwable t) {
                return EntryTrigger.Tri.UNKNOWN;
            }
        }
    }
}
