package com.onesay.assistant.v2.phone;

import com.onesay.assistant.v2.a11y.A11yState.Route;

/**
 * [exec-1124 C] 按动作选后端（纯 Java，JVM 可测）。以前是「整个任务二选一」：助手 ping 得通就全程用助手，
 * 哪怕无障碍开着、助手的触摸注入是坏的也不换。现在每个动作按大方向（{@link Route}）排一个先后顺序，
 * {@link FastScreenDriver} 按顺序一级一级试，前一级失败才走下一级。
 *
 * <p>思路参考 Mika（Apache-2.0）的点按降级顺序：节点点击 → 元素范围内无障碍手势 → 注入触摸。代码自己写的。
 */
public final class ActionRoute {
    private ActionRoute() {}

    /** 点按的各级。 */
    public static final String A11Y_NODE = "a11y_node", HELPER_NODE = "helper_node", A11Y_GESTURE = "a11y_gesture", HELPER_INJECT = "helper_inject";
    /** 按键的各级。 */
    public static final String A11Y_GLOBAL = "a11y_global", HELPER_KEY = "helper_key", A11Y_IME = "a11y_ime";
    /** 读屏 / 输入 / 滑动这类「整件事」的两条路。 */
    public static final String A11Y = "a11y", HELPER = "helper";

    private static final String[] NONE = new String[0];

    /**
     * 点按顺序。root 路径保持原来的「助手节点点击 → 助手注入」（安卓 8 上注入一直好使，不往中间插别的）；
     * 两者都在时以无障碍为主：无障碍节点点击 → 助手节点点击 → 无障碍手势 → 助手注入。
     */
    public static String[] tap(Route r) {
        if (r == null) return NONE;
        switch (r) {
            case A11Y_FIRST: return new String[]{A11Y_NODE, HELPER_NODE, A11Y_GESTURE, HELPER_INJECT};
            case A11Y_ONLY: return new String[]{A11Y_NODE, A11Y_GESTURE};
            case HELPER:
            case HELPER_ROOT: return new String[]{HELPER_NODE, HELPER_INJECT};
            default: return NONE;
        }
    }

    /** 这条路线的点按里有没有某一级。 */
    public static boolean tapUses(Route r, String step) {
        for (String s : tap(r)) if (s.equals(step)) return true;
        return false;
    }

    /** 读屏 / 输入 / 滑动 / 长按 / 等页面安静：先走哪条、再走哪条。 */
    public static String[] whole(Route r) {
        if (r == null) return NONE;
        switch (r) {
            case A11Y_FIRST: return new String[]{A11Y, HELPER};
            case A11Y_ONLY: return new String[]{A11Y};
            case HELPER:
            case HELPER_ROOT: return new String[]{HELPER};
            default: return NONE;
        }
    }

    /**
     * 按键顺序。返回 / 主页：无障碍连着就先用它的全局动作（不经过触摸注入，也不再只在插件开着时才用），助手按键兜底；
     * 回车 / 搜索：助手按键优先，无障碍的输入法动作兜底；删除：只有助手做得了。
     *
     * @param a11yUsable 无障碍服务此刻连着且没被开发者关掉
     */
    public static String[] key(Route r, boolean a11yUsable, String key) {
        if (r == null || r == Route.NONE || key == null) return NONE;
        boolean helper = r != Route.A11Y_ONLY;
        boolean a = a11yUsable || r == Route.A11Y_ONLY;
        if ("back".equals(key) || "home".equals(key)) {
            if (a && helper) return new String[]{A11Y_GLOBAL, HELPER_KEY};
            return a ? new String[]{A11Y_GLOBAL} : new String[]{HELPER_KEY};
        }
        if ("enter".equals(key) || "search".equals(key)) {
            if (a && helper) return new String[]{HELPER_KEY, A11Y_IME};
            return a ? new String[]{A11Y_IME} : new String[]{HELPER_KEY};
        }
        if ("delete".equals(key)) return helper ? new String[]{HELPER_KEY} : NONE;
        return NONE;
    }

    /** 助手按键码（不认识 = -1）。 */
    public static int helperKeyCode(String key) {
        if ("back".equals(key)) return 4;
        if ("home".equals(key)) return 3;
        if ("enter".equals(key)) return 66;
        if ("search".equals(key)) return 84;
        if ("delete".equals(key)) return 67;
        return -1;
    }
}
