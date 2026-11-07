package com.onesay.assistant.v2.wake;

/**
 * ★ D1（lane-d，2026-10-22）：按键唤醒的「提示音期间预录」缓存。纯 Java，JVM 可测；WakeService 只按它的结果执行。
 *
 * <h3>解决什么</h3>
 * R14 按键唤醒的上传起点是「唤醒 +300 ms」（{@code WAKE_LOCAL_GUARD_MS}）。用户在提示音一响就开口时，前 0.3 s 的音频不进缓冲，
 * 实测首字丢失最多约 0.29 s（「讲个笑话」→「个笑话」）。D1：按键唤醒时，从<b>唤醒那一刻</b>起就把麦克风帧收进本缓存
 * （约 300 ms，正好补上 [唤醒, 唤醒+300 ms) 这段），等第一块 R14 捕获帧到来时，用 {@link ChimeEchoGate} 剔除提示音回声，
 * 有人声才整段接到捕获缓冲 / 流式会话最前面；没人声就整段丢弃 —— 此时上传的音频与 R14 逐字节一致。
 *
 * <h3>★ 隐私（不可改）</h3>
 * <ul>
 *   <li><b>预录只在按键之后开始，绝不往前挪。</b>起点 = {@link #startAt}(唤醒时刻, …) ≥ 唤醒时刻。按键唤醒的「唤醒时刻」是守护报来
 *       按键事件之后（守护 v8 是松手那一刻，R14 实测按下 → 唤醒 252–299 ms），所以起点一定晚于按下。</li>
 *   <li>一块麦克风帧如果跨过起点，起点之前的样本<b>直接丢掉</b>，不进缓存、不参与回声分析（{@link #offer}）。</li>
 *   <li>不读常听滚动缓冲（WakeService.mWakeRollingBuf，那里有按键之前的音频）—— 只收唤醒之后 micLoop 新读到的帧。</li>
 *   <li>只给按键唤醒用（{@link #allowed}）。唤醒词唤醒不预录：唤醒词尾巴就在那几百毫秒里，起点保持 R14 的 450 / 1100 ms。
 *       「✦ 语音」继续聆听（来源 voice-ui）也不预录。</li>
 *   <li>{@code preroll_ms} 调参只能让预录<b>更短</b>（起点往后挪），上限就是 R14 的隐私起点；永远不会早于唤醒时刻。</li>
 *   <li>开关 {@code key_preroll=0}：{@link #arm} 不会被调用，行为与 R14 完全一致。</li>
 * </ul>
 *
 * <h3>不改的 R14 规则</h3>
 * 预录帧不喂 {@link EnergyGate}、KWS、声纹滚动缓冲 —— R14 的「本地能量门至少 3 帧人声」只认提示音之后的真实帧，照旧；
 * 提示音尾巴被转成「嗯」的处理（应答音 turn 当空 turn）也照旧。预录这段自己的判定由 {@link ChimeEchoGate} 做：只有提示音时整段丢弃。
 */
public final class KeyPreRoll {

    /** 默认预录长度 = R14 按键隐私起点（WAKE_LOCAL_GUARD_MS），正好补上丢掉的那 300 ms。 */
    public static final long DEFAULT_PRE_ROLL_MS = 300;

    private final int mSampleRate;

    private boolean mArmed;
    private long mWakeAt;
    private long mStartAt;
    private long mEndAt;
    private float mNoiseFloor;
    private ChimeEchoGate mGate;
    private short[] mBuf = new short[0];
    private int mLen;
    private int mDroppedBeforeStart;

    public KeyPreRoll(int sampleRate) {
        mSampleRate = sampleRate;
    }

    /** 只有按键唤醒、且开关开着才预录。 */
    public static boolean allowed(boolean enabled, boolean keySource) {
        return enabled && keySource;
    }

    /**
     * 预录起点（elapsedRealtime 毫秒）：{@code captureFrom − preRollMs}，但<b>永远不早于唤醒时刻</b>、不晚于 captureFrom。
     * preRollMs ≤ 0 → 等于 captureFrom（= 不预录）。
     */
    public static long startAt(long wakeAt, long captureFrom, long preRollMs) {
        if (preRollMs <= 0) return captureFrom;
        long s = captureFrom - preRollMs;
        if (s < wakeAt) s = wakeAt;
        if (s > captureFrom) s = captureFrom;
        return s;
    }

    /**
     * 开始一次预录（armCaptureWindow 里、按键唤醒时调用）。
     *
     * @param wakeAt      唤醒时刻（= mLastWakeAt，按键事件之后）
     * @param captureFrom R14 的上传起点（唤醒 +300 ms），预录收到这里为止，之后的帧归 R14
     * @param preRollMs   预录长度（调参 preroll_ms，默认 300）
     * @param noiseFloor  唤醒前的底噪（EnergyGate.getNoiseFloor()，提示音还没进来）
     */
    public synchronized void arm(long wakeAt, long captureFrom, long preRollMs, ChimeEchoGate gate, float noiseFloor) {
        mWakeAt = wakeAt;
        mStartAt = startAt(wakeAt, captureFrom, preRollMs);
        mEndAt = captureFrom;
        mGate = gate;
        mNoiseFloor = noiseFloor;
        mLen = 0;
        mDroppedBeforeStart = 0;
        int cap = (int) ((mEndAt - mStartAt + 200) * mSampleRate / 1000);   // + 两块读缓冲的余量
        if (mBuf.length < cap) mBuf = new short[Math.max(cap, 0)];
        mArmed = gate != null && mEndAt > mStartAt;
    }

    public synchronized void disarm() {
        mArmed = false;
        mLen = 0;
    }

    public synchronized boolean armed() {
        return mArmed;
    }

    public synchronized long startAt() {
        return mStartAt;
    }

    public synchronized long wakeAt() {
        return mWakeAt;
    }

    public synchronized int bufferedSamples() {
        return mLen;
    }

    public synchronized int droppedBeforeStart() {
        return mDroppedBeforeStart;
    }

    /**
     * 这一块麦克风帧是不是预录段的（块尾时刻落在 (起点, captureFrom] 内）。跨过 captureFrom 的那块归 R14（R14 原本就按块尾判）。
     */
    public synchronized boolean wants(long chunkEndAt) {
        return mArmed && chunkEndAt > mStartAt && chunkEndAt <= mEndAt;
    }

    /**
     * 收一块麦克风帧。块内样本按「块尾 = chunkEndAt」均匀倒推时刻，<b>早于起点的样本丢掉</b>（隐私：起点之前一个样本都不留）。
     *
     * @return 实际收下的样本数
     */
    public synchronized int offer(short[] buf, int read, long chunkEndAt) {
        if (!wants(chunkEndAt) || buf == null || read <= 0) return 0;
        read = Math.min(read, buf.length);
        long chunkMs = (long) read * 1000 / mSampleRate;
        long chunkStartAt = chunkEndAt - chunkMs;
        int skip = 0;
        if (chunkStartAt < mStartAt) {
            // 向上取整：宁可多丢一个样本，也不收起点之前的
            skip = (int) Math.min(read, ((mStartAt - chunkStartAt) * mSampleRate + 999) / 1000);
        }
        mDroppedBeforeStart += skip;
        int n = read - skip;
        if (n <= 0) return 0;
        if (mLen + n > mBuf.length) {
            short[] nb = new short[Math.max(mBuf.length * 2, mLen + n)];
            System.arraycopy(mBuf, 0, nb, 0, mLen);
            mBuf = nb;
        }
        System.arraycopy(buf, skip, mBuf, mLen, n);
        mLen += n;
        return n;
    }

    /** {@link #flush} 的结果。 */
    public static final class Flushed {
        /** 要接到捕获缓冲最前面的 PCM；null = 不接（没人声 / 没收到帧 / 未启用）。 */
        public final short[] pcm;
        public final ChimeEchoGate.Result gate;
        public final int bufferedMs;
        public final long startAt;
        public final long wakeAt;

        Flushed(short[] pcm, ChimeEchoGate.Result gate, int bufferedMs, long startAt, long wakeAt) {
            this.pcm = pcm;
            this.gate = gate;
            this.bufferedMs = bufferedMs;
            this.startAt = startAt;
            this.wakeAt = wakeAt;
        }

        public boolean emitted() { return pcm != null && pcm.length > 0; }
    }

    /**
     * 第一块 R14 捕获帧到来时调用（或窗关掉时，context 传 null）：剔除提示音回声，决定接不接，然后解除预录。
     *
     * @param context    紧接着的第一块 R14 帧，只当频谱分析的右侧上下文（不输出）；可为 null
     * @param contextLen 上下文样本数
     * @return 永不为 null；未启用 / 已 flush 过时 {@code pcm == null, gate == null}
     */
    public synchronized Flushed flush(short[] context, int contextLen) {
        if (!mArmed) return new Flushed(null, null, 0, mStartAt, mWakeAt);
        mArmed = false;
        int bufferedMs = (int) ((long) mLen * 1000 / mSampleRate);
        // ★ 隐私自检：起点不能早于唤醒时刻（startAt() 已保证；这里再验一次，防后人改坏）
        if (mStartAt < mWakeAt || mLen == 0 || mGate == null) {
            mLen = 0;
            return new Flushed(null, null, bufferedMs, mStartAt, mWakeAt);
        }
        int ctx = (context == null) ? 0 : Math.max(0, Math.min(contextLen, context.length));
        short[] all = new short[mLen + ctx];
        System.arraycopy(mBuf, 0, all, 0, mLen);
        if (ctx > 0) System.arraycopy(context, 0, all, mLen, ctx);
        ChimeEchoGate.Result r = mGate.process(all, all.length, mLen, mNoiseFloor);
        mLen = 0;
        return new Flushed(r.pcm, r, bufferedMs, mStartAt, mWakeAt);
    }
}
