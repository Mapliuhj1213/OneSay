package com.onesay.assistant.v2.assist;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;

import com.onesay.assistant.v2.wake.WakeService;

/**
 * ★ r2/sysux P2-1：默认数字助理入口（混合式：系统入口 + 屏幕上下文提供者，界面仍用现有悬浮面板）。
 *
 * <p>被设为默认助理（{@code settings put secure voice_interaction_service/assistant}）后，系统开机绑定、常驻本服务。
 * 三件套必须齐全（{@code res/xml/onesay_voice_interaction.xml}：sessionService + recognitionService），否则 AOSP O 的
 * VoiceInteractionServiceInfo 判无效、清掉设置，ASSIST 键落到三星浏览器（10-04 「Bixby 键打开浏览器」的根因）。</p>
 */
public class OneSayVoiceInteractionService extends VoiceInteractionService {

    private static final String TAG = "BixbyVIS";
    private static volatile OneSayVoiceInteractionService sReady;

    @Override
    public void onReady() {
        super.onReady();
        sReady = this;
        boolean active = false;
        try { active = isActiveService(this, new ComponentName(this, OneSayVoiceInteractionService.class)); } catch (Throwable ignore) {}
        Log.i(TAG, "[VIS] onReady（active=" + active + "）");
        ensureWakeService(this);
    }

    @Override
    public void onShutdown() {
        Log.i(TAG, "[VIS] onShutdown");
        if (sReady == this) sReady = null;
        super.onShutdown();
    }

    /** 锁屏上按 ASSIST / 长按 Home（supportsLaunchVoiceAssistFromKeyguard）：交给现有唤醒，息屏分支的托底 Activity 负责浮在锁屏上。 */
    @Override
    public void onLaunchVoiceAssistFromKeyguard() {
        Log.i(TAG, "[VIS] 锁屏上召唤 → 现有唤醒（托底 Activity）");
        ensureWakeService(this);
        WakeService.requestWake(AssistEntryPolicy.SRC_KEYGUARD, false);
    }

    /** 本 App 现在是不是默认助理（VIS 已就绪 + 系统设置指向我们）。 */
    public static boolean isActive(Context c) {
        OneSayVoiceInteractionService s = sReady;
        if (s == null) return false;
        try {
            return isActiveService(c, new ComponentName(c, OneSayVoiceInteractionService.class));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 唤醒时顺手向系统要前台 App 的 AssistStructure + 截图（session 不画界面，只收数据）。
     * @return true = 已发起（数据稍后经 OneSaySession → ScreenContextCapture.put 进识屏缓存）
     */
    public static boolean requestContext(Context c, String wakeSource) {
        OneSayVoiceInteractionService s = sReady;
        if (s == null || !isActive(c)) return false;
        try {
            Bundle args = new Bundle();
            args.putString(AssistEntryPolicy.ARG_SRC, wakeSource);
            s.showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST | VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
            Log.i(TAG, "[VIS] 唤醒 src=" + wakeSource + " → 向系统要屏幕上下文（AssistStructure + 截图）");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[VIS] showSession 失败（回退 uiautomator）: " + t);
            return false;
        }
    }

    static void ensureWakeService(Context c) {
        try {
            Intent i = new Intent(c, WakeService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "[VIS] 拉起 WakeService 失败: " + t);
        }
    }
}
