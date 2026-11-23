package com.onesay.assistant.v2.wake;

/**
 * 唤醒词「什么时候开麦听」—— 纯 Java 判定（JVM 单测入口），WakeService 读循环据此决定要不要 stop() 麦克风。
 *
 * <p>背景：唤醒词监听（KWS）要一直开着麦克风，Android 12+ 的隐私指示（状态栏绿点 / Flyme 的麦克风胶囊）就会一直亮。
 * 只要真的在录，指示灯就一定亮（系统按 AppOps RECORD_AUDIO 的 active 状态画，App 关不掉，也不该关）；
 * 唯一的办法是<b>不需要听的时候真的停录</b>。这里决定「不需要听」的时机。</p>
 *
 * <h3>模式</h3>
 * <ul>
 *   <li>{@link #MODE_UNLOCKED}（默认）：亮屏且已解锁才听；息屏 / 锁屏立即停录，解锁后恢复。</li>
 *   <li>{@link #MODE_AFTER_UNLOCK}：同上，但每次解锁后只听 N 分钟（{@link #minutes}），过后停录，下次解锁再听。</li>
 *   <li>{@link #MODE_CHARGING}：只在充电时听（不管亮屏与否，适合放床头 / 桌上充电时喊）。</li>
 *   <li>{@link #MODE_ALWAYS}：一直听（旧行为，含息屏低功耗听），指示灯一直亮。</li>
 *   <li>{@link #MODE_OFF}：不听唤醒词，只用快捷开关 / 侧键 / 手势 / 桌面入口叫出来。</li>
 * </ul>
 * 另有<b>快捷开关</b>（{@link #quickOff}）：一键临时关掉唤醒词监听，优先于模式；再点恢复。
 *
 * <p>无论哪种模式，用按键 / 快捷开关 / 手势叫出来的那一句（捕获窗开着）照常开麦，说完再停 —— 由调用方把捕获窗状态传进
 * {@link #micOpen}。</p>
 */
public final class MicListenPolicy {

    public static final String MODE_UNLOCKED = "unlocked";
    public static final String MODE_AFTER_UNLOCK = "after_unlock";
    public static final String MODE_CHARGING = "charging";
    public static final String MODE_ALWAYS = "always";
    public static final String MODE_OFF = "off";

    public static final String DEFAULT_MODE = MODE_UNLOCKED;
    public static final int DEFAULT_MINUTES = 10;
    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 240;

    /** 设置页顺序（id, 标题, 说明）。 */
    public static final String[][] MODES = {
            {MODE_UNLOCKED, "亮屏并解锁时", "锁屏或息屏就停止监听，麦克风指示灯随之熄灭（推荐）"},
            {MODE_AFTER_UNLOCK, "解锁后一段时间", "每次解锁后只听几分钟，之后停止监听"},
            {MODE_CHARGING, "仅充电时", "插着电才听，息屏也听"},
            {MODE_ALWAYS, "一直监听", "息屏也听；麦克风指示灯会一直亮着"},
            {MODE_OFF, "关闭唤醒词", "只用快捷开关、按键或手势叫出来"},
    };

    private MicListenPolicy() {}

    /** 不认识的模式回默认。 */
    public static String normalizeMode(String m) {
        if (m == null) return DEFAULT_MODE;
        for (String[] r : MODES) if (r[0].equals(m)) return m;
        return DEFAULT_MODE;
    }

    public static int clampMinutes(int m) {
        return m < MIN_MINUTES ? MIN_MINUTES : (m > MAX_MINUTES ? MAX_MINUTES : m);
    }

    /** 某一时刻的环境。 */
    public static final class State {
        public final boolean screenOn;
        public final boolean keyguardLocked;
        public final boolean charging;
        /** 最近一次解锁（USER_PRESENT，或服务启动时已解锁）的 elapsedRealtime；从没解锁过 = Long.MIN_VALUE。 */
        public final long unlockedAt;
        public final long now;

        public State(boolean screenOn, boolean keyguardLocked, boolean charging, long unlockedAt, long now) {
            this.screenOn = screenOn;
            this.keyguardLocked = keyguardLocked;
            this.charging = charging;
            this.unlockedAt = unlockedAt;
            this.now = now;
        }
    }

    /** 判定结果：听不听、为什么、什么时候需要再判一次（只有限时模式有；-1 = 不用定时，等事件）。 */
    public static final class Decision {
        public final boolean listen;
        public final String reason;
        public final long recheckAt;

        Decision(boolean listen, String reason, long recheckAt) {
            this.listen = listen;
            this.reason = reason;
            this.recheckAt = recheckAt;
        }

        @Override public String toString() {
            return (listen ? "听" : "不听") + "（" + reason + (recheckAt >= 0 ? "，" + recheckAt + " 再判" : "") + "）";
        }
    }

    /** 唤醒词该不该在听（不含捕获窗；捕获窗见 {@link #micOpen}）。 */
    public static Decision decide(String mode, int minutes, boolean quickOff, State s) {
        mode = normalizeMode(mode);
        if (quickOff) return new Decision(false, "快捷开关已关", -1);
        if (s == null) return new Decision(true, "无状态，按旧行为", -1);
        boolean unlockedOn = s.screenOn && !s.keyguardLocked;
        if (MODE_ALWAYS.equals(mode)) return new Decision(true, "一直监听", -1);
        if (MODE_OFF.equals(mode)) return new Decision(false, "唤醒词已关闭", -1);
        if (MODE_CHARGING.equals(mode)) {
            return s.charging ? new Decision(true, "正在充电", -1) : new Decision(false, "没在充电", -1);
        }
        if (MODE_AFTER_UNLOCK.equals(mode)) {
            if (!unlockedOn) return new Decision(false, s.screenOn ? "锁屏中" : "息屏", -1);
            if (s.unlockedAt == Long.MIN_VALUE) return new Decision(false, "还没解锁过", -1);
            long until = s.unlockedAt + clampMinutes(minutes) * 60000L;
            if (s.now < until) return new Decision(true, "解锁后 " + clampMinutes(minutes) + " 分钟内", until);
            return new Decision(false, "解锁已超过 " + clampMinutes(minutes) + " 分钟", -1);
        }
        // MODE_UNLOCKED
        if (unlockedOn) return new Decision(true, "亮屏且已解锁", -1);
        return new Decision(false, s.screenOn ? "锁屏中" : "息屏", -1);
    }

    /** 读循环用：唤醒词在听，或者有一句正在收（按键 / 手势叫出来的捕获窗、声纹注册），麦克风就开。 */
    public static boolean micOpen(Decision d, boolean captureOpen, boolean enrolling) {
        return captureOpen || enrolling || (d != null && d.listen);
    }

    /** 息屏低功耗听只在「一直监听」「仅充电」下才有意义；其他模式息屏前就停录了。 */
    public static boolean listensWhileScreenOff(String mode) {
        mode = normalizeMode(mode);
        return MODE_ALWAYS.equals(mode) || MODE_CHARGING.equals(mode);
    }

    /** 前台通知 / 快捷开关副标题。 */
    public static String statusText(Decision d) {
        if (d == null) return "";
        return d.listen ? "唤醒词监听中" : "唤醒词未在监听（" + d.reason + "）";
    }

    /** 设置页上模式的标题。 */
    public static String title(String mode) {
        mode = normalizeMode(mode);
        for (String[] r : MODES) if (r[0].equals(mode)) return r[1];
        return mode;
    }
}
