package com.onesay.assistant.v2.ui.live;

/**
 * ★ ui-K2 相机对话「把自己朗读的声音录进去」的三层防线里的纯逻辑部分（纯 Java，JVM 单测）。
 *
 * <ol>
 *   <li><b>采集层</b>（LiveConversation / WakeService）：录音源 VOICE_COMMUNICATION + 同 session 上的 AEC / NS（isAvailable 才开），
 *       通话模式 + 外放，TTS 走 USAGE_VOICE_COMMUNICATION —— 播放与采集同一条通话路由，AEC 才拿得到参考信号。</li>
 *   <li><b>打断门槛</b>：朗读期间及结束后 {@link #GUARD_TAIL_MS} 内，打断要更「像人」：
 *       流式中间结果 ≥{@link #GUARD_MIN_CHARS} 个有效字且不像回声；本地能量判据用更高的绝对门限 / 回声倍数 / 连续帧数
 *       （{@link #GUARD_ABS_MIN} / {@link #GUARD_ECHO_RATIO} / {@link #GUARD_MIN_FRAMES}）。真人说「等一下」仍能打断。</li>
 *   <li><b>文本层</b>：ASR 结果与当前 / 刚结束的朗读文本比相似度 —— 去标点后最长公共子串占识别文本 ≥{@link #LCS_RATIO}，
 *       或连续相同 ≥{@link #LCS_RUN} 个字 → 丢弃（日志 [LIVE-ECHO]，只记字数与比例，不记原话）。</li>
 * </ol>
 */
public final class LiveEchoPolicy {

    private LiveEchoPolicy() {}

    /** 朗读结束后仍按「朗读中」抬高打断门槛的时长。 */
    public static final long GUARD_TAIL_MS = 800L;
    /** 门槛期内流式中间结果打断要求的最少有效字数（原 2；「等一下」= 3 字仍可打断）。 */
    public static final int GUARD_MIN_CHARS = 3;
    /** 门槛期能量打断：绝对 RMS 下限（原 2000）。 */
    public static final float GUARD_ABS_MIN = 2600f;
    /** 门槛期能量打断：相对回声基线的倍数（原 3.5）。 */
    public static final float GUARD_ECHO_RATIO = 4.5f;
    /** 门槛期能量打断：连续超限帧数（原 4，×100 ms）。 */
    public static final int GUARD_MIN_FRAMES = 6;

    /** 文本层：最长公共子串占识别文本的比例门限。 */
    public static final double LCS_RATIO = 0.6;
    /** 文本层：连续相同字数门限。 */
    public static final int LCS_RUN = 6;
    /** 比例判据要求识别文本至少这么长（1–2 个字的「好」「停」不按比例判，免得吞掉真人打断）。 */
    public static final int RATIO_MIN_CHARS = 3;

    /** 是否处在抬高门槛的时段（朗读中，或朗读结束 ≤{@link #GUARD_TAIL_MS}）。spokenEndAt ≤ 0 = 还没播完过。 */
    public static boolean inGuard(boolean speaking, long spokenEndAt, long nowMs) {
        if (speaking) return true;
        return spokenEndAt > 0 && nowMs >= spokenEndAt && nowMs - spokenEndAt <= GUARD_TAIL_MS;
    }

    /** 门槛期内这句中间结果够不够格打断（有效字数 ≥ GUARD_MIN_CHARS 且不是纯语气词）。 */
    public static boolean strongEnoughToBarge(String partial) {
        String n = LiveTurnPolicy.norm(partial);
        if (n.length() < GUARD_MIN_CHARS) return false;
        return LiveTurnPolicy.meaningful(partial);
    }

    /** 文本层回声判据（见类注释 ③）。 */
    public static boolean isEcho(String heard, String spoken) {
        String h = LiveTurnPolicy.norm(heard), s = LiveTurnPolicy.norm(spoken);
        if (h.length() == 0 || s.length() == 0) return false;
        int run = lcs(h, s);
        if (run >= LCS_RUN) return true;
        return h.length() >= RATIO_MIN_CHARS && run >= Math.ceil(h.length() * LCS_RATIO);
    }

    /** 日志用：公共子串占比（0–1，保留两位）。 */
    public static double ratio(String heard, String spoken) {
        String h = LiveTurnPolicy.norm(heard), s = LiveTurnPolicy.norm(spoken);
        if (h.length() == 0) return 0d;
        return Math.round(lcs(h, s) * 100.0 / h.length()) / 100.0;
    }

    /** 最长公共子串长度（O(n·m)，两边都只有几十到几百字）。 */
    public static int lcs(String a, String b) {
        if (a == null || b == null || a.length() == 0 || b.length() == 0) return 0;
        if (b.length() > 2000) b = b.substring(0, 2000);
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        int best = 0;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > best) best = cur[j];
                } else {
                    cur[j] = 0;
                }
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return best;
    }
}
