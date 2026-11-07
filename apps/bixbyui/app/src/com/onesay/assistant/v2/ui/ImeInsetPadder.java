package com.onesay.assistant.v2.ui;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;

import com.onesay.assistant.v2.ui.wake.WindowFlowPolicy;

/**
 * ★ integrate（2026-10-24 上机发现）：沉浸式全屏（系统 policy_control immersive.full）下 adjustResize 不压缩窗口，
 * 键盘会盖住 Activity 底部的按钮条 / 输入框。这里按可见区手动给 {@code target} 补底部内边距（几何见
 * {@link WindowFlowPolicy#activityImePad}）。adjustResize 正常生效时差值为 0，什么都不做。
 */
public final class ImeInsetPadder {
    private ImeInsetPadder() {
    }

    public static void attach(Activity a, final View target) {
        if (a == null || target == null) return;
        final View content = a.findViewById(android.R.id.content);
        if (content == null) return;
        final int basePad = target.getPaddingBottom();
        final Rect r = new Rect();
        final int[] loc = new int[2];
        content.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                try {
                    content.getWindowVisibleDisplayFrame(r);
                    content.getLocationOnScreen(loc);
                    int screenH = content.getRootView().getHeight();
                    int pad = WindowFlowPolicy.activityImePad(loc[1] + content.getHeight(), r.bottom, screenH);
                    int want = basePad + pad;
                    if (target.getPaddingBottom() != want) {
                        target.setPadding(target.getPaddingLeft(), target.getPaddingTop(), target.getPaddingRight(), want);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
