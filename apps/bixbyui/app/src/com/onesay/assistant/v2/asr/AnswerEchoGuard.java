package com.onesay.assistant.v2.asr;

/**
 * ★ device-1102 问题 2：「回答完点录音按钮，又直接吐出一大段」的另一条来源 —— <b>上一轮 TTS 回采</b>（纯 Java，JVM 可测）。
 *
 * <p>面板上的 🎤 续说走 {@code WakeService.requestContinueInternal}，它不像唤醒入口那样先 {@code TtsSpeaker.stopAll()}：
 * 回答还在读的时候按录音，麦克风把扬声器里的回答录进去 → 能量门把它当人声 → 识别出「一大段」上屏，并可能被当成下一条指令。
 * WakeService 里已补上 barge-in（开麦先停 TTS）；这里是兜底：识别结果（中间 / 最终）如果基本就是刚才读出来的那段话，丢掉。
 *
 * <p>判据：归一化（去空白标点、小写）后识别文本 ≥ {@link #MIN_CHARS} 字，且是最近一次朗读文本的子串，
 * 或与它的最长公共子串占识别文本 ≥ 70%。只看 {@link #TTL_MS} 内读过的那一段（之后自然失效）。
 */
public final class AnswerEchoGuard {

    public static final int MIN_CHARS = 8;
    public static final long TTL_MS = 60_000L;
    public static final double OVERLAP = 0.7;

    private static volatile String sLast = "";
    private static volatile long sAt = 0L;

    private AnswerEchoGuard() {}

    /** TtsSpeaker 真正要读出来之前登记（全文）。 */
    public static void noteSpoken(String text, long nowMs) {
        String n = norm(text);
        if (n.length() == 0) return;
        sLast = n.length() > 2000 ? n.substring(0, 2000) : n;
        sAt = nowMs;
    }

    public static void clear() {
        sLast = "";
        sAt = 0L;
    }

    public static boolean isEcho(String transcript, long nowMs) {
        return isEcho(transcript, sLast, nowMs - sAt <= TTL_MS);
    }

    /** 纯判据。 */
    public static boolean isEcho(String transcript, String lastSpokenNorm, boolean fresh) {
        if (!fresh || transcript == null || lastSpokenNorm == null || lastSpokenNorm.length() == 0) return false;
        String t = norm(transcript);
        if (t.length() < MIN_CHARS) return false;
        String s = norm(lastSpokenNorm);
        if (s.contains(t)) return true;
        return lcs(t, s) >= Math.ceil(t.length() * OVERLAP);
    }

    static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\p{Punct}，。？！、：；“”‘’（）《》【】…—·]", "").toLowerCase();
    }

    /** 最长公共子串长度（O(n·m)，两边都很短）。 */
    static int lcs(String a, String b) {
        if (a.length() == 0 || b.length() == 0) return 0;
        int[] prev = new int[b.length() + 1];
        int best = 0;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > best) best = cur[j];
                }
            }
            prev = cur;
        }
        return best;
    }
}
