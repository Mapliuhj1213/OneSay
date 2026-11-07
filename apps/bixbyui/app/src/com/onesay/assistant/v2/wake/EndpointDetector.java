package com.onesay.assistant.v2.wake;

/**
 * 自适应断句（P1-9，纯 Java，JVM 单测；WakeService 只调两个方法）。
 *
 * <h3>为什么</h3>
 * 旧判据是固定"静音 1.6 s"才断句（WakeTuning.silenceMs），短指令（"打开蓝牙"约 1 s）说完还要干等 1.6 s，
 * 是离原版 Bixby 体验差距最大的几处之一（审计 §5 体验表：断句 −0.5~0.9 s）。
 *
 * <h3>规则（开关关闭时完全等于旧行为）</h3>
 * <ul>
 *   <li>已说时长 span = 最后一次人声 − 第一次人声；</li>
 *   <li>span &lt; {@code shortSpanMs}（默认 1500）→ 短句，尾静音 {@code shortSilenceMs}（默认 700）；</li>
 *   <li>否则 → 长句，尾静音 {@code longSilenceMs}（默认 900；长句里常有换气停顿）；</li>
 *   <li>两者都夹在 [600, 旧固定值] 之间：只会比旧行为<b>更快</b>，绝不更慢；</li>
 *   <li><b>最短语句保护</b>：span &lt; {@code minUtteranceMs}（默认 300）且人声帧不足时不断句 ——
 *       防"咳一声/环境噪声一下"就被当成说完了（这与旧逻辑的 minSpeechFrames 门槛叠加生效）。</li>
 * </ul>
 */
public final class EndpointDetector {

    /** 尾静音下限：低于 0.6 s 会把句中正常停顿切断。 */
    public static final int FLOOR_MS = 600;

    private final boolean adaptive;
    private final int fixedSilenceMs;
    private final int shortSilenceMs;
    private final int longSilenceMs;
    private final int shortSpanMs;
    private final int minUtteranceMs;

    public EndpointDetector(boolean adaptive, int fixedSilenceMs, int shortSilenceMs, int longSilenceMs,
                            int shortSpanMs, int minUtteranceMs) {
        this.adaptive = adaptive;
        this.fixedSilenceMs = Math.max(1, fixedSilenceMs);
        int cap = Math.max(FLOOR_MS, this.fixedSilenceMs);
        this.shortSilenceMs = clamp(shortSilenceMs, FLOOR_MS, cap);
        this.longSilenceMs = clamp(Math.max(longSilenceMs, this.shortSilenceMs), FLOOR_MS, cap);
        this.shortSpanMs = Math.max(0, shortSpanMs);
        this.minUtteranceMs = Math.max(0, minUtteranceMs);
    }

    /** 旧行为：固定静音阈值。 */
    public static EndpointDetector fixed(int silenceMs) {
        return new EndpointDetector(false, silenceMs, silenceMs, silenceMs, 0, 0);
    }

    public boolean isAdaptive() { return adaptive; }

    /**
     * 本次应使用的尾静音阈值。
     *
     * @param firstSpeechAt 本窗第一次检测到人声的时刻（0 = 还没有）
     * @param lastSpeechAt  最后一次人声时刻
     */
    public int trailingSilenceMs(long firstSpeechAt, long lastSpeechAt) {
        if (!adaptive) return fixedSilenceMs;
        long span = (firstSpeechAt > 0 && lastSpeechAt >= firstSpeechAt) ? lastSpeechAt - firstSpeechAt : 0;
        return span < shortSpanMs ? shortSilenceMs : longSilenceMs;
    }

    /**
     * 最短语句保护：自适应模式下，说话跨度不足 {@code minUtteranceMs} 时，
     * 需要人声帧数达到 {@code minFrames} 的两倍才放行（短促的真实指令如"关灯"帧数是够的）。
     * 非自适应模式恒为 true（旧逻辑只看 minSpeechFrames，由调用方判断）。
     */
    public boolean utteranceLongEnough(long firstSpeechAt, long lastSpeechAt, int speechFrames, int minFrames) {
        if (!adaptive) return true;
        long span = (firstSpeechAt > 0 && lastSpeechAt >= firstSpeechAt) ? lastSpeechAt - firstSpeechAt : 0;
        if (span >= minUtteranceMs) return true;
        return speechFrames >= Math.max(1, minFrames) * 2;
    }

    /**
     * 完整判定（便于单测与日志）：说过话、静音够久、人声帧够、语句够长。
     */
    public boolean shouldEndpoint(long now, long firstSpeechAt, long lastSpeechAt, int speechFrames, int minFrames) {
        if (firstSpeechAt <= 0 || lastSpeechAt <= 0) return false;
        if (speechFrames < minFrames) return false;
        if (now - lastSpeechAt <= trailingSilenceMs(firstSpeechAt, lastSpeechAt)) return false;
        return utteranceLongEnough(firstSpeechAt, lastSpeechAt, speechFrames, minFrames);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
