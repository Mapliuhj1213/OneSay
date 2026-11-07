package com.onesay.assistant.v2.ui.console;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * [console R12] 灵动岛页顶部的预览（示意图 03）：画一个紧凑态的岛（纯黑药丸 + 红点 + 动作词 + 进度环）。
 * 只是静态示意，不碰真正的岛和面板→岛的动画。两种主题下岛都是纯黑。
 */
public final class IslandPreview {

    private final Activity mAct;

    public IslandPreview(Activity act) {
        mAct = act;
    }

    public View card() {
        ConsoleKit k = new ConsoleKit(mAct, ConsoleTheme.current());
        LinearLayout outer = k.column();
        outer.setBackground(k.round(k.t.surface, ConsoleTheme.R_HERO));
        outer.setPadding(k.dp(16), k.dp(16), k.dp(16), k.dp(16));
        LinearLayout inner = k.column();
        inner.setGravity(Gravity.CENTER_HORIZONTAL);
        inner.setBackground(k.round(k.t.surface2, ConsoleTheme.R_CARD - 8));
        inner.setPadding(k.dp(12), k.dp(18), k.dp(12), k.dp(16));
        Pill pill = new Pill(mAct, k);
        inner.addView(pill, new LinearLayout.LayoutParams(k.dp(116), k.dp(30)));
        TextView cap = k.text("后台任务、确认、提醒、取件码显示在这里", ConsoleTheme.SP_SUB, k.t.text2, ConsoleKit.W_REGULAR);
        cap.setGravity(Gravity.CENTER);
        cap.setPadding(0, k.dp(14), 0, 0);
        inner.addView(cap);
        outer.addView(inner);
        outer.setContentDescription("灵动岛预览");
        outer.setLayoutParams(k.lpMatch(4, 0));
        return outer;
    }

    static final class Pill extends View {
        private final Paint mBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mR = new RectF();
        private final float mD;

        Pill(Context ctx, ConsoleKit k) {
            super(ctx);
            mD = ctx.getResources().getDisplayMetrics().density;
            mBg.setColor(k.t.island);
            mDot.setColor(0xFFE5484D);   // 岛的红点（和 IslandView 同色）
            mText.setColor(k.t.islandText);
            mText.setTextSize(13 * ctx.getResources().getDisplayMetrics().scaledDensity);
            mText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mRing.setColor(0xFF84AE64);
            mRing.setStyle(Paint.Style.STROKE);
            mRing.setStrokeWidth(2.2f * mD);
            mRing.setStrokeCap(Paint.Cap.ROUND);
            mTrack.setColor(0xFF2A2927);
            mTrack.setStyle(Paint.Style.STROKE);
            mTrack.setStrokeWidth(2.2f * mD);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            mR.set(0, 0, w, h);
            c.drawRoundRect(mR, h / 2f, h / 2f, mBg);
            c.drawCircle(h / 2f + 2 * mD, h / 2f, 4 * mD, mDot);
            Paint.FontMetrics fm = mText.getFontMetrics();
            c.drawText("查快递", h / 2f + 12 * mD, h / 2f - (fm.ascent + fm.descent) / 2f, mText);
            float r = 7.5f * mD, cx = w - h / 2f, cy = h / 2f;
            mR.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(mR, 0, 360, false, mTrack);
            c.drawArc(mR, -90, 250, false, mRing);
        }
    }
}
