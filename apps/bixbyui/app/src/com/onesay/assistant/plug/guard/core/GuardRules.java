/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/guard/GuardRules.kt) to Java 7. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.guard.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 操作守卫的判定规则（纯函数）。阶梯：先提醒（nudge，给三条出路），最后一级才叫停（安全网）。
 *
 * <pre>
 * | 信号                                   | 提醒      | 叫停                         |
 * | 动作没有效果（no_effect / 动后界面不变）连续 | 3、6、9   | 12                           |
 * | 页面没变时又读了一遍                       | 5、8      | 12                           |
 * | 连续失败（第 4 次起附出路）                 | 2、4      | 8                            |
 * | 不可恢复的失败                            | 1         | 3                            |
 * | 没到过新状态时第 N 次回到同一状态            | 3、6      | 步数上限的 1/4（至少 10）      |
 * | 在两三个状态之间兜圈（整圈数）               | 2、4      | 步数上限的 1/5（至少 6）       |
 * | 连续 N 个动作没到过任何新状态               | 8、16     | 只提醒（上限 1/2，至少 24）    |
 * </pre>
 */
public final class GuardRules {
    private GuardRules() {
    }

    public enum Severity {
        WARN("warn"), STOP("stop");
        public final String wire;

        Severity(String w) {
            wire = w;
        }
    }

    public static final int DEFAULT_BUDGET = 60, MAX_CYCLE = 3, FAILURE_WAYS_OUT = 4;
    public static final int[] NO_EFFECT_LADDER = {3, 6, 9, 12};
    public static final int[] REREAD_LADDER = {5, 8, 12};
    public static final int[] FAILURE_LADDER = {2, 4, 8};
    public static final int[] UNRECOVERABLE_LADDER = {1, 3};

    public static int lateStop(Integer budget, int divisor, int floor) {
        return Math.max(floor, (budget == null ? DEFAULT_BUDGET : budget) / divisor);
    }

    public static int[] sameStateLadder(Integer budget) {
        return new int[]{3, 6, lateStop(budget, 4, 10)};
    }

    public static int[] cycleLadder(Integer budget) {
        return new int[]{2, 4, lateStop(budget, 5, 6)};
    }

    public static int[] noNewStateLadder(Integer budget) {
        return new int[]{8, 16, lateStop(budget, 2, 24)};
    }

    /** 一级：threshold + severity。 */
    public static final class Rung {
        public final int threshold;
        public final Severity severity;

        Rung(int t, Severity s) {
            threshold = t;
            severity = s;
        }
    }

    /** 计数在阶梯上到了哪一级；没到第一级或这一级已提醒过为 null；到最高一级且 canStop 时叫停。 */
    public static Rung climb(int count, int[] ladder, int warnedAt, boolean canStop) {
        int threshold = -1;
        for (int t : ladder) if (count >= t) threshold = t;
        if (threshold < 0 || threshold <= warnedAt) return null;
        return new Rung(threshold, threshold == ladder[ladder.length - 1] && canStop ? Severity.STOP : Severity.WARN);
    }

    /** 兜圈：period 个状态一圈，length 是合这一圈的末尾长度。 */
    public static final class Cycle {
        public final int period, length;

        Cycle(int p, int l) {
            period = p;
            length = l;
        }

        public int rounds() {
            return length / period;
        }
    }

    /** 先后到过的各状态（相邻不同）末尾是不是在 2–3 个状态之间兜圈；几种圈长都合时取走得最长的。 */
    public static Cycle cycle(List<Long> trail) {
        Cycle best = null;
        for (int p = 2; p <= MAX_CYCLE; p++) {
            if (trail.size() < p + 1) continue;
            Set<Long> last = new HashSet<Long>(trail.subList(trail.size() - p, trail.size()));
            if (last.size() != p) continue;
            int len = p, i = trail.size() - 1;
            while (i - p >= 0 && trail.get(i).equals(trail.get(i - p))) {
                len++;
                i--;
            }
            if (len > p && (best == null || len > best.length)) best = new Cycle(p, len);
        }
        return best;
    }

    public static Severity failures(int consecutive, int unrecoverable) {
        if (consecutive >= FAILURE_LADDER[FAILURE_LADDER.length - 1] || unrecoverable >= UNRECOVERABLE_LADDER[UNRECOVERABLE_LADDER.length - 1])
            return Severity.STOP;
        if (consecutive >= FAILURE_LADDER[0] || unrecoverable >= UNRECOVERABLE_LADDER[0]) return Severity.WARN;
        return null;
    }
}
