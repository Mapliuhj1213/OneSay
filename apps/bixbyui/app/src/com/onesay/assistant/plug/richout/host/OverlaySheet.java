/*
 * [富内容输出插件 · 宿主适配] OneSay 自写。叠在面板上方的浮层（阅读器 / 全屏看图共用）。
 */
package com.onesay.assistant.plug.richout.host;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 面板是 TYPE_APPLICATION_OVERLAY 悬浮窗，普通 Activity 会被它盖住；所以阅读器和全屏看图也做成悬浮窗，
 * 后加的窗口在上面 —— 「面板上方的底部弹层」。面板内容原封不动留在下面，关掉浮层就回到回答。
 *
 * <p>浮层可获得焦点（网页输入框能弹键盘、返回键归浮层）；开着时 {@link RichOutHost#overlayOpen()} 为 true，
 * 面板据此不把浮层上的触摸当成「点了面板外面」。
 */
abstract class OverlaySheet {

    private static final String TAG = "RichOut";

    protected final Context mCtx;
    private final WindowManager mWm;
    private Root mRoot;
    private boolean mShown;

    OverlaySheet(Context ctx) {
        mCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        mWm = (WindowManager) mCtx.getSystemService(Context.WINDOW_SERVICE);
    }

    /** 内容；heightFraction ≥ 1 = 全屏。 */
    protected abstract View content(FrameLayout root);

    protected abstract float heightFraction();

    /** 返回键：返回 true = 自己消费了（例如网页后退）。 */
    protected boolean onBack() {
        return false;
    }

    protected void onClosed() {
    }

    final class Root extends FrameLayout {
        Root(Context c) {
            super(c);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP && !onBack()) close();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    final void show() {
        if (mShown) return;
        try {
            mRoot = new Root(mCtx);
            View c = content(mRoot);
            if (c != null && c.getParent() == null) mRoot.addView(c, new FrameLayout.LayoutParams(-1, -1));
            int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            float f = heightFraction();
            int h = f >= 1f ? WindowManager.LayoutParams.MATCH_PARENT
                    : (int) (mCtx.getResources().getDisplayMetrics().heightPixels * f);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, h, type,
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM;
            lp.dimAmount = f >= 1f ? 0f : 0.45f;
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            lp.setTitle("OneSay-RichOut");
            mWm.addView(mRoot, lp);
            mShown = true;
            RichOutHost.overlayOpened();
        } catch (Throwable t) {
            Log.w(TAG, "[RICHOUT] 浮层打不开: " + t.getMessage());
            mShown = false;
        }
    }

    final void close() {
        if (!mShown) return;
        mShown = false;
        RichOutHost.overlayClosed();
        try {
            onClosed();
        } catch (Throwable ignored) {
        }
        try {
            mWm.removeView(mRoot);
        } catch (Throwable ignored) {
        }
        mRoot = null;
    }

    final boolean shown() {
        return mShown;
    }
}
