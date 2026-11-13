package com.onesay.assistant.v2.exec;

/**
 * ★ borrow-1112：执行时屏幕边框（防误触护罩的光晕 / 细边框）和灵动岛描边的颜色 = 现在谁在控屏（纯 Java，JVM 可测）。
 * <ul>
 *   <li>{@link Who#AGENT} 助手在操作：蓝紫（光晕呼吸）；</li>
 *   <li>{@link Who#WAIT_USER} 等你确认 / 等你决定：琥珀色；</li>
 *   <li>{@link Who#USER} 你接手了（点了接管 / 按了返回或主页键 / 在写补充）：绿色细边框；</li>
 *   <li>{@link Who#STOPPED} 已停止：红色（马上随覆盖层撤掉）。</li>
 * </ul>
 * 开关「控屏状态颜色」（prefs bixby_phone / control_color，默认开；kill 文件 control_color.off）。关 = 原来的颜色（光晕蓝、细边框浅蓝），岛不描边。
 *
 * <p>思路借鉴 Agent-VScreen（https://github.com/kilolonion/agent-vscreen，MIT）悬浮窗边框颜色表示谁在控制屏幕；配色和代码是自己写的。
 */
public final class ControlColor {

    private ControlColor() {}

    public enum Who {
        AGENT("助手在操作"), WAIT_USER("等你确认"), USER("你在操作"), STOPPED("已停止");

        public final String zh;

        Who(String zh) {
            this.zh = zh;
        }
    }

    /** 颜色（不含 alpha，0xRRGGBB）。 */
    public static final int AGENT = 0x7C7CFF, WAIT_USER = 0xFFB020, USER = 0x34C759, STOPPED = 0xFF453A;
    /** 关掉开关时的原配色：光晕 / 细边框。 */
    public static final int LEGACY_GLOW = 0x6FA8FF, LEGACY_THIN = 0x8FB8FF;

    public static final String PREF = "bixby_phone", KEY = "control_color", KILL = "control_color.off";
    public static final boolean DEFAULT_ON = true;
    public static final com.onesay.assistant.v2.kernel.FeatureSwitch SWITCH = new com.onesay.assistant.v2.kernel.FeatureSwitch(PREF, KEY, DEFAULT_ON, KILL);

    /** 护罩该用的颜色。thin = 细边框（不拦触摸）状态。 */
    public static int shieldRgb(Who who, boolean thin, boolean enabled) {
        if (!enabled || who == null) return thin ? LEGACY_THIN : LEGACY_GLOW;
        return rgb(who);
    }

    public static int rgb(Who who) {
        if (who == null) return LEGACY_GLOW;
        switch (who) {
            case WAIT_USER: return WAIT_USER;
            case USER: return USER;
            case STOPPED: return STOPPED;
            default: return AGENT;
        }
    }

    /** 岛描边颜色（0 = 不描边：开关关 / 没在执行）。 */
    public static int islandRing(Who who, boolean enabled) {
        if (!enabled || who == null) return 0;
        return 0xFF000000 | rgb(who);
    }

    /** 暂停的原因 → 是用户在操作，还是在等用户（代理自己停下来问：卡住 / 预算到了 / 要你决定）。 */
    public static Who pausedWho(boolean userInitiated) {
        return userInitiated ? Who.USER : Who.WAIT_USER;
    }
}
