package com.onesay.assistant.v2.s2;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.v2.s1.S1Settings;


/**
 * [s2] S2「记得住」三个功能的开关 + kill-switch（写法与 {@link S1Settings} 一致，裁决复用 {@link S1Settings#decide}）。
 *
 * <h3>设置项（SharedPreferences {@code bixby_s2}）</h3>
 * <table>
 *   <tr><th>键</th><th>功能</th><th>默认</th></tr>
 *   <tr><td>{@code memory}</td><td>#5「记一下」记忆库（写入 / 本地回忆 / 列表 / 删除 / AgentLoop 的 memory.search）</td><td>开</td></tr>
 *   <tr><td>{@code memory.implicit}</td><td>round3 #2：不带前缀的事实陈述也记（「我车停在B2-17」→ 记下了 + 撤销芯片；
 *       规则见 {@link ImplicitNote} 与）。{@code memory} 关时一并关</td><td>开</td></tr>
 *   <tr><td>{@code habit}</td><td>#12 习惯默认值（打车/导航/外卖/听歌的默认 App）</td><td>开</td></tr>
 *   <tr><td>{@code ledger}</td><td>#20 闪记记账（记一笔 / 本地汇总 / 删除）—— <b>设计稿，待用户确认</b>：代码保留、默认关，
 *       关闭时分诊/路由完全不认记账句式</td><td><b>关</b></td></tr>
 * </table>
 *
 * <h3>Kill-switch（文件存在即关，删除即恢复）</h3>
 * 整体 {@code /sdcard/Android/data/com.onesay.assistant/files/s2.off} 或 {@code /data/local/tmp/bixby_s2.off}；
 * 单项同目录 {@code memory.off} / {@code habit.off} / {@code ledger.off} / {@code memory.implicit.off}（只关免前缀记忆，「记一下…」照常）。
 * 关闭后对应句子回到 S2 之前的原流程（分诊 → L0/L1/LLM/AgentLoop），已存数据不删。
 */
public final class S2Settings {

    private S2Settings() {}

    public static final String PREF = "bixby_s2";
    public static final String KEY_MEMORY = "memory";
    public static final String KEY_HABIT = "habit";
    public static final String KEY_LEDGER = "ledger";
    /** round3 #2：免前缀记忆。 */
    public static final String KEY_IMPLICIT = "memory.implicit";

    public static final String KILL_ALL = "s2.off";
    public static final String KILL_ALL_TMP = com.onesay.assistant.v2.BuildVariant.KILL_TMP_ROOT + "/bixby_s2.off";
    public static final String KILL_MEMORY = "memory.off";
    public static final String KILL_HABIT = "habit.off";
    public static final String KILL_LEDGER = "ledger.off";
    public static final String KILL_IMPLICIT = "memory.implicit.off";

    /** 自测/调试覆盖（null = 按设置走）。注意：覆盖为 true 时三项全开（含默认关的记账），只用于自测。 */
    public static volatile Boolean sOverride;

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static boolean killFile(Context ctx, String name) {
        // 审计 R-2：kill 文件口径统一到 FeatureSwitch（外部 files 目录 或 /data/local/tmp/bixby_<名>）
        return com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, name);
    }

    public static boolean killAll(Context ctx) {
        // 审计 R-2：kill 文件口径统一到 FeatureSwitch（外部 files 目录 或 /data/local/tmp/bixby_<名>）
        return com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, KILL_ALL);
    }

    /** 默认值：记忆库、习惯默认值开；闪记记账<b>关</b>（2026-10-08 用户调整：只做设计稿，待确认后再开）。 */
    public static boolean defaultOf(String key) {
        return !KEY_LEDGER.equals(key);
    }

    private static boolean on(Context ctx, String key, String killName) {
        if (ctx == null) return false;
        try {
            return S1Settings.decide(sOverride, killAll(ctx), killFile(ctx, killName), sp(ctx).getBoolean(key, defaultOf(key)));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean memoryEnabled(Context ctx) {
        return on(ctx, KEY_MEMORY, KILL_MEMORY);
    }

    public static boolean habitEnabled(Context ctx) {
        return on(ctx, KEY_HABIT, KILL_HABIT);
    }

    public static boolean ledgerEnabled(Context ctx) {
        return on(ctx, KEY_LEDGER, KILL_LEDGER);
    }

    /** round3 #2：免前缀记忆（{@code memory} 关时也关）。 */
    public static boolean implicitEnabled(Context ctx) {
        return memoryEnabled(ctx) && on(ctx, KEY_IMPLICIT, KILL_IMPLICIT);
    }

    public static S2Engine.Flags flags(Context ctx) {
        boolean mem = memoryEnabled(ctx);
        return new S2Engine.Flags(mem, habitEnabled(ctx), ledgerEnabled(ctx), mem && on(ctx, KEY_IMPLICIT, KILL_IMPLICIT));
    }

    /** 设置页 / 调试用：设置项本身的值（不看 kill-switch）。 */
    public static boolean pref(Context ctx, String key) {
        return sp(ctx).getBoolean(key, defaultOf(key));
    }

    public static void set(Context ctx, String key, boolean on) {
        sp(ctx).edit().putBoolean(key, on).apply();
    }

    public static String dump(Context ctx) {
        return "memory=" + memoryEnabled(ctx) + " implicit=" + implicitEnabled(ctx) + " habit=" + habitEnabled(ctx) + " ledger=" + ledgerEnabled(ctx)
                + " killAll=" + killAll(ctx);
    }
}
