package com.onesay.clock.core;

/**
 * UI 触感映射（纯 Java，JVM 锁定）。默认 = 一版老式系统时钟（Android 8.0）的实际做法，
 * 逐项来自反编译：
 *   - 响铃页滑动解除 AlertSlidingTab：按住（setState ACTIVE）与触发（dispatchTriggerEvent，停止/滑动贪睡）各振一次
 *     厂商振动接口(SEM_VIBRATION_COMMON_A = 50025, -1, null, SemMagnitudeTypes.TYPE_MAX)，
 *     前提 StateUtils.checkHapticFeedbackEnabled：Settings.System haptic_feedback_enabled == 1 且 semIsHapticSupported()。
 *   - 时间滚轮 SemTimePicker → SemNumberPickerSpinnerDelegate.playSoundAndHapticFeedback()：每走一格
 *     AudioManager.playSoundEffect(SOUND_TIME_PICKER_SCROLL = 105)。8.0 的 AudioService 收到音效不振（只有 vibrateCall 用振动器），
 *     所以旧版滚轮实为「滴答声」，由系统「触摸音」开关管。
 *   - 长按：框架 View.performLongClick → performHapticFeedback(LONG_PRESS)，长按回调返回 true 即自动振，App 不另调。
 *   - 开关 Switch、星期 ToggleButton、普通按钮、贪睡按钮、删除确认：App 与 8.0 框架都只有 playSoundEffect(CLICK)，没有振动调用。
 * 新版 12.6.05.23 的做法放在 NEW 表里只作对照，默认不用（见报告，由用户选）。
 */
public final class HapticMap {
    private HapticMap() { }

    public enum Ev { SWITCH_TOGGLE, TIME_TICK, DAY_TOGGLE, BUTTON_TAP, LONG_PRESS, SLIDE_GRAB, SLIDE_TRIGGER, SNOOZE_TAP, SNOOZE_STEP, DELETE_CONFIRM }

    /**
     * NONE 不振；FRAMEWORK 框架自己振（App 不调）；SEM_VIBRATE 厂商振动；开源版一律退到 View.performHapticFeedback(fallback)；
     * SOUND_EFFECT AudioManager.playSoundEffect(code)（系统触摸音开关管）；SEM_INDEX 新版 HapticFeedbackConstants.semGetVibrationIndex(code)（Android 8 没有）。
     */
    public enum Kind { NONE, FRAMEWORK, SEM_VIBRATE, SOUND_EFFECT, SEM_INDEX }

    public static final int SEM_VIBRATION_COMMON_A = 50025;
    public static final int SOUND_TIME_PICKER_SCROLL = 105;
    /** android.view.HapticFeedbackConstants 公开值（core 不能引用 android.*）。 */
    public static final int HFC_LONG_PRESS = 0, HFC_VIRTUAL_KEY = 1;

    public static final class Act {
        public final Kind kind;
        public final int code;
        public final int fallback;     // SEM_VIBRATE 的 performHapticFeedback 常量；-1 = 不退
        Act(Kind k, int c, int f) { kind = k; code = c; fallback = f; }
        @Override public String toString() { return kind + (kind == Kind.NONE || kind == Kind.FRAMEWORK ? "" : "(" + code + ")"); }
    }

    static final Act NONE = new Act(Kind.NONE, 0, -1);

    /** 旧版（老式系统时钟）——默认使用。 */
    public static Act old(Ev e) {
        switch (e) {
            case SLIDE_GRAB:
            case SLIDE_TRIGGER: return new Act(Kind.SEM_VIBRATE, SEM_VIBRATION_COMMON_A, HFC_VIRTUAL_KEY);
            case TIME_TICK: return new Act(Kind.SOUND_EFFECT, SOUND_TIME_PICKER_SCROLL, -1);
            case LONG_PRESS: return new Act(Kind.FRAMEWORK, HFC_LONG_PRESS, -1);
            default: return NONE;        // 开关、星期、按钮、贪睡、删除确认：旧版无振动
        }
    }

    /** 新版 12.6.05.23 对照（不启用）：semGetVibrationIndex 编号。 */
    public static Act neu(Ev e) {
        switch (e) {
            case SWITCH_TOGGLE: return new Act(Kind.SEM_INDEX, 27, -1);   // SwitchCompat（sesl）
            case TIME_TICK: return new Act(Kind.SEM_INDEX, 32, -1);       // SeslNumberPicker，同时放 SOUND_TIME_PICKER_SCROLL
            case SNOOZE_TAP:
            case SNOOZE_STEP: return new Act(Kind.SEM_INDEX, 40, -1);     // AlarmAlertSnoozeView 胶囊与 −/+
            case SLIDE_GRAB:
            case SLIDE_TRIGGER: return new Act(Kind.SEM_INDEX, 43, -1);   // AlertSlidingTab，TYPE_TOUCH（DC 马达机型用 100）
            case LONG_PRESS: return new Act(Kind.SEM_INDEX, 1, -1);       // 列表拖动排序按下
            default: return NONE;
        }
    }

    /** 本版启用的表。 */
    public static Act active(Ev e) { return old(e); }

    /** SEM_VIBRATE 要先自查「触摸时振动」；其余种类由系统自己管。 */
    public static boolean allowed(Act a, int hapticFeedbackEnabledSetting) {
        if (a.kind == Kind.NONE || a.kind == Kind.FRAMEWORK || a.kind == Kind.SEM_INDEX) return false;
        if (a.kind == Kind.SEM_VIBRATE) return hapticFeedbackEnabledSetting == 1;
        return true;                     // SOUND_EFFECT：AudioManager 内部按触摸音开关决定
    }
}
