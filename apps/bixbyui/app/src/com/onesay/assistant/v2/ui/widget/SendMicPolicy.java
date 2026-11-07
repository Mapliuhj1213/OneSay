package com.onesay.assistant.v2.ui.widget;

/**
 * 后处理面板输入框有字时右侧没变「发送」）：
 * 有字（或带着附图）= 圆形「发送」箭头，点了发送文字；空 = 麦克风，点了开始语音；录音中 = 波点（宿主自己画）。
 * 纯 Java、无 Android 依赖，ChatBottomBar（对话窗 / 悬浮输入条）与 PillSearchBar（卡片面板 / 截图分析后追问）共用。
 */
public final class SendMicPolicy {
    private SendMicPolicy() {
    }

    public enum Mode { MIC, SEND, LISTENING }

    public static final String DESC_SEND = "发送";
    public static final String DESC_MIC = "语音输入";
    public static final String DESC_LISTENING = "聆听中，点按说完了";

    /** 去掉首尾空白后还有字才算有字（只打了空格 / 换行仍是麦克风）。 */
    public static boolean hasText(CharSequence t) {
        if (t == null) return false;
        for (int i = 0; i < t.length(); i++) if (!Character.isWhitespace(t.charAt(i))) return true;
        return false;
    }

    public static Mode mode(CharSequence text, boolean attachment, boolean listening) {
        if (listening) return Mode.LISTENING;
        return hasText(text) || attachment ? Mode.SEND : Mode.MIC;
    }

    public static String desc(Mode m) {
        return m == Mode.SEND ? DESC_SEND : m == Mode.LISTENING ? DESC_LISTENING : DESC_MIC;
    }

    /** 点右侧按钮该做什么：SEND → 发送；MIC → 开始语音；LISTENING → 交给宿主（说完了 / 停）。 */
    public static Mode onTap(CharSequence text, boolean attachment, boolean listening) {
        return mode(text, attachment, listening);
    }
}
