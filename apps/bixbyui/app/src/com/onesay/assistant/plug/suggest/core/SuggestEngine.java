/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

import com.onesay.assistant.plug.api.Clock;
import com.onesay.assistant.plug.api.PlugLog;
import com.onesay.assistant.plug.api.Storage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executor;

/**
 * 信号 → 场景 → 候选 → 仲裁 → 频控 → Presenter（§5.4）。
 *
 * <p>所有状态只在 {@code exec}（宿主给单线程 executor「suggest-engine」，单测给直接执行）上改；信号队列有界 {@link SuggestDefaults#QUEUE_CAP}，
 * 满了丢最旧的信号。Presenter 回调（主线程）也投递到同一队列。岛上同时只有一张建议卡：新的替换旧的（同组 = 就地更新计数）。
 *
 * <p>日志只写场景 id、裁决、原因，不写内容。
 */
public final class SuggestEngine implements SignalSource.Sink, Presenter.Listener {

    /** 宿主旁听结局（剪贴板场景被岛拒绝时退回旧卡片用）。 */
    public interface OutcomeListener {
        void onOutcome(Suggestion s, Outcome o, String reason);
    }

    public static final String UNDO_SCENE = "_undo";
    public static final String UNDO_ACTION = "undo";

    private final SceneRegistry mReg;
    private final SceneConfig mCfg;
    private final HostState mHost;
    private final Presenter mPresenter;
    private final ActionRunner mRunner;
    private final Clock mClock;
    private final Ports.Sched mSched;
    private final Executor mExec;
    private final Storage mStore;
    private final PlugLog mLog;
    private final Ports.FileProbe mFiles;
    private final RateGovernor mGov;
    private volatile OutcomeListener mOutcomeListener;

    private final Object mLock = new Object();
    private final ArrayDeque<Object[]> mQueue = new ArrayDeque<Object[]>();   // {Boolean isSignal, Runnable}
    private int mQueuedSignals;
    private boolean mDraining;
    private volatile boolean mStopped;

    // ---- 只在 exec 上读写
    private Shown mCur;
    private Suggestion mUndo;
    private final LinkedList<String> mRecent = new LinkedList<String>();
    private final Map<String, String> mMem = new HashMap<String, String>();
    /** 场景 id → 最近一次收到撤回信号（{@link Signal#K_WITHDRAW}）的 elapsed：比它早生成的推迟候选不再放。 */
    private final Map<String, Long> mWithdrawnAt = new HashMap<String, Long>();

    static final class Shown {
        Suggestion s;
        Ports.Cancel watch = Ports.NOOP;
        Ports.Cancel safety = Ports.NOOP;

        Shown(Suggestion s) {
            this.s = s;
        }
    }

    public SuggestEngine(SceneRegistry reg, SceneConfig cfg, HostState host, Presenter presenter, ActionRunner runner, Clock clock,
                         Ports.Sched sched, Executor exec, Storage store, PlugLog log, Ports.FileProbe files, TimeZone tz) {
        mReg = reg;
        mCfg = cfg;
        mHost = host;
        mPresenter = presenter == null ? Presenter.NONE : presenter;
        mRunner = runner == null ? ActionRunner.NONE : runner;
        mClock = clock == null ? Clock.SYSTEM : clock;
        mSched = sched == null ? Ports.NEVER : sched;
        mExec = exec;
        mStore = store;
        mLog = log == null ? PlugLog.NONE : log;
        mFiles = files;
        mGov = new RateGovernor(reg, store, tz);
    }

    public RateGovernor governor() {
        return mGov;
    }

    public SceneRegistry registry() {
        return mReg;
    }

    public void setOutcomeListener(OutcomeListener l) {
        mOutcomeListener = l;
    }

    /** 停止：不再接新信号，撤掉岛上的卡（零副作用回落）。 */
    public void stop() {
        mStopped = true;
        synchronized (mLock) {
            mQueue.clear();
            mQueuedSignals = 0;
        }
        Runnable r = new Runnable() {
            @Override public void run() {
                if (mCur != null) {
                    mCur.watch.cancel();
                    mCur.safety.cancel();
                    try {
                        mPresenter.withdraw(mCur.s.id);
                    } catch (Throwable ignored) {
                    }
                    mCur = null;
                }
                if (mUndo != null) {
                    try {
                        mPresenter.withdraw(mUndo.id);
                    } catch (Throwable ignored) {
                    }
                    mUndo = null;
                }
            }
        };
        try {
            if (mExec != null) mExec.execute(r);
            else r.run();
        } catch (Throwable t) {
            r.run();
        }
    }

    // ================================================================= 入口

    @Override public void emit(final Signal s) {
        if (s == null || mStopped) return;
        post(true, new Runnable() {
            @Override public void run() {
                process(s);
            }
        });
    }

    @Override public void onAction(final String id, final String actionId) {
        post(false, new Runnable() {
            @Override public void run() {
                handleAction(id, actionId);
            }
        });
    }

    @Override public void onTimeout(final String id) {
        post(false, new Runnable() {
            @Override public void run() {
                if (mUndo != null && mUndo.id.equals(id)) {
                    mUndo = null;
                    return;
                }
                if (isCur(id)) finish(Outcome.TIMEOUT, "ttl");
            }
        });
    }

    @Override public void onSwipedAway(final String id) {
        post(false, new Runnable() {
            @Override public void run() {
                if (mUndo != null && mUndo.id.equals(id)) {
                    mUndo = null;
                    return;
                }
                if (isCur(id)) finish(Outcome.DISMISSED, "swipe");
            }
        });
    }

    private void post(boolean signal, Runnable r) {
        synchronized (mLock) {
            if (signal) {
                if (mQueuedSignals >= SuggestDefaults.QUEUE_CAP) {   // 满了丢最旧的信号
                    Iterator<Object[]> it = mQueue.iterator();
                    while (it.hasNext()) {
                        if (Boolean.TRUE.equals(it.next()[0])) {
                            it.remove();
                            mQueuedSignals--;
                            break;
                        }
                    }
                    note("queue_full");
                }
                mQueuedSignals++;
            }
            mQueue.add(new Object[]{signal, r});
            if (mDraining) return;
            mDraining = true;
        }
        Runnable drain = new Runnable() {
            @Override public void run() {
                drain();
            }
        };
        try {
            if (mExec != null) mExec.execute(drain);
            else drain.run();
        } catch (Throwable t) {
            synchronized (mLock) {
                mDraining = false;
            }
        }
    }

    private void drain() {
        while (true) {
            Object[] task;
            synchronized (mLock) {
                task = mQueue.poll();
                if (task == null) {
                    mDraining = false;
                    return;
                }
                if (Boolean.TRUE.equals(task[0])) mQueuedSignals--;
            }
            try {
                ((Runnable) task[1]).run();
            } catch (Throwable t) {
                mLog.w("suggest: task error " + t.getClass().getSimpleName());
            }
        }
    }

    // ================================================================= 处理

    private void process(Signal sig) {
        if (mStopped) return;
        if (!safeMaster()) {
            note(sig.type + ":master_off");
            if (Signal.SCREENSHOT.equals(sig.type)) mLog.i("suggest/screenshot: SKIP(master_off) 主动建议总开关关着");
            return;
        }
        String wd = sig.get(Signal.K_WITHDRAW);
        if (wd != null && wd.length() > 0) withdrawScene(wd);
        Suggestion best = null;
        SceneRegistry.Entry bestE = null;
        for (SceneRegistry.Entry e : mReg.entries()) {
            Scene sc = e.scene;
            boolean accepts;
            try {
                accepts = sc.accepts(sig.type);
            } catch (Throwable t) {
                accepts = false;
            }
            if (!accepts) continue;
            if (!sceneOn(sc.id())) {
                if (Signal.SCREENSHOT.equals(sig.type)) mLog.i("suggest/" + sc.id() + ": SKIP(scene_off) 场景关着（控制台 / 点过「不再提示」）");
                continue;
            }
            Suggestion c;
            try {
                c = sc.evaluate(sig, ctx(sc.id()));
            } catch (Throwable t) {
                mLog.w("suggest/" + sc.id() + ": evaluate error " + t.getClass().getSimpleName());
                c = null;
            }
            if (c == null) continue;
            if (best == null || c.priority > best.priority) {
                best = c;
                bestE = e;
            }
        }
        if (best == null) return;
        if (bestE.skeleton) {
            note(best.sceneId + ":skeleton");
            mLog.i("suggest/" + best.sceneId + ": SKIP(skeleton)");
            notifyOutcome(best, Outcome.SUPPRESSED, "skeleton");
            return;
        }
        handleCandidate(best, false);
    }

    private void handleCandidate(final Suggestion cand, boolean retried) {
        if (mStopped) return;
        long now = mClock.wall();
        // 同组合并（连拍截图）：就地更新，不重弹、不计数
        if (cand.groupKey != null && mCur != null && cand.groupKey.equals(mCur.s.groupKey) && cand.sceneId.equals(mCur.s.sceneId)) {
            Suggestion merged = cand.toBuilder().id(mCur.s.id).build();
            mCur.s = merged;
            try {
                mPresenter.update(merged.id, merged.subtitle);
            } catch (Throwable ignored) {
            }
            restartWatch(mCur);
            mLog.i("suggest/" + cand.sceneId + ": MERGED count=" + cand.get("count"));
            return;
        }
        RateGovernor.Verdict v = mGov.permit(cand, mHost, safeQuietLevel(), now, retried);
        if (v.kind == RateGovernor.DEFER) {
            mLog.i("suggest/" + cand.sceneId + ": DEFER(" + v.reason + ")");
            final long born = mClock.elapsed();
            mSched.schedule(new Runnable() {
                @Override public void run() {
                    post(false, new Runnable() {
                        @Override public void run() {
                            if (withdrawnSince(cand.sceneId, born)) {   // 推迟期间信号源撤回了（手电筒已关）
                                suppressed(cand, "withdrawn");
                                return;
                            }
                            if (sceneOn(cand.sceneId) && safeMaster()) handleCandidate(cand, true);
                        }
                    });
                }
            }, v.deferMs);
            return;
        }
        if (v.kind == RateGovernor.DROP) {
            suppressed(cand, v.reason);
            return;
        }
        if (cand.external) {
            mGov.recordShown(cand, now);
            mLog.i("suggest/" + cand.sceneId + ": SHOW(external)");
            sceneOutcome(cand, Outcome.SHOWN);
            notifyOutcome(cand, Outcome.SHOWN, "external");
            return;
        }
        Suggestion show = v.hint ? cand.withSubtitle(join(cand.subtitle, SuggestDefaults.HINT_RARELY_USED)) : cand;
        if (mCur != null) {   // 同屏只出一条：新的替换旧的
            Shown old = mCur;
            old.watch.cancel();
            old.safety.cancel();
            mCur = null;
            try {
                mPresenter.withdraw(old.s.id);
            } catch (Throwable ignored) {
            }
            sceneOutcome(old.s, Outcome.WITHDRAWN);
            notifyOutcome(old.s, Outcome.WITHDRAWN, "replaced");
        }
        boolean ok;
        try {
            ok = mPresenter.show(show);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            suppressed(show, "host:presenter");
            return;
        }
        final Shown sh = new Shown(show);
        mCur = sh;
        mGov.recordShown(show, now);
        mLog.i("suggest/" + show.sceneId + ": SHOW p=" + show.priority);
        sceneOutcome(show, Outcome.SHOWN);
        notifyOutcome(show, Outcome.SHOWN, null);
        restartWatch(sh);
        final String sid = show.id;
        sh.safety = mSched.schedule(new Runnable() {   // 岛没回调超时时的兜底（触碰暂停最长 30 s）
            @Override public void run() {
                post(false, new Runnable() {
                    @Override public void run() {
                        if (isCur(sid)) finish(Outcome.TIMEOUT, "ttl_safety");
                    }
                });
            }
        }, Math.max(show.ttlMs, 30000L) + 5000L);
    }

    private void suppressed(Suggestion s, String reason) {
        note(s.sceneId + ":" + reason);
        mLog.i("suggest/" + s.sceneId + ": SKIP(" + reason + ")");
        sceneOutcome(s, Outcome.SUPPRESSED);
        notifyOutcome(s, Outcome.SUPPRESSED, reason);
    }

    private void handleAction(String id, String actionId) {
        if (mUndo != null && mUndo.id.equals(id)) {
            String scene = mUndo.get("scene");
            mUndo = null;
            if (UNDO_ACTION.equals(actionId) && scene != null) {
                try {
                    mCfg.setSceneOn(scene, true);
                } catch (Throwable ignored) {
                }
                mLog.i("suggest/" + scene + ": UNDO_MUTE");
            }
            return;
        }
        if (!isCur(id)) {
            mLog.i("suggest: stale action ignored");
            return;
        }
        final Suggestion s = mCur.s;
        SAction a = s.action(actionId);
        if (a == null) return;
        if (a.kind == SAction.DISMISS) {
            finish(Outcome.DISMISSED, "button");
        } else if (a.kind == SAction.MUTE_SCENE) {
            try {
                mCfg.setSceneOn(s.sceneId, false);
            } catch (Throwable ignored) {
            }
            finish(Outcome.MUTED, "button");
            showUndo(s.sceneId);
        } else {
            finish(Outcome.ACTION, a.id);
            final String aid = a.id;
            try {
                mRunner.run(s, a, new ActionRunner.Done() {
                    @Override public void finished(boolean ok, String shortReason) {
                        mLog.i("suggest/" + s.sceneId + ": RUN " + aid + " → " + (ok ? "ok" : "fail(" + shortReason + ")"));
                    }
                });
            } catch (Throwable t) {
                mLog.w("suggest/" + s.sceneId + ": runner error " + t.getClass().getSimpleName());
            }
        }
    }

    /** D2：「不再提示」不确认，给 3 秒撤销。 */
    private void showUndo(String sceneId) {
        Scene sc = mReg.scene(sceneId);
        Suggestion u = Suggestion.builder(UNDO_SCENE).title("已关闭" + (sc == null ? "" : sc.title()) + "建议")
                .action(SAction.run(UNDO_ACTION, SuggestDefaults.LABEL_UNDO)).ttl(SuggestDefaults.UNDO_TTL_MS).priority(3)
                .put("scene", sceneId).build();
        boolean ok;
        try {
            ok = mPresenter.show(u);
        } catch (Throwable t) {
            ok = false;
        }
        mUndo = ok ? u : null;
    }

    private void finish(Outcome o, String why) {
        Shown sh = mCur;
        if (sh == null) return;
        mCur = null;
        sh.watch.cancel();
        sh.safety.cancel();
        if (o == Outcome.WITHDRAWN) {
            try {
                mPresenter.withdraw(sh.s.id);
            } catch (Throwable ignored) {
            }
        }
        mGov.recordOutcome(sh.s.sceneId, o, mClock.wall());
        mLog.i("suggest/" + sh.s.sceneId + ": " + o + "(" + why + ")");
        sceneOutcome(sh.s, o);
        notifyOutcome(sh.s, o, why);
    }

    // ================================================================= 信号源撤回（手电筒关了等）

    private void withdrawScene(String sceneId) {
        mWithdrawnAt.put(sceneId, mClock.elapsed());
        if (mCur != null && sceneId.equals(mCur.s.sceneId)) {
            mLog.i("suggest/" + sceneId + ": source → withdraw");
            finish(Outcome.WITHDRAWN, "source_off");
        }
    }

    private boolean withdrawnSince(String sceneId, long bornElapsed) {
        Long w = mWithdrawnAt.get(sceneId);
        return w != null && w >= bornElapsed;
    }

    // ================================================================= 文件被删 → 撤卡

    private void restartWatch(final Shown sh) {
        sh.watch.cancel();
        sh.watch = Ports.NOOP;
        if (sh.s.watchPath == null || mFiles == null) return;
        final String id = sh.s.id;
        sh.watch = mSched.schedule(new Runnable() {
            @Override public void run() {
                post(false, new Runnable() {
                    @Override public void run() {
                        checkWatch(id);
                    }
                });
            }
        }, SuggestDefaults.WATCH_PERIOD_MS);
    }

    private void checkWatch(String id) {
        if (!isCur(id)) return;
        Shown sh = mCur;
        String p = sh.s.watchPath;
        boolean exists;
        try {
            exists = mFiles.exists(p);
        } catch (Throwable t) {
            exists = true;
        }
        if (exists) {
            restartWatch(sh);
            return;
        }
        // 连拍：主图被删，还有别的就退到最新一张还在的；全没了就撤卡
        List<String> left = new ArrayList<String>();
        String paths = sh.s.get("paths");
        if (paths != null) for (String x : paths.split("\n")) {
            if (x.length() == 0 || x.equals(p)) continue;
            boolean e;
            try {
                e = mFiles.exists(x);
            } catch (Throwable t) {
                e = false;
            }
            if (e) left.add(x);
        }
        if (left.isEmpty()) {
            mLog.i("suggest/" + sh.s.sceneId + ": file gone → withdraw");
            finish(Outcome.WITHDRAWN, "file_gone");
            return;
        }
        StringBuilder b = new StringBuilder();
        for (String x : left) b.append(b.length() > 0 ? "\n" : "").append(x);
        String latest = left.get(left.size() - 1);
        String sub = left.size() > 1 ? left.size() + " 张截图" : sh.s.get("sub1");
        Suggestion n = sh.s.toBuilder().watch(latest).thumb(latest).put("path", latest).put("paths", b.toString())
                .put("count", String.valueOf(left.size())).subtitle(sub).build();
        sh.s = n;
        try {
            mPresenter.update(n.id, n.subtitle);
        } catch (Throwable ignored) {
        }
        restartWatch(sh);
    }

    // ================================================================= 杂项

    private boolean isCur(String id) {
        return mCur != null && mCur.s.id.equals(id);
    }

    /** 当前展示中的建议（只给宿主调试 / 单测）。 */
    public Suggestion current() {
        Shown s = mCur;
        return s == null ? null : s.s;
    }

    private boolean safeMaster() {
        try {
            return mCfg.masterOn();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean sceneOn(String id) {
        try {
            return mCfg.sceneOn(id, mReg.defaultOn(id));
        } catch (Throwable t) {
            return false;
        }
    }

    private int safeQuietLevel() {
        try {
            return mCfg.quietLevel();
        } catch (Throwable t) {
            return 0;
        }
    }

    private void sceneOutcome(Suggestion s, Outcome o) {
        Scene sc = mReg.scene(s.sceneId);
        if (sc == null) return;
        try {
            sc.onOutcome(s, o, ctx(sc.id()));
        } catch (Throwable t) {
            mLog.w("suggest/" + sc.id() + ": onOutcome error");
        }
    }

    private void notifyOutcome(Suggestion s, Outcome o, String reason) {
        OutcomeListener l = mOutcomeListener;
        if (l == null) return;
        try {
            l.onOutcome(s, o, reason);
        } catch (Throwable ignored) {
        }
    }

    private void note(String r) {
        synchronized (mRecent) {
            mRecent.addFirst(r);
            while (mRecent.size() > SuggestDefaults.RECENT_SKIPS) mRecent.removeLast();
        }
    }

    /** 最近被跳过的建议（只有场景 id 与原因，不含内容）。 */
    public List<String> recentSkips() {
        synchronized (mRecent) {
            return new ArrayList<String>(mRecent);
        }
    }

    public String status() {
        StringBuilder b = new StringBuilder();
        b.append("master=").append(safeMaster() ? "on" : "off").append(" quiet=").append(safeQuietLevel());
        Shown c = mCur;
        b.append(" showing=").append(c == null ? "-" : c.s.sceneId).append('\n');
        for (SceneRegistry.Entry e : mReg.entries()) {
            String id = e.scene.id();
            b.append("  ").append(id).append(' ').append(sceneOn(id) ? "开" : "关").append(" (默认").append(mReg.defaultOn(id) ? "开" : "关")
                    .append(e.skeleton ? "·骨架" : "").append(" 隐私").append(e.scene.privacyClass()).append(") streak=").append(mGov.streak(id)).append('\n');
        }
        b.append("  skipped=").append(recentSkips());
        return b.toString();
    }

    private static String join(String a, String b) {
        return a == null || a.length() == 0 ? b : a + " · " + b;
    }

    // ================================================================= 场景上下文

    private SceneContext ctx(final String sceneId) {
        return new SceneContext() {
            @Override public Clock clock() {
                return mClock;
            }

            @Override public HostState host() {
                return mHost;
            }

            @Override public SceneConfig config() {
                return mCfg;
            }

            @Override public String load(String key) {
                String k = "scene." + sceneId + "." + key;
                if (mStore != null) {
                    try {
                        return mStore.all().get(k);
                    } catch (Throwable t) {
                        return null;
                    }
                }
                synchronized (mMem) {
                    return mMem.get(k);
                }
            }

            @Override public boolean save(String key, String val) {
                String k = "scene." + sceneId + "." + key;
                if (mStore != null) {
                    try {
                        return val == null ? mStore.remove(k) : mStore.put(k, val);
                    } catch (Throwable t) {
                        return false;
                    }
                }
                synchronized (mMem) {
                    if (val == null) mMem.remove(k);
                    else mMem.put(k, val);
                }
                return true;
            }
        };
    }
}
