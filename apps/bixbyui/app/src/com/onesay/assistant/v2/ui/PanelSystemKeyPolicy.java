package com.onesay.assistant.v2.ui;

/**
 * ★ r2/sysux P1-4：面板与系统键（返回 / Home / 最近任务）的纯 Java 判定。
 *
 * <h3>返回键</h3>
 * Android 8 上 overlay 窗只有<b>可获焦</b>时才收得到 KEYCODE_BACK。新界面为了让 uiautomator 读到下层 App，从挂窗起不可获焦，
 * 结果按返回会让下层 App 后退。这里规定：<b>识屏完成（或本次不识屏）之后</b>、面板进入回答 / 结果态（或续听态）时切成可获焦，
 * 返回键由 FloatingSheetView.dispatchKeyEvent 收起面板。听写中（识屏可能还在 dump）保持不可获焦。
 *
 * <h3>Home / 最近任务</h3>
 * 监听 {@code ACTION_CLOSE_SYSTEM_DIALOGS}：reason 为 homekey / recentapps → 收起面板。
 * <b>assist / voiceinteraction 不收</b>：长按 Home 召唤本助理时系统先发 reason=assist 的广播，收了就等于自己关自己。
 * globalactions（电源菜单）、lock、dream、null（别的 App 发的）也不收：不是用户离开当前页面。
 */
public final class PanelSystemKeyPolicy {

    private PanelSystemKeyPolicy() {}

    public static final String REASON_HOME = "homekey";
    public static final String REASON_RECENTS = "recentapps";

    public static boolean collapseOnCloseSystemDialogs(String reason) {
        return REASON_HOME.equals(reason) || REASON_RECENTS.equals(reason);
    }

    /** 面板阶段（只关心与焦点有关的几种）。 */
    public enum Phase { LISTENING, THINKING, ANSWER, FOLLOWUP, ISLAND, HIDDEN }

    /**
     * 卡片窗现在是否应可获焦。
     *
     * @param useV2            新界面（旧界面一直可获焦，由 ScreenRefHooks.focusTrick 自管）
     * @param screenCaptureBusy 识屏 dump 还在途 / 还没开始（推迟期内）
     * @param textMode         文字输入模式（键盘需要焦点，原逻辑）
     */
    public static boolean wantFocusable(boolean useV2, Phase phase, boolean screenCaptureBusy, boolean textMode) {
        if (!useV2) return true;
        if (textMode) return true;
        if (screenCaptureBusy) return false;
        return phase == Phase.ANSWER || phase == Phase.FOLLOWUP;   // THINKING 不放：工具 / AgentLoop 可能还要 dump 下层屏幕
    }
}
