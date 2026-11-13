package com.onesay.clock.core;

/**
 * UI 触感映射（纯 Java，JVM 锁定）。默认 = 老式系统时钟（Android 8.0）的做法，改用<b>通用</b>接口实现：
 *   - 响铃页滑动解除：按住与触发各振一次「短促点击」——{@link Kind#VIBRATE}，由 ui.Haptics 用标准 VibrationEffect 播放
 *     （API 29+ 预置 EFFECT_CLICK；API 26–28 {@link #oneShotMs} 毫秒满幅单次振；更老的用 Vibrator.vibrate(ms)），
 *     前提 Settings.System haptic_feedback_enabled == 1（系统「触摸时振动」开关）。
 *   - 时间滚轮：每走一格 AudioManager.playSoundEffect({@link #SOUND_TIME_PICKER_SCROLL})（「滴答声」，由系统「触摸音」开关管；
 *     系统不认识这个音效编号时框架直接忽略）。
 *   - 长按：框架 View.performLongClick → performHapticFeedback(LONG_PRESS)，App 不另调。
 *   - 开关、星期、普通按钮、贪睡按钮、删除确认：只有系统点击音，没有振动调用。
 */
public final class HapticMap {
    private HapticMap() { }

    public enum Ev { SWITCH_TOGGLE, TIME_TICK, DAY_TOGGLE, BUTTON_TAP, LONG_PRESS, SLIDE_GRAB, SLIDE_TRIGGER, SNOOZE_TAP, SNOOZE_STEP, DELETE_CONFIRM }

    /**
     * NONE 不振；FRAMEWORK 框架自己振（App 不调）；VIBRATE 标准 VibrationEffect（code = 振动样式，见 {@link #oneShotMs}），
     * 振不了再退到 View.performHapticFeedback(fallback)；SOUND_EFFECT AudioManager.playSoundEffect(code)（系统触摸音开关管）。
     */
    public enum Kind { NONE, FRAMEWORK, VIBRATE, SOUND_EFFECT }

    /** 振动样式：短促点击（相当于老式时钟滑动解除那一下）。 */
    public static final int VIB_CLICK = 1;
    public static final int SOUND_TIME_PICKER_SCROLL = 105;
    /** android.view.HapticFeedbackConstants 公开值（core 不能引用 android.*）。 */
    public static final int HFC_LONG_PRESS = 0, HFC_VIRTUAL_KEY = 1;

    public static final class Act {
        public final Kind kind;
        public final int code;
        public final int fallback;     // VIBRATE 振不了时的 performHapticFeedback 常量；-1 = 不退
        Act(Kind k, int c, int f) { kind = k; code = c; fallback = f; }
        @Override public String toString() { return kind + (kind == Kind.NONE || kind == Kind.FRAMEWORK ? "" : "(" + code + ")"); }
    }

    static final Act NONE = new Act(Kind.NONE, 0, -1);

    /** 默认映射（老式系统时钟的触感分布）。 */
    public static Act old(Ev e) {
        switch (e) {
            case SLIDE_GRAB:
            case SLIDE_TRIGGER: return new Act(Kind.VIBRATE, VIB_CLICK, HFC_VIRTUAL_KEY);
            case TIME_TICK: return new Act(Kind.SOUND_EFFECT, SOUND_TIME_PICKER_SCROLL, -1);
            case LONG_PRESS: return new Act(Kind.FRAMEWORK, HFC_LONG_PRESS, -1);
            default: return NONE;        // 开关、星期、按钮、贪睡、删除确认：旧版无振动
        }
    }

    /** API 26–28 用的单次振动时长（毫秒，满幅）；未知样式按点击。 */
    public static int oneShotMs(int code) {
        return code == VIB_CLICK ? 12 : 12;
    }

    /** 单次振动幅度（1–255）。 */
    public static int amplitude(int code) {
        return 255;
    }

    /** 本版启用的表。 */
    public static Act active(Ev e) { return old(e); }

    /** VIBRATE 要先自查「触摸时振动」；其余种类由系统自己管。 */
    public static boolean allowed(Act a, int hapticFeedbackEnabledSetting) {
        if (a.kind == Kind.NONE || a.kind == Kind.FRAMEWORK) return false;
        if (a.kind == Kind.VIBRATE) return hapticFeedbackEnabledSetting == 1;
        return true;                     // SOUND_EFFECT：AudioManager 内部按触摸音开关决定
    }
}
