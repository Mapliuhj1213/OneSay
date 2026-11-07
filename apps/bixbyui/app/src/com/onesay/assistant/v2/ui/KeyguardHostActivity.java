package com.onesay.assistant.v2.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.lang.ref.WeakReference;

/**
 * ★ r2/screenoff-wake：锁屏托底 Activity。
 *
 * <p>Android 8 的 PhoneWindowManager 在锁屏显示时会把层级低于状态栏（锁屏宿主窗）的窗口全部藏起来，
 * TYPE_APPLICATION_OVERLAY 的面板也不例外；只有「锁屏被遮挡（occluded）且窗口自己带 FLAG_SHOW_WHEN_LOCKED」时才放行。
 * 所以锁着的时候受理唤醒 → 起这个<b>不透明</b>（黑底）、show-when-locked 的全屏 Activity 把锁屏「遮挡」掉，
 * 面板窗（FloatingSheetService 已加 FLAG_SHOW_WHEN_LOCKED）就能浮在上方语音回答。
 *
 * <p>为什么不透明：透明窗不算「全屏不透明窗」，锁屏不会进入被遮挡态；而且一旦被遮挡，下面露出来的是上一个 App ——
 * 黑底保证锁屏期间看不到任何解锁后才能看的内容。
 *
 * <p>退出：点面板以外的任何地方 / 返回键 / 面板离屏 / 息屏 / 服务销毁 / 兜底超时（keyguard_host_max_ms）。退出后锁屏原样回来。
 * 有副作用 / 读隐私的能力在锁着时由 ExecutionGate 直接拒绝（ScreenOffPolicy.unlockDenyReason），这里不放开任何东西。
 */
public class KeyguardHostActivity extends Activity {

    private static final String TAG = "KeyguardHost";
    static final String EXTRA_MAX_MS = "max_ms";
    static final String EXTRA_WHY = "why";

    private static WeakReference<KeyguardHostActivity> sCur = new WeakReference<KeyguardHostActivity>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * ★ r2/kb-fix（LC-2 锁屏复发）：托底到点时问一声「面板还在用吗」（打字中 / 键盘弹着）。
     * 以前固定 60 s 就退：用户在锁屏上点 ⌨ 慢慢打中文，托底一退锁屏回来把面板藏住，键盘却还挂着 ——
     * 现场照片就是「锁屏 + 键盘，上面没有输入框」。现在用着就顺延，绝对上限 KbLockPolicy.HOLD_CAP_MS（10 分钟）。
     */
    public interface Hold {
        boolean holdHost();
    }

    /** 托底退出（任何原因）的回调：面板据此收键盘 / 收起，别让键盘孤零零挂在锁屏上。 */
    public interface GoneListener {
        void onKeyguardHostGone(String why);
    }

    static final long HOLD_STEP_MS = com.onesay.assistant.v2.ui.ime.KbLockPolicy.HOLD_STEP_MS;
    private static Hold sHold;
    private static GoneListener sGone;
    private long mShownAt;
    private String mFinishWhy = "finish";

    public static void setHold(Hold h) {
        sHold = h;
    }

    public static void setGoneListener(GoneListener g) {
        sGone = g;
    }

    static boolean shouldExtend(boolean inUse, long shownForMs) {
        return com.onesay.assistant.v2.ui.ime.KbLockPolicy.shouldExtend(inUse, shownForMs);
    }

    private final Runnable mTimeout = new Runnable() {
        @Override public void run() {
            boolean inUse = false;
            try {
                inUse = sHold != null && sHold.holdHost();
            } catch (Throwable ignored) {
            }
            long shown = android.os.SystemClock.elapsedRealtime() - mShownAt;
            if (shouldExtend(inUse, shown)) {
                Log.i(TAG, "托底到点但面板还在用（打字中）→ 顺延 " + HOLD_STEP_MS + "ms（已 " + shown + "ms）");
                MAIN.postDelayed(this, HOLD_STEP_MS);
                return;
            }
            Log.i(TAG, "托底超时 → 退出（锁屏回来）");
            mFinishWhy = "timeout";
            finish();
        }
    };

    /** WakeService 受理唤醒且锁着时调用。 */
    public static void show(Context c, int maxMs, String why) {
        Intent i = new Intent(c, KeyguardHostActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(EXTRA_MAX_MS, maxMs);
        i.putExtra(EXTRA_WHY, why);
        c.startActivity(i);
    }

    /** 息屏 / 面板离屏 / 服务销毁时调用（任意线程）。 */
    public static void finishIfShowing(final String why) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                KeyguardHostActivity a = sCur.get();
                if (a != null && !a.isFinishing()) {
                    Log.i(TAG, "退出托底（" + why + "）");
                    a.mFinishWhy = why;
                    a.finish();
                }
            }
        });
    }

    public static boolean isShowing() {
        KeyguardHostActivity a = sCur.get();
        return a != null && !a.isFinishing();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        View v = new View(this);
        v.setBackgroundColor(Color.BLACK);
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    Log.i(TAG, "点了面板以外 → 退出托底");
                    mFinishWhy = "outside";
                    finish();
                }
                return true;
            }
        });
        setContentView(v);
        sCur = new WeakReference<KeyguardHostActivity>(this);
        mShownAt = android.os.SystemClock.elapsedRealtime();
        armTimeout(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        armTimeout(intent);
    }

    private void armTimeout(Intent i) {
        int ms = i != null ? i.getIntExtra(EXTRA_MAX_MS, 60000) : 60000;
        MAIN.removeCallbacks(mTimeout);
        MAIN.postDelayed(mTimeout, Math.max(5000, ms));
        Log.i(TAG, "托底显示（" + (i != null ? i.getStringExtra(EXTRA_WHY) : "?") + "，最长 " + ms + "ms）");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mFinishWhy = "back";
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        MAIN.removeCallbacks(mTimeout);
        if (sCur.get() == this) sCur = new WeakReference<KeyguardHostActivity>(null);
        GoneListener g = sGone;
        if (g != null && sCur.get() == null) {
            try {
                g.onKeyguardHostGone(mFinishWhy);
            } catch (Throwable t) {
                Log.w(TAG, "托底退出回调异常: " + t);
            }
        }
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
