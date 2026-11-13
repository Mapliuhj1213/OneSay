package com.onesay.assistant.v2.exec;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.onesay.assistant.v2.ui.wake.TouchRegionCompat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ★ r2/fast-screen：执行期间的防误触护罩 + 屏幕边缘光晕（TYPE_APPLICATION_OVERLAY，<b>FLAG_NOT_FOCUSABLE</b>：
 * 输入法 / 按键焦点一直留在被操作的 App）。
 *
 * <ul>
 *   <li>FULL：整屏可触摸、吞掉误触（只轻提示「点灵动岛可接管」，任务不断）；四边光晕呼吸，表示 AI 正在动手；</li>
 *   <li>THIN：用户接手 / 补充时，光晕变 3dp 细边框，FLAG_NOT_TOUCHABLE，不拦任何触摸；</li>
 *   <li>可触摸区域（隐藏 API touchable region，同 {@link TouchRegionCompat}）= 全屏 − 灵动岛窗口 − 当前注入孔：
 *       护罩永远不盖住岛；我们自己注入点按 / 滑动时只在注入点开一个小孔（{@link #openHole}），注入完立刻补上。
 *       孔开好要等 WMS 拿到新区域（等两帧），所以 {@link #openHole} 会阻塞到那之后再返回。</li>
 * </ul>
 * 不支持 touchable region 时退回：注入前整窗 FLAG_NOT_TOUCHABLE、注入后恢复（护罩短暂失效，报告里记了风险）。
 */
public final class ShieldOverlay extends View {

    private static final String TAG = "ExecShield";

    public interface Env {
        /** 岛窗口当前矩形（护罩要给它留洞）。 */
        Rect islandRect();

        /** 护罩吞了一次触摸（x,y 屏幕坐标）。 */
        void onStray(float x, float y);
    }

    private final WindowManager mWm;
    private final Env mEnv;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private WindowManager.LayoutParams mLp;
    private Object mRegionToken;
    private boolean mAttached;
    private volatile boolean mThin;
    private final Rect mHole = new Rect();
    private final Rect mIsland = new Rect();
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mD;
    /** ★ borrow-1112：控屏状态颜色（0xRRGGBB；-1 = 原配色）。 */
    private volatile int mTint = -1;
    /** 统计：孔开了几次 / 退回整窗不可触摸几次。 */
    public volatile int holes, fallbacks;

    public ShieldOverlay(Context ctx, WindowManager wm, Env env) {
        super(ctx);
        mWm = wm;
        mEnv = env;
        mD = ctx.getResources().getDisplayMetrics().density;
        mLine.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
    }

    // ------------------------------------------------------------------ 挂载（主线程）

    public void show(boolean thin) {
        mThin = thin;
        if (!mAttached) {
            mLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    com.onesay.assistant.v2.s1.S1Overlay.windowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | (thin ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0),
                    PixelFormat.TRANSLUCENT);
            mLp.gravity = Gravity.TOP | Gravity.START;
            mLp.setTitle("OneSay-ExecShield");
            try {
                mWm.addView(this, mLp);
                mAttached = true;
                mRegionToken = TouchRegionCompat.install(this, new TouchRegionCompat.Source() {
                    @Override public boolean touchRegion(Region out) {
                        return computeRegion(out);
                    }
                });
                Log.i(TAG, "[SHIELD] 护罩已挂出 thin=" + thin + " region=" + (mRegionToken != null));
            } catch (Throwable t) {
                Log.w(TAG, "[SHIELD] 挂护罩失败: " + t);
                return;
            }
        } else {
            setTouchable(!thin);
        }
        mMain.removeCallbacks(mTick);
        mMain.post(mTick);
        invalidate();
    }

    public void hide() {
        mMain.removeCallbacks(mTick);
        if (!mAttached) return;
        TouchRegionCompat.uninstall(this, mRegionToken);
        mRegionToken = null;
        try {
            mWm.removeViewImmediate(this);
        } catch (Throwable ignored) {
        }
        mAttached = false;
        Log.i(TAG, "[SHIELD] 护罩已撤掉");
    }

    /** ★ borrow-1112：换边框颜色（任意线程；-1 = 原配色）。 */
    public void setTint(int rgb) {
        mTint = rgb;
        postInvalidate();
    }

    public int tint() {
        return mTint;
    }

    public boolean attached() {
        return mAttached;
    }

    public boolean thin() {
        return mThin;
    }

    private void setTouchable(boolean on) {
        if (!mAttached || mLp == null) return;
        int f = mLp.flags;
        if (on) f &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else f |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (f == mLp.flags) return;
        mLp.flags = f;
        try {
            mWm.updateViewLayout(this, mLp);
        } catch (Throwable ignored) {
        }
    }

    /** 每 200 ms：岛的窗口变了（展开 / 收起）就重算可触摸区域；顺便推动光晕动画。 */
    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            if (!mAttached) return;
            Rect r = mEnv.islandRect();
            if (r != null && !r.equals(mIsland)) {
                mIsland.set(r);
                requestLayout();
            }
            invalidate();
            mMain.postDelayed(this, mThin ? 400 : 60);
        }
    };

    synchronized boolean computeRegion(Region out) {
        if (mThin) return false;
        out.set(0, 0, Math.max(1, getWidth()), Math.max(1, getHeight()));
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        if (!mIsland.isEmpty()) {
            Rect r = new Rect(mIsland);
            r.offset(-loc[0], -loc[1]);
            out.op(r, Region.Op.DIFFERENCE);
        }
        if (!mHole.isEmpty()) {
            Rect r = new Rect(mHole);
            r.offset(-loc[0], -loc[1]);
            out.op(r, Region.Op.DIFFERENCE);
        }
        return true;
    }

    // ------------------------------------------------------------------ 注入孔（后台线程调用，阻塞到生效）

    /** 在 rect（屏幕坐标）开孔；返回时 WMS 已拿到新区域（等了两帧）。不支持 touchable region 时整窗放行。 */
    public void openHole(final Rect rect) {
        if (!mAttached || mThin) return;
        final CountDownLatch l = new CountDownLatch(1);
        mMain.post(new Runnable() {
            @Override public void run() {
                if (mRegionToken != null) {
                    synchronized (ShieldOverlay.this) {
                        mHole.set(rect);
                    }
                    holes++;
                    requestLayout();
                    invalidate();
                } else {
                    fallbacks++;
                    setTouchable(false);
                }
                // 两帧：第一帧 traversal 把新区域经 setInsets 交给 WMS，第二帧之后 InputMonitor 已更新
                Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                    @Override public void doFrame(long t) {
                        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                            @Override public void doFrame(long t2) {
                                l.countDown();
                            }
                        });
                    }
                });
            }
        });
        try {
            l.await(300, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        SystemClock.sleep(16);
    }

    public void closeHole() {
        if (!mAttached) return;
        mMain.post(new Runnable() {
            @Override public void run() {
                synchronized (ShieldOverlay.this) {
                    mHole.setEmpty();
                }
                if (mRegionToken != null) requestLayout();
                else if (!mThin) setTouchable(true);
                invalidate();
            }
        });
    }

    // ------------------------------------------------------------------ 触摸 / 绘制

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) mEnv.onStray(e.getRawX(), e.getRawY());
        return true;   // 吞掉：误触不漏进 App
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (mThin) {
            mLine.setStrokeWidth(3f * mD);
            mLine.setColor(0xCC000000 | (mTint >= 0 ? mTint : com.onesay.assistant.v2.exec.ControlColor.LEGACY_THIN));
            c.drawRect(1.5f * mD, 1.5f * mD, w - 1.5f * mD, h - 1.5f * mD, mLine);
            return;
        }
        long now = SystemClock.uptimeMillis();
        float k = 0.55f + 0.45f * (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * ((now % 2400L) / 2400.0)));
        float g = 22f * mD;
        int a = (int) (170 * k);
        int rgb = mTint >= 0 ? mTint : com.onesay.assistant.v2.exec.ControlColor.LEGACY_GLOW;
        int c1 = (a << 24) | rgb, c0 = rgb;
        mGlow.setShader(new LinearGradient(0, 0, 0, g, c1, c0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, g, mGlow);
        mGlow.setShader(new LinearGradient(0, h, 0, h - g, c1, c0, Shader.TileMode.CLAMP));
        c.drawRect(0, h - g, w, h, mGlow);
        mGlow.setShader(new LinearGradient(0, 0, g, 0, c1, c0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, g, h, mGlow);
        mGlow.setShader(new LinearGradient(w, 0, w - g, 0, c1, c0, Shader.TileMode.CLAMP));
        c.drawRect(w - g, 0, w, h, mGlow);
    }
}
