package com.onesay.assistant.v2.brief;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.kernel.IslandHost;

import java.util.List;

/**
 * 把 {@link BriefState#plan} 的操作交给灵动岛通道（插件 id = brief）。岛没挂载（面板服务没起来）时通道是空操作。
 * 记住上一个 phase，用来判断「运行 → 运行」只换文字。
 *
 * <p>2026-10-23（lane-a B-9）：功能被关掉（控制台开关 / kill-file brief.off）时，岛上已有的简报必须被拿掉：
 * <ul>
 *   <li>撤下走 {@link IslandHost.Channel#cleanupBg}（不受插件开关门控 —— 关掉后内核会丢弃本插件的普通岛操作）；</li>
 *   <li>运行态（生成中 / 播放中）期间起一个轻量看门狗，每 {@link #KILL_POLL_MS} 现查一次开关，发现关了就撤下；</li>
 *   <li>开关关着时收到的任何简报事件（包括 DONE / 失败）也会撤下岛上的旧状态（{@link #onEventWhileOff}）。</li>
 * </ul>
 */
public final class BriefIsland {

    private BriefIsland() {}

    private static final String TAG = "BriefIsland";

    /** 看门狗轮询间隔（kill-file 没有系统回调，只能现查）。 */
    public static final long KILL_POLL_MS = 1000L;
    /** PLAYING 没带时长时，最多认为它在播多久（v2 崩了不发 DONE 也不会永远「在播」）。 */
    public static final long PLAYING_FALLBACK_MS = 15L * 60L * 1000L;
    /** 时长之外的宽限（v2 发 DONE 有延迟）。 */
    public static final long PLAYING_GRACE_MS = 30L * 1000L;

    private static BriefState.Phase sLast = BriefState.Phase.IGNORE;
    private static long sPlayingUntil;
    /** 最近一次简报事件（含 DETAIL 回执）的时刻，判「活跃」用。 */
    private static long sLastEventAt;
    /** 最近一次因按 Bixby 键停简报的时刻（多通道去抖）。 */
    private static long sKeyStopAt;
    /** ★ BUG-A1：上一次因按键停简报时那次按键的 seq（0 = 不知道）。 */
    private static int sKeyStopSeq;
    private static volatile Thread sWatch;

    /** 岛上「停止」：拿掉岛上的活动。 */
    static final IslandHost.Listener LISTENER = new IslandHost.Listener() {
        @Override public void onBgConfirm(String id, boolean allow) { }
        @Override public void onBgStop(String id) {
            if (!BriefState.ISLAND_ID.equals(id)) return;
            boolean wasRunning;
            synchronized (BriefIsland.class) {
                wasRunning = BriefState.isRunning(sLast);
                reset();
            }
            Log.i(TAG, "[BRIEF] 岛上点了停止 → 移除简报活动" + (wasRunning ? "，并让 v2 停播（STOP）" : ""));
            channel().cleanupBg(id);
            // lane-a 第 2 项：「停止」要真的停 v2（root 广播 STOP），不只是把岛上的活动拿掉
            if (wasRunning) BriefRemote.send(sApp, BriefControl.Cmd.STOP, -1);
        }
        @Override public void onRemindDone(String id) { }
        @Override public void onRemindSnooze(String id) { }
        @Override public void onTodoCheck(String id) { }
    };

    static IslandHost.Channel channel() {
        return com.onesay.assistant.v2.kernel.FeatureKernel.get().island(com.onesay.assistant.v2.kernel.plugins.BriefPlugin.ID);
    }

    private static volatile Context sApp;

    static Context appContext() {
        return sApp;
    }

    public static List<BriefState.Step> apply(BriefState.Event ev) {
        return apply(null, ev);
    }

    public static synchronized List<BriefState.Step> apply(Context ctx, BriefState.Event ev) {
        if (ctx != null) sApp = ctx.getApplicationContext();
        if (ev != null && ev.phase != BriefState.Phase.IGNORE) sLastEventAt = System.currentTimeMillis();
        List<BriefState.Step> steps = BriefState.plan(sLast, ev);
        if (steps.isEmpty()) return steps;
        IslandHost.Channel ch = channel();
        ch.setScopedListener(LISTENER);
        String id = BriefState.ISLAND_ID;
        for (BriefState.Step s : steps) {
            switch (s.op) {
                case REMOVE: ch.removeBg(id); break;
                case START: ch.bgStarted(id, s.text); break;
                case STEP: ch.bgStep(id, s.text); break;
                case END_OK: ch.bgEnded(id, true, null); break;
                case END_FAIL: ch.bgEnded(id, false, s.text); break;
                default: break;
            }
        }
        sLast = ev.phase;
        long now = System.currentTimeMillis();
        sPlayingUntil = ev.phase == BriefState.Phase.PLAYING ? playingUntil(now, ev.detail) : 0L;
        Log.i(TAG, "[BRIEF] 上岛 " + ev + " → " + steps + (ch.ready() ? "" : "（岛未挂载，空操作）"));
        if (BriefState.isRunning(ev.phase)) startKillWatch();
        return steps;
    }

    /** 关掉功能时把岛上的简报拿掉（不受开关门控）。 */
    public static synchronized void clear() {
        reset();
        channel().cleanupBg(BriefState.ISLAND_ID);
    }

    /** 岛上有本插件的简报（上一个事件不是 IGNORE）才撤；返回是否撤了。 */
    public static synchronized boolean clearIfShowing() {
        if (sLast == BriefState.Phase.IGNORE) return false;
        clear();
        return true;
    }

    /**
     * 开关关着时收到了简报事件：不上岛，但要把岛上旧的简报状态撤掉（例如 kill-file 期间 v2 发来 DONE）。
     * 返回是否撤了。
     */
    public static boolean onEventWhileOff(BriefState.Event ev) {
        boolean did = clearIfShowing();
        if (did) Log.i(TAG, "[BRIEF] 功能已关，收到 " + ev + " → 撤下岛上旧的简报");
        return did;
    }

    /** 此刻 v2 是否在播（岛上是「播放中」且没超过时长 + 宽限）。按 Bixby 键停简报用。 */
    public static synchronized boolean isPlayingNow(long now) {
        return sLast == BriefState.Phase.PLAYING && now < sPlayingUntil;
    }

    /** 简报此刻是否「活跃」（在播，或 10 分钟内有过事件）：裸短句「再播一遍 / 跳过 / 详细说说」只在活跃时归简报。 */
    public static synchronized boolean isActive(long now) {
        return BriefControl.isActive(isPlayingNow(now), sLastEventAt, now);
    }

    /** 「详细说说」发出后多久内认 v2 的 DETAIL 回执。 */
    public static final long DETAIL_REPLY_MS = 30L * 1000L;
    private static long sDetailAskedAt;

    /** 发出 DETAIL 时记一笔。 */
    public static synchronized void markDetailRequested(long now) {
        sDetailAskedAt = now;
    }

    /** 收到 DETAIL 回执：最近 {@link #DETAIL_REPLY_MS} 内发过才认，认了就作废（一次请求一次展开）。 */
    public static synchronized boolean consumeDetailRequest(long now) {
        boolean ok = sDetailAskedAt > 0 && now >= sDetailAskedAt && now - sDetailAskedAt <= DETAIL_REPLY_MS;
        if (ok) sDetailAskedAt = 0L;
        return ok;
    }

    /** 收到 DETAIL 回执等不上岛的事件时也算一次活动。 */
    public static synchronized void touch(long now) {
        sLastEventAt = now;
    }

    /**
     * 按 Bixby 键唤醒前调用（经 WakeAdmission，见下）：简报在播 → 发 STOP、吞掉这次按键（返回 true，不唤醒）；
     * 同一次按键从别的通道再到 → 也吞掉。其余返回 false（照常唤醒）。
     */
    public static boolean onKeyWake(Context ctx, String source) {
        return onKeyWake(ctx, source, 0);
    }

    /**
     * ★ BUG-A1：带按键序号（守护 v7/v8 的 seq，不知道传 0）。只由 {@code WakeAdmission} 调用——
     * 唤醒汇合点 {@code WakeService.requestWakeInternal} 与手势汇合点 {@code WakeService.dispatchKeyGesture}。
     */
    public static boolean onKeyWake(Context ctx, String source, int seq) {
        boolean key = source != null && source.startsWith("key-");
        if (!key) return false;
        long now = System.currentTimeMillis();
        BriefControl.KeyDecision d;
        synchronized (BriefIsland.class) {
            d = BriefControl.onKey(true, isPlayingNow(now), sKeyStopAt, sKeyStopSeq, seq, now);
            if (d == BriefControl.KeyDecision.STOP_AND_SWALLOW) {
                sKeyStopAt = now;
                sKeyStopSeq = seq;
                if (ctx != null && sApp == null) sApp = ctx.getApplicationContext();
                reset();
            }
        }
        if (d == BriefControl.KeyDecision.PASS) return false;
        if (d == BriefControl.KeyDecision.STOP_AND_SWALLOW) {
            Log.i(TAG, "[BRIEF] 简报播放中按了 Bixby 键（src=" + source + (seq > 0 ? " seq=" + seq : "") + "）→ 只停简报，本次不唤醒");
            channel().cleanupBg(BriefState.ISLAND_ID);
            BriefRemote.send(ctx != null ? ctx : sApp, BriefControl.Cmd.STOP, -1);
        } else {
            Log.i(TAG, "[BRIEF] 同一次按键的另一路通道 / 后续事件（src=" + source + (seq > 0 ? " seq=" + seq : "") + "）→ 一并吞掉");
        }
        return true;
    }

    public static synchronized BriefState.Phase lastPhase() {
        return sLast;
    }

    private static void reset() {
        sLast = BriefState.Phase.IGNORE;
        sPlayingUntil = 0L;
    }

    /** PLAYING 的有效期（纯函数）：detail 里 durationMs=N → now + N + 宽限；否则 now + 15 分钟。 */
    public static long playingUntil(long now, String detail) {
        long d = durationMs(detail);
        return now + (d > 0 ? d + PLAYING_GRACE_MS : PLAYING_FALLBACK_MS);
    }

    /** detail「durationMs=N」里的 N（没有 / 读不出 = 0）。 */
    public static long durationMs(String detail) {
        if (detail == null) return 0L;
        int i = detail.indexOf("durationMs=");
        if (i < 0) return 0L;
        int j = i + "durationMs=".length(), k = j;
        while (k < detail.length() && Character.isDigit(detail.charAt(k))) k++;
        if (k == j || k - j > 9) return 0L;
        return Long.parseLong(detail.substring(j, k));
    }

    /** 看门狗是否该撤下（纯函数）：岛上在运行态，而开关已关。 */
    public static boolean shouldClear(BriefState.Phase last, boolean switchOn) {
        return !switchOn && last != BriefState.Phase.IGNORE;
    }

    private static synchronized void startKillWatch() {
        if (sWatch != null && sWatch.isAlive()) return;
        final Context app = sApp;
        if (app == null) return;   // 只有 JVM 单测 / 调试入口没给 Context
        sWatch = com.onesay.assistant.v2.util.Threads.start("brief-killwatch", new Runnable() {
            @Override public void run() {
                try {
                    while (true) {
                        Thread.sleep(KILL_POLL_MS);
                        BriefState.Phase last;
                        synchronized (BriefIsland.class) {
                            last = sLast;
                        }
                        if (!BriefState.isRunning(last)) return;   // 终态由岛自己 3 s / 5 min 收尾
                        if (shouldClear(last, com.onesay.assistant.v2.kernel.plugins.BriefPlugin.SWITCH.on(app))) {
                            Log.i(TAG, "[BRIEF] 看门狗：功能已关（kill-file / 开关）→ 撤下岛上的「" + last + "」");
                            clear();
                            return;
                        }
                    }
                } catch (InterruptedException ignored) {
                } catch (Throwable t) {
                    Log.w(TAG, "[BRIEF] 看门狗异常: " + t);
                } finally {
                    synchronized (BriefIsland.class) {
                        if (sWatch == Thread.currentThread()) sWatch = null;
                    }
                }
            }
        });
    }

    /** JVM 单测：复位静态状态。 */
    public static synchronized void resetForTestPublic() {
        resetForTest();
    }

    static synchronized void resetForTest() {
        reset();
        sApp = null;
        sLastEventAt = 0L;
        sKeyStopAt = 0L;
        sKeyStopSeq = 0;
        sDetailAskedAt = 0L;
    }
}
