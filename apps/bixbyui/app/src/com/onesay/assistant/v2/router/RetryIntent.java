package com.onesay.assistant.v2.router;

import java.util.regex.Pattern;

/**
 * [chat-1124] 「再试一次」和「你打开错了」这类话（纯 Java，JVM 可测）。
 *
 * <p>真机（魅族）：任务没做成以后，用户说「你打开了 X 并不是 Y 当然找不到」被当成购物新问题、出了一段购物方式说明；
 * 再说「再试一次」被当成一句全新的话，按这四个字挑工具，只挑到两个不相干的，于是答「我没有打开 App 的能力」。
 * 这两类话本身没有任务内容，只在<b>刚有一个没做成的任务</b>时才有意义：接回那个任务、带上用户的纠正重做。
 * 判定只看整句是不是这类话，不看关键词（「再来一单」是 App 里的按钮，不算重试）。
 */
public final class RetryIntent {

    private RetryIntent() {}

    /** 重试：整句就是「再试一次 / 重试 / 再来一次 / 你再试试 / 重新来」这类。 */
    private static final Pattern RETRY = Pattern.compile(
            "^(?:那|嗯|好|好吧|行|行吧|那你|你|请|麻烦你?|帮我)?(?:再|重新|重)(?:试|做|弄|跑|来|执行|操作|搞)(?:一次|一遍|一下|下|试|一回)?(?:看|看看)?(?:吧|呀|啊|呢|嘛)?$"
                    + "|^(?:那|嗯|好|行)?(?:你)?(?:重试|重来|再试试看|继续试|接着试|再给我试一次|再帮我试一次|再帮我试试|你再帮我试试|再去试试)(?:一下|一次)?(?:吧|呀|啊)?$"
                    + "|^(?:retry|try ?again)$", Pattern.CASE_INSENSITIVE);

    /** 纠错：说你打开 / 点 / 找错了，或「不是 X 是 Y」「X 不是 Y」「我说的是 X」。 */
    private static final Pattern CORRECT = Pattern.compile(
            "(?:打开|开|点|进|找|选|用|弄|搞|做|走|认|看)错了"
                    + "|(?:打开|开|进)(?:的|了)?(?:是)?.{1,10}?(?:并|而)?不是.{1,10}"
                    + "|^(?:不是|不对|错了)[，,、 ]*(?:是|而是|应该是|要)?.{0,12}$"
                    + "|不是.{1,10}?(?:是|而是|应该是|要的是).{1,10}"
                    + "|^(?:我)?(?:说的|要的|让你打开的|让你开的)是.{1,12}$"
                    + "|^应该(?:是|打开|开|用).{1,12}$"
                    + "|当然(?:找不到|打不开|进不去|不行)"
                    + "|(?:你)?(?:搞|弄)反了");

    static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("[。.！!？?~～…\\s]+$", "").replaceAll("\\s+", "");
    }

    public static boolean isRetry(String s) {
        String t = clean(s);
        if (t.length() == 0 || t.length() > 12) return false;
        return RETRY.matcher(t).matches();
    }

    public static boolean isCorrection(String s) {
        String t = clean(s);
        if (t.length() < 2 || t.length() > 40) return false;
        return CORRECT.matcher(t).find();
    }

    /** 重试或纠错（只在有一个刚没做成的任务时才接回去）。 */
    public static boolean isRetryOrCorrection(String s) {
        return isRetry(s) || isCorrection(s);
    }
}
