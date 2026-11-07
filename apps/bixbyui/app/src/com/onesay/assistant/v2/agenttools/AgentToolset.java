package com.onesay.assistant.v2.agenttools;

import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ arch/tool-tiers（A 路）：一次 AgentLoop 任务的「工具层」状态 —— 候选怎么排、GUI 档折没折。
 *
 * <p>AgentLoop 跑在单个后台线程上，插件的 {@code appendAgentTools / executeForAgent} 也在同一线程同步调用，
 * 所以会话放在 {@link ThreadLocal} 里（不同线程上的两个任务互不影响）。
 *
 * <p>AgentLoop 的接入点（只有三处，见 ARCH-A.md）：
 * <ol>
 *   <li>{@link #candidates}：开局候选（按档位 + 相关度排序再截断）并开一个新会话；</li>
 *   <li>{@link #afterStep}：每步执行后报告结果；GUI 档从折叠变展开时返回 true，AgentLoop 据此重新导出工具表；</li>
 *   <li>提示词：{@code L2LLMCompiler.buildAgentLoopPrompt(ctx, tools)} 按当轮实际挂载的工具拼段。</li>
 * </ol>
 * 分诊结论由 {@code UnifiedRouter.runAgentLoop} 在同一线程上用 {@link #prepare} 递过来（没有就按办事处理）。
 */
public final class AgentToolset {

    private static final String TAG = "AgentToolset";

    private AgentToolset() {}

    /** 一次任务的会话。 */
    public static final class Session {
        public final String query;
        public final String triageLabel;
        /** 全量打分（签名 → 分数），用于判断「前三档有没有强相关」。 */
        final Map<String, Integer> scores = new HashMap<String, Integer>();
        private GuiFold.Reason fold;    // null = 还没判
        private int failScore;
        /** 截断后的候选里有没有强相关的前三档工具。 */
        boolean upperStrong;

        Session(String query, String triageLabel) {
            this.query = query == null ? "" : query;
            this.triageLabel = triageLabel;
        }

        /** 当前 GUI 档状态（第一次问时按开局规则判）。 */
        public synchronized GuiFold.Reason fold() {
            if (fold == null) {
                fold = GuiFold.initial(query, triageLabel, upperStrong);
                Log.i(TAG, "[TIERS] GUI 档开局：" + fold + (fold.expanded ? "（展开 phone.operate）" : "（折叠，只给入口 gui.unfold）"));
            }
            return fold;
        }

        public boolean guiExpanded() {
            return fold().expanded;
        }

        /** @return 本步让 GUI 档从折叠变成展开 */
        synchronized boolean report(String sign, ToolTier tier, boolean ok, boolean gated) {
            GuiFold.Reason before = fold();
            if (before.expanded) return false;
            if (ToolTiers.SIGN_GUI_ENTRY.equals(sign)) {
                fold = GuiFold.Reason.ENTRY_CALLED;
            } else if (!ok && !gated && sign != null && !sign.startsWith("probe.")) {
                failScore += GuiFold.failWeight(tier);
                if (failScore >= GuiFold.FAIL_SCORE_TO_UNFOLD) fold = GuiFold.Reason.UPPER_TIERS_FAILED;
            }
            if (fold.expanded) {
                Log.i(TAG, "[TIERS] GUI 档运行中展开：" + fold + " (step=" + sign + ")");
                return true;
            }
            return false;
        }

        public int failScore() {
            return failScore;
        }
    }

    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<Session>();
    private static final ThreadLocal<String[]> PENDING = new ThreadLocal<String[]>();

    /** UnifiedRouter 在调 AgentLoop.run 前（同一线程）递分诊结论。 */
    public static void prepare(String query, String triageLabel) {
        PENDING.set(new String[]{query, triageLabel});
    }

    /** 开新会话（消费同一句话的分诊结论）。 */
    public static Session begin(String query) {
        String label = null;
        String[] p = PENDING.get();
        PENDING.remove();
        if (p != null && p[0] != null && p[0].equals(query)) label = p[1];
        Session s = new Session(query, label);
        CURRENT.set(s);
        return s;
    }

    /** 当前线程上的会话；没有（插件被别处调用）返回 null。 */
    public static Session current() {
        return CURRENT.get();
    }

    /** 当前会话，且必须是同一句话的；否则 null。 */
    public static Session currentFor(String query) {
        Session s = CURRENT.get();
        if (s == null) return null;
        if (query != null && !query.equals(s.query)) return null;
        return s;
    }

    public static void end() {
        CURRENT.remove();
    }

    /**
     * 开局候选：开会话 + 全量打分 + 按档位/相关度排序截断。
     *
     * @param scored 全量（能力, 分数）——由 {@code CapabilityRetriever.scoreAll} 给
     */
    public static List<Capability> candidates(String query, List<ToolTiers.Scored> scored, int k) {
        Session s = begin(query);
        for (ToolTiers.Scored x : scored) {
            if (x.cap != null) s.scores.put(x.cap.sign, x.score);
        }
        List<ToolTiers.Scored> top = ToolTiers.rank(scored, k, 3);
        for (ToolTiers.Scored x : top) {
            if (x.tier.isUpper() && ToolTiers.band(x.score) >= 3) { s.upperStrong = true; break; }
        }
        return ToolTiers.caps(top);
    }

    /**
     * 每步执行后报告。返回 true = GUI 档刚展开，调用方要重新导出工具表（插件会把 phone.operate 放进去）。
     */
    public static boolean afterStep(String name, Capability cap, boolean ok, boolean gated) {
        Session s = CURRENT.get();
        if (s == null) return false;
        String sign = cap != null && cap.sign != null ? cap.sign : CapabilityRegistry.toolNameToSign(name == null ? "" : name);
        if (cap == null && !ToolTiers.SIGN_GUI_ENTRY.equals(sign)) return false;   // 未注册工具不算「前三档试过」
        ToolTier tier = cap != null ? ToolTiers.of(cap) : ToolTier.GUI;
        return s.report(sign, tier, ok, gated);
    }

    /** 元工具（入口类，不能作为 L1 技能回放）。 */
    public static boolean isMetaTool(String sign) {
        return ToolTiers.SIGN_GUI_ENTRY.equals(sign) || ToolTiers.SIGN_GUI_ENTRY.replace(".", "__").equals(sign);
    }

    /** 回给模型的一句话：点界面工具已加入（只在重新导出后的 tools 里真有 phone.operate 时才说）。 */
    public static String withGuiUnfolded(String observation, org.json.JSONArray tools) {
        if (!ToolTiers.mountedSigns(tools).contains(ToolTiers.SIGN_GUI_OPERATE)) return observation;
        try {
            org.json.JSONObject o = new org.json.JSONObject(observation == null ? "{}" : observation);
            o.put("gui_tool_added", CapabilityRegistry.signToToolName(ToolTiers.SIGN_GUI_OPERATE));
            o.put("note_gui", "点界面工具 phone.operate 已加入你的工具列表。仍然先确认接口、查信息、深链都不适用，再用它；一次只交给它一个 App 和一个明确目标。");
            return o.toString();
        } catch (Throwable t) {
            return observation;
        }
    }
}
