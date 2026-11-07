package com.onesay.assistant.v2.agent;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ★ arch/agent-loop（B 路）：AgentLoop 新能力的可调参数（纯 Java 默认值 + 可选 SharedPreferences 覆盖，JVM 可测）。
 *
 * <p>偏好文件 {@code bixby_agent_loop}（没有就全用默认值）：
 * <ul>
 *   <li>{@code max_tokens_round}：单轮模型输出上限，默认 {@link #DEF_MAX_TOKENS_ROUND}（原 L2LLMCompiler.AGENT_LOOP_MAX_TOKENS = 900）；</li>
 *   <li>{@code unattended_total_tokens}：无人值守任务（含 research 子任务）的<b>总</b>输出 token 预算，默认 {@link #DEF_UNATTENDED_TOTAL_TOKENS}；</li>
 *   <li>{@code web_tools}：web.search / web.read / research.run 是否进 AgentLoop，默认开；</li>
 *   <li>{@code research_in_loop}：「帮我调研 / 查一下资料」是否进 AgentLoop（而不是分诊前被跑腿路由抢走），默认开；</li>
 *   <li>{@code ask_timeout_sec}：ask_user 等用户回答的秒数，默认 {@link #DEF_ASK_TIMEOUT_SEC}。</li>
 * </ul>
 * 所有读取都容错：没有 Context（JVM）/ 读失败 → 默认值。
 */
public final class LoopConfig {

    private LoopConfig() {}

    public static final String PREF = "bixby_agent_loop";

    /** 单轮输出上限（用户 11-01 决策：900 → 1500）。 */
    public static final int DEF_MAX_TOKENS_ROUND = 1500;
    /** 允许配置的范围（防止误填 0 或几万）。 */
    public static final int MIN_MAX_TOKENS_ROUND = 300;
    public static final int MAX_MAX_TOKENS_ROUND = 4000;

    /** 无人值守总输出预算：默认 = 无人值守调用上限（8）× 单轮上限（1500）。research 子任务也从这里扣。 */
    public static final int DEF_UNATTENDED_TOTAL_TOKENS = 8 * DEF_MAX_TOKENS_ROUND;
    /** 剩余预算不够这么多就不再调模型（给不出有用的一轮）。 */
    public static final int MIN_ROUND_TOKENS = 200;

    /** ask_user 等待秒数（用户决策：60 s）。 */
    public static final int DEF_ASK_TIMEOUT_SEC = 60;

    /** web.read 单次回给模型的正文上限（字符）。 */
    public static final int DEF_READ_CHARS = 3000;
    /** 一个任务里 web.read 累计回给模型的正文上限（字符），超了只给来源不给正文。 */
    public static final int DEF_READ_TOTAL_CHARS = 12000;
    /** web.search 一次最多几条。 */
    public static final int DEF_SEARCH_RESULTS = 5;

    /** research.run 子循环最多调几次模型。 */
    public static final int DEF_RESEARCH_CALLS = 6;
    /** research.run 回给父任务的摘要上限（字符）。 */
    public static final int DEF_RESEARCH_SUMMARY_CHARS = 800;
    /** research.run 子循环单轮输出上限。 */
    public static final int DEF_RESEARCH_TOKENS_ROUND = 800;

    /** 同一界面入口（同签名前缀 + 同参数）一个任务里最多成功打开几次（防死循环，替代「只许一次」）。 */
    public static final int UI_REPEAT_MAX = 3;

    // ---- JVM 测试可覆写（null = 不覆写）
    static volatile Integer sMaxTokensOverride;
    static volatile Integer sUnattendedTotalOverride;
    static volatile Boolean sWebToolsOverride;
    static volatile Boolean sResearchInLoopOverride;

    public static void overrideForTest(Integer maxTokens, Integer unattendedTotal, Boolean webTools, Boolean researchInLoop) {
        sMaxTokensOverride = maxTokens;
        sUnattendedTotalOverride = unattendedTotal;
        sWebToolsOverride = webTools;
        sResearchInLoopOverride = researchInLoop;
    }

    private static SharedPreferences sp(Context ctx) {
        try {
            return ctx == null ? null : ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int getInt(Context ctx, String key, int def) {
        try {
            SharedPreferences p = sp(ctx);
            return p == null ? def : p.getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean getBool(Context ctx, String key, boolean def) {
        try {
            SharedPreferences p = sp(ctx);
            return p == null ? def : p.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 单轮输出上限（夹在 [300, 4000]）。 */
    public static int maxTokensPerRound(Context ctx) {
        Integer o = sMaxTokensOverride;
        int v = o != null ? o : getInt(ctx, "max_tokens_round", DEF_MAX_TOKENS_ROUND);
        return clampTokens(v);
    }

    static int clampTokens(int v) {
        if (v < MIN_MAX_TOKENS_ROUND) return MIN_MAX_TOKENS_ROUND;
        if (v > MAX_MAX_TOKENS_ROUND) return MAX_MAX_TOKENS_ROUND;
        return v;
    }

    /** 无人值守总输出预算（≥ 一轮最小值）。 */
    public static int unattendedTotalTokens(Context ctx) {
        Integer o = sUnattendedTotalOverride;
        int v = o != null ? o : getInt(ctx, "unattended_total_tokens", DEF_UNATTENDED_TOTAL_TOKENS);
        return Math.max(MIN_ROUND_TOKENS, v);
    }

    public static boolean webToolsEnabled(Context ctx) {
        Boolean o = sWebToolsOverride;
        return o != null ? o : getBool(ctx, "web_tools", true);
    }

    public static boolean researchInLoop(Context ctx) {
        Boolean o = sResearchInLoopOverride;
        return o != null ? o : getBool(ctx, "research_in_loop", true);
    }

    public static int askTimeoutSec(Context ctx) {
        int v = getInt(ctx, "ask_timeout_sec", DEF_ASK_TIMEOUT_SEC);
        return v < 10 ? 10 : (v > 300 ? 300 : v);
    }
}
