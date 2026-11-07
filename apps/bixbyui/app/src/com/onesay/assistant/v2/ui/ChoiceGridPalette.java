package com.onesay.assistant.v2.ui;

/**
 * [r2/bike-chooser] 应用选择网格的颜色 / 尺寸 token（纯 Java，ARGB 字面量，JVM 可测对比度）。
 *
 * <p>沿用现在的设计语言：苔绿（唤醒卡 MOSS {@code #8CD27A} / 控制台 {@code #84AE64}，浅色压暗到 {@code #4E7A35}）+ 暖墨灰文字。
 * <ul>
 *   <li><b>深色</b>：格子直接放在唤醒卡（{@code #161619→#09090B}）上，白 8.5% 底，和动作芯片 v2 的次芯片同一材质；</li>
 *   <li><b>浅色</b>：唤醒卡本身不跟主题（一直是深色玻璃卡），所以浅色时网格自带一块暖纸底板（{@code #F4F2EE}，控制台浅色 bg），
 *       格子是白卡 + 细描边 —— 在深色卡上是一块「浅色插页」，文字对比按这块底板算。</li>
 * </ul>
 * 主题跟控制台「外观」开关（prefs bixby_console / theme），debug 可强制（TestRunReceiver --es choicegrid light|dark）。
 */
public final class ChoiceGridPalette {

    public final boolean dark;
    /** 网格底板（深色 = 0 透明，直接透出唤醒卡）。 */
    public final int panel;
    /** 用来算对比度的「实际背景」（深色 = 唤醒卡上沿色）。 */
    public final int surface;
    public final int cell, cellPressed, cellStroke;
    public final int name, action, actionDownload;
    public final int link, linkPressed;
    public final int placeholderBg, placeholderText;

    private ChoiceGridPalette(boolean dark, int panel, int surface, int cell, int cellPressed, int cellStroke, int name,
                              int action, int actionDownload, int link, int linkPressed, int placeholderBg, int placeholderText) {
        this.dark = dark;
        this.panel = panel;
        this.surface = surface;
        this.cell = cell;
        this.cellPressed = cellPressed;
        this.cellStroke = cellStroke;
        this.name = name;
        this.action = action;
        this.actionDownload = actionDownload;
        this.link = link;
        this.linkPressed = linkPressed;
        this.placeholderBg = placeholderBg;
        this.placeholderText = placeholderText;
    }

    public static final ChoiceGridPalette DARK = new ChoiceGridPalette(true,
            0x00000000, 0xFF161619,
            0x16FFFFFF, 0x2BFFFFFF, 0x00000000,
            0xFFF0EEEA,
            0xFFA9D894,      // 打开：苔绿（比 MOSS 提亮一点，小字也够 4.5:1）
            0xFFB2AFAA,      // 下载：暖灰 T2
            0xFF7DB4FF,      // 链接蓝
            0xFFB5D3FF,
            0x2E8CD27A, 0xFFB9E2A6);

    public static final ChoiceGridPalette LIGHT = new ChoiceGridPalette(false,
            0xFFF4F2EE, 0xFFF4F2EE,
            0xFFFFFFFF, 0xFFE9E6E0, 0xFFE4E0D8,
            0xFF1C1B19,
            0xFF4E7A35,      // 打开：控制台浅色苔绿
            0xFF66635D,      // 下载：暖灰
            0xFF1A5FB4,      // 链接蓝
            0xFF0F3F7A,
            0xFFE3EDDA, 0xFF3F6A28);

    public static ChoiceGridPalette of(boolean light) {
        return light ? LIGHT : DARK;
    }

    // ------------------------------------------------------------------ 尺寸（dp / sp）
    /** 格子最小高度（触控区 ≥ 48dp，用户要「触控区足够」）。 */
    public static final int CELL_MIN_H = 64;
    public static final int CELL_RADIUS = 18;
    public static final int CELL_GAP = 10;
    public static final int ICON = 40;          // 图标比文字大
    public static final int ICON_RADIUS = 11;
    public static final float SP_NAME = 15f;
    public static final float SP_ACTION = 12f;
    public static final int ACTION_ICON = 12;
    /** 「打开应用商店查看更多」行高（触控区）。 */
    public static final int LINK_MIN_H = 44;
    public static final float SP_LINK = 14f;
    public static final int PANEL_RADIUS = 22;
    public static final int PANEL_PAD = 10;

    // ------------------------------------------------------------------ 对比度（与 ConsoleTheme 同算法）

    /** 半透明前景先叠到背景上再算对比度。 */
    public static int over(int fg, int bg) {
        int a = (fg >>> 24) & 0xFF;
        if (a == 0xFF) return fg;
        float k = a / 255f;
        int r = Math.round(((fg >> 16) & 0xFF) * k + ((bg >> 16) & 0xFF) * (1 - k));
        int g = Math.round(((fg >> 8) & 0xFF) * k + ((bg >> 8) & 0xFF) * (1 - k));
        int b = Math.round((fg & 0xFF) * k + (bg & 0xFF) * (1 - k));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 格子的实际底色（cell 叠在 panel/surface 上）。 */
    public int cellOpaque() {
        int base = (panel >>> 24) == 0 ? surface : panel;
        return over(cell, base);
    }

    public double contrast(int fg, int bg) {
        return com.onesay.assistant.v2.ui.console.ConsoleTheme.contrast(over(fg, bg), bg);
    }
}
