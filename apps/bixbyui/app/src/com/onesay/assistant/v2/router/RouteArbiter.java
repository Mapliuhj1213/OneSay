package com.onesay.assistant.v2.router;

import android.content.Context;

import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.quicknote.QuickNoteIntent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ★ fix/1123-bug2：分诊统一出口 —— 一句话只按一张优先级表走一次，谁排前面谁答，答了就不再有第二个出口。
 *
 * <h3>以前的毛病（11-03 / 11-04 审计「4 套抢答」）</h3>
 * 同一句话要过四套各自为政的判断：
 * <ol>
 *   <li>插件本地接管（kernel TAKEOVER：存图 / 摘要 / 日程 / 提醒待办 / 记一下 / 诊断 / 记一记 …按插件优先级挨个问）；</li>
 *   <li>零 LLM 快路径（{@link FastIntents} 整句模板 / 改值追问 / 多意图拆分）；</li>
 *   <li>「进不进 AgentLoop」（{@code UnifiedRouter.shouldUseAgentLoop}，自己又排了一遍顺序）；</li>
 *   <li>分诊决策表 + LLM 分类（{@link TriageDecider}）。</li>
 * </ol>
 * 级联里插件接管排在快路径前面，于是「每天 6 点 50 叫我」快路径明明判成闹钟，提醒插件（300）先接走建成了通知栏提醒；
 * 「随口记 明天下午三点开会」点名了随口记，日程插件（290）先接走建了日程；而 shouldUseAgentLoop 按另一套顺序判，
 * 两个入口对同一句话的结论还可能不一样。
 *
 * <h3>现在：一张表（{@link #ORDER}）</h3>
 * <pre>
 *   ① 自测库 / 纠错语 / 补槽应答（上一轮问过的，这一轮是答案）
 *   ② 状态 / 设备插件（TAKEOVER 优先级 &lt; {@link #CONTENT_TIER}：装应用卡在屏、存图待选、摘要、雨天、电脑联动、计划 App）
 *   ③ 用户点名的插件（「随口记 …」「记一记 …」→ 记一记）
 *   ④ 零 LLM 快路径整句命中（{@link FastIntents}：闹钟 / 计时 / 开关 / 音量 / 发短信 …）
 *   ⑤ 内容插件（优先级 ≥ {@link #CONTENT_TIER}：日程 290 / 提醒待办 300 / 记一下 400 / 诊断 500 …）
 *   ⑥ 点名的 App 没装 → 安装卡
 *   ⑦ 分诊决策表（→ AgentLoop 或 问答 / 单步）
 * </pre>
 * 级联（{@code UnifiedRouter.cascade}）和「进不进 AgentLoop」都按这张表；级联的出口用 {@link #once} 包一层，
 * 同一句话只交付一次结果，后来的重复交付丢掉并记日志。
 *
 * <p>否定 / 反悔句（{@link NegationGuard}）照旧：插件和快路径都不接，直接落分诊。纯 Java，JVM 可测。
 */
public final class RouteArbiter {

    private RouteArbiter() {}

    /** 出口（按优先级从高到低）。 */
    public enum Exit {
        SELF_TEST_OR_SLOT("自测 / 补槽"),
        STATE_PLUGIN("状态插件"),
        EXPLICIT_PLUGIN("点名插件"),
        FAST_PATH("快路径"),
        CONTENT_PLUGIN("内容插件"),
        MISSING_APP("安装卡"),
        TRIAGE("分诊");

        public final String zh;

        Exit(String zh) {
            this.zh = zh;
        }
    }

    /** 唯一的优先级表（文档 / 日志 / 测试都看它）。 */
    public static final List<Exit> ORDER = Collections.unmodifiableList(Arrays.asList(Exit.values()));

    /**
     * 插件分档线：TAKEOVER 优先级小于它的是状态 / 设备插件（排在快路径前），大于等于它的是内容插件（排在快路径后）。
     * 290 = 日程插件（SchedulePlugin）—— 它和提醒待办（300）、记一下（400）、诊断（500）这些按句子内容抢活的插件都在后面。
     */
    public static final int CONTENT_TIER = 290;

    /** 记一记的插件 id。 */
    public static final String QUICKNOTE = "quicknote";

    /**
     * 用户在句子里点名了哪个插件（null = 没点名）。只认明确的口令词，不猜：
     * 「随口记 …」「帮我记一下 …」「记一记 …」（打开 / 列表 / 删掉 / 刚才那条）→ 记一记。
     * 「记一下 X」不算点名（照旧是 S2 的活）。
     */
    public static String explicitOwner(String query) {
        if (query == null || query.trim().length() == 0) return null;
        if (NegationGuard.blocks(query)) return null;
        QuickNoteIntent.Kind k;
        try {
            k = QuickNoteIntent.of(query);
        } catch (Throwable t) {
            return null;
        }
        switch (k) {
            case SUIKOUJI:
            case RUN:
            case OPEN:
            case LIST:
            case DELETE_LAST:
            case RECALL:
                return QUICKNOTE;
            default:
                return null;
        }
    }

    /**
     * 快路径整句命中（④ 档）：只看 {@link FastIntents} 整句模板（确定性、零 LLM、无副作用）。
     * 改值追问 / 多意图拆分不在这里抢内容插件的活（它们照旧在分诊第 0 层）。
     */
    public static Invocation fastHit(String query) {
        if (query == null || query.trim().length() == 0) return null;
        if (TriageDecider.tooLongForRules(query)) return null;
        if (NegationGuard.blocks(query)) return null;
        try {
            return FastIntents.route(query);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 插件本地接管的结论：hit 可能为 null（= 插件都不接，往下走 ⑥ ⑦），exit 是这次停在哪一档。 */
    public static final class Take {
        public final FeatureKernel.Hit hit;
        public final Exit exit;

        Take(FeatureKernel.Hit hit, Exit exit) {
            this.hit = hit;
            this.exit = exit;
        }

        @Override public String toString() {
            return exit.name() + (hit != null ? " plugin=" + hit.plugin.id() + " sign=" + hit.inv.sign : "");
        }
    }

    /**
     * ②–⑤：按表问插件。快路径命中（④）时内容插件（⑤）一个都不问，返回 hit=null、exit=FAST_PATH，调用方照旧走分诊第 0 层拿快路径结果。
     * 否定句：插件都不问（与修前一致）。
     */
    public static Take takeover(FeatureKernel k, Context ctx, String query, L2LLMCompiler l2) {
        if (k == null || query == null || NegationGuard.blocks(query)) return new Take(null, Exit.TRIAGE);
        FeatureKernel.Hit h = k.takeoverRange(ctx, query, l2, Integer.MIN_VALUE, CONTENT_TIER, null);
        if (h != null) return new Take(h, Exit.STATE_PLUGIN);
        String owner = explicitOwner(query);
        if (owner != null) {
            h = k.takeoverOnly(ctx, query, l2, owner);
            if (h != null) {
                com.onesay.assistant.v2.util.SafeLog.i("RouteArbiter", "[ARBITER] 用户点名插件 " + owner + " 先答");
                return new Take(h, Exit.EXPLICIT_PLUGIN);
            }
        }
        if (fastHit(query) != null) {
            com.onesay.assistant.v2.util.SafeLog.i("RouteArbiter", "[ARBITER] 快路径整句命中，内容插件让路");
            return new Take(null, Exit.FAST_PATH);
        }
        h = k.takeoverRange(ctx, query, l2, CONTENT_TIER, Integer.MAX_VALUE, owner);
        if (h != null) return new Take(h, Exit.CONTENT_PLUGIN);
        return new Take(null, Exit.TRIAGE);
    }

    /**
     * 「进不进 AgentLoop」里与表对齐的一步：点名插件认领 → 不进（以前诊断插件的 wantsAgentLoop 排在前面，
     * 「随口记 WiFi 老断」会被拉进循环，而级联里是记一记答的）。
     */
    public static boolean explicitClaims(FeatureKernel k, Context ctx, String query) {
        String owner = explicitOwner(query);
        return owner != null && k != null && k.claimsOnly(ctx, query, owner);
    }

    // ------------------------------------------------------------------ 只答一次

    /** 交付了几次（测试 / 日志用）。 */
    public interface Counted {
        int finals();
    }

    /**
     * 级联出口只交付一次：第一次 onFinal 原样转交，之后的 onFinal / onPartial / onStage 都丢掉（记日志）。
     * 级联里每一档 onFinal 后都紧跟 return，这层是防以后有人漏写 return 的保险。
     */
    public static UnifiedRouter.StreamHandler once(final UnifiedRouter.StreamHandler h, final String query) {
        if (h == null || h instanceof Once) return h;
        return new Once(h, query);
    }

    static final class Once implements UnifiedRouter.StreamHandler, Counted {
        private final UnifiedRouter.StreamHandler mH;
        private final String mQuery;
        private final java.util.concurrent.atomic.AtomicInteger mFinals = new java.util.concurrent.atomic.AtomicInteger();

        Once(UnifiedRouter.StreamHandler h, String query) {
            mH = h;
            mQuery = query;
        }

        @Override public void onStage(int stage) {
            if (mFinals.get() == 0) mH.onStage(stage);
        }

        @Override public void onPartial(String textSoFar) {
            if (mFinals.get() == 0) mH.onPartial(textSoFar);
        }

        @Override public void onFinal(Invocation inv) {
            int n = mFinals.incrementAndGet();
            if (n > 1) {
                com.onesay.assistant.v2.util.SafeLog.w("RouteArbiter", "[ARBITER] 同一句第 " + n + " 次交付，丢掉（只答一次）: "
                        + (inv == null ? "null" : inv.sign) + " 语句=" + com.onesay.assistant.v2.util.LogText.u(mQuery));
                return;
            }
            mH.onFinal(inv);
        }

        @Override public int finals() {
            return mFinals.get();
        }
    }
}
