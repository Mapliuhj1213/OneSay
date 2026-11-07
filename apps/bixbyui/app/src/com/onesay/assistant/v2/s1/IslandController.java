package com.onesay.assistant.v2.s1;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ToolGate;
import com.onesay.assistant.v2.island.IslandGeometry;
import com.onesay.assistant.v2.island.IslandModel;
import com.onesay.assistant.v2.island.IslandSettings;
import com.onesay.assistant.v2.island.IslandView;
import com.onesay.assistant.v2.island.Subgoal;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * [s1/island v3] 后台任务 + 取件码 + S3 提醒 / 后台任务 / 待办的 Android 侧：把 {@link IslandModel} 的效果落到窗口上。全部在主线程执行。
 *
 * <h3>浮层（v3：不再有无障碍服务；2026-10-10 第 6 项：旧悬浮胶囊与旧取件码浮卡已删除）</h3>
 * <ul>
 *   <li><b>岛</b>（有悬浮窗权限 + bixby_island.enabled + 没有 island.off）：{@link IslandView}，<b>TYPE_APPLICATION_OVERLAY</b>，
 *       状态栏下方 2dp、水平居中（不读 DisplayCutout）；全屏时只显示关键事件，上沿 8dp；</li>
 *   <li><b>不画浮层</b>（{@link IslandModel.Mode#NONE}：island.off / 岛开关关 / 没有悬浮窗权限 / 岛窗口挂载失败）：
 *       不收起面板，长任务的步骤、确认卡、结果都留在面板上（与接入 S1 前一致）；S3 提醒与结果走通知，
 *       S3 无人值守的确认没法弹 → 按拒绝处理并发一条通知说明（见 S3Runner）；取件码不上屏（短信本身仍在通知栏）。</li>
 * </ul>
 *
 * <h3>全屏检测（岛有活动时才挂 1×1 探针窗口）</h3>
 * ① 探针上的 {@code OnSystemUiVisibilityChangeListener}（SYSTEM_UI_FLAG_FULLSCREEN）→ ② 探针的 WindowInsets（顶部 inset == 0）→
 * ③ 前两路 30 s 没有任何回调时，有 root 才每 2 s 跑一次 {@code dumpsys window policy} 兜底（字段需真机确认）。
 */
public final class IslandController implements com.onesay.assistant.v2.kernel.IslandHost {

    static final String TAG = "Island";
    private static final String TAG_S1 = "S1";
    static final String PREF = "bixby_s1_pickup";
    static final String KEY_CARDS = "cards";
    static final String ACTION_SMS = "android.provider.Telephony.SMS_RECEIVED";
    static final long FS_ROOT_POLL_MS = 2000L;
    static final long FS_ROOT_QUIET_MS = 30000L;

    // 第 4 阶段（D-3a）：岛上 S3 活动的回调接口挪到内核（IslandHost.Listener），S3Runner 经 FeatureKernel.island("s3") 注册。

    private final FloatingSheetService mSvc;
    private final WindowManager mWm;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    final IslandModel mModel = new IslandModel();
    com.onesay.assistant.v2.kernel.IslandHost.Listener mBgListener;
    private final java.util.Map<String, com.onesay.assistant.v2.kernel.IslandHost.Listener> mBgListeners =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, com.onesay.assistant.v2.kernel.IslandHost.Listener>());
    /** ★ R10 记一记的回调（与 S3 的 mBgListener 分开，互不覆盖）。 */
    com.onesay.assistant.v2.kernel.IslandHost.NoteListener mNoteListener;
    /** ★ 消息提醒的回调。 */
    com.onesay.assistant.v2.kernel.IslandHost.NoticeListener mNoticeListener;
    /** ★ feat/suggest 主动建议卡回调。 */
    com.onesay.assistant.v2.kernel.IslandHost.SuggestListener mSuggestListener;
    /** 第 4 阶段（D-3a）：活动 id → 放它上岛的插件（只用于 dump / 日志）。主线程以外也会写，用同步 Map。 */
    private final java.util.Map<String, String> mOwners = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, String>());

    // 岛
    private IslandView mIsland;
    private WindowManager.LayoutParams mIslandParams;
    private IslandGeometry.Anchor mAnchor;
    private Rect mLastWin = new Rect();

    // 全屏探针
    private View mProbe;
    private long mProbeSignalAt;
    private boolean mFsByVis, mFsByInsets, mFsByRoot;
    private Boolean mFsOverride;
    private boolean mRootPolling;
    /** ★ R11：root 兜底这一路读不到（无 root / 字段名不同）→ 只能信 insets（带去抖）。 */
    private boolean mRootUnavailable;
    private boolean mFsConfirming;
    /** ★ R11：进入全屏要稳定这么久才生效（真机：探针挂上的第一帧 insetTop=0，14 ms 后又变 63 → 岛闪一下 = 「自己抖」）。 */
    static final long FS_ENTER_DEBOUNCE_MS = 350L;
    private final Runnable mFsApply = new Runnable() {
        @Override
        public void run() {
            applyFullscreen("debounced");
        }
    };

    // 取件码
    private PickupCardStore mStore;
    private BroadcastReceiver mSmsReceiver;

    // 任务
    private String mLastCommand;
    private boolean mOutcomeFinished = true;
    private String mOutcomeAnswer;
    private String mOutcomeReason;
    /** [r2/bike-chooser ⑤] 结论在卡片上带可点内容（应用网格）→ 收工要挂回卡片，不能只进岛。 */
    private boolean mOutcomeNeedsCard;
    private String mConfirmTitle, mConfirmDetail;
    boolean mDemo;

    private final Runnable mExpiryTick = new Runnable() {
        @Override
        public void run() {
            pushPickups("expiry");
        }
    };
    private final Runnable mRootPoll = new Runnable() {
        @Override
        public void run() {
            pollRootFullscreen();
        }
    };

    IslandController(FloatingSheetService svc, WindowManager wm) {
        mSvc = svc;
        mWm = wm;
    }

    // ================================================================== 生命周期

    void start() {
        mStore = PickupCardStore.fromJson(prefs().getString(KEY_CARDS, null), System.currentTimeMillis());
        registerSms();
        refreshMode("start");
        pushPickups("start");
    }

    void destroy() {
        mMain.removeCallbacksAndMessages(null);
        if (mSmsReceiver != null) {
            try {
                mSvc.unregisterReceiver(mSmsReceiver);
            } catch (Throwable ignored) {
            }
            mSmsReceiver = null;
        }
        removeIsland();
        removeProbe();
    }

    /** 屏幕旋转 / 分辨率变化（FSS.onConfigurationChanged）。 */
    void onDisplayChanged() {
        mAnchor = null;
        if (mIsland != null) {
            Point p = screenSize();
            mIsland.setAnchor(anchor(), p.x, p.y);
        }
    }

    // ================================================================== 前台任务入口

    void taskStarted(final String command) {
        taskStarted(command, true);
    }

    /** ★ arch/island-progress：islandNow = 分诊判多步 / 混合，一开始就上岛；false = 懒上岛（单次接口调用不上岛）。 */
    void taskStarted(final String command, final boolean islandNow) {
        runMain(new Runnable() {
            @Override
            public void run() {
                if (mDemo) endDemo();
                mLastCommand = command;
                mOutcomeFinished = true;
                mOutcomeAnswer = null;
                mOutcomeReason = null;
                mOutcomeNeedsCard = false;
                refreshMode("taskStarted");
                // ★ lane C：新唤醒界面「一转任务立刻收进岛」（150 ms，留一帧给任务行排版）；旧界面 6 s
                mModel.configure(S1Settings.capsuleEnabled(mSvc), S1Settings.autoCollapseEnabled(mSvc),
                        com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.collapseDelayMs(com.onesay.assistant.v2.ui.wake.WakeUiSettings.useNew(mSvc)));
                apply(mModel.taskStarted(System.currentTimeMillis(), command, islandNow), "taskStarted(island=" + islandNow + ")");
            }
        });
    }

    void step(final String text) {
        runMain(new Runnable() {
            @Override
            public void run() {
                apply(mModel.step(text), "step");
            }
        });
    }

    /** ★ 11-04 ①：当前一行改写（不算一步）。任意线程。 */
    void note(final String text) {
        runMain(new Runnable() {
            @Override
            public void run() {
                apply(mModel.stepNote(text), "note");
            }
        });
    }

    /** ★ 11-04 午 进度反馈：进入 / 离开「思考中」（等大模型）。任意线程。 */
    void thinking(final boolean on) {
        runMain(new Runnable() {
            @Override
            public void run() {
                apply(mModel.thinking(on), on ? "thinking" : "thinkingEnd");
            }
        });
    }

    /** ★ arch/voice-sync：语音字幕（不算一步）。任意线程。 */
    void caption(final String text) {
        runMain(new Runnable() {
            @Override
            public void run() {
                apply(mModel.caption(text), "caption");
            }
        });
    }

    // ================================================================== ★ arch/island-progress（C 路）：子目标 + ask_user（任意线程）

    void subgoals(final List<Subgoal> goals) {
        runMain(new Runnable() {
            @Override
            public void run() {
                apply(mModel.subgoals(goals), "subgoals(" + (goals == null ? 0 : goals.size()) + ")");
            }
        });
    }

    /** 能上岛就上岛并返回 true；岛接不了（无浮层 / 没任务 / 确认卡未决 / 选项 &lt; 2）→ false，调用方走别的入口。 */
    boolean askUser(final String id, final String question, final List<String> options, final long timeoutMs) {
        if (!mModel.canAskUser(options)) return false;
        runMain(new Runnable() {
            @Override
            public void run() {
                List<IslandModel.Effect> e = mModel.askUser(id, question, options, timeoutMs);
                if (e.isEmpty()) {   // 排队期间状态变了（任务刚收工 / 确认卡弹出）→ 让等待方拿 no_answer，不悬着
                    S1Hooks.askBroker().expire(id);
                    return;
                }
                apply(e, "askUser(" + id + ")");
            }
        });
        return true;
    }

    void askResolved(final String id) {
        runMain(new Runnable() {
            @Override
            public void run() {
                apply(mModel.askResolved(id), "askResolved(" + id + ")");
            }
        });
    }

    /** 关键事件（「已接单」）：公开入口，目前只有调试驱动会调（执行器以后再接）。 */
    void keyUpdate(final String text) {
        runMain(new Runnable() {
            @Override
            public void run() {
                apply(mModel.keyUpdate(text), "keyUpdate");
            }
        });
    }

    boolean outsideTap() {
        refreshMode("outsideTap");
        List<IslandModel.Effect> e = mModel.requestCollapse(IslandModel.CollapseReason.OUTSIDE_TAP);
        if (e.isEmpty()) return false;
        Log.i(TAG, "[ISLAND] 点面板外区域 → 任务转后台，面板收成岛");
        apply(e, "outsideTap");
        return true;
    }

    void confirmInfo(Capability cap, JSONObject args) {
        mConfirmTitle = cap == null ? null : (cap.summary != null && cap.summary.length() > 0 ? cap.summary : cap.sign);
        mConfirmDetail = confirmDetail(args);
    }

    void confirmRequired() {
        List<IslandModel.Effect> e = mModel.confirmRequired(mConfirmTitle, mConfirmDetail);
        if (!e.isEmpty()) {
            Log.i(TAG, "[ISLAND] 需要确认 → " + (mModel.mode() == IslandModel.Mode.ISLAND
                    ? "岛上立即展开确认卡（确认卡不能藏在紧凑态里）"
                    : "强制展开面板（不画浮层时确认卡在面板上）"));
        }
        apply(e, "confirmRequired");
    }

    void confirmResolved() {
        runMain(new Runnable() {
            @Override
            public void run() {
                apply(mModel.confirmResolved(), "confirmResolved");
            }
        });
    }

    void taskOutcome(boolean finished, String answer, String reason) {
        taskOutcome(finished, answer, reason, false);
    }

    void taskOutcome(boolean finished, String answer, String reason, boolean needsCard) {
        mOutcomeFinished = finished;
        mOutcomeAnswer = answer;
        mOutcomeReason = reason;
        mOutcomeNeedsCard = needsCard;
    }

    void taskEnded(final boolean cancelled, final boolean navLaunched) {
        runMain(new Runnable() {
            @Override
            public void run() {
                boolean hasText = mOutcomeAnswer != null && mOutcomeAnswer.trim().length() > 0;
                boolean newUi = com.onesay.assistant.v2.ui.wake.WakeUiSettings.useNew(mSvc);
                // ★ lane C：新界面下任务完成「只在岛上反馈」—— 不论结论长短（含闹钟卡）都在岛上展开结果卡（超长截断，「查看」看全文），
                //   不再自动挂回卡片。失败 / 取消 / 拉起了界面 / 没有结论 → 照旧（岛上失败卡 / 对勾）。岛关了（island.off）→
                //   taskEndedShort 自己回落到 taskEnded（只能挂回卡片，没别处可显示）。
                final boolean needsCard = mOutcomeNeedsCard;   // [r2/bike-chooser ⑤] 带应用网格 → 两条「只进岛」都不走，挂回卡片
                if (!cancelled && !navLaunched && mOutcomeFinished && hasText
                        && com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.islandOnlyAfterTask(newUi, needsCard)) {
                    String[] hb = com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.islandSummary(mOutcomeAnswer);
                    if (hb != null) {
                        apply(mModel.taskEndedShort(false, false, true, hb[0], hb[1], "查看", mOutcomeReason, System.currentTimeMillis()),
                                "taskEndedIslandOnly(text=" + mOutcomeAnswer.trim().length() + "字)");
                        return;
                    }
                }
                // ★ R13 D4=A：新唤醒界面下，收在岛上的任务带着<b>短</b>结论收工 → 岛就地展开结果卡（「查看」= 流回卡片看全文）；
                //   长结论 / 闹钟卡 / 拉起界面 / 失败 / 取消 → 原规则（长结论挂回卡片，新界面下以「流回」形变出现）
                if (!cancelled && !navLaunched && mOutcomeFinished && !needsCard
                        && com.onesay.assistant.v2.ui.wake.IslandResultPolicy.onIsland(mOutcomeAnswer, com.onesay.assistant.v2.ui.wake.WakeUiSettings.useNew(mSvc))) {
                    String[] hb = com.onesay.assistant.v2.ui.wake.IslandResultPolicy.split(mOutcomeAnswer);
                    apply(mModel.taskEndedShort(false, false, true, hb[0], hb[1], "查看", mOutcomeReason, System.currentTimeMillis()),
                            "taskEndedShort(text=" + mOutcomeAnswer.trim().length() + "字)");
                    return;
                }
                apply(mModel.taskEnded(cancelled, navLaunched, mOutcomeFinished, hasText, mOutcomeReason, System.currentTimeMillis()),
                        "taskEnded(cancelled=" + cancelled + ",nav=" + navLaunched + ",finished=" + mOutcomeFinished
                                + ",text=" + hasText + ")");
            }
        });
    }

    void newWake() {
        if (mDemo) {
            endDemo();
            return;
        }
        apply(mModel.newWake(), "newWake");
    }

    // ================================================================== ★ r2/fast-screen 执行模式入口（任意线程）

    public void execStarted(final String owner, final String id, final String title, final String pkg) {
        own(owner, id);
        runMain(new Runnable() {
            @Override public void run() {
                refreshMode("execStarted");
                apply(mModel.execStarted(id, title, pkg, System.currentTimeMillis()), "execStarted(" + id + ")");
            }
        });
    }

    public void execStep(final String id, final String word, final String pkg) {
        runMain(new Runnable() {
            @Override public void run() {
                if (pkg != null) apply(mModel.execIcon(id, pkg), "execIcon");
                apply(mModel.bgStep(id, word), "execStep(" + id + ")");
            }
        });
    }

    public void execPaused(final String id, final String why) {
        runMain(new Runnable() {
            @Override public void run() {
                apply(mModel.execPaused(id, why), "execPaused(" + id + ")");
            }
        });
    }

    public void execResumed(final String id) {
        runMain(new Runnable() {
            @Override public void run() {
                apply(mModel.execResumed(id), "execResumed(" + id + ")");
            }
        });
    }

    public void execDone(final String id, final boolean ok, final String title, final String detail) {
        runMain(new Runnable() {
            @Override public void run() {
                apply(mModel.execDone(id, ok, title, detail, System.currentTimeMillis()), "execDone(" + id + ",ok=" + ok + ")");
            }
        });
    }

    /** 岛窗口当前在屏幕上的矩形（防误触护罩要给它留洞）；没挂出返回空矩形。 */
    public Rect islandWindowRect() {
        Rect r = new Rect();
        if (mIsland != null && mIsland.getParent() != null) r.set(mLastWin);
        return r;
    }

    /** 岛模式是否可用（执行模式要靠岛承载状态）。 */
    public boolean execReady() {
        return mModel.mode() == IslandModel.Mode.ISLAND;
    }

    // ================================================================== S3 活动入口（任意线程）

    void bgStarted(final String id, final String title) {
        runMain(new Runnable() {
            @Override public void run() {
                refreshMode("bgStarted");
                apply(mModel.bgStarted(id, title, System.currentTimeMillis()), "bgStarted(" + id + ")");
            }
        });
    }

    void bgStep(final String id, final String text) {
        runMain(new Runnable() {
            @Override public void run() {
                apply(mModel.bgStep(id, text), "bgStep(" + id + ")");
            }
        });
    }

    void bgConfirm(final String id, final String title, final String detail) {
        runMain(new Runnable() {
            @Override public void run() {
                apply(mModel.bgConfirm(id, title, detail), "bgConfirm(" + id + ")");
            }
        });
    }

    void bgConfirmResolved(final String id) {
        runMain(new Runnable() {
            @Override public void run() {
                apply(mModel.bgConfirmResolved(id), "bgConfirmResolved(" + id + ")");
            }
        });
    }

    void bgEnded(final String id, final boolean ok, final String reason) {
        runMain(new Runnable() {
            @Override public void run() {
                apply(mModel.bgEnded(id, ok, reason, System.currentTimeMillis()), "bgEnded(" + id + ",ok=" + ok + ")");
            }
        });
    }

    void remindFired(final String id, final String title, final long atMs, final int lateMin) {
        runMain(new Runnable() {
            @Override public void run() {
                refreshMode("remindFired");
                apply(mModel.remindFired(id, title, atMs, lateMin, System.currentTimeMillis()),
                        "remindFired(" + id + (lateMin > 0 ? ",late=" + lateMin : "") + ")");
            }
        });
    }

    void removeBg(final String id) {
        runMain(new Runnable() {
            @Override public void run() {
                apply(mModel.removeBg(id), "removeBg(" + id + ")");
            }
        });
    }

    // ================================================================== 第 4 阶段（D-3a）：IslandHost —— 插件经内核放活动（任意线程）

    @Override public android.content.Context hostContext() {
        return mSvc;
    }

    @Override public boolean islandMode() {
        return mModel.mode() == IslandModel.Mode.ISLAND;
    }

    @Override public void setListener(String owner, com.onesay.assistant.v2.kernel.IslandHost.Listener l) {
        mBgListener = l;   // 默认回调：行为与以前一致（只有 S3 调这个）
        if (owner != null) mBgListeners.put(owner, l);
    }

    /** 2026-10-22 [brief]：只给自己放的活动收回调（「停止」），不动默认回调（不会顶掉 S3）。 */
    @Override public void setScopedListener(String owner, com.onesay.assistant.v2.kernel.IslandHost.Listener l) {
        if (owner != null) mBgListeners.put(owner, l);
    }

    /** 活动 id → 放它的插件登记的回调；找不到就用默认回调。 */
    com.onesay.assistant.v2.kernel.IslandHost.Listener listenerFor(String id) {
        String o = id == null ? null : mOwners.get(id);
        com.onesay.assistant.v2.kernel.IslandHost.Listener l = o == null ? null : mBgListeners.get(o);
        return l != null ? l : mBgListener;
    }

    private void own(String owner, String id) {
        if (owner != null && id != null) mOwners.put(id, owner);
    }

    @Override public void bgStarted(String owner, String id, String title) {
        own(owner, id);
        bgStarted(id, title);
    }

    @Override public void bgStep(String owner, String id, String text) {
        bgStep(id, text);
    }

    @Override public void bgConfirm(String owner, String id, String title, String detail) {
        own(owner, id);
        bgConfirm(id, title, detail);
    }

    @Override public void bgConfirmResolved(String owner, String id) {
        bgConfirmResolved(id);
    }

    @Override public void bgEnded(String owner, String id, boolean ok, String reason) {
        bgEnded(id, ok, reason);
    }

    @Override public void remindFired(String owner, String id, String title, long atMs, int lateMin) {
        own(owner, id);
        remindFired(id, title, atMs, lateMin);
    }

    @Override public void removeBg(String owner, String id) {
        mOwners.remove(id);
        removeBg(id);
    }

    @Override public void setTodos(String owner, List<IslandModel.TodoRow> rows, int today, boolean chip) {
        own(owner, "todo");
        setTodos(rows, today, chip);
    }

    // ------------------------------------------------------------------ ★ R10 记一记

    @Override public void setNoteListener(String owner, com.onesay.assistant.v2.kernel.IslandHost.NoteListener l) {
        mNoteListener = l;
    }

    @Override public void noteStarted(String owner, final String id, final String title) {
        own(owner, id);
        runMain(new Runnable() {
            @Override public void run() {
                refreshMode("noteStarted");
                apply(mModel.noteStarted(id, title, System.currentTimeMillis()), "noteStarted(" + id + ")");
            }
        });
    }

    @Override public void noteResult(String owner, final String id, final boolean ok, final String title, final String detail,
                                     final String meta, final List<String> actions) {
        own(owner, id);
        runMain(new Runnable() {
            @Override public void run() {
                refreshMode("noteResult");
                apply(mModel.noteResult(id, ok, title, detail, meta, actions, System.currentTimeMillis()), "noteResult(" + id + ",ok=" + ok + ")");
            }
        });
    }

    @Override public boolean addCode(String owner, final String kind, final String code, final String station, final String pkg,
                                     final long ttlMs, final long noteId) {
        if (mStore == null || code == null || code.length() == 0) return false;
        PickupCardStore.Card c = mStore.addFromQuickNote(kind, code, station, pkg, noteId, System.currentTimeMillis(), ttlMs);
        Log.i(TAG_S1, "[PICKUP] 记一记上岛 " + kind + (c != null ? " 新卡 ttl=" + (ttlMs / 60000L) + "min" : " 已在岛上（不重复）"));
        if (c != null) {
            save();
            runMain(new Runnable() {
                @Override public void run() {
                    refreshMode("quicknote-code");
                    pushPickups("quicknote");
                }
            });
        }
        return c != null;
    }

    @Override public void removeCode(String owner, String kind, String code) {
        if (mStore == null) return;
        if (mStore.dismissByKey(kind, code)) {
            save();
            runMain(new Runnable() {
                @Override public void run() {
                    pushPickups("quicknote-undo");
                }
            });
        }
    }

    // ------------------------------------------------------------------ ★ 消息提醒

    @Override public void setNoticeListener(String owner, com.onesay.assistant.v2.kernel.IslandHost.NoticeListener l) {
        mNoticeListener = l;
    }

    // ------------------------------------------------------------------ ★ feat/suggest 主动建议卡

    @Override public void setSuggestListener(String owner, com.onesay.assistant.v2.kernel.IslandHost.SuggestListener l) {
        mSuggestListener = l;
    }

    @Override public boolean suggestShow(String owner, final String id, String title, String subtitle, final String thumbPath,
                                         List<String> actions, long ttlMs) {
        boolean panel = false;
        try {
            panel = mSvc.panelOnScreen();
        } catch (Throwable ignored) {
        }
        if (panel) {   // 面板（回答 / 聆听卡）在屏幕上：不放（引擎记 SUPPRESSED）
            Log.i(TAG, "[ISLAND] suggestShow 拒绝：面板在屏幕上");
            return false;
        }
        final List<IslandModel.Effect> e = mModel.suggestShow(id, title, subtitle, thumbPath, actions, ttlMs, System.currentTimeMillis());
        if (e == null) {
            Log.i(TAG, "[ISLAND] suggestShow 拒绝：岛忙 / 非岛模式 / 全屏 mode=" + mModel.mode());
            return false;
        }
        own(owner, id);
        if (thumbPath != null) IslandView.preloadThumb(thumbPath);   // 调用方在后台线程：先解码 96px 缩略图，不上传
        runMain(new Runnable() {
            @Override public void run() {
                apply(e, "suggestShow(" + id + ")");
            }
        });
        return true;
    }

    @Override public void suggestUpdate(String owner, final String id, final String subtitle) {
        runMain(new Runnable() {
            @Override public void run() {
                apply(mModel.suggestUpdate(id, subtitle), "suggestUpdate(" + id + ")");
            }
        });
    }

    @Override public void suggestWithdraw(String owner, final String id) {
        mOwners.remove(id);
        runMain(new Runnable() {
            @Override public void run() {
                apply(mModel.suggestWithdraw(id), "suggestWithdraw(" + id + ")");
                if (!mModel.suggestShowing(id)) IslandView.releaseThumbs();
            }
        });
    }

    @Override public void setNotices(String owner, final List<IslandModel.NoticeRow> rows, final boolean alert, final boolean high) {
        own(owner, IslandModel.K_NOTICE);
        runMain(new Runnable() {
            @Override public void run() {
                refreshMode("notices");
                // 面板（全屏回答 / 聆听卡）在屏幕上 → 提醒只排队，不自动展开
                boolean panel = false;
                try {
                    panel = mSvc.panelOnScreen();
                } catch (Throwable ignored) {
                }
                // BUG-I2：取样结果也走让位 / 恢复（万一错过了挂摘回调，这里补上）
                apply(mModel.panelShown(panel), "panel(" + panel + ",sample)");
                apply(mModel.setNotices(rows, alert, high), "notices(" + (rows == null ? 0 : rows.size()) + (alert ? ",alert" : "")
                        + (high ? ",high" : "") + (panel ? ",panel" : "") + ")");
            }
        });
    }

    /** ★ BUG-I2：面板挂上 / 摘下（S1Hooks.onPanelVisibility）。提醒展开中 → 立即收回队列；面板关了 → 岛空闲时恢复展开。 */
    void panelVisibility(final boolean on) {
        runMain(new Runnable() {
            @Override public void run() {
                List<IslandModel.Effect> e = mModel.panelShown(on);
                // BUG-I2b：没有效果（例如没让过位 / 等退出全屏）也记一行，上机能直接 grep 到 panel(false)
                if (e == null || e.isEmpty()) {
                    Log.i(TAG, "[ISLAND] panel(" + on + ") → (无变化) mode=" + mModel.mode() + " fs=" + mModel.isFullscreen()
                            + " yield=" + mModel.noticeYieldPending() + " waitFs=" + mModel.noticeYieldWaitingFullscreen());
                }
                apply(e, "panel(" + on + ")");
            }
        });
    }

    /** dump 用：每个插件当前在岛上登记过几个活动。 */
    String owners() {
        java.util.Map<String, Integer> n = new java.util.TreeMap<String, Integer>();
        synchronized (mOwners) {
            for (String o : mOwners.values()) n.put(o, (n.containsKey(o) ? n.get(o) : 0) + 1);
        }
        return n.toString();
    }

    void setTodos(final List<IslandModel.TodoRow> rows, final int today, final boolean chip) {
        runMain(new Runnable() {
            @Override public void run() {
                List<IslandModel.Effect> e = new ArrayList<IslandModel.Effect>(mModel.setTodoChipEnabled(chip));
                e.addAll(mModel.setTodos(rows, today));
                if (!e.isEmpty()) apply(e, "todos(" + rows.size() + ",today=" + today + ")");
            }
        });
    }

    // ================================================================== 浮层形态

    void refreshMode(String why) {
        boolean overlay = true;
        try {
            if (Build.VERSION.SDK_INT >= 23) overlay = Settings.canDrawOverlays(mSvc);
        } catch (Throwable ignored) {
        }
        boolean pref = IslandSettings.enabled(mSvc);   // 已含 island.off 与调试覆盖
        IslandModel.Mode m = IslandModel.chooseMode(pref, false, overlay);
        IslandModel.Mode old = mModel.mode();
        if (m == old) return;
        Log.i(TAG, "[ISLAND] 浮层形态 " + old + " → " + m + "（" + why + "；" + IslandSettings.dump(mSvc) + " overlay=" + overlay + "）");
        apply(mModel.setMode(m), "mode→" + m);
        pushPickups("mode");
    }

    // ================================================================== 效果执行

    void apply(List<IslandModel.Effect> effects, String why) {
        if (effects == null || effects.isEmpty()) {
            scheduleTimers();
            return;
        }
        Log.i(TAG, "[ISLAND] " + why + " → " + effects + " mode=" + mModel.mode() + " sheet=" + mModel.sheet()
                + " view=" + mModel.view() + (mDemo ? " (demo)" : ""));
        for (IslandModel.Effect e : effects) {
            try {
                switch (e) {
                    case DETACH_SHEET:
                        if (!mDemo) mSvc.s1DetachForCapsule();
                        break;
                    case ATTACH_SHEET:
                        if (!mDemo) mSvc.s1AttachSheet();
                        break;
                    case CANCEL_TASK:
                        Log.i(TAG, "[ISLAND] 停止后台任务（ErrorCode.CANCELLED）");
                        S1Hooks.askBroker().expireAll();   // ★ arch/island-progress：停了 → 没答的问题按 no_answer 放行
                        if (!mDemo) mSvc.s1CancelActiveTask();
                        mDemo = false;
                        break;
                    case END_SESSION:
                        if (!mDemo) mSvc.s1CancelActiveTask();
                        mDemo = false;
                        break;
                    case RETRY_TASK:
                        Log.i(TAG, "[ISLAND] 重试：用原指令重跑" + (mDemo ? "（demo：只记日志）" : ""));
                        if (!mDemo && mLastCommand != null) mSvc.s1RetryCommand(mLastCommand);
                        mDemo = false;
                        break;
                    case RESOLVE_CONFIRM_ALLOW:
                    case RESOLVE_CONFIRM_DENY: {
                        boolean allow = e == IslandModel.Effect.RESOLVE_CONFIRM_ALLOW;
                        Log.i(TAG, "[ISLAND] 岛上确认卡：" + (allow ? "允许（单次）" : "拒绝"));
                        boolean ok = !mDemo && mSvc.s1ResolveLoopConfirm(allow);
                        if (!ok) apply(mModel.confirmResolved(), mDemo ? "demoConfirm" : "confirmGone");
                        break;
                    }
                    case BG_CONFIRM_ALLOW:
                    case BG_CONFIRM_DENY: {
                        boolean allow = e == IslandModel.Effect.BG_CONFIRM_ALLOW;
                        String id = mModel.lastTarget();
                        Log.i(TAG, "[ISLAND] 定时任务确认卡（" + id + "）：" + (allow ? "允许（单次）" : "拒绝"));
                        // ★ lane C3：按活动的 owner 找回调（跑腿的「接手 / 去结算页」确认卡），找不到仍是默认回调（S3），S3 行为不变
                        com.onesay.assistant.v2.kernel.IslandHost.Listener cl = listenerFor(id);
                        if (cl != null && !isDemoId(id)) cl.onBgConfirm(id, allow);
                        else apply(mModel.bgConfirmResolved(id), "demoBgConfirm");
                        break;
                    }
                    case EXEC_ACTION: {
                        String id = mModel.lastTarget();
                        String act = mModel.lastExecAction();
                        Log.i(TAG, "[ISLAND] 执行模式按钮 " + act + "（" + id + "）");
                        com.onesay.assistant.v2.exec.ExecBridge.onIslandAction(id, act);
                        break;
                    }
                    case BG_STOP: {
                        String id = mModel.lastTarget();
                        Log.i(TAG, "[ISLAND] 停止定时任务 " + id);
                        com.onesay.assistant.v2.kernel.IslandHost.Listener bl = listenerFor(id);
                        if (bl != null && !isDemoId(id)) bl.onBgStop(id);
                        else apply(mModel.bgEnded(id, false, "已停止", System.currentTimeMillis()), "demoBgStop");
                        break;
                    }
                    case REMIND_DONE:
                        Log.i(TAG, "[ISLAND] 提醒「完成」" + mModel.lastTarget());
                        if (mBgListener != null && !isDemoId(mModel.lastTarget())) mBgListener.onRemindDone(mModel.lastTarget());
                        break;
                    case REMIND_SNOOZE:
                        Log.i(TAG, "[ISLAND] 提醒「稍后10分钟」" + mModel.lastTarget());
                        if (mBgListener != null && !isDemoId(mModel.lastTarget())) mBgListener.onRemindSnooze(mModel.lastTarget());
                        break;
                    case TODO_CHECK:
                        Log.i(TAG, "[ISLAND] 勾销待办 " + mModel.lastTarget());
                        if (mBgListener != null) mBgListener.onTodoCheck(mModel.lastTarget());
                        break;
                    case OPEN_APP_TASK:
                    case OPEN_APP_PICKUP:
                    case OPEN_APP_TODO:
                        openApp(e);
                        break;
                    case COPY_PICKUP:
                        copy(mModel.currentPickupId());
                        break;
                    case NOTE_ACTION: {
                        String t = mModel.lastTarget();
                        if (t != null && t.startsWith(IslandModel.TASK_RESULT_PREFIX)) {   // ★ R13：岛上短结果卡「查看」→ 流回卡片
                            Log.i(TAG, "[ISLAND] 短结果卡「查看」→ 卡片");
                            if (!mDemo) mSvc.s1ShowTaskResult(t);   // ★ LC-5：卡片被新请求接管过 → 先重画任务结论
                            break;
                        }
                        int k = t == null ? -1 : t.lastIndexOf('#');
                        if (k > 0 && mNoteListener != null && !mDemo) {
                            try {
                                mNoteListener.onNoteAction(t.substring(0, k), Integer.parseInt(t.substring(k + 1)));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        break;
                    }
                    case OPEN_APP_NOTE:
                        if (mModel.lastTarget() != null && mModel.lastTarget().startsWith(IslandModel.TASK_RESULT_PREFIX)) {
                            if (!mDemo) mSvc.s1ShowTaskResult(mModel.lastTarget());   // ★ R13：点短结果卡正文 = 看全文（流回卡片）；LC-5 同上
                            break;
                        }
                        if (mNoteListener != null && !mDemo) mNoteListener.onNoteOpen(mModel.lastTarget());
                        break;
                    case NOTICE_DISMISS:
                        Log.i(TAG, "[ISLAND] 忽略提醒 " + mModel.lastTarget());
                        if (mNoticeListener != null && !mDemo) mNoticeListener.onNoticeDismiss(mModel.lastTarget());
                        break;
                    case NOTICE_DISMISS_ALL:
                        Log.i(TAG, "[ISLAND] 全部忽略提醒");
                        if (mNoticeListener != null && !mDemo) mNoticeListener.onNoticeDismissAll();
                        break;
                    case NOTICE_ACTION: {
                        String t = mModel.lastTarget();
                        int k = t == null ? -1 : t.lastIndexOf('#');
                        Log.i(TAG, "[ISLAND] 提醒动作 " + t);
                        if (k > 0 && mNoticeListener != null && !mDemo) {
                            try {
                                mNoticeListener.onNoticeAction(t.substring(0, k), Integer.parseInt(t.substring(k + 1)));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        break;
                    }
                    case DISMISS_PICKUP: {
                        String id = mModel.currentPickupId();
                        PickupCardStore.Card pc = id == null ? null : mStore.find(id);
                        if (id != null && mStore.dismiss(id)) {
                            Log.i(TAG_S1, "[PICKUP] 用户关闭了一张卡" + (pc != null && pc.fromQuickNote() ? "（记一记 → 已取）" : ""));
                            if (pc != null) com.onesay.assistant.v2.planner.PlannerHooks.onPickupTaken(mSvc, pc.fromQuickNote() ? "bixbyui:qn:" + pc.noteId : "bixbyui:card:" + pc.id);   // ★ planner
                            save();
                            if (pc != null && pc.fromQuickNote() && mNoteListener != null) mNoteListener.onCodeGone(pc.noteId, false);
                        }
                        pushPickups("dismiss");
                        break;
                    }
                    case ASK_ANSWER: {   // ★ arch/island-progress：点了芯片 → 回填给等待方（B 路 ask_user）
                        String id = mModel.lastTarget();
                        String choice = mModel.lastAskChoice();
                        boolean ok = S1Hooks.askBroker().answer(id, choice);
                        Log.i(TAG, "[ISLAND] ask_user（" + id + "）选了「" + choice + "」" + (ok ? "" : "（已没人在等）"));
                        break;
                    }
                    case SUGGEST_ACTION: {   // ★ feat/suggest
                        String t = mModel.lastTarget();
                        int k = t == null ? -1 : t.lastIndexOf('#');
                        IslandView.releaseThumbs();
                        if (k > 0 && mSuggestListener != null && !mDemo) {
                            try {
                                mSuggestListener.onSuggestAction(t.substring(0, k), Integer.parseInt(t.substring(k + 1)));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        break;
                    }
                    case SUGGEST_TIMEOUT:
                        IslandView.releaseThumbs();
                        if (mSuggestListener != null && !mDemo) mSuggestListener.onSuggestTimeout(mModel.lastTarget());
                        break;
                    case SUGGEST_SWIPED:
                        IslandView.releaseThumbs();
                        if (mSuggestListener != null && !mDemo) mSuggestListener.onSuggestSwipedAway(mModel.lastTarget());
                        break;
                    case ASK_TIMEOUT: {
                        String id = mModel.lastTarget();
                        S1Hooks.askBroker().expire(id);
                        Log.i(TAG, "[ISLAND] ask_user（" + id + "）超时收起 → no_answer");
                        break;
                    }
                    case RENDER:
                        render();
                        break;
                    default:
                        break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "[ISLAND] 执行效果 " + e + " 异常: " + t.getMessage());
            }
        }
        scheduleTimers();
    }

    private static boolean isDemoId(String id) {
        return id != null && id.startsWith("demo");
    }

    /** 把模型排下的延时请求挂到主线程（序号过期由模型判无效）。 */
    private void scheduleTimers() {
        for (final IslandModel.Timer t : mModel.drainTimers()) {
            mMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    List<IslandModel.Effect> r = mModel.timerDue(t.name, t.seq, t.target);
                    if ("doneHide".equals(t.name) && !r.isEmpty()) mDemo = false;
                    apply(r, t.name + "#" + t.seq + (t.target != null ? "(" + t.target + ")" : ""));
                }
            }, Math.max(0L, t.delayMs));
        }
    }

    private void openApp(IslandModel.Effect e) {
        String page = e == IslandModel.Effect.OPEN_APP_TODO ? "todo" : e == IslandModel.Effect.OPEN_APP_PICKUP ? "pickup" : "task";
        Log.i(TAG, "[ISLAND] 点按岛 → 打开 App（page=" + page + "）");
        if (mDemo) return;
        // ★ R10：记一记放上岛的码 → 打开来源 App（取餐页 / 驿站 App）
        if (e == IslandModel.Effect.OPEN_APP_PICKUP && mStore != null) {
            PickupCardStore.Card pc = mStore.find(mModel.currentPickupId() == null ? "" : mModel.currentPickupId());
            if (pc != null && pc.pkg != null && pc.pkg.length() > 0) {
                try {
                    Intent li = mSvc.getPackageManager().getLaunchIntentForPackage(pc.pkg);
                    if (li != null) {
                        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mSvc.startActivity(li);
                        Log.i(TAG, "[ISLAND] 码胶囊 → 打开来源 App " + pc.pkg);
                        return;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "[ISLAND] 打开来源 App 失败: " + t.getMessage());
                }
            }
        }
        try {
            Intent it = new Intent(mSvc, com.onesay.assistant.v2.ui.MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            it.putExtra("page", page);
            mSvc.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "[ISLAND] 打开 App 失败: " + t.getMessage());
        }
    }

    // ================================================================== 渲染

    void render() {
        IslandModel.Snapshot s = mModel.view();
        if (mModel.mode() == IslandModel.Mode.ISLAND) {
            renderIsland(s);
        } else if (mIsland != null) {
            // 不画浮层（island.off / 开关关 / 没有悬浮窗权限）：摘掉岛，任务留在面板
            mIsland.setSnapshot(null);
            removeIsland();
        }
        updateProbe();
    }

    /** 有任何活动 → 挂探针；没有 → 摘掉。 */
    private boolean hasActivity() {
        return mModel.isCollapsed() || mModel.bgCount() > 0 || mModel.currentPickupId() != null || mModel.noticeCount() > 0;
    }

    private void renderIsland(IslandModel.Snapshot s) {
        boolean anchorFs = mAnchor != null && mAnchor.fullscreen;
        if (mAnchor != null && anchorFs != mModel.isFullscreen()) mAnchor = null;
        if (mIsland == null) {
            if (s.kind == IslandModel.Kind.HIDDEN) return;
            mIsland = new IslandView(mSvc);
            mIsland.setListener(new IslandView.Listener() {
                @Override public void onTap(String key) {
                    apply(key == null ? mModel.tapMain() : mModel.tap(key), "tap(" + key + ")");
                }

                @Override public void onLongPress(String key) {
                    apply(key == null ? mModel.longPress() : mModel.longPress(key), "longPress(" + key + ")");
                }

                @Override public void onSwipeSide() {
                    if (IslandSettings.tuckEnabled(mSvc)) apply(mModel.tuck(), "swipeSide→tuck");
                }

                @Override public void onSwipeDismiss(String key) {
                    apply(mModel.noticeDismiss(null), "swipeLeft→noticeDismiss");
                }

                @Override public void onSwipeUp() {
                    apply(mModel.collapseExpanded(IslandModel.CollapseReason.SWIPE_UP), "swipeUp");
                }

                @Override public void onAction(String a) {
                    onIslandAction(a);
                }

                @Override public void onOutside() {
                    apply(mModel.collapseExpanded(IslandModel.CollapseReason.OUTSIDE_TAP), "outsideIsland");
                }
            });
            mIsland.setHost(new IslandView.Host() {
                @Override public void requestWindow(Rect r) {
                    placeIsland(r);
                }

                @Override public void onHiddenSettled() {
                    removeIsland();
                }
            });
        }
        if (mIsland.anchor() == null || mAnchor == null) {
            Point p = screenSize();
            mIsland.setAnchor(anchor(), p.x, p.y);
        }
        if (mArmMorphAt > 0L) {   // ★ R13：卡片正在形变飞来 → 岛先停在「种子」，等舞台接触时 appearFrom 接力
            long left = MORPH_ARM_MAX_MS - (android.os.SystemClock.uptimeMillis() - mArmMorphAt);
            mArmMorphAt = 0L;
            if (left > 50L) mIsland.armMorph(left);
        }
        mIsland.setSnapshot(s);
    }

    private void onIslandAction(String a) {
        if (IslandView.A_EXEC_SUPPLEMENT.equals(a) || IslandView.A_EXEC_TAKEOVER.equals(a) || IslandView.A_EXEC_RESUME.equals(a)) {
            apply(mModel.execAction(a), "execAction(" + a + ")");   // ★ r2/fast-screen
            return;
        }
        if (a != null && a.startsWith(IslandView.A_ASK_PREFIX)) {   // ★ arch/island-progress：ask_user 芯片
            try {
                apply(mModel.askAnswer(Integer.parseInt(a.substring(IslandView.A_ASK_PREFIX.length()))), "askAnswer(" + a + ")");
            } catch (NumberFormatException ignored) {
            }
            return;
        }
        if (IslandView.A_STOP.equals(a)) apply(mModel.stop(), "stop");
        else if (IslandView.A_ALLOW.equals(a)) apply(mModel.islandConfirm(true), "allow");
        else if (IslandView.A_DENY.equals(a)) apply(mModel.islandConfirm(false), "deny");
        else if (IslandView.A_CLOSE.equals(a)) apply(mModel.closeFailed(), "closeFailed");
        else if (IslandView.A_RETRY.equals(a)) apply(mModel.retry(), "retry");
        else if (IslandView.A_COPY.equals(a)) apply(mModel.copyPickup(), "copy");
        else if (IslandView.A_PREV.equals(a)) apply(mModel.pagePickup(-1), "prev");
        else if (IslandView.A_NEXT.equals(a)) apply(mModel.pagePickup(1), "next");
        else if (IslandView.A_DISMISS.equals(a)) apply(mModel.dismissPickup(), "dismiss");
        else if (IslandView.A_REMIND_DONE.equals(a)) apply(mModel.remindDone(null), "remindDone");
        else if (IslandView.A_NOTE_OPEN.equals(a)) apply(mModel.noteOpen(), "noteOpen");
        else if (a != null && a.startsWith(IslandView.A_NOTE_PREFIX)) {
            try {
                apply(mModel.noteAction(Integer.parseInt(a.substring(IslandView.A_NOTE_PREFIX.length()))), "noteAction(" + a + ")");
            } catch (NumberFormatException ignored) {
            }
        }
        else if (IslandView.A_REMIND_SNOOZE.equals(a)) apply(mModel.remindSnooze(null), "remindSnooze");
        else if (IslandView.A_NOTICE_ALL.equals(a)) apply(mModel.noticeDismissAll(), "noticeDismissAll");
        else if (a != null && a.startsWith(IslandView.A_NOTICE_X)) apply(mModel.noticeDismiss(a.substring(IslandView.A_NOTICE_X.length())), "noticeX");
        else if (a != null && a.startsWith(IslandView.A_NOTICE_FOCUS)) apply(mModel.noticeFocus(a.substring(IslandView.A_NOTICE_FOCUS.length())), "noticeFocus");
        else if (a != null && a.startsWith(IslandView.A_NOTICE_ACT)) {
            try {
                apply(mModel.noticeAction(Integer.parseInt(a.substring(IslandView.A_NOTICE_ACT.length()))), "noticeAction(" + a + ")");
            } catch (NumberFormatException ignored) {
            }
        }
        else if (IslandView.A_TODO_ALL.equals(a)) {
            apply(mModel.collapseExpanded(IslandModel.CollapseReason.USER_BUTTON), "todoAll");
            openApp(IslandModel.Effect.OPEN_APP_TODO);
        } else if (a != null && a.startsWith(IslandView.A_TODO_PREFIX)) {
            apply(mModel.todoCheck(a.substring(IslandView.A_TODO_PREFIX.length())), "todoCheck");
        }
    }

    private void placeIsland(Rect r) {
        if (mIsland == null) return;
        boolean attached = mIsland.getParent() != null;
        if (!attached && mIsland.targetHidden()) {
            mIsland.windowDropped();
            return;
        }
        if (mIslandParams == null) {
            // v3：TYPE_APPLICATION_OVERLAY（App 已有的悬浮窗权限）；不用 NO_LIMITS，不用挖孔模式
            mIslandParams = new WindowManager.LayoutParams(r.width(), r.height(), S1Overlay.windowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            mIslandParams.gravity = Gravity.TOP | Gravity.START;
            mIslandParams.setTitle("OneSay-Island");
            Log.i(TAG, "[ISLAND] 窗口模式=" + (mIsland.fixedWindowMode() ? "固定全宽 + 触摸区域只认岛（R11）" : "包住岛（兜底）"));
        }
        mIslandParams.x = r.left;
        mIslandParams.y = r.top;
        mIslandParams.width = Math.max(1, r.width());
        mIslandParams.height = Math.max(1, r.height());
        try {
            if (attached) mWm.updateViewLayout(mIsland, mIslandParams);
            else {
                mWm.addView(mIsland, mIslandParams);
                Log.i(TAG, "[ISLAND] 岛窗口已挂出（TYPE_APPLICATION_OVERLAY）锚点 " + mAnchor);
            }
            mIsland.setWindowOrigin(r.left, r.top);
            if (!r.equals(mLastWin)) {
                mLastWin.set(r);
                Log.i(TAG, "[ISLAND-GEOM] win x=" + r.left + " y=" + r.top + " w=" + r.width() + " h=" + r.height()
                        + " anchor{" + mAnchor + "} view=" + (mIsland.snapshot() == null ? "null" : mIsland.snapshot().kind));
            }
        } catch (Throwable t) {
            Log.w(TAG, "[ISLAND] 岛窗口挂载失败 → 不画浮层，任务留在面板: " + t.getMessage());
            apply(mModel.setMode(IslandModel.Mode.NONE), "mode→NONE(island-failed)");
        }
    }

    // ================================================================== ★ R13 卡 ↔ 岛 形变接力

    /** 舞台最长等多久接触（超时岛自己从种子长出来，绝不卡住）。 */
    static final long MORPH_ARM_MAX_MS = 900L;
    private long mArmMorphAt;

    /** 卡片要形变飞进岛（DETACH_SHEET 之前由面板服务调用；随后的 RENDER 会让岛停在种子等接力）。主线程。 */
    public void armMorph() {
        if (mModel.mode() != IslandModel.Mode.ISLAND) return;
        if (mIsland != null && mIsland.getParent() != null && !mIsland.targetHidden()) {
            mIsland.armMorph(MORPH_ARM_MAX_MS);
            return;
        }
        mArmMorphAt = android.os.SystemClock.uptimeMillis();
    }

    /** 舞台接触：岛以合并形状的尺寸与速度起步，APPEAR（ω22 ζ0.86 门槛 0.8）收尾 + 吸收鼓包。主线程。 */
    public boolean appearFrom(android.graphics.RectF screenRect, float vw, float vh) {
        mArmMorphAt = 0L;
        if (mIsland == null) return false;
        return mIsland.appearFrom(screenRect, vw, vh);
    }

    /** 放弃接力（新唤醒 / 取消）：岛照常从种子长出来。 */
    public void releaseMorph() {
        mArmMorphAt = 0L;
        if (mIsland != null) mIsland.releaseMorph();
    }

    /** 岛在屏幕上的几何 {cx, top, seed, windowBottom, compactW}（px）；没有岛 / 非岛形态 → null。 */
    public float[] morphTarget() {
        if (mModel.mode() != IslandModel.Mode.ISLAND) return null;
        IslandGeometry.Anchor a = anchor();
        Point p = screenSize();
        float dens = mSvc.getResources().getDisplayMetrics().density;
        int[] f = IslandGeometry.fixedWindow(p.x, p.y, a.top, dens);
        float compact = IslandGeometry.compactWidth(60f * dens, dens);
        return new float[] {a.cx, a.top, IslandGeometry.COMPACT_H_DP * dens, f[3], compact};
    }

    /** 岛当前形状（屏幕坐标）+ 圆角，用于「流回」起点；岛不可见 → null。 */
    public float[] islandShape() {
        if (mIsland == null || mIsland.getParent() == null || mIsland.targetHidden()) return null;
        return mIsland.shapeForHandoff();
    }

    public void holdIslandForHandoff(long ms) {
        if (mIsland != null && mIsland.getParent() != null) mIsland.holdForHandoff(ms);
    }

    /** 回程：舞台已接手岛的形状 → 岛这一帧起不再画形状（避免两个黑块）。 */
    public void handOffIsland() {
        if (mIsland != null) mIsland.vanishForHandoff();
    }

    private void removeIsland() {
        if (mIsland != null) mIsland.windowDropped();
        if (mIsland == null || mIsland.getParent() == null) return;
        S1Overlay.remove(mWm, mIsland);
        mLastWin.setEmpty();
    }

    // ------------------------------------------------------------------ 锚点（状态栏下 2dp / 全屏 8dp）

    private IslandGeometry.Anchor anchor() {
        if (mAnchor != null) return mAnchor;
        Point scr = screenSize();
        mAnchor = IslandGeometry.anchor(scr.x, mSvc.getResources().getDisplayMetrics().density, statusBarHeight(), mModel.isFullscreen());
        Log.i(TAG, "[ISLAND-GEOM] screen=" + scr.x + "x" + scr.y + " density=" + mSvc.getResources().getDisplayMetrics().density
                + " statusBar=" + statusBarHeight() + " sdk=" + Build.VERSION.SDK_INT + " → 锚点 " + mAnchor);
        return mAnchor;
    }

    private Point screenSize() {
        return S1Overlay.realSize(mSvc);
    }

    private int statusBarHeight() {
        try {
            int id = mSvc.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return mSvc.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    // ------------------------------------------------------------------ 全屏探针

    private void updateProbe() {
        boolean want = mModel.mode() == IslandModel.Mode.ISLAND && hasActivity() && IslandSettings.fullscreenHide(mSvc);
        if (want && mProbe == null) addProbe();
        else if (!want && mProbe != null) {
            removeProbe();
            if (mModel.isFullscreen() && mFsOverride == null) {
                mFsByVis = mFsByInsets = mFsByRoot = false;
                apply(mModel.setFullscreen(false), "fullscreen(probe-removed)");
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void addProbe() {
        try {
            View v = new View(mSvc);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(1, 1, S1Overlay.windowType(),
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;
            p.alpha = 0f;
            p.setTitle("OneSay-IslandProbe");
            v.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
                @Override
                public void onSystemUiVisibilityChange(int vis) {
                    mProbeSignalAt = System.currentTimeMillis();
                    mFsByVis = (vis & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;
                    onFullscreenSignal("systemUiVisibility=0x" + Integer.toHexString(vis));
                }
            });
            v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                    mProbeSignalAt = System.currentTimeMillis();
                    mFsByInsets = insets.getSystemWindowInsetTop() == 0;
                    if (!mFsByInsets) mFsByRoot = false;   // 状态栏看得见 → root 上一次的「全屏」作废
                    else confirmFullscreenByRoot();        // ★ R11：insets=0 只是「疑似」，root 读 mTopIsFullscreen 确认
                    onFullscreenSignal("insetTop=" + insets.getSystemWindowInsetTop());
                    return insets;
                }
            });
            mWm.addView(v, p);
            mProbe = v;
            mProbeSignalAt = 0L;
            Log.i(TAG, "[ISLAND-FS] 全屏探针已挂出");
            mMain.removeCallbacks(mRootPoll);
            mMain.postDelayed(mRootPoll, FS_ROOT_QUIET_MS);
        } catch (Throwable t) {
            Log.w(TAG, "[ISLAND-FS] 探针挂载失败: " + t.getMessage());
        }
    }

    private void removeProbe() {
        mMain.removeCallbacks(mRootPoll);
        mMain.removeCallbacks(mFsApply);
        mRootPolling = false;
        if (mProbe != null) {
            S1Overlay.remove(mWm, mProbe);
            mProbe = null;
            Log.i(TAG, "[ISLAND-FS] 全屏探针已摘掉");
        }
    }

    /**
     * ★ R11 全屏判定（真机 QQ 邮箱 / 桌面 / 自家控制台：探针 insetTop=0 而状态栏明明在 → 岛被当成全屏藏起来）：
     * 全屏 = 调试覆盖 ?: (systemUiVisibility 带 FULLSCREEN) || root 读到 mTopIsFullscreen=true || (root 不可用 && insets=0)。
     * 进入全屏要稳定 {@link #FS_ENTER_DEBOUNCE_MS}；退出立即生效。
     */
    static boolean fullscreenVerdict(Boolean override, boolean byVis, boolean byRoot, boolean byInsets, boolean rootUnavailable) {
        return FullscreenPolicy.verdict(override, byVis, byRoot, byInsets, rootUnavailable);
    }

    private void onFullscreenSignal(String why) {
        boolean fs = fullscreenVerdict(mFsOverride, mFsByVis, mFsByRoot, mFsByInsets, mRootUnavailable);
        if (fs == mModel.isFullscreen()) {
            mMain.removeCallbacks(mFsApply);
            return;
        }
        if (fs && mFsOverride == null) {
            mMain.removeCallbacks(mFsApply);
            mMain.postDelayed(mFsApply, FS_ENTER_DEBOUNCE_MS);
            return;
        }
        mMain.removeCallbacks(mFsApply);
        applyFullscreen(why);
    }

    private void applyFullscreen(String why) {
        boolean fs = fullscreenVerdict(mFsOverride, mFsByVis, mFsByRoot, mFsByInsets, mRootUnavailable);
        if (fs == mModel.isFullscreen()) return;
        Log.i(TAG, "[ISLAND-FS] " + (fs ? "进入" : "退出") + "全屏（" + why + " vis=" + mFsByVis + " root=" + mFsByRoot
                + " insets=" + mFsByInsets + " rootUnavail=" + mRootUnavailable + "）");
        mAnchor = null;
        apply(mModel.setFullscreen(fs), "fullscreen(" + fs + ")");
    }

    /** ★ R11：insets 说「疑似全屏」时马上用 root 读一次 dumpsys window policy 确认（不排队，≤1.5 s）。 */
    private void confirmFullscreenByRoot() {
        if (mFsConfirming || mRootUnavailable) return;
        mFsConfirming = true;
        com.onesay.assistant.v2.util.Threads.start("island-fs-confirm", new Runnable() {
            @Override
            public void run() {
                final FullscreenPolicy.Read fs = FullscreenPolicy.read();
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mFsConfirming = false;
                        if (fs == FullscreenPolicy.Read.UNAVAILABLE) {
                            mRootUnavailable = true;
                            Log.i(TAG, "[ISLAND-FS] root 确认不可用 → 只信 insets（去抖 " + FS_ENTER_DEBOUNCE_MS + "ms）");
                        } else if (fs != FullscreenPolicy.Read.BUSY) {
                            mFsByRoot = fs == FullscreenPolicy.Read.FULLSCREEN;
                            Log.i(TAG, "[ISLAND-FS] root 确认 mTopIsFullscreen=" + mFsByRoot + "（insets=" + mFsByInsets + "）");
                        }
                        onFullscreenSignal("root-confirm");
                    }
                });
            }
        });
    }

    /** 第 3 路：前两路 30 s 都没信号且有 root 时，每 2 s 读一次 dumpsys window policy。 */
    private void pollRootFullscreen() {
        if (mProbe == null) return;
        if (mProbeSignalAt > 0 && System.currentTimeMillis() - mProbeSignalAt < FS_ROOT_QUIET_MS) {
            mMain.postDelayed(mRootPoll, FS_ROOT_QUIET_MS);
            return;
        }
        if (!mRootPolling) {
            mRootPolling = true;
            Log.i(TAG, "[ISLAND-FS] 前两路 " + FS_ROOT_QUIET_MS + "ms 没有信号 → root 兜底轮询 dumpsys window policy");
        }
        com.onesay.assistant.v2.util.Threads.start("island-fs-root", new Runnable() {
            @Override
            public void run() {
                final FullscreenPolicy.Read fs = FullscreenPolicy.read();
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fs == FullscreenPolicy.Read.BUSY) {
                            // 审计 B-2：root 通道正被别的命令占用 → 跳过本轮，不排队、不关这一路
                            if (mProbe != null) mMain.postDelayed(mRootPoll, FS_ROOT_POLL_MS);
                            return;
                        }
                        if (fs == FullscreenPolicy.Read.UNAVAILABLE) {
                            Log.i(TAG, "[ISLAND-FS] root 兜底读不到字段（无 root 或字段名不同）→ 关闭这一路");
                            mRootPolling = false;
                            mRootUnavailable = true;
                            onFullscreenSignal("root-unavailable");
                            return;
                        }
                        mFsByRoot = fs == FullscreenPolicy.Read.FULLSCREEN;
                        onFullscreenSignal("dumpsys");
                        if (mProbe != null) mMain.postDelayed(mRootPoll, FS_ROOT_POLL_MS);
                    }
                });
            }
        });
    }

    // ================================================================== 取件码

    private SharedPreferences prefs() {
        return mSvc.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private void save() {
        prefs().edit().putString(KEY_CARDS, mStore.toJson()).apply();
    }

    private void registerSms() {
        boolean granted = mSvc.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        if (!granted) Log.w(TAG_S1, "[PICKUP] 未授予 RECEIVE_SMS，收不到短信广播（deploy.sh grant-all 或系统设置里授权）");
        mSmsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_SMS.equals(intent.getAction())) return;
                try {
                    SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
                    if (msgs == null) return;
                    // 长短信会被拆成多段：按发件人拼回整条
                    Map<String, StringBuilder> bySender = new LinkedHashMap<String, StringBuilder>();
                    for (SmsMessage m : msgs) {
                        if (m == null) continue;
                        String from = m.getDisplayOriginatingAddress();
                        if (from == null) from = "";
                        StringBuilder sb = bySender.get(from);
                        if (sb == null) {
                            sb = new StringBuilder();
                            bySender.put(from, sb);
                        }
                        String body = m.getDisplayMessageBody();
                        if (body != null) sb.append(body);
                    }
                    for (StringBuilder sb : bySender.values()) onIncomingText(sb.toString(), "sms");
                } catch (Throwable t) {
                    Log.w(TAG_S1, "[PICKUP] 解析短信异常: " + t.getMessage());
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_SMS);
        f.setPriority(999);
        try {
            mSvc.registerReceiver(mSmsReceiver, f);
            Log.i(TAG_S1, "[PICKUP] 短信接收器已注册 granted=" + granted);
        } catch (Throwable t) {
            Log.w(TAG_S1, "[PICKUP] 注册短信接收器失败: " + t.getMessage());
            mSmsReceiver = null;
        }
    }

    /** 一段收到的文字（短信正文 / 调试注入）。主线程。返回新出的卡片数。 */
    int onIncomingText(String body, String source) {
        if (!S1Settings.pickupEnabled(mSvc)) return 0;
        long now = System.currentTimeMillis();
        List<PickupCodeDetector.Pickup> found = PickupCodeDetector.detect(body, now, TimeZone.getDefault());
        if (found.isEmpty()) {
            if (PickupCodeDetector.looksLikeOtp(body)) Log.i(TAG_S1, "[PICKUP] " + source + " 验证码类短信，按规则不出卡");
            return 0;
        }
        int added = 0;
        for (PickupCodeDetector.Pickup p : found) {
            PickupCardStore.Card c = mStore.add(p, now, S1Settings.PICKUP_TTL_MS);
            if (c != null) {
                added++;
                Log.i(TAG_S1, "[PICKUP] 新卡（" + source + "）: " + c.display());
                com.onesay.assistant.v2.planner.PlannerHooks.onPickupCard(mSvc, c.id, c.code, c.kind, c.station, c.pkg, c.expiresAt, c.noteId);   // ★ planner：码也进计划 App
                com.onesay.assistant.v2.suggest.SuggestHost.onPickupShown(p.kind, p.code);   // ★ feat/suggest：只计入主动建议的总频控（插件关 = 空操作）
            } else {
                Log.i(TAG_S1, "[PICKUP] 重复/已关闭的码，不再弹出: " + p.kind);
            }
        }
        if (added > 0) {
            save();
            pushPickups("sms");
        }
        return added;
    }

    /** 仓库 → 模型（活动卡，最新 3 张由模型截取）；并安排下一次过期刷新。 */
    void pushPickups(String why) {
        mMain.removeCallbacks(mExpiryTick);
        if (mStore == null) return;
        long now = System.currentTimeMillis();
        if (mStore.purge(now) > 0) save();
        List<IslandModel.Pickup> items = new ArrayList<IslandModel.Pickup>();
        if (S1Settings.pickupEnabled(mSvc)) {
            for (PickupCardStore.Card c : mStore.active(now)) {
                items.add(new IslandModel.Pickup(c.id, c.kind, c.code, c.station, c.fromQuickNote() ? "记一记" : "短信", c.createdAt, c.expiresAt));
            }
        }
        List<IslandModel.Effect> e = mModel.setPickups(items);
        if (e.isEmpty()) render();   // 时效文字仍要刷新
        else apply(e, "pickups(" + why + ")");
        long next = mStore.nextExpiry(now);
        if (next > now) mMain.postDelayed(mExpiryTick, Math.min(next - now + 500L, 30L * 60L * 1000L));
    }

    /** 复制：与识屏复制同一能力（screen.copy，SideEffect NONE），过一次 ExecutionGate 再本地写剪贴板。 */
    private void copy(String id) {
        if (id == null || mStore == null) return;
        PickupCardStore.Card card = null;
        for (PickupCardStore.Card c : mStore.active(System.currentTimeMillis())) if (c.id.equals(id)) card = c;
        if (card == null) return;
        Capability cap = ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_COPY);
        JSONObject args = new JSONObject();
        try {
            args.put("text", card.code);
            args.put("kind", card.kind);
        } catch (Exception ignored) {
        }
        ExecutionGate.Decision dd = ExecutionGate.evaluate(cap, args, false);
        if (dd.decision != ToolGate.Decision.ALLOW) {
            if (dd.token != null) ExecutionGate.reject(dd.token);
            Log.w(TAG_S1, "[PICKUP] 复制被门禁拦下: " + dd.reason);
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) mSvc.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipboardMemory.markOwnWrite(card.code, System.currentTimeMillis());   // 防剪贴板芯片回环
            cm.setPrimaryClip(ClipData.newPlainText(card.kind, card.code));
            apply(mModel.markCopied(id), "copied");   // 岛上：复制键变「已复制」1.5 s
            Log.i(TAG_S1, "[PICKUP] 已复制 " + card.kind);
        } catch (Throwable t) {
            Log.w(TAG_S1, "[PICKUP] 复制失败: " + t.getMessage());
        }
    }

    /** 确认卡摘要：args 里的字符串值用 · 连起来（跳过令牌类字段），最多 60 字。 */
    static String confirmDetail(JSONObject args) {
        if (args == null) return null;
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = args.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k == null || k.toLowerCase().contains("token") || k.startsWith("_")) continue;
            Object v = args.opt(k);
            if (!(v instanceof String)) continue;
            String s = ((String) v).trim();
            if (s.length() == 0) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s);
        }
        if (sb.length() == 0) return null;
        String r = sb.toString();
        return r.length() > 60 ? r.substring(0, 59) + "…" : r;
    }

    // ================================================================== 调试（IslandTestDriver）

    String dump() {
        return "mode=" + mModel.mode() + " " + IslandSettings.dump(mSvc)
                + " sheet=" + mModel.sheet() + " running=" + mModel.isRunning() + " confirm=" + mModel.isConfirmPending()
                + " view=" + mModel.view() + " fullscreen=" + mModel.isFullscreen() + " tucked=" + mModel.isTucked()
                + " bg=" + mModel.bgCount() + " anchor{" + mAnchor + "}"
                + " islandAttached=" + (mIsland != null && mIsland.getParent() != null)
                + " probe=" + (mProbe != null) + " fsVis=" + mFsByVis + " fsRoot=" + mFsByRoot + " fsInsets=" + mFsByInsets
                + " rootUnavail=" + mRootUnavailable + " fixedWin=" + (mIsland != null && mIsland.fixedWindowMode())
                + " touch=" + (mIsland == null ? "-" : mIsland.touchRect().toShortString())
                + " pickup cards=" + (mStore == null ? -1 : mStore.size()) + " active="
                + (mStore == null ? -1 : mStore.active(System.currentTimeMillis()).size()) + " smsReceiver=" + (mSmsReceiver != null)
                + " owners=" + owners()
                + (mDemo ? " demo" : "");
    }

    /** 几何一行：锚点、窗口 x/y/w/h、density、状态栏 px、形状的屏幕矩形。 */
    String geom() {
        mAnchor = null;
        IslandGeometry.Anchor a = anchor();
        Point p = screenSize();
        if (mIsland != null) mIsland.setAnchor(a, p.x, p.y);
        String win = mIslandParams == null || mIsland == null || mIsland.getParent() == null ? "none"
                : "x=" + mIslandParams.x + " y=" + mIslandParams.y + " w=" + mIslandParams.width + " h=" + mIslandParams.height;
        return "screen=" + p.x + "x" + p.y + " density=" + mSvc.getResources().getDisplayMetrics().density
                + " statusBar=" + statusBarHeight() + " anchor{" + a + "} win{" + win + "}"
                + (mIsland != null ? " shape=" + mIsland.shapeOnScreen().toShortString() : "")
                + " view=" + mModel.view().kind + " fullscreen=" + mModel.isFullscreen();
    }

    /** 调试演示（主线程）。返回一行结果。 */
    String demo(String cmd, String text) {
        long now = System.currentTimeMillis();
        refreshMode("debug");
        if ("run".equals(cmd) || "dual".equals(cmd)) {
            startDemo(now - 42000L, text);
            if ("dual".equals(cmd)) demoPickups();
        } else if ("wait".equals(cmd) || "confirm".equals(cmd)) {
            if (!mModel.isCollapsed() || !mModel.isRunning()) startDemo(now - 58000L, text);
            apply(mModel.step("发送短信给 妈妈"), "demo-step");
            apply(mModel.confirmRequired("发送短信给 妈妈", "我打车去公司了，约 28 分钟到"), "demo-confirmRequired");
        } else if ("allow".equals(cmd) || "deny".equals(cmd)) {
            apply(mModel.islandConfirm("allow".equals(cmd)), "demo-" + cmd);
            if (mModel.isConfirmPending()) apply(mModel.confirmResolved(), "demo-resolved");
        } else if ("step".equals(cmd)) {
            if (!mModel.isCollapsed() || !mModel.isRunning()) startDemo(now - 30000L, null);
            apply(mModel.step(text != null && text.length() > 0 ? text : "确认上车点"), "demo-step");
        } else if ("key".equals(cmd)) {
            if (!mModel.isCollapsed() || !mModel.isRunning()) startDemo(now - 30000L, null);
            apply(mModel.keyUpdate(text != null && text.length() > 0 ? text : "司机已接单 · 粤B·D12345 · 3 分钟到"), "demo-key");
        } else if ("done".equals(cmd)) {
            if (!mModel.isCollapsed()) startDemo(now - 72000L, text);
            apply(mModel.taskEnded(false, false, true, false, null, now), "demo-done");
        } else if ("fail".equals(cmd)) {
            if (!mModel.isCollapsed()) startDemo(now - 95000L, text);
            apply(mModel.taskEnded(false, false, false, false,
                    text != null && text.length() > 0 ? text : "叫车超时：附近暂无可接单车辆", now), "demo-fail");
        } else if ("subgoals".equals(cmd)) {   // ★ arch/island-progress：text = 「done,doing,pending」这样的状态串
            if (!mModel.isCollapsed() || !mModel.isRunning()) startDemo(now - 20000L, null);
            apply(mModel.subgoals(demoSubgoals(text)), "demo-subgoals");
        } else if ("ask".equals(cmd)) {
            if (!mModel.isCollapsed() || !mModel.isRunning()) startDemo(now - 20000L, null);
            S1Hooks.askBroker().register("demo-ask");
            apply(mModel.askUser("demo-ask", "这两家都在营业，订哪一家？", java.util.Arrays.asList("海底捞（1.2km）", "西贝（800m）", "都不要"),
                    IslandModel.ASK_TIMEOUT_MS), "demo-ask");
        } else if ("pickup".equals(cmd)) {
            demoPickups();
        } else if ("expand".equals(cmd) || "longpress".equals(cmd)) {
            apply(mModel.longPress(), "demo-longPress");
        } else if ("tap".equals(cmd)) {
            apply(mModel.tapMain(), "demo-tap");
        } else if ("collapse".equals(cmd) || "up".equals(cmd)) {
            apply(mModel.collapseExpanded(IslandModel.CollapseReason.SWIPE_UP), "demo-collapse");
        } else if ("outside".equals(cmd)) {
            apply(mModel.collapseExpanded(IslandModel.CollapseReason.OUTSIDE_TAP), "demo-outside");
        } else if ("tuck".equals(cmd)) {
            apply(mModel.tuck(), "demo-tuck");
        } else if ("fullscreen".equals(cmd)) {
            if ("on".equals(text)) mFsOverride = Boolean.TRUE;
            else if ("off".equals(text)) mFsOverride = Boolean.FALSE;
            else mFsOverride = null;
            onFullscreenSignal("debug " + text);
        } else if ("copy".equals(cmd)) {
            if (mModel.view().kind != IslandModel.Kind.EXP_PICKUP) apply(mModel.longPress(IslandModel.K_PICKUP), "demo-longPress");
            apply(mModel.copyPickup(), "demo-copy");
        } else if ("next".equals(cmd) || "prev".equals(cmd)) {
            apply(mModel.pagePickup("next".equals(cmd) ? 1 : -1), "demo-" + cmd);
        } else if ("dismiss".equals(cmd)) {
            apply(mModel.dismissPickup(), "demo-dismiss");
        } else if ("stop".equals(cmd)) {
            apply(mModel.stop(), "demo-stop");
        } else if ("retry".equals(cmd)) {
            apply(mModel.retry(), "demo-retry");
        } else if ("close".equals(cmd)) {
            apply(mModel.closeFailed(), "demo-close");
        } else if ("remind".equals(cmd)) {
            apply(mModel.remindFired("demo-r1", text != null && text.length() > 0 ? text : "交周报", now,
                    0, now), "demo-remind");
        } else if ("remindlate".equals(cmd)) {
            apply(mModel.remindFired("demo-r1", "取快递", now - 25L * 60000L, 25, now), "demo-remind");
        } else if ("rsnooze".equals(cmd)) {
            apply(mModel.remindSnooze(null), "demo-remindSnooze");   // 展开的提醒（真提醒会走 S3：新建 10 分钟后的提醒）
        } else if ("rdone".equals(cmd)) {
            apply(mModel.remindDone(null), "demo-remindDone");
        } else if ("bg".equals(cmd)) {
            apply(mModel.bgStarted("demo-bg1", text != null && text.length() > 0 ? text : "读一下QQ邮箱未读", now - 5000L), "demo-bgStarted");
            apply(mModel.bgStep("demo-bg1", "读取邮件"), "demo-bgStep");
        } else if ("bgwait".equals(cmd)) {
            if (!mModel.hasBg("demo-bg1")) apply(mModel.bgStarted("demo-bg1", "读一下QQ邮箱未读", now - 5000L), "demo-bgStarted");
            apply(mModel.bgConfirm("demo-bg1", "发送短信给 妈妈", "邮件摘要已读完"), "demo-bgConfirm");
        } else if ("bgdone".equals(cmd) || "bgfail".equals(cmd)) {
            apply(mModel.bgEnded("demo-bg1", "bgdone".equals(cmd), "bgfail".equals(cmd) ? "确认超时，已安全中止" : null, now), "demo-" + cmd);
        } else if ("reset".equals(cmd)) {
            endDemo();
            apply(mModel.removeBg("demo-r1"), "demo-reset");
            apply(mModel.removeBg("demo-bg1"), "demo-reset");
            mFsOverride = null;
        } else if ("mode".equals(cmd)) {
            if ("island".equals(text)) IslandSettings.sOverride = Boolean.TRUE;
            else if ("off".equals(text) || "capsule".equals(text)) IslandSettings.sOverride = Boolean.FALSE;   // capsule = 旧写法，同 off（不画浮层）
            else IslandSettings.sOverride = null;
            refreshMode("debug-mode " + text);
            render();
        } else {
            return "未知子命令: " + cmd;
        }
        return dump();
    }

    private void startDemo(long startedAt, String title) {
        mDemo = true;
        mLastCommand = title;
        mModel.configure(true, false);
        apply(mModel.taskStarted(startedAt, title != null && title.length() > 0 ? title : "打车去公司，到了告诉妈妈一声"), "demo-taskStarted");
        apply(mModel.step("打开滴滴出行"), "demo-step");
        apply(mModel.step("设置目的地：公司"), "demo-step");
        apply(mModel.step("叫车并等待接单"), "demo-step");
        apply(mModel.requestCollapse(IslandModel.CollapseReason.USER_BUTTON), "demo-collapse");
    }

    private static List<Subgoal> demoSubgoals(String states) {
        String[] titles = {"查明天北京天气", "订明早 7 点闹钟", "把行程发给妈妈", "叫车去机场"};
        String[] st = (states == null || states.trim().length() == 0 ? "done,doing,pending" : states).split(",");
        List<Subgoal> out = new ArrayList<Subgoal>();
        for (int i = 0; i < st.length && i < titles.length; i++) out.add(new Subgoal("demo-g" + i, titles[i], st[i]));
        return out;
    }

    private void endDemo() {
        boolean was = mDemo;
        mDemo = true;
        if (mModel.isCollapsed() || mModel.isRunning()) apply(mModel.stop(), "demo-reset");
        mDemo = false;
        if (was) Log.i(TAG, "[ISLAND] demo 已复位");
    }

    private void demoPickups() {
        onIncomingText("【菜鸟驿站】您的中通包裹已到科技园南区菜鸟驿站，请凭取件码5-2-1234取件，详询0755-86012345。", "debug");
        onIncomingText("【丰巢】您的包裹已存入深大北门丰巢柜，取件码【83920417】，请及时取件。", "debug");
        onIncomingText("【瑞幸咖啡】您的订单已制作完成，取餐码 A123，请到门店取餐台取餐。", "debug");
    }

    void clearAllPickups() {
        mStore = new PickupCardStore();
        save();
        pushPickups("clear");
    }

    /**
     * 截屏前把岛藏起来（只改可见性，不动模型 / 活动），截完恢复。主线程。
     * 岛视图被重建时新视图默认可见（截屏窗口只有几百毫秒，不跟踪）。
     */
    public void setCaptureHidden(boolean hidden) {
        IslandView v = mIsland;
        if (v == null) return;
        try {
            if (hidden) {
                if (!mCaptureHidden) mCaptureSavedVis = v.getVisibility();
                mCaptureHidden = true;
                v.setVisibility(android.view.View.INVISIBLE);
            } else if (mCaptureHidden) {
                mCaptureHidden = false;
                v.setVisibility(mCaptureSavedVis);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean mCaptureHidden;
    private int mCaptureSavedVis = android.view.View.VISIBLE;

    private void runMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mMain.post(r);
    }
}
