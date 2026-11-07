package com.onesay.assistant.v2.ui.console;

/**
 * [console R12] 控制台颜色 / 字号 token（方案 §6）。纯 Java（颜色是 ARGB 字面量，不调 android.graphics.Color），JVM 可测。
 *
 * <p>D2：默认深色，可切浅色；D3：苔绿（深色 {@code #84AE64}，浅色压暗到 {@code #4E7A35}，否则白底上只有 2.55:1）。
 * 灵动岛在两种模式下都保持纯黑（和 iOS 一致），见 {@link #island}。
 */
public final class ConsoleTheme {

    public static final String MODE_DARK = "dark";
    public static final String MODE_LIGHT = "light";

    public final boolean dark;
    public final int bg, surface, surface2, line;
    public final int text1, text2, text3;
    public final int accent, accentContainer, onAccent;
    public final int warn, warnContainer, danger, dangerContainer;
    public final int switchOffTrack, switchOffThumb;
    public final int island, islandText;

    private ConsoleTheme(boolean dark, int[] c) {
        this.dark = dark;
        int i = 0;
        bg = c[i++];
        surface = c[i++];
        surface2 = c[i++];
        line = c[i++];
        text1 = c[i++];
        text2 = c[i++];
        text3 = c[i++];
        accent = c[i++];
        accentContainer = c[i++];
        onAccent = c[i++];
        warn = c[i++];
        warnContainer = c[i++];
        danger = c[i++];
        dangerContainer = c[i++];
        switchOffTrack = c[i++];
        switchOffThumb = c[i++];
        island = c[i++];
        islandText = c[i];
    }

    public static final ConsoleTheme DARK = new ConsoleTheme(true, new int[]{
            0xFF121211, 0xFF1C1C1A, 0xFF262624, 0xFF2E2D2A,
            0xFFEDEAE4, 0xFFA3A09A, 0xFF8C8983,
            0xFF84AE64, 0xFF26321F, 0xFF10170B,
            0xFFE0A84B, 0xFF3A2F1C, 0xFFE5484D, 0xFF3A1D1E,
            0xFF3A3936, 0xFF8C8983,
            0xFF000000, 0xFFCFCBC4});

    public static final ConsoleTheme LIGHT = new ConsoleTheme(false, new int[]{
            0xFFF4F2EE, 0xFFFFFFFF, 0xFFECE9E3, 0xFFE4E0D8,
            0xFF1C1B19, 0xFF66635D, 0xFF75726C,
            0xFF4E7A35, 0xFFE3EDDA, 0xFFFFFFFF,
            0xFF845A0B, 0xFFF6EBD3, 0xFFC9363B, 0xFFF8E1E1,
            0xFFD6D2CA, 0xFFFFFFFF,
            0xFF000000, 0xFFCFCBC4});

    public static ConsoleTheme of(String mode) {
        return MODE_LIGHT.equals(mode) ? LIGHT : DARK;
    }

    /** 当前主题（MainActivity 读 prefs 后设置；QuickNotePage / 邮箱页 / 插件跟着用）。 */
    private static volatile ConsoleTheme sCurrent = DARK;

    public static ConsoleTheme current() {
        return sCurrent;
    }

    public static void setCurrent(ConsoleTheme t) {
        sCurrent = t == null ? DARK : t;
    }

    // ------------------------------------------------------------------ 字号（sp，方案 §6.3）
    public static final int SP_DISPLAY = 28, SP_HEADLINE = 24, SP_LARGE_TITLE = 26, SP_TITLE = 20, SP_ITEM = 16, SP_BODY = 14,
            SP_SUB = 13, SP_LABEL = 12;

    // ------------------------------------------------------------------ 圆角 / 间距（dp，方案 §6.4）
    public static final int R_HERO = 28, R_CARD = 24, R_BANNER = 18, R_FIELD = 12, R_INNER = 8;
    public static final int PAD_SCREEN = 16, PAD_CARD = 16, GAP_CARD = 12, GAP_GROUP = 16;

    // ------------------------------------------------------------------ WCAG 对比度（JVM 测 token 用）

    static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    public static double luminance(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
    }

    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** 带透明度的颜色（禁用态等）。 */
    public static int alpha(int argb, float a) {
        int al = Math.max(0, Math.min(255, Math.round(255 * a)));
        return (argb & 0x00FFFFFF) | (al << 24);
    }
}
