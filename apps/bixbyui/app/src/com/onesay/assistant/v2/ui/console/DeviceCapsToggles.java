package com.onesay.assistant.v2.ui.console;

import android.app.Activity;
import android.util.Log;

import com.onesay.assistant.v2.platform.FeatureSpec;

/**
 * [refactor/oss 2026-11-06] 「设备能力与功能」页上的开关 → 复用各功能原设置页的 setter 与即时副作用。
 *
 * <p>原设置页拨开关时除了写 prefs 还有即时副作用（灵动岛重判形态、记一记同步按键守护的手势标记、消息提醒重新拉取），
 * 这里逐项调用<b>同一组</b>方法，保证从能力页拨和从原设置页拨效果完全一致。新增带开关的 {@link FeatureSpec} 时必须在这里加一个 case
 * （JVM 测试 DeviceCapsTogglesSourceTest 逐项检查）。
 */
final class DeviceCapsToggles {

    private DeviceCapsToggles() {}

    private static final String TAG = "[PLATFORM]";

    /** 拨开关。返回 false = 这个 id 没有登记专门的 setter（只写了 prefs，并打一条警告日志）。 */
    static boolean apply(Activity act, FeatureSpec f, boolean on) {
        String id = f.id;
        if ("screen.ref".equals(id)) {
            // 原设置页（ScreenRefPlugin.pref().set）只写 prefs，无副作用
            com.onesay.assistant.v2.vision.screenref.ScreenRefSettings.setEnabled(act, on);
            return true;
        }
        if ("quicknote".equals(id)) {
            // 同 QuickNotePlugin 主开关：写开关 + 立即同步守护用的长按手势标记
            com.onesay.assistant.v2.kernel.plugins.QuickNotePlugin.SWITCH.set(act, on);
            com.onesay.assistant.v2.wake.WakeService.syncGestureFlag(act);
            return true;
        }
        if ("imgsave".equals(id)) {
            // 同 ImageSavePlugin.pref().set
            com.onesay.assistant.v2.imgsave.ImageSaveSettings.set(act, com.onesay.assistant.v2.imgsave.ImageSaveSettings.KEY_ENABLED, on);
            return true;
        }
        if ("island".equals(id)) {
            // 同 IslandPlugin 主开关：写开关 + 浮层形态重新判定（主线程）
            com.onesay.assistant.v2.island.IslandSettings.set(act, com.onesay.assistant.v2.island.IslandSettings.KEY_ENABLED, on);
            com.onesay.assistant.v2.s1.S1Hooks.refreshIslandMode("console");
            return true;
        }
        if ("notice".equals(id)) {
            // 同 NoticePlugin 主开关：写开关 + 后台重新拉取提醒源
            com.onesay.assistant.v2.notice.NoticeSettings.SWITCH.set(act, on);
            com.onesay.assistant.v2.kernel.plugins.NoticePlugin.refresh(act);
            return true;
        }
        if ("errand.samsung".equals(id)) {
            // 同 ErrandPlugin.toggle(ErrandSettings.SAMSUNG)
            com.onesay.assistant.v2.errand.ErrandSettings.SAMSUNG.set(act, on);
            return true;
        }
        act.getApplicationContext().getSharedPreferences(f.prefs, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(f.key, on).apply();
        Log.w(TAG, "能力页开关没有登记专门的 setter，只写了 prefs：" + id);
        return false;
    }
}
