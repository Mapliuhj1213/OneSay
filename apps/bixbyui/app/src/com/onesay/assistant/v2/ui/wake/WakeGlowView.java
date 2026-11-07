package com.onesay.assistant.v2.ui.wake;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import com.onesay.assistant.v2.island.IslandGeometry;
import com.onesay.assistant.v2.ui.motion.MotionSpec;

/**
 * [R13 唤醒界面 v2] 全屏不可触摸的光晕窗（替换 WakeRippleView 的位置与 flag），三件事：
 * <ol>
 *   <li><b>U 形 bloom</b>（v2.4）：屏幕可见区连续圆角框向内羽化，三层 72 / 34 / 12dp，底部最亮、沿两侧向上淡出（竖向权重 1.6 次方，
 *       屏高 40% 处到 0）。三层各一张 <b>1/4 分辨率 ALPHA_8</b> 位图（首次显示时烘焙），绘制时双线性放大；上色用
 *       {@code ComposeShader(LinearGradient(调色板), BitmapShader(遮罩), DST_IN)}（不同类型 Shader 组合，API 26 硬件支持）。
 *       色相漂移只平移渐变的局部矩阵（静默 1 圈 / 14 s，说话最快 1 圈 / 7 s）；RMS → 包络 → GLOW_LEVEL 弹簧 → 只改三层 alpha。</li>
 *   <li><b>底部局部压暗</b>（D3′）：聆听 30%（从屏高 45% 渐入）、作答 50%（从 25% 渐入），顶部 0。</li>
 *   <li><b>形变舞台</b>：卡 → 岛黏连形变 / 岛 → 卡流回（{@link MorphGeometry}），卡片窗在舞台画出第一帧之后才摘。</li>
 * </ol>
 * onDraw 零分配：Paint / Shader / Matrix / Path / 位图在烘焙或 start 时建好。
 */
public final class WakeGlowView extends View {

    private static final String TAG = "WakeUI";

    public interface StageListener {
        /** 舞台已画出第一帧（下一个 vsync）：现在可以摘卡片窗了。 */
        void onStageFirstFrame();

        /** 去程接触：合并形状（屏幕坐标）+ 接力速度。 */
        void onContact(RectF merged, float vw, float vh);

        /** 回程落定（卡片窗可以显示了）。 */
        void onUnmorphSettled();
    }

    private final float d;
    private int mW, mH, mVisTop, mVisBottom;
    private final int[] mLoc = new int[2];

    // ---- bloom ----
    private static final float[] LAYER_DP = {72f, 34f, 12f};
    private static final float[] LAYER_OPACITY = {0.85f, 0.75f, 0.55f};
    private static final int Q = 4;   // 1/4 分辨率
    private float[] mLut;
    private final Bitmap[] mMask = new Bitmap[3];
    private final BitmapShader[] mMaskShader = new BitmapShader[3];
    private final ComposeShader[] mCompose = new ComposeShader[3];
    private final Paint[] mLayer = new Paint[3];
    private final Matrix mMaskM = new Matrix();
    private byte[] mPx;
    /** bloom 只在离边 3.2σ 的带里有值：分左 / 右 / 底三条带画，屏幕中间那一大块不着色。 */
    private float mBand;
    private final Runnable mInvalidateNext = new Runnable() {
        @Override
        public void run() {
            postInvalidateOnAnimation();
        }
    };
    private LinearGradient mPalette;
    private final Matrix mPalM = new Matrix();
    private float mBloomTop;   // 位图覆盖区上沿（view 坐标）
    private boolean mBakedTint;
    private final Paint mFlat = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** 调色（D2=A）：苔绿 → 暖琥珀 → 青 → 苔绿。 */
    static final int[] PALETTE = {0xFF8CD27A, 0xFFFFBA62, 0xFF62D2CE, 0xFF8CD27A};

    private final IslandGeometry.Spring mGlowA = MotionSpec.GLOW_FADE.spring(0f);
    private final IslandGeometry.Spring mRise = MotionSpec.GLOW_INHALE.spring(1f);
    private final IslandGeometry.Spring mLevel = MotionSpec.GLOW_LEVEL.spring(0f);
    private final VoiceLevel.Envelope mEnv = new VoiceLevel.Envelope();
    private float mPhase;
    private long mLastLoudAt;
    private long mLast;

    // ---- 压暗 ----
    private final IslandGeometry.Spring mDim = MotionSpec.GLOW_FADE.spring(0f);
    private float mDimStart = 0.45f, mDimShaderStart = -1f;
    private LinearGradient mDimShader;
    private final Paint mDimPaint = new Paint();

    // ---- 舞台 ----
    private MorphGeometry mMorph;
    private Bitmap mSnap;
    private BitmapShader mSnapShader;
    private final Matrix mSnapM = new Matrix();
    private final RectF mSnapFrom = new RectF();
    private final Paint mStageFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStageSnap = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path mNeck = new Path();
    private final float[] mR4 = new float[4];
    private final RectF mRect = new RectF();
    private StageListener mStageListener;
    private boolean mFirstFramePending;
    private float mIslandCx, mIslandTop, mIslandSeed, mIslandWinBottom;
    private boolean mInstant;
    /** 可见区下沿（屏幕 y）：卡片窗的下沿 = 导航栏上沿（导航栏隐藏时 = 屏幕底）。0 = 自己估。 */
    private int mVisBottomOverride;
    // 舞台计时（验收：卡片在按键 / 收起后 ≤2 帧开始动；形变全程掉帧数）
    private long mStageStartAt, mStageLastFrameAt;
    private int mStageFrames, mStageSlowFrames;
    private long mStageMaxGap;

    public WakeGlowView(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        for (int i = 0; i < 3; i++) {
            mLayer[i] = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        }
        mStageFill.setColor(0xFF050506);
        mDimPaint.setDither(true);
        setWillNotDraw(false);
    }

    /** 卡片窗量出来的可见区下沿（屏幕坐标）；变了就重烘 bloom（一次 ~15 ms，只在导航栏显隐切换时）。 */
    public void setVisibleBottom(int screenY) {
        if (screenY <= 0 || Math.abs(screenY - mVisBottomOverride) < 4) return;
        mVisBottomOverride = screenY;
        mW = 0;
        if (getParent() != null) {
            ensureGeometry();
            invalidate();
        }
    }

    public void setInstant(boolean instant) {
        mInstant = instant;
    }

    public void setBakedTint(boolean baked) {
        mBakedTint = baked;
    }

    // =====================================================================
    // 外部接口
    // =====================================================================

    /** 光点亮 / 熄灭（GLOW_FADE），压暗目标（D3′）。 */
    public void setGlow(boolean on) {
        mGlowA.t = on ? 1f : 0f;
        if (on) {
            mRise.t = 1f;
            if (mGlowA.v < 0.02f) mRise.v = 1f;
            mLastLoudAt = SystemClock.uptimeMillis();
        }
        if (mInstant) {
            mGlowA.snap();
            mRise.snap();
        }
        kick();
    }

    /** 光「吸」进卡片（GLOW_INHALE：竖向范围收拢到 0 + 淡出）。 */
    public void inhale() {
        mRise.t = 0f;
        mGlowA.t = 0f;
        if (mInstant) {
            mRise.snap();
            mGlowA.snap();
        }
        kick();
    }

    public void setDim(float level, float startFrac) {
        mDim.t = Math.max(0f, Math.min(0.8f, level));
        mDimStart = startFrac;
        if (mInstant) mDim.snap();
        kick();
    }

    /** 当前漂移色（卡片内发光 / 外晕跟着同一相位走）。 */
    public int currentColor() {
        float p = mPhase - (float) Math.floor(mPhase);
        float seg = p * 3f;
        int i = Math.min(2, (int) seg);
        return lerpColor(PALETTE[i], PALETTE[i + 1], seg - i);
    }

    public boolean idle() {
        return mGlowA.v < 0.004f && mGlowA.t == 0f && mDim.v < 0.004f && mDim.t == 0f && mMorph == null;
    }

    public void setStageListener(StageListener l) {
        mStageListener = l;
    }

    public boolean stageActive() {
        return mMorph != null;
    }

    /**
     * 去程：快照位图 + 卡片屏幕矩形 → 岛。
     *
     * @param island {cx, top, seed, windowBottom, compactW}（{@link com.onesay.assistant.v2.s1.S1Hooks#islandMorphTarget()}）
     */
    public void startMorph(Bitmap snapshot, RectF cardOnScreen, float cardR, float[] island) {
        releaseStage();
        ensureGeometry();
        mIslandCx = island[0];
        mIslandTop = island[1];
        mIslandSeed = island[2];
        mIslandWinBottom = island[3];
        mMorph = MorphGeometry.toIsland(d, toView(cardOnScreen), cardR, mIslandCx - mLoc[0], mIslandTop - mLoc[1],
                mIslandSeed, island[4]);
        setSnapshot(snapshot, cardOnScreen);
        mFirstFramePending = true;
        resetStageClock();
        Log.i(TAG, "[WAKE-MORPH] 去程开始：卡片 " + cardOnScreen.toShortString() + " → 岛 cx=" + Math.round(mIslandCx) + " top="
                + Math.round(mIslandTop));
        kick();
    }

    /** 回程：岛形状 {l,t,r,b,radius}（屏幕）→ 卡片矩形（屏幕）。snapshot = 卡片内容（60% 后淡入），可空。 */
    public void startUnmorph(float[] islandShape, RectF cardOnScreen, float cardR, Bitmap snapshot) {
        releaseStage();
        ensureGeometry();
        float[] from = toView(new RectF(islandShape[0], islandShape[1], islandShape[2], islandShape[3]));
        mMorph = MorphGeometry.fromIsland(d, from, islandShape[4], toView(cardOnScreen), cardR);
        setSnapshot(snapshot, cardOnScreen);
        mFirstFramePending = true;
        resetStageClock();
        Log.i(TAG, "[WAKE-MORPH] 回程开始：岛 → 卡片 " + cardOnScreen.toShortString());
        kick();
    }

    private void resetStageClock() {
        mStageStartAt = SystemClock.uptimeMillis();
        mStageLastFrameAt = 0L;
        mStageFrames = mStageSlowFrames = 0;
        mStageMaxGap = 0L;
    }

    private String stageClock() {
        return "帧=" + mStageFrames + " 慢帧(>20ms)=" + mStageSlowFrames + " 最长间隔=" + mStageMaxGap + "ms 墙钟="
                + (SystemClock.uptimeMillis() - mStageStartAt) + "ms";
    }

    public void releaseStage() {
        mMorph = null;
        mSnapShader = null;
        mStageSnap.setShader(null);
        if (mSnap != null && !mSnap.isRecycled()) mSnap.recycle();
        mSnap = null;
        mFirstFramePending = false;
    }

    private void setSnapshot(Bitmap b, RectF onScreen) {
        mSnap = b;
        if (b != null) {
            mSnapShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float[] v = toView(onScreen);
            mSnapFrom.set(v[0], v[1], v[2], v[3]);
        }
    }

    private float[] toView(RectF screen) {
        return new float[] {screen.left - mLoc[0], screen.top - mLoc[1], screen.right - mLoc[0], screen.bottom - mLoc[1]};
    }

    private void kick() {
        mLast = 0L;
        postInvalidateOnAnimation();
    }

    // =====================================================================
    // 几何 + 烘焙
    // =====================================================================

    private void ensureGeometry() {
        try {
            getLocationOnScreen(mLoc);
        } catch (Throwable ignored) {
        }
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            Point p = new Point();
            try {
                ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(p);
            } catch (Throwable ignored) {
            }
            w = p.x;
            h = p.y;
        }
        if (w == mW && h == mH && mMask[0] != null) return;
        mW = w;
        mH = h;
        int sb = 0, appH = h;
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) sb = getResources().getDimensionPixelSize(id);
            Point p = new Point();
            ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getSize(p);
            if (p.y > 0 && p.y <= h) appH = p.y;   // 竖屏：导航栏在底部，getSize 不含它
        } catch (Throwable ignored) {
        }
        // 三星可隐藏导航栏：getSize 仍按「有导航栏」报（真机首版 bloom 在离底 126px 处截断成一条横线）→ 优先信卡片窗量出来的下沿
        if (mVisBottomOverride > 0 && mVisBottomOverride <= h + mLoc[1]) appH = mVisBottomOverride;
        mVisTop = sb - mLoc[1];
        mVisBottom = appH - mLoc[1];
        bakeBloom();
    }

    /** 三层 U 形遮罩（1/4 分辨率 ALPHA_8），只覆盖可见区下 60%（上面权重为 0）。 */
    private void bakeBloom() {
        long t0 = SystemClock.uptimeMillis();
        float visH = Math.max(1f, mVisBottom - mVisTop);
        mBloomTop = mVisTop + 0.40f * visH;
        int qw = Math.max(2, mW / Q), qh = Math.max(2, (int) Math.ceil((mVisBottom - mBloomTop) / Q));
        float corner = 36f * d;   // Note 8 屏幕圆角约 36dp（方案推断，P3 真机看）
        if (mPx == null || mPx.length != qw * qh) mPx = new byte[qw * qh];
        byte[] px = mPx;
        // 三层（72 / 34 / 12dp，0.85 / 0.75 / 0.55）在烘焙时按「滤色」合成一张：真机上三张全宽 ComposeShader 叠画把 RenderThread
        //   拖到每帧 10–20 ms（UI 线程卡在 sync 上），合成一张后声音只调这一张的亮度与竖向范围
        float[] nominal = {0.775f, 0.85f, 0.90f};   // = 各层 alpha 在 e = 0.5 时的值
        // R13 卡顿排查：真机上这一步在主线程（布局回调里）跑 50–200 ms（每像素 3 次 Math.exp）。改成按内侧距离查表（0.5 px 一格，
        //   线性插值；bloom 是低频信号，结果与逐像素 exp 差 < 1/255）+ 超过最宽层 3.6σ 直接记 0（本来就会舍入成 0）+ 左右对称只算一半
        float sigma0 = LAYER_DP[0] * d * 0.5f;
        float cutoff = 3.6f * sigma0;
        int nLut = (int) Math.ceil(cutoff * 2f) + 2;
        if (mLut == null || mLut.length != nLut * 3) mLut = new float[nLut * 3];
        float[] lut = mLut;
        for (int k = 0; k < nLut; k++) {
            float dd = k * 0.5f;
            for (int i = 0; i < 3; i++) {
                float sigma = LAYER_DP[i] * d * 0.5f;
                lut[k * 3 + i] = (float) Math.exp(-(dd * dd) / (2f * sigma * sigma)) * LAYER_OPACITY[i] * nominal[i];
            }
        }
        boolean mirror = qw * Q == mW;
        int xEnd = mirror ? (qw + 1) / 2 : qw;
        for (int y = 0; y < qh; y++) {
            float sy = mBloomTop + (y + 0.5f) * Q;
            float wy = (float) Math.pow(Math.max(0f, Math.min(1f, (sy - mBloomTop) / Math.max(1f, mVisBottom - mBloomTop))), 1.6);
            int row = y * qw;
            for (int x = 0; x < xEnd; x++) {
                float sx = (x + 0.5f) * Q;
                float dist = insideDistance(sx, sy, 0f, mVisTop, mW, mVisBottom, corner);
                byte v = 0;
                if (dist < cutoff && wy > 0f) {
                    float f = dist * 2f;
                    int k = (int) f;
                    float fr = f - k;
                    if (k >= nLut - 1) { k = nLut - 2; fr = 1f; }
                    float keep = 1f;
                    for (int i = 0; i < 3; i++) {
                        float g = lut[k * 3 + i] + (lut[(k + 1) * 3 + i] - lut[k * 3 + i]) * fr;
                        float a = g * wy;
                        keep *= 1f - (a < 0f ? 0f : (a > 1f ? 1f : a));
                    }
                    v = (byte) Math.round(255f * (1f - keep));
                }
                px[row + x] = v;
                if (mirror) px[row + qw - 1 - x] = v;
            }
        }
        mBand = 3.2f * LAYER_DP[0] * d * 0.5f;
        for (int i = 0; i < 1; i++) {
            Bitmap b = Bitmap.createBitmap(qw, qh, Bitmap.Config.ALPHA_8);
            b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px));
            // 转成不可变位图：API 26 的 BitmapShader 每次换局部矩阵都会重建原生 Shader，可变位图会被整张拷贝一次
            Bitmap imm = Bitmap.createBitmap(b);
            if (imm != b) b.recycle();
            if (mMask[i] != null && mMask[i] != imm) mMask[i].recycle();
            b = imm;
            mMask[i] = b;
            mMaskShader[i] = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        float period = mW * 1.6f;
        mPalette = new LinearGradient(0, 0, period, 0, PALETTE, null, Shader.TileMode.REPEAT);
        mCompose[0] = new ComposeShader(mPalette, mMaskShader[0], PorterDuff.Mode.DST_IN);
        mMaskM.setScale(Q, Q);
        mMaskM.postTranslate(0, mBloomTop);
        mMaskShader[0].setLocalMatrix(mMaskM);
        Log.i(TAG, "[WAKE-GLOW] bloom 烘焙（3 层合 1）" + qw + "x" + qh + " ALPHA_8（1/4 分辨率）用时 " + (SystemClock.uptimeMillis() - t0) + "ms");
    }

    /** 点到圆角框边界的内侧距离（框外 = 0）。 */
    static float insideDistance(float x, float y, float l, float t, float r, float b, float rad) {
        float dx = Math.min(x - l, r - x), dy = Math.min(y - t, b - y);
        if (dx < 0 || dy < 0) return 0f;
        if (dx < rad && dy < rad) {
            float cx = rad - dx, cy = rad - dy;
            float dd = (float) Math.sqrt(cx * cx + cy * cy);
            return Math.max(0f, rad - dd);
        }
        return Math.min(dx, dy);
    }

    /** 预热 bloom 遮罩（进程起来后调一次；窗口还没挂也行，用屏幕实尺寸）。 */
    public void prewarm() {
        ensureGeometry();
    }

    public void releaseBitmaps() {
        for (int i = 0; i < 3; i++) {
            if (mMask[i] != null) mMask[i].recycle();
            mMask[i] = null;
            mMaskShader[i] = null;
            mCompose[i] = null;
        }
        mW = mH = 0;
    }

    // =====================================================================
    // 绘制
    // =====================================================================

    @Override
    protected void onDraw(Canvas c) {
        drawGlow(c);
    }

    private void drawGlow(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = mLast == 0L ? 0.016f : Math.min(0.033f, (now - mLast) / 1000f);
        mLast = now;
        if (mW == 0 || mMask[0] == null) ensureGeometry();

        // 声音 → 包络 → GLOW_LEVEL
        float norm = VoiceLevel.fresh(android.os.SystemClock.elapsedRealtime())
                ? VoiceLevel.norm(VoiceLevel.rms(), VoiceLevel.floor()) : 0f;
        float env = mEnv.step(norm, dt);
        mLevel.t = env;
        if (env > 0.08f) mLastLoudAt = now;
        mGlowA.step(dt);
        mRise.step(dt);
        mLevel.step(dt);
        mDim.step(dt);
        float e = Math.max(0f, Math.min(1.2f, mLevel.v));
        mPhase += dt / (14f - 7f * Math.min(1f, e));

        // 1) 底部局部压暗
        if (mDim.v > 0.003f) {
            if (mDimShader == null || Math.abs(mDimShaderStart - mDimStart) > 0.01f) {
                float y0 = mH * mDimStart;
                mDimShader = new LinearGradient(0, y0, 0, mVisBottom > 0 ? mVisBottom : mH, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
                mDimShaderStart = mDimStart;
            }
            mDimPaint.setShader(mDimShader);
            mDimPaint.setAlpha(Math.round(255 * Math.min(1f, mDim.v)));
            c.drawRect(0, mH * mDimStart, mW, mH, mDimPaint);
        }

        // 2) bloom
        float ga = Math.max(0f, Math.min(1f, mGlowA.v));
        if (ga > 0.004f && mMask[0] != null) {
            float breath = 1f;
            if (now - mLastLoudAt > 1200L) breath = 0.925f + 0.075f * (float) Math.sin(2 * Math.PI * 0.25 * (now - mLastLoudAt) / 1000.0);
            float rise = Math.max(0f, Math.min(1f, mRise.v));
            float top = mVisBottom - (mVisBottom - mBloomTop) * (float) Math.sqrt(rise);
            float sy = (mVisBottom - top) / Math.max(1f, mVisBottom - mBloomTop);
            float ee = Math.min(1f, e);
            // 声音：亮度 0.78 → 1.0，竖向范围 0.94 → 1.06（大声时光往上多爬一点）
            float lift = 0.94f + 0.12f * ee;
            float top2 = mVisBottom - (mVisBottom - top) * lift;
            float sy2 = sy * lift;
            float shift = mPhase * mW * 1.6f;
            int a = Math.round(255f * ga * breath * (0.78f + 0.22f * ee) * Math.min(1f, 0.35f + 0.65f * rise));
            if (a > 0) {
                if (mBakedTint) {
                    mFlat.setColor(currentColor());
                    mFlat.setAlpha(a);
                    mRect.set(0, top2, mW, mVisBottom);
                    c.drawBitmap(mMask[0], null, mRect, mFlat);
                } else {
                    mMaskM.setScale(Q, Q * sy2);
                    mMaskM.postTranslate(0, top2);
                    mMaskShader[0].setLocalMatrix(mMaskM);
                    mPalM.setTranslate(-shift, 0);
                    mPalette.setLocalMatrix(mPalM);
                    mLayer[0].setShader(mCompose[0]);
                    mLayer[0].setAlpha(a);
                    float band = Math.min(mBand, mW / 2f);
                    c.drawRect(0, top2, band, mVisBottom, mLayer[0]);
                    c.drawRect(mW - band, top2, mW, mVisBottom, mLayer[0]);
                    c.drawRect(band, Math.max(top2, mVisBottom - mBand), mW - band, mVisBottom, mLayer[0]);
                }
            }
        }

        // 3) 舞台
        boolean stage = mMorph != null && drawStage(c, dt);

        boolean anim = stage || !mGlowA.settled(0.004f) || !mRise.settled(0.004f) || !mDim.settled(0.003f)
                || ga > 0.004f;
        // [R13 卡死排查] 窗口不可见 / 已摘：不再续帧（View 摘下后 postOnAnimation 会进 RunQueue，挂回时又接着转）
        if (anim && (!isAttachedToWindow() || getWindowVisibility() != VISIBLE)) anim = false;
        if (anim) {
            // 形变舞台 60 fps；光 / 压暗是柔和的大面积渐变 → 30 fps（隔一个 vsync 画一次），省一半 RenderThread
            if (stage || mMorph != null) postInvalidateOnAnimation();
            else postOnAnimation(mInvalidateNext);
        }
    }

    private boolean drawStage(Canvas c, float dt) {
        MorphGeometry m = mMorph;
        if (mInstant) {
            // 系统动画关掉：没有形变，直接落到终点
            mMorph = null;
            finishStage(m, true);
            return false;
        }
        long nowMs = SystemClock.uptimeMillis();
        if (mStageFrames == 0) {
            Log.i(TAG, "[WAKE-MORPH] 舞台第一帧：start 后 " + (nowMs - mStageStartAt) + "ms");
            dt = 0.016f;
        } else {
            long gap = nowMs - mStageLastFrameAt;
            if (gap > 20L) mStageSlowFrames++;
            if (gap > mStageMaxGap) mStageMaxGap = gap;
        }
        mStageLastFrameAt = nowMs;
        mStageFrames++;
        m.step(dt);
        m.rect(mR4);
        mRect.set(mR4[0], mR4[1], mR4[2], mR4[3]);
        float r = m.cornerRadius();
        float p = m.progress();
        // 颜色：卡片近黑 → 岛纯黑
        int fill = lerpColor(m.reverse() ? 0xFF000000 : WakeCardPainter.FILL_TOP, m.reverse() ? WakeCardPainter.FILL_TOP : 0xFF000000, p);
        mStageFill.setColor(fill);
        c.drawRoundRect(mRect, r, r, mStageFill);
        float sa = m.snapshotAlpha();
        if (mSnapShader != null && sa > 0.01f && mSnapFrom.width() > 1f) {
            mSnapM.setScale(mRect.width() / mSnapFrom.width(), mRect.height() / mSnapFrom.height());
            mSnapM.postTranslate(mRect.left, mRect.top);
            mSnapShader.setLocalMatrix(mSnapM);
            mStageSnap.setShader(mSnapShader);
            mStageSnap.setAlpha(Math.round(255 * sa));
            c.drawRoundRect(mRect, r, r, mStageSnap);
        }
        float waist = m.neckWaist();
        if (waist > 0.5f) {
            float cx = mIslandCx - mLoc[0];
            float sb = mIslandTop - mLoc[1] + mIslandSeed;
            float top = mRect.top;
            float g = top - sb;
            float a = mIslandSeed * 0.42f;
            float b = Math.min(mRect.width() / 2f, Math.max(waist, a) * 1.1f);
            mNeck.reset();
            mNeck.moveTo(cx - a, sb - 2f * d);
            mNeck.cubicTo(cx - waist / 2f, sb + g * 0.45f, cx - waist / 2f, top - g * 0.45f, cx - b, top + 2f * d);
            mNeck.lineTo(cx + b, top + 2f * d);
            mNeck.cubicTo(cx + waist / 2f, top - g * 0.45f, cx + waist / 2f, sb + g * 0.45f, cx + a, sb - 2f * d);
            mNeck.close();
            mStageFill.setColor(0xFF000000);
            c.drawPath(mNeck, mStageFill);
        }
        if (mFirstFramePending) {
            mFirstFramePending = false;
            postOnAnimation(new Runnable() {   // 这一帧已交给渲染线程；下一个 vsync 再通知宿主摘卡片窗
                @Override
                public void run() {
                    if (mStageListener != null) mStageListener.onStageFirstFrame();
                }
            });
        }
        // [R13 卡死排查] 去程以前只认「接触」才收尾；几何上一旦永远够不着（岛目标变了 / 旋转），舞台会 60 fps 全屏重绘到天荒地老。
        //   与回程同一个上限：1.4 s 没接触也按接触收尾（岛照常 APPEAR）。
        if (!m.reverse() && (m.contact() || m.elapsed() > MorphGeometry.MAX_STAGE_S)) {
            mMorph = null;
            finishStage(m, false);
            return false;
        }
        if (m.reverse() && (m.settled() || m.elapsed() > MorphGeometry.MAX_STAGE_S)) {
            mMorph = null;
            finishStage(m, false);
            return false;
        }
        return true;
    }

    private void finishStage(MorphGeometry m, boolean instant) {
        boolean firstFrameOwed = mFirstFramePending || instant;
        if (!m.reverse()) {
            float[] merged = new float[4];
            m.mergedRect(merged, mIslandWinBottom - mLoc[1]);
            float[] v = instant ? new float[] {0f, 0f} : m.handoffVelocity();
            RectF onScreen = new RectF(merged[0] + mLoc[0], merged[1] + mLoc[1], merged[2] + mLoc[0], merged[3] + mLoc[1]);
            Log.i(TAG, "[WAKE-MORPH] 接触 t=" + Math.round(m.elapsed() * 1000) + "ms 合并 " + onScreen.toShortString()
                    + " v=(" + Math.round(v[0]) + "," + Math.round(v[1]) + ") " + stageClock());
            releaseStage();
            if (mStageListener != null) {
                if (firstFrameOwed) mStageListener.onStageFirstFrame();
                mStageListener.onContact(onScreen, v[0], v[1]);
            }
        } else {
            Log.i(TAG, "[WAKE-MORPH] 回程落定 t=" + Math.round(m.elapsed() * 1000) + "ms " + stageClock());
            releaseStage();
            if (mStageListener != null) {
                if (firstFrameOwed) mStageListener.onStageFirstFrame();
                mStageListener.onUnmorphSettled();
            }
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mInvalidateNext);   // [R13] 摘窗即停帧
        mLast = 0L;
    }

    static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
