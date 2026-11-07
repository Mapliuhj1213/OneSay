package com.onesay.assistant.v2.ui.wake;

/**
 * [R13 D4=A] 收在岛上的任务带着结论收工时：结论短 → 岛就地展开结果卡（约 6 s 后收起）；结论长 → 从岛「流」回底部卡片
 * （纯函数，JVM 可测）。
 */
public final class IslandResultPolicy {

    private IslandResultPolicy() {}

    /** 短结论：≤ 2 行、≤ 60 字（岛展开卡一行约 20 字，主句 2 行 + 次行 2 行刚好放下）。 */
    public static final int SHORT_MAX_CHARS = 60;
    public static final int SHORT_MAX_LINES = 2;

    /**
     * @param answer   结论文字（"alarm" = 闹钟卡标记：要看卡片，永远不算短）
     * @param newUi    新唤醒界面开着（旧界面保持 R12 行为：一律挂回面板）
     */
    public static boolean onIsland(String answer, boolean newUi) {
        if (!newUi || answer == null) return false;
        String a = answer.trim();
        if (a.length() == 0 || "alarm".equals(a)) return false;
        if (a.length() > SHORT_MAX_CHARS) return false;
        int lines = 1;
        for (int i = 0; i < a.length(); i++) if (a.charAt(i) == '\n') lines++;
        return lines <= SHORT_MAX_LINES;
    }

    /** 岛结果卡的 {主句, 次行}：沿用卡片的首行拆分；没有首行时整段当主句。 */
    public static String[] split(String answer) {
        String[] hb = WakeCopy.splitHeadline(answer);
        if (hb[0] == null) return new String[] {hb[1], null};
        return hb;
    }
}
