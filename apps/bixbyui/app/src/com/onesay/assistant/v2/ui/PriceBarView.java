package com.onesay.assistant.v2.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

import com.onesay.assistant.v2.router.ResearchVisuals;

/**
 * ★ 11-04 ④：调研 / 购物结论的价格横向条形图（原生 Canvas，复用 MarketChartView 的三列思路：名称 / 条 / 数值）。
 * 不联网、无 WebView、无新依赖；列宽按实测最大宽度排，放不下整体缩字号（下限 0.6），不出省略号。
 */
public class PriceBarView extends View {

    private final ResearchVisuals.Model mModel;
    private final float mDp;
    private final TextPaint mName = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mValue = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mFoot = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float mNameSize, mValueSize;

    public PriceBarView(Context context, ResearchVisuals.Model model, int textMain, int textSub, int textMuted) {
        super(context);
        mModel = model;
        mDp = context.getResources().getDisplayMetrics().density;
        float sp = context.getResources().getDisplayMetrics().scaledDensity;
        mNameSize = 13.5f * sp;
        mValueSize = 12.5f * sp;
        mName.setColor(textMain);
        mName.setTextSize(mNameSize);
        mValue.setColor(textSub);
        mValue.setTextSize(mValueSize);
        mValue.setTextAlign(Paint.Align.RIGHT);
        mFoot.setColor(textMuted);
        mFoot.setTextSize(11f * sp);
        mBar.setColor(0xFF5E5CE6);
        setContentDescription("价格对比条形图");
    }

    private float rowH() { return 26 * mDp; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = (int) (mModel.bars.size() * rowH() + 22 * mDp);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        if (w <= 0 || mModel.bars.isEmpty()) return;
        float gap = 8 * mDp;
        float scale = 1f;
        float nameW = 0, valW = 0;
        for (int it = 0; it < 6; it++) {
            mName.setTextSize(mNameSize * scale);
            mValue.setTextSize(mValueSize * scale);
            nameW = 0;
            valW = 0;
            for (ResearchVisuals.Bar b : mModel.bars) {
                nameW = Math.max(nameW, mName.measureText(b.label));
                valW = Math.max(valW, mValue.measureText(ResearchVisuals.fmt(b)));
            }
            if (nameW + valW + 2 * gap + 40 * mDp <= w || scale <= 0.6f) break;
            scale = Math.max(0.6f, scale - 0.08f);
        }
        float barX = nameW + gap;
        float barMax = Math.max(10 * mDp, w - barX - valW - gap);
        double max = mModel.max();
        float y = 0;
        for (ResearchVisuals.Bar b : mModel.bars) {
            float cy = y + rowH() / 2f;
            c.drawText(b.label, 0, cy + mName.getTextSize() / 3f, mName);
            float len = max <= 0 ? 0 : (float) (barMax * (b.value / max));
            mRect.set(barX, cy - 6 * mDp, barX + Math.max(2 * mDp, len), cy + 6 * mDp);
            c.drawRoundRect(mRect, 3 * mDp, 3 * mDp, mBar);
            c.drawText(ResearchVisuals.fmt(b), w, cy + mValue.getTextSize() / 3f, mValue);
            y += rowH();
        }
        c.drawText("价格取自上面结论，以平台页面为准", 0, y + 15 * mDp, mFoot);
    }
}
