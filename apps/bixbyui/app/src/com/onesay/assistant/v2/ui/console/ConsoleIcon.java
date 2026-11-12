package com.onesay.assistant.v2.ui.console;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * [console R12] 首页 / 大卡用的线性图标（Canvas 画，不加资源文件；示意图 01 的 7 个图标）。
 * 图标画在 36dp 的圆底里，线宽 1.8dp，颜色 = 主色。
 */
public final class ConsoleIcon extends View {

    public static final int NOTE = 1, MEMORY = 2, SLIDERS = 3, ISLAND = 4, MIC = 5, GEAR = 6, INFO = 7, STATUS = 8, PLAN = 9, BACK = 20,
            /* 控制台整理第二步 */ SPEAKER = 10, BULB = 11, SHIELD = 12, LOCK = 13, CODE = 14,
            /* ★ console-align */ LINK = 15, HAND = 16, DOWNLOAD = 17, PUZZLE = 18,
            CHEVRON = 21;

    private final int mKind;
    private final Paint mBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBlack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mD;
    private final RectF mR = new RectF();
    private final Path mPath = new Path();

    public ConsoleIcon(Context ctx, int kind, int fg, int bg) {
        super(ctx);
        mKind = kind;
        mD = ctx.getResources().getDisplayMetrics().density;
        mBg.setColor(bg);
        mFg.setColor(fg);
        mFg.setStyle(Paint.Style.STROKE);
        mFg.setStrokeWidth(1.8f * mD);
        mFg.setStrokeCap(Paint.Cap.ROUND);
        mFg.setStrokeJoin(Paint.Join.ROUND);
        mFill.setColor(fg);
        mBlack.setColor(0xFF000000);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f, d = mD;
        if (mKind == BACK || mKind == CHEVRON) {
            mFg.setStrokeWidth((mKind == BACK ? 2.2f : 2f) * d);
            float s = (mKind == BACK ? 7f : 5f) * d;
            mPath.reset();
            if (mKind == BACK) {
                mPath.moveTo(cx + s * 0.5f, cy - s);
                mPath.lineTo(cx - s * 0.5f, cy);
                mPath.lineTo(cx + s * 0.5f, cy + s);
            } else {
                mPath.moveTo(cx - s * 0.5f, cy - s);
                mPath.lineTo(cx + s * 0.5f, cy);
                mPath.lineTo(cx - s * 0.5f, cy + s);
            }
            c.drawPath(mPath, mFg);
            return;
        }
        c.drawCircle(cx, cy, Math.min(w, h) / 2f, mBg);
        mFg.setStrokeWidth(1.8f * d);
        switch (mKind) {
            case NOTE: {
                mR.set(cx - 7 * d, cy - 9 * d, cx + 7 * d, cy + 9 * d);
                c.drawRoundRect(mR, 3 * d, 3 * d, mFg);
                c.drawLine(cx - 3.5f * d, cy - 3 * d, cx + 3.5f * d, cy - 3 * d, mFg);
                c.drawLine(cx - 3.5f * d, cy + 1.5f * d, cx + 3.5f * d, cy + 1.5f * d, mFg);
                break;
            }
            case MEMORY:
                c.drawCircle(cx, cy, 9 * d, mFg);
                c.drawCircle(cx, cy, 4 * d, mFill);
                break;
            case SLIDERS:
                for (int i = -1; i <= 1; i++) {
                    float y = cy + i * 6 * d;
                    c.drawLine(cx - 10 * d, y, cx + 10 * d, y, mFg);
                    float kx = cx + (i == 0 ? 4 : -4) * d;
                    c.drawCircle(kx, y, 2.6f * d, mFill);
                }
                break;
            case ISLAND:
                mR.set(cx - 11 * d, cy - 4.5f * d, cx + 11 * d, cy + 4.5f * d);
                c.drawRoundRect(mR, 4.5f * d, 4.5f * d, mBlack);
                c.drawRoundRect(mR, 4.5f * d, 4.5f * d, mFg);
                break;
            case MIC:
                mR.set(cx - 4 * d, cy - 10 * d, cx + 4 * d, cy + 3 * d);
                c.drawRoundRect(mR, 4 * d, 4 * d, mFg);
                mR.set(cx - 8 * d, cy - 6 * d, cx + 8 * d, cy + 7 * d);
                c.drawArc(mR, 20, 140, false, mFg);
                c.drawLine(cx, cy + 7 * d, cx, cy + 11 * d, mFg);
                break;
            case GEAR: {
                c.drawCircle(cx, cy, 6.5f * d, mFg);
                c.drawCircle(cx, cy, 2.5f * d, mFg);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    float x1 = cx + (float) Math.cos(a) * 6.5f * d, y1 = cy + (float) Math.sin(a) * 6.5f * d;
                    float x2 = cx + (float) Math.cos(a) * 10f * d, y2 = cy + (float) Math.sin(a) * 10f * d;
                    c.drawLine(x1, y1, x2, y2, mFg);
                }
                break;
            }
            case INFO:
                c.drawCircle(cx, cy - 6 * d, 1.6f * d, mFill);
                mFg.setStrokeWidth(2.4f * d);
                c.drawLine(cx, cy - 1.5f * d, cx, cy + 8 * d, mFg);
                break;
            case STATUS:
                mPath.reset();
                mPath.moveTo(cx - 10 * d, cy);
                mPath.lineTo(cx - 5 * d, cy);
                mPath.lineTo(cx - 2 * d, cy - 7 * d);
                mPath.lineTo(cx + 2 * d, cy + 7 * d);
                mPath.lineTo(cx + 5 * d, cy);
                mPath.lineTo(cx + 10 * d, cy);
                c.drawPath(mPath, mFg);
                break;
            case PLAN: {   // 日历：方框 + 顶栏线 + 两个挂环
                mR.set(cx - 9 * d, cy - 7 * d, cx + 9 * d, cy + 9 * d);
                c.drawRoundRect(mR, 3 * d, 3 * d, mFg);
                c.drawLine(cx - 9 * d, cy - 2 * d, cx + 9 * d, cy - 2 * d, mFg);
                c.drawLine(cx - 4.5f * d, cy - 10 * d, cx - 4.5f * d, cy - 5 * d, mFg);
                c.drawLine(cx + 4.5f * d, cy - 10 * d, cx + 4.5f * d, cy - 5 * d, mFg);
                c.drawCircle(cx - 3 * d, cy + 3.5f * d, 1.6f * d, mFill);
                break;
            }
            case SPEAKER: {   // 喇叭 + 两道声波
                mPath.reset();
                mPath.moveTo(cx - 9 * d, cy - 3.5f * d);
                mPath.lineTo(cx - 5 * d, cy - 3.5f * d);
                mPath.lineTo(cx, cy - 8 * d);
                mPath.lineTo(cx, cy + 8 * d);
                mPath.lineTo(cx - 5 * d, cy + 3.5f * d);
                mPath.lineTo(cx - 9 * d, cy + 3.5f * d);
                mPath.close();
                c.drawPath(mPath, mFg);
                mR.set(cx - 2 * d, cy - 5 * d, cx + 6 * d, cy + 5 * d);
                c.drawArc(mR, -50, 100, false, mFg);
                mR.set(cx - 2 * d, cy - 9 * d, cx + 10 * d, cy + 9 * d);
                c.drawArc(mR, -50, 100, false, mFg);
                break;
            }
            case BULB:   // 灯泡
                c.drawCircle(cx, cy - 2.5f * d, 6.5f * d, mFg);
                c.drawLine(cx - 3 * d, cy + 6.5f * d, cx + 3 * d, cy + 6.5f * d, mFg);
                c.drawLine(cx - 2 * d, cy + 9.5f * d, cx + 2 * d, cy + 9.5f * d, mFg);
                break;
            case SHIELD: {   // 盾牌 + 勾
                mPath.reset();
                mPath.moveTo(cx, cy - 10 * d);
                mPath.lineTo(cx + 8 * d, cy - 6.5f * d);
                mPath.lineTo(cx + 8 * d, cy + 1 * d);
                mPath.quadTo(cx + 7 * d, cy + 7.5f * d, cx, cy + 10 * d);
                mPath.quadTo(cx - 7 * d, cy + 7.5f * d, cx - 8 * d, cy + 1 * d);
                mPath.lineTo(cx - 8 * d, cy - 6.5f * d);
                mPath.close();
                c.drawPath(mPath, mFg);
                c.drawLine(cx - 3.5f * d, cy, cx - 1 * d, cy + 3 * d, mFg);
                c.drawLine(cx - 1 * d, cy + 3 * d, cx + 4 * d, cy - 3 * d, mFg);
                break;
            }
            case LOCK:   // 锁
                mR.set(cx - 7.5f * d, cy - 2 * d, cx + 7.5f * d, cy + 9 * d);
                c.drawRoundRect(mR, 2.5f * d, 2.5f * d, mFg);
                mR.set(cx - 4.5f * d, cy - 10 * d, cx + 4.5f * d, cy + 2 * d);
                c.drawArc(mR, 180, 180, false, mFg);
                c.drawCircle(cx, cy + 3.5f * d, 1.5f * d, mFill);
                break;
            case CODE:   // </>
                c.drawLine(cx - 4 * d, cy - 6 * d, cx - 10 * d, cy, mFg);
                c.drawLine(cx - 10 * d, cy, cx - 4 * d, cy + 6 * d, mFg);
                c.drawLine(cx + 4 * d, cy - 6 * d, cx + 10 * d, cy, mFg);
                c.drawLine(cx + 10 * d, cy, cx + 4 * d, cy + 6 * d, mFg);
                c.drawLine(cx + 2 * d, cy - 8 * d, cx - 2 * d, cy + 8 * d, mFg);
                break;
            case LINK: {   // ★ console-align：连接（两台设备 + 双向箭头）
                mR.set(cx - 10 * d, cy - 7 * d, cx - 3 * d, cy + 7 * d);
                c.drawRoundRect(mR, 2 * d, 2 * d, mFg);
                mR.set(cx + 2 * d, cy - 5 * d, cx + 10 * d, cy + 5 * d);
                c.drawRoundRect(mR, 1.5f * d, 1.5f * d, mFg);
                c.drawLine(cx - 1.5f * d, cy, cx + 0.5f * d, cy, mFg);
                break;
            }
            case HAND: {   // ★ console-align：替你操作（指针箭头）
                mPath.reset();
                mPath.moveTo(cx - 6 * d, cy - 9 * d);
                mPath.lineTo(cx + 7 * d, cy + 1 * d);
                mPath.lineTo(cx + 1 * d, cy + 2 * d);
                mPath.lineTo(cx + 4 * d, cy + 9 * d);
                mPath.lineTo(cx + 1 * d, cy + 10 * d);
                mPath.lineTo(cx - 2 * d, cy + 3 * d);
                mPath.lineTo(cx - 6 * d, cy + 7 * d);
                mPath.close();
                c.drawPath(mPath, mFg);
                break;
            }
            case DOWNLOAD:   // ★ console-align：下载（向下箭头 + 托盘）
                c.drawLine(cx, cy - 9 * d, cx, cy + 4 * d, mFg);
                c.drawLine(cx - 5 * d, cy - 1 * d, cx, cy + 4 * d, mFg);
                c.drawLine(cx + 5 * d, cy - 1 * d, cx, cy + 4 * d, mFg);
                c.drawLine(cx - 9 * d, cy + 9 * d, cx + 9 * d, cy + 9 * d, mFg);
                break;
            case PUZZLE: {   // ★ console-align：插件（方块 + 凸起）
                mR.set(cx - 8 * d, cy - 5 * d, cx + 6 * d, cy + 9 * d);
                c.drawRoundRect(mR, 2 * d, 2 * d, mFg);
                c.drawCircle(cx - 1 * d, cy - 7.5f * d, 2.5f * d, mFg);
                c.drawCircle(cx + 8.5f * d, cy + 2 * d, 2.5f * d, mFg);
                break;
            }
            default:
                break;
        }
    }
}
