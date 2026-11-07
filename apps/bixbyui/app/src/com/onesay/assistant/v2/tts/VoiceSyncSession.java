package com.onesay.assistant.v2.tts;

/**
 * ★ arch/voice-sync：一个前台长任务（AgentLoop）的「语音 × 交互」时序。纯 Java（时钟 / 定时器 / 出声都注入），JVM 可测。
 *
 * <h3>时序</h3>
 * <ol>
 *   <li>{@link #start}：分诊判多步（islandNow）→ <b>立刻</b>播应答（LLM 规划在另一个线程并行进行，不等它）；
 *       单步懒上岛任务先不说，等拿到上岛资格（{@link #onStep} 第 2 次工具调用 / {@link #onSubgoals} ≥2 /
 *       {@link #beforeGui} / {@link #onAskUser}）再说；一次都没拿到（单次接口调用就做完）→ 整个任务不应答。
 *       同时排一个 {@link VoiceSyncPolicy#STILL_WORKING_MS} 的「催一次」。</li>
 *   <li>{@link #beforeGui}（循环线程，阻塞）：第一个 GUI 动作之前，还没应答就先应答，然后等应答播完 / 被打断 / 没出声，
 *       最多 {@link VoiceSyncPolicy#GATE_MAX_WAIT_MS}。之后的 GUI 动作直接放行。</li>
 *   <li>催一次：到点时任务还在跑、没在等用户回答 / 确认、整个任务还没催过 → 播「还在弄，快好了」。到点时应答还在播 → 往后挪 1 s；
 *       正在问用户 / 确认卡未决 → 不催，等用户答完再重新计 8 s（仍然只催一次）。</li>
 *   <li>{@link #onAskUser}：播提示 + 字幕（岛上芯片由岛自己出）；问的时候不催。</li>
 *   <li>{@link #end}：取消催、放开门控。结局那句由宿主按 {@link #outcomeTemplate} 走回答模板播。</li>
 * </ol>
 * 每句提示都同步出字幕（岛 + 面板，{@link Out#caption}）；静音（{@link Out#silenceReason} 非空）时只出字幕不出声，门控立即放行。
 */
public final class VoiceSyncSession {

    /** 出声 / 字幕 / 日志（Android 侧由 s1.VoiceSync 实现）。 */
    public interface Out {
        /**
         * 播一句一次性提示（不进回答状态机）。返回 true = 已开始播，之后 {@code done} 恰好回调一次（播完 / 被打断 / 播放失败，任意线程）；
         * false = 没播（音频缺失等），{@code done} 不会被回调。
         */
        boolean playCue(VoiceTemplate t, Runnable done);

        /** null = 可以出声；否则是静音原因。 */
        String silenceReason();

        /** 字幕：岛 + 面板同步显示这句。 */
        void caption(String text);

        void log(String msg);
    }

    /** 定时器（Android 侧 = 主线程 Handler）。 */
    public interface Scheduler {
        Object schedule(Runnable r, long delayMs);

        void cancel(Object token);
    }

    private final Out mOut;
    private final Scheduler mSched;

    private boolean mIslandNow;
    private int mEstSteps = 1;
    private boolean mAcked;
    private VoiceTemplate mAckTpl;
    /** 应答正在播（门控关着）。 */
    private boolean mAckPlaying;
    /** 当前提示的编号（迟到的 done 回调按编号认领）。 */
    private int mCueSeq;
    private int mAckCueSeq = -1;
    private boolean mGuiGatePassed;
    private boolean mNudged;
    private boolean mAsking;
    private boolean mConfirming;
    private boolean mEnded;
    private int mSteps;
    private int mMaxSubgoals;
    private boolean mLastWriteUnverified;
    private Object mNudgeToken;
    private final Object mGate = new Object();

    public VoiceSyncSession(Out out, Scheduler sched) {
        mOut = out;
        mSched = sched;
    }

    // ------------------------------------------------------------------ 入口

    /** 任务开始（主线程）。 */
    public void start(boolean islandNow, int estSteps) {
        synchronized (this) {
            mIslandNow = islandNow;
            mEstSteps = Math.max(1, estSteps);
        }
        if (islandNow) ack(VoiceSyncPolicy.AckWhy.ISLAND_NOW, estSteps);
        scheduleNudge(VoiceSyncPolicy.STILL_WORKING_MS);
    }

    /** 循环里的一次工具调用开始（任意线程）。第 2 次 = 不是单次接口调用 → 懒上岛任务补应答。 */
    public void onStep() {
        boolean need;
        synchronized (this) {
            mSteps++;
            need = mSteps >= 2 && !mAcked && !mEnded;
        }
        if (need) ack(VoiceSyncPolicy.AckWhy.STEPS, 1);
    }

    /** 任务账本变了（任意线程）：子目标 ≥2 → 懒上岛任务补应答（≥3 用「这个有点多」）。 */
    public void onSubgoals(int n) {
        boolean need;
        synchronized (this) {
            if (n > mMaxSubgoals) mMaxSubgoals = n;
            need = n >= 2 && !mAcked && !mEnded;
        }
        if (need) ack(VoiceSyncPolicy.AckWhy.SUBGOALS, n);
    }

    /**
     * 第一个 GUI 动作之前（<b>循环线程，阻塞</b>）：还没应答 → 先应答；等应答播完，最多 maxWaitMs。
     *
     * @return 实际等了多少毫秒（不用等 → 0）
     */
    public long beforeGui(long maxWaitMs) {
        boolean need;
        synchronized (this) {
            if (mGuiGatePassed || mEnded) return 0L;
            mGuiGatePassed = true;
            need = !mAcked;
        }
        if (need) ack(VoiceSyncPolicy.AckWhy.GUI, mEstSteps);
        long t0 = System.currentTimeMillis();
        synchronized (mGate) {
            while (isAckPlaying()) {
                long left = maxWaitMs - (System.currentTimeMillis() - t0);
                if (left <= 0) {
                    mOut.log("[VOICE-SYNC] 门控等应答超过 " + maxWaitMs + "ms，放行第一个 GUI 动作");
                    break;
                }
                try {
                    mGate.wait(Math.min(left, 100L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        long waited = System.currentTimeMillis() - t0;
        mOut.log("[VOICE-SYNC] 第一个 GUI 动作放行（等应答 " + waited + "ms）");
        return waited;
    }

    /** ask_user 弹出（任意线程）：播提示 + 字幕；问的时候不催。没应答过的任务这句就当应答（不再补「好的」）。 */
    public void onAskUser(String question) {
        synchronized (this) {
            if (mEnded) return;
            mAsking = true;
            mAcked = true;   // 提问本身就是开口，不再补应答
        }
        cue(VoiceTemplate.ASK_PICK, question == null || question.trim().length() == 0 ? VoiceTemplate.ASK_PICK.text : question.trim(), false);   // ★ v3b：原暂代 CONFIRM
    }

    /** ask_user 收到回答 / 撤回 / 超时（任意线程）。 */
    public void onAskResolved() {
        boolean resched;
        synchronized (this) {
            if (!mAsking) return;
            mAsking = false;
            resched = !mNudged && !mEnded;
        }
        if (resched) scheduleNudge(VoiceSyncPolicy.STILL_WORKING_MS);
    }

    /** 循环确认卡弹出 / 裁决（任意线程）。确认期间不催。 */
    public void onConfirm(boolean pending) {
        boolean resched = false;
        synchronized (this) {
            if (pending) {
                mConfirming = true;
            } else if (mConfirming) {
                mConfirming = false;
                resched = !mNudged && !mEnded;
            }
        }
        if (resched) scheduleNudge(VoiceSyncPolicy.STILL_WORKING_MS);
    }

    /** 一个动作执行完（任意线程）。只读动作不改「最后一个改动类动作」。 */
    public synchronized void onAction(boolean ok, boolean verified, boolean navigation, boolean readOnly) {
        if (readOnly) return;
        mLastWriteUnverified = VoiceSyncPolicy.unverifiedWrite(ok, verified, navigation, false);
    }

    /** 任务结束（主线程）：取消催、放开门控。 */
    public void end() {
        Object tok;
        synchronized (this) {
            mEnded = true;
            tok = mNudgeToken;
            mNudgeToken = null;
            mAckPlaying = false;
        }
        if (tok != null) mSched.cancel(tok);
        synchronized (mGate) {
            mGate.notifyAll();
        }
    }

    /** 结局模板（{@link VoiceSyncPolicy#outcome}）：多步 = 账本子目标 ≥2 或开局按「这个有点多」应答过。 */
    public synchronized VoiceTemplate outcomeTemplate(boolean resultNull, com.onesay.assistant.v2.agent.Outcome o, String reason, int subgoals) {
        boolean multi = Math.max(subgoals, mMaxSubgoals) >= 2 || mAckTpl == VoiceTemplate.ACK_MANY;
        return VoiceSyncPolicy.outcome(resultNull, o, reason, multi, mLastWriteUnverified);
    }

    // ------------------------------------------------------------------ 查询（测试 / 日志）

    public synchronized boolean acked() { return mAcked; }

    public synchronized VoiceTemplate ackTemplate() { return mAckTpl; }

    public synchronized boolean nudged() { return mNudged; }

    public synchronized boolean isAckPlaying() { return mAckPlaying; }

    public synchronized boolean ended() { return mEnded; }

    // ------------------------------------------------------------------ 内部

    private void ack(VoiceSyncPolicy.AckWhy why, int est) {
        VoiceTemplate t;
        synchronized (this) {
            if (mAcked || mEnded) return;
            mAcked = true;
            t = VoiceSyncPolicy.ack(why, est);
            mAckTpl = t;
        }
        mOut.log("[VOICE-SYNC] 应答「" + t.text + "」（" + why + "，估计 " + est + " 步）");
        cue(t, t.text, true);
    }

    /** 播一句提示 + 字幕。isAck = 这句是应答（门控等它）。 */
    private void cue(VoiceTemplate t, String caption, final boolean isAck) {
        if (caption != null) mOut.caption(caption);
        String silent = mOut.silenceReason();
        if (silent != null) {
            mOut.log("[VOICE-SYNC] 「" + t.text + "」不出声（" + silent + "），只显示字幕");
            return;
        }
        final int seq;
        synchronized (this) {
            seq = ++mCueSeq;
            if (isAck) {
                mAckCueSeq = seq;
                mAckPlaying = true;
            } else if (mAckPlaying) {
                mAckPlaying = false;   // 新提示顶掉还在播的应答（播放层会停掉上一句）
            }
        }
        boolean started = mOut.playCue(t, new Runnable() {
            @Override public void run() {
                cueDone(seq);
            }
        });
        if (!started) cueDone(seq);
        if (!isAck) wakeGate();
    }

    private void cueDone(int seq) {
        synchronized (this) {
            if (seq == mAckCueSeq) mAckPlaying = false;
        }
        wakeGate();
    }

    private void wakeGate() {
        synchronized (mGate) {
            mGate.notifyAll();
        }
    }

    private void scheduleNudge(long delayMs) {
        Object old;
        synchronized (this) {
            if (mNudged || mEnded) return;
            old = mNudgeToken;
            mNudgeToken = null;
        }
        if (old != null) mSched.cancel(old);
        Object tok = mSched.schedule(new Runnable() {
            @Override public void run() {
                nudgeDue();
            }
        }, delayMs);
        synchronized (this) {
            if (mEnded) {
                mSched.cancel(tok);
                return;
            }
            mNudgeToken = tok;
        }
    }

    private void nudgeDue() {
        boolean fire = false, retry = false;
        synchronized (this) {
            mNudgeToken = null;
            if (mNudged || mEnded) return;
            if (mAsking || mConfirming) return;   // 等用户答完再重新计时（onAskResolved / onConfirm(false)）
            if (mAckPlaying) {
                retry = true;
            } else {
                mNudged = true;
                fire = true;
            }
        }
        if (retry) {
            scheduleNudge(VoiceSyncPolicy.NUDGE_RETRY_MS);
            return;
        }
        if (fire) {
            mOut.log("[VOICE-SYNC] 超过 " + (VoiceSyncPolicy.STILL_WORKING_MS / 1000) + " 秒没完成，催一次（之后不再催）");
            cue(VoiceTemplate.STILL_WORKING, null, false);   // ★ 11-04 午：字幕不出套话 —— 岛 / 面板到 8 s 自己补真实状态（ProgressFeedback.stallLine）
        }
    }
}
