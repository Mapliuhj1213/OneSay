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

    private WindowManager mWm;
    private final Env mEnv;
    /** [exec-1124 B] 护罩用哪种窗口（{@link ShieldMode}）；默认原样。 */
    private volatile ShieldMode.Mode mMode = ShieldMode.Mode.LEGACY;
    /** [exec-1124 B] 无障碍浮层模式下拿无障碍服务的窗口管理（服务被重绑后要换新实例的）。 */
    private volatile A11yWm mA11yWm;

    /** [exec-1124 B] 提供无障碍服务的窗口管理；服务没连着返回 null。 */
    public interface A11yWm {
        /** 当前无障碍服务实例（不等；没连着 = null）。用来认出服务换了实例。 */
        Object owner();

        /** 当前实例的窗口管理；没连着最多等 waitMs，还没有 = null。 */
        WindowManager get(long waitMs);
    }

    /** [exec-1124 B] 无障碍浮层挂在哪个服务实例上。 */
    private volatile Object mOwner;

    /**
     * [exec-1124 B] 挂出之前定护罩模式（主线程，show 之前调）。A11Y_OVERLAY 要给 a11yWm；拿不到就退成只留光晕。
     */
    public void setMode(ShieldMode.Mode m, A11yWm a11yWm) {
        mMode = m == null ? ShieldMode.Mode.LEGACY : m;
        mA11yWm = a11yWm;
    }

    public ShieldMode.Mode mode() {
        return mMode;
    }
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
        return s != null && s.mAttached && !s.mThin && ShieldMode.blocksTouch(s.mMode) ? s : null;   // [exec-1124 B] 只留光晕时不用开孔
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
        mAppWm = wm;
        mEnv = env;
        mD = ctx.getResources().getDisplayMetrics().density;
        mLine.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
    }

    // ------------------------------------------------------------------ 挂载（主线程）

    public void show(boolean thin) {
        mThin = thin;
        if (!mAttached) {
            if (mMode == ShieldMode.Mode.A11Y_OVERLAY) {   // [exec-1124 B] 无障碍浮层：由无障碍服务挂，不算进新安卓的「遮挡」
                A11yWm h = mA11yWm;
                Object owner = h == null ? null : h.owner();
                WindowManager w = h == null ? null : h.get(0);
                if (w != null && attach(w, ShieldMode.TYPE_ACCESSIBILITY_OVERLAY, thin)) {
                    mOwner = owner;
                    afterShow();
                    return;
                }
                Log.w(TAG, "[SHIELD] 无障碍浮层挂不上，改成只留光晕（不拦触摸）");
                mMode = ShieldMode.Mode.GLOW_ONLY;
            }
            if (!attach(mAppWm, com.onesay.assistant.v2.s1.S1Overlay.windowType(), thin)) return;
        } else {
            setTouchable(!thin);
        }
        afterShow();
    }

    /** 原来构造时给的普通悬浮窗管理（只留光晕 / 原护罩用）。 */
    private WindowManager mAppWm;

    private void afterShow() {
        mMain.removeCallbacks(mTick);
        mMain.post(mTick);
        invalidate();
    }

    /**
     * 挂窗口。[exec-1124 B] 只留光晕的模式整窗 FLAG_NOT_TOUCHABLE（不接触摸、不挡读屏）；其余和原来一样。
     */
    private boolean attach(WindowManager wm, int type, boolean thin) {
        boolean glow = mMode == ShieldMode.Mode.GLOW_ONLY;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | (thin || glow ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0),
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("OneSay-ExecShield");
        lp.alpha = com.onesay.assistant.v2.island.IslandGeometry.shieldWindowAlpha(android.os.Build.VERSION.SDK_INT);   // ★ meizu-1118 B10
        try {
            wm.addView(this, lp);
            mWm = wm;
            mLp = lp;
            mAttached = true;
            sActive = this;
            mRegionToken = glow ? null : TouchRegionCompat.install(this, new TouchRegionCompat.Source() {
                @Override public boolean touchRegion(Region out) {
                    return computeRegion(out);
                }
            });
            Log.i(TAG, "[SHIELD] 护罩已挂出 thin=" + thin + " 模式=" + ShieldMode.zh(mMode) + " region=" + (mRegionToken != null));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[SHIELD] 挂护罩失败（" + ShieldMode.zh(mMode) + "）: " + t);
            return false;
        }
    }

    /**
     * [exec-1124 B] 无障碍浮层的宿主（无障碍服务实例）还在不在。Flyme 会周期性地解绑 / 重绑无障碍服务，服务一断它挂的窗口就被系统拆掉
     * （客户端不一定收到通知）。每秒看一次：实例换了 → 拆掉旧的、挂到新实例上；没了超过 {@link ShieldMode#REATTACH_WAIT_MS}
     * → 退成只留光晕，不退回会挡读屏的普通护罩。主线程。
     */
    private void checkA11yHost() {
        if (mMode != ShieldMode.Mode.A11Y_OVERLAY || !mAttached) return;
        A11yWm h = mA11yWm;
        Object now = h == null ? null : h.owner();
        if (now != null && now == mOwner) {
            mA11yGoneAt = 0;
            return;
        }
        long t = SystemClock.uptimeMillis();
        if (now == null) {
            if (mA11yGoneAt == 0) mA11yGoneAt = t;
            if (t - mA11yGoneAt < ShieldMode.REATTACH_WAIT_MS) return;
        }
        mA11yGoneAt = 0;
        remount(now == null ? null : h.get(0), now);
    }

    private long mA11yGoneAt;

    /** 拆掉当前窗口，挂到新的无障碍实例上（w == null 或挂不上 → 只留光晕）。主线程。 */
    private void remount(WindowManager w, Object owner) {
        TouchRegionCompat.uninstall(this, mRegionToken);
        mRegionToken = null;
        mDetaching = true;
        try {
            mWm.removeViewImmediate(this);
        } catch (Throwable ignored) {
        } finally {
            mDetaching = false;
        }
        mAttached = false;
        ShieldMode.Mode next = ShieldMode.afterA11yLost(w != null);
        if (next == ShieldMode.Mode.A11Y_OVERLAY && attach(w, ShieldMode.TYPE_ACCESSIBILITY_OVERLAY, mThin)) {
            mOwner = owner;
            Log.i(TAG, "[SHIELD] 无障碍服务换了实例，护罩已挂回");
            return;
        }
        Log.w(TAG, "[SHIELD] 无障碍服务没回来，护罩改成只留光晕（不拦触摸）");
        mMode = ShieldMode.Mode.GLOW_ONLY;
        mOwner = null;
        synchronized (this) {
            mHoles.clear();
            mHole.setEmpty();
        }
        attach(mAppWm, com.onesay.assistant.v2.s1.S1Overlay.windowType(), mThin);
    }

    /** hide() 自己拆窗口时不要当成被系统拆。 */
    private volatile boolean mDetaching;


    public void hide() {
        mMain.removeCallbacks(mTick);
        if (!mAttached) return;
        TouchRegionCompat.uninstall(this, mRegionToken);
        mRegionToken = null;
        mDetaching = true;
        try {
            mWm.removeViewImmediate(this);
        } catch (Throwable ignored) {
        } finally {
            mDetaching = false;
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
        if (on && !ShieldMode.blocksTouch(mMode)) return;   // [exec-1124 B] 只留光晕：永远不接触摸
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
            if (mMode == ShieldMode.Mode.A11Y_OVERLAY && ++mTicks % 16 == 0) {   // [exec-1124 B] 约每秒看一次无障碍宿主
                checkA11yHost();
                if (!mAttached) return;
            }
            Rect r = mEnv.islandRect();
            if (r != null && !r.equals(mIsland)) {
                mIsland.set(r);
                requestLayout();
            }
            invalidate();
            mMain.postDelayed(this, mThin ? 400 : 60);
        }
    };

    private int mTicks;

    synchronized boolean computeRegion(Region out) {
        if (mThin || !ShieldMode.blocksTouch(mMode)) return false;
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
        if (!mAttached || mThin || !ShieldMode.blocksTouch(mMode)) return;   // [exec-1124 B] 只留光晕：本来就不拦，不用开孔、不用等
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
        if (!mAttached || !ShieldMode.blocksTouch(mMode)) return;   // [exec-1124 B] 只留光晕时没开过孔
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
