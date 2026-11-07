package com.onesay.clock.ui;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;

import com.onesay.clock.L;
import com.onesay.clock.core.HapticMap;

import java.lang.reflect.Method;

/**
 * UI 触感执行器（映射见 core.HapticMap，默认 = 系统通用触感）。
 * 不碰响铃振动：响铃波形 Basic call [0,1500,2000] 由 RingService 自己的 Vibrator 播放；滑动解除按住时的 semVibrate 若打断了它，
 * 松手时 RingService 会重新起波形（REVIB）。
 */
public final class Haptics {
    private Haptics() { }


    /** 触发一个 UI 事件的触感；返回是否真的发出了（日志/测试用）。 */
    public static boolean fire(View v, HapticMap.Ev e) {
        if (v == null) return false;
        Context c = v.getContext();
        HapticMap.Act a = HapticMap.active(e);
        int setting = Settings.System.getInt(c.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 0);
        if (!HapticMap.allowed(a, setting)) return false;
        try {
            switch (a.kind) {
                case SOUND_EFFECT: {
                    AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) am.playSoundEffect(a.code);   // 超出范围的音效框架直接忽略
                    return am != null;
                }
                case SEM_VIBRATE: {
                    Vibrator vib = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
                    if (vib == null || !vib.hasVibrator()) return false;
                    if (a.fallback >= 0 && v.performHapticFeedback(a.fallback)) return true;
                    // 都不行：20 ms 短振（Android 8 起有 VibrationEffect）
                    if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE));
                    else vib.vibrate(20);
                    return true;
                }
                default: return false;
            }
        } catch (Throwable t) {
            L.w(c, "HAPTIC " + e + " 失败 " + t);
            return false;
        }
    }


}
