package com.onesay.assistant.v2.s1;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

/**
 * [s1] 悬浮小窗的公共工具：窗口类型（O+ 用 TYPE_APPLICATION_OVERLAY，以下用 TYPE_PHONE —— 与面板一致）、
 * 不抢焦点的 LayoutParams、安全的 add/remove/update、屏幕真实尺寸。
 */
public final class S1Overlay {

    private static final String TAG = "S1";

    private S1Overlay() {}

    @SuppressWarnings("deprecation")
    public static int windowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /**
     * 小浮窗：不可获焦（不抢输入法/不影响 uiautomator 读前台 App）、窗口外的触摸照常传给下面的 App。
     */
    static WindowManager.LayoutParams smallParams(int gravity, int x, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        p.x = x;
        p.y = y;
        return p;
    }

    /** 全屏遮罩（圈选用）：要接触摸，但仍不可获焦；铺满含状态栏的整屏，坐标与 getRawX/Y 一致。 */
    static WindowManager.LayoutParams fullScreenParams() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        return p;
    }

    static boolean add(WindowManager wm, View v, WindowManager.LayoutParams p) {
        if (wm == null || v == null) return false;
        try {
            if (v.getParent() != null) {
                wm.updateViewLayout(v, p);
                return true;
            }
            wm.addView(v, p);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[OVERLAY] 挂载失败（悬浮窗权限？）: " + t.getMessage());
            return false;
        }
    }

    static void remove(WindowManager wm, View v) {
        if (wm == null || v == null || v.getParent() == null) return;
        try {
            wm.removeViewImmediate(v);
        } catch (Throwable t) {
            try {
                wm.removeView(v);
            } catch (Throwable ignored) {
            }
        }
    }

    static void update(WindowManager wm, View v, WindowManager.LayoutParams p) {
        if (wm == null || v == null || v.getParent() == null) return;
        try {
            wm.updateViewLayout(v, p);
        } catch (Throwable ignored) {
        }
    }

    /** 屏幕真实尺寸（含状态栏/导航栏），与截图分辨率同一坐标系。 */
    static Point realSize(Context ctx) {
        Point pt = new Point();
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            d.getRealSize(pt);
        } catch (Throwable t) {
            pt.set(ctx.getResources().getDisplayMetrics().widthPixels, ctx.getResources().getDisplayMetrics().heightPixels);
        }
        return pt;
    }

    static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
