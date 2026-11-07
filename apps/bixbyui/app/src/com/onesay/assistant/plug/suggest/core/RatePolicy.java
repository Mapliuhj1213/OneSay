/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

/** 每场景的频控参数（§5.5 / §7 表格的「频控」列）。 */
public final class RatePolicy {
    /** 两次展示的最小间隔（0 = 不限）。 */
    public final long minIntervalMs;
    /** 每天最多几次（0 = 不限）。 */
    public final int dailyCap;
    /** 同 dedupKey 的去重窗口。 */
    public final long dedupWindowMs;
    /** 是否参与连续忽略退避。 */
    public final boolean ignoreBackoff;
    /**
     * 用户主动行为触发（截图）：不受夜间时段、克制档与全局配额拦截（仍计入全局计数）；
     * 去重 / 退避 / 场景间隔 / 日上限 / 静默条件照旧。
     */
    public final boolean userInitiated;

    public RatePolicy(long minIntervalMs, int dailyCap, long dedupWindowMs, boolean ignoreBackoff) {
        this(minIntervalMs, dailyCap, dedupWindowMs, ignoreBackoff, false);
    }

    public RatePolicy(long minIntervalMs, int dailyCap, long dedupWindowMs, boolean ignoreBackoff, boolean userInitiated) {
        this.minIntervalMs = minIntervalMs;
        this.dailyCap = dailyCap;
        this.dedupWindowMs = dedupWindowMs;
        this.ignoreBackoff = ignoreBackoff;
        this.userInitiated = userInitiated;
    }

    public static final RatePolicy DEFAULT = new RatePolicy(0L, 0, SuggestDefaults.DEDUP_WINDOW_MS, true);

    public static RatePolicy interval(long ms) {
        return new RatePolicy(ms, 0, SuggestDefaults.DEDUP_WINDOW_MS, true);
    }
}
