package com.onesay.assistant.v2.ui.live;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ 11-04 相机对话（Gemini Live 式）的回合状态机。纯 Java，JVM 单测。
 *
 * <h3>循环</h3>
 * 听（流式 ASR 一直开着，服务端 VAD 断句）→ 一句话的最终结果 = 一轮：取当前最新一帧 + 这句话 → qwen3-vl → 念出来（TTS）+ 字幕 → 回到听。
 *
 * <h3>打断</h3>
 * 播报中用户开口（流式中间结果 ≥2 个有效字且不是自己播报的回声，或本地能量判据触发）→ 立刻停播，转去听；
 * 思考中又说了一句 → 作废在途请求（代次 +1），按新的一句重新问。
 *
 * <h3>回声</h3>
 * 麦克风虽有 AEC，扬声器播报仍可能被转写回来：与正在播 / 刚播完（{@link #ECHO_TAIL_MS} 内）的回答比对，像就丢掉。
 */
public final class LiveTurnPolicy {

    public enum State { LISTENING, THINKING, SPEAKING, ENDED }

    public enum Action {
        /** 什么都不做。 */
        NONE,
        /** 开一轮（调用方：停掉播报、取帧、问模型；代次见 {@link #gen()}）。 */
        START_TURN,
        /** 打断播报（调用方：停 TTS，继续听）。 */
        BARGE_IN,
        /** 回声：丢掉。 */
        DROP_ECHO,
        /** 静音中：丢掉。 */
        DROP_MUTED
    }

    /** 用户只说了语气词 / 什么都没说清时的默认问题。 */
    public static final String DEFAULT_QUESTION = "这是什么？帮我看看";
    /** 带进上下文的历史轮数（只带文字，图只带本轮的）。 */
    public static final int MAX_TURNS = 6;
    /** 打断要求的最少有效字数。 */
    public static final int MIN_BARGE_CHARS = 2;
    /** 播报结束后这段时间内转写回来的、像回答原文的，仍按回声丢掉。 */
    public static final long ECHO_TAIL_MS = 3000L;
    /** 回声判据：听到的字两两相邻对（bigram）在回答里出现的比例。 */
    public static final double ECHO_BIGRAM_RATIO = 0.6;
    /**
     * 相机对话 ASR 会话的「有声」RMS 门限（默认会话 400）。真机 11-04：借的唤醒麦克风带 AEC / NS、人离手机一臂远，
     * 正常说话的整句被 400 判成无声丢掉；相机对话不带热词 corpus，放低到 150（底噪约 30–150）。
     */
    public static final int ASR_VOICE_RMS = 150;
    /**
     * 能量打断参数（比唤醒默认 1200 / 2.5× / 3 帧 / 500 ms 保守）：真机 11-04 外放播报出声 0.7 s 就被自己的回声判成人声。
     * 能量判据只兜底，主判据是流式中间结果（≥2 个非回声有效字）。
     */
    public static final float BARGE_ABS_MIN = 2000f;
    public static final float BARGE_ECHO_RATIO = 3.5f;
    public static final int BARGE_MIN_FRAMES = 4;
    public static final long BARGE_CALIBRATE_MS = 800L;

    private State mState = State.LISTENING;
    private boolean mMuted;
    private int mGen;
    private String mSpoken = "";
    private long mSpokenEndAt;
    private final List<String[]> mHistory = new ArrayList<String[]>();

    public synchronized State state() { return mState; }

    public synchronized int gen() { return mGen; }

    public synchronized boolean muted() { return mMuted; }

    public synchronized void setMuted(boolean on) { mMuted = on; }

    /** 流式中间结果。 */
    public synchronized Action onPartial(String text) {
        if (mState == State.ENDED || mMuted) return Action.NONE;
        if (mState != State.SPEAKING) return Action.NONE;
        // ★ ui-K2 ②：朗读中门槛抬高 —— ≥3 个有效字（原 2）且两种回声判据都不像（bigram / 最长公共子串）
        if (!meaningful(text) || !LiveEchoPolicy.strongEnoughToBarge(text) || looksLikeEcho(text)) return Action.NONE;
        mState = State.LISTENING;
        return Action.BARGE_IN;
    }

    /** ★ ui-K2 ③：两种回声判据任一命中（原 bigram 比例，或最长公共子串 ≥60% / 连续 ≥6 字）。 */
    private boolean looksLikeEcho(String text) {
        return isEcho(text, mSpoken) || LiveEchoPolicy.isEcho(text, mSpoken);
    }

    /** ★ ui-K2：最近一次（正在 / 刚刚）朗读的原文（日志算比例用；不打原文）。 */
    public synchronized String spokenText() { return mSpoken; }

    /**
     * 流式中间结果要不要当「你说的话」上字幕：播报中不上（多半是回声）；播完 {@link #ECHO_TAIL_MS} 内像回答原文的也不上
     * （真机 11-04：外放回声被整句转写，播完那一刻字幕「你」那一行出现了回答原文）。
     */
    public synchronized boolean showPartial(String text, long nowMs) {
        if (mState == State.ENDED || mMuted || mState == State.SPEAKING) return false;
        boolean tail = mSpokenEndAt > 0 && nowMs - mSpokenEndAt <= ECHO_TAIL_MS;
        return !(tail && looksLikeEcho(text));
    }

    /** 本地能量判据触发（播报中有人声）。 */
    public synchronized Action onEnergyBarge() {
        if (mState != State.SPEAKING || mMuted) return Action.NONE;
        mState = State.LISTENING;
        return Action.BARGE_IN;
    }

    /** 一句话的最终结果。 */
    public synchronized Action onFinal(String text, long nowMs) {
        if (mState == State.ENDED) return Action.NONE;
        if (mMuted) return Action.DROP_MUTED;
        String t = text == null ? "" : text.trim();
        if (norm(t).length() == 0) return Action.NONE;
        boolean echoWindow = mState == State.SPEAKING || (mSpokenEndAt > 0 && nowMs - mSpokenEndAt <= ECHO_TAIL_MS);
        if (echoWindow && looksLikeEcho(t)) return Action.DROP_ECHO;
        // ★ ui-K2 ②：朗读中 / 刚播完 800 ms 内冒出来的 1–2 字碎片，多半是回声尾巴（真人打断走中间结果，那条已经停了播报）
        if (LiveEchoPolicy.inGuard(mState == State.SPEAKING, mSpokenEndAt, nowMs) && norm(t).length() < LiveEchoPolicy.GUARD_MIN_CHARS)
            return Action.DROP_ECHO;
        mGen++;   // 思考中又说一句 → 在途请求作废；播报中说完一句 → 也是打断 + 新一轮
        mState = State.THINKING;
        return Action.START_TURN;
    }

    /** 模型答复回来。代次不对（被新一句顶掉）/ 已不在思考 → false（丢弃）。 */
    public synchronized boolean onAnswer(int gen, String question, String answer) {
        if (gen != mGen || mState != State.THINKING) return false;
        mState = State.SPEAKING;
        mSpoken = answer == null ? "" : answer;
        mSpokenEndAt = 0L;
        addHistory(question, answer);
        return true;
    }

    /** 本轮失败（网络 / 模型）。代次对得上才回到听。 */
    public synchronized boolean onError(int gen) {
        if (gen != mGen || mState != State.THINKING) return false;
        mState = State.LISTENING;
        return true;
    }

    /** 播报结束（播完或被打断）。 */
    public synchronized void onSpeechDone(long nowMs) {
        if (mSpoken.length() > 0) mSpokenEndAt = nowMs;
        if (mState == State.SPEAKING) mState = State.LISTENING;
    }

    /** 挂断：后续一切回调作废，历史清空。 */
    public synchronized void end() {
        mState = State.ENDED;
        mGen++;
        mSpoken = "";
        mHistory.clear();
    }

    public synchronized List<String[]> history() {
        return new ArrayList<String[]>(mHistory);
    }

    private void addHistory(String q, String a) {
        if (q == null || a == null) return;
        mHistory.add(new String[]{q, a});
        while (mHistory.size() > MAX_TURNS) mHistory.remove(0);
    }

    // ------------------------------------------------------------------ 纯函数

    /** 这句话当问题用：语气词 / 空 → 默认问题。 */
    public static String questionOrDefault(String text) {
        String t = text == null ? "" : text.trim();
        if (!meaningful(t)) return DEFAULT_QUESTION;
        return t;
    }

    /** 有没有 ≥{@link #MIN_BARGE_CHARS} 个有效字、且不是纯语气词。 */
    public static boolean meaningful(String text) {
        String n = norm(text);
        if (n.length() < MIN_BARGE_CHARS) return false;
        return !n.matches("[嗯啊哦呃额唔噢喔哈诶欸哎呀吧呢嘛]+");
    }

    /** 听到的是不是在复读我们自己的播报。 */
    public static boolean isEcho(String heard, String spoken) {
        String h = norm(heard), s = norm(spoken);
        if (h.length() == 0 || s.length() == 0) return false;
        if (s.contains(h)) return true;
        if (h.length() < 2) return false;
        int total = 0, hit = 0;
        for (int i = 0; i + 1 < h.length(); i++) {
            total++;
            if (s.contains(h.substring(i, i + 2))) hit++;
        }
        return total > 0 && hit >= ECHO_BIGRAM_RATIO * total;
    }

    /** 去标点 / 空白，英文转小写。 */
    public static String norm(String t) {
        if (t == null) return "";
        return t.replaceAll("[\\s\\p{Punct}，。？！、：；“”‘’（）《》【】…—·～]", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** 岛上那一行：思考中 →「思考中」；其余 →「相机对话中 · 0:12」。 */
    public static String islandLine(boolean thinking, long elapsedMs) {
        return thinking ? "思考中" : "相机对话中 · " + clock(elapsedMs);
    }

    /** m:ss（≥1 小时 h:mm:ss）。 */
    public static String clock(long ms) {
        long s = Math.max(0L, ms) / 1000L;
        long h = s / 3600L, m = (s / 60L) % 60L, sec = s % 60L;
        String ss = sec < 10 ? "0" + sec : String.valueOf(sec);
        if (h > 0) return h + ":" + (m < 10 ? "0" + m : String.valueOf(m)) + ":" + ss;
        return m + ":" + ss;
    }
}
