package com.onesay.assistant.v2.exec;

import com.onesay.assistant.v2.a11y.A11yState.Route;

/**
 * [exec-1124 B] 执行护罩用哪种窗口（纯 Java，JVM 可测）。
 *
 * <p>真机（魅族，安卓 16）：执行时盖在屏幕上的护罩是别家 App 的可触摸悬浮窗，新安卓据此把下面 App 被盖住的控件标成
 * 「用户看不见」，读屏跳过它们 → 一进执行模式只读得到状态栏。无障碍服务自己挂的浮层（无障碍浮层类型）不算进这个遮挡，
 * 不可触摸的窗口也不算。所以（用户 2026-11-24 拍板）：
 * <ul>
 *   <li>{@link Mode#LEGACY}：root 助手，或安卓 8.1 及以下 → 原样（可触摸的普通悬浮窗，吞误触、注入点开小孔）；</li>
 *   <li>{@link Mode#A11Y_OVERLAY}：无障碍服务开着 → 护罩改由无障碍服务挂（无障碍浮层类型），照样吞误触、开孔逻辑不变；</li>
 *   <li>{@link Mode#GLOW_ONLY}：只有 Shizuku（无障碍没开）→ 不要可触摸护罩，只留边缘光晕：不接触摸、不挡读屏，误触不拦。</li>
 * </ul>
 *
 * <p>思路参考 Mika（Apache-2.0）用无障碍浮层画主屏浮层的做法，代码自己写的。
 */
public final class ShieldMode {
    private ShieldMode() {}

    public enum Mode { LEGACY, A11Y_OVERLAY, GLOW_ONLY }

    /** 安卓 8.1（API 27）及以下保持原样。 */
    public static final int LEGACY_MAX_SDK = 27;

    /** WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY（API 22 常量，按字面写）。 */
    public static final int TYPE_ACCESSIBILITY_OVERLAY = 2032;

    /**
     * @param route          这次任务的后端路线（{@link com.onesay.assistant.v2.phone.FastScreenDriver#route()}）
     * @param sdk            Build.VERSION.SDK_INT
     * @param a11yConnected  无障碍服务此刻连着（能拿到它的窗口管理）
     */
    public static Mode choose(Route route, int sdk, boolean a11yConnected) {
        if (route == Route.HELPER_ROOT || sdk <= LEGACY_MAX_SDK) return Mode.LEGACY;
        if (a11yConnected && (route == Route.A11Y_FIRST || route == Route.A11Y_ONLY)) return Mode.A11Y_OVERLAY;
        if (route == Route.HELPER || route == Route.A11Y_FIRST || route == Route.A11Y_ONLY) return Mode.GLOW_ONLY;
        return Mode.LEGACY;   // 没走 chooseBackend（NONE / 未知）：不改原行为
    }

    /** 这种模式拦不拦用户的触摸（GLOW_ONLY 不拦：不用开孔，也没有误触提示）。 */
    public static boolean blocksTouch(Mode m) {
        return m != Mode.GLOW_ONLY;
    }

    /**
     * 无障碍浮层被系统拆掉（Flyme 周期性解绑 / 重绑无障碍服务，服务一断它挂的窗口就没了）之后怎么办：
     * 新实例在等待时间内连上 → 挂回新实例；等不到 → 退成只留光晕（不能退回普通可触摸悬浮窗，那样又会挡读屏）。
     */
    public static Mode afterA11yLost(boolean newInstance) {
        return newInstance ? Mode.A11Y_OVERLAY : Mode.GLOW_ONLY;
    }

    /** 无障碍浮层被拆后最多等多久新实例（和 RebindWait 一致）。 */
    public static final long REATTACH_WAIT_MS = 2000;

    /** 日志用。 */
    public static String zh(Mode m) {
        if (m == Mode.A11Y_OVERLAY) return "无障碍浮层护罩";
        if (m == Mode.GLOW_ONLY) return "只留光晕（不拦触摸）";
        return "原护罩";
    }
}
