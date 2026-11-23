package com.onesay.assistant.v2.ui.console;

/**
 * ★ feat/console-ui（设计稿 console-v1）：对话控制台配色。纯 Java（ARGB 字面量），JVM 可测。
 *
 * <p>深色按设计稿：底 #0E0F12、卡片 #1A1C21、主色蓝 #0A5BFF（按钮 / 麦克风 / 用户气泡）、苔绿 #84AE64 只表示「开着」，
 * 状态红黄绿灰。App 的「外观」是手动的深 / 浅（没有跟随系统），首页跟着它走：选了浅色就用下面这套对应的浅色。
 * 对话窗（②）和全屏聆听（③）是悬浮窗，原来就只有深色，这次也只做深色。
 */
public final class ConsolePalette {

    public final boolean dark;
    public final int bg, card, card2, line;
    public final int text, sub, dim;
    /** 主色蓝：按钮、麦克风、用户气泡。 */
    public final int blue;
    /** 蓝底上的字（白）。 */
    public final int onBlue;
    /** 苔绿：只表示「开着 / 好了」。 */
    public final int green;
    public final int yellow, red, gray;

    private ConsolePalette(boolean dark, int[] c) {
        this.dark = dark;
        int i = 0;
        bg = c[i++];
        card = c[i++];
        card2 = c[i++];
        line = c[i++];
        text = c[i++];
        sub = c[i++];
        dim = c[i++];
        blue = c[i++];
        onBlue = c[i++];
        green = c[i++];
        yellow = c[i++];
        red = c[i++];
        gray = c[i];
    }

    public static final ConsolePalette DARK = new ConsolePalette(true, new int[]{
            0xFF0E0F12, 0xFF1A1C21, 0xFF22252B, 0xFF2C2F36,
            0xFFF2F3F5, 0xFF9AA0A8, 0xFF6B7079,
            0xFF0A5BFF, 0xFFFFFFFF,
            0xFF84AE64, 0xFFE3B341, 0xFFE5534B, 0xFF6B7079});

    /** 浅色：同一套角色，压暗状态色保证白底上看得清（苔绿沿用控制台浅色的 #4E7A35）。 */
    public static final ConsolePalette LIGHT = new ConsolePalette(false, new int[]{
            0xFFF5F6F8, 0xFFFFFFFF, 0xFFEDEFF2, 0xFFE0E3E8,
            0xFF15171A, 0xFF5A6068, 0xFF737983,
            0xFF0A5BFF, 0xFFFFFFFF,
            0xFF4E7A35, 0xFF8A5F00, 0xFFC4332F, 0xFF737983});

    /** 跟控制台「外观」走（深色默认）。 */
    public static ConsolePalette of(boolean darkTheme) {
        return darkTheme ? DARK : LIGHT;
    }

    /** 状态等级 → 小圆点颜色：绿 = 开着、黄 = 要处理、红 = 坏了、灰 = 关 / 不知道。 */
    public int levelColor(ConsoleStatus.Level l) {
        if (l == null) return gray;
        switch (l) {
            case OK: return green;
            case WARN: return yellow;
            case BAD: return red;
            default: return gray;
        }
    }
}
