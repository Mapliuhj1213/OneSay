package com.onesay.assistant.v2.ui.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** ★ 11-04 相机对话底部圆钮（自绘矢量：翻转 / 麦克风 / 手电筒 / 挂断），不依赖字体里的符号。 */
final class LiveCtlButton extends View {

    static final int FLIP = 1, MIC = 2, TORCH = 3, HANGUP = 4;

    private final int mKind;
    private final float mDp;
    private final Paint mBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mR = new RectF();
    private final Path mPath = new Path();
    private boolean mOn;
    private boolean mDown;

    LiveCtlButton(Context c, int kind) {
        super(c);
        mKind = kind;
        mDp = c.getResources().getDisplayMetrics().density;
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(2f * mDp);
        mStroke.setStrokeCap(Paint.Cap.ROUND);
        mStroke.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
        updateDesc();
    }

    /** MIC：on = 已静音；TORCH：on = 已打开。 */
    void setOn(boolean on) {
        if (mOn == on) return;
        mOn = on;
        updateDesc();
        invalidate();
    }

    boolean isOn() { return mOn; }

    private void updateDesc() {
        switch (mKind) {
            case FLIP: setContentDescription("切换前后摄像头"); break;
            case MIC: setContentDescription(mOn ? "取消静音" : "静音麦克风"); break;
            case TORCH: setContentDescription(mOn ? "关闭手电筒" : "打开手电筒"); break;
            default: setContentDescription("挂断"); break;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) { mDown = true; invalidate(); }
        else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { mDown = false; invalidate(); }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f, d = mDp;
        int bg;
        if (mKind == HANGUP) bg = mDown ? 0xFFB3261E : 0xFFE5483D;
        else if (mOn) bg = mDown ? 0xDDDDDDDD : 0xFFF2F2F2;
        else bg = mDown ? 0x66FFFFFF : 0x40FFFFFF;
        mBg.setColor(bg);
        c.drawCircle(cx, cy, r, mBg);
        int g = (mOn && mKind != HANGUP) ? 0xFF1C1C1E : 0xFFFFFFFF;
        mStroke.setColor(g);
        if (mKind == FLIP) {
            float rr = 8.5f * d;
            mR.set(cx - rr, cy - rr, cx + rr, cy + rr);
            c.drawArc(mR, 200f, 140f, false, mStroke);
            c.drawArc(mR, 20f, 140f, false, mStroke);
            // 箭头：右上弧尾 & 左下弧尾
            float ax = cx + (float) (rr * Math.cos(Math.toRadians(340))), ay = cy + (float) (rr * Math.sin(Math.toRadians(340)));
            c.drawLine(ax, ay, ax - 4f * d, ay - 1.2f * d, mStroke);
            c.drawLine(ax, ay, ax + 0.6f * d, ay - 4f * d, mStroke);
            float bx = cx + (float) (rr * Math.cos(Math.toRadians(160))), by = cy + (float) (rr * Math.sin(Math.toRadians(160)));
            c.drawLine(bx, by, bx + 4f * d, by + 1.2f * d, mStroke);
            c.drawLine(bx, by, bx - 0.6f * d, by + 4f * d, mStroke);
        } else if (mKind == MIC) {
            float mw = 7.5f * d, mh = 12f * d, top = cy - 10f * d;
            mR.set(cx - mw / 2f, top, cx + mw / 2f, top + mh);
            c.drawRoundRect(mR, mw / 2f, mw / 2f, mStroke);
            mR.set(cx - 7.5f * d, top + 3.5f * d, cx + 7.5f * d, top + mh + 4.5f * d);
            c.drawArc(mR, 15f, 150f, false, mStroke);
            c.drawLine(cx, top + mh + 4.5f * d, cx, cy + 9.5f * d, mStroke);
            if (mOn) c.drawLine(cx - 9f * d, cy - 9f * d, cx + 9f * d, cy + 9f * d, mStroke);
        } else if (mKind == TORCH) {
            mPath.reset();
            mPath.moveTo(cx + 2f * d, cy - 11f * d);
            mPath.lineTo(cx - 6f * d, cy + 1.5f * d);
            mPath.lineTo(cx + 0.5f * d, cy + 1.5f * d);
            mPath.lineTo(cx - 2f * d, cy + 11f * d);
            mPath.lineTo(cx + 6f * d, cy - 1.5f * d);
            mPath.lineTo(cx - 0.5f * d, cy - 1.5f * d);
            mPath.close();
            c.drawPath(mPath, mStroke);
        } else {
            float k = 7f * d;
            c.drawLine(cx - k, cy - k, cx + k, cy + k, mStroke);
            c.drawLine(cx + k, cy - k, cx - k, cy + k, mStroke);
        }
    }
}
