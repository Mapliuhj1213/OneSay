package com.onesay.assistant.v2.wake;

/**
 * [R13 离线兜底 / 会话收尾] 捕获窗收尾的纯函数裁决（JVM 可测），WakeService 只按结果执行。
 *
 * <h3>修掉的缺陷（2026-10-13 真机飞行模式：「没听清 → 正在想（卡住）→ 唤不醒」）</h3>
 * 旧实现收窗时用 {@code mCloudFiredForThisCapture && !mGotCommand} 判「云端还在路上」。可云端<b>失败</b>后这个式子依然为真
 * （发过、没拿到命令）—— 于是：云端失败 → 「没听清」→ 2.5 s 自动收起 → 窗到点 → 误判「在途」→ {@code setListeningPrompt("云端识别中…")}
 * 把面板重新弹出来停在「正在想」，而且再也没有任何回调来收它。现在在途只认 {@code cloudInFlight}（发出置真、回调到达即置假），
 * 云端失败 / 空结果 / 幻觉丢弃时同时关窗（{@link #closeOnCloudSettled}），窗到点的收尾不会再跑。
 */
public final class CaptureSessionPolicy {

    private CaptureSessionPolicy() {}

    public enum Expiry {
        /** 已拿到命令 / 本窗已收尾：什么都不做。 */
        IGNORE,
        /** 云端请求真的还在路上：只切文案（有界超时，回调必到）。 */
        CLOUD_PENDING,
        /** 没网：不发云端，立刻「没有网络」收尾。 */
        OFFLINE,
        /** 有网、还没发过：最后送一次云端。 */
        TRY_CLOUD
    }

    /** 窗到点时怎么收。 */
    public static Expiry onWindowExpired(boolean gotCommand, boolean closed, boolean cloudInFlight, boolean online) {
        if (gotCommand || closed) return Expiry.IGNORE;
        if (cloudInFlight) return Expiry.CLOUD_PENDING;
        if (!online) return Expiry.OFFLINE;
        return Expiry.TRY_CLOUD;
    }

    /**
     * （HIDE 迟到「没听清」）：ACTION_HIDE 收起浮层时这一窗要不要作废。
     * 没拿到指令、没收尾，且窗还开着或云端还在路上 → 作废（代际号 +1）。已拿到指令（随口记 / 长按 / 语音指令执行中）不动。
     */
    public static boolean cancelOnHide(boolean gotCommand, boolean closed, boolean windowOpen, boolean cloudInFlight) {
        if (gotCommand || closed) return false;
        return windowOpen || cloudInFlight;
    }

    /** 结果 / 提示所属的会话号 ≠ 当前会话号 = 迟到（上一窗的），一律丢弃。 */
    public static boolean isStale(int sessionGen, int currentGen) {
        return sessionGen != currentGen;
    }

    /** 断句时：没网就别发（返回 false = 走「没有网络」收尾）。 */
    public static boolean mayFireCloud(boolean online) {
        return online;
    }

    /** 云端回调（本窗的）到达：不论成败，在途标记都清掉。 */
    public static boolean cloudInFlightAfterCallback() {
        return false;
    }

    /** 云端回调没产出可执行命令（失败 / 空 / 幻觉）→ 本窗立即关闭（不再等窗到点）。 */
    public static boolean closeOnCloudSettled(boolean producedCommand) {
        return !producedCommand;
    }

    /** ASR 读超时的有界区间（用户可在设置里改 timeout_ms；写个 0 或 600000 都不能让识别无限等）。 */
    public static final int ASR_TIMEOUT_MIN_MS = 1000;
    public static final int ASR_TIMEOUT_MAX_MS = 15000;

    public static int clampAsrTimeoutMs(int ms) {
        if (ms < ASR_TIMEOUT_MIN_MS) return ms <= 0 ? 6000 : ASR_TIMEOUT_MIN_MS;
        return Math.min(ms, ASR_TIMEOUT_MAX_MS);
    }

    /**
     * ★ R14 隐私起点（距唤醒时刻的毫秒数）：按键唤醒 = 硬门限 guardMs（300；松手才唤醒，音频里没有唤醒词，提示音期间开口的字也收）；
     * 语音唤醒照旧 = 短提示音 shortMs（450）/ 长应答音 longMs（1100）。结果永远 ≥ guardMs（tryCloudRecognize 的隐私硬闸门）。
     */
    public static long captureFromOffsetMs(boolean keySource, boolean shortChime, long guardMs, long shortMs, long longMs) {
        // ★ r2/sysux（用户 10-29 拍板「按下即听」）：短提示音模式下所有来源都从 +guardMs 起，[唤醒, +guardMs) 由预录补齐
        //   （起点 = 唤醒时刻，剔提示音回声，见 WakePromptPolicy）。shortMs 不再参与（保留参数，调用方不变）。
        long v = keySource || shortChime ? guardMs : longMs;
        return Math.max(guardMs, v);
    }

    /**
     * ★ r2/fixes 12：隐私自检里「上传窗起点规则」那一行，按实际生效的规则描述（纯函数，可单测）。
     * 以前写死「唤醒时刻 + 1100ms」，但 R14 起按键唤醒是 +guardMs（300），短提示音模式是 +shortMs（450），
     * D1 按键预录还会从唤醒那一刻起补上 [唤醒, 唤醒+guardMs) 这段 —— 写死 1100 是错的描述。
     *
     * @param preRollMs 预录长度（只在 keyPreRoll 开时有意义；≤0 等于不预录）
     */
    public static String captureRuleText(boolean shortChime, boolean keyPreRoll, long preRollMs,
                                         long guardMs, long shortMs, long longMs) {
        long voice = captureFromOffsetMs(false, shortChime, guardMs, shortMs, longMs);
        long key = captureFromOffsetMs(true, shortChime, guardMs, shortMs, longMs);
        StringBuilder sb = new StringBuilder("上传窗起点规则 = 一律晚于唤醒时刻：");
        sb.append("唤醒词唤醒 +").append(voice).append("ms（").append(shortChime ? "按下即听·短提示音模式" : "长应答音模式")
                .append(shortChime && keyPreRoll && preRollMs > 0 ? "，之前那段从唤醒那一刻起预录、剔提示音回声、只在有人声时接上）；"
                        : "，唤醒词与应答音不进缓冲）；");
        sb.append("按键唤醒 +").append(key).append("ms");
        long pre = Math.min(Math.max(0L, preRollMs), key);
        if (keyPreRoll && pre > 0) {
            sb.append("，提示音期间预录开：从唤醒那一刻起最多补 ").append(pre)
                    .append("ms，只在有人声时接上，绝不早于唤醒");
        } else {
            sb.append("，提示音期间预录关");
        }
        return sb.toString();
    }

    /** ★ R14.1：按下键先暂停 KWS 这么久（松手 → 开窗之前的空档；开窗后由捕获窗接管）。 */
    public static final long KWS_PAUSE_ON_DOWN_MS = 3000;
    /** ★ R14.1：捕获窗关掉后 KWS 再停这么久（覆盖 思考 → 结果 的卡片动画）。 */
    public static final long KWS_RESUME_TAIL_MS = 2000;

    /** ★ R14.1：唤醒会话期间（捕获窗开着，或还在暂停尾巴里）不跑 KWS。纯函数。 */
    public static boolean kwsPaused(long now, long captureUntil, long pauseUntil) {
        return captureUntil > now || now < pauseUntil;
    }

    // ------------------------------------------------------------------ ★ r2/input（2026-10-24）手动结束

    /**
     * 手动结束（点卡片 / 再按一次键）有没有意义：窗开着（过了隐私起点）、没拿到指令、没收尾、没发过云端，
     * 且已经听到人声（≥ minSpeechFrames）或流式已有文字。
     */
    public static boolean finishAllowed(boolean windowOpen, boolean gotCommand, boolean closed, boolean cloudFired,
                                        int speechFrames, int needFrames, boolean hasRtText) {
        if (!windowOpen || gotCommand || closed || cloudFired) return false;
        return hasRtText || speechFrames >= Math.max(1, needFrames);
    }

    /**
     * 收音中按键 = 结束：除了 {@link #finishAllowed}，还要距上次受理唤醒 ≥ 去抖窗 —— 同一次按键的两条通道（key-file / key-activity）
     * 在去抖窗内先后到，第二条照旧按重复丢弃，不会被当成「再按一次」。
     */
    public static boolean keyPressFinishes(boolean windowOpen, boolean gotCommand, boolean closed, boolean cloudFired,
                                           long sinceLastWakeMs, long dedupMs, int speechFrames, int needFrames,
                                           boolean hasRtText) {
        if (sinceLastWakeMs < dedupMs) return false;
        return finishAllowed(windowOpen, gotCommand, closed, cloudFired, speechFrames, needFrames, hasRtText);
    }

    /** 续期判据窗口：max(基础值, 当前尾静音 + 0.5 s) —— 说到「然后」停下来想 3 s 时，窗不会在停顿里到点关掉。 */
    public static long renewWithinMs(long baseMs, int requiredSilenceMs) {
        return Math.max(baseMs, requiredSilenceMs + 500L);
    }

    /** 续期判定（WakeService.shouldRenewCapture 的纯函数实现；JVM 里 WakeService 不能初始化）。 */
    public static boolean shouldRenew(long now, long lastWakeAt, long captureFrom, long captureUntil,
                                      long lastSpeechAt, long withinMs, long maxMs) {
        if (captureUntil <= 0) return false;                 // 窗已关（指令已提交 / 已取消）
        if (now < captureFrom) return false;                 // 仍在应答音播放期，别被自己续命
        if (lastWakeAt > 0 && (now - lastWakeAt) >= maxMs) return false; // 触顶
        if (lastSpeechAt <= 0) return false;                 // 从没听见人声 → 按点收窗
        return (now - lastSpeechAt) <= withinMs;
    }

    /** 「结束」那次按键从另一条通道迟到（受理后去抖窗内）→ 吞掉，不当新唤醒。 */
    public static boolean swallowAfterKeyFinish(long now, long keyFinishAt, long dedupMs) {
        return keyFinishAt > 0 && now >= keyFinishAt && now - keyFinishAt < dedupMs;
    }
}
