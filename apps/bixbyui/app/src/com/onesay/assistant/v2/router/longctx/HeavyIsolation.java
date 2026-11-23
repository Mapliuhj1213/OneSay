package com.onesay.assistant.v2.router.longctx;

import com.onesay.assistant.v2.router.ctx.TokenEstimator;

/**
 * ★ fix/1123-longctx-phone 第 5 条：重活隔离 —— 识屏 / 代操作 / 多步任务 / 读信 / 跑腿这类长任务的回执，
 * 进主对话上下文前先精简：每步过程行（「· …」）一律不进；剩下的仍超过 {@link #HEAVY_TOKENS} 就只留回执头 + 结论（没结论留开头一段）。
 * 完整原文由调用方先存进 {@link TurnArchive}，后面问到（「第三条说了什么」「刚才屏幕上那个地址」）按需调回。
 * 普通问答答复不动。
 */
public final class HeavyIsolation {
    private HeavyIsolation() {
    }

    public static final String[] HEAVY_PREFIXES = {"[执行回执]", "[视觉识屏回执]", "[圈选识屏回执]", "[识屏指代回执]", "[跑腿回执]", "[读信回执]"};
    public static final int HEAVY_TOKENS = 400;
    /** 没有「结论：」时留开头多少字。 */
    public static final int KEEP_HEAD_CHARS = 200;
    public static final int KEEP_CONCLUSION_CHARS = 600;
    public static final String NOTE = "（过程原文已存档，需要时会调出）";

    public static boolean isHeavy(String answer) {
        if (answer == null) return false;
        for (String p : HEAVY_PREFIXES) if (answer.startsWith(p)) return true;
        return false;
    }

    /** 精简后的回执；不是重活 / 不用精简 = 原样返回（同一对象）。 */
    public static String compact(String answer) {
        if (!isHeavy(answer)) return answer;
        String[] lines = answer.split("\n", -1);
        StringBuilder kept = new StringBuilder();
        boolean dropped = false;
        for (String l : lines) {
            if (l.startsWith("· ") || l.startsWith("·")) {
                dropped = true;
                continue;
            }
            if (kept.length() > 0) kept.append('\n');
            kept.append(l);
        }
        String s = kept.toString();
        if (TokenEstimator.estimate(s) > HEAVY_TOKENS) {
            dropped = true;
            int nl = s.indexOf('\n');
            String head = nl > 0 ? s.substring(0, nl) : TextSim.head(s, KEEP_HEAD_CHARS);
            if (head.length() > KEEP_HEAD_CHARS) head = head.substring(0, KEEP_HEAD_CHARS) + "…";
            int ci = s.lastIndexOf("结论：");
            String body;
            if (ci >= 0) {
                body = s.substring(ci);
                if (body.length() > KEEP_CONCLUSION_CHARS) body = body.substring(0, KEEP_CONCLUSION_CHARS) + "…";
            } else {
                String rest = nl > 0 ? s.substring(nl + 1) : "";
                body = cutAtLine(rest, KEEP_HEAD_CHARS);
            }
            s = body.length() == 0 ? head : head + "\n" + body;
        }
        if (!dropped) return answer;
        return s + "\n" + NOTE;
    }

    /** 留开头约 n 字，尽量切在换行处。 */
    static String cutAtLine(String s, int n) {
        if (s.length() <= n) return s;
        int nl = s.lastIndexOf('\n', n);
        if (nl >= n / 3) return s.substring(0, nl);
        return s.substring(0, n) + "…";
    }
}
