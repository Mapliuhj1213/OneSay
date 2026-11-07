package com.onesay.assistant.v2.ui.chat;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * ★ ui-K1 对话窗的线框图标（矢量自绘，无资源 / 无新依赖）：朗读、复制、选择文字(T)、分享、详情(i)、赞、踩、铅笔、
 * 加号、麦克风、通话、圈选、Bixby 彩色标。按 24 单位网格画，绘制时等比缩放到 bounds。
 */
public final class ChatIcons extends Drawable {

    public static final int PLAY = 1, COPY = 2, TEXT = 3, SHARE = 4, INFO = 5, LIKE = 6, DISLIKE = 7, PENCIL = 8,
            PLUS = 9, MIC = 10, CALL = 11, CIRCLE = 12, BIXBY = 13, CLOSE = 14, CAMERA = 15, GALLERY = 16, STOP = 17,
            /** 圆形「发送」：实心圆 + 镂空上箭头（箭头用 {@link #setCutColor} 的颜色，默认黑）。 */
            SEND = 18;

    private final int mKind;
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mR = new RectF();
    private boolean mFilled;
    private int mAlpha = 255;

    public ChatIcons(int kind, int color) {
        mKind = kind;
        mP.setColor(color);
        mP.setStyle(Paint.Style.STROKE);
        mP.setStrokeCap(Paint.Cap.ROUND);
        mP.setStrokeJoin(Paint.Join.ROUND);
    }

    /** 赞 / 踩已点：实心。 */
    public ChatIcons filled(boolean on) {
        mFilled = on;
        invalidateSelf();
        return this;
    }

    private int mCut = 0xFF151517;

    /** SEND 的箭头颜色（画在实心圆上）。 */
    public ChatIcons setCutColor(int c) {
        mCut = c;
        invalidateSelf();
        return this;
    }

    public void setColor(int c) {
        mP.setColor(c);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        float w = getBounds().width(), h = getBounds().height();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h) / 24f;
        c.save();
        c.translate(getBounds().left + (w - 24 * s) / 2f, getBounds().top + (h - 24 * s) / 2f);
        c.scale(s, s);
        mP.setStrokeWidth(1.8f);
        mP.setStyle(Paint.Style.STROKE);
        int baseAlpha = mP.getAlpha();
        mP.setAlpha(Math.round(baseAlpha * mAlpha / 255f));
        mP.setShader(null);
        switch (mKind) {
            case PLAY:
                c.drawCircle(12, 12, 9.5f, mP);
                mPath.reset();
                mPath.moveTo(10, 8.2f);
                mPath.lineTo(16, 12);
                mPath.lineTo(10, 15.8f);
                mPath.close();
                mP.setStyle(Paint.Style.FILL_AND_STROKE);
                mP.setStrokeWidth(1.2f);
                c.drawPath(mPath, mP);
                break;
            case STOP:
                c.drawCircle(12, 12, 9.5f, mP);
                mP.setStyle(Paint.Style.FILL);
                mR.set(9, 9, 15, 15);
                c.drawRoundRect(mR, 1.2f, 1.2f, mP);
                break;
            case COPY:
                mR.set(8, 7, 18, 19.5f);
                c.drawRoundRect(mR, 2.2f, 2.2f, mP);
                mPath.reset();
                mPath.moveTo(5.5f, 15.5f);
                mPath.lineTo(5.5f, 6.5f);
                mPath.quadTo(5.5f, 4.5f, 7.5f, 4.5f);
                mPath.lineTo(14.5f, 4.5f);
                c.drawPath(mPath, mP);
                break;
            case TEXT:
                mP.setStrokeWidth(2f);
                c.drawLine(6, 6, 18, 6, mP);
                c.drawLine(12, 6, 12, 19, mP);
                c.drawLine(9.5f, 19, 14.5f, 19, mP);
                c.drawLine(6, 6, 6, 8, mP);
                c.drawLine(18, 6, 18, 8, mP);
                break;
            case SHARE:
                c.drawCircle(17.5f, 6, 2.4f, mP);
                c.drawCircle(6.5f, 12, 2.4f, mP);
                c.drawCircle(17.5f, 18, 2.4f, mP);
                c.drawLine(8.6f, 10.9f, 15.4f, 7.1f, mP);
                c.drawLine(8.6f, 13.1f, 15.4f, 16.9f, mP);
                break;
            case INFO:
                c.drawCircle(12, 12, 9.5f, mP);
                c.drawLine(12, 11, 12, 16.5f, mP);
                mP.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 7.7f, 1.2f, mP);
                break;
            case LIKE:
            case DISLIKE:
                c.save();
                if (mKind == DISLIKE) c.rotate(180, 12, 12);
                mPath.reset();
                mPath.moveTo(8, 10.5f);
                mPath.lineTo(11.2f, 4.2f);
                mPath.quadTo(13.6f, 4.4f, 13.2f, 7.2f);
                mPath.lineTo(12.6f, 9.6f);
                mPath.lineTo(17.8f, 9.6f);
                mPath.quadTo(19.8f, 9.8f, 19.4f, 12f);
                mPath.lineTo(18, 18.2f);
                mPath.quadTo(17.6f, 19.8f, 15.8f, 19.8f);
                mPath.lineTo(8, 19.8f);
                mPath.close();
                if (mFilled) {
                    mP.setStyle(Paint.Style.FILL_AND_STROKE);
                }
                c.drawPath(mPath, mP);
                mP.setStyle(Paint.Style.STROKE);
                mR.set(4, 10.5f, 8, 19.8f);
                c.drawRoundRect(mR, 1f, 1f, mP);
                c.restore();
                break;
            case PENCIL:
                mPath.reset();
                mPath.moveTo(5, 19);
                mPath.lineTo(6, 15);
                mPath.lineTo(15.5f, 5.5f);
                mPath.quadTo(17, 4.2f, 18.5f, 5.5f);
                mPath.quadTo(19.8f, 7, 18.5f, 8.5f);
                mPath.lineTo(9, 18);
                mPath.close();
                c.drawPath(mPath, mP);
                c.drawLine(14, 7, 17, 10, mP);
                break;
            case PLUS:
                mP.setStrokeWidth(2.1f);
                c.drawLine(12, 4.5f, 12, 19.5f, mP);
                c.drawLine(4.5f, 12, 19.5f, 12, mP);
                break;
            case CLOSE:
                mP.setStrokeWidth(2f);
                c.drawLine(7, 7, 17, 17, mP);
                c.drawLine(17, 7, 7, 17, mP);
                break;
            case SEND: {
                mP.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 12, 11f, mP);
                int keep = mP.getColor();
                mP.setColor(mCut);
                mP.setAlpha(Math.round(Color.alpha(mCut) * mAlpha / 255f));
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(2.2f);
                c.drawLine(12, 17.5f, 12, 7f, mP);
                c.drawLine(7.5f, 11.2f, 12, 6.8f, mP);
                c.drawLine(16.5f, 11.2f, 12, 6.8f, mP);
                mP.setColor(keep);
                break;
            }
            case MIC:
                mR.set(9, 3.5f, 15, 14.5f);
                c.drawRoundRect(mR, 3f, 3f, mP);
                mPath.reset();
                mPath.moveTo(6, 11.5f);
                mPath.quadTo(6, 17.5f, 12, 17.5f);
                mPath.quadTo(18, 17.5f, 18, 11.5f);
                c.drawPath(mPath, mP);
                c.drawLine(12, 17.5f, 12, 20.5f, mP);
                break;
            case CALL:
                mPath.reset();
                mPath.moveTo(6.5f, 4.5f);
                mPath.lineTo(9, 4.5f);
                mPath.lineTo(10.4f, 8.2f);
                mPath.lineTo(8.6f, 9.6f);
                mPath.quadTo(10, 13.2f, 13.6f, 14.6f);
                mPath.lineTo(15, 12.8f);
                mPath.lineTo(18.6f, 14.2f);
                mPath.lineTo(18.6f, 16.8f);
                mPath.quadTo(18.4f, 19.2f, 16, 19.2f);
                mPath.quadTo(5, 18.4f, 4.4f, 7f);
                mPath.quadTo(4.4f, 4.6f, 6.5f, 4.5f);
                mPath.close();
                c.drawPath(mPath, mP);
                sparkle(c, 18.2f, 5.2f, 2.6f);
                break;
            case CIRCLE:
                // 四角括号 + 中间小星（圈选识屏）
                float k = 5f;
                c.drawLine(4, 4 + k, 4, 6, mP);
                c.drawArc(new RectF(4, 4, 8, 8), 180, 90, false, mP);
                c.drawLine(6, 4, 4 + k, 4, mP);
                c.drawLine(20 - k, 4, 18, 4, mP);
                c.drawArc(new RectF(16, 4, 20, 8), 270, 90, false, mP);
                c.drawLine(20, 6, 20, 4 + k, mP);
                c.drawLine(4, 20 - k, 4, 18, mP);
                c.drawArc(new RectF(4, 16, 8, 20), 90, 90, false, mP);
                c.drawLine(6, 20, 4 + k, 20, mP);
                c.drawLine(20 - k, 20, 18, 20, mP);
                c.drawArc(new RectF(16, 16, 20, 20), 0, 90, false, mP);
                c.drawLine(20, 18, 20, 20 - k, mP);
                sparkle(c, 12, 12, 4f);
                break;
            case CAMERA:
                mR.set(3.5f, 7, 20.5f, 19);
                c.drawRoundRect(mR, 2.5f, 2.5f, mP);
                c.drawLine(9, 7, 10.2f, 4.8f, mP);
                c.drawLine(10.2f, 4.8f, 13.8f, 4.8f, mP);
                c.drawLine(13.8f, 4.8f, 15, 7, mP);
                c.drawCircle(12, 13, 3.4f, mP);
                break;
            case GALLERY:
                mR.set(3.5f, 4.5f, 20.5f, 19.5f);
                c.drawRoundRect(mR, 2.5f, 2.5f, mP);
                c.drawCircle(9, 9.5f, 1.8f, mP);
                mPath.reset();
                mPath.moveTo(4, 17.5f);
                mPath.lineTo(9.5f, 12.5f);
                mPath.lineTo(13, 15.5f);
                mPath.lineTo(16, 13);
                mPath.lineTo(20, 16.5f);
                c.drawPath(mPath, mP);
                break;
            case BIXBY:
                // 三个交叠的彩色椭圆环（截图里输入框左边的彩色标）
                mP.setStrokeWidth(2.2f);
                int[][] cols = {{0xFFFF6B9A, 0xFFFFB648}, {0xFF8E7BFF, 0xFF4FC3FF}, {0xFFFFA24D, 0xFFFF5C7A}};
                for (int i = 0; i < 3; i++) {
                    c.save();
                    c.rotate(i * 60f, 12, 12);
                    mP.setShader(new LinearGradient(4, 12, 20, 12, cols[i][0], cols[i][1], Shader.TileMode.CLAMP));
                    mR.set(4, 8.2f, 20, 15.8f);
                    c.drawOval(mR, mP);
                    c.restore();
                }
                mP.setShader(null);
                sparkle(c, 19.5f, 4.5f, 2.4f);
                break;
            default:
                break;
        }
        mP.setAlpha(baseAlpha);
        c.restore();
    }

    /** 四角星。 */
    private void sparkle(Canvas c, float cx, float cy, float r) {
        Paint.Style st = mP.getStyle();
        mP.setStyle(Paint.Style.FILL);
        mPath.reset();
        mPath.moveTo(cx, cy - r);
        mPath.quadTo(cx, cy, cx + r, cy);
        mPath.quadTo(cx, cy, cx, cy + r);
        mPath.quadTo(cx, cy, cx - r, cy);
        mPath.quadTo(cx, cy, cx, cy - r);
        mPath.close();
        c.drawPath(mPath, mP);
        mP.setStyle(st);
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mP.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return -1;
    }
}
