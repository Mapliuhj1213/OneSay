package com.onesay.assistant.v2.fastscreen;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.view.WindowManager;

/**
 * ★ r2/fast-screen：三星 One UI（Android 8）导航栏按键区域（纯推算，JVM 可测的部分在 {@link #compute}）。
 * 导航栏在屏幕底部，高 = 系统资源 navigation_bar_height；按键顺序 Settings.Global navigationbar_key_order：
 * 0（默认）= 最近任务 | 主页 | 返回（返回在右），1 = 返回 | 主页 | 最近任务（返回在左）。每键占三分之一宽。
 */
public final class NavKeys {

    private NavKeys() {}

    /** @return {backL, backT, backR, backB, homeL, homeT, homeR, homeB} */
    public static int[] compute(int screenW, int screenH, int navH, int keyOrder) {
        if (navH <= 0 || screenW <= 0 || screenH <= 0) return new int[8];
        int t = screenH - navH, third = screenW / 3;
        int bl = keyOrder == 1 ? 0 : screenW - third, br = keyOrder == 1 ? third : screenW;
        return new int[]{bl, t, br, screenH, third, t, screenW - third, screenH};
    }

    /**
     * [fix-1123-c] 护罩上的一次按下落在导航栏哪儿：「按了返回键」/「按了主页键」/「按了多任务键」（导航栏里别的位置）；不在导航栏 = null。
     * 执行模式下护罩盖住了导航栏，以前这些按下被当误触吞掉（返回键「没有任何反应」）；现在一按就紧急接管。
     */
    public static String hit(int[] r, int x, int y) {
        if (r == null || r.length < 8) return null;
        if (in(r, 0, x, y)) return "按了返回键";
        if (in(r, 4, x, y)) return "按了主页键";
        int top = Math.min(r[1], r[5]), bottom = Math.max(r[3], r[7]);
        if (r[3] > r[1] && y >= top && y < bottom) return "按了多任务键";
        return null;
    }

    static boolean in(int[] r, int o, int x, int y) {
        return r[o + 2] > r[o] && r[o + 3] > r[o + 1] && x >= r[o] && x < r[o + 2] && y >= r[o + 1] && y < r[o + 3];
    }

    /**
     * [exec-1124 B] 手势导航（没有三键）时，助手的真人触摸观察看哪几块：
     * {左边缘返回带 l t r b, 底边回桌面带 l t r b, 右边缘返回带 l t r b}（和护罩上的 {@link com.onesay.assistant.v2.exec.EdgeGesture} 同一套边缘带）。
     * 以前只认三键导航栏，手势导航下找不到返回键就报 NO_BACK_BUTTON 退出，只留光晕时用户的返回手势就没人看见。
     */
    public static int[] gestureZones(int screenW, int screenH, int edgePx, int bottomPx) {
        if (screenW <= 0 || screenH <= 0 || edgePx <= 0 || bottomPx <= 0) return new int[12];
        int bt = screenH - bottomPx;
        return new int[]{0, 0, edgePx, bt, 0, bt, screenW, screenH, screenW - edgePx, 0, screenW, bt};
    }

    /** [exec-1124 B] 观察区：手势导航 = {@link #gestureZones}（12 个数），三键 = {@link #compute}（8 个数）。 */
    public static int[] watchZones(boolean gestureNav, int screenW, int screenH, int navH, int keyOrder, float density) {
        if (gestureNav) return gestureZones(screenW, screenH, Math.round(24 * density), Math.round(32 * density));
        return compute(screenW, screenH, navH, keyOrder);
    }

    /** [exec-1124 B] 系统现在是不是全手势导航。 */
    public static boolean gestureNav(Context ctx) {
        try {
            return com.onesay.assistant.v2.exec.EdgeGesture.gestureNav(android.provider.Settings.Secure.getInt(ctx.getContentResolver(), "navigation_mode", 0));
        } catch (Throwable t) {
            return false;
        }
    }

    /** [exec-1124 B] 本机的观察区。 */
    public static int[] watchZones(Context ctx) {
        if (!gestureNav(ctx)) return rects(ctx);
        try {
            Point p = new Point();
            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(p);
            return watchZones(true, p.x, p.y, 0, 0, ctx.getResources().getDisplayMetrics().density);
        } catch (Throwable t) {
            return rects(ctx);
        }
    }

    public static int[] rects(Context ctx) {
        try {
            Resources r = ctx.getResources();
            int id = r.getIdentifier("navigation_bar_height", "dimen", "android");
            int navH = id > 0 ? r.getDimensionPixelSize(id) : 0;
            int order = android.provider.Settings.Global.getInt(ctx.getContentResolver(), "navigationbar_key_order", 0);
            Point p = new Point();
            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(p);
            return compute(p.x, p.y, navH, order);
        } catch (Throwable t) {
            return new int[8];
        }
    }
}
