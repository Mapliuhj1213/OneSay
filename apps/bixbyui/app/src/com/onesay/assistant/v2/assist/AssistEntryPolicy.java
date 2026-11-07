package com.onesay.assistant.v2.assist;

import com.onesay.assistant.v2.wake.WakePromptPolicy;

/**
 * ★ r2/sysux P2：默认数字助理（混合式）的入口判定。纯 Java，JVM 可测。
 *
 * <ul>
 *   <li>系统召唤（长按 Home / ASSIST 键 → SystemUI startAssist → showSession）：args 里没有我们的标记 → 走现有唤醒
 *       {@code WakeService.requestWake("assist-system")}（经去抖：同一次按键若 hijack 守护也报了，时间级去抖丢掉后到的那条）。</li>
 *   <li>我们自己在唤醒时 {@code showSession(SHOW_WITH_ASSIST|SHOW_WITH_SCREENSHOT)} 只为拿屏幕上下文：args 带
 *       {@link #ARG_SRC} → 只收数据，不再唤醒（否则自激）。</li>
 *   <li>唤醒时要不要顺手要上下文（{@link #wantContextOnWake}）：开关开 + 本 App 是当前默认助理 + 没锁屏 + 是「新一次唤醒」
 *       （按键 / 唤醒词）。系统入口自带上下文；续听 / ✦ 语音 时面板已在屏上，沿用缓存。</li>
 * </ul>
 */
public final class AssistEntryPolicy {

    private AssistEntryPolicy() {}

    /** showSession 的 args 标记（值 = 触发这次取上下文的唤醒来源）。 */
    public static final String ARG_SRC = "bixby_ctx_src";
    public static final String SRC_SYSTEM = WakePromptPolicy.SRC_ASSIST_PREFIX + "system";
    public static final String SRC_KEYGUARD = WakePromptPolicy.SRC_ASSIST_PREFIX + "keyguard";

    /** 结构 / 截图最长等多久就 hide() 收掉空 session（ms）。 */
    public static final long SESSION_MAX_MS = 2500L;

    public enum OnShow { WAKE, CONTEXT_ONLY }

    public static OnShow onShow(String ctxSrcArg) {
        return (ctxSrcArg != null && ctxSrcArg.length() > 0) ? OnShow.CONTEXT_ONLY : OnShow.WAKE;
    }

    public static boolean wantContextOnWake(boolean opt, boolean activeService, boolean keyguardLocked, String wakeSource) {
        if (!opt || !activeService || keyguardLocked) return false;
        WakePromptPolicy.Kind k = WakePromptPolicy.kindOf(wakeSource);
        return k == WakePromptPolicy.Kind.KEY || k == WakePromptPolicy.Kind.VOICE;
    }

    /** 收到的上下文是否算「本次唤醒的」：在唤醒之后到达，且没过 TTL。 */
    public static boolean freshForWake(long ctxAtMs, long wakeAtMs, long nowMs, long ttlMs) {
        return ctxAtMs >= wakeAtMs && nowMs - ctxAtMs <= ttlMs;
    }
}
