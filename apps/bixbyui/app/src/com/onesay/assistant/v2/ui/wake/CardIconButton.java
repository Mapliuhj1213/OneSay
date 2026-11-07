package com.onesay.assistant.v2.ui.wake;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * ★ R14（diag D 方案 A）：唤醒卡片右下角的 32dp 圆形图标钮 —— ⌨ 文字输入 / ◎ 识屏。
 *
 * <p>自绘矢量字形（不依赖系统字体里有没有 ⌨ U+2328），配色与关闭钮同一套：深色卡片上白 8.5% 圆底 + T2 灰字形，
 * 按下时圆底换成苔绿（{@link WakeCardPainter#MOSS}）淡色、字形变苔绿。带 contentDescription（无障碍朗读「文字输入」「识屏」）。
 */
public final class CardIconButton extends View {

    public static final int KIND_KEYBOARD = 1;
    public static final int KIND_SCREEN = 2;
    /** ★ 综合包：🎤 续说（{@link MicButtonPolicy}）。 */
    public static final int KIND_MIC = 3;
    /** ★ 11-04 相机对话（Gemini Live 式实时取景 + 语音对话，{@link com.onesay.assistant.v2.ui.live.CameraLiveActivity}）。 */
    public static final int KIND_CAMERA = 4;

    /** 与卡片关闭钮同色（FloatingSheetView.T2 / mCloseBg）。 */
    static final int BG = 0x16FFFFFF;
    static final int GLYPH = 0xFFB2AFAA;
    static final int BG_PRESSED = (WakeCardPainter.MOSS & 0x00FFFFFF) | 0x33000000;
    private boolean mActive;

    private final int mKind;
    private final float mDp;
    private final Paint mBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mR = new RectF();
    private boolean mDown;

    public CardIconButton(Context c, int kind) {
        super(c);
        mKind = kind;
        mDp = c.getResources().getDisplayMetrics().density;
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(1.5f * mDp);
        mStroke.setStrokeCap(Paint.Cap.ROUND);
        mStroke.setStrokeJoin(Paint.Join.ROUND);
        mFill.setStyle(Paint.Style.FILL);
        setClickable(true);
        setFocusable(false);
        setContentDescription(descriptionFor(kind));
    }

    /** 无障碍朗读名（纯函数，JVM 单测）。 */
    public static String descriptionFor(int kind) {
        switch (kind) {
            case KIND_KEYBOARD: return "文字输入";
            case KIND_MIC: return MicButtonPolicy.description(false);
            case KIND_CAMERA: return "相机对话";
            default: return "识屏";
        }
    }

    public int kind() {
        return mKind;
    }

    /** 🎤 收音中（苔绿实底，再点停止）。 */
    public void setActive(boolean on) {
        if (mActive == on) return;
        mActive = on;
        if (mKind == KIND_MIC) setContentDescription(MicButtonPolicy.description(on));
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            mDown = true;
            invalidate();
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (mDown) {
                mDown = false;
                invalidate();
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f;
        boolean act = mActive && mKind == KIND_MIC;
        mBg.setColor(act ? (mDown ? (MicButtonPolicy.ACTIVE_BG & 0x00FFFFFF) | 0xAA000000 : MicButtonPolicy.ACTIVE_BG) : mDown ? BG_PRESSED : BG);
        c.drawCircle(cx, cy, r, mBg);
        int g = act ? MicButtonPolicy.ACTIVE_GLYPH : mDown ? WakeCardPainter.MOSS : GLYPH;
        mStroke.setColor(g);
        mFill.setColor(g);
        float d = mDp;
        if (mKind == KIND_MIC) {
            // 麦克风：胶囊 + 托架弧 + 立杆 + 底座
            float mw = 6.4f * d, mh = 10.5f * d, top = cy - 8.2f * d;
            mR.set(cx - mw / 2f, top, cx + mw / 2f, top + mh);
            c.drawRoundRect(mR, mw / 2f, mw / 2f, mStroke);
            mR.set(cx - 6.2f * d, top + 3.0f * d, cx + 6.2f * d, top + mh + 3.6f * d);
            c.drawArc(mR, 15f, 150f, false, mStroke);
            c.drawLine(cx, top + mh + 3.6f * d, cx, cy + 7.8f * d, mStroke);
            c.drawLine(cx - 3.4f * d, cy + 7.8f * d, cx + 3.4f * d, cy + 7.8f * d, mStroke);
        } else if (mKind == KIND_CAMERA) {
            // 相机：圆角机身 + 顶部取景凸台 + 镜头圆
            float bw = 15.5f * d, bh = 11f * d, top = cy - bh / 2f + 1.2f * d;
            mR.set(cx - bw / 2f, top, cx + bw / 2f, top + bh);
            c.drawRoundRect(mR, 2.6f * d, 2.6f * d, mStroke);
            c.drawLine(cx - 3.6f * d, top, cx - 2.2f * d, top - 2.2f * d, mStroke);
            c.drawLine(cx - 2.2f * d, top - 2.2f * d, cx + 2.2f * d, top - 2.2f * d, mStroke);
            c.drawLine(cx + 2.2f * d, top - 2.2f * d, cx + 3.6f * d, top, mStroke);
            c.drawCircle(cx, top + bh / 2f, 3.1f * d, mStroke);
        } else if (mKind == KIND_KEYBOARD) {
            // 键盘：圆角矩形外框 + 两排键点 + 空格条
            float kw = 15f * d, kh = 10.5f * d;
            mR.set(cx - kw / 2f, cy - kh / 2f, cx + kw / 2f, cy + kh / 2f);
            c.drawRoundRect(mR, 2.2f * d, 2.2f * d, mStroke);
            float dot = 0.95f * d;
            float x0 = cx - kw / 2f + 3.2f * d, step = (kw - 6.4f * d) / 3f;
            for (int i = 0; i < 4; i++) c.drawCircle(x0 + i * step, cy - 1.9f * d, dot, mFill);
            for (int i = 0; i < 4; i++) c.drawCircle(x0 + i * step, cy + 0.6f * d, dot, mFill);
            c.drawLine(cx - 3.2f * d, cy + 3.0f * d, cx + 3.2f * d, cy + 3.0f * d, mStroke);
        } else {
            // 识屏：取景框四角 + 中心实心点（◎ 的意思，但更像「看屏幕」）
            float s = 7.2f * d, k = 3.0f * d;
            c.drawLine(cx - s, cy - s + k, cx - s, cy - s, mStroke);
            c.drawLine(cx - s, cy - s, cx - s + k, cy - s, mStroke);
            c.drawLine(cx + s - k, cy - s, cx + s, cy - s, mStroke);
            c.drawLine(cx + s, cy - s, cx + s, cy - s + k, mStroke);
            c.drawLine(cx - s, cy + s - k, cx - s, cy + s, mStroke);
            c.drawLine(cx - s, cy + s, cx - s + k, cy + s, mStroke);
            c.drawLine(cx + s - k, cy + s, cx + s, cy + s, mStroke);
            c.drawLine(cx + s, cy + s, cx + s, cy + s - k, mStroke);
            c.drawCircle(cx, cy, 3.4f * d, mStroke);
            c.drawCircle(cx, cy, 1.2f * d, mFill);
        }
    }
}
