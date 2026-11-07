package com.onesay.assistant.v2.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import com.onesay.assistant.v2.router.MarketChart;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ fix/shop-research（用户 11-04 00:13「行情类结论要画图展示」）：主要指数分地区横向涨跌幅柱状图，原生 Canvas 自绘
 * （不联网、不建 WebView、无新依赖）。数据只来自 {@link MarketChart.Model}（MarketQuotes 结构化源原字符串）。
 * <p>每行：指数短名 ｜ 以 0 为中线的涨跌幅柱（右红涨、左绿跌）｜ 点位（2 位小数）+ 涨跌幅（两列按实测宽度排，不截断）；未查到的行只写「未查到」不画柱；
 * 地区小标题（A股 / 港股 / 美股 / 欧洲 / 日本）；图下脚注：行情时间与时区、来源。</p>
 */
public class MarketChartView extends View {

    private final MarketChart.Model mModel;
    private final float mDp;
    private final TextPaint mName = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mValue = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mHead = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mFoot = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<StaticLayout> mFootLayouts = new ArrayList<StaticLayout>();
    private int mFootWidth = -1;
    private final RectF mRect = new RectF();

    public MarketChartView(Context context, MarketChart.Model model, int textMain, int textSub, int textMuted) {
        super(context);
        mModel = model;
        mDp = context.getResources().getDisplayMetrics().density;
        float sp = context.getResources().getDisplayMetrics().scaledDensity;
        mName.setColor(textMain);
        mName.setTextSize(13.5f * sp);
        mValue.setColor(textSub);
        mValue.setTextSize(12.5f * sp);
        mValue.setTextAlign(Paint.Align.RIGHT);
        mHead.setColor(textMuted);
        mHead.setTextSize(12f * sp);
        mHead.setFakeBoldText(true);
        mFoot.setColor(textMuted);
        mFoot.setTextSize(11f * sp);
        mAxis.setColor(0x40FFFFFF);
        mAxis.setStrokeWidth(Math.max(1f, mDp));
        setContentDescription("主要指数涨跌幅柱状图");
    }

    private float rowH() { return 26 * mDp; }
    private float headH() { return 22 * mDp; }

    private void layoutFooter(int width) {
        if (width == mFootWidth) return;
        mFootWidth = width;
        mFootLayouts.clear();
        for (String f : mModel.footer) {
            mFootLayouts.add(new StaticLayout(f, mFoot, Math.max(1, width), Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false));
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        if (w <= 0) w = (int) (320 * mDp);
        layoutFooter(w);
        float h = 4 * mDp;
        for (MarketChart.Group g : mModel.groups) h += headH() + g.bars.size() * rowH();
        h += 8 * mDp;
        for (StaticLayout l : mFootLayouts) h += l.getHeight() + 2 * mDp;
        setMeasuredDimension(w, (int) Math.ceil(h + 6 * mDp));
    }

    private float mBaseName = -1, mBaseValue = -1;

    /** ★ 11-04：名称列 / 数值列按实测最大宽度排；放不下整体缩字号（{@link MarketChart#fitScale}），任何一行不出省略号。 */
    private float[] columns(int w) {
        if (mBaseName < 0) { mBaseName = mName.getTextSize(); mBaseValue = mValue.getTextSize(); }
        mName.setTextSize(mBaseName);
        mValue.setTextSize(mBaseValue);
        float maxN = 0, maxV = 0;
        for (MarketChart.Group g : mModel.groups) {
            for (MarketChart.Bar b : g.bars) {
                maxN = Math.max(maxN, mName.measureText(b.name));
                maxV = Math.max(maxV, mValue.measureText(b.valueText()));
            }
        }
        float gaps = 14 * mDp;
        float sc = MarketChart.fitScale(w, maxN, maxV, gaps, 48 * mDp);
        if (sc < 1f) {
            mName.setTextSize(mBaseName * sc);
            mValue.setTextSize(mBaseValue * sc);
            maxN *= sc;
            maxV *= sc;
        }
        return new float[]{maxN, maxV};
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth();
        layoutFooter(w);
        float[] col = columns(w);
        float nameW = col[0] + 4 * mDp;
        float valueW = col[1];
        float barL = nameW + 4 * mDp, barR = Math.max(barL + 2 * mDp, w - valueW - 6 * mDp);
        float mid = (barL + barR) / 2f, half = Math.max(1f, (barR - barL) / 2f);
        float y = 4 * mDp;
        for (MarketChart.Group g : mModel.groups) {
            Paint.FontMetrics hf = mHead.getFontMetrics();
            c.drawText(g.region, 0, y + (headH() - hf.ascent - hf.descent) / 2f, mHead);
            y += headH();
            for (MarketChart.Bar b : g.bars) {
                float cy = y + rowH() / 2f;
                Paint.FontMetrics nf = mName.getFontMetrics();
                float base = cy - (nf.ascent + nf.descent) / 2f;
                c.drawText(b.name, 0, base, mName);
                c.drawLine(mid, y + 3 * mDp, mid, y + rowH() - 3 * mDp, mAxis);
                if (b.drawBar()) {
                    float len = (float) (Math.min(1d, Math.abs(b.pct) / mModel.maxAbs) * half);
                    len = Math.max(len, 2 * mDp);
                    float bh = 7 * mDp;
                    if (b.pct >= 0) mRect.set(mid, cy - bh, mid + len, cy + bh);
                    else mRect.set(mid - len, cy - bh, mid, cy + bh);
                    mBar.setColor(b.color);
                    c.drawRoundRect(mRect, 3 * mDp, 3 * mDp, mBar);
                    mValue.setColor(b.color);
                } else {
                    mValue.setColor(mFoot.getColor());
                }
                c.drawText(b.valueText(), w, base, mValue);
                y += rowH();
            }
        }
        y += 8 * mDp;
        for (StaticLayout l : mFootLayouts) {
            c.save();
            c.translate(0, y);
            l.draw(c);
            c.restore();
            y += l.getHeight() + 2 * mDp;
        }
    }
}
