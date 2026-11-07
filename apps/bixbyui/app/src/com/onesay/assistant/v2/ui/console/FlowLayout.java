package com.onesay.assistant.v2.ui.console;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** [console R12] 状态胶囊用的自动换行布局（一行放不下就折到下一行，不截断文字）。 */
public final class FlowLayout extends ViewGroup {

    private final int mHGap, mVGap;

    public FlowLayout(Context ctx, int hGap, int vGap) {
        super(ctx);
        mHGap = hGap;
        mVGap = vGap;
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineH = 0, usedW = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            if (x > 0 && x + cw > maxW) {
                y += lineH + mVGap;
                x = 0;
                lineH = 0;
            }
            x += cw + mHGap;
            usedW = Math.max(usedW, x - mHGap);
            lineH = Math.max(lineH, ch);
        }
        int h = y + lineH + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(usedW + getPaddingLeft() + getPaddingRight(), wSpec), resolveSize(h, hSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            if (x > 0 && x + cw > maxW) {
                y += lineH + mVGap;
                x = 0;
                lineH = 0;
            }
            c.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + cw, getPaddingTop() + y + ch);
            x += cw + mHGap;
            lineH = Math.max(lineH, ch);
        }
    }
}
