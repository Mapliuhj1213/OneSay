package com.onesay.assistant.v2.wake;

/**
 * ★ r2/sysux P1-1「按下即听」：每次开收音窗时决定 提示音 / 应答语音 / 隐私起点 / 预录。纯 Java，JVM 可测。
 *
 * <h3>用户 10-29 拍板</h3>
 * <ul>
 *   <li>默认 = 约 0.15 s 短提示音（{@link Earcon}）+ 屏幕边缘光效，<b>唤醒那一刻就开始收音</b>：
 *       所有唤醒来源（按键 / 唤醒词 / 系统助理入口 / 「✦ 语音」）都从唤醒时刻起预录（{@link KeyPreRoll}），
 *       提示音回声由 {@link ChimeEchoGate} 剔除，只有提示音时整段丢弃 —— 提示音不会进 ASR。</li>
 *   <li>「我在」长应答保留为设置里的可选项（{@link WakeTuning#K_WAKE_ACK_VOICE}，默认关）；开启时<b>只在
 *       息屏 + 语音唤醒</b>说，并且边说边听：收音起点不等它播完（AEC 开着，识别文本再用 {@link WakeWordStripper#stripAckEcho} 剥掉回读）。
 *       这种窗不预录（回声门的掩码是提示音频带，挡不住人声的「我在」）。</li>
 *   <li>续听窗（播报结束后自动开）：不响提示音、只有光效；起点 = 窗开 + 隐私硬门限，不预录。</li>
 *   <li>旧版（{@code short_chime=0}）：start_tone + 应答语音，行为与 R14.1 一致（用户可在控制台切回）。</li>
 * </ul>
 *
 * <h3>★ 隐私（不可改）</h3>
 * 预录起点永远不早于唤醒时刻（{@link KeyPreRoll#startAt}）；不读常听滚动缓冲；常规上传起点仍 ≥ 硬门限 300 ms。
 */
public final class WakePromptPolicy {

    private WakePromptPolicy() {}

    /** 唤醒来源分类。 */
    public enum Kind {
        /** 实体键 / 模拟键（key-*） */
        KEY,
        /** 唤醒词（KWS） */
        VOICE,
        /** 系统助理入口（长按 Home / ASSIST 键 → VoiceInteractionService，assist-*） */
        ASSIST,
        /** 「✦ 语音」继续聆听（voice-ui） */
        CONTINUE,
        /** 播报结束自动续听 / 说话打断播报（followup-*） */
        FOLLOWUP
    }

    public static final String SRC_ASSIST_PREFIX = "assist-";
    public static final String SRC_FOLLOWUP_PREFIX = "followup-";

    public static Kind kindOf(String source) {
        if (source == null) return Kind.VOICE;
        if (source.startsWith("key-")) return Kind.KEY;
        if (source.startsWith(SRC_ASSIST_PREFIX)) return Kind.ASSIST;
        if (source.startsWith(SRC_FOLLOWUP_PREFIX)) return Kind.FOLLOWUP;
        if (source.endsWith("-ui")) return Kind.CONTINUE;
        return Kind.VOICE;
    }

    /** 提示音种类。 */
    public enum Chime { NONE, SHORT, LEGACY }

    /** 一次开窗的决定。 */
    public static final class Plan {
        public final Chime chime;
        /** 边说边听的「我在」（只在 SHORT 后接着播，不阻塞收音）。 */
        public final boolean ackVoice;
        /** 隐私起点（相对唤醒时刻，ms）。 */
        public final long captureFromMs;
        /** 从唤醒时刻起预录（剔提示音回声）。 */
        public final boolean preRoll;

        Plan(Chime chime, boolean ackVoice, long captureFromMs, boolean preRoll) {
            this.chime = chime;
            this.ackVoice = ackVoice;
            this.captureFromMs = captureFromMs;
            this.preRoll = preRoll;
        }

        @Override public String toString() {
            return "chime=" + chime + " ack=" + ackVoice + " from=+" + captureFromMs + "ms preRoll=" + preRoll;
        }
    }

    /**
     * @param instant      按下即听（= short_chime 开，10-29 起默认）
     * @param ackVoiceOpt  设置「唤醒时说『我在』」
     * @param preRollOpt   设置 key_preroll（抢话不丢字；关掉 = 不预录）
     * @param screenOn     唤醒那一刻屏幕是否亮着（息屏唤醒在亮屏之前判）
     * @param guardMs      隐私硬门限（300）
     * @param legacyVoiceMs 旧版语音唤醒起点（1100）
     */
    public static Plan plan(Kind kind, boolean instant, boolean ackVoiceOpt, boolean preRollOpt, boolean screenOn,
                            long guardMs, long legacyVoiceMs) {
        if (kind == Kind.FOLLOWUP) return new Plan(Chime.NONE, false, guardMs, false);
        if (!instant) {
            // 旧版（R14.1）：按键 +guard，其余 +1100；只有按键预录
            boolean key = kind == Kind.KEY;
            return new Plan(Chime.LEGACY, false, key ? guardMs : Math.max(guardMs, legacyVoiceMs), preRollOpt && key);
        }
        boolean ack = wantAckVoice(ackVoiceOpt, screenOn, kind);
        return new Plan(Chime.SHORT, ack, guardMs, preRollOpt && !ack);
    }

    /** 「我在」只在 选项开 + 息屏 + 语音唤醒 时说。 */
    public static boolean wantAckVoice(boolean opt, boolean screenOn, Kind kind) {
        return opt && !screenOn && kind == Kind.VOICE;
    }

    /** 预录的隐私复核：起点不早于唤醒时刻，且本窗计划里允许预录。 */
    public static boolean preRollPrivacyOk(long startAt, long wakeAt, long flushedWakeAt, boolean planPreRoll) {
        return planPreRoll && startAt >= wakeAt && flushedWakeAt == wakeAt;
    }
}
