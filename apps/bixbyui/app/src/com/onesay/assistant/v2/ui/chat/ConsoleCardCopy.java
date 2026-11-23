package com.onesay.assistant.v2.ui.chat;

/**
 * ★ feat/console-ui（设计稿 ②）：对话窗里卡片的标题 + 状态色。纯 Java，JVM 可测。
 * 确认卡：「要你确认 · 等你点头」（黄）；点了拒绝 →「没做」（灰）。执行结果：「已办好」（苔绿）/「没办成」（红）。
 * 只换外观和这两行字，确认逻辑（不点不执行）全是原来的。
 */
public final class ConsoleCardCopy {

    private ConsoleCardCopy() {}

    public static final class Status {
        public final String label;
        public final String status;
        public final int color;

        Status(String label, String status, int color) {
            this.label = label;
            this.status = status;
            this.color = color;
        }

        @Override public String toString() {
            return label + "/" + status;
        }
    }

    /** 卡片左上的标签最长几个字（再长截掉加「…」）。 */
    static final int LABEL_MAX = 16;

    public static Status confirm(boolean decided, boolean redTier) {
        String l = redTier ? "这一步要你确认" : "要你确认";
        if (decided) return new Status(l, "没做", ChatWindowPolicy.CONSOLE_OFF);
        return new Status(l, "等你点头", ChatWindowPolicy.CONSOLE_WARN);
    }

    public static Status result(boolean ok, String summary) {
        return new Status(label(summary), ok ? "已办好" : "没办成", ok ? ChatWindowPolicy.CONSOLE_OK : ChatWindowPolicy.CONSOLE_BAD);
    }

    /** 能力的说明 → 卡片标签：取第一句、截短；没有 / 像内部标识（全是英文字母数字下划线点）→「办事结果」。 */
    static String label(String summary) {
        if (summary == null) return "办事结果";
        String s = summary.trim();
        int cut = s.length();
        for (String sep : new String[]{"。", "，", "；", "（", "(", "\n", ",", "：", ":"}) {
            int i = s.indexOf(sep);
            if (i > 0 && i < cut) cut = i;
        }
        s = s.substring(0, cut).trim();
        if (s.length() == 0 || looksLikeId(s)) return "办事结果";
        return s.length() <= LABEL_MAX ? s : s.substring(0, LABEL_MAX - 1) + "…";
    }

    static boolean looksLikeId(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean idc = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '.' || ch == '-' || ch == '/';
            if (!idc && ch != ' ') return false;
        }
        return true;
    }
}
