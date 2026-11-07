package com.onesay.assistant.v2.entry.keywake;

import com.onesay.plugin.api.entry.EntryTrigger;

/**
 * 「按键唤醒」（Key wake）的纯 Java 部分：触发方式、动作、时间窗与动作路由（JVM 单测 KeyWakeTest）。
 *
 * <p>入口本身默认关（{@link com.onesay.assistant.v2.entry.BuiltinEntries#KEY_WAKE}）；打开后默认触发方式 = 双击电源键，
 * 默认动作 = 语音唤起。检测托管在 OneSay 的无障碍服务（读屏 / 识屏同一个服务）里：
 * <ul>
 *   <li>{@link Trigger#POWER_DOUBLE}：App 拿不到 KEYCODE_POWER（无障碍按键过滤也收不到），所以用「熄屏 / 亮屏」广播对
 *       在时间窗（默认 {@link #DEFAULT_WINDOW_MS} ms，可调）内成对出现来判定；通话中（距离传感器熄屏）与超时熄屏不算；</li>
 *   <li>{@link Trigger#VOLUME_UP_LONG} / {@link Trigger#VOLUME_DOWN_LONG}：无障碍按键过滤（flagRequestFilterKeyEvents），
 *       长按 ≥ {@link #LONG_PRESS_MS} ms 触发，短按照常调音量；</li>
 *   <li>{@link Trigger#RAW_INPUT}：可选精确路径 —— root / Shizuku 执行后端在线时，由 {@code tools/kdaemon/key_daemon.sh}
 *       读 {@code getevent} 原始按键事件，写 App 私有目录的触发文件（格式见 {@code KeyTrigger}）。</li>
 * </ul>
 * 很多 ROM 把「双击电源键」给了相机快速启动，开启前请在系统设置里关掉，否则两边会抢。
 */
public final class KeyWake {
    private KeyWake() {}

    public static final String PREFS = "onesay_key_wake";
    public static final String K_TRIGGER = "trigger";
    public static final String K_ACTION = "action";
    public static final String K_WINDOW = "window_ms";

    public static final int DEFAULT_WINDOW_MS = 600, MIN_WINDOW_MS = 300, MAX_WINDOW_MS = 1200;
    public static final int LONG_PRESS_MS = 600;

    public static final String LABEL = "按键唤醒";
    public static final String DESCRIPTION = "用实体按键唤起 OneSay（默认：双击电源键 → 语音唤起）。需要在系统无障碍里打开「OneSay 读屏与按键唤醒」。"
            + "注意：很多手机把双击电源键设成了快速打开相机，请先在系统设置里关掉，避免冲突。";

    public enum Trigger {
        POWER_DOUBLE("power_double", "双击电源键"),
        VOLUME_UP_LONG("volume_up_long", "长按音量上键"),
        VOLUME_DOWN_LONG("volume_down_long", "长按音量下键"),
        RAW_INPUT("raw_input", "原始按键事件（root / Shizuku，需部署 key_daemon）");

        public final String key, label;
        Trigger(String key, String label) { this.key = key; this.label = label; }
    }

    public enum Action {
        VOICE("voice", "语音唤起"),
        SCREEN("screen", "识屏"),
        TEXT("text", "文字面板");

        public final String key, label;
        Action(String key, String label) { this.key = key; this.label = label; }
    }

    public static Trigger parseTrigger(String s) {
        if (s != null) for (Trigger t : Trigger.values()) if (t.key.equalsIgnoreCase(s.trim())) return t;
        return Trigger.POWER_DOUBLE;
    }

    public static Action parseAction(String s) {
        if (s != null) for (Action a : Action.values()) if (a.key.equalsIgnoreCase(s.trim())) return a;
        return Action.VOICE;
    }

    public static int clampWindow(int ms) {
        if (ms <= 0) return DEFAULT_WINDOW_MS;
        return Math.max(MIN_WINDOW_MS, Math.min(MAX_WINDOW_MS, ms));
    }

    /** 动作 → 入口唤起方式（其他通用入口共用这张表）。 */
    public static EntryTrigger.Mode modeFor(Action a) {
        if (a == Action.SCREEN) return EntryTrigger.Mode.SCREEN;
        if (a == Action.TEXT) return EntryTrigger.Mode.TEXT;
        return EntryTrigger.Mode.VOICE;
    }

    /** 识屏动作提交给面板的指令（与 VisionCaps 的「识屏」说法一致）。 */
    public static final String SCREEN_COMMAND = "识屏";

    /** 这个触发方式要不要无障碍按键过滤。 */
    public static boolean needsKeyFilter(Trigger t) {
        return t == Trigger.VOLUME_UP_LONG || t == Trigger.VOLUME_DOWN_LONG;
    }

    /** 这个触发方式要不要监听熄屏 / 亮屏广播。 */
    public static boolean needsScreenEvents(Trigger t) {
        return t == Trigger.POWER_DOUBLE;
    }

    /**
     * 超时熄屏判定：距离上次界面活动已接近系统熄屏超时（留 1.5 s 余量）就当作超时熄屏，不算一次按键。
     * @param quietMs   距最近一次界面事件的毫秒数（未知传 Long.MAX_VALUE 以外的负数 = 不判定）
     * @param timeoutMs 系统 screen_off_timeout（拿不到传 ≤0 = 不判定）
     */
    public static boolean likelyTimeout(long quietMs, long timeoutMs) {
        if (quietMs < 0 || timeoutMs <= 0 || quietMs == Long.MAX_VALUE) return false;
        return quietMs >= timeoutMs - 1500;
    }
}
