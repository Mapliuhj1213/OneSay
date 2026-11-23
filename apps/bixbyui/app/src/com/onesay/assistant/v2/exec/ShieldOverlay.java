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
    /** [fix-1123-dexec B8/B40] 孔按引用计数叠加（里层开 / 关不再把外层的孔关掉）。 */
    private final ShieldHoles mHoles = new ShieldHoles();
    /** [fix-1123-dexec B8] 手势导航：边缘返回 / 底边上划 = 紧急接管；null = 三键导航。 */
    private volatile EdgeGesture mEdge;
    /** [fix-1123-dexec B40] 当前挂着的护罩（无障碍手势 / 助手注入统一从这里开孔）。 */
    private static volatile ShieldOverlay sActive;

    /** 当前挂着、拦触摸的护罩；没有 = null。 */
    public static ShieldOverlay active() {
        ShieldOverlay s = sActive;
        return s != null && s.mAttached && !s.mThin ? s : null;
    }

    /**
     * [fix-1123-dexec B40] 代操作注入前调用：当前护罩在注入落点开孔（阻塞到生效）；返回要不要在注入后 {@link #unholeInject}。
     * 护罩只拦用户的手指，不能拦代操作自己注入的点按 / 手势。
     */
    public static boolean holeForInject(int l, int t, int r, int b) {
        ShieldOverlay s = active();
        if (s == null) return false;
        // [fix-1123-dexec B38] 落点压着灵动岛：岛先让开（不接触摸），注入完恢复
        try {
            com.onesay.assistant.v2.s1.IslandController isl = com.onesay.assistant.v2.s1.S1Hooks.island();
            Rect ir = isl == null ? null : isl.islandWindowRect();
            if (ir != null && com.onesay.assistant.v2.island.IslandTouchDodge.overlaps(new int[]{l, t, r, b}, new int[]{ir.left, ir.top, ir.right, ir.bottom})) {
                isl.yieldTouch(true);
                s.mIslandYielded = true;
            }
        } catch (Throwable ignored) {
        }
        s.openHole(new Rect(l, t, r, b));
        return true;
    }

    public static void unholeInject(boolean opened) {
        if (!opened) return;
        ShieldOverlay s = sActive;
        if (s != null) {
            s.closeHole();
            final ShieldOverlay fs = s;
            s.mMain.post(new Runnable() {   // closeHole 也是抛到主线程的：排在它后面再看孔是不是全补上了
                @Override public void run() {
                    if (fs.mIslandYielded && fs.mHoles.empty()) {
                        fs.mIslandYielded = false;
                        try {
                            com.onesay.assistant.v2.s1.IslandController isl = com.onesay.assistant.v2.s1.S1Hooks.island();
                            if (isl != null) isl.yieldTouch(false);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        }
    }

    private volatile boolean mIslandYielded;

    public void setEdgeGesture(EdgeGesture g) {
        mEdge = g;
    }
    private final Rect mIsland = new Rect();
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mD;
    /** ★ borrow-1112：控屏状态颜色（0xRRGGBB；-1 = 原配色）。 */
    private volatile int mTint = -1;
    /** [fix-1123-c] 导航键区域（{@link com.onesay.assistant.v2.fastscreen.NavKeys#compute} 的 8 个数）和按到时的回调。 */
    public interface NavListener {
        void onNavKey(String which);
    }

    private volatile int[] mNav;
    private volatile NavListener mNavL;

    /** 护罩盖着导航栏时，按到返回 / 主页 / 多任务键 = 紧急接管（不再当误触吞掉）。 */
    public void setNavKeys(int[] rects, NavListener l) {
        mNav = rects;
        mNavL = l;
    }

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
            mLp.alpha = com.onesay.assistant.v2.island.IslandGeometry.shieldWindowAlpha(android.os.Build.VERSION.SDK_INT);   // ★ meizu-1118 B10
            try {
                mWm.addView(this, mLp);
                mAttached = true;
                sActive = this;
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
        if (sActive == this) sActive = null;
        if (mIslandYielded) {
            mIslandYielded = false;
            try {
                com.onesay.assistant.v2.s1.IslandController isl = com.onesay.assistant.v2.s1.S1Hooks.island();
                if (isl != null) isl.yieldTouch(false);
            } catch (Throwable ignored) {
            }
        }
        synchronized (this) {
            mHoles.clear();
            mHole.setEmpty();
        }
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
                        mHoles.open(rect.left, rect.top, rect.right, rect.bottom);
                        int[] u = mHoles.rect();
                        mHole.set(u[0], u[1], u[2], u[3]);
                    }
                    holes++;
                    requestLayout();
                    invalidate();
                } else {
                    fallbacks++;
                    synchronized (ShieldOverlay.this) {
                        mHoles.open(rect.left, rect.top, rect.right, rect.bottom);
                    }
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
                boolean empty;
                synchronized (ShieldOverlay.this) {
                    empty = mHoles.close() == 0;   // [fix-1123-dexec] 最后一层关掉才补孔
                    if (empty) mHole.setEmpty();
                    else {
                        int[] u = mHoles.rect();
                        mHole.set(u[0], u[1], u[2], u[3]);
                    }
                }
                if (mRegionToken != null) requestLayout();
                else if (!mThin && empty) setTouchable(true);
                invalidate();
            }
        });
    }

    // ------------------------------------------------------------------ 触摸 / 绘制

    @Override public boolean onTouchEvent(MotionEvent e) {
        EdgeGesture eg = mEdge;
        if (eg != null) {   // [fix-1123-dexec B8] 手势导航：边缘带里的按下先不算误触，看是不是返回 / 上划
            int a = e.getActionMasked();
            String hit = null;
            if (a == MotionEvent.ACTION_DOWN) {
                if (eg.down(e.getRawX(), e.getRawY()) != 0) return true;
            } else if (a == MotionEvent.ACTION_MOVE) {
                hit = eg.move(e.getRawX(), e.getRawY());
            } else if (a == MotionEvent.ACTION_CANCEL) {
                hit = eg.cancel();
                eg.up();
            } else if (a == MotionEvent.ACTION_UP) {
                boolean was = eg.inZone();
                eg.up();
                if (was) mEnv.onStray(e.getRawX(), e.getRawY());   // 边缘轻点一下：还是误触
                return true;
            }
            NavListener nl = mNavL;
            if (hit != null && nl != null) {
                nl.onNavKey(hit);
                return true;
            }
            if (eg.inZone()) return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            String nav = com.onesay.assistant.v2.fastscreen.NavKeys.hit(mNav, (int) e.getRawX(), (int) e.getRawY());
            NavListener l = mNavL;
            if (nav != null && l != null) {
                l.onNavKey(nav);   // [fix-1123-c] 紧急接管：这一下不漏进 App（护罩随即变细边框，再按就是系统的返回 / 主页）
                return true;
            }
            mEnv.onStray(e.getRawX(), e.getRawY());
        }
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
