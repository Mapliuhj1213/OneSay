package com.onesay.assistant.v2.a11ylabel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;

import com.onesay.assistant.v2.phone.UiTree;

/**
 * ★ a11y-labels：「显示按钮」的悬浮编号层（只画、不拦触摸：FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE）。
 * 只给 {@link ButtonNumbers.Session} 里的真实可点节点画圆形标号和细框；会话里一个都没有就不加窗口（不画假格子）。
 * 主线程调用。
 */
public final class ButtonNumbersOverlay extends View {

    private final WindowManager mWm;
    private ButtonNumbers.Session mSession;
    private int[][] mPos = new int[0][];
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG), mText = new Paint(Paint.ANTI_ALIAS_FLAG), mBox = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mRadius;
    private boolean mAdded;

    public ButtonNumbersOverlay(Context ctx) {
        super(ctx.getApplicationContext());
        mWm = (WindowManager) ctx.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        float d = ctx.getResources().getDisplayMetrics().density;
        mRadius = Math.round(11 * d);
        mDot.setColor(Color.argb(230, 255, 87, 34));
        mText.setColor(Color.WHITE);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTextSize(12 * d);
        mText.setFakeBoldText(true);
        mBox.setStyle(Paint.Style.STROKE);
        mBox.setStrokeWidth(Math.max(1f, 1.5f * d));
        mBox.setColor(Color.argb(170, 255, 87, 34));
    }

    /** 显示（已显示就换内容）。会话为空 / 没有可点节点 → 收起，返回 false。 */
    public boolean show(ButtonNumbers.Session s) {
        if (s == null || s.size() == 0) {
            hide();
            return false;
        }
        mSession = s;
        mPos = ButtonNumbers.badgePositions(s, mRadius);
        if (!mAdded) {
            int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.x = 0;
            lp.y = 0;
            lp.setTitle("ButtonNumbers");
            try {
                mWm.addView(this, lp);
                mAdded = true;
            } catch (RuntimeException e) {   // 没有悬浮窗权限
                return false;
            }
        }
        invalidate();
        return true;
    }

    public void hide() {
        mSession = null;
        if (mAdded) {
            try {
                mWm.removeViewImmediate(this);
            } catch (RuntimeException ignored) {
            }
            mAdded = false;
        }
    }

    public boolean showing() {
        return mAdded && mSession != null;
    }

    @Override protected void onDraw(Canvas c) {
        ButtonNumbers.Session s = mSession;
        if (s == null) return;
        int[] loc = new int[2];
        getLocationOnScreen(loc);   // 树里是屏幕坐标；窗口原点不在 (0,0) 时减掉
        for (int i = 0; i < s.size() && i < mPos.length; i++) {
            UiTree.El e = s.numbered.get(i);
            c.drawRect(e.l - loc[0], e.t - loc[1], e.r - loc[0], e.b - loc[1], mBox);
            float x = mPos[i][0] - loc[0], y = mPos[i][1] - loc[1];
            c.drawCircle(x, y, mRadius, mDot);
            c.drawText(String.valueOf(i + 1), x, y - (mText.descent() + mText.ascent()) / 2, mText);
        }
    }
}
