package com.onesay.assistant.v2.suggest;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.onesay.assistant.plug.suggest.core.HostState;

/** ★ feat/suggest：只读宿主状态（每次现读，异常给保守值）。全屏沉浸由岛自己判（IslandModel.canSuggest 全屏时拒绝），这里恒 false。 */
final class SuggestHostState implements HostState {
    private final Context mCtx;
    private final com.onesay.assistant.v2.ui.FloatingSheetService mSvc;
    private volatile String mFg;
    private volatile long mFgAt;

    SuggestHostState(com.onesay.assistant.v2.ui.FloatingSheetService svc) {
        mSvc = svc;
        mCtx = svc.getApplicationContext();
    }

    @Override public String foregroundPkg() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - mFgAt < 2000L) return mFg;
        String[] p = ForegroundProbe.now(mCtx, false);   // 引擎线程上不走 root
        mFg = p == null ? null : p[0];
        mFgAt = now;
        return mFg;
    }

    @Override public boolean keyguardLocked() {
        try {
            KeyguardManager km = (KeyguardManager) mCtx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return true;
        }
    }

    @Override public boolean screenOn() {
        try {
            PowerManager pm = (PowerManager) mCtx.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    @Override public boolean inCall() {
        try {
            AudioManager am = (AudioManager) mCtx.getSystemService(Context.AUDIO_SERVICE);
            int m = am == null ? AudioManager.MODE_NORMAL : am.getMode();
            return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public boolean assistantBusy() {
        try {
            if (mSvc.panelOnScreen()) return true;
        } catch (Throwable ignored) {
        }
        return com.onesay.assistant.v2.s1.S1Hooks.isTaskRunning() || com.onesay.assistant.v2.s1.S1Hooks.isConfirmPending();
    }

    @Override public boolean fullscreenImmersive() {
        return false;
    }

    @Override public boolean dndOn() {
        try {
            NotificationManager nm = (NotificationManager) mCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            int f = nm == null ? NotificationManager.INTERRUPTION_FILTER_ALL : nm.getCurrentInterruptionFilter();
            return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        } catch (Throwable t) {
            return false;
        }
    }

    private int[] real() {
        try {
            WindowManager wm = (WindowManager) mCtx.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    @Override public int screenWidthPx() {
        return real()[0];
    }

    @Override public int screenHeightPx() {
        return real()[1];
    }
}
