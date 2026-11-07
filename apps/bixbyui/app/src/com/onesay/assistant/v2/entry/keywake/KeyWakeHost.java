package com.onesay.assistant.v2.entry.keywake;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;

import com.onesay.assistant.v2.a11y.A11yBackend;
import com.onesay.assistant.v2.entry.BuiltinEntries;
import com.onesay.assistant.v2.entry.Entries;

/**
 * 按键唤醒的 Android 侧，托管在 OneSay 无障碍服务（{@link A11yBackend}）里：服务本来就由用户手动打开、常驻，
 * 所以不再依赖厂商的虚拟 Home 长按手势或厂商按键。入口关着时什么都不注册。
 */
public final class KeyWakeHost {
    static final String TAG = "KeyWake";

    private final AccessibilityService svc;
    private PowerDoubleDetector power;
    private LongPressDetector longPress;
    private BroadcastReceiver screenRx;
    private KeyWake.Trigger trigger;

    public KeyWakeHost(AccessibilityService svc) {
        this.svc = svc;
    }

    /** 服务连上 / 设置改动后调用：按当前开关与触发方式注册监听。 */
    public synchronized void refresh() {
        stop();
        if (!Entries.canFire(svc, BuiltinEntries.KEY_WAKE)) return;
        trigger = KeyWakeSettings.trigger(svc);
        if (KeyWake.needsScreenEvents(trigger)) {
            power = new PowerDoubleDetector(KeyWakeSettings.windowMs(svc));
            screenRx = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    boolean on = Intent.ACTION_SCREEN_ON.equals(i.getAction());
                    boolean suppress = inCall() || (!on && KeyWake.likelyTimeout(A11yBackend.quietForMs(), screenTimeout()));
                    if (power != null && power.onScreen(on, SystemClock.elapsedRealtime(), suppress)) fire("power_double");
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            svc.registerReceiver(screenRx, f);
        }
        if (KeyWake.needsKeyFilter(trigger)) {
            longPress = new LongPressDetector(KeyWake.LONG_PRESS_MS);
            setKeyFilter(true);
        }
        Log.i(TAG, "按键唤醒已启用：" + trigger.label + " → " + KeyWakeSettings.action(svc).label);
    }

    public synchronized void stop() {
        if (screenRx != null) {
            try { svc.unregisterReceiver(screenRx); } catch (Throwable ignored) {}
            screenRx = null;
        }
        if (longPress != null) setKeyFilter(false);
        power = null;
        longPress = null;
    }

    /** 无障碍按键过滤回调。返回 true = 吃掉这个按键事件。 */
    public boolean onKeyEvent(KeyEvent e) {
        LongPressDetector lp = longPress;
        KeyWake.Trigger t = trigger;
        if (lp == null || t == null || e == null) return false;
        int want = t == KeyWake.Trigger.VOLUME_UP_LONG ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN;
        if (e.getKeyCode() != want) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            lp.onDown(e.getEventTime());
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            LongPressDetector.Result r = lp.onUp(e.getEventTime());
            if (r == LongPressDetector.Result.LONG) {
                fire(t.key);
            } else if (r == LongPressDetector.Result.SHORT) {
                // 短按：照常调一次音量（按下时吃掉了事件）
                try {
                    AudioManager am = (AudioManager) svc.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) am.adjustSuggestedStreamVolume(want == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE
                            : AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                } catch (Throwable ignored) {
                }
            }
            return true;
        }
        return false;
    }

    void fire(String how) {
        KeyWake.Action a = KeyWakeSettings.action(svc);
        Log.i(TAG, "[KEY-WAKE] " + how + " → " + a.label);
        Entries.fire(svc, BuiltinEntries.KEY_WAKE, KeyWake.modeFor(a));
    }

    private void setKeyFilter(boolean on) {
        try {
            AccessibilityServiceInfo info = svc.getServiceInfo();
            if (info == null) return;
            if (on) info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            else info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            svc.setServiceInfo(info);
        } catch (Throwable t) {
            Log.w(TAG, "按键过滤设置失败: " + t.getClass().getSimpleName());
        }
    }

    private boolean inCall() {
        try {
            TelephonyManager tm = (TelephonyManager) svc.getSystemService(Context.TELEPHONY_SERVICE);
            return tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE;
        } catch (Throwable t) {
            return false;
        }
    }

    private long screenTimeout() {
        try {
            return Settings.System.getLong(svc.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT);
        } catch (Throwable t) {
            return -1;
        }
    }
}
