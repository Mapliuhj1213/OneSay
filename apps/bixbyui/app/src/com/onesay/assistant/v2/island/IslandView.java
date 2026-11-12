package com.onesay.assistant.v2.island;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * [island v3] 灵动岛的<b>自绘视图</b>：黑色形状，上沿固定在状态栏下 2dp（全屏 8dp）、水平居中，各状态只改宽 / 高 / 圆角
 * （弹簧 ω=22 ζ=0.86），内容在形状长到 80% 后才淡入。设计稿 motion/island-v3.html；方案 island-v3-plan.md §5。
 *
 * <p>窗口（TYPE_APPLICATION_OVERLAY）只在形变期间变大，静止时只包住岛。逻辑全在 {@link IslandModel}；这里只画快照、翻译手势。
 * 手势：点按 = 打开 App；长按 450ms = 展开；横滑 &gt; 24dp = 暂避 60s；展开态上滑 &gt; 24dp 或点外部 = 收起（确认卡除外）。
 */
public class IslandView extends View {

    public static final String A_STOP = "stop", A_ALLOW = "allow", A_DENY = "deny", A_CLOSE = "close", A_RETRY = "retry",
            A_COPY = "copy", A_PREV = "prev", A_NEXT = "next", A_DISMISS = "dismiss", A_REMIND_DONE = "remindDone",
            A_REMIND_SNOOZE = "remindSnooze", A_TODO_ALL = "todoAll", A_TODO_PREFIX = "todo:",
            /** ★ R10 记一记结果卡：「note:<n>」= 第 n 个动作；A_NOTE_OPEN = 点正文打开详情。 */
            A_NOTE_PREFIX = "note:", A_NOTE_OPEN = "noteOpen",
            /** ★ 消息提醒：「ntx:<id>」点叉忽略、「ntf:<id>」选中、「nta:<n>」选中那条的第 n 个动作、全部忽略。 */
            A_NOTICE_X = "ntx:", A_NOTICE_FOCUS = "ntf:", A_NOTICE_ACT = "nta:", A_NOTICE_ALL = "ntall",
            /** ★ r2/fast-screen 执行模式：补充 / 接管 / 继续（停止沿用 A_STOP）。 */
            A_EXEC_SUPPLEMENT = "execSupplement", A_EXEC_TAKEOVER = "execTakeover", A_EXEC_RESUME = "execResume",
            /** ★ arch/island-progress：ask_user 芯片「ask:<n>」= 点了第 n 个选项（0 起）。 */
            A_ASK_PREFIX = "ask:";

    public interface Listener {
        void onTap(String key);

        void onLongPress(String key);

        void onSwipeSide();

        /** ★：紧凑态向左滑过提醒 = 忽略队首那条（向右滑仍是暂避）。 */
        void onSwipeDismiss(String key);

        void onSwipeUp();

        void onAction(String action);

        void onOutside();
    }

    public interface Host {
        void requestWindow(Rect screenRect);

        void onHiddenSettled();
    }

    static final int INK = 0xFF111111, INK2 = 0xFF262624, ON1 = 0xFFFFFFFF, ON2 = 0xFFCFCBC4, ON3 = 0xFFA3A09A,
            RED = 0xFFE5484D, ACC = 0xFF84AE64, MUTE = 0xFF8C8983, LINE = 0xFF2A2927;
    /** 提醒点颜色：麦克风橙（Android 12 起的系统约定）、相机 / 通话绿、警示黄。 */
    static final int TONE_MIC = 0xFFF5A524, TONE_CAM = 0xFF30C452, TONE_WARN = 0xFFF2C94C;

    /**：提醒点的颜色（纯函数）。 */
    static int noticeDot(IslandModel.NoticeRow r) {
        if (r == null) return ACC;
        String k = r.dotClass();
        if ("mic".equals(k)) return TONE_MIC;
        if ("cam".equals(k)) return TONE_CAM;
        if ("warn".equals(k)) return TONE_WARN;
        return "high".equals(k) ? RED : ACC;
    }

    private final float d;
    private Listener mListener;
    private Host mHost;
    private IslandGeometry.Anchor mAnchor;
    private int mScreenW, mScreenH;
    private int mWinL, mWinT;
    private final Rect mWinReq = new Rect();

    private IslandModel.Snapshot mCur, mPrev;
    private final IslandGeometry.Spring mW, mH, mR, mSplit;
    private final IslandGeometry.Spring mCurA = new IslandGeometry.Spring(0f, 20f, 1f);
    private final IslandGeometry.Spring mPrevA = new IslandGeometry.Spring(0f, 50f, 1f);
    private float mPrevH;
    private long mLastFrame;
    private boolean mSettledReported = true;

    // ★ R11（真机：岛会自己抖动 / 动画不够丝滑）
    /** 形状不透明度：消失时缩回小药丸再淡出（不再缩成一个点、也不硬切）。 */
    private final IslandGeometry.Spring mShapeA = new IslandGeometry.Spring(0f, IslandGeometry.omegaOf(0.28f), 1f);
    /** 按下回弹（画布里缩放，不再 setScaleX —— 全宽窗口下 View 缩放会把岛整体挪位）。 */
    private final IslandGeometry.Spring mPress = new IslandGeometry.Spring(1f, IslandGeometry.omegaOf(0.30f), 0.72f);
    /** 只有换了「层」（种类 / 活动）时才按形状进度拦内容；同层更新（文字变了）直接交叉淡化，不把内容闪成 0。 */
    private boolean mGateArmed;
    private float mGate = IslandGeometry.CONTENT_GATE;

    // ---- ★ R13 卡 → 岛 形变接力 ----
    /** 卡片正在飞来：到这个时刻（uptime）前，从隐藏出现的岛只长到「种子」（28dp 小胶囊）并且不显示内容。 */
    private long mMorphHoldUntil;
    /** appearFrom 之后到落定前锁定 APPEAR 参数（中途的内容刷新不许把它换成 COLLAPSE 弹簧）。 */
    private boolean mAppearLock;
    /** appearFrom 的内容门槛：按「离目标还剩多少」算进度，≥ 0.8（现有 CONTENT_GATE）才淡入内容。 */
    private boolean mMorphGateOn;
    private float mMorphW0, mMorphH0;
    /** ISLAND_BULGE：吸收后鼓一下（scaleX +6% / scaleY −8% 脉冲，静止于 0），只叠在绘制上，不碰 IslandGeometry。 */
    private final IslandGeometry.Spring mBulgeX = com.onesay.assistant.v2.ui.motion.MotionSpec.ISLAND_BULGE.spring(0f);
    private final IslandGeometry.Spring mBulgeY = com.onesay.assistant.v2.ui.motion.MotionSpec.ISLAND_BULGE.spring(0f);
    private final Runnable mMorphRelease = new Runnable() {
        @Override
        public void run() {
            if (mMorphHoldUntil == 0L) return;
            android.util.Log.i("Island", "[ISLAND-MORPH] 等接触超时 → 岛自己从种子长出来");
            mMorphHoldUntil = 0L;
            retarget();
        }
    };
    /** 新内容从 0.94 放大到 1（只在换层时）。 */
    private boolean mScaleIn;
    private IslandGeometry.Motion mMotion = IslandGeometry.Motion.NONE;
    /** 固定窗口模式（touchable region 可用）：活动期间窗口不挪、不变大小。 */
    private final boolean mFixedWin;
    private final TouchRegion mTouch;
    private final Rect mTouchRect = new Rect();
    private final int[] mLoc = new int[2];
    private android.graphics.LinearGradient mShimmer;
    private float mShimmerW = -1f;
    private final android.graphics.Matrix mShimmerM = new android.graphics.Matrix();
    private final java.util.IdentityHashMap<IslandModel.Snapshot, float[]> mMeasureCache =
            new java.util.IdentityHashMap<IslandModel.Snapshot, float[]>();
    private final java.util.LinkedHashMap<String, StaticLayout> mLayouts = new java.util.LinkedHashMap<String, StaticLayout>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, StaticLayout> e) {
            return size() > 48;
        }
    };

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pWord, pTime, pCode, pLabel, pTtl, pTtl17, pSub, pStep, pStepCur, pStepS, pBtn, pQuote, pPCode,
            pSt, pMeta, pPgn, pSmall, pNum, pHi;
    private final Path mPath = new Path();
    private final RectF mTmp = new RectF();
    private final List<Hit> mHits = new ArrayList<Hit>();
    private final RectF mIslandRect = new RectF();
    private final RectF mLeftRect = new RectF();
    private final RectF mRightRect = new RectF();
    private final SimpleDateFormat mHm = new SimpleDateFormat("HH:mm", Locale.CHINA);

    private float mDownX, mDownY;
    private long mDownAt;
    private boolean mLongFired, mSwipeFired;
    private String mDownKey;
    private final Runnable mLongPress = new Runnable() {
        @Override
        public void run() {
            mLongFired = true;
            if (mListener != null && mCur != null && !mCur.isExpanded()) mListener.onLongPress(mDownKey);
        }
    };

    private static final class Hit {
        final RectF r;
        final String act;
        final boolean enabled;

        Hit(RectF r, String act, boolean enabled) {
            this.r = r;
            this.act = act;
            this.enabled = enabled;
        }
    }

    public IslandView(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        mW = new IslandGeometry.Spring(0f, IslandGeometry.SPRING_OMEGA, IslandGeometry.SPRING_ZETA);
        mH = new IslandGeometry.Spring(0f, IslandGeometry.SPRING_OMEGA, IslandGeometry.SPRING_ZETA);
        mR = new IslandGeometry.Spring(0f, IslandGeometry.SPRING_OMEGA, IslandGeometry.SPRING_ZETA);
        mSplit = new IslandGeometry.Spring(0f, IslandGeometry.SPRING_OMEGA, IslandGeometry.SPRING_ZETA);
        Typeface med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        Typeface bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        pWord = tp(13, ON1, med, false);
        pTime = tp(12, ON2, med, true);
        pCode = tp(13, ON1, bold, true);
        pLabel = tp(12, ON2, med, false);
        pTtl = tp(16, ON1, bold, false);
        pTtl17 = tp(17, ON1, bold, false);
        pSub = tp(12, ON3, null, false);
        pStep = tp(13, ON3, null, false);
        pStepCur = tp(13, ON1, med, false);
        pStepS = tp(11, ON3, null, false);
        pBtn = tp(14, ON1, med, false);
        pQuote = tp(13, ON2, null, false);
        pPCode = tp(32, ON1, bold, true);
        pSt = tp(14, ON1, med, false);
        pMeta = tp(12, ON3, null, false);
        pPgn = tp(12, ON2, med, true);
        pSmall = tp(11, ON3, null, false);
        pNum = tp(9, ON1, bold, true);
        pHi = tp(13, ACC, med, false);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeCap(Paint.Cap.ROUND);
        mStroke.setStrokeJoin(Paint.Join.ROUND);
        setContentDescription("OneSay 灵动岛");
        mTouch = TouchRegion.create(this);
        mFixedWin = mTouch != null;
    }

    /** 固定窗口模式（R11）：true = 窗口全宽、活动期间不挪；false = 旧的「形变时变大、静止时包住岛」兜底。 */
    public boolean fixedWindowMode() {
        return mFixedWin;
    }

    /** 当前触摸区域（窗口坐标；调试用）。 */
    public Rect touchRect() {
        return new Rect(mTouchRect);
    }

    /**
     * ★ R11：把触摸区域限定在岛的形状上（隐藏 API ViewTreeObserver.OnComputeInternalInsetsListener，Android 8 可反射）。
     * 全宽固定窗口里形状以外的地方全部透传给下面的 App；形状外的点按仍会以 ACTION_OUTSIDE 送来（展开态点外部收起）。
     */
    static final class TouchRegion implements java.lang.reflect.InvocationHandler {
        private final IslandView v;
        private final Object proxy;
        private final java.lang.reflect.Method setTouchable;
        private final java.lang.reflect.Field regionField;

        private TouchRegion(IslandView v, Object proxy, java.lang.reflect.Method setTouchable, java.lang.reflect.Field regionField) {
            this.v = v;
            this.proxy = proxy;
            this.setTouchable = setTouchable;
            this.regionField = regionField;
        }

        static TouchRegion create(IslandView v) {
            try {
                Class<?> lis = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
                Class<?> info = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
                java.lang.reflect.Method st = info.getMethod("setTouchableInsets", int.class);
                java.lang.reflect.Field rf = info.getField("touchableRegion");
                android.view.ViewTreeObserver.class.getMethod("addOnComputeInternalInsetsListener", lis);
                final TouchRegion[] holder = new TouchRegion[1];
                Object px = java.lang.reflect.Proxy.newProxyInstance(lis.getClassLoader(), new Class<?>[] {lis},
                        new java.lang.reflect.InvocationHandler() {
                            @Override public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) throws Throwable {
                                return holder[0] == null ? null : holder[0].invoke(p, m, a);
                            }
                        });
                holder[0] = new TouchRegion(v, px, st, rf);
                return holder[0];
            } catch (Throwable t) {
                android.util.Log.w("Island", "[ISLAND-TOUCH] touchable region 不可用 → 回退到「窗口包住岛」: " + t);
                return null;
            }
        }

        void attach() {
            try {
                Class<?> lis = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
                android.view.ViewTreeObserver.class.getMethod("addOnComputeInternalInsetsListener", lis).invoke(v.getViewTreeObserver(), proxy);
            } catch (Throwable t) {
                android.util.Log.w("Island", "[ISLAND-TOUCH] 注册失败: " + t);
            }
        }

        void detach() {
            try {
                Class<?> lis = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
                android.view.ViewTreeObserver.class.getMethod("removeOnComputeInternalInsetsListener", lis).invoke(v.getViewTreeObserver(), proxy);
            } catch (Throwable ignored) {
            }
        }

        @Override public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) throws Throwable {
            String n = m.getName();
            if ("onComputeInternalInsets".equals(n) && a != null && a.length == 1) {
                setTouchable.invoke(a[0], 3);   // TOUCHABLE_INSETS_REGION
                android.graphics.Region r = (android.graphics.Region) regionField.get(a[0]);
                Rect t = v.mTouchRect;
                if (t.isEmpty()) r.setEmpty();
                else r.set(t);
                return null;
            }
            if ("equals".equals(n)) return p == (a == null ? null : a[0]);
            if ("hashCode".equals(n)) return System.identityHashCode(p);
            if ("toString".equals(n)) return "IslandTouchRegion";
            return null;
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mTouch != null) mTouch.attach();
    }

    @Override protected void onDetachedFromWindow() {
        if (mTouch != null) mTouch.detach();
        super.onDetachedFromWindow();
    }

    private TextPaint tp(float sizeDp, int color, Typeface tf, boolean tabular) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(sizeDp * d);
        p.setColor(color);
        if (tf != null) p.setTypeface(tf);
        if (tabular) p.setFontFeatureSettings("tnum");
        return p;
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public void setHost(Host h) {
        mHost = h;
    }

    public void setAnchor(IslandGeometry.Anchor a, int screenW, int screenH) {
        mAnchor = a;
        mScreenW = screenW;
        mScreenH = screenH;
        mWinReq.setEmpty();
        retarget();
    }

    public void windowDropped() {
        mWinReq.setEmpty();
    }

    public IslandGeometry.Anchor anchor() {
        return mAnchor;
    }

    public void setWindowOrigin(int l, int t) {
        mWinL = l;
        mWinT = t;
        invalidate();
    }

    public void setSnapshot(IslandModel.Snapshot s) {
        if (mHoldUntil > SystemClock.uptimeMillis()) {   // ★ R13 回程冻结：先记下，舞台接手后再执行
            mHeldSnap = s;
            return;
        }
        IslandModel.Snapshot old = mCur;
        if (old != null && s != null && layerKey(old) == layerKey(s)) {
            // ★ R11：同一层的内容变了（步骤文字 / 结果摘要）→ 旧字 ~120 ms 淡出、新字淡入，不再一帧硬切；内容没变 → 什么都不动（不重启动画）
            if (contentSig(old) != contentSig(s) && mCurA.v > 0.5f) {
                mPrev = old;
                mPrevA.v = mCurA.v;
                mPrevA.vel = 0f;
                mPrevA.t = 0f;
                mPrevH = mH.v;
                mCurA.v = 0f;
                mCurA.vel = 0f;
                mScaleIn = false;
            }
            mCur = s;
            if (contentSig(old) == contentSig(s) && sizeOf(old) == sizeOf(s)) {
                // 同一层、同样内容：只换引用（noteHide / 重复渲染），不 retarget → 不触发任何形变 / 窗口请求（「自己抖」的来源之一）
                mMeasureCache.remove(old);
                invalidate();
                return;
            }
        } else {
            if (old != null && layerKey(old) != 0) {
                mPrev = old;
                mPrevA.v = mCurA.v;
                mPrevA.vel = 0f;
                mPrevA.t = 0f;
                mPrevH = mH.v;
            }
            mCur = s;
            mCurA.v = 0f;
            mCurA.vel = 0f;
            mGateArmed = true;
            mScaleIn = old != null && layerKey(old) != 0;
        }
        retarget();
    }

    /** 内容签名：只含会画出来的文字 / 状态（不含时间戳）。 */
    static int contentSig(IslandModel.Snapshot s) {
        if (s == null) return 0;
        int h = s.kind.ordinal();
        h = h * 31 + (s.status == null ? 0 : s.status.ordinal());
        h = h * 31 + str(s.title);
        h = h * 31 + str(s.pulseText);
        h = h * 31 + str(s.alertText);
        h = h * 31 + s.stepNo;
        h = h * 31 + (s.steps == null ? 0 : s.steps.hashCode());
        h = h * 31 + str(s.noteDetail);
        h = h * 31 + str(s.noteMeta);
        h = h * 31 + (s.noteActions == null ? 0 : s.noteActions.hashCode());
        h = h * 31 + str(s.noteThumb) + (s.noteThumb != null && thumbFor(s.noteThumb) != null ? 1 : 0);   // ★ feat/suggest
        h = h * 31 + str(s.confirmTitle) + str(s.confirmDetail) + str(s.failReason);
        h = h * 31 + (s.pickup == null ? 0 : str(s.pickup.code));
        h = h * 31 + s.todoCount + (s.todos == null ? 0 : s.todos.size());
        h = h * 31 + str(s.bubbleText) + (s.bubbleStatus == null ? 0 : s.bubbleStatus.ordinal());
        h = h * 31 + (s.notices == null ? 0 : s.notices.hashCode()) + str(s.noticeFocus);
        // ★ arch/island-progress：子目标进度 / ask_user
        h = h * 31 + str(s.progressText) + str(s.progressCurrent) + (s.progressVisible ? 1 : 0) + (s.progressFailed ? 2 : 0);
        h = h * 31 + (s.subgoals == null ? 0 : s.subgoals.hashCode());
        h = h * 31 + str(s.askId) + str(s.askQuestion) + (s.askOptions == null ? 0 : s.askOptions.hashCode());
        return h;
    }

    /** 岛上确认卡明细最多 5 行（原 3 行），收件人 / 金额 / 网址排最前，不被挤掉。 */
    static final int CONFIRM_DETAIL_LINES = 5;

    private static int str(String x) {
        return x == null ? 0 : x.hashCode();
    }

    private int sizeOf(IslandModel.Snapshot s) {
        if (s == null || mAnchor == null) return 0;
        float[] m = measure(s);
        return Math.round(m[0]) * 4099 + Math.round(m[1]);
    }

    public IslandModel.Snapshot snapshot() {
        return mCur;
    }

    public boolean targetHidden() {
        return mCur == null || mCur.kind == IslandModel.Kind.HIDDEN;
    }

    private static int layerKey(IslandModel.Snapshot s) {
        if (s == null || s.kind == IslandModel.Kind.HIDDEN) return 0;
        return 1 + s.kind.ordinal() * 31 + (s.key == null ? 0 : s.key.hashCode());
    }

    /** 当前形状的屏幕矩形（调试 geom 用）。 */
    public Rect shapeOnScreen() {
        return new Rect(Math.round(mIslandRect.left + mWinL), Math.round(mIslandRect.top + mWinT),
                Math.round(mIslandRect.right + mWinL), Math.round(mIslandRect.bottom + mWinT));
    }

    // ------------------------------------------------------------------ 目标与窗口

    private void retarget() {
        if (mAnchor == null) return;
        IslandModel.Snapshot s = mCur;
        float tw, th, tr;
        boolean hide = s == null || s.kind == IslandModel.Kind.HIDDEN;
        if (hide) {
            tw = th = tr = 0f;
        } else {
            float[] sz = measure(s);
            tw = sz[0];
            th = sz[1];
            tr = sz[2];
        }
        boolean visibleNow = mW.v >= 1f && mShapeA.v > 0.02f;
        boolean hold = !hide && mMorphHoldUntil > SystemClock.uptimeMillis() && !visibleNow;
        if (hold) {   // ★ R13：卡片在飞来 → 先只出一颗种子（APPEAR 的原生起点），不显示内容
            tw = th = IslandGeometry.COMPACT_H_DP * d;
            tr = tw / 2f;
        }
        if (!visibleNow || hide) mAppearLock = false;
        mMotion = mAppearLock ? IslandGeometry.Motion.APPEAR : IslandGeometry.motionFor(visibleNow ? mW.v : 0f, visibleNow ? mH.v : 0f, tw, th);
        float[] sp = IslandGeometry.springFor(mMotion);
        mW.setParams(sp[0], sp[1]);
        mH.setParams(sp[0], sp[1]);
        mR.setParams(sp[0], sp[1]);
        mSplit.setParams(sp[0], sp[1]);
        // 面板收成岛 / 出现走 APPEAR：门槛与参数和 R10 完全一样（用户表扬过，不动）；其余形变用 R11 的 0.6 门槛
        mGate = mMotion == IslandGeometry.Motion.APPEAR ? IslandGeometry.CONTENT_GATE : IslandGeometry.CONTENT_GATE_R11;
        if (hide) {
            // ★ R11：缩回一颗小药丸再淡出（不缩成一个点）；内容先淡掉
            float pill = IslandGeometry.HIDE_PILL_DP * d;
            mW.t = mW.v < 1f ? 0f : pill;
            mH.t = mH.v < 1f ? 0f : pill;
            mR.t = pill / 2f;
            mCurA.t = 0f;
            mSplit.t = 0f;
            mShapeA.t = 0f;
        } else {
            mW.t = tw;
            mH.t = th;
            mR.t = tr;
            mCurA.t = hold ? 0f : 1f;
            mSplit.t = s.kind == IslandModel.Kind.MINIMAL_DUAL && !hold ? 1f : 0f;
            mShapeA.t = 1f;
        }
        if (!hide && (mW.v < 1f || mShapeA.v <= 0.02f)) {   // 从隐藏出现：从小胶囊长出来（R10 原样）
            mW.v = Math.min(mW.t, IslandGeometry.COMPACT_H_DP * d);
            mH.v = Math.min(mH.t, IslandGeometry.COMPACT_H_DP * d);
            mR.v = mH.v / 2f;
            mW.vel = mH.vel = mR.vel = 0f;
            mShapeA.v = 1f;
            mShapeA.vel = 0f;
        }
        if (mFixedWin) {
            requestFixedWindow();
            mSettledReported = false;
            mLastFrame = 0L;
            postInvalidateOnAnimation();
            return;
        }
        Rect env = new Rect();
        shapeRect(env, mW.v, mH.v);
        Rect tgt = new Rect();
        shapeRect(tgt, mW.t, mH.t);
        env.union(tgt);
        env.inset(-Math.round(6 * d), -Math.round(6 * d));
        clampRect(env);
        if (mHost != null && !env.equals(mWinReq)) {
            mWinReq.set(env);
            mHost.requestWindow(new Rect(env));
        }
        mSettledReported = false;
        mLastFrame = 0L;
        postInvalidateOnAnimation();
    }

    /** 固定窗口：只在锚点 / 屏幕尺寸变了时请求一次。 */
    private void requestFixedWindow() {
        if (mHost == null || mAnchor == null) return;
        int[] f = IslandGeometry.fixedWindow(mScreenW > 0 ? mScreenW : Math.round(360 * d), mScreenH > 0 ? mScreenH : Math.round(800 * d),
                mAnchor.top, d);
        Rect r = new Rect(f[0], f[1], f[2], f[3]);
        if (!r.equals(mWinReq)) {
            mWinReq.set(r);
            mHost.requestWindow(new Rect(r));
        }
    }

    private void clampRect(Rect r) {
        if (r.top < 0) r.top = 0;
        if (r.left < 0) r.left = 0;
        if (mScreenW > 0 && r.right > mScreenW) r.right = mScreenW;
        if (r.width() < 1) r.right = r.left + 1;
        if (r.height() < 1) r.bottom = r.top + 1;
    }

    private void shapeRect(Rect out, float w, float h) {
        float cx = mAnchor.cx;
        float top = mAnchor.top;
        out.set((int) Math.floor(cx - w / 2f), (int) Math.floor(top), (int) Math.ceil(cx + w / 2f), (int) Math.ceil(top + h));
    }

    // ------------------------------------------------------------------ ★ borrow-1112：控屏状态描边

    /** 0 = 不描边；否则 ARGB（{@link com.onesay.assistant.v2.exec.ControlColor#islandRing}）。 */
    private static volatile int sControlRing;

    public static void setControlRing(int argb) {
        sControlRing = argb;
    }

    public static int controlRing() {
        return sControlRing;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas c) {
        if (mAnchor == null) return;
        long now = SystemClock.uptimeMillis();
        float dt = mLastFrame == 0L ? 0.016f : Math.min(0.033f, (now - mLastFrame) / 1000f);
        mLastFrame = now;
        stepAll(dt);
        // ★ R11：用这一帧真实的窗口位置画（窗口挪动是异步的；按「请求的」原点画会先错位一帧 = 抖）
        try {
            getLocationOnScreen(mLoc);
            if (getParent() != null) {
                mWinL = mLoc[0];
                mWinT = mLoc[1];
            }
        } catch (Throwable ignored) {
        }
        float shapeA = clamp01(mShapeA.v);
        float press = mPress.v;

        float w = Math.max(0f, mW.v), h = Math.max(0f, mH.v);
        float r = Math.min(mR.v, Math.min(w, h) / 2f);
        float cx = mAnchor.cx - mWinL;
        float top = mAnchor.top - mWinT;
        mIslandRect.set(cx - w / 2f, top, cx + w / 2f, top + h);
        mHits.clear();

        float split = clamp01(mSplit.v);
        c.save();
        if (Math.abs(press - 1f) > 0.001f) c.scale(press, press, mIslandRect.centerX(), mIslandRect.top + h / 2f);
        if (Math.abs(mBulgeX.v) > 0.0005f || Math.abs(mBulgeY.v) > 0.0005f) {   // ★ R13 ISLAND_BULGE（只缩放绘制）
            c.scale(1f + mBulgeX.v, 1f + mBulgeY.v, mIslandRect.centerX(), mIslandRect.top + h / 2f);
        }
        updateTouchRect();
        mFill.setColor(INK);
        mFill.setAlpha(Math.round(255 * shapeA));
        if (w > 0.5f && h > 0.5f) {
            if (split > 0.01f) {
                float gap = IslandGeometry.DUAL_GAP_DP * d * split;
                float total = IslandGeometry.MIN_TASK_DP + IslandGeometry.MIN_CODE_W_DP;
                float wa = (w - gap) * (IslandGeometry.MIN_TASK_DP / total);
                mLeftRect.set(mIslandRect.left, top, mIslandRect.left + wa, top + h);
                mRightRect.set(mLeftRect.right + gap, top, mIslandRect.right, top + h);
                c.drawRoundRect(mLeftRect, h / 2f, h / 2f, mFill);
                c.drawRoundRect(mRightRect, h / 2f, h / 2f, mFill);
            } else {
                mLeftRect.setEmpty();
                mRightRect.setEmpty();
                c.drawRoundRect(mIslandRect, r, r, mFill);
            }
        }

        int ring = sControlRing;   // ★ borrow-1112：执行中按「谁在控屏」描一圈边（助手 / 等你 / 你接手）
        if (ring != 0 && w > 0.5f && h > 0.5f && split <= 0.01f && mCur != null && mCur.exec) {
            mStroke.setColor(ring);
            mStroke.setStrokeWidth(1.6f * d);
            mStroke.setAlpha(Math.round(230 * shapeA));
            c.drawRoundRect(mIslandRect, r, r, mStroke);
        }

        c.save();
        mPath.reset();
        mPath.addRoundRect(mIslandRect, r, r, Path.Direction.CW);
        c.clipPath(mPath);
        float pa = clamp01(mPrevA.v) * shapeA;
        if (mPrev != null && pa > 0.01f) {
            float pw = measure(mPrev)[0];
            render(c, mPrev, mIslandRect.centerX() - pw / 2f, mIslandRect.top, pw, (int) (pa * 255), false, now);
        } else if (clamp01(mPrevA.v) <= 0.01f) {
            if (mPrev != null) mMeasureCache.remove(mPrev);
            mPrev = null;
        }
        float ca = clamp01(mCurA.v) * shapeA;
        if (mCur != null && mCur.kind != IslandModel.Kind.HIDDEN && ca > 0.01f) {
            float cw = measure(mCur)[0];
            boolean sc = mScaleIn && ca < 0.995f;
            if (sc) {
                float k = IslandGeometry.CONTENT_SCALE_FROM + (1f - IslandGeometry.CONTENT_SCALE_FROM) * ca;
                c.save();
                c.scale(k, k, mIslandRect.centerX(), mIslandRect.top + Math.min(h, IslandGeometry.COMPACT_H_DP * d) / 2f);
            }
            render(c, mCur, mIslandRect.centerX() - cw / 2f, mIslandRect.top, cw, (int) (ca * 255), mCurA.t == 1f, now);
            if (sc) c.restore();
        }
        c.restore();
        c.restore();

        boolean moving = !(mW.settled(0.3f) && mH.settled(0.3f) && mR.settled(0.3f) && mSplit.settled(0.01f)
                && mCurA.settled(0.01f) && mPrevA.settled(0.01f) && mShapeA.settled(0.01f) && mPress.settled(0.002f)
                && mBulgeX.settled(0.0005f) && mBulgeY.settled(0.0005f));
        if (!moving) mAppearLock = false;
        if (moving) {
            invalidateIsland(true);
            return;
        }
        if (!mSettledReported) {
            mSettledReported = true;
            onSettled();
            invalidateIsland(true);   // 再跑一帧 traversal，让 touchable region 用上最终形状
            return;
        }
        if (!targetHidden() && needsTicker()) {
            if (mCur != null && mCur.kind == IslandModel.Kind.COMPACT_NOTE && mCur.status == IslandModel.Status.RUN) invalidateIsland(true);
            else invalidateIsland(false);
        } else if (!targetHidden() && IslandModel.noticeChronoShown(mCur)) {
            //：通话时长 / 计时器——每秒重画一次就够（不走 50 ms 的状态点节拍，省电）
            postInvalidateDelayed(1000L);
        }
    }

    /** 只重画岛附近（当前形状 ∪ 目标形状，外扩 12dp）；frame = 下一帧，否则 50 ms 后（状态点 / 步骤环）。 */
    private void invalidateIsland(boolean frame) {
        if (mAnchor == null) {
            if (frame) postInvalidateOnAnimation();
            else postInvalidateDelayed(50L);
            return;
        }
        float w = Math.max(Math.max(0f, mW.v), mW.t) * 1.05f, h = Math.max(Math.max(0f, mH.v), mH.t) * 1.05f;
        float cx = mAnchor.cx - mWinL, top = mAnchor.top - mWinT;
        int m = Math.round(12 * d);
        int l = (int) Math.floor(cx - w / 2f) - m, t = (int) Math.floor(top) - m, r = (int) Math.ceil(cx + w / 2f) + m,
                b = (int) Math.ceil(top + h) + m;
        if (frame) postInvalidateOnAnimation(l, t, r, b);
        else postInvalidateDelayed(50L, l, t, r, b);
    }

    /** 触摸区域 = 当前形状（窗口坐标，外扩 4dp）；隐藏 / 正在消失 → 空（全部透传）。 */
    private void updateTouchRect() {
        if (!mFixedWin) return;
        if (targetHidden() || mShapeA.v < 0.5f || mW.v < 1f) {
            mTouchRect.setEmpty();
            return;
        }
        int m = Math.round(4 * d);
        mTouchRect.set((int) Math.floor(mIslandRect.left) - m, (int) Math.floor(mIslandRect.top) - m,
                (int) Math.ceil(mIslandRect.right) + m, (int) Math.ceil(mIslandRect.bottom) + m);
    }

    private boolean needsTicker() {
        if (mCur == null) return false;
        return mCur.status == IslandModel.Status.RUN || mCur.status == IslandModel.Status.WAIT
                || mCur.kind == IslandModel.Kind.MINIMAL_DUAL;
    }

    private void onSettled() {
        if (mHost == null) return;
        if (targetHidden()) {
            // 下次出现走 APPEAR（与 R10 一样从小胶囊长出来）
            mW.v = mH.v = mR.v = 0f;
            mW.vel = mH.vel = mR.vel = 0f;
            mShapeA.v = 0f;
            mTouchRect.setEmpty();
            mWinReq.setEmpty();
            mMeasureCache.clear();
            mHost.onHiddenSettled();
            return;
        }
        if (mFixedWin) return;   // 固定窗口：静止时不收窗口（收窗口 = 挪位置 = 抖）
        Rect tgt = new Rect();
        shapeRect(tgt, mW.t, mH.t);
        tgt.inset(-Math.round(2 * d), -Math.round(2 * d));
        clampRect(tgt);
        if (!tgt.equals(mWinReq)) {
            mWinReq.set(tgt);
            mHost.requestWindow(new Rect(tgt));
        }
    }

    private void stepAll(float dt) {
        mW.step(dt);
        mH.step(dt);
        mR.step(dt);
        mSplit.step(dt);
        mPrevA.step(dt);
        mCurA.step(dt);
        mShapeA.step(dt);
        mPress.step(dt);
        mBulgeX.step(dt);
        mBulgeY.step(dt);
        if (mMorphGateOn && mCurA.t == 1f) {
            if (morphProgress() < IslandGeometry.CONTENT_GATE) {
                mCurA.v = 0f;
                mCurA.vel = 0f;
            } else {
                mMorphGateOn = false;
                mGateArmed = false;
            }
        } else if (mCurA.t == 1f && mGateArmed) {
            if (!IslandGeometry.contentGateOpen(mW.v, mW.t, mH.v, mH.t, mGate)) {
                mCurA.v = 0f;
                mCurA.vel = 0f;
            } else {
                mGateArmed = false;   // 门开过一次就不再拦（之后同层的小幅长高不会把内容闪成 0）
            }
        }
        if (mPrev != null && mPrevA.v > 0.01f && mPrevH > mH.v * 1.4f) mPrevA.v *= 0.6f;
    }

    // ------------------------------------------------------------------ 布局 + 绘制（c == null 时只量尺寸）

    /** 返回 {宽, 高, 圆角}（像素）。 */
    float[] measure(IslandModel.Snapshot s) {
        float[] c = mMeasureCache.get(s);
        if (c != null) return c;
        c = measure0(s);
        if (mMeasureCache.size() > 8) mMeasureCache.clear();
        mMeasureCache.put(s, c);
        return c;
    }

    /** 量尺寸（展开卡要排版文字，结果按快照缓存：R11 之前每帧都重新排版 2–4 次 → Note 8 上掉帧）。 */
    private float[] measure0(IslandModel.Snapshot s) {
        float ch = IslandGeometry.COMPACT_H_DP * d;
        switch (s.kind) {
            case COMPACT_TASK:
            case COMPACT_PICKUP:
            case COMPACT_REMIND:
                return new float[] {IslandGeometry.compactWidth(compactContent(s), d), ch, ch / 2f};
            case COMPACT_NOTE:
                return new float[] {IslandGeometry.compactWidth(12f * d + 6f * d + pWord.measureText(noteWord(s)), d), ch, ch / 2f};
            case COMPACT_NOTICE: {
                float maxW = Math.max(IslandGeometry.MIN_CODE_W_DP * d, Math.min(220f * d, (mScreenW > 0 ? mScreenW : 360f * d) - 48f * d));
                IslandModel.NoticeRow top = s.notices != null && !s.notices.isEmpty() ? s.notices.get(0) : null;
                float chronoW = top != null && top.chronoBase > 0 ? pTime.measureText("00:00:00") + 8f * d : 0f;
                float want = 12f * d + 6f * d + pWord.measureText(s.title == null ? "" : s.title) + chronoW + noticeBadgeW(s);
                return new float[] {Math.min(maxW, IslandGeometry.compactWidth(want, d)), ch, ch / 2f};
            }
            case COMPACT_TODO: {
                float w = Math.max(IslandGeometry.MIN_CODE_W_DP * d, 9f * d + 12f * d + 6f * d
                        + pCode.measureText(String.valueOf(s.todoCount)) + 10f * d);
                return new float[] {w, ch, ch / 2f};
            }
            case MINIMAL_DUAL:
                return new float[] {(IslandGeometry.MIN_TASK_DP + IslandGeometry.DUAL_GAP_DP + IslandGeometry.MIN_CODE_W_DP) * d,
                        ch, ch / 2f};
            case PULSE_STEP:
                return new float[] {Math.min(IslandGeometry.PULSE_W_DP * d, Math.max(1f, mScreenW - 24f * d)),
                        IslandGeometry.PULSE_H_DP * d, IslandGeometry.PULSE_R_DP * d};
            case HIDDEN:
                return new float[] {0f, 0f, 0f};
            default: {
                float w = IslandGeometry.expandedWidth(mScreenW > 0 ? mScreenW : Math.round(360 * d), d);
                float h = render(null, s, 0f, 0f, w, 255, false, 0L);
                float max = IslandGeometry.expandedMaxHeight(mScreenH > 0 ? mScreenH : Math.round(800 * d), mAnchor.top);
                if (max > 0f) h = Math.min(h, max);
                return new float[] {w, h, IslandGeometry.EXP_R_DP * d};
            }
        }
    }

    /** ★ 11-04 ①：放不下就按词缩短（{@link IslandStepText#fit}），不加省略号。 */
    static String fitWidth(String text, Paint p, float avail) {
        String t = text == null ? "" : text;
        if (p.measureText(t) <= avail) return t;
        for (double u = Math.ceil(IslandStepText.units(t)) - 0.5; u >= 2; u -= 0.5) {
            String f = IslandStepText.fit(t, u);
            if (p.measureText(f) <= avail) return f;
        }
        return IslandStepText.fit(t, 2);
    }

    // ------------------------------------------------------------------ ★ 11-04 午 进度反馈（思考中 / 执行中：步骤 · 计时 · 第几步）

    /** 前台任务运行中（不是执行模式 / 不是在等用户选）→ 显示进度反馈。 */
    static boolean progressLive(IslandModel.Snapshot s) {
        return s != null && s.act == IslandModel.Act.TASK && s.status == IslandModel.Status.RUN && !s.exec && s.askId == null;
    }

    /** 还没有任何真实步骤时也按「思考中」显示（循环在拼提示词 / 等第一轮模型）。 */
    static ProgressFeedback.Phase progressPhase(IslandModel.Snapshot s) {
        return s.thinking || s.stepNo <= 0 ? ProgressFeedback.Phase.THINKING : ProgressFeedback.Phase.WORKING;
    }

    static String lastRealStep(IslandModel.Snapshot s) {
        return s.stepNo > 0 && s.steps != null && !s.steps.isEmpty() ? s.steps.get(s.steps.size() - 1) : null;
    }

    static long sincePhase(IslandModel.Snapshot s, long now) {
        long at = s.phaseAt > 0 ? s.phaseAt : s.startedAt;
        return at > 0 ? now - at : 0L;
    }

    /** 主行（到 8 s 没新步骤 → 真实状态补句）。 */
    static String progressWord(IslandModel.Snapshot s, long now) {
        return ProgressFeedback.line(progressPhase(s), lastRealStep(s), sincePhase(s, now));
    }

    /** 右侧「2/4 · 12 s」（紧凑态只要计时，传 withCounter=false）。 */
    static String progressMeta(IslandModel.Snapshot s, long now, boolean withCounter) {
        long el = s.startedAt > 0 ? now - s.startedAt : 0L;
        if (!withCounter) return ProgressFeedback.elapsed(el);
        int n = s.progressTotal >= 2 ? s.progressIndex : s.stepNo;
        return ProgressFeedback.meta(n, s.progressTotal, el);
    }

    private String compactWord(IslandModel.Snapshot s) {
        if (s.kind == IslandModel.Kind.COMPACT_REMIND) return "提醒";
        if (s.exec && s.paused) return "已暂停";
        switch (s.status) {
            case WAIT: return s.askId != null ? "待你选择" : "待确认";
            case DONE: return "已完成";
            case FAIL: return "没完成";
            default:
                if (progressLive(s)) return progressWord(s, System.currentTimeMillis());   // ★ 11-04 午：思考中 / 执行中：步骤 / 真实状态补句
                return IslandModel.compactActionWord(s);   // ★ arch/island-progress：有账本 → 当前子目标
        }
    }

    private float compactContent(IslandModel.Snapshot s) {
        if (s.kind == IslandModel.Kind.COMPACT_PICKUP) {
            return 16f * d + 8f * d + (s.pickup == null ? 0f : pCode.measureText(s.pickup.code));
        }
        if (s.kind == IslandModel.Kind.COMPACT_REMIND) {
            return 12f * d + 6f * d + pWord.measureText("提醒") + 10f * d + pTime.measureText("00:00");
        }
        // ★ arch/island-progress：右侧不再是步数 —— 运行中：有账本 = 进度点，否则转动的环（无数字）；失败：有账本 =「i/n」，否则空
        if (progressLive(s)) {
            // ★ 11-04 午：宽度按「主行 / 8 s 补句」两者较宽的量（快照缓存了尺寸，补句出现时岛不跳）；右边 = 进度点（有账本）+ 计时
            ProgressFeedback.Phase ph = progressPhase(s);
            String st = lastRealStep(s);
            float ww = Math.max(pWord.measureText(ProgressFeedback.headline(ph, st)),
                    pWord.measureText(ProgressFeedback.line(ph, st, Long.MAX_VALUE / 4)));
            float rr = pTime.measureText("00 s") + (s.progressTotal >= 2 ? progressDotsWidth(s.progressTotal) + 6f * d : 0f);
            return 12f * d + 6f * d + ww + IslandGeometry.COMPACT_GROUP_GAP_DP * d + rr;
        }
        float right = s.status == IslandModel.Status.DONE ? pTime.measureText("0:00")
                : s.status == IslandModel.Status.FAIL ? pTime.measureText(IslandModel.compactFailRight(s))
                : s.progressTotal >= 2 ? progressDotsWidth(s.progressTotal) : 18f * d;
        return 12f * d + 6f * d + (s.exec ? 22f * d : 0f) + pWord.measureText(compactWord(s)) + IslandGeometry.COMPACT_GROUP_GAP_DP * d + right;
    }

    // ------------------------------------------------------------------ ★ r2/fast-screen 执行模式：红点 + App 图标

    private final java.util.HashMap<String, android.graphics.Bitmap> mIcons = new java.util.HashMap<String, android.graphics.Bitmap>();

    private android.graphics.Bitmap appIcon(String pkg) {
        if (pkg == null || pkg.length() == 0) return null;
        if (mIcons.containsKey(pkg)) return mIcons.get(pkg);
        android.graphics.Bitmap bm = null;
        try {
            android.graphics.drawable.Drawable dr = getContext().getPackageManager().getApplicationIcon(pkg);
            int px = Math.max(8, Math.round(18f * d));
            bm = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            dr.setBounds(0, 0, px, px);
            dr.draw(cv);
        } catch (Throwable ignored) {
        }
        mIcons.put(pkg, bm);
        return bm;
    }

    /** 执行中的红点（录制感：实心红点 + 1 s 呼吸外圈）；暂停时是空心红圈。 */
    private void drawExecDot(Canvas c, float cx, float cy, boolean paused, int alpha, long now) {
        if (paused) {
            mStroke.setColor(RED);
            mStroke.setStrokeWidth(1.6f * d);
            mStroke.setAlpha(alpha);
            c.drawCircle(cx, cy, 4.5f * d, mStroke);
            return;
        }
        double e = 0.5 - 0.5 * Math.cos(2 * Math.PI * ((now % 1000L) / 1000.0));
        mFill.setColor(RED);
        mFill.setAlpha((int) (alpha * 0.35f * (1f - (float) e)));
        c.drawCircle(cx, cy, (4.5f + 3f * (float) e) * d, mFill);
        mFill.setAlpha(alpha);
        c.drawCircle(cx, cy, 4.5f * d, mFill);
    }

    private float render(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, boolean hits, long now) {
        switch (s.kind) {
            case COMPACT_TASK: return renderCompactTask(c, s, x, y, w, alpha, now);
            case COMPACT_PICKUP: return renderCompactPickup(c, s, x, y, w, alpha);
            case COMPACT_REMIND: return renderCompactRemind(c, s, x, y, w, alpha);
            case COMPACT_TODO: return renderCompactTodo(c, s, x, y, w, alpha);
            case MINIMAL_DUAL: return renderDual(c, s, alpha, now);
            case PULSE_STEP: return renderPulse(c, s, x, y, w, alpha, now);
            case ALERT_KEY:
            case EXP_RUN: return renderRun(c, s, x, y, w, alpha, hits, now);
            case EXP_CONFIRM: return renderConfirm(c, s, x, y, w, alpha, hits, now);
            case EXP_ASK: return renderAsk(c, s, x, y, w, alpha, hits, now);
            case EXP_FAIL: return renderFail(c, s, x, y, w, alpha, hits, now);
            case EXP_PICKUP: return renderPickup(c, s, x, y, w, alpha, hits);
            case EXP_REMIND: return renderRemind(c, s, x, y, w, alpha, hits, now);
            case EXP_TODO: return renderTodo(c, s, x, y, w, alpha, hits);
            case COMPACT_NOTE: return renderCompactNote(c, s, x, y, w, alpha, now);
            case EXP_NOTE: return renderNote(c, s, x, y, w, alpha, hits, now);
            case COMPACT_NOTICE: return renderCompactNotice(c, s, x, y, w, alpha);
            case EXP_NOTICE: return renderNotices(c, s, x, y, w, alpha, hits, now);
            default: return 0f;
        }
    }

    private float ch() {
        return IslandGeometry.COMPACT_H_DP * d;
    }

    private float renderCompactTask(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, long now) {
        if (c == null) return ch();
        float cy = y + ch() / 2f;
        float lx = x + IslandGeometry.COMPACT_PAD_L_DP * d;
        float rx = x + w - IslandGeometry.COMPACT_PAD_R_DP * d;
        float iconW = 0f;
        if (s.exec && s.status != IslandModel.Status.DONE && s.status != IslandModel.Status.FAIL) {
            drawExecDot(c, lx + 6f * d, cy, s.paused, alpha, now);
            android.graphics.Bitmap ic = appIcon(s.iconPkg);
            if (ic != null) {
                mFill.setAlpha(alpha);
                c.drawBitmap(ic, lx + 16f * d, cy - ic.getHeight() / 2f, mFill);
                iconW = 22f * d;
            }
        } else if (progressLive(s)) {
            // ★ 11-04 午：思考中 → 圆点呼吸（1.4 s 一周期）；执行中 → 原来的运行点
            drawDot(c, lx + 6f * d, cy, 12f * d, s.status, (int) (alpha * ProgressFeedback.breath(progressPhase(s), now)), now);
        } else drawDot(c, lx + 6f * d, cy, 12f * d, s.status, alpha, now);
        float rightW;
        if (progressLive(s)) {
            String tm = progressMeta(s, System.currentTimeMillis(), false);
            float tw = pTime.measureText("00 s");
            drawText(c, tm, rx - 3f * d, cy, pTime, alpha, true);
            rightW = tw;
            if (s.progressTotal >= 2) {
                float dw = progressDotsWidth(s.progressTotal);
                drawProgressDots(c, rx - tw - 6f * d - dw, cy, s, alpha, now);
                rightW += dw + 6f * d;
            }
        } else if (s.status == IslandModel.Status.DONE) {
            String t = IslandModel.fmtElapsed(s.elapsed(System.currentTimeMillis()));
            rightW = pTime.measureText(t);
            drawText(c, t, rx - 3f * d, cy, pTime, alpha, true);
        } else if (s.status == IslandModel.Status.FAIL) {
            String t = IslandModel.compactFailRight(s);   // ★ arch/island-progress：不再显示步数
            rightW = pTime.measureText(t);
            if (t.length() > 0) drawText(c, t, rx - 3f * d, cy, pTime, alpha, true);
        } else if (s.progressTotal >= 2) {
            rightW = progressDotsWidth(s.progressTotal);
            drawProgressDots(c, rx - rightW, cy, s, alpha, now);
        } else {
            rightW = 18f * d;
            drawRing(c, rx - 9f * d, cy, 9f * d, 0, alpha, now, s.status == IslandModel.Status.RUN);
        }
        float wordX = lx + 18f * d + iconW;
        float avail = Math.max(0f, rx - rightW - IslandGeometry.COMPACT_GROUP_GAP_DP * d - wordX);
        String t = fitWidth(compactWord(s), pWord, avail);   // ★ 11-04 ①：整词截断，不出省略号
        drawText(c, t, wordX, cy, pWord, alpha, false);
        return ch();
    }

    // ------------------------------------------------------------------ ★ R10 记一记

    private String noteWord(IslandModel.Snapshot s) {
        if (s.exec) return s.status == IslandModel.Status.FAIL ? "没完成" : "完成";   // ★ r2/fast-screen 执行完成
        if (s.status == IslandModel.Status.RUN) return s.title == null || s.title.length() == 0 ? "记录中" : s.title;
        if (s.status == IslandModel.Status.FAIL) return "没记";
        return "已记";
    }

    /** 紧凑「记录中」：绿点 + 文字，上面一道从左到右扫过的流光（1.2 s 一圈）。 */
    private float renderCompactNote(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, long now) {
        if (c == null) return ch();
        float cy = y + ch() / 2f;
        float lx = x + IslandGeometry.COMPACT_PAD_L_DP * d;
        mFill.setColor(s.status == IslandModel.Status.FAIL ? MUTE : ACC);
        mFill.setAlpha(alpha);
        c.drawCircle(lx + 6f * d, cy, 4.5f * d, mFill);
        drawText(c, noteWord(s), lx + 18f * d, cy, pWord, alpha, false);
        if (s.status == IslandModel.Status.RUN) {
            float t = (now % 1200L) / 1200f;
            float gw = w * 0.45f;
            float gx = x - gw + (w + gw) * t;
            if (mShimmer == null || Math.abs(mShimmerW - gw) > 0.5f) {   // R11：渐变只建一次，每帧只挪矩阵（不再每帧 new → GC 抖）
                mShimmer = new android.graphics.LinearGradient(0, 0, gw, 0,
                        new int[]{0x0084AE64, 0x7084AE64, 0x0084AE64}, null, android.graphics.Shader.TileMode.CLAMP);
                mShimmerW = gw;
            }
            mShimmerM.setTranslate(gx, 0);
            mShimmer.setLocalMatrix(mShimmerM);
            mFill.setShader(mShimmer);
            mFill.setAlpha(alpha);
            mTmp.set(x, y, x + w, y + ch());
            c.drawRoundRect(mTmp, ch() / 2f, ch() / 2f, mFill);
            mFill.setShader(null);
        }
        return ch();
    }

    // ------------------------------------------------------------------ ★ feat/suggest 缩略图缓存（只留一张；不上传、不落盘）

    private static final Object THUMB_LOCK = new Object();
    private static String sThumbPath;
    private static android.graphics.Bitmap sThumb;

    /** 后台线程预解码（inSampleSize 到长边约 96px）。解不出来就不画缩略图。 */
    public static void preloadThumb(String path) {
        if (path == null) return;
        synchronized (THUMB_LOCK) {
            if (path.equals(sThumbPath) && sThumb != null) return;
        }
        android.graphics.Bitmap bm = null;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            int longSide = Math.max(o.outWidth, o.outHeight);
            int sample = 1;
            while (longSide / (sample * 2) >= 96) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            bm = android.graphics.BitmapFactory.decodeFile(path, o2);
        } catch (Throwable ignored) {
            bm = null;
        }
        synchronized (THUMB_LOCK) {
            sThumbPath = path;
            sThumb = bm;
        }
    }

    /** 卡片收起 / 撤回后释放。 */
    public static void releaseThumbs() {
        synchronized (THUMB_LOCK) {
            sThumbPath = null;
            sThumb = null;
        }
    }

    static android.graphics.Bitmap thumbFor(String path) {
        synchronized (THUMB_LOCK) {
            return path != null && path.equals(sThumbPath) ? sThumb : null;
        }
    }

    /** 展开结果卡：抬头（记一记 · 类别 · 来自 X）、标题、两行摘要、最多 3 个动作键。点正文 → 详情。 */
    private float renderNote(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, boolean hits, long now) {
        boolean ok = s.status != IslandModel.Status.FAIL;
        if (c != null) header(c, x, y, w, alpha, ok ? IslandModel.Status.DONE : IslandModel.Status.FAIL, null,
                s.noteMeta == null || s.noteMeta.length() == 0 ? (ok ? "记一记" : "记一记 · 没记") : s.noteMeta, ok ? "刚刚" : null, now);
        float pad = IslandGeometry.EXP_PAD_DP * d;
        float bx = x + pad, bw = w - 2f * pad;
        float top = y + headerH() + 8f * d;
        float cy = top;
        // ★ feat/suggest：建议卡左侧缩略图（本地 96px 预解码；没解出来就不画、文字占满）
        android.graphics.Bitmap th = s.noteThumb == null ? null : thumbFor(s.noteThumb);
        float tx = bx, tw = bw;
        if (th != null) {
            float ts = 48f * d;
            if (c != null) {
                float sc = Math.min(ts / th.getWidth(), ts / th.getHeight());
                float dw = th.getWidth() * sc, dh = th.getHeight() * sc;
                RectF dst = new RectF(bx + (ts - dw) / 2f, top + (ts - dh) / 2f, bx + (ts + dw) / 2f, top + (ts + dh) / 2f);
                Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
                bp.setAlpha(alpha);
                c.drawBitmap(th, null, dst, bp);
            }
            tx = bx + ts + 10f * d;
            tw = bw - ts - 10f * d;
        }
        cy += para(c, s.title, pTtl17, tx, cy, tw, 2, alpha, 24f);
        if (s.noteDetail != null && s.noteDetail.length() > 0) {
            cy += 2f * d;
            cy += para(c, s.noteDetail, pSub, tx, cy, tw, 2, alpha, 18f);
        }
        if (th != null) cy = Math.max(cy, top + 48f * d);
        if (hits && ok) mHits.add(new Hit(new RectF(x, y, x + w, cy), A_NOTE_OPEN, true));
        int n = s.noteActions == null ? 0 : s.noteActions.size();
        if (n > 0) {
            cy += 12f * d;
            if (c != null) {
                float gap = 8f * d;
                float bwi = (bw - gap * (n - 1)) / n;
                for (int i = 0; i < n; i++) {
                    RectF b = new RectF(bx + i * (bwi + gap), cy, bx + i * (bwi + gap) + bwi, cy + 36f * d);
                    boolean last = i == n - 1;
                    mFill.setColor(last ? INK2 : ON1);
                    mFill.setAlpha(alpha);
                    c.drawRoundRect(b, 18f * d, 18f * d, mFill);
                    int oc = pBtn.getColor();
                    pBtn.setColor(last ? ON1 : INK);
                    String t = fitWidth(s.noteActions.get(i), pBtn, Math.max(0f, bwi - 12f * d));
                    drawTextCentered(c, t, b, pBtn, alpha);
                    pBtn.setColor(oc);
                    if (hits) mHits.add(new Hit(b, A_NOTE_PREFIX + i, true));
                }
            }
            cy += 36f * d;
        }
        cy += pad;
        return cy - y;
    }

    // ------------------------------------------------------------------ ★ 消息提醒

    private float noticeBadgeW(IslandModel.Snapshot s) {
        return s.noticeCount > 1 ? 10f * d + pTime.measureText("+" + (s.noticeCount - 1)) : 0f;
    }

    /** 紧凑：彩点（高优先级红）+ 队首标题 + 「+N」。 */
    private float renderCompactNotice(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha) {
        if (c == null) return ch();
        float cy = y + ch() / 2f;
        float lx = x + IslandGeometry.COMPACT_PAD_L_DP * d;
        IslandModel.NoticeRow top = s.notices != null && !s.notices.isEmpty() ? s.notices.get(0) : null;
        mFill.setColor(noticeDot(top));
        mFill.setAlpha(alpha);
        c.drawCircle(lx + 6f * d, cy, 4.5f * d, mFill);
        float rx = x + w - IslandGeometry.COMPACT_PAD_R_DP * d;
        float bw = 0f;
        if (s.noticeCount > 1) {
            String b = "+" + (s.noticeCount - 1);
            bw = pTime.measureText(b) + 10f * d;
            drawText(c, b, rx - 3f * d, cy, pTime, alpha, true);
        }
        float tx = lx + 18f * d;
        String chrono = top != null && top.chronoBase > 0 ? top.chronoText(System.currentTimeMillis()) : "";
        float cw = chrono.length() > 0 ? pTime.measureText(chrono) + 8f * d : 0f;
        if (cw > 0f) drawText(c, chrono, rx - bw - 2f * d, cy, pTime, alpha, true);
        String t = fitWidth(s.title == null ? "" : s.title, pWord, Math.max(0f, rx - bw - cw - tx));
        drawText(c, t, tx, cy, pWord, alpha, false);
        return ch();
    }

    /** 展开：抬头「提醒 · N 条 / 全部忽略」，每条一行（标题 + 摘要 + ×），选中的那条下面一排动作键；多于 4 条显示「还有 N 条」。 */
    private float renderNotices(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, boolean hits, long now) {
        int n = s.notices == null ? 0 : s.notices.size();
        float pad = IslandGeometry.EXP_PAD_DP * d;
        float bx = x + pad, bw = w - 2f * pad;
        boolean anyHigh = false;
        for (int i = 0; i < n; i++) anyHigh |= s.notices.get(i).high;
        if (c != null) header(c, x, y, w, alpha, anyHigh ? IslandModel.Status.FAIL : IslandModel.Status.DONE, null,
                "提醒 · " + n + " 条", null, now);
        if (n > 1) {
            String all = "全部忽略";
            float aw = pTime.measureText(all);
            float hy = y + pad + 9f * d;
            if (c != null) drawText(c, all, x + w - pad, hy, pTime, alpha, true);
            if (hits) mHits.add(new Hit(new RectF(x + w - pad - aw - 10f * d, y, x + w, y + headerH() + 4f * d), A_NOTICE_ALL, true));
        }
        float cy = y + headerH() + 6f * d;
        int shown = Math.min(n, IslandModel.MAX_NOTICES_SHOWN);
        for (int i = 0; i < shown; i++) {
            IslandModel.NoticeRow r = s.notices.get(i);
            boolean focus = r.id.equals(s.noticeFocus) || (s.noticeFocus == null && i == 0);
            float top = cy;
            if (i > 0 && c != null) {
                mFill.setColor(LINE);
                mFill.setAlpha(alpha);
                c.drawRect(bx, cy, bx + bw, cy + Math.max(1f, d), mFill);
            }
            cy += 6f * d;
            float xr = 28f * d;
            float textW = bw - xr - 6f * d;
            String rt = r.chronoBase > 0 ? r.title + " · " + r.chronoText(System.currentTimeMillis()) : r.title;
            if (focus) {
                cy += para(c, rt, pTtl, bx, cy, textW, 2, alpha, 22f);
                String sub = r.meta.length() > 0 ? r.meta + (r.summary.length() > 0 ? " · " + r.summary : "") : r.summary;
                if (sub.length() > 0) cy += para(c, sub, pSub, bx, cy, textW, 2, alpha, 18f);
                if (r.bars != null && r.bars.length() > 0) cy += rainBars(c, r.bars, bx, cy + 6f * d, textW, alpha) + 6f * d;
            } else {
                if (c != null) {
                    String t = fitWidth(rt, pSt, Math.max(0f, textW));
                    drawText(c, t, bx, cy + 11f * d, pSt, alpha, false);
                }
                cy += 22f * d;
            }
            RectF xb = new RectF(bx + bw - 24f * d, top + 6f * d, bx + bw, top + 30f * d);
            if (c != null) circleBtn(c, xb, "x", alpha, true);
            if (hits) {
                mHits.add(new Hit(new RectF(xb.left - 6f * d, top, xb.right + 4f * d, xb.bottom + 6f * d), A_NOTICE_X + r.id, true));
                if (!focus) mHits.add(new Hit(new RectF(bx - 4f * d, top, xb.left - 6f * d, cy + 4f * d), A_NOTICE_FOCUS + r.id, true));
            }
            if (focus && !r.actions.isEmpty()) {
                cy += 8f * d;
                int k = r.actions.size();
                if (c != null) {
                    float gap = 8f * d;
                    float bwi = (bw - gap * (k - 1)) / k;
                    for (int j = 0; j < k; j++) {
                        RectF b = new RectF(bx + j * (bwi + gap), cy, bx + j * (bwi + gap) + bwi, cy + 34f * d);
                        boolean primary = j == 0;
                        mFill.setColor(primary ? ON1 : INK2);
                        mFill.setAlpha(alpha);
                        c.drawRoundRect(b, 17f * d, 17f * d, mFill);
                        int oc = pBtn.getColor();
                        pBtn.setColor(primary ? INK : ON1);
                        String t = fitWidth(r.actions.get(j), pBtn, Math.max(0f, bwi - 12f * d));
                        drawTextCentered(c, t, b, pBtn, alpha);
                        pBtn.setColor(oc);
                        if (hits) mHits.add(new Hit(b, A_NOTICE_ACT + j, true));
                    }
                }
                cy += 34f * d;
            }
            cy += 6f * d;
        }
        if (n > shown) {
            cy += para(c, "还有 " + (n - shown) + " 条", pMeta, bx, cy, bw, 1, alpha, 18f);
        }
        cy += pad;
        return cy - y;
    }

    /**
     * ★ feat/rain-minutely：2 小时降水小柱状条（每根 = 5 分钟，'0'–'8' 级；0 级画一条细底线），下面一行「现在 · 1 小时 · 2 小时」。
     * 返回占用高度（c == null 时只量高度）。
     */
    private float rainBars(Canvas c, String bars, float x, float y, float w, int alpha) {
        float barH = 26f * d, labelH = 14f * d;
        if (c == null) return barH + labelH + 4f * d;
        int n = bars.length();
        float gap = 2f * d;
        float bw = n > 0 ? Math.max(1f, (w - gap * (n - 1)) / Math.max(n, 24)) : 0f;
        float base = y + barH;
        for (int i = 0; i < n; i++) {
            int lv = Math.max(0, Math.min(8, bars.charAt(i) - '0'));
            float h = lv == 0 ? Math.max(1f, d) : Math.max(3f * d, barH * lv / 8f);
            mFill.setColor(lv == 0 ? LINE : 0xFF5AA9E6);
            mFill.setAlpha(alpha);
            float bx0 = x + i * (bw + gap);
            c.drawRoundRect(new RectF(bx0, base - h, bx0 + bw, base), 1.5f * d, 1.5f * d, mFill);
        }
        float ly = base + 4f * d + labelH / 2f;
        drawText(c, "现在", x, ly, pMeta, alpha, false);
        float total = Math.max(n, 24) * (bw + gap) - gap;
        if (n >= 12) {
            String mid = "1 小时";
            drawText(c, mid, x + total / 2f - pMeta.measureText(mid) / 2f, ly, pMeta, alpha, false);
        }
        drawText(c, "2 小时", x + total, ly, pMeta, alpha, true);
        return barH + labelH + 4f * d;
    }

    private float renderCompactPickup(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha) {
        if (c == null) return ch();
        float cy = y + ch() / 2f;
        float lx = x + IslandGeometry.COMPACT_PAD_L_DP * d;
        drawGlyph(c, s.pickup != null && "取餐码".equals(s.pickup.kind) ? "bowl" : "pkg", lx, cy - 8f * d, 16f * d, ON1, alpha);
        if (s.pickup != null) {
            float avail = w - (lx - x) - 24f * d - IslandGeometry.COMPACT_PAD_R_DP * d;
            String code = TextUtils.ellipsize(s.pickup.code, pCode, Math.max(0f, avail), TextUtils.TruncateAt.START).toString();
            drawText(c, code, x + w - IslandGeometry.COMPACT_PAD_R_DP * d - 4f * d, cy, pCode, alpha, true);
        }
        return ch();
    }

    private float renderCompactRemind(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha) {
        if (c == null) return ch();
        float cy = y + ch() / 2f;
        float lx = x + IslandGeometry.COMPACT_PAD_L_DP * d;
        mFill.setColor(ACC);
        mFill.setAlpha(alpha);
        c.drawCircle(lx + 6f * d, cy, 4.5f * d, mFill);
        drawText(c, "提醒", lx + 18f * d, cy, pWord, alpha, false);
        drawText(c, s.remindAt > 0 ? mHm.format(new Date(s.remindAt)) : "", x + w - IslandGeometry.COMPACT_PAD_R_DP * d - 4f * d,
                cy, pTime, alpha, true);
        return ch();
    }

    private float renderCompactTodo(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha) {
        if (c == null) return ch();
        float cy = y + ch() / 2f;
        float lx = x + 9f * d;
        mStroke.setColor(ON2);
        mStroke.setAlpha(alpha);
        mStroke.setStrokeWidth(1.4f * d);
        mTmp.set(lx, cy - 6f * d, lx + 12f * d, cy + 6f * d);
        c.drawRoundRect(mTmp, 3f * d, 3f * d, mStroke);
        drawText(c, String.valueOf(s.todoCount), x + w - 10f * d, cy, pCode, alpha, true);
        return ch();
    }

    private float renderDual(Canvas c, IslandModel.Snapshot s, int alpha, long now) {
        if (c == null) return ch();
        if (mLeftRect.isEmpty() || mRightRect.isEmpty()) return ch();
        float cy = mLeftRect.centerY();
        drawMini(c, s.act, s.status, s.pickup, mLeftRect.centerX(), cy, alpha, now);
        if (s.bubble == IslandModel.Act.PICKUP || s.bubble == IslandModel.Act.REMIND || s.bubble == IslandModel.Act.NOTICE) {
            String t = s.bubbleText == null ? "" : s.bubbleText;
            TextPaint p = s.bubble == IslandModel.Act.PICKUP ? pCode : pLabel;
            String tt = TextUtils.ellipsize(t, p, Math.max(0f, mRightRect.width() - 8f * d), TextUtils.TruncateAt.START).toString();
            drawTextCentered(c, tt, mRightRect, p, alpha);
        } else {
            drawMini(c, s.bubble, s.bubbleStatus, null, mRightRect.centerX(), cy, alpha, now);
        }
        return ch();
    }

    private void drawMini(Canvas c, IslandModel.Act act, IslandModel.Status st, IslandModel.Pickup p, float cx, float cy,
                          int alpha, long now) {
        if (act == IslandModel.Act.PICKUP) {
            drawGlyph(c, p != null && "取餐码".equals(p.kind) ? "bowl" : "pkg", cx - 7f * d, cy - 7f * d, 14f * d, ON1, alpha);
        } else if (act == IslandModel.Act.REMIND || act == IslandModel.Act.NOTICE) {
            mFill.setColor(ACC);
            mFill.setAlpha(alpha);
            c.drawCircle(cx, cy, 4.5f * d, mFill);
        } else if (st == IslandModel.Status.RUN) {
            drawRing(c, cx, cy, 9f * d, -1, alpha, now, true);
            drawDot(c, cx, cy, 10f * d, st, alpha, now);
        } else {
            drawDot(c, cx, cy, 14f * d, st, alpha, now);
        }
    }

    private float renderPulse(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, long now) {
        float h = IslandGeometry.PULSE_H_DP * d;
        if (c == null) return h;
        float cy = y + h / 2f;
        float lx = x + 10f * d;
        drawDot(c, lx + 12f * d, cy, 22f * d, IslandModel.Status.DONE, alpha, now);
        float tx = lx + 30f * d;
        float rx = x + w - 10f * d - 22f * d - 8f * d;
        float avail = Math.max(0f, rx - tx);
        // ★ arch/island-progress：不再显示「第 N 步」；有账本 → 上一行 = 最近完成的子目标，下一行 =「第 i/n 项 · 当前子目标」
        String prev = pulsePrev(s);
        String cur = IslandModel.pulseLine(s);
        if (progressLive(s)) {   // ★ 11-04 午：上行「执行中 · 2/4 · 12 s」，下行 = 真实步骤（8 s 没新步骤 → 真实状态补句）
            long nw = System.currentTimeMillis();
            ProgressFeedback.Phase ph = progressPhase(s);
            prev = (ph == ProgressFeedback.Phase.THINKING ? ProgressFeedback.THINKING : ProgressFeedback.WORKING) + " · " + progressMeta(s, nw, true);
            String st = lastRealStep(s);
            String stall = ProgressFeedback.stallLine(ph, st, sincePhase(s, nw));
            cur = stall != null ? stall : ph == ProgressFeedback.Phase.THINKING
                    ? (st != null ? "上一步 " + ProgressFeedback.headline(ProgressFeedback.Phase.WORKING, st).replaceFirst("^执行中：", "") : "等模型回复")
                    : ProgressFeedback.headline(ph, st).replaceFirst("^执行中：", "");
        }
        drawText(c, fitWidth(prev, pSmall, avail), tx, cy - 7f * d, pSmall, alpha, false);
        drawText(c, fitWidth(cur, pWord, avail), tx, cy + 7f * d, pWord, alpha, false);
        drawRing(c, x + w - 10f * d - 11f * d, cy, 11f * d, 0, alpha, now, true);
        return h;
    }

    private void header(Canvas c, float x, float y, float w, int alpha, IslandModel.Status dot, String glyph, String label,
                        String right, long now) {
        float pad = IslandGeometry.EXP_PAD_DP * d;
        float cy = y + pad + 9f * d;
        float lx = x + pad;
        if (glyph != null) drawGlyph(c, glyph, lx, cy - 8f * d, 16f * d, ON1, alpha);
        else drawDot(c, lx + 7f * d, cy, 14f * d, dot, alpha, now);
        float rw = right == null ? 0f : pTime.measureText(right);
        String l = fitWidth(label, pLabel, Math.max(0f, w - 2f * pad - 22f * d - rw - 8f * d));
        drawText(c, l, lx + 22f * d, cy, pLabel, alpha, false);
        if (right != null) drawText(c, right, x + w - pad, cy, pTime, alpha, true);
    }

    private float headerH() {
        return IslandGeometry.EXP_PAD_DP * d + 18f * d;
    }

    private float renderRun(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, boolean hits, long now) {
        boolean bg = s.act == IslandModel.Act.AGENT;
        if (c != null) header(c, x, y, w, alpha, IslandModel.Status.RUN, null,
                s.exec ? (s.paused ? "已暂停 · 你来操作" : "正在替你操作") : bg ? "定时任务 · 正在执行"
                        : progressLive(s) ? progressWord(s, System.currentTimeMillis()) : "正在执行",   // ★ 11-04 午
                progressLive(s) ? progressMeta(s, System.currentTimeMillis(), true) : IslandModel.fmtElapsed(s.elapsed(System.currentTimeMillis())), now);
        float pad = IslandGeometry.EXP_PAD_DP * d;
        float bx = x + pad, bw = w - 2f * pad;
        float cy = y + headerH() + 8f * d;
        cy += para(c, s.title, pTtl, bx, cy, bw, 2, alpha, 22f);
        if (s.kind == IslandModel.Kind.ALERT_KEY && s.alertText != null) {
            cy += 6f * d;
            cy += para(c, s.alertText, pHi, bx, cy, bw, 2, alpha, 20f);
        }
        if (s.subgoals != null && s.subgoals.size() >= 2) {
            cy += subgoalRows(c, s, bx, cy, bw, alpha, now);   // ★ arch/island-progress：有账本 → 列子目标而不是工具调用
        } else if (!s.steps.isEmpty()) {
            cy += 8f * d;
            if (c != null) {
                mFill.setColor(LINE);
                mFill.setAlpha(alpha);
                c.drawRect(bx, cy, bx + bw, cy + Math.max(1f, d), mFill);
            }
            cy += Math.max(1f, d) + 2f * d;
            int n = s.steps.size();
            for (int i = 0; i < n; i++) {
                boolean cur = i == n - 1 && s.status == IslandModel.Status.RUN;
                if (c != null) {
                    float ry = cy + 13f * d;
                    if (cur) drawDot(c, bx + 8f * d, ry, 14f * d, IslandModel.Status.RUN, alpha, now);
                    else drawStepCheck(c, bx + 8f * d, ry, alpha);
                    String st = cur ? "进行中" : "完成";
                    float sw = pStepS.measureText(st);
                    TextPaint tp = cur ? pStepCur : pStep;
                    String label = fitWidth(s.steps.get(i), tp, Math.max(0f, bw - 24f * d - sw - 8f * d));
                    drawText(c, label, bx + 24f * d, ry, tp, alpha, false);
                    drawText(c, st, bx + bw, ry, pStepS, alpha, true);
                }
                cy += 26f * d;
            }
        }
        cy += 10f * d;
        if (c != null) {
            if (s.exec) {
                // ★ r2/fast-screen：执行模式按钮。运行中：补充 / 接管 / 停止；暂停中：继续 / 停止
                String[] labels = s.paused ? new String[]{"继续", "停止"} : new String[]{"补充", "接管", "停止"};
                String[] acts = s.paused ? new String[]{A_EXEC_RESUME, A_STOP} : new String[]{A_EXEC_SUPPLEMENT, A_EXEC_TAKEOVER, A_STOP};
                float gap = 8f * d, right = bx + bw;
                for (int i = labels.length - 1; i >= 0; i--) {
                    float bw2 = pBtn.measureText(labels[i]) + 28f * d;
                    RectF b = new RectF(right - bw2, cy, right, cy + 36f * d);
                    mFill.setColor(i == 0 && s.paused ? ACC : INK2);
                    mFill.setAlpha(alpha);
                    c.drawRoundRect(b, 18f * d, 18f * d, mFill);
                    drawTextCentered(c, labels[i], b, pBtn, alpha);
                    if (hits) mHits.add(new Hit(b, acts[i], true));
                    right -= bw2 + gap;
                }
                if (!s.paused) drawText(c, IslandModel.progressLine(s), bx, cy + 18f * d, pSub, alpha, false);
            } else {
            drawText(c, IslandModel.progressLine(s), bx, cy + 18f * d, pSub, alpha, false);
            float bw2 = pBtn.measureText("停止") + 32f * d;
            RectF b = new RectF(bx + bw - bw2, cy, bx + bw, cy + 36f * d);
            mFill.setColor(INK2);
            mFill.setAlpha(alpha);
            c.drawRoundRect(b, 18f * d, 18f * d, mFill);
            drawTextCentered(c, "停止", b, pBtn, alpha);
            if (hits) mHits.add(new Hit(b, A_STOP, true));
            }
        }
        cy += 36f * d + pad;
        return cy - y;
    }

    // ------------------------------------------------------------------ ★ arch/island-progress（C 路）：子目标进度 + ask_user

    static final int MAX_DOTS = 6;

    private float progressDotsWidth(int total) {
        if (total > MAX_DOTS) return pTime.measureText(total + "/" + total);
        return total * 5f * d + (total - 1) * 4f * d;
    }

    /** 紧凑态进度点：完成 = 实心灰、当前 = 绿（失败 = 红）、未做 = 空心；超过 6 个改写「i/n」。 */
    private void drawProgressDots(Canvas c, float left, float cy, IslandModel.Snapshot s, int alpha, long now) {
        int n = s.progressTotal;
        if (n > MAX_DOTS) {
            drawText(c, s.progressIndex + "/" + n, left + progressDotsWidth(n), cy, pTime, alpha, true);
            return;
        }
        float r = 2.5f * d;
        for (int i = 0; i < n; i++) {
            float cx = left + r + i * 9f * d;
            Subgoal.State st = s.subgoals != null && i < s.subgoals.size() ? s.subgoals.get(i).state : Subgoal.State.PENDING;
            boolean cur = i == s.progressIndex - 1;
            if (st == Subgoal.State.FAILED) {
                mFill.setColor(RED);
                mFill.setAlpha(alpha);
                c.drawCircle(cx, cy, r, mFill);
            } else if (cur && st != Subgoal.State.DONE) {
                double e = 0.5 - 0.5 * Math.cos(2 * Math.PI * ((now % 1200L) / 1200.0));
                mFill.setColor(ACC);
                mFill.setAlpha((int) (alpha * (0.6f + 0.4f * (float) e)));
                c.drawCircle(cx, cy, r, mFill);
            } else if (st == Subgoal.State.DONE) {
                mFill.setColor(ON2);
                mFill.setAlpha(alpha);
                c.drawCircle(cx, cy, r, mFill);
            } else {
                mStroke.setColor(MUTE);
                mStroke.setStrokeWidth(1.2f * d);
                mStroke.setAlpha(alpha);
                c.drawCircle(cx, cy, r - 0.6f * d, mStroke);
            }
        }
    }

    private static String pulsePrev(IslandModel.Snapshot s) {
        if (s.subgoals != null && s.subgoals.size() >= 2) {
            for (int i = s.subgoals.size() - 1; i >= 0; i--) {
                Subgoal g = s.subgoals.get(i);
                if (g.state == Subgoal.State.DONE && g.title.length() > 0) return "已完成 · " + g.title;
            }
            return "已开始";
        }
        return s.steps.size() >= 2 ? "已完成 · " + s.steps.get(s.steps.size() - 2) : "已开始";
    }

    /** 展开运行卡里的子目标列表（当前项附近最多 4 个）。返回占用高度。 */
    private float subgoalRows(Canvas c, IslandModel.Snapshot s, float bx, float cy0, float bw, int alpha, long now) {
        float cy = cy0 + 8f * d;
        if (c != null) {
            mFill.setColor(LINE);
            mFill.setAlpha(alpha);
            c.drawRect(bx, cy, bx + bw, cy + Math.max(1f, d), mFill);
        }
        cy += Math.max(1f, d) + 2f * d;
        int n = s.subgoals.size();
        int from = IslandModel.subgoalWindowStart(n, s.progressIndex);
        int to = Math.min(n, from + IslandModel.MAX_SUBGOALS_SHOWN);
        for (int i = from; i < to; i++) {
            Subgoal g = s.subgoals.get(i);
            boolean cur = i == s.progressIndex - 1 && g.state != Subgoal.State.DONE && g.state != Subgoal.State.FAILED
                    && s.status != IslandModel.Status.FAIL;
            if (c != null) {
                float ry = cy + 13f * d;
                String st;
                if (g.state == Subgoal.State.FAILED) {
                    drawDot(c, bx + 8f * d, ry, 14f * d, IslandModel.Status.FAIL, alpha, now);
                    st = "没完成";
                } else if (g.state == Subgoal.State.DONE) {
                    drawStepCheck(c, bx + 8f * d, ry, alpha);
                    st = "完成";
                } else if (cur) {
                    drawDot(c, bx + 8f * d, ry, 14f * d,
                            s.status == IslandModel.Status.WAIT ? IslandModel.Status.WAIT : IslandModel.Status.RUN, alpha, now);
                    st = s.status == IslandModel.Status.WAIT ? "等你" : "进行中";
                } else {
                    mStroke.setColor(MUTE);
                    mStroke.setStrokeWidth(1.2f * d);
                    mStroke.setAlpha(alpha);
                    c.drawCircle(bx + 8f * d, ry, 3f * d, mStroke);
                    st = "";
                }
                float sw = st.length() > 0 ? pStepS.measureText(st) : 0f;
                TextPaint tp = cur ? pStepCur : pStep;
                String label = fitWidth((i + 1) + ". " + g.title, tp, Math.max(0f, bw - 24f * d - sw - 8f * d));
                drawText(c, label, bx + 24f * d, ry, tp, alpha, false);
                if (st.length() > 0) drawText(c, st, bx + bw, ry, pStepS, alpha, true);
            }
            cy += 26f * d;
        }
        return cy - cy0;
    }

    /** ask_user：问题 + 2–4 个选项芯片（自动换行），底部「60 秒内没选会自动收起」。不能随手收起（同确认卡）。 */
    private float renderAsk(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, boolean hits, long now) {
        if (c != null) header(c, x, y, w, alpha, IslandModel.Status.WAIT, null, "需要你选一下", s.progressText, now);
        float pad = IslandGeometry.EXP_PAD_DP * d;
        float bx = x + pad, bw = w - 2f * pad;
        float cy = y + headerH() + 8f * d;
        String q = s.askQuestion == null || s.askQuestion.trim().length() == 0 ? "选一个，我接着做" : s.askQuestion.trim();
        cy += para(c, q, pTtl17, bx, cy, bw, 3, alpha, 24f);
        cy += 12f * d;
        float gap = 8f * d, chipH = 36f * d;
        float rowX = bx;
        java.util.List<String> opts = s.askOptions == null ? java.util.Collections.<String>emptyList() : s.askOptions;
        for (int i = 0; i < opts.size(); i++) {
            float want = Math.min(bw, pBtn.measureText(opts.get(i)) + 32f * d);
            if (rowX > bx && rowX + want > bx + bw) {
                rowX = bx;
                cy += chipH + gap;
            }
            if (c != null) {
                RectF b = new RectF(rowX, cy, rowX + want, cy + chipH);
                mFill.setColor(i == 0 ? ON1 : INK2);
                mFill.setAlpha(alpha);
                c.drawRoundRect(b, 18f * d, 18f * d, mFill);
                int oc = pBtn.getColor();
                pBtn.setColor(i == 0 ? INK : ON1);
                String t = fitWidth(opts.get(i), pBtn, Math.max(0f, want - 24f * d));
                drawTextCentered(c, t, b, pBtn, alpha);
                pBtn.setColor(oc);
                if (hits) mHits.add(new Hit(b, A_ASK_PREFIX + i, true));
            }
            rowX += want + gap;
        }
        cy += chipH + 10f * d;
        if (c != null) drawText(c, IslandModel.askHint(s.askTimeoutMs), bx, cy + 8f * d, pSub, alpha, false);
        cy += 16f * d + pad;
        return cy - y;
    }

    private float renderConfirm(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, boolean hits, long now) {
        boolean bg = s.act == IslandModel.Act.AGENT;
        if (c != null) header(c, x, y, w, alpha, IslandModel.Status.WAIT, null, bg ? "定时任务 · 等你确认" : "等你确认",
                s.progressText, now);   // ★ arch/island-progress：右上角 =「第 i/n 项」（没有账本就不写）
        float pad = IslandGeometry.EXP_PAD_DP * d;
        float bx = x + pad, bw = w - 2f * pad;
        float cy = y + headerH() + 8f * d;
        String t = s.confirmTitle == null || s.confirmTitle.trim().length() == 0 ? "要继续执行下一步吗" : s.confirmTitle.trim();
        if (!t.endsWith("？") && !t.endsWith("?")) t = t + "？";
        cy += para(c, t, pTtl17, bx, cy, bw, 2, alpha, 24f);
        if (s.confirmDetail != null && s.confirmDetail.trim().length() > 0) {
            cy += 8f * d;
            float th = para(null, s.confirmDetail.trim(), pQuote, bx + 12f * d, 0f, bw - 24f * d, CONFIRM_DETAIL_LINES, alpha, 19f);
            if (c != null) {
                mFill.setColor(INK2);
                mFill.setAlpha(alpha);
                mTmp.set(bx, cy, bx + bw, cy + th + 20f * d);
                c.drawRoundRect(mTmp, IslandGeometry.EXP_INNER_R_DP * d, IslandGeometry.EXP_INNER_R_DP * d, mFill);
                para(c, s.confirmDetail.trim(), pQuote, bx + 12f * d, cy + 10f * d, bw - 24f * d, CONFIRM_DETAIL_LINES, alpha, 19f);
            }
            cy += th + 20f * d;
        }
        cy += 12f * d;
        buttons(c, x, cy, w, alpha, hits, "拒绝", A_DENY, "允许", A_ALLOW);
        cy += 36f * d + pad;
        return cy - y;
    }

    private float renderFail(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, boolean hits, long now) {
        boolean bg = s.act == IslandModel.Act.AGENT;
        if (c != null) header(c, x, y, w, alpha, IslandModel.Status.FAIL, null, bg ? "定时任务没完成" : "没完成",
                IslandModel.fmtElapsed(s.elapsed(System.currentTimeMillis())), now);
        float pad = IslandGeometry.EXP_PAD_DP * d;
        float bx = x + pad, bw = w - 2f * pad;
        float cy = y + headerH() + 8f * d;
        cy += para(c, s.title, pTtl, bx, cy, bw, 2, alpha, 22f);
        cy += 2f * d;
        cy += para(c, IslandModel.failLine(s), pSub, bx, cy, bw, 2, alpha, 18f);   // ★ arch/island-progress
        cy += 12f * d;
        if (bg) single(c, x, cy, w, alpha, hits, "关闭", A_CLOSE);
        else buttons(c, x, cy, w, alpha, hits, "关闭", A_CLOSE, "重试", A_RETRY);
        cy += 36f * d + pad;
        return cy - y;
    }

    private float renderRemind(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, boolean hits, long now) {
        String right = s.remindAt > 0 ? mHm.format(new Date(s.remindAt)) : null;
        if (s.lateMin > 0) right = (right == null ? "" : right + " · ") + "晚了 " + s.lateMin + " 分钟";
        if (c != null) header(c, x, y, w, alpha, IslandModel.Status.DONE, null, "提醒", right, now);
        float pad = IslandGeometry.EXP_PAD_DP * d;
        float bx = x + pad, bw = w - 2f * pad;
        float cy = y + headerH() + 8f * d;
        cy += para(c, s.title == null || s.title.length() == 0 ? "到时间了" : s.title, pTtl17, bx, cy, bw, 2, alpha, 24f);
        cy += 12f * d;
        buttons(c, x, cy, w, alpha, hits, "稍后10分钟", A_REMIND_SNOOZE, "完成", A_REMIND_DONE);
        cy += 36f * d + pad;
        return cy - y;
    }

    private float renderTodo(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, boolean hits) {
        int n = s.todos.size();
        if (c != null) {
            header(c, x, y, w, alpha, null, "check", n == 0 ? "没有未完成的待办" : "待办 · " + n + " 项"
                    + (s.todoCount > 0 ? " · 今天 " + s.todoCount : ""), null, 0L);
        }
        float pad = IslandGeometry.EXP_PAD_DP * d;
        float bx = x + pad, bw = w - 2f * pad;
        float cy = y + headerH() + 6f * d;
        long now = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            IslandModel.TodoRow r = s.todos.get(i);
            float rh = 30f * d;
            if (c != null) {
                float ry = cy + rh / 2f;
                mStroke.setColor(ON2);
                mStroke.setAlpha(alpha);
                mStroke.setStrokeWidth(1.5f * d);
                c.drawCircle(bx + 8f * d, ry, 7f * d, mStroke);
                String due = r.dueAt > 0 ? dueText(r.dueAt, now) : "";
                float dw = due.length() == 0 ? 0f : pStepS.measureText(due);
                String t = fitWidth(r.text, pSt, Math.max(0f, bw - 24f * d - dw - 8f * d));
                drawText(c, t, bx + 24f * d, ry, pSt, alpha, false);
                if (dw > 0) drawText(c, due, bx + bw, ry, r.dueAt < now ? pHi : pStepS, alpha, true);
                if (hits) mHits.add(new Hit(new RectF(bx - 4f * d, cy, bx + bw + 4f * d, cy + rh), A_TODO_PREFIX + r.id, true));
            }
            cy += rh;
        }
        cy += 8f * d;
        single(c, x, cy, w, alpha, hits, "全部", A_TODO_ALL);
        cy += 36f * d + pad;
        return cy - y;
    }

    static String dueText(long due, long now) {
        if (due < now) return "已过期";
        long min = (due - now) / 60000L;
        if (min < 60) return min + " 分钟后";
        if (min < 24 * 60) return (min / 60) + " 小时后";
        return (min / (24 * 60)) + " 天后";
    }

    private float renderPickup(Canvas c, IslandModel.Snapshot s, float x, float y, float w, int alpha, boolean hits) {
        IslandModel.Pickup p = s.pickup;
        if (p == null) return headerH() + 16f * d;
        if (c != null) header(c, x, y, w, alpha, null, "取餐码".equals(p.kind) ? "bowl" : "pkg",
                p.kind + " · 共 " + s.pickupCount + " 个待取", p.source + " " + mHm.format(new Date(p.receivedAt)), 0L);
        float pad = IslandGeometry.EXP_PAD_DP * d;
        float bx = x + pad, bw = w - 2f * pad;
        float cy = y + headerH() + 8f * d;
        if (c != null) {
            String code = TextUtils.ellipsize(p.code, pPCode, bw, TextUtils.TruncateAt.END).toString();   // 取件码不是步骤文案：保持原样
            drawText(c, code, bx, cy + 20f * d, pPCode, alpha, false);
        }
        cy += 40f * d;
        if (p.station != null && p.station.length() > 0) cy += para(c, p.station, pSt, bx, cy, bw, 1, alpha, 20f);
        cy += para(c, leftText(p.expiresAt - System.currentTimeMillis()), pMeta, bx, cy, bw, 1, alpha, 18f);
        cy += 12f * d;
        if (c != null) {
            float by = cy;
            RectF xr = new RectF(bx, by, bx + 36f * d, by + 36f * d);
            circleBtn(c, xr, "x", alpha, true);
            if (hits) mHits.add(new Hit(xr, A_DISMISS, true));
            boolean prevOk = s.pickupIndex > 0, nextOk = s.pickupIndex < s.pickupCount - 1;
            RectF pr = new RectF(xr.right + 8f * d, by, xr.right + 44f * d, by + 36f * d);
            circleBtn(c, pr, "prev", alpha, prevOk);
            if (hits) mHits.add(new Hit(pr, A_PREV, prevOk));
            RectF pg = new RectF(pr.right + 4f * d, by, pr.right + 48f * d, by + 36f * d);
            drawTextCentered(c, (s.pickupIndex + 1) + " / " + s.pickupCount, pg, pPgn, alpha);
            RectF nr = new RectF(pg.right + 4f * d, by, pg.right + 40f * d, by + 36f * d);
            circleBtn(c, nr, "next", alpha, nextOk);
            if (hits) mHits.add(new Hit(nr, A_NEXT, nextOk));
            String ct = s.copied ? "已复制" : "复制";
            float cw = 13f * d + 18f * d + 5f * d + pBtn.measureText(ct) + 16f * d;
            RectF cr = new RectF(bx + bw - cw, by, bx + bw, by + 36f * d);
            mFill.setColor(s.copied ? ACC : ON1);
            mFill.setAlpha(alpha);
            c.drawRoundRect(cr, 18f * d, 18f * d, mFill);
            int fg = s.copied ? ON1 : INK;
            drawGlyph(c, s.copied ? "check" : "copy", cr.left + 13f * d, cr.centerY() - 9f * d, 18f * d, fg, alpha);
            int oc = pBtn.getColor();
            pBtn.setColor(fg);
            drawText(c, ct, cr.left + 13f * d + 18f * d + 5f * d, cr.centerY(), pBtn, alpha, false);
            pBtn.setColor(oc);
            if (hits) mHits.add(new Hit(cr, A_COPY, true));
        }
        cy += 36f * d + pad;
        return cy - y;
    }

    static String leftText(long ms) {
        if (ms <= 0) return "已失效";
        long min = (ms + 59999L) / 60000L;
        if (min < 60) return min + " 分钟后失效";
        return ((min + 59L) / 60L) + " 小时后失效";
    }

    private void buttons(Canvas c, float x, float y, float w, int alpha, boolean hits, String l1, String a1, String l2, String a2) {
        if (c == null) return;
        float pad = IslandGeometry.EXP_PAD_DP * d, gap = 8f * d;
        float bw = (w - 2f * pad - gap) / 2f;
        RectF b1 = new RectF(x + pad, y, x + pad + bw, y + 36f * d);
        RectF b2 = new RectF(b1.right + gap, y, b1.right + gap + bw, y + 36f * d);
        mFill.setColor(INK2);
        mFill.setAlpha(alpha);
        c.drawRoundRect(b1, 18f * d, 18f * d, mFill);
        int oc = pBtn.getColor();
        pBtn.setColor(ON1);
        drawTextCentered(c, l1, b1, pBtn, alpha);
        mFill.setColor(ON1);
        mFill.setAlpha(alpha);
        c.drawRoundRect(b2, 18f * d, 18f * d, mFill);
        pBtn.setColor(INK);
        drawTextCentered(c, l2, b2, pBtn, alpha);
        pBtn.setColor(oc);
        if (hits) {
            mHits.add(new Hit(b1, a1, true));
            mHits.add(new Hit(b2, a2, true));
        }
    }

    private void single(Canvas c, float x, float y, float w, int alpha, boolean hits, String l, String a) {
        if (c == null) return;
        float pad = IslandGeometry.EXP_PAD_DP * d;
        RectF b = new RectF(x + pad, y, x + w - pad, y + 36f * d);
        mFill.setColor(INK2);
        mFill.setAlpha(alpha);
        c.drawRoundRect(b, 18f * d, 18f * d, mFill);
        drawTextCentered(c, l, b, pBtn, alpha);
        if (hits) mHits.add(new Hit(b, a, true));
    }

    private void circleBtn(Canvas c, RectF r, String glyph, int alpha, boolean enabled) {
        int a = enabled ? alpha : (int) (alpha * 0.35f);
        mFill.setColor(INK2);
        mFill.setAlpha(a);
        c.drawOval(r, mFill);
        drawGlyph(c, glyph, r.centerX() - 9f * d, r.centerY() - 9f * d, 18f * d, ON2, a);
    }

    private float para(Canvas c, String text, TextPaint p, float x, float y, float w, int maxLines, int alpha, float lineDp) {
        if (text == null || text.length() == 0) return 0f;
        int width = Math.max(1, (int) w);
        float fontH = p.getFontMetrics().descent - p.getFontMetrics().ascent;
        float add = Math.max(0f, lineDp * d - fontH);
        String key = System.identityHashCode(p) + "|" + width + "|" + maxLines + "|" + lineDp + "|" + text;
        StaticLayout sl = mLayouts.get(key);
        if (sl == null) {
            // ★ 11-04 午：放不下不再出「…」—— 先完整排版，超过 maxLines 就在最后一行内按词 / 标点切（IslandStepText.cutAt），再排一次
            StaticLayout full = StaticLayout.Builder.obtain(text, 0, text.length(), p, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(add, 1f)
                    .setIncludePad(false)
                    .build();
            String shown = text;
            if (full.getLineCount() > maxLines) {
                int end = full.getLineEnd(maxLines - 1);
                int start = full.getLineStart(maxLines - 1);
                shown = IslandStepText.cutAt(text, start, end);
            }
            sl = StaticLayout.Builder.obtain(shown, 0, shown.length(), p, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(add, 1f)
                    .setIncludePad(false)
                    .setMaxLines(maxLines)
                    .build();
            mLayouts.put(key, sl);
        }
        int lines = Math.min(maxLines, sl.getLineCount());
        float h = lines * lineDp * d;
        if (c != null) {
            int oa = p.getAlpha();
            p.setAlpha(alpha);
            c.save();
            c.translate(x, y + add / 2f);
            sl.draw(c);
            c.restore();
            p.setAlpha(oa);
        }
        return h;
    }

    private void drawText(Canvas c, String t, float x, float cy, TextPaint p, int alpha, boolean right) {
        if (t == null || c == null) return;
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = cy - (fm.ascent + fm.descent) / 2f;
        int oa = p.getAlpha();
        p.setAlpha(alpha);
        float tx = right ? x - p.measureText(t) : x;
        c.drawText(t, tx, base, p);
        p.setAlpha(oa);
    }

    private void drawTextCentered(Canvas c, String t, RectF r, TextPaint p, int alpha) {
        float tw = p.measureText(t);
        drawText(c, t, r.centerX() - tw / 2f, r.centerY(), p, alpha, false);
    }

    // ------------------------------------------------------------------ 红点 / 步骤环 / 图标

    private void drawDot(Canvas c, float cx, float cy, float size, IslandModel.Status st, int alpha, long now) {
        float k = size / 20f;
        switch (st) {
            case WAIT: {
                double e = 0.5 - 0.5 * Math.cos(2 * Math.PI * ((now % 1000L) / 1000.0));
                mStroke.setColor(RED);
                mStroke.setStrokeWidth(1.5f * k);
                mStroke.setAlpha((int) (alpha * (1f - 0.7f * (float) e)));
                c.drawCircle(cx, cy, 8.25f * k, mStroke);
                mFill.setColor(RED);
                mFill.setAlpha(alpha);
                c.drawCircle(cx, cy, 4f * k, mFill);
                break;
            }
            case DONE: {
                mFill.setColor(ACC);
                mFill.setAlpha(alpha);
                c.drawCircle(cx, cy, 7f * k, mFill);
                mStroke.setColor(ON1);
                mStroke.setAlpha(alpha);
                mStroke.setStrokeWidth(1.8f * k);
                mPath.reset();
                mPath.moveTo(cx + (6.6f - 10f) * k, cy + (10.3f - 10f) * k);
                mPath.rLineTo(2.3f * k, 2.3f * k);
                mPath.rLineTo(4.6f * k, -4.9f * k);
                c.drawPath(mPath, mStroke);
                break;
            }
            case FAIL: {
                mStroke.setColor(MUTE);
                mStroke.setAlpha(alpha);
                mStroke.setStrokeWidth(1.5f * k);
                c.drawCircle(cx, cy, 7.25f * k, mStroke);
                mStroke.setColor(ON2);
                mStroke.setStrokeWidth(1.6f * k);
                c.drawLine(cx, cy - 3.6f * k, cx, cy + 0.8f * k, mStroke);
                mFill.setColor(ON2);
                mFill.setAlpha(alpha);
                c.drawCircle(cx, cy + 3.5f * k, 0.95f * k, mFill);
                break;
            }
            default: {   // RUN：红点 2.4 s 透明度呼吸
                double e = 0.5 - 0.5 * Math.cos(2 * Math.PI * ((now % 2400L) / 2400.0));
                mFill.setColor(RED);
                mFill.setAlpha((int) (alpha * (1f - 0.55f * (float) e)));
                c.drawCircle(cx, cy, 5f * k, mFill);
                break;
            }
        }
    }

    /** 步骤环：总步数未知 → 暗色整环 + #84AE64 的 3/4 弧缓慢转动；环心写步骤号（n ≤ 0 不写）。 */
    private void drawRing(Canvas c, float cx, float cy, float radius, int n, int alpha, long now, boolean spinning) {
        float sw = 2f * d;
        mStroke.setStrokeWidth(sw);
        mStroke.setColor(LINE);
        mStroke.setAlpha(alpha);
        c.drawCircle(cx, cy, radius - sw / 2f, mStroke);
        mStroke.setColor(ACC);
        mStroke.setAlpha(alpha);
        float start = spinning ? (now % 2400L) / 2400f * 360f : -90f;
        mTmp.set(cx - radius + sw / 2f, cy - radius + sw / 2f, cx + radius - sw / 2f, cy + radius - sw / 2f);
        c.drawArc(mTmp, start, 270f, false, mStroke);
        if (n > 0) drawTextCentered(c, String.valueOf(Math.min(99, n)), new RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                pNum, alpha);
    }

    private void drawStepCheck(Canvas c, float cx, float cy, int alpha) {
        float k = 16f * d / 20f;
        mStroke.setColor(MUTE);
        mStroke.setAlpha(alpha);
        mStroke.setStrokeWidth(1.6f * k);
        mPath.reset();
        mPath.moveTo(cx + (5.5f - 10f) * k, cy + (10.4f - 10f) * k);
        mPath.rLineTo(3f * k, 3f * k);
        mPath.rLineTo(6f * k, -6.2f * k);
        c.drawPath(mPath, mStroke);
    }

    private void drawGlyph(Canvas c, String name, float x, float y, float size, int color, int alpha) {
        float k = size / 20f;
        mStroke.setColor(color);
        mStroke.setAlpha(alpha);
        Path p = mPath;
        p.reset();
        if ("pkg".equals(name)) {
            p.moveTo(3.2f, 6.2f); p.lineTo(10f, 2.8f); p.rLineTo(6.8f, 3.4f); p.rLineTo(0f, 7.6f);
            p.lineTo(10f, 17.2f); p.rLineTo(-6.8f, -3.4f); p.close();
            p.moveTo(3.4f, 6.3f); p.lineTo(10f, 9.6f); p.rLineTo(6.6f, -3.3f);
            p.moveTo(10f, 9.6f); p.rLineTo(0f, 7.4f);
            p.moveTo(6.6f, 4.5f); p.rLineTo(6.7f, 3.4f);
        } else if ("bowl".equals(name)) {
            p.moveTo(3f, 9.5f); p.lineTo(17f, 9.5f);
            p.arcTo(new RectF(3f, 2.5f, 17f, 16.5f), 0f, 180f, false);
            p.close();
            p.moveTo(7f, 6.5f); p.rLineTo(1f, -2f);
            p.moveTo(10f, 6.5f); p.rLineTo(1f, -2f);
            p.moveTo(13f, 6.5f); p.rLineTo(1f, -2f);
        } else if ("copy".equals(name)) {
            p.addRoundRect(new RectF(7f, 7f, 16.5f, 16.5f), 2f, 2f, Path.Direction.CW);
            p.moveTo(13f, 7f); p.lineTo(13f, 5.2f); p.quadTo(13f, 3.5f, 11.3f, 3.5f); p.lineTo(5.2f, 3.5f);
            p.quadTo(3.5f, 3.5f, 3.5f, 5.2f); p.lineTo(3.5f, 11.3f); p.quadTo(3.5f, 13f, 5.2f, 13f); p.lineTo(7f, 13f);
        } else if ("check".equals(name)) {
            p.moveTo(5f, 10.4f); p.rLineTo(3.2f, 3.2f); p.lineTo(15f, 6.8f);
        } else if ("prev".equals(name)) {
            p.moveTo(12f, 5f); p.rLineTo(-5f, 5f); p.rLineTo(5f, 5f);
        } else if ("next".equals(name)) {
            p.moveTo(8f, 5f); p.rLineTo(5f, 5f); p.rLineTo(-5f, 5f);
        } else if ("x".equals(name)) {
            p.moveTo(6f, 6f); p.rLineTo(8f, 8f);
            p.moveTo(14f, 6f); p.rLineTo(-8f, 8f);
        } else {
            return;
        }
        c.save();
        c.translate(x, y);
        c.scale(k, k);
        mStroke.setStrokeWidth(1.5f);
        c.drawPath(p, mStroke);
        c.restore();
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    // ------------------------------------------------------------------ 触摸

    private String keyAt(float x, float y) {
        if (mCur == null) return null;
        if (mCur.kind == IslandModel.Kind.MINIMAL_DUAL && !mRightRect.isEmpty() && x >= mRightRect.left - 3f * d) {
            return mCur.bubbleKey;
        }
        return mCur.key;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_OUTSIDE:
                if (mListener != null) mListener.onOutside();
                return false;
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mDownAt = SystemClock.uptimeMillis();
                mLongFired = false;
                mSwipeFired = false;
                mDownKey = keyAt(mDownX, mDownY);
                removeCallbacks(mLongPress);
                if (mCur != null && !mCur.isExpanded() && mIslandRect.contains(mDownX, mDownY) && hitAt(mDownX, mDownY) == null) {
                    pressTo(0.96f);
                    postDelayed(mLongPress, 450L);
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - mDownX, dy = ev.getY() - mDownY;
                if (Math.abs(dx) > 12f * d || Math.abs(dy) > 12f * d) removeCallbacks(mLongPress);
                float th = IslandGeometry.SWIPE_TUCK_DP * d;
                if (!mSwipeFired && mListener != null && mCur != null) {
                    if (!mCur.isExpanded() && IslandModel.isSwipeSide(dx, dy, th) && dx < 0
                            && IslandModel.K_NOTICE.equals(mDownKey)) {
                        mSwipeFired = true;
                        mListener.onSwipeDismiss(mDownKey);   // ★：左滑忽略提醒
                    } else if (!mCur.isExpanded() && IslandModel.isSwipeSide(dx, dy, th)) {
                        mSwipeFired = true;
                        mListener.onSwipeSide();
                    } else if (mCur.isExpanded() && IslandModel.isSwipeUp(dx, dy, th)) {
                        mSwipeFired = true;
                        mListener.onSwipeUp();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(mLongPress);
                pressTo(1f);
                if (mLongFired || mSwipeFired) return true;
                float x = ev.getX(), y = ev.getY();
                if (!IslandModel.isTap(x - mDownX, y - mDownY, SystemClock.uptimeMillis() - mDownAt, 12f * d)) return true;
                performClick();
                Hit h = hitAt(x, y);
                if (h != null) {
                    if (h.enabled && mListener != null) mListener.onAction(h.act);
                } else if (mIslandRect.contains(x, y)) {
                    if (mListener != null && mCur != null && !mCur.isExpanded()) mListener.onTap(keyAt(x, y));
                } else if (mListener != null) {
                    mListener.onOutside();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(mLongPress);
                pressTo(1f);
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    // ------------------------------------------------------------------ ★ R13 卡 ↔ 岛 接力

    /** 卡片要形变飞进来：ms 内从隐藏出现的岛只长到种子、不显示内容；超时自己长出来。 */
    public void armMorph(long ms) {
        mMorphHoldUntil = SystemClock.uptimeMillis() + Math.max(0L, ms);
        removeCallbacks(mMorphRelease);
        postDelayed(mMorphRelease, Math.max(0L, ms));
        android.util.Log.i("Island", "[ISLAND-MORPH] 岛停在种子等卡片接触（≤" + ms + "ms）");
    }

    public void releaseMorph() {
        removeCallbacks(mMorphRelease);
        if (mMorphHoldUntil == 0L) return;
        mMorphHoldUntil = 0L;
        retarget();
    }

    /**
     * 舞台接触：以合并形状（屏幕坐标，上沿 ≈ 岛锚点）的尺寸 + 速度为初值，<b>APPEAR（ω22 ζ0.86）</b>收尾，内容按 0.8 门槛淡入，
     * 同时打一下 ISLAND_BULGE 脉冲。APPEAR 的常量一个不改（IslandGeometry 原样）。
     *
     * @param vw 宽度速度（px/s），vh 高度速度（px/s）
     */
    public boolean appearFrom(RectF screenRect, float vw, float vh) {
        removeCallbacks(mMorphRelease);
        mMorphHoldUntil = 0L;
        if (mAnchor == null || screenRect == null || targetHidden()) return false;
        retarget();   // 先算出真正的目标（紧凑 / 展开）
        float w = Math.max(1f, screenRect.width());
        float h = Math.max(IslandGeometry.COMPACT_H_DP * d * 0.5f, screenRect.bottom - mAnchor.top);
        mW.v = w;
        mH.v = h;
        mR.v = Math.min(w, h) / 2f;
        mW.vel = vw;
        mH.vel = vh;
        mR.vel = 0f;
        mShapeA.v = 1f;
        mShapeA.vel = 0f;
        mMotion = IslandGeometry.Motion.APPEAR;
        mAppearLock = true;
        float[] sp = IslandGeometry.springFor(IslandGeometry.Motion.APPEAR);   // {SPRING_OMEGA, SPRING_ZETA}
        mW.setParams(sp[0], sp[1]);
        mH.setParams(sp[0], sp[1]);
        mR.setParams(sp[0], sp[1]);
        mSplit.setParams(sp[0], sp[1]);
        mGate = IslandGeometry.CONTENT_GATE;
        mMorphGateOn = true;
        mMorphW0 = w;
        mMorphH0 = h;
        mCurA.v = 0f;
        mCurA.vel = 0f;
        mScaleIn = true;
        mBulgeX.v = 0f;
        mBulgeY.v = 0f;
        mBulgeX.t = 0f;
        mBulgeY.t = 0f;
        mBulgeX.vel = com.onesay.assistant.v2.ui.motion.MotionSpec.BULGE_VX;
        mBulgeY.vel = com.onesay.assistant.v2.ui.motion.MotionSpec.BULGE_VY;
        mSettledReported = false;
        mLastFrame = 0L;
        android.util.Log.i("Island", "[ISLAND-MORPH] appearFrom " + Math.round(w) + "x" + Math.round(h) + " v=(" + Math.round(vw) + ","
                + Math.round(vh) + ") → 目标 " + Math.round(mW.t) + "x" + Math.round(mH.t) + "（APPEAR ω" + sp[0] + " ζ" + sp[1] + "）");
        invalidate();
        postInvalidateOnAnimation();
        return true;
    }

    /** appearFrom 后的形变进度（0 = 接力起点，1 = 到目标），取宽 / 高中较慢的一个。 */
    private float morphProgress() {
        float pw = mMorphW0 - mW.t, ph = mMorphH0 - mH.t;
        float a = Math.abs(pw) < 1f ? 1f : 1f - Math.abs(mW.v - mW.t) / Math.abs(pw);
        float b = Math.abs(ph) < 1f ? 1f : 1f - Math.abs(mH.v - mH.t) / Math.abs(ph);
        return Math.max(0f, Math.min(a, b));
    }

    /** 回程起点：当前形状（屏幕坐标）{l, t, r, b, radius}。 */
    public float[] shapeForHandoff() {
        if (mAnchor == null || mW.v < 1f || mShapeA.v < 0.05f) return null;
        float w = mW.v, h = mH.v;
        float l = mAnchor.cx - w / 2f, t = mAnchor.top;
        return new float[] {l, t, l + w, t + h, Math.min(mR.v, Math.min(w, h) / 2f)};
    }

    /** 回程：冻住当前形状（模型的 RENDER 先记下、不执行），直到 vanishForHandoff 或超时。 */
    private long mHoldUntil;
    private IslandModel.Snapshot mHeldSnap;
    private final Runnable mHoldRelease = new Runnable() {
        @Override
        public void run() {
            if (mHoldUntil == 0L) return;
            mHoldUntil = 0L;
            android.util.Log.i("Island", "[ISLAND-MORPH] 回程冻结超时 → 照常");
            IslandModel.Snapshot h = mHeldSnap;
            mHeldSnap = null;
            if (h != null) setSnapshot(h);
        }
    };

    public void holdForHandoff(long ms) {
        mHoldUntil = SystemClock.uptimeMillis() + Math.max(0L, ms);
        removeCallbacks(mHoldRelease);
        postDelayed(mHoldRelease, Math.max(0L, ms));
    }

    /** 回程：舞台已接手 → 这一帧起不画形状与内容（下一次出现照旧从小胶囊 APPEAR）。 */
    public void vanishForHandoff() {
        removeCallbacks(mHoldRelease);
        boolean wasHeld = mHoldUntil != 0L;
        mHoldUntil = 0L;
        mShapeA.v = 0f;
        mShapeA.vel = 0f;
        mCurA.v = 0f;
        mPrevA.v = 0f;
        mAppearLock = false;
        mMorphGateOn = false;
        mTouchRect.setEmpty();
        IslandModel.Snapshot h = mHeldSnap;
        mHeldSnap = null;
        if (wasHeld && h != null) setSnapshot(h);   // 冻结期间记下的 RENDER（通常是 HIDDEN）现在执行 —— 形状已是 0，看不见
        invalidate();
    }

    private void pressTo(float k) {
        mPress.t = k;
        mSettledReported = mSettledReported && Math.abs(mPress.v - k) < 0.002f;
        invalidateIsland(true);
    }

    private Hit hitAt(float x, float y) {
        for (int i = mHits.size() - 1; i >= 0; i--) if (mHits.get(i).r.contains(x, y)) return mHits.get(i);
        return null;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
