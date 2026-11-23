package com.onesay.assistant.v2.fastscreen;

/**
 * [fix-1123-c] 输入后读回哪个值（纯 Java，JVM 可测）。
 *
 * <p>2026-10-31 真机：点了系统设置的搜索框以后，设置换成了新的搜索页 / 新输入框，往里写完「关于手机」，对旧节点 refresh 读回的还是提示文字「搜索」，
 * 模型拿到错误的读回。现在旧节点读回不含要输入的字时，重新取当前窗口里有输入焦点的那个框再读一次，用含要输入字的那个；
 * 两个都不含时用新取的（它才是屏上现在的框），并标明「没读到刚输入的字」，不冒充成功。
 */
public final class ReadBack {
    private ReadBack() {}

    public static boolean has(String got, String want) {
        return got != null && want != null && got.contains(want);
    }

    /**
     * @param oldNode 旧节点读回（可空）
     * @param focused 重新取到的输入焦点框读回（可空 = 没取到）
     * @return 回给调用方的文字："set:" + 读回；都不含要输入的字时末尾加「（没读到刚输入的字）」
     */
    public static String pick(String want, String oldNode, String focused) {
        if (has(oldNode, want)) return "set:" + oldNode;
        if (has(focused, want)) return "set:" + focused;
        String v = focused != null ? focused : oldNode == null ? "" : oldNode;
        return "set:" + v + "（没读到刚输入的字）";
    }
}
