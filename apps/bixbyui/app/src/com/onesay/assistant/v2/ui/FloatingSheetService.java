package com.onesay.assistant.v2.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.UnifiedExecutor;
import com.onesay.assistant.v2.router.AgentLoop;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.router.UnifiedRouter;
import com.onesay.assistant.v2.vision.ScreenVisionEngine;
import com.onesay.assistant.v2.wake.WakeService;
import org.json.JSONObject;

/**
 * 底部 Sheet 悬浮窗服务（基于 WindowManager.addView，非全屏流体交互）。
 *
 * 核心升级与交互修复：
 * 1. 彻底解决“打开一个软件后应该收起也没做”：
 *    - 新增 isAppLaunchOrNavigation 研判逻辑；
 *    - 针对应用启动类指令（app.launch、相机、浏览器、打电话、发短信等切屏操作），
 *      在拉起目标 Intent 成功后立即触发 220ms 丝滑 Slide-Out 退场，绝不遮挡目标应用！
 * 2. 彻底解决“取消按键点了没反应”：
 *    - onCancelAction 与 onDismiss 彻底闭环：用户点击取消、关闭按钮、顶部手柄或下拉手势时，
 *      不仅立即重置语音捕获状态（WakeService.cancelCaptureFromUi()），而且立即触发 hideSheet()；
 * 3. 优化常驻停留时间阶梯（Snappy Auto-Dismiss）：
 *    - 界面切换类：220ms 闪现确认即收起；
 *    - 普通物理控制（音量/蓝牙/WiFi/亮度）：1.6 秒轻巧收起；
 *    - 智能问答（Ask 模式）：**常驻，等用户手动收起**（2026-10-03 修订，原为 4.5s 自动收起）；
 *    - 错误警示：2.8 秒清晰呈现。
 * 4. 外部触控感知：
 *    - 触控浮层外区域（FLAG_WATCH_OUTSIDE_TOUCH），立即平滑收起。
 * 5. **ASK 流式交付**。
 *    - 检索阶段显示"正在检索资料…"，生成阶段按 ~70ms 节流逐段上屏；
 *    - 面板收起时置位取消令牌，在途流式请求立即中止（见 {@link #cancelActiveStream()}）。
 */
public class FloatingSheetService extends Service implements FloatingSheetView.OnActionListener {

    private static final String TAG = "FloatingSheetService";

    public static final String ACTION_SHOW = "com.onesay.assistant.v2.action.SHOW_SHEET";
    public static final String ACTION_HIDE = "com.onesay.assistant.v2.action.HIDE_SHEET";
    public static final String ACTION_LISTEN = "com.onesay.assistant.v2.action.LISTEN";
    /** [s3] 闹钟 / 开机 / 改时间后处理到期的定时任务（BootReceiver → 本服务；不导出，同 uid）。 */
    public static final String ACTION_S3_FIRE = "com.onesay.assistant.v2.action.S3_FIRE";
    /** ★ feat/api-first：点「调用中：X」前台通知 → 安静地挂回面板（不清内容、不放唤醒水光）。 */
    public static final String ACTION_OPEN_PANEL = "com.onesay.assistant.v2.action.OPEN_PANEL";
    public static final String EXTRA_QUERY = "query";
    public static final String EXTRA_PROMPT = "prompt";

    private static volatile FloatingSheetService sInstance;

    private WindowManager mWindowManager;
    private FloatingSheetView mSheetView;
    private WindowManager.LayoutParams mParams;
    private volatile boolean mIsAttached = false;
    private volatile boolean mHiding = false; // 防重入：动画进行中不允许再次触发 hideSheet

    // ★ 小爱水光实机取色全屏动效层 (WakeRippleView)
    private WakeRippleView mRippleView;
    private WindowManager.LayoutParams mRippleParams;
    private volatile boolean mRippleAttached = false;

    // ★ R13 唤醒界面 v2：光晕 / 压暗 / 形变舞台窗（同 WakeRippleView 的窗口类型与 flag，二者每次只挂一个）
    private com.onesay.assistant.v2.ui.wake.WakeGlowView mGlowView;
    private volatile boolean mGlowAttached = false;
    /** 这一轮会话用新界面吗（挂载时按 WakeUiSettings 定，会话内不变；wakeui.off → 下一次挂载就是旧界面）。 */
    private volatile boolean mUseV2 = false;
    /** 这次挂窗后已经问过可见区下沿（见卡片窗 onLayoutChange）。 */
    private boolean mGlowVisAsked;
    /** 去程：卡片窗等舞台第一帧画出后再摘。 */
    private boolean mCardRemovalPending;
    /** 去程：卡片窗已 GONE、等延迟摘窗。 */
    private boolean mCardRemovalScheduled;
    private final Runnable mDeferredCardRemoval = new Runnable() {
        @Override
        public void run() {
            if (!mCardRemovalScheduled) return;
            mCardRemovalScheduled = false;
            long t0 = SystemClock.uptimeMillis();
            removeSheetWindowOnly("形变落定后");
            Log.i(TAG, "[WAKE-MORPH] 摘卡片窗用时 " + (SystemClock.uptimeMillis() - t0) + "ms");
        }
    };

    /** 有延迟摘窗挂着时，任何新的挂载 / 收起先把它做完（不能让 detachFromWindowImmediate 以为面板还在而结束会话）。 */
    private void flushPendingCardRemoval() {
        if (!mCardRemovalScheduled) return;
        mMainHandler.removeCallbacks(mDeferredCardRemoval);
        mCardRemovalScheduled = false;
        removeSheetWindowOnly("新挂载前补摘");
    }

    /** 新界面的退场动画代号（新一轮挂载会作废旧的延迟摘窗）。 */
    private int mDismissGen;
    /** 新界面外部点击保护期（D9：1200 → 500 ms，新入场 ≈ 0.5 s）。 */
    private static final long OUTSIDE_TAP_GUARD_V2_MS = 500L;

    /**
     * 面板最近一次挂载到屏幕的时刻（elapsedRealtime）。
     *
     * <p>用途：外部点击（ACTION_OUTSIDE）收起的**滑入保护期**判定。面板滑入动画约 1s，
     * 这段期间用户"抬手离开屏幕/按键"会被系统合成为外部触摸事件 —— 那不是"想收起面板"。
     * 实测（18:31）挂载后 1027ms 即被自己的抬手动作收掉，用户看到"面板闪一下就没了"。
     */
    private volatile long mShownAt = 0L;

    /** 滑入保护期时长：动画（约 1s）+ 200ms 余量。期间忽略 ACTION_OUTSIDE。 */
    private static final long OUTSIDE_TAP_GUARD_MS = 1200L;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private UnifiedRouter mRouter;
    private UnifiedExecutor mExecutor;

    // ★ device-1102 问题 3：会话保持。面板离屏 → 记下离屏时刻，排一个「保持期满」任务；期满仍没有新一轮才结束会话（触发 soul 整理）。
    private final Runnable mConversationExpire = new Runnable() {
        @Override public void run() {
            boolean ended = com.onesay.assistant.v2.router.ConversationMemory.getInstance().expireIfIdle();
            Log.i(TAG, "[CONV] 会话保持期满检查：" + (ended ? "超时，会话结束" : "期间有新一轮，继续保持"));
        }
    };

    private void endConversationSoft() {
        com.onesay.assistant.v2.router.ConversationMemory m = com.onesay.assistant.v2.router.ConversationMemory.getInstance();
        try {   // 可配：SharedPreferences bixby_conv / keep_sec（debug 广播 --ei conv_keep_sec N 写入）；缺省 300 秒
            long sec = getSharedPreferences("bixby_conv", MODE_PRIVATE).getLong("keep_sec", 0L);
            if (sec > 0) m.setKeepMs(sec * 1000L);
        } catch (Throwable ignored) { }
        com.onesay.assistant.v2.router.ctx.ContextPrefs.apply(this);   // ★ int/1102
        m.onPanelClosed();
        mMainHandler.removeCallbacks(mConversationExpire);
        mMainHandler.postDelayed(mConversationExpire, m.keepMs() + 1000L);
        Log.i(TAG, "[CONV] 面板离屏：会话保留 " + (m.keepMs() / 1000) + " 秒（历史 " + m.size() + " 轮），期间再唤醒接着上轮");
    }

    private final Runnable mAutoHideRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "自动延时定时器触发 -> 滑出收起悬浮面板");
            hideSheet();
        }
    };

    // ---------- 「没听清」错误态短暂显示 → 中性态 → 自动收起 ----------
    //   决策全部在 ErrorStatePolicy（纯 Java，有单测）；这里只执行。两个回调都随 cancelAutoHide() 一起取消，
    //   所以任何新一轮输入（新唤醒 / 提交指令 / 确认卡）都会让它们作废；到点时还会再复核一次。

    /** 中性态复位时是否清转录区（新唤醒：清；继续会话：保留上一轮问答）。 */
    private volatile boolean mErrorClearTranscript = true;

    private final Runnable mErrorNeutralRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSheetView == null) return;
            if (!com.onesay.assistant.v2.ui.ErrorStatePolicy.stillNeutral(isPanelOnScreen(), isConfirmPendingAnywhere(),
                    com.onesay.assistant.v2.s1.S1Hooks.isTaskRunning())) return;
            mSheetView.resetToNeutral(mErrorClearTranscript);
            Log.i(TAG, "[ERR-UI] 错误态已复位为中性态");
        }
    };

    private final Runnable mErrorHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSheetView == null) return;
            if (!com.onesay.assistant.v2.ui.ErrorStatePolicy.stillHide(isPanelOnScreen(), isConfirmPendingAnywhere(),
                    com.onesay.assistant.v2.s1.S1Hooks.isTaskRunning(), mSheetView.isTextModeActive())) {
                Log.i(TAG, "[ERR-UI] 到点复核不收起（确认卡/任务/文字输入/已离屏）");
                return;
            }
            Log.i(TAG, "[ERR-UI] 错误态到时自动收起面板");
            mSheetView.resetToNeutral(mErrorClearTranscript);
            WakeService.cancelCaptureFromUi();
            hideSheet();
        }
    };

    private boolean isPanelOnScreen() {
        return mSheetView != null && (mIsAttached || mSheetView.getParent() != null) && !mHiding;
    }

    /** ★：面板（聆听 / 全屏回答 / 确认卡）此刻在屏幕上 —— 灵动岛提醒据此只排队、不自动展开。 */
    public boolean panelOnScreen() {
        return mPresence.onScreen();   // ★ BUG-I2b：与报给岛的同一份真相（卡片窗挂着就算，直到真的 removeView）
    }

    /**
     * 面板挂上 / 摘下时告诉灵动岛（{@code S1Hooks.onPanelVisibility}）：提醒展开中面板出现 → 提醒立即
     * 收回队列；面板关了 → 提醒恢复展开（岛空闲时）。
     * <p>I2b：通知只从卡片窗真正 addView / removeView 的两个汇合点发出（{@link #addSheetWindow} / {@link #removeSheetWindow}），
     * 不再在各条挂摘路径上各调一次；合并 / 去重在 {@link PanelPresence}（同一次关闭只报一次 panel(false)）。
     */
    private final PanelPresence mPresence = new PanelPresence(new PanelPresence.Sink() {
        @Override public void onPanelVisibility(boolean on, String why) {
            Log.i(TAG, "[PANEL] panel(" + on + ") ← " + why);
            com.onesay.assistant.v2.s1.S1Hooks.onPanelVisibility(on);
        }
    }, new PanelPresence.Poster() {
        @Override public void post(Runnable r) {
            mMainHandler.post(r);
        }

        @Override public void cancel(Runnable r) {
            mMainHandler.removeCallbacks(r);
        }
    });

    /** 唯一的卡片窗挂载点（BUG-I2b）：addView 成功后登记「面板在屏」。失败照旧抛给调用方。 */
    private void addSheetWindow(String why) {
        mParams.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;   // ★ r2/kb-fix：只在打字阶段常亮
        // ★ r2/screenoff-wake：锁屏被托底 Activity 遮挡时，只有带 FLAG_SHOW_WHEN_LOCKED 的窗才放行（其余时候锁屏照样藏住它）
        mParams.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
        mWindowManager.addView(mSheetView, mParams);
        mPresence.attached(why);
    }

    /** 唯一的卡片窗摘除点（BUG-I2b）：removeViewImmediate（失败退回 removeView），然后登记「面板离屏」—— 所有关闭路径都经过这里。 */
    private void removeSheetWindow(String why) {
        if (mSheetView == null) return;
        try {
            mWindowManager.removeViewImmediate(mSheetView);
        } catch (Throwable t) {
            try { mWindowManager.removeView(mSheetView); } catch (Throwable ignore) {}
        }
        mPresence.removed(why);
        // ★ r2/screenoff-wake：面板离屏 → 锁屏托底也退（稍等一下：同一流程里摘了又挂回的不算离屏）
        mMainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (mSheetView == null || mSheetView.getParent() == null) KeyguardHostActivity.finishIfShowing("panel-removed");
            }
        }, 1200);
    }

    /**
     * ★ r2/kb-fix（LC-2 锁屏复发，现场照片）：锁屏上唤醒 → 托底 Activity 遮挡锁屏、面板浮在上面；用户点 ⌨ 打字，
     * 托底 60 s 到点（或息屏）就退了 → 锁屏回来把面板藏住，键盘却还挂着（输入法目标仍是我们这个带 SHOW_WHEN_LOCKED 的窗）。
     * 现在：① 面板在屏且处于打字阶段时托底到点自动顺延（上限 10 分钟）；② 托底因任何原因退出而锁屏还锁着、面板还在 →
     * 收键盘并收起面板（面板反正已被锁屏挡住，留着只剩一个孤零零的键盘）。
     */
    private void installKeyguardHostHooks() {
        KeyguardHostActivity.setHold(new KeyguardHostActivity.Hold() {
            @Override public boolean holdHost() {
                return isPanelOnScreen() && mSheetView != null && mSheetView.isTextModeActive();
            }
        });
        KeyguardHostActivity.setGoneListener(new KeyguardHostActivity.GoneListener() {
            @Override public void onKeyguardHostGone(final String why) {
                mMainHandler.post(new Runnable() {
                    @Override public void run() {
                        boolean locked = false;
                        try {
                            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                            locked = km != null && km.isKeyguardLocked();
                        } catch (Throwable ignored) {
                        }
                        Log.i(TAG, "[KB-LOCK] 托底退出（" + why + "）locked=" + locked + " panel=" + isPanelOnScreen());
                        if (mSheetView == null || !com.onesay.assistant.v2.ui.ime.KbLockPolicy.collapseOnHostGone(locked, isPanelOnScreen())) return;
                        mSheetView.hideSoftKeyboard();
                        hideSheet(true);
                        Log.i(TAG, "[KB-LOCK] 锁屏回来 → 收键盘、收起面板（不留孤立键盘）");
                    }
                });
            }
        });
    }

    /** 调试：面板在屏登记状态一行。 */
    public String debugPanelPresence() {
        return mPresence.dump();
    }

    /** 任一确认卡未决（单步确认令牌 / 面板上的确认卡 / S1 胶囊记录的确认）。 */
    private boolean isConfirmPendingAnywhere() {
        return mPendingConfirm.isHolding()
                || (mSheetView != null && mSheetView.isConfirmShowing())
                || com.onesay.assistant.v2.s1.S1Hooks.isConfirmPending();
    }

    /** 主线程：进入错误态后按 ErrorStatePolicy 安排「清芯片 / 回中性态 / 自动收起」。 */
    private void applyErrorPolicy(com.onesay.assistant.v2.ui.ErrorStatePolicy.Source source) {
        if (mSheetView == null) return;
        com.onesay.assistant.v2.ui.ErrorStatePolicy.Plan plan = com.onesay.assistant.v2.ui.ErrorStatePolicy.plan(source,
                isPanelOnScreen(), isConfirmPendingAnywhere(), com.onesay.assistant.v2.s1.S1Hooks.isTaskRunning(),
                mSheetView.isTextModeActive());
        Log.i(TAG, "[ERR-UI] " + source + " → " + plan);
        if (!plan.apply) return;
        if (plan.clearSuggestions) mSheetView.setSuggestionsVisible(false);
        mErrorClearTranscript = plan.clearTranscriptOnNeutral;
        if (plan.neutralAfterMs >= 0) mMainHandler.postDelayed(mErrorNeutralRunnable, plan.neutralAfterMs);
        if (plan.hideAfterMs >= 0) mMainHandler.postDelayed(mErrorHideRunnable, plan.hideAfterMs);
    }

    /**
     * ★ round3 #1：确认卡被拒绝后按 {@link com.onesay.assistant.v2.ui.ConfirmRejectPolicy} 安排「中性态 / 自动收起」。
     * 复用 M-2 错误态的两个回调（到点再复核：期间出现新确认卡 / 任务 / 文字输入 / 新一轮输入都会作废）。
     */
    private void applyRejectPolicy(com.onesay.assistant.v2.ui.ConfirmRejectPolicy.Path path) {
        if (mSheetView == null) return;
        cancelAutoHide();
        com.onesay.assistant.v2.ui.ConfirmRejectPolicy.Plan plan = com.onesay.assistant.v2.ui.ConfirmRejectPolicy.plan(path,
                isPanelOnScreen(), isConfirmPendingAnywhere(), com.onesay.assistant.v2.s1.S1Hooks.isTaskRunning(),
                mSheetView.isTextModeActive());
        Log.i(TAG, "[REJECT-UI] " + path + " → " + plan);
        if (!plan.apply) return;
        mErrorClearTranscript = false;   // 收起前保留「你说：…」，只收掉「已拒绝」卡
        if (plan.neutralAfterMs >= 0) mMainHandler.postDelayed(mErrorNeutralRunnable, plan.neutralAfterMs);
        if (plan.hideAfterMs >= 0) mMainHandler.postDelayed(mErrorHideRunnable, plan.hideAfterMs);
    }

    // ---------- 流式渲染节流（新增） ----------

    /**
     * 增量上屏的最小间隔（毫秒）。
     *
     * <p>实测：DeepSeek 吐出速度极不均匀 —— 短答案 250ms 内一次性丢过来 60 多帧
     * （≈250 帧/秒），长答案则是均匀的十几帧/秒。节流窗口取 45ms（≈22fps）是两侧的折中：
     * 慢流不受影响（帧间隔本来就更长，帧帧都上屏），快流被压成"看得见在长"的节奏，
     * 而不是一帧糊上去。
     *
     * <p>为什么不做成"每 N 字刷一次"：那会让长答案的字块越刷越大（越到后面越卡顿感），
     * 按时间刷才是匀速的视觉节奏。面板是 WRAP_CONTENT 的浅层布局，
     * 每秒 22 次 setText 的排版开销可以忽略。
     */
    private static final long STREAM_FLUSH_MS = 45;
    /**
     * 拨动"反悔开关"的点击触觉反馈时长（毫秒）。
     *
     * <p>"每次按开关都会回复已完成，按钮不需要播报，
     * 但需要它调用震动马达来做反馈。" —— 语音路径保留应答音（用户在看别处），
     * 手指点在面板上的路径改为纯震动（用户就在看屏幕，再念一遍"已完成"是噪音）。
     */
    private static final int TOGGLE_VIBRATE_MS = 30;
    /** 静默路径执行失败时的补震（更长，让手感能区分成败）。 */
    private static final int TOGGLE_FAIL_VIBRATE_MS = 90;

    /**
     * 确认卡上“确认”按钮的触觉反馈时长。
     *
     * <p>同样是“手指直接操作”，与反悔开关同源：不再播“已完成”（用户就在看屏幕），
     * 改为震动。比开关略长（50ms）—— 这是**高危操作的受理确认**，需要更明确的手感。
     */
    private static final int CONFIRM_VIBRATE_MS = 50;

    /** 本轮在途的流式令牌（用户收起面板时置位，让读取循环别白跑） */
    private volatile UnifiedRouter.Cancel mActiveStream;
    /** r2/fixes 14：正在跑的 AgentLoop 复合任务的取消令牌与派发时的请求号（见 {@link com.onesay.assistant.v2.router.AgentPreemption}）。 */
    private volatile UnifiedRouter.Cancel mActiveAgent;
    private volatile int mActiveAgentSeq;

    // ---------- 敏感操作确认会话（循环挂起等待用户决策）----------

    /** 确认会话锁（循环线程设 / 主线程取，单一互斥点）。 */
    private final Object mConfirmLock = new Object();

    /** 循环里挂起等待用户决策的确认（null = 当前无）。 */
    private PendingLoopConfirm mPendingLoopConfirm;

    /** 单步确认卡：一次性确认令牌 + 原始指令（替代 mPendingConfirmQuery/mNextExecConfirmed）。 */
    private final com.onesay.assistant.v2.executor.PendingConfirm mPendingConfirm =
            new com.onesay.assistant.v2.executor.PendingConfirm();

    /** 用户决策的最长等待（超时按未授权处理，避免循环线程无限挂起）。 */
    private static final long CONFIRM_WAIT_TIMEOUT_MS = 120000L;

    /** 待上屏的最新全量文本（节流期间只保留最后一份，中间帧直接丢） */
    private String mPendingStreamText;
    private boolean mStreamFlushScheduled;
    private long mLastStreamFlushAt;

    private final Runnable mStreamFlushRunnable = new Runnable() {
        @Override
        public void run() {
            mStreamFlushScheduled = false;
            if (mPendingStreamText == null || mSheetView == null) return;
            mLastStreamFlushAt = SystemClock.elapsedRealtime();
            mSheetView.setAskStreamText(mPendingStreamText);
            Log.i(TAG, "[STREAM-UI] 增量上屏 " + mPendingStreamText.length() + " 字");
        }
    };

    /** 流式增量节流入口（**只在主线程调用**）。 */
    private void pushStreamText(String text) {
        if (mSheetView == null) return;
        mPendingStreamText = text;

        long now = SystemClock.elapsedRealtime();
        long since = now - mLastStreamFlushAt;   // 流开始时 mLastStreamFlushAt=0 → 首段立即上屏
        if (since >= STREAM_FLUSH_MS) {
            mMainHandler.removeCallbacks(mStreamFlushRunnable);
            mStreamFlushScheduled = false;
            mLastStreamFlushAt = now;
            mSheetView.setAskStreamText(text);
            Log.i(TAG, "[STREAM-UI] 增量上屏 " + (text != null ? text.length() : 0) + " 字");
            return;
        }
        if (!mStreamFlushScheduled) {
            mStreamFlushScheduled = true;
            mMainHandler.postDelayed(mStreamFlushRunnable, STREAM_FLUSH_MS - since);
        }
    }

    /**
     * 作废在途的流式请求。
     *
     * <p>单一收口点：面板被收起（用户点 ✕ / 点外部 / 下拉手柄 / 自动收起）时调用。
     * 置位后读取循环在下一行退出，不会抱着连接把整段答案读完 —— 用户已经看不到它了。
     */
    private void cancelActiveStream() {
        cancelActiveStream(false);
    }

    /**
     * r2/fixes 14：作废在途请求。
     *
     * @param newRequest true = 新请求到达（onSubmitCommand / 识屏）：只作废流式问答，<b>不碰</b>正在跑的复合任务
     *                   （新请求要进 AgentLoop 时由 {@link #cancelActiveAgent} 另行取消）；
     *                   false = 面板收起 / 超时等收尾：复合任务还占着卡片时一起取消，已转后台的不动。
     */
    private void cancelActiveStream(boolean newRequest) {
        UnifiedRouter.Cancel c = mActiveStream;
        UnifiedRouter.Cancel agent = mActiveAgent;
        boolean agentToo = agent != null && !newRequest
                && com.onesay.assistant.v2.router.AgentPreemption.teardownCancelsAgent(mActiveAgentSeq, mRequestSeq, mYieldNoCancel);
        if (c != null && (c != agent || agentToo)) {
            c.cancel();
            Log.i(TAG, "已作废在途流式请求（面板已收起或新指令到达）");
        }
        if (agentToo) {
            agent.cancel();
            mActiveAgent = null;
            Log.i(TAG, "[AGENT-LOOP] 收尾：复合任务还占着卡片，一并取消");
        } else if (agent != null && c == agent) {
            Log.i(TAG, "[AGENT-LOOP] 新请求不进 AgentLoop：复合任务转后台继续（卡片让给新请求）");
        }
        mActiveStream = null;
        mPendingStreamText = null;
        mStreamFlushScheduled = false;
        if (!newRequest) {
            // 面板收起 / 「✕ 取消」→ 在途识屏一并作废（worker 在检查点放弃，不上传）
            try {
                ScreenVisionEngine.getInstance(this).cancelInFlight();
            } catch (Throwable ignored) {
            }
        }
        mMainHandler.removeCallbacks(mStreamFlushRunnable);
        // 挂起中的确认等待一并放行（按"未授权"），避免循环线程空转到超时。
        resolvePendingLoopConfirm(PendingLoopConfirm.VERDICT_DENY);
    }

    public static FloatingSheetService getInstance() {
        return sInstance;
    }

    public static void show(Context context, String initialQuery) {
        Intent intent = new Intent(context, FloatingSheetService.class);
        intent.setAction(ACTION_SHOW);
        if (initialQuery != null) intent.putExtra(EXTRA_QUERY, initialQuery);
        startCompat(context, intent);
    }

    public static void showListening(Context context, String prompt) {
        Intent intent = new Intent(context, FloatingSheetService.class);
        intent.setAction(ACTION_LISTEN);
        if (prompt != null) intent.putExtra(EXTRA_PROMPT, prompt);
        startCompat(context, intent);
    }

    public static void hide(Context context) {
        if (sInstance == null) return;   // ★ round3 #3：服务不在 = 面板不在，别为了「收起」去冷启动一个服务
        Intent intent = new Intent(context, FloatingSheetService.class);
        intent.setAction(ACTION_HIDE);
        startCompat(context, intent);
    }

    /**
     * ★ round3 #3：启动本服务。服务还不存在（冷启动）且 Android 8+ 时用 startForegroundService ——
     * 进程没有前台组件时普通 startService 会被后台启动限制拒绝（App 内抛 IllegalStateException，指令随之丢失）。
     * 本服务 onCreate 第一件事就是 startForeground，满足 5 秒约定；onStartCommand 也会再声明一次。
     */
    private static void startCompat(Context context, Intent intent) {
        boolean cold = sInstance == null;
        try {
            if (cold && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.i(TAG, "[COLD] 面板服务未运行，以前台服务方式启动: " + intent.getAction());
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (IllegalStateException ise) {
            Log.w(TAG, "[COLD] startService 被后台启动限制拒绝，改用 startForegroundService: " + ise.getMessage());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent);
                } catch (Throwable t) {
                    Log.e(TAG, "[COLD] 面板服务启动失败，指令丢失: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "[COLD] 面板服务启动失败: " + t.getMessage());
        }
    }

    /** ★ R13：下一次 setListeningPrompt 是 ACTION_LISTEN（新唤醒）而不是识别阶段的提示更新。只在主线程读写。 */
    private volatile boolean mNextListenIsWake;

    public void setListeningPrompt(final String prompt) {
        boolean wake = mNextListenIsWake;
        mNextListenIsWake = false;
        setListeningPrompt(prompt, wake);
    }

    /** @param newWake true = ACTION_LISTEN（新一次唤醒：新界面清上一轮卡片、点亮边缘光）；false = 识别阶段的提示更新。 */
    private void setListeningPrompt(final String prompt, final boolean newWake) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                // ★ R13 卡死修复：「识别中…」只是给<b>屏上</b>那张卡换文案。面板已经收起（用户关了 / 错误态到点收了）时
                //   绝不能借它把面板重新弹出来停在「正在想」—— 2026-10-13 飞行模式下的「第二次唤醒卡在正在想」就是这样来的
                //   （根因在 WakeService 收窗误判在途，已修；这里是第二道闸）。
                if (mUseV2 && !newWake && prompt != null && prompt.contains("识别中") && !isPanelOnScreen()) {
                    Log.w(TAG, "[WAKE-UI] 面板已不在屏上，忽略识别中提示（不复活面板）: " + com.onesay.assistant.v2.util.LogText.u(prompt));
                    return;
                }
                showSheet();
                cancelAutoHide();
                if (mSheetView != null) {
                    mSheetView.setSuggestionsVisible(true);   // ★ M-2：新唤醒恢复推荐芯片
                    if (mUseV2 && newWake && mSheetView.chatActive()) chatRewake();   // 真机 B
                    if (mUseV2 && newWake) mSheetView.v2Wake();
                    if (mUseV2 && prompt != null && prompt.contains("识别中")) {
                        // 「识别中…（已说 N 秒）」「云端识别中…」：话已经说完了 → 新界面是「正在想…」（光吸进卡片），不是还在听
                        mSheetView.v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.THINK);
                    } else {
                        mSheetView.updateState("正在聆听", Color.parseColor("#30D158"), prompt != null ? prompt : "我在听，请讲...");
                    }
                }
            }
        });
    }

    public void updateTranscript(final String text) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                cancelAutoHide();
                if (mSheetView != null) {
                    mSheetView.updateState("正在聆听", Color.parseColor("#30D158"), "你说：“" + text + "”");
                }
            }
        });
    }

    /**
     * ★ 终态提示（2026-10-02 晚新增；2026-10-03 改常驻）。
     *
     * 只更新**已有**面板的文案；面板不在屏幕上时什么都不做。
     * 与 {@link #setListeningPrompt} 的关键区别是：**绝不主动把面板弹出来**。
     *
     * 它服务的场景是"本次唤醒已经彻底结束、不可能再有后续动作"（没听清、云端也没救回来）。
     *
     * <p><b>2026-10-03 用户修订为常驻</b>：用户原话
     * "唤醒后超过录入时长还是会自动收起窗口，改掉"。
     * 原实现安排 1.8s / 2.2s 后自动滑走，用户还没看清"没听清"写的是什么、也不知道
     * 下次该怎么唤醒，面板就没了。现在与其余状态统一为**常驻 + 手动关闭**。
     * （原注释里"留在屏幕上只会让用户以为它还在听"的判断被用户否掉 ——
     *   badge 已经是橙色"没听清"而非绿色"正在聆听"，不会造成误解。）
     *
     * 原实现还有一个缺陷：{@code mWindowExpiredRunnable} 用 setListeningPrompt 写下了
     * "正在云端重判..."，而云端失败分支只打一行日志就 return —— 面板既不复位也不收起。
     * 实测唤醒后 30 秒面板仍停在"正在云端重判..."（已截图取证）。
     *
     * <p><b>常驻的「没听清」带着上一轮的推荐芯片、副标题还停在
     * 「正在听你说…」，遮住被测应用下半屏。现在：清芯片、副标题改成终态文案，错误显示
     * {@link com.onesay.assistant.v2.ui.ErrorStatePolicy#ERROR_VISIBLE_MS}（2.5 s）后回中性态并自动收起；
     * 确认卡未决 / 任务在跑 / 用户在文字输入时不收（规则与单测见 ErrorStatePolicy）。
     */
    public void showTerminalPrompt(final String prompt) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                cancelAutoHide();
                if (mSheetView == null) return;
                if (!mIsAttached && mSheetView.getParent() == null) return; // 不在屏幕上就别弹
                // badge 用「没听清」而非「正在聆听」：这次唤醒已经结束，绿色的"在听"
                // 会让人以为麦克风还开着。橙色 + 不脉冲才是诚实的终态。
                // 注意别用带"执行"字样的 badge —— FloatingSheetView.updateState 是
                // 按 contains 分派的，会被吞进 setExecuting 分支（本轮体检的另一个缺陷）。
                mSheetView.updateState("没听清", Color.parseColor("#FF9F0A"),
                        prompt != null ? prompt : "没听清指令，请重新唤醒...");
                // ★ M-2：updateState 的「没听清」落进 else 分支不改副标题 → 会残留「正在听你说…」，这里显式写终态
                mSheetView.markTerminalState(Color.parseColor("#FF9F0A"), "没听清", "本次唤醒已结束");
                // 用户修订曾改为"常驻、手动关闭"（嫌 1.8 s 太快看不清）；
                //   常驻错误态遮挡被测界面 → 折中为显示 2.5 s 后回中性态并自动收起。
                mSheetView.setDismissLabel("✕ 关闭");
                cancelAutoHide();
                applyErrorPolicy(com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.NO_SPEECH_NEW_WAKE);
            }
        });
    }

    /** ★ R13b：上次因离线打开网络设置的时间（节流用）。 */
    private long mLastOfflineSettingsAt = 0L;

    /**
     * ★ R13b 离线兜底（用户 2026-10-14 修订：没网直接弹网络设置，而不是按着头退回去）。
     * WakeService 断句 / 收窗时探测到没网 → 卡片显示「没有网络 · 正在打开网络设置」约 0.7 s →
     * 收起面板并按模式打开设置：飞行模式开着 → 飞行模式开关页；没开 → Wi‑Fi 设置页（移动数据不管）。10 s 内重复只出卡片、不再顶设置页。
     * 打不开设置页时退回 R13 的错误策略（2.5 s 收起）。面板不在屏上时不弹（与 showTerminalPrompt 同规则）。
     */
    public void showOfflinePrompt(final boolean quiet) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                cancelAutoHide();
                if (mSheetView == null) return;
                if (!mIsAttached && mSheetView.getParent() == null) return;
                String badge = com.onesay.assistant.v2.ui.wake.WakeCopy.BADGE_OFFLINE;
                long now = android.os.SystemClock.elapsedRealtime();
                final boolean open = com.onesay.assistant.v2.net.OfflineSettingsPolicy.shouldOpen(now, mLastOfflineSettingsAt);
                if (open) mLastOfflineSettingsAt = now;
                String line = open ? com.onesay.assistant.v2.ui.wake.WakeCopy.OFFLINE_OPEN_SETTINGS
                        : "没有网络：语音识别要联网，可以点「⌨ 文字」输入本地指令";
                if (!quiet || open) mSheetView.updateState(badge, Color.parseColor("#FF9F0A"), line);
                mSheetView.markTerminalState(Color.parseColor("#FF9F0A"), badge,
                        open ? "正在打开网络设置" : "本次唤醒已结束（没有网络）");
                if (!quiet) playVoiceCue(com.onesay.assistant.v2.tts.VoiceTemplate.OFFLINE);   // ★ r2/voice-templates
                mSheetView.setDismissLabel("✕ 关闭");
                if (!open) {
                    applyErrorPolicy(quiet ? com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.NO_SPEECH_CONTINUE
                            : com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.NO_SPEECH_NEW_WAKE);
                    return;
                }
                mMainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (openNetworkSettings()) {
                            hideSheet(false);
                        } else {
                            applyErrorPolicy(com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.NO_SPEECH_NEW_WAKE);
                        }
                    }
                }, com.onesay.assistant.v2.net.OfflineSettingsPolicy.HANDOFF_DELAY_MS);
            }
        });
    }

    /** 按模式打开设置：飞行模式开着 → 飞行模式页；否则 Wi‑Fi 页；解析不到依次退到「连接」总页、设置首页。返回是否成功拉起。 */
    private boolean openNetworkSettings() {
        boolean airplane = false;
        try {
            airplane = android.provider.Settings.Global.getInt(getContentResolver(),
                    android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        } catch (Throwable ignore) {}
        String[] actions = com.onesay.assistant.v2.net.OfflineSettingsPolicy.target(airplane)
                == com.onesay.assistant.v2.net.OfflineSettingsPolicy.Target.AIRPLANE
                ? new String[]{android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS,
                    android.provider.Settings.ACTION_WIRELESS_SETTINGS, android.provider.Settings.ACTION_SETTINGS}
                : new String[]{android.provider.Settings.ACTION_WIFI_SETTINGS,
                    android.provider.Settings.ACTION_WIRELESS_SETTINGS, android.provider.Settings.ACTION_SETTINGS};
        for (String a : actions) {
            try {
                Intent i = new Intent(a);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                if (i.resolveActivity(getPackageManager()) == null) continue;
                startActivity(i);
                Log.i(TAG, "[OFFLINE] 已打开网络设置：" + a + "（飞行模式=" + airplane + "）");
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "[OFFLINE] 打开 " + a + " 失败：" + t);
            }
        }
        return false;
    }

    /**
     * 继续聆听会话的"云端识别中"轻提示 —— 只改状态行，<b>不擦会话正文</b>。
     *
     * <p>与 {@link #setListeningPrompt} 的区别：后者会经 updateState("正在聆听")
     * 清转录 + 隐藏结果卡（那是"新一次唤醒"的语义）；本方法给"接着聊"的会话用，
     * 云端判读期间上一轮问答应原样留在屏幕上。
     */
    public void showCloudBusyQuiet() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                cancelAutoHide();
                if (mSheetView == null) return;
                if (!mIsAttached && mSheetView.getParent() == null) return;
                mSheetView.markTerminalState(Color.parseColor("#64D2FF"), "正在识别", "云端识别中…");
            }
        });
    }

    /** 聆听/识别状态轻提示（只改状态行，不擦会话正文；面板不在屏上时不弹出）。 */
    public void showListeningStateQuiet(final String title, final String sub) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mSheetView == null) return;
                if (!mIsAttached && mSheetView.getParent() == null) return;
                mSheetView.markListeningState(Color.parseColor("#30D158"), title, sub);
            }
        });
    }

    /**
     * 继续聆听会话的"轻终态" —— 只改状态行，<b>绝不动会话正文</b>。
     *
     * <p>场景：用户在面板里点「✦ 语音」接着聊、但一直没说话 → 捕获窗超时。
     * 此时屏幕上还留着上一轮问答（会话并没有结束，只是这一轮没听到），
     * 所以不能像新唤醒那样把转录区替换成"请重新唤醒"。
     */
    public void showTerminalPromptQuiet(final String sub) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                cancelAutoHide();
                if (mSheetView == null) return;
                if (!mIsAttached && mSheetView.getParent() == null) return;
                mSheetView.markTerminalState(Color.parseColor("#FF9F0A"), "没听清",
                        sub != null ? sub : "可再说一次，或点「⌨ 文字」输入");
                cancelAutoHide();
                applyErrorPolicy(com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.NO_SPEECH_CONTINUE);   // ★ M-2
            }
        });
    }

    // ==================== r2/p0 P0-2：语音识别失败分型卡（未配置 / Key 失效 / 超时 / 限流 / 服务端 / 网络） ====================

    /** 本类挂上的识别失败芯片的属主标记（收起时只收自己的，不误收别的插件的芯片）。 */
    private static final Object ASR_CHIPS_OWNER = new Object();

    /**
     * 识别失败卡（WakeService 按 {@link com.onesay.assistant.v2.asr.AsrFailure#classify} 分型后调用；任何线程可调）。
     * 未配置 / Key 失效 →「去配置」芯片、不复位不自动收起；可重试 →「重试」芯片、{@link ErrorStatePolicy#RETRY_CARD_HIDE_MS} 后收起；
     * 其余按原「云端识别失败」规则。面板不在屏上时不弹（与 showTerminalPrompt 同规则）。
     *
     * @param quiet    继续聆听会话：只改状态行，不抹上一轮问答
     * @param canRetry 录音缓冲还在、本窗还没重试过
     */
    public void showAsrFailure(final com.onesay.assistant.v2.asr.AsrFailure.Kind kind, final boolean quiet, final boolean canRetry) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                cancelAutoHide();
                if (mSheetView == null) return;
                if (!mIsAttached && mSheetView.getParent() == null) return;
                String badge = com.onesay.assistant.v2.asr.AsrFailure.badge(kind);
                String line = com.onesay.assistant.v2.asr.AsrFailure.line(kind, canRetry);
                int col = Color.parseColor("#FF9F0A");
                if (!quiet) mSheetView.updateState(badge, col, line);
                mSheetView.markTerminalState(col, badge, quiet ? line : "本次唤醒已结束");
                mSheetView.setDismissLabel("✕ 关闭");
                boolean setup = com.onesay.assistant.v2.asr.AsrFailure.needsSetup(kind);
                boolean retry = canRetry && com.onesay.assistant.v2.asr.AsrFailure.retryable(kind);
                Log.i(TAG, com.onesay.assistant.v2.asr.AsrFailure.logTag(kind) + " 卡片 quiet=" + quiet + " 重试=" + retry);
                if (!quiet) playVoiceCue(com.onesay.assistant.v2.tts.VoiceTemplate.forAsrFailure(kind));   // ★ r2/voice-templates
                applyErrorPolicy(setup ? com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.ASR_NEEDS_SETUP
                        : retry ? com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.ASR_RETRYABLE
                        : quiet ? com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.NO_SPEECH_CONTINUE
                        : com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.ASR_FAILED);
                showAsrChips(com.onesay.assistant.v2.asr.AsrFailure.chips(kind, canRetry));   // 放在 applyErrorPolicy 之后：它会清推荐芯片
            }
        });
    }

    private void showAsrChips(final java.util.List<String> labels) {
        if (mSheetView == null || labels == null || labels.isEmpty()) return;
        ActionChipsView v;
        android.view.View existing = mSheetView.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) {
            v = (ActionChipsView) existing;
        } else {
            v = new ActionChipsView(this);
            int idx = Math.min(2, mSheetView.getChildCount());
            mSheetView.addView(v, idx, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        v.setListener(new ActionChipsView.Listener() {
            @Override
            public void onChipClicked(int index) {
                if (index < 0 || index >= labels.size()) return;
                String l = labels.get(index);
                hideAsrChips();
                if (com.onesay.assistant.v2.asr.AsrFailure.CHIP_SETUP.equals(l)) {
                    openAsrSetup();
                } else if (com.onesay.assistant.v2.asr.AsrFailure.CHIP_RETRY.equals(l)) {
                    cancelAutoHide();
                    if (!WakeService.retryCloudFromUi()) {
                        // 会话已作废 / 录音没了：别骗用户「在识别」
                        showTerminalPromptQuiet("这段录音已经不在了，请再说一次");
                    } else {
                        showCloudBusyQuiet();
                    }
                }
            }
        });
        v.showChips(labels);
        v.owner = ASR_CHIPS_OWNER;
    }

    /** 只收本类挂的识别失败芯片。 */
    private void hideAsrChips() {
        if (mSheetView == null) return;
        android.view.View existing = mSheetView.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView && ((ActionChipsView) existing).owner == ASR_CHIPS_OWNER) {
            ((ActionChipsView) existing).hideChips();
        }
    }

    /**
     * 「去配置」：打开控制台的语音识别 Key 设置（page=console:asr.key → Console.openPage）。
     * 控制台深链白名单（r2/fixes 的 ConsoleDeepLink）集成时要登记 {@link com.onesay.assistant.v2.asr.AsrFailure#CONSOLE_PAGE}。
     */
    private void openAsrSetup() {
        try {
            WakeService.cancelCaptureFromUi();
        } catch (Throwable ignored) {
        }
        try {
            Intent it = new Intent(this, com.onesay.assistant.v2.ui.MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            it.putExtra("page", "console:" + com.onesay.assistant.v2.asr.AsrFailure.CONSOLE_PAGE);
            it.putExtra(com.onesay.assistant.v2.ui.ConsoleDeepLink.EXTRA_TOKEN, com.onesay.assistant.v2.ui.ConsoleDeepLink.token());   // ★ IPC-3：内部深链口令
            it.putExtra("title", "语音识别 Key");
            startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "[ASR-FAIL] 打开控制台失败: " + t.getMessage());
        }
        hideSheet(false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mCreatedAt = SystemClock.elapsedRealtime();
        sInstance = this;
        registerSystemDialogsReceiver();   // ★ r2/sysux P1-4：按 Home / 最近任务 → 收起面板
        com.onesay.assistant.v2.timeline.TimelineNotes.add(mTimelineNotes);   // ★ borrow-1112：等待期间画面变化 / 已切换到备用模型 → 时间轴说明行
        com.onesay.assistant.v2.contacts.ContactSource.installFor(this);   // AgentLoop 的人名 → 号码（本机解析）
        com.onesay.assistant.plug.richout.host.RichOutHost.install(this);
        com.onesay.assistant.v2.router.ctx.ContextPrefs.apply(this);   // ★ int/1102：会话历史 token 预算 / 模型窗口覆盖（prefs bixby_conv）   // [richout] 富内容回答插件开关接上 prefs / kill 文件
        // ★ 前置声明前台服务：MainActivity/BootReceiver 用 startForegroundService 启动本服务，
        //   Android 8 要求 5 秒内必须调用 startForeground，否则抛 ANR 并被系统强杀
        //   （2026-10-02 实测复现：Bringing down service while still waiting for start foreground）。
        startForeground(2027, buildServiceNotification());
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        installKeyguardHostHooks();   // ★ r2/kb-fix：锁屏上打字时托底不退；托底一退就收键盘 / 收面板
        com.onesay.assistant.v2.ui.ime.ImeProbeWindow.get(this).ensureAttached();   // ★ r2/kb-fix：键盘探针窗先于卡片窗挂（z 序在下）
        // 语音链路真正的入口就是本服务（常驻），
        //   必须在这里把**自动挖掘的系统接口**也加载进注册表 ——
        //   否则"用户没打开过控制台就说话"时，挖掘出的界面接口对路由器不可见。
        //   init 内部幂等，MainActivity 也调一次不会重复注册。
        CapabilityRegistry.getInstance().init(getApplicationContext());
        // 加载「始终允许」白名单（权限确认策略持久化，重启后仍生效）
        com.onesay.assistant.v2.executor.ConsentPolicy.init(getApplicationContext());
        // 本服务的执行路径已全部迁移到 ExecutionGate 令牌 → 打开严格模式，
        //   旧布尔 isConfirmed=true 不再能绕过确认卡（E4）。
        com.onesay.assistant.v2.executor.ExecutionGate.setStrictConfirm(true);
        mRouter = UnifiedRouter.getInstance(this);
        mExecutor = UnifiedExecutor.getInstance(this);

        initView();
        // [kernel] 装配插件（onInit / onEnable）后按顺序 onServiceCreate：
        //   [s1] 胶囊 / 取件码浮卡 / 剪贴板芯片 / 圈选即问 → [s3] 定时任务 / 提醒 / 待办上岛（补触发到期任务）
        com.onesay.assistant.v2.kernel.FeatureKernel.get().onServiceCreate(this);
        startColdWarmup();   // ★ round3 #3：后台预热路由层，就绪前到达的指令排队
    }

    // ---------- 冷启动就绪闸门 ----------
    //   onCreate 结束后后台预热（技能缓存库迁移 / S2 记忆库与角色快照 / 分诊）——这些原本都压在第一条指令的路由线程上；
    //   预热完成前到达的指令（面板注入 / 语音转写）排队，就绪后按到达顺序补发；超过 COLD_QUEUE_TIMEOUT_MS 仍未就绪 →
    //   放行闸门（fail-open，绝不让服务永远排队），已超时的指令丢弃并在面板说明。排队逻辑：util.ReadyQueue（JVM 单测）。
    /** 排队指令最长等待。 */
    static final long COLD_QUEUE_TIMEOUT_MS = 10000L;
    /** 多条排队指令补发间隔（后一条会作废前一条在途的流式请求，与连说两句同语义；间隔让每条都能走完分诊并留下日志）。 */
    static final long COLD_REPLAY_GAP_MS = 1500L;
    private final com.onesay.assistant.v2.util.ReadyQueue<String> mColdQueue =
            new com.onesay.assistant.v2.util.ReadyQueue<String>(COLD_QUEUE_TIMEOUT_MS, 4);
    private long mCreatedAt;

    private void startColdWarmup() {
        Log.i(TAG, "[COLD] 面板服务 onCreate 完成（" + (SystemClock.elapsedRealtime() - mCreatedAt) + "ms），后台预热路由层…");
        com.onesay.assistant.v2.util.Threads.start("FssColdWarmup", new Runnable() {
            @Override
            public void run() {
                long t0 = SystemClock.elapsedRealtime();
                try {
                    mRouter.getL1().cache();   // 技能缓存库打开 + 旧库一次性迁移
                    com.onesay.assistant.v2.s2.S2Hooks.engine(FloatingSheetService.this);   // 记忆库 + 默认应用角色快照
                    com.onesay.assistant.v2.router.TriageDecider.decide("现在几点", com.onesay.assistant.v2.router.TriageDecider.Signals.offline());
                } catch (Throwable t) {
                    Log.w(TAG, "[COLD] 预热异常（不影响就绪）: " + t.getMessage());
                }
                final long warm = SystemClock.elapsedRealtime() - t0;
                mMainHandler.post(new Runnable() {
                    @Override public void run() {
                        markColdReady("预热完成 " + warm + "ms");
                    }
                });
            }
        });
    }

    /** 主线程：就绪 → 按到达顺序补发排队指令（已超时的丢弃并说明）。 */
    private void markColdReady(String why) {
        long now = SystemClock.elapsedRealtime();
        mMainHandler.removeCallbacks(mColdTimeoutRunnable);
        com.onesay.assistant.v2.util.ReadyQueue.Drain<String> d = mColdQueue.markReady(now);
        Log.i(TAG, "[COLD] 面板服务就绪（" + why + "，创建后 " + (now - mCreatedAt) + "ms），排队指令 "
                + d.ready.size() + " 条待补发" + (d.expired.isEmpty() ? "" : "，" + d.expired.size() + " 条已超时丢弃"));
        replayCold(d, now);
    }

    private void replayCold(com.onesay.assistant.v2.util.ReadyQueue.Drain<String> d, long now) {
        for (com.onesay.assistant.v2.util.ReadyQueue.Item<String> it : d.expired) {
            Log.w(TAG, "[COLD] 排队指令等待 " + it.waitedMs(now) + "ms 超时，丢弃: " + it.value);
        }
        if (!d.expired.isEmpty() && d.ready.isEmpty() && mSheetView != null) {
            mSheetView.updateState("没听清", Color.parseColor("#FF9F0A"), "启动较慢，刚才的指令没来得及执行，请再说一次");
            mSheetView.markTerminalState(Color.parseColor("#FF9F0A"), "启动超时", "刚才的指令已丢弃");
            applyErrorPolicy(com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.INJECTED_QUERY_FAILED);
        }
        long delay = 0;
        for (final com.onesay.assistant.v2.util.ReadyQueue.Item<String> it : d.ready) {
            final long waited = it.waitedMs(now);
            mMainHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    Log.i(TAG, "[COLD] 补发排队指令（排队 " + waited + "ms）: " + it.value);
                    onSubmitCommand(it.value);
                }
            }, delay);
            delay += COLD_REPLAY_GAP_MS;
        }
    }

    private final Runnable mColdTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            boolean wasReady = mColdQueue.isReady();
            com.onesay.assistant.v2.util.ReadyQueue.Drain<String> d = mColdQueue.expire(now, true);
            if (!wasReady) {
                Log.w(TAG, "[COLD] 预热 " + COLD_QUEUE_TIMEOUT_MS + "ms 未完成，放行闸门（fail-open），补发 "
                        + d.ready.size() + " 条、丢弃 " + d.expired.size() + " 条");
            }
            replayCold(d, now);
        }
    };

    /** 冷启动闸门是否已就绪（调试驱动 / 验收脚本探针用）。 */
    public boolean isColdReady() {
        return mColdQueue.isReady();
    }

    /** ★ feat/api-first：任务通知标题（与面板常驻通知同一个应用名）。 */
    static final String PANEL_NOTIFY_TITLE = com.onesay.assistant.v2.BuildVariant.APP_TITLE_V2;

    private Notification buildServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                    "bixby_sheet_channel", "OneSay 悬浮面板", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(chan);
            return new Notification.Builder(this, "bixby_sheet_channel")
                    .setContentTitle(com.onesay.assistant.v2.BuildVariant.APP_TITLE_V2 + " 悬浮面板")
                    .setContentText("语音助手悬浮面板服务运行中")
                    .setSmallIcon(getApplicationInfo().icon)
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle(com.onesay.assistant.v2.BuildVariant.APP_TITLE_V2 + " 悬浮面板")
                .setContentText("语音助手悬浮面板服务运行中")
                .setSmallIcon(getApplicationInfo().icon)
                .setOngoing(true)
                .build();
    }

    private void initView() {
        mSheetView = new FloatingSheetView(this);
        mSheetView.setOnActionListener(this);

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
        );

        mParams.gravity = Gravity.BOTTOM;
        mParams.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;   // ★ r2/screenoff-wake
        mParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;

        // 全屏小爱水光动效窗口（不拦截触控，层级在 Sheet 之下）
        mRippleView = new WakeRippleView(this);
        mRippleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        mRippleParams.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;   // ★ r2/screenoff-wake：水光 / 光晕窗同面板

        // ★ R13：新界面的光晕窗（同上 flag；舞台回调 = 形变握手）
        mGlowView = new com.onesay.assistant.v2.ui.wake.WakeGlowView(this);
        mGlowView.setStageListener(new com.onesay.assistant.v2.ui.wake.WakeGlowView.StageListener() {
            @Override
            public void onStageFirstFrame() {
                if (!mCardRemovalPending) return;
                mCardRemovalPending = false;
                // 舞台已接手：卡片窗先 GONE（WMS 立刻不显示、不收触摸），真正 removeView（同步销毁硬件渲染上下文，真机一次
                // 上百 ms）推迟到岛落定之后，免得卡在形变中途
                mSheetView.setVisibility(View.GONE);
                mCardRemovalScheduled = true;
                mMainHandler.removeCallbacks(mDeferredCardRemoval);
                mMainHandler.postDelayed(mDeferredCardRemoval, 1500);
                Log.i(TAG, "[WAKE-MORPH] 卡片窗已隐藏（舞台第一帧已画出），稍后摘窗");
            }

            @Override
            public void onContact(android.graphics.RectF merged, float vw, float vh) {
                if (mCardRemovalScheduled) {   // 岛 APPEAR ≈ 0.25 s + 鼓包：落定后再摘
                    mMainHandler.removeCallbacks(mDeferredCardRemoval);
                    mMainHandler.postDelayed(mDeferredCardRemoval, 650);
                }
                boolean ok = com.onesay.assistant.v2.s1.S1Hooks.islandAppearFrom(merged, vw, vh);
                if (!ok) com.onesay.assistant.v2.s1.S1Hooks.releaseIslandMorph();
                if (mSheetView != null) mSheetView.v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.LANDED_IN_ISLAND);
                scheduleGlowDetachCheck();
            }

            @Override
            public void onUnmorphSettled() {
                if (mSheetView != null) mSheetView.v2RevealAfterUnmorph();
            }
        });
        mSheetView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (!mUseV2 || mGlowView == null || v.getParent() == null) return;
                // R13 卡顿排查：getWindowVisibleDisplayFrame 是一次 WMS binder（真机 10–20 ms），卡片每次重排都问一遍没必要 ——
                //   只在每次挂窗后的第一次布局、以及卡片窗位置 / 尺寸真变了时问（导航栏显隐会让窗口底边变）
                if (mGlowVisAsked && l == ol && t == ot && r == or && b == ob) return;
                mGlowVisAsked = true;
                android.graphics.Rect vis = new android.graphics.Rect();
                v.getWindowVisibleDisplayFrame(vis);   // 不含导航栏的可见区（不受卡片入场平移影响）
                mGlowView.setVisibleBottom(vis.bottom);
            }
        });
        mSheetView.setChatServiceHost(new ChatServiceHost());   // ★ ui-K1 对话窗：圈选 / 相机对话 / 选图识图 / 分享
        mSheetView.setV2Host(new FloatingSheetView.V2Host() {
            @Override
            public void onStageTransition(com.onesay.assistant.v2.ui.wake.WakeStage.Transition t) {
                applyStageEffects(t);
            }

            @Override
            public void onOutsideTapV2() {
                handleOutsideTap();
            }
        });

        // 点击外部空白区域自动滑出收起浮层并取消捕获
        mSheetView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    return handleOutsideTap();
                }
                return false;
            }
        });
        prewarmV2Later();
    }

    /**
     * 点外部（窗口外的 ACTION_OUTSIDE，或新界面卡片四周的透明留白）。滑入保护期内忽略；长任务在跑 → 收进岛；否则收起。
     */
    private boolean handleOutsideTap() {
        // ★挂载后 1.2s 内的 ACTION_OUTSIDE 一律忽略。
        //   这一段是滑入动画期，用户"抬手离开屏幕/按键"很容易被系统合成为一个
        //   外部触摸事件 —— 它不是"用户想收起面板"。实测（18:31）挂载 1027ms
        //   后就被自己的抬手动作收掉了，用户看到的是"面板闪一下就没了"。
        //   1.2s 覆盖动画时长（约 1s）并留 200ms 余量；此后用户真的点外部
        //   仍会正常收起，不损失任何可感知能力。
        long sinceShown = SystemClock.elapsedRealtime() - mShownAt;
        long guard = mUseV2 ? OUTSIDE_TAP_GUARD_V2_MS : OUTSIDE_TAP_GUARD_MS;
        if (mShownAt > 0 && sinceShown < guard) {
            Log.i(TAG, "外部触摸事件落在滑入保护期内（挂载后 " + sinceShown
                    + "ms < " + guard + "ms），忽略，不收面板");
            return true;
        }
        // [richout] 阅读器 / 全屏看图 / 离开确认框叠在面板上方：那上面的触摸对面板是「外部」，不算用户想收面板
        if (com.onesay.assistant.plug.richout.host.RichOutHost.overlayOpen()) {
            Log.i(TAG, "外部触摸落在富内容浮层上（阅读器 / 看图），不收面板");
            return true;
        }
        // [s1] 长任务（AgentLoop）在跑：点外部 = 转后台、面板收成胶囊，而不是取消任务
        if (com.onesay.assistant.v2.s1.S1Hooks.onOutsideTap(FloatingSheetService.this)) return true;
        Log.i(TAG, "检测到点击外部屏幕区域 -> 立即收起浮层（挂载后 "
                + sinceShown + "ms）");
        WakeService.cancelCaptureFromUi();
        dismissByUser(); // 用户主动触外：旧界面立即强退；新界面卡片沉下退场（同样立即结束会话）
        endSessionOnClose(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.CloseReason.USER_OUTSIDE);
        return true;
    }

    /** [island v3] 旋转 / 分辨率变化：岛重算锚点（状态栏下 2dp、水平居中）。 */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        com.onesay.assistant.v2.s1.S1Hooks.onDisplayChanged();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            // ★ round3 #3：经 startForegroundService 启动时（冷启动），每次都满足「5 秒内 startForeground」约定（幂等）
            try { startForeground(2027, buildServiceNotification()); } catch (Throwable ignore) {}
            String action = intent.getAction();
            if (ACTION_S3_FIRE.equals(action)) {
                com.onesay.assistant.v2.s3.S3Runner r = com.onesay.assistant.v2.s3.S3Runner.get();   // [s3] 只处理到期任务，不弹面板
                if (r != null) r.fireDue(String.valueOf(intent.getStringExtra("why")));
            } else if (ACTION_HIDE.equals(action)) {
                // 收起 = 这一窗作废（代际号 +1、撤掉窗到点回调、迟到的识别结果按会话号丢弃），
                //   以前只收面板不停采集，上一轮窗到点的「没听清」会落到下一张卡上。已拿到指令 / 已收尾的窗不受影响。
                WakeService.cancelCaptureFromUi("HIDE");
                hideSheet(true); // 外部请求 hide，立即强制收起
            } else if (ACTION_LISTEN.equals(action)) {
                String prompt = intent.getStringExtra(EXTRA_PROMPT);
                // [kernel] WAKE_EARLY —— [s1] 收起在岛上的前台任务被新唤醒接管（按取消处理）；[s3] 定时任务 / 提醒 / 取件码不受影响（v3 多任务）
                com.onesay.assistant.v2.kernel.FeatureKernel.get().onWakeEarly(this);
                prepareWakeWindowFlags();   // ★ R14：新界面卡片从挂窗起就不可获焦（识屏不再需要中途 updateViewLayout）
                // [kernel] WAKE —— [screenref] 新一次唤醒：面板挂载前开始采集屏幕上下文（排第一；采集期间面板暂不抢焦点）
                //   → [roles] 新一轮：收起上一轮残留的安装提示；随后比对插件开关
                com.onesay.assistant.v2.kernel.FeatureKernel.get().onWake(this);
                mNextListenIsWake = true;   // ★ R13：这一次是新唤醒（新界面清卡片、点亮边缘光）
                setListeningPrompt(prompt);
            } else if (ACTION_OPEN_PANEL.equals(action)) {
                Log.i(TAG, "[TASK-NOTIFY] 点了任务通知 → 挂回面板");
                s1AttachSheet();   // ★ feat/api-first：回到面板看时间轴（任务照跑）
            } else if (ACTION_SHOW.equals(action)) {
                // 只有明确 ACTION_SHOW 才弹出面板（防止预启动/系统重启时意外弹出）
                showSheet();
                String query = intent.getStringExtra(EXTRA_QUERY);
                if (query != null && query.trim().length() > 0) {
                    onSubmitCommand(query.trim());
                } else if (intent.hasExtra(EXTRA_QUERY) && mSheetView != null) {
                    // ★ M-2：panel_query 注入了 query 但内容为空（引号被 shell 吃掉等）→ 短暂报错后收起，别常驻
                    cancelAutoHide();
                    mSheetView.updateState("没听清", Color.parseColor("#FF9F0A"), "没有收到有效指令");
                    mSheetView.markTerminalState(Color.parseColor("#FF9F0A"), "没听清", "注入的指令为空");
                    applyErrorPolicy(com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.INJECTED_QUERY_FAILED);
                }
            }
            // 无 action（预启动/系统自愈重启）：仅保证 Service 实例在线，不做任何 UI 动作
        }
        return START_STICKY; // 常驻：系统回收后自动重启
    }

    private final Runnable mHideFallbackRunnable = new Runnable() {
        @Override
        public void run() {
            Log.w(TAG, "hideSheet 动画超时兜底触发，强制移除悬浮窗");
            detachFromWindowImmediate();
        }
    };

    /**
     * 真正安全从 WindowManager 摘除 View，彻底重置全部状态与动画
     */
    private synchronized void detachFromWindowImmediate() {
        mMainHandler.removeCallbacks(mHideFallbackRunnable);
        cancelAutoHide();
        // ★ 面板一旦离屏，在途的流式请求就没有意义了：置位令牌让读取循环退出，
        //   别继续抱着连接把整段答案读完（用户已经看不到了）。
        //   所有退出路径（✕ 按钮 / 点外部 / 下拉手柄 / 自动收起）最终都汇到这里，
        //   所以只需要在这一个地方收口。
        cancelActiveStream();
        WakeService.onPanelGone("离屏");   // ★ r2/sysux P1-2：面板离屏 = 整轮结束，归还音频焦点
        // 面板一旦离屏，这次"会话"就结束了。
        //   与 cancelActiveStream 同一个汇点 —— 三条退出路径（✕ / 点外部 / 下拉）
        //   以及跳转自动收起，全部经过这里。
        //   下一句话必然是"新一次唤醒"，不能带上上轮的残留
        //   （否则用户上次问的股票会在这次莫名其妙地影响模型）。
        endConversationSoft();   // ★ device-1102：面板离屏不再立即清会话（5 分钟内再唤醒 / 再按录音接着上轮），超时才结束
        mHiding = false;
        if (mSheetView != null) {
            try {
                mSheetView.animate().cancel();
                mSheetView.clearAnimation();
            } catch (Throwable ignore) {}
            mSheetView.setVisibility(View.GONE);
            mSheetView.setSuggestionsVisible(true);   // ★ M-2：下次挂载从干净状态开始
            if (mSheetView.getParent() != null || mIsAttached) {
                removeSheetWindow("detach");
                Log.i(TAG, "已从 WindowManager 立即卸载底部 Sheet 悬浮面板");
            }
        }
        mIsAttached = false;
        mCardRemovalPending = false;
        mDismissGen++;
        mMainHandler.removeCallbacks(mV2DismissFallback);
        if (mSheetView != null && mSheetView.isV2()) {
            com.onesay.assistant.v2.ui.wake.WakeStage.Stage st = mSheetView.v2Stage();
            if (st != com.onesay.assistant.v2.ui.wake.WakeStage.Stage.HANDOFF && st != com.onesay.assistant.v2.ui.wake.WakeStage.Stage.ISLAND) {
                mSheetView.v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.RESET);
            }
        }
        // ★ R13：光晕窗（舞台正在把卡片送进岛时保留，接触后自己摘）
        if (mGlowView != null && !mGlowView.stageActive()) detachGlow();

        detachRipple();   // ★ 同步清理小爱水光全屏动效层（旧界面）
    }

    /** 旧界面水光层的唯一摘除点（会话结束 detachFromWindowImmediate / 收成胶囊 s1DetachForCapsule 共用；审计 G2 合并两份重复实现）。 */
    private void detachRipple() {
        if (mRippleView != null) {
            try {
                mRippleView.stopRipple();
                mRippleView.animate().cancel();
                mRippleView.setVisibility(View.GONE);
                if (mRippleView.getParent() != null || mRippleAttached) {
                    mWindowManager.removeViewImmediate(mRippleView);
                }
            } catch (Throwable ignore) {
                try { mWindowManager.removeView(mRippleView); } catch (Throwable ignore2) {}
            }
        }
        mRippleAttached = false;
    }

    private synchronized void showSheet() {
        cancelAutoHide();
        if (mSheetView == null) return;
        mMainHandler.removeCallbacks(mConversationExpire);
        com.onesay.assistant.v2.router.ConversationMemory.getInstance().onPanelShown();   // ★ device-1102：保持期内重新出现 → 接着上轮

        // ★★ 缺陷修复（2026-10-02 晚，真机实测「面板重复呼出」）：
        //   面板**已经挂在屏幕上**时必须原样保留，绝不能先 detach 再 addView。
        //   原实现会走到下面的 detachFromWindowImmediate() 分支 —— 只要 getParent() != null
        //   就先把 View 真从 WindowManager 摘掉，随后 !mIsAttached 又成立，于是 addView()
        //   + animateShow() 重新滑入一次。用户看到的就是"面板消失了一下又弹出来"。
        //   实测一次唤醒会触发两次这种拆挂（日志里成对出现 `已从 WindowManager 立即卸载`
        //   → `已成功挂载`）：
        //     ① 唤醒瞬间  requestWakeInternal → mShowListeningRunnable → showListening
        //     ② 8 秒后    指令窗超时 → setListeningPrompt("本地没听清，正在云端重判...")
        //   已在屏幕上就不动它，是这里唯一正确的行为。
        if (mIsAttached && !mHiding && mSheetView.getParent() != null) {
            return;
        }

        flushPendingCardRemoval();
        // 如果处于正在收起中或有残留 View，先强制清理彻底
        if (mHiding || mSheetView.getParent() != null) {
            detachFromWindowImmediate();
        }

        chooseUiOnce();   // ★ R14：同一次唤醒里 prepareWakeWindowFlags 刚选过就不再重复
        mGlowVisAsked = false;
        if (mUseV2) {
            // ★ R13：新界面 —— 光晕窗（z-order 在底）+ 悬浮小卡；不挂水光
            ensureGlowAttached();
        } else
        // 1. 优先挂载小爱水光全屏光效层（z-order 在底）
        if (!mRippleAttached && mRippleView != null && mRippleView.getParent() == null) {
            try {
                mWindowManager.addView(mRippleView, mRippleParams);
                mRippleAttached = true;
                mRippleView.startRipple();
            } catch (Throwable t) {
                Log.w(TAG, "挂载光效层异常: " + t.getMessage());
            }
        } else if (mRippleView != null) {
            mRippleView.startRipple();
        }

        // 2. 挂载底部流体交互抽屉面板（z-order 在上）
        if (!mIsAttached && mSheetView.getParent() == null) {
            try {
                addSheetWindow("showSheet");
                mIsAttached = true;
                mHiding = false;
                // ★//   记录挂载时刻，供外部点击收起判定加一道保护窗。
                mShownAt = SystemClock.elapsedRealtime();
                mSheetView.animateShow();
                Log.i(TAG, "已成功挂载底部 Sheet 悬浮面板并触发物理弹簧入场");
            } catch (Throwable t) {
                Log.e(TAG, "挂载悬浮窗失败: " + t.getMessage());
            }
        }
    }

    /**
     * 收起悬浮面板。
     * @param immediate true 表示用户主动取消/点击关闭，零延迟强退；false 表示正常延时滑出退场
     */
    public synchronized void hideSheet(boolean immediate) {
        cancelAutoHide();

        // ★ R13：新界面 + 非强制（自动收起 / 错误态到点 / 用户点 ✕ 或外部）→ 卡片沉下退场；会话立即结束
        if (!immediate && mUseV2 && mSheetView != null && mSheetView.isV2() && mSheetView.getParent() != null && !mCardRemovalPending) {
            v2DismissAnimated();
            return;
        }

        // 用户主动取消 -> 立即强制销毁，绝不等待任何动画，绝不受 mHiding 阻塞！
        if (immediate) {
            Log.i(TAG, "用户主动触发取消/关闭 -> 立即强制销毁悬浮面板与光效层");
            detachFromWindowImmediate();
            return;
        }

        if (mRippleView != null && mRippleAttached) {
            mRippleView.fadeOutRipple(null);
        }

        if (!mIsAttached && (mSheetView == null || mSheetView.getParent() == null)) {
            mHiding = false;
            return;
        }

        if (mHiding) {
            Log.d(TAG, "hideSheet 已经在执行收起中");
            return;
        }

        mHiding = true;
        // 注册 220ms 超时安全网：若硬件或渲染丢帧导致动画回调未触发，由 Handler 兜底强制卸载
        mMainHandler.removeCallbacks(mHideFallbackRunnable);
        mMainHandler.postDelayed(mHideFallbackRunnable, 220);

        mSheetView.animateHide(new Runnable() {
            @Override
            public void run() {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        detachFromWindowImmediate();
                    }
                });
            }
        });
    }

    public void hideSheet() {
        hideSheet(false);
    }

    private void scheduleAutoHide(long delayMs) {
        cancelAutoHide();
        mMainHandler.postDelayed(mAutoHideRunnable, delayMs);
    }

    private void cancelAutoHide() {
        mMainHandler.removeCallbacks(mAutoHideRunnable);
        mMainHandler.removeCallbacks(mErrorNeutralRunnable);   // ★ M-2
        mMainHandler.removeCallbacks(mErrorHideRunnable);
        hideAsrChips();   // r2/p0 P0-2：新一轮输入 / 收起 → 识别失败卡的「去配置 / 重试」芯片一并收掉
    }

    /**
     * 前端（语音 / 文字 / 快捷词）提交指令的唯一入口。
     *
     * <p>由"等整段结果再渲染"改为**流式交付**。
     * 用户反馈"Ask 出答案的方式有割裂感" —— 原实现里，检索 1~3 秒 + 生成 1~2 秒
     * 期间面板只有一颗不动的"正在解析"胶囊，然后整段答案一次出现。
     * 现在：检索阶段给阶段提示，生成阶段每 ~70ms 刷一次增量，字是长出来的。
     *
     * <p>注意这条路**同时承载全部非问答指令**（开应用、改设置、多步计划）：
     * {@code routeStreaming} 只在 kind==ASK 且 L2 有 Key 时才走流式，
     * 其余情况立刻回调 {@code onFinal}，行为与改造前逐行一致。
     */
    @Override
    public void onSubmitCommand(final String command) {
        // ★ round3 #3：冷启动预热未完成 → 排队（就绪后按到达顺序补发；超时丢弃并说明）
        if (command != null && !mColdQueue.isReady()) {
            long now = SystemClock.elapsedRealtime();
            com.onesay.assistant.v2.util.ReadyQueue.Offer o = mColdQueue.offer(command, now);
            if (o != com.onesay.assistant.v2.util.ReadyQueue.Offer.RUN_NOW) {
                com.onesay.assistant.v2.util.ReadyQueue.Item<String> dropped = mColdQueue.takeDropped();
                Log.i(TAG, "[COLD] 面板服务未就绪（创建后 " + (now - mCreatedAt) + "ms），指令排队（第 "
                        + mColdQueue.size() + " 条）: " + command
                        + (dropped != null ? "；队列满，丢弃最早一条: " + dropped.value : ""));
                if (mSheetView != null) mSheetView.updateState("正在解析", Color.parseColor("#0A84FF"), "“" + command + "” · 正在启动…");
                mMainHandler.removeCallbacks(mColdTimeoutRunnable);
                long oldest = mColdQueue.oldestAt();
                mMainHandler.postDelayed(mColdTimeoutRunnable,
                        Math.max(50, COLD_QUEUE_TIMEOUT_MS - (now - (oldest >= 0 ? oldest : now)) + 50));
                return;
            }
        }
        cancelAutoHide();
        // ★ arch/agent-loop（B 路）：AgentLoop 的 ask_user 正在等回答 → 这句话（语音 / 打字）就是回答，回填后不开新请求
        //   （不取消在途循环、不新建卡片）。「取消 / 停止」不当回答，照常走取消。
        {
            String[] ans = com.onesay.assistant.v2.agent.AskUserSupport.interceptVoice(command);
            if (ans != null && com.onesay.assistant.v2.s1.S1Hooks.answerAskUser(ans[0], ans[1])) {
                WakeService.claimVoiceTurn(command);
                Log.i(TAG, "[ASK-USER] 用户回答已回填 " + ans[0]);
                if (mSheetView != null) mSheetView.updateState("正在处理", Color.parseColor("#5E5CE6"), "收到：" + ans[1]);
                return;
            }
        }
        // ★ r2/sysux P1-3：这条是不是刚才语音识别出来的 → 语音回合默认语音回答（文字 / 芯片 / 注入 = 文字回合）
        boolean voiceTurn = WakeService.claimVoiceTurn(command);
        TtsSpeaker.setVoiceTurn(voiceTurn);
        if (!voiceTurn) TtsSpeaker.stopAll();
        com.onesay.assistant.v2.s1.VoiceSync.commandReceived();   // ★ arch/voice-sync：没听清计数清零
        // ★ arch/voice-sync：复合任务在跑时说「取消 / 停止 / 别弄了」= 停掉它，播「好，不弄了」（以前会当新指令、任务照跑）
        if (mActiveAgent != null && com.onesay.assistant.v2.tts.VoiceSyncPolicy.isCancelPhrase(command)) {
            UnifiedRouter.Cancel running = mActiveAgent;
            running.cancel();
            timelineStopped();   // ★ feat/api-first：时间轴记「已停止」、通知复原
            Log.i(TAG, "[VOICE-SYNC] 任务在跑时说「" + com.onesay.assistant.v2.util.LogText.u(command) + "」→ 停掉复合任务");
            if (mSheetView != null) mSheetView.updateState("已停止", Color.parseColor("#8E8E93"), com.onesay.assistant.v2.tts.VoiceTemplate.CANCELLED.text);
            TtsSpeaker.get(this).speakReplyTemplate(com.onesay.assistant.v2.tts.VoiceTemplate.CANCELLED);
            return;
        }
        // ★ mika-plug（mika.inbox，照 Mika RunInbox）：复合任务跑着时再说一句 = 追加给当前任务，不打断、不开新任务
        if (mActiveAgent != null && com.onesay.assistant.v2.mikaplug.LoopPlugs.steering()
                && com.onesay.assistant.v2.mikaplug.LoopPlugs.steer("u" + System.currentTimeMillis(), command) != null) {
            Log.i(TAG, "[MIKA-PLUG] 任务在跑，插话追加给当前任务：" + com.onesay.assistant.v2.util.LogText.u(command));
            if (mSheetView != null) mSheetView.updateState("正在执行", Color.parseColor("#5E5CE6"), "收到补充：" + command);
            return;
        }
        cancelActiveStream(true);   // 新一轮指令到达 → 上一轮在途流式作废；r2/fixes 14：复合任务只在新请求也进 AgentLoop 时取消
        beginForegroundRequest();   // ★ LC-5：卡片在岛上时新建卡片接住这条请求（岛上任务不动）
        Log.i(TAG, "收到前端输入指令: " + com.onesay.assistant.v2.util.LogText.u(command));
        mSheetView.setSuggestionsVisible(true);   // ★ M-2：离开错误态，恢复推荐芯片
        mSheetView.updateState("正在解析", Color.parseColor("#0A84FF"), "“" + command + "”");
        // [kernel] 插件输入拦截：[s1] 「圈一下 / 框选（这是什么）」→ 圈选即问；
        //   [imgsave] 多图编号待选时的「第N张 / 全部 / 取消」→ 存图流程（触发句本身走路由层 L0 规则 → image.save）
        if (com.onesay.assistant.v2.kernel.FeatureKernel.get().interceptCommand(this, command)) return;
        clearPreviousRoundViews();   // ★ audit-J 5：新 query 入场 → 清上一轮结果卡 / 回执 / 安装卡（不清本轮；插件拦截的「第N张」等续答不清）
        // [screenref] 指代屏幕内容（"复制这个/导航去这里/打这个电话"）→ 识屏指代接管；未命中则原流程照旧
        //   [s2] 「删掉停车那条 / 我记了什么 / 上次记的…」里的「那条/刚才那」不是屏幕指代 → 跳过识屏，交给路由层 S2
        //   [diag] 「为什么这个屏幕老亮 / 把刚才的改回去」→ 设置诊断，跳过识屏
        //   [imgsave] 「存这张图」里的「这张」不是识屏指代 → 跳过识屏，交给路由层
        //   [summary] 「总结这页 / 这篇讲了什么」里的「这页 / 这篇」不是识屏指代 → 跳过识屏，交给路由层
        //   [kernel] 以上由插件声明 bypassScreenRef（顺序 S2 → S3 → 诊断 → 存图 → 摘要），任一为真即跳过识屏
        if (!com.onesay.assistant.v2.kernel.FeatureKernel.get().bypassScreenRef(this, command)
                && com.onesay.assistant.v2.kernel.FeatureKernel.get().screenReference(this, command)) return;   // [kernel] SCREEN_REFERENCE —— [screenref]

        final UnifiedRouter.Cancel cancel = new UnifiedRouter.Cancel();
        mActiveStream = cancel;
        mLastStreamFlushAt = 0;   // 让第一段增量立刻上屏，不等节流窗口

        // AgentLoop 分支（对标 Codex 的 reason→act→observe）
        //   复合/条件式任务（"看看相册多少张，再告诉我该不该清"）走单步翻译必错 ——
        //   第 2 步看不见第 1 步的结果。这类任务交给 AgentLoop：模型自己调工具、
        //   看真实结果、再决定下一步，直到它自己判断做完。
        //   判据保守（L0 未命中 + 复合信号），L0/L1 快路径不受任何影响。
        // 分诊（TriageDecider，可能含一次 ≤1.5s 的 LLM 兜底分类）挪到后台线程，
        //   主线程只做 UI。ASK 永不进 AgentLoop（走 AskEngine → L1 → 流式问答）。
        com.onesay.assistant.v2.util.Threads.start("RouteStream", com.onesay.assistant.v2.net.StopScope.wrap(cancel.scope(), new Runnable() {   // ★ feat/api-first：停止键真中断
            @Override
            public void run() {
                if (mRouter.shouldUseAgentLoop(command)) {
                    Log.i(TAG, "[AGENT-LOOP] 分诊判定交给 AgentLoop: " + com.onesay.assistant.v2.util.LogText.u(command));
                    // ★ arch/island-progress：分诊决定要不要一开始就上岛（单步请求不包成岛上的任务；triage 5 s 内复用，不重算）
                    final boolean islandNow = com.onesay.assistant.v2.router.TriageDecider.islandUpFront(mRouter.triage(command).decision);
                    mMainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (cancel.isCancelled() || mSheetView == null) return;
                            cancelActiveAgent(cancel, "新请求也进 AgentLoop");   // r2/fixes 14
                            runAgentLoop(command, cancel, islandNow);
                        }
                    });
                    return;
                }
                mRouter.routeStreaming(command, new UnifiedRouter.StreamHandler() {
                    // ★ int/1102 思考模式：思维链期间面板显示「思考中」；8 s 还没出第一个字 → 沿用「还在弄，快好了」催一次（整轮一次）
                    private volatile boolean mGotText, mNudged;

                    @Override
                    public void onStage(final int stage) {
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (cancel.isCancelled() || mSheetView == null) return;
                                if (stage == L2LLMCompiler.STAGE_THINKING) {
                                    mSheetView.setAskStreamStage("思考中…");
                                    mMainHandler.postDelayed(new Runnable() {
                                        @Override public void run() {
                                            if (cancel.isCancelled() || mGotText || mNudged || mSheetView == null) return;
                                            mNudged = true;
                                            mSheetView.setAskStreamStage("还在想，快好了…");
                                            try { TtsSpeaker.get(FloatingSheetService.this).playCue(com.onesay.assistant.v2.tts.VoiceTemplate.STILL_WORKING, null); } catch (Throwable ignored) {}
                                            Log.i(TAG, "[THINK] 8 s 未出字，催一次「还在弄，快好了」");
                                        }
                                    }, com.onesay.assistant.v2.tts.VoiceSyncPolicy.STILL_WORKING_MS);
                                    return;
                                }
                                if (stage == L2LLMCompiler.STAGE_SEARCH) {
                                    mSheetView.beginAskStream(command, "正在检索资料…");
                                } else {
                                    mSheetView.setAskStreamStage("正在生成答案…");
                                }
                            }
                        });
                    }

                    @Override
                    public void onPartial(final String textSoFar) {
                        mGotText = true;
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (cancel.isCancelled()) return;
                                pushStreamText(textSoFar);
                            }
                        });
                    }

                    @Override
                    public void onFinal(final Invocation inv) {
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                // 已被用户收起：迟到的结果不再弹面板（避免"关掉又自己冒出来"）
                                if (cancel.isCancelled()) {
                                    Log.i(TAG, "面板已收起，丢弃迟到的路由结果: "
                                            + (inv != null ? inv.sign : "null"));
                                    return;
                                }
                                mActiveStream = null;
                                dispatch(inv, command);
                            }
                        });
                    }
                }, cancel);
            }
        }));
    }

    /**
     * 把路由结果落到界面上（**只在主线程调用**）。
     *
     * <p>从 {@link #onSubmitCommand} 里抽出来，是因为流式改造后它有两条汇入路径：
     * 本地层/缓存的即时回调，以及流式通道的终态回调。界面行为必须完全一致。
     */
    /** ★ fix/leak-guard：L2 结果里要判的文字（参数 + 多步计划各步参数）。 */
    private static String leakText(Invocation inv) {
        StringBuilder sb = new StringBuilder();
        if (inv.args != null) sb.append(inv.args.toString());
        if (inv.steps != null) for (Invocation st : inv.steps) if (st != null && st.args != null) sb.append('\n').append(st.args.toString());
        return sb.toString();
    }

    private void dispatch(Invocation inv, String command) {
        if (mSheetView == null) return;
        if (inv != null) mSheetView.chatNoteRoute(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.routeLabel(String.valueOf(inv.source), inv.sign,
                com.onesay.assistant.v2.router.UnifiedRouter.localRuleOf(inv)));   // ★ ui-K1：详情（i）显示路由；★ 11-06 本地规则答复不显示「以上由 AI 生成」

        if (inv == null) {
            // [r2/uxcompare] 复述 + 换个说法的例子（原来只有「未能理解指令: …」，用户不知道下一步怎么说）
            mSheetView.updateState("未能识别", Color.parseColor("#FF453A"),
                    com.onesay.assistant.v2.ui.wake.WakeCopy.unrecognizedLine(command));
            // 唤醒后面板**不自动收起**（只有"打开xx软件/跳转界面"才让位）。
            //   原先 2.5s 自动滑走 —— 用户还没看清"未能理解指令"写的是什么，面板就没了。
            //   改为常驻，由用户手动关闭（✕ / 点面板外 / 下拉）。
            mSheetView.setDismissLabel("✕ 关闭");
            cancelAutoHide();
            return;
        }

        // ★ fix/leak-guard：L2 编译 / 计划结果里带系统提示词回吐 → 不执行、不显示，按「没听清」收尾
        if (inv.source == Invocation.RouteSource.L2_LLM
                && com.onesay.assistant.v2.leakguard.LeakGuardHost.blockOutput(this, "L2.final", leakText(inv))) {
            showTerminalPromptQuiet("没听清，可再说一次或点「⌨ 文字」输入");
            return;
        }

        // [kernel] 插件按签名接管派发：
        //   [imgsave] image.save：面板先让位 → 截图 + 非压缩 dump → 裁剪 / 编号芯片 / 手动框选
        //   [summary] screen.summary：面板先让位 → 识屏文字树（--compressed）/ 截图兜底 → 一次大模型 → 一句话 + 要点 + 芯片
        if (com.onesay.assistant.v2.kernel.FeatureKernel.get().dispatch(this, inv, command)) return;

        // ★ 混合任务（MIXED）：多步计划顺序执行
        if (inv.isPlan()) {
            executePlan(inv);
            return;
        }

        // Ask 模式与闲聊直接呈现（自然回答，不展示冗余系统卡）
        if ("ask.reply".equals(inv.sign) || "chat.reply".equals(inv.sign)
                || com.onesay.assistant.v2.router.SlotFiller.SIGN_CLARIFY.equals(inv.sign)) {
            String txt = inv.args.optString("text");
            String q = inv.args.optString("query", command);
            // [round7] 按路由来源收状态行：L2 = 云端作答；L0 / L1（规则、本地问答、缓存、角色层…）= 本地直答
            mSheetView.showAskReply(q, txt, inv.source == Invocation.RouteSource.L2_LLM
                    ? StatusLinePolicy.Source.CLOUD : StatusLinePolicy.Source.LOCAL);
            com.onesay.assistant.v2.kernel.FeatureKernel.get().onReplyShown(this, inv);   // [kernel] [diag] 诊断回答带修复芯片 → [roles] 默认应用：选择芯片 / 未安装提示
            playAckDone();
            mSheetView.setDismissLabel("✕ 关闭");
            // ★ 交互修订（2026-10-03，用户反馈）：问答结果**不再自动收起**。
            //   原先此处 scheduleAutoHide(4500) —— 4.5 秒后自动滑走。
            //   接入实时检索后答案普遍变长（"比特币现价约 86,258 美元，24 小时内上涨约
            //   3.28%（Yahoo 财经数据）。TradingView 显示为 86,282 美元…" 一百多字），
            //   用户常在读到一半时面板消失，只能重新问一遍 —— 明显不合理。
            //   现改为**常驻，等用户手动收起**，三条退出路径都保留：
            //     ① 点右上角「✕ 关闭」；② 点面板外任意区域；③ 下拉手势。
            //   注（2026-10-03 用户修订）：**执行类指令也不再自动收起**，只有
            //   "打开xx软件/跳转界面"才让位 —— 见 executeCapability()。
            //   原先"做完就该让位"的判断被用户否掉了：改完设置也想看到结果。
            //
            //   流式改造后这一条不变：终稿（polish 过的完整文本）会整体覆盖掉
            //   流式过程中显示的增量，避免"流式显示到 280 字、终稿裁到 180 字"留下尾巴。
            cancelAutoHide();
            return;
        }

        // ★ 视觉多模态识屏处理
        if ("vision.screen.understand".equals(inv.sign)) {
            String q = null;
            if (inv.args != null && inv.args.has("content")) {
                q = inv.args.optString("content");
            } else if (command != null && !"识屏".equals(command) && !"识别屏幕".equals(command)
                    && !"看下屏幕".equals(command) && !"看看屏幕".equals(command)
                    && !"屏幕上有什么".equals(command) && !"分析屏幕".equals(command)
                    && !"帮我看看屏幕".equals(command)) {
                q = command;
            }
            triggerScreenVision(q);
            return;
        }

        Capability capFound = CapabilityRegistry.getInstance().describe(inv.sign);
        // [kernel] 插件自带能力（不进注册表）：[s2] 记忆 → [diag] 撤销 diag.undo → [s3] 定时任务 / 待办
        if (capFound == null) capFound = com.onesay.assistant.v2.kernel.FeatureKernel.get().describe(inv.sign);
        final Capability cap = capFound;
        if (cap == null) {
            mSheetView.updateState("异常", Color.parseColor("#FF453A"), "未知能力签名: " + inv.sign);
            mSheetView.setDismissLabel("✕ 关闭");
            cancelAutoHide();
            return;
        }

        // 「打电话给妈妈」：目标不是号码 → 本机通讯录解析（一个 → 确认卡；多个 → 芯片；没有 → 如实说），见 contacts/ContactCallHooks
        if (com.onesay.assistant.v2.contacts.ContactCallHooks.intercept(this, cap, inv, command)) return;

        // 门禁（ToolGate + 「始终允许」白名单）—— 走 ExecutionGate 唯一入口
        // 分诊先区分任务类型 —— 白名单命中直接放行；需确认的按 tier 弹红/黄卡。
        final com.onesay.assistant.v2.executor.ExecutionGate.Decision gate =
                com.onesay.assistant.v2.executor.ExecutionGate.evaluate(cap, inv.args, inv.tainted);
        if (gate.decision == com.onesay.assistant.v2.executor.ToolGate.Decision.DENY) {
            mSheetView.updateState("已被拦截", Color.parseColor("#FF453A"), gate.reason);
            // 拦截原因必须让用户看清（原 3.5s 自动收起），改为常驻。
            mSheetView.setDismissLabel("✕ 关闭");
            cancelAutoHide();
            return;
        } else if (gate.decision == com.onesay.assistant.v2.executor.ToolGate.Decision.REQUIRE_CONFIRM) {
            // 确认卡按任务类型分级（红/黄）+ 语音提示"请确定执行"；
            //   记住原始指令，点击「允许」后执行回执照常写回会话上下文。
            //   ★ F2：卡片绑定一次性令牌（能力+参数+污点），点「允许」时批准并凭令牌执行。
            mPendingConfirm.hold(gate, command);
            mSheetView.showConfirmCard(cap, inv.args, inv.tainted, gate.tier, null);
            playConfirmPrompt();
            return;
        }

        // 正常执行（★ P1-1：带上原始指令，执行结果会回喂进会话上下文）
        // 放行（含「始终允许」）无需令牌 —— 执行层会重算门禁得到同一结论
        executeCapability(cap, inv.args, null, inv.tainted, false, inv.originalQuery);
    }

    /**
     * 跑一次 AgentLoop（新增，对标 Codex 的 reason→act→observe）。
     *
     * <p><b>与 {@link #executePlan} 的本质区别</b>：
     * <ul>
     *   <li>executePlan 是**盲写剧本**：模型一次生成 steps，然后照本宣科跑完，
     *       第 N 步生成时看不见第 N-1 步的结果，失败也照跑。</li>
     *   <li>AgentLoop 是**边做边看**：每轮把真实执行结果回灌给模型，
     *       模型据此决定下一步；失败它会换路或如实报告。</li>
     * </ul>
     *
     * <p>线程：整个循环在后台线程跑（含多次网络往返）；UI 只在主线程刷。
     * 执行回调里**不切线程**，直接同步执行 —— 循环本身需要拿到结果才能继续。
     */
    /** ★ arch/agent-loop：ask_user 面板芯片的 owner 标记。 */
    private static final Object ASK_CHIPS_OWNER = new Object();

    /**
     * ★ arch/agent-loop（B 路）：AgentLoop 的 ask_user（循环线程调用，阻塞）。
     * 先 {@code S1Hooks.onAskUser} 登记（C 路接上后岛上出问题 + 选项芯片）；卡片还归这个任务时，面板上同时显示问题 + 选项芯片，
     * 并播「需要你确认」提示音，用户也可以直接说 / 打字回答（{@code onSubmitCommand} 开头拦截回填）。
     * 都没人看得到（卡片已被新请求接管且岛接不了）→ 立刻按 no_answer 返回。
     */
    private String askUserFromLoop(final String id, final String question, final java.util.List<String> options,
                                   int timeoutSec, final UnifiedRouter.Cancel cancel, final int agentSeq) {
        if (cancel.isCancelled()) return null;
        boolean onIsland = com.onesay.assistant.v2.s1.S1Hooks.onAskUser(id, question, options, timeoutSec);
        final boolean onCard = agentOwnsCard(agentSeq);
        if (!onIsland && !onCard) {
            Log.i(TAG, "[ASK-USER] " + id + " 卡片已转后台、岛接不了 → no_answer");
            com.onesay.assistant.v2.s1.S1Hooks.cancelAskUser(id);
            return null;
        }
        com.onesay.assistant.v2.agent.AskUserSupport.open(id, options);
        if (onCard) {
            mMainHandler.post(new Runnable() {
                @Override public void run() {
                    if (mSheetView == null || cancel.isCancelled() || !agentOwnsCard(agentSeq)) return;
                    mSheetView.updateState("想问你一下", Color.parseColor("#FF9F0A"),
                            question + (options.isEmpty() ? "（直接说出或输入你的回答）" : "（点选，或直接说）"));
                    if (!options.isEmpty()) {
                        ActionChipsView v;
                        android.view.View existing = mSheetView.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
                        if (existing instanceof ActionChipsView) {
                            v = (ActionChipsView) existing;
                        } else {
                            v = new ActionChipsView(FloatingSheetService.this);
                            int idx = Math.min(2, mSheetView.getChildCount());
                            mSheetView.addView(v, idx, new android.widget.LinearLayout.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                        }
                        v.setListener(new ActionChipsView.Listener() {
                            @Override public void onChipClicked(int index) {
                                if (index < 0 || index >= options.size()) return;
                                com.onesay.assistant.v2.s1.S1Hooks.answerAskUser(id, options.get(index));
                            }
                        });
                        v.showChips(options);
                        v.owner = ASK_CHIPS_OWNER;
                    }
                    // ★ arch/voice-sync：提问提示改由 S1Hooks.onAskUser → VoiceSync 统一播（岛 / 面板一次，带静音条件和字幕）
                }
            });
        }
        Log.i(TAG, "[ASK-USER] " + id + " 等回答（" + timeoutSec + " 秒，岛=" + onIsland + " 卡片=" + onCard + "）");
        String a = com.onesay.assistant.v2.agent.AskUserSupport.await(com.onesay.assistant.v2.s1.S1Hooks.askBroker(), id, timeoutSec * 1000L,
                new com.onesay.assistant.v2.agent.AskUserSupport.Cancel() {
                    @Override public boolean isCancelled() {
                        return cancel.isCancelled();
                    }
                }, new com.onesay.assistant.v2.agent.AskUserSupport.Withdraw() {
                    @Override public void withdraw(String qid) {
                        com.onesay.assistant.v2.s1.S1Hooks.cancelAskUser(qid);
                    }
                }, null);
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                if (mSheetView == null) return;
                android.view.View existing = mSheetView.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
                if (existing instanceof ActionChipsView && ((ActionChipsView) existing).owner == ASK_CHIPS_OWNER) {
                    ((ActionChipsView) existing).hideChips();
                }
                if (!cancel.isCancelled() && agentOwnsCard(agentSeq)) {
                    mSheetView.updateState("正在处理", Color.parseColor("#5E5CE6"), "这是一条复合任务，正在逐步执行…");
                }
            }
        });
        Log.i(TAG, "[ASK-USER] " + id + (a == null ? " 没有回答（no_answer）" : " 已回答"));
        return a;
    }

    /** r2/fixes 14：新请求也进 AgentLoop → 取消旧的复合任务（同一时间只跑一个循环）。主线程。 */
    private void cancelActiveAgent(UnifiedRouter.Cancel keep, String why) {
        UnifiedRouter.Cancel old = mActiveAgent;
        if (old != null && old != keep && com.onesay.assistant.v2.router.AgentPreemption.newRequestCancelsAgent(true)) {
            old.cancel();
            Log.i(TAG, "[AGENT-LOOP] 取消旧的复合任务（" + why + "）");
        }
        mActiveAgent = null;
    }

    /** r2/fixes 14：复合任务是否还占着卡片（没被新请求接管）。 */
    private boolean agentOwnsCard(int dispatchSeq) {
        return com.onesay.assistant.v2.router.AgentPreemption.agentOwnsCard(dispatchSeq, mRequestSeq);
    }

    private void runAgentLoop(final String command, final UnifiedRouter.Cancel cancel) {
        runAgentLoop(command, cancel, false);   // ★ arch/island-progress：识屏指代等单步入口 → 懒上岛
    }

    private void runAgentLoop(final String command, final UnifiedRouter.Cancel cancel, final boolean islandNow) {
        final int agentSeq = mRequestSeq;   // r2/fixes 14：派发时的请求号；之后被新请求接管就不再写卡片
        if (mSheetView != null) mSheetView.chatNoteRoute("AgentLoop");   // ★ ui-K1
        mActiveAgent = cancel;
        mActiveAgentSeq = agentSeq;
        mSheetView.updateState("正在处理", Color.parseColor("#5E5CE6"), "这是一条复合任务，正在逐步执行…");
        mSheetView.setDismissLabel("✕ 取消");
        com.onesay.assistant.v2.s1.S1Hooks.onTaskStart(this, command, islandNow);   // [s1] 长任务开始：稍后可收成胶囊（★ arch/island-progress：单步不上岛）
        {   // ★ fix/triage-fast：一句多意图先按子句给岛子目标（模型 task.todo 后全量替换），岛显示子目标而不是抽象步数
            java.util.List<com.onesay.assistant.v2.island.Subgoal> seed = com.onesay.assistant.v2.router.CompoundSplitter.seedSubgoals(command);
            if (seed.size() >= 2) com.onesay.assistant.v2.s1.S1Hooks.onSubgoals(seed);
        }

        final java.util.List<FloatingSheetView.ToggleItem> toggles =
                new java.util.ArrayList<FloatingSheetView.ToggleItem>();
        // 本任务是否成功执行过"打开应用/跳转界面"步骤（完成时浮层让位用）
        final boolean[] navExecuted = {false};
        // 本任务最后一步**闹钟域**能力的执行结果（收工后渲染卡片用）。
        //   之所以"记下来、收工后再画"而不是"执行时立刻画"：showAskReply 会 clearAlarmCards，
        //   画早了会被收工回复擦掉（见收工处的注释）。
        final Capability[] alarmCap = {null};
        final com.onesay.assistant.v2.executor.ExecutionResult[] alarmRes = {null};

        // ★ feat/api-first：执行时间轴（回答上方「正在调用 X ▲」）+ 停止键真中断（取消 = 断开在途请求、停掉点屏代理）
        final com.onesay.assistant.v2.timeline.ExecTimeline tl = mSheetView.timelineBegin();
        cancel.scope().register(new Runnable() {
            @Override public void run() {
                stopGuiAgent("复合任务取消");
            }
        });
        com.onesay.assistant.v2.util.Threads.start("AgentLoop", com.onesay.assistant.v2.net.StopScope.wrap(cancel.scope(), new Runnable() {
            @Override
            public void run() {
                AgentLoop.Result result;
                try {
                    // 经由 UnifiedRouter 包装运行 —— 收工后确定性单步结果回写为技能缓存
                    result = mRouter.runAgentLoop(command, new AgentLoop.LoopAwareCallback() {   // BudgetAskingCallback + 账本 / ask_user 事件
                        // ★ arch/agent-loop（B 路）：task.todo 每次改账本 → 结构化事件给岛（C 路显示「第 i/n 项」）
                        @Override
                        public void onSubgoals(java.util.List<com.onesay.assistant.v2.island.Subgoal> goals) {
                            com.onesay.assistant.v2.s1.S1Hooks.onSubgoals(goals);
                        }

                        // ★ 11-04 ①：每次工具调用（含循环内执行的 web.* / 本地工具）→ 岛与卡片显示真实步骤短句；失败 / 整理结论只改当前一行
                        @Override
                        public void onToolStep(final String text, boolean counts) {
                            if (counts) com.onesay.assistant.v2.s1.S1Hooks.onTaskStep(text);
                            else com.onesay.assistant.v2.s1.S1Hooks.onTaskNote(text);
                            if (counts) tl.toolStart(text);   // ★ feat/api-first：时间轴一步
                            else tl.note(text);
                            timelineTouched(tl);
                            if (counts && mSheetView != null && agentOwnsCard(agentSeq)) mSheetView.progressStep(text);   // ★ 11-04 午：面板「执行中：步骤 · 计时」
                            mMainHandler.post(new Runnable() {
                                @Override public void run() {
                                    if (mSheetView != null && !cancel.isCancelled() && agentOwnsCard(agentSeq)) {
                                        mSheetView.updateState("正在处理", Color.parseColor("#5E5CE6"), text);
                                    }
                                }
                            });
                        }

                        // ★ 11-04 午 进度反馈：每轮等模型 = 「思考中」（岛 + 面板呼吸），拿到回复退出
                        @Override
                        public void onThinking(boolean on) {
                            com.onesay.assistant.v2.s1.S1Hooks.onThinking(on);
                            tl.thinking(on);   // ★ feat/api-first
                            timelineTouched(tl);
                            if (mSheetView != null && agentOwnsCard(agentSeq)) mSheetView.progressThinking(on);
                        }

                        // ★ arch/agent-loop：ask_user —— 岛上芯片（C 路）+ 面板卡片芯片 / 麦克风兜底，阻塞到回答 / 超时 / 取消
                        @Override
                        public String askUser(String id, String question, java.util.List<String> options, int timeoutSec) {
                            return askUserFromLoop(id, question, options, timeoutSec, cancel, agentSeq);
                        }

                        // ★ feat/api-first：每次工具调用结束的成败（失败带原因）→ 时间轴 ✓ / ✗
                        @Override
                        public void onToolResult(String sign, boolean ok, String reasonOrDetail) {
                            tl.toolResult(ok, reasonOrDetail);
                            timelineTouched(tl);
                        }

                        // [s1] 胶囊停止键 / ✕ / 新指令接管：循环据此在下一轮前收尾（ErrorCode.CANCELLED）
                        @Override
                        public boolean isCancelled() {
                            return cancel.isCancelled();
                        }

                        // ★ fast-screen-v2：TaskBudget 到上限（模型调用数 / 总时长 / 无进展）先问用户 —— 复用确认卡（不执行任何能力）
                        @Override
                        public boolean askContinue(String reason, String info) {
                            Capability ask = new Capability.Builder("agent.budget.continue", "继续执行这个任务（" + info + "）").build();
                            return onRequireConfirm(ask, new org.json.JSONObject(), reason,
                                    com.onesay.assistant.v2.executor.ConsentPolicy.TIER_YELLOW, false)
                                    == com.onesay.assistant.v2.router.AgentLoop.ConfirmVerdict.ALLOW;
                        }

                        @Override
                        public com.onesay.assistant.v2.executor.ExecutionResult execute(
                                Capability cap, org.json.JSONObject args) {
                            return execute(cap, args, null, false);
                        }

                        @Override
                        public com.onesay.assistant.v2.router.AgentLoop.ConfirmVerdict onRequireConfirm(
                                Capability cap, org.json.JSONObject args, String reason) {
                            return onRequireConfirm(cap, args, reason,
                                    com.onesay.assistant.v2.executor.ConsentPolicy.tierOf(cap, false), false);
                        }

                        // 循环已用 ExecutionGate 过闸，确认后交来已批准的令牌与污点
                        @Override
                        public com.onesay.assistant.v2.executor.ExecutionResult execute(
                                Capability cap, org.json.JSONObject args,
                                com.onesay.assistant.v2.executor.ExecutionGate.ConfirmToken token,
                                boolean tainted) {
                            if (cancel.isCancelled()) {
                                return com.onesay.assistant.v2.executor.ExecutionResult.failed(
                                        com.onesay.assistant.v2.executor.ErrorCode.CANCELLED,
                                        "已取消", "cancelled", cap, args);
                            }
                            // ★ 11-04 ①：逐步上屏改由 AgentLoop.exec → onToolStep（所有工具都走那里，这里不再重复报一步）
                            // 凭令牌执行（需确认的步骤由循环在用户允许后 approve），
                            //   不再用全局布尔 mNextExecConfirmed 放行
                            // ★ arch/voice-sync：第一个 GUI 动作等应答播完再开始点（不一边说一边点）；最多 4 s
                            if (com.onesay.assistant.v2.agenttools.ToolTiers.SIGN_GUI_OPERATE.equals(cap.sign)) com.onesay.assistant.v2.s1.S1Hooks.beforeGuiAction();
                            com.onesay.assistant.v2.executor.ExecutionResult r =
                                    mExecutor.execute(cap, args, token, tainted);
                            com.onesay.assistant.v2.s1.S1Hooks.onActionResult(cap, r, isAppLaunchOrNavigation(cap));   // ★ arch/voice-sync：OK_UNVERIFIED 判定
                            com.onesay.assistant.v2.kernel.FeatureKernel.get().afterExec(FloatingSheetService.this, command, cap, args, r);   // [kernel] [s2] 习惯默认值
                            if (r != null && r.ok) {
                                String base = toggleBaseSign(cap.sign);
                                if (base != null) {
                                    toggles.add(new FloatingSheetView.ToggleItem(
                                            toggleLabel(base), cap.sign.endsWith(".on"), base));
                                }
                                // 成功拉起界面 → 记住，完成时浮层必须让位
                                //   （失败不算：什么都没打开，面板留着给用户看原因）
                                if (isAppLaunchOrNavigation(cap)) navExecuted[0] = true;

                                // 闹钟域能力在**循环路径**里执行后同样要弹
                                //   "晨间播报同款"卡片（用户要求：设闹钟要看到卡片，不是一句话）。
                                //
                                //   此前只有**单步路径**（UnifiedRouter 同步路由）接了这段渲染，
                                //   于是同一个意图出现两种表现：走单步会弹卡，走 AgentLoop
                                //   （达标型/复合任务）却只回一句话。
                                //   实测（10-06）：「40分钟后叫醒我」被判为复合任务走 AgentLoop，
                                //   闹钟真的设上了（dump 里多了 id=2 / 01:20），但卡片没出现。
                                //   这里**只记录不渲染**：渲染时机在收工之后（见收工处注释）。
                                if (com.onesay.assistant.v2.executor.AlarmBridge.isAlarmSign(cap.sign)) {
                                    alarmCap[0] = cap;
                                    alarmRes[0] = r;
                                }
                            }
                            return r;
                        }

                        @Override
                        public com.onesay.assistant.v2.router.AgentLoop.ConfirmVerdict onRequireConfirm(
                                final Capability cap, final org.json.JSONObject args, String reason,
                                final int tier, final boolean tainted) {
                            // 「权限卡弹出后被回复盖住、没法点」）：
                            //   旧实现"甩卡片到主线程 + 立即 return true" —— 循环不等待、卡片会被
                            //   最终回复顶掉（showAskReply 里 hideConfirmCard）、且确认后不会继续执行。
                            //   新语义：**阻塞循环线程**，等用户在卡片上做出决策（或超时/面板收起）；
                            //   返回 ALLOW 时循环立即执行该步并继续下一步，上下文全程连续。
                            if (cancel.isCancelled()) {
                                return com.onesay.assistant.v2.router.AgentLoop.ConfirmVerdict.DENY;
                            }
                            // r2/fixes 14：卡片已被新请求接管（任务在后台跑）→ 不把确认卡弹到别的请求上面，按未授权处理
                            if (!agentOwnsCard(agentSeq)) {
                                Log.i(TAG, "[CONFIRM] 复合任务已转后台，需确认的步骤按未授权处理: " + cap.sign);
                                return com.onesay.assistant.v2.router.AgentLoop.ConfirmVerdict.DENY;
                            }

                            final PendingLoopConfirm pending = new PendingLoopConfirm(cap, args);
                            synchronized (mConfirmLock) { mPendingLoopConfirm = pending; }
                            tl.approval(cap.summary != null && cap.summary.length() > 0 ? cap.summary : cap.sign);   // ★ feat/api-first：确认卡也算一步
                            timelineTouched(tl);

                            mMainHandler.post(new Runnable() {
                                @Override public void run() {
                                    if (cancel.isCancelled() || mSheetView == null || !agentOwnsCard(agentSeq)) {
                                        pending.resolve(PendingLoopConfirm.VERDICT_DENY);
                                        return;
                                    }
                                    // [s1] 确认卡绝不能藏在胶囊里：收起状态先强制展开面板（[island] 岛：待确认 → 1 s 后岛上展开确认卡）
                                    com.onesay.assistant.v2.s1.S1Hooks.onConfirmInfo(cap, args);   // [island]
                                    com.onesay.assistant.v2.s1.S1Hooks.onConfirmRequired(FloatingSheetService.this);
                                    mSheetView.showConfirmCard(cap, args, tainted, tier,
                                            "确认后，我会继续完成剩余的步骤。");
                                    playConfirmPrompt();
                                }
                            });
                            Log.i(TAG, "[CONFIRM] 循环步骤挂起等待用户决策: " + cap.sign
                                    + "（最长 " + (CONFIRM_WAIT_TIMEOUT_MS / 1000) + " 秒）");

                            long deadline = SystemClock.elapsedRealtime() + CONFIRM_WAIT_TIMEOUT_MS;
                            boolean decided = false;
                            while (SystemClock.elapsedRealtime() < deadline) {
                                try {
                                    if (pending.latch.await(250,
                                            java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                        decided = true;
                                        break;
                                    }
                                } catch (InterruptedException ie) {
                                    break;
                                }
                                if (cancel.isCancelled()) break;
                            }
                            synchronized (mConfirmLock) {
                                if (mPendingLoopConfirm == pending) mPendingLoopConfirm = null;
                            }
                            com.onesay.assistant.v2.s1.S1Hooks.onConfirmResolved();   // [s1]
                            tl.approvalResolved(decided && pending.verdict == PendingLoopConfirm.VERDICT_ALLOW);   // ★ feat/api-first
                            timelineTouched(tl);
                            if (!decided) {
                                final boolean byCancel = cancel.isCancelled();
                                Log.w(TAG, "[CONFIRM] 等待超时/取消，按未授权处理: " + cap.sign);
                                if (!byCancel) {
                                    mMainHandler.post(new Runnable() {
                                        @Override public void run() {
                                            if (mSheetView != null && agentOwnsCard(agentSeq)) {
                                                mSheetView.hideConfirmCard();
                                                mSheetView.updateState("确认超时",
                                                        Color.parseColor("#FF9F0A"), "该操作未执行");
                                            }
                                        }
                                    });
                                }
                                return com.onesay.assistant.v2.router.AgentLoop.ConfirmVerdict.DENY;
                            }
                            if (pending.verdict == PendingLoopConfirm.VERDICT_ALLOW) {
                                Log.i(TAG, "[CONFIRM] 用户已授权，继续执行: " + cap.sign);
                                return com.onesay.assistant.v2.router.AgentLoop.ConfirmVerdict.ALLOW;
                            }
                            Log.i(TAG, "[CONFIRM] 用户拒绝，通知模型收尾: " + cap.sign);
                            return com.onesay.assistant.v2.router.AgentLoop.ConfirmVerdict.DENY;
                        }
                    }, 0);
                } catch (Throwable t) {
                    Log.e(TAG, "[AGENT-LOOP] 异常: " + t.getMessage());
                    result = null;
                }

                final AgentLoop.Result fr = result;
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // [s1] 任务结束：胶囊收起时自动展开显示结果（拉起了别的界面则只闪「已完成」；取消则收掉胶囊）
                        //   [island] 收起状态下：有结论文字/闹钟卡 → 挂回面板显示；没有（或拉起了界面）→ 岛/胶囊对勾 1.8 s；失败 → 岛/胶囊失败态
                        // [r2/bike-chooser ⑤] 终稿带应用网格 → 收工挂回卡片（新界面默认只进岛，格子画在卡片里）
                        final boolean needsCard = fr != null && com.onesay.assistant.v2.roles.RoleHooks.agentGridWanted(FloatingSheetService.this, command, fr.appChoice);
                        // ★ arch/integrate：岛上失败原因按结构化结局换说法（needs_user → 要你做什么；blocked → 被什么挡住）
                        final String islandWhy = fr == null || fr.finished ? (fr == null ? null : fr.reason)
                                : com.onesay.assistant.v2.agent.OutcomeText.line(fr.outcome, fr.outcomeReason, fr.reason);
                        com.onesay.assistant.v2.s1.S1Hooks.onTaskOutcome(fr != null && fr.finished,
                                fr == null ? null : (alarmCap[0] != null ? "alarm" : fr.answer),
                                fr == null ? "任务处理失败" : islandWhy, needsCard);   // [island]
                        com.onesay.assistant.v2.s1.S1Hooks.onTaskEnded(FloatingSheetService.this, cancel.isCancelled(), navExecuted[0]);
                        if (mActiveAgent == cancel) mActiveAgent = null;   // r2/fixes 14
                        // ★ feat/api-first：时间轴收尾（取消 = 已停止；否则按结局折叠成「已完成 / 没完成」），通知复原
                        if (cancel.isCancelled()) tl.stopped();
                        else tl.finish(fr != null && fr.finished);
                        timelineTouched(tl);
                        if (cancel.isCancelled()) {
                            Log.i(TAG, "[AGENT-LOOP] 面板已收起，丢弃循环结果");
                            return;
                        }
                        // r2/fixes 14：卡片已被新请求接管 → 结论不盖住新请求的卡片（岛 / 胶囊已收到 onTaskOutcome），只回喂会话记忆
                        if (!agentOwnsCard(agentSeq)) {
                            Log.i(TAG, "[AGENT-LOOP] [LC-5] 卡片已被新请求接管（请求号 " + agentSeq + " → " + mRequestSeq
                                    + "），结论不写卡片");
                            if (fr != null && command != null && command.trim().length() > 0) {
                                com.onesay.assistant.v2.router.ConversationMemory.getInstance().record(command,
                                        "[执行回执] Agent 循环（后台）" + (fr.finished ? "已完成" : "未收工(" + fr.reason + ")")
                                                + (fr.answer != null && fr.answer.length() > 0 ? "\n结论：" + fr.answer : ""),
                                        fr.tainted);   // ★ LOGIC-1
                            }
                            return;
                        }
                        if (mActiveStream == cancel) mActiveStream = null;
                        if (fr == null) {
                            // ★ arch/voice-sync：循环异常 →「哎呀，出岔子了，没弄好」，状态行同一句开头
                            com.onesay.assistant.v2.tts.VoiceTemplate vx = com.onesay.assistant.v2.s1.S1Hooks.agentOutcomeVoice(FloatingSheetService.this, true, null, null, 0);
                            TtsSpeaker.get(FloatingSheetService.this).speakReplyTemplate(vx);
                            TtsSpeaker.clearReplyOverride();
                            mSheetView.updateState("未能完成", Color.parseColor("#FF453A"),
                                    com.onesay.assistant.v2.tts.VoiceSyncPolicy.statusSub(vx, "任务处理失败，请换种说法再试一次。"));
                            mSheetView.setDismissLabel("✕ 关闭");
                            cancelAutoHide();
                            return;
                        }

                        StringBuilder sb = new StringBuilder();
                        if (fr.answer != null && fr.answer.length() > 0) {
                            // ★ fix/leak-guard：最终回答在吐系统提示词 → 丢掉这段（不上卡片、不朗读、不进会话记忆），按结局走兜底说法
                            if (!com.onesay.assistant.v2.leakguard.LeakGuardHost.blockOutput(FloatingSheetService.this, "agentloop.final", fr.answer)) sb.append(fr.answer);
                        }
                        playAckDone();

                        // 回喂会话记忆：与 executePlan 同源 —— 让用户接着说
                        // 「刚才那步没成功，再试一次」时模型有据可依。
                        if (command != null && command.trim().length() > 0) {
                            StringBuilder receipt = new StringBuilder();
                            receipt.append("[执行回执] Agent 循环")
                                   .append(fr.finished ? "已完成" : "未收工(" + fr.reason + ")")
                                   .append("，共 ").append(fr.steps.size()).append(" 次工具调用");
                            if (fr.steps.size() > 0) {
                                receipt.append("：\n");
                                for (int i = 0; i < fr.steps.size(); i++) {
                                    if (i > 0) receipt.append("\n");
                                    receipt.append("· ").append(fr.steps.get(i).toString());
                                }
                            }
                            if (sb.length() > 0) {
                                receipt.append("\n结论：").append(sb.toString());
                            }
                            com.onesay.assistant.v2.router.ConversationMemory.getInstance()
                                    .record(command, receipt.toString(), fr.tainted);   // ★ LOGIC-1
                            Log.i(TAG, "[FEEDBACK] Agent 循环结果已回喂会话上下文（"
                                    + fr.steps.size() + " 次调用）");
                        }

                        if (sb.length() == 0) {
                            sb.append(com.onesay.assistant.v2.agent.OutcomeText.fallbackAnswer(fr.finished, fr.outcome, fr.outcomeReason, fr.reason));   // ★ arch/integrate
                        }
                        // ★ arch/voice-sync：结局挑一句（好了 / 都弄好了 / 我点了没看到结果 / 得你自己点 / FAIL_*），回答模板层播这一句
                        final com.onesay.assistant.v2.tts.VoiceTemplate spoken = com.onesay.assistant.v2.s1.S1Hooks.agentOutcomeVoice(FloatingSheetService.this,
                                false, fr.outcome, fr.outcomeReason, fr.subgoals == null ? 0 : fr.subgoals.size());
                        mSheetView.showAskReply("任务结果", sb.toString());
                        TtsSpeaker.clearReplyOverride();
                        com.onesay.assistant.v2.roles.RoleHooks.onAgentAnswer(FloatingSheetService.this, command, fr.appChoice);   // [r2/bike-chooser] 终稿带 app_choice → 应用网格
                        com.onesay.assistant.v2.shortcut.ShortcutHooks.onAgentResultShown(FloatingSheetService.this, command);   // ★ feat/shortcuts：「存为快捷指令」/「不对」芯片
                        // 闹钟卡片必须**在 showAskReply 之后**渲染。
                        //   实测踩坑：先前把渲染放在 execute() 回调里（执行后立刻渲染），
                        //   卡片确实画出来了，但循环收工时 showAskReply 内部会
                        //   clearAlarmCards() —— 刚画的卡片被自己的收工回复擦掉，
                        //   用户看到的还是"只有一句话"。
                        //   故改为：执行时只**记住**最后一步闹钟能力的结果，收工后再渲染。
                        if (alarmCap[0] != null) {
                            renderAlarmCardsIfAny(alarmCap[0], alarmRes[0]);
                        }
                        boolean navDone = navExecuted[0];
                        if (fr.finished) {
                            mSheetView.updateState("执行完毕", Color.parseColor("#30D158"),
                                    com.onesay.assistant.v2.tts.VoiceSyncPolicy.statusSub(spoken, navDone ? "已跳转界面 · 面板即将让位"
                                            : ("共 " + fr.steps.size() + " 步")));   // ★ arch/voice-sync：与所播同一句开头
                        } else {
                            // ★ arch/integrate：按结构化结局换说法（需要你来 / 暂时做不了 / 已停止 / 部分完成）
                            mSheetView.updateState(com.onesay.assistant.v2.agent.OutcomeText.title(fr.outcome),
                                    Color.parseColor(com.onesay.assistant.v2.agent.OutcomeText.color(fr.outcome)),
                                    com.onesay.assistant.v2.tts.VoiceSyncPolicy.statusSub(spoken,
                                            com.onesay.assistant.v2.agent.OutcomeText.line(fr.outcome, fr.outcomeReason, fr.reason)));   // ★ arch/voice-sync
                        }
                        // （用户实测：「下载微信」商店只在后台跑、前端没起来）：
                        //   循环里执行过"打开应用/跳转界面/打开商店"步骤 → 浮层必须让位，
                        //   与单步路径同一规范（拉起目标界面的浮层留着只会盖住目标 App；
                        //   面板还持有输入焦点，目标应用会"看得见摸不着"）。
                        //   纯查询/改设置类任务依旧常驻，等用户读完手动收起。
                        if (navDone) {
                            mSheetView.setDismissLabel("✕ 取消");
                            scheduleAutoHide(1200);
                        } else {
                            mSheetView.setDismissLabel("✕ 关闭");
                            cancelAutoHide();
                        }
                    }
                });
            }
        }));
    }

    /**
     * 执行混合任务的多步计划（顺序、逐步汇总）。
     *
     * 安全铁律：**计划不得绕过高危确认卡**。
     * 若任一步骤是 risk>=2 的敏感动作，立即中止整条计划并提示用户单独发起，
     * 而不是"因为它在计划里"就顺手执行掉。
     */
    private void executePlan(final Invocation plan) {
        cancelAutoHide();
        final java.util.List<Invocation> steps = plan.steps;
        Log.i(TAG, "收到混合任务计划，共 " + steps.size() + " 步");
        // ★ fix/triage-fast：显示子目标（「调低亮度 · 开勿扰 · 10分钟后提醒我」），不再是「共 N 步」
        String headline = com.onesay.assistant.v2.router.CompoundSplitter.planHeadline(plan);
        mSheetView.updateState("正在执行", Color.parseColor("#5E5CE6"),
                headline.length() > 0 ? headline : "这是一条多步任务，共 " + steps.size() + " 步");

        com.onesay.assistant.v2.util.Threads.start("PlanExecutor", new Runnable() {
            @Override
            public void run() {
                final StringBuilder summary = new StringBuilder();
                boolean allOk = true;
                boolean aborted = false;
                boolean anyOk = false;   // ★ v3b：至少一个执行步骤成功（区分「弄好了一部分」和全败）
                boolean planTainted = false;   // ★ LOGIC-1：计划里任一步带污点 / 读进了外部内容 → 回执 tainted
                // 只有计划里含"打开xx软件/跳转界面"步骤时才让位收起；
                //   动手改设置/查状态、纯问答一律常驻（等用户手动收起）。
                boolean hasNavStep = false;
                // ★ 多个设置项改动 → 收集多个可反悔开关（用户原话"多个设置项改动就展示多个"）
                final java.util.List<FloatingSheetView.ToggleItem> planToggles =
                        new java.util.ArrayList<FloatingSheetView.ToggleItem>();

                for (int i = 0; i < steps.size(); i++) {
                    Invocation step = steps.get(i);
                    if (summary.length() > 0) summary.append("\n");

                    // 纯回答步骤：直接把文本并进汇总
                    if ("ask.reply".equals(step.sign) || "chat.reply".equals(step.sign)) {
                        summary.append(step.args.optString("text"));
                        continue;
                    }

                    Capability cap = CapabilityRegistry.getInstance().describe(step.sign);
                    if (cap == null) cap = com.onesay.assistant.v2.kernel.FeatureKernel.get().describe(step.sign);   // ★ int/1102：插件能力（导航 s2.nav.app 等）进计划也认
                    if (cap == null) {
                        summary.append("· 跳过一步（未知能力 ").append(step.sign).append("）");
                        allOk = false;
                        continue;
                    }

                    // 高危步骤或需确认步骤：不绕过门禁，中止计划并要求用户单独确认
                    //   （走 ConsentPolicy —— 「始终允许」白名单命中的步骤不再中止）
                    //   （走 ExecutionGate；中止时作废随结论签发的令牌）
                    com.onesay.assistant.v2.executor.ExecutionGate.Decision gate =
                            com.onesay.assistant.v2.executor.ExecutionGate.evaluate(cap, step.args, step.tainted);
                    if (gate.decision != com.onesay.assistant.v2.executor.ToolGate.Decision.ALLOW) {
                        com.onesay.assistant.v2.executor.ExecutionGate.reject(gate.token);
                        Log.w(TAG, "混合计划中出现需确认或被拦截动作，中止整条计划: " + cap.sign + " (reason: " + gate.reason + ")");
                        summary.append("· 检测到【").append(cap.summary)
                                .append("】被安全策略拦截（").append(gate.reason).append("），已中止后续步骤。");
                        allOk = false;
                        aborted = true;
                        break;
                    }

                    ExecutionResult r = mExecutor.execute(cap, step.args, null, step.tainted);
                    if (step.tainted || com.onesay.assistant.v2.router.AgentLoop.taintsAfter(cap, r)) planTainted = true;
                    if (isAppLaunchOrNavigation(cap)) hasNavStep = true;
                    if (r.ok) {
                        String base = toggleBaseSign(cap.sign);
                        if (base != null) {
                            planToggles.add(new FloatingSheetView.ToggleItem(
                                    toggleLabel(base), cap.sign.endsWith(".on"), base));
                        }
                    }
                    if (r.ok) {
                        anyOk = true;
                        summary.append("· ").append(r.userMessage);
                    } else {
                        allOk = false;
                        summary.append("· ").append(r.userMessage);
                    }
                }

                // 多步计划同样回喂。把"哪几步成了、哪步没成、为什么中止"
                //   整体写进会话历史 —— 用户接着说「刚才那步没成功，再试一次」时，
                //   模型才能依据回执判断该补哪一步，而不是把整条计划重跑一遍。
                if (plan.originalQuery != null && plan.originalQuery.trim().length() > 0) {
                    StringBuilder receipt = new StringBuilder();
                    receipt.append("[执行回执] 多步任务")
                           .append(aborted ? "被安全策略中止" : (allOk ? "全部完成" : "部分完成"))
                           .append("，共 ").append(steps.size()).append(" 步：\n")
                           .append(summary);
                    com.onesay.assistant.v2.router.ConversationMemory.getInstance()
                            .record(plan.originalQuery, receipt.toString(), planTainted);   // ★ LOGIC-1
                    Log.i(TAG, "[FEEDBACK] 已回喂多步计划结果到会话上下文（"
                            + (aborted ? "中止" : (allOk ? "全成" : "部分完成")) + "）");
                }

                final boolean ok = allOk;
                final boolean didAbort = aborted;
                final boolean someOk = anyOk;
                final boolean ranNav = hasNavStep;
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        playAckDone();
                        // ★ arch/voice-sync：多步计划收尾按全成 / 中止 / 有成有败 / 全败挑一句（都弄好了 / 最后一步得你自己点 / 弄好了一部分，剩下的没弄成 / 哎呀，没弄成）
                        com.onesay.assistant.v2.tts.VoiceTemplate spoken = com.onesay.assistant.v2.s1.S1Hooks.planOutcomeVoice(FloatingSheetService.this, ok, didAbort, someOk);
                        mSheetView.showAskReply("多步任务", summary.toString());
                        TtsSpeaker.clearReplyOverride();
                        if (didAbort) {
                            mSheetView.updateState("已中止", Color.parseColor("#FF9F0A"),
                                    com.onesay.assistant.v2.tts.VoiceSyncPolicy.statusSub(spoken, "计划中包含敏感操作"));
                        } else {
                            mSheetView.updateState(ok ? "执行完毕" : "部分完成",
                                    ok ? Color.parseColor("#30D158") : Color.parseColor("#FF9F0A"),
                                    com.onesay.assistant.v2.tts.VoiceSyncPolicy.statusSub(spoken, "多步任务已处理"));
                        }
                        // 分三档 ——
                        //   ① 计划里**打开了应用/跳转了界面** → 4.8s 后让位（面板会被目标界面盖住）；
                        //   ② 只是动手改设置/查状态（未跳转） → 常驻，等用户读；
                        //   ③ 纯问答型计划 → 常驻（与单条 Ask 一致）。
                        if (ranNav) {
                            mSheetView.setDismissLabel("✕ 取消");
                            scheduleAutoHide(4800);
                        } else {
                            mSheetView.setDismissLabel("✕ 关闭");
                            cancelAutoHide();
                        }
                        // 多步计划里改过几个设置项，就展示几个可反悔开关
                        // （放在 showAskReply 之后 —— 它会 clearToggles，顺序不能反）
                        if (!planToggles.isEmpty()) {
                            mSheetView.setToggles(planToggles);
                        }
                    }
                });
            }
        });
    }

    /**
     * 判断当前能力是否属于拉起应用或切换 Activity 界面的操作。
     * 主流手机厂商交互标准：拉起新 App 后，语音浮层必须立即退场收起，不可阻挡目标 App。
     */
    public static boolean isAppLaunchOrNavigation(Capability cap) {
        if (cap == null) return false;
        String sign = cap.sign != null ? cap.sign : "";
        String cat = cap.category != null ? cap.category : "";

        // 晨间播报闹钟联动（alarmbridge）不让位 ——
        //   设/查/开关/删闹钟都是"面板内交互"（要展示晨间播报同款卡片），
        //   屏幕没有跳转，面板必须留下给用户看/点。
        //   （clock.alarm.set 的 cmd 保留着 am start 文本，若不早退会被下面的
        //   cmd 分支误判成"拉起界面"而 220ms 自收。）
        if ("alarmbridge".equalsIgnoreCase(cap.executorType)) {
            return false;
        }

        if ("app.launch".equals(sign) || "browser.open".equals(sign) || "browser.search".equals(sign)
                || "browser.default.set".equals(sign) || "notes.new".equals(sign)
                || "notes.new.text".equals(sign) || "phone.call".equals(sign)
                || "sms.send".equals(sign) || "sys.wipe".equals(sign)) {
            return true;
        }
        if ("app".equalsIgnoreCase(cat)) {
            return true;
        }
        if ("intent".equalsIgnoreCase(cap.executorType) && !"clock".equalsIgnoreCase(cat)) {
            return true;
        }
        // 补 shell 通道的界面跳转 —— 运行时挖到的接口（mobileqq.*）
        //   与部分页面跳转能力都是 `am start -n 组件`，同样是"拉起界面"，必须让位。
        String cmd = cap.cmdTemplate != null ? cap.cmdTemplate : "";
        if (cmd.indexOf("am start ") >= 0) return true;
        return false;
    }

    private void executeCapability(final Capability cap, final JSONObject args,
                                   final com.onesay.assistant.v2.executor.ExecutionGate.ConfirmToken token,
                                   final boolean isTainted) {
        executeCapability(cap, args, token, isTainted, false, null);
    }

    /**
     * @param silentAck {@code true} = 本次不播报"已完成"应答音。
     *                  用于**用户手指直接操作**的入口（反悔开关）——
     *                  触觉反馈已在 {@link #onToggleRequested} 里给出，再响一声是多余的。
     *                  语音指令路径必须传 {@code false}：用户可能没看屏幕，声音是唯一反馈。
     */
    private void executeCapability(final Capability cap, final JSONObject args,
                                   final com.onesay.assistant.v2.executor.ExecutionGate.ConfirmToken token,
                                   final boolean isTainted, final boolean silentAck) {
        executeCapability(cap, args, token, isTainted, silentAck, null);
    }

    /**
     * 完整签名。
     *
     * @param query 用户原始指令。非空时，执行结果会被写成一条「执行回执」进入会话历史，
     *              于是**下一轮** LLM 能看到"上一步到底成没成"。
     *              这解决了"多步任务做不完整"的根因 —— 模型此前是**一次盲发**，
     *              发完就结束了，看不到任何执行结果。
     *              用户手指拨开关（反悔开关）没有 query，传 null 即可，不记回执。
     */
    private void executeCapability(final Capability cap, final JSONObject args,
                                   final com.onesay.assistant.v2.executor.ExecutionGate.ConfirmToken token,
                                   final boolean isTainted, final boolean silentAck, final String query) {
        cancelAutoHide();
        mSheetView.updateState("正在执行", Color.parseColor("#5E5CE6"), "正在调度特权通道...");
        mSheetView.progressStep(com.onesay.assistant.v2.island.IslandStepText.forTool(cap.sign, args, cap.summary));   // ★ 11-04 午：快路径单步「执行中：设闹钟 3:53」
        if (com.onesay.assistant.v2.executor.AlarmBridge.isAlarmSign(cap.sign)) mAlarmReqSeq = mRequestSeq;

        // ★ feat/api-first：单步执行也有停止范围与时间轴（反悔开关 query=null：手指直接操作，不起时间轴）
        final com.onesay.assistant.v2.net.StopScope execScope = new com.onesay.assistant.v2.net.StopScope("exec");
        mExecScope = execScope;
        final com.onesay.assistant.v2.timeline.ExecTimeline stl = query != null ? mSheetView.timelineBegin() : null;
        if (stl != null) {
            stl.toolStart(com.onesay.assistant.v2.island.IslandStepText.forTool(cap.sign, args, cap.summary));
            timelineTouched(stl);
        }

        com.onesay.assistant.v2.util.Threads.start("ExecSingle", com.onesay.assistant.v2.net.StopScope.wrap(execScope, new Runnable() {
            @Override
            public void run() {
                // 令牌入口（token=null 表示无需确认；需确认却无有效令牌 → RISK_GATED，不执行）
                final ExecutionResult result = mExecutor.execute(cap, args, token, isTainted);
                if (mExecScope == execScope) mExecScope = null;
                if (stl != null) {   // ★ feat/api-first：单步结局写时间轴
                    if (execScope.isStopped()) stl.stopped();
                    else {
                        stl.toolResult(result != null && result.ok, result == null ? "" : result.userMessage);
                        stl.finish(result != null && result.ok);
                    }
                    timelineTouched(stl);
                }
                // 执行结果回报路由层 —— 技能缓存只在"执行成功之后"写入，
                //   缓存回放失败计数/隔离也在这里结算（只用 ExecutionResult 的 ok/code/verified）。
                if (query != null) mRouter.reportExecution(query, cap.sign, result);
                com.onesay.assistant.v2.kernel.FeatureKernel.get().afterExec(FloatingSheetService.this, query, cap, args, result);   // [kernel] [s2] 习惯默认值：记下实际用的 App
                // ★ P1-1：先把回执写进会话历史，再回主线程刷 UI。
                //   刻意放在 post 之前 —— 若这次是"打开应用"随后会收起面板，
                //   而面板离屏会清空会话（detachFromWindowImmediate），顺序反了就白写。
                recordExecutionReceipt(query, cap, result);

                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result.ok) {
                            // 静默路径（开关）不播报：点击时已经震过了
                            if (!silentAck) playAckDone();
                            mSheetView.showResult(result);

                            // ★ 核心交互规范（2026-10-03 用户修订）：**只有"打开xx软件/跳转界面"才收起面板**。
                            //   标准：拉起新 App / 切到设置页面后，浮层留着也只会被目标界面盖住，
                            //   所以延迟 220ms 平滑退场。
                            if (isAppLaunchOrNavigation(cap)) {
                                // 结果文案只在结果卡呈现 —— 第三参（转录位）传 null，
                                //   不再把"结果"覆盖到转录卡上（否则同一句话双处显示，
                                //   且会盖掉"你说：xxx"的原话回显）。下同。
                                mSheetView.updateState("已启动应用", Color.parseColor("#30D158"), null);
                                mMainHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        Log.i(TAG, "已启动目标应用/跳转界面，立即收起悬浮面板");
                                        hideSheet();
                                    }
                                }, 220);
                            } else {
                                // 其余一切（改设置、调音量、状态查询…）**常驻**。
                                // 用户原话："语音唤醒后不需要自动收起，只有收到打开xx软件打开后再收起。"
                                // 原实现是 1.6s 自动收起 —— 用户看不到结果也被迫重问一次。
                                mSheetView.updateState("执行成功", Color.parseColor("#30D158"), null);
                                mSheetView.setDismissLabel("✕ 关闭");
                                cancelAutoHide();

                                // 修改设置类的结果卡要带"可反悔开关"。
                                //   "这种修改设置类的我觉得需要把蓝牙已开启做一个可反悔的点击项目，
                                //    多个设置项改动就展示多个，按钮开关可以修改设置项目状态。"
                                //   只对 on/off 型能力开放（数值型如亮度/音量无法简单回退）。
                                String base = toggleBaseSign(cap.sign);
                                if (base != null) {
                                    java.util.List<FloatingSheetView.ToggleItem> toggles =
                                            new java.util.ArrayList<FloatingSheetView.ToggleItem>();
                                    toggles.add(new FloatingSheetView.ToggleItem(
                                            toggleLabel(base),
                                            cap.sign.endsWith(".on"),   // 执行后的真实状态
                                            base));
                                    mSheetView.setToggles(toggles);
                                }

                                // 晨间播报联动 —— 闹钟域能力执行后渲染
                                //   "晨间播报同款"卡片（设/查/开关/删共用一套渲染）。
                                renderAlarmCardsIfAny(cap, result);
                                // [kernel] [diag] 修复成功 → 「撤销」芯片；[s2] 记下了 → 「撤销」芯片（round3 #2）；[roles] 设了未安装的默认 → 安装提示
                                com.onesay.assistant.v2.kernel.FeatureKernel.get().onExecuted(FloatingSheetService.this, cap, args, result);
                            }
                        } else {
                            // 失败更要让用户看清原因，常驻不收起（原为 2.8s 自动收起）。
                            // 静默路径下用户没听到任何声音，补一次更长震动，手感上能区分成败。
                            if (silentAck) vibrateFeedback(TOGGLE_FAIL_VIBRATE_MS);
                            mSheetView.updateState("执行未达成", Color.parseColor("#FF9F0A"), null);
                            mSheetView.showResult(result);
                            // 联动失败（如开关键冲突）同样要看到卡片现状，
                            //   用户才能明白"为什么没生效"（dump 在 detail 里，照常可渲染）。
                            renderAlarmCardsIfAny(cap, result);
                            com.onesay.assistant.v2.kernel.FeatureKernel.get().onExecFailed(FloatingSheetService.this, cap, args, result);   // [kernel] [roles] 没装 → 「未安装 X，点此安装」（通用）
                            mSheetView.setDismissLabel("✕ 关闭");
                            cancelAutoHide();
                        }
                    }
                });
            }
        }));
    }

    // =====================================================================
    // 闹钟卡片渲染
    // =====================================================================

    /**
     * 闹钟域能力（clock.alarm.set / alarm.query / alarm.toggle / alarm.cancel）
     * 执行后，把 {@link ExecutionResult#detail} 里的 dump JSON 渲染成
     * "晨间播报同款"卡片（用户要求：设闹钟不再只回一句话）。
     * 非闹钟能力、缺 dump 时静默跳过（不影响任何既有链路）。
     */
    private void renderAlarmCardsIfAny(Capability cap, ExecutionResult r) {
        if (cap == null || r == null) return;
        if (!com.onesay.assistant.v2.executor.AlarmBridge.isAlarmSign(cap.sign)) return;
        String detail = r.detail;
        if (detail == null || detail.length() == 0) return;
        if (mSheetView == null) return;
        try {
            org.json.JSONObject dump = new org.json.JSONObject(detail);
            mSheetView.showAlarmCards(buildAlarmCardItems(dump), dump.optInt("lastId", -1), alarmSummary(dump));
        } catch (Throwable t) {
            Log.w(TAG, "闹钟卡片渲染失败: " + t);
        }
    }

    /**
     * dump JSON → 卡片展示模型（改成 Bixby 时钟样式；格式化在 {@link AlarmCardStyle#fromDump}，JVM 单测）。
     */
    private java.util.List<AlarmCardStyle.Card> buildAlarmCardItems(org.json.JSONObject dump) {
        boolean is24 = true;
        try {
            is24 = android.text.format.DateFormat.is24HourFormat(this);
        } catch (Throwable ignored) { }
        return AlarmCardStyle.fromDump(dump, is24, System.currentTimeMillis(), java.util.TimeZone.getDefault());
    }

    /** 卡片上方那行：「X 小时 Y 分钟后响铃」/「所有闹钟均已关闭」（Bixby 时钟列表标题下的副标题）。 */
    private static String alarmSummary(org.json.JSONObject dump) {
        long now = System.currentTimeMillis();
        return AlarmCardStyle.untilLabel(AlarmCardStyle.earliestOn(dump, now), now);
    }

    /** 执行回执备注的截断上限（防止一段底层报错把会话预算吃光）。 */
    private static final int RECEIPT_DETAIL_MAX = 120;

    /**
     * 把一次真实执行的结果写进会话上下文（闭环）。
     *
     * <p><b>为什么必须做</b>：改造前模型是**一次盲发** —— 它输出能力调用后就结束了，
     * 看不到这个调用是成功、失败、还是被校验打回。于是"做不成多步任务"被误判成"模型笨"，
     * 真因是**它没有反馈**。补上这一步，模型才能在下一轮做出正确判断。
     *
     * <p><b>只记事实，不记推测</b>：回执里只放执行器真实产出的东西 ——
     * 能力签名、成败、物理回读是否通过、失败时的错误码与底层详情。
     * 绝不写"可能""大概"这类词，避免把猜测污染成下一轮的"事实"。
     *
     * <p><b>不自动重试</b>：本方法只负责"让模型看见"，绝不触发任何重试或补偿动作 ——
     * 自动重试会在高危能力上放大风险，且可能烧掉大量 Token。
     *
     * <p><b>线程</b>：由执行线程调用；{@link com.onesay.assistant.v2.router.ConversationMemory}
     * 内部全部加锁，可安全跨线程。
     *
     * @param query 用户原始指令；null / 空 = 非语音路径（如手指拨开关），不记
     */
    private static void recordExecutionReceipt(String query, Capability cap, ExecutionResult r) {
        if (query == null || query.trim().length() == 0) return;
        if (cap == null || r == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("[执行回执] ").append(cap.summary)
          .append("（").append(cap.sign).append("）→ ")
          .append(r.ok ? "成功" : "失败");
        if (r.ok) {
            sb.append("；物理回读核对").append(r.verified ? "通过" : "未校验");
            // ★ audit-J 5：闹钟回执带上真实时刻（以前只写「成功」，下一轮问答模型按「现在 + 40 分钟」自己推算，说成 19:20，实际 19:18）
            if (com.onesay.assistant.v2.executor.AlarmBridge.isAlarmSign(cap.sign) && r.userMessage != null && r.userMessage.length() > 0) {
                String um = r.userMessage.replace('\n', ' ');
                sb.append("；结果=").append(um.length() > 60 ? um.substring(0, 60) : um);
            }
        } else {
            sb.append("；错误码=").append(r.code);
            String d = r.detail;
            if (d != null && d.length() > 0) {
                sb.append("；底层详情=")
                  .append(d.length() > RECEIPT_DETAIL_MAX ? d.substring(0, RECEIPT_DETAIL_MAX) : d);
            }
        }
        // ★ LOGIC-1：读取类成功 / 远端回执 → 外部内容进了记忆 → tainted
        com.onesay.assistant.v2.router.ConversationMemory.getInstance().record(query, sb.toString(),
                com.onesay.assistant.v2.router.AgentLoop.taintsAfter(cap, r));
        Log.i(TAG, "[FEEDBACK] 已回喂执行结果到会话上下文: " + sb);
    }

    private void playAckDone() {
        // ★ r2/voice-templates：这一轮会播回答模板（「已完成」「好了，看屏幕」…）→ 不再单独响 ack_done，免得连说两句
        if (TtsSpeaker.shouldSkipAckDone(this)) {
            Log.i(TAG, "[ACK] 跳过 ack_done（本轮由回答模板出声）");
            return;
        }
        // ★ R14.1 卡顿：MediaPlayer 的 setDataSource + prepare() 是同步的，原来在主线程、正好落在 思考→结果 的卡片动画上 → 挪到后台线程
        com.onesay.assistant.v2.util.Threads.start("AckDone", new Runnable() {
            @Override
            public void run() {
                playAckDoneSync();
            }
        });
    }

    private void playAckDoneSync() {
        try {
            // 埋点：用于区分"语音路径该响"与"开关路径不该响"（开关走 vibrateFeedback）
            Log.i(TAG, "[ACK] 播放完成应答音 ack_done.wav");
            final MediaPlayer mp = new MediaPlayer();
            // ★ 完成应答音同样走助手/系统音通道，不跟随媒体音量（与 WakeService 对齐）
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            AssetFileDescriptor afd = getAssets().openFd("ack_done.wav");
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.prepare();
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mediaPlayer) {
                    try { mediaPlayer.release(); } catch (Throwable ignore) {}
                }
            });
            mp.start();
        } catch (Throwable t) {
            Log.w(TAG, "playAckDone 异常: " + t.getMessage());
        }
    }

    /**
     * ★ arch/voice-sync：语音字幕（应答 / 催一次 / 提问提示）同步到面板状态行。主线程。只在复合任务还占着卡片时写，
     * 不盖住新请求的卡片。
     */
    public void voiceCaption(String text) {
        if (mSheetView == null || text == null || mActiveAgent == null || !agentOwnsCard(mActiveAgentSeq)) return;
        mSheetView.updateState("正在处理", Color.parseColor("#5E5CE6"), text);
    }

    /**
     * 敏感操作确认卡的语音提示。
     *
     * <p>内容为端侧 Piper TTS 合成的「检测到敏感操作，请确定执行」（assets/confirm_prompt.wav,
     * 24kHz mono 16bit）。弹出确认卡时播放 —— 用户可能没看屏幕，声音是唯一提示。
     */
    private void playConfirmPrompt() {
        playVoiceCueAsset(com.onesay.assistant.v2.tts.VoiceTemplate.CONFIRM);
    }

    /**
     * ★ r2/voice-templates：一次性提示短句（没听清 / 没连上网络 / 出错了），与确认卡提示同一播放方式（助手提示音通道，不进回答状态机、不触发续听）。
     * 受「语音问就语音答」开关约束（关了就只看屏幕）。任何线程。
     */
    /** ★ r2/voice-templates debug 直测：直接把一段答案当问答结果上卡片（看排版 / 回答模板；release 不生效）。 */
    public void debugShowAskReply(final String question, final String answer) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mSheetView == null) return;
                mSheetView.showAskReply(question, answer, StatusLinePolicy.Source.LOCAL);
                mSheetView.setDismissLabel("✕ 关闭");
                cancelAutoHide();
            }
        });
    }

    /** ★ arch/voice-sync debug 直测：当场（主线程）上一张问答卡 + 状态行（看结局语音与屏幕是否一致；release 不生效）。 */
    /** ★ fix/leak-guard debug：模拟一次流式回答上屏（beginAskStream + setAskStreamText），看守卫是否中途截停。 */
    public void debugAskStream(final String question, final String textSoFar) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mSheetView == null) return;
                mSheetView.beginAskStream(question, "正在生成答案…");
                mSheetView.setAskStreamText(textSoFar);
                cancelAutoHide();
            }
        });
    }

    /** 本地功能（如设置诊断的读屏查找）把一段结果上卡片。任何线程。 */
    public void showLocalReply(final String question, final String answer, final String title) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mSheetView == null) return;
                mSheetView.showAskReply(question, answer, StatusLinePolicy.Source.LOCAL);
                if (title != null) mSheetView.updateState(title, Color.parseColor("#5E5CE6"), null);
                mSheetView.setDismissLabel("✕ 关闭");
                cancelAutoHide();
            }
        });
    }

    public void debugShowAskReplyNow(String question, String answer, String title, String sub) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG || mSheetView == null) return;
        mSheetView.showAskReply(question, answer, StatusLinePolicy.Source.LOCAL);
        mSheetView.updateState(title, Color.parseColor("#5E5CE6"), sub);
        mSheetView.setDismissLabel("✕ 关闭");
        cancelAutoHide();
    }

    public void playVoiceCue(com.onesay.assistant.v2.tts.VoiceTemplate t) {
        if (t == null || !TtsSpeaker.isVoiceTurnReplyEnabled(this)) return;
        playVoiceCueAsset(t);
    }

    private void playVoiceCueAsset(com.onesay.assistant.v2.tts.VoiceTemplate t) {
        try {
            Log.i(TAG, "[ACK] 播放提示短句「" + t.text + "」" + t.asset);
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            AssetFileDescriptor afd = getAssets().openFd(t.asset);
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.prepare();
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mediaPlayer) {
                    try { mediaPlayer.release(); } catch (Throwable ignore) {}
                }
            });
            mp.start();
        } catch (Throwable e) {
            Log.w(TAG, "playVoiceCue 异常: " + e.getMessage());
        }
    }

    /**
     * 触觉反馈：**直接驱动震动马达**，不走系统 haptic 通道。
     *
     * <p>为什么不直接调 {@code View.performHapticFeedback}：本机
     * {@code settings get system haptic_feedback_enabled} = 0（用户在系统里关了"触摸振动"），
     * 走 haptic 通道会被系统静默吞掉 —— 表现就是"按了没反应"。
     * 用户明确要求"按钮需要调用震动马达做反馈"，所以这里直接拿 Vibrator 驱动，
     * 不再二次受系统开关影响（这是刻意的：面板按钮的反馈是产品行为，不是系统触感偏好）。
     */
    private void vibrateFeedback(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                Log.w(TAG, "[HAPTIC] 设备无震动马达，跳过");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);   // API 24/25 的旧接口
            }
            Log.i(TAG, "[HAPTIC] 震动反馈 " + ms + "ms");
        } catch (Throwable t) {
            Log.w(TAG, "震动反馈异常: " + t.getMessage());
        }
    }

    /**
     * 确认卡的用户选择（承接旧 onConfirmAction，语义扩展为三选项）。
     *
     * <p>两条汇路：
     * <ol>
     *   <li><b>循环挂起中</b>（mPendingLoopConfirm != null）：以用户决策唤醒循环 ——
     *       「允许单次/始终允许」放行并让循环**继续执行下一步**；「拒绝」标记未授权。
     *       执行与续跑全部交给循环本体，上下文天然连续（用户重点关注项）。</li>
     *   <li><b>单步确认卡</b>（无挂起循环）：直接执行（带原始指令 → 结果写回会话上下文）。</li>
     * </ol>
     */
    @Override
    public void onConfirmChoice(Capability cap, JSONObject args, boolean isTainted, int choice) {
        String choiceText = choice == FloatingSheetView.CHOICE_ALWAYS ? "始终允许"
                : choice == FloatingSheetView.CHOICE_ONCE ? "允许单次" : "拒绝";
        Log.i(TAG, "[CONFIRM] 用户选择「" + choiceText + "」"
                + (cap != null ? " → " + cap.sign : ""));
        vibrateFeedback(CONFIRM_VIBRATE_MS);   // 手指操作 → 震动受理确认，不播“已完成”

        // ① 循环挂起中 → 唤醒循环（执行与"继续下一步"由循环本体完成）
        PendingLoopConfirm pending;
        synchronized (mConfirmLock) {
            pending = mPendingLoopConfirm;
            if (pending != null) mPendingLoopConfirm = null;
        }
        if (pending != null) {
            if (choice == FloatingSheetView.CHOICE_ALWAYS && pending.cap != null) {
                com.onesay.assistant.v2.executor.ConsentPolicy.allowAlways(pending.cap);   // r2/fixes：带契约，不接受的能力拒收
            }
            if (choice == FloatingSheetView.CHOICE_REJECT) {
                pending.resolve(PendingLoopConfirm.VERDICT_DENY);
                // ★ round3 #1：卡片换成「已拒绝」终态；循环拿到 DENY 继续收尾，面板归任务（不收起）
                mSheetView.showRejectedState(pending.cap != null ? pending.cap.summary : null);
                applyRejectPolicy(com.onesay.assistant.v2.ui.ConfirmRejectPolicy.Path.AGENT_LOOP);
            } else {
                pending.resolve(PendingLoopConfirm.VERDICT_ALLOW);
                mSheetView.updateState("已允许", Color.parseColor("#30D158"), "正在执行并继续后续步骤…");
            }
            return;
        }

        // ② 单步确认卡：无等待线程 → 直接执行（与原 onConfirmAction 同链路，多带原始指令）
        if (choice == FloatingSheetView.CHOICE_REJECT) {
            mPendingConfirm.reject();
            // ★ round3 #1：红卡「取消」/ 黄卡「拒绝」同一终态：「已拒绝」卡 → 2.5 s 中性态 → 收起
            //   （另一张确认卡未决 / 任务在跑 / 文字输入中不收，见 ConfirmRejectPolicy）
            mSheetView.showRejectedState(cap != null ? cap.summary : null);
            applyRejectPolicy(com.onesay.assistant.v2.ui.ConfirmRejectPolicy.Path.SINGLE_STEP);
            return;
        }
        if (choice == FloatingSheetView.CHOICE_ALWAYS && cap != null) {
            com.onesay.assistant.v2.executor.ConsentPolicy.allowAlways(cap);   // r2/fixes：带契约，不接受的能力拒收
        }
        // 批准卡片绑定的一次性令牌后凭令牌执行；令牌过期/缺失 → 不执行，请用户重说
        com.onesay.assistant.v2.executor.PendingConfirm.Approved ap = mPendingConfirm.allow();
        if (!ap.ok()) {
            Log.w(TAG, "[CONFIRM] 确认令牌已失效（超时 >"
                    + (com.onesay.assistant.v2.executor.ExecutionGate.TOKEN_TTL_MS / 1000) + "s 或无挂起卡），不执行");
            mSheetView.updateState("确认已过期", Color.parseColor("#FF9F0A"), "请再说一次指令");
            return;
        }
        executeCapability(cap, args, ap.token, isTainted, true, ap.query);
    }

    /** 循环内挂起等待用户决策的确认会话（）。 */
    private static final class PendingLoopConfirm {
        static final int VERDICT_ALLOW = 1;
        static final int VERDICT_DENY = 2;
        final Capability cap;
        final JSONObject args;
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        volatile int verdict = 0;

        PendingLoopConfirm(Capability cap, JSONObject args) {
            this.cap = cap;
            this.args = args;
        }

        void resolve(int v) {
            verdict = v;
            latch.countDown();
        }
    }

    /** 取消/收起路径：挂起的确认等待直接按"未授权"放行（避免循环线程空转到超时）。 */
    private void resolvePendingLoopConfirm(int verdict) {
        PendingLoopConfirm p;
        synchronized (mConfirmLock) {
            p = mPendingLoopConfirm;
            mPendingLoopConfirm = null;
        }
        if (p != null) p.resolve(verdict);
    }

    /**
     * 用户拨动结果卡上的"可反悔开关"。
     *
     * 语义：把该设置项改到用户拨向的状态（on → 执行 xxx.on，off → 执行 xxx.off）。
     * 这是**用户主动操作**，不带 tainted，走与语音指令完全相同的执行链路
     * （含回读校验），执行完面板会自动刷新开关位置与结果文案。
     */
    @Override
    public void onToggleRequested(String baseSign, boolean newState) {
        if (baseSign == null) return;
        final String target = baseSign + (newState ? ".on" : ".off");
        Capability cap = CapabilityRegistry.getInstance().describe(target);
        if (cap == null) {
            Log.w(TAG, "反悔开关目标能力不存在: " + target);
            return;
        }
        Log.i(TAG, "用户拨动反悔开关 → 执行 " + target + "（静默，仅震动反馈）");
        vibrateFeedback(TOGGLE_VIBRATE_MS);   // 立即触觉反馈，不等执行结果
        executeCapability(cap, new org.json.JSONObject(), null, false, true);
    }

    // =====================================================================
    // 闹钟卡片交互（OneSay ↔ OneSay 时钟）
    // =====================================================================

    /**
     * 闹钟卡片开关：拨动某条闹钟 → 有序广播进 OneSay 时钟接口（它自己执行 + 落盘）→ 拿回最新闹钟表
     * 刷新卡片。失败（通道断 / 开关键冲突被拒）时用状态行文案如实说明。
     */
    @Override
    public void onAlarmToggle(final int id, final boolean on) {
        Log.i(TAG, "[UI-ACTION] 闹钟卡片开关: id=" + id + " on=" + on);
        vibrateFeedback(TOGGLE_VIBRATE_MS);   // 手指操作 → 立即触觉反馈
        com.onesay.assistant.v2.util.Threads.start("AlarmToggle", new Runnable() {
            @Override
            public void run() {
                final org.json.JSONObject dump = com.onesay.assistant.v2.executor.AlarmBridge.toggleFromCard(
                        FloatingSheetService.this, id, on);
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mSheetView == null) return;
                        if (dump == null) {
                            mSheetView.updateState("开关未生效", Color.parseColor("#FF9F0A"),
                                    "OneSay 时钟没有响应");
                            return;
                        }
                        org.json.JSONObject after =
                                com.onesay.assistant.v2.executor.AlarmBridge.findById(dump, id);
                        boolean nowOn = after != null && after.optBoolean("on", false);
                        mSheetView.showAlarmCards(buildAlarmCardItems(dump), dump.optInt("lastId", -1), alarmSummary(dump));
                        if (on && !nowOn) {
                            mSheetView.updateState("未开启", Color.parseColor("#FF9F0A"),
                                    "OneSay 时钟没有开启这个闹钟");
                        } else {
                            mSheetView.updateState(nowOn ? "闹钟已开启" : "闹钟已关闭",
                                    Color.parseColor("#30D158"), null);
                        }
                    }
                });
            }
        });
    }

    /**
     * 闹钟卡片点击：打开 OneSay 时钟（编辑页不导出，打开主界面，用户在那里精细调整）。面板 220ms 让位（对齐"打开应用"的行为）。
     */
    @Override
    public void onAlarmOpen(final int id) {
        Log.i(TAG, "[UI-ACTION] 打开 OneSay 时钟: id=" + id);
        vibrateFeedback(TOGGLE_VIBRATE_MS);
        mMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideSheet();
            }
        }, 220);
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(com.onesay.assistant.v2.executor.AlarmBridge.CLOCK_PKG);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            } else {
                Log.w(TAG, "没有安装 OneSay 时钟，打不开闹钟编辑页");
            }
        } catch (Throwable t) {
            Log.w(TAG, "打开 OneSay 时钟失败: " + t);
        }
    }

    /**
     * 若能力是 on/off 型，返回其前缀（不含 .on/.off）；否则 null。
     *
     * 只有 on/off 型才可"反悔"（拨回去）。数值型（亮度 {level}、音量 {level}）
     * 需要知道原值才能回退，本轮不纳入。
     */
    private static String toggleBaseSign(String sign) {
        if (sign == null) return null;
        if (sign.endsWith(".on")) return sign.substring(0, sign.length() - 3);
        if (sign.endsWith(".off")) return sign.substring(0, sign.length() - 4);
        return null;
    }

    /** 取开关的中文标签：用能力 summary 去掉开头的动词（"打开蓝牙" → "蓝牙"）。 */
    private static String toggleLabel(String baseSign) {
        Capability c = CapabilityRegistry.getInstance().describe(baseSign + ".on");
        if (c == null) c = CapabilityRegistry.getInstance().describe(baseSign + ".off");
        if (c != null && c.summary != null) {
            String label = c.summary;
            String[] verbs = { "打开", "开启", "启用", "关闭", "关掉", "停用" };
            for (String v : verbs) {
                if (label.startsWith(v)) { label = label.substring(v.length()); break; }
            }
            if (label.trim().length() > 0) return label.trim();
        }
        return baseSign;
    }

    @Override
    public void onCancelAction() {
        Log.i(TAG, "用户点击取消，立即收起面板并重置唤醒捕获");
        WakeService.cancelCaptureFromUi();
        dismissByUser();
        endSessionOnClose(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.CloseReason.USER_CLOSE); // ★ 旧界面立即强制收起；新界面卡片沉下退场（会话同样立即结束）
    }

    @Override
    public void onDismiss() {
        Log.i(TAG, "用户触发退出/取消，立即收起面板并重置唤醒捕获");
        WakeService.cancelCaptureFromUi();
        dismissByUser();
        endSessionOnClose(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.CloseReason.USER_CLOSE); // ★ 旧界面立即强制收起；新界面卡片沉下退场（会话同样立即结束）
    }

    // ==================== ★ r2/sysux 系统级交互（P1-3 续听 / P1-4 返回 · Home） ====================

    private android.content.BroadcastReceiver mSysDialogsReceiver;

    /** ACTION_CLOSE_SYSTEM_DIALOGS：reason=homekey / recentapps → 收起卡片（岛上任务不动；assist 原因不收，见 PanelSystemKeyPolicy）。 */
    private void registerSystemDialogsReceiver() {
        try {
            mSysDialogsReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    final String reason = intent != null ? intent.getStringExtra("reason") : null;
                    if (!PanelSystemKeyPolicy.collapseOnCloseSystemDialogs(reason)) return;
                    mMainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (!isCardShowing()) return;
                            Log.i(TAG, "[SYSKEY] 系统关闭对话框 reason=" + reason + " → 收起面板");
                            WakeService.cancelCaptureFromUi("Home/最近任务");
                            dismissByUser();
                            endSessionOnClose(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.CloseReason.USER_HOME);
                        }
                    });
                }
            };
            registerReceiver(mSysDialogsReceiver, new android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        } catch (Throwable t) {
            Log.w(TAG, "[SYSKEY] 注册 CLOSE_SYSTEM_DIALOGS 失败: " + t);
            mSysDialogsReceiver = null;
        }
    }

    /** 卡片在屏上（不在岛上、不在退场中）。主线程。 */
    public boolean isCardShowing() {
        if (mSheetView == null || mSheetView.getParent() == null || !mIsAttached) return false;
        if (mSheetView.getVisibility() != View.VISIBLE) return false;
        if (mSheetView.isV2()) {
            com.onesay.assistant.v2.ui.wake.WakeStage.Stage st = mSheetView.v2Stage();
            return st != com.onesay.assistant.v2.ui.wake.WakeStage.Stage.ISLAND && st != com.onesay.assistant.v2.ui.wake.WakeStage.Stage.HANDOFF
                    && st != com.onesay.assistant.v2.ui.wake.WakeStage.Stage.DISMISSING && st != com.onesay.assistant.v2.ui.wake.WakeStage.Stage.IDLE;
        }
        return true;
    }

    /** 卡片已收进岛（任务在后台）。 */
    public boolean isCardOnIsland() {
        if (mSheetView == null || !mSheetView.isV2()) return false;
        com.onesay.assistant.v2.ui.wake.WakeStage.Stage st = mSheetView.v2Stage();
        return st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.ISLAND || st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.HANDOFF;
    }

    /** 对话窗开着（续听判定用：对话窗里不自动开录音）。主线程。 */
    public boolean chatWindowOpen() {
        return mSheetView != null && mSheetView.chatActive();
    }

    /** 续听窗开了：卡片切回聆听态（不清上一轮问答），光效重新点亮。主线程。 */
    public void beginFollowUpListening() {
        if (!isCardShowing()) return;
        cancelAutoHide();
        mSheetView.switchToVoiceModeQuiet();   // 续听不算「接着说」边界（真开口提交才进对话窗）
        Log.i(TAG, "[FOLLOWUP] 卡片 → 聆听态（续听）");
    }

    /** 续听窗没人说话：回到结果态（光效收起），面板按原策略保留。任何线程。 */
    public void endFollowUpListening() {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                if (mSheetView == null || !isCardShowing()) return;
                mSheetView.endFollowUpListening();
                Log.i(TAG, "[FOLLOWUP] 续听结束 → 回结果态");
            }
        });
    }

    /** 助手侧结束整轮（续听里说「好了 / 谢谢」）：和用户点 ✕ 一样收起。 */
    public void dismissFromAssistant(String why) {
        if (!isCardShowing()) return;
        Log.i(TAG, "[FOLLOWUP] " + why + " → 收起面板");
        dismissByUser();
        endSessionOnClose(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.CloseReason.ASSISTANT_DONE);
    }

    /**
     * ★ r2/sysux P1-4：回答 / 结果已上屏 → 卡片窗改为可获焦（返回键由 FloatingSheetView.dispatchKeyEvent 收起面板，不再让下层 App 后退）。
     * 识屏 dump 还没做完就等它（最多约 6 s），绝不在 dump 前抢焦点（uiautomator 读的是焦点窗）。FloatingSheetView 在结果上屏处调用。
     */
    public static void onResultShown(String why) {
        final FloatingSheetService s = sInstance;
        if (s == null) return;
        s.mMainHandler.post(new Runnable() {
            @Override public void run() { s.makeSheetFocusableForResult(0); }
        });
    }

    private void makeSheetFocusableForResult(final int attempt) {
        try {
            if (mParams == null || mSheetView == null || !isCardShowing()) return;
            if ((mParams.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) return;   // 已可获焦
            boolean busy = com.onesay.assistant.v2.vision.screenref.ScreenContextCapture.wakeCaptureBusy()
                    || com.onesay.assistant.v2.vision.screenref.ScreenContextCapture.inFlight();
            PanelSystemKeyPolicy.Phase phase = PanelSystemKeyPolicy.Phase.ANSWER;
            if (!PanelSystemKeyPolicy.wantFocusable(mUseV2, phase, busy, false)) {
                if (busy && attempt < 20) {
                    mMainHandler.postDelayed(new Runnable() {
                        @Override public void run() { makeSheetFocusableForResult(attempt + 1); }
                    }, 300L);
                }
                return;
            }
            mParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            mWindowManager.updateViewLayout(mSheetView, mParams);
            Log.i(TAG, "[SYSKEY] 结果已上屏、识屏已完成 → 卡片窗可获焦（返回键收起面板）");
        } catch (Throwable t) {
            Log.w(TAG, "[SYSKEY] 切可获焦异常: " + t.getMessage());
        }
    }

    /**
     * 用户关闭面板 = 结束本次会话（拖拽条 / ✕ / 返回键 / 点外部 / Home / 「好了」）：清对话窗历史 + 会话记忆，
     * 下次唤醒像第一次一样弹原卡、直接录音、按新请求路由（不再进对话窗停在上一轮回复）。根因：ChatWindowPolicy.decide 在面板不在屏上时
     * 只看「会话记忆没超时」（device-1102 离屏保留 5 分钟），关窗后 5 分钟内再唤醒一律 EXPAND。自动收起 / 分享 / 跳转仍按保持期（ChatWindowPolicy.endsSession）。
     */
    private void endSessionOnClose(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.CloseReason why) {
        if (!com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.endsSession(why)) return;
        if (mSheetView != null) mSheetView.chatEndSession();
        com.onesay.assistant.v2.router.ConversationMemory m = com.onesay.assistant.v2.router.ConversationMemory.getInstance();
        int n = m.size();
        m.clear();
        mMainHandler.removeCallbacks(mConversationExpire);
        Log.i(TAG, "[CONV] 用户关闭面板（" + why + "）→ 会话结束（清 " + n + " 轮），下次唤醒全新开始");
    }

    /** 用户主动收起（✕ / 点外部 / 返回键）：旧界面 = hideSheet(true) 原样；新界面 = DISMISS 弹簧退场（不弹），会话立即结束。 */
    private void dismissByUser() {
        if (mUseV2 && mSheetView != null && mSheetView.isV2() && mSheetView.getParent() != null && !mCardRemovalPending) {
            hideSheet(false);
        } else {
            hideSheet(true);
        }
    }

    @Override
    public void onVoiceModeRequested() {
        Log.i(TAG, "[UI-ACTION] 用户点击「✦ 语音」按钮 -> 在当前会话中继续聆听");
        vibrateFeedback(TOGGLE_VIBRATE_MS);
        if (mRippleView != null && !mUseV2) {
            mRippleView.startRipple();
        }
        // ★//   旧实现走 WakeService.requestWake(...) —— 那是"新一次唤醒"语义，
        //   会经 mShowListeningRunnable → setListeningPrompt 把面板上的会话展示
        //   整屏重置（转录清空、结果卡隐藏），看起来像"点语音就新开了一个对话"。
        //   现在改走专用入口：只开捕获窗接着听，**不清会话展示、不重置上下文**。
        WakeService.requestContinueListening();
    }

    @Override
    public void onTextModeRequested() {
        Log.i(TAG, "[UI-ACTION] 用户点击「⌨ 文字」按钮 -> 切换文字输入");
        com.onesay.assistant.v2.kernel.FeatureKernel.get().onTextMode(this);   // [kernel] TEXT_MODE —— [screenref] 要打字了，立刻还焦点
        vibrateFeedback(TOGGLE_VIBRATE_MS);
        WakeService.cancelCaptureFromUi();
        makeSheetFocusableForText();   // ★ R14：新界面卡片默认不可获焦 —— 打字时才放开（键盘要焦点）
        if (mRippleView != null && !mUseV2) {
            mRippleView.fadeOutRipple(null);
        }
    }

    @Override
    public void onFinishListening() {
        // ★ r2/input：点问句卡 / 波形 = 说完了。还没开口（没有人声、没有流式文字）→ 不做任何事（不当成误触收起）
        if (!WakeService.canFinishCapture()) {
            Log.i(TAG, "[UI-ACTION] 点按卡片结束收音：还没听到说话，忽略");
            return;
        }
        Log.i(TAG, "[UI-ACTION] 点按卡片 → 说完了，马上识别");
        vibrateFeedback(TOGGLE_VIBRATE_MS);
        WakeService.finishCaptureFromUi("点按卡片");
    }

    /** ★ 综合包：🎤 收音中再点、还没开口 → 停止收音，卡片回结果态（不清上一轮问答，整轮焦点归还）。 */
    @Override
    public void onStopListening() {
        Log.i(TAG, "[UI-ACTION] 🎤 收音中再点 → 停止收音（不清屏）");
        vibrateFeedback(TOGGLE_VIBRATE_MS);
        WakeService.cancelCaptureFromUi();
        // 对话窗开着时停录音绝不收起（之前上一轮已冻结进历史 → hasVisibleResult=false → 误收起）
        boolean chat = mSheetView != null && mSheetView.chatActive();
        if (mSheetView != null && !com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.dismissOnStopListening(chat, mSheetView.hasVisibleResult())) {
            mSheetView.endFollowUpListening();
        } else {
            dismissByUser();   // 新唤醒还没开口就点停：没有上一轮可回，直接收起
        }
    }

    @Override
    public void onScreenModeRequested() {
        Log.i(TAG, "[UI-ACTION] 用户点击「◎ 识屏」按钮 -> 触发多模态识屏");
        vibrateFeedback(TOGGLE_VIBRATE_MS);
        WakeService.cancelCaptureFromUi();   // ★ R14：先干净地停掉收音（流式会话 / 窗到点回调一并撤），再识屏
        triggerScreenVision(null);
    }

    // ===================================================================== ★ 11-04 相机对话（Gemini Live 式）
    //   点输入栏相机钮 → 记下当前输入模式（录音 / 键盘）→ 卡片窗暂时隐身且不接触摸（不摘窗：会话与卡片内容都保留）
    //   → 拉起全屏 CameraLiveActivity（Camera2 实时取景 + 自动语音对话）。退出时 Activity 回调 onCameraModeExit → 卡片原样回来，
    //   之前在打字就回到打字（重新放开焦点 + 键盘），之前是录音就停在结果态（不自动开麦，免得退出就被录音）。
    private boolean mCamSuspended;
    private int mCamSavedFlags;

    @Override
    public void onCameraModeRequested() {
        Log.i(TAG, "[UI-ACTION] 用户点击「相机」按钮 -> 进入相机对话");
        vibrateFeedback(TOGGLE_VIBRATE_MS);
        boolean wasText = mSheetView != null && mSheetView.isTextModeActive();
        com.onesay.assistant.v2.ui.live.LiveInputMode.get().enterCamera(wasText
                ? com.onesay.assistant.v2.ui.live.LiveInputMode.Mode.TEXT : com.onesay.assistant.v2.ui.live.LiveInputMode.Mode.VOICE);
        WakeService.cancelCaptureFromUi("相机对话");   // 先干净地停掉面板收音（相机对话自己接管麦克风）
        cancelActiveStream(true);
        TtsSpeaker.stopAll();
        suspendSheetForCamera();
        try {
            Intent it = new Intent(this, com.onesay.assistant.v2.ui.live.CameraLiveActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "[CAMERA-LIVE] 拉起相机对话失败: " + t);
            onCameraModeExit();
        }
    }

    /** 卡片窗隐身 + 不接触摸 / 不获焦（相机页在下面要全屏收触摸）。主线程。 */
    private void suspendSheetForCamera() {
        cancelAutoHide();
        if (mSheetView == null) return;
        try { mSheetView.hideSoftKeyboard(); } catch (Throwable ignore) {}
        if (mSheetView.getParent() == null || mParams == null) return;
        try {
            mCamSavedFlags = mParams.flags;
            mParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            mParams.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            mSheetView.setVisibility(View.INVISIBLE);
            mWindowManager.updateViewLayout(mSheetView, mParams);
            if (mGlowView != null) mGlowView.setVisibility(View.INVISIBLE);
            mCamSuspended = true;
        } catch (Throwable t) {
            Log.w(TAG, "[CAMERA-LIVE] 卡片隐身异常: " + t.getMessage());
        }
    }

    /** 相机对话退出（CameraLiveActivity 结束时调，任意线程）：卡片回来、回到原来的输入模式。 */
    public void onCameraModeExit() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                com.onesay.assistant.v2.ui.live.LiveInputMode.Mode back = com.onesay.assistant.v2.ui.live.LiveInputMode.get().exitCamera();
                Log.i(TAG, "[CAMERA-LIVE] 相机对话结束 → 回到 " + back);
                if (mSheetView == null) return;
                if (mCamSuspended) {
                    mCamSuspended = false;
                    try {
                        mParams.flags = mCamSavedFlags;
                        mSheetView.setVisibility(View.VISIBLE);
                        if (mSheetView.getParent() != null) mWindowManager.updateViewLayout(mSheetView, mParams);
                        if (mGlowView != null) mGlowView.setVisibility(View.VISIBLE);
                    } catch (Throwable t) {
                        Log.w(TAG, "[CAMERA-LIVE] 卡片恢复异常: " + t.getMessage());
                    }
                }
                if (back == com.onesay.assistant.v2.ui.live.LiveInputMode.Mode.TEXT) {
                    if (mSheetView.getParent() == null) showSheet();
                    mSheetView.switchToTextMode();
                    makeSheetFocusableForText();
                } else if (mSheetView.getParent() != null) {
                    scheduleAutoHide(com.onesay.assistant.v2.ui.live.LiveInputMode.RESTORE_AUTO_HIDE_MS);
                }
            }
        });
    }

    /**
     * 触发屏幕视觉多模态识别与理解（ScreenVisionEngine）。
     *
     * @param userQuestion 用户自定义问题（若为 null 则执行通用屏幕内容总结）
     */
    public void triggerScreenVision(final String userQuestion) {
        cancelAutoHide();
        cancelActiveStream(true);   // r2/fixes 14：识屏是新请求，不取消后台复合任务
        Log.i(TAG, "[VISION] 启动多模态识屏流程，问题=" + (userQuestion != null ? userQuestion : "通用总结"));

        final String promptDisplay = (userQuestion != null && userQuestion.trim().length() > 0)
                ? userQuestion.trim()
                : "正在识别当前屏幕…";

        showSheet();
        if (mRippleView != null && !mUseV2) {
            mRippleView.fadeOutRipple(null);
        }

        mSheetView.beginAskStream(promptDisplay, "正在截取当前屏幕画面…");
        mSheetView.updateState("正在识屏", Color.parseColor("#5E5CE6"), promptDisplay);
        mSheetView.setDismissLabel("✕ 取消");

        ScreenVisionEngine.getInstance(this).recognizeScreen(userQuestion, new ScreenVisionEngine.Callback() {
            @Override
            public void onProgress(final String stage) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mSheetView != null && mIsAttached) {
                            mSheetView.setAskStreamStage(stage);
                            mSheetView.updateState("正在识屏", Color.parseColor("#5E5CE6"), stage);
                        }
                    }
                });
            }

            @Override
            public void onSuccess(final String answer) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mSheetView == null || !mIsAttached) return;
                        playAckDone();
                        mSheetView.showAskReply(userQuestion != null ? userQuestion : "屏幕内容识别", answer);
                        mSheetView.updateState("识屏完成", Color.parseColor("#30D158"), "已完成屏幕视觉解析");
                        mSheetView.setDismissLabel("✕ 关闭");
                        cancelAutoHide();

                        // 回喂会话记忆（多模态视觉上下文闭环）
                        StringBuilder receipt = new StringBuilder();
                        receipt.append("[视觉识屏回执] 屏幕识别完成：\n").append(answer);
                        com.onesay.assistant.v2.router.ConversationMemory.getInstance()
                                .record(userQuestion != null ? userQuestion : "识别屏幕", receipt.toString(), true);   // ★ LOGIC-1：识屏内容不可信
                        Log.i(TAG, "[FEEDBACK] 识屏结果已回喂会话上下文，字数=" + answer.length());
                    }
                });
            }

            @Override
            public void onError(final String errorMsg) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mSheetView == null || !mIsAttached) return;
                        // ★ LOGIC-8：普通识屏也开了全黑拦截 —— 固定码换成人话
                        final String shown = ScreenVisionEngine.ERR_SECURE_BLACK.equals(errorMsg)
                                ? "这个页面不允许截屏（画面全黑），没有上传，也就没法识别。" : errorMsg;
                        mSheetView.updateState("识屏失败", Color.parseColor("#FF453A"), shown);
                        mSheetView.showAskReply("识屏失败", shown);
                        mSheetView.setDismissLabel("✕ 关闭");
                        cancelAutoHide();
                    }
                });
            }
        });
    }

    // ===== ★ ui-K1 对话窗服务侧能力 BEGIN =====

    /** 对话窗底栏 / 页脚要用的服务侧能力（只在主线程回调）。 */
    private final class ChatServiceHost implements com.onesay.assistant.v2.ui.chat.ChatWindowController.ServiceHost {
        @Override public void circleSelect() {
            // 左侧按钮 = 「识屏」——点一下截当前屏（先藏自己的浮层）附到输入框上方，等用户输入问题后发送。
            //   不依赖「圈选即问」开关（那个只管原来的长按 / 说「圈一下」圈选）。
            WakeService.cancelCaptureFromUi("识屏");
            chatCaptureScreen();
        }

        @Override public void cameraCall() {
            onCameraModeRequested();   // 用户指定：直接进现有相机对话，不另做通话
        }

        @Override public void pickImage(boolean camera) {
            chatPickImage(camera);
        }

        @Override public void askWithImage(String question, String path) {
            chatAskWithImage(question, path);
        }

        @Override public void askWithScreen(String question, String path) {
            chatAskWithImage(question, path, true);
        }

        @Override public void share(String text) {
            chatShare(text);
        }

        @Override public void ensureFocusable() {
            chatEnsureFocusable();
        }
    }

    /**
     * 对话窗开着时再次唤醒（唤醒词 / 侧键）= 在当前对话窗开新一轮录音。
     * 收键盘、退出打字态；唤醒受理后 {@link com.onesay.assistant.v2.ui.chat.ChatWindowPolicy#REWAKE_CHECK_MS} 复核一次：
     * 捕获窗没开（被打字态 / 续听窗收尾 / 去抖吞掉等）→ 走「接着说」入口在当前窗补开一轮（不清会话、不收窗）。
     */
    private void chatRewake() {
        if (mSheetView == null) return;
        mSheetView.chatPrepareRewake();
        mMainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (mSheetView == null) return;
                boolean chat = mSheetView.chatActive();
                boolean open = WakeService.captureOpen();
                if (!com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.rewakeNeedsCapture(chat, open)) return;
                Log.i(TAG, "[CHAT] 对话窗再次唤醒：捕获窗没开 → 在当前对话窗补开一轮录音");
                mSheetView.switchToVoiceMode();
                WakeService.requestContinueListening();
            }
        }, com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.REWAKE_CHECK_MS);
    }

    private boolean mChatPickSuspended;

    /** 「+」→ 拍照 / 从相册选图：卡片窗先隐身让位（会话与对话窗内容都保留），透明跳板页拉起相机 / 相册。 */
    private void chatPickImage(boolean camera) {
        WakeService.cancelCaptureFromUi("选图");
        TtsSpeaker.stopAll();
        suspendSheetForCamera();
        mChatPickSuspended = mCamSuspended;
        mCamSuspended = false;
        try {
            Intent it = new Intent(this, com.onesay.assistant.v2.ui.chat.ChatImagePickActivity.class);
            it.putExtra(com.onesay.assistant.v2.ui.chat.ChatImagePickActivity.EXTRA_CAMERA, camera);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(it);
            Log.i(TAG, "[CHAT-IMG] 拉起" + (camera ? "相机" : "相册"));
        } catch (Throwable t) {
            Log.w(TAG, "[CHAT-IMG] 拉起选图失败: " + t);
            chatImageResult(null, null);
        }
    }

    /** 跳板页交回结果（主线程；path = null 表示取消 / 失败）：卡片窗回来，缩略图附到输入框上方。 */
    public static void chatImageResult(final String path, final android.graphics.Bitmap thumb) {
        final FloatingSheetService s = sInstance;
        if (s == null) {
            if (path != null) try { new java.io.File(path).delete(); } catch (Throwable ignore) { }
            return;
        }
        s.mMainHandler.post(new Runnable() {
            @Override public void run() { s.chatResumeAfterPick(path, thumb); }
        });
    }

    private void chatResumeAfterPick(String path, android.graphics.Bitmap thumb) {
        chatResumeAfterPick(path, thumb, false);
    }

    private volatile boolean mScreenAttachBusy;

    /**
     * 识屏附图（对话窗 / 悬浮输入条左侧按钮，两处都是 ChatBottomBar → ChatServiceHost.circleSelect）：
     * 藏起卡片窗 + 光效 + 岛 → 等 {@link com.onesay.assistant.v2.ui.chat.ChatWindowPolicy#SCREEN_HIDE_SETTLE_MS} → 工作线程截屏
     * （root screencap 优先，失败回落常驻读屏助手）→ 浮层恢复，截图以缩略图附在输入框上方；两条都不行给明确提示。只截不传。
     */
    private void chatCaptureScreen() {
        if (mScreenAttachBusy) return;
        mScreenAttachBusy = true;
        TtsSpeaker.stopAll();
        suspendSheetForCamera();
        mChatPickSuspended = mCamSuspended;
        mCamSuspended = false;
        final com.onesay.assistant.v2.s1.IslandController isl = com.onesay.assistant.v2.s1.S1Hooks.island();
        if (isl != null) isl.setCaptureHidden(true);
        Log.i(TAG, "[CHAT-IMG] 识屏：浮层已隐身，准备截屏");
        mMainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                com.onesay.assistant.v2.util.Threads.start("ScreenAttach", new Runnable() {
                    @Override public void run() {
                        ScreenVisionEngine.AttachShot r0;
                        try {
                            r0 = ScreenVisionEngine.getInstance(FloatingSheetService.this).captureForAttach();
                        } catch (Throwable t) {
                            r0 = null;
                        }
                        final ScreenVisionEngine.AttachShot r = r0;
                        mMainHandler.post(new Runnable() {
                            @Override public void run() {
                                if (isl != null) isl.setCaptureHidden(false);
                                mScreenAttachBusy = false;
                                String path = r == null ? null : r.path;
                                chatResumeAfterPick(path, r == null ? null : r.thumb, true);
                                if (path == null) {
                                    String why = r == null || r.error == null ? com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.SCREEN_CAPTURE_FAILED : r.error;
                                    Log.w(TAG, "[CHAT-IMG] 识屏截屏失败：" + why);
                                    android.widget.Toast.makeText(FloatingSheetService.this, why, android.widget.Toast.LENGTH_LONG).show();
                                } else {
                                    Log.i(TAG, "[CHAT-IMG] 识屏截屏完成 via=" + r.via);
                                }
                            }
                        });
                    }
                });
            }
        }, com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.SCREEN_HIDE_SETTLE_MS);
    }

    private void chatResumeAfterPick(String path, android.graphics.Bitmap thumb, boolean screen) {
        if (mChatPickSuspended && mSheetView != null) {
            mChatPickSuspended = false;
            try {
                mParams.flags = mCamSavedFlags;
                mSheetView.setVisibility(View.VISIBLE);
                if (mSheetView.getParent() != null) mWindowManager.updateViewLayout(mSheetView, mParams);
                if (mGlowView != null) mGlowView.setVisibility(View.VISIBLE);
            } catch (Throwable t) {
                Log.w(TAG, "[CHAT-IMG] 卡片恢复异常: " + t.getMessage());
            }
        }
        if (mSheetView == null) return;
        if (mSheetView.getParent() == null) showSheet();
        if (path != null) {
            if (screen) mSheetView.chatOnScreenCaptured(path, thumb);
            else mSheetView.chatOnImagePicked(path, thumb);
            makeSheetFocusableForText();   // 附了图多半要打字补一句
        }
    }

    /**
     * 对话窗里带图提问：走现有识图链路 {@link ScreenVisionEngine#recognizeImage}（与截图分析同一套压缩 / 上传 / 错误摘要），
     * 问句 + 图进这一轮，回答回喂会话（标不可信：图里的字来自外部）。问完删掉缓存里的这张图。
     */
    private void chatAskWithImage(final String question, final String path) {
        chatAskWithImage(question, path, false);
    }

    /** @param screen true = 识屏附图（当前屏幕截图）：提示词 / 回执按屏幕口径，回执同样标不可信；问完同样删图。 */
    private void chatAskWithImage(final String question, final String path, final boolean screen) {
        if (mSheetView == null) return;
        cancelAutoHide();
        cancelActiveStream(true);
        TtsSpeaker.setVoiceTurn(false);
        TtsSpeaker.stopAll();
        WakeService.cancelCaptureFromUi("看图");
        beginForegroundRequest();
        showSheet();
        mSheetView.updateState("正在解析", Color.parseColor("#0A84FF"), "“" + question + "”");   // = 一轮的边界 + 回显问句
        mSheetView.clearPreviousRound();   // 同 audit-J 5：清上一轮结果卡 / 回执 / 安装卡
        com.onesay.assistant.v2.ui.InstallPromptView.hideIn(mSheetView);
        mSheetView.chatNoteRoute((screen ? "识屏问答 · " : "看图问答 · ") + ScreenVisionEngine.effectiveModel(this));
        mSheetView.beginAskStream(question, screen ? "正在看屏幕…" : "正在看图…");
        Log.i(TAG, "[CHAT-IMG] " + (screen ? "识屏" : "带图") + "提问（" + question.length() + "字）");
        String prompt = screen ? com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.screenPrompt(question)
                : com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.imagePrompt(question);
        ScreenVisionEngine.getInstance(this).recognizeImage(path, prompt,
                new ScreenVisionEngine.Callback() {
                    @Override
                    public void onProgress(final String stage) {
                        mMainHandler.post(new Runnable() {
                            @Override public void run() {
                                if (mSheetView != null && mIsAttached) mSheetView.setAskStreamStage(stage);
                            }
                        });
                    }

                    @Override
                    public void onSuccess(final String answer) {
                        deleteQuietly(path);
                        mMainHandler.post(new Runnable() {
                            @Override public void run() {
                                if (mSheetView == null || !mIsAttached) return;
                                mSheetView.showAskReply(question, answer);
                                mSheetView.setDismissLabel("✕ 关闭");
                                com.onesay.assistant.v2.router.ConversationMemory.getInstance()
                                        .record(question, screen ? com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.screenReceipt(answer)
                                                : "[视觉识屏回执] 用户上传的图片识别完成：\n" + answer, true);   // LOGIC-1：图里的内容不可信
                            }
                        });
                    }

                    @Override
                    public void onError(final String errorMsg) {
                        deleteQuietly(path);
                        mMainHandler.post(new Runnable() {
                            @Override public void run() {
                                if (mSheetView == null || !mIsAttached) return;
                                mSheetView.showAskReply(question, (screen ? "没看成这张截图：" : "没看成这张图：") + errorMsg);
                                mSheetView.setDismissLabel("✕ 关闭");
                            }
                        });
                    }
                });
    }

    private static void deleteQuietly(String path) {
        if (path == null) return;
        try { new java.io.File(path).delete(); } catch (Throwable ignore) { }
    }

    /** 分享：先收起面板（分享面板在下面），再弹系统分享。会话照旧保留。 */
    private void chatShare(String text) {
        if (text == null || text.trim().length() == 0) return;
        dismissByUser();
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            Intent ch = Intent.createChooser(send, "分享");
            ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(ch);
        } catch (Throwable t) {
            Log.w(TAG, "[CHAT] 分享失败: " + t.getClass().getSimpleName());
        }
    }

    /** 选择文字要窗口焦点（不弹键盘）。 */
    private void chatEnsureFocusable() {
        try {
            if (mParams == null || mSheetView == null || mSheetView.getParent() == null) return;
            if ((mParams.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) return;
            mParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            mWindowManager.updateViewLayout(mSheetView, mParams);
        } catch (Throwable t) {
            Log.w(TAG, "[CHAT] 放开焦点异常: " + t.getMessage());
        }
    }
    // ===== ★ ui-K1 对话窗服务侧能力 END =====

    // ===== [screenref] BEGIN 识屏指代桥接（整块可原样搬运；只被 vision/screenref/ScreenRefHooks 调用）=====
    /**
     * [screenref] 屏幕实体动作（芯片点击 / 指代消解结果）走与 {@link #dispatch} 相同的
     * 门禁 → 确认卡 → 执行器链路。能力对象由 screenref 目录或注册表提供。
     *
     * <p>2026-10-07 集成：迁到 ExecutionGate 令牌流程（严格确认模式下旧布尔无法放行）。
     * 动作是用户亲口/亲手选定的，参数取自屏幕 —— 按 <b>tainted=false</b> 过闸（否则"复制取件码""导航去这里"每次都弹红卡）；
     * phone.call 等 risk=2 能力照常弹确认卡（显示号码），点「允许」后由 onConfirmChoice 凭令牌执行。
     * 本地能力（复制/打开链接）risk 0 未污染，门禁恒为放行，不会进入确认卡。
     */
    public void screenRefExecute(Capability cap, JSONObject args, String query) {
        if (mSheetView == null || cap == null) return;
        cancelAutoHide();
        final com.onesay.assistant.v2.executor.ExecutionGate.Decision gate =
                com.onesay.assistant.v2.executor.ExecutionGate.evaluate(cap, args, false);
        if (gate.decision == com.onesay.assistant.v2.executor.ToolGate.Decision.DENY) {
            mSheetView.updateState("已被拦截", Color.parseColor("#FF453A"), gate.reason);
            mSheetView.setDismissLabel("✕ 关闭");
            return;
        }
        boolean local = com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.isLocal(cap.sign);
        if (gate.decision == com.onesay.assistant.v2.executor.ToolGate.Decision.REQUIRE_CONFIRM) {
            if (local) {
                // 防御：本地能力不经 UnifiedExecutor，确认卡的令牌无处消费 —— 不应出现，出现即拒绝
                com.onesay.assistant.v2.executor.ExecutionGate.reject(gate.token);
                mSheetView.updateState("已被拦截", Color.parseColor("#FF453A"), "该屏幕操作需要确认，但当前入口不支持确认卡");
                mSheetView.setDismissLabel("✕ 关闭");
                return;
            }
            mPendingConfirm.hold(gate, query);
            mSheetView.showConfirmCard(cap, args, false, gate.tier, null);
            playConfirmPrompt();
            return;
        }
        if (local) {
            com.onesay.assistant.v2.vision.screenref.ScreenRefHooks.executeLocal(this, cap, args, query);
            return;
        }
        executeCapability(cap, args, null, false, false, query);
    }

    /** [screenref] 有指代但动作需要模型理解 → AgentLoop（AgentLoop 内的挂钩会自动注入屏幕上下文）。 */
    public void screenRefRunAgentLoop(String command) {
        if (mSheetView == null) return;
        UnifiedRouter.Cancel cancel = new UnifiedRouter.Cancel();
        mActiveStream = cancel;
        cancelActiveAgent(cancel, "识屏指代进 AgentLoop");   // r2/fixes 14
        runAgentLoop(command, cancel);
    }

    /** [screenref] 面板是否仍在屏幕上（迟到的异步结果据此丢弃）。 */
    public boolean screenRefSheetAlive() {
        return mSheetView != null && mIsAttached && !mHiding;
    }
    // ===== [screenref] END =====

    // ===== [s1] BEGIN S1 桥接（整块可原样搬运；只被 s1/* 调用）=====
    /**
     * [s1] 把面板从窗口摘下但<b>不</b>结束会话：不取消在途任务（AgentLoop 继续在后台线程跑）、不清会话记忆。
     * 与 {@link #detachFromWindowImmediate()} 的区别正在于此 —— 那是"会话结束"的汇点。
     */
    public synchronized void s1DetachForCapsule() {
        // ★ ui-K1：对话窗模式下长任务不再飞进灵动岛 —— 留在窗里显示进度（「好，我来处理 · 执行中…」）
        if (mSheetView != null && !com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.allowIslandMorph(mSheetView.chatActive())) {
            Log.i(TAG, "[CHAT] 对话窗开着：长任务留在窗里显示进度，不收进岛");
            return;
        }
        cancelAutoHide();
        WakeService.onPanelGone("收成胶囊");
        mMainHandler.removeCallbacks(mHideFallbackRunnable);
        mHiding = false;
        flushPendingCardRemoval();
        if (v2MorphToIsland()) return;   // ★ R13：卡片黏连形变飞进岛（舞台第一帧画出后才摘卡片窗）
        if (mSheetView != null && mSheetView.isV2()) {
            mSheetView.v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.COLLAPSE_TO_ISLAND);
            mSheetView.v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.LANDED_IN_ISLAND);
            detachGlow();
        }
        if (mSheetView != null && mSheetView.getParent() != null) {
            try { mSheetView.animate().cancel(); } catch (Throwable ignore) {}
            removeSheetWindow("capsule");
        }
        mIsAttached = false;
        detachRipple();
        Log.i(TAG, "[S1] 面板已收成胶囊（任务继续在后台执行）");
    }

    /** [s1] 安静地挂回面板（不放唤醒水光、不清内容）：胶囊展开 / 任务完成 / 剪贴板芯片 / 圈选结果。 */
    public synchronized void s1AttachSheet() {
        if (mSheetView == null) return;
        cancelAutoHide();
        flushPendingCardRemoval();
        if (mCardRemovalPending) {
            // 去程第一帧还没画出就要挂回：卡片窗其实还在 → 取消形变，原地留下
            mCardRemovalPending = false;
            if (mGlowView != null) mGlowView.releaseStage();
            com.onesay.assistant.v2.s1.S1Hooks.releaseIslandMorph();
            mIsAttached = true;
            mSheetView.v2RevealAfterUnmorph();
            mSheetView.v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.RETURN_FROM_ISLAND);
            return;
        }
        if (v2UnmorphFromIsland()) return;   // ★ R13：结果从岛「流」回卡片
        if (mIsAttached && mSheetView.getParent() != null && !mHiding) return;
        if (mHiding || mSheetView.getParent() != null) detachFromWindowImmediate();
        try {
            if (!mIsAttached && mSheetView.getParent() == null) chooseUi();
            if (mUseV2) ensureGlowAttached();
            mSheetView.setVisibility(View.VISIBLE);
            addSheetWindow("s1Attach");
            mIsAttached = true;
            mHiding = false;
            mShownAt = SystemClock.elapsedRealtime();
            mSheetView.animateShow();
            if (mUseV2) mSheetView.v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.RETURN_FROM_ISLAND);
            Log.i(TAG, "[S1] 面板已挂回（安静模式）");
        } catch (Throwable t) {
            Log.e(TAG, "[S1] 挂回面板失败: " + t.getMessage());
        }
    }

    // ===================================================================== ★ feat/api-first：停止键 / 时间轴 / 任务通知

    /** 单步执行（ExecSingle）的停止范围；没有在跑 → null。 */
    private volatile com.onesay.assistant.v2.net.StopScope mExecScope;
    private final Object mTaskNotifyLock = new Object();
    private com.onesay.assistant.v2.timeline.ExecTimeline mNotifyTl;
    private String mNotifyLine;

    /**
     * 停止键（输入条 / 对话窗底栏）：真中断当前任务 —— 取消复合任务与在途流式请求（停止范围里登记的连接立刻断开）、
     * 停掉单步执行的范围与点屏代理、挂起的确认按未授权放行；时间轴记「已停止」，状态行「已停止」，前台通知复原。任意线程。
     */
    public void stopCurrentTask(final String why) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mMainHandler.post(new Runnable() {
                @Override public void run() {
                    stopCurrentTask(why);
                }
            });
            return;
        }
        UnifiedRouter.Cancel agent = mActiveAgent;
        UnifiedRouter.Cancel stream = mActiveStream;
        com.onesay.assistant.v2.net.StopScope es = mExecScope;
        boolean any = false;
        if (agent != null && !agent.isCancelled()) {
            agent.cancel();
            any = true;
        }
        if (stream != null && stream != agent && !stream.isCancelled()) {
            stream.cancel();
            any = true;
        }
        if (es != null && !es.isStopped()) {
            es.stop(why);
            any = true;
        }
        if (stopGuiAgent(why)) any = true;
        resolvePendingLoopConfirm(PendingLoopConfirm.VERDICT_DENY);
        try {
            ScreenVisionEngine.getInstance(this).cancelInFlight();
        } catch (Throwable ignored) {
        }
        mActiveStream = null;
        Log.i(TAG, "[STOP] 停止键（" + why + "）" + (any ? "：已中断当前任务" : "：没有在跑的任务"));
        timelineStopped();
        if (mSheetView != null) {
            mSheetView.setTaskRunning(false);
            mSheetView.updateState("已停止", Color.parseColor("#8E8E93"), com.onesay.assistant.v2.tts.VoiceTemplate.CANCELLED.text);
            mSheetView.setDismissLabel("✕ 关闭");
        }
        cancelAutoHide();
    }

    /** 停掉正在点屏的执行模式（没有 / 已停 → false）。 */
    private boolean stopGuiAgent(String why) {
        try {
            com.onesay.assistant.v2.exec.ExecModeController ex = com.onesay.assistant.v2.exec.ExecModeController.current();
            if (ex == null || ex.state.stopped()) return false;
            ex.stop(why == null ? "停止" : why);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[STOP] 停点屏代理失败: " + t);
            return false;
        }
    }

    /** ★ borrow-1112：别处补来的说明行（点屏代理等待期间画面变化、已切换到备用模型）写进正在跑的那份时间轴（屏上的，或通知跟着的）。 */
    private final com.onesay.assistant.v2.timeline.TimelineNotes.Listener mTimelineNotes = new com.onesay.assistant.v2.timeline.TimelineNotes.Listener() {
        @Override public void onNote(String text) {
            com.onesay.assistant.v2.timeline.ExecTimeline tl = mSheetView == null ? null : mSheetView.timeline();
            if (tl == null || !tl.live()) {
                synchronized (mTaskNotifyLock) { tl = mNotifyTl; }
            }
            if (tl == null || !tl.live()) return;
            tl.info(text);
            timelineTouched(tl);
        }
    };

    /** 屏上这一轮的时间轴（和通知跟着的那份）记「已停止」。 */
    private void timelineStopped() {
        com.onesay.assistant.v2.timeline.ExecTimeline tl = mSheetView == null ? null : mSheetView.timeline();
        if (tl != null && tl.live()) tl.stopped();
        com.onesay.assistant.v2.timeline.ExecTimeline n;
        synchronized (mTaskNotifyLock) { n = mNotifyTl; }
        if (n != null && n != tl && n.live()) n.stopped();
        if (tl != null) timelineTouched(tl);
        if (n != null && n != tl) timelineTouched(n);
    }

    /** 时间轴数据变了（任意线程）：面板重画（只画屏上那一份）+ 前台通知「调用中：X」。 */
    private void timelineTouched(com.onesay.assistant.v2.timeline.ExecTimeline tl) {
        if (tl == null) return;
        FloatingSheetView v = mSheetView;
        if (v != null && v.timeline() == tl) v.timelineChanged();
        updateTaskNotification(tl);
    }

    /** 前台通知：任务在跑 →「调用中：X / 等你确认」，点了回面板；跑完 → 复原成常驻通知。只在文字变了时重发。 */
    private void updateTaskNotification(com.onesay.assistant.v2.timeline.ExecTimeline tl) {
        String line;
        synchronized (mTaskNotifyLock) {
            if (tl != null && tl.live()) {
                mNotifyTl = tl;
                line = tl.statusLine();
            } else {
                if (tl != null && mNotifyTl != null && mNotifyTl != tl) return;   // 别的任务的收尾，不动当前任务的通知
                mNotifyTl = null;
                line = null;
            }
            if (line == null ? mNotifyLine == null : line.equals(mNotifyLine)) return;
            mNotifyLine = line;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(2027, line == null ? buildServiceNotification() : buildTaskNotification(line));
        } catch (Throwable t) {
            Log.w(TAG, "[TASK-NOTIFY] 更新通知失败: " + t.getMessage());
        }
    }

    private Notification buildTaskNotification(String line) {
        Intent open = new Intent(this, FloatingSheetService.class);
        open.setAction(ACTION_OPEN_PANEL);
        android.app.PendingIntent pi = android.app.PendingIntent.getService(this, 2028, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, "bixby_sheet_channel") : new Notification.Builder(this);
        return b.setContentTitle(PANEL_NOTIFY_TITLE)
                .setContentText(line)
                .setSmallIcon(getApplicationInfo().icon)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    /** [s1] 停止后台任务：与用户点 ✕ 同一个汇点（取消在途任务 + 结束会话）。 */
    public void s1CancelActiveTask() {
        WakeService.cancelCaptureFromUi();
        detachFromWindowImmediate();
    }

    /** [s1] 立即收起面板（圈选前让位）。 */
    public void s1HideSheetNow() {
        hideSheet(true);
    }

    /**
     * ★ 11-04 B（机上 02:12:45：岛上点「允许」后执行模式收起小窗 → hideSheet(true) → 「复合任务还占着卡片，一并取消」，
     * AgentLoop 被当成用户关面板取消，手机操作做完了结果却被丢掉，用户看不到「改好了」）：执行模式让位不取消在途复合任务。
     */
    public void s1YieldSheetForExec() {
        mYieldNoCancel = true;
        try {
            hideSheet(true);
        } finally {
            mYieldNoCancel = false;
        }
    }

    private volatile boolean mYieldNoCancel;

    /** [island] 岛上确认卡的允许/拒绝：与面板确认卡同一个汇点（onConfirmChoice）。没有挂起的循环确认 → false。 */
    public boolean s1ResolveLoopConfirm(boolean allow) {
        PendingLoopConfirm p;
        synchronized (mConfirmLock) { p = mPendingLoopConfirm; }
        if (p == null || mSheetView == null) return false;
        onConfirmChoice(p.cap, p.args, false, allow ? FloatingSheetView.CHOICE_ONCE : FloatingSheetView.CHOICE_REJECT);
        return true;
    }

    // ===== ★ LC-5 新请求接管卡片 / 岛上任务结论 =====
    /** 前台请求号：每条新请求 +1（岛上任务据此判断卡片还归不归它，见 {@link com.onesay.assistant.v2.ui.wake.TaskResultRouting}）。 */
    private volatile int mRequestSeq;
    /** ★ 11-04 午：最近一次闹钟单步执行时的请求号（后台补读的 dump 只画给这一次）。 */
    private volatile int mAlarmReqSeq = -1;
    private final com.onesay.assistant.v2.ui.wake.TaskResultRouting.Store mTaskResults = new com.onesay.assistant.v2.ui.wake.TaskResultRouting.Store();

    public int requestSeq() {
        return mRequestSeq;
    }

    /**
     * ★ LC-5：新请求开始（主线程，onSubmitCommand 入口）。卡片在岛上（ISLAND / HANDOFF）时：挂回卡片窗、清掉旧内容、
     * 状态机走 NEW_REQUEST → THINKING，回答阶段按产品规则进全屏卡片；岛上任务继续（或保持完成状态），不取消、不挂回。
     */
    private void beginForegroundRequest() {
        mRequestSeq++;
        if (!mUseV2 || mSheetView == null || !mSheetView.isV2()) return;
        com.onesay.assistant.v2.ui.wake.WakeStage.Stage st = mSheetView.v2Stage();
        if (!com.onesay.assistant.v2.ui.wake.WakeStage.newRequestLeavesIsland(st)) return;
        Log.i(TAG, "[LC-5] 新请求接管卡片（stage=" + st + "，请求号 " + mRequestSeq + "；岛上任务不动）");
        if (!mIsAttached || mSheetView.getParent() == null || mHiding) showSheet();
        mSheetView.v2BeginRequest();
    }

    /** ★ audit-J 5：上一轮的结果卡 / 回执正文 / 开关 / 闹钟卡 / 安装提示在新一轮输入时清掉（Gemini 与我方冒烟：「别打开蓝牙」屏上还挂着上一轮闹钟）。 */
    private void clearPreviousRoundViews() {
        if (mSheetView == null) return;
        try {
            mSheetView.clearPreviousRound();
            com.onesay.assistant.v2.ui.InstallPromptView.hideIn(mSheetView);
        } catch (Throwable t) {
            Log.w(TAG, "[AUDIT-J5] 清上一轮卡片异常: " + t.getMessage());
        }
    }

    /** ★ LC-5：岛上任务收工时登记结论（任意线程）。 */
    public void s1RememberTaskResult(String resultId, String question, String answer, int dispatchSeq) {
        mTaskResults.put(resultId, new com.onesay.assistant.v2.ui.wake.TaskResultRouting.Result(question, answer, dispatchSeq));
    }

    /**
     * ★ LC-5：岛上结果卡「查看」（主线程）。派发后卡片被新请求接管过 → 先把这条任务的结论画回卡片再流回；
     * 没登记（非跑腿任务）/ 卡片仍是它 → 与以前一样直接流回。
     */
    public void s1ShowTaskResult(String resultId) {
        com.onesay.assistant.v2.ui.wake.TaskResultRouting.Result e = mTaskResults.get(resultId);
        boolean restore = e != null && mSheetView != null
                && com.onesay.assistant.v2.ui.wake.TaskResultRouting.restoreOnView(e.seq, mRequestSeq);
        boolean onScreen = mIsAttached && mSheetView != null && mSheetView.getParent() != null && !mHiding;
        if (restore) Log.i(TAG, "[LC-5] 「查看」" + resultId + "：卡片已被新请求接管过，先重画任务结论");
        if (restore && !onScreen) mSheetView.showAskReply(e.question, e.answer);
        s1AttachSheet();
        if (restore && onScreen) mSheetView.showAskReply(e.question, e.answer);
    }

    /** [island] 失败卡「重试」：挂回面板，用原指令重新走一遍（与用户重说一次等价）。 */
    public void s1RetryCommand(String command) {
        if (command == null || command.trim().length() == 0 || mSheetView == null) return;
        s1AttachSheet();
        onSubmitCommand(command.trim());
    }
    // ===== [s1] END =====

    // ===== ★ R13 唤醒界面 v2 BEGIN（光晕窗 / 状态效果 / 卡 ↔ 岛形变 / 退场）=====

    /** 挂载新会话时定新 / 旧界面（kill-switch 每次现查）。 */
    /** ★ R14（diag C4）：同一次唤醒里 prepareWakeWindowFlags 已经选过界面，showSheet 不再重复读 prefs / 查 kill 文件（/sdcard 走 FUSE）。 */
    private long mUiChosenAt;

    private void chooseUiOnce() {
        if (android.os.SystemClock.elapsedRealtime() - mUiChosenAt < 1500L) return;
        chooseUi();
    }

    /**
     * ★ R14（diag C1 / P0-4）：ACTION_LISTEN 时、识屏挂钩之前定好卡片窗的可获焦状态。
     * 新界面：面板不在屏上 → 从挂窗起就带 FLAG_NOT_FOCUSABLE（uiautomator dump 读的是活动窗口，卡片不抢焦点 = 读到的是下面的 App）；
     *   ScreenRefHooks 看到已经不可获焦就不做 focusTrick，于是没有「dump 完 updateViewLayout → 导航栏 inset 变化 → 整窗跳 126 px」。
     *   只有进文字模式（{@link #makeSheetFocusableForText}）才放开。
     * 旧界面：保持可获焦（照旧由 focusTrick 临时关、dump 完恢复）。
     */
    private void prepareWakeWindowFlags() {
        try {
            if (mSheetView == null || mParams == null) return;
            if (mIsAttached && mSheetView.getParent() != null) return;   // 已在屏上（追加唤醒）：不动
            chooseUi();
            if (mUseV2) mParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            else mParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } catch (Throwable t) {
            Log.w(TAG, "[WAKE-UI] 预设窗口 flag 异常: " + t.getMessage());
        }
    }

    /** ★ R14：文字模式要键盘 → 卡片窗改成可获焦（一次 updateViewLayout，发生在用户点按之后，不在入场动画里），再补弹一次键盘。 */
    private void makeSheetFocusableForText() {
        try {
            if (mParams == null || (mParams.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) return;
            mParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            // ★ r2/kb-fix：打字期间不让屏幕超时熄灭（锁屏上打字时一息屏，托底就退、面板被锁屏藏住）
            mParams.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            if (mSheetView != null && mSheetView.getParent() != null && mIsAttached) {
                mWindowManager.updateViewLayout(mSheetView, mParams);
                mMainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mSheetView != null) mSheetView.showSoftKeyboard();
                    }
                }, 120L);
            }
            Log.i(TAG, "[WAKE-UI] 文字模式：卡片窗改为可获焦");
        } catch (Throwable t) {
            Log.w(TAG, "[WAKE-UI] 放开焦点异常: " + t.getMessage());
        }
    }

    private void chooseUi() {
        mUiChosenAt = android.os.SystemClock.elapsedRealtime();
        boolean v2 = com.onesay.assistant.v2.ui.wake.WakeUiSettings.useNew(this);
        boolean instant = !com.onesay.assistant.v2.ui.wake.WakeUiSettings.animatorsEnabled(this);
        if (v2 != mUseV2) Log.i(TAG, "[WAKE-UI] 唤醒界面 → " + (v2 ? "新（R13）" : "旧（classic）") + " " + com.onesay.assistant.v2.ui.wake.WakeUiSettings.dump(this));
        mUseV2 = v2;
        if (mSheetView != null) {
            mSheetView.setV2(v2);
            mSheetView.setV2Instant(instant);
            // ★ lane C 窗口交互：新界面的卡片窗固定全屏（窗口尺寸从挂上到摘下不变 → 没有 WRAP_CONTENT relayout）；
            //   只在窗口没挂上时改 LayoutParams（挂着时改要 updateViewLayout，正是要消掉的那次 relayout）
            if (mParams != null && mSheetView.getParent() == null) {
                boolean full = com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.fullWindow(v2,
                        com.onesay.assistant.v2.ui.wake.WakeUiSettings.windowPref(this));
                // ★ LC-1：全屏窗要靠 touchable region 让紧凑卡以外的点击落到后面的 App；系统不支持就退回 wrap 窗（触摸行为已知正确）
                if (full && !com.onesay.assistant.v2.ui.wake.TouchRegionCompat.supported()) full = false;
                mParams.height = full ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT;
                mSheetView.setV2FullWindow(full);
            }
        }
        if (mGlowView != null) {
            mGlowView.setInstant(instant);
            mGlowView.setBakedTint(com.onesay.assistant.v2.ui.wake.WakeUiSettings.bakedTint(this));
        }
        if (v2 && mRippleView != null && (mRippleAttached || mRippleView.getParent() != null)) {
            try {
                mRippleView.stopRipple();
                mWindowManager.removeViewImmediate(mRippleView);
            } catch (Throwable ignore) {}
            mRippleAttached = false;
        }
    }

    private void ensureGlowAttached() {
        mMainHandler.removeCallbacks(mGlowDetachCheck);
        if (mGlowView == null || mGlowAttached || mGlowView.getParent() != null) return;
        try {
            mWindowManager.addView(mGlowView, mRippleParams);
            mGlowAttached = true;
        } catch (Throwable t) {
            Log.w(TAG, "[WAKE-UI] 挂载光晕窗异常: " + t.getMessage());
        }
    }

    private void detachGlow() {
        mMainHandler.removeCallbacks(mGlowDetachCheck);
        if (mGlowView == null) return;
        mGlowView.releaseStage();
        mGlowView.setGlow(false);
        mGlowView.setDim(0f, 0.45f);
        if (mGlowAttached || mGlowView.getParent() != null) {
            try {
                mWindowManager.removeViewImmediate(mGlowView);
            } catch (Throwable t) {
                try { mWindowManager.removeView(mGlowView); } catch (Throwable ignore) {}
            }
        }
        mGlowAttached = false;
    }

    /** [R13] 摘光晕窗的复查次数（120 ms 一次，最多 ~3 s；光卡在亮着也到点强摘，不无限轮询）。 */
    private int mGlowDetachTries;
    private static final int GLOW_DETACH_MAX_TRIES = 25;

    private final Runnable mGlowDetachCheck = new Runnable() {
        @Override
        public void run() {
            if (mGlowView == null || !mGlowAttached) return;
            boolean cardUp = mSheetView != null && mSheetView.getParent() != null && mIsAttached;
            if (cardUp) return;   // 卡片又回来了（新一轮）：光晕窗归它
            if (mGlowView.idle() || ++mGlowDetachTries >= GLOW_DETACH_MAX_TRIES) {
                if (!mGlowView.idle()) Log.w(TAG, "[WAKE-UI] 光晕窗 3 s 仍未静止，强制摘下");
                detachGlow();
            } else {
                mMainHandler.postDelayed(this, 120);
            }
        }
    };

    /** 光 / 压暗淡完再摘光晕窗（卡片已经不在屏上时）。 */
    private void scheduleGlowDetachCheck() {
        mMainHandler.removeCallbacks(mGlowDetachCheck);
        mGlowDetachTries = 0;
        mMainHandler.postDelayed(mGlowDetachCheck, 120);
    }

    // ---- ★ R13 卡死兜底：状态看门狗（每个状态事件后只挂一个延时检查，见 StageWatchdog）----
    private com.onesay.assistant.v2.ui.wake.WakeStage.Stage mWdStage;
    private long mWdEnterAt, mWdLastEventAt;

    private final Runnable mStageWatchdog = new Runnable() {
        @Override
        public void run() {
            if (mSheetView == null || !mSheetView.isV2()) return;
            com.onesay.assistant.v2.ui.wake.WakeStage.Stage st = mSheetView.v2Stage();
            if (st != mWdStage) return;   // 状态已变：新的检查已经挂上
            long now = SystemClock.uptimeMillis();
            com.onesay.assistant.v2.ui.wake.StageWatchdog.Action a = com.onesay.assistant.v2.ui.wake.StageWatchdog.verdict(st, now, mWdEnterAt, mWdLastEventAt);
            if (a == com.onesay.assistant.v2.ui.wake.StageWatchdog.Action.NONE) {
                long dl = com.onesay.assistant.v2.ui.wake.StageWatchdog.deadlineMs(st, mWdEnterAt, mWdLastEventAt);
                if (dl >= 0) mMainHandler.postDelayed(this, Math.max(50L, dl - now));
                return;
            }
            Log.w(TAG, "[WAKE-WD] 状态 " + st + " 停留超时（进入 " + (now - mWdEnterAt) + "ms / 距上次事件 " + (now - mWdLastEventAt)
                    + "ms）→ " + a);
            if (a == com.onesay.assistant.v2.ui.wake.StageWatchdog.Action.TIMEOUT_ERROR && isPanelOnScreen()) {
                WakeService.cancelCaptureFromUi();
                cancelActiveStream();
                cancelAutoHide();
                mSheetView.markTerminalState(Color.parseColor("#FF9F0A"), com.onesay.assistant.v2.ui.wake.WakeCopy.BADGE_TIMEOUT, "本次唤醒已结束");
                applyErrorPolicy(com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.NO_SPEECH_NEW_WAKE);
            } else {
                detachFromWindowImmediate();
                if (mGlowView != null) {
                    mGlowView.releaseStage();
                    detachGlow();
                }
            }
        }
    };

    private void armStageWatchdog(com.onesay.assistant.v2.ui.wake.WakeStage.Transition t) {
        long now = SystemClock.uptimeMillis();
        if (t.to != mWdStage || t.changed()) {
            mWdStage = t.to;
            mWdEnterAt = now;
        }
        mWdLastEventAt = now;
        mMainHandler.removeCallbacks(mStageWatchdog);
        long dl = com.onesay.assistant.v2.ui.wake.StageWatchdog.deadlineMs(t.to, mWdEnterAt, mWdLastEventAt);
        if (dl >= 0) mMainHandler.postDelayed(mStageWatchdog, Math.max(50L, dl - now));
    }

    /** 状态机效果 → 光晕 / 压暗 / 触感（卡片自己的换入效果由 FloatingSheetView 执行）。 */
    private void applyStageEffects(com.onesay.assistant.v2.ui.wake.WakeStage.Transition t) {
        armStageWatchdog(t);
        if (mGlowView == null) return;
        com.onesay.assistant.v2.ui.wake.WakeStage.Stage to = t.to;
        if (t.has(com.onesay.assistant.v2.ui.wake.WakeStage.Effect.GLOW_IGNITE)) {
            ensureGlowAttached();
            mGlowView.setGlow(true);
        }
        if (t.has(com.onesay.assistant.v2.ui.wake.WakeStage.Effect.GLOW_INHALE)) mGlowView.inhale();
        if (t.has(com.onesay.assistant.v2.ui.wake.WakeStage.Effect.GLOW_OFF) || t.has(com.onesay.assistant.v2.ui.wake.WakeStage.Effect.GLOW_DIM_ERROR)) {
            mGlowView.setGlow(false);
        }
        mGlowView.setDim(com.onesay.assistant.v2.ui.wake.WakeStage.dimLevel(to), com.onesay.assistant.v2.ui.wake.WakeStage.dimStartFrac(to));
        if (t.has(com.onesay.assistant.v2.ui.wake.WakeStage.Effect.HAPTIC_IGNITE)) vibrateWave(new long[] {0, 12});
        if (t.has(com.onesay.assistant.v2.ui.wake.WakeStage.Effect.HAPTIC_RESULT)) vibrateWave(new long[] {0, 10, 60, 10});
        if (t.has(com.onesay.assistant.v2.ui.wake.WakeStage.Effect.HAPTIC_LAND)) vibrateWave(new long[] {0, 18});
        if (t.has(com.onesay.assistant.v2.ui.wake.WakeStage.Effect.HAPTIC_ERROR)) vibrateWave(new long[] {0, 30, 50, 40, 50, 30});
        if (to == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.IDLE || to == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.ISLAND) scheduleGlowDetachCheck();
    }

    /**
     * 新界面触感（方案 §9；D5=A 与现有一致：直接驱动马达，不受系统「触摸振动」开关影响 —— 用户系统里关了触摸振动，
     * 走 haptic 通道会被吞掉）。Note 8 不一定有振幅控制，轻重靠时长区分。
     */
    private void vibrateWave(long[] pattern) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (pattern.length == 2) v.vibrate(VibrationEffect.createOneShot(pattern[1], VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(pattern, -1);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[HAPTIC] 震动异常: " + t.getMessage());
        }
    }

    /** 新界面退场：会话立即结束（流式作废 / 会话记忆清空），卡片 DISMISS 弹簧沉下后再摘窗。 */
    private void v2DismissAnimated() {
        if (mHiding) return;
        mHiding = true;
        cancelActiveStream();
        endConversationSoft();   // ★ device-1102：面板离屏不再立即清会话（5 分钟内再唤醒 / 再按录音接着上轮），超时才结束
        final int gen = ++mDismissGen;
        mSheetView.v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.DISMISS);
        mMainHandler.removeCallbacks(mV2DismissFallback);
        mMainHandler.postDelayed(mV2DismissFallback, 900);
        mSheetView.animateHide(new Runnable() {
            @Override
            public void run() {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != mDismissGen) return;   // 退场途中来了新一轮挂载：旧的摘窗作废
                        detachFromWindowImmediate();
                    }
                });
            }
        });
    }

    private final Runnable mV2DismissFallback = new Runnable() {
        @Override
        public void run() {
            if (!mHiding) return;
            Log.w(TAG, "[WAKE-UI] 退场动画超时兜底，强制摘窗");
            detachFromWindowImmediate();
        }
    };

    /** 只摘卡片窗（不结束会话；形变第一帧之后用）。 */
    private void removeSheetWindowOnly(String why) {
        if (mSheetView == null || mSheetView.getParent() == null) return;
        try { com.onesay.assistant.v2.ui.motion.ViewSpring.cancel(mSheetView); } catch (Throwable ignore) {}
        removeSheetWindow("morph:" + why);
        mSheetView.v2RevealAfterUnmorph();   // 复位 alpha / 平移，下次挂回从干净状态开始
        Log.i(TAG, "[WAKE-MORPH] 卡片窗已摘（" + why + "）");
    }

    /** 去程：能做形变就做（返回 true = 已接管 DETACH_SHEET）。 */
    private boolean v2MorphToIsland() {
        if (!mUseV2 || mSheetView == null || !mSheetView.isV2() || mSheetView.getParent() == null || mGlowView == null) return false;
        boolean animators = com.onesay.assistant.v2.ui.wake.WakeUiSettings.animatorsEnabled(this);
        if (!com.onesay.assistant.v2.ui.wake.WakeUiSettings.decideMorph(true, com.onesay.assistant.v2.ui.wake.WakeUiSettings.morphPref(this), animators)) return false;
        float[] target = com.onesay.assistant.v2.s1.S1Hooks.islandMorphTarget();
        if (target == null) return false;
        long t0 = SystemClock.uptimeMillis();
        android.graphics.Bitmap snap = mSheetView.snapshotCard();
        android.graphics.RectF card = mSheetView.cardRectOnScreen();
        if (snap == null || card.width() < 2f) return false;
        Log.i(TAG, "[WAKE-MORPH] 卡片快照 " + snap.getWidth() + "x" + snap.getHeight() + " 用时 " + (SystemClock.uptimeMillis() - t0) + "ms");
        com.onesay.assistant.v2.s1.S1Hooks.armIslandMorph();
        ensureGlowAttached();
        mSheetView.v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.COLLAPSE_TO_ISLAND);
        WakeService.onPanelGone("进岛");   // ★ r2/sysux：长任务进岛必须归还焦点（不能让音乐一直停着）
        mIsAttached = false;
        mCardRemovalPending = true;
        mGlowView.startMorph(snap, card, mSheetView.cardRadius(), target);
        Log.i(TAG, "[S1] 卡片形变飞进岛（任务继续在后台执行）");
        return true;
    }

    /** 回程：岛还在屏上 → 先透明挂上卡片窗、量出位置，再让舞台从岛的形状流下来（返回 true = 已接管 ATTACH_SHEET）。 */
    private boolean v2UnmorphFromIsland() {
        if (!mUseV2 || mSheetView == null || !mSheetView.isV2() || mGlowView == null) return false;
        if (mIsAttached && mSheetView.getParent() != null && !mHiding) return false;
        if (!com.onesay.assistant.v2.ui.wake.WakeUiSettings.decideMorph(true, com.onesay.assistant.v2.ui.wake.WakeUiSettings.morphPref(this),
                com.onesay.assistant.v2.ui.wake.WakeUiSettings.animatorsEnabled(this))) return false;
        final float[] shape0 = com.onesay.assistant.v2.s1.S1Hooks.islandShape();
        if (shape0 == null) return false;
        // 卡片第一次排版（长结论 StaticLayout）要几百 ms：这期间岛冻在原形状（不收、不缩），舞台从它当下的样子流下来
        com.onesay.assistant.v2.s1.S1Hooks.holdIslandForHandoff(1200L);
        if (mHiding || mSheetView.getParent() != null) detachFromWindowImmediate();
        try {
            ensureGlowAttached();
            mSheetView.v2HoldInvisible();
            addSheetWindow("unmorph");
            mIsAttached = true;
            mHiding = false;
            mShownAt = SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            Log.e(TAG, "[WAKE-MORPH] 回程挂卡片窗失败: " + t.getMessage());
            return false;
        }
        mSheetView.v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.RETURN_FROM_ISLAND);
        mSheetView.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mSheetView.getViewTreeObserver().removeOnPreDrawListener(this);
                android.graphics.RectF card = mSheetView.cardRectOnScreen();
                android.graphics.Bitmap snap = mSheetView.snapshotCard();
                float[] now = com.onesay.assistant.v2.s1.S1Hooks.islandShape();
                mGlowView.startUnmorph(now != null ? now : shape0, card, mSheetView.cardRadius(), snap);
                com.onesay.assistant.v2.s1.S1Hooks.handOffIsland();
                return true;
            }
        });
        Log.i(TAG, "[S1] 结果从岛流回卡片");
        return true;
    }

    /** 进程起来 1.5 s 后预热新界面的烘焙位图（不拖慢唤醒键按下 → CARD_IN 第一帧）。 */
    private void prewarmV2Later() {
        mMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!com.onesay.assistant.v2.ui.wake.WakeUiSettings.useNew(FloatingSheetService.this) || mSheetView == null) return;
                    if (mSheetView.getParent() == null) mSheetView.setV2(true);
                    mSheetView.prewarmV2();
                    if (mGlowView != null) mGlowView.prewarm();
                } catch (Throwable t) {
                    Log.w(TAG, "[WAKE-UI] 预热失败（首次唤醒时再烘焙）: " + t.getMessage());
                }
            }
        }, 1500);
    }

    /** 调试：新界面状态一行。 */
    public String debugWakeUi() {
        return com.onesay.assistant.v2.ui.wake.WakeUiSettings.dump(this) + " | useV2=" + mUseV2 + " stage="
                + (mSheetView != null ? mSheetView.v2Stage() : null) + " sheetAttached=" + mIsAttached + " glow=" + mGlowAttached
                + " stageActive=" + (mGlowView != null && mGlowView.stageActive()) + " body=" + (mSheetView != null ? mSheetView.v2BodyText() : null);
    }
    // ===== ★ R13 唤醒界面 v2 END =====

    /** [kernel] 给插件的 WAKE 挂钩用（[screenref] 唤醒采集要面板窗口参数）。 */
    public WindowManager pluginWindowManager() {
        return mWindowManager;
    }

    /** [kernel] 给插件的 WAKE 挂钩用（[screenref]）。 */
    public WindowManager.LayoutParams pluginSheetParams() {
        return mParams;
    }

    public FloatingSheetView getSheetView() {
        return mSheetView;
    }

    @Override
    public void onDestroy() {
        com.onesay.assistant.v2.timeline.TimelineNotes.remove(mTimelineNotes);   // ★ borrow-1112
        // ★ BUG-I2b：先摘面板并同步报 panel(false)（岛在下面的 [s1] onServiceDestroy 里才拆，post 的回调等不到）
        cancelAutoHide();
        hideSheet(true);
        try { com.onesay.assistant.v2.ui.ime.ImeProbeWindow.get(this).destroy(); } catch (Throwable ignore) {}   // ★ r2/kb-fix
        if (mSysDialogsReceiver != null) {
            try { unregisterReceiver(mSysDialogsReceiver); } catch (Throwable ignore) {}
            mSysDialogsReceiver = null;
        }
        mPresence.flushNow();
        com.onesay.assistant.v2.kernel.FeatureKernel.get().onServiceDestroy(this);   // [kernel] [s3] → [s1] → [imgsave] → [summary]
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
