package com.onesay.assistant.v2.ui.wake;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import com.onesay.assistant.v2.ui.shape.Squircle;

/**
 * [R13 唤醒界面 v2] 悬浮小卡的材质（方案 v2.3 / v2.4）：
 * <ul>
 *   <li>阴影（远影 + 近影）、外晕、内发光：首次显示时用连续圆角 Path 在<b>软件 Canvas</b> 上烘焙成 9 宫格位图
 *       （四角不拉伸、中间一行 / 一列拉伸），卡片长高时只是拉伸 → 每帧零路径栅格化；BlurMaskFilter 只用在烘焙时（软件可用）。</li>
 *   <li>填充：近黑竖向渐变 #161619 → #09090B（D1=A 黑卡，与岛同一材质；渐变用单位空间 Shader + 局部矩阵，长高时不新建对象）。</li>
 *   <li>顶部 1dp 受光高光（中间满、两侧按 1−|x|^2.2 衰减），无描边。</li>
 * </ul>
 * 形变舞台用的是 drawRoundRect（普通圆角）—— n/k 按 45° 重合选取，同 r 下与连续圆角最大差约 1px。
 */
public final class WakeCardPainter {

    public static final int FILL_TOP = 0xFF161619;
    public static final int FILL_BOTTOM = 0xFF09090B;
    public static final int MOSS = 0xFF8CD27A;

    private final float d;
    public final float radius;
    private final float pad;
    private final float extent;
    private Bitmap mShadow, mHalo, mInner;
    private int mSlice;   // 9 宫格四角边长（px）
    private final Paint mBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mTint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHi = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearGradient mFillShader;
    private final Matrix mFillM = new Matrix();
    private LinearGradient mHiShader;
    private float mHiW = -1f;
    private final Matrix mHiM = new Matrix();
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();

    public WakeCardPainter(Context ctx) {
        d = ctx.getResources().getDisplayMetrics().density;
        radius = 34f * d;
        pad = 40f * d;
        extent = Squircle.K * radius;
        mFillShader = new LinearGradient(0, 0, 0, 1, FILL_TOP, FILL_BOTTOM, Shader.TileMode.CLAMP);
        mFill.setShader(mFillShader);
    }

    public boolean baked() {
        return mShadow != null;
    }

    /** 烘焙（软件 Canvas，约 5–10 ms；放在 CARD_IN 第一帧之前或进程启动时预热）。 */
    public synchronized void bake() {
        if (mShadow != null) return;
        long t0 = android.os.SystemClock.uptimeMillis();
        mSlice = (int) Math.ceil(pad + extent);
        int size = mSlice * 2 + 2;
        RectF card = new RectF(pad, pad, size - pad, size - pad);
        Path shape = Squircle.build(card.left, card.top, card.right, card.bottom, radius, new Path());

        // 阴影：远影 28dp 模糊 y+14dp 黑 62%；近影 6dp y+3dp 黑 35%（v2.4）
        mShadow = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(mShadow);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x9E000000);
        p.setMaskFilter(new BlurMaskFilter(14f * d, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, 14f * d);
        c.drawPath(shape, p);
        c.restore();
        p.setColor(0x59000000);
        p.setMaskFilter(new BlurMaskFilter(3f * d, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, 3f * d);
        c.drawPath(shape, p);
        c.restore();

        // 外晕（光吸进卡片后）：卡片轮廓 34dp 羽化，ALPHA_8，绘制时上苔绿
        mHalo = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
        c = new Canvas(mHalo);
        p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setMaskFilter(new BlurMaskFilter(12f * d, BlurMaskFilter.Blur.OUTER));
        c.drawPath(shape, p);

        // 内发光（代替描边）：形状内侧 11dp 羽化，上沿强、下沿弱（×1.15 → ×0.25）
        mInner = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
        c = new Canvas(mInner);
        p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        // BlurMaskFilter 的 radius 约等于 2σ：6dp 半径 ≈ 11dp 可见羽化（真机首版用 11dp 半径，光渗满整张小卡、卡片发绿）
        p.setMaskFilter(new BlurMaskFilter(6f * d, BlurMaskFilter.Blur.INNER));
        c.drawPath(shape, p);
        Paint g = new Paint();
        g.setShader(new LinearGradient(0, card.top, 0, card.bottom, 0xFF000000, 0x3A000000, Shader.TileMode.CLAMP));
        g.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawRect(0, 0, size, size, g);
        android.util.Log.i("WakeUI", "[WAKE-UI] 卡片材质烘焙 " + size + "px² 用时 " + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
    }

    public synchronized void release() {
        if (mShadow != null) mShadow.recycle();
        if (mHalo != null) mHalo.recycle();
        if (mInner != null) mInner.recycle();
        mShadow = mHalo = mInner = null;
    }

    /**
     * @param card       卡片矩形（view 坐标）
     * @param alpha      整体不透明度 0..1
     * @param innerGlow  内发光强度 0..1（聆听 0.22 / 作答 0.30 / 平时 0.12）
     * @param halo       外晕强度 0..1（光吸入后 0.17）
     * @param glowColor  内发光 / 外晕颜色（随 bloom 漂移的当前色）
     */
    public void draw(Canvas c, RectF card, float alpha, float innerGlow, float halo, int glowColor) {
        if (mShadow == null) bake();
        int a = Math.round(255 * clamp(alpha));
        if (a <= 0) return;
        mBmp.setAlpha(a);
        nine(c, mShadow, card, mBmp);
        if (halo > 0.003f) {
            mTint.setColor(glowColor);
            mTint.setAlpha(Math.round(255 * clamp(halo) * clamp(alpha)));
            nine(c, mHalo, card, mTint);
        }
        mFillM.setScale(1f, Math.max(1f, card.height()));
        mFillM.postTranslate(0, card.top);
        mFillShader.setLocalMatrix(mFillM);
        mFill.setAlpha(a);
        float r = Math.min(radius, Math.min(card.width(), card.height()) / 2f);
        c.drawRoundRect(card, r, r, mFill);
        if (innerGlow > 0.003f) {
            mTint.setColor(glowColor);
            mTint.setAlpha(Math.round(255 * clamp(innerGlow) * clamp(alpha)));
            nine(c, mInner, card, mTint);
        }
        drawHighlight(c, card, alpha, 0.30f);
    }

    /** 顶部 1dp 受光高光：白 30%，水平中间满、两侧衰减；外加顶部 46dp 内白 4.5% 的极淡光泽。 */
    private void drawHighlight(Canvas c, RectF card, float alpha, float strength) {
        float w = card.width() - 2f * radius * 0.6f;
        if (w <= 2f) return;
        if (mHiShader == null || Math.abs(mHiW - w) > 0.5f) {
            int n = 9;
            int[] cols = new int[n];
            float[] pos = new float[n];
            for (int i = 0; i < n; i++) {
                float x = i / (float) (n - 1) * 2f - 1f;
                float k = (float) (1.0 - Math.pow(Math.abs(x), 2.2));
                cols[i] = ((Math.round(255 * k)) << 24) | 0x00FFFFFF;
                pos[i] = i / (float) (n - 1);
            }
            mHiShader = new LinearGradient(0, 0, w, 0, cols, pos, Shader.TileMode.CLAMP);
            mHiW = w;
        }
        mHiM.setTranslate(card.left + radius * 0.6f, 0);
        mHiShader.setLocalMatrix(mHiM);
        mHi.setShader(mHiShader);
        mHi.setAlpha(Math.round(255 * strength * clamp(alpha)));
        float y = card.top + 0.5f * d;
        c.drawRect(card.left + radius * 0.6f, y, card.left + radius * 0.6f + w, y + Math.max(1f, d), mHi);
        mHi.setShader(null);
    }

    /**
     * 9 宫格绘制（位图四角 = mSlice，卡片四周外扩 pad）。卡片比两个角还矮（聆听态 84dp）时<b>裁</b>角而不是缩角：
     * 上角取位图上半截、下角取下半截，位图上下对称 → 接缝处数值相同、没有缝（真机首版缩角在接缝处出了一道亮线）。
     * 四角 1:1 绘制（不缩放、不滤波糊），只有中间行 / 列拉伸。
     */
    private void nine(Canvas c, Bitmap b, RectF card, Paint p) {
        if (b == null || b.isRecycled()) return;
        int s = mSlice, bw = b.getWidth(), bh = b.getHeight();
        float l = card.left - pad, t = card.top - pad, r = card.right + pad, bo = card.bottom + pad;
        int cx = (int) Math.min(s, Math.floor((r - l) / 2f));
        int cy = (int) Math.min(s, Math.floor((bo - t) / 2f));
        float[] xs = {l, l + cx, r - cx, r};
        float[] ys = {t, t + cy, bo - cy, bo};
        int[] sx = {0, cx, bw - cx, bw};
        int[] sy = {0, cy, bh - cy, bh};
        if (cx < s) {   // 中间列取位图正中 1px
            sx[1] = bw / 2;
            sx[2] = bw / 2 + 1;
        }
        if (cy < s) {
            sy[1] = bh / 2;
            sy[2] = bh / 2 + 1;
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (xs[j + 1] - xs[j] < 0.5f || ys[i + 1] - ys[i] < 0.5f) continue;
                int x0 = j == 2 && cx < s ? bw - cx : sx[j], x1 = j == 0 && cx < s ? cx : sx[j + 1];
                int y0 = i == 2 && cy < s ? bh - cy : sy[i], y1 = i == 0 && cy < s ? cy : sy[i + 1];
                mSrc.set(x0, y0, Math.max(x0 + 1, x1), Math.max(y0 + 1, y1));
                mDst.set(xs[j], ys[i], xs[j + 1], ys[i + 1]);
                c.drawBitmap(b, mSrc, mDst, p);
            }
        }
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
