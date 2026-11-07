package com.onesay.assistant.v2.tts;

import com.onesay.assistant.v2.agent.Outcome;

/**
 * ★ arch/voice-sync（用户 2026-11-02：「交互也需要配合一下语音，要不然会有割裂感，有些场景不需要有些需要，
 * 比如收到『我来弄』这个需要模板式回复一句然后再开始」）：场景 → 说不说、说哪句、屏幕显示什么。纯函数，JVM 可测。
 *
 * <h3>规则（报告 VOICE-SYNC.md §1 对照表）</h3>
 * <ul>
 *   <li>单步接口请求（L0 / L1 / 单次工具调用、不上岛）：<b>不先应答</b>，直接给结果模板。</li>
 *   <li>进 AgentLoop 且上岛（分诊多步 / 混合 → 立即；懒上岛任务在拿到上岛资格时：GUI 开始 / 子目标 ≥2 / 第 2 次工具调用 /
 *       ask_user）：先播应答 —— 子目标 ≥3 或明显多步「这个有点多，我一步步来」，上岛 / GUI「收到，我来弄」，其余「好的」。</li>
 *   <li>第一个 GUI 动作等应答播完（最多 {@link #GATE_MAX_WAIT_MS}），避免一边说一边点。</li>
 *   <li>开始后 {@link #STILL_WORKING_MS} 还没完：播一次「还在弄，快好了」，整个任务只催这一次。</li>
 *   <li>结局按 Outcome 选句（{@link #outcome}），屏幕状态行副文字以同一句开头（{@link #statusSub}）。</li>
 *   <li>静音条件（{@link #silenceReason}）：设置里关了语音 / 用户正在说话录音 / 通话中 / 媒体在播 → 只显示字幕不出声。</li>
 * </ul>
 */
public final class VoiceSyncPolicy {

    private VoiceSyncPolicy() {}

    /** 开始后这么久还没完 → 催一次。 */
    public static final long STILL_WORKING_MS = 8000L;
    /** 首个 GUI 动作最多等应答这么久（最长的应答 1.9 s + 余量；播放卡住也不会把任务卡死）。 */
    public static final long GATE_MAX_WAIT_MS = 4000L;
    /** 催的时候应答还在播 → 往后挪这么久再看。 */
    public static final long NUDGE_RETRY_MS = 1000L;
    /** 连续没听清的计数窗口：上次没听清超过这么久就重新从 1 数。 */
    public static final long MISS_WINDOW_MS = 60000L;

    /** 应答为什么播（日志 / 测试）。 */
    public enum AckWhy { ISLAND_NOW, GUI, SUBGOALS, STEPS, ASK_USER }

    // ------------------------------------------------------------------ 应答

    /**
     * 应答选句。estSteps = 估计的步数（分诊多步时按指令分句数估，或任务账本的子目标数）；gui = 因为要点界面才应答。
     * ≥3 步 → 这个有点多，我一步步来；上岛 / GUI → 收到，我来弄；其余 → 好的。
     */
    public static VoiceTemplate ack(AckWhy why, int estSteps) {
        if (estSteps >= 3) return VoiceTemplate.ACK_MANY;
        if (why == AckWhy.ISLAND_NOW || why == AckWhy.GUI || why == AckWhy.SUBGOALS) return VoiceTemplate.ACK_ON_IT;
        return VoiceTemplate.ACK_OK;
    }

    /** 分诊判多步的指令大概几步（按连接词 / 分句数估，至少 1）。只用来挑「这个有点多」还是「收到，我来弄」。 */
    public static int estimateSteps(String command) {
        if (command == null) return 1;
        String s = command.trim();
        if (s.length() == 0) return 1;
        String[] parts = s.split("[，,。；;、]|然后|再帮我|接着|之后|顺便|并且|同时|最后|还要|再把|再给|再去|再看|再打开|再关");
        int n = 0;
        for (String p : parts) if (p.trim().length() >= 2) n++;
        return Math.max(1, n);
    }

    // ------------------------------------------------------------------ 静音

    /**
     * 这句该不该出声。null = 出声；否则是不出声的原因（只显示字幕）。
     * settingOn = 语音回合看「语音问就语音答」、文字回合看「语音回复」（{@code ConversationTurnPolicy.shouldSpeak}）。
     */
    public static String silenceReason(boolean settingOn, boolean userSpeaking, boolean inCall, boolean mediaPlaying) {
        if (!settingOn) return "设置关了语音";
        if (userSpeaking) return "用户正在说话 / 录音";
        if (inCall) return "通话中";
        if (mediaPlaying) return "媒体在播";
        return null;
    }

    // ------------------------------------------------------------------ 结局

    /**
     * AgentLoop 结局 → 结果模板。
     *
     * @param resultNull     循环抛异常 / 没有结果
     * @param multi          多步（账本子目标 ≥2，或开局就按多步应答过）
     * @param lastWriteUnverified 最后一个改动类动作执行成功但没回读到结果（不是跳转 / 只读）
     */
    public static VoiceTemplate outcome(boolean resultNull, Outcome o, String reason, boolean multi, boolean lastWriteUnverified) {
        if (resultNull) return VoiceTemplate.EXCEPTION;
        String r = reason == null ? "" : reason;
        if (o == null || o == Outcome.DONE) {
            if (lastWriteUnverified) return VoiceTemplate.UNVERIFIED;
            return multi ? VoiceTemplate.PLAN_ALL : VoiceTemplate.DONE;
        }
        switch (o) {
            case NEEDS_USER:
                if (Outcome.Reason.NO_ANSWER.equals(r)) return VoiceTemplate.CONFIRM_TIMEOUT;
                // ★ v3b：登录 / 验证 / 付款 / 缺信息各有专句（原暂代 HANDOFF / ERROR）
                if (Outcome.Reason.LOGIN.equals(r)) return VoiceTemplate.NEED_LOGIN;
                if (Outcome.Reason.CAPTCHA.equals(r)) return VoiceTemplate.NEED_VERIFY;
                if (Outcome.Reason.PAYMENT.equals(r)) return VoiceTemplate.NEED_PAY;
                if (Outcome.Reason.NEEDS_INFO.equals(r)) return VoiceTemplate.NEED_INFO;
                return VoiceTemplate.ERROR;
            case BLOCKED:
                if (Outcome.Reason.MODEL_UNAVAILABLE.equals(r)) return VoiceTemplate.NET_WEAK;
                if (Outcome.Reason.POLICY.equals(r)) return VoiceTemplate.GATE;
                if (Outcome.Reason.NO_TOOLS.equals(r)) return VoiceTemplate.UNSUPPORTED;
                if (Outcome.Reason.NO_KEY.equals(r)) return VoiceTemplate.NO_KEY;                    // ★ v3b
                if (Outcome.Reason.DEVICE_UNAVAILABLE.equals(r)) return VoiceTemplate.DEVICE_BUSY;   // ★ v3b
                return VoiceTemplate.ERROR;
            case CANCELLED:
                return VoiceTemplate.CANCELLED;
            default:   // NOT_DONE
                if (Outcome.Reason.BUDGET.equals(r)) return VoiceTemplate.TIMEOUT;
                if (Outcome.Reason.LOOP_GUARD.equals(r)) return VoiceTemplate.STUCK;
                if (Outcome.Reason.ERROR.equals(r)) return VoiceTemplate.EXCEPTION;
                if (Outcome.Reason.PARTIAL.equals(r)) return VoiceTemplate.PLAN_PARTIAL;   // ★ v3b：多步只完成一部分
                return VoiceTemplate.ERROR;
        }
    }

    /**
     * 多步计划（executePlan）收尾：全成 → 都弄好了；被敏感步骤中止 → 最后一步得你自己点；
     * 有成有败 → 弄好了一部分，剩下的没弄成（★ v3b）；一步都没成 → 哎呀，没弄成（不说「弄好了一部分」）。
     *
     * @param anyOk 至少有一个执行步骤成功（纯回答步骤不算）
     */
    public static VoiceTemplate plan(boolean allOk, boolean aborted, boolean anyOk) {
        if (aborted) return VoiceTemplate.HANDOFF;
        if (allOk) return VoiceTemplate.PLAN_ALL;
        return anyOk ? VoiceTemplate.PLAN_PARTIAL : VoiceTemplate.ERROR;
    }

    /**
     * 屏幕状态行副文字：以所播那句开头，后面跟细节（共 N 步 / OutcomeText.line）。细节为空或与所播相同时只显示所播那句。
     */
    public static String statusSub(VoiceTemplate spoken, String detail) {
        String a = spoken == null ? "" : spoken.text;
        String d = detail == null ? "" : detail.trim();
        if (a.length() == 0) return d;
        if (d.length() == 0 || d.equals(a)) return a;
        return a + " · " + d;
    }

    /** 最后一个改动类动作算不算「做了但没看到结果」：执行成功 + 没回读确认 + 不是跳转界面 + 不是只读。 */
    public static boolean unverifiedWrite(boolean ok, boolean verified, boolean navigation, boolean readOnly) {
        return ok && !verified && !navigation && !readOnly;
    }

    // ------------------------------------------------------------------ 取消 / 没听清

    /** 任务在跑时说的「取消 / 停止 / 别弄了 / 不弄了 / 算了」= 停掉在跑的任务（不当新指令）。 */
    public static boolean isCancelPhrase(String command) {
        if (command == null) return false;
        String t = command.replaceAll("[\\s，。！？!?,.、~～吧啊呀了]", "");
        return t.equals("取消") || t.equals("停止") || t.equals("停") || t.equals("算") || t.equals("别弄")
                || t.equals("不弄") || t.equals("不要弄") || t.equals("停下") || t.equals("停一下") || t.equals("取消任务")
                || t.equals("别做") || t.equals("不用弄");
    }

    /** 连续没听清计数：距上次没听清 ≤ {@link #MISS_WINDOW_MS} 累加，否则从 1 起。 */
    public static int nextMissCount(int prevCount, long prevAtMs, long nowMs) {
        if (prevCount <= 0 || prevAtMs <= 0 || nowMs - prevAtMs > MISS_WINDOW_MS || nowMs < prevAtMs) return 1;
        return prevCount + 1;
    }
}
