package com.onesay.assistant.v2.ui.wake;

/**
 * ★ 综合包：唤醒卡图标行的 🎤「续说」（恢复旧「✦ 语音」：在当前对话里接着说，不清屏、保留上一轮问答与上下文）。纯 Java，JVM 可测。
 *
 * <ul>
 *   <li>结果 / 出错态点 → {@link Tap#CONTINUE}：FloatingSheetView.switchToVoiceMode + onVoiceModeRequested
 *       （WakeService.requestContinueListening：只开捕获窗，不走新唤醒的 v2Wake 清卡）。</li>
 *   <li>收音中（LISTENING）再点：已听到人声 → {@link Tap#FINISH}（= 说完了，马上识别）；还没开口 → {@link Tap#STOP}（停收音、回结果态）。</li>
 * </ul>
 * 触控 44dp（≥ 无障碍 44/48 的下限，比 ⌨ ◎ 的 32dp 大一号，主入口更好点）；唤醒卡不跟主题（一直是深色玻璃卡），
 * 所以深浅主题下按钮都用卡片色；收音中用苔绿实底 + 深色字形表示「在听，再点停止」。
 */
public final class MicButtonPolicy {

    private MicButtonPolicy() {}

    public enum Tap { NONE, CONTINUE, FINISH, STOP }

    public static final int TOUCH_DP = 44;
    /** 收音中：苔绿实底 + 卡片深色字形。 */
    public static final int ACTIVE_BG = WakeCardPainter.MOSS;
    public static final int ACTIVE_GLYPH = WakeCardPainter.FILL_TOP;

    public static Tap onTap(WakeStage.Stage st, boolean heardSpeech) {
        if (st == null) return Tap.NONE;
        switch (st) {
            case LISTENING:
                return heardSpeech ? Tap.FINISH : Tap.STOP;
            case RESULT:
            case ERROR:
                return Tap.CONTINUE;
            default:
                return Tap.NONE;
        }
    }

    public static boolean active(WakeStage.Stage st) {
        return st == WakeStage.Stage.LISTENING;
    }

    public static String description(boolean active) {
        return active ? "停止收音" : "续说";
    }

    /** WCAG 对比度（不透明色）。 */
    public static double contrast(int a, int b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** 半透明前景叠在不透明背景上的结果色。 */
    public static int over(int fg, int bg) {
        int a = (fg >>> 24) & 0xFF;
        int r = (((fg >> 16) & 0xFF) * a + ((bg >> 16) & 0xFF) * (255 - a)) / 255;
        int g = (((fg >> 8) & 0xFF) * a + ((bg >> 8) & 0xFF) * (255 - a)) / 255;
        int b = ((fg & 0xFF) * a + (bg & 0xFF) * (255 - a)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static double lum(int c) {
        return 0.2126 * ch((c >> 16) & 0xFF) + 0.7152 * ch((c >> 8) & 0xFF) + 0.0722 * ch(c & 0xFF);
    }

    private static double ch(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
