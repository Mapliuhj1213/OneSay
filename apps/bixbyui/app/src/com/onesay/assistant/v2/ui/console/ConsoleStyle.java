package com.onesay.assistant.v2.ui.console;

import com.onesay.assistant.v2.exec.ControlColor;

/**
 * ★ console-align 2026-11-12：手机控制台与电脑端设置页共用的写法（纯 Java，JVM 可测；电脑端 index.html 里有同一张表，见 docs/CONSOLE.md）。
 *
 * <ul>
 *   <li><b>危险项</b>：按钮文字是 {@link #DANGER_VERBS} 里的动词 → 红字红底胶囊；点了先弹确认框「X？」+ 一句后果 + 红色动词按钮 + 「取消」；</li>
 *   <li><b>状态徽标</b>：替你操作的四种状态与控屏颜色（{@link ControlColor}）同色——操作中蓝紫、等确认琥珀、你接手绿、已停止红；</li>
 *   <li><b>尺寸</b>：卡片圆角 22、分组标题 13 中粗 + 左侧 4×14 色条、行标题 16、说明 14（与电脑端 15 / 12.5 px 同比例）。</li>
 * </ul>
 */
public final class ConsoleStyle {

    private ConsoleStyle() {}

    /** 危险动作（两端同一张表）。 */
    public static final String[] DANGER_VERBS = {"清空", "清除", "删除", "撤销", "解除", "解除配对", "卸载", "关机", "睡眠", "换一把钥匙", "忘掉", "重置", "停止"};

    public static boolean isDanger(String action) {
        if (action == null) return false;
        String a = action.trim();
        for (String v : DANGER_VERBS) if (a.equals(v) || a.startsWith(v)) return true;
        return false;
    }

    /** 替你操作的状态徽标：{文字, 颜色 0xRRGGBB, 网页色值}。顺序 = 操作中 / 等确认 / 你接手 / 已停止。 */
    public static final String RUN = "操作中", WAIT = "等确认", USER = "你接手", STOP = "已停止";

    public static int badgeRgb(String state) {
        if (WAIT.equals(state)) return ControlColor.WAIT_USER;
        if (USER.equals(state)) return ControlColor.USER;
        if (STOP.equals(state)) return ControlColor.STOPPED;
        return ControlColor.AGENT;
    }

    public static String hex(int rgb) {
        return String.format(java.util.Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** 四个状态（文字 + 颜色），控制台「替你操作 › 控屏颜色」一组照这个画。 */
    public static final String[] BADGES = {RUN, WAIT, USER, STOP};

    /** 每个状态的一句说明（两端同文）。 */
    public static String badgeSub(String state) {
        if (WAIT.equals(state)) return "弹确认卡等你点";
        if (USER.equals(state)) return "你点了接管 / 按了返回或主页键";
        if (STOP.equals(state)) return "点了停止，覆盖层马上撤掉";
        return "助手正在替你点按";
    }

    /** 卡片圆角（dp，电脑端 22px）。 */
    public static final int R_CARD = 22;
}
