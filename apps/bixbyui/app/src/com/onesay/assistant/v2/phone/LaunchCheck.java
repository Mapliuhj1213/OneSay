package com.onesay.assistant.v2.phone;

/**
 * [exec-1124 C] 打开应用之后核对它真的到了前台（纯 Java，JVM 可测）。
 *
 * <p>以前 {@code startActivity} 不抛异常就当成功；新安卓 / 一些系统会静默拦住「从后台打开界面」，
 * 任务就在错的页面上空转。现在打开后最多等 {@link #WAIT_MS}：到前台 = 成功；等到一半还完全没动静（前台没变）
 * 就换一条路再拉一次（由调用方决定怎么拉，比如读屏助手）；到点还不是目标 = 失败，给一句大白话原因。
 * 一直读不到前台是谁（两条读屏都不可用）= 核对不了，按原来的「当成功」处理，不误报失败。
 *
 * <p>思路参考 Mika（Apache-2.0）打开应用后等前台核对的做法，代码自己写的。
 */
public final class LaunchCheck {
    private LaunchCheck() {}

    /** 最多等多久。 */
    public static final long WAIT_MS = 5000;
    /** 等到这时前台还没变，就换一条路再拉一次。 */
    public static final long RETRY_AT_MS = 2500;
    /** 多久看一次前台。 */
    public static final long POLL_MS = 250;

    public enum Outcome { OK, FAIL, UNKNOWN }

    /** 调用方提供：看前台包名（读不到 = null）、时钟、换一条路再拉一次。 */
    public interface Probe {
        String foreground();

        long now();

        void sleep(long ms);

        /** 换一条路再拉一次；拉不了返回 false。 */
        boolean relaunch();
    }

    public static final class Result {
        public final Outcome outcome;
        /** 最后一次看到的前台包名（可能 null）。 */
        public final String lastForeground;
        public final boolean relaunched;
        public final long waitedMs;

        Result(Outcome o, String fg, boolean re, long ms) {
            outcome = o;
            lastForeground = fg;
            relaunched = re;
            waitedMs = ms;
        }
    }

    /**
     * @param target 要打开的包名
     * @param before 打开前的前台包名（可能 null）
     */
    public static Result await(String target, String before, Probe p) {
        long t0 = p.now();
        boolean seen = false, re = false;
        String last = null;
        while (true) {
            long el = p.now() - t0;
            String fg = p.foreground();
            if (fg != null && fg.length() > 0) {
                seen = true;
                last = fg;
                if (fg.equals(target)) return new Result(Outcome.OK, fg, re, el);
            }
            if (el >= WAIT_MS) break;
            // 等到一半前台还是原来那个（或读不到）：多半是被静默拦了，换条路再拉一次（只拉一次）
            if (!re && el >= RETRY_AT_MS && (fg == null || fg.length() == 0 || fg.equals(before))) {
                re = true;
                p.relaunch();
            }
            p.sleep(POLL_MS);
        }
        return new Result(seen ? Outcome.FAIL : Outcome.UNKNOWN, last, re, p.now() - t0);
    }

    /** 失败时给用户 / 模型看的话（大白话，不出现别家名字）。 */
    public static String failNote(String label, boolean stillOther) {
        String name = label == null || label.trim().length() == 0 ? "这个应用" : "「" + label.trim() + "」";
        return "没能打开" + name + "：等了 " + (WAIT_MS / 1000) + " 秒它还没到前台" + (stillOther ? "，现在前面还是别的界面" : "")
                + "。可能是系统拦住了从后台打开，可以到 OneSay 的应用权限里允许「后台弹出界面」，或者先自己把它打开，我再接着做。";
    }
}
