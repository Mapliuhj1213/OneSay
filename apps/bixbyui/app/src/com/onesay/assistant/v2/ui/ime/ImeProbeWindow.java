package com.onesay.assistant.v2.ui.ime;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

/**
 * ★ r2/kb-fix（LC-2 第三次）：键盘高度探针窗。
 *
 * <p>一个全高、1px 宽、透明、FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE 的 TYPE_APPLICATION_OVERLAY，
 * softInputMode = ADJUST_RESIZE。它在输入法下面一层，WMS 布局时把输入法的可见区从它的可见区里扣掉
 * （不依赖它是不是输入法目标，也不依赖目标窗口的 softInputMode 生不生效），所以
 * {@code getWindowVisibleDisplayFrame().bottom} 就是键盘上沿；窗口自己的 frame 底边就是「键盘没弹」的基线。
 *
 * <p>事件驱动：键盘弹出 / 收起 / 换高度（中英切换、表情面板、候选栏）/ 转屏，WMS 都会给这个窗口发 resized，
 * 触发 OnGlobalLayout → 回调 {@link Listener#onImeChanged}。另有低频轮询兜底（三星输入法个别动画不发 resized）。
 *
 * <p><b>z 序很关键</b>（真机 v1 教训）：输入法窗口被 WMS 放在「输入法目标窗」正上方；同层 overlay 按挂窗先后叠放。
 * 探针窗如果比卡片窗晚挂，就叠在卡片上面、也就在输入法上面，WMS 先布局它、后布局输入法 → 它根本看不到键盘（读数恒为导航栏上沿）。
 * 所以探针窗在面板服务 onCreate 时就挂上（{@link #ensureAttached}），一直留着，保证比之后每次挂的卡片窗 / 补充框都低。
 * acquire / release 只控制回调和兜底轮询，不摘窗；服务销毁时 {@link #destroy}。只在主线程用。
 */
public final class ImeProbeWindow {

    private static final String TAG = "ImeProbe";

    public interface Listener {
        /**
         * @param imeTop   键盘上沿（屏幕坐标）；-1 = 键盘没弹
         * @param baseBottom 键盘没弹时的可见区底边（导航栏上沿 / 屏底）
         * @param screenH  探针窗所在屏幕高度（px）
         */
        void onImeChanged(int imeTop, int baseBottom, int screenH);
    }

    private static ImeProbeWindow sInstance;

    public static ImeProbeWindow get(Context c) {
        if (sInstance == null) sInstance = new ImeProbeWindow(c.getApplicationContext());
        return sInstance;
    }

    private final Context mApp;
    private final WindowManager mWm;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final java.util.ArrayList<Listener> mListeners = new java.util.ArrayList<Listener>();
    private View mView;
    private final Rect mVis = new Rect();
    private final int[] mLoc = new int[2];
    private int mLastTop = Integer.MIN_VALUE;
    private int mLastBase = -1;
    private int mLastScreenH = -1;
    private String mLogged;

    private ImeProbeWindow(Context app) {
        mApp = app;
        mWm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
    }

    private final ViewTreeObserver.OnGlobalLayoutListener mLayout = new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override public void onGlobalLayout() {
            measure("layout");
        }
    };

    private final Runnable mPoll = new Runnable() {
        @Override public void run() {
            if (mView == null || mListeners.isEmpty()) return;
            measure("poll");
            mMain.postDelayed(this, 250L);
        }
    };

    public boolean isAttached() {
        return mView != null;
    }

    /** 面板服务 onCreate 调：先于任何卡片窗挂上（z 序在它们下面）。 */
    public void ensureAttached() {
        if (mView == null) attach();
    }

    /** 开始跟踪。立即回调一次当前值（可能还是 -1：键盘异步弹）。 */
    public void acquire(Listener l) {
        if (l == null) return;
        if (!mListeners.contains(l)) mListeners.add(l);
        if (mView == null) {
            Log.w(TAG, "[IME-PROBE] 跟踪时探针窗还没挂（z 序可能在卡片之上，读数会失效，仍有自身可见区 / 反射兜底）");
            attach();
        }
        mMain.removeCallbacks(mPoll);
        mMain.postDelayed(mPoll, 120L);
        measure("acquire");
        if (mLastTop != Integer.MIN_VALUE) l.onImeChanged(mLastTop, mLastBase, screenH());
    }

    public void release(Listener l) {
        mListeners.remove(l);
        if (mListeners.isEmpty()) mMain.removeCallbacks(mPoll);
    }

    /** 面板服务销毁时摘窗。 */
    public void destroy() {
        mListeners.clear();
        detach();
    }

    /** 最近一次读到的键盘上沿；-1 = 没弹 / 没在跟踪。 */
    public int lastTop() {
        return mView == null || mLastTop == Integer.MIN_VALUE ? -1 : mLastTop;
    }

    public int lastBase() {
        return mLastBase;
    }

    private int screenH() {
        try {
            return mApp.getResources().getDisplayMetrics().heightPixels;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void attach() {
        try {
            View v = new View(mApp);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    com.onesay.assistant.v2.s1.S1Overlay.windowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSPARENT);
            lp.gravity = Gravity.START | Gravity.TOP;
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;
            lp.setTitle("OneSay-ImeProbe");
            mWm.addView(v, lp);
            v.getViewTreeObserver().addOnGlobalLayoutListener(mLayout);
            mView = v;
            mLastTop = Integer.MIN_VALUE;
            mLastBase = -1;
            mLogged = null;
            Log.i(TAG, "[IME-PROBE] 探针窗已挂出");
        } catch (Throwable t) {
            Log.w(TAG, "[IME-PROBE] 探针窗挂不出: " + t);
            mView = null;
        }
    }

    private void detach() {
        mMain.removeCallbacks(mPoll);
        if (mView == null) return;
        try {
            mView.getViewTreeObserver().removeOnGlobalLayoutListener(mLayout);
        } catch (Throwable ignored) {
        }
        try {
            mWm.removeViewImmediate(mView);
        } catch (Throwable t) {
            try { mWm.removeView(mView); } catch (Throwable ignored) {}
        }
        mView = null;
        mLastTop = Integer.MIN_VALUE;
        Log.i(TAG, "[IME-PROBE] 探针窗已摘除");
    }

    /** 转屏后基线作废，下一次读数重新记。 */
    public void resetBase() {
        mLastBase = -1;
        if (mView != null) measure("config");
    }

    private void measure(String why) {
        View v = mView;
        if (v == null || v.getWindowToken() == null) return;
        try {
            v.getWindowVisibleDisplayFrame(mVis);
            v.getLocationOnScreen(mLoc);
        } catch (Throwable t) {
            return;
        }
        int frameBottom = mLoc[1] + v.getHeight();
        int sh = screenH();
        // 转屏（屏高变了）：旧基线作废（真机 v2：竖屏留下的 2220 带进横屏 1080）
        if (sh != mLastScreenH) {
            mLastScreenH = sh;
            mLastBase = -1;
        }
        // 基线 = 探针窗自己的 frame 底边（MATCH_PARENT，不随键盘变）与见过的「没弹」可见区底边取大
        int base = Math.max(frameBottom, ImeGeometry.updateBase(mLastBase, mVis.bottom, sh));
        int top = ImeGeometry.topFromVisible(base, mVis.bottom, sh);
        String tag = "vis=" + mVis.bottom + " frame=" + frameBottom + " base=" + base + " → top=" + top;
        if (!tag.equals(mLogged)) {
            mLogged = tag;
            Log.i(TAG, "[IME-PROBE] " + why + " " + tag);
        }
        boolean changed = top != mLastTop || base != mLastBase;
        mLastTop = top;
        mLastBase = base;
        if (!changed) return;
        for (Listener l : new java.util.ArrayList<Listener>(mListeners)) {
            try {
                l.onImeChanged(top, base, sh);
            } catch (Throwable t) {
                Log.w(TAG, "[IME-PROBE] 回调异常: " + t);
            }
        }
    }
}
