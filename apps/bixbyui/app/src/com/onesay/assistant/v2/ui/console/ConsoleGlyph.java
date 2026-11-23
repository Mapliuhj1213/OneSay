package com.onesay.assistant.v2.ui.console;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * ★ feat/console-ui：对话控制台用的线条图标（设计稿：按钮图标一律是图形，不用汉字当图标）。
 * 24×24 网格里画，按 bounds 缩放；描边宽 1.8（实心麦克风例外）。全 App 新加的界面按钮都用它。
 */
public final class ConsoleGlyph extends Drawable {

    public static final int MENU = 1, GEAR = 2, PLUS = 3, MIC = 4, SEND = 5, KEYBOARD = 6, REDO = 7, CLOSE = 8, BACK = 9, CHEVRON = 10,
            /** ★ 发送：纸飞机（输入框有字时替换麦克风）。 */ PLANE = 11;

    private final int mKind;
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mR = new RectF();
    private float mStroke = 1.8f;

    public ConsoleGlyph(int kind, int color) {
        mKind = kind;
        mP.setColor(color);
        mP.setStyle(Paint.Style.STROKE);
        mP.setStrokeCap(Paint.Cap.ROUND);
        mP.setStrokeJoin(Paint.Join.ROUND);
    }

    public ConsoleGlyph stroke(float w) {
        mStroke = w;
        return this;
    }

    public int kind() {
        return mKind;
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        c.save();
        float k = Math.min(b.width(), b.height()) / 24f;
        c.translate(b.left + (b.width() - 24f * k) / 2f, b.top + (b.height() - 24f * k) / 2f);
        c.scale(k, k);
        draw24(c, mKind, mP, mPath, mR, mStroke);
        c.restore();
    }

    /** 在 24×24 坐标里画（全屏聆听在画布上直接用）。 */
    public static void draw24(Canvas c, int kind, Paint p, Path path, RectF r, float stroke) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        path.reset();
        switch (kind) {
            case MENU:
                c.drawLine(5, 7, 19, 7, p);
                c.drawLine(5, 12, 19, 12, p);
                c.drawLine(5, 17, 19, 17, p);
                break;
            case GEAR: {
                c.drawCircle(12, 12, 3.2f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                    c.drawLine(12 + 6.2f * cs, 12 + 6.2f * sn, 12 + 8.6f * cs, 12 + 8.6f * sn, p);
                }
                c.drawCircle(12, 12, 6.2f, p);
                break;
            }
            case PLUS:
                c.drawLine(12, 6, 12, 18, p);
                c.drawLine(6, 12, 18, 12, p);
                break;
            case MIC: {
                p.setStyle(Paint.Style.FILL);
                r.set(9, 3, 15, 14);
                c.drawRoundRect(r, 3, 3, p);
                p.setStyle(Paint.Style.STROKE);
                r.set(5.5f, 6, 18.5f, 17.5f);
                c.drawArc(r, 0, 180, false, p);
                c.drawLine(12, 17.5f, 12, 21, p);
                break;
            }
            case SEND:
                c.drawLine(12, 19, 12, 5.5f, p);
                path.moveTo(6.5f, 11);
                path.lineTo(12, 5.5f);
                path.lineTo(17.5f, 11);
                c.drawPath(path, p);
                break;
            case KEYBOARD: {
                r.set(3, 6, 21, 18);
                c.drawRoundRect(r, 2, 2, p);
                c.drawLine(7, 10, 8, 10, p);
                c.drawLine(11, 10, 12, 10, p);
                c.drawLine(15, 10, 16, 10, p);
                c.drawLine(8, 14, 16, 14, p);
                break;
            }
            case REDO: {
                // 循环箭头：一段 300° 的圆弧 + 起点处的箭头
                r.set(4, 4, 20, 20);
                c.drawArc(r, 200, 300, false, p);
                path.moveTo(4, 4);
                path.lineTo(4, 8.5f);
                path.lineTo(8.5f, 8.5f);
                c.drawPath(path, p);
                break;
            }
            case CLOSE:
                c.drawLine(6, 6, 18, 18, p);
                c.drawLine(18, 6, 6, 18, p);
                break;
            case BACK:
                path.moveTo(14.5f, 6);
                path.lineTo(8.5f, 12);
                path.lineTo(14.5f, 18);
                c.drawPath(path, p);
                break;
            case PLANE:
                // 纸飞机：机身三角（尖朝右上）+ 中间一道折痕
                path.moveTo(3f, 11f);
                path.lineTo(20.5f, 3.5f);
                path.lineTo(13.5f, 20.5f);
                path.lineTo(10.8f, 13.2f);
                path.close();
                c.drawPath(path, p);
                c.drawLine(10.8f, 13.2f, 20.5f, 3.5f, p);
                break;
            case CHEVRON:
                path.moveTo(9.5f, 6);
                path.lineTo(15.5f, 12);
                path.lineTo(9.5f, 18);
                c.drawPath(path, p);
                break;
            default:
                break;
        }
    }

    @Override public void setAlpha(int a) {
        mP.setAlpha(a);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter cf) {
        mP.setColorFilter(cf);
        invalidateSelf();
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
