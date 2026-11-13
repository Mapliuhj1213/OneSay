package com.onesay.assistant.v2.companion;

import com.onesay.assistant.v2.s3.S3Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [companion] 路由优先级（纯 Java，JVM 可测）。
 *
 * <pre>
 *   句子属于伴侣领域（待办 / 日程 → 计划；闹钟 → 时钟）？
 *     否                         → NONE   （与本模块无关，原流程）
 *     伴侣没装 / 签名不同        → NONE   （保留原回落，含 AgentLoop）
 *     装了、同证书、连接没通     → CLAIM  （交给已有规则层，但永不进 AgentLoop、不探测第三方 App）
 *     连接通了（READY）：
 *       删 / 改待办              → REPLY  （经连接 listTasks + deleteTask / updateTask 执行，本地回话）
 *       加 / 列 / 勾 / 清空待办  → CLAIM  （S3 规则层执行；连着计划 App 时它的存储就是计划 App 的 provider）
 *                                  S3 待办开关关着 → 经连接 createTask / listTasks / completeTask 执行（REPLY）
 *       日程 / 其他待办句        → CLAIM
 *       闹钟                     → CLAIM  （FastIntents → AlarmBridge；AlarmBridge 连接通时走 createAlarm 等函数）
 * </pre>
 */
public final class CompanionRouter {
    private CompanionRouter() {}

    public enum Action { NONE, CLAIM, REPLY }

    /** 计划 App 的连接函数（真机 = {@link CompanionClient}；JVM = 假实现）。出错抛 RuntimeException。 */
    public interface PlanPort {
        List<Map<String, Object>> listTasks(boolean includeDone);
        int deleteTask(long id);
        int updateTask(long id, String title);
        int completeTask(long id);
        long createTask(String title, long dueAt);
    }

    /** 路由需要的环境。 */
    public interface Env {
        CompanionHandshake.State plan();
        CompanionHandshake.State clock();
        /** S3 待办开关开着（加 / 列 / 勾 / 清空由 S3 规则层做）。 */
        boolean s3Todo();
        PlanPort planPort();
    }

    public static final class Outcome {
        public final Action action;
        public final CompanionIntent.Parsed intent;
        public final String reply;
        /** 经连接调过的函数（日志 / 测试核对用）。 */
        public final List<String> calls;

        Outcome(Action action, CompanionIntent.Parsed intent, String reply, List<String> calls) {
            this.action = action;
            this.intent = intent;
            this.reply = reply;
            this.calls = calls;
        }

        /** 这句话不许进 AgentLoop。 */
        public boolean blocksAgentLoop() {
            return action != Action.NONE;
        }

        @Override public String toString() {
            return action + " " + intent + (reply != null ? " → " + reply : "") + (calls.isEmpty() ? "" : " via " + calls);
        }
    }

    static final double MATCH = 0.5;
    static final int MAX_LIST = 5;

    /** 只判定、不执行（claims / AgentLoop 闸门用，不碰连接）。 */
    public static Outcome classify(String q, Env env) {
        return decide(q, env, false);
    }

    /** 判定并在 READY 时经连接执行删 / 改等（route 用；后台线程）。 */
    public static Outcome handle(String q, Env env) {
        return decide(q, env, true);
    }

    static Outcome decide(String q, Env env, boolean execute) {
        List<String> calls = new ArrayList<String>();
        CompanionIntent.Parsed p = CompanionIntent.parse(q);
        if (p.domain == CompanionIntent.Domain.NONE) return new Outcome(Action.NONE, p, null, calls);
        CompanionHandshake.State st = p.domain == CompanionIntent.Domain.PLAN ? env.plan() : env.clock();
        if (st == null || !st.present()) return new Outcome(Action.NONE, p, null, calls);
        if (!st.ready() || p.domain == CompanionIntent.Domain.CLOCK) return new Outcome(Action.CLAIM, p, null, calls);
        switch (p.kind) {
            case TODO_DELETE:
            case TODO_UPDATE:
                break;
            case TODO_ADD:
            case TODO_LIST:
            case TODO_COMPLETE:
                if (env.s3Todo()) return new Outcome(Action.CLAIM, p, null, calls);
                break;
            default:
                return new Outcome(Action.CLAIM, p, null, calls);
        }
        if (!execute) return new Outcome(Action.REPLY, p, null, calls);
        String reply;
        try {
            reply = run(p, env.planPort(), calls);
        } catch (RuntimeException e) {
            reply = "计划 App 没响应，这次没改成（" + e.getMessage() + "）。";
        }
        return new Outcome(Action.REPLY, p, reply, calls);
    }

    static String run(CompanionIntent.Parsed p, PlanPort port, List<String> calls) {
        switch (p.kind) {
            case TODO_ADD: {
                calls.add(CompanionContract.F_CREATE_TASK);
                long id = port.createTask(p.target, 0);
                return id > 0 ? "记好了，待办「" + p.target + "」已加到计划里。" : "没加成，计划 App 没收下这条待办。";
            }
            case TODO_LIST: {
                calls.add(CompanionContract.F_LIST_TASKS);
                List<Map<String, Object>> l = port.listTasks(false);
                if (l.isEmpty()) return "计划里没有待办。";
                return "你有 " + l.size() + " 条待办：" + names(l, MAX_LIST) + "。";
            }
            default:
                break;
        }
        calls.add(CompanionContract.F_LIST_TASKS);
        List<Map<String, Object>> open = port.listTasks(false);
        String verb = p.kind == CompanionIntent.Kind.TODO_DELETE ? "删" : p.kind == CompanionIntent.Kind.TODO_UPDATE ? "改" : "勾掉";
        if (p.target.length() == 0) {
            if (open.isEmpty()) return "计划里没有待办。";
            return "要" + verb + "哪一条？" + names(open, MAX_LIST) + "。";
        }
        Map<String, Object> best = null;
        double bs = 0;
        int ties = 0;
        for (Map<String, Object> r : open) {
            double s = S3Parser.similarity(p.target, title(r));
            if (s > bs + 1e-9) {
                bs = s;
                best = r;
                ties = 1;
            } else if (Math.abs(s - bs) < 1e-9 && s >= MATCH) {
                ties++;
            }
        }
        if (best == null || bs < MATCH) return "计划里没找到「" + p.target + "」这条待办。";
        Map<String, Object> exact = null;
        int exactN = 0;
        for (Map<String, Object> r : open) {
            if (title(r).trim().equals(p.target)) {
                exact = r;
                exactN++;
            }
        }
        if (exactN == 1) best = exact;
        else if (ties > 1) return "有好几条像「" + p.target + "」的待办，要" + verb + "哪一条？" + names(open, MAX_LIST) + "。";
        long id = id(best);
        String t = title(best);
        switch (p.kind) {
            case TODO_DELETE:
                calls.add(CompanionContract.F_DELETE_TASK);
                return port.deleteTask(id) == 1 ? "已从计划里删掉待办「" + t + "」。" : "没删成，计划里这条待办可能已经不在了。";
            case TODO_UPDATE:
                calls.add(CompanionContract.F_UPDATE_TASK);
                return port.updateTask(id, p.newText) == 1 ? "改好了：「" + t + "」→「" + p.newText + "」。" : "没改成，计划里这条待办可能已经不在了。";
            case TODO_COMPLETE:
                calls.add(CompanionContract.F_COMPLETE_TASK);
                return port.completeTask(id) == 1 ? "勾掉了：「" + t + "」。" : "没勾成，计划里这条待办可能已经不在了。";
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ AgentLoop 闸门

    /** AgentLoop 里会去碰别的 App 的工具（探针 / 读 shell / 拉起 App / 读写界面）。 */
    static boolean touchesOtherApps(String tool) {
        if (tool == null) return false;
        return tool.startsWith("probe.") || tool.startsWith("app.") || tool.startsWith("ui.") || tool.startsWith("shell")
                || tool.startsWith("a11y.") || tool.startsWith("phone.") || tool.startsWith("intent.") || tool.startsWith("deeplink");
    }

    /**
     * AgentLoop 工具过闸前：伴侣在场时，替待办 / 日程 / 闹钟意图去探测第三方 App（或 root 读 shell）一律不执行。
     * @return 非 null = 拦下，返回值作为观测交给 AgentLoop
     */
    public static String agentPrecheck(String tool, String argsText, String targetPkg, Env env) {
        if (!touchesOtherApps(tool)) return null;
        if (targetPkg != null && (targetPkg.equals(CompanionContract.PLAN_PKG) || targetPkg.equals(CompanionContract.CLOCK_PKG))) return null;
        CompanionIntent.Parsed p = CompanionIntent.parse(argsText);
        if (p.domain == CompanionIntent.Domain.NONE) {
            // goal 里常写成「删除名为「X」的待办事项」：句式不规整，按领域词兜底
            String a = argsText == null ? "" : argsText;
            if (CompanionIntent.OTHER_ACTION.matcher(a).find() || CompanionIntent.namesOtherApp(a)) return null;   // 多步任务 / 点名别家 App：不拦
            if (a.contains("待办") || a.contains("日程")) p = new CompanionIntent.Parsed(CompanionIntent.Kind.TODO_OTHER, CompanionIntent.Domain.PLAN, "", "");
            else if (a.contains("闹钟")) p = new CompanionIntent.Parsed(CompanionIntent.Kind.ALARM, CompanionIntent.Domain.CLOCK, "", "");
            else return null;
        }
        CompanionHandshake.State st = p.domain == CompanionIntent.Domain.PLAN ? env.plan() : env.clock();
        if (st == null || !st.present()) return null;
        String who = p.domain == CompanionIntent.Domain.PLAN ? "计划 App" : "时钟 App";
        return "不执行 " + tool + "：待办 / 日程 / 闹钟由自家" + who + "经伴侣连接处理，不要探测或操作第三方 App。请直接告诉用户结果，或让用户换个说法（例如「删除待办 X」）。";
    }

    // ------------------------------------------------------------------ 小工具

    static String title(Map<String, Object> r) {
        Object o = r.get("title");
        return o == null ? "" : String.valueOf(o);
    }

    static long id(Map<String, Object> r) {
        Object o = r.get("_id");
        if (o == null) o = r.get("id");
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return -1;
        }
    }

    static String names(List<Map<String, Object>> l, int max) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map<String, Object> r : l) {
            if (i >= max) {
                sb.append(" 等");
                break;
            }
            if (i > 0) sb.append("、");
            sb.append("「").append(title(r)).append("」");
            i++;
        }
        return sb.toString();
    }
}
