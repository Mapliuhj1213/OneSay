package com.onesay.assistant.v2.router;

import org.json.JSONObject;

import java.util.List;

/**
 * 路由输出契约（所有路由器的唯一标准输出）。
 *
 * 铁律：
 * 绝不允许输出裸 shell 命令！只能输出结构化能力签名与参数对象。
 *
 * 2026-10-02 扩展：新增 {@link #steps} 以支持**混合任务（MIXED）**。
 * 一条输入里挂多件事时（"把亮度调到最低，顺便告诉我现在几点"），
 * L2 会返回一个多步计划，由 UI 层顺序执行并按步汇报。
 * 单步调用保持 steps == null，行为与旧版完全一致（零回归）。
 */
public class Invocation {

    public enum RouteSource {
        L0_RULE,
        L1_SKILL,
        L2_LLM
    }

    /** 多步计划的虚拟签名。它不是注册表里的能力，UI 层必须按 steps 展开执行。 */
    public static final String SIGN_PLAN = "plan.multi";

    public final String sign;
    public final JSONObject args;
    public final double confidence;
    public final RouteSource source;
    public final String originalQuery;
    
    /** 标记此调用是否由不可信内容（如网页搜索结果）污染。若是，高危动作直接拒绝，普通带副作用动作强制确认。 */
    public final boolean tainted;

    /**
     * 多步计划的有序步骤列表。为 null 表示这是普通单步调用。
     * 每个子步骤只允许是 ask.reply（纯回答）或注册表中的真实能力。
     */
    public final List<Invocation> steps;

    public Invocation(String sign, JSONObject args, double confidence, RouteSource source, String originalQuery) {
        this(sign, args, confidence, source, originalQuery, null, false);
    }

    public Invocation(String sign, JSONObject args, double confidence, RouteSource source,
                      String originalQuery, List<Invocation> steps) {
        this(sign, args, confidence, source, originalQuery, steps, false);
    }

    public Invocation(String sign, JSONObject args, double confidence, RouteSource source,
                      String originalQuery, List<Invocation> steps, boolean tainted) {
        this.sign = sign;
        this.args = args != null ? args : new JSONObject();
        this.confidence = confidence;
        this.source = source;
        this.originalQuery = originalQuery;
        this.steps = steps;
        this.tainted = tainted;
    }

    /** 构造一个混合任务计划 */
    public static Invocation plan(List<Invocation> steps, RouteSource source, String originalQuery) {
        return new Invocation(SIGN_PLAN, new JSONObject(), 0.85, source, originalQuery, steps, false);
    }
    
    /** 构造一个混合任务计划（带污染标记） */
    public static Invocation plan(List<Invocation> steps, RouteSource source, String originalQuery, boolean tainted) {
        return new Invocation(SIGN_PLAN, new JSONObject(), 0.85, source, originalQuery, steps, tainted);
    }

    /** 是否为多步计划 */
    public boolean isPlan() {
        return steps != null && steps.size() > 0;
    }

    @Override
    public String toString() {
        if (isPlan()) {
            StringBuilder sb = new StringBuilder("Invocation{PLAN[")
                    .append(steps.size()).append(" steps], src=").append(source).append(", tainted=").append(tainted).append(", steps=[");
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(steps.get(i).sign).append('=').append(steps.get(i).args);
            }
            return sb.append("]}").toString();
        }
        return "Invocation{sign='" + sign + "', args=" + args + ", src=" + source + ", tainted=" + tainted + "}";
    }
}
