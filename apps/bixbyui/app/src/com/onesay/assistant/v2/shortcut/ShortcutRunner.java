package com.onesay.assistant.v2.shortcut;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.executor.ConsentPolicy;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.ToolGate;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * [shortcut] 逐步执行一条快捷指令（纯 Java，宿主能力全部经 {@link Ports} 注入，JVM 全测）。
 *
 * <h3>每一步</h3>
 * <ol>
 *   <li>查能力（工具注册表 / 插件 / 内部动作）；能力探测说不能用（置灰原因）→ 本步失败（交给正常模式兜底）；</li>
 *   <li>留空的参数现场问（{@link Ports#ask}，没回答 = 取消）；{@code {{prev}}} 换成上一步输出；</li>
 *   <li><b>{@link ExecutionGate#evaluate} 过闸</b>（和 AgentLoop 同一把尺）：DENY 停；REQUIRE_CONFIRM 弹确认卡（{@link Ports#confirm}，
 *       阻塞到用户裁决），用户允许才 {@link ExecutionGate#approve} 并凭令牌执行；ALLOW 但属于发消息 / 花钱 / 删除类
 *       （{@link ShortcutSafety#alwaysConfirm}）照样弹卡，用户允许才执行 —— 快捷指令绕不过确认卡；</li>
 *   <li>执行（{@link Ports#execute}）；失败就停，交给 {@link ShortcutFlow} 转正常模式。</li>
 * </ol>
 * 开头只说一句 {@link #ANNOUNCE}，之后逐步执行不再额外提示。成败不由这里判定对不对：执行完只报「已执行」。
 *
 * <p>本类在 {@link ExecutionGate#APPROVE_CALLERS} 里：和 AgentLoop 一样，只在宿主的确认卡返回「允许」之后调用 approve。
 */
public final class ShortcutRunner {

    private ShortcutRunner() {}

    public static final String ANNOUNCE = "正在执行快捷指令";
    public static final String ALWAYS_CONFIRM_REASON = "快捷指令里的这一步每次都要你确认";

    /** 宿主能力（真机 = ShortcutHooks 适配 AgentLoop 的回调；单测 = 假实现）。 */
    public interface Ports {
        /** 签名 → 能力；没有返回 null。 */
        Capability lookup(String sign);

        /** 能力探测 / 构建开关：这一步为什么不能用，能用返回 null。 */
        String unavailableReason(String sign, Capability cap);

        /** 现场问一个参数；用户没回答 / 取消返回 null。 */
        String ask(String question, String param);

        /** 弹确认卡并阻塞到裁决；true = 用户允许。 */
        boolean confirm(Capability cap, JSONObject args, String reason, int tier, boolean tainted);

        ExecutionResult execute(Capability cap, JSONObject args, ExecutionGate.ConfirmToken token, boolean tainted);

        /** 说一句（只在开头用一次）。 */
        void announce(String text);

        boolean cancelled();
    }

    public enum Status {
        /** 全部步骤执行完。 */
        OK,
        /** 某步失败（含能力不可用）：可转正常模式兜底。 */
        FAILED,
        /** 用户取消（没回答问题 / 停止键）。 */
        CANCELLED,
        /** 用户在确认卡上没允许。 */
        REJECTED,
        /** 安全策略拒绝（锁屏、号码不合法等）。 */
        DENIED
    }

    public static final class StepRun {
        public final ShortcutModel.Step planned;
        public JSONObject args;
        public boolean ok;
        public String output = "";
        public String error = "";
        public ErrorCode code;
        public boolean confirmed;

        StepRun(ShortcutModel.Step planned) {
            this.planned = planned;
        }

        public String tool() {
            return planned.tool;
        }
    }

    public static final class Result {
        public Status status = Status.OK;
        /** 停在第几步（0 起）；OK 时 -1。 */
        public int failedIndex = -1;
        public String reason = "";
        public final List<StepRun> runs = new ArrayList<StepRun>();

        public boolean ok() {
            return status == Status.OK;
        }

        /** 停下前已经做成的步骤（保持原定义：留空参数仍是「每次问」）。 */
        public List<ShortcutModel.Step> doneSteps() {
            List<ShortcutModel.Step> l = new ArrayList<ShortcutModel.Step>();
            for (StepRun r : runs) if (r.ok) l.add(r.planned);
            return l;
        }

        /** 执行记录用的摘要：只有工具名和每步成败，不含参数。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (StepRun r : runs) sb.append(sb.length() > 0 ? " → " : "").append(r.tool()).append(r.ok ? " ✓" : " ✗");
            return sb.toString();
        }
    }

    public static Result run(ShortcutModel.Shortcut sc, Ports p) {
        Result res = new Result();
        if (sc == null || sc.steps.isEmpty()) {
            res.status = Status.FAILED;
            res.failedIndex = 0;
            res.reason = "快捷指令没有步骤";
            return res;
        }
        p.announce(ANNOUNCE);
        String prev = "";
        boolean prevTaint = false;
        for (int i = 0; i < sc.steps.size(); i++) {
            ShortcutModel.Step st = sc.steps.get(i);
            StepRun sr = new StepRun(st);
            res.runs.add(sr);
            if (p.cancelled()) return stop(res, i, Status.CANCELLED, "已取消");
            Capability cap = p.lookup(st.tool);
            String why = p.unavailableReason(st.tool, cap);
            if (why != null) {
                sr.error = why;
                return stop(res, i, Status.FAILED, why);
            }
            // 1. 参数：留空的现场问；{{prev}} 换上一步输出
            JSONObject args;
            try {
                args = new JSONObject(st.args == null ? "{}" : st.args.toString());
            } catch (JSONException e) {
                return stop(res, i, Status.FAILED, "参数损坏");
            }
            for (String k : st.askParams()) {
                Object v = askValue(p, st.tool, k, cap);
                if (v == null) return stop(res, i, Status.CANCELLED, "没有回答「" + k + "」，已取消");
                put(args, k, v);
            }
            boolean usedPrev = substitutePrev(args, prev);
            boolean tainted = usedPrev && prevTaint;
            sr.args = args;
            // 2. 过闸（唯一门禁入口），需要确认就弹卡
            ExecutionGate.Decision gate = ExecutionGate.evaluate(cap, args, tainted);
            ExecutionGate.ConfirmToken token = null;
            if (gate.denied()) {
                sr.error = gate.reason;
                return stop(res, i, Status.DENIED, gate.reason);
            }
            if (gate.needsConfirm()) {
                boolean allow = p.confirm(cap, args, gate.reason, gate.tier, tainted);
                if (!allow || !ExecutionGate.approve(gate.token)) {
                    ExecutionGate.reject(gate.token);
                    return stop(res, i, Status.REJECTED, "没有确认「" + label(cap, st) + "」");
                }
                token = gate.token;
                sr.confirmed = true;
            } else if (ShortcutSafety.alwaysConfirm(cap)) {
                // 门禁放行了（例如「始终允许」），但发消息 / 花钱 / 删除类在快捷指令里每次都要卡片确认
                boolean allow = p.confirm(cap, args, ALWAYS_CONFIRM_REASON, ConsentPolicy.tierOf(cap, tainted), tainted);
                if (!allow) return stop(res, i, Status.REJECTED, "没有确认「" + label(cap, st) + "」");
                sr.confirmed = true;
            }
            // 3. 执行
            ExecutionResult r = p.execute(cap, args, token, tainted);
            if (r == null || !r.ok) {
                sr.code = r == null ? ErrorCode.EXEC_FAILED : r.code;
                sr.error = r == null ? "执行无返回" : (r.userMessage == null ? String.valueOf(r.code) : r.userMessage);
                if (sr.code == ErrorCode.USER_REJECTED || sr.code == ErrorCode.RISK_REJECTED) return stop(res, i, Status.REJECTED, sr.error);
                if (sr.code == ErrorCode.CANCELLED) return stop(res, i, Status.CANCELLED, sr.error);
                if (sr.code == ErrorCode.GATE_DENIED) return stop(res, i, Status.DENIED, sr.error);
                return stop(res, i, Status.FAILED, sr.error);
            }
            sr.ok = true;
            sr.output = r.userMessage == null ? "" : r.userMessage;
            prev = sr.output;
            prevTaint = ShortcutSafety.taintSource(cap) || tainted;
        }
        res.status = Status.OK;
        return res;
    }

    static Result stop(Result res, int i, Status s, String why) {
        res.status = s;
        res.failedIndex = i;
        res.reason = why == null ? "" : why;
        return res;
    }

    static String label(Capability cap, ShortcutModel.Step st) {
        if (st.title != null && st.title.length() > 0) return st.title;
        ShortcutActions.Action a = ShortcutActions.get(st.tool);
        if (a != null) return a.label;
        return cap != null && cap.summary != null ? cap.summary : st.tool;
    }

    /** 问一个参数：整数参数答不成数字再问一次，还不行算没回答。 */
    static Object askValue(Ports p, String sign, String param, Capability cap) {
        boolean integer = isInt(sign, param, cap);
        String q = ShortcutActions.question(sign, param, cap);
        for (int attempt = 0; attempt < 2; attempt++) {
            String a = p.ask(attempt == 0 ? q : q + "（请说一个数字）", param);
            if (a == null) return null;
            a = a.trim();
            if (!integer) return a;
            Long n = toLong(a);
            if (n != null) return n;
        }
        return null;
    }

    static boolean isInt(String sign, String param, Capability cap) {
        if (cap != null && cap.paramsSchema != null) {
            ParamsSchema.PropertyRule r = cap.paramsSchema.getRules().get(param);
            if (r != null) return r.type == ParamsSchema.ParamType.INTEGER;
        }
        ShortcutActions.Action a = ShortcutActions.get(sign);
        ShortcutActions.Param pp = a == null ? null : a.param(param);
        return pp != null && "int".equals(pp.type);
    }

    static Long toLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return com.onesay.assistant.v2.router.NumberParser.parse(s);   // 「七」「十五」这类口语数字
        }
    }

    static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }

    /** 把字符串参数里的 {{prev}} 换成上一步输出；用到了返回 true。 */
    static boolean substitutePrev(JSONObject args, String prev) {
        boolean used = false;
        List<String> keys = new ArrayList<String>();
        Iterator<?> it = args.keys();
        while (it.hasNext()) keys.add(String.valueOf(it.next()));
        for (String k : keys) {
            Object v = args.opt(k);
            if (v instanceof String && ((String) v).contains(ShortcutModel.PREV)) {
                put(args, k, ((String) v).replace(ShortcutModel.PREV, prev == null ? "" : prev));
                used = true;
            }
        }
        return used;
    }
}
