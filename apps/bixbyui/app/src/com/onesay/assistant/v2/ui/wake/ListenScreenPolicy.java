package com.onesay.assistant.v2.ui.wake;

/**
 * ★ feat/console-ui（设计稿 ③「说话中」）：全屏聆听什么时候出现、三个按钮做什么、按钮摆在哪。纯 Java，JVM 可测。
 *
 * <p>悬浮按钮、快捷开关、桌面小部件、默认助理、唤醒词、首页麦克风……所有「开始听」最后都落到卡片窗的 LISTENING 阶段，
 * 所以只要在 LISTENING 时盖上这一层，所有入口自然都进这个画面。说完（识别出一句话）→ 这一层收掉，结果进对话窗（②）同一个会话。
 * 设置「对话控制台」关掉 → 不盖这一层，回到原来的聆听卡片（旧代码路径原样保留）。
 */
public final class ListenScreenPolicy {

    private ListenScreenPolicy() {}

    /**
     * 这一刻盖不盖全屏聆听。
     *
     * @param consoleOn  设置「对话控制台」开着
     * @param v2         新界面
     * @param fullWindow 固定全屏卡片窗（wrap 窗没有全屏）
     * @param stage      卡片阶段
     * @param shownThisRound 这一轮聆听已经盖过（出错时留着说原因、给「重说」；收起时跟着淡出）
     * @param userClosed 用户在出错画面点了停止，这一轮不再盖
     */
    public static boolean shows(boolean consoleOn, boolean v2, boolean fullWindow, WakeStage.Stage stage,
                                boolean shownThisRound, boolean userClosed) {
        if (!consoleOn || !v2 || !fullWindow || stage == null) return false;
        if (stage == WakeStage.Stage.LISTENING) return true;
        // 出错：留着说原因、给「重说」；收起中：跟着整窗淡出（不露出底下那张小卡片）
        return (stage == WakeStage.Stage.ERROR || stage == WakeStage.Stage.DISMISSING) && shownThisRound && !userClosed;
    }

    // ------------------------------------------------------------------ 三个按钮 + 左上返回 + 右上键盘

    public enum Button { NONE, REDO, STOP, TYPE, BACK, KEYBOARD }

    public enum Action {
        NONE,
        /** 作废这一句，马上重新开始听（同一个会话，不清对话）。 */
        RESTART_LISTEN,
        /** 停止收音，不发；有对话 / 上一轮结果就回到对话窗，没有就收起。 */
        STOP_LISTEN,
        /** 出错画面：对话窗开着 → 只收掉这一层回对话；没有对话 → 收起面板。 */
        CLOSE_TO_CHAT,
        CLOSE_PANEL,
        /** 停止收音，打开对话窗打字（弹键盘）。 */
        TYPE
    }

    public static Action action(Button b, WakeStage.Stage stage, boolean chatActive) {
        if (b == null || b == Button.NONE) return Action.NONE;
        if (stage != WakeStage.Stage.LISTENING && stage != WakeStage.Stage.ERROR) return Action.NONE;   // 收起中：不再接按钮
        switch (b) {
            case REDO:
                return Action.RESTART_LISTEN;
            case TYPE:
            case KEYBOARD:
                return Action.TYPE;
            case STOP:
            case BACK:
                if (stage == WakeStage.Stage.LISTENING) return Action.STOP_LISTEN;
                return chatActive ? Action.CLOSE_TO_CHAT : Action.CLOSE_PANEL;
            default:
                return Action.NONE;
        }
    }

    // ------------------------------------------------------------------ 布局（像素；dens = 每 dp 多少像素）

    /** 底部三个圆钮直径 / 中间停止钮直径（dp）。 */
    public static final float BTN_DP = 56f, STOP_DP = 64f;
    /** 圆钮中心距底边（dp，不含导航栏）。 */
    public static final float BTN_BOTTOM_DP = 70f;
    /** 左右两颗离屏幕边的水平距离（dp，到圆心）。 */
    public static final float BTN_SIDE_DP = 76f;
    /** 顶上返回 / 键盘小圆钮：直径、圆心距上边、距左右边。 */
    public static final float TOP_DP = 40f, TOP_Y_DP = 62f, TOP_X_DP = 38f;
    /** 圆球半径（dp），随音量最多放大多少。 */
    public static final float ORB_DP = 66f, ORB_GROW = 0.14f;

    /** 返回 {cx, cy, r}。 */
    public static float[] button(Button b, int w, int h, float dens, int bottomInsetPx) {
        float cy = h - bottomInsetPx - BTN_BOTTOM_DP * dens;
        switch (b) {
            case REDO: return new float[]{BTN_SIDE_DP * dens, cy, BTN_DP * dens / 2f};
            case STOP: return new float[]{w / 2f, cy, STOP_DP * dens / 2f};
            case TYPE: return new float[]{w - BTN_SIDE_DP * dens, cy, BTN_DP * dens / 2f};
            case BACK: return new float[]{TOP_X_DP * dens, TOP_Y_DP * dens, TOP_DP * dens / 2f};
            case KEYBOARD: return new float[]{w - TOP_X_DP * dens, TOP_Y_DP * dens, TOP_DP * dens / 2f};
            default: return null;
        }
    }

    /** 点在哪颗钮上（圆外扩 10dp 的点按余量）。 */
    public static Button hit(float x, float y, int w, int h, float dens, int bottomInsetPx) {
        Button[] all = {Button.STOP, Button.REDO, Button.TYPE, Button.BACK, Button.KEYBOARD};
        for (Button b : all) {
            float[] c = button(b, w, h, dens, bottomInsetPx);
            float r = c[2] + 10f * dens;
            float dx = x - c[0], dy = y - c[1];
            if (dx * dx + dy * dy <= r * r) return b;
        }
        return Button.NONE;
    }

    /** 圆球中心 y（屏幕偏上一点，下面留给字）。 */
    public static float orbCenterY(int h) {
        return h * 0.40f;
    }

    /** 圆球半径：音量 0..1 → 基础半径 ×(1 + 0.14·音量)。 */
    public static float orbRadius(float dens, float level) {
        float l = Math.max(0f, Math.min(1f, level));
        return ORB_DP * dens * (1f + ORB_GROW * l);
    }

    // ------------------------------------------------------------------ 文字

    /** 还没听到字时的提示。 */
    public static final String HINT = "在听，请说";

    /** 屏上那一句：没字 = 提示；有字 = 原话（还在听时末尾加「…」，淡色）。 */
    public static String[] liveText(String partial, String errorLine, WakeStage.Stage stage) {
        if (stage == WakeStage.Stage.ERROR) {
            String e = errorLine == null || errorLine.trim().length() == 0 ? "没听清，点左下角再说一次" : errorLine.trim();
            return new String[]{e, ""};
        }
        String p = partial == null ? "" : partial.trim();
        if (p.length() == 0 || WakeCopy.LISTEN_HINT.equals(p)) return new String[]{"", HINT};
        return new String[]{p, "…"};
    }
}
