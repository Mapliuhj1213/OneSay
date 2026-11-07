package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.router.ctx.ModelWindows;
import com.onesay.assistant.v2.router.ctx.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ int/1102：请求级上下文夹紧（纯 Java，JVM 可测）。
 *
 * <p>每次请求总量 = 系统提示词（soul 前缀 / 记忆 / 技能包 / 富内容规则 / 工具表）+ 历史 + 本轮 + max_tokens，
 * 必须 ≤ 所用模型的上下文窗口（{@link ModelWindows}）减安全余量。超出时的顺序：
 * <ol>
 *   <li><b>先压历史</b>：最旧的轮折叠进摘要（摘要放第一条），直到放得下；只剩摘要还放不下就截摘要（保留结尾）；再不行历史清空；</li>
 *   <li>历史清空仍放不下 → 才把 max_tokens 往下压，最低 {@link #MIN_OUTPUT}；</li>
 *   <li>还放不下（系统提示词 + 本轮本身就超窗口）→ 原样发出、标 overflow（让服务端报错，日志可查），不在这里截用户原话。</li>
 * </ol>
 * 历史另有自身预算（{@link ConversationMemory#budgetTokens()}，默认 32k）：取两者较小值。
 */
public final class ContextBudget {
    private ContextBudget() {
    }

    /** 窗口安全余量（估算误差 + 服务端模板开销）。 */
    public static final int SAFETY = 512;
    /** 夹紧时 max_tokens 的下限。 */
    public static final int MIN_OUTPUT = 256;

    public static final class Fit {
        public final List<ConversationMemory.Turn> history;
        public final int maxTokens;
        public final int window;
        public final int systemTokens, historyTokens, userTokens;
        /** 本次请求级折叠的轮数（不改 ConversationMemory 本身）。 */
        public final int folded;
        public final boolean outputClamped, overflow;

        Fit(List<ConversationMemory.Turn> h, int maxTokens, int window, int sys, int hist, int user, int folded, boolean oc, boolean of) {
            this.history = h;
            this.maxTokens = maxTokens;
            this.window = window;
            this.systemTokens = sys;
            this.historyTokens = hist;
            this.userTokens = user;
            this.folded = folded;
            this.outputClamped = oc;
            this.overflow = of;
        }

        public int total() {
            return systemTokens + historyTokens + userTokens + maxTokens;
        }

        public String log() {
            return "窗口 " + window + " = 系统 " + systemTokens + " + 历史 " + historyTokens + "（" + history.size() + " 条，请求级折叠 " + folded
                    + "）+ 本轮 " + userTokens + " + 输出 " + maxTokens + (outputClamped ? "（输出被压）" : "") + (overflow ? " ⚠超窗口" : "");
        }
    }

    /** 用模型名查窗口。 */
    public static Fit fit(String model, String system, List<ConversationMemory.Turn> history, String user, int maxTokens, int historyBudget) {
        return fit(ModelWindows.window(model), TokenEstimator.message(system), history, TokenEstimator.message(user), maxTokens, historyBudget);
    }

    public static Fit fit(int window, int systemTokens, List<ConversationMemory.Turn> history, int userTokens, int maxTokens, int historyBudget) {
        List<ConversationMemory.Turn> h = history == null ? new ArrayList<ConversationMemory.Turn>() : new ArrayList<ConversationMemory.Turn>(history);
        int avail = window - SAFETY - systemTokens - userTokens - maxTokens;
        int cap = Math.min(historyBudget > 0 ? historyBudget : Integer.MAX_VALUE, Math.max(0, avail));
        int folded = 0;
        int ht = tokens(h);
        if (ht > cap) {
            // 摘要（若有）在第一条；把它和最旧的轮依次并进一条新摘要
            String summary = "";
            boolean summaryTainted = false;
            if (!h.isEmpty() && ConversationMemory.SUMMARY_QUESTION.equals(h.get(0).question)) {
                summary = h.get(0).answer;
                summaryTainted = h.get(0).isTainted();
                h.remove(0);
            }
            while (!h.isEmpty() && tokensWith(summary, h) > cap) {
                ConversationMemory.Turn t = h.remove(0);
                summary = ConversationMemory.appendSummary(summary, ConversationMemory.foldLine(t), ConversationMemory.MAX_SUMMARY_TOKENS);
                if (t.isTainted()) summaryTainted = true;
                folded++;
            }
            if (summary.length() > 0) {
                int room = cap - TokenEstimator.message(ConversationMemory.SUMMARY_QUESTION) - TokenEstimator.PER_MESSAGE - tokens(h);
                if (room > 16) {
                    if (TokenEstimator.estimate(summary) > room) summary = summary.substring(summary.length() - TokenEstimator.suffixLenPublic(summary, room));
                    h.add(0, new ConversationMemory.Turn(ConversationMemory.SUMMARY_QUESTION, summary, summaryTainted));
                }
            }
            ht = tokens(h);
        }
        boolean oc = false, of = false;
        if (avail < 0) {
            // 历史已清空（cap=0）：再压输出
            int need = -avail;
            int mt = Math.max(MIN_OUTPUT, maxTokens - need);
            oc = mt < maxTokens;
            maxTokens = mt;
            if (systemTokens + userTokens + maxTokens + SAFETY > window) of = true;
        }
        return new Fit(h, maxTokens, window, systemTokens, ht, userTokens, folded, oc, of);
    }

    static int tokens(List<ConversationMemory.Turn> h) {
        int n = 0;
        for (ConversationMemory.Turn t : h) n += ConversationMemory.turnTokens(t);
        return n;
    }

    private static int tokensWith(String summary, List<ConversationMemory.Turn> h) {
        int s = summary.length() == 0 ? 0 : TokenEstimator.message(ConversationMemory.SUMMARY_QUESTION) + TokenEstimator.message(summary);
        return s + tokens(h);
    }
}
