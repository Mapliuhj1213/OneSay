/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

/**
 * 默认值与待拍板项（D1–D5，按默认实现，改这里即可）：
 * <ul>
 *   <li>D1 卡片停留 {@link #CARD_TTL_MS} = 6 s；</li>
 *   <li>D2 「不再提示」不弹确认，给 {@link #UNDO_TTL_MS} = 3 s 撤销（{@link #MUTE_CONFIRM} = false）；</li>
 *   <li>D3 微信不进私密名单（{@link #WECHAT_PRIVATE} = false，见 PrivateApps）；</li>
 *   <li>D4 点「分析」后先出结构化结果 + 后续芯片，不自动执行（{@link #ANALYZE_AUTO_STRUCTURED} = true，{@link #AUTO_EXECUTE} = false）；</li>
 *   <li>D5 插件开启时只有 privacyClass 0 的场景默认开（{@link #defaultSceneOn}）。</li>
 * </ul>
 */
public final class SuggestDefaults {
    private SuggestDefaults() {
    }

    // D1
    public static final long CARD_TTL_MS = 6000L;
    // D2
    public static final boolean MUTE_CONFIRM = false;
    public static final long UNDO_TTL_MS = 3000L;
    // D3
    public static final boolean WECHAT_PRIVATE = false;
    // D4
    public static final boolean ANALYZE_AUTO_STRUCTURED = true;
    public static final boolean AUTO_EXECUTE = false;

    /** D5：插件开启时某场景的默认开关。骨架场景一律关。 */
    public static boolean defaultSceneOn(int privacyClass, boolean skeleton) {
        return !skeleton && privacyClass == Scene.PRIVACY_LOCAL;
    }

    public static final int MAX_ACTIONS = 3;
    public static final String LABEL_DISMISS = "忽略";
    public static final String LABEL_MUTE = "不再提示";
    public static final String LABEL_UNDO = "撤销";

    // 全局频控（§5.5）
    public static final int GLOBAL_PER_10MIN = 3, GLOBAL_PER_HOUR = 6, GLOBAL_PER_DAY = 30;
    public static final long DEDUP_WINDOW_MS = 24L * 3600L * 1000L;
    public static final int IGNORE_STREAK_SHORT = 3;
    public static final long IGNORE_COOLDOWN_SHORT_MS = 2L * 3600L * 1000L;
    public static final int IGNORE_STREAK_LONG = 6;
    public static final long IGNORE_COOLDOWN_LONG_MS = 24L * 3600L * 1000L;
    public static final String HINT_RARELY_USED = "不常用？可以关掉";
    public static final long DEFER_MS = 10000L;
    public static final int NIGHT_FROM_HOUR = 23, NIGHT_TO_HOUR = 7;
    public static final int NIGHT_MIN_PRIORITY = 2;
    /** 不受全局 10 分钟 / 小时 / 日上限约束的最低优先级（仍计数）。见 SUGGEST.md「判断」。 */
    public static final int QUOTA_EXEMPT_PRIORITY = 2;

    // 引擎
    public static final int QUEUE_CAP = 32;
    public static final long WATCH_PERIOD_MS = 1000L;
    public static final int RECENT_SKIPS = 12;
}
