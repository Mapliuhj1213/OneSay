package com.onesay.assistant.v2.wake;

/**
 * ★ r2/sysux P1-3「语音问就语音答 + 自动续听」：一轮对话的纯 Java 判定。
 *
 * <ul>
 *   <li><b>读不读</b>（{@link #shouldSpeak}）：语音发起的回合默认朗读（{@code tts_voice_turn}，默认开）；
 *       文字发起的回合只有原「语音回复」开关（{@code tts_reply}，默认关）开着才读；主动播报（force）不看开关。</li>
 *   <li><b>续听</b>（{@link #shouldFollowUp}）：语音回合 + 播报正常播完（不是被打断）+ 面板还在、没进岛 + 开关开 → 自动开一个短收音窗。</li>
 *   <li><b>续听窗长</b>（{@link #followUpMs}）：调参 followup_ms，默认 5000，夹在 4000–6000。</li>
 *   <li><b>结束语</b>（{@link #isDismissPhrase}）：续听窗里说「好了 / 没事了 / 谢谢 …」= 整轮结束，不当指令执行。</li>
 * </ul>
 */
public final class ConversationTurnPolicy {

    private ConversationTurnPolicy() {}

    public static final int DEF_FOLLOWUP_MS = 5000;
    public static final int MIN_FOLLOWUP_MS = 4000;
    public static final int MAX_FOLLOWUP_MS = 6000;

    public static boolean shouldSpeak(boolean force, boolean voiceTurn, boolean voiceTurnOpt, boolean replyAllOpt) {
        if (force) return true;
        if (voiceTurn && voiceTurnOpt) return true;
        return replyAllOpt;
    }

    public static boolean shouldFollowUp(boolean followUpOpt, boolean voiceTurn, boolean interrupted,
                                         boolean panelVisible, boolean onIsland, boolean paused) {
        return followUpOpt && voiceTurn && !interrupted && panelVisible && !onIsland && !paused;
    }

    /**
     * ★ r2/voice-templates：播报被 stopAll 打断后要不要立刻归还焦点。
     * 打断者接管（新唤醒 / 说话打断都会马上开收音窗 → 状态变 LISTENING）时不动；
     * 没人接管（ttsstop、关开关、文字回合顶掉）时状态还停在 SPEAKING → 立刻归还，不再等 30 s 看门狗（媒体一直被压低）。
     */
    public static boolean releaseFocusAfterInterrupt(boolean stillSpeakingState, boolean focusHeld, boolean captureOpen) {
        return stillSpeakingState && focusHeld && !captureOpen;
    }

    public static int followUpMs(int configured) {
        if (configured <= 0) return DEF_FOLLOWUP_MS;
        if (configured < MIN_FOLLOWUP_MS) return MIN_FOLLOWUP_MS;
        if (configured > MAX_FOLLOWUP_MS) return MAX_FOLLOWUP_MS;
        return configured;
    }

    private static final String[] DISMISS = {
            "好了", "好的谢谢", "没事了", "没事", "谢谢", "谢谢你", "不用了", "算了", "退下吧", "退下", "就这样", "可以了", "行了", "知道了"
    };

    /** 整句（去标点、去语气词「啊吧呀哈」）等于结束语之一。 */
    public static boolean isDismissPhrase(String text) {
        if (text == null) return false;
        String s = text.replaceAll("[\\s，。？！、,.!?~～]", "").replaceAll("[啊吧呀哈啦]+$", "");
        if (s.length() == 0) return false;
        for (String d : DISMISS) if (s.equals(d)) return true;
        return false;
    }
}
