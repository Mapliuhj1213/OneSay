package com.onesay.assistant.v2.island;

/**
 * ★ 11-04 午（用户：缩短单任务时长 + 减少等待焦虑）：灵动岛 / 面板的进度反馈文案（纯函数，JVM 可测）。
 *
 * <p>两态：
 * <ul>
 *   <li>思考中 —— 在等大模型（每轮模型调用前进入，拿到回复退出）；岛上圆点呼吸。</li>
 *   <li>执行中：&lt;真实步骤&gt; —— 正在执行一个工具 / 接口（「执行中：读取 什么值得买」）。</li>
 * </ul>
 * 计时：已用秒数（「12 s」，满 1 分钟「1:05」）。步数：知道总数 →「2/4」，不知道 →「第 2 步」（只有 1 步不显示）。
 * 超过 {@link #STALL_MS} 没有新步骤 → 补一句真实状态（「还在读 什么值得买」「还在等模型回复」），不说「快好了」之类套话。
 * 截断一律整词（{@link IslandStepText#fit}），不加省略号。
 */
public final class ProgressFeedback {

    private ProgressFeedback() {}

    public enum Phase { THINKING, WORKING }

    public static final String THINKING = "思考中";
    public static final String WORKING = "执行中";
    /** 多久没有新步骤就补一句真实状态。 */
    public static final long STALL_MS = 8000L;
    /** 一行最多多少字宽（汉字 1、ASCII 0.5）。 */
    public static final int MAX_UNITS = 15;

    /** 已用时间：「0 s」「12 s」「1:05」。 */
    public static String elapsed(long ms) {
        long s = Math.max(0L, ms / 1000L);
        if (s < 60L) return s + " s";
        long sec = s % 60L;
        return (s / 60L) + ":" + (sec < 10 ? "0" : "") + sec;
    }

    /** 步数：总数 ≥ 2 →「i/n」；不知道总数 → 第 2 步起「第 n 步」；只有 0–1 步 → 空。 */
    public static String stepCounter(int n, int total) {
        if (total >= 2) return Math.max(1, Math.min(n, total)) + "/" + total;
        return n >= 2 ? "第 " + n + " 步" : "";
    }

    /** 「读取：什么值得买」→「读取 什么值得买」（去掉动词后的冒号，好接在「执行中：」后面）。 */
    static String stepBody(String step) {
        String s = step == null ? "" : step.trim();
        if (s.startsWith("正在：")) s = s.substring(3).trim();
        else if (s.startsWith("正在")) s = s.substring(2).trim();
        int k = s.indexOf('：');
        if (k > 0 && k <= 4) s = s.substring(0, k) + " " + s.substring(k + 1).trim();
        return s.trim();
    }

    /** 主行：「思考中」/「执行中：读取 什么值得买」（没有步骤文字 →「执行中」）。 */
    public static String headline(Phase p, String step) {
        if (p == Phase.THINKING) return THINKING;
        String b = stepBody(step);
        if (b.length() == 0 || IslandStepText.CONCLUDE.equals(b)) return b.length() == 0 ? WORKING : WORKING + "：" + b;
        return IslandStepText.fit(WORKING + "：" + b, MAX_UNITS);
    }

    /** 动词 → 「还在…」的说法（真实状态：正在做的那件事本身）。 */
    private static final String[][] STALL_VERB = {
            {"读取", "还在读"}, {"搜索", "还在搜"}, {"调研", "还在查"}, {"比价", "还在比价"}, {"操作", "还在操作"},
            {"打开", "还在打开"}, {"查应用", "还在查应用"}, {"查记忆", "还在查记忆"}, {"读屏幕", "还在读屏幕"},
            {"查看本机", "还在查看本机"}, {"整理结论", "还在整理结论"}, {"等你选择", "还在等你选择"},
    };

    /**
     * 超过 {@link #STALL_MS} 没有新步骤时的补充一句；没到点 → null。
     * 思考中 →「还在等模型回复」；执行中 →「还在读 什么值得买」（认不出的动词 →「还在」+ 步骤原文）。
     */
    public static String stallLine(Phase p, String step, long sinceStepMs) {
        if (sinceStepMs < STALL_MS) return null;
        if (p == Phase.THINKING) return "还在等模型回复";
        String b = stepBody(step);
        if (b.length() == 0) return "还在执行";
        for (String[] kv : STALL_VERB) {
            if (b.equals(kv[0])) return kv[1];
            if (b.startsWith(kv[0] + " ")) return IslandStepText.fit(kv[1] + " " + b.substring(kv[0].length() + 1).trim(), MAX_UNITS);
        }
        if (b.startsWith("还在")) return IslandStepText.fit(b, MAX_UNITS);
        return IslandStepText.fit("还在" + b, MAX_UNITS);
    }

    /** 当前该显示的一行：到点了用真实状态补句，否则主行。 */
    public static String line(Phase p, String step, long sinceStepMs) {
        String st = stallLine(p, step, sinceStepMs);
        return st != null ? st : headline(p, step);
    }

    /** 右侧小字：「2/4 · 12 s」/「第 3 步 · 12 s」/「12 s」。 */
    public static String meta(int n, int total, long elapsedMs) {
        String c = stepCounter(n, total);
        String e = elapsed(elapsedMs);
        return c.length() > 0 ? c + " · " + e : e;
    }

    /** 呼吸动画的透明度系数（0.45–1.0，1.4 s 一个周期）—— 思考中用；执行中恒为 1。 */
    public static float breath(Phase p, long now) {
        if (p != Phase.THINKING) return 1f;
        double e = 0.5 - 0.5 * Math.cos(2 * Math.PI * ((now % 1400L) / 1400.0));
        return (float) (0.45 + 0.55 * e);
    }
}
