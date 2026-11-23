package com.onesay.assistant.v2.ui.wake;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;

import com.onesay.assistant.v2.ui.console.ConsoleGlyph;

/**
 * ★ feat/console-ui（设计稿 ③「说话中」）：全屏聆听层。不是 View —— 由卡片窗（FloatingSheetView）在自己的
 * dispatchDraw 最上面画、在 dispatchTouchEvent 最前面接点按，所以不加窗口、不改窗口层级、不碰灵动岛。
 *
 * <p>画面：深色底 #0E0F12；中间蓝色圆球随音量变大变小（读 {@link VoiceLevel}，和光晕同一份音量）；球下面边说边出字；
 * 左上返回、右上键盘；底部三颗图标圆钮：重说（循环箭头）· 停止（红圆白 X）· 打字（键盘）。
 */
public final class ListenScreen {

    static final int BG = 0xFF0E0F12, CARD = 0xFF1A1C21, SUB = 0xFF9AA0A8, DIM = 0xFF6B7079, TEXT = 0xFFF2F3F5,
            BLUE = 0xFF0A5BFF, BLUE_HI = 0xFF4C86FF, BLUE_LO = 0xFF06368F, RED = 0xFFE5534B, PRESSED = 0x22FFFFFF;

    private final float mD;
    private final Paint mBg = new Paint();
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOrb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mR = new RectF();
    private final VoiceLevel.Envelope mEnv = new VoiceLevel.Envelope();
    private long mLastFrame;
    private StaticLayout mLayout;
    private String mLaidOut;
    private int mLaidW;
    private ListenScreenPolicy.Button mDown = ListenScreenPolicy.Button.NONE;
    private RadialGradient mShader;
    private float mShaderR;
    private float mShaderCy;

    public ListenScreen(float density) {
        mD = density;
        mBg.setColor(BG);
        mGlyph.setStrokeCap(Paint.Cap.ROUND);
        mGlyph.setStrokeJoin(Paint.Join.ROUND);
        mText.setColor(TEXT);
        mText.setTextSize(22f * density);
    }

    /** 按下的那颗钮（画按下态）。 */
    public void setPressed(ListenScreenPolicy.Button b) {
        mDown = b == null ? ListenScreenPolicy.Button.NONE : b;
    }

    public ListenScreenPolicy.Button pressed() {
        return mDown;
    }

    /** 重新出现时从安静开始（音量包络归零）。 */
    public void reset() {
        mEnv.value = 0f;
        mLastFrame = 0L;
        mDown = ListenScreenPolicy.Button.NONE;
    }

    /**
     * 画一帧。返回 true = 还在动（调用方 postInvalidateOnAnimation）。
     *
     * @param main 已经听到的字（白）；tail 后面淡色的部分（「…」或提示）
     * @param listening 还在听（圆球跟音量；出错时球变暗不动）
     */
    public boolean draw(Canvas c, int w, int h, int bottomInset, String main, String tail, boolean listening, boolean instant) {
        long now = android.os.SystemClock.uptimeMillis();
        float dt = mLastFrame == 0L ? 0.016f : Math.min(0.05f, (now - mLastFrame) / 1000f);
        mLastFrame = now;
        float target = listening && VoiceLevel.fresh(android.os.SystemClock.elapsedRealtime())
                ? VoiceLevel.norm(VoiceLevel.rms(), VoiceLevel.floor()) : 0f;
        float lvl = instant ? target : mEnv.step(target, dt);

        c.drawRect(0, 0, w, h, mBg);
        // 圆球 + 两圈淡光
        float cx = w / 2f, cy = ListenScreenPolicy.orbCenterY(h);
        float r = ListenScreenPolicy.orbRadius(mD, lvl);
        mRing.setStyle(Paint.Style.FILL);
        mRing.setColor(listening ? 0x0F0A5BFF : 0x08FFFFFF);
        c.drawCircle(cx, cy, r + (30f + 18f * lvl) * mD, mRing);
        mRing.setColor(listening ? 0x210A5BFF : 0x0CFFFFFF);
        c.drawCircle(cx, cy, r + (14f + 8f * lvl) * mD, mRing);
        if (mShader == null || Math.abs(mShaderR - r) > 0.5f || mShaderCy != cy) {
            mShader = new RadialGradient(cx - r * 0.2f, cy - r * 0.3f, r * 1.25f,
                    new int[]{BLUE_HI, BLUE, BLUE_LO}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            mShaderR = r;
            mShaderCy = cy;
        }
        mOrb.setShader(mShader);
        mOrb.setAlpha(listening ? 255 : 110);
        c.drawCircle(cx, cy, r, mOrb);

        // 字：主句白、尾巴淡（居中，最多 4 行，超出只留最后几行 = 跟着最新的字走）
        drawText(c, w, cy + ListenScreenPolicy.orbRadius(mD, 1f) + 40f * mD, main, tail);

        // 左上返回 / 右上键盘
        topButton(c, ListenScreenPolicy.Button.BACK, w, h, bottomInset, ConsoleGlyph.BACK);
        topButton(c, ListenScreenPolicy.Button.KEYBOARD, w, h, bottomInset, ConsoleGlyph.KEYBOARD);
        // 底部三颗：重说 · 停止 · 打字
        roundButton(c, ListenScreenPolicy.Button.REDO, w, h, bottomInset, CARD, SUB, ConsoleGlyph.REDO, 22f, 2f);
        roundButton(c, ListenScreenPolicy.Button.STOP, w, h, bottomInset, RED, 0xFFFFFFFF, ConsoleGlyph.CLOSE, 26f, 2.4f);
        roundButton(c, ListenScreenPolicy.Button.TYPE, w, h, bottomInset, CARD, SUB, ConsoleGlyph.KEYBOARD, 22f, 1.8f);
        return listening && !instant;
    }

    private void drawText(Canvas c, int w, float top, String main, String tail) {
        String m = main == null ? "" : main, t = tail == null ? "" : tail;
        String key = m + "\u0000" + t;
        int tw = Math.max(1, Math.round(w - 52f * mD));
        if (!key.equals(mLaidOut) || tw != mLaidW || mLayout == null) {
            SpannableStringBuilder sb = new SpannableStringBuilder(m);
            int s = sb.length();
            sb.append(t);
            if (t.length() > 0) sb.setSpan(new ForegroundColorSpan(DIM), s, sb.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            mLayout = new StaticLayout(sb, mText, tw, Layout.Alignment.ALIGN_CENTER, 1.15f, 0f, false);
            mLaidOut = key;
            mLaidW = tw;
        }
        int lines = mLayout.getLineCount();
        int first = Math.max(0, lines - 4);
        float skip = mLayout.getLineTop(first);
        c.save();
        c.translate((w - tw) / 2f, top - skip);
        c.clipRect(0, skip, tw, mLayout.getHeight());
        mLayout.draw(c);
        c.restore();
    }

    private void topButton(Canvas c, ListenScreenPolicy.Button b, int w, int h, int inset, int glyph) {
        float[] g = ListenScreenPolicy.button(b, w, h, mD, inset);
        mFill.setStyle(Paint.Style.FILL);
        mFill.setColor(CARD);
        c.drawCircle(g[0], g[1], g[2], mFill);
        if (mDown == b) {
            mFill.setColor(PRESSED);
            c.drawCircle(g[0], g[1], g[2], mFill);
        }
        glyph(c, g[0], g[1], 18f, glyph, SUB, 1.8f);
    }

    private void roundButton(Canvas c, ListenScreenPolicy.Button b, int w, int h, int inset, int fill, int fg, int glyph, float sizeDp, float stroke) {
        float[] g = ListenScreenPolicy.button(b, w, h, mD, inset);
        mFill.setStyle(Paint.Style.FILL);
        mFill.setColor(fill);
        c.drawCircle(g[0], g[1], g[2], mFill);
        if (mDown == b) {
            mFill.setColor(PRESSED);
            c.drawCircle(g[0], g[1], g[2], mFill);
        }
        glyph(c, g[0], g[1], sizeDp, glyph, fg, stroke);
    }

    private void glyph(Canvas c, float cx, float cy, float sizeDp, int kind, int color, float stroke) {
        float s = sizeDp * mD;
        c.save();
        c.translate(cx - s / 2f, cy - s / 2f);
        c.scale(s / 24f, s / 24f);
        mGlyph.setColor(color);
        mGlyph.setShader(null);
        ConsoleGlyph.draw24(c, kind, mGlyph, mPath, mR, stroke);
        c.restore();
    }

    /** 读屏用的说明（无障碍）。 */
    public static String describe(ListenScreenPolicy.Button b) {
        if (b == null) return "";
        switch (b) {
            case REDO: return "重说";
            case STOP: return "停止";
            case TYPE: return "打字";
            case BACK: return "返回";
            case KEYBOARD: return "打字";
            default: return "";
        }
    }
}
