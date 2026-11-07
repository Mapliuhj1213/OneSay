package com.onesay.assistant.v2.wake;

/**
 * ★ D1（lane-d，2026-10-22）：按键预录段的「提示音回声剔除」—— 已知提示音频带陷除 + 帧级能量门。纯 Java，JVM 可测。
 *
 * <h3>用在哪</h3>
 * 只处理按键唤醒后、提示音一开始那约 300 ms 的预录缓存（见 {@link KeyPreRoll}）。R14 的捕获窗（唤醒 +300 ms 之后）一帧都不经过这里，
 * 行为保持 R14 原样。
 *
 * <h3>为什么选「频带陷除 + 能量门」，不选 AEC / 波形参考消除</h3>
 * <ul>
 *   <li><b>AEC</b>：bootEngine 早就给 AudioRecord 挂了平台 AcousticEchoCanceler，可 R14 实测提示音尾巴照样漏进来
 *       （开窗头 1–2 帧 RMS 1400–2800）。而且自适应回声消除要几百毫秒收敛，预录段恰好是提示音刚开始的 300 ms —— 最没收敛的时候。
 *       效果全看机型 DSP，离线也没法验证。</li>
 *   <li><b>按已知波形做时域参考消除</b>（NLMS 之类）：要知道提示音在麦克风里的精确起点（MediaPlayer prepare / 起播延迟每次不同）、
 *       扬声器到麦克风的传递函数，小喇叭还有削波失真；start_tone.ogg 在手机上还得先解码。对不齐就消不掉，残差反而像「嗯」。</li>
 *   <li><b>本方案</b>：用提示音<b>已知的频谱</b>（离线从波形算出它的能量集中在哪些频点）做频带陷除 —— 不需要时间对齐，
 *       也不怕起播延迟抖动；再对陷除后剩下的能量做帧级门：只有频带外仍有明显能量、而且不只是提示音的泄漏/谐波时才算人声。
 *       整段预录人声帧不足 {@code minFrames} 就<b>整段丢弃</b>，于是「只有提示音」时输出与 R14 逐字节一致，提示音本身不可能被转成「嗯」。
 *       代价：预录段里 500–1060 Hz（原提示音）/ 780–970 + 1220–1400 Hz（短提示音）被挖掉，首字音色略薄，但离线云识别全部认得。</li>
 * </ul>
 *
 * <h3>算法</h3>
 * STFT（N=512 / hop=256 / sqrt-Hann 分析+合成，50 % 重叠完美重建）。右侧可带一块上下文（下一块 R14 帧），只做分析不输出。每帧：
 * <ol>
 *   <li>residual = 200–4000 Hz 内、提示音掩码<b>之外</b>的等效 RMS；masked = 掩码内的等效 RMS；</li>
 *   <li>人声帧 ⇔ residual &gt; max(absMin, 底噪×floorRatio) 且 residual &gt; leak×masked（提示音的混响 / 谐波泄漏只有掩码内能量的一成左右）；</li>
 *   <li>人声帧前后各延 hang 帧（保辅音起头和字尾），其余帧整帧置零；保留帧的掩码频点乘 atten（默认 0）；</li>
 *   <li>放行判定（否则返回 null，整段丢弃）：
 *     <ul>
 *       <li><b>窗内</b>最长连续人声帧段 ≥ minFrames —— 只数预录窗内的帧（帧中心落在输出段内），右侧上下文帧<b>不计</b>。
 *           提示音音符起音的瞬态偶尔有孤立 1–2 帧越线，窗内连续 3 帧不会；</li>
 *       <li>或者<b>跨界</b>（D1-5，抢话的字跨过预录边界）：一段连续人声贴着预录窗末尾（窗内尾段 ≥ minFrames−1 帧），
 *           并且在右侧上下文里<b>自己</b>又连续 ≥ minFrames 帧 —— 窗外那部分单独就够得上人声，才算「窗内的已经确认是人声」。</li>
 *     </ul>
 *     ★ 10-23 bug D-1：原来把「窗内 2 帧 + 窗外 1 帧」也凑成 3 帧放行，短提示音尾音正好跨在边界上，只按键时约 2/10 误接。
 *     ★ 10-23 bug LB-1：「窗内够帧」这条另要求<b>窗外有续段</b>：右侧上下文里算作续段的人声帧 ≥ ctxMinFrames（默认 2）；
 *     续段只有 1 帧时窗内段峰值要 ≥ {@link #LOUD_RATIO}×门；窗内只有 minFrames−1 帧时续段要 ≥ ctxMinFrames+1。
 *     贴着窗末的短段（窗内尾段 + 窗外续段合计 ≤ {@link #BURST_MAX_FRAMES}）溢出到窗外的那几帧不算续段。
 *     原版提示音 start_tone 起音的宽带瞬态在窗内后段连出 3–4 帧、窗外 0 帧（真机 12 次 1 次「最长连续 4」误接）；
 *     真人抢话会接着说下去。跨界规则本来就要求窗外续段 ≥ minFrames，不受影响。没有上下文（窗关时 flush 传 null）不适用。</li>
 * </ol>
 * 本类不碰 {@link EnergyGate}：预录段<b>不计入</b> R14 的「本地能量门至少 3 帧人声」—— 那条规则只认提示音结束后的真实帧，保持不变。
 */
public final class ChimeEchoGate {

    public static final int SAMPLE_RATE = 16000;
    public static final int N = 512;
    public static final int HOP = 256;
    public static final int BINS = N / 2 + 1;

    // ---- 默认参数 ----
    public static final float DEF_FLOOR_RATIO = 1.8f;     // 与 EnergyGate.CAPTURE_FLOOR_RATIO 一致
    public static final float DEF_ABS_MIN = 220f;         // 与 EnergyGate.CAPTURE_ABS_MIN 一致
    public static final float DEF_LEAK = 0.35f;
    /**
     * 窗内最长连续段 ≥ 4 × 16 ms（跨界时窗外续段也要 ≥ 4）。★ 10-23 D-1 由 3 调到 4：真机只按键时，短提示音第二个音起音
     * 9/10 次有 2 帧越线、第 3 帧残差已到门值的 0.6–0.9 倍；原版提示音 5/10 次窗末有 2 帧越线 —— 门限 3 只差一帧。
     * 离线（R14 wav，唤醒后 50 / 150 ms 抢话）调到 4 首字全部保留；调到 5 会丢 joke@140–150ms，所以停在 4。
     */
    public static final int DEF_MIN_FRAMES = 4;
    public static final int DEF_HANG = 2;
    /**
     * ★ 10-23 bug LB-1：「窗内够帧」放行还要求右侧上下文（第一块 R14 帧，约 100 ms ≈ 6 帧）里<b>自己</b>有连续 ≥ 这么多帧人声 ——
     * 真人抢话几乎总会接着说下去，提示音起音的宽带瞬态到了窗外是 0 帧。0 = 关掉这条（回到 D-1 的判定）。
     * 跨界规则（D1-5）本来就要求窗外续段 ≥ minFrames，不受影响；没有上下文（窗已关、flush 传 null）时这条不适用。
     * 默认 2 而不是 1：离线合成里提示音回声自己在窗外偶尔孤立越线 1 帧（音符起音），1 帧的续段挡不住；
     * 真机抢话窗外续段 4–7 帧（fixD1 跨界 2+4、1+7、3+6、3+4）。取值和代价（LB-1）。
     */
    public static final int DEF_CTX_MIN_FRAMES = 2;
    /** 真机提示音起音瞬态的最长帧数上限（含溢出到上下文的部分）：贴窗末的段合计不超过它时，溢出帧不算「续段」。 */
    public static final int BURST_MAX_FRAMES = 5;
    /**
     * 续段只有 1 帧时，窗内段峰值至少要是门值的这么多倍才放行（真机 10-23：原版 / 短提示音起音瞬态峰值 1.3–1.9 倍门，
     * 抢话字头 2.5–9 倍）。
     */
    public static final double LOUD_RATIO = 2.2;
    public static final float DEF_ATTEN = 0f;
    public static final int BAND_LO_HZ = 200;
    public static final int BAND_HI_HZ = 4000;

    /**
     * 原版提示音 start_tone.ogg（R13b，0.42 s）的掩码频点（Hz，bin 宽 31.25 Hz）。
     * 由 {@link #maskFromChime}(start_tone 解码到 16 kHz, 0.97, 2) 离线算出：覆盖 97 % 能量的频点再左右各扩 2 个 bin。
     * 单测 ChimeEchoGateTest 用 fixture 重算并与这张表比对，换提示音时测试会提醒同步更新。
     */
    public static final int START_TONE_LO_HZ = 500;
    public static final int START_TONE_HI_HZ = 1062;

    private final boolean[] mMask;
    private final float mFloorRatio, mAbsMin, mLeak, mAtten;
    private final int mMinFrames, mHang, mCtxMinFrames;

    public ChimeEchoGate(boolean[] mask, float floorRatio, float absMin, float leak, int minFrames, int hang, float atten) {
        this(mask, floorRatio, absMin, leak, minFrames, hang, atten, DEF_CTX_MIN_FRAMES);
    }

    /** @param ctxMinFrames 窗内放行时，右侧上下文里自己要有的最长连续人声帧（LB-1）；0 = 不要求 */
    public ChimeEchoGate(boolean[] mask, float floorRatio, float absMin, float leak, int minFrames, int hang, float atten, int ctxMinFrames) {
        if (mask == null || mask.length != BINS) throw new IllegalArgumentException("mask length must be " + BINS);
        mMask = mask.clone();
        mFloorRatio = floorRatio > 0 ? floorRatio : DEF_FLOOR_RATIO;
        mAbsMin = absMin > 0 ? absMin : DEF_ABS_MIN;
        mLeak = leak >= 0 ? leak : DEF_LEAK;
        mMinFrames = Math.max(1, minFrames);
        mHang = Math.max(0, hang);
        mAtten = Math.max(0f, Math.min(1f, atten));
        mCtxMinFrames = Math.max(0, ctxMinFrames);
    }

    public float leak() { return mLeak; }

    public int minFrames() { return mMinFrames; }

    public int ctxMinFrames() { return mCtxMinFrames; }

    /**
     * 由调参（WakeTuning 的 preroll_* 键，均为整数）构造，带上下界保护：
     * leakPct 5–200、minFrames 2–10（<b>至少 2 帧</b>，防有人调成 1 让孤立瞬态放行）、hang 0–5、absMin 50–3000、floorPct 100–500、attenPct 0–100。
     *
     * @param shortChime true = 短提示音掩码；false = 原版 start_tone 掩码
     */
    public static ChimeEchoGate fromParams(boolean shortChime, int leakPct, int minFrames, int hang, int absMin, int floorPct, int attenPct) {
        return fromParams(shortChime, leakPct, minFrames, hang, absMin, floorPct, attenPct, DEF_CTX_MIN_FRAMES);
    }

    /** 同上，外加 ctxMinFrames 0–8（LB-1；0 = 关掉「窗外要有续段」这条）。 */
    public static ChimeEchoGate fromParams(boolean shortChime, int leakPct, int minFrames, int hang, int absMin, int floorPct, int attenPct, int ctxMinFrames) {
        boolean[] mask = shortChime ? shortChimeMask() : startToneMask();
        return new ChimeEchoGate(mask,
                clamp(floorPct, 100, 500) / 100f,
                clamp(absMin, 50, 3000),
                clamp(leakPct, 5, 200) / 100f,
                clamp(minFrames, 2, 10),
                clamp(hang, 0, 5),
                clamp(attenPct, 0, 100) / 100f,
                clamp(ctxMinFrames, 0, 8));
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static ChimeEchoGate withDefaults(boolean[] mask) {
        return new ChimeEchoGate(mask, DEF_FLOOR_RATIO, DEF_ABS_MIN, DEF_LEAK, DEF_MIN_FRAMES, DEF_HANG, DEF_ATTEN);
    }

    /** 处理结果。{@link #pcm} == null 表示整段丢弃（人声帧不足）。 */
    public static final class Result {
        public final short[] pcm;
        public final int speechFrames;
        /** 预录窗内的最长连续人声帧段（判定依据；★ D-1 起只数窗内帧，不含右侧上下文）。 */
        public final int longestRun;
        /** 贴着窗末尾的连续人声帧数（窗内部分）；0 = 窗末帧不是人声。 */
        public final int crossWin;
        /** 跨界段在右侧上下文里继续连着的帧数（只在 crossWin &gt; 0 时有意义）。 */
        public final int crossCtx;
        /** 是否靠「跨界」规则放行（窗内最长连续不够 minFrames）。 */
        public final boolean viaCross;
        /** 右侧上下文帧数（0 = 没有上下文，LB-1 那条不适用）。 */
        public final int ctxFrames;
        /** ★ LB-1：右侧上下文里自己的最长连续人声帧（不管贴不贴窗末，诊断用）。 */
        public final int ctxRun;
        /** ★ LB-1：算作「接着说下去」的上下文人声帧数（贴窗末短段的溢出不算），放行要 ≥ ctxMinFrames。 */
        public final int follow;
        /** ★ LB-1：窗内已够 minFrames，但窗外没有续段（ctxRun &lt; ctxMinFrames）而丢弃 —— 典型就是提示音起音瞬态。 */
        public final boolean noFollowUp;
        /** 窗内够 minFrames 的段里残差峰值 / 门值（0 = 没有够帧的段）。 */
        public float loud;
        public final int frames;
        public final int maxResidual;
        public final int maxMasked;
        public final float threshold;
        /** 判定用的泄漏比（日志里逐帧标 * 用）。 */
        public final float leak;
        /** 每帧（16 ms）的残差 / 掩码等效 RMS，上机日志和调参用。前 {@link #frames} 个是窗内帧，后 {@link #ctxFrames} 个是上下文帧（★ LB-1 起一并记录）。 */
        public final int[] residTrace;
        public final int[] maskedTrace;

        Result(short[] pcm, int speechFrames, int longestRun, int frames, int maxResidual, int maxMasked, float threshold, float leak, int[] residTrace, int[] maskedTrace) {
            this(pcm, speechFrames, longestRun, 0, 0, false, 0, 0, 0, false, frames, maxResidual, maxMasked, threshold, leak, residTrace, maskedTrace);
        }

        Result(short[] pcm, int speechFrames, int longestRun, int crossWin, int crossCtx, boolean viaCross, int ctxFrames, int ctxRun, int follow, boolean noFollowUp,
               int frames, int maxResidual, int maxMasked, float threshold, float leak, int[] residTrace, int[] maskedTrace) {
            this.pcm = pcm;
            this.ctxFrames = ctxFrames;
            this.ctxRun = ctxRun;
            this.follow = follow;
            this.noFollowUp = noFollowUp;
            this.speechFrames = speechFrames;
            this.longestRun = longestRun;
            this.crossWin = crossWin;
            this.crossCtx = crossCtx;
            this.viaCross = viaCross;
            this.frames = frames;
            this.maxResidual = maxResidual;
            this.maxMasked = maxMasked;
            this.threshold = threshold;
            this.leak = leak;
            this.residTrace = residTrace == null ? new int[0] : residTrace;
            this.maskedTrace = maskedTrace == null ? new int[0] : maskedTrace;
        }

        /** 逐帧「残差/掩码」紧凑串，如 "12/80 340/2900* …"（* = 判为人声）。 */
        public String trace() {
            return trace(leak);
        }

        /** 窗内帧和上下文帧之间用「 | 」隔开。 */
        public String trace(float leak) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < residTrace.length; i++) {
                if (i > 0) sb.append(i == frames ? " | " : " ");
                sb.append(residTrace[i]).append('/').append(maskedTrace[i]);
                if (residTrace[i] > threshold && residTrace[i] > leak * maskedTrace[i]) sb.append('*');
            }
            return sb.toString();
        }

        public boolean kept() { return pcm != null; }

        @Override public String toString() {
            return (kept() ? "保留" : "丢弃") + " 人声帧 " + speechFrames + "/" + frames + "（最长连续 " + longestRun
                    + (crossWin > 0 ? "，跨界 " + crossWin + "+" + crossCtx + (viaCross ? " 放行" : "") : "")
                    + (ctxFrames > 0 ? "，窗外续 " + follow + (noFollowUp ? " 无续段" : "") : "")
                    + (loud > 0 ? String.format(java.util.Locale.US, "，峰/门 %.1f", loud) : "") + "）"
                    + " 残差峰 " + maxResidual + " 掩码峰 " + maxMasked + " 门 " + (int) threshold;
        }
    }

    /**
     * @param in         预录 PCM（16 kHz 单声道）
     * @param len        有效样本数
     * @param noiseFloor 唤醒前的底噪 RMS（{@link EnergyGate#getNoiseFloor()}；≤0 时只用 absMin）
     */
    public Result process(short[] in, int len, float noiseFloor) {
        return process(in, len, len, noiseFloor);
    }

    /**
     * 带右侧上下文的版本：{@code in[0, outLen)} 是要处理并输出的预录段，{@code in[outLen, len)} 只当频谱分析的右侧上下文
     * （= 紧接着的第一块 R14 捕获帧），不参与人声判定、不输出。没有上下文时（窗已关）在分析副本末尾做 16 ms 渐弱，
     * 免得硬截断的频谱飞溅被当成人声。左端做 8 ms 渐强（起点之前的音频永远不用，见 {@link KeyPreRoll} 隐私说明）。
     */
    public Result process(short[] in, int len, int outLen, float noiseFloor) {
        len = Math.max(0, Math.min(len, in == null ? 0 : in.length));
        outLen = Math.max(0, Math.min(outLen, len));
        float thr = Math.max(mAbsMin, noiseFloor > 0 ? noiseFloor * mFloorRatio : 0f);
        if (outLen == 0) return new Result(null, 0, 0, 0, 0, 0, thr, mLeak, null, null);
        boolean hasCtx = len > outLen;

        // 前补 HOP、后补 N 个零：每个原始样本都被两帧覆盖，重建无边缘衰减
        int padLen = HOP + len + N;
        double[] pad = new double[padLen];
        for (int i = 0; i < len; i++) pad[HOP + i] = in[i];
        int fadeIn = SAMPLE_RATE * 8 / 1000;
        for (int i = 0; i < fadeIn && i < len; i++) pad[HOP + i] *= 0.5 - 0.5 * Math.cos(Math.PI * i / fadeIn);
        if (len == outLen) {
            int fadeOut = SAMPLE_RATE * 16 / 1000;
            for (int i = 0; i < fadeOut && i < len; i++) pad[HOP + len - 1 - i] *= 0.5 - 0.5 * Math.cos(Math.PI * i / fadeOut);
        }
        int nf = (padLen - N) / HOP + 1;
        // 参与判定的帧：帧中心落在输出段内（帧 k 覆盖原始样本 [k·HOP − HOP, k·HOP + HOP)，中心 = k·HOP）
        int decideFrames = 0;
        for (int k = 0; k < nf; k++) if (k * HOP < outLen) decideFrames = k + 1;
        // 上下文帧：帧中心落在 [outLen, len) 的真实上下文样本里（之后的帧大半是补零，不算）
        int ctxEnd = decideFrames;
        if (hasCtx) for (int k = decideFrames; k < nf; k++) if (k * HOP < len) ctxEnd = k + 1;
        int ctxFrames = ctxEnd - decideFrames;

        double[] win = sqrtHann();
        int lo = (int) Math.ceil(BAND_LO_HZ * (double) N / SAMPLE_RATE);
        int hi = (int) Math.floor(BAND_HI_HZ * (double) N / SAMPLE_RATE);

        double[][] re = new double[nf][];
        double[][] im = new double[nf][];
        boolean[] speech = new boolean[nf];
        int[] rTrace = new int[ctxEnd];
        int[] mTrace = new int[ctxEnd];
        int nSpeech = 0;
        double maxR = 0, maxM = 0;
        for (int k = 0; k < nf; k++) {
            double[] r = new double[N];
            double[] m = new double[N];
            int off = k * HOP;
            for (int i = 0; i < N; i++) r[i] = pad[off + i] * win[i];
            fft(r, m, false);
            re[k] = r;
            im[k] = m;
            double pr = 0, pm = 0;
            for (int b = lo; b <= hi; b++) {
                double p = r[b] * r[b] + m[b] * m[b];
                if (mMask[b]) pm += p; else pr += p;
            }
            for (int b = 0; b < BINS; b++) {   // 掩码可能延伸到 band 之外（如低频），一并计入 masked
                if (mMask[b] && (b < lo || b > hi)) pm += r[b] * r[b] + m[b] * m[b];
            }
            double resid = equivRms(pr);
            double masked = equivRms(pm);
            if (k < rTrace.length) rTrace[k] = (int) resid;
            if (k < mTrace.length) mTrace[k] = (int) masked;
            if (k < decideFrames && resid > maxR) maxR = resid;
            if (k < decideFrames && masked > maxM) maxM = masked;
            if (resid > thr && resid > mLeak * masked) {
                speech[k] = true;              // 上下文帧也判，但只用于跨界续段（见下），不计入窗内最长连续
                if (k < decideFrames) nSpeech++;
            }
        }
        // 「连续」人声帧：提示音每个音符起音的瞬态（叠加喇叭削波谐波）偶尔会让孤立的 1–2 帧越过泄漏比，但不会连成串；
        // 真人开口一个字至少 100 ms ≈ 6 帧连续。
        // ★ D-1（10-23）：连续段<b>只在预录窗内</b>数。以前窗内段可以延伸进右侧上下文一起数，短提示音的尾音跨在边界上时
        //   「窗内 2 帧 + 窗外 1 帧」凑成 3 帧放行 → 只按键 2/10 误接。
        int run = 0, longest = 0;
        for (int k = 0; k < decideFrames; k++) {
            run = speech[k] ? run + 1 : 0;
            if (run > longest) longest = run;
        }
        // D1-5 跨界保留：字跨过预录边界时窗内只有开头 1–2 帧（如 mail@150ms「查」只有 2 帧在窗内）。
        // 窗内尾段 = 贴着窗末尾的连续人声（上面循环结束时的 run）；窗外续段 = 上下文里紧接着连续的人声帧。
        // 要求窗外续段<b>自己</b>就有 minFrames 帧（单独够得上人声），窗内尾段 ≥ minFrames−1：提示音尾巴越线最多孤立 1–2 帧，
        // 凑不出「窗内 2 + 窗外 3」。起点就在上下文里的段（预录之后才开口）crossWin = 0，不算 —— 那段话本来就在 R14 的帧里。
        int crossWin = run, crossCtx = 0;
        if (crossWin > 0) {
            for (int k = decideFrames; k < nf && speech[k]; k++) crossCtx++;
        }
        boolean crossOk = crossWin >= 1 && crossCtx >= mMinFrames;
        // ★ LB-1（10-23）：窗内够帧还不够 —— 右侧上下文里得有人声续段（自己连续 ≥ ctxMinFrames 帧，不要求贴着窗末）。
        //   真机原版提示音只按键：start_tone 起音的宽带瞬态在窗末/窗内后段连出 3–4 帧，紧接着的上下文帧一帧都不越线
        //   （日志「窗末 3 帧 + 窗外 0 帧」、LB-1「最长连续 4」且无跨界）；真人抢话 300 ms 内开口，100 ms 后几乎总还在说。
        //   没有上下文（窗关时 flush 传 null）不适用，按 D-1 的判定。
        int ctxRun = 0, ctxSpeech = 0;
        for (int k = decideFrames, r = 0; k < ctxEnd; k++) {
            r = speech[k] ? r + 1 : 0;
            if (r > ctxRun) ctxRun = r;
            if (speech[k]) ctxSpeech++;
        }
        // 续段帧数 = 上下文里的人声帧数；但如果它们只是贴着窗末那一小段的「溢出」（窗内尾段 + 窗外续段合计 ≤ BURST_MAX_FRAMES，
        // 真机提示音瞬态最长 4 帧：「3+0」「2+1」「1+2」「4」），这几帧不算续段 —— 瞬态的尾巴不能替自己作证。
        int follow = ctxSpeech;
        int attached = Math.min(crossCtx, Math.max(0, ctxEnd - decideFrames));
        if (crossWin > 0 && crossWin + attached <= BURST_MAX_FRAMES) follow -= attached;
        // 窗内够 minFrames 的段里残差最响的一帧 / 门值（真机提示音起音瞬态只比门高 1.3–1.9 倍，抢话的字头 2.5 倍以上）
        double loud = 0;
        for (int k = 0, r = 0, pk = 0; k < decideFrames; k++) {
            if (speech[k]) { r++; pk = Math.max(pk, rTrace[k]); } else { r = 0; pk = 0; }
            if (r >= mMinFrames) loud = Math.max(loud, pk / (double) thr);
        }
        boolean ctxRule = hasCtx && ctxFrames > 0 && mCtxMinFrames > 0;
        // ★ 放行（有上下文时，满足其一）：
        //   ① 窗内 ≥ minFrames 且 续段 ≥ ctxMinFrames（默认 2）；
        //   ② 窗内 ≥ minFrames 且 续段 ≥ 1 且 段内峰值 ≥ LOUD_RATIO×门 —— 字间有停顿的抢话（「讲 / 个」：讲占满窗末，g 声母那 100 ms 回落，
        //      真机 so_3 续段只有 1 帧，峰值 2.7 倍门）；提示音瞬态不够响；
        //   ③ 窗内 ≥ minFrames−1 且 续段 ≥ ctxMinFrames+1 —— 字头刚开始窗就结束了、窗外接着说了好几帧（真机 mail「查」窗内 3 帧 + 窗外 3–6 帧）；
        //      提示音只按键时窗内 3 帧的那几次窗外都是 0 帧。
        boolean followUpOk = !ctxRule || follow >= mCtxMinFrames || (follow >= 1 && loud >= LOUD_RATIO);
        boolean winOk = longest >= mMinFrames && followUpOk;
        boolean headOk = ctxRule && mMinFrames > 2 && longest >= mMinFrames - 1 && follow >= mCtxMinFrames + 1;
        winOk = winOk || headOk;
        boolean viaCross = !winOk && crossOk;      // 跨界段的窗外续段 ≥ minFrames，本身就是「接着说下去」
        boolean noFollowUp = longest >= mMinFrames && !winOk && !viaCross;
        if (!winOk && !viaCross) {
            Result dr = new Result(null, nSpeech, longest, crossWin, crossCtx, false, ctxFrames, ctxRun, follow, noFollowUp, decideFrames, (int) maxR, (int) maxM, thr, mLeak, rTrace, mTrace);
            dr.loud = (float) loud;
            return dr;
        }
        boolean[] keep = new boolean[nf];
        for (int k = 0; k < nf; k++) {
            if (!speech[k]) continue;
            int a = Math.max(0, k - mHang), b = Math.min(nf - 1, k + mHang);
            for (int j = a; j <= b; j++) keep[j] = true;
        }
        // 上下文帧和输出段末尾重叠：沿用最后一个判定帧的取舍（再叠加上面 hang 的结果），保证末尾完美重建
        if (decideFrames > 0) for (int k = decideFrames; k < nf; k++) keep[k] = keep[k] || keep[decideFrames - 1];
        double[] out = new double[padLen];
        for (int k = 0; k < nf; k++) {
            if (!keep[k]) continue;
            double[] r = re[k], m = im[k];
            for (int b = 0; b < BINS; b++) {
                if (mMask[b]) {
                    r[b] *= mAtten; m[b] *= mAtten;
                    if (b > 0 && b < N / 2) { r[N - b] *= mAtten; m[N - b] *= mAtten; }
                }
            }
            fft(r, m, true);
            int off = k * HOP;
            for (int i = 0; i < N; i++) out[off + i] += r[i] * win[i];
        }
        short[] pcm = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            long v = Math.round(out[HOP + i]);
            pcm[i] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
        }
        Result kr = new Result(pcm, nSpeech, longest, crossWin, crossCtx, viaCross, ctxFrames, ctxRun, follow, false, decideFrames, (int) maxR, (int) maxM, thr, mLeak, rTrace, mTrace);
        kr.loud = (float) loud;
        return kr;
    }

    /** 单边功率和 → 时域等效 RMS（sqrt-Hann 窗：Σw² = N/2；单边计两倍）。 */
    private static double equivRms(double oneSidedPower) {
        return Math.sqrt(2.0 * oneSidedPower / ((double) N * N / 2.0));
    }

    // ==================== 掩码 ====================

    /** 频率区间 [loHz, hiHz] → 掩码（含两端所在 bin）。 */
    public static boolean[] maskFromBand(int loHz, int hiHz) {
        boolean[] m = new boolean[BINS];
        int a = (int) Math.round(loHz * (double) N / SAMPLE_RATE);
        int b = (int) Math.round(hiHz * (double) N / SAMPLE_RATE);
        for (int i = Math.max(0, a); i <= Math.min(BINS - 1, b); i++) m[i] = true;
        return m;
    }

    /** 原版提示音（start_tone.ogg）的掩码。 */
    public static boolean[] startToneMask() {
        return maskFromBand(START_TONE_LO_HZ, START_TONE_HI_HZ);
    }

    /** 短提示音（{@link Earcon#wakeChime()} 同参数、按 16 kHz 重新合成）的掩码 —— 纯 Java 现算，改 Earcon 参数会自动跟上。 */
    public static boolean[] shortChimeMask() {
        synchronized (ChimeEchoGate.class) {
            int v = WakeCue.version();
            if (sShortMask == null || sShortMaskVer != v) {   // 算一次缓存（每次按键开窗都要构造回声门，别在唤醒路径上反复做 FFT）
                // ★ int/1102：按实际播放的提示音现算（厂商原声 / 方案 A / 旧版；换来源 → version 变 → 重算）
                short[] w = WakeCue.at(SAMPLE_RATE);
                if (w == null || w.length < N) w = Earcon.chimeA(SAMPLE_RATE);
                sShortMask = maskFromChime(w, 0.97, 2);
                sShortMaskVer = v;
            }
            return sShortMask.clone();
        }
    }

    private static boolean[] sShortMask;
    private static int sShortMaskVer = -1;

    /**
     * 由已知提示音波形（16 kHz）算掩码：长时功率谱里覆盖 {@code cover} 比例能量的最少频点集合，再左右各扩 {@code dilate} 个 bin
     * （容忍混响展宽和重采样误差）。
     */
    public static boolean[] maskFromChime(short[] chime16k, double cover, int dilate) {
        double[] p = new double[BINS];
        double[] win = sqrtHann();
        int step = HOP / 2;
        for (int off = 0; off + N <= chime16k.length; off += step) {
            double[] r = new double[N], m = new double[N];
            for (int i = 0; i < N; i++) r[i] = chime16k[off + i] * win[i];
            fft(r, m, false);
            for (int b = 0; b < BINS; b++) p[b] += r[b] * r[b] + m[b] * m[b];
        }
        double total = 0;
        for (double v : p) total += v;
        boolean[] base = new boolean[BINS];
        if (total <= 0) return base;
        Integer[] order = new Integer[BINS];
        for (int i = 0; i < BINS; i++) order[i] = i;
        final double[] pp = p;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return Double.compare(pp[b], pp[a]); }
        });
        double acc = 0;
        for (int i = 0; i < BINS; i++) {
            base[order[i]] = true;
            acc += p[order[i]];
            if (acc / total >= cover) break;
        }
        boolean[] m = base.clone();
        for (int i = 0; i < BINS; i++) {
            if (!base[i]) continue;
            for (int d = 1; d <= dilate; d++) {
                if (i - d >= 0) m[i - d] = true;
                if (i + d < BINS) m[i + d] = true;
            }
        }
        return m;
    }

    /** 掩码 → 连续频段描述（日志 / 文档用），如 "500–1062Hz"。 */
    public static String describe(boolean[] mask) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < mask.length) {
            if (!mask[i]) { i++; continue; }
            int j = i;
            while (j + 1 < mask.length && mask[j + 1]) j++;
            if (sb.length() > 0) sb.append(" + ");
            sb.append(Math.round(i * (double) SAMPLE_RATE / N)).append("–").append(Math.round(j * (double) SAMPLE_RATE / N)).append("Hz");
            i = j + 1;
        }
        return sb.length() == 0 ? "(空)" : sb.toString();
    }

    // ==================== DSP 小工具 ====================

    private static double[] sWin;

    /** 周期 sqrt-Hann（分析、合成各乘一次 = Hann，50 % 重叠和为 1）。 */
    static synchronized double[] sqrtHann() {
        if (sWin == null) {
            double[] w = new double[N];
            for (int i = 0; i < N; i++) w[i] = Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
            sWin = w;
        }
        return sWin;
    }

    /** 原地基 2 复数 FFT；inverse=true 时含 1/N 归一化。 */
    static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
        }
    }
}
