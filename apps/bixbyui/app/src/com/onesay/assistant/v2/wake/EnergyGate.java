package com.onesay.assistant.v2.wake;

import android.os.SystemClock;

/**
 * 自适应能量门与回溯窗口。
 *
 * 核心设计：
 * 1. 动态估计环境底噪；
 * 2. 结合 2500ms 回溯窗口（避免因 KWS 识别延迟导致真实人声被瞬时能量门切断）；
 * 3. ★ 缺陷修复（2026-10-02）：新增「软能量」通道。
 *    神经网 KWS 自身的判别力远强于静态 RMS 阈值，轻声/远场唤醒时
 *    rms 常常低于硬门限 350（v1 实测出现过 rms=53 而 KWS 已确信命中的情况），
 *    结果关键词被自己的门丢掉 → 用户感觉「轻声唤不醒」。
 *    现在为 KWS 提供一条放宽但仍带底噪约束的通道（见 isSoftSpeech），
 *    而不是直接把能量门整个摘掉（那会把误唤醒放回来）。
 */
public class EnergyGate {

    private static final long BACKTRACK_WINDOW_MS = 2500;
    private static final float NOISE_SMOOTH_FACTOR = 0.95f;

    /** 硬门限：判定“确实有人在说话”（KWS 放行 / 捕获窗外的人声计时；R14 起不再参与捕获窗内的断句） */
    private static final float HARD_ABS_RMS = 350.0f;
    private static final float HARD_FLOOR_RATIO = 2.5f;

    /** 软门限：仅供神经网 KWS 命中后的放行判定使用 */
    private static final float SOFT_ABS_RMS = 110.0f;
    private static final float SOFT_FLOOR_RATIO = 1.6f;

    // ================= ★ R14（2026-10-15）：捕获窗断句改用「冻结底噪 × 1.8」 =================
    //
    // 10-15 01:10 真机根因（diag-0115/REPORT.md §A）：旧实现「没过硬门的帧都拿去平滑底噪」，
    //   用户一开口，没过线的人声把底噪从 253 抬到 330、484，阈值 max(350, 底噪×2.5) 跟着涨到 825–1200，
    //   形成正反馈 —— 三次尝试只数到 0→3 / 1 / 5 帧人声（断句要 4 帧），第 1 次一直断不了句、用户放弃。
    // 修法（三件事，缺一不可）：
    //   ① 底噪只从「安静帧」更新：rms < 底噪 × QUIET_RATIO 才按 0.95 平滑；比底噪高的帧只在捕获窗外极慢上调
    //      （SLOW_RISE，约 80 s 跟上环境变吵），人声帧永远进不了底噪。
    //   ② 捕获窗内（armCapture → endCapture）底噪冻结在开窗那一刻的值，一帧都不更新。
    //   ③ 捕获窗内的人声判据 = rms > max(CAPTURE_ABS_MIN, 冻结底噪 × CAPTURE_FLOOR_RATIO)。
    //   KWS 放行（硬门 / 软门）照旧用原来的门限和实时底噪，误唤醒行为不变。

    /** 安静帧判据：rms 低于底噪的这个倍数才算环境底噪样本。 */
    static final float QUIET_RATIO = 1.3f;
    /** 捕获窗外、比底噪高但不是人声的帧：每帧只把底噪往上拉 0.4%（环境真变吵时约 80 s 跟上）。 */
    static final float SLOW_RISE = 0.004f;
    /** ★ R14.1：捕获窗外过了硬门的帧也极慢地抬底噪（稳态噪声约 40 s 跟上）。 */
    static final float HARD_SLOW_RISE = 0.002f;
    /** 捕获窗内人声判据：冻结底噪 × 1.8。 */
    public static final float CAPTURE_FLOOR_RATIO = 1.8f;
    /** 捕获窗内人声判据的绝对下限（安静房间底噪 100 时阈值 = 220，而不是 180）。 */
    public static final float CAPTURE_ABS_MIN = 220.0f;
    /** 冻结的安全上限：调用方忘了 endCapture 也会在这之后自动解冻（整段窗上限 20 s + 1 s 余量）。 */
    static final long CAPTURE_FREEZE_MAX_MS = 21000L;

    private float mNoiseFloor = 200.0f;
    private long mLastSpeechTimestamp = 0;
    private long mLastSoftSpeechTimestamp = 0;

    /** 捕获窗状态（armCapture 置真，endCapture / 超时置假）。 */
    private boolean mCapturing = false;
    private float mFrozenFloor = 200.0f;
    private long mCaptureArmedAt = 0L;
    /** 本窗第一帧人声时刻（0 = 还没有）。 */
    private long mFirstSpeechAt = 0L;

    /** 本窗 RMS 统计（收窗汇总日志用；只存 int，最多 CAPTURE_TRACE_MAX 帧）。 */
    static final int CAPTURE_TRACE_MAX = 240;
    private final int[] mTrace = new int[CAPTURE_TRACE_MAX];
    private int mTraceN = 0;
    private int mSoftFrames = 0;

    /**
     * 计算并处理单音频帧能量。
     *
     * @param buffer 16-bit PCM 采样
     * @param read   采样点数
     * @return 当前是否有人声或处于回溯许可期（捕获窗内按捕获窗判据，窗外按 KWS 硬门）
     */
    public synchronized boolean processFrame(short[] buffer, int read) {
        if (buffer == null || read <= 0) return false;
        return processRms(rmsOf(buffer, read), SystemClock.elapsedRealtime());
    }

    /** 一帧的均方根（纯函数）。 */
    public static float rmsOf(short[] buffer, int read) {
        long sumSquare = 0;
        for (int i = 0; i < read; i++) {
            sumSquare += (long) buffer[i] * buffer[i];
        }
        return (float) Math.sqrt((double) sumSquare / read);
    }

    /**
     * ★ R14：按 RMS 处理一帧（纯逻辑，JVM 单测直接回放 RMS 序列）。{@link #processFrame} 只是先算 RMS 再调这里。
     */
    public synchronized boolean processRms(float rms, long now) {
        if (mCapturing && now - mCaptureArmedAt > CAPTURE_FREEZE_MAX_MS) {
            mCapturing = false;   // 兜底解冻（调用方漏了 endCapture）
        }
        boolean hard = (rms > mNoiseFloor * HARD_FLOOR_RATIO && rms > HARD_ABS_RMS);
        if (hard) mLastHardAt = now;
        boolean isSpeech = mCapturing ? rms > captureThreshold() : hard;
        // ★ R13：音量只读出口（唤醒界面的边缘光 / 小球随声音呼吸）；只写 volatile，不持锁、不分配
        com.onesay.assistant.v2.ui.wake.VoiceLevel.publish(rms, mNoiseFloor, now);

        if (isSpeech) {
            // ★ R14.1（10-15 白天实测）：屏外持续的稳态噪声（风扇 / 电视 / 人声嘈杂，rms≈700）一直过硬门 → 被当「人声」，
            //   底噪永远停在 190，开窗冻结后阈值 343，静音唤醒 20 s 全是「人声」、一直续期，到点把整段送识别。
            //   捕获窗外的硬门帧也极慢地抬底噪（每帧 0.2 %，约 40 s 跟上稳态噪声；几秒的说话只抬几个百分点）。
            if (!mCapturing) mNoiseFloor += (rms - mNoiseFloor) * HARD_SLOW_RISE;
            mLastSpeechTimestamp = now;
            if (mFirstSpeechAt == 0L) mFirstSpeechAt = now;
            // 累加人声帧数，供断句判据做"确实听到过一段话"的前置校验。
            if (mSpeechFrameCount < 1000) mSpeechFrameCount++;   // 封顶，防长会话溢出
        } else if (!mCapturing) {
            // ★ R14 ①：只用安静帧追踪底噪；比底噪高、又不是人声的帧只极慢上调（环境真变吵）
            if (rms < mNoiseFloor * QUIET_RATIO) {
                mNoiseFloor = mNoiseFloor * NOISE_SMOOTH_FACTOR + rms * (1.0f - NOISE_SMOOTH_FACTOR);
            } else {
                mNoiseFloor += (rms - mNoiseFloor) * SLOW_RISE;
            }
            if (mNoiseFloor < 100.0f) mNoiseFloor = 100.0f;
        }
        // ★ R14 ②：捕获窗内底噪冻结（上面的 else-if 不进），只记统计
        if (mCapturing && mTraceN < CAPTURE_TRACE_MAX) mTrace[mTraceN++] = Math.round(rms);

        // 软通道：仍须高于底噪一定比例，避免持续本底噪声把 KWS 放行判定污染成常开
        if (rms > mNoiseFloor * SOFT_FLOOR_RATIO && rms > SOFT_ABS_RMS) {
            mLastSoftSpeechTimestamp = now;
            if (mCapturing) mSoftFrames++;
        }

        // 能量门判定：瞬时为人声 或 距上次人声未超过回溯窗口
        return isSpeech || (now - mLastSpeechTimestamp <= BACKTRACK_WINDOW_MS);
    }

    /** 捕获窗内的人声阈值（窗外返回 KWS 硬门的值，仅供日志）。 */
    public synchronized float captureThreshold() {
        if (!mCapturing) return Math.max(HARD_ABS_RMS, mNoiseFloor * HARD_FLOOR_RATIO);
        return Math.max(CAPTURE_ABS_MIN, mFrozenFloor * CAPTURE_FLOOR_RATIO);
    }

    /**
     * KWS 放行用的「硬门」：最近 {@link #BACKTRACK_WINDOW_MS} 内有帧过了硬门（= R13 及以前 processFrame 返回值在捕获窗外的语义）。
     * 捕获窗内 processFrame 的返回值改按捕获窗判据，KWS 不能再拿它当硬门，改调这里。
     */
    public synchronized boolean hardRecent(long now) {
        return mLastHardAt > 0 && (now - mLastHardAt) <= BACKTRACK_WINDOW_MS;
    }

    private long mLastHardAt = 0L;

    public synchronized boolean isCapturing() {
        return mCapturing;
    }

    /** 本窗第一帧人声时刻（0 = 还没有）。 */
    public synchronized long getFirstSpeechTimestamp() {
        return mFirstSpeechAt;
    }

    /**
     * ★ R14：开捕获窗（应答音结束、断句计时起点那一刻调用）—— 快照并冻结底噪，清零本窗统计。
     * 同时做 {@link #arm()} 的事（人声计时对齐到此刻、人声帧清零）。
     */
    public synchronized void armCapture(long now) {
        armAt(now);
        mCapturing = true;
        mFrozenFloor = mNoiseFloor;
        mCaptureArmedAt = now;
        mTraceN = 0;
        mSoftFrames = 0;
    }

    /** ★ R14：收捕获窗（拿到命令 / 关窗 / 取消）—— 解冻底噪。幂等。 */
    public synchronized void endCapture() {
        mCapturing = false;
    }

    /**
     * ★ R14：续期判据要求「本窗人声帧够数」—— 10-15 第 1 次尝试就是一帧零星人声（1/4）触发续期，
     * 把窗拖到 16 s、缓冲早满，用户等不下去放弃。
     */
    public static boolean enoughSpeechToRenew(int speechFrames, int needFrames) {
        return speechFrames >= Math.max(1, needFrames);
    }

    /**
     * 本窗汇总（收窗时打一行日志）：冻结底噪、阈值、RMS p50/p90/max、人声帧、软人声帧，以及 RMS 序列（每帧 100 ms，
     * 最多 {@link #CAPTURE_TRACE_MAX} 帧）—— 一行就能判断是不是阈值问题，序列可直接贴进 JVM 回放测试。
     */
    public synchronized String captureSummary() {
        int n = mTraceN;
        int[] sorted = java.util.Arrays.copyOf(mTrace, n);
        java.util.Arrays.sort(sorted);
        int p50 = n == 0 ? 0 : sorted[n / 2];
        int p90 = n == 0 ? 0 : sorted[Math.min(n - 1, (int) (n * 0.9f))];
        int max = n == 0 ? 0 : sorted[n - 1];
        StringBuilder sb = new StringBuilder(64 + n * 4);
        sb.append("底噪(冻结)=").append(Math.round(mFrozenFloor))
                .append(" 阈值=").append(Math.round(Math.max(CAPTURE_ABS_MIN, mFrozenFloor * CAPTURE_FLOOR_RATIO)))
                .append(" 帧=").append(n).append(" p50=").append(p50).append(" p90=").append(p90).append(" max=").append(max)
                .append(" 人声帧=").append(mSpeechFrameCount).append(" 软人声帧=").append(mSoftFrames)
                .append(" rms=[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(mTrace[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * ★ 软能量判定（仅供 KWS 神经网命中后的放行使用）。
     * 与 processFrame 的返回值相比，下界更低、仍带底噪比例约束，
     * 用于覆盖“轻声细语 / 离手机较远”的唤醒场景。
     */
    public synchronized boolean isSoftSpeech() {
        long now = SystemClock.elapsedRealtime();
        return mLastSoftSpeechTimestamp > 0
                && (now - mLastSoftSpeechTimestamp <= BACKTRACK_WINDOW_MS);
    }

    public synchronized long getLastSpeechTimestamp() {
        return mLastSpeechTimestamp;
    }

    /**
     * **真正解除抑制、开始采集指令音频**时重起算人声计时。
     *
     * <p>⚠ 历史教训（两次踩坑，务必读完再改）：
     * <ol>
     *   <li><b>第一版挂在唤醒时刻</b>（{@code armCaptureWindow} 里直接调）：无效。
     *       应答音时长 1.6~1.7s，而静音阈值恰好也是 1.6s —— arm 记的是 T0，
     *       等 Acoustic Shield 解除抑制已是 T0+1600ms 之后，"距上次人声"默认就
     *       已经超阈值，解除抑制后<b>第一帧</b>即假断句（实测间隔 14ms）。</li>
     *   <li>所以本方法<b>必须</b>由"抑制解除"这一事件驱动（{@code mAckSoundEndAt} 到期
     *       或 ack 播放完成回调），而不是由开窗驱动。</li>
     * </ol>
     *
     * <p>语义：把"最后人声时刻"推到现在，使 1.6s 静音计时从采集恢复的那一帧起算。
     * 只影响断句计时起点，不动任何门限值 —— 灵敏度零回退。
     */
    public synchronized void arm() {
        armAt(SystemClock.elapsedRealtime());
    }

    private void armAt(long now) {
        mFirstSpeechAt = 0L;
        mLastSpeechTimestamp = now;
        mLastSoftSpeechTimestamp = now;
        // ★ 关键：同时清零"已检测到人声"的累积计数 —— 断句必须先真正听到人说话。
        mSpeechFrameCount = 0;
    }

    /**
     * 自 arm() 以来累计检测到的**人声帧数**。
     *
     * <p>断句判据不能只看"距上次人声多久"，还必须有"确实听到过一段人声"的前置条件 ——
     * 否则静音起点一被推后，任何没有语音的窗口都会在 1.6s 后误判成"用户说完了"，
     * 把近空音频送云端（云端对近空音频产生幻觉，回吐热词表原文）。
     *
     * <p>用途见 {@code WakeService} 的断句块：要求人声帧 ≥ {@code MIN_SPEECH_FRAMES}
     * 才允许断句。帧长 100ms，故 4 帧 ≈ 400ms 真实语音，足以排除"一拍即合"的假断句。
     */
    public synchronized int getSpeechFrameCount() {
        return mSpeechFrameCount;
    }

    /** 自上次 arm() 以来累计的人声帧数（100ms/帧）。 */
    private int mSpeechFrameCount = 0;

    // ================= 噪声底值对外暴露 =================

    /**
     * 当前环境噪声底值（RMS，与 {@link #processFrame} 内部平滑追踪的同一个量）。
     *
     * <p><b>用途</b>：对标小米小爱"嘈杂场景临时调高档"、Open-XiaoAI"环境自适应切换配置"
     * 以及 Siri/Google 的动态灵敏度策略 —— 唤醒灵敏度不应是一条固定阈值，
     * 而应随环境噪声动态调整：
     * <ul>
     *   <li><b>安静环境</b>（卧室/办公室）：收紧一阶阈值，最大化抑制误唤醒
     *       （此时人声信噪比高，收紧也不会漏掉真唤醒）。</li>
     *   <li><b>嘈杂环境</b>（地铁/商场）：放宽一阶阈值，保住召回
     *       （此时宁可多误报，也不能"喊了没反应"——误报还有二阶声纹兜底）。</li>
     * </ul>
     *
     * <p>这是本工程"两级判定"架构的正向延伸：一阶（KWS）负责在噪声下保住召回，
     * 二阶（声纹）负责把放宽带来的误报拦掉。
     *
     * <p>取值语义（16-bit PCM RMS；硬门限 {@link #HARD_ABS_RMS}=350 是"是否人声"的分界，
     * 故只有**低于 350 的稳态电平**才会被当作环境底噪累积 —— 高于它的会被判成人声、
     * 不进底噪平滑分支）：
     * <ul>
     *   <li>安静室内：约 100~200（底噪下限 100）</li>
     *   <li>普通居家/办公：约 200~320</li>
     *   <li>嘈杂（开窗临街、家电视作背景音）：约 320~350（逼近硬门限）</li>
     * </ul>
     * 分级刻度据此设定，让"安静 / 普通"这个最常用的界（200）落在真实分布中。
     */
    public synchronized float getNoiseFloor() {
        return mNoiseFloor;
    }

    /**
     * 噪声分级：0=安静 / 1=普通 / 2=嘈杂 / 3=很吵。
     *
     * <p>阈值依据见 {@link #getNoiseFloor()} 的取值语义。分级而非直接用连续值，
     * 是为了让灵敏度调整**阶梯化、可预测**（避免阈值随噪声连续抖动造成的
     * "有时候灵敏有时候迟钝"的不确定感），也与小米"低/中/高"三档的交互一致。
     *
     * <p><b>注意</b>：本分级只反映**持续低于硬门限的稳态背景电平**。若环境噪声
     * 高到超过 350，它会被硬门当人声处理、不再进底噪统计，因此分级上界实际
     * 被封在"接近 350"附近 —— 这对本用途是**正确**的：一旦背景逼近硬门限，
     * 信噪比已恶化到该放宽灵敏度了（落在分档 2）。
     */
    public synchronized int getNoiseLevel() {
        float nf = mNoiseFloor;
        if (nf < 200f) return 0;      // 安静
        if (nf < 320f) return 1;      // 普通
        if (nf < 345f) return 2;      // 嘈杂
        return 3;                     // 很吵
    }

    public synchronized void reset() {
        mCapturing = false;
        mFirstSpeechAt = 0L;
        mTraceN = 0;
        mSoftFrames = 0;
        mLastSpeechTimestamp = 0;
        mLastSoftSpeechTimestamp = 0;
        mSpeechFrameCount = 0;
        mNoiseFloor = 200.0f;
    }
}
